package com.blitz.downloader.service

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBatchAnalysisCoordinatorTest {
    @Test fun limitsConcurrencyRefillsSlotsAndCountsOutOfOrderResultsOnce() = runBlocking {
        withTimeout(5000) {
            val started = Channel<String>(Channel.UNLIMITED)
            val completed = Channel<Pair<String, Int>>(Channel.UNLIMITED)
            val gates = (0..5).associate { it.toString() to CompletableDeferred<Unit>() }
            val active = AtomicInteger()
            val peak = AtomicInteger()
            val job = async {
                AiBatchAnalysisCoordinator(3).run(
                    ids = gates.keys.toList() + "0",
                    analyze = { id ->
                        val count = active.incrementAndGet()
                        peak.updateAndGet { maxOf(it, count) }
                        try {
                            started.send(id)
                            gates.getValue(id).await()
                            id != "1"
                        } finally {
                            active.decrementAndGet()
                        }
                    },
                    onCompleted = { id, _, done, total ->
                        assertEquals(6, total)
                        completed.send(id to done)
                    },
                )
            }
            assertEquals(setOf("0", "1", "2"), setOf(started.receive(), started.receive(), started.receive()))
            assertFalse(started.tryReceive().isSuccess)
            // 第二项先失败，下一项应立即补位，不等待第一项结束。
            gates.getValue("1").complete(Unit)
            assertEquals("1" to 1, completed.receive())
            assertEquals("3", started.receive())
            assertFalse(started.tryReceive().isSuccess)
            gates.values.forEach { it.complete(Unit) }
            val summary = job.await()
            assertEquals(AiBatchAnalysisCoordinator.Summary(5, 1), summary)
            assertEquals(3, peak.get())
            assertEquals(0, active.get())
            val remaining = List(5) { completed.receive() }
            assertEquals(listOf(2, 3, 4, 5, 6), remaining.map { it.second })
            assertEquals(setOf("0", "2", "3", "4", "5"), remaining.map { it.first }.toSet())
        }
    }

    @Test fun cancellationStopsWorkersAndLeavesQueuedItemsUnstarted() = runBlocking {
        withTimeout(5000) {
            val started = Channel<String>(Channel.UNLIMITED)
            val active = AtomicInteger()
            val gate = CompletableDeferred<Unit>()
            val job = async {
                AiBatchAnalysisCoordinator(2).run(
                    ids = (0..50).map(Int::toString),
                    analyze = { id ->
                        active.incrementAndGet()
                        try {
                            started.send(id)
                            gate.await()
                            true
                        } finally {
                            active.decrementAndGet()
                        }
                    },
                    onCompleted = { _, _, _, _ -> error("取消后不能发布完成") },
                )
            }
            started.receive()
            started.receive()
            job.cancelAndJoin()
            assertEquals(0, active.get())
            assertFalse(started.tryReceive().isSuccess)
            assertTrue(job.isCancelled)
        }
    }

    @Test fun emptyBatchDoesNotStartAnalysis() = runBlocking {
        val result = AiBatchAnalysisCoordinator().run(
            emptyList(),
            analyze = { error("空队列不执行") },
            onCompleted = { _, _, _, _ -> error("空队列不发进度") },
        )
        assertEquals(AiBatchAnalysisCoordinator.Summary(0, 0), result)
    }
}
