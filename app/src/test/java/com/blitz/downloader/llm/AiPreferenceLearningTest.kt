package com.blitz.downloader.llm

import com.blitz.downloader.data.LearningCase
import com.blitz.downloader.data.PreferenceContextSelector
import com.blitz.downloader.data.PreferenceLearningPlanner
import com.blitz.downloader.data.db.TagEntity
import com.blitz.downloader.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AiPreferenceLearningTest {
    @Test
    fun missingOrUnknownSourceCannotBecomeHumanEvidence() {
        val decisions = PreferenceJson.decisions("""[
            {"tagId":51,"judgment":"POSITIVE"},
            {"tagId":52,"judgment":"POSITIVE","source":"FUTURE"},
            {"tagId":53,"judgment":"NEGATIVE","source":"AUTO_EXISTING"},
            {"tagId":54,"judgment":"NEGATIVE","source":"HUMAN_REMOVE"},
            {"tagId":55,"judgment":"FUTURE","source":"HUMAN_ADD"},
            {"source":"HUMAN_ADD"}
        ]""")
        assertEquals(listOf(51L, 52L, 53L, 54L, 55L), decisions.map { it.tagId })
        assertEquals(listOf(54L), decisions.filter { it.judgment == PreferenceJudgment.NEGATIVE }.map { it.tagId })
        assertEquals(PreferenceSource.LEGACY_UNKNOWN, decisions[1].source)
        assertEquals(PreferenceJudgment.UNKNOWN, decisions.last().judgment)
    }

    @Test
    fun leafPlannerPreservesJudgmentsAcrossLargeCasesAndLargeLibraries() {
        val tags = (1L..90).map { TagEntity("标签$it", id = it) }
        val veryLong = "很长的相同文案".repeat(1600)
        val decisions = tags.map { PreferenceDecision(it.id, PreferenceJudgment.NEGATIVE, PreferenceSource.HUMAN_REMOVE) }
        val samples = (1..117).map { number ->
            LearningCase("video-$number", 1, null, veryLong, emptyList(),
                if (number == 1) decisions else decisions.take(1), "", "", emptyList())
        }
        val first = PreferenceLearningPlanner.leaves(samples, tags, emptyMap())
        val second = PreferenceLearningPlanner.leaves(samples, tags, emptyMap())
        assertTrue(first.all { it.caseIds.size <= 8 && it.input("node").preferenceBytes() <= 32 * 1024 })
        assertEquals(117, first.flatMap { it.caseIds }.distinct().size)
        assertEquals(decisions, first.flatMap { it.parts }.filter { it.caseId == "video-1" }.flatMap { it.decisions })
        assertEquals(first.map { PreferenceJson.hash(PreferenceJson.encode(it)) },
            second.map { PreferenceJson.hash(PreferenceJson.encode(it)) })
    }

    @Test
    fun ruleBudgetUsesWholeRulesAndCurrentVideoIsExcluded() = runBlocking {
        val tags = (1L..16).associateWith { TagEntity("标签$it", id = it) }
        val rules = tags.keys.map { id ->
            PreferenceRule("tag", listOf(id), "判断依据".repeat(50), emptyList(), emptyList(),
                emptyList(), emptyList(), emptyList(), emptyList(), false)
        }
        val ids = listOf("current", "a", "b", "c")
        val coverage = rules.associate { it.scopeKey to PreferenceRuleCoverage(ids, ids, emptyList(), emptyList(), emptyList()) }
        val context = PreferenceContextSelector.select(3, "current", rules,
            mapOf("tag:16" to PreferenceRuleOverride("我的覆写")), coverage, tags, setOf("标签3"), emptySet()) { id ->
            LearningCase(id, 0, null, id, listOf(1),
                listOf(PreferenceDecision(1, PreferenceJudgment.POSITIVE, PreferenceSource.HUMAN_ADD)), "", "", emptyList())
        }
        assertEquals(listOf("tag:16", "tag:3"), context.selected.take(2))
        assertTrue(context.selected.size <= 12)
        assertEquals(rules.size, context.selected.size + context.omitted.size)
        assertEquals(listOf("a", "b"), context.examples.map { it.desc })
        assertTrue(context.examples.all { it.evidenceImage == null })
    }

    @Test
    fun preparationClockKeepsStagesDisjointAndFreezesAtFirstCall() {
        var now = 0L
        val tracker = AiPreparationTracker { now * 1_000_000 }
        now = 4
        tracker.enter("等待")
        now = 17
        tracker.enter("解码")
        now = 30
        tracker.enter("编码")
        now = 45
        val result = tracker.finish()
        assertEquals(45L, result.totalMs)
        assertEquals(mapOf("等待" to 13L, "解码" to 13L, "编码" to 15L), result.stageMs)
        assertEquals(4L, result.otherMs)
        now = 400
        assertSame(result, tracker.finish(true))
    }

    @Test
    fun ownerCannotReleaseAnotherWorkItem() {
        val gate = com.blitz.downloader.service.AiWorkGate
        assertTrue(gate.acquire("learning-test"))
        try {
            gate.release("batch-test")
            assertFalse(gate.acquire("batch-test"))
        } finally { gate.release("learning-test") }
        assertTrue(gate.acquire("batch-test"))
        gate.release("batch-test")
    }
}
