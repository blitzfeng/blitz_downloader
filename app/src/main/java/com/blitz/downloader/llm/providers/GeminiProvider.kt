package com.blitz.downloader.llm.providers

import android.content.Context
import android.util.Base64
import android.util.Log
import com.blitz.downloader.config.AppSettings
import com.blitz.downloader.llm.AuthorProfileContext
import com.blitz.downloader.llm.FewShotExample
import com.blitz.downloader.llm.ImagePart
import com.blitz.downloader.llm.LlmProvider
import com.blitz.downloader.llm.PreferenceSummaryRequest
import com.blitz.downloader.llm.TagCandidate
import com.blitz.downloader.llm.TagSuggestionRequest
import com.blitz.downloader.llm.TagSuggestionResponse
import com.blitz.downloader.llm.TagWordEntry
import com.blitz.downloader.llm.VisualDimension
import com.blitz.downloader.llm.VisualFeatureProfile
import com.google.gson.Gson
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response as OkResponse
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * [LlmProvider] 的 Gemini 实现，对接 Google Generative Language API。鉴权走 `x-goog-api-key`
 * 请求头（不用 URL query 参数，避免 Key 出现在日志/代理记录的 URL 里）。结构化输出用
 * `responseSchema` 强制模型按 [TAG_SUGGESTION_SCHEMA] 返回 JSON，对应评审文档"必须使用严格
 * JSON/Structured Output 思路"的要求，不需要"提示词里要求返回 JSON 再自己兜底解析"这种脆弱方案。
 *
 * API Key 每次调用时从 [AppSettings] 现读（不缓存，符合项目既有约定），未配置时直接返回失败，
 * 不发起网络请求。
 */
class GeminiProvider(private val context: Context) : LlmProvider {

    override val providerId: String = "gemini"
    override val modelId: String = MODEL

    private val gson = Gson()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // 多图 + 结构化输出，比抖音接口耗时更长
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(LoggingInterceptor())
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val service = retrofit.create(GeminiApiService::class.java)

    override suspend fun generateTagSuggestion(request: TagSuggestionRequest): Result<TagSuggestionResponse> {
        val apiKey = AppSettings.getGeminiApiKey(context)
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("Gemini API Key 未配置"))

        val parts = buildList {
            add(GeminiPart(text = buildTagSuggestionPrompt(request)))
            add(GeminiPart(text = "图片 0（封面）"))
            add(toGeminiPart(request.coverImage))
            request.keyFrames.forEachIndexed { i, frame ->
                val faceNote = if (frame.hasFace) {
                    "（本地初筛检测到人脸）"
                } else {
                    "（本地初筛未检测到人脸，不代表画面中一定没有人物，仅供参考，请以你自己观察到的为准）"
                }
                add(GeminiPart(text = "图片 ${i + 1} $faceNote"))
                add(toGeminiPart(frame))
            }
        }
        val body = GeminiGenerateContentRequest(
            contents = listOf(GeminiContent(role = "user", parts = parts)),
            generationConfig = GeminiGenerationConfig(
                responseMimeType = "application/json",
                responseSchema = TAG_SUGGESTION_SCHEMA,
            ),
        )
        return runCatching {
            val response = service.generateContent(MODEL, apiKey, body)
            val text = extractText(response)
            val payload = gson.fromJson(text, GeminiTagSuggestionPayload::class.java)
                ?: error("Gemini 结构化输出解析结果为空")
            payload.toDomain()
        }
    }

    override suspend fun summarizePreference(request: PreferenceSummaryRequest): Result<String> {
        val apiKey = AppSettings.getGeminiApiKey(context)
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("Gemini API Key 未配置"))

        val body = GeminiGenerateContentRequest(
            contents = listOf(
                GeminiContent(role = "user", parts = listOf(GeminiPart(text = buildPreferenceSummaryPrompt(request)))),
            ),
            // 纯文本摘要不需要结构化输出，直接吃自然语言回复
            generationConfig = null,
        )
        return runCatching {
            val response = service.generateContent(MODEL, apiKey, body)
            extractText(response).trim()
        }
    }

    /** 从 Gemini 响应里取出第一个 candidate 的文本；非 2xx 或结构缺失时抛错，交给外层 `runCatching`。 */
    private fun extractText(response: retrofit2.Response<GeminiGenerateContentResponse>): String {
        if (!response.isSuccessful) {
            val errorSnippet = response.errorBody()?.string().orEmpty().take(300)
            error("Gemini HTTP ${response.code()}: $errorSnippet")
        }
        return response.body()?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: error("Gemini 返回结果为空")
    }

    private fun toGeminiPart(image: ImagePart): GeminiPart = GeminiPart(
        inlineData = GeminiInlineData(
            mimeType = "image/jpeg",
            data = Base64.encodeToString(image.jpegBytes, Base64.NO_WRAP),
        ),
    )

    private fun buildTagSuggestionPrompt(request: TagSuggestionRequest): String = buildString {
        appendLine("你是一个短视频内容标签助手。请仔细观察后面提供的图片（第一张是视频封面，其余是从视频中抽取的关键帧），")
        appendLine("结合视频文案，完成两件事：")
        appendLine("1. 输出结构化的视觉证据 visualFeatureProfile：按 face/expression/bodyAndStyling/clothing/action 五个维度，")
        appendLine("   分别说明该维度在图片中是否可见（visibility: high/medium/low/none）、观察到的具体特征、以及依据的图片序号")
        appendLine("   （图片序号从 0 开始，0 是封面，之后依次是关键帧）。某个维度在图片里完全看不出来时，visibility 填 \"none\"，")
        appendLine("   不要编造证据。")
        appendLine("2. 从下面给出的标签词表中选择候选标签，仅返回词表中已有的 tagId，不要创造新标签或返回词表之外的名字。")
        appendLine("   每个候选标签给出 confidence（0~1）与它依据的图片序号 evidenceFrames。")
        appendLine("   如果某个标签依赖的视觉维度在图片中缺乏清晰证据（尤其是颜值类标签依赖清晰人脸），必须降低该标签的置信度")
        appendLine("   或直接不返回这个候选，不能仅凭文案或猜测给出高置信度。")
        appendLine()
        if (request.desc.isNotBlank()) {
            appendLine("视频文案：${request.desc}")
            appendLine()
        }
        appendLine("可选标签词表（JSON，字段：tagId/name/description/parentTagId，description 是人工定义的判断标准，")
        appendLine("parentTagId 非空表示存在上级大类，仅供参考不代表必须同时选中）：")
        appendLine(gson.toJson(request.tagVocabulary))
        appendLine()
        request.authorProfile?.let { profile ->
            appendLine("该视频作者的历史标签先验（该作者共有 ${profile.sampleCount} 个已下载视频，以下是其中出现较多的标签及占比，")
            appendLine("**仅作先验参考，不能替代当前图片证据**——历史上常打某标签不代表这条视频也符合，必须以图片证据为准）：")
            appendLine(gson.toJson(profile.topTags))
            appendLine()
        }
        request.preferenceProfileText?.takeIf { it.isNotBlank() }?.let { profileText ->
            appendLine("这是根据用户过往采纳/拒绝建议总结出的个人标签偏好，供参考：")
            appendLine(profileText)
            appendLine()
        }
        if (request.fewShotExamples.isNotEmpty()) {
            appendLine("以下是该用户过往对类似视频的真实标注结果，供参考风格标准（不代表这次视频的答案）：")
            request.fewShotExamples.forEach { example ->
                appendLine("- 文案：${example.desc}；最终标签：${example.confirmedTagNames.joinToString("、")}")
            }
            appendLine()
        }
        appendLine("请严格按照给定的 JSON Schema 返回结果。")
    }

    private fun buildPreferenceSummaryPrompt(request: PreferenceSummaryRequest): String = buildString {
        appendLine("以下是某用户最近对 AI 建议标签的确认记录（视频文案 + 最终确认的标签），请用简洁的中文段落总结出")
        appendLine("这个用户打标签的个人偏好与标准（例如对哪类标签宽松、对哪类标签严格、更看重什么视觉线索），")
        appendLine("直接输出摘要文本，不要输出 JSON 或多余的说明：")
        appendLine()
        request.recentSamples.forEach { sample ->
            appendLine("- 文案：${sample.desc}；标签：${sample.confirmedTagNames.joinToString("、")}")
        }
    }

    private fun GeminiTagSuggestionPayload.toDomain(): TagSuggestionResponse = TagSuggestionResponse(
        visualFeatureProfile = VisualFeatureProfile(
            face = visualFeatureProfile.face.toDomain(),
            expression = visualFeatureProfile.expression.toDomain(),
            bodyAndStyling = visualFeatureProfile.bodyAndStyling.toDomain(),
            clothing = visualFeatureProfile.clothing.toDomain(),
            action = visualFeatureProfile.action.toDomain(),
        ),
        candidates = candidates.map { TagCandidate(it.tagId, it.confidence, it.evidenceFrames) },
    )

    private fun GeminiVisualDimensionPayload.toDomain(): VisualDimension =
        VisualDimension(visibility, observableTraits, evidenceFrames)

    /** 只打印方法/URL/状态码，不打印请求体（含 base64 图片与 Key）与响应体。 */
    private class LoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): OkResponse {
            val request = chain.request()
            Log.d(TAG, "→ ${request.method} ${request.url.encodedPath}")
            val response = chain.proceed(request)
            Log.d(TAG, "← ${response.code} ${request.url.encodedPath}")
            return response
        }
    }

    companion object {
        private const val TAG = "GeminiProvider"

        /**
         * 具体模型版本号留到实现阶段按当时可用的模型直接定，不影响本类的行为契约——
         * 之后要换型号只改这一个常量。选用 flash 档位控制成本。
         */
        private const val MODEL = "gemini-2.5-flash"

        private val DIMENSION_SCHEMA = GeminiSchema(
            type = "OBJECT",
            properties = mapOf(
                "visibility" to GeminiSchema(type = "STRING", enumValues = listOf("high", "medium", "low", "none")),
                "observableTraits" to GeminiSchema(type = "ARRAY", items = GeminiSchema(type = "STRING")),
                "evidenceFrames" to GeminiSchema(type = "ARRAY", items = GeminiSchema(type = "INTEGER")),
            ),
            required = listOf("visibility"),
        )

        private val TAG_SUGGESTION_SCHEMA = GeminiSchema(
            type = "OBJECT",
            properties = mapOf(
                "visualFeatureProfile" to GeminiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "face" to DIMENSION_SCHEMA,
                        "expression" to DIMENSION_SCHEMA,
                        "bodyAndStyling" to DIMENSION_SCHEMA,
                        "clothing" to DIMENSION_SCHEMA,
                        "action" to DIMENSION_SCHEMA,
                    ),
                    required = listOf("face", "expression", "bodyAndStyling", "clothing", "action"),
                ),
                "candidates" to GeminiSchema(
                    type = "ARRAY",
                    items = GeminiSchema(
                        type = "OBJECT",
                        properties = mapOf(
                            "tagId" to GeminiSchema(type = "INTEGER"),
                            "confidence" to GeminiSchema(type = "NUMBER"),
                            "evidenceFrames" to GeminiSchema(type = "ARRAY", items = GeminiSchema(type = "INTEGER")),
                        ),
                        required = listOf("tagId", "confidence"),
                    ),
                ),
            ),
            required = listOf("visualFeatureProfile", "candidates"),
        )
    }
}
