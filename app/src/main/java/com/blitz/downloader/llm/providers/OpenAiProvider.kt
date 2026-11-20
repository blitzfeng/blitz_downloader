package com.blitz.downloader.llm.providers

import com.blitz.downloader.config.AiConnectionConfig
import com.blitz.downloader.llm.AiAnalysisLogFormatter
import com.blitz.downloader.llm.ImagePart
import com.blitz.downloader.llm.LlmProvider
import com.blitz.downloader.llm.LlmResponseDiagnostics
import com.blitz.downloader.llm.LlmResponseException
import com.blitz.downloader.llm.PreferenceSummaryRequest
import com.blitz.downloader.llm.TagSuggestionRequest
import com.blitz.downloader.llm.TagSuggestionRequestBuilder
import com.blitz.downloader.llm.TagSuggestionResponse
import com.blitz.downloader.llm.isRetryableLlmStatus
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.ByteString.Companion.toByteString
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

internal interface OpenAiResponsesApi {
    @POST("v1/responses")
    suspend fun create(
        @Header("Authorization") authorization: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): Response<ResponseBody>
}

internal class OpenAiProvider(
    private val config: AiConnectionConfig,
    private val api: OpenAiResponsesApi = sharedApi,
    private val testApi: OpenAiResponsesApi = sharedTestApi,
) : LlmProvider {
    override val providerId = config.service.id
    override val modelId = config.model

    override suspend fun generateTagSuggestion(request: TagSuggestionRequest): Result<TagSuggestionResponse> = call {
        val content = buildList {
            add(textPart(TagSuggestionRequestBuilder.buildTagSuggestionPrompt(request)))
            request.multimodalEvidenceSamples.forEachIndexed { index, sample ->
                add(textPart(
                    "同作者历史案例 ${index + 1}，仅供参考。文案：${sample.desc}。" +
                        "用户采纳：${sample.acceptedTagNames.joinToString("、")}。" +
                        "用户拒绝：${sample.rejectedTagNames.joinToString("、")}。",
                ))
                sample.evidenceImage?.let { add(imagePart(it, "low")) }
            }
            add(textPart("以下是当前视频的图片。evidenceFrames 仅使用下面标注的序号。图片 0（封面）"))
            add(imagePart(request.coverImage, "high"))
            request.keyFrames.forEachIndexed { index, frame ->
                add(textPart("当前视频图片 ${index + 1}"))
                add(imagePart(frame, if (frame.hasFace) "high" else "low"))
            }
        }
        val response = create(api, listOf(mapOf("role" to "user", "content" to content)), structured = true)
        try {
            gson.fromJson(response.text, GeminiTagSuggestionPayload::class.java)
                .toDomain().copy(diagnostics = response.diagnostics)
        } catch (error: Exception) {
            throw LlmResponseException("OpenAI 标签输出解析失败", response.diagnostics)
        }
    }

    override suspend fun summarizePreference(request: PreferenceSummaryRequest): Result<String> = call {
        val prompt = buildString {
            appendLine("根据用户确认的视频标签，用简洁中文总结用户的打标签偏好和判断标准。只输出摘要。")
            request.recentSamples.forEach { sample ->
                appendLine("- 文案：${sample.desc}。最终标签：${sample.confirmedTagNames.joinToString("、")}")
            }
        }
        create(api, prompt).text.trim()
    }

    override suspend fun testConnection(): Result<String> = call {
        create(testApi, "请只回复 OK，用于测试连接。")
        "连接成功（OpenAI · $modelId）"
    }

    private suspend fun <T> call(block: suspend () -> T): Result<T> {
        config.validationError?.let { return Result.failure(IllegalStateException(it)) }
        return try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LlmResponseException) {
            Result.failure(error)
        } catch (error: IOException) {
            Result.failure(IOException("OpenAI 网络请求失败：${error.javaClass.simpleName}"))
        } catch (error: Exception) {
            Result.failure(IllegalStateException("OpenAI 请求失败：${error.javaClass.simpleName}"))
        }
    }

    private suspend fun create(api: OpenAiResponsesApi, input: Any, structured: Boolean = false): Parsed {
        val request = buildMap {
            put("model", modelId)
            put("input", input)
            put("store", false)
            if (structured) {
                put("text", mapOf("format" to mapOf(
                    "type" to "json_schema",
                    "name" to "tag_suggestion",
                    "strict" to true,
                    "schema" to tagSchema,
                )))
            }
        }
        val response = api.create("Bearer ${config.apiKey}", request)
        val raw = (if (response.isSuccessful) response.body() else response.errorBody())
            ?.use { it.string() }.orEmpty()
            .replace(config.apiKey, "[REDACTED]")
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
        val diagnostics = LlmResponseDiagnostics(
            rawResponseBody = AiAnalysisLogFormatter.sanitizeResponse(raw),
            tokenUsage = root?.get("usage")?.takeIf { it.isJsonObject }?.toString(),
        )
        fun fail(message: String, retryable: Boolean = false): Nothing =
            throw LlmResponseException(message, diagnostics, retryable = retryable)

        if (!response.isSuccessful) {
            fail("OpenAI HTTP ${response.code()}：${diagnostics.rawResponseBody}", isRetryableLlmStatus(response.code()))
        }
        if (root == null) fail("OpenAI 响应 JSON 解析失败")
        if (root.string("status") != "completed") {
            val reason = root.get("incomplete_details")?.takeIf { it.isJsonObject }?.asJsonObject?.string("reason")
            fail("OpenAI 未完成生成（${reason ?: root.string("status").orEmpty()}）")
        }
        val content = root.objects("output").filter { it.string("type") == "message" }
            .flatMap { it.objects("content") }
        if (content.any { it.string("type") == "refusal" }) fail("OpenAI 拒绝了本次请求")
        val text = content.filter { it.string("type") == "output_text" }
            .mapNotNull { it.string("text") }.joinToString("")
        if (text.isBlank()) fail("OpenAI 返回结果为空")
        return Parsed(text, diagnostics)
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.objects(name: String): List<JsonObject> =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { item -> item.isJsonObject }?.asJsonObject }.orEmpty()

    private data class Parsed(val text: String, val diagnostics: LlmResponseDiagnostics)

    companion object {
        private val gson = Gson()
        private val tagSchema by lazy { GeminiProvider.TAG_SUGGESTION_SCHEMA.toStrictJsonSchema() }
        private val sharedApi by lazy { createApi(90) }
        private val sharedTestApi by lazy { createApi(20) }

        private fun createApi(seconds: Long): OpenAiResponsesApi = Retrofit.Builder()
            .baseUrl("https://api.openai.com/")
            .client(OkHttpClient.Builder()
                .callTimeout(seconds, TimeUnit.SECONDS)
                .readTimeout(seconds, TimeUnit.SECONDS)
                .writeTimeout(seconds, TimeUnit.SECONDS)
                .followRedirects(false)
                .retryOnConnectionFailure(false)
                .build())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build().create(OpenAiResponsesApi::class.java)

        private fun textPart(text: String) = mapOf("type" to "input_text", "text" to text)

        private fun imagePart(image: ImagePart, detail: String) = mapOf(
            "type" to "input_image",
            "detail" to detail,
            "image_url" to "data:image/jpeg;base64,${image.jpegBytes.toByteString().base64()}",
        )

        private fun GeminiSchema.toStrictJsonSchema(): Map<String, Any> = buildMap {
            put("type", type.lowercase())
            properties?.let { fields ->
                put("properties", fields.mapValues { (_, schema) -> schema.toStrictJsonSchema() })
                put("required", fields.keys.toList())
                put("additionalProperties", false)
            }
            items?.let { put("items", it.toStrictJsonSchema()) }
            enumValues?.let { put("enum", it) }
            description?.let { put("description", it) }
        }
    }
}
