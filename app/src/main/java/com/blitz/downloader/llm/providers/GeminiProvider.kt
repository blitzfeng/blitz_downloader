package com.blitz.downloader.llm.providers

import android.util.Base64
import android.util.Log
import com.blitz.downloader.config.AiConnectionConfig
import com.blitz.downloader.config.AiService
import kotlinx.coroutines.CancellationException
import com.blitz.downloader.llm.AuthorProfileContext
import com.blitz.downloader.llm.FewShotExample
import com.blitz.downloader.llm.ImagePart
import com.blitz.downloader.llm.LlmProvider
import com.blitz.downloader.llm.LlmNetworkTrace
import com.blitz.downloader.llm.LlmNetworkEvents
import com.blitz.downloader.llm.PreferenceSummaryRequest
import com.blitz.downloader.llm.TagSuggestionRequest
import com.blitz.downloader.llm.TagSuggestionResponse
import com.blitz.downloader.llm.TagSuggestionRequestBuilder
import com.blitz.downloader.llm.TagWordEntry
import com.google.gson.Gson
import java.util.concurrent.TimeUnit
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response as OkResponse
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * [LlmProvider] 的 Gemini 实现，复用 Gemini Developer API 与 Vertex AI 的生成协议。鉴权走 `x-goog-api-key`
 * 请求头（不用 URL query 参数，避免 Key 出现在日志/代理记录的 URL 里）。结构化输出用
 * `responseSchema` 强制模型按 [TAG_SUGGESTION_SCHEMA] 返回 JSON，对应评审文档"必须使用严格
 * JSON/Structured Output 思路"的要求，不需要"提示词里要求返回 JSON 再自己兜底解析"这种脆弱方案。
 *
 * 每次操作使用 [AiConnectionConfig] 快照，客户端跨操作复用；未配置时直接返回失败，
 * 不发起网络请求。
 */
class GeminiProvider(
    private val config: AiConnectionConfig,
    private val service: GeminiApiService = sharedService,
    private val testService: GeminiApiService = sharedTestService,
) : LlmProvider {
    override val providerId: String = config.service.id
    override val modelId: String = config.model
    private val gson = Gson()

    private suspend fun <T> requestResult(block: suspend () -> T): Result<T> {
        config.validationError?.let { return Result.failure(IllegalStateException(it)) }
        return try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 服务端错误也可能回显凭据，保留诊断信息但删除当前密钥。
            val message = (error.message ?: error.javaClass.simpleName).replace(config.apiKey, "[REDACTED]")
            if (error is com.blitz.downloader.llm.LlmResponseException) {
                val diagnostics = error.diagnostics.copy(
                    rawResponseBody = error.diagnostics.rawResponseBody.replace(config.apiKey, "[REDACTED]"),
                    tokenUsage = error.diagnostics.tokenUsage?.replace(config.apiKey, "[REDACTED]"),
                )
                Result.failure(com.blitz.downloader.llm.LlmResponseException(
                    "${config.service.displayName}：$message", diagnostics, retryable = error.retryable,
                ))
            } else if (error is IOException) {
                Result.failure(IOException("${config.service.displayName}：$message"))
            } else {
                Result.failure(IllegalStateException("${config.service.displayName}：$message"))
            }
        }
    }

    private fun requestBody(body: GeminiGenerateContentRequest): GeminiGenerateContentRequest =
        if (config.service == AiService.VERTEX_AI) body.copy(
            safetySettings = body.safetySettings?.filterNot { it.category == "HARM_CATEGORY_CIVIC_INTEGRITY" },
        ) else body

    override suspend fun generateTagSuggestion(request: TagSuggestionRequest): Result<TagSuggestionResponse> {
        config.validationError?.let { return Result.failure(IllegalStateException(it)) }

        val parts = buildList {
            add(GeminiPart(text = buildTagSuggestionPrompt(request)))
            if (request.multimodalEvidenceSamples.isNotEmpty()) {
                add(GeminiPart(text = "【同作者历史审核参考样例（包含用户曾采纳与拒绝的标签及对应依据图，仅作风格参考，不要据此给当前视频打标）】："))
                request.multimodalEvidenceSamples.forEachIndexed { idx, sample ->
                    val tagInfo = buildString {
                        append("历史参考案例 ${idx + 1}：")
                        if (sample.desc.isNotBlank()) append("文案「${sample.desc}」；")
                        if (sample.acceptedTagNames.isNotEmpty()) append("用户曾采纳标签：${sample.acceptedTagNames.joinToString("、")}；")
                        if (sample.rejectedTagNames.isNotEmpty()) append("用户曾拒绝标签：${sample.rejectedTagNames.joinToString("、")}；")
                    }
                    add(GeminiPart(text = tagInfo))
                    sample.evidenceImage?.let { img ->
                        // 必须强制使用 MEDIA_RESOLUTION_LOW，绝不使用 medium！
                        add(toGeminiPart(img, resolution = MEDIA_RESOLUTION_LOW))
                    }
                }
                add(GeminiPart(text = "【以下是当前需要分析的新视频，候选标签的 evidenceFrames 序号仅针对以下待分析图片】："))
            }
            add(GeminiPart(text = "当前待分析图片 0（封面）"))
            // 封面是唯一保证会被看到的图，也是"第一印象"，恒定用 MEDIUM，不参与按人脸降档
            add(toGeminiPart(request.coverImage, resolution = MEDIA_RESOLUTION_MEDIUM))
            request.keyFrames.forEachIndexed { i, frame ->
                val faceNote = if (frame.hasFace) {
                    "（本地初筛检测到人脸）"
                } else {
                    "（本地初筛未检测到人脸，不代表画面中一定没有人物，仅供参考，请以你自己观察到的为准）"
                }
                add(GeminiPart(text = "当前待分析图片 ${i + 1} $faceNote"))
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
        return requestResult {
            val trace = request.onNetworkUpdate?.let { LlmNetworkTrace(config.service.displayName, modelId, it) }
            val response = service.generateContent(config.generateContentUrl, config.apiKey, requestBody(body), trace)
            val parsed = GeminiResponseParser.parse(response)
            parsed.decode { text ->
                val payload = gson.fromJson(text, GeminiTagSuggestionPayload::class.java)
                    ?: error("Gemini 结构化输出解析结果为空")
                payload.toDomain().copy(diagnostics = parsed.diagnostics)
            }
        }
    }

    override suspend fun summarizePreference(request: PreferenceSummaryRequest): Result<String> {
        config.validationError?.let { return Result.failure(IllegalStateException(it)) }

        val body = GeminiGenerateContentRequest(
            contents = listOf(
                GeminiContent(role = "user", parts = listOf(GeminiPart(text = buildPreferenceSummaryPrompt(request)))),
            ),
            // 纯文本摘要不需要结构化输出（responseMimeType/responseSchema 留空），仍带 thinkingConfig
            generationConfig = GeminiGenerationConfig(thinkingConfig = GeminiThinkingConfig(thinkingLevel = THINKING_LEVEL)),
        )
        return requestResult {
            val response = service.generateContent(config.generateContentUrl, config.apiKey, requestBody(body))
            extractText(response).trim()
        }
    }

    override suspend fun testConnection(): Result<String> {
        config.validationError?.let { return Result.failure(IllegalStateException(it)) }

        val body = GeminiGenerateContentRequest(
            contents = listOf(
                GeminiContent(role = "user", parts = listOf(GeminiPart(text = "请只回复\"OK\"三个字符，用于测试连接。"))),
            ),
            // 不需要结构化输出，但带上 thinkingConfig 让连接测试也验证这个字段没被网关/模型拒绝
            generationConfig = GeminiGenerationConfig(thinkingConfig = GeminiThinkingConfig(thinkingLevel = THINKING_LEVEL)),
        )
        return requestResult {
            val response = testService.generateContent(config.generateContentUrl, config.apiKey, requestBody(body))
            extractText(response)
            "连接成功（${config.service.displayName} · ${config.model}）"
        }
    }

    private fun extractText(response: retrofit2.Response<okhttp3.ResponseBody>): String =
        GeminiResponseParser.parse(response).text

    private fun toGeminiPart(image: ImagePart, resolution: String): GeminiPart = GeminiPart(
        inlineData = GeminiInlineData(
            mimeType = "image/jpeg",
            data = Base64.encodeToString(image.jpegBytes, Base64.NO_WRAP),
        ),
        mediaResolution = GeminiMediaResolutionConfig(level = resolution),
    )

    private fun buildTagSuggestionPrompt(request: TagSuggestionRequest): String =
        TagSuggestionRequestBuilder.buildTagSuggestionPrompt(request)

    private fun buildPreferenceSummaryPrompt(request: PreferenceSummaryRequest): String = buildString {
        appendLine("以下是某用户最近对 AI 建议标签的确认记录（视频文案 + 最终确认的标签），请用简洁的中文段落总结出")
        appendLine("这个用户打标签的个人偏好与标准（例如对哪类标签宽松、对哪类标签严格、更看重什么视觉线索），")
        appendLine("直接输出摘要文本，不要输出 JSON 或多余的说明：")
        appendLine()
        request.recentSamples.forEach { sample ->
            appendLine("- 文案：${sample.desc}；标签：${sample.confirmedTagNames.joinToString("、")}")
        }
    }

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
            val key = request.header("x-goog-api-key").orEmpty()
            fun redact(value: String): String = if (key.isEmpty()) value else value.replace(key, "[REDACTED]")
            Log.d(TAG, "→ ${request.method} ${request.url.encodedPath}")
            val requestBody = request.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }
            logChunked("→ body", redact(redactImageData(requestBody ?: "(empty)")))

            val response = chain.proceed(request)
            Log.d(TAG, "← ${response.code} ${request.url.encodedPath}")
            val headersText = redact(response.headers.toString().trimEnd())
            if (headersText.isNotEmpty()) {
                Log.d(TAG, "← headers:\n$headersText")
            }
            // peekBody 只读一份拷贝，不消费原始响应体，Retrofit 之后仍能正常解析
            val responseBody = response.peekBody(Long.MAX_VALUE).string()
            logChunked("← body", redact(redactImageData(responseBody)))
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

        /** 客户端跨配置快照共享，连接测试独立使用较短超时。 */
        private val sharedService: GeminiApiService by lazy { createService(30, 60) }
        private val sharedTestService: GeminiApiService by lazy { createService(10, 15) }

        private fun createService(connectSeconds: Long, ioSeconds: Long): GeminiApiService =
            Retrofit.Builder()
                .baseUrl("https://generativelanguage.googleapis.com/")
                .client(OkHttpClient.Builder()
                    .connectTimeout(connectSeconds, TimeUnit.SECONDS)
                    .readTimeout(ioSeconds, TimeUnit.SECONDS)
                    .writeTimeout(ioSeconds, TimeUnit.SECONDS)
                    .addInterceptor(LoggingInterceptor())
                    .eventListenerFactory(LlmNetworkEvents)
                    .build())
                .addConverterFactory(GsonConverterFactory.create())
                .build().create(GeminiApiService::class.java)

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

        internal val TAG_SUGGESTION_SCHEMA = GeminiSchema(
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
