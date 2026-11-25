package com.blitz.downloader.llm

import com.blitz.downloader.data.db.TagEntity

data class TagChangeSuggestion(
    val tagName: String,
    val action: String,
    val replacedTags: Set<String>,
    val relatedTags: Set<String>,
    val reason: String,
) {
    fun description(): String = when (action) {
        "replace" -> "建议替换：${replacedTags.joinToString("、")} → $tagName\n依据：$reason"
        "coexist" -> "建议共存：${(relatedTags + tagName).joinToString("、")}\n依据：$reason"
        else -> ""
    }
}

object TagChangeSuggestions {
    /** 替换只能指向本条视频已有的同类互斥标签；共存必须有明确依据且至多两项。 */
    fun validate(
        candidates: List<TagCandidate>,
        tags: List<TagEntity>,
        existingNames: Set<String>,
    ): List<TagCandidate> {
        val byId = tags.associateBy { it.id }
        val exclusiveParents = tags.filter { it.isExclusive }.map { it.tagName }.toSet()
        val existingIds = tags.filter { it.tagName in existingNames }.map { it.id }.toSet()
        val normalized = candidates.map { candidate ->
            val target = byId[candidate.tagId]
            if (candidate.action == "replace") {
                require(candidate.changeReason.isNotBlank()) { "AI 替换建议缺少依据" }
                require(candidate.replacesTagIds.isNotEmpty()) { "AI 替换建议未指定已有标签" }
                require(candidate.replacesTagIds.all { id ->
                    val old = byId[id]
                    id in existingIds && id != candidate.tagId && old != null && target != null &&
                        target.parentTagName in exclusiveParents && old.parentTagName == target.parentTagName
                }) { "AI 替换建议包含无效的已有标签或非互斥标签" }
                candidate
            } else {
                if (candidate.action == "coexist") require(candidate.changeReason.isNotBlank()) { "AI 共存建议缺少依据" }
                candidate.copy(replacesTagIds = emptyList())
            }
        }
        val removed = normalized.flatMap { it.replacesTagIds }.toSet()
        val kept = normalized.filterNot { it.tagId in removed }
        val finalIds = existingIds - removed + kept.map { it.tagId }
        for ((parent, suggested) in kept.groupBy { byId[it.tagId]?.parentTagName }) {
            if (parent !in exclusiveParents) continue
            val siblings = tags.filter { it.parentTagName == parent && it.id in finalIds }
            if (siblings.size > 1) {
                require(siblings.size <= 2 && suggested.all { it.action == "coexist" && it.changeReason.isNotBlank() }) {
                    "AI 建议存在互斥冲突，未明确提出有依据的双标签共存"
                }
            }
        }
        return kept.map { candidate ->
            if (candidate.action != "coexist") return@map candidate
            val parent = byId[candidate.tagId]?.parentTagName
            val pair = tags.filter { it.parentTagName == parent && it.id in finalIds }.map { it.id }
            require(parent in exclusiveParents && pair.size == 2) { "AI 共存建议未形成明确的双标签组合" }
            candidate.copy(coexistingTagIds = pair)
        }
    }

    fun describe(
        candidates: List<TagCandidate>,
        tags: List<TagEntity>,
    ): List<TagChangeSuggestion> {
        val byId = tags.associateBy { it.id }
        return candidates.filter { it.action in setOf("replace", "coexist") }.map { candidate ->
            val related = candidate.coexistingTagIds.mapNotNull { byId[it]?.tagName }.toSet()
            TagChangeSuggestion(
                candidate.tagName, candidate.action,
                candidate.replacesTagIds.mapNotNull { byId[it]?.tagName }.toSet(),
                related, candidate.changeReason,
            )
        }
    }

    fun apply(current: Set<String>, additions: Set<String>, changes: List<TagChangeSuggestion>): Set<String> {
        val removed = changes.filter { it.action == "replace" && it.tagName in additions }
            .flatMap { it.replacedTags }.toSet()
        return current - removed + additions
    }
}
