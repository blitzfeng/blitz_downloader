package com.blitz.downloader.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.R
import com.blitz.downloader.activity.BatchTagReviewActivity
import com.blitz.downloader.config.AppSettings
import com.blitz.downloader.data.db.AiTagSuggestionPendingEntity
import com.blitz.downloader.util.VideoFrameExtractor
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 前台批量 AI 标签分析服务：
 *
 * 参照 [com.blitz.downloader.download.DownloadService] 架构：
 * 前台通知、串行处理队列、进度更新、完成后停止。
 *
 * 逐条调用 [com.blitz.downloader.data.AiTagSuggestionRepository.requestSuggestion] 获取建议，
 * 成功项把返回的 analysisId 与建议标签名 upsert 进 `ai_tag_suggestion_pending` 表；
 * 失败项记录日志并跳过继续处理下一条，不中断队列。
 */
class AiBatchAnalysisService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val working = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForegroundInitial()
        ensureWorker()
        return START_NOT_STICKY
    }

    private fun startForegroundInitial() {
        val notif = buildProgressNotification(done = 0, total = 0, indeterminate = true)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, FOREGROUND_ID, notif, type)
    }

    private fun ensureWorker() {
        if (!working.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (true) {
                    val job = pendingJobs.poll() ?: break
                    runCatching { processJob(job) }
                        .onFailure { Log.e(TAG, "batch analysis job failed", it) }
                }
            } finally {
                working.set(false)
                if (pendingJobs.isEmpty()) {
                    stopForegroundCompat()
                    stopSelf()
                } else {
                    ensureWorker()
                }
            }
        }
    }

    private suspend fun processJob(job: BatchAnalysisJob) {
        val awemeIds = job.awemeIds
        val total = awemeIds.size
        if (total == 0) return

        AiBatchAnalysisEvents.setAnalyzing(true, total)
        updateProgress(done = 0, total = total, indeterminate = false)

        val app = BlitzApp.instance
        val videoRepo = app.downloadedVideoRepository
        val tagRepo = app.videoTagRepository
        val aiRepo = app.aiTagSuggestionRepository
        val pendingDao = app.database.aiTagSuggestionPendingDao()

        // 确保所有标签均已分配稳定唯一数值 ID
        withContext(Dispatchers.IO) {
            tagRepo.backfillTagIds()
        }

        var succeededCount = 0
        var failedCount = 0

        for ((index, awemeId) in awemeIds.withIndex()) {
            val success = withContext(Dispatchers.IO) {
                try {
                    val video = videoRepo.getByAwemeIds(listOf(awemeId)).firstOrNull()
                    if (video == null) {
                        Log.w(TAG, "Video not found for $awemeId")
                        return@withContext false
                    }

                    val storageRoot = Environment.getExternalStorageDirectory()
                    val coverFile = File(storageRoot, video.coverPath)
                    val coverBytes = VideoFrameExtractor.compressCoverImage(coverFile)
                        ?: runCatching { coverFile.readBytes() }.getOrNull()

                    if (coverBytes == null) {
                        Log.w(TAG, "Cover file missing for $awemeId: ${video.coverPath}")
                        return@withContext false
                    }

                    val videoFile = video.filePath.takeIf { it.isNotBlank() }?.let { File(storageRoot, it) }

                    val outcome = aiRepo.requestSuggestion(
                        awemeId = video.awemeId,
                        secUserId = video.videoAuthorSecUserId,
                        desc = video.desc,
                        coverBytes = coverBytes,
                        videoFile = videoFile,
                    )

                    outcome.fold(
                        onSuccess = { result ->
                            val candidateNames = if (result.candidateTagNames.isNotEmpty()) {
                                result.candidateTagNames.distinct()
                            } else {
                                val allTags = tagRepo.getAvailableTagEntities()
                                val tagMap = allTags.associate { it.id to it.tagName }
                                result.candidateTagIds.mapNotNull { tagMap[it] }.distinct()
                            }
                            val suggestedTags = candidateNames.joinToString("|")
                            Log.i(TAG, "[$awemeId] 建议标签: $suggestedTags")

                            pendingDao.upsert(
                                AiTagSuggestionPendingEntity(
                                    awemeId = video.awemeId,
                                    analysisId = result.analysisId,
                                    suggestedTags = suggestedTags,
                                    generatedAtMillis = System.currentTimeMillis(),
                                ),
                            )
                            true
                        },
                        onFailure = { err ->
                            Log.e(TAG, "Analysis failed for $awemeId", err)
                            false
                        },
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Exception during analysis for $awemeId", e)
                    false
                }
            }

            if (success) {
                succeededCount++
            } else {
                failedCount++
            }

            val done = index + 1
            AiBatchAnalysisEvents.updateProgress(done = done, total = total)
            AiBatchAnalysisEvents.notifyItemDone(awemeId, success)
            updateProgress(done = done, total = total, indeterminate = false)
        }

        AiBatchAnalysisEvents.notifyBatchFinished(succeededCount, failedCount)
        notifyComplete(succeededCount, failedCount)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.batch_tag_review_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.batch_tag_review_channel_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, BatchTagReviewActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flag = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flag)
    }

    private fun buildProgressNotification(
        done: Int,
        total: Int,
        indeterminate: Boolean,
    ): android.app.Notification {
        val text = if (indeterminate || total == 0) {
            getString(R.string.batch_tag_review_notif_preparing)
        } else {
            getString(R.string.batch_tag_review_notif_progress, done, total)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.batch_tag_review_notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .setProgress(if (indeterminate) 0 else total, done, indeterminate)
            .build()
    }

    private fun updateProgress(done: Int, total: Int, indeterminate: Boolean) {
        val notif = buildProgressNotification(done, total, indeterminate)
        NotificationManagerCompat.from(this).notify(FOREGROUND_ID, notif)
    }

    private fun notifyComplete(success: Int, failed: Int) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.batch_tag_review_notif_done_title))
            .setContentText(getString(R.string.batch_tag_review_notif_done_text, success, failed))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        NotificationManagerCompat.from(this).notify(nextCompleteId(), notif)
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    data class BatchAnalysisJob(val awemeIds: List<String>)

    companion object {
        private const val TAG = "AiBatchAnalysisService"
        private const val CHANNEL_ID = "blitz_ai_batch_analysis"
        private const val FOREGROUND_ID = 2001
        private var completeIdSeq = 3000
        private fun nextCompleteId(): Int = ++completeIdSeq

        private val pendingJobs = ConcurrentLinkedQueue<BatchAnalysisJob>()

        /** 检查 AI 建议标签功能是否启用且配置了 API Key */
        fun isConfigured(context: Context): Boolean {
            return AppSettings.isAiSuggestionEnabled(context) &&
                AppSettings.getGeminiApiKey(context).isNotBlank()
        }

        /** 启动批量分析服务 */
        fun start(context: Context, awemeIds: List<String>): Boolean {
            if (!isConfigured(context)) return false
            if (awemeIds.isEmpty()) return false
            pendingJobs.add(BatchAnalysisJob(awemeIds))
            val intent = Intent(context, AiBatchAnalysisService::class.java)
            ContextCompat.startForegroundService(context, intent)
            return true
        }
    }
}
