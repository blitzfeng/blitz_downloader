package com.blitz.downloader.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blitz.downloader.data.db.AppDatabase
import com.blitz.downloader.data.db.DownloadedVideoEntity
import com.blitz.downloader.data.db.TagEntity
import com.blitz.downloader.llm.*
import com.blitz.downloader.model.*
import com.google.gson.JsonParser
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiPreferenceLearningIntegrationTest {
    private class FakeProvider : LlmProvider {
        override val providerId = "fake"
        override val modelId = "fixture"
        var calls = 0
        var failAt = 2
        var suspendAt = -1
        val requestStarted = CompletableDeferred<Unit>()
        override suspend fun generateTagSuggestion(request: TagSuggestionRequest): Result<TagSuggestionResponse> =
            error("测试不执行日常分析")
        override suspend fun summarizePreference(request: PreferenceSummaryRequest): Result<String> =
            error("禁止自动摘要")
        override suspend fun testConnection(): Result<String> = Result.success("fixture")

        override suspend fun learnPreference(request: PreferenceLearningRequest): Result<PreferenceLearningResponse> {
            calls++
            if (calls == suspendAt) {
                requestStarted.complete(Unit)
                awaitCancellation()
            }
            if (calls == failAt) return Result.failure(IOException("模拟网络中断"))
            val input = JsonParser.parseString(request.input).asJsonObject
            val source = input.get("sourceNodeId")?.asString?.let(::listOf)
                ?: input.getAsJsonArray("children").map { it.asJsonObject.get("nodeId").asString }
            val cases = input.getAsJsonArray("cases")?.map { it.asJsonObject.get("caseId").asString }
                ?: emptyList()
            return Result.success(PreferenceLearningResponse(
                listOf(PreferenceRule("tag", listOf(901), "判断标准", listOf("支持"), listOf("拒绝"),
                    listOf("替换"), listOf("共存"), source, cases.distinct().take(8), insufficientEvidence = true)),
                LlmResponseDiagnostics("", """{"input_tokens":10}"""),
            ))
        }
    }

    @Test
    fun localOperationKeepsOtherSourcesAndTracksTagSemantics() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = AiPreferenceRepository(context, db)
            val tagRepo = VideoTagRepository(context, db)
            val id = "source-fixture"
            db.downloadedVideoDao().insert(DownloadedVideoEntity(awemeId = id, downloadType = "post", userName = "测试作者"))
            db.tagDao().insert(TagEntity("收藏夹", id = 901))
            db.tagDao().insert(TagEntity("人工", id = 902))
            db.tagDao().insert(TagEntity("跳过", id = 903))
            tagRepo.ensureCollectFolderTagLinked(id, "收藏夹")
            tagRepo.addTagsAsUserEdit(listOf(id), listOf("人工"))
            tagRepo.setTagsAsUserEdit(id, listOf("收藏夹", "人工"))
            val initial = checkNotNull(repo.dao.findCase(id))
            val decisions = PreferenceJson.decisions(initial.judgmentsJson).associateBy { it.tagId }
            assertEquals(PreferenceSource.AUTO_EXISTING, decisions[901]?.source)
            assertEquals(PreferenceSource.HUMAN_ADD, decisions[902]?.source)
            assertEquals(1, repo.overview().positive)

            val skipped = mapOf("跳过" to (PreferenceJudgment.UNKNOWN to PreferenceSource.SKIPPED))
            db.withTransaction { repo.cases.update(id, skipped) }
            val beforeUndo = checkNotNull(repo.dao.findCase(id))
            db.withTransaction { repo.cases.update(id, skipped, forceRevision = true) }
            assertEquals(beforeUndo.revision + 1, repo.dao.findCase(id)?.revision)
            assertEquals(0, repo.overview().negative)

            val plan = repo.createPlan(listOf(PreferenceProvider("fake", "fixture")))
            tagRepo.renameTag("人工", "改名")
            val manifest = checkNotNull(repo.manifest(checkNotNull(repo.dao.run(plan.id)).manifestJson))
            assertTrue(repo.cases.compatible(manifest, db.tagDao().getAllEntities()))
            tagRepo.setTagDescription("改名", "修改标签定义")
            assertEquals("STALE", repo.dao.run(plan.id)?.status)
        } finally { db.close() }
    }

    @Test
    fun interruptionKeepsCompletedLeafAndResendsOnlyAfterConfirmation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = AiPreferenceRepository(context, db)
            val tagRepo = VideoTagRepository(context, db)
            db.tagDao().insert(TagEntity("测试标签", id = 901))
            for (number in 1..9) {
                val id = "interrupt-$number"
                db.downloadedVideoDao().insert(DownloadedVideoEntity(awemeId = id, downloadType = "post", userName = "测试"))
                tagRepo.addTagsAsUserEdit(listOf(id), listOf("测试标签"))
            }
            val fake = FakeProvider().apply { failAt = -1; suspendAt = 2 }
            val plan = repo.createPlan(listOf(PreferenceProvider(fake.providerId, fake.modelId)))
            val runner = PreferenceLearningRunner(repo) { listOf(fake) }
            val work = launch { runner.run(plan.id, false, { false }) { _, _, _ -> } }
            withTimeout(8_000) { fake.requestStarted.await() }
            work.cancelAndJoin()

            assertEquals("INTERRUPTED", repo.dao.run(plan.id)?.status)
            assertEquals(1, repo.dao.run(plan.id)?.completedNodeCount)
            assertTrue(repo.hasUncertainNode(plan.id))
            val completed = repo.dao.nodes(plan.id).single { it.status == "SUCCEEDED" }
            assertNull(repo.dao.active())

            runner.run(plan.id, false, { false }) { _, _, _ -> }
            assertEquals(2, fake.calls)
            runner.run(plan.id, true, { false }) { _, _, _ -> }
            assertEquals(4, fake.calls) // 两个首次尝试，仅重试第二批，然后合并。
            assertEquals("READY_FOR_REVIEW", repo.dao.run(plan.id)?.status)
            assertEquals(completed, repo.dao.nodes(plan.id).single { it.nodeId == completed.nodeId })
            assertFalse(repo.hasUncertainNode(plan.id))
        } finally { db.close() }
    }

    @Test
    fun resumeSkipsSuccessfulLeafAndRequiresReviewBeforeActivation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = AiPreferenceRepository(context, db)
            val tagRepo = VideoTagRepository(context, db)
            db.tagDao().insert(TagEntity("测试标签", id = 901))
            for (number in 1..105) {
                val video = DownloadedVideoEntity(awemeId = "video-$number", downloadType = "post", userName = "同作者", desc = "同文案")
                db.downloadedVideoDao().insert(video)
                tagRepo.addTagsAsUserEdit(listOf(video.awemeId), listOf("测试标签"))
            }
            val fake = FakeProvider()
            val plan = repo.createPlan(listOf(PreferenceProvider(fake.providerId, fake.modelId)))
            assertEquals(105, plan.cases)
            assertEquals(14, plan.leaves)
            db.downloadedVideoDao().insert(DownloadedVideoEntity(awemeId = "unrelated", downloadType = "post", userName = "新作者"))
            tagRepo.addTagsAsUserEdit(listOf("unrelated"), listOf("测试标签"))
            assertEquals("PLANNED", repo.dao.run(plan.id)?.status)
            val runner = PreferenceLearningRunner(repo) { listOf(fake) }
            runner.run(plan.id, false, { false }) { _, _, _ -> }
            assertEquals("FAILED", repo.dao.run(plan.id)?.status)
            assertEquals(1, repo.dao.nodes(plan.id).count { it.status == "SUCCEEDED" })
            val successful = repo.dao.nodes(plan.id).single { it.status == "SUCCEEDED" }
            fake.failAt = -1
            runner.run(plan.id, false, { false }) { _, _, _ -> }
            assertEquals("READY_FOR_REVIEW", repo.dao.run(plan.id)?.status)
            assertEquals(105, repo.dao.run(plan.id)?.processedCaseCount)
            assertEquals(successful, repo.dao.nodes(plan.id).single { it.nodeId == successful.nodeId })
            assertNull(repo.dao.active())
            val draft = repo.dao.usableProfiles().single()
            assertFalse(repo.activate(draft.version))
            val coverage = repo.review(draft.version)?.coverage?.get("tag:901")
            assertEquals(105, coverage?.positiveIds?.size)
            repo.editRule(draft.version, "tag:901", PreferenceRuleOverride(reviewed = true))
            assertTrue(repo.activate(draft.version))
            assertEquals(draft.version, repo.dao.active()?.version)
            assertEquals(1, repo.overview().pending)

            db.withTransaction {
                db.aiReferenceDao().setExcluded(listOf("video-1"), true, 1000)
                PreferenceSourceInvalidation(db).casesChanged(setOf("video-1"))
            }
            db.aiReferenceDao().setExcluded(listOf("video-1"), false, 1001)
            assertFalse(repo.activate(draft.version))
            assertEquals("INVALID", repo.dao.profile(draft.version)?.status)
        } finally { db.close() }
    }
}
