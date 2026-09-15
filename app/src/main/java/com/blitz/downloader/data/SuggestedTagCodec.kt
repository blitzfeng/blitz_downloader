package com.blitz.downloader.data

import com.blitz.downloader.llm.TagCandidate

data class SuggestedTagDetail(
    val tagId: Long,
    val confidence: Float?,
    val evidencePath: String? = null,
)

/**
 * [com.blitz.downloader.data.db.VideoAiAnalysisEntity.suggestedTagIds] 的编解码：
 * `"id:confidence"` 或 `"id:confidence:evidencePath"`，`|` 分隔多项。
 * 独立成纯函数对象，方便单元测试（不需要 Context/Room）。
 */
object SuggestedTagCodec {

    fun encode(
        candidates: List<TagCandidate>,
        tagIdToEvidencePath: Map<Long, String> = emptyMap(),
    ): String = candidates.joinToString("|") { candidate ->
        val path = tagIdToEvidencePath[candidate.tagId]
        if (!path.isNullOrBlank()) {
            "${candidate.tagId}:${candidate.confidence}:$path"
        } else {
            "${candidate.tagId}:${candidate.confidence}"
        }
    }

    /**
     * 解析解码：兼容历史 `"id:confidence"` 格式与新增的 `"id:confidence:evidencePath"` 格式。
     * 解析失败的单项（格式损坏）直接跳过，不影响其余项。
     */
    fun decode(encoded: String): Map<Long, SuggestedTagDetail> {
        if (encoded.isBlank()) return emptyMap()
        return encoded.split("|").mapNotNull { entry ->
            val parts = entry.split(":")
            val tagId = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val confidence = parts.getOrNull(1)?.toFloatOrNull()
            val evidencePath = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
            tagId to SuggestedTagDetail(tagId, confidence, evidencePath)
        }.toMap()
    }
}
