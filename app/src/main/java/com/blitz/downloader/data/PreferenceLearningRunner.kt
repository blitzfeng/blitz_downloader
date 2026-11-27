package com.blitz.downloader.data

import androidx.room.withTransaction
import com.blitz.downloader.data.db.AiPreferenceNodeEntity
import com.blitz.downloader.data.db.AiPreferenceRuleProfileEntity
import com.blitz.downloader.llm.LlmProvider
import com.blitz.downloader.llm.LlmResponseException
import com.blitz.downloader.llm.PreferenceLearningRequest
import com.blitz.downloader.model.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class PreferenceLearningRunner(
    private val repository: AiPreferenceRepository,
    private val providerResolver: () -> List<LlmProvider> = repository::providers,
) {
    private val dao = repository.dao
    private val db = repository.db

    private data class Derived(val rule: PreferenceRule, val caseIds: List<String>, val evidenceIds: List<String>)
    private data class Checkpoint(val cases: List<String>, val derived: List<Derived>)
    private class Stale : Exception("学习资料或模型已变化，请重新生成计划")
    private class Uncertain : Exception("请求可能已计费。继续前请确认重试")
    private class Failed(val messageForUser: String) : Exception(messageForUser)

    suspend fun run(id: String, retryUncertain: Boolean, cancelledByUser: () -> Boolean, progress: (Int, Int, Int) -> Unit) {
        val saved = db.withTransaction {
            val candidate = dao.run(id) ?: return@withTransaction null
            if (!retryUncertain && dao.hasUncertainNode(id)) return@withTransaction null
            val started = dao.beginRun(id, System.currentTimeMillis())
            candidate.takeIf { started > 0 }
        } ?: return
        val sources = repository.manifest(saved.manifestJson)
        val plan = PreferenceJson.decode<List<PreferenceProvider>>(saved.providerPlanJson)
        val providers by lazy(providerResolver)
        var current: AiPreferenceNodeEntity? = null
        val processed = mutableSetOf<String>()
        val successes = mutableSetOf<String>()

        suspend fun status(status: String, error: String? = null) {
            val latest = dao.run(id) ?: return
            if (latest.status == "STALE" && status != "STALE") return
            val total = dao.nodeCount(id)
            val completed = maxOf(latest.completedNodeCount, successes.size)
            val covered = maxOf(latest.processedCaseCount, processed.size)
            dao.saveRun(latest.copy(status = status, completedNodeCount = completed,
                plannedNodeCount = total, processedCaseCount = covered, lastError = error,
                updatedAtMillis = System.currentTimeMillis()))
            progress(completed, total, covered)
        }

        suspend fun validate(): PreferenceManifest {
            if (dao.run(id)?.status == "STALE" || sources == null || plan.isNullOrEmpty() ||
                saved.strategyVersion != PreferenceLearningPolicy.VERSION ||
                !repository.cases.compatible(sources, db.tagDao().getAllEntities())) throw Stale()
            val configured = providerResolver()
            if (configured.isEmpty() || plan.any { target -> configured.none { it.providerId == target.providerId && it.modelId == target.modelId } }) {
                throw Stale()
            }
            return sources
        }

        suspend fun checkpoint(
            node: AiPreferenceNodeEntity,
            stage: String,
            request: () -> PreferenceLearningRequest,
            derive: (List<PreferenceRule>) -> Checkpoint,
        ): Checkpoint {
            currentCoroutineContext().ensureActive()
            if (node.status == "SUCCEEDED") {
                val loaded = checkNotNull(PreferenceJson.decode<Checkpoint>(node.outputJson.orEmpty()))
                successes += node.nodeId
                processed += loaded.cases
                return loaded
            }
            if (node.status == "UNCERTAIN" && !retryUncertain) throw Uncertain()
            if (dao.run(id)?.status == "STALE") throw Stale()
            val input = request()
            val active = node.copy(status = "RUNNING")
            val attempts = mutableListOf<Map<String, Any?>>()
            val result = withTimeoutOrNull(PreferenceLearningPolicy.TIMEOUT_MILLIS) {
                for (target in plan.orEmpty()) {
                    currentCoroutineContext().ensureActive()
                    // 准备完本地图片后校验完整清单；每次降级发送前也重新核对。
                    validate()
                    val provider: LlmProvider = providers.first { it.providerId == target.providerId && it.modelId == target.modelId }
                    if (current == null) {
                        db.withTransaction {
                            if (dao.run(id)?.status == "STALE") throw Stale()
                            dao.saveNode(active)
                            status(stage)
                        }
                        current = active
                    }
                    val start = System.nanoTime()
                    val response = provider.learnPreference(input)
                    val error = response.exceptionOrNull()
                    if (error is CancellationException) throw error
                    attempts += mapOf("provider" to target.providerId, "model" to target.modelId,
                        "elapsedMs" to (System.nanoTime() - start) / 1_000_000,
                        "usage" to (response.getOrNull()?.diagnostics ?: (error as? LlmResponseException)?.diagnostics)?.tokenUsage,
                        "errorType" to error?.javaClass?.simpleName)
                    if (error == null) return@withTimeoutOrNull derive(response.getOrThrow().rules)
                    dao.saveNode(active.copy(diagnosticsJson = PreferenceJson.encode(attempts)))
                    if (error !is IOException && (error as? LlmResponseException)?.retryable != true) {
                        throw Failed("服务拒绝请求、配置无效或响应格式无效，请检查服务设置")
                    }
                }
                throw Failed("学习服务网络请求失败或已限流，可重试")
            } ?: throw if (current != null || dao.hasUncertainNode(id)) Uncertain()
                else Failed("本批次校验超时，请重试")
            db.withTransaction {
                dao.saveNode(active.copy(status = "SUCCEEDED", outputJson = PreferenceJson.encode(result),
                    diagnosticsJson = PreferenceJson.encode(attempts)))
                successes += node.nodeId
                processed += result.cases
                status(stage)
            }
            current = null
            return result
        }

        try {
            val manifest = validate()
            val leaves = dao.nodes(id).filter { it.stage == "LEAF" }
            val knownMerges = dao.nodes(id).filter { it.stage == "MERGE" }.associateBy { it.inputHash }
            val groups = mutableMapOf<String, MutableList<Pair<String, Derived>>>()
            for (node in leaves) {
                val leaf = checkNotNull(PreferenceJson.decode<LearningLeaf>(node.inputJson))
                var evidenceIds = leaf.photos.map { it.caseId }
                val result = checkpoint(node, "RUNNING", {
                    val images = leaf.photos.mapNotNull { photo ->
                        if (AiPreferenceCases.fileFingerprint(photo.path) != photo.fingerprint) throw Stale()
                        PreferenceLearningPlanner.loadPhoto(photo)?.let { photo.caseId to it }
                    }
                    evidenceIds = images.map { it.first }
                    val text = leaf.copy(photos = leaf.photos.filter { it.caseId in evidenceIds }).input(node.nodeId)
                    check(text.preferenceBytes() <= PreferenceLearningPolicy.MAX_TEXT_BYTES)
                    PreferenceLearningRequest(text, images)
                }) { rules ->
                    val tagIds = leaf.tags.map { it.id }.toSet()
                    val derived = rules.map { rule ->
                        require(rule.tagIds.all { it in tagIds } &&
                            rule.sourceNodeIds.all { it == node.nodeId } &&
                            rule.representativeCaseIds.all { it in leaf.caseIds }) { "模型引用了批次外的来源" }
                        val related = leaf.parts.filter { part ->
                            rule.scope == "global" || part.caseId in rule.representativeCaseIds ||
                                part.decisions.any { it.tagId in rule.tagIds } || part.currentTagIds.any { it in rule.tagIds }
                        }.map { it.caseId }.distinct()
                        require(related.isNotEmpty()) { "规则没有关联案例" }
                        Derived(rule.copy(sourceNodeIds = listOf(node.nodeId)), related, evidenceIds.intersect(related.toSet()).sorted())
                    }
                    Checkpoint(leaf.caseIds, derived)
                }
                result.derived.forEach { groups.getOrPut(it.rule.scopeKey, ::mutableListOf).add(node.nodeId to it) }
            }

            val tags = db.tagDao().getAllEntities()
            val allCases = manifest.cases.associate { it.id to (repository.cases.get(it.id, tags) ?: throw Stale()) }
            val tagsByName = tags.associateBy { it.tagName }
            val tagParents = tags.associate { it.id to tagsByName[it.parentTagName]?.id }
            val finished = mutableListOf<Derived>()
            val overrides = manifest.overrides.mapValues { (_, edit) -> edit.copy(reviewed = edit.reviewed && edit.text != null) }
            for ((scope, initial) in groups.toSortedMap()) {
                var level = initial.toList()
                while (level.size > 1) {
                    val next = mutableListOf<Pair<String, Derived>>()
                    var index = 0
                    while (index < level.size) {
                        var children = level.drop(index).take(PreferenceLearningPolicy.MAX_CHILDREN)
                        val manual = overrides[scope]?.text
                        fun input() = PreferenceLearningPlanner.mergeInput(scope, children.map { it.first to it.second.rule }, manual)
                        while (children.size > 1 && input().preferenceBytes() > PreferenceLearningPolicy.MAX_TEXT_BYTES - 1024) {
                            children = children.dropLast(1)
                        }
                        index += children.size
                        if (children.size == 1) {
                            next += children.single()
                            continue
                        }
                        val text = input()
                        val hash = PreferenceJson.hash(text)
                        val node = knownMerges[hash] ?: AiPreferenceNodeEntity("$id-merge-$hash", id, "MERGE",
                            hash, text, PreferenceJson.encode(children.map { it.first }), "PENDING", null, null)
                        val childIds = children.map { it.first }
                        val relevant = children.flatMap { it.second.caseIds }.distinct().sorted()
                        val evidence = children.flatMap { it.second.evidenceIds }.distinct().sorted()
                        val shownCaseIds = children.flatMap { it.second.rule.representativeCaseIds }.toSet()
                        val result = checkpoint(node, "MERGING", { PreferenceLearningRequest(text, merging = true) }) { rules ->
                            val rule = rules.singleOrNull()
                            require(rule?.scopeKey == scope && rule.sourceNodeIds.all { it in childIds } &&
                                rule.representativeCaseIds.all { it in shownCaseIds }) { "合并结果的来源或规则作用域无效" }
                            val childNegative = children.flatMap { it.second.rule.negativeSignals }.distinct()
                            val merged = if (rule.negativeSignals.isEmpty() && childNegative.isNotEmpty()) {
                                val candidate = rule.copy(negativeSignals = childNegative.take(1))
                                if (candidate.body.codePointCount(0, candidate.body.length) <= PreferenceLearningPolicy.MAX_RULE_LENGTH) candidate else rule
                            } else rule
                            Checkpoint(emptyList(), listOf(Derived(merged.copy(sourceNodeIds = childIds), relevant, evidence)))
                        }
                        next += node.nodeId to result.derived.single()
                    }
                    check(next.size < level.size) { "合并摘要超过预算" }
                    level = next
                }
                finished += level.single().second
            }

            check(processed == allCases.keys) { "案例覆盖未完成" }
            check(finished.isNotEmpty()) { "学习返回空规则，无法生成草稿" }
            val coverage = finished.associate { derived ->
                val relevant = derived.caseIds.mapNotNull(allCases::get)
                val rule = derived.rule
                fun decisions(case: LearningCase) = case.decisions.filter {
                    rule.scope == "global" || it.tagId in rule.tagIds ||
                        (rule.scope == "category" && tagParents[it.tagId] in rule.tagIds)
                }
                fun ids(judgment: PreferenceJudgment) = relevant.filter { case ->
                    decisions(case).any { it.source.isHuman && it.judgment == judgment }
                }.map { it.id }
                rule.scopeKey to PreferenceRuleCoverage(derived.caseIds, ids(PreferenceJudgment.POSITIVE),
                    ids(PreferenceJudgment.NEGATIVE), relevant.filter { case -> decisions(case).any { !it.source.isHuman } }.map { it.id },
                    derived.evidenceIds)
            }
            val generatedRules = finished.map { derived ->
                val rule = derived.rule
                val counts = coverage.getValue(rule.scopeKey)
                val manual = (counts.positiveIds + counts.negativeIds).distinct()
                rule.copy(insufficientEvidence = rule.insufficientEvidence || manual.size < 2 || counts.evidenceIds.isEmpty(),
                    representativeCaseIds = (counts.negativeIds.take(2) + counts.positiveIds.take(2) +
                        rule.representativeCaseIds + counts.unknownIds + counts.caseIds).distinct()
                        .take(PreferenceLearningPolicy.MAX_REPRESENTATIVES))
            }
            val generatedKeys = generatedRules.map { it.scopeKey }.toSet()
            val retained = overrides.filterKeys { it !in generatedKeys }.mapNotNull { (key, edit) ->
                val text = edit.text?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val scope = key.substringBefore(':')
                val ids = key.substringAfter(':').split(',').mapNotNull(String::toLongOrNull)
                if (scope !in setOf("global", "category", "tag") || ids.any { it !in manifest.tags }) return@mapNotNull null
                PreferenceRule(scope, ids, text, emptyList(), emptyList(), emptyList(), emptyList(),
                    emptyList(), emptyList(), insufficientEvidence = false)
            }
            val rules = generatedRules + retained
            db.withTransaction {
                validate()
                dao.saveProfile(AiPreferenceRuleProfileEntity(runId = id, status = "DRAFT", rulesJson = PreferenceJson.encode(rules),
                    manifestJson = saved.manifestJson, overridesJson = PreferenceJson.encode(overrides),
                    coverageJson = PreferenceJson.encode(coverage), createdAtMillis = System.currentTimeMillis(), activatedAtMillis = null))
                status("READY_FOR_REVIEW")
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { db.withTransaction {
                dao.nodes(id).filter { it.status == "RUNNING" }.forEach { dao.saveNode(it.copy(status = "UNCERTAIN")) }
                val userCancelled = cancelledByUser()
                val message = when {
                    dao.hasUncertainNode(id) -> "请求可能已计费，请确认后重试"
                    !userCancelled -> "学习服务已中断，完成的批次已保留"
                    else -> null
                }
                status(if (userCancelled) "CANCELLED" else "INTERRUPTED", message)
            } }
            throw cancelled
        } catch (error: Exception) {
            db.withTransaction {
                dao.nodes(id).filter { it.status == "RUNNING" }.forEach {
                    dao.saveNode(it.copy(status = if (error is Uncertain || error is Stale) "UNCERTAIN" else "FAILED"))
                }
                when (error) {
                    is Stale -> status("STALE", error.message)
                    is Uncertain -> status("INTERRUPTED", error.message)
                    is Failed -> status("FAILED", error.messageForUser)
                    else -> status("FAILED", "学习资料或模型输出不符合要求")
                }
            }
        }
    }
}
