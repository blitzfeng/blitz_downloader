package com.blitz.downloader.data.db

import com.google.gson.JsonParser
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 仅管理应用拥有的直接子目录；SQL 校验由调用方在隔离文件上完成。 */
class BackupFileStore(
    val root: File,
    private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    private val delete: (File) -> Boolean = { it.delete() },
    // Android 产品入口注入 lstat，兼容 API 24；默认实现供 JVM 文件系统测试。
    private val isLink: (File) -> Boolean = { java.nio.file.Files.isSymbolicLink(it.toPath()) },
) {
    data class Manifest(
        val formatVersion: Int = 1,
        val backupId: String,
        val kind: String,
        val startedAt: Long,
        val completedAt: Long,
        val databaseVersion: Int,
        val sizeBytes: Long,
        val sha256: String,
    )
    data class Entry(val dir: File, val completedAt: Long, val manifest: Manifest? = null) {
        val id: String get() = manifest?.backupId ?: dir.name
        val dbFile: File get() = File(dir, DB_NAME)
    }
    data class Scan(val entries: List<Entry>, val skipped: Int)
    data class Cleanup(val deleted: Int = 0, val failed: Int = 0, val skipped: Int = 0)

    fun scan(validate: (Entry) -> Unit): Scan {
        if (!root.exists()) {
            // 不把无法访问的现有父目录当成空备份目录。
            var parent = root.parentFile
            while (parent != null && !parent.exists()) parent = parent.parentFile
            if (parent == null || !parent.canRead()) throw IOException("无法访问备份目录")
            return Scan(emptyList(), 0)
        }
        val children = root.listFiles() ?: throw IOException("无法读取备份目录")
        val valid = mutableListOf<Entry>()
        var skipped = 0
        children.filter { it.name.startsWith("backup_") }.forEach { dir ->
            try {
                val entry = inspect(dir) ?: throw IOException("不是完整备份")
                validate(entry)
                valid += entry
            } catch (_: Exception) { skipped++ }
        }
        return Scan(valid.sortedWith(compareByDescending<Entry> { it.completedAt }.thenBy { it.id }), skipped)
    }

    fun inspect(dir: File): Entry? {
        if (!ownedDirectory(dir)) return null
        val files = dir.listFiles() ?: return null
        if (!files.all { safeFile(dir, it) && it.name in ALLOWED }) return null
        val db = File(dir, DB_NAME)
        if (!db.isFile || db.length() == 0L) return null
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.exists()) {
            val timestamp = legacyTime(dir.name) ?: return null
            return Entry(dir, timestamp)
        }
        if (!NEW_NAME.matches(dir.name) || manifestFile.length() > 16_384) return null
        val json = JsonParser.parseString(manifestFile.readText()).asJsonObject
        val required = setOf("formatVersion", "backupId", "kind", "startedAt", "completedAt",
            "databaseVersion", "sizeBytes", "sha256")
        if (json.keySet() != required) return null
        fun longValue(name: String): Long {
            val p = json[name].asJsonPrimitive
            require(p.isNumber && Regex("[0-9]+").matches(p.asString))
            return p.asString.toLong()
        }
        fun stringValue(name: String): String {
            val p = json[name].asJsonPrimitive
            require(p.isString)
            return p.asString
        }
        val version = longValue("databaseVersion")
        if (version !in 1..Int.MAX_VALUE.toLong()) return null
        val m = Manifest(longValue("formatVersion").toInt(), stringValue("backupId"), stringValue("kind"),
            longValue("startedAt"), longValue("completedAt"), version.toInt(), longValue("sizeBytes"), stringValue("sha256"))
        if (longValue("formatVersion") != 1L || m.kind !in setOf("AUTO", "MANUAL") ||
            !Regex("[a-f0-9]{32}").matches(m.backupId) || !dir.name.endsWith("_${m.backupId}") ||
            m.startedAt <= 0 || m.completedAt < m.startedAt || m.sizeBytes != db.length() ||
            !Regex("[a-f0-9]{64}").matches(m.sha256) || digest(db) != m.sha256) return null
        if (files.any { it.name.endsWith("-wal") || it.name.endsWith("-shm") }) return null
        return Entry(dir, m.completedAt, m)
    }

    fun publish(snapshot: File, kind: String, startedAt: Long, completedAt: Long, version: Int): Entry {
        require(kind in setOf("AUTO", "MANUAL"))
        if (!root.isDirectory && !root.mkdirs()) throw IOException("无法创建备份目录")
        runCatching { File(root, ".nomedia").createNewFile() }
        val id = UUID.randomUUID().toString().replace("-", "")
        val pending = File(root, ".pending_$id")
        if (!pending.mkdir()) throw IOException("无法创建备份临时目录")
        val finalDir = File(root, "backup_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(completedAt))}_$id")
        try {
            val hash = digest(snapshot)
            val db = File(pending, DB_NAME)
            snapshot.inputStream().use { input -> FileOutputStream(db).use { out -> input.copyTo(out); out.fd.sync() } }
            if (db.length() != snapshot.length() || digest(db) != hash) throw IOException("备份文件校验失败")
            val m = Manifest(backupId = id, kind = kind, startedAt = startedAt,
                completedAt = completedAt, databaseVersion = version, sizeBytes = db.length(), sha256 = hash)
            FileOutputStream(File(pending, "manifest.json")).use { out ->
                out.write(Gson().toJson(m).toByteArray(Charsets.UTF_8)); out.fd.sync()
            }
            if (!rename(pending, finalDir)) throw IOException("无法发布备份")
            return inspect(finalDir) ?: throw IOException("备份发布校验失败")
        } finally {
            // 只处理本次创建的确定文件，未知内容保持原位。
            if (pending.exists()) removeKnown(pending, setOf(DB_NAME, "manifest.json"), pendingDirectory = true)
        }
    }

    fun cleanup(now: Long, entries: List<Entry>, protectedId: String?, validate: (Entry) -> Unit): Cleanup {
        var deleted = 0; var failed = 0; var skipped = 0
        entries.filter { BackupPolicy.expired(now, it, protectedId) }.forEach { old ->
            try {
                val current = inspect(old.dir)
                if (current == null || current != old || current.id == protectedId) { skipped++; return@forEach }
                validate(current)
                if (removeKnown(current.dir, if (current.manifest == null) LEGACY_FILES else setOf(DB_NAME, "manifest.json"))) deleted++
                else failed++
            } catch (_: Exception) { failed++ }
        }
        return Cleanup(deleted, failed, skipped)
    }

    private fun removeKnown(dir: File, allowed: Set<String>, pendingDirectory: Boolean = false): Boolean {
        if (!ownedDirectory(dir, pendingDirectory)) return false
        val files = dir.listFiles() ?: return false
        if (!files.all { it.name in allowed && safeFile(dir, it) }) return false
        for (file in files) {
            if (!ownedDirectory(dir, pendingDirectory) || !safeFile(dir, file) || !delete(file)) return false
        }
        return delete(dir)
    }

    private fun ownedDirectory(dir: File, pending: Boolean = false): Boolean =
        !isLink(dir) && dir.isDirectory && dir.parentFile?.canonicalFile == root.canonicalFile &&
            dir.canonicalFile == File(root.canonicalFile, dir.name) &&
            (if (pending) Regex("\\.pending_[a-f0-9]{32}").matches(dir.name)
                else LEGACY_NAME.matches(dir.name) || NEW_NAME.matches(dir.name))

    private fun safeFile(dir: File, file: File): Boolean = !isLink(file) && file.isFile &&
        file.canonicalFile == File(dir.canonicalFile, file.name)

    companion object {
        const val DB_NAME = "blitz_downloader.db"
        private val LEGACY_NAME = Regex("backup_[0-9]{8}_[0-9]{6}")
        private val NEW_NAME = Regex("backup_[0-9]{8}_[0-9]{6}_[0-9]{3}_[a-f0-9]{32}")
        private val LEGACY_FILES = setOf(DB_NAME, "$DB_NAME-wal", "$DB_NAME-shm")
        private val ALLOWED = LEGACY_FILES + "manifest.json"
        fun legacyTime(name: String): Long? {
            if (!LEGACY_NAME.matches(name)) return null
            val fmt = SimpleDateFormat("'backup_'yyyyMMdd_HHmmss", Locale.US).apply { isLenient = false }
            val pos = ParsePosition(0)
            return fmt.parse(name, pos)?.takeIf { pos.index == name.length }?.time?.takeIf { it > 0 }
        }
        fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
