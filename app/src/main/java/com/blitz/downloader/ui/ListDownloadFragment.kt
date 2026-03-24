package com.blitz.downloader.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blitz.downloader.R
import com.blitz.downloader.activity.DouyinWebBrowserActivity
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
import com.blitz.downloader.util.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ListDownloadFragment : Fragment() {

    private lateinit var binding: FragmentListDownloadBinding
    private lateinit var videoAdapter: VideoGridAdapter
    private lateinit var tvCookieStatus: TextView
    private var tvListStatus: TextView? = null
    private lateinit var tvSelectedCountView: TextView
    private val videoItems = mutableListOf<VideoItemUiModel>()

    private var listLoadJob: Job? = null
    private var batchDownloadJob: Job? = null

    private enum class ListApiMode { None, UserPost, UserLike, UserCollection, CollectsVideo, MixAweme }

    private var listApiMode: ListApiMode = ListApiMode.None
    private var listSecUserId: String? = null
    private var listMixId: String? = null
    private var listCollectsId: String? = null
    private var listNextCursor: Long = 0L
    private var listHasMore: Boolean = false
    private var listLoadingMore: Boolean = false

    override fun onCreateView(
        layoutInflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentListDownloadBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etUrl: EditText = view.findViewById(R.id.etUrlInput)
        val tvSelectedCount: TextView = view.findViewById(R.id.tvSelectedCount)
        tvSelectedCountView = tvSelectedCount
        tvCookieStatus = view.findViewById(R.id.tvCookieStatus)
        tvListStatus = view.findViewById(R.id.tvStatus)
        refreshCookieStatusUi()

        val recyclerView: RecyclerView = view.findViewById(R.id.rvVideos)
        recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)

        videoAdapter = VideoGridAdapter(emptyList()) { item ->
            toggleSelection(item.id)
        }
        recyclerView.adapter = videoAdapter

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy <= 0 || listApiMode == ListApiMode.None || listLoadingMore || !listHasMore) return
                val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                val last = lm.findLastVisibleItemPosition()
                val total = videoAdapter.itemCount
                if (last >= total - 5 && total > 0) {
                    loadNextListPage()
                }
            }
        })

        view.findViewById<Button>(R.id.btnPaste).setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            val text = clip?.getItemAt(0)?.text?.toString()
            val url = if (!text.isNullOrBlank()) UrlUtils.extractFirstUrl(text) else null
            if (url != null) {
                etUrl.setText(url)
                Toast.makeText(requireContext(), "已粘贴链接", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "剪贴板中没有链接", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<Button>(R.id.btnParse).setOnClickListener {
            parseAndOpenOrLoadList(etUrl)
        }

        view.findViewById<Button>(R.id.btnSyncCookie).setOnClickListener {
            syncCookieFromCookieManager()
        }

        view.findViewById<Button>(R.id.btnPasteCookie).setOnClickListener {
            importCookieFromClipboard()
        }

        view.findViewById<Button>(R.id.btnOpenDouyinBrowser).setOnClickListener {
            startDouyinBrowser(initialUrlFromInput(etUrl))
        }

        view.findViewById<Button>(R.id.btnSelectAll).setOnClickListener {
            if (videoItems.isEmpty()) {
                Toast.makeText(requireContext(), R.string.batch_select_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val allSelected = videoItems.all { it.isSelected }
            val newSelect = !allSelected
            for (i in videoItems.indices) {
                videoItems[i] = videoItems[i].copy(isSelected = newSelect)
            }
            videoAdapter.submitList(videoItems.toList())
            updateSelectedCountText()
        }

        view.findViewById<Button>(R.id.btnDownloadSelected).setOnClickListener {
            val selected = videoItems.filter { it.isSelected }
            if (selected.isEmpty()) {
                Toast.makeText(requireContext(), R.string.batch_download_none_selected, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selected.all { it.downloadUrl.isNullOrBlank() }) {
                Toast.makeText(requireContext(), R.string.batch_download_no_play_url, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            batchDownloadJob?.cancel()
            batchDownloadJob = viewLifecycleOwner.lifecycleScope.launch {
                val n = selected.count { !it.downloadUrl.isNullOrBlank() }
                tvListStatus?.text = getString(R.string.batch_download_running, n)
                val result = BatchDownloadCoordinator.downloadSelected(requireContext(), videoItems.toList())
                val line = getString(R.string.batch_download_done, result.success, result.failed)
                tvListStatus?.text = line
                Toast.makeText(requireContext(), line, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCookieStatusUi()
    }

    override fun onDestroyView() {
        batchDownloadJob?.cancel()
        listLoadJob?.cancel()
        super.onDestroyView()
    }

    private fun startDouyinBrowser(initialUrl: String?) {
        startActivity(DouyinWebBrowserActivity.createIntent(requireContext(), initialUrl))
    }

    private fun initialUrlFromInput(etUrl: EditText): String? {
        val raw = etUrl.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    }

    private fun toggleSelection(id: String) {
        val index = videoItems.indexOfFirst { it.id == id }
        if (index != -1) {
            val current = videoItems[index]
            videoItems[index] = current.copy(isSelected = !current.isSelected)
//            viewModel.selectedItemsSize.value = videoItems.count { it.isSelected }
            videoAdapter.submitList(videoItems.toList())
            updateSelectedCountText()
        }
    }

    private fun updateSelectedCountText() {
        val c = videoItems.count { it.isSelected }
        if (c == 0) {
            binding.btnDownloadSelected.isEnabled = false
        } else {
            binding.btnDownloadSelected.isEnabled = true
        }
        tvSelectedCountView.text = "已选择 $c / ${videoItems.size}"
    }

    private fun refreshCookieStatusUi() {
        val line = DouyinApiClient.globalCookie
        if (line.isNullOrBlank()) {
            tvCookieStatus.setText(R.string.cookie_status_none)
            return
        }
        val snap = DouyinCookieSync.cookieTokenSnapshot(line)
        val yn = { ok: Boolean ->
            getString(if (ok) R.string.token_status_yes else R.string.token_status_no)
        }
        tvCookieStatus.text = buildString {
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

    private fun parseAndOpenOrLoadList(etUrl: EditText) {
        val rg = view?.findViewById<RadioGroup>(R.id.rgListKind) ?: return
        if (rg.checkedRadioButtonId == R.id.rbKindCollectsFolder) {
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

        val raw = etUrl.text?.toString()?.trim()
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
                    when (view?.findViewById<RadioGroup>(R.id.rgListKind)?.checkedRadioButtonId) {
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
                    val initial = initialUrlFromInput(etUrl)
                    startDouyinBrowser(initial)
                }
                DouyinPageKind.SHORT_UNRESOLVED -> {
                    Toast.makeText(requireContext(), R.string.list_api_short_unresolved, Toast.LENGTH_LONG).show()
                }
                DouyinPageKind.UNKNOWN -> {
                    resetListBatchState(ListApiMode.None, null, null)
                    Toast.makeText(requireContext(), "已按普通链接打开网页", Toast.LENGTH_SHORT).show()
                    startDouyinBrowser(initialUrlFromInput(etUrl))
                }
            }
        }
    }

    private suspend fun runCollectsFolderPickFlow() {
        tvListStatus?.text = getString(R.string.collects_list_loading)
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
                tvListStatus?.text = getString(R.string.collects_list_empty)
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
                    val id = folders[which].id
                    resetListBatchState(ListApiMode.CollectsVideo, secUserId = null, mixId = null, collectsId = id)
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
        listNextCursor = 0L
        listHasMore = false
        videoItems.clear()
        videoAdapter.submitList(emptyList())
        updateSelectedCountText()
        if (mode == ListApiMode.None) {
            tvListStatus?.setText(R.string.list_batch_scope_hint)
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
        tvListStatus?.text = getString(R.string.list_api_loading)
    }

    private fun refreshListStatusAfterOk() {
        val tail = if (listHasMore) getString(R.string.list_api_has_more) else getString(R.string.list_api_no_more)
        tvListStatus?.text = getString(R.string.list_api_status_loaded, videoItems.size, tail)
    }

    private fun refreshListStatusError(msg: String?) {
        tvListStatus?.text = getString(R.string.list_api_error, msg ?: "")
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
                    videoAdapter.submitList(videoItems.toList())
                    refreshListStatusAfterOk()
                    updateSelectedCountText()
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
