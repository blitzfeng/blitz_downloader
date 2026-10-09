package com.blitz.downloader.llm.providers

import com.blitz.downloader.llm.TagCandidate
import com.blitz.downloader.llm.TagSuggestionResponse
import com.blitz.downloader.llm.VisualDimension
import com.blitz.downloader.llm.VisualFeatureProfile

/** 在外部 JSON 边界消化缺省值和显式 null，避免 Gson 绕过 Kotlin 非空约束。 */
internal fun GeminiTagSuggestionPayload.toDomain(): TagSuggestionResponse {
    val profile = requireNotNull(visualFeatureProfile) { "Gemini 结构化输出缺少 visualFeatureProfile" }
    val tags = requireNotNull(candidates) { "Gemini 结构化输出缺少 candidates" }
    return TagSuggestionResponse(
        visualFeatureProfile = VisualFeatureProfile(
            face = profile.face.toDomain(),
            expression = profile.expression.toDomain(),
            bodyAndStyling = profile.bodyAndStyling.toDomain(),
            clothing = profile.clothing.toDomain(),
            action = profile.action.toDomain(),
        ),
        candidates = tags.filterNotNull().map {
            TagCandidate(
                tagId = it.tagId,
                confidence = it.confidence,
                evidenceFrames = it.evidenceFrames.orEmpty().filterNotNull(),
                tagName = it.tagName.orEmpty(),
            )
        },
    )
}

private fun GeminiVisualDimensionPayload?.toDomain(): VisualDimension {
    val visibility = this?.visibility?.takeIf { it in setOf("high", "medium", "low", "none") } ?: "none"
    // 不可见维度不保留矛盾的特征或证据，尤其不能凭缺失的人脸推断颜值。
    if (visibility == "none") return VisualDimension(visibility = "none")
    return VisualDimension(
        visibility = visibility,
        observableTraits = this?.observableTraits.orEmpty().filterNotNull(),
        evidenceFrames = this?.evidenceFrames.orEmpty().filterNotNull(),
    )
}
