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
    /**
     * 该图片的 token 预算档位，与 `inlineData` 同级。**字段名是 `mediaResolution`，不是
     * `resolution`**——第一次实现用错字段名（`resolution`），真机请求被 Gemini 判为 400 未知字段
     * （`Unknown name "resolution" ... Cannot find field.`），见 `GeminiProvider.THINKING_LEVEL`
     * companion object 里的踩坑记录。**值是嵌套对象 `{"level": "MEDIA_RESOLUTION_MEDIUM"}`，
     * 不是扁平字符串**——中途还错误地把 `level` 值改成过小写 `"medium"`（依据的是过期文档），
     * 已按用户核实的最新文档改回大写 `SCREAMING_SNAKE_CASE`。省略整个字段时按模型默认档位处理，
     * 只对带 [inlineData] 的图片 part 有意义，纯文本 part 留空。
     */
    val mediaResolution: GeminiMediaResolutionConfig? = null,
)

/** [GeminiPart.mediaResolution] 的嵌套值，`level` 取 `MEDIA_RESOLUTION_LOW`/`_MEDIUM`/`_HIGH`。 */
data class GeminiMediaResolutionConfig(
    val level: String,
)

data class GeminiInlineData(
    val mimeType: String,
    /** Base64（`Base64.NO_WRAP`）编码的原始字节。 */
    val data: String,
)

data class GeminiGenerationConfig(
    val responseMimeType: String? = null,
    val responseSchema: GeminiSchema? = null,
    val thinkingConfig: GeminiThinkingConfig? = null,
)

/**
 * Gemini 3.x 系列新增：用 `thinkingLevel`（`"low"`/`"medium"`/`"high"`）替代旧版 `thinking_budget`，
 * 两者不能同时出现在同一请求里。省略整个 `thinkingConfig` 时后端按 `"medium"` 处理，
 * 行为与本类之前（gemini-2.5-flash 时代）不设该字段完全一致——这不是破坏性变更，只是新增旋钮。
 */
data class GeminiThinkingConfig(
    val thinkingLevel: String,
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
