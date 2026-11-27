package com.blitz.downloader.data

import androidx.room.withTransaction
import com.blitz.downloader.data.db.*
import com.blitz.downloader.model.*
import kotlinx.coroutines.*
import java.util.UUID

data class RecoveryPreview(val session: String, val parts: List<String>, val rows: List<RecoveryBatchItem>,
    val expectedNew: Int, val dbBytes: Long, val operation: String)

internal class RecoveryWriter(private val repo: MediaRecoveryRepository, private val db: AppDatabase,
    private val maintenance: BackupMaintenance) {
    private val dao = db.mediaRecoveryDao()
    private val gson = repo.gson

    suspend fun preview(selected: List<RecoveryCandidate>): RecoveryPreview = repo.exclusive {
        require(selected.isNotEmpty())
        val session = selected.first().sessionId
        repo.completeSession(session)
        require(selected.all { it.sessionId == session } && selected.distinctBy { it.awemeId }.size == selected.size)
        val operation = UUID.randomUUID().toString()
        val batches = mutableListOf<RecoveryBatch>()
        val items = mutableListOf<RecoveryBatchItem>()
        var directoryChanges: Set<String>? = null
        var exists = 0
        for ((part, chunk) in selected.sortedBy { it.awemeId }.chunked(50).withIndex()) {
            val batch = UUID.randomUUID().toString()
            val rows = chunk.map { pick ->
                val c = checkNotNull(dao.candidates(session,pick.awemeId).find { it.groupKey == pick.groupKey })
                require(c.state == "CONFIRMED" && c == pick) { "审核内容已变化" }
                val work = checkNotNull(dao.work(session,c.awemeId))
                val input = gson.fromJson(c.inputsJson, RecoveryInputs::class.java)
                val detail = gson.fromJson(work.detailJson,RecoveryDetail::class.java)
                if (db.downloadedVideoDao().getByAwemeId(c.awemeId) != null) exists++
                else {
                    val changes = directoryChanges ?: repo.access.changedGroups(dao.scannedPaths(session)).also { directoryChanges = it }
                    verifyCandidate(c,work,changes)
                }
                val size = if (detail.mediaType == "video") repo.access.videoSize(input.main) else repo.access.imageSize(input.main)
                check(size != null || db.downloadedVideoDao().getByAwemeId(c.awemeId) != null) { "主文件无法正常解码，请检查所选媒体" }
                val payload = DownloadedVideoEntity(awemeId = c.awemeId, downloadType = DownloadSourceType.RECOVERED,
                    mediaWidth = size?.width ?: 0, mediaHeight = size?.height ?: 0,
                    userName = detail.author, createdAtMillis = 0, mediaType = detail.mediaType,
                    filePath = input.main, coverPath = input.cover.orEmpty(), createTime = detail.createTime,
                    desc = detail.description, videoAuthorSecUserId = detail.authorId,
                    diggCount = detail.diggCount, collectCount = detail.collectCount,
                    hasLivePhoto = input.members.any { it.substringAfterLast('.').equals("mp4",true) } && detail.mediaType == "image")
                val json = gson.toJson(payload)
                RecoveryBatchItem(batch,c.awemeId,c.groupKey,c.fingerprint,c.revision,json,sha256(json))
            }
            val manifest = gson.toJson(rows)
            batches += RecoveryBatch(batch,operation,session,part+1,manifest,sha256(manifest),System.currentTimeMillis())
            items += rows
        }
        // 预览只保存冻结清单。服务写回还需要当前进程内用户明确交付的一次性提交命令。
        db.withTransaction { batches.forEach { dao.insert(it) }; dao.insertItems(items) }
        val size = db.openHelper.readableDatabase.let { sql ->
            fun pragma(p: String) = sql.query("PRAGMA $p").use { it.moveToFirst(); it.getLong(0) }
            pragma("page_count") * pragma("page_size")
        }
        RecoveryPreview(session,batches.map { it.batchId },items,items.size-exists,size,operation)
    }

    private suspend fun claimedPaths(): MutableSet<String> {
        val paths = db.downloadedVideoDao().getAll().mapTo(hashSetOf()) { RecoveryNames.logical(it.filePath) }
        dao.provenanceInputs().forEach { paths += gson.fromJson(it,RecoveryInputs::class.java).members }
        return paths
    }

    private suspend fun verifyCandidate(c: RecoveryCandidate, work: RecoveryWork, changed: Set<String>) {
        val actual = try {
            if (changed.isNotEmpty()) {
                val input = gson.fromJson(c.inputsJson,RecoveryInputs::class.java)
                val main = checkNotNull(dao.file(c.sessionId,input.main))
                check(work.awemeId !in changed && main.groupKey !in changed) { "作品的本地文件集合已变化，请重新扫描" }
            }
            repo.fingerprint(c,work)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            dao.review(c.sessionId,c.awemeId,c.groupKey,c.revision,"STALE","",0)
            throw e
        }
        if (actual != c.fingerprint) {
            dao.review(c.sessionId,c.awemeId,c.groupKey,c.revision,"STALE","",0)
            error("文件或授权已变更，请重新审核")
        }
    }

    suspend fun commit(preview: RecoveryPreview) = repo.exclusive {
        repo.completeSession(preview.session)
        repo.gated(preview.session) {
            maintenance.exclusive {
                val reserved = hashSetOf<String>()
                preview.rows.forEach { row ->
                    val payload = gson.fromJson(row.payloadJson,DownloadedVideoEntity::class.java)
                    check(reserved.add(payload.filePath)) { "多个作品选择了同一个主文件，请重新预览" }
                }
                for (id in preview.parts) {
                    currentCoroutineContext().ensureActive()
                    val batch = checkNotNull(dao.batch(id))
                    if (batch.state == "COMMITTED") continue
                    check(batch.state == "PLANNED" && batch.operationId == preview.operation)
                    var state = batch
                    var activeItem: RecoveryBatchItem? = null
                    var itemFailureState = "STALE"
                    var failureReason = "清单校验失败，请重新预览"
                    try {
                        val items = dao.items(id)
                        check(items.size in 1..50 && gson.toJson(items) == batch.manifestJson && sha256(batch.manifestJson) == batch.manifestHash)
                        suspend fun recheck(): List<RecoveryCandidate> {
                            val all = items.map { row ->
                                activeItem = row
                                val c = checkNotNull(dao.candidates(batch.sessionId,row.awemeId).find { it.groupKey == row.groupKey })
                                check(c.revision == row.revision && c.fingerprint == row.fingerprint && c.state == "CONFIRMED")
                                c
                            }
                            activeItem = null
                            val claims = hashSetOf<String>()
                            val referenced = claimedPaths()
                            var directoryChanges: Set<String>? = null
                            for ((index,c) in all.withIndex()) {
                                activeItem = items[index]
                                if (db.downloadedVideoDao().getByAwemeId(c.awemeId) != null) continue
                                val input = gson.fromJson(c.inputsJson,RecoveryInputs::class.java)
                                check(input.members.all { it !in referenced && claims.add(it) }) { "所选本地文件已被其他作品引用" }
                                val changes = directoryChanges ?: repo.access.changedGroups(dao.scannedPaths(batch.sessionId)).also { directoryChanges = it }
                                verifyCandidate(c,checkNotNull(dao.work(batch.sessionId,c.awemeId)),changes)
                            }
                            activeItem = null
                            return all
                        }
                        val confirmed = recheck()
                        val actual = items.any { db.downloadedVideoDao().getByAwemeId(it.awemeId) == null }
                        val backup = if (actual) {
                            failureReason = "创建独立备份失败，请检查目录授权和剩余空间"
                            state = state.copy(state = "BACKING_UP").also { dao.update(it) }
                            repo.backups.publish(state).also {
                                state = state.copy(state = "BACKUP_VERIFIED", backupLocator = it.folder,
                                    backupSize = it.manifest.bytes, backupSha256 = it.manifest.sha256,
                                    verifiedAt = it.manifest.verifiedAt).also { verified -> dao.update(verified) }
                                failureReason = "关联确认已失效，请重新检查文件和授权"
                                recheck()
                                failureReason = "备份回读校验失败，请重新预览并备份"
                                repo.backups.verify(it)
                            }
                        } else null
                        val time = System.currentTimeMillis()
                        itemFailureState = "FAILED"
                        failureReason = "数据库事务未提交，本批修改已回滚"
                        db.withTransaction {
                            val claims = claimedPaths()
                            var inserted = 0
                            for ((i,row) in items.withIndex()) {
                                activeItem = row
                                check(row.payloadHash == sha256(row.payloadJson))
                                val payload = gson.fromJson(row.payloadJson,DownloadedVideoEntity::class.java)
                                val c = confirmed[i]
                                val existing = db.downloadedVideoDao().getByAwemeId(row.awemeId)
                                val rowId = if (existing != null) -1 else {
                                    check(backup != null && repo.fresh(checkNotNull(dao.work(batch.sessionId,c.awemeId))))
                                    val inputs = gson.fromJson(c.inputsJson,RecoveryInputs::class.java)
                                    check(inputs.members.all(claims::add)) { "文件已被其他下载记录引用" }
                                    db.downloadedVideoDao().insert(payload.copy(createdAtMillis = time))
                                }
                                if (rowId != -1L) {
                                    val inputs = gson.fromJson(c.inputsJson,RecoveryInputs::class.java)
                                    val digests = (inputs.members + listOfNotNull(inputs.cover) + inputs.evidence).distinct()
                                        .associateWith { path -> checkNotNull(dao.file(c.sessionId,path)?.fullSha256) }
                                    dao.insert(RecoveryRecord(row.awemeId,rowId,id,time,gson.toJson(inputs.copy(fileDigests = digests))))
                                    inserted++
                                }
                                val status = if (rowId == -1L) "ALREADY_PRESENT" else "APPLIED"
                                check(dao.review(c.sessionId,c.awemeId,c.groupKey,c.revision,status,c.fingerprint,c.confirmedAt) == 1)
                                dao.update(row.copy(state = if (rowId == -1L) "SKIPPED_EXISTING" else "SUCCEEDED",rowId = rowId.takeIf { it != -1L }))
                            }
                            activeItem = null
                            dao.update(state.copy(state = "COMMITTED",completedAt = time,inserted = inserted,skipped = items.size-inserted))
                        }
                    } catch (e: Exception) {
                        withContext(NonCancellable) {
                            if (dao.batch(id)?.state != "COMMITTED") db.withTransaction {
                                val paused = e is CancellationException
                                val failed = activeItem
                                if (!paused && failed != null) dao.update(failed.copy(state = itemFailureState))
                                dao.update(state.copy(state = if (paused) "INTERRUPTED" else "FAILED",
                                    errorCode = if (paused) "任务已暂停" else failureReason))
                            }
                        }
                        throw e
                    }
                }
            }
        }
    }
}
