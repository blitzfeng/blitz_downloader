package com.blitz.downloader.data.db

/** 时间仅来自成功副本，失败冷却使用单调时钟。 */
object BackupPolicy {
    const val DAY = 86_400_000L
    const val INTERVAL = 5 * DAY
    const val RETENTION = 30 * DAY
    const val RETRY_DELAY = 3_600_000L

    data class Decision(val create: Boolean, val allowCleanup: Boolean, val latestId: String?)

    fun decide(now: Long, entries: List<BackupFileStore.Entry>, calibratedId: String?): Decision {
        val calibrated = entries.find { it.id == calibratedId && it.completedAt <= now }
        val future = entries.any { it.completedAt > now }
        val latest = if (future && calibrated != null) calibrated
            else entries.maxWithOrNull(compareBy<BackupFileStore.Entry> { it.completedAt }.thenBy { it.id })
        val anomaly = latest != null && latest.completedAt > now
        return Decision(latest == null || anomaly || now - latest.completedAt > INTERVAL,
            !future, latest?.id)
    }

    fun expired(now: Long, entry: BackupFileStore.Entry, protectedId: String?): Boolean =
        entry.id != protectedId && entry.completedAt <= now && now - entry.completedAt > RETENTION
}

/** 独立于 Activity 实例的前台会话状态，可直接验证重建与页面切换。 */
class BackupForegroundState {
    var foreground = false
        private set
    private var started = 0
    fun start(): Boolean {
        started++
        if (foreground) return false
        foreground = true
        return true
    }
    fun stop() { started = (started - 1).coerceAtLeast(0) }
    fun settleBackground() { if (started == 0) foreground = false }
}
