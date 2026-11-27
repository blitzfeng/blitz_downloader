package com.blitz.downloader.data

import com.blitz.downloader.data.db.BackupFileStore
import com.blitz.downloader.data.db.BackupPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 可注入存储和时钟，以真实策略测试失败、冷却和串行重查。 */
class BackupMaintenance(
    private val storage: Storage,
    private val wallTime: () -> Long,
    private val elapsedTime: () -> Long,
    private val getSuccessId: () -> String?,
    private val setSuccess: (BackupFileStore.Entry) -> Unit,
) {
    interface Storage {
        fun scan(): BackupFileStore.Scan
        suspend fun create(kind: String): BackupFileStore.Entry
        fun cleanup(now: Long, entries: List<BackupFileStore.Entry>, protectedId: String?): BackupFileStore.Cleanup
    }
    data class Result(
        val created: BackupFileStore.Entry? = null,
        val cleanup: BackupFileStore.Cleanup = BackupFileStore.Cleanup(),
        val error: Exception? = null,
        val latestTime: Long? = null,
        val skipped: Int = 0,
    )
    private val mutex = Mutex()
    private var failedAt: Long? = null
    private var restoring = false

    suspend fun check(protectedMode: () -> Boolean = { false }): Result = mutex.withLock {
        if (restoring || protectedMode()) return@withLock Result()
        val now = wallTime()
        val mono = elapsedTime()
        if (failedAt?.let { mono - it < BackupPolicy.RETRY_DELAY } == true) return@withLock Result()
        var latestTime: Long? = null
        try {
            val scan = storage.scan()
            val decision = BackupPolicy.decide(now, scan.entries, getSuccessId())
            latestTime = scan.entries.find { it.id == decision.latestId }?.completedAt
            var created: BackupFileStore.Entry? = null
            if (decision.create) {
                if (failedAt?.let { mono - it < BackupPolicy.RETRY_DELAY } == true) return@withLock Result(latestTime = latestTime)
                created = storage.create("AUTO")
                setSuccess(created)
                failedAt = null
            }
            val entries = scan.entries + listOfNotNull(created)
            val cleanup = if (decision.allowCleanup) {
                try { storage.cleanup(now, entries, created?.id ?: decision.latestId) }
                catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    BackupFileStore.Cleanup(failed = 1)
                }
            } else BackupFileStore.Cleanup()
            Result(created, cleanup, latestTime = latestTime, skipped = scan.skipped)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            failedAt = mono
            Result(error = error, latestTime = latestTime)
        }
    }

    suspend fun manualBackup(): BackupFileStore.Entry = mutex.withLock {
        check(!restoring) { "数据库正在恢复" }
        storage.create("MANUAL").also { setSuccess(it); failedAt = null }
    }

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock {
        check(!restoring) { "整库恢复已完成，请先重启应用" }
        block()
    }

    suspend fun restore(block: () -> Unit) = mutex.withLock {
        check(!restoring) { "数据库正在恢复" }
        block()
        // 成功后等待进程重启，禁止再从旧 Repository 备份。
        restoring = true
    }

    suspend fun permissionChanged() = mutex.withLock { failedAt = null }
}
