package com.blitz.downloader.util

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * 读本地媒体文件的**真实呈现宽高**（即播放器 / 图片查看器里看到的那个方向）。
 *
 * 之所以读文件而不用抖音接口下发的 `video.width/height`：
 * - 接口字段不保证已经过旋转修正，可能与实际呈现方向相反；
 * - 历史下载记录根本没有这个字段，读文件对新老数据一视同仁。
 *
 * **一份逻辑，两个调用点**——下载落盘后（[com.blitz.downloader.download.DownloadService]）
 * 与局域网导出前的懒回填（[com.blitz.downloader.viewmodel.ManageViewModel]）都走这里，
 * 保证同一个文件在两条路径上算出的方向永远一致。
 *
 * **所有方法必须在 IO 线程调用。**
 */
object MediaOrientationProbe {

    private const val TAG = "MediaOrientationProbe"

    /** 修正旋转后的呈现宽高。 */
    data class Size(val width: Int, val height: Int)

    private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "mkv", "webm", "3gp", "avi")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

    /**
     * 探测 [file] 的呈现宽高。
     *
     * 读不出一律返回 `null`（文件损坏、格式不支持、未知扩展名、底层抛异常）。
     * 调用方把 `null` 当作「未知」处理并保持宽高为 0，**不要**写哨兵值——
     * `0` 语义单一（不知道），不必区分「没探过」和「探过但失败」。
     */
    fun probe(file: File): Size? {
        if (!file.isFile) return null
        return when (file.extension.lowercase()) {
            in VIDEO_EXTENSIONS -> probeVideo(file)
            in IMAGE_EXTENSIONS -> probeImage(file)
            else -> null
        }
    }

    /**
     * 视频：读 `VIDEO_WIDTH` / `VIDEO_HEIGHT` / `VIDEO_ROTATION`。
     *
     * **旋转修正不能省。** 1920×1080 且带 90° 旋转元数据的视频，裸读宽高是横屏，
     * 但播放器里呈现为竖屏；不交换就会判反方向，整个分包功能失去意义。
     */
    private fun probeVideo(file: File): Size? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: return null
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: return null
            if (w <= 0 || h <= 0) return null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) Size(h, w) else Size(w, h)
        } catch (e: Exception) {
            Log.w(TAG, "probeVideo failed: ${file.name}", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 图片：`inJustDecodeBounds` 只读文件头、不解码像素（大图也不会 OOM）。
     * JPEG 再读 EXIF `TAG_ORIENTATION`，横置类同样交换宽高。
     */
    private fun probeImage(file: File): Size? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val w = options.outWidth
            val h = options.outHeight
            if (w <= 0 || h <= 0) return null
            if (isExifTransposed(file)) Size(h, w) else Size(w, h)
        } catch (e: Exception) {
            Log.w(TAG, "probeImage failed: ${file.name}", e)
            null
        }
    }

    /** EXIF 方向是否会把宽高对调（旋转 90/270 与两种转置）。读不到 EXIF 一律按「不对调」。 */
    private fun isExifTransposed(file: File): Boolean {
        return runCatching {
            val orientation = ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                orientation == ExifInterface.ORIENTATION_TRANSVERSE
        }.getOrDefault(false)
    }
}
