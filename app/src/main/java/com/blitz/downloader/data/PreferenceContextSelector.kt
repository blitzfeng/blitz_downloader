package com.blitz.downloader.data

import com.blitz.downloader.data.db.TagEntity
import com.blitz.downloader.llm.MultimodalEvidenceSample
import com.blitz.downloader.model.*

internal object PreferenceContextSelector {
    suspend fun select(
        version: Long, currentId: String, allRules: List<PreferenceRule>,
        overrides: Map<String, PreferenceRuleOverride>, coverage: Map<String, PreferenceRuleCoverage>,
        candidates: Map<Long, TagEntity>, preferred: Set<String>, author: Set<String>,
        findCase: suspend (String) -> LearningCase?,
    ): PreferenceContext {
        val order = allRules.sortedWith(compareByDescending<PreferenceRule> { overrides[it.scopeKey]?.text != null }
            .thenByDescending { it.tagIds.any { id -> candidates[id]?.tagName in preferred } }
            .thenByDescending { it.tagIds.any { id -> candidates[id]?.tagName in author } }
            .thenByDescending { coverage[it.scopeKey]?.negativeIds?.size ?: 0 }
            .thenBy { it.tagIds.firstOrNull() ?: 0 }
            .thenBy { it.scopeKey })
        val selected = mutableListOf<PreferenceRule>()
        val lines = mutableListOf<String>()
        var globalBytes = 0
        var tagBytes = 0
        var tagCount = 0
        for (rule in order) {
            val edit = overrides[rule.scopeKey]
            if (edit?.enabled == false || rule.tagIds.any { it !in candidates }) continue
            val label = rule.tagIds.joinToString("、") { candidates.getValue(it).tagName }.ifBlank { "全局" }
            val line = "$label · ${edit?.text ?: rule.body}\n"
            val bytes = line.preferenceBytes()
            if (rule.scope == "global") {
                if (globalBytes + bytes > PreferenceLearningPolicy.GLOBAL_RULE_BYTES) continue
                globalBytes += bytes
            } else {
                if (tagBytes + bytes > PreferenceLearningPolicy.TAG_RULE_BYTES ||
                    tagCount >= PreferenceLearningPolicy.TAG_RULE_COUNT) continue
                tagBytes += bytes
                tagCount++
            }
            selected += rule
            lines += line
        }
        val weights = selected.flatMap { coverage[it.scopeKey]?.caseIds.orEmpty() }.groupingBy { it }.eachCount()
        val possible = weights.keys.filter { it != currentId }.mapNotNull { findCase(it) }
            .filter { it.decisions.any { decision -> decision.source.isHuman } }
            .sortedWith(compareByDescending<LearningCase> { weights[it.id] }
                .thenByDescending { it.decisions.maxOfOrNull(PreferenceDecision::modifiedAtMillis) }.thenBy { it.id })
        val examples = mutableListOf<MultimodalEvidenceSample>()
        var exampleBytes = 0
        for (case in possible) {
            val judgments = case.decisions.filter { it.source.isHuman }
            val accepted = judgments.filter { it.judgment == PreferenceJudgment.POSITIVE }.mapNotNull { candidates[it.tagId]?.tagName }
            val rejected = judgments.filter { it.judgment == PreferenceJudgment.NEGATIVE }.mapNotNull { candidates[it.tagId]?.tagName }
            if (accepted.isEmpty() && rejected.isEmpty()) continue
            val candidate = MultimodalEvidenceSample(case.desc, accepted, rejected)
            val bytes = PreferenceJson.encode(candidate).preferenceBytes()
            if (exampleBytes + bytes > PreferenceLearningPolicy.EXAMPLE_BYTES) continue
            examples += candidate
            exampleBytes += bytes
            if (examples.size == PreferenceLearningPolicy.EXAMPLE_COUNT) break
        }
        val chosen = selected.map { it.scopeKey }
        return PreferenceContext(version, "本地偏好规则版本 $version。以下规则仅供参考，以词表与当前画面为准。\n" +
            lines.joinToString(""), chosen, allRules.map { it.scopeKey } - chosen.toSet(), examples)
    }
}
