package com.blitz.downloader.model

import com.blitz.downloader.data.db.AiReferenceRow
import org.junit.Assert.*
import org.junit.Test

class AiReferenceVideoTest {
    private fun row(id: Long, video: String, kind: String = "ACCEPTED", tag: String = "穿搭",
        author: String = "author-a", excluded: Boolean = false, path: String? = "image.jpg") =
        AiReferenceRow(id, video, "同名作者", author, "相同文案", tag, kind, path, id, excluded)

    @Test fun groupsAllFeedbackByVideoRatherThanCaptionAndDeduplicatesImages() {
        val videos = groupAiReferenceVideos(listOf(
            row(1, "one"), row(2, "one", "REJECTED", "舞蹈"), row(3, "one", "MISSED", "古装"),
            row(4, "two"), row(5, "one"),
        ))
        assertEquals(listOf("one", "two"), videos.map { it.awemeId })
        val first = videos.first()
        assertEquals(4, first.feedbackCount)
        assertEquals(listOf("穿搭"), first.acceptedTags)
        assertEquals(listOf("舞蹈"), first.rejectedTags)
        assertEquals(listOf("古装"), first.missedTags)
        assertEquals(listOf("image.jpg"), first.evidencePaths)
    }

    @Test fun scopeUsesStableAuthorIdAndSeparatesRemovedVideos() {
        val videos = groupAiReferenceVideos(listOf(row(1, "one"), row(2, "two", author = "author-b"),
            row(3, "three", excluded = true)))
        assertEquals(listOf("one"), filterAiReferenceVideos(videos, "", "author-a", false).map { it.awemeId })
        assertEquals(listOf("two", "one"), filterAiReferenceVideos(videos, "", null, false).map { it.awemeId })
        assertEquals(listOf("three"), filterAiReferenceVideos(videos, "", "author-a", true).map { it.awemeId })
    }

    @Test fun searchesCaptionAuthorAndVideoIdWithoutChangingSource() {
        val videos = groupAiReferenceVideos(listOf(row(1, "abc"), row(2, "def").copy(desc = "我屠一座城")))
        assertEquals("def", filterAiReferenceVideos(videos, "  屠一座城  ", null, false).single().awemeId)
        assertEquals(2, filterAiReferenceVideos(videos, "同名作者", null, false).size)
        assertEquals("abc", filterAiReferenceVideos(videos, "abc", null, false).single().awemeId)
        assertTrue(filterAiReferenceVideos(videos, "不存在", null, false).isEmpty())
        assertEquals(2, videos.size)
    }

    @Test fun missingImageStillProducesManageableCase() {
        val video = groupAiReferenceVideos(listOf(row(1, "one", path = null), row(2, "one", path = ""))).single()
        assertTrue(video.evidencePaths.isEmpty())
        assertEquals("one", video.awemeId)
        assertEquals(2, video.feedbackCount)
    }
}
