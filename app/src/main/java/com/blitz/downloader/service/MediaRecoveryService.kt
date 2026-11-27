package com.blitz.downloader.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.R
import com.blitz.downloader.activity.MediaRecoveryActivity
import com.blitz.downloader.data.RecoveryPreview
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicReference

class MediaRecoveryService : Service() {
    enum class Action { SCAN, QUERY, MATCH, WRITE }
    data class Command(val action: Action,val session: String,val preview: RecoveryPreview? = null)

    companion object {
        private const val channel = "media_recovery"
        private const val notificationId = 7026
        private val pending = AtomicReference<Command?>(null)
        val running = MutableStateFlow(false)
        val message = MutableStateFlow("")
        fun start(context: Context, command: Command) {
            if (!pending.compareAndSet(null,command)) return
            try { ContextCompat.startForegroundService(context,Intent(context,MediaRecoveryService::class.java)) }
            catch (e: Exception) { pending.compareAndSet(command,null); throw e }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "pause") {
            job?.cancel() ?: stopSelf(startId)
            return START_NOT_STICKY
        }
        val cmd = pending.get()
        if (cmd == null) { stopSelf(startId); return START_NOT_STICKY }
        if (job != null) return START_NOT_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channel,"失联记录恢复",NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this,0,Intent(this,MediaRecoveryActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this,0,Intent(this,MediaRecoveryService::class.java).setAction("pause"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(notificationId,NotificationCompat.Builder(this,channel).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.media_recovery_title)).setContentText("任务进行中，点击查看进度")
            .setContentIntent(open).addAction(android.R.drawable.ic_media_pause,"暂停",pause).setOngoing(true).build())
        running.value = true
        message.value = ""
        val repo = (application as BlitzApp).mediaRecovery
        job = scope.launch {
            try {
                when(cmd.action) {
                    Action.SCAN -> repo.scan()
                    Action.QUERY -> repo.query(cmd.session)
                    Action.MATCH -> repo.match(cmd.session)
                    Action.WRITE -> repo.writer.commit(checkNotNull(cmd.preview))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.localizedMessage?.take(150) ?: "任务中断，请重试" }
            finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    pending.compareAndSet(cmd,null)
                    running.value = false
                    job = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
