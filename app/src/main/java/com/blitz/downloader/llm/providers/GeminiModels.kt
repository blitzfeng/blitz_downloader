package com.blitz.downloader.llm.providers

import com.google.gson.annotations.SerializedName

/**
 * Google Generative Language API（Gemini）的 wire-format Gson 模型，只在 [GeminiProvider]
 * 内部使用——外部一律通过 `llm/LlmModels.kt` 的 provider 无关领域模型交互。
 */

data class GeminiGenerateContentRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null,
)

data class GeminiContent(
    val role: String,
    val parts: List<GeminiPart>,
)

data class GeminiPart(
    val text: String? = null,
    val inlineData: GeminiInlineData? = null,
)

data class GeminiInlineData(
    val mimeType: String,
    /** Base64（`Base64.NO_WRAP`）编码的原始字节。 */
    val data: String,
)

data class GeminiGenerationConfig(
    val responseMimeType: String,
    val responseSchema: GeminiSchema? = null,
)

/**
 * Gemini `responseSchema` 用的 OpenAPI 子集 Schema 对象。`type` 取值大写：
 * `OBJECT`/`STRING`/`ARRAY`/`NUMBER`/`INTEGER`/`BOOLEAN`。
 * `enum` 是 Kotlin 关键字，字段名改用 [enumValues] + `@SerializedName("enum")`。
 */
data class GeminiSchema(
    val type: String,
    val properties: Map<String, GeminiSchema>? = null,
    val items: GeminiSchema? = null,
    @SerializedName("enum") val enumValues: List<String>? = null,
    val required: List<String>? = null,
    val description: String? = null,
)

data class GeminiGenerateContentResponse(
    val candidates: List<GeminiResponseCandidate>? = null,
)

data class GeminiResponseCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null,
)

/**
 * 严格 JSON 输出的顶层负载，字段名对齐 `TAG_SUGGESTION_SCHEMA` 的 properties——
 * 解析 [GeminiResponseCandidate.content] 里 `parts[0].text`（这段文本本身就是一份 JSON）得到。
 */
data class GeminiTagSuggestionPayload(
    val visualFeatureProfile: GeminiVisualFeatureProfilePayload,
    val candidates: List<GeminiTagCandidatePayload> = emptyList(),
)

data class GeminiVisualFeatureProfilePayload(
    val face: GeminiVisualDimensionPayload,
    val expression: GeminiVisualDimensionPayload,
    val bodyAndStyling: GeminiVisualDimensionPayload,
    val clothing: GeminiVisualDimensionPayload,
    val action: GeminiVisualDimensionPayload,
)

data class GeminiVisualDimensionPayload(
    val visibility: String,
    val observableTraits: List<String> = emptyList(),
    val evidenceFrames: List<Int> = emptyList(),
)

data class GeminiTagCandidatePayload(
    val tagId: Long,
    val confidence: Float,
    val evidenceFrames: List<Int> = emptyList(),
)
