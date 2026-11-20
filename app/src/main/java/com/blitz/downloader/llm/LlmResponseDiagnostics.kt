package com.blitz.downloader.llm

/** 接口诊断信息随本次调用传递，避免并发请求之间串用响应。 */
data class LlmResponseDiagnostics(
    val rawResponseBody: String,
    val tokenUsage: String? = null,
)

class LlmResponseException(
    message: String,
    val diagnostics: LlmResponseDiagnostics,
    cause: Throwable? = null,
    val retryable: Boolean = false,
) : IllegalStateException(message, cause)

internal fun isRetryableLlmStatus(code: Int): Boolean = code == 408 || code == 429 || code in 500..599
