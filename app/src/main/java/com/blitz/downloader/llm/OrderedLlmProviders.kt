package com.blitz.downloader.llm

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 每次请求使用固定顺序，并返回实际产生结果的服务。 */
internal suspend fun <T> List<LlmProvider>.requestInPriorityOrder(
    request: suspend (LlmProvider) -> Result<T>,
): Result<Pair<LlmProvider, T>> {
    var failure: Throwable = IllegalStateException("请先在设置中配置 AI 服务")
    for (provider in this) {
        currentCoroutineContext().ensureActive()
        val result = request(provider)
        if (result.isSuccess) return result.map { provider to it }
        failure = checkNotNull(result.exceptionOrNull())
        if (failure is CancellationException) throw failure
        if (failure !is IOException && (failure as? LlmResponseException)?.retryable != true) break
    }
    return Result.failure(failure)
}
