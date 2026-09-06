package com.blitz.downloader.llm

/**
 * Provider 无关的领域模型——[LlmProvider] 的输入/输出全部用这套类型，各 Provider 内部
 * 自行转换成对应厂商的请求/响应格式（见 `llm/providers/GeminiProvider.kt`）。
 */

/** 一张待上传的图片：JPEG 字节 + 是否含清晰人脸（供 prompt 提示模型"这张证据是否包含人脸"）。 */
class ImagePart(val jpegBytes: ByteArray, val hasFace: Boolean)

/** 标签词表中的一项，`parentTagId` 非空时表示存在上级（依赖 `tag-hierarchy`，可选）。 */
data class TagWordEntry(
    val tagId: Long,
    val name: String,
    val description: String = "",
    val parentTagId: Long? = null,
)

/** 作者历史标签先验的单项，见 [com.blitz.downloader.data.AuthorTagRatio]。 */
data class AuthorTagContext(val tagName: String, val count: Int, val ratio: Float?)

/** 作者历史标签先验，见 [com.blitz.downloader.data.AuthorProfile]。 */
data class AuthorProfileContext(val sampleCount: Int, val topTags: List<AuthorTagContext>)

/** 一条 few-shot 样例：这条视频的文案 + 用户最终确认的标签名集合。 */
data class FewShotExample(val desc: String, val confirmedTagNames: List<String>)

/** 一次 AI 建议标签请求的完整输入。 */
data class TagSuggestionRequest(
    val coverImage: ImagePart,
    val keyFrames: List<ImagePart>,
    val desc: String,
    val tagVocabulary: List<TagWordEntry>,
    val authorProfile: AuthorProfileContext? = null,
    val preferenceProfileText: String? = null,
    val fewShotExamples: List<FewShotExample> = emptyList(),
)

/** 单个视觉维度的可见证据；`visibility` 取值 "high"/"medium"/"low"/"none"。 */
data class VisualDimension(
    val visibility: String,
    val observableTraits: List<String> = emptyList(),
    val evidenceFrames: List<Int> = emptyList(),
)

/**
 * 结构化视觉证据（VisualFeatureProfile），替代一句话 Visual Summary——某维度缺乏可见证据时
 * 该字段为 `null` 或 `visibility = "none"`，依赖该维度的标签建议应相应降低置信度或不予建议。
 */
data class VisualFeatureProfile(
    val face: VisualDimension? = null,
    val expression: VisualDimension? = null,
    val bodyAndStyling: VisualDimension? = null,
    val clothing: VisualDimension? = null,
    val action: VisualDimension? = null,
)

/** 一个候选标签：仅认标签 id（不接受模型自造名称），[evidenceFrames] 指向 [TagSuggestionRequest] 里第几张图。 */
data class TagCandidate(
    val tagId: Long,
    val confidence: Float,
    val evidenceFrames: List<Int> = emptyList(),
)

/** 一次 AI 建议标签请求的完整输出。 */
data class TagSuggestionResponse(
    val visualFeatureProfile: VisualFeatureProfile,
    val candidates: List<TagCandidate>,
)

/** 生成个人偏好摘要的输入：最近一批已确认的反馈样例（纯文本，不带图片）。 */
data class PreferenceSummaryRequest(val recentSamples: List<FewShotExample>)
