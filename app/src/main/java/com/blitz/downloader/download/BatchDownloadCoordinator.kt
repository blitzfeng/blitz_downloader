package com.blitz.downloader.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.blitz.downloader.ui.VideoItemUiModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 列表批量下载：仅处理 [VideoItemUiModel.isSelected] 且 [VideoItemUiModel.downloadUrl] 非空的项；
 * 并发、[retryAttempts] 与 F2 `f2/dl/base_downloader.py` 中单次 range 请求循环（`retry_attempts = 3`）对齐；
 * F2 该处重试循环内无 `asyncio.sleep`，失败重试前仅加极短退避以缓解瞬时断连。
 *
 * 保存目录：`Download/bDouyin/videos`（视频）/ `Download/bDouyin/images`（图集）。
 */
object BatchDownloadCoordinator {

    private const val TAG = "BatchDownload"

    /** 根子目录：`Download/bDouyin` */
    const val RELATIVE_DOWNLOAD_SUBDIR: String = "bDouyin"

    /** 视频子目录：`Download/bDouyin/videos` */
    const val VIDEO_SUBDIR: String = "$RELATIVE_DOWNLOAD_SUBDIR/videos"

    /** 图片子目录：`Download/bDouyin/images` */
    const val IMAGE_SUBDIR: String = "$RELATIVE_DOWNLOAD_SUBDIR/images"

    /**
     * 封面缩略图子目录：`Download/bDouyin/covers`。
     * 视频封面在主体下载时一并保存；图集封面直接复用第一张图片路径，不重复写入。
     */
    const val COVER_SUBDIR: String = "$RELATIVE_DOWNLOAD_SUBDIR/covers"

    /** 与 F2 `base_downloader.py` 中 `retry_attempts = 3` 一致。 */
    const val DEFAULT_MAX_RETRIES: Int = 3

    /** 同时下载任务数上限（移动端略保守）。 */
    const val DEFAULT_MAX_CONCURRENT: Int = 3

    /** 单次下载任务的内部结果（主文件路径 + 封面路径）。 */
    private data class DownloadOutcome(
        val filePath: String,
        val coverPath: String,
    )

    data class BatchResult(
        val total: Int,
        val success: Int,
        val failed: Int,
        /** HTTP 写入成功、可写入本地库的 [VideoItemUiModel]（与 success 数量一致）。 */
        val succeededItems: List<VideoItemUiModel> = emptyList(),
        /**
         * 各成功项的主文件可读路径（awemeId → 路径字符串）。
         * 视频为完整文件路径；图集为第一张图的路径。
         */
        val succeededPaths: Map<String, String> = emptyMap(),
        /**
         * 各成功项的封面本地路径（awemeId → 路径字符串）。
         * 视频封面保存在 `covers/` 子目录；图集封面等于 [succeededPaths] 中对应值（第一张图）。
         * 封面下载为尽力而为，失败时该项为空字符串。
         */
        val succeededCovers: Map<String, String> = emptyMap(),
    )

    /**
     * @param items 当前列表中的项（调用方传入快照，通常含仅选中项）；视频与图集均支持。
     */
    suspend fun downloadSelected(
        context: Context,
        items: List<VideoItemUiModel>,
        maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
        maxRetries: Int = DEFAULT_MAX_RETRIES,
    ): BatchResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val videoTargets = items.filter { it.isSelected && !it.isPhoto && !it.downloadUrl.isNullOrBlank() }
        val photoTargets = items.filter { it.isSelected && it.isPhoto && it.imageUrls.isNotEmpty() }
        val total = videoTargets.size + photoTargets.size
        if (total == 0) {
            return@withContext BatchResult(total = 0, success = 0, failed = 0, succeededItems = emptyList())
        }
        val sem = Semaphore(maxConcurrent.coerceAtLeast(1))

        // item to DownloadOutcome-or-null
        val videoPairs = coroutineScope {
            videoTargets.map { item ->
                async {
                    val outcome = sem.withPermit {
                        val url = item.downloadUrl!!
                        val base = buildFileNameBase(item)
                        val filePath = downloadToMediaStoreWithRetries(
                            app = app,
                            videoUrl = url,
                            displayName = "$base.mp4",
                            maxRetries = maxRetries,
                        ) ?: return@withPermit null
                        // 封面：尽力而为，不计入成功/失败统计
                        val coverPath = if (!item.coverUrl.isNullOrBlank()) {
                            val ext = extractImageExtension(item.coverUrl)
                            downloadCoverBestEffort(app, item.coverUrl, "$base.$ext")
                        } else ""
                        DownloadOutcome(filePath = filePath, coverPath = coverPath)
                    }
                    item to outcome
                }
            }.awaitAll()
        }

        val photoPairs = coroutineScope {
            photoTargets.map { item ->
                async {
                    val outcome = sem.withPermit {
                        val firstImagePath = downloadPhotoGalleryWithRetries(
                            app = app,
                            item = item,
                            maxRetries = maxRetries,
                        ) ?: return@withPermit null
                        // 图集封面直接复用第一张图，不重复下载
                        DownloadOutcome(filePath = firstImagePath, coverPath = firstImagePath)
                    }
                    item to outcome
                }
            }.awaitAll()
        }

        val allPairs = videoPairs + photoPairs
        val succeededPairs = allPairs.filter { it.second != null }
        val succeededItems = succeededPairs.map { it.first }
        val succeededPaths = succeededPairs.associate { (item, o) -> item.id to o!!.filePath }
        val succeededCovers = succeededPairs.associate { (item, o) -> item.id to o!!.coverPath }
        val ok = succeededItems.size
        BatchResult(
            total = total,
            success = ok,
            failed = allPairs.size - ok,
            succeededItems = succeededItems,
            succeededPaths = succeededPaths,
            succeededCovers = succeededCovers,
        )
    }

    /**
     * 下载图集中的全部图片；全部成功才视为整体成功（与视频重试逻辑对齐）。
     * 文件名：`{作者}_{描述}_{序号}.jpg`（序号从 01 起，两位补零）。
     * @return 第一张图片的存储路径（供数据库记录），任意一张失败则返回 null。
     */
    private suspend fun downloadPhotoGalleryWithRetries(
        app: Context,
        item: VideoItemUiModel,
        maxRetries: Int,
    ): String? {
        val base = buildFileNameBase(item)
        val total = item.imageUrls.size
        var firstPath: String? = null
        var allOk = true
        for (idx in item.imageUrls.indices) {
            val url = item.imageUrls[idx]
            val ext = extractImageExtension(url)
            val displayName = "${base}_${(idx + 1).toString().padStart(2, '0')}.$ext"
            val path = downloadImageToMediaStoreWithRetries(
                app = app,
                imageUrl = url,
                displayName = displayName,
                maxRetries = maxRetries,
                index = idx,
                total = total,
            )
            if (path == null) allOk = false
            if (idx == 0) firstPath = path
        }
        return if (allOk) firstPath else null
    }

    /**
     * @return 文件的可读存储路径（如 `Download/bDouyin/images/xxx.jpg`），失败返回 null。
     */
    private suspend fun downloadImageToMediaStoreWithRetries(
        app: Context,
        imageUrl: String,
        displayName: String,
        maxRetries: Int,
        index: Int,
        total: Int,
    ): String? {
        val attempts = maxRetries.coerceAtLeast(1)
        repeat(attempts) { attempt ->
            if (attempt > 0) {
                delay(retryDelayMsForAttemptAfterFailure(attempt - 1))
            }
            val path = runCatching {
                downloadImageToMediaStoreOnce(app, imageUrl, displayName)
            }.getOrNull()
            if (path != null) return path
            Log.w(TAG, "image download failed attempt ${attempt + 1}/$attempts [$index/$total]: $displayName")
        }
        return null
    }

    /**
     * @return 文件的可读存储路径（如 `Download/bDouyin/images/xxx.jpg`），失败抛出异常或返回 null。
     *
     * 使用 [MediaStore.Downloads] 集合（与视频相同）以保证在所有 Android 10+ 设备上可写入。
     * MIME type 保留图片类型，文件管理器/相册可按类型识别。
     */
    private fun downloadImageToMediaStoreOnce(
        app: Context,
        imageUrl: String,
        displayName: String,
    ): String? {
        val resolver = app.contentResolver
        val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/$IMAGE_SUBDIR"
        val mimeType = if (displayName.endsWith(".webp", ignoreCase = true)) "image/webp" else "image/jpeg"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativeDir)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection: Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { outStream ->
                val conn = URL(imageUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                DouyinVideoHttp.applyCdnHeaders(conn)
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "CDN HTTP $code for image $displayName url=${imageUrl.take(96)}…")
                    conn.disconnect()
                    resolver.delete(uri, null, null)
                    return null
                }
                conn.inputStream.use { input -> input.copyTo(outStream) }
                conn.disconnect()
            } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "$relativeDir/$displayName"
        } catch (e: Exception) {
            Log.e(TAG, "image download error: $displayName", e)
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            null
        }
    }

    /** 从图片 URL 中提取扩展名（webp / jpeg → jpg），默认返回 `jpg`。 */
    internal fun extractImageExtension(url: String): String {
        val path = url.substringBefore("?").trimEnd('/')
        return when {
            path.endsWith(".webp", ignoreCase = true) -> "webp"
            path.endsWith(".jpeg", ignoreCase = true) -> "jpg"
            path.endsWith(".jpg", ignoreCase = true) -> "jpg"
            path.endsWith(".png", ignoreCase = true) -> "png"
            else -> "jpg"
        }
    }

    /**
     * 尽力下载封面缩略图到 `Download/bDouyin/covers/` 目录；失败不抛异常、不影响主体下载。
     *
     * 封面**不会出现在相册类 App** 中：
     * - API 29+：写入 [MediaStore.Downloads]（相册 App 不扫描 Downloads 内容）。
     * - API 24–28：通过 [File] API 直接写文件，并在目录中放置 `.nomedia` 文件阻止媒体扫描器索引。
     *
     * @return 成功时的可读路径；失败返回空字符串。
     */
    private fun downloadCoverBestEffort(app: Context, coverUrl: String, displayName: String): String =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                downloadCoverViaMediaStoreDownloads(app, coverUrl, displayName)
            } else {
                downloadCoverViaFileApi(coverUrl, displayName)
            }
        }.getOrDefault("")

    /** API 29+：写入 MediaStore.Downloads，相册 App 不索引此集合，文件不会出现在相册中。 */
    private fun downloadCoverViaMediaStoreDownloads(
        app: Context,
        coverUrl: String,
        displayName: String,
    ): String {
        val resolver = app.contentResolver
        val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/$COVER_SUBDIR"
        val mimeType = if (displayName.endsWith(".webp", ignoreCase = true)) "image/webp" else "image/jpeg"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativeDir)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection: Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return ""
        return try {
            resolver.openOutputStream(uri)?.use { outStream ->
                fetchUrlToStream(coverUrl) { input -> input.copyTo(outStream) }
                    ?: run {
                        resolver.delete(uri, null, null)
                        return ""
                    }
            } ?: run {
                resolver.delete(uri, null, null)
                return ""
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "$relativeDir/$displayName"
        } catch (e: Exception) {
            Log.w(TAG, "cover download (Downloads) failed: $displayName", e)
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            ""
        }
    }
    // 避免被相册扫描当前文件夹
    fun createNoMediaFile(directory: File) {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val noMediaFile = File(directory, ".nomedia")
        if (!noMediaFile.exists()) {
            try {
                noMediaFile.createNewFile()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
    /**
     * API 24–28：通过 [File] API 写文件，并在 covers 目录中创建 `.nomedia`，
     * 阻止媒体扫描器索引该目录（文件不会出现在相册中）。
     * `WRITE_EXTERNAL_STORAGE`（maxSdkVersion=28）权限已在 Manifest 中声明。
     */
    @Suppress("DEPRECATION")
    private fun downloadCoverViaFileApi(
        coverUrl: String,
        displayName: String,
    ): String {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val coversDir = File(base, COVER_SUBDIR)
        if (!coversDir.exists()) {
            coversDir.mkdirs()
            // .nomedia 阻止媒体扫描器索引本目录
            File(coversDir, ".nomedia").createNewFile()
        }
        val outFile = File(coversDir, displayName)
        return try {
            outFile.outputStream().use { outStream ->
                fetchUrlToStream(coverUrl) { input -> input.copyTo(outStream) }
                    ?: run {
                        outFile.delete()
                        return ""
                    }
            }
            outFile.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "cover download (File) failed: $displayName", e)
            outFile.delete()
            ""
        }
    }

    /**
     * 打开 HTTP 连接并在 [block] 中消费响应流；返回 [block] 的结果，HTTP 非 2xx 或异常时返回 null。
     */
    private inline fun <T> fetchUrlToStream(url: String, block: (java.io.InputStream) -> T): T? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        DouyinVideoHttp.applyCdnHeaders(conn)
        return try {
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            conn.inputStream.use { block(it) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * `{作者昵称}_{去掉#话题后的描述}.mp4`；若去掉话题后无描述则为 `{作者昵称}_{videoId}.mp4`。
     */
    internal fun buildFileName(item: VideoItemUiModel): String = "${buildFileNameBase(item)}.mp4"

    /**
     * 无扩展名的基础文件名，图集各张图在其后追加 `_{序号}.{ext}`。
     * `{作者昵称}_{去掉#话题后的描述}`；若去掉话题后无描述则为 `{作者昵称}_{id}`。
     */
    internal fun buildFileNameBase(item: VideoItemUiModel): String {
        val user = sanitizeFileNameBase(item.authorNickname).ifBlank { "user" }.take(40)
        val descClean = sanitizeFileNameBase(stripHashtagTopics(item.descRaw)).trim()
        val body = if (descClean.isBlank()) {
            sanitizeFileNameBase(item.id).ifBlank { "item" }
        } else {
            descClean.take(80)
        }
        return "${user}_${body}"
    }

    /** 去掉 `#xxx`、`＃xxx` 等形式的话题标签，并折叠空白。 */
    internal fun stripHashtagTopics(raw: String): String =
        Regex("[#＃][^\\s#＃]+").replace(raw, " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun sanitizeFileNameBase(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim()

    /**
     * F2：最多 3 次尝试；重试前退避（毫秒）——F2 源码无显式 sleep，此处为 0 / 短 / 略长 阶梯，避免忙等。
     */
    internal fun retryDelayMsForAttemptAfterFailure(attemptIndex: Int): Long = when (attemptIndex) {
        0 -> 0L
        1 -> 200L
        else -> 500L
    }

    /**
     * @return 文件的可读存储路径（如 `Download/bDouyin/videos/xxx.mp4`），失败返回 null。
     */
    private suspend fun downloadToMediaStoreWithRetries(
        app: Context,
        videoUrl: String,
        displayName: String,
        maxRetries: Int,
    ): String? {
        val attempts = maxRetries.coerceAtLeast(1)
        repeat(attempts) { attempt ->
            if (attempt > 0) {
                delay(retryDelayMsForAttemptAfterFailure(attempt - 1))
            }
            val path = runCatching { downloadToMediaStoreOnce(app, videoUrl, displayName) }.getOrNull()
            if (path != null) return path
            Log.w(TAG, "download failed attempt ${attempt + 1}/$attempts: $displayName")
        }
        return null
    }

    /**
     * @return 文件的可读存储路径（如 `Download/bDouyin/videos/xxx.mp4`），失败抛出异常或返回 null。
     *
     * 使用 [MediaStore.Downloads] 集合（与原始实现一致），避免部分设备对
     * [MediaStore.Video.Media] + `Download/` 路径的组合拒绝 insert。
     * MIME type 保留 `video/mp4`，媒体播放器可正确识别。
     */
    private fun downloadToMediaStoreOnce(
        app: Context,
        videoUrl: String,
        displayName: String,
    ): String? {
        val resolver = app.contentResolver
        val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/$VIDEO_SUBDIR"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, relativeDir)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { outStream ->
                val conn = URL(videoUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 20_000
                conn.readTimeout = 120_000
                DouyinVideoHttp.applyCdnHeaders(conn)
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "CDN HTTP $code for $displayName url=${videoUrl.take(96)}…")
                    conn.disconnect()
                    resolver.delete(uri, null, null)
                    return null
                }
                conn.inputStream.use { input -> input.copyTo(outStream) }
                conn.disconnect()
            } ?: run {
                resolver.delete(uri, null, null)
                return null
            }

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "$relativeDir/$displayName"
        } catch (e: Exception) {
            Log.e(TAG, "download error: $displayName", e)
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            null
        }
    }
}
