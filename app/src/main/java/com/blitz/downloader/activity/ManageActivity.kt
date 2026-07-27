package com.blitz.downloader.activity

import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
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
import com.blitz.downloader.data.db.DatabaseBackupManager
import com.blitz.downloader.data.db.DownloadedVideoEntity
import com.blitz.downloader.databinding.ActivityManageBinding
import com.blitz.downloader.download.MediaExportManager
import com.blitz.downloader.net.LanFileServer
import com.blitz.downloader.ui.AuthorFilterAdapter
import com.blitz.downloader.ui.ManageImageFragment
import com.blitz.downloader.ui.ManageTabFragment
import com.blitz.downloader.ui.ManageVideoFragment
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

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
        authorAdapter = AuthorFilterAdapter { name ->
            getCurrentTabFragment()?.applyAuthorFilter(name)
            authorAdapter.setSelected(name)
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
            getCurrentTabFragment()?.applyAuthorFilter(null)
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
        val currentAuthor = getCurrentTabFragment()?.activeAuthorName
        lifecycleScope.launch {
            val authors = withContext(Dispatchers.IO) { downloadedRepo.getAuthorCounts(mediaType) }
            authorAdapter.submit(authors)
            authorAdapter.setSelected(currentAuthor)
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

        val backup = menu.findItem(R.id.action_backup_db)
        val restore = menu.findItem(R.id.action_restore_db)
        val filterAuthor = menu.findItem(R.id.action_filter_author)

        val exportZip = menu.findItem(R.id.action_export_zip)
        val exportLan = menu.findItem(R.id.action_export_lan)

        if (isInSelectionMode) {
            // 多选模式：让出 Toolbar 给删除/标签按钮，且搜索/备份在多选状态下都无意义
            search?.isVisible = false
            clearInvalid?.isVisible = false
            manageTags?.isVisible = false
            backup?.isVisible = false
            restore?.isVisible = false
            filterAuthor?.isVisible = false
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
            backup?.isVisible = true
            restore?.isVisible = true
            filterAuthor?.isVisible = true
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
            R.id.action_backup_db -> {
                confirmAndBackup()
                true
            }
            R.id.action_restore_db -> {
                pickAndRestoreBackup()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── 备份 / 恢复 ────────────────────────────────────────────────────────────

    /** 二次确认后在 IO 线程执行 [DatabaseBackupManager.backupNow]，结果用 Toast 提示。 */
    private fun confirmAndBackup() {
        AlertDialog.Builder(this)
            .setTitle(R.string.manage_backup_confirm_title)
            .setMessage(R.string.manage_backup_confirm_msg)
            .setPositiveButton(android.R.string.ok) { _, _ -> doBackup() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doBackup() {
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(this).apply {
            setMessage(getString(R.string.manage_backup_doing))
            setCancelable(false)
            show()
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DatabaseBackupManager.backupNow(applicationContext) }
            }
            progress.dismiss()
            result.fold(
                onSuccess = { file ->
                    Toast.makeText(
                        this@ManageActivity,
                        getString(R.string.manage_backup_done, file.absolutePath),
                        Toast.LENGTH_LONG,
                    ).show()
                },
                onFailure = { e ->
                    Toast.makeText(
                        this@ManageActivity,
                        getString(R.string.manage_backup_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    /** 列出已有备份让用户选；选定后再二次确认并执行 [doRestore]。 */
    private fun pickAndRestoreBackup() {
        lifecycleScope.launch {
            val backups = withContext(Dispatchers.IO) { DatabaseBackupManager.listBackups() }
            if (backups.isEmpty()) {
                Toast.makeText(
                    this@ManageActivity,
                    R.string.manage_restore_no_backups,
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val labels = backups.map { it.displayLabel() }.toTypedArray()
            AlertDialog.Builder(this@ManageActivity)
                .setTitle(R.string.manage_restore_pick_title)
                .setItems(labels) { _, which ->
                    confirmAndRestore(backups[which])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun confirmAndRestore(entry: DatabaseBackupManager.BackupEntry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.manage_restore_confirm_title)
            .setMessage(R.string.manage_restore_confirm_msg)
            .setPositiveButton(android.R.string.ok) { _, _ -> doRestore(entry) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doRestore(entry: DatabaseBackupManager.BackupEntry) {
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(this).apply {
            setMessage(getString(R.string.manage_restore_doing))
            setCancelable(false)
            show()
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DatabaseBackupManager.restoreFrom(applicationContext, entry) }
            }
            progress.dismiss()
            result.fold(
                onSuccess = { restartApp() },
                onFailure = { e ->
                    Toast.makeText(
                        this@ManageActivity,
                        getString(R.string.manage_restore_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    /**
     * 恢复完成后必须重启进程：Room/SQLite 在进程内可能残留页缓存或仓库层引用，
     * 重启是最干净的兜底（也避免 UI 显示恢复前已加载的旧数据）。
     */
    private fun restartApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        startActivity(launchIntent)
        finishAffinity()
        Process.killProcess(Process.myPid())
        exitProcess(0)
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

    /** 方案③：起局域网 HTTP 服务，电脑同 WiFi 用浏览器下载，无需数据线 / 第三方 App。 */
    private fun handleExportLan() {
        val entities = getCurrentTabFragment()?.getSelectedEntities().orEmpty()
        if (entities.isEmpty()) return
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
            val server = LanFileServer(files)
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

    private fun showLanDialog(url: String, count: Int) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.manage_export_lan_title)
            .setMessage(getString(R.string.manage_export_lan_msg, count, url))
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

    private fun stopLanServer() {
        lanServer?.stop()
        lanServer = null
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
