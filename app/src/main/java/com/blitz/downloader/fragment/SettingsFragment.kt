package com.blitz.downloader.fragment

import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.blitz.downloader.R
import com.blitz.downloader.config.AppSettings
import com.blitz.downloader.data.db.DatabaseBackupManager
import com.blitz.downloader.databinding.FragmentSettingsBinding
import com.blitz.downloader.dialog.AllFilesAccessDialogFragment
import com.blitz.downloader.util.MediaVisibilityManager
import com.blitz.downloader.util.MediaVisibilityManager.MediaFolder
import com.blitz.downloader.viewmodel.SettingsEvent
import com.blitz.downloader.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/**
 * 底部导航「设置」页。
 *
 * 承载数据库备份 / 恢复（原先挂在管理页溢出菜单里）与标签筛选模式设置。
 * 恢复走两条路径：直接 File 读取，以及重装 / 换签名导致备份文件在 MediaStore 被孤儿化时
 * 的 SAF 兜底（详见 CLAUDE.md「持久化」一节）。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    @Suppress("DEPRECATION")
    private var progressDialog: ProgressDialog? = null

    /**
     * SAF 文件选择器：选中备份 .db 后经授权 Uri 恢复，绕过 MediaStore 归属限制（重装后可用）。
     * 必须是 Fragment 的顶层属性——注册要发生在 onCreate 之前。
     */
    private val restoreFilePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) confirmAndRestoreUri(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // status bar 高度 → Toolbar 顶部 padding；底部导航的 inset 由外壳处理。
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            binding.toolbarSettings.updatePadding(top = statusBars.top)
            insets
        }

        binding.itemBackupDb.setOnClickListener { confirmAndBackup() }
        binding.itemRestoreDb.setOnClickListener { viewModel.loadBackups() }
        binding.itemTagFilterMode.setOnClickListener { showTagFilterModeDialog() }
        refreshTagFilterModeSummary()

        pendingHideFolder = savedInstanceState?.getString(STATE_PENDING_HIDE_FOLDER)
        binding.itemHideVideos.setOnClickListener { onFolderRowClicked(MediaFolder.VIDEOS) }
        binding.itemHideImages.setOnClickListener { onFolderRowClicked(MediaFolder.IMAGES) }
        registerAllFilesAccessResult()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.busy.collect { renderBusy(it) } }
                launch { viewModel.events.collect { handleEvent(it) } }
                launch { viewModel.folderVisibility.collect { renderFolderVisibility(it) } }
            }
        }
    }

    /**
     * 每次回到前台都重新探磁盘：用户可能刚从系统权限页返回，也可能在 App 外用文件管理器
     * 动过 `.nomedia`。这里也是「授权后继续未完成的隐藏动作」的落点，见 [pendingHideFolder]。
     */
    override fun onResume() {
        super.onResume()
        viewModel.refreshFolderVisibility()
        consumePendingHideFolder()
    }

    @Suppress("DEPRECATION")
    private fun renderBusy(kind: SettingsViewModel.BusyKind?) {
        if (kind == null) {
            progressDialog?.dismiss()
            progressDialog = null
            return
        }
        if (progressDialog != null) return
        progressDialog = ProgressDialog(requireContext()).apply {
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
            is SettingsEvent.NeedsAllFilesAccess -> showAllFilesAccessDialog(event.folder)
            is SettingsEvent.FolderVisibilityChanged -> toast(
                getString(
                    if (event.hidden) {
                        R.string.settings_hide_done_hidden
                    } else {
                        R.string.settings_hide_done_visible
                    },
                ),
            )
        }
    }

    private fun toast(text: CharSequence) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_HIDE_FOLDER, pendingHideFolder)
    }

    override fun onDestroyView() {
        progressDialog?.dismiss()
        progressDialog = null
        _binding = null
        super.onDestroyView()
    }

    // ── 筛选设置 ───────────────────────────────────────────────────────────────

    /**
     * 管理页标签栏多选时取交集还是并集。
     * 管理页每次取数时现读 [AppSettings]，所以这里改完不需要通知它。
     */
    private fun showTagFilterModeDialog() {
        val context = requireContext()
        val options = arrayOf(
            getString(R.string.settings_tag_filter_mode_all),
            getString(R.string.settings_tag_filter_mode_any),
        )
        val checked = if (AppSettings.isTagFilterMatchAll(context)) 0 else 1
        AlertDialog.Builder(context)
            .setTitle(R.string.settings_tag_filter_mode)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                AppSettings.setTagFilterMatchAll(context, which == 0)
                refreshTagFilterModeSummary()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshTagFilterModeSummary() {
        binding.tvTagFilterModeSummary.setText(
            if (AppSettings.isTagFilterMatchAll(requireContext())) {
                R.string.settings_tag_filter_mode_all_short
            } else {
                R.string.settings_tag_filter_mode_any_short
            }
        )
    }

    // ── 相册可见性 ─────────────────────────────────────────────────────────────

    /**
     * 用户点了「隐藏」但缺权限时，先记下是哪个目录，跳系统设置页；回到本页时（[onResume]）
     * 若权限已到手就继续把它做完。
     *
     * 存成 String 而非枚举是为了能塞进 `savedInstanceState`——跳系统页期间本 Fragment 可能被回收，
     * 放在纯内存字段里回来就丢了，用户会觉得「授权了却什么也没发生」。
     */
    private var pendingHideFolder: String? = null

    private fun onFolderRowClicked(folder: MediaFolder) {
        val hidden = viewModel.folderVisibility.value[folder] ?: return
        // 取反即目标状态；开启方向缺权限时 ViewModel 会回 NeedsAllFilesAccess 事件
        viewModel.setFolderHidden(folder, !hidden)
    }

    private fun renderFolderVisibility(state: Map<MediaFolder, Boolean>) {
        binding.tvHideVideosSummary.setText(summaryFor(state[MediaFolder.VIDEOS]))
        binding.tvHideImagesSummary.setText(summaryFor(state[MediaFolder.IMAGES]))
    }

    private fun summaryFor(hidden: Boolean?): Int = when (hidden) {
        true -> R.string.settings_hide_state_hidden
        else -> R.string.settings_hide_state_visible
    }

    private fun showAllFilesAccessDialog(folder: MediaFolder) {
        AllFilesAccessDialogFragment.newInstance(folder.name)
            .show(parentFragmentManager, AllFilesAccessDialogFragment.REQUEST_KEY)
    }

    /** 弹窗点「去设置」→ 记下待办目录并跳系统页。结果不看返回值，统一在 [onResume] 里判权限。 */
    private fun registerAllFilesAccessResult() {
        parentFragmentManager.setFragmentResultListener(
            AllFilesAccessDialogFragment.REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            pendingHideFolder = bundle.getString(AllFilesAccessDialogFragment.RESULT_FOLDER)
            openAllFilesAccessSettings()
        }
    }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val uri = Uri.parse("package:${requireContext().packageName}")
        // 带包名的直达页在个别 ROM 上缺失，退回全局列表页让用户自己找本应用
        val direct = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
        runCatching { startActivity(direct) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                .onFailure { toast(getString(R.string.settings_all_files_access_denied)) }
        }
    }

    /** 从系统权限页回来：授到了就把当初那次「隐藏」补做完，没授到就明确告诉用户已取消。 */
    private fun consumePendingHideFolder() {
        val folderName = pendingHideFolder ?: return
        pendingHideFolder = null
        val folder = MediaFolder.entries.firstOrNull { it.name == folderName } ?: return
        if (MediaVisibilityManager.hasAllFilesAccess()) {
            viewModel.setFolderHidden(folder, true)
        } else {
            toast(getString(R.string.settings_all_files_access_denied))
        }
    }

    // ── 备份 / 恢复 ────────────────────────────────────────────────────────────

    /** 二次确认后交给 ViewModel 执行备份，结果用 Toast 提示。 */
    private fun confirmAndBackup() {
        AlertDialog.Builder(requireContext())
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
        AlertDialog.Builder(requireContext())
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
                Toast.makeText(
                    requireContext(),
                    R.string.manage_restore_picker_unavailable,
                    Toast.LENGTH_LONG,
                ).show()
            }
    }

    private fun confirmAndRestoreUri(uri: Uri) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manage_restore_confirm_title)
            .setMessage(R.string.manage_restore_confirm_msg)
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.restoreFromUri(uri) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmAndRestore(entry: DatabaseBackupManager.BackupEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manage_restore_confirm_title)
            .setMessage(R.string.manage_restore_confirm_msg)
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.restoreFrom(entry) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 备份文件被孤儿化（重装 / 换签名），File 读取被拒 → 引导改用文件选择器。 */
    private fun showRestoreDeniedHint() {
        AlertDialog.Builder(requireContext())
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
        val activity = requireActivity()
        val launchIntent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            ?: Intent(activity, com.blitz.downloader.activity.MainActivity::class.java)
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        startActivity(launchIntent)
        activity.finishAffinity()
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    private companion object {
        const val STATE_PENDING_HIDE_FOLDER = "pending_hide_folder"
    }
}
