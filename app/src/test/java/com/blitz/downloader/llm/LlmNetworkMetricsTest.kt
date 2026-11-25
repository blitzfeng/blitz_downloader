package com.blitz.downloader.llm

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmNetworkMetricsTest {
    @Test fun concurrentCallsKeepIndependentMetricsAndFailedRequestHasNoResponseSize() {
        val first = mutableListOf<LlmNetworkMetrics>()
        val second = mutableListOf<LlmNetworkMetrics>()
        val client = OkHttpClient()
        fun call(updates: MutableList<LlmNetworkMetrics>) = client.newCall(
            Request.Builder().url("https://example.invalid/")
                .post("含图片的请求".toRequestBody())
                .tag(LlmNetworkTrace::class.java, LlmNetworkTrace("测试服务", "测试模型") { updates += it })
                .build(),
        )
        val firstCall = call(first)
        val secondCall = call(second)
        val firstEvents = LlmNetworkEvents.create(firstCall)
        val secondEvents = LlmNetworkEvents.create(secondCall)
        firstEvents.callStart(firstCall)
        secondEvents.callStart(secondCall)
        firstEvents.requestBodyStart(firstCall)
        firstEvents.requestBodyEnd(firstCall, 4096)
        firstEvents.responseHeadersEnd(firstCall, Response.Builder()
            .request(firstCall.request()).protocol(Protocol.HTTP_1_1).code(429).message("test").build())
        firstEvents.responseBodyEnd(firstCall, 123)
        firstEvents.callEnd(firstCall)
        secondEvents.callFailed(secondCall, IOException("timeout"))

        val completed = first.last()
        assertEquals(4096L, completed.requestBytes)
        assertEquals(123L, completed.responseBytes)
        assertEquals(429, completed.httpCode)
        assertTrue(completed.responseAtMillis != null)
        assertTrue(completed.durationMs != null)
        assertFalse(completed.failed)
        val failed = second.last()
        assertTrue(failed.failed)
        assertEquals(null, failed.responseAtMillis)
        assertEquals(null, failed.responseBytes)
        assertTrue(failed.durationMs != null)
        assertTrue(completed.id != failed.id)
    }

    @Test fun copiedLogIncludesAllAttemptsAndExactByteCounts() {
        val entry = AiAnalysisLogEntry(
            id = "test", awemeId = "video", videoTitle = "视频", timestamp = 1000,
            networkRequests = listOf(
                LlmNetworkMetrics(service = "OpenAI", model = "gpt-test", requestAtMillis = 1500,
                    responseAtMillis = 2000, completedAtMillis = 2100, requestBytes = 2048, responseBytes = 120,
                    durationMs = 600, httpCode = 429),
                LlmNetworkMetrics(service = "Gemini", model = "gemini-test", requestAtMillis = 2200),
            ),
        )
        val report = AiAnalysisLogFormatter.formatEntryToPlainText(entry)
        assertTrue(report.contains("请求前准备耗时  500 ms"))
        assertTrue(report.contains("2048 B"))
        assertTrue(report.contains("120 B"))
        assertTrue(report.contains("600 ms"))
        assertTrue(report.contains("请求 2  Gemini"))
        assertTrue(report.contains("尚未收到"))
    }
}
