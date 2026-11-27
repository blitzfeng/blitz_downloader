package com.blitz.downloader.data

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.blitz.downloader.R
import com.blitz.downloader.data.db.BackupFileStore
import com.blitz.downloader.data.db.BackupForegroundState
import com.blitz.downloader.data.db.DatabaseBackupManager
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 生命周期只发送检查信号；所有文件操作在应用级 IO 协程中完成。 */
class DatabaseBackupCoordinator(
    private val app: Application,
    private val scope: CoroutineScope,
    maintenanceOverride: BackupMaintenance? = null,
    private val notify: (String) -> Unit = { Toast.makeText(app, it, Toast.LENGTH_LONG).show() },
    private val permissionProbe: () -> Boolean = { hasStoragePermission(app) },
    private val protectedMode: () -> Boolean = { RecoveryProtection.enabled(app) },
) : Application.ActivityLifecycleCallbacks {
    private val store = DatabaseBackupManager.fileStore()
    private val prefs = app.getSharedPreferences("database_backup", 0)
    internal val maintenance = maintenanceOverride ?: BackupMaintenance(object : BackupMaintenance.Storage {
        override fun scan() = DatabaseBackupManager.scan(app, store)
        override suspend fun create(kind: String): BackupFileStore.Entry {
            val file = DatabaseBackupManager.backupNow(app, kind, store)
            return store.inspect(checkNotNull(file.parentFile)) ?: error("备份发布后不可读取")
        }
        override fun cleanup(now: Long, entries: List<BackupFileStore.Entry>, protectedId: String?) =
            store.cleanup(now, entries, protectedId) { DatabaseBackupManager.validateEntry(app, it) }
    }, System::currentTimeMillis, SystemClock::elapsedRealtime,
        { prefs.getString("success_id", null) },
        { entry -> prefs.edit().putString("success_id", entry.id).putLong("success_at", entry.completedAt).apply() })

    private val handler = Handler(Looper.getMainLooper())
    private val foreground = BackupForegroundState()
    private val settle = Runnable { foreground.settleBackground() }
    private var resumed = 0
    private var checking = false
    private var recheckForPermission = false
    private var permission = permissionProbe()
    private var pending: BackupMaintenance.Result? = null

    suspend fun backup(): File = withContext(Dispatchers.IO) { maintenance.manualBackup().dbFile }

    suspend fun listBackups(): List<DatabaseBackupManager.BackupEntry> = withContext(Dispatchers.IO) {
        maintenance.exclusive { DatabaseBackupManager.listBackups(app) }
    }

    suspend fun restore(entry: DatabaseBackupManager.BackupEntry) = withContext(Dispatchers.IO) {
        maintenance.restore {
            if (!entry.dbFile.canRead()) throw SecurityException("备份无法直接访问，请使用文件选择器")
            val current = store.inspect(entry.dir) ?: throw java.io.IOException("备份已变更或不可读取")
            if (current.dbFile != entry.dbFile || current.completedAt != entry.createdAt) throw java.io.IOException("备份已变更")
            DatabaseBackupManager.restoreFrom(app, entry)
        }
    }

    suspend fun restore(open: () -> InputStream) = withContext(Dispatchers.IO) {
        maintenance.restore { open().use { DatabaseBackupManager.restoreFromStream(app, it) } }
    }

    private fun requestCheck(permissionChanged: Boolean = false) {
        if (protectedMode()) return
        if (checking) { recheckForPermission = recheckForPermission || permissionChanged; return }
        checking = true
        scope.launch {
            if (permissionChanged) maintenance.permissionChanged()
            val started = SystemClock.elapsedRealtime()
            val result = maintenance.check(protectedMode)
            Log.i("DatabaseBackup", "source=foreground latest=${result.latestTime} totalMs=${SystemClock.elapsedRealtime() - started} created=${result.created != null} deleted=${result.cleanup.deleted} failed=${result.cleanup.failed} skipped=${result.skipped + result.cleanup.skipped} error=${result.error?.javaClass?.simpleName}")
            withContext(Dispatchers.Main) {
                checking = false
                if (result.created != null || result.error != null || result.cleanup.failed > 0) pending = result
                showPending()
                if (recheckForPermission) { recheckForPermission = false; requestCheck(true) }
            }
        }
    }

    private fun showPending() {
        if (resumed == 0) return
        val result = pending ?: return
        pending = null
        val text = when {
            result.created != null && result.cleanup.failed > 0 -> app.getString(R.string.auto_backup_cleanup_partial, result.created.dbFile.absolutePath)
            result.created != null -> app.getString(R.string.auto_backup_done, result.created.dbFile.absolutePath)
            result.error != null -> {
                val error = result.error
                val reason = when {
                    error is SecurityException || error.message?.contains("EACCES", true) == true ||
                        error.message?.contains("Permission denied", true) == true -> app.getString(R.string.auto_backup_permission)
                    error.message?.contains("space", true) == true || error is android.database.sqlite.SQLiteFullException -> app.getString(R.string.auto_backup_space)
                    else -> app.getString(R.string.auto_backup_io)
                }
                app.getString(R.string.auto_backup_failed, reason)
            }
            else -> app.getString(R.string.auto_backup_cleanup_failed)
        }
        notify(text)
    }

    companion object {
        private fun hasStoragePermission(app: Application): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
            else ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    override fun onActivityStarted(activity: Activity) {
        handler.removeCallbacks(settle)
        if (foreground.start()) requestCheck()
    }
    override fun onActivityStopped(activity: Activity) {
        foreground.stop()
        if (!activity.isChangingConfigurations) handler.postDelayed(settle, 700)
    }
    override fun onActivityResumed(activity: Activity) {
        resumed++
        val current = permissionProbe()
        if (current != permission) { permission = current; requestCheck(true) }
        showPending()
    }
    override fun onActivityPaused(activity: Activity) { resumed = (resumed - 1).coerceAtLeast(0) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
