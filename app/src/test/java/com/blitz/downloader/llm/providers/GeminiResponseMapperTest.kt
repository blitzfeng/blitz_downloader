package com.blitz.downloader.llm.providers

import com.blitz.downloader.llm.AiAnalysisLogFormatter
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class GeminiResponseMapperTest {
    private fun parse(json: String) = Gson().fromJson(json, GeminiTagSuggestionPayload::class.java).toDomain()

    @Test
    fun invisibleFaceWithOmittedListsDoesNotFailAnalysis() {
        val result = parse("""{
            "visualFeatureProfile": {
                "face": {"visibility":"none"},
                "expression": {"visibility":"none"},
                "bodyAndStyling": {"visibility":"high","observableTraits":["腿部特写"],"evidenceFrames":[1]}
            },
            "candidates": [{"tagName":"腿部","tagId":7,"confidence":0.9}]
        }""")
        assertEquals("none", result.visualFeatureProfile.face?.visibility)
        assertEquals(emptyList<String>(), result.visualFeatureProfile.face?.observableTraits)
        assertEquals(listOf("腿部特写"), result.visualFeatureProfile.bodyAndStyling?.observableTraits)
        assertEquals(listOf(1), result.visualFeatureProfile.bodyAndStyling?.evidenceFrames)
        assertEquals(7L, result.candidates.single().tagId)
        assertTrue(result.candidates.single().evidenceFrames.isEmpty())
        assertTrue(AiAnalysisLogFormatter.formatVisualProfile(result.visualFeatureProfile).contains("腿部特写"))
    }

    @Test
    fun explicitNullDimensionsAndListsAreSafe() {
        val result = parse("""{
            "visualFeatureProfile": {
                "face": null,
                "expression": {"visibility":null},
                "clothing": {"visibility":"high","observableTraits":null,"evidenceFrames":null},
                "action": {"visibility":"low","observableTraits":[null,"站立"],"evidenceFrames":[null,2]}
            },
            "candidates": [null,{"tagName":null,"evidenceFrames":null}]
        }""")
        assertEquals("none", result.visualFeatureProfile.face?.visibility)
        assertEquals("none", result.visualFeatureProfile.expression?.visibility)
        assertTrue(result.visualFeatureProfile.clothing!!.observableTraits.isEmpty())
        assertEquals(listOf(2), result.visualFeatureProfile.action?.evidenceFrames)
        assertEquals(listOf("站立"), result.visualFeatureProfile.action?.observableTraits)
        assertEquals("", result.candidates.single().tagName)
        assertTrue(result.candidates.single().evidenceFrames.isEmpty())
    }

    @Test
    fun noMatchingTagsIsSuccessful() {
        assertTrue(parse("""{"visualFeatureProfile":{},"candidates":[]}""").candidates.isEmpty())
    }

    @Test
    fun visibleFaceAndTagEvidenceArePreserved() {
        val result = parse("""{
            "visualFeatureProfile": {
                "face":{"visibility":"high","observableTraits":["正脸"],"evidenceFrames":[0]},
                "expression":{"visibility":"medium","observableTraits":["微笑"],"evidenceFrames":[1]}
            },
            "candidates":[{"tagName":"微笑","tagId":8,"confidence":0.85,"evidenceFrames":[1]}]
        }""")
        assertEquals("high", result.visualFeatureProfile.face?.visibility)
        assertEquals(listOf("正脸"), result.visualFeatureProfile.face?.observableTraits)
        assertEquals(listOf("微笑"), result.visualFeatureProfile.expression?.observableTraits)
        assertEquals(listOf(1), result.candidates.single().evidenceFrames)
        assertEquals(0.85f, result.candidates.single().confidence, 0.001f)
    }

    @Test
    fun missingRequiredTopLevelFieldsHaveClearErrors() {
        for (json in listOf("{}", """{"visualFeatureProfile":{},"candidates":null}""")) {
            val error = runCatching { parse(json) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error!!.message!!.contains("Gemini 结构化输出缺少"))
        }
    }
}
