package com.blitz.downloader.llm

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AiAnalysisLogStoreTest {

    @Before
    fun setup() {
        AiAnalysisLogStore.clear()
    }

    @Test
    fun testAddAndUpdateEntry() {
        val entry = AiAnalysisLogEntry(
            id = "log-1",
            awemeId = "aweme-1",
            videoTitle = "视频1",
            status = AiAnalysisLogStatus.RUNNING,
        )
        AiAnalysisLogStore.addEntry(entry)
        assertEquals(1, AiAnalysisLogStore.getEntries().size)
        assertEquals(AiAnalysisLogStatus.RUNNING, AiAnalysisLogStore.getEntries()[0].status)

        AiAnalysisLogStore.updateEntry("log-1") {
            it.copy(
                status = AiAnalysisLogStatus.SUCCESS,
                durationMs = 800L,
                rawResponseBody = "{\"candidates\":[]}",
            )
        }

        val updated = AiAnalysisLogStore.getEntries()[0]
        assertEquals(AiAnalysisLogStatus.SUCCESS, updated.status)
        assertEquals(800L, updated.durationMs)
        assertEquals("{\"candidates\":[]}", updated.rawResponseBody)
    }

    @Test
    fun testCapacityLimit() {
        for (i in 1..120) {
            AiAnalysisLogStore.addEntry(
                AiAnalysisLogEntry(
                    id = "log-$i",
                    awemeId = "aweme-$i",
                    videoTitle = "视频$i",
                )
            )
        }

        val entries = AiAnalysisLogStore.getEntries()
        assertEquals(AiAnalysisLogStore.MAX_LOG_CAPACITY, entries.size)
        // 应该保留 21..120，前20条已被淘汰
        assertEquals("log-21", entries.first().id)
        assertEquals("log-120", entries.last().id)
    }

    @Test
    fun testClear() {
        AiAnalysisLogStore.addEntry(
            AiAnalysisLogEntry(
                id = "log-1",
                awemeId = "aweme-1",
                videoTitle = "视频1",
            )
        )
        assertEquals(1, AiAnalysisLogStore.getEntries().size)
        AiAnalysisLogStore.clear()
        assertEquals(0, AiAnalysisLogStore.getEntries().size)
    }
}
