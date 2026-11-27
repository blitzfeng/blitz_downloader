package com.blitz.downloader.data

import androidx.room.withTransaction
import com.blitz.downloader.data.db.AiPreferenceCaseEntity
import com.blitz.downloader.data.db.AppDatabase
import com.blitz.downloader.data.db.TagEntity
import com.blitz.downloader.model.PreferenceCaseStamp
import com.blitz.downloader.model.PreferenceDecision
import com.blitz.downloader.model.PreferenceJson
import com.blitz.downloader.model.PreferenceJudgment
import com.blitz.downloader.model.PreferenceManifest
import com.blitz.downloader.model.PreferenceSource
import com.blitz.downloader.util.DownloadedMediaFileManager
import java.io.File

internal data class LearningCase(
    val id: String,
    val revision: Long,
    val analysisId: Long?,
    val desc: String,
    val currentTags: List<Long>,
    val decisions: List<PreferenceDecision>,
    val suggested: String,
    val visual: String,
    val evidencePaths: List<String>,
    val evidence: String? = null,
    val evidenceHash: String? = null,
) {
    fun stamp() = PreferenceCaseStamp(id, revision, PreferenceJson.hash(PreferenceJson.encode(copy(
        analysisId = null,
        decisions = decisions.map { it.copy(modifiedAtMillis = 0, analysisId = null) },
    ))))
}

internal class AiPreferenceCases(private val db: AppDatabase) {
    private val dao = db.aiPreferenceDao()
    private val invalidation = PreferenceSourceInvalidation(db)

    suspend fun all(tags: List<TagEntity>): List<LearningCase> = buildList {
        var after: String? = null
        while (true) {
            val page = dao.casePage(after, 64)
            if (page.isEmpty()) break
            page.forEach { id -> get(id, tags)?.let(::add) }
            after = page.last()
        }
    }

    suspend fun get(id: String, tags: List<TagEntity>): LearningCase? {
        if (dao.isExcluded(id)) return null
        return snapshot(id, tags, dao.findCase(id))?.takeIf { it.decisions.isNotEmpty() }
    }

    suspend fun update(
        id: String,
        changes: Map<String, Pair<PreferenceJudgment, PreferenceSource>>,
        analysisId: Long? = null,
        forceRevision: Boolean = false,
    ) {
        if (changes.isEmpty()) return
        val tags = db.tagDao().getAllEntities()
        val old = dao.findCase(id)
        val baseline = snapshot(id, tags, old) ?: return
        val now = System.currentTimeMillis()
        val decisions = baseline.decisions.associateBy { it.tagId }.toMutableMap()
        for (tag in tags) {
            val (judgment, source) = changes[tag.tagName] ?: continue
            if (tag.id <= 0) continue
            val previous = decisions[tag.id]
            if (!source.isHuman && source != PreferenceSource.SKIPPED && previous?.source?.isHuman == true) continue
            decisions[tag.id] = PreferenceDecision(tag.id, judgment, source, analysisId, now)
        }
        if (decisions.isEmpty()) return
        val nextAnalysis = analysisId?.let { db.videoAiAnalysisDao().getById(it) }
            ?.takeIf { it.awemeId == id }
        val suggested = nextAnalysis?.suggestedTagIds ?: baseline.suggested
        val visual = nextAnalysis?.let { dao.visual(it.id)?.profileJson }.orEmpty().ifEmpty { baseline.visual }
        val paths = (evidenceFromSuggestions(suggested, decisions.keys) + baseline.evidencePaths).distinct()
        val savedDecisions = decisions.values.sortedBy { it.tagId }
        val hash = PreferenceJson.hash(PreferenceJson.encode(listOf(
            savedDecisions.map { it.copy(modifiedAtMillis = 0, analysisId = null) }, suggested, visual, paths, baseline.desc,
        )))
        val changed = forceRevision || old?.contentHash != hash
        val revision = (old?.revision ?: 0) + if (changed) 1 else 0
        dao.saveCase(AiPreferenceCaseEntity(
            id, revision, nextAnalysis?.id ?: baseline.analysisId, PreferenceJson.encode(savedDecisions),
            suggested, visual, PreferenceJson.encode(paths), baseline.desc, hash, now,
        ))
        if (changed) invalidation.casesChanged(setOf(id))
    }

    private fun evidenceFromSuggestions(suggested: String, ids: Set<Long>): List<String> =
        SuggestedTagCodec.decode(suggested).values.filter { it.tagId in ids }.mapNotNull { it.evidencePath }
            .filter { it.isNotBlank() }

    private suspend fun snapshot(id: String, tags: List<TagEntity>, row: AiPreferenceCaseEntity?): LearningCase? {
        val video = db.downloadedVideoDao().getByAwemeId(id) ?: return null
        val ids = tags.filter { it.id > 0 }.associate { it.tagName to it.id }
        val validIds = ids.values.toSet()
        val current = db.videoTagDao().getTagsForVideo(id).mapNotNull(ids::get).sorted()
        val oldFeedback = if (row == null) dao.latestFeedback(id) else emptyList()
        val analysisId = row?.analysisId ?: oldFeedback.firstOrNull()?.analysisId
        val decisions = (row?.let { PreferenceJson.decisions(it.judgmentsJson) } ?: oldFeedback
            .associateBy { it.tagId }.values.map {
                PreferenceDecision(
                    it.tagId, PreferenceJudgment.UNKNOWN, PreferenceSource.LEGACY_UNKNOWN,
                    it.analysisId, it.createdAtMillis, it.kind,
                )
            }).filter { it.tagId in validIds }.sortedBy { it.tagId }
        val paths = (row?.let { PreferenceJson.decode<List<String>>(it.evidenceJson) }
            ?: oldFeedback.mapNotNull { it.evidenceImagePath }) + video.coverPath
        val candidates = paths.filter { it.isNotBlank() }.distinct()
        val evidence = candidates.firstNotNullOfOrNull { path -> imageStamp(path)?.let { path to it } }
        return LearningCase(
            id, row?.revision ?: 0, analysisId, video.desc, current, decisions,
            row?.suggestedJson ?: analysisId?.let { db.videoAiAnalysisDao().getById(it)?.suggestedTagIds }.orEmpty(),
            row?.visualJson ?: analysisId?.let { dao.visual(it)?.profileJson }.orEmpty(),
            candidates, evidence?.first, evidence?.second,
        )
    }

    suspend fun compatible(manifest: PreferenceManifest, tags: List<TagEntity>): Boolean {
        val fingerprints = tagFingerprints(tags)
        if (!manifest.tags.all { (id, hash) -> fingerprints[id] == hash }) return false
        return manifest.cases.all { stamp -> get(stamp.id, tags)?.stamp() == stamp }
    }

    companion object {
        fun tagFingerprints(tags: List<TagEntity>): Map<Long, String> {
            val ids = tags.associate { it.tagName to it.id }
            return tags.filter { it.id > 0 }.sortedBy { it.id }.associate { tag ->
                tag.id to PreferenceJson.hash(PreferenceJson.encode(listOf(
                    tag.description, ids[tag.parentTagName], tag.isExclusive, tag.enableAi,
                )))
            }
        }

        fun resolve(path: String): File = DownloadedMediaFileManager.resolveFile(path)

        private fun imageStamp(path: String): String? = runCatching {
            val file = resolve(path)
            if (!file.isFile || file.length() == 0L) null else "${file.length()}:${file.lastModified()}"
        }.getOrNull()

        fun fileFingerprint(path: String): String? = runCatching {
            val file = resolve(path)
            if (!file.isFile || file.length() == 0L) return null
            // 内容指纹用于识别同名替换，分块读取以限制内存占用。
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val size = stream.read(buffer)
                    if (size < 0) break
                    digest.update(buffer, 0, size)
                }
            }
            PreferenceJson.hex(digest.digest())
        }.getOrNull()
    }
}
