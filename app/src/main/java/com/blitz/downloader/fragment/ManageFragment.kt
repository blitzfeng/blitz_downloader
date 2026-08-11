package com.blitz.downloader.fragment

import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.GravityCompat
import androidx.core.view.MenuItemCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.blitz.downloader.R
import com.blitz.downloader.activity.TagManageActivity
import com.blitz.downloader.adapter.AuthorFilterAdapter
import com.blitz.downloader.data.db.DownloadedVideoDao.AuthorCount
import com.blitz.downloader.data.db.DownloadedVideoEntity
import com.blitz.downloader.databinding.FragmentManageBinding
import com.blitz.downloader.dialog.TagQueryDialog
import com.blitz.downloader.download.MediaExportManager
import com.blitz.downloader.model.filter.ManageRelationFilter
import com.blitz.downloader.model.filter.ManageSortOrder
import com.blitz.downloader.model.filter.ManageTagCountFilter
import com.blitz.downloader.model.filter.ManageTagEditCountFilter
import com.blitz.downloader.viewmodel.LanExportState
import com.blitz.downloader.viewmodel.LanPrepareProgress
import com.blitz.downloader.viewmodel.LanStartFailure
import com.blitz.downloader.viewmodel.ManageStats
import com.blitz.downloader.viewmodel.ManageViewModel
import com.blitz.downloader.viewmodel.ZipProgress
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 底部导航「管理」页外壳：Toolbar、菜单、Tab、作者抽屉、以及各类对话框。
 *
 * 筛选条件、多选状态、统计 / 导出的实际工作都在 [ManageViewModel] 里；本类与两个 Tab
 * Fragment **不再直接互相引用**，三条通路（条件下行 / 动作下行 / 数据上行）全部经 ViewModel。
 *
 * 两处与改造前不同、改动时容易踩的地方：
 *
 * - **菜单走 Toolbar 自身的 API**（[android.widget.Toolbar.inflateMenu] /
 *   `setOnMenuItemClickListener`），不用 `setSupportActionBar` + `onCreateOptionsMenu`：
 *   三个 tab 页面同时存活，而 Activity 级 ActionBar 只有一份，谁 set 谁赢。
 *   原先的 `invalidateOptionsMenu()` 一律换成 [applyMenuState]，它只改既有 MenuItem 的
 *   `isVisible` / `title`，**不重新 inflate**，所以 SearchView 实例始终不变。
 * - **返回处理注册在本页**（[backCallback]），且 `isEnabled` 由「本页可见 且（抽屉打开 或
 *   多选中）」共同决定：被隐藏的页面 `STARTED` 时 callback 仍在册，不让路会抢走别的页面的返回键。
 */
class ManageFragment : Fragment() {

    private var _binding: FragmentManageBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ManageViewModel by viewModels()

    /** 记录当前菜单中的 SearchView，便于切 Tab / 切走本页时折叠、清空。 */
    private var searchMenuItem: MenuItem? = null

    /** 作者筛选抽屉的列表 Adapter。 */
    private lateinit var authorAdapter: AuthorFilterAdapter

    @Suppress("DEPRECATION")
    private var zipProgressDialog: ProgressDialog? = null
    private var lanDialog: AlertDialog? = null
    private var lanStatusView: TextView? = null
    private var lanPrepareDialog: ProgressDialog? = null

    private val currentTab: Int get() = binding.viewPagerManage.currentItem
    private val isInSelectionMode: Boolean get() = viewModel.selectionOf(currentTab).inSelectionMode
    private val currentSelectionCount: Int get() = viewModel.selectionOf(currentTab).count

    /** 抽屉 → 多选 两级返回处理；外壳的「回到下载 tab」优先级更低，由 MainActivity 兜底。 */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.END)) {
                binding.drawerLayout.closeDrawer(GravityCompat.END)
                return
            }
            if (isInSelectionMode) {
                viewModel.exitSelectionMode(currentTab)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentManageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // status bar 高度 → Toolbar / 抽屉顶部 padding。
        // 抽屉底部不再补 navBars：系统导航栏那块现在被底部导航占着，再补一次会多出一段空白。
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            binding.toolbarManage.updatePadding(top = statusBars.top)
            binding.authorDrawer.updatePadding(top = statusBars.top, bottom = 0)
            insets
        }

        setupMenu()

        // 多选时标题可能被右侧菜单挤到省略，点一下 Toolbar 用 Toast 把数量完整弹出来
        binding.toolbarManage.setOnClickListener {
            if (isInSelectionMode) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.manage_selected_count, currentSelectionCount),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        // 导航图标只在多选态出现（关闭图标 = 退出多选）
        binding.toolbarManage.setNavigationOnClickListener {
            if (isInSelectionMode) viewModel.exitSelectionMode(currentTab)
        }

        // 子 Fragment 挂在 childFragmentManager 上，它们经 requireParentFragment() 取到本页的 ViewModel
        binding.viewPagerManage.adapter = ManagePagerAdapter(this)

        TabLayoutMediator(binding.tabLayoutManage, binding.viewPagerManage) { tab, position ->
            tab.text = when (position) {
                ManageViewModel.TAB_VIDEO -> getString(R.string.manage_tab_videos)
                ManageViewModel.TAB_IMAGE -> getString(R.string.manage_tab_images)
                else -> ""
            }
        }.attach()

        // Tab 切换时退出上一个 Tab 的多选模式 / 折叠搜索框
        binding.viewPagerManage.registerOnPageChangeCallback(pageChangeCallback)

        setupAuthorDrawer()
        observeViewModel()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        updateToolbar()
        updateBackCallback()
    }

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        private var previousItem = ManageViewModel.TAB_VIDEO
        override fun onPageSelected(position: Int) {
            viewModel.exitSelectionMode(previousItem)
            // 搜索范围按 Tab 独立维护；切 Tab 时收起搜索框，旧 Tab 在重新打开时自动是非搜索态
            collapseAndResetSearch()
            previousItem = position
            updateToolbar()
            refreshMenu()
            updateBackCallback()
        }
    }

    /** 切走本页时收起搜索框；隐藏的页面还要让出返回键。 */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) collapseAndResetSearch()
        updateBackCallback()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 多选状态变化 → 刷新 Toolbar 标题与菜单
                launch {
                    viewModel.selection.collect {
                        updateToolbar()
                        refreshMenu()
                        updateBackCallback()
                    }
                }
                // 筛选条件变化 → 菜单标题回显当前筛选状态。
                //
                // **只看影响菜单标题的那四层**：搜索词也在 filters 里，若整体订阅，
                // 每敲一个字都会刷一次菜单，纯属无用功。
                launch {
                    viewModel.filters
                        .map { all -> all.mapValues { (_, f) -> f.menuTitleSignature } }
                        .distinctUntilChanged()
                        .collect { refreshMenu() }
                }
                launch { viewModel.authors.collect { onAuthorsLoaded(it) } }
                launch { viewModel.tagQueryTags.collect { onTagQueryTagsLoaded(it) } }
                launch { viewModel.stats.collect { showStatsDialog(it) } }
                launch { viewModel.zipProgress.collect { renderZipProgress(it) } }
                launch { viewModel.zipResult.collect { onZipFinished(it) } }
                launch { viewModel.lanState.collect { renderLanState(it) } }
                launch { viewModel.lanError.collect { onLanStartFailed(it) } }
                launch { viewModel.lanPreparing.collect { renderLanPreparing(it) } }
            }
        }
    }

    /** 本页可见、且（抽屉打开 或 多选中）时才拦返回键，其余情况让给外壳。 */
    private fun updateBackCallback() {
        val binding = _binding ?: return
        backCallback.isEnabled = !isHidden &&
            (binding.drawerLayout.isDrawerOpen(GravityCompat.END) || isInSelectionMode)
    }

    // ── 按作者筛选抽屉 ───────────────────────────────────────────────────────────

    private fun setupAuthorDrawer() {
        authorAdapter = AuthorFilterAdapter { author ->
            viewModel.applyAuthorFilter(currentTab, author.secUserId, author.name)
            authorAdapter.setSelected(author.secUserId.ifBlank { author.name })
            binding.drawerLayout.closeDrawer(GravityCompat.END)
        }
        binding.rvAuthorFilter.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAuthorFilter.adapter = authorAdapter

        binding.etAuthorSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                authorAdapter.filter(s?.toString().orEmpty())
                updateAuthorEmptyState()
            }
        })

        binding.btnAuthorClear.setOnClickListener {
            viewModel.applyAuthorFilter(currentTab, null, null)
            authorAdapter.setSelected(null)
            binding.drawerLayout.closeDrawer(GravityCompat.END)
        }

        // 默认锁闭，避免右缘滑动与 ViewPager2 换页冲突；仅通过按钮打开，关闭后重新锁闭。
        binding.drawerLayout.setDrawerLockMode(
            DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.END,
        )
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                updateBackCallback()
            }

            override fun onDrawerClosed(drawerView: View) {
                binding.drawerLayout.setDrawerLockMode(
                    DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.END,
                )
                updateBackCallback()
            }
        })
    }

    /** 作者聚合加载完成：填充列表、预选中当前筛选作者、打开抽屉。 */
    private fun onAuthorsLoaded(authors: List<AuthorCount>) {
        authorAdapter.submit(authors)
        authorAdapter.setSelected(viewModel.filtersOf(currentTab).authorKey)
        binding.etAuthorSearch.setText("")
        updateAuthorEmptyState()
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.END)
        binding.drawerLayout.openDrawer(GravityCompat.END)
    }

    /** 打开作者抽屉：加载当前 Tab（mediaType）的作者聚合，加载完成后由 [onAuthorsLoaded] 打开。 */
    private fun openAuthorDrawer() {
        viewModel.loadAuthors(currentTab)
    }

    private fun updateAuthorEmptyState() {
        binding.tvAuthorEmpty.visibility =
            if (authorAdapter.itemCountShown() == 0) View.VISIBLE else View.GONE
    }

    // ── 排序 ───────────────────────────────────────────────────────────────────

    private fun showSortDialog() {
        val orders = ManageSortOrder.values()
        val labels = orders.map { getString(it.labelRes) }.toTypedArray()
        val checked = orders.indexOf(viewModel.filtersOf(currentTab).sort)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manage_sort_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                viewModel.applySort(currentTab, orders[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── 按归属筛选 ─────────────────────────────────────────────────────────────

    /**
     * 「按归属筛选」是三态：不筛选 / 排除无归属 / 仅看无归属（反选）。
     * 它叠加在当前 Tab 已有的搜索、标签、作者筛选之上，因此这里只切状态，不清其他筛选。
     */
    private fun showRelationFilterDialog() {
        val filters = ManageRelationFilter.values()
        val labels = filters.map { getString(it.labelRes) }.toTypedArray()
        val checked = filters.indexOf(viewModel.filtersOf(currentTab).relation)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manage_relation_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                viewModel.applyRelationFilter(currentTab, filters[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 菜单标题：未筛选时用原文案，筛选中则附上当前模式，避免"筛了但看不出来"。 */
    private fun relationMenuTitle(): String {
        val filter = viewModel.filtersOf(currentTab).relation
        return if (filter == ManageRelationFilter.OFF) {
            getString(R.string.manage_menu_filter_relation)
        } else {
            getString(R.string.manage_menu_filter_relation_active, getString(filter.labelRes))
        }
    }

    // ── 按标签数量筛选 ─────────────────────────────────────────────────────────

    /**
     * 「按标签数量筛选」：0 个 …… / 5 个及以上（上限见 [ManageTagCountFilter.MAX_COUNT]），
     * **多选，档位之间取并集**；一个都不勾即不筛选，中间的「清除筛选」是快捷清空。
     * 与归属筛选一样是叠加的一层，只切状态。
     */
    private fun showTagCountFilterDialog() {
        val filters = ManageTagCountFilter.values()
        val labels = filters.map { getString(it.labelRes) }.toTypedArray()
        val current = viewModel.filtersOf(currentTab).tagCounts
        // 勾选状态在数组里累积，「确定」时才一次性提交，避免每勾一下就重刷列表
        val checked = BooleanArray(filters.size) { filters[it] in current }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manage_tag_count_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.applyTagCountFilters(
                    currentTab,
                    filters.filterIndexed { i, _ -> checked[i] }.toSet(),
                )
            }
            .setNeutralButton(R.string.manage_tag_count_clear) { _, _ ->
                viewModel.applyTagCountFilters(currentTab, emptySet())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 菜单标题：未筛选时用原文案，筛选中则附上已勾选档位（短文案拼接）。 */
    private fun tagCountMenuTitle(): String {
        val filters = viewModel.filtersOf(currentTab).tagCounts
        return if (filters.isEmpty()) {
            getString(R.string.manage_menu_filter_tag_count)
        } else {
            getString(
                R.string.manage_menu_filter_tag_count_active,
                ManageTagCountFilter.shortSummary(requireContext(), filters),
            )
        }
    }

    // ── 按标签修改次数筛选 ─────────────────────────────────────────────────────

    /**
     * 「按标签修改次数筛选」：不限 / 改过 0 次 …… / 改过 5 次以上（逐档上限见
     * [ManageTagEditCountFilter.MAX_COUNT]）。与归属筛选一样是叠加的一层，只切状态。
     */
    private fun showTagEditCountFilterDialog() {
        val filters = ManageTagEditCountFilter.values()
        val labels = filters.map { getString(it.labelRes) }.toTypedArray()
        val checked = filters.indexOf(viewModel.filtersOf(currentTab).tagEditCount)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manage_tag_edit_count_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                viewModel.applyTagEditCountFilter(currentTab, filters[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 菜单标题：未筛选时用原文案，筛选中则附上当前档位。 */
    private fun tagEditCountMenuTitle(): String {
        val filter = viewModel.filtersOf(currentTab).tagEditCount
        return if (!filter.isActive) {
            getString(R.string.manage_menu_filter_tag_edit_count)
        } else {
            getString(R.string.manage_menu_filter_tag_edit_count_active, getString(filter.labelRes))
        }
    }

    // ── 标签精细检索 ───────────────────────────────────────────────────────────

    /**
     * 可选标签加载完成 → 弹规则对话框。
     *
     * **在这里捕获 tab**：对话框展示期间用户仍可切 Tab，若等到「确定」回调里再读
     * `currentTab`，条件就会落到另一个 Tab 上（与 [handleExportLan] 同一类坑）。
     */
    private fun onTagQueryTagsLoaded(allTags: List<String>) {
        if (allTags.isEmpty()) {
            Toast.makeText(requireContext(), R.string.manage_set_tags_no_tags, Toast.LENGTH_SHORT).show()
            return
        }
        val tab = currentTab
        TagQueryDialog.show(
            context = requireContext(),
            allTags = allTags,
            current = viewModel.filtersOf(tab).tagQuery,
        ) { query ->
            viewModel.applyTagQuery(tab, query)
        }
    }

    /**
     * 菜单标题：未筛选时用原文案，筛选中则附上规则条数。
     * 只回显条数而不是完整表达式——表达式可能很长，塞进菜单项会被截断。
     */
    private fun tagQueryMenuTitle(): String {
        val query = viewModel.filtersOf(currentTab).tagQuery
        return if (!query.isActive) {
            getString(R.string.manage_menu_filter_tag_query)
        } else {
            getString(R.string.manage_menu_filter_tag_query_active, query.activeRuleCount)
        }
    }

    // ── 统计面板 ───────────────────────────────────────────────────────────────

    private fun showStatsDialog(stats: ManageStats) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manage_stats_title)
            .setMessage(buildStatsText(stats))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun buildStatsText(s: ManageStats): String = buildString {
        append(getString(R.string.manage_stats_count_line, s.videoCount, s.imageCount, s.totalCount))
        append("\n\n")
        append(getString(R.string.manage_stats_size_title)).append('\n')
        append(getString(R.string.manage_stats_size_video, humanSize(s.videoBytes))).append('\n')
        append(getString(R.string.manage_stats_size_image, humanSize(s.imageBytes))).append('\n')
        append(getString(R.string.manage_stats_size_cover, humanSize(s.coverBytes))).append('\n')
        append(getString(R.string.manage_stats_size_total, humanSize(s.totalBytes)))
        append("\n\n")
        append(getString(R.string.manage_stats_top_authors)).append('\n')
        if (s.topAuthors.isEmpty()) {
            append(getString(R.string.manage_stats_none))
        } else {
            s.topAuthors.forEachIndexed { i, a ->
                val name = a.name.ifBlank { getString(R.string.manage_author_unknown) }
                append("${i + 1}. $name (${a.count})")
                if (i < s.topAuthors.lastIndex) append('\n')
            }
        }
        append("\n\n")
        append(getString(R.string.manage_stats_top_tags)).append('\n')
        if (s.topTags.isEmpty()) {
            append(getString(R.string.manage_stats_none))
        } else {
            s.topTags.forEachIndexed { i, t ->
                append("${i + 1}. ${t.tagName} (${t.count})")
                if (i < s.topTags.lastIndex) append('\n')
            }
        }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    }

    // ── 菜单 ───────────────────────────────────────────────────────────────────

    /**
     * 菜单只在这里 inflate 一次。之后的所有刷新走 [applyMenuState]（改既有 MenuItem 的
     * `isVisible` / `title`），**不要**再 inflate —— 重新 inflate 会把 SearchView 整个重建，
     * 折叠、清空、丢焦点，根本没法输入。
     */
    private fun setupMenu() {
        val toolbar = binding.toolbarManage
        toolbar.menu.clear()
        toolbar.inflateMenu(R.menu.menu_manage)
        searchMenuItem = toolbar.menu.findItem(R.id.action_search)
        setupSearchView(searchMenuItem)
        toolbar.setOnMenuItemClickListener { onMenuItemSelected(it) }
        applyMenuState(toolbar.menu)
    }

    /** 取代 `invalidateOptionsMenu()`：视图还在时按当前状态重刷菜单项。 */
    private fun refreshMenu() {
        val binding = _binding ?: return
        applyMenuState(binding.toolbarManage.menu)
    }

    /** 取代 `onPrepareOptionsMenu`。 */
    private fun applyMenuState(menu: Menu) {
        val search = menu.findItem(R.id.action_search)
        val clearInvalid = menu.findItem(R.id.action_clear_invalid)
        val deleteSelected = menu.findItem(R.id.action_delete_selected)
        val setTagsSelected = menu.findItem(R.id.action_set_tags_selected)
        val selectAll = menu.findItem(R.id.action_select_all)
        val manageTags = menu.findItem(R.id.action_manage_tags)

        val filterAuthor = menu.findItem(R.id.action_filter_author)
        val filterRelation = menu.findItem(R.id.action_filter_relation)
        val filterTagCount = menu.findItem(R.id.action_filter_tag_count)
        val filterTagEditCount = menu.findItem(R.id.action_filter_tag_edit_count)
        val filterTagQuery = menu.findItem(R.id.action_filter_tag_query)
        val sort = menu.findItem(R.id.action_sort)
        val stats = menu.findItem(R.id.action_stats)

        val exportZip = menu.findItem(R.id.action_export_zip)
        val exportLan = menu.findItem(R.id.action_export_lan)

        // 标签相关的能力只有视频 Tab 具备，直接按 Tab 判定
        val onVideoTab = currentTab == ManageViewModel.TAB_VIDEO

        if (isInSelectionMode) {
            // 多选模式：让出 Toolbar 给删除/标签按钮，其余入口在多选状态下都无意义
            search?.isVisible = false
            clearInvalid?.isVisible = false
            manageTags?.isVisible = false
            filterAuthor?.isVisible = false
            filterRelation?.isVisible = false
            filterTagCount?.isVisible = false
            filterTagEditCount?.isVisible = false
            filterTagQuery?.isVisible = false
            sort?.isVisible = false
            stats?.isVisible = false
            deleteSelected?.isVisible = true
            deleteSelected?.title = getString(R.string.manage_menu_delete_selected_count, currentSelectionCount)
            setTagsSelected?.isVisible = onVideoTab
            // 全选 / 取消全选：标题按当前是否已全选动态切换
            selectAll?.isVisible = true
            selectAll?.setTitle(
                if (viewModel.isAllSelected(currentTab)) {
                    R.string.manage_menu_deselect_all
                } else {
                    R.string.manage_menu_select_all
                },
            )
            // 导出仅在多选态出现（溢出菜单）
            exportZip?.isVisible = true
            exportLan?.isVisible = true
        } else {
            search?.isVisible = true
            // 「清除已失效」仅在视频 Tab 下显示
            clearInvalid?.isVisible = onVideoTab
            manageTags?.isVisible = true
            filterAuthor?.isVisible = true
            filterRelation?.isVisible = true
            filterRelation?.title = relationMenuTitle()
            // 「按标签数量筛选」「按标签修改次数筛选」同样只在有标签功能的视频 Tab 下显示
            filterTagCount?.isVisible = onVideoTab
            if (onVideoTab) filterTagCount?.title = tagCountMenuTitle()
            filterTagEditCount?.isVisible = onVideoTab
            if (onVideoTab) filterTagEditCount?.title = tagEditCountMenuTitle()
            // 标签精细检索同样只在有标签功能的视频 Tab 下显示
            filterTagQuery?.isVisible = onVideoTab
            if (onVideoTab) filterTagQuery?.title = tagQueryMenuTitle()
            sort?.isVisible = true
            stats?.isVisible = true
            deleteSelected?.isVisible = false
            setTagsSelected?.isVisible = false
            selectAll?.isVisible = false
            exportZip?.isVisible = false
            exportLan?.isVisible = false
        }
    }

    /** 配置 Toolbar 搜索框：监听文本变化即查询，折叠时清空并恢复正常列表。 */
    private fun setupSearchView(item: MenuItem?) {
        val sv = item?.actionView as? SearchView ?: return
        sv.queryHint = getString(R.string.manage_search_hint)
        sv.setIconifiedByDefault(true)

        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.applySearchQuery(currentTab, query)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                // 实时搜索：空串等价于退出搜索，ViewModel 内会恢复分页
                viewModel.applySearchQuery(currentTab, newText)
                return true
            }
        })

        MenuItemCompat.setOnActionExpandListener(item, object : MenuItemCompat.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                // 折叠时立即恢复当前 Tab 的非搜索状态
                viewModel.applySearchQuery(currentTab, null)
                return true
            }
        })
    }

    /** Tab 切换 / 切走本页时收起搜索框并清空查询；折叠监听会触发对应 Tab 的非搜索恢复。 */
    private fun collapseAndResetSearch() {
        val item = searchMenuItem ?: return
        if (item.isActionViewExpanded) {
            item.collapseActionView()
        }
    }

    /** 取代 `onOptionsItemSelected`；返回箭头那一支由 `setNavigationOnClickListener` 承接。 */
    private fun onMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_set_tags_selected -> {
                viewModel.requestSetTagsSelected(currentTab)
                true
            }
            R.id.action_delete_selected -> {
                confirmDeleteSelected()
                true
            }
            R.id.action_select_all -> {
                viewModel.toggleSelectAll(currentTab)
                true
            }
            R.id.action_export_zip -> {
                handleExportZip()
                true
            }
            R.id.action_export_lan -> {
                handleExportLan()
                true
            }
            R.id.action_clear_invalid -> {
                confirmClearInvalid()
                true
            }
            R.id.action_manage_tags -> {
                startActivity(Intent(requireContext(), TagManageActivity::class.java))
                true
            }
            R.id.action_filter_author -> {
                openAuthorDrawer()
                true
            }
            R.id.action_filter_relation -> {
                showRelationFilterDialog()
                true
            }
            R.id.action_filter_tag_count -> {
                showTagCountFilterDialog()
                true
            }
            R.id.action_filter_tag_edit_count -> {
                showTagEditCountFilterDialog()
                true
            }
            R.id.action_filter_tag_query -> {
                viewModel.loadTagsForQuery()
                true
            }
            R.id.action_sort -> {
                showSortDialog()
                true
            }
            R.id.action_stats -> {
                viewModel.loadStats()
                true
            }
            else -> false
        }
    }

    private fun confirmDeleteSelected() {
        val count = currentSelectionCount
        if (count == 0) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manage_confirm_delete_title)
            .setMessage(getString(R.string.manage_confirm_delete_msg, count))
            .setPositiveButton(R.string.manage_confirm_ok) { _, _ ->
                viewModel.requestDeleteSelected(currentTab)
            }
            .setNegativeButton(R.string.manage_confirm_cancel, null)
            .show()
    }

    private fun confirmClearInvalid() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manage_menu_clear_invalid)
            .setMessage(R.string.manage_clear_invalid_confirm)
            .setPositiveButton(R.string.manage_confirm_ok) { _, _ ->
                viewModel.requestClearInvalid(currentTab)
            }
            .setNegativeButton(R.string.manage_confirm_cancel, null)
            .show()
    }

    // ── 导出选中 ───────────────────────────────────────────────────────────────

    /** 方案④：打包 zip 到 `Download/bDouyin/export`，手机连电脑后拷这一个文件。 */
    private fun handleExportZip() {
        val entities = viewModel.selectedEntities(currentTab)
        if (entities.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manage_export_zip_confirm_title)
            .setMessage(getString(R.string.manage_export_zip_confirm_msg, entities.size))
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.exportToZip(entities) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun renderZipProgress(progress: ZipProgress?) {
        if (progress == null) {
            zipProgressDialog?.dismiss()
            zipProgressDialog = null
            return
        }
        val dialog = zipProgressDialog ?: ProgressDialog(requireContext()).apply {
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setMessage(getString(R.string.manage_export_zip_doing))
            setCancelable(false)
            isIndeterminate = false
            show()
            zipProgressDialog = this
        }
        dialog.max = progress.total
        dialog.progress = progress.done
    }

    private fun onZipFinished(result: Result<MediaExportManager.ZipResult>) {
        result.fold(
            onSuccess = { r ->
                Toast.makeText(
                    requireContext(),
                    getString(R.string.manage_export_zip_done, r.fileCount, r.zipFile.absolutePath),
                    Toast.LENGTH_LONG,
                ).show()
                viewModel.exitSelectionMode(currentTab)
            },
            onFailure = { e ->
                val msg = if (e is IllegalStateException) {
                    getString(R.string.manage_export_none)
                } else {
                    getString(R.string.manage_export_zip_failed, e.message ?: e.javaClass.simpleName)
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            },
        )
    }

    /**
     * 方案③：起局域网 HTTP 服务，电脑同 WiFi 用浏览器下载，无需数据线 / 第三方 App。
     *
     * 选中项里有导出过的（`exportCount > 0`）时先三选一确认：
     * 取消 / 过滤已导出（只导出 `exportCount == 0` 的）/ 确认（全部导出，允许重复）。
     */
    private fun handleExportLan() {
        // tab 与 entities 必须在同一时刻捕获：确认对话框展示期间用户仍可切 Tab，
        // 若在按钮回调里再读 currentTab 就可能与这里捕获的 entities 对不上——
        // 本分支起 splitByOrientation 也由 tab 决定，错配会导致按错误的口径分包/不分包。
        val tab = currentTab
        val entities = viewModel.selectedEntities(tab)
        if (entities.isEmpty()) return
        val notExported = entities.filter { it.exportCount <= 0 }
        val exportedCount = entities.size - notExported.size
        if (exportedCount == 0) {
            startLanExport(tab, entities)
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manage_export_lan_reexport_title)
            .setMessage(
                getString(
                    R.string.manage_export_lan_reexport_msg,
                    entities.size,
                    exportedCount,
                    notExported.size,
                ),
            )
            .setPositiveButton(R.string.manage_export_lan_reexport_confirm) { _, _ ->
                startLanExport(tab, entities)
            }
            // 中间键 = 过滤已导出：全都导出过时过滤结果为空，只提示不起服务
            .setNeutralButton(R.string.manage_export_lan_reexport_filter) { _, _ ->
                if (notExported.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.manage_export_lan_reexport_none,
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    startLanExport(tab, notExported)
                }
            }
            .setNegativeButton(R.string.manage_confirm_cancel, null)
            .show()
    }

    private fun startLanExport(tab: Int, entities: List<DownloadedVideoEntity>) {
        viewModel.startLanExport(tab, entities)
    }

    private fun onLanStartFailed(failure: LanStartFailure) {
        val msg = when (failure) {
            LanStartFailure.NoWifi -> getString(R.string.manage_export_lan_no_wifi)
            LanStartFailure.NothingToExport -> getString(R.string.manage_export_none)
            is LanStartFailure.StartFailed ->
                getString(R.string.manage_export_lan_failed, failure.message)
        }
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }

    /**
     * 导出前的宽高回填探测进度（v14）。历史记录首次导出可能要探几百个文件、耗时数秒，
     * 没有提示界面会像卡死；之后是纯读库，这个对话框不会再出现。
     */
    @Suppress("DEPRECATION")
    private fun renderLanPreparing(progress: LanPrepareProgress?) {
        if (progress == null) {
            lanPrepareDialog?.dismiss()
            lanPrepareDialog = null
            return
        }
        val dialog = lanPrepareDialog ?: ProgressDialog(requireContext()).apply {
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setMessage(getString(R.string.manage_export_lan_preparing))
            setCancelable(false)
            isIndeterminate = false
            show()
            lanPrepareDialog = this
        }
        dialog.max = progress.total
        dialog.progress = progress.done
    }

    /**
     * 局域网导出对话框。用自定义 View 而不是 `setMessage`，因为传输状态行要在服务运行期间
     * 随传输事件实时刷新（`setMessage` 在 `show()` 之后改不动）。
     */
    private fun renderLanState(state: LanExportState?) {
        if (state == null) {
            lanDialog?.dismiss()
            lanDialog = null
            lanStatusView = null
            return
        }
        if (lanDialog == null) showLanDialog(state)
        lanStatusView?.text = lanStatusText(state)
    }

    private fun lanStatusText(state: LanExportState): CharSequence {
        val last = state.lastTransfer ?: return getString(R.string.manage_export_lan_status_idle)
        return if (last.isZip) {
            getString(R.string.manage_export_lan_status_zip, last.itemCount, state.transferCount)
        } else {
            getString(R.string.manage_export_lan_status_file, last.label, state.transferCount)
        }
    }

    private fun showLanDialog(state: LanExportState) {
        val content = layoutInflater.inflate(R.layout.dialog_lan_export, null)
        content.findViewById<TextView>(R.id.tvLanHint).text =
            getString(R.string.manage_export_lan_hint, state.fileCount)
        content.findViewById<TextView>(R.id.tvLanSplit).apply {
            if (state.splitByOrientation && (state.landscapeCount > 0 || state.portraitCount > 0)) {
                text = getString(
                    R.string.manage_export_lan_split_hint,
                    state.landscapeCount,
                    state.portraitCount,
                )
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        content.findViewById<TextView>(R.id.tvLanUrl).text = state.url
        lanStatusView = content.findViewById<TextView>(R.id.tvLanStatus).apply {
            setText(R.string.manage_export_lan_status_idle)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.manage_export_lan_title)
            .setView(content)
            .setPositiveButton(R.string.manage_export_lan_stop, null)
            .setNeutralButton(R.string.manage_export_lan_copy, null)
            .setOnDismissListener {
                lanDialog = null
                lanStatusView = null
                viewModel.stopLanExport()
            }
            .create()
        dialog.show()
        lanDialog = dialog
        // 「复制网址」不关闭对话框，让服务继续跑
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("bDouyin export url", state.url))
            Toast.makeText(requireContext(), R.string.manage_export_lan_copied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        // 对话框随视图销毁，但服务本身活在 ViewModel 里：
        // 转屏不再中断传输，真正的关闭在 ManageViewModel.onCleared()。
        // 先摘掉 dismiss 监听再 dismiss，否则会误调 stopLanExport() 把服务停掉。
        lanDialog?.setOnDismissListener(null)
        lanDialog?.dismiss()
        lanDialog = null
        lanStatusView = null
        zipProgressDialog?.dismiss()
        zipProgressDialog = null
        lanPrepareDialog?.dismiss()
        lanPrepareDialog = null
        searchMenuItem = null
        // 只摘监听，不把 adapter 置空：FragmentStateAdapter 置空会在 onDestroyView 时机去
        // 拆 childFragmentManager 里的子 Fragment，容易撞上「onSaveInstanceState 之后不能提交」。
        // ViewPager2 本身随视图丢弃，adapter 不会泄漏。
        binding.viewPagerManage.unregisterOnPageChangeCallback(pageChangeCallback)
        _binding = null
        super.onDestroyView()
    }

    private fun updateToolbar() {
        val binding = _binding ?: return
        if (isInSelectionMode) {
            binding.toolbarManage.setNavigationIcon(R.drawable.ic_close_manage)
            binding.toolbarManage.title = getString(R.string.manage_selected_count, currentSelectionCount)
        } else {
            // 根 tab 没有向上导航，非多选态不显示导航图标
            binding.toolbarManage.navigationIcon = null
            binding.toolbarManage.title = getString(R.string.manage_title)
        }
    }

    private class ManagePagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int): Fragment = when (position) {
            ManageViewModel.TAB_VIDEO -> ManageVideoFragment()
            ManageViewModel.TAB_IMAGE -> ManageImageFragment()
            else -> throw IllegalStateException("Unknown manage tab: $position")
        }
    }
}
