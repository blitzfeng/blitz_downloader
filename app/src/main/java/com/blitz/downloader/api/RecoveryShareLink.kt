package com.blitz.downloader.api

import com.blitz.downloader.model.RecoveryNames
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** 仅在用户提交短链时读取抖音跳转头；每跳先验证目的地址，全程不携带账号凭据。 */
internal class RecoveryShareLink(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).callTimeout(15,TimeUnit.SECONDS).build()) {
    suspend fun resolve(value: String): String? {
        RecoveryNames.workId(value)?.let { return it }
        if (!RecoveryNames.shortLink(value)) return null
        var url = value.trim().toHttpUrlOrNull() ?: return null
        repeat(4) {
            if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.host !in setOf("v.douyin.com","www.douyin.com","douyin.com","m.douyin.com","www.iesdouyin.com")) return null
            RecoveryNames.workId(url.toString())?.let { return it }
            if (url.host == "www.iesdouyin.com") {
                Regex("/share/(?:video|note)/([0-9]{15,22})/?").matchEntire(url.encodedPath)?.let { return it.groupValues[1] }
            }
            val location = suspendCancellableCoroutine<String?> { waiting ->
                val call = client.newCall(Request.Builder().url(url).head().build())
                waiting.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { waiting.resume(null) }
                    override fun onResponse(call: Call, response: Response) {
                        response.use { waiting.resume(if (it.isRedirect) it.header("Location") else null) }
                    }
                })
            } ?: return null
            url = url.resolve(location) ?: return null
        }
        return null
    }
}
