package com.blitz.downloader.util

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit

/**
 * 用 ML Kit Face Detection（免费、设备端处理、无用量计费）从候选帧里挑出含清晰正脸/半正脸的帧，
 * 供 [VideoFrameExtractor] 组装最终上传给 AI 建议服务的关键帧集合。
 *
 * 全部候选帧都检测不到人脸时返回空列表（不抛异常）——调用方据此自动降级为纯时间点均匀采样，
 * 对应 spec"关键帧选取优先包含清晰人脸，无人脸时自动降级"的场景。
 */
object FaceFrameSelector {

    private const val TAG = "FaceFrameSelector"
    private const val TIMEOUT_SECONDS = 5L

    /** [index] 是 [candidates] 里的下标；[score] 是最大人脸边界框占整帧面积的比例（越大越靠前）。 */
    data class ScoredFrame(val index: Int, val score: Float)

    /**
     * 对 [candidates] 逐帧跑人脸检测，按最大人脸占比降序排列，取前 [maxFaceFrames] 张。
     *
     * 用 `Tasks.await` 同步阻塞而不是 `kotlinx-coroutines-play-services` 的 `.await()`——
     * 只有这一处调用，不值得为此新增一个依赖；调用方（[VideoFrameExtractor]）本身就要求跑在
     * IO 线程，阻塞调用不会占用主线程。单帧检测失败（超时/异常）按"这帧没有人脸"处理，
     * 不影响其余帧的检测结果。
     */
    fun selectFaceFrames(candidates: List<Bitmap>, maxFaceFrames: Int): List<ScoredFrame> {
        if (candidates.isEmpty() || maxFaceFrames <= 0) return emptyList()
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .build(),
        )
        return try {
            candidates.mapIndexedNotNull { index, bitmap ->
                val faces = runCatching {
                    Tasks.await(
                        detector.process(InputImage.fromBitmap(bitmap, 0)),
                        TIMEOUT_SECONDS,
                        TimeUnit.SECONDS,
                    )
                }.onFailure { Log.w(TAG, "第 $index 帧人脸检测失败，按无人脸处理", it) }
                    .getOrNull()
                    .orEmpty()

                val bestFace = faces.maxByOrNull { it.boundingBox.width().toLong() * it.boundingBox.height() }
                    ?: return@mapIndexedNotNull null
                val frameArea = (bitmap.width.toLong() * bitmap.height).coerceAtLeast(1L)
                val faceArea = bestFace.boundingBox.width().toLong() * bestFace.boundingBox.height()
                ScoredFrame(index, faceArea.toFloat() / frameArea)
            }.sortedByDescending { it.score }.take(maxFaceFrames)
        } finally {
            detector.close()
        }
    }
}
