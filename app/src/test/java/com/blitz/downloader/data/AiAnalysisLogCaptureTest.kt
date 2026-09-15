package com.blitz.downloader.data

import com.blitz.downloader.llm.AiAnalysisLogEntry
import com.blitz.downloader.llm.AiAnalysisLogStatus
import com.blitz.downloader.llm.AiAnalysisLogStore
import com.blitz.downloader.llm.TagCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class AiAnalysisLogCaptureTest {

    @Before
    fun setup() {
        AiAnalysisLogStore.clear()
    }

    @Test
    fun testSuccessfulAnalysisLogLifecycle() {
        val logId = "log-success-1"
        // 1. 发起前记录 RUNNING
        AiAnalysisLogStore.addEntry(
            AiAnalysisLogEntry(
                id = logId,
                awemeId = "aweme-100",
                videoTitle = "测试视频标题",
                status = AiAnalysisLogStatus.RUNNING,
                requestPrompt = "提示词：五个维度",
                requestFramesSummary = "封面 1 张，关键帧 2 张",
            ),
        )

        val running = AiAnalysisLogStore.getEntries().first()
        assertEquals(AiAnalysisLogStatus.RUNNING, running.status)

        // 2. 成功后更新为 SUCCESS
        AiAnalysisLogStore.updateEntry(logId) {
            it.copy(
                status = AiAnalysisLogStatus.SUCCESS,
                durationMs = 1500L,
                suggestedTags = listOf(TagCandidate(tagId = 10L, confidence = 0.92f, tagName = "颜值")),
                rawResponseBody = "{\"candidates\":[{\"tagName\":\"颜值\",\"confidence\":0.92}]}",
            )
        }

        val success = AiAnalysisLogStore.getEntries().first()
        assertEquals(AiAnalysisLogStatus.SUCCESS, success.status)
        assertEquals(1500L, success.durationMs)
        assertEquals(1, success.suggestedTags.size)
        assertEquals("颜值", success.suggestedTags[0].tagName)
    }

    @Test
    fun testFailedAnalysisLogLifecycle() {
        val logId = "log-fail-1"
        // 1. 发起前记录 RUNNING
        AiAnalysisLogStore.addEntry(
            AiAnalysisLogEntry(
                id = logId,
                awemeId = "aweme-200",
                videoTitle = "失败视频测试",
                status = AiAnalysisLogStatus.RUNNING,
            ),
        )

        // 2. 异常后更新为 FAILED
        val exception = IllegalStateException("网络连接超时 (HTTP 504)")
        AiAnalysisLogStore.updateEntry(logId) {
            it.copy(
                status = AiAnalysisLogStatus.FAILED,
                durationMs = 3000L,
                errorMessage = exception.message,
            )
        }

        val failed = AiAnalysisLogStore.getEntries().first()
        assertEquals(AiAnalysisLogStatus.FAILED, failed.status)
        assertEquals(3000L, failed.durationMs)
        assertNotNull(failed.errorMessage)
        assertEquals("网络连接超时 (HTTP 504)", failed.errorMessage)
    }
}
