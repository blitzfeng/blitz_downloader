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
import com.blitz.downloader.activity.VideoPlayerActivity
import com.blitz.downloader.adapter.ManageGridAdapter
import com.blitz.downloader.adapter.TagFilterAdapter
import com.blitz.downloader.model.filter.ManageTagCountFilter
import com.blitz.downloader.util.TagQueryFormatter
import com.blitz.downloader.viewmodel.ManageCommand
import com.blitz.downloader.viewmodel.ManageEmptyReason
import com.blitz.downloader.viewmodel.ManageTabEvent
import com.blitz.downloader.viewmodel.ManageTabUiState
import com.blitz.downloader.viewmodel.ManageVideoViewModel
import com.blitz.downloader.viewmodel.ManageViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 管理页「视频」Tab。
 *
 * 与外层 [ManageFragment] 之间**只通过 [ManageViewModel] 通信**（不再有 `ManageTabFragment`
 * 接口，外层也不用 `findFragmentByTag` 反查 Fragment）：
 * 筛选条件与多选状态从父 Fragment 级 ViewModel 观察下来，菜单动作以命令形式收到，
 * 列表数据每次变化上报回去供「全选」与「导出选中」使用。
 */
class ManageVideoFragment : Fragment(R.layout.fragment_manage_video) {

    private lateinit var adapter: ManageGridAdapter
    private lateinit var tagFilterAdapter: TagFilterAdapter
    private var progressRef: ProgressBar? = null
    private var tvEmptyRef: TextView? = null

    private val viewModel: ManageVideoViewModel by viewModels()
    // 作用域是外层 ManageFragment（两个 Tab 共享它），不是 Activity——管理页的状态跟着管理页走
    private val manageViewModel: ManageViewModel by viewModels(
        ownerProducer = { requireParentFragment() },
    )

    private val tab = ManageViewModel.TAB_VIDEO

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
        tagFilterAdapter = TagFilterAdapter { tags -> manageViewModel.applyTags(tab, tags) }
        rvTagFilter.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rvTagFilter.adapter = tagFilterAdapter
    }

    private fun setupGrid(view: View) {
        val recyclerView: RecyclerView = view.findViewById(R.id.rvManageVideos)
        progressRef = view.findViewById(R.id.progressManageVideo)
        tvEmptyRef = view.findViewById(R.id.tvEmptyManageVideo)

        adapter = ManageGridAdapter(
            onItemClick = { entity -> viewModel.openVideoPlayer(entity) },
            onItemLongClick = { awemeId -> manageViewModel.enterSelectionMode(tab, awemeId) },
            onSelectionToggle = { awemeId -> manageViewModel.toggleSelection(tab, awemeId) },
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
                launch {
                    manageViewModel.filters
                        .map { it[tab] }
                        .distinctUntilChanged()
                        .collect { filters ->
                            if (filters == null) return@collect
                            viewModel.applyFilters(filters)
                            // 作者筛选与标签互斥，条件里标签被清空时标签栏也要回到「全部」
                            if (filters.tags.isEmpty()) tagFilterAdapter.resetToAll()
                        }
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
        is ManageCommand.SetTagsSelected -> viewModel.requestBatchTagPicker(command.ids)
        is ManageCommand.ClearInvalid -> viewModel.clearInvalid()
        is ManageCommand.LoadFullScopeThenSelectAll -> viewModel.loadFullScope()
        is ManageCommand.MarkExported -> viewModel.markExported(command.awemeIds)
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
        // 上报已加载快照：Activity 侧据此算「是否已全选」与「选中了哪些实体」
        manageViewModel.setLoaded(tab, state.items.map { it.entity }, state.hasMore)
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
        is ManageEmptyReason.TagRules -> getString(
            R.string.manage_tag_query_empty,
            TagQueryFormatter.format(requireContext(), reason.query),
        )
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
                manageViewModel.exitSelectionMode(tab)
            }
            is ManageTabEvent.TagEditCountBumped -> {
                toast(getString(R.string.manage_bump_tag_edit_count_done, event.count))
                manageViewModel.exitSelectionMode(tab)
            }
            ManageTabEvent.FileNotFound -> toast(getString(R.string.player_file_not_found))
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
