package com.blitz.downloader.fragment

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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blitz.downloader.R
import com.blitz.downloader.activity.ManageActivity
import com.blitz.downloader.activity.VideoPlayerActivity
import com.blitz.downloader.adapter.ManageGridAdapter
import com.blitz.downloader.adapter.TagFilterAdapter
import com.blitz.downloader.data.db.DownloadedVideoEntity
import com.blitz.downloader.model.filter.ManageRelationFilter
import com.blitz.downloader.model.filter.ManageSortOrder
import com.blitz.downloader.model.filter.ManageTagCountFilter
import com.blitz.downloader.model.filter.ManageTagEditCountFilter
import com.blitz.downloader.viewmodel.ManageEmptyReason
import com.blitz.downloader.viewmodel.ManageTabEvent
import com.blitz.downloader.viewmodel.ManageTabUiState
import com.blitz.downloader.viewmodel.ManageVideoViewModel
import kotlinx.coroutines.launch

/**
 * 管理页「视频」Tab。
 *
 * 只负责视图：网格与标签栏、把菜单动作转发给 [ManageVideoViewModel]、渲染 `uiState`、
 * 弹出对话框。取数、筛选、排序、删除、标签读写都在 ViewModel 里。
 */
class ManageVideoFragment : Fragment(R.layout.fragment_manage_video), ManageTabFragment {

    private lateinit var adapter: ManageGridAdapter
    private lateinit var tagFilterAdapter: TagFilterAdapter
    private var progressRef: ProgressBar? = null
    private var tvEmptyRef: TextView? = null

    private val viewModel: ManageVideoViewModel by viewModels()

    /** 收到 [ManageTabEvent.SelectAllAfterLoad] 后置位，在下一次渲染完成后执行全选。 */
    private var pendingSelectAll = false

    // -----------------------------------------------------------------------
    // ManageTabFragment：供 ManageActivity 的菜单驱动
    // -----------------------------------------------------------------------

    override val inSelectionMode: Boolean get() = ::adapter.isInitialized && adapter.inSelectionMode
    override val selectedCount: Int get() = if (::adapter.isInitialized) adapter.getSelectedAwemeIds().size else 0
    override val supportsClearInvalid: Boolean get() = true
    override val supportsTagCountFilter: Boolean get() = true
    override val supportsTagEditCountFilter: Boolean get() = true

    override fun exitSelectionMode() {
        if (::adapter.isInitialized) adapter.exitSelectionMode()
    }

    override fun getSelectedEntities(): List<DownloadedVideoEntity> {
        if (!::adapter.isInitialized) return emptyList()
        return viewModel.entitiesFor(adapter.getSelectedAwemeIds())
    }

    // 「已全选」= 当前范围全部记录都已加载（无更多分页）且全部选中。
    // 仅当满足此条件时按钮才显示「取消全选」，否则显示「全选」（点击会拉取全库该范围记录）。
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
        // 仍有未加载的分页：先把当前范围在数据库中的全部记录拉齐再全选。
        viewModel.loadFullScopeThenSelectAll()
    }

    override val activeSortOrder: ManageSortOrder get() = viewModel.filters.sort
    override fun applySort(sort: ManageSortOrder) = viewModel.applySort(sort)

    override val activeRelationFilter: ManageRelationFilter get() = viewModel.filters.relation
    override fun applyRelationFilter(filter: ManageRelationFilter) = viewModel.applyRelationFilter(filter)

    override val activeTagCountFilters: Set<ManageTagCountFilter> get() = viewModel.filters.tagCounts
    override fun applyTagCountFilters(filters: Set<ManageTagCountFilter>) =
        viewModel.applyTagCountFilters(filters)

    override val activeTagEditCountFilter: ManageTagEditCountFilter get() = viewModel.filters.tagEditCount
    override fun applyTagEditCountFilter(filter: ManageTagEditCountFilter) =
        viewModel.applyTagEditCountFilter(filter)

    override fun applySearchQuery(query: String?) = viewModel.applySearchQuery(query)

    override val activeAuthorKey: String? get() = viewModel.filters.authorKey

    override fun applyAuthorFilter(secUserId: String?, userName: String?) {
        viewModel.applyAuthorFilter(secUserId, userName)
        // 作者筛选与标签互斥，ViewModel 已清掉标签条件，标签栏也要跟着回到「全部」
        if (viewModel.filters.authorKey != null) tagFilterAdapter.resetToAll()
    }

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

    override fun handleSetTagsSelected() {
        if (!::adapter.isInitialized) return
        viewModel.requestBatchTagPicker(adapter.getSelectedAwemeIds())
    }

    override fun handleClearInvalid() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manage_menu_clear_invalid)
            .setMessage(R.string.manage_clear_invalid_confirm)
            .setPositiveButton(R.string.manage_confirm_ok) { _, _ -> viewModel.clearInvalid() }
            .setNegativeButton(R.string.manage_confirm_cancel, null)
            .show()
    }

    // -----------------------------------------------------------------------
    // 生命周期
    // -----------------------------------------------------------------------

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTagFilterBar(view)
        setupGrid(view)
        observeViewModel()

        viewModel.loadTagFilterBar()
        viewModel.loadInitialIfNeeded()
    }

    override fun onDestroyView() {
        progressRef = null
        tvEmptyRef = null
        super.onDestroyView()
    }

    /**
     * 从播放页返回时补齐「已看过」标记。
     *
     * 这是两条刷新路径中的兜底那条（点开的那条在打开播放页时已就地标掉），
     * **不要**当成冗余删掉：ViewModel 不随 onResume 重建，替代不了它。
     */
    override fun onResume() {
        super.onResume()
        viewModel.refreshWatchedFlags()
    }

    private fun setupTagFilterBar(view: View) {
        val rvTagFilter: RecyclerView = view.findViewById(R.id.rvTagFilter)
        tagFilterAdapter = TagFilterAdapter { tags -> viewModel.applyTags(tags) }
        rvTagFilter.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rvTagFilter.adapter = tagFilterAdapter
    }

    private fun setupGrid(view: View) {
        val recyclerView: RecyclerView = view.findViewById(R.id.rvManageVideos)
        progressRef = view.findViewById(R.id.progressManageVideo)
        tvEmptyRef = view.findViewById(R.id.tvEmptyManageVideo)

        adapter = ManageGridAdapter(
            onSelectionChanged = { active, count ->
                (activity as? ManageActivity)?.onSelectionChanged(active, count)
            },
            onItemClick = { entity -> viewModel.openVideoPlayer(entity) },
            onTagAreaClick = { awemeId, currentTags -> viewModel.requestTagEditor(awemeId, currentTags) },
            supportsUserTags = true,
            showWatchedBadge = true,
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
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { render(it) } }
                launch { viewModel.availableTags.collect { tagFilterAdapter.submitTags(it) } }
                launch { viewModel.events.collect { handleEvent(it) } }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 渲染
    // -----------------------------------------------------------------------

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
        // 标签多选取交集时很容易筛空，说清是「哪几个标签的什么组合」没结果
        is ManageEmptyReason.Tags -> {
            val names = reason.names.joinToString("、")
            if (reason.matchAll) {
                getString(R.string.manage_tag_filter_empty_all, names)
            } else {
                getString(R.string.manage_tag_filter_empty_any, names)
            }
        }
        is ManageEmptyReason.TagCounts -> getString(
            R.string.manage_tag_count_empty,
            ManageTagCountFilter.shortSummary(requireContext(), reason.filters),
        )
        is ManageEmptyReason.TagEditCount ->
            getString(R.string.manage_tag_edit_count_empty, getString(reason.filter.labelRes))
        ManageEmptyReason.Relation -> getString(R.string.manage_relation_empty)
        ManageEmptyReason.NoRecords -> getString(R.string.manage_empty_videos)
    }

    // -----------------------------------------------------------------------
    // 一次性事件
    // -----------------------------------------------------------------------

    private fun handleEvent(event: ManageTabEvent) {
        when (event) {
            is ManageTabEvent.DeleteDone ->
                toast(getString(R.string.manage_delete_done, event.count))
            is ManageTabEvent.ClearInvalidDone ->
                toast(getString(R.string.manage_clear_invalid_done, event.count))
            ManageTabEvent.ClearInvalidNone -> toast(getString(R.string.manage_clear_invalid_none))
            ManageTabEvent.NoTagsAvailable -> toast(getString(R.string.manage_set_tags_no_tags))
            is ManageTabEvent.ShowTagEditor -> showTagEditDialog(event)
            is ManageTabEvent.ShowBatchTagPicker -> showBatchTagDialog(event)
            is ManageTabEvent.TagsApplied -> {
                toast(getString(R.string.manage_set_tags_done, event.count))
                adapter.exitSelectionMode()
            }
            is ManageTabEvent.TagEditCountBumped -> {
                toast(getString(R.string.manage_bump_tag_edit_count_done, event.count))
                adapter.exitSelectionMode()
            }
            ManageTabEvent.FileNotFound -> toast(getString(R.string.player_file_not_found))
            // uiState 与 events 由两个独立协程收集，到达顺序不保证：列表已经渲染出来就直接
            // 全选，还没渲染就置位、等 render 完成后再选。少了任一分支都可能全选不生效。
            ManageTabEvent.SelectAllAfterLoad ->
                if (adapter.itemCount > 0) adapter.selectAll() else pendingSelectAll = true
            is ManageTabEvent.OpenVideoPlayer -> startActivity(
                VideoPlayerActivity.createListFileIntent(
                    context = requireContext(),
                    filePaths = ArrayList(event.filePaths),
                    titles = ArrayList(event.titles),
                    subtitles = ArrayList(event.subtitles),
                    position = event.position,
                    awemeIds = ArrayList(event.awemeIds),
                ),
            )
            is ManageTabEvent.OpenImageViewer -> Unit // 视频 Tab 不会收到
        }
    }

    /**
     * 单条记录的标签设置弹窗：显示全部可用标签、当前已打的预先勾选，
     * 确认后整体覆盖写库。
     */
    private fun showTagEditDialog(event: ManageTabEvent.ShowTagEditor) {
        val allTags = event.allTags
        val checkedItems = BooleanArray(allTags.size) { allTags[it] in event.currentTags }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manage_edit_tags_title)
            .setMultiChoiceItems(allTags.toTypedArray(), checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.applyTagsToVideo(
                    event.awemeId,
                    allTags.filterIndexed { i, _ -> checkedItems[i] },
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 多选后的批量标签弹窗：勾选的标签会**追加**到所有已选记录上。 */
    private fun showBatchTagDialog(event: ManageTabEvent.ShowBatchTagPicker) {
        val allTags = event.allTags
        val ids = event.awemeIds
        val checkedItems = BooleanArray(allTags.size) { false }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.manage_set_tags_title, ids.size))
            .setMultiChoiceItems(allTags.toTypedArray(), checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val chosen = allTags.filterIndexed { i, _ -> checkedItems[i] }
                if (chosen.isEmpty()) {
                    toast(getString(R.string.manage_set_tags_none_checked))
                    return@setPositiveButton
                }
                viewModel.addTagsToVideos(ids, chosen)
            }
            // 只给已选记录的 tagEditCount +1，不动标签：
            // 补 v12 之前手工改过标签、但库里没留下计数的历史数据。
            .setNeutralButton(R.string.manage_bump_tag_edit_count) { _, _ ->
                confirmAndBumpTagEditCount(ids)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 二次确认后把已选记录的标签修改次数 +1（不改标签）。
     * 无条件累加、没有幂等标记，重复执行会重复加，所以确认框里写清楚。
     */
    private fun confirmAndBumpTagEditCount(ids: List<String>) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manage_bump_tag_edit_count)
            .setMessage(getString(R.string.manage_bump_tag_edit_count_confirm, ids.size))
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.bumpTagEditCount(ids) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(text: CharSequence) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }
}
