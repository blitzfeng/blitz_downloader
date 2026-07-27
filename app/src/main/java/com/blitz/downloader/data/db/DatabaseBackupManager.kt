package com.blitz.downloader.data.db

import android.content.Context
import android.os.Environment
import com.blitz.downloader.download.BatchDownloadCoordinator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Room 数据库备份 / 恢复。
 *
 * 备份策略：
 * - 备份目录：`Download/bDouyin/backup/backup_<yyyyMMdd_HHmmss>/blitz_downloader.db`
 *   每次备份新开带时间戳的子目录，旧备份保留，UI 上让用户选择恢复哪一份。
 * - WAL：Room 默认开启 WAL，备份前必须执行 `PRAGMA wal_checkpoint(FULL)`，否则
 *   WAL 中尚未合并到主库的事务会丢失。
 * - `.nomedia`：在 `backup/` 根目录创建 .nomedia，阻止媒体扫描器把备份当成媒体文件索引。
 *
 * 恢复策略：
 * - 必须先关闭 [AppDatabase] 单例并删除现有 `.db / -wal / -shm` 三件套，
 *   再把备份文件覆盖到 `getDatabasePath()`。仅覆盖 .db 而保留旧 WAL 会让 SQLite
 *   把新主库回滚到一个不一致的状态。
 * - 恢复完成后调用方应触发进程重启（Room 再次 `getInstance` 时会按新文件初始化）。
 */
object DatabaseBackupManager {

    /** 与 [AppDatabase.DB_NAME] 保持一致。AppDatabase 中是 private const，这里复刻为常量。 */
    const val DB_FILE_NAME: String = "blitz_downloader.db"

    /** 备份根目录：`Download/bDouyin/backup`。 */
    const val BACKUP_SUBDIR: String = "${BatchDownloadCoordinator.RELATIVE_DOWNLOAD_SUBDIR}/backup"

    /** 单次备份子目录前缀：`backup_yyyyMMdd_HHmmss`。 */
    private const val BACKUP_ENTRY_PREFIX: String = "backup_"

    private val ENTRY_TIME_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /** 单条备份的描述。 */
    data class BackupEntry(
        /** 备份子目录绝对路径（包含 .db 文件）。 */
        val dir: File,
        /** 备份的 .db 文件。 */
        val dbFile: File,
        /** 创建时间（取自目录名时间戳；解析失败用文件 lastModified）。 */
        val createdAt: Long,
    ) {
        /** UI 展示用：`2026-06-18 12:34:56 (1.2 MB)` */
        fun displayLabel(): String {
            val dt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(createdAt))
            val sizeMb = dbFile.length() / 1024.0 / 1024.0
            return "%s (%.2f MB)".format(Locale.US, dt, sizeMb)
        }
    }

    // ── 路径辅助 ───────────────────────────────────────────────────────────────

    /** 公共 `Download/bDouyin/backup` 目录（不保证存在）。 */
    fun backupRootDir(): File {
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloads, BACKUP_SUBDIR)
    }

    // ── 备份 ───────────────────────────────────────────────────────────────────

    /**
     * 执行一次完整备份。**必须在 IO 线程调用。**
     *
     * @return 备份成功的 .db 文件可读路径（如 `Download/bDouyin/backup/backup_xxx/blitz_downloader.db`）。
     * @throws Exception 任何 I/O 或 SQLite 异常都向上抛出，由 UI 层 catch 提示。
     */
    @Throws(Exception::class)
    fun backupNow(context: Context): File {
        // 1) 先 checkpoint，确保 WAL 中数据全部合并到主库文件
        val db = AppDatabase.getInstance(context)
        db.openHelper.writableDatabase.use { sdb ->
            sdb.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        }

        // 2) 准备目标目录
        val root = backupRootDir()
        if (!root.exists() && !root.mkdirs()) {
            throw java.io.IOException("无法创建备份目录: ${root.absolutePath}")
        }
        // .nomedia 防止备份目录被扫成媒体
        val nomedia = File(root, ".nomedia")
        if (!nomedia.exists()) {
            runCatching { nomedia.createNewFile() }
        }

        val entryDir = File(root, BACKUP_ENTRY_PREFIX + ENTRY_TIME_FORMAT.format(Date(System.currentTimeMillis())))
        if (!entryDir.mkdirs()) {
            throw java.io.IOException("无法创建本次备份子目录: ${entryDir.absolutePath}")
        }

        // 3) 复制 .db / -wal / -shm 三件套（wal/shm 可能不存在，存在就一起备份保险一点）
        val src = context.getDatabasePath(DB_FILE_NAME)
        if (!src.exists()) {
            throw java.io.FileNotFoundException("数据库文件不存在: ${src.absolutePath}")
        }
        val dstDb = File(entryDir, DB_FILE_NAME)
        src.copyTo(dstDb, overwrite = true)
        listOf("$DB_FILE_NAME-wal", "$DB_FILE_NAME-shm").forEach { suffixName ->
            val s = File(src.parentFile, suffixName)
            if (s.exists()) s.copyTo(File(entryDir, suffixName), overwrite = true)
        }
        return dstDb
    }

    // ── 恢复 ───────────────────────────────────────────────────────────────────

    /** 列出已有备份，按时间倒序（最新在前）。**必须在 IO 线程调用。** */
    fun listBackups(): List<BackupEntry> {
        val root = backupRootDir()
        if (!root.isDirectory) return emptyList()
        val out = mutableListOf<BackupEntry>()
        root.listFiles()?.forEach { sub ->
            if (!sub.isDirectory || !sub.name.startsWith(BACKUP_ENTRY_PREFIX)) return@forEach
            val dbFile = File(sub, DB_FILE_NAME)
            if (!dbFile.isFile) return@forEach
            val ts = parseEntryTimestamp(sub.name) ?: dbFile.lastModified()
            out += BackupEntry(sub, dbFile, ts)
        }
        out.sortByDescending { it.createdAt }
        return out
    }

    /**
     * 从指定备份覆盖当前数据库。**必须在 IO 线程调用，且调用方负责重启进程。**
     *
     * 实现要点：
     * 1. `AppDatabase.closeAndReset()` 关闭单例并释放底层文件句柄；
     * 2. 删除现有 `.db / -wal / -shm`；
     * 3. 拷贝备份的 `.db`（以及备份目录下若存在的 -wal/-shm，正常不应该有）；
     * 4. 不在此处 `getInstance()`，等进程重启后由调用方/启动逻辑重新打开。
     */
    @Throws(Exception::class)
    fun restoreFrom(context: Context, entry: BackupEntry) {
        val srcDb = entry.dbFile
        if (!srcDb.isFile) {
            throw java.io.FileNotFoundException("备份文件不存在: ${srcDb.absolutePath}")
        }
        // 直接 File 读取；重装/换签名后该文件在 MediaStore 里可能被"孤儿化"，
        // 读取会抛 SecurityException——此时应由 UI 层引导改走 SAF（[restoreFromStream]）。
        applyRestore(context) { srcDb.inputStream() }
    }

    /**
     * 从任意输入流恢复（供 SAF 文件选择器场景使用）。**必须在 IO 线程调用，且调用方负责重启进程。**
     *
     * 用户通过系统文件选择器（`ACTION_OPEN_DOCUMENT`）选中备份 `.db` 后，用其授权 Uri 打开流传入，
     * 可**绕过 MediaStore 归属校验**，因此即便备份文件在重装后被孤儿化也能读取。
     */
    @Throws(Exception::class)
    fun restoreFromStream(context: Context, source: java.io.InputStream) {
        applyRestore(context) { source }
    }

    /**
     * 恢复的写入核心：关闭数据库单例 → 删除现有 `.db / -wal / -shm` → 从 [openSource] 覆盖写入 `.db`。
     * 备份写入时已 `checkpoint`，主库自洽，无需再恢复 wal/shm。
     */
    @Throws(Exception::class)
    private inline fun applyRestore(context: Context, openSource: () -> java.io.InputStream) {
        AppDatabase.closeAndReset()

        val dst = context.getDatabasePath(DB_FILE_NAME)
        dst.parentFile?.let { if (!it.exists()) it.mkdirs() }

        // 删 -wal / -shm 必须在覆盖 .db 之前；否则旧 WAL 会把新库回滚
        listOf("$DB_FILE_NAME-wal", "$DB_FILE_NAME-shm").forEach { suffixName ->
            val f = File(dst.parentFile, suffixName)
            if (f.exists() && !f.delete()) {
                throw java.io.IOException("无法删除旧 WAL/SHM 文件: ${f.absolutePath}")
            }
        }
        if (dst.exists() && !dst.delete()) {
            throw java.io.IOException("无法删除现有数据库: ${dst.absolutePath}")
        }

        openSource().use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        }
    }

    // ── 私有辅助 ───────────────────────────────────────────────────────────────

    /** 解析 `backup_yyyyMMdd_HHmmss` 形式的目录名为毫秒；失败返回 null。 */
    private fun parseEntryTimestamp(dirName: String): Long? {
        if (!dirName.startsWith(BACKUP_ENTRY_PREFIX)) return null
        val tail = dirName.removePrefix(BACKUP_ENTRY_PREFIX)
        return runCatching { ENTRY_TIME_FORMAT.parse(tail)?.time }.getOrNull()
    }
}
