package com.blitz.downloader.fragment

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blitz.downloader.R
import com.blitz.downloader.activity.ImageViewerActivity
import com.blitz.downloader.activity.ManageActivity
import com.blitz.downloader.adapter.ManageGridAdapter
import com.blitz.downloader.data.db.DownloadedVideoEntity
import com.blitz.downloader.model.filter.ManageRelationFilter
import com.blitz.downloader.model.filter.ManageSortOrder
import com.blitz.downloader.viewmodel.ManageEmptyReason
import com.blitz.downloader.viewmodel.ManageImageViewModel
import com.blitz.downloader.viewmodel.ManageTabEvent
import com.blitz.downloader.viewmodel.ManageTabUiState
import kotlinx.coroutines.launch

/**
 * 管理页「图片」Tab。
 *
 * 与 [ManageVideoFragment] 结构一致但能力更少：没有标签栏、不检查文件失效、
 * 不支持清除失效——这些与既有行为保持一致，不要顺手「补齐」。
 */
class ManageImageFragment : Fragment(R.layout.fragment_manage_image), ManageTabFragment {

    private lateinit var adapter: ManageGridAdapter
    private var progressRef: ProgressBar? = null
    private var tvEmptyRef: TextView? = null

    private val viewModel: ManageImageViewModel by viewModels()

    /** 收到 [ManageTabEvent.SelectAllAfterLoad] 后置位，在下一次渲染完成后执行全选。 */
    private var pendingSelectAll = false

    // -----------------------------------------------------------------------
    // ManageTabFragment：供 ManageActivity 的菜单驱动
    // -----------------------------------------------------------------------

    override val inSelectionMode: Boolean get() = ::adapter.isInitialized && adapter.inSelectionMode
    override val selectedCount: Int get() = if (::adapter.isInitialized) adapter.getSelectedAwemeIds().size else 0
    override val supportsClearInvalid: Boolean get() = false

    override fun exitSelectionMode() {
        if (::adapter.isInitialized) adapter.exitSelectionMode()
    }

    override fun getSelectedEntities(): List<DownloadedVideoEntity> {
        if (!::adapter.isInitialized) return emptyList()
        return viewModel.entitiesFor(adapter.getSelectedAwemeIds())
    }

    // 「已全选」= 当前范围全部记录都已加载（无更多分页）且全部选中；否则按钮显示「全选」。
    override val isAllSelected: Boolean
        get() = ::adapter.isInitialized && !viewModel.uiState.value.hasMore && adapter.isAllSelected()

    override fun markExported(awemeIds: Set<String>) = viewModel.markExported(awemeIds)

    override fun handleToggleSelectAll() {
        if (!::adapter.isInitialized) return
        // 当前范围已全部加载：纯内存切换，不重建列表、不丢滚动位置。
        if (!viewModel.uiState.value.hasMore) {
            if (adapter.isAllSelected()) adapter.deselectAll() else adapter.selectAll()
            return
        }
        viewModel.loadFullScopeThenSelectAll()
    }

    override val activeSortOrder: ManageSortOrder get() = viewModel.filters.sort
    override fun applySort(sort: ManageSortOrder) = viewModel.applySort(sort)

    override val activeRelationFilter: ManageRelationFilter get() = viewModel.filters.relation
    override fun applyRelationFilter(filter: ManageRelationFilter) = viewModel.applyRelationFilter(filter)

    override fun applySearchQuery(query: String?) = viewModel.applySearchQuery(query)

    override val activeAuthorKey: String? get() = viewModel.filters.authorKey

    override fun applyAuthorFilter(secUserId: String?, userName: String?) =
        viewModel.applyAuthorFilter(secUserId, userName)

    override fun handleDeleteSelected() {
        if (!::adapter.isInitialized) return
        val ids = adapter.getSelectedAwemeIds()
        if (ids.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manage_confirm_delete_title)
            .setMessage(getString(R.string.manage_confirm_delete_msg, ids.size))
            .setPositiveButton(R.string.manage_confirm_ok) { _, _ -> viewModel.deleteSelected(ids) }
            .setNegativeButton(R.string.manage_confirm_cancel, null)
            .show()
    }

    override fun handleClearInvalid() {
        // 图片 Tab 不支持此操作
    }

    // -----------------------------------------------------------------------
    // 生命周期
    // -----------------------------------------------------------------------

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView: RecyclerView = view.findViewById(R.id.rvManageImages)
        progressRef = view.findViewById(R.id.progressManageImage)
        tvEmptyRef = view.findViewById(R.id.tvEmptyManageImage)

        adapter = ManageGridAdapter(
            onSelectionChanged = { active, count ->
                (activity as? ManageActivity)?.onSelectionChanged(active, count)
            },
            onItemClick = { entity -> viewModel.openImageViewer(entity) },
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
            }
        }
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
        if (pendingSelectAll && state.items.isNotEmpty()) {
            pendingSelectAll = false
            adapter.selectAll()
        }
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
            // uiState 与 events 由两个独立协程收集，到达顺序不保证：列表已经渲染出来就直接
            // 全选，还没渲染就置位、等 render 完成后再选。少了任一分支都可能全选不生效。
            ManageTabEvent.SelectAllAfterLoad ->
                if (adapter.itemCount > 0) adapter.selectAll() else pendingSelectAll = true
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
