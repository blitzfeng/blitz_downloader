package com.blitz.downloader.viewmodel

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.data.DownloadMediaType
import com.blitz.downloader.data.db.DownloadedVideoDao.AuthorCount
import com.blitz.downloader.data.db.DownloadedVideoEntity
import com.blitz.downloader.download.BatchDownloadCoordinator
import com.blitz.downloader.download.MediaExportManager
import com.blitz.downloader.model.MediaOrientation
import com.blitz.downloader.model.filter.ManageFilterState
import com.blitz.downloader.model.filter.ManageRelationFilter
import com.blitz.downloader.model.filter.ManageSortOrder
import com.blitz.downloader.model.filter.ManageTagCountFilter
import com.blitz.downloader.model.filter.ManageTagEditCountFilter
import com.blitz.downloader.model.filter.TagQuery
import com.blitz.downloader.net.LanFileServer
import com.blitz.downloader.util.MediaOrientationProbe
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 管理页 ViewModel（作用域是 [com.blitz.downloader.fragment.ManageFragment]）：
 * **筛选条件**与**多选状态**的唯一权威，外加统计面板、ZIP 导出与局域网导出。
 *
 * 它取代了原先管理页用
 * `supportFragmentManager.findFragmentByTag("f$position") as? ManageTabFragment`
 * 往下喊话的那套耦合——那依赖 ViewPager2 的内部 tag 命名约定，不受 API 保证。
 * 现在管理页外壳与两个 Tab 只通过本 ViewModel 通信：
 *
 * - 条件下行：外壳改 [filters] / [selection] → Tab 观察到自己那一份并应用；
 * - 动作下行：外壳发 [commands] → 目标 Tab 消费后在自己的 ViewModel 上执行；
 * - 数据上行：Tab 每次列表变化调用 [setLoaded]，外壳侧据此算「是否已全选」
 *   与「选中了哪些实体」（导出要用）。
 *
 * 筛选与多选都**按 Tab 独立维护**：视频页搜「张三」不该影响图片页。
 */
class ManageViewModel(app: Application) : AndroidViewModel(app) {

    private val repo get() = (getApplication<Application>() as BlitzApp).downloadedVideoRepository
    private val tagRepo get() = (getApplication<Application>() as BlitzApp).videoTagRepository

    // -----------------------------------------------------------------------
    // 筛选条件（按 Tab 独立）
    // -----------------------------------------------------------------------

    private val _filters = MutableStateFlow(
        mapOf(TAB_VIDEO to ManageFilterState(), TAB_IMAGE to ManageFilterState()),
    )
    val filters: StateFlow<Map<Int, ManageFilterState>> = _filters.asStateFlow()

    fun filtersOf(tab: Int): ManageFilterState = _filters.value[tab] ?: ManageFilterState()

    private fun updateFilters(tab: Int, transform: (ManageFilterState) -> ManageFilterState) {
        val current = filtersOf(tab)
        val next = transform(current)
        if (next == current) return
        _filters.value = _filters.value + (tab to next)
    }

    fun applySearchQuery(tab: Int, query: String?) =
        updateFilters(tab) { it.withSearchQuery(query.orEmpty()) }

    fun applyAuthorFilter(tab: Int, secUserId: String?, userName: String?) =
        updateFilters(tab) { it.withAuthor(secUserId, userName) }

    fun applyTags(tab: Int, tags: Set<String>) = updateFilters(tab) { it.withTags(tags) }

    /** 标签精细检索与标签多选互斥，互斥清理在 [ManageFilterState.withTagQuery] 里。 */
    fun applyTagQuery(tab: Int, query: TagQuery) = updateFilters(tab) { it.withTagQuery(query) }

    fun applySort(tab: Int, sort: ManageSortOrder) = updateFilters(tab) { it.copy(sort = sort) }

    /** 归属 / 标签数量 / 标签修改次数都是**叠加**的一层，只切自己那层。 */
    fun applyRelationFilter(tab: Int, filter: ManageRelationFilter) =
        updateFilters(tab) { it.copy(relation = filter) }

    fun applyTagCountFilters(tab: Int, f: Set<ManageTagCountFilter>) =
        updateFilters(tab) { it.copy(tagCounts = f) }

    fun applyTagEditCountFilter(tab: Int, f: ManageTagEditCountFilter) =
        updateFilters(tab) { it.copy(tagEditCount = f) }

    // -----------------------------------------------------------------------
    // 多选状态（按 Tab 独立）
    // -----------------------------------------------------------------------

    private val _selection = MutableStateFlow(
        mapOf(TAB_VIDEO to ManageSelection(), TAB_IMAGE to ManageSelection()),
    )
    val selection: StateFlow<Map<Int, ManageSelection>> = _selection.asStateFlow()

    fun selectionOf(tab: Int): ManageSelection = _selection.value[tab] ?: ManageSelection()

    private fun setSelection(tab: Int, next: ManageSelection) {
        if (next == selectionOf(tab)) return
        _selection.value = _selection.value + (tab to next)
    }

    /** 长按进入多选并选中该条。 */
    fun enterSelectionMode(tab: Int, awemeId: String) {
        val current = selectionOf(tab)
        if (current.inSelectionMode) return
        setSelection(tab, ManageSelection(inSelectionMode = true, selectedIds = setOf(awemeId)))
    }

    /** 多选态下点击条目：切换选中；取消到一个不剩时退出多选模式。 */
    fun toggleSelection(tab: Int, awemeId: String) {
        val current = selectionOf(tab)
        if (!current.inSelectionMode) return
        val ids = if (awemeId in current.selectedIds) {
            current.selectedIds - awemeId
        } else {
            current.selectedIds + awemeId
        }
        setSelection(tab, if (ids.isEmpty()) ManageSelection() else current.copy(selectedIds = ids))
    }

    fun exitSelectionMode(tab: Int) = setSelection(tab, ManageSelection())

    /**
     * 全选 / 取消全选。
     *
     * 当前范围已全部加载时纯内存切换；仍有未加载的分页则先让该 Tab 把全库该范围的记录
     * 拉齐（[ManageCommand.LoadFullScopeThenSelectAll]），拉齐后经 [setLoaded] 回来再全选。
     */
    fun toggleSelectAll(tab: Int) {
        val loaded = loadedOf(tab)
        if (loaded.hasMore) {
            pendingSelectAll += tab
            emitCommand(ManageCommand.LoadFullScopeThenSelectAll(tab))
            return
        }
        if (isAllSelected(tab)) {
            // 取消全选但**保持多选模式**，便于用户重新挑选
            setSelection(tab, selectionOf(tab).copy(selectedIds = emptySet()))
        } else {
            selectAllLoaded(tab)
        }
    }

    private fun selectAllLoaded(tab: Int) {
        val ids = loadedOf(tab).entities.mapTo(linkedSetOf()) { it.awemeId }
        if (ids.isEmpty()) return
        setSelection(tab, ManageSelection(inSelectionMode = true, selectedIds = ids))
    }

    /**
     * 「已全选」= 当前范围全部记录都已加载（无更多分页）且全部选中。
     * 仅当满足此条件时菜单才显示「取消全选」，否则显示「全选」。
     */
    fun isAllSelected(tab: Int): Boolean {
        val loaded = loadedOf(tab)
        if (loaded.hasMore || loaded.entities.isEmpty()) return false
        return selectionOf(tab).selectedIds.size == loaded.entities.size
    }

    /** 当前选中的实体（导出用）。 */
    fun selectedEntities(tab: Int): List<DownloadedVideoEntity> {
        val ids = selectionOf(tab).selectedIds
        if (ids.isEmpty()) return emptyList()
        return loadedOf(tab).entities.filter { it.awemeId in ids }
    }

    // -----------------------------------------------------------------------
    // 各 Tab 上报的已加载快照
    // -----------------------------------------------------------------------

    private val loaded = mutableMapOf<Int, LoadedSnapshot>()
    private val pendingSelectAll = mutableSetOf<Int>()

    private fun loadedOf(tab: Int): LoadedSnapshot = loaded[tab] ?: LoadedSnapshot()

    /**
     * Tab 每次列表变化时调用：Activity 侧据此算「是否已全选」与「选中了哪些实体」。
     * 顺带把已不在列表里的选中项剔掉（删除后自动取消选中）。
     */
    fun setLoaded(tab: Int, entities: List<DownloadedVideoEntity>, hasMore: Boolean) {
        loaded[tab] = LoadedSnapshot(entities, hasMore)

        // 只在确实拉齐（没有更多分页）时才兑现待办的全选：
        // 命令若因页面不在前台而丢失，标志不会残留到下一次普通分页加载上。
        if (!hasMore && pendingSelectAll.remove(tab)) {
            selectAllLoaded(tab)
            return
        }
        val current = selectionOf(tab)
        if (!current.inSelectionMode) return
        val surviving = entities.mapTo(mutableSetOf()) { it.awemeId }
        val kept = current.selectedIds.intersect(surviving)
        if (kept.size == current.selectedIds.size) return
        setSelection(tab, if (kept.isEmpty()) ManageSelection() else current.copy(selectedIds = kept))
    }

    // -----------------------------------------------------------------------
    // 下发给 Tab 的动作
    // -----------------------------------------------------------------------

    private val _commands = MutableSharedFlow<ManageCommand>(replay = 0, extraBufferCapacity = 8)
    val commands: SharedFlow<ManageCommand> = _commands.asSharedFlow()

    private fun emitCommand(command: ManageCommand) {
        _commands.tryEmit(command)
    }

    fun requestDeleteSelected(tab: Int) {
        val ids = selectionOf(tab).selectedIds.toList()
        if (ids.isEmpty()) return
        emitCommand(ManageCommand.DeleteSelected(tab, ids))
        exitSelectionMode(tab)
    }

    fun requestSetTagsSelected(tab: Int) {
        val ids = selectionOf(tab).selectedIds.toList()
        if (ids.isEmpty()) return
        emitCommand(ManageCommand.SetTagsSelected(tab, ids))
    }

    fun requestClearInvalid(tab: Int) = emitCommand(ManageCommand.ClearInvalid(tab))

    fun requestMarkExported(tab: Int, awemeIds: Set<String>) =
        emitCommand(ManageCommand.MarkExported(tab, awemeIds))

    // -----------------------------------------------------------------------
    // 作者抽屉
    // -----------------------------------------------------------------------

    /**
     * 作者聚合加载完成。
     *
     * 有意做成事件流而非 [StateFlow]：它触发的是「打开抽屉」这个动作，
     * 用 StateFlow 会在转屏重新收集时凭最后一个值再弹一次抽屉，
     * 而且连续两次结果相同时不会重新发射、抽屉就打不开了。
     */
    private val _authors = MutableSharedFlow<List<AuthorCount>>(replay = 0, extraBufferCapacity = 4)
    val authors: SharedFlow<List<AuthorCount>> = _authors.asSharedFlow()

    fun loadAuthors(tab: Int) {
        viewModelScope.launch {
            _authors.tryEmit(withContext(Dispatchers.IO) { repo.getAuthorCounts(mediaTypeOf(tab)) })
        }
    }

    // -----------------------------------------------------------------------
    // 标签精细检索
    // -----------------------------------------------------------------------

    /**
     * 精细检索可选标签加载完成，触发「打开规则对话框」。
     *
     * 与 [authors] 同样做成事件流而非 [StateFlow]：它触发的是一个动作，
     * 用 StateFlow 会在转屏重新收集时凭最后一个值再弹一次对话框。
     */
    private val _tagQueryTags = MutableSharedFlow<List<String>>(replay = 0, extraBufferCapacity = 4)
    val tagQueryTags: SharedFlow<List<String>> = _tagQueryTags.asSharedFlow()

    fun loadTagsForQuery() {
        viewModelScope.launch {
            _tagQueryTags.tryEmit(withContext(Dispatchers.IO) { tagRepo.getAvailableTags() })
        }
    }

    // -----------------------------------------------------------------------
    // 统计面板
    // -----------------------------------------------------------------------

    private val _stats = MutableSharedFlow<ManageStats>(replay = 0, extraBufferCapacity = 4)
    val stats: SharedFlow<ManageStats> = _stats.asSharedFlow()

    fun loadStats() {
        viewModelScope.launch {
            val s = withContext(Dispatchers.IO) {
                ManageStats(
                    videoCount = repo.countByMediaType(DownloadMediaType.VIDEO),
                    imageCount = repo.countByMediaType(DownloadMediaType.IMAGE),
                    videoBytes = dirSize(BatchDownloadCoordinator.VIDEO_SUBDIR),
                    imageBytes = dirSize(BatchDownloadCoordinator.IMAGE_SUBDIR),
                    coverBytes = dirSize(BatchDownloadCoordinator.COVER_SUBDIR),
                    topAuthors = repo.getAuthorCountsAll().take(TOP_N),
                    topTags = tagRepo.getTagsWithCount().take(TOP_N)
                        .map { ManageStats.TagCount(it.tagName, it.count) },
                )
            }
            _stats.tryEmit(s)
        }
    }

    private fun dirSize(subDir: String): Long {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(root, subDir)
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { runCatching { it.length() }.getOrDefault(0L) }
    }

    // -----------------------------------------------------------------------
    // ZIP 导出
    // -----------------------------------------------------------------------

    private val _zipProgress = MutableStateFlow<ZipProgress?>(null)

    /** 非 null 表示 ZIP 打包进行中，Activity 据此显示 / 更新进度对话框。 */
    val zipProgress: StateFlow<ZipProgress?> = _zipProgress.asStateFlow()

    private val _zipResult = MutableSharedFlow<Result<MediaExportManager.ZipResult>>(
        replay = 0,
        extraBufferCapacity = 4,
    )
    val zipResult: SharedFlow<Result<MediaExportManager.ZipResult>> = _zipResult.asSharedFlow()

    fun exportToZip(entities: List<DownloadedVideoEntity>) {
        if (entities.isEmpty()) return
        _zipProgress.value = ZipProgress(0, 0)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    MediaExportManager.exportToZip(getApplication(), entities) { done, total ->
                        _zipProgress.value = ZipProgress(done, total)
                    }
                }
            }
            _zipProgress.value = null
            _zipResult.tryEmit(result)
        }
    }

    // -----------------------------------------------------------------------
    // 局域网导出
    // -----------------------------------------------------------------------

    private var lanServer: LanFileServer? = null

    private val _lanPreparing = MutableStateFlow<LanPrepareProgress?>(null)

    /**
     * 非 null 表示正在做导出前的宽高回填探测（v14）。
     *
     * 只在**确有待探测项**时非 null。历史记录第一次导出可能要探几百个文件、耗时数秒，
     * 没有进度提示界面会像卡死；之后是纯读库，这个状态不会再出现。
     */
    val lanPreparing: StateFlow<LanPrepareProgress?> = _lanPreparing.asStateFlow()

    private val _lanState = MutableStateFlow<LanExportState?>(null)

    /** 非 null 表示局域网服务运行中。 */
    val lanState: StateFlow<LanExportState?> = _lanState.asStateFlow()

    private val _lanError = MutableSharedFlow<LanStartFailure>(replay = 0, extraBufferCapacity = 4)
    val lanError: SharedFlow<LanStartFailure> = _lanError.asSharedFlow()

    /**
     * 导出计数落库用的独立 scope。
     *
     * 传输完成事件可能在用户刚点「停止服务」时到达，用 [viewModelScope] 会被取消掉导致计数丢失；
     * 作用域内只有一次短 UPDATE，且只持有 application 级的 dao，不会泄漏，故不做取消。
     */
    private val exportCountScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [backfillMediaSizes] 的返回值：回填后的实体列表 + 本次实际探测到的 `<awemeId, 宽高>`。 */
    private data class BackfillResult(
        val entities: List<DownloadedVideoEntity>,
        val probed: Map<String, MediaOrientationProbe.Size>,
    )

    /**
     * 导出前的宽高懒回填（v14）：对 `mediaWidth == 0` 的记录探测本地文件并写库。
     *
     * 返回回填后的实体列表——`resolveExportFiles` 读的是内存对象而不是数据库，
     * 只写库不更新内存会让本次导出全部落进竖屏包。同时把本次实际探测到的结果一并返回，
     * 由调用方（[startLanExport]）切回 Main 线程后合并进 [loaded]（见 [mergeProbedSizesIntoLoaded]），
     * 使**同一页面内的重复导出**命中已探测过的宽高，不再重新弹「正在识别视频方向…」。
     * 这个函数本身**不直接碰 [loaded]**——它跑在 IO 线程，[loaded] 只能在 Main 线程写。
     *
     * 探测失败保持 0（归竖屏），**不写哨兵值**：下次导出再探一次，代价几毫秒，
     * 换来 `0` 语义单一（只表示「不知道」）。写库失败也不影响本次导出（内存里已是探测结果）。
     *
     * 待探测集合额外要求文件仍存在：文件已被删除的记录探测永远返回 null、`mediaWidth` 永远是 0，
     * 若不排除会让进度分母里混入「探了也白探」的记录——反正 `resolveExportFiles` 后面也会跳过它们。
     *
     * **必须在 IO 线程调用。**
     */
    private suspend fun backfillMediaSizes(
        entities: List<DownloadedVideoEntity>,
    ): BackfillResult {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory()
        val pending = entities.filter {
            it.mediaWidth == 0 && it.filePath.isNotBlank() && File(root, it.filePath).isFile
        }
        if (pending.isEmpty()) return BackfillResult(entities, emptyMap())

        _lanPreparing.value = LanPrepareProgress(done = 0, total = pending.size)
        val probed = HashMap<String, MediaOrientationProbe.Size>(pending.size)
        try {
            pending.forEachIndexed { index, e ->
                MediaOrientationProbe.probe(File(root, e.filePath))?.let { size ->
                    probed[e.awemeId] = size
                    runCatching { repo.updateMediaSize(e.awemeId, size.width, size.height) }
                }
                _lanPreparing.value = LanPrepareProgress(done = index + 1, total = pending.size)
            }
        } finally {
            // 无论正常探测完还是中途抛异常，都要收掉进度对话框——它是 setCancelable(false)，
            // 不清掉用户就再也关不掉了。
            _lanPreparing.value = null
        }

        if (probed.isEmpty()) return BackfillResult(entities, emptyMap())
        val merged = entities.map { e ->
            probed[e.awemeId]?.let { e.copy(mediaWidth = it.width, mediaHeight = it.height) } ?: e
        }
        return BackfillResult(merged, probed)
    }

    /**
     * 把刚探测到的宽高按 `awemeId` 合并进 [loaded] 里对应 Tab 的快照。
     *
     * 只更新命中的记录的 `mediaWidth` / `mediaHeight`，其余字段与 `hasMore` 原样保留；
     * Fragment 之后一次普通的 [setLoaded] 仍会整体覆盖它，这里只是让「导出、不离开页面、再导出一次」
     * 这条常见路径不必重新探测。
     *
     * **必须在 Main 线程调用**——[loaded] 是普通 `mutableMapOf`，与 [setLoaded] 共用同一份存储，
     * 两者都只能在 Main 线程写，否则并发 `put` 是未定义行为。
     */
    private fun mergeProbedSizesIntoLoaded(tab: Int, probed: Map<String, MediaOrientationProbe.Size>) {
        val snapshot = loaded[tab] ?: return
        if (snapshot.entities.isEmpty()) return
        val merged = snapshot.entities.map { e ->
            probed[e.awemeId]?.let { e.copy(mediaWidth = it.width, mediaHeight = it.height) } ?: e
        }
        loaded[tab] = snapshot.copy(entities = merged)
    }

    fun startLanExport(tab: Int, entities: List<DownloadedVideoEntity>) {
        val ip = LanFileServer.localIpv4()
        if (ip == null) {
            _lanError.tryEmit(LanStartFailure.NoWifi)
            return
        }
        // 只有视频 Tab 分横屏/竖屏包；图片 Tab 的行为与改动前完全一致。
        val split = tab == TAB_VIDEO
        viewModelScope.launch {
            // 图片 Tab 不分包，探测对本次导出毫无用处，直接跳过回填。
            val resolved = if (split) {
                val backfill = withContext(Dispatchers.IO) { backfillMediaSizes(entities) }
                // 切回 Main（viewModelScope 默认 Dispatchers.Main.immediate）后再合并进 loaded，
                // 与 setLoaded 保持同一线程写，避免和列表刷新竞态（见 mergeProbedSizesIntoLoaded 文档）。
                if (backfill.probed.isNotEmpty()) {
                    mergeProbedSizesIntoLoaded(tab, backfill.probed)
                }
                backfill.entities
            } else {
                entities
            }
            val files = withContext(Dispatchers.IO) {
                MediaExportManager.resolveExportFiles(resolved)
            }
            if (files.isEmpty()) {
                _lanError.tryEmit(LanStartFailure.NothingToExport)
                return@launch
            }
            stopLanExport() // 若已有服务在跑，先停掉
            val server = LanFileServer(files, split) { event -> onLanTransferComplete(tab, event) }
            val port = try {
                server.start()
            } catch (e: Exception) {
                _lanError.tryEmit(LanStartFailure.StartFailed(e.message ?: e.javaClass.simpleName))
                return@launch
            }
            lanServer = server
            _lanState.value = LanExportState(
                url = "http://$ip:$port/",
                fileCount = files.size,
                splitByOrientation = split,
                landscapeCount = if (split) files.count { it.orientation == MediaOrientation.LANDSCAPE } else 0,
                portraitCount = if (split) files.count { it.orientation == MediaOrientation.PORTRAIT } else 0,
            )
        }
    }

    fun stopLanExport() {
        lanServer?.stop()
        lanServer = null
        _lanState.value = null
        _lanPreparing.value = null
    }

    /**
     * 传输完成回调：**在 [LanFileServer] 的连接线程触发**。
     *
     * 计数落库走 [exportCountScope]（IO），且必须保持
     * `SET exportCount = exportCount + 1` 的**原子累加**——不要改成「读实体→改→整行 update」，
     * 并发写会互相覆盖。
     *
     * 语义只到「手机已把字节完整发出」；电脑侧是否保存成功探知不到（HTTP 无反向通道），
     * 所以这是提示性计数，不是权威状态。
     */
    private fun onLanTransferComplete(tab: Int, event: LanFileServer.TransferEvent) {
        exportCountScope.launch {
            runCatching { repo.incrementExportCount(event.awemeIds) }
        }
        // 从连接线程切回 ViewModel 的主调度器再改状态
        viewModelScope.launch {
            val current = _lanState.value ?: return@launch
            _lanState.value = current.copy(
                transferCount = current.transferCount + 1,
                lastTransfer = LanExportState.Transfer(
                    isZip = event.isZip,
                    label = event.label,
                    itemCount = event.awemeIds.size,
                ),
            )
            // 当前列表里的对应条目立刻显示「已导出」标记
            requestMarkExported(tab, event.awemeIds)
        }
    }

    override fun onCleared() {
        // 服务活在 ViewModel 里，转屏不再中断正在进行的下载
        stopLanExport()
        super.onCleared()
    }

    private fun mediaTypeOf(tab: Int): String =
        if (tab == TAB_VIDEO) DownloadMediaType.VIDEO else DownloadMediaType.IMAGE

    companion object {
        const val TAB_VIDEO = 0
        const val TAB_IMAGE = 1
        private const val TOP_N = 8
    }
}

/** 某个 Tab 的多选状态。 */
data class ManageSelection(
    val inSelectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
) {
    val count: Int get() = selectedIds.size
}

/** 某个 Tab 上报的已加载快照，供 Activity 侧算全选与导出。 */
private data class LoadedSnapshot(
    val entities: List<DownloadedVideoEntity> = emptyList(),
    val hasMore: Boolean = true,
)

/** Activity 下发给某个 Tab 的动作。 */
sealed interface ManageCommand {
    val tab: Int

    data class DeleteSelected(override val tab: Int, val ids: List<String>) : ManageCommand
    data class SetTagsSelected(override val tab: Int, val ids: List<String>) : ManageCommand
    data class ClearInvalid(override val tab: Int) : ManageCommand
    data class LoadFullScopeThenSelectAll(override val tab: Int) : ManageCommand
    data class MarkExported(override val tab: Int, val awemeIds: Set<String>) : ManageCommand
}

/** 统计面板的原始数据；文案拼接留在视图层。 */
data class ManageStats(
    val videoCount: Int,
    val imageCount: Int,
    val videoBytes: Long,
    val imageBytes: Long,
    val coverBytes: Long,
    val topAuthors: List<AuthorCount>,
    val topTags: List<TagCount>,
) {
    val totalBytes: Long get() = videoBytes + imageBytes + coverBytes
    val totalCount: Int get() = videoCount + imageCount

    data class TagCount(val tagName: String, val count: Int)
}

data class ZipProgress(val done: Int, val total: Int)

data class LanExportState(
    val url: String,
    val fileCount: Int,
    /** 是否按横屏/竖屏分包（仅视频 Tab 为 true）。 */
    val splitByOrientation: Boolean = false,
    /** 横屏文件数；[splitByOrientation] 为 false 时恒为 0。 */
    val landscapeCount: Int = 0,
    /** 竖屏文件数；[splitByOrientation] 为 false 时恒为 0。 */
    val portraitCount: Int = 0,
    val transferCount: Int = 0,
    val lastTransfer: Transfer? = null,
) {
    data class Transfer(val isZip: Boolean, val label: String, val itemCount: Int)
}

/** 局域网导出前的宽高回填探测进度（v14）。 */
data class LanPrepareProgress(val done: Int, val total: Int)

sealed interface LanStartFailure {
    data object NoWifi : LanStartFailure
    data object NothingToExport : LanStartFailure
    data class StartFailed(val message: String) : LanStartFailure
}
