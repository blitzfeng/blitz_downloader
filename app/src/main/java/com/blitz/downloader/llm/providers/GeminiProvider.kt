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
import okio.Buffer
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

    /**
     * [testConnection] 专用的短超时 client：正式建议请求要传多图 + 等结构化输出，超时故意放宽到
     * 60s；连接测试只是一次纯文本探测，用同一套超时会让用户在网络差时空转到 90s 才看到失败。
     * 复用同一个 [okHttpClient] 的连接池没有意义（不同 Retrofit client 各自独立连接池），
     * 索性单独建一个更"没耐心"的 client，让排查问题时更快拿到结果。
     */
    private val testService: GeminiApiService by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(LoggingInterceptor())
            .build()
        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GeminiApiService::class.java)
    }

    override suspend fun generateTagSuggestion(request: TagSuggestionRequest): Result<TagSuggestionResponse> {
        val apiKey = AppSettings.getGeminiApiKey(context)
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("Gemini API Key 未配置"))

        val parts = buildList {
            add(GeminiPart(text = buildTagSuggestionPrompt(request)))
            add(GeminiPart(text = "图片 0（封面）"))
            // 封面是唯一保证会被看到的图，也是"第一印象"，恒定用 MEDIUM，不参与按人脸降档
            add(toGeminiPart(request.coverImage, resolution = MEDIA_RESOLUTION_MEDIUM))
            request.keyFrames.forEachIndexed { i, frame ->
                val faceNote = if (frame.hasFace) {
                    "（本地初筛检测到人脸）"
                } else {
                    "（本地初筛未检测到人脸，不代表画面中一定没有人物，仅供参考，请以你自己观察到的为准）"
                }
                add(GeminiPart(text = "图片 ${i + 1} $faceNote"))
                // 含人脸的帧留给 face/expression 判断，需要更清晰的细节，用 MEDIUM；
                // 不含人脸的帧只用来佐证 bodyAndStyling/clothing/action，细节要求更低，降到 LOW 省 token。
                val resolution = if (frame.hasFace) MEDIA_RESOLUTION_MEDIUM else MEDIA_RESOLUTION_LOW
                add(toGeminiPart(frame, resolution = resolution))
            }
        }
        val body = GeminiGenerateContentRequest(
            contents = listOf(GeminiContent(role = "user", parts = parts)),
            generationConfig = GeminiGenerationConfig(
                responseMimeType = "application/json",
                responseSchema = TAG_SUGGESTION_SCHEMA,
                thinkingConfig = GeminiThinkingConfig(thinkingLevel = THINKING_LEVEL),
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
            // 纯文本摘要不需要结构化输出（responseMimeType/responseSchema 留空），仍带 thinkingConfig
            generationConfig = GeminiGenerationConfig(thinkingConfig = GeminiThinkingConfig(thinkingLevel = THINKING_LEVEL)),
        )
        return runCatching {
            val response = service.generateContent(MODEL, apiKey, body)
            extractText(response).trim()
        }
    }

    override suspend fun testConnection(): Result<String> {
        val apiKey = AppSettings.getGeminiApiKey(context)
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("Gemini API Key 未配置"))

        val body = GeminiGenerateContentRequest(
            contents = listOf(
                GeminiContent(role = "user", parts = listOf(GeminiPart(text = "请只回复\"OK\"三个字符，用于测试连接。"))),
            ),
            // 不需要结构化输出，但带上 thinkingConfig 让连接测试也验证这个字段没被网关/模型拒绝
            generationConfig = GeminiGenerationConfig(thinkingConfig = GeminiThinkingConfig(thinkingLevel = THINKING_LEVEL)),
        )
        return runCatching {
            val response = testService.generateContent(MODEL, apiKey, body)
            extractText(response)
            "连接成功（模型 $MODEL）"
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

    private fun toGeminiPart(image: ImagePart, resolution: String): GeminiPart = GeminiPart(
        inlineData = GeminiInlineData(
            mimeType = "image/jpeg",
            data = Base64.encodeToString(image.jpegBytes, Base64.NO_WRAP),
        ),
        mediaResolution = GeminiMediaResolutionConfig(level = resolution),
    )

    private fun buildTagSuggestionPrompt(request: TagSuggestionRequest): String = buildString {
        appendLine("你是一个短视频内容标签助手。请仔细观察后面提供的图片（第一张是视频封面，其余是从视频中抽取的关键帧），")
        appendLine("结合视频文案，完成两件事：")
        appendLine("1. 输出结构化的视觉证据 visualFeatureProfile：按 face/expression/bodyAndStyling/clothing/action 五个维度，")
        appendLine("   分别说明该维度在图片中是否可见（visibility: high/medium/low/none）、观察到的具体特征、以及依据的图片序号")
        appendLine("   （图片序号从 0 开始，0 是封面，之后依次是关键帧）。某个维度在图片里完全看不出来时，visibility 填 \"none\"，")
        appendLine("   不要编造证据。")
        appendLine("2. 从下面给出的标签词表中选择候选标签，必须返回词表中已有标签的 tagName（标签名称，如 \"颜值\"）与对应的 tagId，不要创造新标签或返回词表之外的名字。")
        appendLine("   每个候选标签给出 confidence（0~1）与支撑该标签的图片序号 evidenceFrames。")
        appendLine("   注意：evidenceFrames 只需填写观察到相关特征的少数关键图片序号，不要无脑填入全部图片序号。")
        appendLine("   如果某个标签依赖的视觉维度在图片中缺乏清晰证据（尤其是颜值类标签依赖清晰人脸），必须降低该标签的置信度")
        appendLine("   或直接不返回这个候选，不能仅凭文案或猜测给出高置信度。")
        appendLine()
        if (request.desc.isNotBlank()) {
            appendLine("视频文案：${request.desc}")
            appendLine()
        }
        appendLine("可选标签词表（JSON，字段：name 是标签名称 tagName、tagId 是标签标识、description 是人工定义的判断标准，")
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
        candidates = candidates.map {
            TagCandidate(
                tagId = it.tagId,
                confidence = it.confidence,
                evidenceFrames = it.evidenceFrames,
                tagName = it.tagName,
            )
        },
    )

    private fun GeminiVisualDimensionPayload.toDomain(): VisualDimension =
        VisualDimension(visibility, observableTraits, evidenceFrames)

    /**
     * 完整打印请求体与响应体，用于排查「HTTP 200 但结构化输出解析失败」这类问题。
     * **Key 不在这里泄露**：鉴权走 `x-goog-api-key` 请求头（见类头 KDoc），这里从不打印请求头，
     * 只打印 body。**图片 base64 用占位符代替**（[redactImageData]）——那串编码人看不懂，
     * 还占大部分篇幅，之前"完整"打印反而把真正有用的 prompt/JSON 结构淹没在几百 KB 乱码里；
     * 占位符带上原始字节数，方便和 `resolution` 调整前后对比传输体积。除图片外的一切
     * （prompt 文本、tagVocabulary、`generationConfig`、响应 JSON 含 `usageMetadata`）仍然完整打印，
     * 不是打了折扣的"完整"。剩余内容仍可能有几十 KB（多个标签/关键帧描述），Logcat 单行 ~4000 字符
     * 会截断，所以按 [CHUNK_SIZE] 分行输出，量级问题请去 `adb logcat` 里搜 TAG。
     */
    private class LoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): OkResponse {
            val request = chain.request()
            Log.d(TAG, "→ ${request.method} ${request.url.encodedPath}")
            val requestBody = request.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }
            logChunked("→ body", redactImageData(requestBody ?: "(empty)"))

            val response = chain.proceed(request)
            Log.d(TAG, "← ${response.code} ${request.url.encodedPath}")
            val headersText = response.headers.toString().trimEnd()
            if (headersText.isNotEmpty()) {
                Log.d(TAG, "← headers:\n$headersText")
            }
            // peekBody 只读一份拷贝，不消费原始响应体，Retrofit 之后仍能正常解析
            val responseBody = response.peekBody(Long.MAX_VALUE).string()
            logChunked("← body", redactImageData(responseBody))
            return response
        }

        /**
         * 把 `"data":"<base64>"` 里的 base64 换成 `[图片内容，约 NKB]`。
         *
         * **改成手写扫描，不再用正则**：最初用一个大正则一次性匹配整段 base64，真机日志里
         * 出现了大段 base64 漏网（原因没有确证，但单个图片的 base64 常有几万到十几万字符，
         * 不排除是 `{200,}` 无上界贪婪匹配在超长输入上的正则引擎行为问题）。base64 字母表
         * 本身不含 `"`，所以"找到 `"data":"` 之后，下一个 `"` 一定是闭合引号"这个结论是
         * 严格成立的，不依赖字符集匹配、不存在潜在的正则性能坑，比之前的写法更可靠。
         */
        private fun redactImageData(json: String): String {
            val marker = "\"data\":\""
            val sb = StringBuilder(json.length)
            var cursor = 0
            while (true) {
                val markerStart = json.indexOf(marker, cursor)
                if (markerStart < 0) {
                    sb.append(json, cursor, json.length)
                    break
                }
                val valueStart = markerStart + marker.length
                val valueEnd = json.indexOf('"', valueStart)
                if (valueEnd < 0) {
                    // 找不到闭合引号（理论不应发生，防御性兜底）：原样保留剩余内容，不冒险瞎替换
                    sb.append(json, cursor, json.length)
                    break
                }
                sb.append(json, cursor, valueStart)
                sb.append("[图片内容，约 ${(valueEnd - valueStart) / 1024}KB]")
                cursor = valueEnd
            }
            return sb.toString()
        }

        private fun logChunked(label: String, content: String) {
            var index = 0
            while (index < content.length) {
                val end = minOf(index + CHUNK_SIZE, content.length)
                Log.d(TAG, "$label [$index-$end): ${content.substring(index, end)}")
                index = end
            }
            if (content.isEmpty()) Log.d(TAG, "$label: (empty)")
        }

        companion object {
            private const val CHUNK_SIZE = 3000
        }
    }

    companion object {
        private const val TAG = "GeminiProvider"

        /**
         * 具体模型版本号留到实现阶段按当时可用的模型直接定，不影响本类的行为契约——
         * 之后要换型号只改这一个常量。选用 flash 档位控制成本。
         */
        private const val MODEL = "gemini-3.8-flash"

        /**
         * Gemini 3.x 系列新增的 `thinkingLevel`（`LOW`/`MEDIUM`/`HIGH`，省略时后端按 `MEDIUM` 处理）。
         * **枚举值大写**——proto3 JSON 映射惯例是"字段名 lowerCamelCase、枚举值原样
         * SCREAMING_SNAKE_CASE"；最初实现误写成小写 `"low"`，值不匹配时后端大概率静默按未知值
         * 处理退回默认档位（不是报错，所以之前的真机验证不会暴露这个问题）。**这个字段本身
         * 真机验证过是被接受的**——第一次因为 `resolution` 字段名写错触发的 400 响应把请求里
         * 所有未知字段都枚举了一遍（同一响应对 11 个不同 part 分别报错），却没有连带报
         * `thinkingConfig`/`thinkingLevel`，说明这两个字段名是对的，不用怀疑。
         *
         * 三处调用（建议标签/偏好摘要/连接测试）统一取这一个值，没有按用途拆开——先用 `LOW`
         * 压成本延迟，与"选用 flash 档位控制成本"是同一个取向；建议标签这条路径本身有
         * `responseSchema` 强约束结构、且已经把视觉证据拆成五个维度分别要求出处，对深度推理的
         * 依赖没有自由问答那么重。**如果真机走查发现建议质量明显下降，把这个值调到 `MEDIUM`
         * 就够了**，不需要改别的地方。
         */
        private const val THINKING_LEVEL = "LOW"

        /**
         * Gemini 3.x 新增的 per-part 混合分辨率（[GeminiPart.mediaResolution]），按图片重要性分别
         * 定档省 token（用户提供的参考单价：MEDIUM ≈560 token/图，LOW ≈280 token/图）。踩过两次坑，
         * 都是用户拿真实请求/更新过的文档校正回来的：
         * 1. **字段名**一度写错成 `resolution`（与 `inlineData` 同级的扁平字段），真机请求被 Gemini
         *    判 400（`Unknown name "resolution" at 'contents[0].parts[N]': Cannot find field.`，
         *    11 个 part 各报一次），改成 `mediaResolution` 才对。
         * 2. **值的形状与大小写**一度写成扁平小写字符串 `"medium"`/`"low"`（依据的是过期文档），
         *    实际是嵌套对象 `{"level": "MEDIA_RESOLUTION_MEDIUM"}`，`level` 取值大写
         *    `SCREAMING_SNAKE_CASE`——见 [GeminiMediaResolutionConfig]。
         *
         * 只用 MEDIUM/LOW 这两档——没有再引入 HIGH，当前证据链（封面 + 关键帧）没有需要它的场景。
         * **这仍然是从二手示例拼出来的，没有逐字核对官方一手 changelog**，务必在真机上点一次
         * 「AI 建议」，对照 `GeminiProvider.LoggingInterceptor` 打的完整请求/响应日志确认这次没有
         * 400、且响应 `usageMetadata.promptTokenCount` 相比不带这个字段时确实下降了。
         */
        private const val MEDIA_RESOLUTION_MEDIUM = "MEDIA_RESOLUTION_MEDIUM"
        private const val MEDIA_RESOLUTION_LOW = "MEDIA_RESOLUTION_LOW"

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
                            "tagName" to GeminiSchema(type = "STRING"),
                            "tagId" to GeminiSchema(type = "INTEGER"),
                            "confidence" to GeminiSchema(type = "NUMBER"),
                            "evidenceFrames" to GeminiSchema(type = "ARRAY", items = GeminiSchema(type = "INTEGER")),
                        ),
                        required = listOf("tagName", "confidence"),
                    ),
                ),
            ),
            required = listOf("visualFeatureProfile", "candidates"),
        )
    }
}
