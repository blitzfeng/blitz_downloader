package com.blitz.downloader.llm

import com.blitz.downloader.data.db.TagEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagSuggestionRequestBuilderTest {

    private val cover = ImagePart(jpegBytes = ByteArray(0), hasFace = false)

    @Test
    fun build_withoutAuthorProfileOrPreference_constructsRequestWithNullContext() {
        val tags = listOf(
            TagEntity(tagName = "可爱", sortOrder = 0, id = 1, description = "笑容甜美"),
            TagEntity(tagName = "颜值", sortOrder = 1, id = 2),
        )

        val request = TagSuggestionRequestBuilder.build(
            coverImage = cover,
            keyFrames = emptyList(),
            desc = "测试视频",
            tags = tags,
            authorProfile = null,
            preferenceProfileText = null,
        )

        assertNull(request.authorProfile)
        assertNull(request.preferenceProfileText)
        assertTrue(request.fewShotExamples.isEmpty())
        assertEquals(2, request.tagVocabulary.size)
        assertEquals(1L, request.tagVocabulary.first { it.name == "可爱" }.tagId)
        assertEquals("笑容甜美", request.tagVocabulary.first { it.name == "可爱" }.description)
    }

    @Test
    fun build_blankPreferenceProfile_treatedAsNull() {
        val request = TagSuggestionRequestBuilder.build(
            coverImage = cover,
            keyFrames = emptyList(),
            desc = "",
            tags = emptyList(),
            preferenceProfileText = "   ",
        )

        assertNull(request.preferenceProfileText)
    }

    @Test
    fun build_withParentHierarchy_resolvesParentTagId() {
        val tags = listOf(
            TagEntity(tagName = "颜值", sortOrder = 0, id = 1),
            TagEntity(tagName = "甜妹", sortOrder = 1, id = 2, parentTagName = "颜值"),
        )

        val request = TagSuggestionRequestBuilder.build(
            coverImage = cover,
            keyFrames = emptyList(),
            desc = "",
            tags = tags,
            includeParentHierarchy = true,
        )

        val sweet = request.tagVocabulary.first { it.name == "甜妹" }
        assertEquals(1L, sweet.parentTagId)
    }

    @Test
    fun build_parentHierarchyDisabled_doesNotResolveParentTagId() {
        val tags = listOf(
            TagEntity(tagName = "颜值", sortOrder = 0, id = 1),
            TagEntity(tagName = "甜妹", sortOrder = 1, id = 2, parentTagName = "颜值"),
        )

        val request = TagSuggestionRequestBuilder.build(
            coverImage = cover,
            keyFrames = emptyList(),
            desc = "",
            tags = tags,
            includeParentHierarchy = false,
        )

        val sweet = request.tagVocabulary.first { it.name == "甜妹" }
        assertNull(sweet.parentTagId)
    }
}
