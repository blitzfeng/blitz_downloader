package com.blitz.downloader.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.config.AppSettings
import com.blitz.downloader.data.db.DatabaseBackupManager
import com.blitz.downloader.util.MediaVisibilityManager
import com.blitz.downloader.util.MediaVisibilityManager.MediaFolder
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页的数据库备份 / 恢复。
 *
 * 恢复有两条路径（详见 CLAUDE.md「持久化」一节）：
 * - [restoreFrom]：直接 File 读取；
 * - [restoreFromUri]：重装 / 换签名后备份文件在 MediaStore 被孤儿化、File 读取抛
 *   `SecurityException` 时的 SAF 兜底，授权 Uri 绕过归属校验。
 *
 * **不要**把恢复退回成「只 File 读取」。
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val _busy = MutableStateFlow<BusyKind?>(null)

    /** 非 null 表示有耗时操作进行中，Activity 据此显示进度对话框。 */
    val busy: StateFlow<BusyKind?> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>(replay = 0, extraBufferCapacity = 8)
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    fun backup() {
        if (_busy.value != null) return
        _busy.value = BusyKind.BACKUP
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DatabaseBackupManager.backupNow(getApplication()) }
            }
            _busy.value = null
            result.fold(
                onSuccess = { emit(SettingsEvent.BackupDone(it)) },
                onFailure = { emit(SettingsEvent.BackupFailed(it.readableMessage())) },
            )
        }
    }

    /**
     * 列出已有备份供用户选择。
     *
     * 列不出来不代表没有备份——重装后旧备份会被孤儿化而读不到，
     * 所以空列表时也要引导用户走 SAF 文件选择器。
     */
    fun loadBackups() {
        viewModelScope.launch {
            val backups = withContext(Dispatchers.IO) { DatabaseBackupManager.listBackups() }
            emit(
                if (backups.isEmpty()) {
                    SettingsEvent.NoBackupsFound
                } else {
                    SettingsEvent.ShowBackupPicker(backups)
                },
            )
        }
    }

    fun restoreFrom(entry: DatabaseBackupManager.BackupEntry) {
        if (_busy.value != null) return
        _busy.value = BusyKind.RESTORE
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DatabaseBackupManager.restoreFrom(getApplication(), entry) }
            }
            _busy.value = null
            result.fold(
                onSuccess = { emit(SettingsEvent.RestoreDone) },
                onFailure = { e ->
                    emit(
                        // 备份文件被孤儿化（重装 / 换签名），File 读取被拒 → 引导改用文件选择器
                        if (e is SecurityException) {
                            SettingsEvent.RestoreNeedsFilePicker
                        } else {
                            SettingsEvent.RestoreFailed(e.readableMessage())
                        },
                    )
                },
            )
        }
    }

    fun restoreFromUri(uri: Uri) {
        if (_busy.value != null) return
        _busy.value = BusyKind.RESTORE
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openInputStream(uri)?.use { input ->
                        DatabaseBackupManager.restoreFromStream(getApplication(), input)
                    } ?: throw IOException("无法打开所选文件")
                }
            }
            _busy.value = null
            result.fold(
                onSuccess = { emit(SettingsEvent.RestoreDone) },
                onFailure = { emit(SettingsEvent.RestoreFailed(it.readableMessage())) },
            )
        }
    }

    // ── 相册可见性 ─────────────────────────────────────────────────────────────

    private val _folderVisibility = MutableStateFlow<Map<MediaFolder, Boolean>>(emptyMap())

    /** 各可切换目录当前是否对相册隐藏；权威在磁盘（`.nomedia` 在不在），这里只是缓存给 UI 渲染。 */
    val folderVisibility: StateFlow<Map<MediaFolder, Boolean>> = _folderVisibility.asStateFlow()

    /**
     * 重新探测磁盘状态。视图层在每次 `onResume` 调一次——用户可能刚从系统权限页回来，
     * 也可能用文件管理器在 App 外改动过 `.nomedia`。
     */
    fun refreshFolderVisibility() {
        viewModelScope.launch {
            _folderVisibility.value = withContext(Dispatchers.IO) {
                MediaFolder.entries.associateWith { MediaVisibilityManager.isHidden(it) }
            }
        }
    }

    /**
     * 切换某个目录的相册可见性。
     *
     * **开启方向的权限校验在视图层**（要弹说明并跳系统设置页，属于纯 UI 流程）；
     * 这里再兜一次底，避免任何调用路径漏检查把媒体锁死——代价只是一次 `Environment` 查询。
     */
    fun setFolderHidden(folder: MediaFolder, hidden: Boolean) {
        if (hidden && !MediaVisibilityManager.hasAllFilesAccess()) {
            emit(SettingsEvent.NeedsAllFilesAccess(folder))
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                MediaVisibilityManager.setHidden(getApplication(), folder, hidden)
            }
            refreshFolderVisibility()
            emit(SettingsEvent.FolderVisibilityChanged(folder, hidden))
        }
    }

    // ── 标签智能预选（author_tag_frequency 缓存表） ───────────────────────────────

    /**
     * 全量重算作者-标签高频缓存。非破坏性、可重复执行的衍生数据重建，不需要二次确认
     * （区别于备份恢复那类有风险的操作）。
     */
    fun analyzeTagFrequency() {
        if (_busy.value != null) return
        _busy.value = BusyKind.TAG_ANALYSIS
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BlitzApp.instance.videoTagRepository.recomputeAuthorTagFrequency() }
            }
            _busy.value = null
            result.fold(
                onSuccess = {
                    AppSettings.setTagFrequencyLastAnalyzedAtMillis(getApplication(), System.currentTimeMillis())
                    emit(SettingsEvent.TagAnalysisDone(it.authorCount, it.tagRowCount))
                },
                onFailure = { emit(SettingsEvent.TagAnalysisFailed(it.readableMessage())) },
            )
        }
    }

    /**
     * 一次性为迁移前的历史标签补齐稳定数值 id（`ai-tag-suggestions` 需要）。
     * 幂等、可重复点击，正常情况下补齐一次后不需要再点。
     */
    fun backfillTagIds() {
        if (_busy.value != null) return
        _busy.value = BusyKind.TAG_ID_BACKFILL
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BlitzApp.instance.videoTagRepository.backfillTagIds() }
            }
            _busy.value = null
            result.fold(
                onSuccess = { emit(SettingsEvent.TagIdBackfillDone) },
                onFailure = { emit(SettingsEvent.TagIdBackfillFailed(it.readableMessage())) },
            )
        }
    }

    private fun emit(event: SettingsEvent) {
        _events.tryEmit(event)
    }

    private fun Throwable.readableMessage(): String = message ?: javaClass.simpleName

    enum class BusyKind { BACKUP, RESTORE, TAG_ANALYSIS, TAG_ID_BACKFILL }
}

sealed interface SettingsEvent {
    data class BackupDone(val file: File) : SettingsEvent
    data class BackupFailed(val message: String) : SettingsEvent

    data class ShowBackupPicker(val backups: List<DatabaseBackupManager.BackupEntry>) : SettingsEvent
    data object NoBackupsFound : SettingsEvent

    /** 恢复成功；Activity 收到后重启进程。 */
    data object RestoreDone : SettingsEvent
    data class RestoreFailed(val message: String) : SettingsEvent

    /** File 读取被系统拒绝（备份被孤儿化），需要改走 SAF 文件选择器。 */
    data object RestoreNeedsFilePicker : SettingsEvent

    /** 想开启隐藏但缺少「所有文件访问权限」，视图层据此弹说明并引导去系统设置。 */
    data class NeedsAllFilesAccess(val folder: MediaFolder) : SettingsEvent

    data class FolderVisibilityChanged(val folder: MediaFolder, val hidden: Boolean) : SettingsEvent

    /** 作者-标签高频缓存重算完成：覆盖了多少作者、写入多少条高频记录。 */
    data class TagAnalysisDone(val authorCount: Int, val tagRowCount: Int) : SettingsEvent
    data class TagAnalysisFailed(val message: String) : SettingsEvent

    /** 标签 id 一次性回填完成（幂等，可能"本来就没有需要补的"）。 */
    data object TagIdBackfillDone : SettingsEvent
    data class TagIdBackfillFailed(val message: String) : SettingsEvent
}
