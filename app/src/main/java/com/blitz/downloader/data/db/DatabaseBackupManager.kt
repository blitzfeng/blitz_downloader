package com.blitz.downloader.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.download.BatchDownloadCoordinator
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 文件操作必须在 IO 线程运行；产品入口统一经应用级协调器串行调用。 */
object DatabaseBackupManager {
    const val DB_FILE_NAME = BackupFileStore.DB_NAME
    const val BACKUP_SUBDIR = "${BatchDownloadCoordinator.RELATIVE_DOWNLOAD_SUBDIR}/backup"

    data class BackupEntry(val dir: File, val dbFile: File, val createdAt: Long) {
        fun displayLabel(): String = "%s (%.2f MB)".format(Locale.US,
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(createdAt)), dbFile.length() / 1024.0 / 1024.0)
    }

    fun backupRootDir(): File {
        @Suppress("DEPRECATION")
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), BACKUP_SUBDIR)
    }

    fun fileStore(root: File = backupRootDir()) = BackupFileStore(root, isLink = {
        android.system.OsConstants.S_ISLNK(android.system.Os.lstat(it.path).st_mode)
    })

    suspend fun backupNow(context: Context, kind: String = "MANUAL", store: BackupFileStore = fileStore()): File {
        val startedAt = System.currentTimeMillis()
        val started = SystemClock.elapsedRealtime()
        val temp = tempDir(context)
        try {
            val file = File(temp, DB_FILE_NAME)
            val snapshotStart = SystemClock.elapsedRealtime()
            val info = DatabaseSnapshot.create(AppDatabase.getInstance(context), file)
            val snapshotMs = SystemClock.elapsedRealtime() - snapshotStart
            val entry = store.publish(file, kind, startedAt, maxOf(startedAt, System.currentTimeMillis()), info.version)
            Log.i("DatabaseBackup", "kind=$kind snapshotMs=$snapshotMs totalMs=${SystemClock.elapsedRealtime() - started} bytes=${file.length()}")
            return entry.dbFile
        } finally { removeTemp(temp) }
    }

    fun scan(context: Context, store: BackupFileStore = fileStore()): BackupFileStore.Scan =
        store.scan { validateEntry(context, it) }

    fun listBackups(context: Context = BlitzApp.instance): List<BackupEntry> =
        scan(context).entries.map { BackupEntry(it.dir, it.dbFile, it.completedAt) }

    /** 旧 WAL 只在隔离目录回放，始终不修改用户原始副本。 */
    fun validateEntry(context: Context, entry: BackupFileStore.Entry) {
        val temp = prepareEntry(context, entry.dbFile)
        try {
            val info = DatabaseSnapshot.validate(File(temp, DB_FILE_NAME))
            if (entry.manifest != null && info.version != entry.manifest.databaseVersion) throw IOException("备份版本校验失败")
        } finally { removeTemp(temp) }
    }

    fun restoreFrom(context: Context, entry: BackupEntry) {
        val temp = prepareEntry(context, entry.dbFile)
        try { applyRestore(context, File(temp, DB_FILE_NAME)) } finally { removeTemp(temp) }
    }

    fun restoreFromStream(context: Context, source: java.io.InputStream) {
        val temp = tempDir(context)
        try {
            val file = File(temp, DB_FILE_NAME)
            source.use { input -> FileOutputStream(file).use { output -> input.copyTo(output); output.fd.sync() } }
            DatabaseSnapshot.validate(file)
            applyRestore(context, file)
        } finally { removeTemp(temp) }
    }

    private fun prepareEntry(context: Context, source: File): File {
        val temp = tempDir(context)
        try {
            source.copyTo(File(temp, DB_FILE_NAME))
            for (suffix in listOf("-wal", "-shm")) {
                val sidecar = File(source.path + suffix)
                if (sidecar.exists()) sidecar.copyTo(File(temp, DB_FILE_NAME + suffix))
            }
            val isolated = File(temp, DB_FILE_NAME)
            SQLiteDatabase.openDatabase(isolated.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                    if (cursor.moveToFirst() && cursor.getInt(0) != 0) throw IOException("备份 WAL 校验失败")
                }
            }
            DatabaseSnapshot.validate(isolated)
            return temp
        } catch (error: Exception) { removeTemp(temp); throw error }
    }

    private fun applyRestore(context: Context, source: File) {
        DatabaseSnapshot.validate(source)
        val dest = context.getDatabasePath(DB_FILE_NAME)
        dest.parentFile?.mkdirs()
        val stage = File(dest.parentFile, "${dest.name}.restore-${UUID.randomUUID()}")
        try {
            source.inputStream().use { input -> FileOutputStream(stage).use { output -> input.copyTo(output); output.fd.sync() } }
            AppDatabase.closeAndReset()
            listOf("-wal", "-shm").forEach { suffix ->
                val sidecar = File(dest.path + suffix)
                if (sidecar.exists() && !sidecar.delete()) throw IOException("无法删除旧 WAL/SHM 文件")
            }
            if (!stage.renameTo(dest)) throw IOException("无法替换数据库文件")
        } finally { stage.delete() }
    }

    private fun tempDir(context: Context): File = File(context.cacheDir, "db-backup-${UUID.randomUUID()}").also {
        if (!it.mkdir()) throw IOException("无法创建数据库临时目录")
    }

    private fun removeTemp(dir: File) {
        listOf(DB_FILE_NAME, "$DB_FILE_NAME-wal", "$DB_FILE_NAME-shm", "$DB_FILE_NAME-journal").forEach { File(dir, it).delete() }
        dir.delete()
    }
}
