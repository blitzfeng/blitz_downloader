package com.blitz.downloader.activity

import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.blitz.downloader.R
import com.blitz.downloader.config.AppSettings
import com.blitz.downloader.data.db.DatabaseBackupManager
import com.blitz.downloader.databinding.ActivitySettingsBinding
import com.blitz.downloader.viewmodel.SettingsEvent
import com.blitz.downloader.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/**
 * 设置页，由 [MainActivity] 的 Toolbar 设置入口进入。
 *
 * 目前承载数据库备份 / 恢复（原先挂在 [ManageActivity] 的溢出菜单里）。
 * 恢复走两条路径：直接 File 读取，以及重装 / 换签名导致备份文件在 MediaStore 被孤儿化时
 * 的 SAF 兜底（详见 CLAUDE.md「持久化」一节）。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val viewModel: SettingsViewModel by viewModels()

    @Suppress("DEPRECATION")
    private var progressDialog: ProgressDialog? = null

    /** SAF 文件选择器：选中备份 .db 后经授权 Uri 恢复，绕过 MediaStore 归属限制（重装后可用）。 */
    private val restoreFilePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) confirmAndRestoreUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarSettings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // status bar 高度 → Toolbar 顶部 padding；导航栏 → 内容底部 padding。
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(navBars.left, 0, navBars.right, 0)
            binding.toolbarSettings.setPadding(0, statusBars.top, 0, 0)
            binding.settingsScroll.updatePadding(bottom = navBars.bottom)
            insets
        }

        binding.itemBackupDb.setOnClickListener { confirmAndBackup() }
        binding.itemRestoreDb.setOnClickListener { viewModel.loadBackups() }
        binding.itemTagFilterMode.setOnClickListener { showTagFilterModeDialog() }
        refreshTagFilterModeSummary()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.busy.collect { renderBusy(it) } }
                launch { viewModel.events.collect { handleEvent(it) } }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun renderBusy(kind: SettingsViewModel.BusyKind?) {
        if (kind == null) {
            progressDialog?.dismiss()
            progressDialog = null
            return
        }
        if (progressDialog != null) return
        progressDialog = ProgressDialog(this).apply {
            setMessage(
                getString(
                    if (kind == SettingsViewModel.BusyKind.BACKUP) {
                        R.string.manage_backup_doing
                    } else {
                        R.string.manage_restore_doing
                    },
                ),
            )
            setCancelable(false)
            show()
        }
    }

    private fun handleEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.BackupDone ->
                toast(getString(R.string.manage_backup_done, event.file.absolutePath))
            is SettingsEvent.BackupFailed ->
                toast(getString(R.string.manage_backup_failed, event.message))
            is SettingsEvent.ShowBackupPicker -> showBackupPicker(event.backups)
            SettingsEvent.NoBackupsFound -> {
                // 没有可列出的备份：直接走文件选择（备份可能存在但已孤儿化，列不出/读不了）
                toast(getString(R.string.manage_restore_no_backups))
                launchRestoreFilePicker()
            }
            SettingsEvent.RestoreDone -> restartApp()
            is SettingsEvent.RestoreFailed ->
                toast(getString(R.string.manage_restore_failed, event.message))
            SettingsEvent.RestoreNeedsFilePicker -> showRestoreDeniedHint()
        }
    }

    private fun toast(text: CharSequence) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        progressDialog?.dismiss()
        progressDialog = null
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── 筛选设置 ───────────────────────────────────────────────────────────────

    /**
     * 管理页标签栏多选时取交集还是并集。
     * 管理页每次取数时现读 [AppSettings]，所以这里改完不需要通知它。
     */
    private fun showTagFilterModeDialog() {
        val options = arrayOf(
            getString(R.string.settings_tag_filter_mode_all),
            getString(R.string.settings_tag_filter_mode_any),
        )
        val checked = if (AppSettings.isTagFilterMatchAll(this)) 0 else 1
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_tag_filter_mode)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                AppSettings.setTagFilterMatchAll(this, which == 0)
                refreshTagFilterModeSummary()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshTagFilterModeSummary() {
        binding.tvTagFilterModeSummary.setText(
            if (AppSettings.isTagFilterMatchAll(this)) {
                R.string.settings_tag_filter_mode_all_short
            } else {
                R.string.settings_tag_filter_mode_any_short
            }
        )
    }

    // ── 备份 / 恢复 ────────────────────────────────────────────────────────────

    /** 二次确认后交给 ViewModel 执行备份，结果用 Toast 提示。 */
    private fun confirmAndBackup() {
        AlertDialog.Builder(this)
            .setTitle(R.string.manage_backup_confirm_title)
            .setMessage(R.string.manage_backup_confirm_msg)
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.backup() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 列出已有备份让用户选；首项固定为「从文件选择」（SAF），用于重装后备份文件被孤儿化、
     * 无法直接 File 读取的场景。选定后二次确认并执行恢复。
     */
    private fun showBackupPicker(backups: List<DatabaseBackupManager.BackupEntry>) {
        val labels = (listOf(getString(R.string.manage_restore_from_file)) +
            backups.map { it.displayLabel() }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.manage_restore_pick_title)
            .setItems(labels) { _, which ->
                if (which == 0) launchRestoreFilePicker() else confirmAndRestore(backups[which - 1])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchRestoreFilePicker() {
        // db 文件 MIME 不稳定，用 */* 让用户自由选取；SAF 授权 Uri 可绕过归属校验
        runCatching { restoreFilePickerLauncher.launch(arrayOf("*/*")) }
            .onFailure {
                Toast.makeText(this, R.string.manage_restore_picker_unavailable, Toast.LENGTH_LONG).show()
            }
    }

    private fun confirmAndRestoreUri(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.manage_restore_confirm_title)
            .setMessage(R.string.manage_restore_confirm_msg)
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.restoreFromUri(uri) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmAndRestore(entry: DatabaseBackupManager.BackupEntry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.manage_restore_confirm_title)
            .setMessage(R.string.manage_restore_confirm_msg)
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.restoreFrom(entry) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 备份文件被孤儿化（重装 / 换签名），File 读取被拒 → 引导改用文件选择器。 */
    private fun showRestoreDeniedHint() {
        AlertDialog.Builder(this)
            .setTitle(R.string.manage_restore_confirm_title)
            .setMessage(R.string.manage_restore_denied_hint)
            .setPositiveButton(R.string.manage_restore_from_file) { _, _ -> launchRestoreFilePicker() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
}
