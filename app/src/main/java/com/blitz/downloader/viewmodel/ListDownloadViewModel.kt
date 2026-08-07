package com.blitz.downloader.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.api.AwemeItem
import com.blitz.downloader.api.AwemeMapper
import com.blitz.downloader.api.DouyinApiClient
import com.blitz.downloader.api.DouyinAuthException
import com.blitz.downloader.api.DouyinCollectsFolderRow
import com.blitz.downloader.api.DouyinListApi
import com.blitz.downloader.api.DouyinPageKind
import com.blitz.downloader.api.DouyinUrlParser
import com.blitz.downloader.config.AppConfig
import com.blitz.downloader.data.DownloadMediaType
import com.blitz.downloader.data.DownloadSourceType
import com.blitz.downloader.data.DownloadedVideoRepository
import com.blitz.downloader.download.DownloadEvents
import com.blitz.downloader.download.DownloadJob
import com.blitz.downloader.download.DownloadRecordMeta
import com.blitz.downloader.download.DownloadService
import com.blitz.downloader.model.VideoItemUiModel
import com.blitz.downloader.util.DouyinCookieSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 批量下载页的网络与数据库操作载体。
 *
 * 承载：列表接口取数与分页、Cookie 同步与登录态失效重试、下载入库元数据组装与前台服务提交、
 * 下载完成事件收集、以及「已下载」标记的整表回查。Fragment 只负责渲染与弹窗。
 *
 * **选中状态的唯一权威是 [selectedIds]**（以及 [imageSelections]）。[VideoItemUiModel.isSelected]
 * 与 [VideoItemUiModel.selectedImageIndices] 只在 [compose] 里按这两者填出来——
 * 一份给 Adapter 渲染，一份给下载链路（[com.blitz.downloader.download.BatchDownloadCoordinator]
 * 靠 `isSelected` 筛选待下载项），这样 `download/` 无需任何改动。
 */
class ListDownloadViewModel(app: Application) : AndroidViewModel(app) {

    // -----------------------------------------------------------------------
    // 对外状态
    // -----------------------------------------------------------------------

    private val _uiState = MutableStateFlow(ListDownloadUiState())
    val uiState: StateFlow<ListDownloadUiState> = _uiState.asStateFlow()

    private val _cookieStatus = MutableStateFlow(CookieStatusUi())
    val cookieStatus: StateFlow<CookieStatusUi> = _cookieStatus.asStateFlow()

    /**
     * 一次性事件（导航、弹窗、Toast）。
     *
     * 与 [DownloadEvents] 保持同一套模型：`replay = 0`，不缓存历史。
     */
    private val _events = MutableSharedFlow<ListDownloadEvent>(
        replay = 0,
        extraBufferCapacity = 16,
    )
    val events: SharedFlow<ListDownloadEvent> = _events.asSharedFlow()

    // -----------------------------------------------------------------------
    // 内部状态
    // -----------------------------------------------------------------------

    /** 列表源数据。其中的 `isSelected` / `selectedImageIndices` 恒为默认值，不作数。 */
    private val items = mutableListOf<VideoItemUiModel>()

    /** 选中的作品 id —— 选中状态的唯一权威。 */
    private val selectedIds = linkedSetOf<String>()

    /**
     * 图集的部分选图结果（awemeId → 勾选下标）。
     *
     * **不在表里 = 全选**（对应 [VideoItemUiModel.selectedImageIndices] 的 `null`）。
     * 空集合不会存进来：一张都不选等于放弃这条，会连同 [selectedIds] 一起清掉。
     */
    private val imageSelections = mutableMapOf<String, Set<Int>>()

    private var hideDownloaded: Boolean = false

    /**
     * 是否处于「查看 TA 的 Post」模式。
     *
     * 放在 ViewModel 里而不是 Fragment：列表数据现在活过配置变更，若这个标志还留在视图层，
     * 转屏后会出现「列表还是作者的、界面却回到默认」的不一致。
     */
    private var authorPostsMode: Boolean = false

    private var listApiMode: ListApiMode = ListApiMode.None
    private var listSecUserId: String? = null
    private var listMixId: String? = null
    private var listCollectsId: String? = null

    /** 当前选中收藏夹的显示名称（[ListApiMode.CollectsVideo] 时有效），用于写入 DB。 */
    private var listCollectsName: String = ""
    private var listNextCursor: Long = 0L
    private var listHasMore: Boolean = false
    private var listLoadingMore: Boolean = false

    private var status: ListStatus = ListStatus.Idle
    private var listLoadJob: Job? = null

    /** 登录态失效弹窗中「去登录」后待重试的加载动作；登录返回且已登录时自动执行。 */
    private var pendingRetryAfterLogin: (suspend () -> Unit)? = null

    /**
     * 是否正等待用户登录返回后自动重试。
     *
     * 放在 ViewModel 而非 Fragment：跳转 WebView 期间 Fragment 可能被系统回收，
     * 字段留在 Fragment 里会连同待重试动作一起丢失，自动重试就静默失效了。
     */
    private var awaitingLoginRetry: Boolean = false

    private val downloadedRepo: DownloadedVideoRepository
        get() = (getApplication<Application>() as BlitzApp).downloadedVideoRepository

    init {
        // 下载服务写库成功后就地刷新列表（打角标 + 取消勾选）。
        // 收集放在 ViewModel 里：它比 Fragment 活得久，页面重建期间不会漏事件。
        // 但**不能**因此删掉 onScreenResumed 的整表回查——进程被杀的场景仍然存在。
        viewModelScope.launch {
            DownloadEvents.recorded.collect { ids -> markItemsDownloaded(ids) }
        }
        refreshCookieStatus()
    }

    // -----------------------------------------------------------------------
    // 生命周期回调
    // -----------------------------------------------------------------------

    /**
     * Fragment `onResume` 时调用。
     *
     * 这里的整表回查是「已下载」标记的**兜底路径**，与 [DownloadEvents] 的就地标记分工不同：
     * ViewModel 的 `init` 收集代替不了它（ViewModel 不随 onResume 重建，进程被杀后事件也不会补发）。
     */
    fun onScreenResumed() {
        refreshCookieStatus()
        if (items.isNotEmpty()) {
            viewModelScope.launch { reapplyDownloadedFlags() }
        }
        if (!awaitingLoginRetry) return
        // 从登录页返回：先把 WebView 里的最新 Cookie 同步进来，再判断是否已登录。
        DouyinCookieSync.syncFromCookieManager()
        refreshCookieStatus()
        if (!DouyinCookieSync.cookieTokenSnapshot(DouyinApiClient.globalCookie).hasLoginSession) return
        awaitingLoginRetry = false
        val retry = pendingRetryAfterLogin
        pendingRetryAfterLogin = null
        if (retry != null) {
            listLoadJob?.cancel()
            listLoadJob = viewModelScope.launch { retry() }
        }
    }

    // -----------------------------------------------------------------------
    // Cookie
    // -----------------------------------------------------------------------

    private fun refreshCookieStatus() {
        val line = DouyinApiClient.globalCookie
        _cookieStatus.value = if (line.isNullOrBlank()) {
            CookieStatusUi()
        } else {
            CookieStatusUi(hasCookie = true, snapshot = DouyinCookieSync.cookieTokenSnapshot(line))
        }
    }

    /** 从系统 Cookie（含独立「抖音网页」WebView）合并到内存并持久化。 */
    fun syncCookieFromCookieManager() {
        val merged = DouyinCookieSync.syncFromCookieManager()
        refreshCookieStatus()
        val snap = DouyinCookieSync.cookieTokenSnapshot(DouyinApiClient.globalCookie)
        val result = when {
            merged == null && DouyinApiClient.globalCookie.isNullOrBlank() -> CookieSyncResult.EMPTY
            merged == null -> CookieSyncResult.WEB_EMPTY
            snap.hasLoginSession -> CookieSyncResult.OK_WITH_LOGIN
            else -> CookieSyncResult.OK_NO_LOGIN
        }
        emit(ListDownloadEvent.CookieSynced(result))
    }

    /** 应用剪贴板中粘贴的 Cookie 头；[text] 为空或非法时只回事件，不改状态。 */
    fun importPastedCookie(text: String?) {
        if (text.isNullOrBlank()) {
            emit(ListDownloadEvent.CookiePasted(CookiePasteResult.CLIPBOARD_EMPTY))
            return
        }
        val ok = DouyinCookieSync.applyPastedCookieHeader(text)
        if (ok) refreshCookieStatus()
        emit(
            ListDownloadEvent.CookiePasted(
                if (ok) CookiePasteResult.OK else CookiePasteResult.INVALID,
            ),
        )
    }

    // -----------------------------------------------------------------------
    // 选中状态
    // -----------------------------------------------------------------------

    fun toggleSelection(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        if (item.isDownloaded) return
        if (id in selectedIds) {
            // 取消勾选时顺手清掉子选择：下次再勾选回到「默认全选」，不残留上一次的选图
            selectedIds.remove(id)
            imageSelections.remove(id)
            publish()
            return
        }
        selectedIds.add(id)
        publish()
        // 勾选图集时弹出选图弹窗（默认全选）
        if (item.isPhoto && item.imageUrls.isNotEmpty()) {
            emit(
                ListDownloadEvent.ShowPhotoSelection(
                    id = id,
                    imageUrls = item.imageUrls,
                    initialSelection = imageSelections[id],
                    editable = true,
                ),
            )
        }
    }

    /** 全选 / 取消全选当前列表中所有未下载项。 */
    fun toggleSelectAll() {
        if (items.isEmpty()) {
            emit(ListDownloadEvent.SelectAllRejected)
            return
        }
        val selectable = items.filter { !it.isDownloaded }
        if (selectable.isEmpty()) {
            emit(ListDownloadEvent.SelectAllRejected)
            return
        }
        val allSelected = selectable.all { it.id in selectedIds }
        if (allSelected) {
            selectable.forEach { selectedIds.remove(it.id); imageSelections.remove(it.id) }
        } else {
            selectable.forEach { selectedIds.add(it.id) }
        }
        publish()
    }

    /** 请求打开图集选图弹窗（预览入口用；[editable] 为 false 时纯看图，关闭不改动选中）。 */
    fun requestPhotoSelection(id: String, editable: Boolean) {
        val item = items.firstOrNull { it.id == id } ?: return
        if (item.imageUrls.isEmpty()) return
        emit(
            ListDownloadEvent.ShowPhotoSelection(
                id = id,
                imageUrls = item.imageUrls,
                initialSelection = imageSelections[id],
                editable = editable,
            ),
        )
    }

    /**
     * 选图弹窗关闭后回写：`null` = 全选，空集合 = 一张都没选（视为放弃这条，连勾选一起取消）。
     */
    fun applyPhotoSelection(id: String, result: Set<Int>?) {
        if (items.none { it.id == id }) return
        when {
            result == null -> {
                imageSelections.remove(id)
                selectedIds.add(id)
            }
            result.isEmpty() -> {
                imageSelections.remove(id)
                selectedIds.remove(id)
                emit(ListDownloadEvent.PhotoSelectionCleared)
            }
            else -> {
                imageSelections[id] = result
                selectedIds.add(id)
            }
        }
        publish()
    }

    fun setHideDownloaded(checked: Boolean) {
        if (hideDownloaded == checked) return
        hideDownloaded = checked
        if (listApiMode != ListApiMode.None) status = loadedStatus()
        publish()
        // 打开开关后当前页可能被过滤到没剩几条：列表撑不满屏 → 滚动分页不会触发 →
        // 看着像「什么都没加载出来」。这里主动续拉后面的页。
        if (checked && needsAutoFillMorePages()) {
            listLoadJob?.cancel()
            listLoadJob = viewModelScope.launch { fetchListPage(isFirstPage = false) }
        }
    }

    // -----------------------------------------------------------------------
    // 列表加载
    // -----------------------------------------------------------------------

    /**
     * 解析输入的 URL 并按 [kind] 决定加载哪个列表，或直接打开网页。
     *
     * @param rawUrl 地址栏原始输入（收藏夹模式下不使用）。
     */
    fun parseAndLoad(rawUrl: String?, kind: ListKindChoice) {
        if (kind == ListKindChoice.CollectsFolder) {
            if (DouyinApiClient.globalCookie.isNullOrBlank()) {
                emit(ListDownloadEvent.NeedLoginForCollection)
                return
            }
            listLoadJob?.cancel()
            listLoadJob = viewModelScope.launch { runCollectsFolderPickFlow() }
            return
        }
        val raw = rawUrl?.trim()
        if (raw.isNullOrBlank()) {
            emit(ListDownloadEvent.UrlInputEmpty)
            return
        }
        listLoadJob?.cancel()
        listLoadJob = viewModelScope.launch {
            val parsed = DouyinUrlParser.parse(raw)
            when (parsed.kind) {
                DouyinPageKind.USER -> {
                    val sid = parsed.secUserId?.takeIf { it.isNotBlank() }
                    if (sid == null) {
                        emit(ListDownloadEvent.NeedUserOrMix)
                        return@launch
                    }
                    when (kind) {
                        ListKindChoice.Like -> {
                            resetListBatchState(ListApiMode.UserLike, secUserId = sid, mixId = null)
                            fetchListPage(isFirstPage = true)
                        }
                        ListKindChoice.Collection -> {
                            if (DouyinApiClient.globalCookie.isNullOrBlank()) {
                                emit(ListDownloadEvent.NeedLoginForCollection)
                                return@launch
                            }
                            resetListBatchState(ListApiMode.UserCollection, secUserId = null, mixId = null)
                            fetchListPage(isFirstPage = true)
                        }
                        else -> {
                            resetListBatchState(ListApiMode.UserPost, secUserId = sid, mixId = null)
                            fetchListPage(isFirstPage = true)
                        }
                    }
                }
                DouyinPageKind.MIX -> {
                    val mid = parsed.mixId?.takeIf { it.isNotBlank() }
                    if (mid == null) {
                        emit(ListDownloadEvent.NeedUserOrMix)
                        return@launch
                    }
                    resetListBatchState(ListApiMode.MixAweme, secUserId = null, mixId = mid)
                    fetchListPage(isFirstPage = true)
                }
                DouyinPageKind.VIDEO -> {
                    resetListBatchState(ListApiMode.None, null, null)
                    emit(ListDownloadEvent.OpenBrowser(raw))
                }
                DouyinPageKind.SHORT_UNRESOLVED -> emit(ListDownloadEvent.ShortLinkUnresolved)
                DouyinPageKind.UNKNOWN -> {
                    resetListBatchState(ListApiMode.None, null, null)
                    emit(ListDownloadEvent.OpenedAsPlainUrl(raw))
                }
            }
        }
    }

    fun loadNextListPage() {
        if (!canLoadMore()) return
        listLoadJob?.cancel()
        listLoadJob = viewModelScope.launch { fetchListPage(isFirstPage = false) }
    }

    /** 滚动分页的前置判定：Fragment 的滚动监听里先问这个，避免每次滚动都进协程。 */
    fun canLoadMore(): Boolean =
        listApiMode != ListApiMode.None && !listLoadingMore && listHasMore

    private suspend fun runCollectsFolderPickFlow() {
        status = ListStatus.CollectsLoading
        publish()
        val folders = DouyinListApi.fetchAllCollectsFolders().getOrElse { e ->
            handleListLoadError(e) { runCollectsFolderPickFlow() }
            return
        }
        if (folders.isEmpty()) {
            status = ListStatus.CollectsEmpty
            publish()
            emit(ListDownloadEvent.CollectsFolderEmpty)
            return
        }
        emit(ListDownloadEvent.ShowCollectsFolderPicker(folders))
    }

    /** 用户在收藏夹选择弹窗里选定某个收藏夹。 */
    fun onCollectsFolderPicked(folder: DouyinCollectsFolderRow) {
        resetListBatchState(
            ListApiMode.CollectsVideo,
            secUserId = null,
            mixId = null,
            collectsId = folder.id,
        )
        listCollectsName = folder.name.ifBlank { folder.id }
        listLoadJob?.cancel()
        listLoadJob = viewModelScope.launch { fetchListPage(isFirstPage = true) }
    }

    private fun resetListBatchState(
        mode: ListApiMode,
        secUserId: String?,
        mixId: String?,
        collectsId: String? = null,
    ) {
        listApiMode = mode
        listSecUserId = secUserId
        listMixId = mixId
        listCollectsId = collectsId
        listCollectsName = ""
        listNextCursor = 0L
        listHasMore = false
        items.clear()
        selectedIds.clear()
        imageSelections.clear()
        if (mode == ListApiMode.None) status = ListStatus.Idle
        publish()
    }

    /**
     * 拉取列表页。开启「隐藏已下载」时，一页里可能整页都是已下载项，过滤后可见项为 0 —— 此时
     * 列表撑不满一屏，滚动分页永远不会被触发，页面看上去就是「加载了但什么都没有」。
     * 这里在单页加载完成后自动续拉，直到可见项够撑起一屏、没有更多数据，或达到 [AUTO_FILL_MAX_PAGES] 上限。
     */
    private suspend fun fetchListPage(isFirstPage: Boolean) {
        if (!fetchListPageOnce(isFirstPage)) return
        var extraPages = 0
        while (needsAutoFillMorePages() && extraPages < AUTO_FILL_MAX_PAGES) {
            extraPages++
            if (!fetchListPageOnce(isFirstPage = false)) return
        }
    }

    /** 隐藏已下载后可见项不足一屏且还有下一页时，需要自动多拉几页。 */
    private fun needsAutoFillMorePages(): Boolean =
        hideDownloaded &&
            listApiMode != ListApiMode.None &&
            listHasMore &&
            visibleItems().size < AUTO_FILL_MIN_VISIBLE

    /** 拉取单页；返回 false 表示未加载（重入/参数缺失）或加载失败，调用方不应继续续拉。 */
    private suspend fun fetchListPageOnce(isFirstPage: Boolean): Boolean {
        if (listLoadingMore) return false
        listLoadingMore = true
        var ok = false
        try {
            status = ListStatus.Loading
            publish()
            val result = when (listApiMode) {
                ListApiMode.UserPost -> {
                    val sid = listSecUserId ?: return false
                    DouyinListApi.fetchUserPostPage(
                        secUserId = sid,
                        maxCursor = cursorFor(isFirstPage),
                    )
                }
                ListApiMode.UserLike -> {
                    val sid = listSecUserId ?: return false
                    DouyinListApi.fetchUserLikePage(
                        secUserId = sid,
                        maxCursor = cursorFor(isFirstPage),
                    )
                }
                ListApiMode.UserCollection ->
                    DouyinListApi.fetchUserCollectionPage(cursor = cursorFor(isFirstPage))
                ListApiMode.MixAweme -> {
                    val mid = listMixId ?: return false
                    DouyinListApi.fetchMixAwemePage(mixId = mid, cursor = cursorFor(isFirstPage))
                }
                ListApiMode.CollectsVideo -> {
                    val cid = listCollectsId ?: return false
                    DouyinListApi.fetchCollectsVideoPage(
                        collectsId = cid,
                        cursor = cursorFor(isFirstPage),
                    )
                }
                ListApiMode.None -> return false
            }
            result.fold(
                onSuccess = { page ->
                    val merged = if (isFirstPage) {
                        AwemeMapper.toGridItems(page.items)
                    } else {
                        mergeGridWithNewAweme(items, page.items)
                    }
                    items.clear()
                    items.addAll(merged)
                    listNextCursor = page.nextCursor
                    listHasMore = page.hasMore
                    reapplyDownloadedFlags()
                    status = loadedStatus()
                    publish()
                    ok = true
                },
                onFailure = { e ->
                    handleListLoadError(e) { fetchListPage(isFirstPage) }
                },
            )
        } finally {
            listLoadingMore = false
        }
        return ok
    }

    private fun cursorFor(isFirstPage: Boolean): Long = if (isFirstPage) 0L else listNextCursor

    private fun mergeGridWithNewAweme(
        existing: List<VideoItemUiModel>,
        newItems: List<AwemeItem>,
    ): List<VideoItemUiModel> {
        val existingIds = existing.map { it.id }.toSet()
        val newUi = AwemeMapper.toGridItems(newItems).filter { it.id !in existingIds }
        return existing + newUi
    }

    /**
     * 列表加载失败统一处理：登录态失效（[DouyinAuthException]）弹「重新登录 / 同步 Cookie」引导，
     * 其余错误照旧提示。[retry] 为该次加载动作，登录/同步后可自动重试。
     */
    private fun handleListLoadError(e: Throwable, retry: (suspend () -> Unit)?) {
        status = ListStatus.Error(e.message)
        publish()
        if (e is DouyinAuthException) {
            pendingRetryAfterLogin = retry
            emit(ListDownloadEvent.ShowSessionExpiredDialog)
        } else {
            emit(ListDownloadEvent.ListLoadFailed(e.message))
        }
    }

    /** 失效弹窗里选了「去登录」：记住待重试动作，跳 WebView，回到本页时由 [onScreenResumed] 续跑。 */
    fun onSessionExpiredLoginChosen() {
        awaitingLoginRetry = true
        emit(ListDownloadEvent.OpenBrowserForLogin)
    }

    /** 失效弹窗里选了「同步 Cookie」：同步后若已登录就地重试。 */
    fun onSessionExpiredSyncChosen() {
        syncCookieFromCookieManager()
        if (!DouyinCookieSync.cookieTokenSnapshot(DouyinApiClient.globalCookie).hasLoginSession) return
        val retry = pendingRetryAfterLogin ?: return
        pendingRetryAfterLogin = null
        listLoadJob?.cancel()
        listLoadJob = viewModelScope.launch { retry() }
    }

    /** 失效弹窗被取消：丢弃待重试动作，避免下次登录返回时跑一个用户已经放弃的加载。 */
    fun onSessionExpiredDismissed() {
        pendingRetryAfterLogin = null
        awaitingLoginRetry = false
    }

    // -----------------------------------------------------------------------
    // 已下载标记
    // -----------------------------------------------------------------------

    private suspend fun reapplyDownloadedFlags() {
        if (items.isEmpty()) return
        val ids = items.map { it.id }
        val downloaded = withContext(Dispatchers.IO) {
            downloadedRepo.getDownloadedAwemeIdSet(ids)
        }
        for (i in items.indices) {
            val v = items[i]
            val isDl = v.id in downloaded
            if (v.isDownloaded != isDl) items[i] = v.copy(isDownloaded = isDl)
            if (isDl) {
                selectedIds.remove(v.id)
                imageSelections.remove(v.id)
            }
        }
        if (listApiMode != ListApiMode.None && status !is ListStatus.Loading) status = loadedStatus()
        publish()
    }

    /**
     * 把刚下载完成的作品在当前列表里标记为已下载并取消勾选。
     * 与当前列表没有交集（页面期间已经换成别的接口数据）时直接返回，不做任何刷新。
     */
    private fun markItemsDownloaded(awemeIds: Set<String>) {
        if (items.isEmpty() || awemeIds.isEmpty()) return
        var changed = false
        for (i in items.indices) {
            val v = items[i]
            if (v.id !in awemeIds) continue
            if (!v.isDownloaded) {
                items[i] = v.copy(isDownloaded = true)
                changed = true
            }
            if (selectedIds.remove(v.id)) changed = true
            imageSelections.remove(v.id)
        }
        if (!changed) return
        if (listApiMode != ListApiMode.None) status = loadedStatus()
        publish()
        // 隐藏已下载时刚下完的项会立刻消失，可见项可能不够一屏 —— 沿用与加载时相同的续拉策略。
        if (needsAutoFillMorePages()) {
            listLoadJob?.cancel()
            listLoadJob = viewModelScope.launch { fetchListPage(isFirstPage = false) }
        }
    }

    // -----------------------------------------------------------------------
    // 批量下载
    // -----------------------------------------------------------------------

    /**
     * 提交批量下载：预先算好每项的入库元数据，交给 [DownloadService] 在前台服务里执行。
     * 下载与写库都在服务内完成，离开本页 / 应用退到后台也不会中断，进度见通知栏。
     */
    fun startBatchDownload() {
        val selected = items.filter { it.id in selectedIds }.map(::compose)
        if (selected.isEmpty()) {
            emit(ListDownloadEvent.NothingSelected)
            return
        }
        // 图集按用户在选图弹窗里的勾选结果算（downloadImageUrls），没选图的图集不算可下载
        val downloadable = selected.filter {
            !it.downloadUrl.isNullOrBlank() || it.downloadImageUrls.isNotEmpty()
        }
        if (downloadable.isEmpty()) {
            emit(ListDownloadEvent.NoPlayUrl)
            return
        }

        // Android 13+：通知权限用于展示前台进度，缺失也不阻断下载（服务照常运行）。
        emit(ListDownloadEvent.RequestNotificationPermission)

        val sourceType = listSourceTypeForCurrentMode()
        val isCollects = listApiMode == ListApiMode.CollectsVideo
        val folderName = if (isCollects) listCollectsName else ""
        val folderId = if (isCollects) listCollectsId.orEmpty() else ""
        val ownerSecUserId = when (listApiMode) {
            ListApiMode.UserPost -> listSecUserId.orEmpty()
            else -> AppConfig.MY_SEC_USER_ID
        }
        val metas = downloadable.associate { item ->
            val userRelation = when (listApiMode) {
                ListApiMode.UserLike ->
                    DownloadedVideoRepository.buildUserRelationFromLike(item.collectStat)
                ListApiMode.UserCollection ->
                    DownloadedVideoRepository.buildUserRelationFromCollection(item.userDigged, "collect")
                ListApiMode.CollectsVideo ->
                    DownloadedVideoRepository.buildUserRelationFromCollection(item.userDigged, folderName)
                else -> ""
            }
            item.id to DownloadRecordMeta(
                downloadType = sourceType,
                userName = item.authorNickname,
                mediaType = if (item.isPhoto) DownloadMediaType.IMAGE else DownloadMediaType.VIDEO,
                createTime = item.createTime,
                desc = item.descRaw,
                collectionType = folderName,
                collectId = folderId,
                videoAuthorSecUserId = item.authorSecUserId,
                sourceOwnerSecUserId = ownerSecUserId,
                userRelation = userRelation,
                diggCount = item.diggCount,
                collectCount = item.collectCount,
                linkCollectFolderTag = isCollects,
            )
        }

        DownloadService.start(
            getApplication<Application>().applicationContext,
            DownloadJob(items = downloadable, metas = metas),
        )
        status = ListStatus.Enqueued(downloadable.size)
        publish()
        emit(ListDownloadEvent.BatchDownloadEnqueued(downloadable.size))
    }

    private fun listSourceTypeForCurrentMode(): String = when (listApiMode) {
        ListApiMode.UserPost -> DownloadSourceType.POST
        ListApiMode.UserLike -> DownloadSourceType.LIKE
        ListApiMode.UserCollection -> DownloadSourceType.COLLECT
        ListApiMode.MixAweme -> DownloadSourceType.MIX
        ListApiMode.CollectsVideo -> DownloadSourceType.COLLECTS
        ListApiMode.None -> DownloadSourceType.POST
    }

    // -----------------------------------------------------------------------
    // 「查看 TA 的 Post」与预览
    // -----------------------------------------------------------------------

    fun onAuthorPostsClicked(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        val secUid = item.authorSecUserId.takeIf { it.isNotBlank() }
        if (secUid == null) {
            emit(ListDownloadEvent.AuthorIdMissing)
            return
        }
        authorPostsMode = true
        status = ListStatus.AuthorPostsMode(item.authorNickname)
        publish()
        emit(
            ListDownloadEvent.EnterAuthorPostsMode(
                authorUrl = "https://www.douyin.com/user/$secUid?from_tab_name=main",
                nickname = item.authorNickname,
            ),
        )
    }

    fun exitAuthorPostsMode() {
        authorPostsMode = false
        resetListBatchState(ListApiMode.None, null, null)
    }

    /**
     * 预览某一项。
     * - 图集：打开选图弹窗（已勾选且未下载时可改选图，否则纯预览）。
     * - 视频：把列表中所有可预览的视频一并交给播放页，支持上下滑动切换。
     */
    fun onPreviewClicked(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        if (item.isPhoto) {
            if (item.imageUrls.isEmpty()) {
                emit(ListDownloadEvent.NoPlayUrl)
                return
            }
            requestPhotoSelection(id, editable = id in selectedIds && !item.isDownloaded)
            return
        }
        val previewable = items.filter { !it.isPhoto && !it.downloadUrl.isNullOrBlank() }
        val position = previewable.indexOfFirst { it.id == id }
        if (position < 0 || item.downloadUrl.isNullOrBlank()) {
            emit(ListDownloadEvent.NoPreviewUrl)
            return
        }
        emit(
            ListDownloadEvent.OpenVideoPreview(
                urls = previewable.map { it.downloadUrl!! },
                nicknames = previewable.map { it.authorNickname },
                descriptions = previewable.map { it.descRaw.take(PREVIEW_SUBTITLE_MAX) },
                position = position,
            ),
        )
    }

    // -----------------------------------------------------------------------
    // 状态合成
    // -----------------------------------------------------------------------

    /** 按 [selectedIds] / [imageSelections] 把选中状态填进模型，供渲染与下载链路使用。 */
    private fun compose(item: VideoItemUiModel): VideoItemUiModel = item.copy(
        isSelected = item.id in selectedIds,
        selectedImageIndices = imageSelections[item.id],
    )

    /** 提交给 Adapter 展示的列表：按「隐藏已下载」开关过滤，源数据 [items] 不变。 */
    private fun visibleItems(): List<VideoItemUiModel> =
        items.filter { !hideDownloaded || !it.isDownloaded }.map(::compose)

    private fun loadedStatus(): ListStatus = ListStatus.Loaded(
        total = items.size,
        hasMore = listHasMore,
        hidden = if (hideDownloaded) items.count { it.isDownloaded } else 0,
    )

    private fun publish() {
        val visible = visibleItems()
        val selectedCount = items.count { it.id in selectedIds }
        _uiState.value = ListDownloadUiState(
            visibleItems = visible,
            totalCount = items.size,
            selectedCount = selectedCount,
            hiddenCount = if (hideDownloaded) items.count { it.isDownloaded } else 0,
            hideDownloaded = hideDownloaded,
            isAuthorPostsMode = authorPostsMode,
            isUserPostMode = listApiMode == ListApiMode.UserPost,
            canDownload = selectedCount > 0,
            status = status,
        )
    }

    private fun emit(event: ListDownloadEvent) {
        _events.tryEmit(event)
    }

    companion object {
        /** 「隐藏已下载」时，可见项少于这个数就自动续拉下一页（3 列网格约一屏）。 */
        private const val AUTO_FILL_MIN_VISIBLE = 9

        /** 单次加载最多为「隐藏已下载」自动续拉的额外页数，防止整个列表都已下载时无限翻页。 */
        private const val AUTO_FILL_MAX_PAGES = 5

        /** 传给播放页的副标题最长字数。 */
        private const val PREVIEW_SUBTITLE_MAX = 60
    }
}

/** 当前列表走的是哪个接口。 */
enum class ListApiMode { None, UserPost, UserLike, UserCollection, CollectsVideo, MixAweme }

/** 界面上「下载哪个列表」的选择（对应 RadioGroup，避免把 `R.id` 传进 ViewModel）。 */
enum class ListKindChoice { Post, Like, Collection, CollectsFolder }

/** 状态栏文案的结构化表示；具体字符串由 Fragment 用 `R.string` 渲染。 */
sealed interface ListStatus {
    data object Idle : ListStatus
    data object Loading : ListStatus
    data class Loaded(val total: Int, val hasMore: Boolean, val hidden: Int) : ListStatus
    data class Error(val message: String?) : ListStatus
    data class AuthorPostsMode(val nickname: String) : ListStatus
    data object CollectsLoading : ListStatus
    data object CollectsEmpty : ListStatus
    data class Enqueued(val count: Int) : ListStatus
}

data class ListDownloadUiState(
    val visibleItems: List<VideoItemUiModel> = emptyList(),
    val totalCount: Int = 0,
    val selectedCount: Int = 0,
    val hiddenCount: Int = 0,
    val hideDownloaded: Boolean = false,
    /** 「查看 TA 的 Post」模式：锁定 Post 选项、显示返回按钮。 */
    val isAuthorPostsMode: Boolean = false,
    val isUserPostMode: Boolean = false,
    val canDownload: Boolean = false,
    val status: ListStatus = ListStatus.Idle,
)

data class CookieStatusUi(
    val hasCookie: Boolean = false,
    val snapshot: DouyinCookieSync.CookieTokenSnapshot? = null,
)

enum class CookieSyncResult { EMPTY, WEB_EMPTY, OK_WITH_LOGIN, OK_NO_LOGIN }

enum class CookiePasteResult { CLIPBOARD_EMPTY, OK, INVALID }

/** ViewModel 主动发起的一次性事件（导航 / 弹窗 / 提示）。 */
sealed interface ListDownloadEvent {
    data class CookieSynced(val result: CookieSyncResult) : ListDownloadEvent
    data class CookiePasted(val result: CookiePasteResult) : ListDownloadEvent
    data class ShowPhotoSelection(
        val id: String,
        val imageUrls: List<String>,
        val initialSelection: Set<Int>?,
        val editable: Boolean,
    ) : ListDownloadEvent
    data object PhotoSelectionCleared : ListDownloadEvent
    data class ShowCollectsFolderPicker(val folders: List<DouyinCollectsFolderRow>) : ListDownloadEvent
    data object CollectsFolderEmpty : ListDownloadEvent
    data object ShowSessionExpiredDialog : ListDownloadEvent
    data object OpenBrowserForLogin : ListDownloadEvent
    data class OpenBrowser(val url: String?) : ListDownloadEvent
    data class OpenedAsPlainUrl(val url: String?) : ListDownloadEvent
    data class EnterAuthorPostsMode(val authorUrl: String, val nickname: String) : ListDownloadEvent
    data class OpenVideoPreview(
        val urls: List<String>,
        val nicknames: List<String>,
        val descriptions: List<String>,
        val position: Int,
    ) : ListDownloadEvent
    data object RequestNotificationPermission : ListDownloadEvent
    data class BatchDownloadEnqueued(val count: Int) : ListDownloadEvent
    data class ListLoadFailed(val message: String?) : ListDownloadEvent
    data object NothingSelected : ListDownloadEvent
    data object NoPlayUrl : ListDownloadEvent
    data object NoPreviewUrl : ListDownloadEvent
    data object SelectAllRejected : ListDownloadEvent
    data object NeedLoginForCollection : ListDownloadEvent
    data object NeedUserOrMix : ListDownloadEvent
    data object UrlInputEmpty : ListDownloadEvent
    data object ShortLinkUnresolved : ListDownloadEvent
    data object AuthorIdMissing : ListDownloadEvent
}
