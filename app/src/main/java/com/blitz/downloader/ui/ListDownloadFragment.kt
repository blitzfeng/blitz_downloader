package com.blitz.downloader.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.R
import com.blitz.downloader.activity.DouyinWebBrowserActivity
import com.blitz.downloader.activity.VideoPlayerActivity
import com.blitz.downloader.config.AppConfig
import com.blitz.downloader.data.DownloadMediaType
import com.blitz.downloader.data.DownloadSourceType
import com.blitz.downloader.data.DownloadedVideoRepository
import com.blitz.downloader.data.VideoTagRepository
import com.blitz.downloader.api.AwemeItem
import com.blitz.downloader.api.AwemeMapper
import com.blitz.downloader.api.DouyinApiClient
import com.blitz.downloader.api.DouyinAuthException
import com.blitz.downloader.api.DouyinCollectsFolderRow
import com.blitz.downloader.api.DouyinListApi
import com.blitz.downloader.api.DouyinPageKind
import com.blitz.downloader.api.DouyinUrlParser
import com.blitz.downloader.databinding.FragmentListDownloadBinding
import com.blitz.downloader.download.BatchDownloadCoordinator
import com.blitz.downloader.download.DownloadJob
import com.blitz.downloader.download.DownloadRecordMeta
import com.blitz.downloader.download.DownloadService
import com.blitz.downloader.util.DouyinCookieSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ListDownloadFragment : Fragment() {


    val myUserId = "MS4wLjABAAAA7ZinArXxNJlWd2iiRKUI3ruz4TwjqKN5F7iqF5nGKIAgCTDtscTfMCQMor1Fn9vr"
    private var _binding: FragmentListDownloadBinding? = null
    private val binding get() = _binding!!
    private lateinit var videoAdapter: VideoGridAdapter
    private val videoItems = mutableListOf<VideoItemUiModel>()

    private var listLoadJob: Job? = null
    private var batchDownloadJob: Job? = null
    private var showFabRunnable: Runnable? = null

    /** Android 13+ 通知权限申请器：用于展示前台下载进度，拒绝也不阻断下载。 */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也照常下载，仅无通知 */ }

    /** 登录态失效弹窗中「去登录」后待重试的加载动作；登录返回且已登录时自动执行。 */
    private var pendingRetryAfterLogin: (suspend () -> Unit)? = null
    private var awaitingLoginRetry: Boolean = false

    /** 是否在列表中隐藏已下载项（仅影响展示，不影响 videoItems 源数据）。 */
    private var hideDownloaded: Boolean = false

    private enum class ListApiMode { None, UserPost, UserLike, UserCollection, CollectsVideo, MixAweme }

    private var listApiMode: ListApiMode = ListApiMode.None
    private var listSecUserId: String? = null
    private var listMixId: String? = null
    private var listCollectsId: String? = null
    /** 当前选中收藏夹的显示名称（[ListApiMode.CollectsVideo] 时有效），用于写入 DB。 */
    private var listCollectsName: String = ""
    private var listNextCursor: Long = 0L
    private var listHasMore: Boolean = false
    private var listLoadingMore: Boolean = false
    private var selectedUserId = myUserId
    val indexMainPage = "https://www.douyin.com/user/${selectedUserId}?from_tab_name=main"

    private val downloadedRepo: DownloadedVideoRepository
        get() = (requireContext().applicationContext as BlitzApp).downloadedVideoRepository

    override fun onCreateView(
        layoutInflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentListDownloadBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.etUrlInput.setText(DouyinWebBrowserActivity.DOUYIN_DEFAULT_HOME_URL)
        refreshCookieStatusUi()
        BatchDownloadCoordinator.createNoMediaFile(File(BatchDownloadCoordinator.COVER_SUBDIR))

        binding.rvVideos.layoutManager = GridLayoutManager(requireContext(), 3)
        videoAdapter = VideoGridAdapter(
            items = emptyList(),
            onItemClicked = { item -> toggleSelection(item.id) },
            onAuthorPostsClicked = { item -> onAuthorPostsClicked(item) },
            onPreviewClicked = { item -> onPreviewClicked(item) },
        )
        binding.rvVideos.adapter = videoAdapter

        val thresholdPx = (200 * resources.displayMetrics.density).toInt()
        binding.nestedScrollView.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { v, _, scrollY, _, _ ->
                // FAB：滚动时立即隐藏；滚回顶部后延迟 300ms 确认停止再显示
                showFabRunnable?.let { binding.root.removeCallbacks(it) }
                if (scrollY > 0) {
                    binding.fabParse.hide()
                } else {
                    val r = Runnable { binding.fabParse.show() }
                    showFabRunnable = r
                    binding.root.postDelayed(r, 300)
                }
                // 加载下一页
                if (listApiMode == ListApiMode.None || listLoadingMore || !listHasMore) return@OnScrollChangeListener
                val diff = v.getChildAt(0).measuredHeight - v.measuredHeight - scrollY
                if (diff <= thresholdPx) {
                    loadNextListPage()
                }
            },
        )

        binding.fabParse.setOnClickListener {
            parseAndOpenOrLoadList()
        }

        binding.btnBackToMyPage.setOnClickListener {
            exitAuthorPostsMode()
        }

        binding.btnSyncCookie.setOnClickListener {
            syncCookieFromCookieManager()
        }

        binding.btnPasteCookie.setOnClickListener {
            importCookieFromClipboard()
        }

        binding.btnOpenDouyinBrowser.setOnClickListener {
            startDouyinBrowser(initialUrlFromInput())
        }

        binding.btnBackToMyPage.setOnClickListener {
            exitAuthorPostsMode()
        }

        binding.btnSelectAll.setOnClickListener {
            if (videoItems.isEmpty()) {
                Toast.makeText(requireContext(), R.string.batch_select_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selectable = videoItems.filter { !it.isDownloaded }
            if (selectable.isEmpty()) {
                Toast.makeText(requireContext(), R.string.batch_select_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val allSelected = selectable.all { it.isSelected }
            val newSelect = !allSelected
            for (i in videoItems.indices) {
                val item = videoItems[i]
                if (item.isDownloaded) continue
                videoItems[i] = item.copy(isSelected = newSelect)
            }
            videoAdapter.submitList(currentDisplayList())
            updateSelectedCountText()
        }

        binding.cbHideDownloaded.setOnCheckedChangeListener { _, checked ->
            hideDownloaded = checked
            videoAdapter.submitList(currentDisplayList())
        }

        binding.btnDownloadSelected.setOnClickListener {
            startBatchDownload()
        }
    }

    /** 提交给 Adapter 展示的列表：按「隐藏已下载」开关过滤，源数据 [videoItems] 不变。 */
    private fun currentDisplayList(): List<VideoItemUiModel> =
        if (hideDownloaded) videoItems.filter { !it.isDownloaded } else videoItems.toList()

    /**
     * 提交批量下载：预先算好每项的入库元数据，交给 [DownloadService] 在前台服务里执行。
     * 下载与写库都在服务内完成，离开本页 / 应用退到后台也不会中断，进度见通知栏。
     */
    private fun startBatchDownload() {
        val selected = videoItems.filter { it.isSelected }
        if (selected.isEmpty()) {
            Toast.makeText(requireContext(), R.string.batch_download_none_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val downloadable = selected.filter { !it.downloadUrl.isNullOrBlank() || it.imageUrls.isNotEmpty() }
        if (downloadable.isEmpty()) {
            Toast.makeText(requireContext(), R.string.batch_download_no_play_url, Toast.LENGTH_LONG).show()
            return
        }

        // Android 13+：通知权限用于展示前台进度，缺失也不阻断下载（服务照常运行）。
        requestNotificationPermissionIfNeeded()

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
            requireContext().applicationContext,
            DownloadJob(items = downloadable, metas = metas),
        )
        binding.tvStatus.text = getString(R.string.batch_download_enqueued, downloadable.size)
        Toast.makeText(requireContext(), R.string.batch_download_enqueued_toast, Toast.LENGTH_LONG).show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCookieStatusUi()
        if (videoItems.isNotEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch {
                reapplyDownloadedFlagsToList()
            }
        }
        // 从登录页返回：先把 WebView 里的最新 Cookie 同步进来，再判断是否已登录。
        if (awaitingLoginRetry) {
            DouyinCookieSync.syncFromCookieManager()
            refreshCookieStatusUi()
            val hasLogin = DouyinCookieSync.cookieTokenSnapshot(DouyinApiClient.globalCookie).hasLoginSession
            if (hasLogin) {
                awaitingLoginRetry = false
                val retry = pendingRetryAfterLogin
                pendingRetryAfterLogin = null
                if (retry != null) {
                    viewLifecycleOwner.lifecycleScope.launch { retry() }
                }
            }
        }
    }

    override fun onDestroyView() {
        showFabRunnable?.let { _binding?.root?.removeCallbacks(it) }
        showFabRunnable = null
        batchDownloadJob?.cancel()
        listLoadJob?.cancel()
        _binding = null
        super.onDestroyView()
    }

    private fun startDouyinBrowser(initialUrl: String?) {
        startActivity(DouyinWebBrowserActivity.createIntent(requireContext(), initialUrl))
    }

    private fun initialUrlFromInput(): String? {
        val raw = binding.etUrlInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    }

    private fun toggleSelection(id: String) {
        val index = videoItems.indexOfFirst { it.id == id }
        if (index != -1) {
            val current = videoItems[index]
            if (current.isDownloaded) return
            videoItems[index] = current.copy(isSelected = !current.isSelected)
            videoAdapter.submitList(currentDisplayList())
            updateSelectedCountText()
        }
    }

    private fun listSourceTypeForCurrentMode(): String = when (listApiMode) {
        ListApiMode.UserPost -> DownloadSourceType.POST
        ListApiMode.UserLike -> DownloadSourceType.LIKE
        ListApiMode.UserCollection -> DownloadSourceType.COLLECT
        ListApiMode.MixAweme -> DownloadSourceType.MIX
        ListApiMode.CollectsVideo -> DownloadSourceType.COLLECTS
        ListApiMode.None -> DownloadSourceType.POST
    }

    private suspend fun reapplyDownloadedFlagsToList() {
        if (videoItems.isEmpty()) return
        val ids = videoItems.map { it.id }
        val downloaded = withContext(Dispatchers.IO) {
            downloadedRepo.getDownloadedAwemeIdSet(ids)
        }
        for (i in videoItems.indices) {
            val v = videoItems[i]
            val isDl = v.id in downloaded
            videoItems[i] = v.copy(
                isDownloaded = isDl,
                isSelected = if (isDl) false else v.isSelected,
            )
        }
        videoAdapter.submitList(currentDisplayList())
        updateSelectedCountText()
    }

    private fun updateSelectedCountText() {
        val c = videoItems.count { it.isSelected }
        binding.btnDownloadSelected.isEnabled = c > 0
        binding.tvSelectedCount.text = "已选择 $c / ${videoItems.size}"
    }

    private fun refreshCookieStatusUi() {
        val line = DouyinApiClient.globalCookie
        if (line.isNullOrBlank()) {
            binding.tvCookieStatus.setText(R.string.cookie_status_none)
            return
        }
        val snap = DouyinCookieSync.cookieTokenSnapshot(line)
        val yn = { ok: Boolean ->
            getString(if (ok) R.string.token_status_yes else R.string.token_status_no)
        }
        binding.tvCookieStatus.text = buildString {
            append(getString(R.string.cookie_status_synced, snap.pairCount, snap.lineLength))
            append('\n')
            append(
                getString(
                    R.string.cookie_status_tokens,
                    yn(snap.hasMsToken),
                    yn(snap.hasWebId),
                    yn(snap.hasTtwid),
                    yn(snap.hasVerifyFp),
                    yn(snap.hasLoginSession),
                )
            )
        }
    }

    /** 从系统 Cookie（含独立「抖音网页」WebView）合并到内存并持久化。 */
    private fun syncCookieFromCookieManager() {
        val merged = DouyinCookieSync.syncFromCookieManager()
        refreshCookieStatusUi()
        val snap = DouyinCookieSync.cookieTokenSnapshot(DouyinApiClient.globalCookie)
        val ctx = requireContext()
        when {
            merged == null && DouyinApiClient.globalCookie.isNullOrBlank() ->
                Toast.makeText(ctx, R.string.toast_cookie_sync_empty, Toast.LENGTH_SHORT).show()
            merged == null && !DouyinApiClient.globalCookie.isNullOrBlank() ->
                Toast.makeText(ctx, R.string.toast_cookie_sync_web_empty, Toast.LENGTH_LONG).show()
            snap.hasLoginSession ->
                Toast.makeText(ctx, R.string.toast_cookie_synced_with_login, Toast.LENGTH_SHORT).show()
            else ->
                Toast.makeText(ctx, R.string.toast_cookie_synced_no_login, Toast.LENGTH_LONG).show()
        }
    }

    private fun importCookieFromClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) {
            Toast.makeText(requireContext(), "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }
        if (DouyinCookieSync.applyPastedCookieHeader(text)) {
            refreshCookieStatusUi()
            Toast.makeText(requireContext(), R.string.toast_cookie_paste_ok, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), R.string.toast_cookie_paste_invalid, Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseAndOpenOrLoadList() {
        if (binding.rgListKind.checkedRadioButtonId == R.id.rbKindCollectsFolder) {
            if (DouyinApiClient.globalCookie.isNullOrBlank()) {
                Toast.makeText(
                    requireContext(),
                    R.string.list_api_collection_need_login,
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            listLoadJob?.cancel()
            listLoadJob = viewLifecycleOwner.lifecycleScope.launch {
                runCollectsFolderPickFlow()
            }
            return
        }

        val raw = binding.etUrlInput.text?.toString()?.trim()
        if (raw.isNullOrBlank()) {
            Toast.makeText(requireContext(), "请输入URL", Toast.LENGTH_SHORT).show()
            return
        }
        listLoadJob?.cancel()
        listLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val parsed = DouyinUrlParser.parse(raw)
            when (parsed.kind) {
                DouyinPageKind.USER -> {
                    val sid = parsed.secUserId?.takeIf { it.isNotBlank() }
                    if (sid == null) {
                        Toast.makeText(requireContext(), R.string.list_api_need_user_or_mix, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    when (binding.rgListKind.checkedRadioButtonId) {
                        R.id.rbKindLike -> {
                            resetListBatchState(ListApiMode.UserLike, secUserId = sid, mixId = null)
                            fetchListPage(isFirstPage = true)
                        }
                        R.id.rbKindCollection -> {
                            if (DouyinApiClient.globalCookie.isNullOrBlank()) {
                                Toast.makeText(
                                    requireContext(),
                                    R.string.list_api_collection_need_login,
                                    Toast.LENGTH_LONG,
                                ).show()
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
                        Toast.makeText(requireContext(), R.string.list_api_need_user_or_mix, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    resetListBatchState(ListApiMode.MixAweme, secUserId = null, mixId = mid)
                    fetchListPage(isFirstPage = true)
                }
                DouyinPageKind.VIDEO -> {
                    resetListBatchState(ListApiMode.None, null, null)
                    startDouyinBrowser(initialUrlFromInput())
                }
                DouyinPageKind.SHORT_UNRESOLVED -> {
                    Toast.makeText(requireContext(), R.string.list_api_short_unresolved, Toast.LENGTH_LONG).show()
                }
                DouyinPageKind.UNKNOWN -> {
                    resetListBatchState(ListApiMode.None, null, null)
                    Toast.makeText(requireContext(), "已按普通链接打开网页", Toast.LENGTH_SHORT).show()
                    startDouyinBrowser(initialUrlFromInput())
                }
            }
        }
    }

    private suspend fun runCollectsFolderPickFlow() {
        binding.tvStatus.text = getString(R.string.collects_list_loading)
        val folders = DouyinListApi.fetchAllCollectsFolders().getOrElse { e ->
            withContext(Dispatchers.Main) {
                handleListLoadError(e) { runCollectsFolderPickFlow() }
            }
            return
        }
        if (folders.isEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), R.string.collects_list_empty, Toast.LENGTH_LONG).show()
                binding.tvStatus.text = getString(R.string.collects_list_empty)
            }
            return
        }
        showCollectsFolderDialog(folders)
    }

    private suspend fun showCollectsFolderDialog(folders: List<DouyinCollectsFolderRow>) {
        val labels = folders.map { row ->
            val name = row.name.ifBlank { row.id }
            "$name (${row.id})"
        }.toTypedArray()
        withContext(Dispatchers.Main) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.collects_pick_title)
                .setItems(labels) { _, which ->
                    val folder = folders[which]
                    resetListBatchState(ListApiMode.CollectsVideo, secUserId = null, mixId = null, collectsId = folder.id)
                    listCollectsName = folder.name.ifBlank { folder.id }
                    listLoadJob = viewLifecycleOwner.lifecycleScope.launch {
                        fetchListPage(isFirstPage = true)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
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
        videoItems.clear()
        videoAdapter.setUserPostMode(mode == ListApiMode.UserPost)
        videoAdapter.submitList(emptyList())
        updateSelectedCountText()
        if (mode == ListApiMode.None) {
            binding.tvStatus.setText(R.string.list_batch_scope_hint)
        }
    }

    private fun mergeGridWithNewAweme(
        existing: List<VideoItemUiModel>,
        newItems: List<AwemeItem>,
    ): List<VideoItemUiModel> {
        val existingIds = existing.map { it.id }.toSet()
        val newUi = AwemeMapper.toGridItems(newItems).filter { it.id !in existingIds }
        return existing + newUi
    }

    private fun refreshListStatusLoading() {
        binding.tvStatus.text = getString(R.string.list_api_loading)
    }

    private fun refreshListStatusAfterOk() {
        val tail = if (listHasMore) getString(R.string.list_api_has_more) else getString(R.string.list_api_no_more)
        binding.tvStatus.text = getString(R.string.list_api_status_loaded, videoItems.size, tail)
    }

    private fun refreshListStatusError(msg: String?) {
        binding.tvStatus.text = getString(R.string.list_api_error, msg ?: "")
    }

    private suspend fun fetchListPage(isFirstPage: Boolean) {
        if (listLoadingMore) return
        listLoadingMore = true
        try {
            refreshListStatusLoading()
            val result = when (listApiMode) {
                ListApiMode.UserPost -> {
                    val sid = listSecUserId ?: return
                    val cursor = if (isFirstPage) 0L else listNextCursor
                    DouyinListApi.fetchUserPostPage(secUserId = sid, maxCursor = cursor)
                }
                ListApiMode.UserLike -> {
                    val sid = listSecUserId ?: return
                    val maxC = if (isFirstPage) 0L else listNextCursor
                    DouyinListApi.fetchUserLikePage(secUserId = sid, maxCursor = maxC)
                }
                ListApiMode.UserCollection -> {
                    val cursor = if (isFirstPage) 0L else listNextCursor
                    DouyinListApi.fetchUserCollectionPage(cursor = cursor)
                }
                ListApiMode.MixAweme -> {
                    val mid = listMixId ?: return
                    val cursor = if (isFirstPage) 0L else listNextCursor
                    DouyinListApi.fetchMixAwemePage(mixId = mid, cursor = cursor)
                }
                ListApiMode.CollectsVideo -> {
                    val cid = listCollectsId ?: return
                    val cursor = if (isFirstPage) 0L else listNextCursor
                    DouyinListApi.fetchCollectsVideoPage(collectsId = cid, cursor = cursor)
                }
                ListApiMode.None -> return
            }
            result.fold(
                onSuccess = { page ->
                    val merged = if (isFirstPage) {
                        AwemeMapper.toGridItems(page.items)
                    } else {
                        mergeGridWithNewAweme(videoItems, page.items)
                    }
                    videoItems.clear()
                    videoItems.addAll(merged)
                    listNextCursor = page.nextCursor
                    listHasMore = page.hasMore
                    reapplyDownloadedFlagsToList()
                    refreshListStatusAfterOk()
                },
                onFailure = { e ->
                    handleListLoadError(e) { fetchListPage(isFirstPage) }
                },
            )
        } finally {
            listLoadingMore = false
        }
    }

    /**
     * 列表加载失败统一处理：登录态失效（[DouyinAuthException]）弹「重新登录 / 同步 Cookie」引导，
     * 其余错误照旧 Toast。[retry] 为该次加载动作，登录/同步后可自动重试。
     */
    private fun handleListLoadError(e: Throwable, retry: (suspend () -> Unit)?) {
        refreshListStatusError(e.message)
        if (e is DouyinAuthException) {
            showSessionExpiredDialog(retry)
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.list_api_error, e.message ?: ""),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun showSessionExpiredDialog(retry: (suspend () -> Unit)?) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.session_expired_title)
            .setMessage(R.string.session_expired_msg)
            .setPositiveButton(R.string.session_expired_login) { _, _ ->
                pendingRetryAfterLogin = retry
                awaitingLoginRetry = true
                startDouyinBrowser(DouyinWebBrowserActivity.DOUYIN_DEFAULT_HOME_URL)
            }
            .setNeutralButton(R.string.session_expired_sync) { _, _ ->
                syncCookieFromCookieManager()
                val hasLogin = DouyinCookieSync.cookieTokenSnapshot(DouyinApiClient.globalCookie).hasLoginSession
                if (hasLogin && retry != null) {
                    viewLifecycleOwner.lifecycleScope.launch { retry() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadNextListPage() {
        if (listApiMode == ListApiMode.None || listLoadingMore || !listHasMore) return
        listLoadJob?.cancel()
        listLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            fetchListPage(isFirstPage = false)
        }
    }

    // -----------------------------------------------------------------------
    // 查看 TA 的 Post 模式
    // -----------------------------------------------------------------------

    /**
     * 进入「查看 TA 的 Post」模式：
     * - 用作者的 secUserId 构造主页 URL 填入输入框
     * - 选中 Post RadioButton，其余不可选
     * - 显示「返回」按钮
     * - 自动触发列表加载
     */
    private fun onAuthorPostsClicked(item: VideoItemUiModel) {
        val secUid = item.authorSecUserId.takeIf { it.isNotBlank() } ?: run {
            Toast.makeText(requireContext(), "该作品没有作者ID，无法加载", Toast.LENGTH_SHORT).show()
            return
        }
        val authorUrl = "https://www.douyin.com/user/$secUid?from_tab_name=main"
        enterAuthorPostsMode(authorUrl, item.authorNickname)
    }

    private fun enterAuthorPostsMode(authorUrl: String, nickname: String) {
        // 填充 URL
        binding.etUrlInput.setText(authorUrl)
        binding.etUrlInput.clearFocus()

        // 强制选中 Post，其余禁用
        binding.rgListKind.check(R.id.rbKindPost)
        binding.rbKindLike.isEnabled = false
        binding.rbKindCollection.isEnabled = false
        binding.rbKindCollectsFolder.isEnabled = false

        // 显示返回按钮
        binding.btnBackToMyPage.visibility = View.VISIBLE

        // 状态栏提示
        binding.tvStatus.text = getString(R.string.author_posts_mode_hint, nickname)

        // 滚回顶部后触发解析加载
        binding.nestedScrollView.smoothScrollTo(0, 0)
        binding.nestedScrollView.post { parseAndOpenOrLoadList() }
    }

    /** 退出「查看 TA 的 Post」模式，恢复默认状态 */
    private fun exitAuthorPostsMode() {
        // 恢复输入框为自己的主页
        binding.etUrlInput.setText(indexMainPage)
        binding.etUrlInput.clearFocus()

        // 恢复所有 RadioButton 可选，重置为 Post
        binding.rbKindLike.isEnabled = true
        binding.rbKindCollection.isEnabled = true
        binding.rbKindCollectsFolder.isEnabled = true
        binding.rgListKind.check(R.id.rbKindPost)

        // 隐藏返回按钮
        binding.btnBackToMyPage.visibility = View.GONE

        // 清空列表与状态
        resetListBatchState(ListApiMode.None, null, null)
    }

    // -----------------------------------------------------------------------
    // 预览占位 —— 后续实现
    // -----------------------------------------------------------------------

    /**
     * 预览当前 item。
     * - 视频：将列表中所有可预览的视频一并传入播放器，支持上下滑动切换。
     * - 图集：TODO 后续打开 ImageViewerActivity 的网络 URL 模式。
     */
    private fun onPreviewClicked(item: VideoItemUiModel) {
        if (item.isPhoto) {
            Toast.makeText(requireContext(), "图集预览功能即将上线", Toast.LENGTH_SHORT).show()
            return
        }

        // 过滤出所有可预览的视频 item（非图集且有播放地址）
        val previewable = videoItems.filter { !it.isPhoto && !it.downloadUrl.isNullOrBlank() }
        val position = previewable.indexOfFirst { it.id == item.id }

        if (position < 0 || item.downloadUrl.isNullOrBlank()) {
            Toast.makeText(requireContext(), "该视频暂无可用播放地址", Toast.LENGTH_SHORT).show()
            return
        }

        startActivity(
            VideoPlayerActivity.createListNetworkIntent(
                context = requireContext(),
                urls = ArrayList(previewable.map { it.downloadUrl!! }),
                titles = ArrayList(previewable.map {
                    it.authorNickname.ifBlank { getString(R.string.video_player_title) }
                }),
                subtitles = ArrayList(previewable.map { it.descRaw.take(60) }),
                position = position,
            )
        )
    }

}
