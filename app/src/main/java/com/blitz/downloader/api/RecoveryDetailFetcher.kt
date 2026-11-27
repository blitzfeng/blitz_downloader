package com.blitz.downloader.api

import com.blitz.downloader.model.RecoveryDetail
import com.blitz.downloader.model.RecoveryNames
import com.blitz.downloader.model.sha256
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class RecoveryReply(val state: String, val detail: RecoveryDetail? = null, val http: Int = 0,
    val retryAfterMs: Long = 0, val retryable: Boolean = false)

class RecoveryDetailFetcher(
    private val client: OkHttpClient = transport,
    private val endpoint: HttpUrl = "https://www.douyin.com/aweme/v1/web/aweme/detail/".toHttpUrl(),
) {
    companion object {
        private val transport = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()
        private const val maxBody = 2L * 1024 * 1024
        private val gson = Gson()

        fun scope(cookie: String?): String {
            val stable = cookie.orEmpty().split(';').map { it.trim() }.filter {
                it.substringBefore('=') in setOf("sessionid", "sessionid_ss", "uid_tt", "sid_guard") && it.substringAfter('=', "").isNotBlank()
            }.sorted()
            return if (stable.isEmpty()) "" else sha256(stable.joinToString("\n"))
        }
    }

    suspend fun fetch(id: String, cookie: String?): RecoveryReply {
        require(RecoveryNames.workId(id) == id)
        if (scope(cookie).isEmpty()) return RecoveryReply("AUTH_REQUIRED")
        val url = endpoint.newBuilder().addQueryParameter("aweme_id", id).addQueryParameter("aid", "6383")
            .addQueryParameter("device_platform","webapp").build()
        val request = Request.Builder().url(url).header("Cookie", cookie.orEmpty()).header("Accept","application/json").build()
        return suspendCancellableCoroutine { waiting ->
            val call = client.newCall(request)
            waiting.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    waiting.resume(RecoveryReply("NETWORK_ERROR", retryable = true))
                }
                override fun onResponse(call: Call, response: Response) {
                    val reply = response.use {
                        try { decode(id, it) }
                        catch (_: IOException) { RecoveryReply("NETWORK_ERROR", http = it.code, retryable = true) }
                        catch (_: RuntimeException) { RecoveryReply("INVALID_RESPONSE", http = it.code) }
                    }
                    waiting.resume(reply)
                }
            })
        }
    }

    private fun decode(id: String, response: Response): RecoveryReply {
        val code = response.code
        when {
            code in listOf(401,403) -> return RecoveryReply("AUTH_REQUIRED", http = code)
            code == 429 -> {
                val seconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 604800)
                val date = response.headers.getDate("Retry-After")?.time
                val wait = seconds?.times(1000) ?: date?.minus(System.currentTimeMillis()) ?: 60_000L
                return RecoveryReply("RATE_LIMITED", http = code, retryAfterMs = wait.coerceAtLeast(1000))
            }
            code in listOf(404,410) -> return RecoveryReply("REMOTE_UNAVAILABLE", http = code)
            !response.isSuccessful -> return RecoveryReply("NETWORK_ERROR", http = code, retryable = code == 408 || code >= 500)
        }
        val body = response.body ?: return RecoveryReply("INVALID_RESPONSE", http = code)
        if (body.contentLength() > maxBody || body.source().request(maxBody + 1)) return RecoveryReply("INVALID_RESPONSE", http = code)
        val json = JsonParser.parseString(body.string()).asJsonObject
        if (json["status_code"]?.asInt != 0) return RecoveryReply("INVALID_RESPONSE", http = code)
        val item = gson.fromJson(json["aweme_detail"], AwemeItem::class.java) ?: return RecoveryReply("INVALID_RESPONSE", http = code)
        if (item.awemeId != id) return RecoveryReply("ID_MISMATCH", http = code)
        val author = item.author ?: return RecoveryReply("INVALID_RESPONSE", http = code)
        val image = !item.images.isNullOrEmpty()
        if ((!image && item.video == null) || author.nickname.isNullOrBlank() || author.secUid.isNullOrBlank() ||
            item.createTime !in 1..(System.currentTimeMillis()/1000 + 86400)) return RecoveryReply("INVALID_RESPONSE", http = code)
        val detail = RecoveryDetail(id, if (image) "image" else "video", author.nickname, author.secUid,
            item.desc.orEmpty(), item.createTime, item.video?.duration?.toLong() ?: 0,
            item.statistics?.diggCount ?: 0, item.statistics?.collectCount ?: 0)
        if (gson.toJson(detail).toByteArray().size > 65536) return RecoveryReply("INVALID_RESPONSE", http = code)
        return RecoveryReply("SUCCESS", detail, code)
    }
}
