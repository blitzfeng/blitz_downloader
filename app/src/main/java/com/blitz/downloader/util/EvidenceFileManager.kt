package com.blitz.downloader.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.util.Log
import com.blitz.downloader.llm.ImagePart
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * AI 推断证据帧（关联图）的文件管理器。
 *
 * 职责：
 * 1. 证据帧图片在 `<存储根目录>/Download/bDouyin/covers/evidence` 下的落盘管理；
 * 2. 尺寸减半（宽高各按 50% 降采样）与 80% 质量压缩，严格控制存储与后续 Token 消耗；
 * 3. 单个视频最多保存 [MAX_EVIDENCE_PER_VIDEO] 张代表性关联图；
 * 4. 提供给多模态 Few-Shot 读取已沉淀关联图的轻量加载能力。
 */
object EvidenceFileManager {

    private const val TAG = "EvidenceFileManager"

    /** 相对外部存储根目录的相对子路径 */
    const val RELATIVE_EVIDENCE_SUBDIR: String = "Download/bDouyin/covers/evidence"

    /** 单个视频保留证据帧上限（兼顾 Accepted 与 Rejected） */
    const val MAX_EVIDENCE_PER_VIDEO: Int = 4

    /** 压缩质量 */
    private const val JPEG_QUALITY: Int = 80

    /**
     * 获取证据图存放的绝对目录：`<外部存储>/Download/bDouyin/covers/evidence`。
     */
    fun getEvidenceDir(rootDir: File = DownloadedMediaFileManager.getExternalStorageDirectorySafely()): File {
        val dir = File(rootDir, RELATIVE_EVIDENCE_SUBDIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 将给定的图片字节数组尺寸减半（50% 降采样）并以 80% 质量保存为证据帧 JPEG。
     *
     * @param awemeId 视频 ID
     * @param frameIndex 对应的请求帧序号（0 为封面，1+ 为抽取的关键帧）
     * @param rawJpegBytes 原始图片 JPEG 数据
     * @return 本地相对路径（如 `Download/bDouyin/covers/evidence/12345_evidence_1.jpg`），失败返回 null
     */
    fun saveDownscaledEvidenceFrame(
        awemeId: String,
        frameIndex: Int,
        rawJpegBytes: ByteArray,
        rootDir: File = DownloadedMediaFileManager.getExternalStorageDirectorySafely(),
    ): String? {
        if (rawJpegBytes.isEmpty()) return null
        return try {
            val originalBitmap = BitmapFactory.decodeByteArray(rawJpegBytes, 0, rawJpegBytes.size) ?: return null
            val targetWidth = (originalBitmap.width * 0.5f).toInt().coerceAtLeast(1)
            val targetHeight = (originalBitmap.height * 0.5f).toInt().coerceAtLeast(1)
            val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true)

            val dir = getEvidenceDir(rootDir)
            val fileName = "${awemeId}_evidence_${frameIndex}.jpg"
            val targetFile = File(dir, fileName)

            FileOutputStream(targetFile).use { fos ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
            }

            if (scaledBitmap !== originalBitmap) {
                scaledBitmap.recycle()
            }
            originalBitmap.recycle()

            "$RELATIVE_EVIDENCE_SUBDIR/$fileName"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save evidence frame for $awemeId, frame $frameIndex", e)
            null
        }
    }

    /**
     * 读取指定相对路径的证据图为 [ImagePart]，供大模型多模态请求作为 Few-Shot 参考图使用。
     *
     * @param relativePath 如 `Download/bDouyin/covers/evidence/xxx.jpg`
     * @return 对应的 [ImagePart]，文件不存在或损坏时返回 null（供调用方优雅降级）
     */
    fun loadEvidenceImagePart(
        relativePath: String,
        rootDir: File = DownloadedMediaFileManager.getExternalStorageDirectorySafely(),
    ): ImagePart? {
        if (relativePath.isBlank()) return null
        val file = DownloadedMediaFileManager.resolveFile(relativePath, rootDir)
        if (!file.isFile || file.length() == 0L) return null
        return try {
            val bytes = file.readBytes()
            ImagePart(jpegBytes = bytes, hasFace = false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read evidence image: $relativePath", e)
            null
        }
    }

    /**
     * 删除指定视频下的所有证据图文件。
     */
    fun deleteEvidenceFiles(
        awemeId: String,
        rootDir: File = DownloadedMediaFileManager.getExternalStorageDirectorySafely(),
    ) {
        try {
            val dir = getEvidenceDir(rootDir)
            val prefix = "${awemeId}_evidence_"
            val files = dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }
            files?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete evidence files for $awemeId", e)
        }
    }
}
