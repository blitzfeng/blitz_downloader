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

    @Test
    fun build_excludesDisabledAiTags() {
        val tags = listOf(
            TagEntity(tagName = "可爱", sortOrder = 0, id = 1, enableAi = true),
            TagEntity(tagName = "不导出", sortOrder = 1, id = 2, enableAi = false),
            TagEntity(tagName = "图片", sortOrder = 2, id = 3, enableAi = false),
        )

        val request = TagSuggestionRequestBuilder.build(
            coverImage = cover,
            keyFrames = emptyList(),
            desc = "",
            tags = tags,
        )

        assertEquals(1, request.tagVocabulary.size)
        assertEquals("可爱", request.tagVocabulary[0].name)
    }

    @Test
    fun build_detectsExclusiveCategory() {
        val tags = listOf(
            TagEntity(tagName = "颜值", sortOrder = 0, id = 10, isExclusive = true),
            TagEntity(tagName = "可爱", sortOrder = 1, id = 11, parentTagName = "颜值"),
            TagEntity(tagName = "身材", sortOrder = 2, id = 20, isExclusive = false),
            TagEntity(tagName = "美腿", sortOrder = 3, id = 21, parentTagName = "身材"),
        )

        val request = TagSuggestionRequestBuilder.build(
            coverImage = cover,
            keyFrames = emptyList(),
            desc = "",
            tags = tags,
        )

        val yanzhi = request.tagVocabulary.first { it.name == "颜值" }
        val keai = request.tagVocabulary.first { it.name == "可爱" }
        val shencai = request.tagVocabulary.first { it.name == "身材" }
        val meitui = request.tagVocabulary.first { it.name == "美腿" }

        assertTrue(yanzhi.isParent)
        assertTrue(yanzhi.isExclusiveCategory)
        assertTrue(keai.isExclusiveCategory)

        assertTrue(shencai.isParent)
        assertTrue(!shencai.isExclusiveCategory)
        assertTrue(!meitui.isExclusiveCategory)
    }

    @Test
    fun buildTagSuggestionPrompt_containsSubjectProminenceAndExclusiveRules() {
        val tags = listOf(
            TagEntity(tagName = "颜值", sortOrder = 0, id = 10, isExclusive = true, description = "颜值气质"),
            TagEntity(tagName = "纯欲", sortOrder = 1, id = 11, parentTagName = "颜值", description = "纯欲风"),
            TagEntity(tagName = "独立", sortOrder = 2, id = 30, description = "独立标签"),
        )

        val request = TagSuggestionRequestBuilder.build(
            coverImage = cover,
            keyFrames = emptyList(),
            desc = "阳光明媚的一天",
            tags = tags,
        )

        val prompt = TagSuggestionRequestBuilder.buildTagSuggestionPrompt(request)

        assertTrue(prompt.contains("【核心主体原则（宁缺毋滥）】"))
        assertTrue(prompt.contains("严禁过度打标"))
        assertTrue(prompt.contains("--- 气质风格分类规则（主导单选原则与反差兼具例外） ---"))
        assertTrue(prompt.contains("【分类：颜值】（主导风格单选分类）："))
        assertTrue(prompt.contains("[常规约束]"))
        assertTrue(prompt.contains("[反差兼具例外]"))
        assertTrue(prompt.contains("[ID: 11] 纯欲"))
        assertTrue(prompt.contains("[ID: 10] 颜值（说明：颜值气质）：若人物/画面整体符合该分类大类特征"))
        assertTrue(prompt.contains("[ID: 30] 独立"))
        assertTrue(prompt.contains("待分析视频文案：阳光明媚的一天"))
    }

    @Test
    fun resolveCandidatesWithParentHierarchy_autoCompletesParentTag() {
        val allTags = listOf(
            TagEntity(tagName = "身材", sortOrder = 0, id = 10L),
            TagEntity(tagName = "美腿", sortOrder = 1, id = 11L, parentTagName = "身材"),
        )
        val raw = listOf(
            TagCandidate(tagId = 11L, tagName = "美腿", confidence = 0.85f, evidenceFrames = listOf(1, 2)),
        )

        val resolved = TagSuggestionRequestBuilder.resolveCandidatesWithParentHierarchy(raw, allTags)

        assertEquals(2, resolved.size)
        val meitui = resolved.first { it.tagId == 11L }
        val shencai = resolved.first { it.tagId == 10L }

        assertEquals("美腿", meitui.tagName)
        assertEquals(0.85f, meitui.confidence, 0.001f)
        assertEquals(listOf(1, 2), meitui.evidenceFrames)

        assertEquals("身材", shencai.tagName)
        assertEquals(0.85f, shencai.confidence, 0.001f)
        assertEquals(listOf(1, 2), shencai.evidenceFrames)
    }

    @Test
    fun resolveCandidatesWithParentHierarchy_fallbackParentTagDirectlyPreserved() {
        val allTags = listOf(
            TagEntity(tagName = "颜值", sortOrder = 0, id = 10L, isExclusive = true),
            TagEntity(tagName = "可爱", sortOrder = 1, id = 11L, parentTagName = "颜值"),
        )
        // AI 兜底返回父标签本身
        val raw = listOf(
            TagCandidate(tagId = 10L, tagName = "颜值", confidence = 0.75f, evidenceFrames = listOf(0)),
        )

        val resolved = TagSuggestionRequestBuilder.resolveCandidatesWithParentHierarchy(raw, allTags)

        assertEquals(1, resolved.size)
        val yanzhi = resolved.first()
        assertEquals(10L, yanzhi.tagId)
        assertEquals("颜值", yanzhi.tagName)
        assertEquals(0.75f, yanzhi.confidence, 0.001f)
        assertEquals(listOf(0), yanzhi.evidenceFrames)
    }

    @Test
    fun resolveCandidatesWithParentHierarchy_mergesMultipleChildrenUnderSameParent() {
        val allTags = listOf(
            TagEntity(tagName = "身材", sortOrder = 0, id = 10L),
            TagEntity(tagName = "美腿", sortOrder = 1, id = 11L, parentTagName = "身材"),
            TagEntity(tagName = "细腰", sortOrder = 2, id = 12L, parentTagName = "身材"),
        )
        val raw = listOf(
            TagCandidate(tagId = 11L, tagName = "美腿", confidence = 0.8f, evidenceFrames = listOf(1)),
            TagCandidate(tagId = 12L, tagName = "细腰", confidence = 0.95f, evidenceFrames = listOf(2, 3)),
        )

        val resolved = TagSuggestionRequestBuilder.resolveCandidatesWithParentHierarchy(raw, allTags)

        assertEquals(3, resolved.size)
        val shencai = resolved.first { it.tagId == 10L }
        assertEquals(0.95f, shencai.confidence, 0.001f) // max confidence
        assertEquals(listOf(1, 2, 3), shencai.evidenceFrames) // merged distinct frames
    }

    @Test
    fun resolveCandidatesWithParentHierarchy_filtersHallucinations() {
        val allTags = listOf(
            TagEntity(tagName = "颜值", sortOrder = 0, id = 10L),
        )
        val raw = listOf(
            TagCandidate(tagId = 999L, tagName = "不存在的标签", confidence = 0.9f),
        )

        val resolved = TagSuggestionRequestBuilder.resolveCandidatesWithParentHierarchy(raw, allTags)
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun buildPreviewPrompt_generatesFullPromptWithTagsAndDescriptions() {
        val tags = listOf(
            TagEntity(tagName = "风格", sortOrder = 0, id = 1L, isExclusive = true),
            TagEntity(tagName = "甜妹", sortOrder = 1, id = 2L, parentTagName = "风格", description = "阳光甜美"),
        )
        val prompt = TagSuggestionRequestBuilder.buildPreviewPrompt(tags)

        assertTrue(prompt.contains("你是一个短视频内容标签助手"))
        assertTrue(prompt.contains("【核心主体原则（宁缺毋滥）】"))
        assertTrue(prompt.contains("【可选标签词表与分类规则】"))
        assertTrue(prompt.contains("[ID: 2] 甜妹（说明：阳光甜美）"))
        assertTrue(prompt.contains("请严格按照给定的 JSON Schema 返回结果"))
    }
}
