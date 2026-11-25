package com.blitz.downloader.llm

import com.blitz.downloader.data.SuggestedTagCodec
import com.blitz.downloader.data.db.TagEntity
import com.blitz.downloader.model.BatchReviewLogic
import org.junit.Assert.*
import org.junit.Test

class TagChangeSuggestionsTest {
    private val tags = listOf(
        TagEntity(tagName = "风格", sortOrder = 0, id = 1, isExclusive = true),
        TagEntity(tagName = "可爱", sortOrder = 1, id = 2, parentTagName = "风格"),
        TagEntity(tagName = "甜妹", sortOrder = 2, id = 3, parentTagName = "风格"),
        TagEntity(tagName = "清冷", sortOrder = 3, id = 4, parentTagName = "风格"),
        TagEntity(tagName = "不导出", sortOrder = 4, id = 5, enableAi = false),
    )

    @Test fun sendsExistingTagsIncludingDisabledTagsSeparatelyFromVocabulary() {
        val request = TagSuggestionRequestBuilder.build(
            ImagePart(byteArrayOf(), false), emptyList(), "文案", tags,
            existingTagNames = setOf("可爱", "不导出"),
        )
        assertEquals(setOf("可爱", "不导出"), request.existingTags.map { it.name }.toSet())
        assertFalse(request.tagVocabulary.any { it.name == "不导出" })
        val prompt = TagSuggestionRequestBuilder.buildTagSuggestionPrompt(request)
        assertTrue(prompt.contains("当前视频已有标签"))
        assertTrue(prompt.contains("[ID: 2] 可爱"))
        assertTrue(prompt.contains("[ID: 5] 不导出"))
        assertTrue(prompt.contains("replace"))
        assertTrue(prompt.contains("coexist"))
    }

    @Test fun replacementRemovesOnlyNamedOldTagAfterExplicitApplication() {
        val candidate = TagCandidate(3, .9f, tagName = "甜妹", action = "replace",
            replacesTagIds = listOf(2), changeReason = "当前画面的主导风格更接近甜美")
        val normalized = TagChangeSuggestions.validate(listOf(candidate), tags, setOf("可爱", "不导出"))
        val changes = TagChangeSuggestions.describe(normalized, tags)
        assertEquals(setOf("可爱"), changes.single().replacedTags)
        assertEquals(setOf("甜妹", "不导出"), TagChangeSuggestions.apply(
            setOf("可爱", "不导出"), setOf("甜妹"), changes,
        ))
        assertEquals(setOf("可爱", "不导出"), TagChangeSuggestions.apply(
            setOf("可爱", "不导出"), emptySet(), changes,
        ))
        assertTrue(changes.single().description().contains("可爱 → 甜妹"))
    }

    @Test fun coexistencePreservesExistingTagsAndExplicitReasonSurvivesStorage() {
        val candidate = TagCandidate(3, .9f, tagName = "甜妹", action = "coexist",
            changeReason = "不同镜头分别展示可爱动作和甜美笑容，形成明显反差")
        val normalized = TagChangeSuggestions.validate(listOf(candidate), tags, setOf("可爱"))
        val detail = SuggestedTagCodec.decode(SuggestedTagCodec.encode(normalized)).getValue(3)
        assertEquals("coexist", detail.action)
        assertEquals(candidate.changeReason, detail.changeReason)
        assertTrue(detail.replacesTagIds.isEmpty())
        assertEquals(setOf(2L, 3L), detail.coexistingTagIds.toSet())
        val changes = TagChangeSuggestions.describe(normalized, tags)
        assertEquals(setOf("可爱", "甜妹"), changes.single().relatedTags)
        assertEquals(setOf("可爱", "甜妹"), TagChangeSuggestions.apply(setOf("可爱"), setOf("甜妹"), changes))
        val allowed = mapOf("video" to changes.map { it.relatedTags + it.tagName })
        assertEquals(setOf("video"), BatchReviewLogic.findConflictingVideos(
            tags, mapOf("video" to setOf("清冷", "甜妹")), allowed,
        ))
    }

    @Test fun replacementMetadataAndEvidenceSurviveRestartAndLegacyStillDecodes() {
        val candidate = TagCandidate(3, .8f, tagName = "甜妹", action = "replace",
            replacesTagIds = listOf(2), changeReason = "画面依据")
        val stored = SuggestedTagCodec.encode(listOf(candidate), mapOf(3L to "Download/evidence.jpg"))
        val detail = SuggestedTagCodec.decode(stored).getValue(3)
        assertEquals(listOf(2L), detail.replacesTagIds)
        assertEquals("replace", detail.action)
        assertEquals("Download/evidence.jpg", detail.evidencePath)
        assertEquals("add", SuggestedTagCodec.decode("2:0.9").getValue(2).action)
    }

    @Test fun rejectsImplicitConflictAndUnrelatedReplacement() {
        assertThrows(IllegalArgumentException::class.java) {
            TagChangeSuggestions.validate(listOf(TagCandidate(3, .9f, tagName = "甜妹")), tags, setOf("可爱"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TagChangeSuggestions.validate(listOf(TagCandidate(3, .9f, tagName = "甜妹", action = "replace",
                replacesTagIds = listOf(5), changeReason = "无效替换")), tags, setOf("不导出"))
        }
    }

    @Test fun rejectsCoexistenceWithoutReasonOrMoreThanTwoSiblings() {
        assertThrows(IllegalArgumentException::class.java) {
            TagChangeSuggestions.validate(listOf(TagCandidate(3, .9f, tagName = "甜妹", action = "coexist")), tags, setOf("可爱"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TagChangeSuggestions.validate(listOf(TagCandidate(4, .9f, tagName = "清冷", action = "coexist",
                changeReason = "仍不可三选")), tags, setOf("可爱", "甜妹"))
        }
    }

    @Test fun validationAllowsOnlyTheExplicitTwoTagCombination() {
        val actual = mapOf("v1" to setOf("可爱", "甜妹"), "v2" to setOf("可爱", "清冷"))
        val allowed = mapOf("v1" to listOf(setOf("可爱", "甜妹")))
        assertEquals(setOf("v2"), BatchReviewLogic.findConflictingVideos(tags, actual, allowed))
        assertEquals(setOf("v1", "v2"), BatchReviewLogic.findConflictingVideos(tags, actual))
    }

    @Test fun deduplicationPreservesModelReplacementMetadata() {
        val candidate = TagCandidate(3, .9f, tagName = "甜妹", action = "replace",
            replacesTagIds = listOf(2), changeReason = "证据")
        val result = TagSuggestionRequestBuilder.resolveCandidatesWithParentHierarchy(listOf(candidate), tags)
        assertEquals(listOf(2L), result.first { it.tagId == 3L }.replacesTagIds)
        assertEquals("replace", result.first { it.tagId == 3L }.action)
    }
}
