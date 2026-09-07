package com.blitz.downloader.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import com.blitz.downloader.llm.ImagePart
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 为 AI 建议标签抽取视频关键帧（不含封面，封面由调用方单独提供），复用 [MediaOrientationProbe]
 * 已经在用的 `MediaMetadataRetriever` API。
 *
 * 策略：按视频时长均匀抽取候选帧（[MIN_CANDIDATES]~[MAX_CANDIDATES] 张），用 [FaceFrameSelector]
 * 从中优选含清晰人脸的 Face Frames，再从时间跨度均匀补足 Body/General Frames，去重合并后
 * 裁到 [MAX_FINAL_FRAMES]（含封面，本类只产出这里面除封面外的部分），统一缩放压缩控制体积。
 *
 * 抽帧/解码失败、文件不存在时返回空列表，不抛异常——调用方在没有关键帧时仍应能只用封面图
 * 发起请求，对应 spec"视频本地文件已丢失"的降级场景。**必须在 IO 线程调用。**
 *
 * 不做旋转修正：ML Kit/多模态视觉模型即使拿到未旋转的画面通常仍能正确识别人脸和内容，
 * 这里不像 [MediaOrientationProbe] 那样要求精确的呈现宽高用于分包决策，属于可接受的简化。
 */
object VideoFrameExtractor {

    private const val TAG = "VideoFrameExtractor"

    /** 候选帧数量下限，即使短视频也至少均匀采样这么多个时间点。 */
    private const val MIN_CANDIDATES = 12

    /** 候选帧数量上限，避免长视频产生过多候选拖慢抽帧与人脸检测。 */
    private const val MAX_CANDIDATES = 20

    /** 每隔这么多毫秒规划一个候选采样点，用于按时长动态决定候选帧数量。 */
    private const val CANDIDATE_INTERVAL_MS = 2500L

    /** 最终上传张数上限（不含封面，封面由调用方单独提供，两者相加对齐文档"8~12 张含封面"的建议）。 */
    private const val MAX_FINAL_FRAMES = 10

    /**
     * 上传前统一缩放到的最长边。**真机验证过：这个值对 token 消耗没有影响**——
     * `GeminiPart.mediaResolution` 的 token 成本按档位固定分配（MEDIUM≈580 token/图，与图片实际
     * 像素尺寸无关，512→384 前后 `usageMetadata.promptTokenCount` 不变），所以别指望靠继续调小
     * 这个值省 token，它只影响上传体积/网络耗时。**真正影响 token 总量的是张数（[MAX_FINAL_FRAMES]）
     * 和每张图的档位比例**（`GeminiProvider.generateTagSuggestion` 里 MEDIUM/LOW 的分配逻辑）。
     * 保留在 384 是因为缩图本身仍有省流量的价值，不是因为它省了 token。
     */
    private const val MAX_DIMENSION = 384

    private const val JPEG_QUALITY = 80

    fun extract(file: File): List<ImagePart> {
        if (!file.isFile) return emptyList()
        val retriever = MediaMetadataRetriever()
        val candidateBitmaps = mutableListOf<Bitmap>()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
            if (durationMs == null || durationMs <= 0) return emptyList()

            val candidateCount = (durationMs / CANDIDATE_INTERVAL_MS).toInt().coerceIn(MIN_CANDIDATES, MAX_CANDIDATES)
            for (i in 0 until candidateCount) {
                val timeUs = durationMs * (i + 1) / (candidateCount + 1) * 1000
                val frame = runCatching {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull()
                if (frame != null) candidateBitmaps.add(frame)
            }
            if (candidateBitmaps.isEmpty()) return emptyList()

            val faceFrames = FaceFrameSelector.selectFaceFrames(candidateBitmaps, MAX_FINAL_FRAMES)
            val faceIndices = faceFrames.map { it.index }.toSet()

            val generalBudget = (MAX_FINAL_FRAMES - faceIndices.size).coerceAtLeast(0)
            val generalIndices = if (generalBudget > 0) {
                evenlySpacedIndices(candidateBitmaps.size, generalBudget).filter { it !in faceIndices }
            } else {
                emptyList()
            }

            val finalIndices = (faceFrames.map { it.index } + generalIndices).distinct().take(MAX_FINAL_FRAMES)

            finalIndices.map { index ->
                val bitmap = candidateBitmaps[index]
                val resized = resizeToMaxDimension(bitmap, MAX_DIMENSION)
                val bytes = compressJpeg(resized)
                if (resized !== bitmap) resized.recycle()
                ImagePart(jpegBytes = bytes, hasFace = index in faceIndices)
            }
        } catch (e: Exception) {
            Log.w(TAG, "视频抽帧失败：${file.name}", e)
            emptyList()
        } finally {
            candidateBitmaps.forEach { it.recycle() }
            runCatching { retriever.release() }
        }
    }

    /**
     * 压缩封面图，供 AI 建议标签请求使用——实验性：封面此前是原始下载文件（接口返回的完整尺寸
     * JPEG/WEBP）未经任何压缩直接上传，走 [BitmapFactory] 解码后复用与关键帧同一套
     * [MAX_DIMENSION]/[JPEG_QUALITY] 参数，验证能否进一步压 token。**调用方需要在这个和
     * "直接读原始字节"之间做取舍——本函数只提供能力，不改变调用方原有行为。**
     * 解码失败（文件损坏/格式不支持/OOM）返回 `null`，调用方应退回读原始字节而非让整次请求失败。
     */
    fun compressCoverImage(file: File): ByteArray? = runCatching {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching null
        val resized = resizeToMaxDimension(bitmap, MAX_DIMENSION)
        val bytes = compressJpeg(resized)
        if (resized !== bitmap) resized.recycle()
        bitmap.recycle()
        bytes
    }.getOrNull()

    /** 在 `[0, total)` 范围内选 [count] 个尽量均匀分布的下标（不足 [count] 个候选时全选）。 */
    private fun evenlySpacedIndices(total: Int, count: Int): List<Int> {
        if (total <= 0 || count <= 0) return emptyList()
        if (count >= total) return (0 until total).toList()
        return (0 until count).map { i -> i * (total - 1) / (count - 1).coerceAtLeast(1) }.distinct()
    }

    private fun resizeToMaxDimension(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / longestSide
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun compressJpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            stream.toByteArray()
        }
}
