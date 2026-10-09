package com.blitz.downloader.llm.providers

import com.blitz.downloader.llm.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class GeminiResponseParserTest {
    private fun failure(raw: String, code: Int = 200): LlmResponseException {
        val response = if (code == 200) Response.success(raw.toResponseBody())
            else Response.error(code, raw.toResponseBody())
        return assertThrows(LlmResponseException::class.java) { GeminiResponseParser.parse(response) }
    }

    @Test fun blockedResponseKeepsReasonAndDiagnosticsInExport() {
        val error = failure("""{
            "promptFeedback":{"blockReason":"PROHIBITED_CONTENT"},
            "usageMetadata":{"promptTokenCount":8028,"totalTokenCount":8028,
                "promptTokensDetails":[{"modality":"TEXT","tokenCount":2214},{"modality":"IMAGE","tokenCount":5814}],
                "serviceTier":"standard"},
            "modelVersion":"gemini-3.8-flash","responseId":"yBDDaoTQKZiHqtsP7NHr-AM"
        }""")
        assertEquals("请求被内容策略拦截（PROHIBITED_CONTENT）", error.message)
        val entry = AiAnalysisLogEntry("request-1", "video-1", videoTitle = "视频",
            rawRequestBody = "{\"request\":\"example\"}").withFailure(error, 500L)
        for (text in listOf(AiAnalysisLogFormatter.formatEntryToPlainText(entry),
            AiAnalysisLogFormatter.formatAllEntriesToPlainText(listOf(entry)))) {
            for (expected in listOf("PROHIBITED_CONTENT", "8028", "5814", "gemini-3.8-flash",
                "yBDDaoTQKZiHqtsP7NHr-AM", "example")) assertTrue(text.contains(expected))
        }
    }

    @Test fun abnormalFinishRejectsEvenPartialText() {
        val error = failure("""{"candidates":[{"finishReason":"MAX_TOKENS","finishMessage":"limit reached",
            "content":{"parts":[{"text":"partial"}]}}]}""")
        assertTrue(error.message!!.contains("MAX_TOKENS"))
        assertTrue(error.message!!.contains("limit reached"))
    }

    @Test fun unknownCodesAndMessageAreKeptAndPromptBlockHasPriority() {
        val error = failure("""{"promptFeedback":{"blockReason":"FUTURE_REASON","blockReasonMessage":"details"},
            "candidates":[{"finishReason":"SAFETY"}]}""")
        assertEquals("请求被模型拦截（FUTURE_REASON）：details", error.message)
        assertTrue(failure("""{"candidates":[{"finishReason":"FUTURE_FINISH"}]}""").message!!.contains("FUTURE_FINISH"))
    }

    @Test fun emptyAndMalformedResponsesHaveFallbackWithBody() {
        assertEquals("Gemini 返回结果为空", failure("{}").message)
        assertEquals("Gemini 返回结果为空", failure("""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":" "}]}}]}""").message)
        val malformed = failure("not json")
        assertEquals("Gemini 响应 JSON 解析失败", malformed.message)
        assertEquals("not json", malformed.diagnostics.rawResponseBody)
    }

    @Test fun httpErrorsRetainStatusAndMessage() {
        val error = failure("""{"error":{"message":"quota exceeded","status":"RESOURCE_EXHAUSTED"}}""", 429)
        assertTrue(error.message!!.contains("HTTP 429"))
        assertTrue(error.message!!.contains("quota exceeded"))
    }

    @Test fun successRetainsOuterResponseAndJoinsTextWithoutThoughts() {
        val result = GeminiResponseParser.parse(Response.success("""{"candidates":[{"finishReason":"STOP",
            "content":{"parts":[{"thought":true,"text":"internal"},{"text":"hello"},{"text":" world"}]}}],
            "modelVersion":"model","responseId":"id","usageMetadata":{"totalTokenCount":12}}""".toResponseBody()))
        assertEquals("hello world", result.text)
        assertTrue(result.diagnostics.rawResponseBody.contains("responseId"))
        assertTrue(result.diagnostics.tokenUsage!!.contains("12"))
    }

    @Test fun responseRedactionHandlesWhitespaceAndEscapedValues() {
        val error = failure("""{"error":{"message":"denied"}, "apiKey" : "secret-key",
            "inlineData":{"data" : "base64-secret"},"cookie":"escaped\"secret"}""", 403)
        assertFalse(error.diagnostics.rawResponseBody.contains("secret"))
        assertFalse(error.message!!.contains("secret"))
        assertTrue(error.diagnostics.rawResponseBody.contains("[已脱敏]"))
    }

    @Test fun malformedPayloadPreservesOuterResponse() {
        val parsed = GeminiResponseParser.parse(Response.success("""{"candidates":[{"finishReason":"STOP",
            "content":{"parts":[{"text":"{broken"}]}}],"responseId":"parse-failure"}""".toResponseBody()))
        val error = assertThrows(LlmResponseException::class.java) {
            parsed.decode { com.google.gson.Gson().fromJson(it, GeminiTagSuggestionPayload::class.java) }
        }
        assertTrue(error.message!!.contains("结构化输出解析失败"))
        assertTrue(error.diagnostics.rawResponseBody.contains("parse-failure"))
    }

    @Test fun networkFailureHasNoFabricatedResponseAndKeepsRequest() {
        val entry = AiAnalysisLogEntry("request", "video", videoTitle = "视频", rawRequestBody = "request body")
            .withFailure(java.net.SocketTimeoutException("连接超时"), 1200L)
        assertEquals(AiAnalysisLogStatus.FAILED, entry.status)
        assertEquals("连接超时", entry.errorMessage)
        assertEquals(1200L, entry.durationMs)
        assertEquals("request body", entry.rawRequestBody)
        assertEquals("", entry.rawResponseBody)
        assertNull(entry.tokenUsage)
    }
}
