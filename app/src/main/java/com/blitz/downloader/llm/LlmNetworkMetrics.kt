package com.blitz.downloader.llm

import java.io.IOException
import java.util.UUID
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response

/** 单次 HTTP 调用的时间和报文字节数，大小不包含 HTTP 头和 TLS 开销。 */
data class LlmNetworkMetrics(
    val id: String = UUID.randomUUID().toString(),
    val service: String,
    val model: String,
    val requestAtMillis: Long,
    val responseAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
    val uploadDurationMs: Long? = null,
    val responseWaitMs: Long? = null,
    val downloadDurationMs: Long? = null,
    val durationMs: Long? = null,
    val requestBytes: Long? = null,
    val responseBytes: Long? = null,
    val httpCode: Int? = null,
    val failed: Boolean = false,
)

/** 通过 Retrofit Tag 关联单条分析，避免共享客户端混用并发请求的统计。 */
class LlmNetworkTrace(
    val service: String,
    val model: String,
    val onUpdate: (LlmNetworkMetrics) -> Unit,
)

internal object LlmNetworkEvents : EventListener.Factory {
    override fun create(call: Call): EventListener {
        val trace = call.request().tag(LlmNetworkTrace::class.java) ?: return EventListener.NONE
        return object : EventListener() {
            private val startedNanos = System.nanoTime()
            private var uploadStartedNanos: Long? = null
            private var uploadEndedNanos: Long? = null
            private var responseStartedNanos: Long? = null
            private var metrics = LlmNetworkMetrics(
                service = trace.service,
                model = trace.model,
                requestAtMillis = System.currentTimeMillis(),
                requestBytes = call.request().body?.contentLength()?.takeIf { it >= 0 },
            )

            private fun elapsed(since: Long = startedNanos) = (System.nanoTime() - since) / 1_000_000
            private fun publish() = trace.onUpdate(metrics)

            override fun callStart(call: Call) = publish()

            override fun requestBodyStart(call: Call) {
                uploadStartedNanos = System.nanoTime()
            }

            override fun requestBodyEnd(call: Call, byteCount: Long) {
                uploadEndedNanos = System.nanoTime()
                metrics = metrics.copy(
                    requestBytes = byteCount,
                    uploadDurationMs = uploadStartedNanos?.let { elapsed(it) },
                )
                publish()
            }

            override fun responseHeadersEnd(call: Call, response: Response) {
                responseStartedNanos = System.nanoTime()
                metrics = metrics.copy(
                    responseAtMillis = System.currentTimeMillis(),
                    responseWaitMs = uploadEndedNanos?.let { elapsed(it) },
                    httpCode = response.code,
                )
                publish()
            }

            override fun responseBodyEnd(call: Call, byteCount: Long) {
                metrics = metrics.copy(
                    responseBytes = byteCount,
                    downloadDurationMs = responseStartedNanos?.let { elapsed(it) },
                )
                publish()
            }

            override fun callEnd(call: Call) {
                metrics = metrics.copy(completedAtMillis = System.currentTimeMillis(), durationMs = elapsed())
                publish()
            }

            override fun callFailed(call: Call, ioe: IOException) {
                metrics = metrics.copy(completedAtMillis = System.currentTimeMillis(), durationMs = elapsed(), failed = true)
                publish()
            }
        }
    }
}
