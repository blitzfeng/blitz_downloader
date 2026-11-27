package com.blitz.downloader.data

import android.content.Context
import androidx.room.withTransaction
import com.blitz.downloader.config.AiService
import com.blitz.downloader.config.AppSettings
import com.blitz.downloader.data.db.AiPreferenceNodeEntity
import com.blitz.downloader.data.db.AiPreferenceRuleProfileEntity
import com.blitz.downloader.data.db.AiPreferenceRunEntity
import com.blitz.downloader.data.db.AppDatabase
import com.blitz.downloader.data.db.TagEntity
import com.blitz.downloader.llm.LlmProvider
import com.blitz.downloader.llm.MultimodalEvidenceSample
import com.blitz.downloader.llm.providers.GeminiProvider
import com.blitz.downloader.llm.providers.OpenAiProvider
import com.blitz.downloader.model.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PreferenceOverview(val cases: Int, val positive: Int, val negative: Int,
    val sources: Map<PreferenceSource, Int>, val photos: Int, val pending: Int, val activeVersion: Long?)
data class PreferencePlanPreview(val id: String, val cases: Int, val images: Int, val leaves: Int,
    val mergeEstimate: Long, val providers: List<PreferenceProvider>)
data class PreferenceSourceReview(val desc: String, val imagePath: String?, val decisions: List<PreferenceDecision>)

data class PreferenceReview(val profile: AiPreferenceRuleProfileEntity, val rules: List<PreferenceRule>,
    val overrides: Map<String, PreferenceRuleOverride>, val coverage: Map<String, PreferenceRuleCoverage>,
    val tagNames: Map<Long, String>)

internal data class PreferenceContext(val version: Long, val text: String, val selected: List<String>,
    val omitted: List<String>, val examples: List<MultimodalEvidenceSample>)

class AiPreferenceRepository(context: Context, internal val db: AppDatabase = AppDatabase.getInstance(context)) {
    internal val dao = db.aiPreferenceDao()
    internal val cases = AiPreferenceCases(db)
    private val appContext = context.applicationContext

    fun observeRuns() = dao.observeRuns()
    internal suspend fun hasUncertainNode(runId: String): Boolean = dao.hasUncertainNode(runId)
    fun observeProfiles() = dao.observeProfiles()
    internal fun providers(): List<LlmProvider> = if (!AppSettings.isAiSuggestionEnabled(appContext)) emptyList()
        else AppSettings.getAiConnections(appContext).filter { it.validationError == null }
            .map { if (it.service == AiService.OPENAI) OpenAiProvider(it) else GeminiProvider(it) }

    fun serviceConfigured(): Boolean = providers().isNotEmpty()

    internal suspend fun compatibleLegacySummary(): String? = dao.legacySummary()

    suspend fun overview(): PreferenceOverview = withContext(Dispatchers.IO) {
        val tags = db.tagDao().getAllEntities()
        val visible = cases.all(tags)
        val active = compatibleActive(tags)
        val last = dao.lastActivated()?.let { manifest(it.manifestJson) }
        val previous = last?.cases.orEmpty().associateBy { it.id }
        val decisions = visible.flatMap { it.decisions }
        PreferenceOverview(
            visible.size, decisions.count { it.source.isHuman && it.judgment == PreferenceJudgment.POSITIVE },
            decisions.count { it.source.isHuman && it.judgment == PreferenceJudgment.NEGATIVE },
            decisions.groupingBy { it.source }.eachCount(), visible.count { it.evidence != null },
            visible.count { previous[it.id] != it.stamp() }, active?.version,
        )
    }

    suspend fun createPlan(): PreferencePlanPreview = createPlan(providers().map { PreferenceProvider(it.providerId, it.modelId) })

    internal suspend fun createPlan(plan: List<PreferenceProvider>): PreferencePlanPreview = withContext(Dispatchers.IO) {
        check(plan.isNotEmpty()) { "请启用 AI 并配置可用服务" }
        val (tags, samples, old) = db.withTransaction {
            VideoTagRepository(appContext, db).backfillTagIds()
            val tags = db.tagDao().getAllEntities()
            Triple(tags, cases.all(tags), dao.lastActivated())
        }
        check(samples.isNotEmpty()) { "请先审核建议或手动纠正标签" }
        val overrides = old?.let { overrides(it) }.orEmpty().mapNotNull { (key, value) ->
            value.text?.takeIf { value.enabled }?.let { key to it }
        }.toMap()
        val leaves = PreferenceLearningPlanner.leaves(samples, tags, overrides).map { leaf ->
            leaf.copy(photos = leaf.photos.mapNotNull { photo ->
                val hash = AiPreferenceCases.fileFingerprint(photo.path)
                if (hash != null && PreferenceLearningPlanner.loadPhoto(photo) != null) photo.copy(fingerprint = hash) else null
            })
        }
        val id = UUID.randomUUID().toString()
        val usedIds = leaves.flatMap { it.tags }.map { it.id }.toSet() +
            overrides.keys.flatMap { it.substringAfter(':').split(',').mapNotNull(String::toLongOrNull) }
        val manifest = PreferenceManifest(samples.map { it.stamp() },
            AiPreferenceCases.tagFingerprints(tags).filterKeys { it in usedIds }, PreferenceLearningPolicy.VERSION,
            old?.let { overrides(it) }.orEmpty())
        val now = System.currentTimeMillis()
        db.withTransaction {
            dao.saveRun(AiPreferenceRunEntity(id, "PLANNED", PreferenceJson.encode(manifest),
                PreferenceLearningPolicy.VERSION, PreferenceJson.encode(plan), leaves.size, 0, 0, null, now, now))
            leaves.forEachIndexed { index, leaf ->
                val input = PreferenceJson.encode(leaf)
                dao.saveNode(AiPreferenceNodeEntity(
                    "$id-leaf-${index.toString().padStart(6, '0')}", id, "LEAF",
                    PreferenceJson.hash(input), input, "[]", "PENDING", null, null,
                ))
            }
        }
        PreferencePlanPreview(id, samples.size, leaves.sumOf { it.photos.size }, leaves.size,
            ((leaves.size - 1).coerceAtLeast(0).toLong() * maxOf(1, usedIds.size)), plan)
    }

    suspend fun recoverInterrupted() = db.withTransaction {
        dao.interruptRuns()
        dao.interruptNodes()
    }

    suspend fun review(version: Long): PreferenceReview? {
        val profile = dao.profile(version) ?: return null
        val names = db.tagDao().getAllEntities().associate { it.id to it.tagName }
        return PreferenceReview(profile, rules(profile), overrides(profile),
            PreferenceJson.decode<Map<String, PreferenceRuleCoverage>>(profile.coverageJson).orEmpty(), names)
    }

    suspend fun sourceDetails(ids: List<String>): Map<String, PreferenceSourceReview> = withContext(Dispatchers.IO) {
        val tags = db.tagDao().getAllEntities()
        ids.mapNotNull { id -> cases.get(id, tags)?.let { source ->
            id to PreferenceSourceReview(source.desc, source.evidence?.let { AiPreferenceCases.resolve(it).path }, source.decisions)
        } }.toMap()
    }

    suspend fun editRule(version: Long, key: String, override: PreferenceRuleOverride) = db.withTransaction {
        val profile = checkNotNull(dao.profile(version))
        check(profile.status == "DRAFT") { "仅可编辑规则草稿" }
        check(rules(profile).any { it.scopeKey == key }) { "规则不存在" }
        val text = override.text?.trim()?.takeIf(String::isNotEmpty)
        require(text == null || text.codePointCount(0, text.length) <= PreferenceLearningPolicy.MAX_RULE_LENGTH) {
            "规则正文最多 512 个字符"
        }
        dao.saveProfile(profile.copy(overridesJson = PreferenceJson.encode(
            overrides(profile) + (key to override.copy(text = text)),
        )))
    }

    suspend fun activate(version: Long): Boolean = withContext(Dispatchers.IO) {
        db.withTransaction {
            val profile = dao.profile(version) ?: return@withTransaction false
            if (profile.status !in setOf("DRAFT", "ARCHIVED", "ACTIVE")) return@withTransaction false
            if (!profileCompatible(profile, db.tagDao().getAllEntities())) {
                dao.saveProfile(profile.copy(status = "INVALID"))
                return@withTransaction false
            }
            val overrides = overrides(profile)
            if (rules(profile).none { overrides[it.scopeKey]?.enabled != false } ||
                rules(profile).any { rule -> rule.insufficientEvidence &&
                    overrides[rule.scopeKey]?.let { it.enabled && !it.reviewed } != false }) return@withTransaction false
            dao.archiveActive()
            dao.saveProfile(profile.copy(status = "ACTIVE", activatedAtMillis = System.currentTimeMillis()))
            true
        }
    }

    private suspend fun compatibleActive(tags: List<TagEntity>): AiPreferenceRuleProfileEntity? = db.withTransaction {
        val active = dao.active() ?: return@withTransaction null
        if (profileCompatible(active, tags)) return@withTransaction active
        dao.saveProfile(active.copy(status = "INVALID"))
        null
    }

    internal suspend fun context(currentId: String, tags: List<TagEntity>, existing: Set<String>,
        frequent: Set<String>, selecting: () -> Unit = {}): PreferenceContext? = withContext(Dispatchers.IO) {
        val profile = compatibleActive(db.tagDao().getAllEntities()) ?: return@withContext null
        val candidates = tags.filter { it.enableAi && it.id > 0 }.associateBy { it.id }
        val overrides = overrides(profile)
        val allRules = rules(profile)
        val coverage = PreferenceJson.decode<Map<String, PreferenceRuleCoverage>>(profile.coverageJson).orEmpty()
        val preferred = tags.filter { it.tagName in existing }.flatMap { listOf(it.tagName, it.parentTagName) }.toSet()
        val author = tags.filter { it.tagName in frequent }.flatMap { listOf(it.tagName, it.parentTagName) }.toSet()
        selecting()
        PreferenceContextSelector.select(profile.version, currentId, allRules, overrides, coverage,
            candidates, preferred, author) { cases.get(it, tags) }
    }

    private suspend fun profileCompatible(profile: AiPreferenceRuleProfileEntity, tags: List<TagEntity>): Boolean =
        manifest(profile.manifestJson)?.let { cases.compatible(it, tags) } == true

    internal fun manifest(text: String): PreferenceManifest? = PreferenceJson.decode<PreferenceManifest>(text)?.let {
        runCatching { it.takeIf { value -> value.strategyVersion == PreferenceLearningPolicy.VERSION &&
            value.cases.isNotEmpty() && value.tags.isNotEmpty() } }.getOrNull()
    }

    private fun rules(profile: AiPreferenceRuleProfileEntity): List<PreferenceRule> =
        PreferenceJson.decode<List<PreferenceRule>>(profile.rulesJson).orEmpty()

    internal fun overrides(profile: AiPreferenceRuleProfileEntity): Map<String, PreferenceRuleOverride> =
        PreferenceJson.decode<Map<String, PreferenceRuleOverride>>(profile.overridesJson).orEmpty()
}
