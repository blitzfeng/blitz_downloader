package com.blitz.downloader.data

import androidx.room.withTransaction
import com.blitz.downloader.data.db.*
import com.blitz.downloader.model.RecoveryNames
import com.blitz.downloader.model.hex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest

private suspend fun RecoveryMediaAccess.visitMedia(visit: suspend (String, RecoveryMediaAccess.Entry, RecoveryNames.Item) -> Unit) {
    val top = list(RecoveryNames.root).filter { it.directory }.map { it.name }.toSet()
    for (folder in RecoveryNames.folders) {
        currentCoroutineContext().ensureActive()
        if (folder.substringBefore('/') !in top) continue
        if (folder == "covers/evidence" && list(RecoveryNames.root + "/covers").none { it.name == "evidence" && it.directory }) continue
        val parent = RecoveryNames.root + "/" + folder
        for (entry in list(parent)) {
            currentCoroutineContext().ensureActive()
            if (entry.directory) continue
            val item = RecoveryNames.parse(folder, entry.name) ?: continue
            visit(parent + "/" + entry.name, entry, item)
        }
    }
}

/** 每次预览或每个子批次只枚举一次目录，发现同作品新增副本或图集成员时要求重新扫描。 */
internal suspend fun RecoveryMediaAccess.changedGroups(scanned: List<String>): Set<String> {
    val missing = scanned.toMutableSet()
    val changed = mutableSetOf<String>()
    fun record(item: RecoveryNames.Item) {
        changed.add(item.group)
        item.work?.let(changed::add)
    }
    visitMedia { path, _, item -> if (!missing.remove(path)) record(item) }
    for (path in missing) {
        val folder = path.removePrefix(RecoveryNames.root + "/").substringBeforeLast('/')
        RecoveryNames.parse(folder, path.substringAfterLast('/'))?.let(::record)
    }
    return changed
}

class MediaRecoveryScanner(private val db: AppDatabase, private val access: RecoveryMediaAccess) {
    suspend fun scan(start: RecoverySession): RecoverySession {
        val dao = db.mediaRecoveryDao()
        val existing = db.downloadedVideoDao().getAll()
        val references = existing.flatMap { listOf(it.filePath, it.coverPath) }.mapTo(hashSetOf(),RecoveryNames::logical)
        val gson = com.google.gson.Gson()
        dao.provenanceInputs().forEach {
            references += gson.fromJson(it,com.blitz.downloader.model.RecoveryInputs::class.java).members
        }
        val albumGroups = existing.filter { it.mediaType == "image" }.mapNotNull {
            RecoveryNames.parse("images", it.filePath.substringAfterLast('/'))?.group
        }.toSet()
        val ids = existing.mapTo(hashSetOf()) { it.awemeId }
        val pending = ArrayList<RecoveryFile>(100)
        var session = start
        suspend fun flush() {
            if (pending.isEmpty()) return
            db.withTransaction {
                dao.insertFiles(pending)
                dao.insertWorks(pending.mapNotNull { it.awemeId }.distinct().map {
                    RecoveryWork(session.sessionId, it, if (it in ids) "ALREADY_PRESENT" else "PENDING")
                })
                session = session.copy(fileCount = session.fileCount + pending.size, updatedAt = System.currentTimeMillis())
                dao.update(session)
            }
            pending.clear()
        }
        access.visitMedia { path, entry, item ->
            val album = item.kind == "IMAGE" || item.kind == "LIVE"
            val referenced = path in references || (album && item.group in albumGroups)
            val quick = quickHash(access, path, entry.size, entry.modified)
            pending += RecoveryFile(session.sessionId, path, entry.locator, item.kind, item.group, entry.size,
                entry.modified, quick, item.work, item.ordinal, referenced)
            if (pending.size == 100) flush()
        }
        flush()
        return session.copy(state = "SCANNED", stage = "REVIEW", updatedAt = System.currentTimeMillis()).also { dao.update(it) }
    }
}

internal fun quickHash(access: RecoveryMediaAccess, path: String, size: Long, modified: Long): String {
    val hash = MessageDigest.getInstance("SHA-256")
    hash.update("$path\n$size\n$modified\n".toByteArray())
    access.stream(path).use { input ->
        val buffer = ByteArray(65536)
        fun sample() {
            var remaining = buffer.size
            while (remaining > 0) {
                val read = input.read(buffer, 0, remaining)
                if (read <= 0) break
                hash.update(buffer, 0, read)
                remaining -= read
            }
        }
        sample()
        if (size > buffer.size) {
            input.channel.position(maxOf(buffer.size.toLong(), size - buffer.size))
            sample()
        }
    }
    return hash.digest().hex()
}

internal suspend fun fullHash(access: RecoveryMediaAccess, path: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    access.stream(path).use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().hex()
}
