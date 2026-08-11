package com.blitz.downloader.fragment

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blitz.downloader.R
import com.blitz.downloader.activity.ImageViewerActivity
import com.blitz.downloader.activity.MainActivity
import com.blitz.downloader.adapter.ManageGridAdapter
import com.blitz.downloader.viewmodel.ManageCommand
import com.blitz.downloader.viewmodel.ManageEmptyReason
import com.blitz.downloader.viewmodel.ManageImageViewModel
import com.blitz.downloader.viewmodel.ManageTabEvent
import com.blitz.downloader.viewmodel.ManageTabUiState
import com.blitz.downloader.viewmodel.ManageViewModel
import com.blitz.downloader.viewmodel.ShellNavViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 管理页「图片」Tab。
 *
 * 与 [ManageVideoFragment] 结构一致但能力更少：没有标签栏、不检查文件失效、
 * 不支持清除失效——这些与既有行为保持一致，不要顺手「补齐」。
 */
class ManageImageFragment : Fragment(R.layout.fragment_manage_image) {

    private lateinit var adapter: ManageGridAdapter
    private var progressRef: ProgressBar? = null
    private var tvEmptyRef: TextView? = null

    private val viewModel: ManageImageViewModel by viewModels()
    // 作用域是外层 ManageFragment（两个 Tab 共享它），不是 Activity——管理页的状态跟着管理页走
    private val manageViewModel: ManageViewModel by viewModels(
        ownerProducer = { requireParentFragment() },
    )
    /** 外壳级导航中转站（作用域是 Activity），点作者名跳批量下载用。 */
    private val shellNav: ShellNavViewModel by activityViewModels()

    private val tab = ManageViewModel.TAB_IMAGE

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView: RecyclerView = view.findViewById(R.id.rvManageImages)
        progressRef = view.findViewById(R.id.progressManageImage)
        tvEmptyRef = view.findViewById(R.id.tvEmptyManageImage)

        adapter = ManageGridAdapter(
            onItemClick = { entity -> viewModel.openImageViewer(entity) },
            onItemLongClick = { awemeId -> manageViewModel.enterSelectionMode(tab, awemeId) },
            onSelectionToggle = { awemeId -> manageViewModel.toggleSelection(tab, awemeId) },
            onAuthorClick = { entity ->
                shellNav.requestAuthorPosts(
                    secUserId = entity.videoAuthorSecUserId,
                    // 与 ManageGridAdapter 里作者行的显示 / 无障碍文案统一：昵称为空的记录
                    // 用 awemeId 兜底，否则「正在查看 __ 的作品」会缺个名字
                    nickname = entity.userName.ifBlank { entity.awemeId },
                    originTab = MainActivity.TAB_MANAGE,
                    targetTab = MainActivity.TAB_DOWNLOAD,
                )
            },
            supportsUserTags = false,
        )
        val gridManager = GridLayoutManager(requireContext(), 2)
        recyclerView.layoutManager = gridManager
        recyclerView.adapter = adapter

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                if (!viewModel.canLoadMore()) return
                if (gridManager.findLastVisibleItemPosition() >= adapter.itemCount - 4) {
                    viewModel.loadNextPage()
                }
            }
        })

        observeViewModel()
        viewModel.loadInitialIfNeeded()
    }

    override fun onDestroyView() {
        progressRef = null
        tvEmptyRef = null
        super.onDestroyView()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { render(it) } }
                launch { viewModel.events.collect { handleEvent(it) } }
                launch {
                    manageViewModel.filters
                        .map { it[tab] }
                        .distinctUntilChanged()
                        .collect { filters -> if (filters != null) viewModel.applyFilters(filters) }
                }
                launch {
                    manageViewModel.selection
                        .map { it[tab] }
                        .distinctUntilChanged()
                        .collect { selection ->
                            if (selection == null) return@collect
                            adapter.submitSelection(selection.inSelectionMode, selection.selectedIds)
                        }
                }
                launch {
                    manageViewModel.commands.collect { command ->
                        if (command.tab == tab) handleCommand(command)
                    }
                }
            }
        }
    }

    private fun handleCommand(command: ManageCommand) = when (command) {
        is ManageCommand.DeleteSelected -> viewModel.deleteSelected(command.ids)
        is ManageCommand.LoadFullScopeThenSelectAll -> viewModel.loadFullScope()
        is ManageCommand.MarkExported -> viewModel.markExported(command.awemeIds)
        // 图片 Tab 不支持标签与清除失效
        is ManageCommand.SetTagsSelected, is ManageCommand.ClearInvalid -> Unit
    }

    private fun render(state: ManageTabUiState) {
        progressRef?.visibility = if (state.showProgress) View.VISIBLE else View.GONE
        adapter.submitItems(state.items)
        val reason = state.emptyReason
        if (reason != null) {
            tvEmptyRef?.text = emptyText(reason)
            tvEmptyRef?.visibility = View.VISIBLE
        } else {
            tvEmptyRef?.visibility = View.GONE
        }
        manageViewModel.setLoaded(tab, state.items.map { it.entity }, state.hasMore)
    }

    private fun emptyText(reason: ManageEmptyReason): CharSequence = when (reason) {
        is ManageEmptyReason.Author -> getString(R.string.manage_author_empty, reason.name)
        is ManageEmptyReason.Search -> getString(R.string.manage_search_empty, reason.query)
        ManageEmptyReason.Relation -> getString(R.string.manage_relation_empty)
        // 图片 Tab 不支持标签相关的筛选层，落到这里只可能是「一条都没有」
        else -> getString(R.string.manage_empty_images)
    }

    private fun handleEvent(event: ManageTabEvent) {
        when (event) {
            is ManageTabEvent.DeleteDone ->
                toast(getString(R.string.manage_delete_done, event.count))
            ManageTabEvent.FileNotFound -> toast(getString(R.string.player_file_not_found))
            is ManageTabEvent.OpenImageViewer -> startActivity(
                Intent(requireContext(), ImageViewerActivity::class.java).apply {
                    putExtra(ImageViewerActivity.EXTRA_FILE_PATH, event.filePath)
                    putExtra(ImageViewerActivity.EXTRA_TITLE, event.title)
                },
            )
            // 以下事件只由视频 Tab 产生
            is ManageTabEvent.ClearInvalidDone,
            ManageTabEvent.ClearInvalidNone,
            ManageTabEvent.NoTagsAvailable,
            is ManageTabEvent.ShowTagEditor,
            is ManageTabEvent.ShowBatchTagPicker,
            is ManageTabEvent.TagsApplied,
            is ManageTabEvent.TagEditCountBumped,
            is ManageTabEvent.OpenVideoPlayer,
            -> Unit
        }
    }

    private fun toast(text: CharSequence) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }
}
