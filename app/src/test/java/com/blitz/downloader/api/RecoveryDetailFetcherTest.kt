package com.blitz.downloader.api

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class RecoveryDetailFetcherTest {
    private val id = "7390000000000000001"
    private val cookie = "sessionid=test"
    private fun client(code: Int, json: String, wait: String = "") = RecoveryDetailFetcher(
        OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals(id,chain.request().url.queryParameter("aweme_id"))
            Response.Builder().request(chain.request()).message("fixture").protocol(Protocol.HTTP_1_1)
                .code(code).header("Retry-After",wait).body(json.toResponseBody("application/json".toMediaType())).build()
        }.build())
    private fun json(work: String, desc: String = "fixture") =
        """{"status_code":0,"aweme_detail":{"aweme_id":"$work","desc":"$desc","create_time":12345,"video":{"duration":10,"play_addr":{"url_list":["https://secret.invalid/"]}},"author":{"nickname":"test","sec_uid":"fake"}}}"""

    @Test fun classifiedFailures(): Unit = runBlocking {
        for (code in listOf(401,403)) assertEquals("AUTH_REQUIRED",client(code,"").fetch(id,cookie).state)
        assertEquals("REMOTE_UNAVAILABLE",client(410,"").fetch(id,cookie).state)
        assertTrue(client(503,"").fetch(id,cookie).retryable)
        assertEquals(120000L,client(429,"","120").fetch(id,cookie).retryAfterMs)
        assertEquals(60000L,client(429,"").fetch(id,cookie).retryAfterMs)
        for (body in listOf("","{}","<html>",json(id,"a".repeat(65536)),"a".repeat(2*1024*1024+1)))
            assertEquals("INVALID_RESPONSE",client(200,body).fetch(id,cookie).state)
        assertEquals("ID_MISMATCH",client(200,json("999")).fetch(id,cookie).state)
        val detail = client(200,json(id)).fetch(id,cookie).detail!!
        assertEquals(id,detail.awemeId)
        assertFalse(detail.toString().contains("secret.invalid"))
    }
    @Test fun accountScopeIgnoresRotatingParameters() {
        assertEquals(RecoveryDetailFetcher.scope(cookie),RecoveryDetailFetcher.scope("$cookie; msToken=other"))
        assertNotEquals(RecoveryDetailFetcher.scope(cookie),RecoveryDetailFetcher.scope("sessionid=changed"))
        assertEquals("",RecoveryDetailFetcher.scope("msToken=test"))
    }
}
