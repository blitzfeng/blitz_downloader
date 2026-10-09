package com.blitz.downloader.llm.providers

import com.blitz.downloader.llm.AiAnalysisLogFormatter
import com.blitz.downloader.llm.LlmResponseDiagnostics
import com.blitz.downloader.llm.LlmResponseException
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.ResponseBody
import retrofit2.Response

/** 保留响应外层诊断信息，在读取候选文本前检查服务端失败原因。 */
internal object GeminiResponseParser {
    data class Parsed(val text: String, val diagnostics: LlmResponseDiagnostics) {
        fun <T> decode(transform: (String) -> T): T = try {
            transform(text)
        } catch (error: Exception) {
            throw LlmResponseException(
                "Gemini 结构化输出解析失败：${error.javaClass.simpleName}", diagnostics, error,
            )
        }
    }

    fun parse(response: Response<ResponseBody>): Parsed {
        val raw = (if (response.isSuccessful) response.body() else response.errorBody())
            ?.use { it.string() }.orEmpty()
        val safeBody = AiAnalysisLogFormatter.sanitizeResponse(raw)
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
        val diagnostics = LlmResponseDiagnostics(
            rawResponseBody = safeBody,
            tokenUsage = root?.get("usageMetadata")?.takeIf { it.isJsonObject }?.let {
                AiAnalysisLogFormatter.sanitizeResponse(it.toString())
            },
        )
        fun fail(message: String): Nothing = throw LlmResponseException(
            AiAnalysisLogFormatter.sanitizeResponse(message), diagnostics,
        )
        if (!response.isSuccessful) {
            fail("Gemini HTTP ${response.code()}: $safeBody")
        }
        if (root == null) fail("Gemini 响应 JSON 解析失败")
        val feedback = root.get("promptFeedback")?.takeIf { it.isJsonObject }?.asJsonObject
        val blockReason = feedback?.text("blockReason")
        if (!blockReason.isNullOrBlank() && blockReason != "BLOCK_REASON_UNSPECIFIED") {
            val description = when (blockReason) {
                "PROHIBITED_CONTENT", "SAFETY" -> "请求被内容策略拦截"
                else -> "请求被模型拦截"
            }
            fail("$description（$blockReason）" + feedback.text("blockReasonMessage").suffix())
        }
        val candidate = root.get("candidates")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        val finishReason = candidate?.text("finishReason")
        if (!finishReason.isNullOrBlank() && finishReason != "STOP" && finishReason != "FINISH_REASON_UNSPECIFIED") {
            val description = when (finishReason) {
                "MAX_TOKENS" -> "模型输出达到长度限制"
                "SAFETY", "PROHIBITED_CONTENT" -> "模型输出被内容策略拦截"
                else -> "模型未正常完成生成"
            }
            fail("$description（$finishReason）" + candidate.text("finishMessage").suffix())
        }
        val content = candidate?.get("content")?.takeIf { it.isJsonObject }?.asJsonObject
        val parts = content?.get("parts")?.takeIf { it.isJsonArray }?.asJsonArray
        val text = parts?.mapNotNull { part ->
            part.takeIf { it.isJsonObject }?.asJsonObject?.let {
                if (it.get("thought")?.toString() == "true") null else it.text("text")
            }
        }?.joinToString("").orEmpty()
        if (text.isBlank()) fail("Gemini 返回结果为空")
        return Parsed(text, diagnostics)
    }

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun String?.suffix(): String = if (isNullOrBlank()) "" else "：$this"
}
