package com.blitz.downloader.data

import com.blitz.downloader.llm.TagCandidate
import com.google.gson.Gson
import com.google.gson.JsonParser

data class SuggestedTagDetail(
    val tagId: Long,
    val confidence: Float?,
    val evidencePath: String? = null,
    val action: String = "add",
    val replacesTagIds: List<Long> = emptyList(),
    val changeReason: String = "",
    val coexistingTagIds: List<Long> = emptyList(),
)

/**
 * [com.blitz.downloader.data.db.VideoAiAnalysisEntity.suggestedTagIds] 的编解码：
 * 普通建议保留历史分隔格式，替换或共存建议使用 JSON 保存动作、旧标签 ID 和依据。
 * 独立成纯函数对象，方便单元测试（不需要 Context/Room）。
 */
object SuggestedTagCodec {
    private val gson = Gson()

    fun encode(
        candidates: List<TagCandidate>,
        tagIdToEvidencePath: Map<Long, String> = emptyMap(),
    ): String {
        if (candidates.any { it.action != "add" }) {
            return gson.toJson(candidates.map { candidate ->
                SuggestedTagDetail(candidate.tagId, candidate.confidence, tagIdToEvidencePath[candidate.tagId],
                    candidate.action, candidate.replacesTagIds, candidate.changeReason, candidate.coexistingTagIds)
            })
        }
        return candidates.joinToString("|") { candidate ->
            val path = tagIdToEvidencePath[candidate.tagId]
            if (!path.isNullOrBlank()) {
                "${candidate.tagId}:${candidate.confidence}:$path"
            } else {
                "${candidate.tagId}:${candidate.confidence}"
            }
        }
    }

    /**
     * 解析解码：兼容历史 `"id:confidence"` 格式与新增的 `"id:confidence:evidencePath"` 格式。
     * 解析失败的单项（格式损坏）直接跳过，不影响其余项。
     */
    fun decode(encoded: String): Map<Long, SuggestedTagDetail> {
        if (encoded.isBlank()) return emptyMap()
        if (encoded.trimStart().startsWith("[")) {
            return runCatching {
                JsonParser.parseString(encoded).asJsonArray.mapNotNull { row ->
                    runCatching {
                        val detail = gson.fromJson(row, SuggestedTagDetail::class.java)
                        detail?.copy(
                            action = detail.action?.takeIf { it in setOf("add", "replace", "coexist") } ?: "add",
                            replacesTagIds = detail.replacesTagIds.orEmpty(),
                            changeReason = detail.changeReason.orEmpty(),
                            coexistingTagIds = detail.coexistingTagIds.orEmpty(),
                        )
                    }.getOrNull()
                }.associateBy { it.tagId }
            }.getOrDefault(emptyMap())
        }
        return encoded.split("|").mapNotNull { entry ->
            val parts = entry.split(":")
            val tagId = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val confidence = parts.getOrNull(1)?.toFloatOrNull()
            val evidencePath = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
            tagId to SuggestedTagDetail(tagId, confidence, evidencePath)
        }.toMap()
    }
}
