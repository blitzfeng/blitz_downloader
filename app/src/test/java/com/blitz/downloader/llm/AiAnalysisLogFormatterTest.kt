package com.blitz.downloader.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAnalysisLogFormatterTest {

    @Test
    fun testRedactImageData() {
        val rawJson = """{"parts":[{"inlineData":{"data":"AAAA1234567890BBBB","mimeType":"image/jpeg"}}]}"""
        val redacted = AiAnalysisLogFormatter.redactImageData(rawJson)
        assertFalse(redacted.contains("AAAA1234567890BBBB"))
        assertTrue(redacted.contains("[图片内容，约"))
        assertTrue(redacted.contains("mimeType"))
    }

    @Test
    fun testFormatCandidates() {
        val candidates = listOf(
            TagCandidate(tagId = 1L, confidence = 0.95f, tagName = "颜值", evidenceFrames = listOf(0, 1)),
            TagCandidate(tagId = 2L, confidence = 0.88f, tagName = "穿搭", evidenceFrames = listOf(1)),
        )
        val formatted = AiAnalysisLogFormatter.formatCandidates(candidates)
        assertTrue(formatted.contains("[ 颜值 ]"))
        assertTrue(formatted.contains("95%"))
        assertTrue(formatted.contains("依据图片: 图 0 ,  图 1"))
        assertTrue(formatted.contains("[ 穿搭 ]"))
    }

    @Test
    fun testFormatVisualProfile() {
        val profile = VisualFeatureProfile(
            face = VisualDimension(visibility = "high", observableTraits = listOf("清秀", "高鼻梁"), evidenceFrames = listOf(0)),
            clothing = VisualDimension(visibility = "medium", observableTraits = listOf("黑色上衣")),
        )
        val formatted = AiAnalysisLogFormatter.formatVisualProfile(profile)
        assertTrue(formatted.contains("面部特征 [high]"))
        assertTrue(formatted.contains("清秀   高鼻梁"))
        assertTrue(formatted.contains("服饰穿搭 [medium]"))
    }

    @Test
    fun testFormatVocabulary() {
        val words = listOf(
            TagWordEntry(tagId = 1, name = "标签A"),
            TagWordEntry(tagId = 2, name = "标签B"),
            TagWordEntry(tagId = 3, name = "标签C"),
            TagWordEntry(tagId = 4, name = "标签D"),
            TagWordEntry(tagId = 5, name = "标签E"),
        )
        val formatted = AiAnalysisLogFormatter.formatVocabulary(words)
        assertTrue(formatted.contains("[ 标签A ]    [ 标签B ]    [ 标签C ]    [ 标签D ]"))
        assertTrue(formatted.contains("[ 标签E ]"))
    }

    @Test
    fun testFormatEntryToPlainText() {
        val entry = AiAnalysisLogEntry(
            id = "log-1",
            awemeId = "112233",
            authorName = "搞笑达人",
            videoTitle = "搞笑达人 - 搞笑短剧",
            videoDesc = "搞笑短剧",
            status = AiAnalysisLogStatus.SUCCESS,
            durationMs = 1200,
            requestPrompt = "请给出标签",
            requestAuthorTagsSummary = "已下载 10 个视频，高频标签: 搞笑 (80%)",
            requestEvidenceSamplesSummary = "共 1 条 (采纳 1 条, 拒绝 0 条): 样例 1: 采纳 [搞笑]",
            suggestedTags = listOf(TagCandidate(tagId = 1L, confidence = 0.9f, tagName = "搞笑")),
        )
        val text = AiAnalysisLogFormatter.formatEntryToPlainText(entry)
        assertTrue(text.contains("视频: 搞笑达人 - 搞笑短剧"))
        assertTrue(text.contains("• 视频作者: 搞笑达人"))
        assertTrue(text.contains("• 视频文案: 搞笑短剧"))
        assertTrue(text.contains("分析成功"))
        assertTrue(text.contains("1200 ms"))
        assertTrue(text.contains("作者高频标签: 已下载 10 个视频，高频标签: 搞笑 (80%)"))
        assertTrue(text.contains("历史审核参考: 共 1 条 (采纳 1 条, 拒绝 0 条): 样例 1: 采纳 [搞笑]"))
        assertTrue(text.contains("[ 搞笑 ]"))
    }
}
