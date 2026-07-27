package com.blitz.downloader.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.R
import com.blitz.downloader.activity.ManageActivity
import com.blitz.downloader.data.VideoTagRepository
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台批量下载服务：把批量下载从 Fragment 生命周期里搬出来，避免「离开页面/退到后台就断」。
 *
 * 交接方式：任务对象较大（含 `VideoItemUiModel` 列表），不走 Intent 序列化，改用同进程内存队列
 * [pendingJobs] 传递；Intent 仅作为「有新任务，请拉起服务」的信号。
 *
 * 流程：`start()` 入队 → `onStartCommand` 拉起前台通知并启动串行 worker → 逐个任务下载（复用
 * [BatchDownloadCoordinator.downloadSelected] 的并发/重试）→ 成功项在服务内自行写库 → 通知栏
 * 汇报进度与结果 → 队列清空后退出前台并 `stopSelf`。
 *
 * 说明：本实现保证「应用在后台但进程存活」期间下载不中断；不做**跨进程重启**的断点续传
 * （那属于「下载队列持久化」，是后续可选增强）。
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val working = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        // 先进入前台，避免 5 秒内未 startForeground 被系统判违规。
        startForegroundInitial()
        ensureWorker()
        return START_NOT_STICKY
    }

    private fun startForegroundInitial() {
        val notif = buildProgressNotification(done = 0, total = 0, running = 0, indeterminate = true)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, FOREGROUND_ID, notif, type)
    }

    /** 若 worker 未运行则启动；串行消费 [pendingJobs]。 */
    private fun ensureWorker() {
        if (!working.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (true) {
                    val job = pendingJobs.poll() ?: break
                    runCatching { processJob(job) }
                        .onFailure { Log.e(TAG, "job failed", it) }
                }
            } finally {
                working.set(false)
                // worker 结束前再检查一次队列，避免与新入队产生竞态漏单
                if (pendingJobs.isEmpty()) {
                    stopForegroundCompat()
                    stopSelf()
                } else {
                    ensureWorker()
                }
            }
        }
    }

    private suspend fun processJob(job: DownloadJob) {
        val total = job.items.count { it.isSelected && (!it.downloadUrl.isNullOrBlank() || it.imageUrls.isNotEmpty()) }
        if (total == 0) return

        updateProgress(done = 0, total = total, running = total, indeterminate = false)

        val result = BatchDownloadCoordinator.downloadSelected(
            context = applicationContext,
            items = job.items,
            onItemDone = { done, tot, _ ->
                updateProgress(done = done, total = tot, running = tot, indeterminate = false)
            },
        )

        // 下载成功项在服务内写库（含收藏夹标签关联），保证离开页面也不丢记录。
        val repo = BlitzApp.instance.downloadedVideoRepository
        val tagRepo = VideoTagRepository(applicationContext)
        result.succeededItems.forEach { item ->
            val meta = job.metas[item.id] ?: return@forEach
            repo.recordSuccessfulDownload(
                awemeId = item.id,
                downloadType = meta.downloadType,
                userName = meta.userName,
                mediaType = meta.mediaType,
                filePath = result.succeededPaths[item.id].orEmpty(),
                coverPath = result.succeededCovers[item.id].orEmpty(),
                createTime = meta.createTime,
                desc = meta.desc,
                collectionType = meta.collectionType,
                collectId = meta.collectId,
                videoAuthorSecUserId = meta.videoAuthorSecUserId,
                sourceOwnerSecUserId = meta.sourceOwnerSecUserId,
                userRelation = meta.userRelation,
                diggCount = meta.diggCount,
                collectCount = meta.collectCount,
            )
            if (meta.linkCollectFolderTag && meta.collectionType.isNotBlank()) {
                tagRepo.ensureCollectFolderTagLinked(awemeId = item.id, folderName = meta.collectionType)
            }
        }

        notifyComplete(result.success, result.failed)
    }

    // ── 通知 ───────────────────────────────────────────────────────────────────

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW, // 低优先级：不响铃、不打扰
        ).apply {
            description = getString(R.string.download_channel_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, ManageActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flag = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flag)
    }

    private fun buildProgressNotification(
        done: Int,
        total: Int,
        running: Int,
        indeterminate: Boolean,
    ): android.app.Notification {
        val text = if (indeterminate || total == 0) {
            getString(R.string.download_notif_preparing)
        } else {
            getString(R.string.download_notif_progress, done, total)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.download_notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .setProgress(if (indeterminate) 0 else total, done, indeterminate)
            .build()
    }

    private fun updateProgress(done: Int, total: Int, running: Int, indeterminate: Boolean) {
        val notif = buildProgressNotification(done, total, running, indeterminate)
        NotificationManagerCompat.from(this).notify(FOREGROUND_ID, notif)
    }

    private fun notifyComplete(success: Int, failed: Int) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.download_notif_title))
            .setContentText(getString(R.string.download_notif_done, success, failed))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        // 用独立 id 发「完成」通知，避免被随后退出前台的清理动作一起撤掉。
        NotificationManagerCompat.from(this).notify(nextCompleteId(), notif)
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "blitz_downloads"
        private const val FOREGROUND_ID = 1001
        private var completeIdSeq = 2000
        private fun nextCompleteId(): Int = ++completeIdSeq

        /** 同进程任务交接队列（Intent 无法承载大列表）。 */
        private val pendingJobs = ConcurrentLinkedQueue<DownloadJob>()

        /** 入队一个下载任务并拉起前台服务。 */
        fun start(context: Context, job: DownloadJob) {
            pendingJobs.add(job)
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
