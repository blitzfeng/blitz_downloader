package com.blitz.downloader.data

import android.os.SystemClock
import androidx.room.withTransaction
import com.blitz.downloader.api.*
import com.blitz.downloader.data.db.*
import com.blitz.downloader.model.*
import com.blitz.downloader.service.AiWorkGate
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class MediaRecoveryRepository(
    private val db: AppDatabase, val access: RecoveryMediaAccess,
    private val fetcher: RecoveryDetailFetcher,
    val backups: RecoveryBackupStore, private val maintenance: BackupMaintenance,
    private val cookie: () -> String? = { DouyinApiClient.globalCookie },
    private val monotonicMs: () -> Long = SystemClock::elapsedRealtime,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val loginEpoch: () -> Long = { com.blitz.downloader.util.DouyinCookieStore.loginEpoch() },
) {
    val dao = db.mediaRecoveryDao()
    internal val gson = Gson()
    private val command = Mutex()
    private val rateLock = Mutex()
    private var nextStart = 0L
    private val startup = Mutex()
    private var initialized = false
    internal val writer = RecoveryWriter(this,db,maintenance)
    private fun accountScope(value: String?) = RecoveryDetailFetcher.scope(value).let {
        if (it.isEmpty()) "" else sha256(it + ":" + loginEpoch())
    }
    internal fun scope() = accountScope(cookie())
    internal fun fresh(work: RecoveryWork): Boolean =
        work.state == "SUCCESS" && work.authScopeHash.isNotEmpty() && work.authScopeHash == scope() &&
            System.currentTimeMillis() - work.fetchedAt in 0 until 86_400_000L

    suspend fun initialize() = startup.withLock {
        if (!initialized) {
            val recovered = dao.interruptedWrites().mapNotNull { batch ->
                backups.recoverPublished(batch)?.let {
                    batch.copy(backupLocator = it.folder,backupSha256 = it.manifest.sha256,
                        backupSize = it.manifest.bytes,verifiedAt = it.manifest.verifiedAt,state = "INTERRUPTED")
                }
            }
            db.withTransaction {
                recovered.forEach { dao.update(it) }
                dao.interruptSessions()
                dao.interruptBatches()
            }
            initialized = true
        }
    }

    suspend fun <T> exclusive(block: suspend () -> T): T {
        initialize()
        check(command.tryLock()) { "已有恢复任务执行中" }
        try { return block() } finally { command.unlock() }
    }

    suspend fun scan() = exclusive {
        val now = System.currentTimeMillis()
        val generation = (dao.latest().first()?.generation ?: 0) + 1
        val session = RecoverySession(UUID.randomUUID().toString(),generation,now)
        dao.insert(session)
        stage(session) { gated(session.sessionId) { MediaRecoveryScanner(db,access).scan(session) } }
    }

    private suspend fun stage(session: RecoverySession, block: suspend () -> Unit) {
        try { block() }
        catch (e: Exception) {
            withContext(NonCancellable) {
                val current = dao.session(session.sessionId) ?: return@withContext
                val status = when (e) {
                    is CancellationException -> "PAUSED"
                    is SecurityException -> "NEEDS_PERMISSION"
                    else -> "FAILED"
                }
                dao.update(current.copy(state = status, errorCode = e.javaClass.simpleName))
            }
            throw e
        }
    }

    private suspend fun waitStart() = rateLock.withLock {
        sleep((nextStart - monotonicMs()).coerceAtLeast(0))
        nextStart = monotonicMs() + 1000
    }

    suspend fun query(id: String) = exclusive {
        val session = completeSession(id)
        access.list(RecoveryNames.root)
        check(System.currentTimeMillis() >= dao.nextQueryTime()) { "限流等待时间未结束" }
        val auth = scope()
        if (auth.isBlank()) { dao.update(session.copy(state = "AUTH_REQUIRED")); return@exclusive }
        val current = session.copy(state = "FETCHING", stage = "QUERY", authScopeHash = auth, errorCode = "")
        dao.update(current)
        stage(current) {
            var stop = false
            var pauseUntil = 0L
            var stopState = "PAUSED"
            val existingIds = db.downloadedVideoDao().getAll().mapTo(hashSetOf()) { it.awemeId }
            val all = dao.works(id)
            db.withTransaction {
                all.filter { it.awemeId in existingIds && it.state != "ALREADY_PRESENT" }.forEach { w ->
                    dao.update(w.copy(state = "ALREADY_PRESENT"))
                }
            }
            val queue = all.filter { !fresh(it) && it.awemeId !in existingIds }.iterator()
            val lock = Mutex()
            coroutineScope {
                repeat(2) { launch {
                    while (true) {
                        val work = lock.withLock { if (!stop && queue.hasNext()) queue.next() else null } ?: break
                        var tries = 0
                        var reply: RecoveryReply
                        val started = SystemClock.elapsedRealtime()
                        while (true) {
                            waitStart()
                            val currentCookie = cookie()
                            if (lock.withLock { stop }) return@launch
                            reply = if (accountScope(currentCookie) == auth) fetcher.fetch(work.awemeId, currentCookie)
                                else RecoveryReply("AUTH_REQUIRED")
                            tries++
                            if (scope() != auth) reply = RecoveryReply("AUTH_REQUIRED")
                            if (reply.state in setOf("AUTH_REQUIRED","RATE_LIMITED")) lock.withLock {
                                stop = true
                                if (reply.state == "RATE_LIMITED") {
                                    stopState = "RATE_LIMITED"
                                    pauseUntil = maxOf(pauseUntil,System.currentTimeMillis() + reply.retryAfterMs)
                                    withContext(NonCancellable) {
                                        dao.update(current.copy(state = stopState,retryAfter = pauseUntil))
                                    }
                                } else if (stopState != "RATE_LIMITED") stopState = "AUTH_REQUIRED"
                            }
                            if (!reply.retryable || tries >= 3) break
                            sleep(if (tries == 1) 2000 else 5000)
                        }
                        val json = reply.detail?.let { gson.toJson(it) }
                        db.withTransaction {
                            dao.resetCandidates(id,work.awemeId)
                            dao.update(work.copy(state = reply.state, detailJson = json, detailHash = json?.let(::sha256).orEmpty(),
                                authScopeHash = auth, fetchedAt = System.currentTimeMillis(), attempts = work.attempts + tries,
                                httpCode = reply.http, elapsedMs = SystemClock.elapsedRealtime() - started))
                        }
                    }
                } }
            }
            dao.update(current.copy(state = if (stop) stopState else "SCANNED", stage = "REVIEW", retryAfter = pauseUntil))
            if (!stop) matchInternal(id)
        }
    }

    suspend fun match(id: String) = exclusive { completeSession(id); matchInternal(id) }

    private suspend fun matchInternal(id: String) = gated(id) {
        val session = completeSession(id).copy(state = "MATCHING", stage = "MATCH")
        dao.update(session)
        stage(session) {
            val matcher = MediaRecoveryMatcher(access,dao.files(id))
            for (work in dao.works(id).filter(::fresh)) {
                currentCoroutineContext().ensureActive()
                if (dao.candidates(id,work.awemeId).isNotEmpty()) continue
                val detail = gson.fromJson(work.detailJson,RecoveryDetail::class.java)
                val choices = matcher.choices(detail)
                db.withTransaction {
                    if (choices.isEmpty()) dao.insert(RecoveryCandidate(id,work.awemeId,"","{}","无本地对应","待人工绑定","REMOTE_ONLY"))
                    choices.forEach { choice ->
                        dao.insert(RecoveryCandidate(id,work.awemeId,choice.inputs.main,gson.toJson(choice.inputs),
                            choice.method,choice.reason,choice.state, revision = java.util.UUID.randomUUID().mostSignificantBits))
                    }
                }
            }
            dao.update(session.copy(state = "REVIEW_READY",stage = "REVIEW"))
        }
    }

    internal suspend fun <T> gated(id: String, block: suspend () -> T): T {
        val owner = "recovery:$id"
        check(AiWorkGate.acquire(owner)) { "AI 分析或学习正在使用设备，请稍后继续" }
        try { return block() } finally { AiWorkGate.release(owner) }
    }

    internal suspend fun completeSession(id: String): RecoverySession {
        val session = checkNotNull(dao.session(id))
        check(session.stage != "SCAN" && dao.lastCompleteScan()?.sessionId == id) { "需要使用最近一次完整扫描" }
        return session
    }

    suspend fun attach(id: String,path: String,input: String) = exclusive {
        completeSession(id)
        val work = RecoveryShareLink().resolve(input) ?: error("未能识别作品，请使用作品 ID 或完整作品链接")
        val known = db.downloadedVideoDao().getByAwemeId(work) != null
        db.withTransaction {
            val source = checkNotNull(dao.file(id,path))
            val members = if (source.kind == "IMAGE") dao.files(id).filter { it.groupKey == source.groupKey && it.kind in listOf("IMAGE","LIVE") }
                else listOf(source)
            for (f in members) {
                check(f.awemeId == work || dao.bind(id,f.logicalPath,work) == 1) { "图集成员或文件已有其他作品引用" }
            }
            dao.insertWorks(listOf(RecoveryWork(id,work,if (known) "ALREADY_PRESENT" else "PENDING")))
            dao.resetCandidates(id,work)
        }
    }

    suspend fun review(candidate: RecoveryCandidate, confirm: Boolean, selection: RecoveryInputs? = null) = exclusive {
        completeSession(candidate.sessionId)
        val latest = dao.candidates(candidate.sessionId,candidate.awemeId).find { it.groupKey == candidate.groupKey }
        check(candidate == latest) { "候选已更新，请重新打开" }
        val work = checkNotNull(dao.work(candidate.sessionId,candidate.awemeId))
        val now = System.currentTimeMillis()
        if (confirm) require(candidate.state in setOf("READY","REVIEW","STALE","EXCLUDED"))
        val json = if (selection != null) {
            val original = gson.fromJson(candidate.inputsJson,RecoveryInputs::class.java)
            require(selection.members.isNotEmpty() && selection.members.all { it in original.members } &&
                selection.main in selection.members && selection.cover in listOf(original.cover,selection.main) &&
                selection.evidence == original.evidence)
            gson.toJson(selection)
        } else candidate.inputsJson
        val fingerprint = if (confirm) fingerprint(candidate.copy(inputsJson = json,revision = candidate.revision + 1),work, verifyScan = true) else ""
        db.withTransaction {
            if (confirm) {
                check(fresh(work))
                dao.candidates(candidate.sessionId,candidate.awemeId).filter { it.state == "CONFIRMED" && it.groupKey != candidate.groupKey }
                    .forEach { old -> check(dao.review(old.sessionId,old.awemeId,old.groupKey,old.revision,"REVIEW","",0) == 1) }
            }
            check(dao.review(candidate.sessionId,work.awemeId,candidate.groupKey,candidate.revision,
                if (confirm) "CONFIRMED" else "EXCLUDED",fingerprint,now,json) == 1) { "审核内容已变化，请重新查看" }
        }
    }

    internal suspend fun fingerprint(candidate: RecoveryCandidate, work: RecoveryWork, verifyScan: Boolean = false): String {
        check(fresh(work)) { "作品详情已过期或登录态变化，请重新查询" }
        val inputs = gson.fromJson(candidate.inputsJson,RecoveryInputs::class.java)
        require(inputs.main in inputs.members && inputs.members.distinct() == inputs.members && inputs.schemaVersion == 1)
        val main = checkNotNull(dao.file(candidate.sessionId,inputs.main))
        val detail = gson.fromJson(work.detailJson,RecoveryDetail::class.java)
        check(!main.referenced && ((detail.mediaType == "video" && main.kind == "VIDEO") ||
            (detail.mediaType == "image" && main.kind == "IMAGE"))) { "主文件与远端类型不符" }
        val parts = mutableListOf("1",work.authScopeHash,work.detailHash,work.awemeId,candidate.inputsJson,
            candidate.revision.toString(),access.authorizationRevision.toString())
        for (path in (inputs.members + listOfNotNull(inputs.cover) + inputs.evidence).distinct()) {
            val file = checkNotNull(dao.file(candidate.sessionId,path))
            val (size,modified) = access.metadata(path)
            check(size == file.size && modified == file.modified) { "文件属性已改变，请重新扫描" }
            if (verifyScan) {
                val quick = quickHash(access,path,file.size,file.modified)
                check(quick == file.quickHash) { "文件内容和扫描时不同，请重新扫描" }
            }
            parts += file.locator
            parts += "$size/$modified"
            val digest = fullHash(access,path)
            if (verifyScan) dao.cacheFullHash(candidate.sessionId,path,digest)
            parts += digest
        }
        return sha256(gson.toJson(parts))
    }
}
