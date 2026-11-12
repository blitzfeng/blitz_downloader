package com.blitz.downloader.llm.providers

import com.blitz.downloader.config.AiConnectionConfig
import com.blitz.downloader.config.AiService
import com.blitz.downloader.llm.LlmResponseException
import com.blitz.downloader.llm.PreferenceSummaryRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** 拦截请求直接返回响应，验证真实 Retrofit 编码，不访问 Google 或使用真实凭据。 */
class VertexAiConnectionTest {
    private class Fixture(val code: Int = 200, val response: String = OK) {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String>()
        val service = Retrofit.Builder().baseUrl("https://unused.invalid/")
            .client(OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests += request
                bodies += Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                    .message("test").body(response.toResponseBody()).build()
            }.build()).addConverterFactory(GsonConverterFactory.create()).build()
            .create(GeminiApiService::class.java)
        fun provider(config: AiConnectionConfig) = GeminiProvider(config, service, service)
    }

    @Test fun vertexUsesPublisherPathAndHeaderOnly() = runBlocking {
        val fixture = Fixture()
        val result = fixture.provider(AiConnectionConfig(AiService.VERTEX_AI, KEY)).testConnection()
        assertTrue(result.getOrThrow().contains("Vertex AI"))
        assertTrue(result.getOrThrow().contains(AiConnectionConfig.DEFAULT_MODEL))
        val request = fixture.requests.single()
        assertEquals("https://aiplatform.googleapis.com/v1/publishers/google/models/gemini-3.8-flash:generateContent", request.url.toString())
        assertEquals(KEY, request.header("x-goog-api-key"))
        assertFalse(request.url.toString().contains(KEY))
        assertFalse(fixture.bodies.single().contains(KEY))
        assertFalse(fixture.bodies.single().contains("CIVIC_INTEGRITY"))
        assertTrue(fixture.bodies.single().contains("thinkingLevel"))
    }

    @Test fun developerApiRemainsUnchanged() = runBlocking {
        val fixture = Fixture()
        fixture.provider(AiConnectionConfig(AiService.GEMINI, KEY)).testConnection().getOrThrow()
        assertEquals("generativelanguage.googleapis.com", fixture.requests.single().url.host)
        assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", fixture.requests.single().url.encodedPath)
        assertTrue(fixture.bodies.single().contains("CIVIC_INTEGRITY"))
    }

    @Test fun summaryUsesSameVertexModelAndKey() = runBlocking {
        val fixture = Fixture()
        val provider = fixture.provider(AiConnectionConfig(AiService.VERTEX_AI, KEY, "gemini-3.1-pro-preview"))
        assertEquals("OK", provider.summarizePreference(PreferenceSummaryRequest(emptyList())).getOrThrow())
        assertEquals("vertex_ai", provider.providerId)
        assertTrue(fixture.requests.single().url.encodedPath.contains(provider.modelId))
        assertEquals(KEY, fixture.requests.single().header("x-goog-api-key"))
    }

    @Test fun blankKeyFailsBeforeNetwork() = runBlocking {
        val fixture = Fixture()
        val result = fixture.provider(AiConnectionConfig(AiService.VERTEX_AI, "")).testConnection()
        assertEquals("Vertex AI API Key 未配置", result.exceptionOrNull()?.message)
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun malformedModelCannotAlterRequestDestination() = runBlocking {
        val fixture = Fixture()
        for (model in listOf("", "https://evil.invalid", "gemini-3/../../test", "gemini-3?key=x", "gemini-2.5-flash")) {
            assertTrue(fixture.provider(AiConnectionConfig(AiService.VERTEX_AI, KEY, model)).testConnection().isFailure)
        }
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun httpErrorRetainsStatusAndRedactsEchoedKey() = runBlocking {
        val fixture = Fixture(403, """{"error":{"message":"denied $KEY"}}""")
        val error = fixture.provider(AiConnectionConfig(AiService.VERTEX_AI, KEY)).testConnection().exceptionOrNull()!!
        assertTrue(error.message!!.contains("403"))
        assertTrue(error.message!!.contains("Vertex AI"))
        assertFalse(error.message!!.contains(KEY))
        assertFalse((error as LlmResponseException).diagnostics.rawResponseBody.contains(KEY))
        assertNull(error.cause)
    }

    @Test fun emptyResponseIsNotConnectionSuccess() = runBlocking {
        val fixture = Fixture(response = "{}")
        assertTrue(fixture.provider(AiConnectionConfig(AiService.VERTEX_AI, KEY)).testConnection().isFailure)
    }

    @Test fun existingProviderKeepsSnapshotAfterSelectionChanges() = runBlocking {
        val fixture = Fixture()
        var selected = AiConnectionConfig(AiService.VERTEX_AI, KEY)
        val inFlight = fixture.provider(selected)
        selected = AiConnectionConfig(AiService.GEMINI, "other-key")
        inFlight.testConnection().getOrThrow()
        fixture.provider(selected).testConnection().getOrThrow()
        assertEquals(listOf("aiplatform.googleapis.com", "generativelanguage.googleapis.com"), fixture.requests.map { it.url.host })
        assertEquals("vertex_ai", inFlight.providerId)
        assertEquals(KEY, fixture.requests[0].header("x-goog-api-key"))
        assertEquals("other-key", fixture.requests[1].header("x-goog-api-key"))
    }

    @Test fun cancellationIsPropagated() = runBlocking {
        val service = object : GeminiApiService {
            override suspend fun generateContent(url: String, apiKey: String, body: GeminiGenerateContentRequest): retrofit2.Response<okhttp3.ResponseBody> {
                throw CancellationException("cancelled")
            }
        }
        try {
            GeminiProvider(AiConnectionConfig(AiService.VERTEX_AI, KEY), service, service).testConnection()
            fail("取消必须向上传播")
        } catch (_: CancellationException) { }
    }

    @Test fun missingOrUnknownSelectionDefaultsToGemini() {
        assertEquals(AiService.GEMINI, AiService.fromId(null))
        assertEquals(AiService.GEMINI, AiService.fromId("unknown"))
        assertEquals(AiService.VERTEX_AI, AiService.fromId("vertex_ai"))
        assertFalse(AiConnectionConfig(AiService.VERTEX_AI, KEY).toString().contains(KEY))
    }

    companion object {
        private const val KEY = "test-secret-never-real"
        private const val OK = """{"candidates":[{"content":{"parts":[{"text":"OK"}]},"finishReason":"STOP"}]}"""
    }
}
