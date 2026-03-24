package com.blitz.downloader.download

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.blitz.downloader.ui.VideoItemUiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 列表批量下载：仅处理 [VideoItemUiModel.isSelected] 且 [VideoItemUiModel.downloadUrl] 非空的项；
 * 并发、[retryAttempts] 与 F2 `f2/dl/base_downloader.py` 中单次 range 请求循环（`retry_attempts = 3`）对齐；
 * F2 该处重试循环内无 `asyncio.sleep`，失败重试前仅加极短退避以缓解瞬时断连。
 *
 * 保存目录：`Download/bDouyin`（[RELATIVE_DOWNLOAD_SUBDIR]）。
 */
object BatchDownloadCoordinator {

    private const val TAG = "BatchDownload"

    /** 与用户目录「下载」下的子文件夹名一致：`Download/bDouyin` */
    const val RELATIVE_DOWNLOAD_SUBDIR: String = "bDouyin"

    /** 与 F2 `base_downloader.py` 中 `retry_attempts = 3` 一致。 */
    const val DEFAULT_MAX_RETRIES: Int = 3

    /** 同时下载任务数上限（移动端略保守）。 */
    const val DEFAULT_MAX_CONCURRENT: Int = 3

    data class BatchResult(
        val total: Int,
        val success: Int,
        val failed: Int,
    )

    /**
     * @param items 当前列表中的项（调用方传入快照，通常含仅选中项）
     */
    suspend fun downloadSelected(
        context: Context,
        items: List<VideoItemUiModel>,
        maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
        maxRetries: Int = DEFAULT_MAX_RETRIES,
    ): BatchResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val targets = items.filter { it.isSelected && !it.downloadUrl.isNullOrBlank() }
        if (targets.isEmpty()) {
            return@withContext BatchResult(total = 0, success = 0, failed = 0)
        }
        val sem = Semaphore(maxConcurrent.coerceAtLeast(1))
        val results = coroutineScope {
            targets.map { item ->
                async {
                    sem.withPermit {
                        val url = item.downloadUrl!!
                        val fileName = buildFileName(item)
                        downloadToMediaStoreWithRetries(
                            app = app,
                            videoUrl = url,
                            displayName = fileName,
                            maxRetries = maxRetries,
                        )
                    }
                }
            }.awaitAll()
        }
        val ok = results.count { it }
        BatchResult(total = targets.size, success = ok, failed = results.size - ok)
    }

    internal fun buildFileName(item: VideoItemUiModel): String {
        val base = sanitizeFileNameBase(item.title).ifBlank { "douyin" }
        return "${base}_${item.id}.mp4"
    }

    private fun sanitizeFileNameBase(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(80)

    /**
     * F2：最多 3 次尝试；重试前退避（毫秒）——F2 源码无显式 sleep，此处为 0 / 短 / 略长 阶梯，避免忙等。
     */
    internal fun retryDelayMsForAttemptAfterFailure(attemptIndex: Int): Long = when (attemptIndex) {
        0 -> 0L
        1 -> 200L
        else -> 500L
    }

    private suspend fun downloadToMediaStoreWithRetries(
        app: Context,
        videoUrl: String,
        displayName: String,
        maxRetries: Int,
    ): Boolean {
        val attempts = maxRetries.coerceAtLeast(1)
        repeat(attempts) { attempt ->
            if (attempt > 0) {
                delay(retryDelayMsForAttemptAfterFailure(attempt - 1))
            }
            val ok = runCatching { downloadToMediaStoreOnce(app, videoUrl, displayName) }.getOrDefault(false)
            if (ok) return true
            Log.w(TAG, "download failed attempt ${attempt + 1}/$attempts: $displayName")
        }
        return false
    }

    private fun downloadToMediaStoreOnce(
        app: Context,
        videoUrl: String,
        displayName: String,
    ): Boolean {
        val resolver = app.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$RELATIVE_DOWNLOAD_SUBDIR"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return false

        return try {
            resolver.openOutputStream(uri)?.use { outStream ->
                val conn = URL(videoUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 20_000
                conn.readTimeout = 120_000
                DouyinVideoHttp.applyCdnHeaders(conn)
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(
                        TAG,
                        "CDN HTTP $code for $displayName url=${videoUrl.take(96)}…",
                    )
                    conn.disconnect()
                    return false
                }
                conn.inputStream.use { input -> input.copyTo(outStream) }
                conn.disconnect()
            } ?: return false

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "download error: $displayName", e)
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) { }
            false
        }
    }
}
