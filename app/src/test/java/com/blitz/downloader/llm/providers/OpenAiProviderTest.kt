package com.blitz.downloader.llm.providers

import com.blitz.downloader.config.AiConnectionConfig
import com.blitz.downloader.config.AiService
import com.blitz.downloader.llm.ImagePart
import com.blitz.downloader.llm.MultimodalEvidenceSample
import com.blitz.downloader.llm.TagSuggestionRequest
import com.blitz.downloader.llm.TagWordEntry
import com.blitz.downloader.llm.requestInPriorityOrder
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class OpenAiProviderTest {
    private class Fixture(code: Int, responseBody: String) {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String>()
        private val api = Retrofit.Builder().baseUrl("https://api.openai.com/")
            .client(OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests += request
                bodies += Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                    .message("test").body(responseBody.toResponseBody()).build()
            }.build()).addConverterFactory(GsonConverterFactory.create()).build()
            .create(OpenAiResponsesApi::class.java)
        val provider = OpenAiProvider(AiConnectionConfig(AiService.OPENAI, KEY), api, api)
    }

    @Test fun analysisSendsImagesAndStrictSchemaAndReadsMessageAfterReasoning() = runBlocking {
        val payload = """{"visualFeatureProfile":{"face":{"visibility":"none"}},"candidates":[{"tagId":12,"tagName":"舞蹈","confidence":0.8,"evidenceFrames":[1],"action":"replace","replacesTagIds":[11],"changeReason":"画面依据"}]}"""
        val fixture = Fixture(200, completed(Gson().toJson(payload)))
        val image = ImagePart(byteArrayOf(1, 2, 3), hasFace = false)
        val result = fixture.provider.generateTagSuggestion(
            TagSuggestionRequest(
                coverImage = image, keyFrames = listOf(image), desc = "新视频",
                tagVocabulary = listOf(TagWordEntry(12, "舞蹈")),
                multimodalEvidenceSamples = listOf(MultimodalEvidenceSample("已审核视频", listOf("舞蹈"), evidenceImage = image)),
            ),
        ).getOrThrow()

        val http = fixture.requests.single()
        assertEquals("/v1/responses", http.url.encodedPath)
        assertEquals("Bearer $KEY", http.header("Authorization"))
        assertFalse(fixture.bodies.single().contains(KEY))
        val body = JsonParser.parseString(fixture.bodies.single()).asJsonObject
        assertEquals("gpt-6-astra", body["model"].asString)
        assertFalse(body["store"].asBoolean)
        val parts = body["input"].asJsonArray[0].asJsonObject["content"].asJsonArray.map { it.asJsonObject }
        val images = parts.filter { it["type"].asString == "input_image" }
        assertEquals(listOf("low", "high", "low"), images.map { it["detail"].asString })
        assertEquals("data:image/jpeg;base64,AQID", images.first()["image_url"].asString)
        assertTrue(parts.any { it["text"]?.asString?.contains("已审核视频") == true })
        val format = body["text"].asJsonObject["format"].asJsonObject
        assertTrue(format["strict"].asBoolean)
        val schema = format["schema"].asJsonObject
        assertFalse(schema["additionalProperties"].asBoolean)
        val candidate = schema["properties"].asJsonObject["candidates"].asJsonObject["items"].asJsonObject
        assertEquals(candidate["properties"].asJsonObject.keySet(), candidate["required"].asJsonArray.map { it.asString }.toSet())
        assertEquals(12L, result.candidates.single().tagId)
        assertEquals(listOf(1), result.candidates.single().evidenceFrames)
        assertEquals("replace", result.candidates.single().action)
        assertEquals(listOf(11L), result.candidates.single().replacesTagIds)
        assertEquals("画面依据", result.candidates.single().changeReason)
    }

    @Test fun rateLimitSelectsNextProvider() = runBlocking {
        val limited = Fixture(429, """{"error":{"message":"额度用尽"}}""")
        val available = Fixture(200, completed("\"OK\""))
        val (provider, _) = listOf(limited.provider, available.provider)
            .requestInPriorityOrder { it.testConnection() }.getOrThrow()
        assertSame(available.provider, provider)
        assertEquals(1, limited.requests.size)
        assertEquals(1, available.requests.size)
    }

    @Test fun providerRefusalDoesNotSendToNextProvider() = runBlocking {
        val refused = Fixture(200, """
            {"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"拒绝"}]}]}
        """.trimIndent())
        val available = Fixture(200, completed("\"OK\""))
        val result = listOf(refused.provider, available.provider).requestInPriorityOrder { it.testConnection() }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("拒绝"))
        assertTrue(available.requests.isEmpty())
    }

    @Test fun authenticationErrorDoesNotFallbackOrExposeKey() = runBlocking {
        val unauthorized = Fixture(401, """{"error":{"message":"Invalid key $KEY"}}""")
        val available = Fixture(200, completed("\"OK\""))
        val failure = listOf(unauthorized.provider, available.provider)
            .requestInPriorityOrder { it.testConnection() }.exceptionOrNull()!!
        assertTrue(failure.message.orEmpty().contains("401"))
        assertFalse(failure.message.orEmpty().contains(KEY))
        assertTrue(available.requests.isEmpty())
    }

    @Test fun priorityPreservesLegacyAndNormalizesSavedOrder() {
        assertEquals(listOf(AiService.VERTEX_AI, AiService.GEMINI, AiService.OPENAI), AiService.priority(null, "vertex_ai"))
        assertEquals(listOf(AiService.OPENAI, AiService.GEMINI, AiService.VERTEX_AI), AiService.priority("openai,openai,unknown", null))
    }

    companion object {
        private const val KEY = "sk-test-only"

        private fun completed(textJson: String) = """
            {"status":"completed","output":[{"type":"reasoning"},{"type":"message","content":[{"type":"output_text","text":$textJson}]}]}
        """.trimIndent()
    }
}
