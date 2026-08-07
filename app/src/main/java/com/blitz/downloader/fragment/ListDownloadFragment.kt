package com.blitz.downloader.fragment

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
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.blitz.downloader.R
import com.blitz.downloader.activity.DouyinWebBrowserActivity
import com.blitz.downloader.activity.VideoPlayerActivity
import com.blitz.downloader.adapter.VideoGridAdapter
import com.blitz.downloader.api.DouyinCollectsFolderRow
import com.blitz.downloader.config.AppConfig
import com.blitz.downloader.databinding.FragmentListDownloadBinding
import com.blitz.downloader.dialog.PhotoSelectionBottomSheet
import com.blitz.downloader.download.BatchDownloadCoordinator
import com.blitz.downloader.viewmodel.CookiePasteResult
import com.blitz.downloader.viewmodel.CookieStatusUi
import com.blitz.downloader.viewmodel.CookieSyncResult
import com.blitz.downloader.viewmodel.ListDownloadEvent
import com.blitz.downloader.viewmodel.ListDownloadUiState
import com.blitz.downloader.viewmodel.ListDownloadViewModel
import com.blitz.downloader.viewmodel.ListKindChoice
import com.blitz.downloader.viewmodel.ListStatus
import java.io.File
import kotlinx.coroutines.launch

/**
 * 批量下载页。
 *
 * 只负责视图：绑定控件、把用户操作转发给 [ListDownloadViewModel]、渲染 `uiState`、
 * 处理 ViewModel 发出的一次性事件（弹窗 / 跳转 / Toast）。
 * 网络请求、数据库读写、分页与登录态重试都在 ViewModel 里。
 */
class ListDownloadFragment : Fragment() {

    private var _binding: FragmentListDownloadBinding? = null
    private val binding get() = _binding!!
    private lateinit var videoAdapter: VideoGridAdapter

    private val viewModel: ListDownloadViewModel by viewModels()

    private var showFabRunnable: Runnable? = null

    /** 本 App 所有者的主页地址，用于「返回我的主页」时回填输入框。 */
    private val indexMainPage =
        "https://www.douyin.com/user/${AppConfig.MY_SEC_USER_ID}?from_tab_name=main"

    /** Android 13+ 通知权限申请器：用于展示前台下载进度，拒绝也不阻断下载。 */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也照常下载，仅无通知 */ }

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
        BatchDownloadCoordinator.createNoMediaFile(File(BatchDownloadCoordinator.COVER_SUBDIR))

        setupRecyclerView()
        setupScrollListener()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        binding.rvVideos.layoutManager = GridLayoutManager(requireContext(), 3)
        videoAdapter = VideoGridAdapter(
            items = emptyList(),
            onItemClicked = { item -> viewModel.toggleSelection(item.id) },
            onAuthorPostsClicked = { item -> viewModel.onAuthorPostsClicked(item.id) },
            onPreviewClicked = { item -> viewModel.onPreviewClicked(item.id) },
        )
        binding.rvVideos.adapter = videoAdapter
    }

    private fun setupScrollListener() {
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
                if (!viewModel.canLoadMore()) return@OnScrollChangeListener
                val diff = v.getChildAt(0).measuredHeight - v.measuredHeight - scrollY
                if (diff <= thresholdPx) {
                    viewModel.loadNextListPage()
                }
            },
        )
    }

    private fun setupClickListeners() {
        binding.fabParse.setOnClickListener {
            viewModel.parseAndLoad(binding.etUrlInput.text?.toString(), currentListKind())
        }
        binding.btnBackToMyPage.setOnClickListener { exitAuthorPostsMode() }
        binding.btnSyncCookie.setOnClickListener { viewModel.syncCookieFromCookieManager() }
        binding.btnPasteCookie.setOnClickListener { viewModel.importPastedCookie(readClipboardText()) }
        binding.btnOpenDouyinBrowser.setOnClickListener { startDouyinBrowser(initialUrlFromInput()) }
        binding.btnSelectAll.setOnClickListener { viewModel.toggleSelectAll() }
        binding.btnDownloadSelected.setOnClickListener { viewModel.startBatchDownload() }
        binding.cbHideDownloaded.setOnCheckedChangeListener { _, checked ->
            viewModel.setHideDownloaded(checked)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { render(it) } }
                launch { viewModel.cookieStatus.collect { renderCookieStatus(it) } }
                launch { viewModel.events.collect { handleEvent(it) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onScreenResumed()
    }

    override fun onDestroyView() {
        showFabRunnable?.let { _binding?.root?.removeCallbacks(it) }
        showFabRunnable = null
        _binding = null
        super.onDestroyView()
    }

    // -----------------------------------------------------------------------
    // 渲染
    // -----------------------------------------------------------------------

    private fun render(state: ListDownloadUiState) {
        videoAdapter.setUserPostMode(state.isUserPostMode)
        videoAdapter.submitList(state.visibleItems)
        renderAuthorPostsChrome(state.isAuthorPostsMode)
        // 配置变更后视图会重建成 XML 默认值，从 state 回写一次；
        // 这会触发监听器，但 setHideDownloaded 对同值早返回，不会形成回环。
        if (binding.cbHideDownloaded.isChecked != state.hideDownloaded) {
            binding.cbHideDownloaded.isChecked = state.hideDownloaded
        }
        binding.btnDownloadSelected.isEnabled = state.canDownload
        binding.tvSelectedCount.text = if (state.hiddenCount > 0) {
            "已选择 ${state.selectedCount} / ${state.totalCount}（隐藏 ${state.hiddenCount}）"
        } else {
            "已选择 ${state.selectedCount} / ${state.totalCount}"
        }
        binding.tvStatus.text = statusText(state)
    }

    private fun statusText(state: ListDownloadUiState): CharSequence = when (val s = state.status) {
        ListStatus.Idle -> getString(R.string.list_batch_scope_hint)
        ListStatus.Loading -> getString(R.string.list_api_loading)
        ListStatus.CollectsLoading -> getString(R.string.collects_list_loading)
        ListStatus.CollectsEmpty -> getString(R.string.collects_list_empty)
        is ListStatus.Error -> getString(R.string.list_api_error, s.message ?: "")
        is ListStatus.AuthorPostsMode -> getString(R.string.author_posts_mode_hint, s.nickname)
        is ListStatus.Enqueued -> getString(R.string.batch_download_enqueued, s.count)
        is ListStatus.Loaded -> {
            val tail = getString(
                if (s.hasMore) R.string.list_api_has_more else R.string.list_api_no_more,
            )
            val base = getString(R.string.list_api_status_loaded, s.total, tail)
            // 开了「隐藏已下载」时把过滤掉多少条说清楚，否则可见项为 0 会被误认为加载失败
            if (s.hidden > 0) {
                getString(R.string.list_api_status_hidden_suffix, base, s.hidden, s.total - s.hidden)
            } else {
                base
            }
        }
    }

    private fun renderCookieStatus(status: CookieStatusUi) {
        val snap = status.snapshot
        if (!status.hasCookie || snap == null) {
            binding.tvCookieStatus.setText(R.string.cookie_status_none)
            return
        }
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

    // -----------------------------------------------------------------------
    // 一次性事件
    // -----------------------------------------------------------------------

    private fun handleEvent(event: ListDownloadEvent) {
        when (event) {
            is ListDownloadEvent.CookieSynced -> toast(
                when (event.result) {
                    CookieSyncResult.EMPTY -> R.string.toast_cookie_sync_empty
                    CookieSyncResult.WEB_EMPTY -> R.string.toast_cookie_sync_web_empty
                    CookieSyncResult.OK_WITH_LOGIN -> R.string.toast_cookie_synced_with_login
                    CookieSyncResult.OK_NO_LOGIN -> R.string.toast_cookie_synced_no_login
                },
                long = event.result == CookieSyncResult.WEB_EMPTY ||
                    event.result == CookieSyncResult.OK_NO_LOGIN,
            )

            is ListDownloadEvent.CookiePasted -> when (event.result) {
                CookiePasteResult.CLIPBOARD_EMPTY -> toast("剪贴板为空")
                CookiePasteResult.OK -> toast(R.string.toast_cookie_paste_ok)
                CookiePasteResult.INVALID -> toast(R.string.toast_cookie_paste_invalid)
            }

            is ListDownloadEvent.ShowPhotoSelection -> PhotoSelectionBottomSheet.show(
                context = requireContext(),
                imageUrls = event.imageUrls,
                initialSelection = event.initialSelection,
                editable = event.editable,
            ) { result -> viewModel.applyPhotoSelection(event.id, result) }

            ListDownloadEvent.PhotoSelectionCleared -> toast(R.string.photo_pick_none_selected)

            is ListDownloadEvent.ShowCollectsFolderPicker -> showCollectsFolderDialog(event.folders)
            ListDownloadEvent.CollectsFolderEmpty -> toast(R.string.collects_list_empty, long = true)

            ListDownloadEvent.ShowSessionExpiredDialog -> showSessionExpiredDialog()
            ListDownloadEvent.OpenBrowserForLogin ->
                startDouyinBrowser(DouyinWebBrowserActivity.DOUYIN_DEFAULT_HOME_URL)

            is ListDownloadEvent.OpenBrowser -> startDouyinBrowser(normalizeUrl(event.url))
            is ListDownloadEvent.OpenedAsPlainUrl -> {
                toast("已按普通链接打开网页")
                startDouyinBrowser(normalizeUrl(event.url))
            }

            is ListDownloadEvent.EnterAuthorPostsMode ->
                enterAuthorPostsMode(event.authorUrl)

            is ListDownloadEvent.OpenVideoPreview -> startActivity(
                VideoPlayerActivity.createListNetworkIntent(
                    context = requireContext(),
                    urls = ArrayList(event.urls),
                    titles = ArrayList(
                        event.nicknames.map { it.ifBlank { getString(R.string.video_player_title) } },
                    ),
                    subtitles = ArrayList(event.descriptions),
                    position = event.position,
                ),
            )

            ListDownloadEvent.RequestNotificationPermission -> requestNotificationPermissionIfNeeded()
            is ListDownloadEvent.BatchDownloadEnqueued ->
                toast(R.string.batch_download_enqueued_toast, long = true)

            is ListDownloadEvent.ListLoadFailed ->
                toast(getString(R.string.list_api_error, event.message ?: ""), long = true)

            ListDownloadEvent.NothingSelected -> toast(R.string.batch_download_none_selected)
            ListDownloadEvent.NoPlayUrl -> toast(R.string.batch_download_no_play_url, long = true)
            ListDownloadEvent.NoPreviewUrl -> toast("该视频暂无可用播放地址")
            ListDownloadEvent.SelectAllRejected -> toast(R.string.batch_select_empty)
            ListDownloadEvent.NeedLoginForCollection ->
                toast(R.string.list_api_collection_need_login, long = true)
            ListDownloadEvent.NeedUserOrMix -> toast(R.string.list_api_need_user_or_mix, long = true)
            ListDownloadEvent.UrlInputEmpty -> toast("请输入URL")
            ListDownloadEvent.ShortLinkUnresolved ->
                toast(R.string.list_api_short_unresolved, long = true)
            ListDownloadEvent.AuthorIdMissing -> toast("该作品没有作者ID，无法加载")
        }
    }

    private fun showCollectsFolderDialog(folders: List<DouyinCollectsFolderRow>) {
        val labels = folders.map { row ->
            val name = row.name.ifBlank { row.id }
            "$name (${row.id})"
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.collects_pick_title)
            .setItems(labels) { _, which -> viewModel.onCollectsFolderPicked(folders[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSessionExpiredDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.session_expired_title)
            .setMessage(R.string.session_expired_msg)
            .setPositiveButton(R.string.session_expired_login) { _, _ ->
                viewModel.onSessionExpiredLoginChosen()
            }
            .setNeutralButton(R.string.session_expired_sync) { _, _ ->
                viewModel.onSessionExpiredSyncChosen()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                viewModel.onSessionExpiredDismissed()
            }
            .setOnCancelListener { viewModel.onSessionExpiredDismissed() }
            .show()
    }

    // -----------------------------------------------------------------------
    // 「查看 TA 的 Post」模式的视图切换
    // -----------------------------------------------------------------------

    /**
     * 按当前是否处于「查看 TA 的 Post」模式切换界面：锁定 / 解锁列表种类选项、显示 / 隐藏返回按钮。
     *
     * 从 `uiState` 驱动而非在进入/退出时一次性设置，这样配置变更后重建的视图也能还原。
     */
    private fun renderAuthorPostsChrome(inAuthorMode: Boolean) {
        binding.rbKindLike.isEnabled = !inAuthorMode
        binding.rbKindCollection.isEnabled = !inAuthorMode
        binding.rbKindCollectsFolder.isEnabled = !inAuthorMode
        binding.btnBackToMyPage.visibility = if (inAuthorMode) View.VISIBLE else View.GONE
        if (inAuthorMode && binding.rgListKind.checkedRadioButtonId != R.id.rbKindPost) {
            binding.rgListKind.check(R.id.rbKindPost)
        }
    }

    /** 进入「查看 TA 的 Post」模式：回填作者主页 URL，滚回顶部后触发加载（界面切换由 [render] 负责）。 */
    private fun enterAuthorPostsMode(authorUrl: String) {
        binding.etUrlInput.setText(authorUrl)
        binding.etUrlInput.clearFocus()
        binding.nestedScrollView.smoothScrollTo(0, 0)
        binding.nestedScrollView.post {
            viewModel.parseAndLoad(authorUrl, ListKindChoice.Post)
        }
    }

    /** 退出「查看 TA 的 Post」模式，恢复默认状态。 */
    private fun exitAuthorPostsMode() {
        binding.etUrlInput.setText(indexMainPage)
        binding.etUrlInput.clearFocus()
        binding.rgListKind.check(R.id.rbKindPost)
        viewModel.exitAuthorPostsMode()
    }

    // -----------------------------------------------------------------------
    // 杂项
    // -----------------------------------------------------------------------

    private fun currentListKind(): ListKindChoice = when (binding.rgListKind.checkedRadioButtonId) {
        R.id.rbKindLike -> ListKindChoice.Like
        R.id.rbKindCollection -> ListKindChoice.Collection
        R.id.rbKindCollectsFolder -> ListKindChoice.CollectsFolder
        else -> ListKindChoice.Post
    }

    private fun readClipboardText(): String? {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    }

    private fun startDouyinBrowser(initialUrl: String?) {
        startActivity(DouyinWebBrowserActivity.createIntent(requireContext(), initialUrl))
    }

    private fun initialUrlFromInput(): String? = normalizeUrl(binding.etUrlInput.text?.toString())

    private fun normalizeUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
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

    private fun toast(resId: Int, long: Boolean = false) {
        Toast.makeText(
            requireContext(),
            resId,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
        ).show()
    }

    private fun toast(text: String, long: Boolean = false) {
        Toast.makeText(
            requireContext(),
            text,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
        ).show()
    }
}
