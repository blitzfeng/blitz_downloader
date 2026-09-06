package com.blitz.downloader.data

import com.blitz.downloader.llm.TagCandidate

/**
 * [com.blitz.downloader.data.db.VideoAiAnalysisEntity.suggestedTagIds] 的编解码：
 * `"id:confidence|id:confidence"`，对齐 `userRelation` 的既有 `|` 分隔编码约定。
 * 独立成纯函数对象，方便单元测试（不需要 Context/Room）。
 */
object SuggestedTagCodec {

    fun encode(candidates: List<TagCandidate>): String =
        candidates.joinToString("|") { "${it.tagId}:${it.confidence}" }

    /** 解析失败的单项（格式损坏）直接跳过，不影响其余项——防御性处理优于整体抛异常。 */
    fun decode(encoded: String): Map<Long, Float?> {
        if (encoded.isBlank()) return emptyMap()
        return encoded.split("|").mapNotNull { entry ->
            val parts = entry.split(":")
            val tagId = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val confidence = parts.getOrNull(1)?.toFloatOrNull()
            tagId to confidence
        }.toMap()
    }
}
