package com.blitz.downloader.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AiAnalysisLogModelsTest {

    @Test
    fun testAiAnalysisLogEntryCreation() {
        val entry = AiAnalysisLogEntry(
            id = "test-1",
            awemeId = "12345",
            authorName = "创作者A",
            videoTitle = "创作者A - 测试短视频",
            videoDesc = "测试短视频",
            status = AiAnalysisLogStatus.SUCCESS,
            durationMs = 1500L,
            requestPrompt = "请识别标签",
            fullPrompt = "你是一个短视频内容标签助手...",
            requestAuthorTagsSummary = "高频: 颜值(90%)",
            requestEvidenceSamplesSummary = "共 1 条 (采纳 1 条, 拒绝 0 条)",
            requestFramesSummary = "共3帧，含人脸1帧",
            requestVocabularySummary = "颜值, 穿搭",
            rawRequestBody = "{\"prompt\":\"test\"}",
            rawResponseBody = "{\"candidates\":[]}",
            suggestedTags = listOf(
                TagCandidate(tagId = 1L, confidence = 0.95f, tagName = "颜值"),
            ),
            visualFeatureProfile = VisualFeatureProfile(
                face = VisualDimension(visibility = "high", observableTraits = listOf("清秀")),
            ),
            tokenUsage = "prompt: 100, candidates: 50",
            errorMessage = null,
        )

        assertEquals("test-1", entry.id)
        assertEquals("12345", entry.awemeId)
        assertEquals("创作者A", entry.authorName)
        assertEquals("创作者A - 测试短视频", entry.videoTitle)
        assertEquals("测试短视频", entry.videoDesc)
        assertEquals(AiAnalysisLogStatus.SUCCESS, entry.status)
        assertEquals("你是一个短视频内容标签助手...", entry.fullPrompt)
        assertEquals("高频: 颜值(90%)", entry.requestAuthorTagsSummary)
        assertEquals("共 1 条 (采纳 1 条, 拒绝 0 条)", entry.requestEvidenceSamplesSummary)
        assertEquals(1, entry.suggestedTags.size)
        assertEquals("颜值", entry.suggestedTags[0].tagName)
        assertNotNull(entry.visualFeatureProfile?.face)
        assertEquals("high", entry.visualFeatureProfile?.face?.visibility)
    }
}
