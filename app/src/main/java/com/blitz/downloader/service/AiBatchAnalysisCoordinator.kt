package com.blitz.downloader.service

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** 固定数量的工作协程处理有界队列，完成结果由单一协程顺序汇总。 */
internal class AiBatchAnalysisCoordinator(private val concurrency: Int = 3) {
    init {
        require(concurrency > 0)
    }

    data class Summary(val succeeded: Int, val failed: Int)
    private data class ItemResult(val id: String, val succeeded: Boolean)

    suspend fun run(
        ids: List<String>,
        analyze: suspend (String) -> Boolean,
        onCompleted: suspend (id: String, succeeded: Boolean, done: Int, total: Int) -> Unit,
    ): Summary = coroutineScope {
        val distinctIds = ids.distinct()
        if (distinctIds.isEmpty()) return@coroutineScope Summary(0, 0)
        val workerCount = minOf(concurrency, distinctIds.size)
        val pending = Channel<String>(workerCount)
        val results = Channel<ItemResult>(workerCount)
        launch {
            for (id in distinctIds) pending.send(id)
            pending.close()
        }
        repeat(workerCount) {
            launch {
                for (id in pending) {
                    ensureActive()
                    results.send(ItemResult(id, analyze(id)))
                }
            }
        }

        var succeeded = 0
        repeat(distinctIds.size) { index ->
            val result = results.receive()
            if (result.succeeded) succeeded++
            onCompleted(result.id, result.succeeded, index + 1, distinctIds.size)
        }
        Summary(succeeded, distinctIds.size - succeeded)
    }
}
