package com.blitz.downloader.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.blitz.downloader.data.db.TagEntity
import com.blitz.downloader.llm.ImagePart
import com.blitz.downloader.model.PreferenceDecision
import com.blitz.downloader.model.PreferenceJson
import com.blitz.downloader.model.PreferenceJudgment
import com.blitz.downloader.model.PreferenceLearningPolicy as Policy
import com.blitz.downloader.model.PreferenceRule
import com.blitz.downloader.model.preferenceBytes
import java.io.ByteArrayOutputStream

internal data class LearningCasePart(
    val caseId: String,
    val part: Int,
    val desc: String,
    val currentTagIds: List<Long>,
    val decisions: List<PreferenceDecision>,
    val originalSuggestions: Map<Long, List<Long>>,
    val visual: String,
)

internal data class LearningTag(val id: Long, val name: String, val description: String, val parentId: Long?, val exclusive: Boolean)
internal data class LearningPhoto(val caseId: String, val path: String, val fingerprint: String)
internal data class LearningLeaf(
    val parts: List<LearningCasePart>,
    val tags: List<LearningTag>,
    val photos: List<LearningPhoto>,
    val overrides: Map<String, String>,
) {
    val caseIds get() = parts.map { it.caseId }.distinct()
    fun input(nodeId: String): String = PreferenceJson.encode(mapOf(
        "sourceNodeId" to nodeId, "cases" to parts, "tags" to tags, "evidenceCaseIds" to photos.map { it.caseId },
        "missingOrOmittedEvidenceCaseIds" to caseIds.filterNot { id -> photos.any { it.caseId == id } },
        "manualCriteria" to overrides,
    ))
}

internal object PreferenceLearningPlanner {
    // 预留节点 ID 和提示文字的编码空间。
    private const val TEXT_LIMIT = Policy.MAX_TEXT_BYTES - 1024
    private const val PHOTO_ESTIMATE = ((Policy.MAX_IMAGE_BYTES + 2) / 3) * 4

    fun leaves(cases: List<LearningCase>, tags: List<TagEntity>, manual: Map<String, String>): List<LearningLeaf> {
        val byName = tags.associateBy { it.tagName }
        val byId = tags.filter { it.id > 0 }.associate { tag ->
            tag.id to LearningTag(tag.id, tag.tagName.take(256), tag.description.take(512),
                byName[tag.parentTagName]?.id?.takeIf { it > 0 }, tag.isExclusive)
        }
        val byCase = cases.associateBy { it.id }
        val usedPhotos = mutableSetOf<String>()
        fun leaf(parts: List<LearningCasePart>): LearningLeaf {
            val relevant = parts.flatMap { it.currentTagIds + it.decisions.map(PreferenceDecision::tagId) +
                it.originalSuggestions.keys + it.originalSuggestions.values.flatten() }.toSet()
            val definitions = (relevant.mapNotNull(byId::get) + relevant.mapNotNull { byId[it]?.parentId }
                .mapNotNull(byId::get)).distinctBy { it.id }.sortedBy { it.id }
            val photos = parts.map { byCase.getValue(it.caseId) }.distinctBy { it.id }
                .filter { it.id !in usedPhotos }
                .sortedWith(compareByDescending<LearningCase> { case ->
                    case.decisions.any { it.judgment == PreferenceJudgment.NEGATIVE || it.source.name in
                        setOf("HUMAN_ADD", "HUMAN_REPLACE", "HUMAN_COEXIST") }
                }.thenBy { it.id })
                .mapNotNull { case ->
                    val path = case.evidence ?: return@mapNotNull null
                    LearningPhoto(case.id, path, checkNotNull(case.evidenceHash))
                }.take(Policy.MAX_IMAGES)
            val overrides = manual.toSortedMap().filterKeys { key ->
                key.startsWith("global:") || key.substringAfter(':').split(',').any { it.toLongOrNull() in relevant }
            }.entries.take(4).associate { it.key to it.value }
            return LearningLeaf(parts, definitions, photos, overrides)
        }
        fun fits(candidate: LearningLeaf): Boolean =
            candidate.caseIds.size <= Policy.MAX_CASES && candidate.input("").preferenceBytes() <= TEXT_LIMIT &&
                candidate.input("").preferenceBytes() + candidate.photos.size * PHOTO_ESTIMATE <= Policy.MAX_BODY_BYTES

        val result = mutableListOf<LearningLeaf>()
        var buffer = emptyList<LearningCasePart>()
        fun flush() {
            if (buffer.isEmpty()) return
            val saved = leaf(buffer)
            result += saved
            usedPhotos += saved.photos.map { it.caseId }
            buffer = emptyList()
        }
        for (case in cases) {
            val suggestions = SuggestedTagCodec.decode(case.suggested).mapValues { (_, detail) ->
                (detail.replacesTagIds + detail.coexistingTagIds).distinct().sorted()
            }
            val allIds = (case.currentTags + case.decisions.map { it.tagId } + suggestions.keys).distinct().sorted()
            var partIndex = 0
            fun addPart(ids: List<Long>) {
                var part = LearningCasePart(case.id, partIndex,
                    case.desc.take(2300), case.currentTags.filter { it in ids },
                    case.decisions.filter { it.tagId in ids }, suggestions.filterKeys { it in ids },
                    case.visual.take(1700))
                if (!fits(leaf(listOf(part)))) {
                    if (ids.size > 1) {
                        val half = ids.size / 2
                        addPart(ids.take(half))
                        addPart(ids.drop(half))
                        return
                    }
                    part = part.copy(desc = part.desc.take(512), visual = part.visual.take(384))
                }
                if (buffer.isNotEmpty() && !fits(leaf(buffer + part))) flush()
                check(fits(leaf(listOf(part)))) { "案例资料超过单批预算" }
                buffer += part
                partIndex++
            }
            allIds.chunked(5).forEach(::addPart)
        }
        flush()
        return result
    }

    fun loadPhoto(photo: LearningPhoto): ImagePart? = runCatching {
        val path = AiPreferenceCases.resolve(photo.path).path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val side = maxOf(bounds.outWidth, bounds.outHeight)
        if (side <= 0) return null
        var sample = 1
        while (side / sample > Policy.MAX_IMAGE_SIDE * 2) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val ratio = (Policy.MAX_IMAGE_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
        val scaled = Bitmap.createScaledBitmap(bitmap, (ratio * bitmap.width).toInt().coerceAtLeast(1),
            (ratio * bitmap.height).toInt().coerceAtLeast(1), true)
        try {
            val bytes = ByteArrayOutputStream().use {
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 78, it)) return null
                it.toByteArray()
            }
            bytes.takeIf { it.size <= Policy.MAX_IMAGE_BYTES }?.let { ImagePart(it, false) }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }.getOrNull()

    fun mergeInput(scope: String, children: List<Pair<String, PreferenceRule>>, manual: String?): String =
        PreferenceJson.encode(mapOf("scope" to scope, "children" to children.map { (id, rule) ->
            mapOf("nodeId" to id, "rule" to rule.copy(sourceNodeIds = emptyList()))
        }, "manualCriteria" to manual))
}
