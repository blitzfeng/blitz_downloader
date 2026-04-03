package com.blitz.downloader.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.R
import com.blitz.downloader.activity.DouyinWebBrowserActivity
import com.blitz.downloader.config.AppConfig
import com.blitz.downloader.data.DownloadMediaType
import com.blitz.downloader.data.DownloadSourceType
import com.blitz.downloader.data.DownloadedVideoRepository
import com.blitz.downloader.data.VideoTagRepository
import com.blitz.downloader.api.AwemeItem
import com.blitz.downloader.api.AwemeMapper
import com.blitz.downloader.api.DouyinApiClient
import com.blitz.downloader.api.DouyinCollectsFolderRow
import com.blitz.downloader.api.DouyinListApi
import com.blitz.downloader.api.DouyinPageKind
import com.blitz.downloader.api.DouyinUrlParser
import com.blitz.downloader.databinding.FragmentListDownloadBinding
import com.blitz.downloader.download.BatchDownloadCoordinator
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

        refreshCookieStatusUi()
        BatchDownloadCoordinator.createNoMediaFile(File(BatchDownloadCoordinator.COVER_SUBDIR))

        binding.rvVideos.layoutManager = GridLayoutManager(requireContext(), 3)
        videoAdapter = VideoGridAdapter(emptyList()) { item ->
            toggleSelection(item.id)
        }
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

        binding.btnSyncCookie.setOnClickListener {
            syncCookieFromCookieManager()
        }

        binding.btnPasteCookie.setOnClickListener {
            importCookieFromClipboard()
        }

        binding.btnOpenDouyinBrowser.setOnClickListener {
            startDouyinBrowser(initialUrlFromInput())
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
            videoAdapter.submitList(videoItems.toList())
            updateSelectedCountText()
        }

        binding.btnDownloadSelected.setOnClickListener {
            val selected = videoItems.filter { it.isSelected }
            if (selected.isEmpty()) {
                Toast.makeText(requireContext(), R.string.batch_download_none_selected, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selected.all { it.downloadUrl.isNullOrBlank() && it.imageUrls.isEmpty() }) {
                Toast.makeText(requireContext(), R.string.batch_download_no_play_url, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            batchDownloadJob?.cancel()
            batchDownloadJob = viewLifecycleOwner.lifecycleScope.launch {
                val n = selected.count { !it.downloadUrl.isNullOrBlank() || it.imageUrls.isNotEmpty() }
                binding.tvStatus.text = getString(R.string.batch_download_running, n)
                val appCtx = requireContext().applicationContext
                val result = BatchDownloadCoordinator.downloadSelected(requireContext(), videoItems.toList())
                val sourceType = listSourceTypeForCurrentMode()
                withContext(Dispatchers.IO) {
                    val isCollects = listApiMode == ListApiMode.CollectsVideo
                    val folderName = if (isCollects) listCollectsName else ""
                    val folderId = if (isCollects) listCollectsId.orEmpty() else ""
                    val ownerSecUserId = when (listApiMode) {
                        ListApiMode.UserPost -> listSecUserId.orEmpty()
                        else -> AppConfig.MY_SEC_USER_ID
                    }
                    val tagRepo = VideoTagRepository(appCtx)
                    result.succeededItems.forEach { item ->
                        val userRelation = when (listApiMode) {
                            ListApiMode.UserLike ->
                                DownloadedVideoRepository.buildUserRelationFromLike(item.collectStat)
                            ListApiMode.UserCollection ->
                                DownloadedVideoRepository.buildUserRelationFromCollection(item.userDigged, "collect")
                            ListApiMode.CollectsVideo ->
                                DownloadedVideoRepository.buildUserRelationFromCollection(item.userDigged, folderName)
                            else -> ""
                        }
                        downloadedRepo.recordSuccessfulDownload(
                            awemeId = item.id,
                            downloadType = sourceType,
                            userName = item.authorNickname,
                            mediaType = if (item.isPhoto) DownloadMediaType.IMAGE else DownloadMediaType.VIDEO,
                            filePath = result.succeededPaths[item.id].orEmpty(),
                            coverPath = result.succeededCovers[item.id].orEmpty(),
                            desc = item.descRaw,
                            collectionType = folderName,
                            collectId = folderId,
                            videoAuthorSecUserId = item.authorSecUserId,
                            sourceOwnerSecUserId = ownerSecUserId,
                            userRelation = userRelation,
                        )
                        if (isCollects) {
                            tagRepo.ensureCollectFolderTagLinked(awemeId = item.id, folderName = folderName)
                        }
                    }
                }
                reapplyDownloadedFlagsToList()
                val line = getString(R.string.batch_download_done, result.success, result.failed)
                binding.tvStatus.text = line
                Toast.makeText(requireContext(), line, Toast.LENGTH_LONG).show()
            }
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
            videoAdapter.submitList(videoItems.toList())
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
        videoAdapter.submitList(videoItems.toList())
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
                Toast.makeText(
                    requireContext(),
                    getString(R.string.list_api_error, e.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
                refreshListStatusError(e.message)
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
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.list_api_error, e.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                    refreshListStatusError(e.message)
                },
            )
        } finally {
            listLoadingMore = false
        }
    }

    private fun loadNextListPage() {
        if (listApiMode == ListApiMode.None || listLoadingMore || !listHasMore) return
        listLoadJob?.cancel()
        listLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            fetchListPage(isFirstPage = false)
        }
    }

}
