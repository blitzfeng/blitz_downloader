package com.blitz.downloader.activity

import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.GravityCompat
import androidx.core.view.MenuItemCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import android.content.Intent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.blitz.downloader.R
import com.blitz.downloader.data.DownloadedVideoRepository
import com.blitz.downloader.data.VideoTagRepository
import com.blitz.downloader.data.db.DownloadedVideoEntity
import com.blitz.downloader.databinding.ActivityManageBinding
import com.blitz.downloader.download.BatchDownloadCoordinator
import com.blitz.downloader.download.MediaExportManager
import com.blitz.downloader.net.LanFileServer
import com.blitz.downloader.ui.AuthorFilterAdapter
import com.blitz.downloader.ui.ManageImageFragment
import com.blitz.downloader.ui.ManageRelationFilter
import com.blitz.downloader.ui.ManageSortOrder
import com.blitz.downloader.ui.ManageTagCountFilter
import com.blitz.downloader.ui.ManageTabFragment
import com.blitz.downloader.ui.ManageVideoFragment
import java.io.File
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManageBinding
    private var isInSelectionMode = false
    private var currentSelectionCount = 0
    /** onCreate 时保存的默认返回箭头图标，退出多选模式时恢复。 */
    private var defaultNavIcon: Drawable? = null
    /** 当前 SearchView 输入内容（搜索栏展开时持有；折叠后清空）。 */
    private var currentSearchQuery: String = ""
    /** 当前 SearchView 是否处于展开状态。 */
    private var isSearchExpanded: Boolean = false
    /** 记录当前菜单中的 SearchView，便于切 Tab 时折叠/清空。 */
    private var searchMenuItem: MenuItem? = null

    /** 「发送到电脑」局域网服务，页面销毁 / 用户停止时关闭。 */
    private var lanServer: LanFileServer? = null

    /** 局域网对话框里的传输状态行；对话框关闭后置空（服务运行期间才有效）。 */
    private var lanStatusView: TextView? = null

    /** 本次局域网服务已完成的传输次数（单文件下载 / 整包各算一次）。 */
    private var lanTransferCount = 0

    /**
     * 导出计数落库用的独立 scope。
     *
     * 传输完成事件可能在用户刚点「停止服务」/ 页面即将销毁时到达，用 `lifecycleScope` 会被
     * 取消掉导致计数丢失；作用域内只有一次短 UPDATE，且只持有 application 级的 dao，
     * 不会泄漏 Activity，故不做取消。
     */
    private val exportCountScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 作者筛选抽屉的列表 Adapter。 */
    private lateinit var authorAdapter: AuthorFilterAdapter
    private val downloadedRepo by lazy { DownloadedVideoRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        enableEdgeToEdge()
        binding = ActivityManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        // status bar 高度 → Toolbar 顶部 padding；导航栏 → 内容底部 padding。
        // padding 施加在内容容器与抽屉上（不是 DrawerLayout 本身，否则会挤压抽屉宽度）。
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.manageRoot.setPadding(navBars.left, 0, navBars.right, navBars.bottom)
            binding.toolbar.setPadding(0, statusBars.top, 0, 0)
            binding.authorDrawer.setPadding(0, statusBars.top, 0, navBars.bottom)
            insets
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        defaultNavIcon = binding.toolbar.navigationIcon

        // 多选时标题可能被右侧菜单挤到省略，点一下 Toolbar 用 Toast 把数量完整弹出来
        binding.toolbar.setOnClickListener {
            if (isInSelectionMode) {
                Toast.makeText(
                    this,
                    getString(R.string.manage_selected_count, currentSelectionCount),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.viewPager.adapter = ManagePagerAdapter(this)

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.manage_tab_videos)
                1 -> getString(R.string.manage_tab_images)
                else -> ""
            }
        }.attach()

        // Tab 切换时退出当前 Tab 的多选模式 / 折叠搜索框
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            private var previousItem = 0
            override fun onPageSelected(position: Int) {
                if (isInSelectionMode) {
                    getTabFragment(previousItem)?.exitSelectionMode()
                }
                // 搜索范围按 Tab 独立维护；切 Tab 时收起搜索框，旧 Tab 在重新打开时自动是非搜索态
                collapseAndResetSearch()
                previousItem = position
                invalidateOptionsMenu()
            }
        })

        setupAuthorDrawer()

        // 返回键：抽屉打开时先收抽屉；其次多选模式先退出多选
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.END)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.END)
                    return
                }
                val fragment = getCurrentTabFragment()
                if (fragment != null && fragment.inSelectionMode) {
                    fragment.exitSelectionMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // ── 按作者筛选抽屉 ───────────────────────────────────────────────────────────

    private fun setupAuthorDrawer() {
        authorAdapter = AuthorFilterAdapter { author ->
            getCurrentTabFragment()?.applyAuthorFilter(author.secUserId, author.name)
            authorAdapter.setSelected(author.secUserId.ifBlank { author.name })
            binding.drawerLayout.closeDrawer(GravityCompat.END)
        }
        binding.rvAuthorFilter.layoutManager = LinearLayoutManager(this)
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
            getCurrentTabFragment()?.applyAuthorFilter(null, null)
            authorAdapter.setSelected(null)
            binding.drawerLayout.closeDrawer(GravityCompat.END)
        }

        // 默认锁闭，避免右缘滑动与 ViewPager2 换页冲突；仅通过按钮打开，关闭后重新锁闭。
        binding.drawerLayout.setDrawerLockMode(
            DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.END
        )
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerClosed(drawerView: View) {
                binding.drawerLayout.setDrawerLockMode(
                    DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.END
                )
            }
        })
    }

    /** 打开作者抽屉：加载当前 Tab（mediaType）的作者聚合，预选中当前筛选作者。 */
    private fun openAuthorDrawer() {
        val mediaType = if (binding.viewPager.currentItem == 0) MEDIA_TYPE_VIDEO else MEDIA_TYPE_IMAGE
        val currentKey = getCurrentTabFragment()?.activeAuthorKey
        lifecycleScope.launch {
            val authors = withContext(Dispatchers.IO) { downloadedRepo.getAuthorCounts(mediaType) }
            authorAdapter.submit(authors)
            authorAdapter.setSelected(currentKey)
            binding.etAuthorSearch.setText("")
            updateAuthorEmptyState()
            binding.drawerLayout.setDrawerLockMode(
                DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.END
            )
            binding.drawerLayout.openDrawer(GravityCompat.END)
        }
    }

    private fun updateAuthorEmptyState() {
        binding.tvAuthorEmpty.visibility =
            if (authorAdapter.itemCountShown() == 0) View.VISIBLE else View.GONE
    }

    // ── 排序 ───────────────────────────────────────────────────────────────────

    private fun showSortDialog() {
        val fragment = getCurrentTabFragment() ?: return
        val orders = ManageSortOrder.values()
        val labels = orders.map { getString(it.labelRes) }.toTypedArray()
        val checked = orders.indexOf(fragment.activeSortOrder)
        AlertDialog.Builder(this)
            .setTitle(R.string.manage_sort_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                fragment.applySort(orders[which])
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
        val fragment = getCurrentTabFragment() ?: return
        val filters = ManageRelationFilter.values()
        val labels = filters.map { getString(it.labelRes) }.toTypedArray()
        val checked = filters.indexOf(fragment.activeRelationFilter)
        AlertDialog.Builder(this)
            .setTitle(R.string.manage_relation_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                fragment.applyRelationFilter(filters[which])
                dialog.dismiss()
                invalidateOptionsMenu() // 菜单标题回显当前筛选状态
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 菜单标题：未筛选时用原文案，筛选中则附上当前模式，避免"筛了但看不出来"。 */
    private fun relationMenuTitle(): String {
        val filter = getCurrentTabFragment()?.activeRelationFilter ?: ManageRelationFilter.DEFAULT
        return if (filter == ManageRelationFilter.OFF) {
            getString(R.string.manage_menu_filter_relation)
        } else {
            getString(R.string.manage_menu_filter_relation_active, getString(filter.labelRes))
        }
    }

    // ── 按标签数量筛选 ─────────────────────────────────────────────────────────

    /**
     * 「按标签数量筛选」：不限 / 0 个 …… / 5 个及以上（上限见
     * [ManageTagCountFilter.MAX_COUNT]）。与归属筛选一样是叠加的一层，只切状态。
     */
    private fun showTagCountFilterDialog() {
        val fragment = getCurrentTabFragment() ?: return
        if (!fragment.supportsTagCountFilter) return
        val filters = ManageTagCountFilter.values()
        val labels = filters.map { getString(it.labelRes) }.toTypedArray()
        val checked = filters.indexOf(fragment.activeTagCountFilter)
        AlertDialog.Builder(this)
            .setTitle(R.string.manage_tag_count_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                fragment.applyTagCountFilter(filters[which])
                dialog.dismiss()
                invalidateOptionsMenu() // 菜单标题回显当前筛选状态
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 菜单标题：未筛选时用原文案，筛选中则附上当前档位。 */
    private fun tagCountMenuTitle(): String {
        val filter = getCurrentTabFragment()?.activeTagCountFilter ?: ManageTagCountFilter.DEFAULT
        return if (!filter.isActive) {
            getString(R.string.manage_menu_filter_tag_count)
        } else {
            getString(R.string.manage_menu_filter_tag_count_active, getString(filter.labelRes))
        }
    }

    // ── 统计面板 ───────────────────────────────────────────────────────────────

    private fun showStatsDialog() {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { buildStatsText() }
            AlertDialog.Builder(this@ManageActivity)
                .setTitle(R.string.manage_stats_title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** 在 IO 线程汇总统计文本：数量、占用空间、Top 作者、Top 标签。 */
    private suspend fun buildStatsText(): String {
        val videoCount = downloadedRepo.countByMediaType(MEDIA_TYPE_VIDEO)
        val imageCount = downloadedRepo.countByMediaType(MEDIA_TYPE_IMAGE)

        val videoBytes = dirSize(BatchDownloadCoordinator.VIDEO_SUBDIR)
        val imageBytes = dirSize(BatchDownloadCoordinator.IMAGE_SUBDIR)
        val coverBytes = dirSize(BatchDownloadCoordinator.COVER_SUBDIR)
        val totalBytes = videoBytes + imageBytes + coverBytes

        val topAuthors = downloadedRepo.getAuthorCountsAll().take(TOP_N)
        val topTags = VideoTagRepository(this).getTagsWithCount().take(TOP_N)

        return buildString {
            append(getString(R.string.manage_stats_count_line, videoCount, imageCount, videoCount + imageCount))
            append("\n\n")
            append(getString(R.string.manage_stats_size_title)).append('\n')
            append(getString(R.string.manage_stats_size_video, humanSize(videoBytes))).append('\n')
            append(getString(R.string.manage_stats_size_image, humanSize(imageBytes))).append('\n')
            append(getString(R.string.manage_stats_size_cover, humanSize(coverBytes))).append('\n')
            append(getString(R.string.manage_stats_size_total, humanSize(totalBytes)))
            append("\n\n")
            append(getString(R.string.manage_stats_top_authors)).append('\n')
            if (topAuthors.isEmpty()) {
                append(getString(R.string.manage_stats_none))
            } else {
                topAuthors.forEachIndexed { i, a ->
                    val name = a.name.ifBlank { getString(R.string.manage_author_unknown) }
                    append("${i + 1}. $name (${a.count})")
                    if (i < topAuthors.lastIndex) append('\n')
                }
            }
            append("\n\n")
            append(getString(R.string.manage_stats_top_tags)).append('\n')
            if (topTags.isEmpty()) {
                append(getString(R.string.manage_stats_none))
            } else {
                topTags.forEachIndexed { i, t ->
                    append("${i + 1}. ${t.tagName} (${t.count})")
                    if (i < topTags.lastIndex) append('\n')
                }
            }
        }
    }

    private fun dirSize(subDir: String): Long {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(root, subDir)
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { runCatching { it.length() }.getOrDefault(0L) }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    }

    /** 由 Fragment 回调：多选模式或选中数量发生变化。 */
    fun onSelectionChanged(active: Boolean, count: Int) {
        isInSelectionMode = active
        currentSelectionCount = count
        updateToolbar()
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_manage, menu)
        searchMenuItem = menu.findItem(R.id.action_search)
        setupSearchView(searchMenuItem)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val search = menu.findItem(R.id.action_search)
        val clearInvalid = menu.findItem(R.id.action_clear_invalid)
        val deleteSelected = menu.findItem(R.id.action_delete_selected)
        val setTagsSelected = menu.findItem(R.id.action_set_tags_selected)
        val selectAll = menu.findItem(R.id.action_select_all)
        val manageTags = menu.findItem(R.id.action_manage_tags)

        val filterAuthor = menu.findItem(R.id.action_filter_author)
        val filterRelation = menu.findItem(R.id.action_filter_relation)
        val filterTagCount = menu.findItem(R.id.action_filter_tag_count)
        val sort = menu.findItem(R.id.action_sort)
        val stats = menu.findItem(R.id.action_stats)

        val exportZip = menu.findItem(R.id.action_export_zip)
        val exportLan = menu.findItem(R.id.action_export_lan)

        if (isInSelectionMode) {
            // 多选模式：让出 Toolbar 给删除/标签按钮，其余入口在多选状态下都无意义
            search?.isVisible = false
            clearInvalid?.isVisible = false
            manageTags?.isVisible = false
            filterAuthor?.isVisible = false
            filterRelation?.isVisible = false
            filterTagCount?.isVisible = false
            sort?.isVisible = false
            stats?.isVisible = false
            deleteSelected?.isVisible = true
            deleteSelected?.title = getString(R.string.manage_menu_delete_selected_count, currentSelectionCount)
            setTagsSelected?.isVisible = true
            // 全选 / 取消全选：标题按当前是否已全选动态切换
            selectAll?.isVisible = true
            val allSelected = getCurrentTabFragment()?.isAllSelected == true
            selectAll?.setTitle(
                if (allSelected) R.string.manage_menu_deselect_all else R.string.manage_menu_select_all
            )
            // 导出仅在多选态出现（溢出菜单）
            exportZip?.isVisible = true
            exportLan?.isVisible = true
        } else {
            search?.isVisible = true
            // 「清除已失效」仅在视频 Tab（position==0）下显示
            val onVideoTab = binding.viewPager.currentItem == 0
            clearInvalid?.isVisible = onVideoTab
            manageTags?.isVisible = true
            filterAuthor?.isVisible = true
            filterRelation?.isVisible = true
            filterRelation?.title = relationMenuTitle()
            // 「按标签数量筛选」只在有标签功能的 Tab（视频）下显示
            val supportsTagCount = getCurrentTabFragment()?.supportsTagCountFilter == true
            filterTagCount?.isVisible = supportsTagCount
            if (supportsTagCount) filterTagCount?.title = tagCountMenuTitle()
            sort?.isVisible = true
            stats?.isVisible = true
            deleteSelected?.isVisible = false
            setTagsSelected?.isVisible = false
            selectAll?.isVisible = false
            exportZip?.isVisible = false
            exportLan?.isVisible = false
        }
        return super.onPrepareOptionsMenu(menu)
    }

    /**
     * 配置 Toolbar 搜索框：监听文本变化即查询，折叠时清空并恢复正常列表。
     * 输入回调里都先 `getCurrentTabFragment()` 再调用 [ManageTabFragment.applySearchQuery]，
     * 由 Fragment 自行决定是否查询及如何展示。
     */
    private fun setupSearchView(item: MenuItem?) {
        val sv = item?.actionView as? SearchView ?: return
        sv.queryHint = getString(R.string.manage_search_hint)
        sv.setIconifiedByDefault(true)

        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                currentSearchQuery = query.orEmpty()
                getCurrentTabFragment()?.applySearchQuery(currentSearchQuery)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                currentSearchQuery = newText.orEmpty()
                // 实时搜索：空串等价于退出搜索，Fragment 内会恢复分页
                getCurrentTabFragment()?.applySearchQuery(currentSearchQuery)
                return true
            }
        })

        MenuItemCompat.setOnActionExpandListener(item, object : MenuItemCompat.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                isSearchExpanded = true
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                isSearchExpanded = false
                currentSearchQuery = ""
                // 折叠时立即恢复当前 Tab 的非搜索状态
                getCurrentTabFragment()?.applySearchQuery(null)
                return true
            }
        })
    }

    /** Tab 切换时收起搜索框并清空查询；折叠监听会触发各 Fragment 的非搜索恢复。 */
    private fun collapseAndResetSearch() {
        val item = searchMenuItem ?: return
        if (item.isActionViewExpanded) {
            item.collapseActionView()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                handleHomeButton()
                true
            }
            R.id.action_set_tags_selected -> {
                getCurrentTabFragment()?.handleSetTagsSelected()
                true
            }
            R.id.action_delete_selected -> {
                getCurrentTabFragment()?.handleDeleteSelected()
                true
            }
            R.id.action_select_all -> {
                getCurrentTabFragment()?.handleToggleSelectAll()
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
                getCurrentTabFragment()?.handleClearInvalid()
                true
            }
            R.id.action_manage_tags -> {
                startActivity(Intent(this, TagManageActivity::class.java))
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
            R.id.action_sort -> {
                showSortDialog()
                true
            }
            R.id.action_stats -> {
                showStatsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── 导出选中 ───────────────────────────────────────────────────────────────

    /** 方案④：打包 zip 到 `Download/bDouyin/export`，手机连电脑后拷这一个文件。 */
    private fun handleExportZip() {
        val entities = getCurrentTabFragment()?.getSelectedEntities().orEmpty()
        if (entities.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.manage_export_zip_confirm_title)
            .setMessage(getString(R.string.manage_export_zip_confirm_msg, entities.size))
            .setPositiveButton(android.R.string.ok) { _, _ -> doExportZip(entities) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doExportZip(entities: List<DownloadedVideoEntity>) {
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(this).apply {
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setMessage(getString(R.string.manage_export_zip_doing))
            setCancelable(false)
            isIndeterminate = false
            show()
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    MediaExportManager.exportToZip(applicationContext, entities) { done, total ->
                        runOnUiThread {
                            progress.max = total
                            progress.progress = done
                        }
                    }
                }
            }
            progress.dismiss()
            result.fold(
                onSuccess = { r ->
                    Toast.makeText(
                        this@ManageActivity,
                        getString(R.string.manage_export_zip_done, r.fileCount, r.zipFile.absolutePath),
                        Toast.LENGTH_LONG,
                    ).show()
                    getCurrentTabFragment()?.exitSelectionMode()
                },
                onFailure = { e ->
                    val msg = if (e is IllegalStateException) {
                        getString(R.string.manage_export_none)
                    } else {
                        getString(R.string.manage_export_zip_failed, e.message ?: e.javaClass.simpleName)
                    }
                    Toast.makeText(this@ManageActivity, msg, Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    /**
     * 方案③：起局域网 HTTP 服务，电脑同 WiFi 用浏览器下载，无需数据线 / 第三方 App。
     *
     * 选中项里有导出过的（`exportCount > 0`）时先三选一确认：
     * 取消 / 过滤已导出（只导出 `exportCount == 0` 的）/ 确认（全部导出，允许重复）。
     */
    private fun handleExportLan() {
        val entities = getCurrentTabFragment()?.getSelectedEntities().orEmpty()
        if (entities.isEmpty()) return
        val notExported = entities.filter { it.exportCount <= 0 }
        val exportedCount = entities.size - notExported.size
        if (exportedCount > 0) {
            AlertDialog.Builder(this)
                .setTitle(R.string.manage_export_lan_reexport_title)
                .setMessage(
                    getString(
                        R.string.manage_export_lan_reexport_msg,
                        entities.size,
                        exportedCount,
                        notExported.size,
                    )
                )
                .setPositiveButton(R.string.manage_export_lan_reexport_confirm) { _, _ ->
                    startLanExport(entities)
                }
                // 中间键 = 过滤已导出：全都导出过时过滤结果为空，只提示不起服务
                .setNeutralButton(R.string.manage_export_lan_reexport_filter) { _, _ ->
                    if (notExported.isEmpty()) {
                        Toast.makeText(
                            this,
                            R.string.manage_export_lan_reexport_none,
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        startLanExport(notExported)
                    }
                }
                .setNegativeButton(R.string.manage_confirm_cancel, null)
                .show()
            return
        }
        startLanExport(entities)
    }

    private fun startLanExport(entities: List<DownloadedVideoEntity>) {
        val ip = LanFileServer.localIpv4()
        if (ip == null) {
            Toast.makeText(this, R.string.manage_export_lan_no_wifi, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { MediaExportManager.resolveExportFiles(entities) }
            if (files.isEmpty()) {
                Toast.makeText(this@ManageActivity, R.string.manage_export_none, Toast.LENGTH_LONG).show()
                return@launch
            }
            stopLanServer() // 若已有服务在跑，先停掉
            val server = LanFileServer(files) { event -> onLanTransferComplete(event) }
            val port = try {
                server.start()
            } catch (e: Exception) {
                Toast.makeText(
                    this@ManageActivity,
                    getString(R.string.manage_export_lan_failed, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            lanServer = server
            showLanDialog("http://$ip:$port/", files.size)
        }
    }

    /**
     * 局域网导出对话框。用自定义 View 而不是 `setMessage`，因为传输状态行要在服务运行期间
     * 随 [onLanTransferComplete] 实时刷新（`setMessage` 在 `show()` 之后改不动）。
     */
    private fun showLanDialog(url: String, count: Int) {
        val content = layoutInflater.inflate(R.layout.dialog_lan_export, null)
        content.findViewById<TextView>(R.id.tvLanHint).text =
            getString(R.string.manage_export_lan_hint, count)
        content.findViewById<TextView>(R.id.tvLanUrl).text = url
        val status = content.findViewById<TextView>(R.id.tvLanStatus)
        status.setText(R.string.manage_export_lan_status_idle)
        lanStatusView = status
        lanTransferCount = 0

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.manage_export_lan_title)
            .setView(content)
            .setPositiveButton(R.string.manage_export_lan_stop, null)
            .setNeutralButton(R.string.manage_export_lan_copy, null)
            .setOnDismissListener { stopLanServer() }
            .create()
        dialog.show()
        // 「复制网址」不关闭对话框，让服务继续跑
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("bDouyin export url", url))
            Toast.makeText(this, R.string.manage_export_lan_copied, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 传输完成回调：**在 [LanFileServer] 的连接线程触发**，这里负责分流——
     * 计数落库走 [exportCountScope]（IO），UI 刷新切主线程。
     *
     * 语义只到"手机已把字节完整发出"；电脑侧是否保存成功探知不到（HTTP 无反向通道），
     * 所以这是提示性计数，不是权威状态。
     */
    private fun onLanTransferComplete(event: LanFileServer.TransferEvent) {
        exportCountScope.launch {
            runCatching { downloadedRepo.incrementExportCount(event.awemeIds) }
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            lanTransferCount += 1
            // 当前列表里的对应条目立刻显示「已导出」标记
            getCurrentTabFragment()?.markExported(event.awemeIds)
            lanStatusView?.text = if (event.isZip) {
                getString(R.string.manage_export_lan_status_zip, event.awemeIds.size, lanTransferCount)
            } else {
                getString(R.string.manage_export_lan_status_file, event.label, lanTransferCount)
            }
        }
    }

    private fun stopLanServer() {
        lanServer?.stop()
        lanServer = null
        lanStatusView = null
    }

    override fun onDestroy() {
        stopLanServer()
        super.onDestroy()
    }

    private fun handleHomeButton() {
        val fragment = getCurrentTabFragment()
        if (fragment != null && fragment.inSelectionMode) {
            fragment.exitSelectionMode()
        } else {
            finish()
        }
    }

    private fun updateToolbar() {
        if (isInSelectionMode) {
            binding.toolbar.setNavigationIcon(R.drawable.ic_close_manage)
            binding.toolbar.title = getString(R.string.manage_selected_count, currentSelectionCount)
        } else {
            binding.toolbar.navigationIcon = defaultNavIcon
            binding.toolbar.title = getString(R.string.manage_title)
        }
    }

    private fun getCurrentTabFragment(): ManageTabFragment? =
        getTabFragment(binding.viewPager.currentItem)

    private fun getTabFragment(position: Int): ManageTabFragment? =
        supportFragmentManager.findFragmentByTag("f$position") as? ManageTabFragment

    companion object {
        private const val MEDIA_TYPE_VIDEO = "video"
        private const val MEDIA_TYPE_IMAGE = "image"
        private const val TOP_N = 8
    }

    private class ManagePagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> ManageVideoFragment()
            1 -> ManageImageFragment()
            else -> throw IllegalStateException("Unknown manage tab: $position")
        }
    }
}
