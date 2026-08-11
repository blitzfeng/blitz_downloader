package com.blitz.downloader.viewmodel

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.config.AppSettings
import com.blitz.downloader.data.DownloadedVideoRepository
import com.blitz.downloader.data.VideoTagRepository
import com.blitz.downloader.data.db.DownloadedVideoEntity
import com.blitz.downloader.model.ManageGridItem
import com.blitz.downloader.model.filter.ManageFilterState
import com.blitz.downloader.model.filter.ManageRelationFilter
import com.blitz.downloader.model.filter.ManageSortOrder
import com.blitz.downloader.model.filter.ManageTagCountFilter
import com.blitz.downloader.model.filter.ManageTagEditCountFilter
import com.blitz.downloader.model.filter.TagQuery
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 管理页各 Tab 的取数载体：分页 / 筛选 / 排序 / 删除 / 标签读写全在这里，
 * Fragment 只渲染 [uiState] 并处理弹窗与跳转。
 *
 * 视频 Tab 与图片 Tab 的取数链路结构完全一致，差异收敛为几个能力开关
 * （[mediaType]、[checksFileExistence]、[loadsUserTags]），因此共用这一个基类。
 * 标签相关的层（标签多选 / 标签数量 / 标签修改次数）对图片 Tab 而言天然是
 * 空条件，不会走到那些分支。
 */
abstract class ManageTabViewModel(app: Application) : AndroidViewModel(app) {

    /** 本 Tab 处理的媒体类型，见 [com.blitz.downloader.data.DownloadMediaType]。 */
    protected abstract val mediaType: String

    /** 是否检查本地文件存在性并显示失效蒙层（视频 Tab 有，图片 Tab 没有）。 */
    protected open val checksFileExistence: Boolean = false

    /** 是否加载并展示用户自定义标签行（同上）。 */
    protected open val loadsUserTags: Boolean = false

    protected val repo: DownloadedVideoRepository
        get() = (getApplication<Application>() as BlitzApp).downloadedVideoRepository

    protected val tagRepo: VideoTagRepository
        get() = (getApplication<Application>() as BlitzApp).videoTagRepository

    private val _uiState = MutableStateFlow(ManageTabUiState())
    val uiState: StateFlow<ManageTabUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ManageTabEvent>(replay = 0, extraBufferCapacity = 16)
    val events: SharedFlow<ManageTabEvent> = _events.asSharedFlow()

    /** 当前筛选条件。提交 4 起由 Activity 级 ViewModel 统一持有并下发。 */
    var filters: ManageFilterState = ManageFilterState()
        private set

    private val items = mutableListOf<ManageGridItem>()
    private var currentOffset = 0
    private var isLoading = false
    private var hasMore = true

    /**
     * 标签多选的匹配方式，每次取数时现读设置。
     *
     * [AppSettings] 每次 getter 直读 SharedPreferences、不做内存缓存，
     * 这里也**不要**把结果缓存起来，否则设置页改完本页拿到的还是旧值。
     */
    private fun tagMatchAll(): Boolean = AppSettings.isTagFilterMatchAll(getApplication())

    // -----------------------------------------------------------------------
    // 筛选条件
    // -----------------------------------------------------------------------

    /** 整体替换筛选条件；有变化则从头重新加载。 */
    fun applyFilters(newFilters: ManageFilterState) {
        if (newFilters == filters) return
        filters = newFilters
        refresh()
    }

    fun applySearchQuery(query: String?) = applyFilters(filters.withSearchQuery(query.orEmpty()))

    fun applyAuthorFilter(secUserId: String?, userName: String?) =
        applyFilters(filters.withAuthor(secUserId, userName))

    fun applyTags(tags: Set<String>) = applyFilters(filters.withTags(tags))

    fun applySort(sort: ManageSortOrder) = applyFilters(filters.copy(sort = sort))

    /** 归属 / 标签数量 / 标签修改次数都是**叠加**的一层，只切自己这层，其他条件原样保留。 */
    fun applyRelationFilter(filter: ManageRelationFilter) = applyFilters(filters.copy(relation = filter))

    fun applyTagCountFilters(f: Set<ManageTagCountFilter>) = applyFilters(filters.copy(tagCounts = f))

    fun applyTagEditCountFilter(f: ManageTagEditCountFilter) = applyFilters(filters.copy(tagEditCount = f))

    // -----------------------------------------------------------------------
    // 加载
    // -----------------------------------------------------------------------

    /** 首次进入时加载第一页；重复调用不会重复加载。 */
    fun loadInitialIfNeeded() {
        if (items.isNotEmpty() || isLoading) return
        loadNextPage()
    }

    /** 从头加载（筛选 / 排序变化时）。 */
    fun refresh() {
        currentOffset = 0
        hasMore = true
        items.clear()
        publish(showProgress = true)
        loadNextPage()
    }

    /** 滚动到底部时的前置判定，避免每次滚动都进协程。 */
    fun canLoadMore(): Boolean = !isLoading && hasMore

    fun loadNextPage() {
        if (isLoading || !hasMore) return
        isLoading = true
        val firstPage = currentOffset == 0
        if (firstPage) publish(showProgress = true)

        viewModelScope.launch {
            val entities = withContext(Dispatchers.IO) { queryPage(firstPage) }
            currentOffset += entities.size
            if (entities.isNotEmpty()) {
                items.addAll(entities.map { ManageGridItem(it) })
            }
            isLoading = false
            publish()
            if (entities.isNotEmpty()) {
                if (loadsUserTags) loadAndApplyTags(entities)
                if (checksFileExistence) checkFileExistence(entities)
            }
        }
    }

    /**
     * 取一页数据。只有「无标签、无内存筛选、无搜索、无作者」的默认路径才真正分页，
     * 其余路径一次性返回全集（[hasMore] 置 false）。
     *
     * 「标签数量」「标签修改次数」只有内存实现，走分页会出现「整页被筛空」，故必须全量加载。
     */
    private suspend fun queryPage(firstPage: Boolean): List<DownloadedVideoEntity> {
        val f = filters
        return when {
            // 作者 / 搜索必须排在 tagQuery 之前：搜索与精细检索刻意不互清（同口径于标签栏多选），
            // 搜索期间要临时压住规则结果，退出搜索规则结果再自动回来——顺序反了会导致激活精细检索后
            // 搜索框输入任何文字列表都纹丝不动，看起来像搜索坏了。
            f.hasAuthorFilter -> oneShot(firstPage) { postProcess(loadAuthorEntities()) }
            f.searchQuery.isNotBlank() ->
                oneShot(firstPage) { postProcess(repo.searchByUserName(mediaType, f.searchQuery)) }
            // 只需排在 tags 相关分支之前：精细检索激活时 tags 恒为空（两者互斥），
            // 排在 `f.tags.isEmpty() && ...` 之后会被那些分支截走。
            f.tagQuery.isActive -> oneShot(firstPage) { postProcess(loadTagQueryEntities()) }
            f.tags.isEmpty() && f.hasMemoryOnlyFilter ->
                oneShot(firstPage) { postProcess(repo.getAllByMediaType(mediaType)) }
            f.tags.isEmpty() -> {
                // 全量分页（按当前排序）；归属筛选下沉到 SQL，保证 LIMIT 前生效、分页计数正确
                val page = repo.getPageByMediaTypeSorted(
                    mediaType,
                    f.sort.column,
                    PAGE_SIZE,
                    currentOffset,
                    f.relation.unassignedOnly,
                )
                if (page.size < PAGE_SIZE) hasMore = false
                page
            }
            else -> oneShot(firstPage) {
                // 按标签全量加载（标签结果集通常不大，不做额外分页）；多选时按设置取交集 / 并集
                postProcess(
                    tagRepo.getVideosByTags(f.tags, tagMatchAll()).filter { it.mediaType == mediaType },
                )
            }
        }
    }

    /** 一次性返回全集的取数路径：只在首页执行，之后置 [hasMore] = false。 */
    private suspend fun oneShot(
        firstPage: Boolean,
        block: suspend () -> List<DownloadedVideoEntity>,
    ): List<DownloadedVideoEntity> {
        hasMore = false
        return if (firstPage) block() else emptyList()
    }

    /** 按当前作者筛选取该 mediaType 下的全部作品：有稳定 ID 走 ID，无则退回昵称。 */
    private suspend fun loadAuthorEntities(): List<DownloadedVideoEntity> =
        if (filters.authorSecId.isNotBlank()) {
            repo.getByMediaTypeAndAuthorSecUserId(mediaType, filters.authorSecId)
        } else {
            repo.getByMediaTypeAndUserName(mediaType, filters.authorName)
        }

    /**
     * 「标签精细检索」的取数：先在 SQL 侧把每个涉及标签的 awemeId 捞出来，
     * 再按 [TagQuery] 的左结合语义在内存里做集合运算，最后与本 Tab 的全量记录取交。
     *
     * 不新增 `WHERE awemeId IN (:ids)` 的 DAO：涉及标签最多几个（查询次数可控），
     * 而算出来的 id 集合可能上千，会撞 SQLite 的变量数上限。全量读一次该 mediaType
     * 的代价与「标签数量 / 标签修改次数」两层的做法一致。
     */
    private suspend fun loadTagQueryEntities(): List<DownloadedVideoEntity> {
        val query = filters.tagQuery
        val idsByTag = query.involvedTags.associateWith { tagRepo.getAwemeIdsByTag(it).toSet() }
        val ids = query.evaluate(idsByTag)
        if (ids.isEmpty()) return emptyList()
        return repo.getAllByMediaType(mediaType).filter { it.awemeId in ids }
    }

    // -----------------------------------------------------------------------
    // 内存侧筛选与排序
    // -----------------------------------------------------------------------

    /**
     * 非分页路径（搜索 / 标签 / 作者 / 全选）的统一后处理：依次过「按归属筛选」
     * 「按标签数量筛选」「按标签修改次数筛选」三层，再按当前排序倒排。
     * 分页路径的归属筛选下沉到 SQL，不走这里；后两层没有 SQL 侧实现，激活时取数路径本身
     * 就已经切成全量加载。
     *
     * 新增取数入口一律经过这里，否则筛选会「漏一层」。
     */
    protected suspend fun postProcess(list: List<DownloadedVideoEntity>): List<DownloadedVideoEntity> =
        sortEntities(filterByTagEditCount(filterByTagCount(filters.relation.apply(list))))

    /**
     * 「标签数量」「标签修改次数」两层筛选共用的归属收窄。
     *
     * 这两层**隶属于归属筛选**：无归属记录（既无点赞/收藏关系、也不属于收藏夹，通常是从
     * 他人主页 post 下载的）绝大多数没打过标签、更没改过，不排掉的话「0 个标签」「改过 0 次」
     * 会被它们淹没。因此归属筛选为 OFF 时仍按 [ManageRelationFilter.EXCLUDE_UNASSIGNED] 收窄；
     * 只有用户显式选了「仅看无归属」才以用户的选择为准。
     */
    private fun scopeByRelation(list: List<DownloadedVideoEntity>): List<DownloadedVideoEntity> =
        if (filters.relation == ManageRelationFilter.ONLY_UNASSIGNED) {
            list
        } else {
            list.filterNot { ManageRelationFilter.isUnassigned(it) }
        }

    /**
     * 按标签数量档位过滤：标签数来自 `video_tags` 聚合，查不到的记录算 0 个。
     * 勾选多档时取**并集**（命中任一档即保留）——与标签栏多选默认取交集不是一回事。
     */
    private suspend fun filterByTagCount(list: List<DownloadedVideoEntity>): List<DownloadedVideoEntity> {
        if (filters.tagCounts.isEmpty() || list.isEmpty()) return list
        val scoped = scopeByRelation(list)
        if (scoped.isEmpty()) return scoped
        val counts = tagRepo.getTagCountMap()
        return scoped.filter { ManageTagCountFilter.matchesAny(filters.tagCounts, counts[it.awemeId] ?: 0) }
    }

    /** 按标签修改次数过滤：次数直接取实体上的 `tagEditCount`，不需要额外查询。 */
    private fun filterByTagEditCount(list: List<DownloadedVideoEntity>): List<DownloadedVideoEntity> {
        if (!filters.tagEditCount.isActive || list.isEmpty()) return list
        val scoped = scopeByRelation(list)
        if (scoped.isEmpty()) return scoped
        return scoped.filter { filters.tagEditCount.matches(it.tagEditCount) }
    }

    /** 按当前排序对内存中的实体列表倒序排序（用于非分页的筛选/全选路径）。 */
    private fun sortEntities(list: List<DownloadedVideoEntity>): List<DownloadedVideoEntity> =
        when (filters.sort) {
            ManageSortOrder.DOWNLOAD_TIME -> list.sortedByDescending { it.createdAtMillis }
            ManageSortOrder.PUBLISH_TIME -> list.sortedByDescending { it.createTime }
            ManageSortOrder.DIGG -> list.sortedByDescending { it.diggCount }
        }

    // -----------------------------------------------------------------------
    // 全选
    // -----------------------------------------------------------------------

    /**
     * 把当前范围在数据库中的全部记录一次拉齐（供「全选」使用）。
     *
     * 拉齐后照常经 `uiState` 发布，Activity 级 ViewModel 在收到新的已加载快照时
     * 完成全选——不需要额外的事件往返。
     */
    fun loadFullScope() {
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { loadFullScopeEntities() }
            if (all.isEmpty()) return@launch
            items.clear()
            items.addAll(all.map { ManageGridItem(it) })
            currentOffset = all.size
            hasMore = false
            isLoading = false
            publish()
            if (loadsUserTags) loadAndApplyTags(all)
            if (checksFileExistence) checkFileExistence(all)
        }
    }

    /** 当前范围（搜索 / 标签 / 作者 / 全部）在数据库中的全部记录。 */
    private suspend fun loadFullScopeEntities(): List<DownloadedVideoEntity> = withContext(Dispatchers.IO) {
        val f = filters
        // 顺序与 queryPage(...) 保持一致：author / search 优先于 tagQuery，理由见那边的注释。
        val list = when {
            f.hasAuthorFilter -> loadAuthorEntities()
            f.searchQuery.isNotBlank() -> repo.searchByUserName(mediaType, f.searchQuery)
            f.tagQuery.isActive -> loadTagQueryEntities()
            f.tags.isEmpty() -> repo.getAllByMediaType(mediaType)
            else -> tagRepo.getVideosByTags(f.tags, tagMatchAll()).filter { it.mediaType == mediaType }
        }
        postProcess(list)
    }

    // -----------------------------------------------------------------------
    // 条目级操作
    // -----------------------------------------------------------------------

    fun deleteSelected(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { repo.deleteByAwemeIds(ids) }
            val idSet = ids.toSet()
            items.removeAll { it.entity.awemeId in idSet }
            publish()
            emit(ManageTabEvent.DeleteDone(deleted))
        }
    }

    /**
     * 局域网导出成功后把这些条目的 `exportCount` 就地 +1，刷新左上角「已导出」标记。
     * 与数据库侧的原子累加（`SET exportCount = exportCount + 1`）是两笔独立更新——
     * 这里只为让当前列表立刻显示正确，下次重新查询时以数据库为准。
     */
    fun markExported(awemeIds: Set<String>) {
        if (awemeIds.isEmpty()) return
        var changed = false
        for (i in items.indices) {
            val entity = items[i].entity
            if (entity.awemeId !in awemeIds) continue
            items[i] = items[i].copy(entity = entity.copy(exportCount = entity.exportCount + 1))
            changed = true
        }
        if (changed) publish()
    }

    /** 把这些条目就地标为「已看过」，去掉封面上的「未看过」标记。 */
    protected fun markWatched(awemeIds: Set<String>) {
        if (awemeIds.isEmpty()) return
        var changed = false
        for (i in items.indices) {
            val entity = items[i].entity
            if (entity.awemeId !in awemeIds || entity.watched) continue
            items[i] = items[i].copy(entity = entity.copy(watched = true))
            changed = true
        }
        if (changed) publish()
    }

    /**
     * 批量查询这些记录的用户自定义标签并回写到列表。
     * 与加载分开跑：标签行晚一点出现不影响封面先显示。
     */
    private fun loadAndApplyTags(entities: List<DownloadedVideoEntity>) {
        if (entities.isEmpty()) return
        viewModelScope.launch {
            val awemeIds = entities.map { it.awemeId }
            val tagsMap = withContext(Dispatchers.IO) { tagRepo.getTagsMapForVideos(awemeIds) }
            applyTagsToItems(tagsMap)
        }
    }

    protected fun applyTagsToItems(tagsMap: Map<String, List<String>>) {
        if (tagsMap.isEmpty()) return
        var changed = false
        for (i in items.indices) {
            val tags = tagsMap[items[i].entity.awemeId] ?: continue
            if (items[i].userTags == tags) continue
            items[i] = items[i].copy(userTags = tags)
            changed = true
        }
        if (changed) publish()
    }

    protected fun applyTagsToItem(awemeId: String, tags: List<String>) =
        applyTagsToItems(mapOf(awemeId to tags))

    /**
     * 检查本地文件存在性；不存在的标记失效蒙层。
     * 只检查 filePath 非空的记录（旧记录无路径，保守不标失效）。
     */
    private fun checkFileExistence(entities: List<DownloadedVideoEntity>) {
        if (entities.isEmpty()) return
        viewModelScope.launch {
            val missing = withContext(Dispatchers.IO) {
                entities.filter { it.filePath.isNotBlank() && !resolveFile(it.filePath).exists() }
                    .map { it.awemeId }
                    .toSet()
            }
            if (missing.isEmpty()) return@launch
            var changed = false
            for (i in items.indices) {
                if (items[i].entity.awemeId !in missing || !items[i].fileExists) continue
                items[i] = items[i].copy(fileExists = false)
                changed = true
            }
            if (changed) publish()
        }
    }

    // -----------------------------------------------------------------------
    // 输出
    // -----------------------------------------------------------------------

    private fun publish(showProgress: Boolean = false) {
        _uiState.value = ManageTabUiState(
            items = items.toList(),
            showProgress = showProgress && items.isEmpty(),
            emptyReason = if (items.isEmpty() && !isLoading && !hasMore) emptyReason() else null,
            hasMore = hasMore,
        )
    }

    /** 空列表时该显示哪条文案。具体字符串由 Fragment 用 `R.string` 渲染。 */
    private fun emptyReason(): ManageEmptyReason {
        val f = filters
        return when {
            f.hasAuthorFilter -> ManageEmptyReason.Author(f.authorName)
            f.searchQuery.isNotBlank() -> ManageEmptyReason.Search(f.searchQuery)
            f.tagQuery.isActive -> ManageEmptyReason.TagRules(f.tagQuery)
            f.tags.isNotEmpty() -> ManageEmptyReason.Tags(f.tags, tagMatchAll())
            f.tagCounts.isNotEmpty() -> ManageEmptyReason.TagCounts(f.tagCounts)
            f.tagEditCount.isActive -> ManageEmptyReason.TagEditCount(f.tagEditCount)
            // 归属筛选放在其他条件之后判断：有更具体的筛选时优先显示那条文案
            f.relation != ManageRelationFilter.OFF -> ManageEmptyReason.Relation
            else -> ManageEmptyReason.NoRecords
        }
    }

    protected fun emit(event: ManageTabEvent) {
        _events.tryEmit(event)
    }

    protected fun resolveFile(filePath: String): File {
        @Suppress("DEPRECATION")
        return File(Environment.getExternalStorageDirectory(), filePath)
    }

    companion object {
        private const val PAGE_SIZE = 20
    }
}

data class ManageTabUiState(
    val items: List<ManageGridItem> = emptyList(),
    /** 首屏加载中（列表为空时才显示进度条）。 */
    val showProgress: Boolean = false,
    /** 非 null 时显示空状态及对应文案。 */
    val emptyReason: ManageEmptyReason? = null,
    val hasMore: Boolean = true,
)

/** 列表为空的原因；`R.string` 的选取留在视图层。 */
sealed interface ManageEmptyReason {
    data class Author(val name: String) : ManageEmptyReason
    data class Search(val query: String) : ManageEmptyReason
    data class Tags(val names: Set<String>, val matchAll: Boolean) : ManageEmptyReason

    /** 标签精细检索无结果。命名避开 `TagQuery`，免得与同名的条件类混淆。 */
    data class TagRules(val query: TagQuery) : ManageEmptyReason
    data class TagCounts(val filters: Set<ManageTagCountFilter>) : ManageEmptyReason
    data class TagEditCount(val filter: ManageTagEditCountFilter) : ManageEmptyReason
    data object Relation : ManageEmptyReason

    /** 该 Tab 一条记录都没有；具体文案（视频 / 图片）由各 Fragment 决定。 */
    data object NoRecords : ManageEmptyReason
}

sealed interface ManageTabEvent {
    data class DeleteDone(val count: Int) : ManageTabEvent
    data class ClearInvalidDone(val count: Int) : ManageTabEvent
    data object ClearInvalidNone : ManageTabEvent
    data object NoTagsAvailable : ManageTabEvent

    /** 单条记录的标签编辑弹窗数据就绪。 */
    data class ShowTagEditor(
        val awemeId: String,
        val allTags: List<String>,
        val currentTags: Set<String>,
    ) : ManageTabEvent

    /** 多选后的批量标签弹窗数据就绪。 */
    data class ShowBatchTagPicker(val awemeIds: List<String>, val allTags: List<String>) : ManageTabEvent

    data class TagsApplied(val count: Int) : ManageTabEvent
    data class TagEditCountBumped(val count: Int) : ManageTabEvent
    data object FileNotFound : ManageTabEvent

    data class OpenVideoPlayer(
        val filePaths: List<String>,
        val titles: List<String>,
        val subtitles: List<String>,
        val position: Int,
        val awemeIds: List<String>,
    ) : ManageTabEvent

    data class OpenImageViewer(val filePath: String, val title: String) : ManageTabEvent
}
