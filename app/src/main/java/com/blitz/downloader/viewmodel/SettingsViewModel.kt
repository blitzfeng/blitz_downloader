package com.blitz.downloader.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    private fun emit(event: SettingsEvent) {
        _events.tryEmit(event)
    }

    private fun Throwable.readableMessage(): String = message ?: javaClass.simpleName

    enum class BusyKind { BACKUP, RESTORE }
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
}
