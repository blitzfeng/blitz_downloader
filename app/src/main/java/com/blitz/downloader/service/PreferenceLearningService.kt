package com.blitz.downloader.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.activity.PreferenceLearningActivity
import com.blitz.downloader.data.PreferenceLearningRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PreferenceLearningService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var renewWakeLockAt = 0L
    @Volatile private var cancelled = false
    private val manager get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            cancelLearning()
            return START_NOT_STICKY
        }
        val runId = intent?.getStringExtra(RUN_ID)
        if (runId == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (worker != null) return START_NOT_STICKY
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "偏好学习", NotificationManager.IMPORTANCE_LOW))
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(runId, 0, 0, 0),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (_: Exception) {
            AiWorkGate.release(owner(runId))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        refreshWakeLock()
        val app = application as BlitzApp
        worker = scope.launch(Dispatchers.IO) {
            app.preferenceRecovery.await()
            PreferenceLearningRunner(app.aiPreferenceRepository).run(
                runId, intent.getBooleanExtra(RETRY, false), { cancelled },
            ) { done, total, cases ->
                mainHandler.post {
                    if (worker?.isActive == true) {
                        refreshWakeLock()
                        runCatching { manager.notify(NOTIFICATION_ID, notification(runId, done, total, cases)) }
                    }
                }
            }
        }.also { job ->
            activeWorker = job
            activeCancellation = { cancelled = true }
            job.invokeOnCompletion {
                mainHandler.post {
                    releaseWakeLock()
                    activeWorker = null
                    activeCancellation = null
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    // 通知取消会带来新的 startId，仍需结束当前服务实例。
                    stopSelf()
                    AiWorkGate.release(owner(runId))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        worker?.cancel()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun refreshWakeLock() {
        val now = SystemClock.elapsedRealtime()
        if (now < renewWakeLockAt) return
        // 仅保持 CPU 可运行。锁有超时，而且只在前台服务执行并报告进度时续期。
        runCatching {
            val lock = wakeLock ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:$WAKE_LOCK_TAG")
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            renewWakeLockAt = now + WAKE_LOCK_TIMEOUT_MS / 2
        }.onFailure {
            renewWakeLockAt = now + WAKE_LOCK_TIMEOUT_MS / 2
            Log.w("PreferenceLearning", "无法取得息屏唤醒锁", it)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        renewWakeLockAt = 0L
    }

    private fun notification(runId: String, done: Int, total: Int, cases: Int): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, PreferenceLearningActivity.intent(this, runId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, PreferenceLearningService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("正在学习标签偏好")
            .setContentText(if (total == 0) "正在核对学习资料" else "已完成 $done/$total 个节点，覆盖 $cases 个作品")
            .setProgress(total, done, total == 0)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "取消", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "blitz_preference_learning"
        internal const val WAKE_LOCK_TAG = "PreferenceLearning"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L
        private const val NOTIFICATION_ID = 2002
        private const val RUN_ID = "preferenceRun"
        private const val RETRY = "retryUncertain"
        private const val CANCEL = "cancelPreferenceLearning"
        @Volatile private var activeWorker: Job? = null
        @Volatile private var activeCancellation: (() -> Unit)? = null

        private fun owner(id: String) = "learning:$id"

        fun start(context: Context, id: String, retryUncertain: Boolean): Boolean {
            if (!AiWorkGate.acquire(owner(id))) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, PreferenceLearningService::class.java)
                    .putExtra(RUN_ID, id).putExtra(RETRY, retryUncertain))
                true
            } catch (_: Exception) {
                AiWorkGate.release(owner(id))
                false
            }
        }

        fun cancelLearning() {
            activeCancellation?.invoke()
            activeWorker?.cancel()
        }
    }
}
