package com.blitz.downloader.data

import androidx.room.withTransaction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blitz.downloader.config.AppSettings
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.activity.BatchTagReviewActivity
import com.blitz.downloader.data.db.*
import com.blitz.downloader.viewmodel.BatchTagReviewViewModel
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a disposable emulator: seeds the app's database, with no paid AI requests. */
@RunWith(AndroidJUnit4::class)
class BatchTagReviewIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val db get() = BlitzApp.instance.database
    private var scenario: ActivityScenario<BatchTagReviewActivity>? = null
    private lateinit var vm: BatchTagReviewViewModel
    private val gson = Gson()

    private fun video(id: String, edits: Int = 0, media: String = "video") =
        DownloadedVideoEntity(awemeId = id, downloadType = "post", userName = id, tagEditCount = edits, mediaType = media)

    @Before fun setup() = runBlocking {
        db.clearAllTables()
        db.tagDao().insert(TagEntity(tagName = "review-a", id = 901))
        db.tagDao().insert(TagEntity(tagName = "review-b", id = 902))
    }

    @After fun cleanup() { scenario?.close(); db.clearAllTables() }

    private fun seed(allFailed: Boolean = false) = runBlocking {
        val videos = listOf(video("success"), video("failure"), video("empty"), video("edited", 1), video("image", media = "image"))
        videos.forEach { db.downloadedVideoDao().insert(it) }
        val batchId = db.downloadBatchDao().insert(DownloadBatchEntity(createdAtMillis = 1, awemeIds = videos.joinToString("|") { it.awemeId }))
        db.batchAnalysisDao().saveSession(BatchAnalysisSessionEntity("session", 1, batchId.toString(), finished = true))
        db.batchAnalysisDao().saveItems(videos.take(3).mapIndexed { index, v ->
            BatchAnalysisItemEntity("session", v.awemeId, index, gson.toJson(v),
                if (allFailed || v.awemeId == "failure") "failed" else "succeeded",
                error = if (allFailed || v.awemeId == "failure") "测试网络错误" else "",
                analysisId = if (!allFailed && v.awemeId == "success") 100L else null,
                suggestedTags = if (!allFailed && v.awemeId == "success") "review-a|review-b" else "")
        })
        if (!allFailed) {
            db.videoAiAnalysisDao().insert(VideoAiAnalysisEntity(id = 100, awemeId = "success", provider = "test", model = "test",
                profileVersion = 1, suggestedTagIds = "901:0.9|902:0.8", succeeded = true, createdAtMillis = 1))
            db.aiTagSuggestionPendingDao().upsert(AiTagSuggestionPendingEntity("success", 100, "review-a|review-b", 1))
        }
    }

    private fun open() {
        scenario = ActivityScenario.launch(BatchTagReviewActivity::class.java)
        scenario!!.onActivity { vm = ViewModelProvider(it)[BatchTagReviewViewModel::class.java] }
        compose.waitUntil(10_000) { !vm.uiState.value.isLoading && vm.uiState.value.analysisItems.isNotEmpty() }
    }

    private fun act(action: (BatchTagReviewViewModel) -> Unit) {
        scenario!!.onActivity { action(vm) }
        compose.waitUntil(10_000) { vm.uiState.value.busyAction == null }
        compose.waitForIdle()
    }

    private fun seedExclusiveRules() = runBlocking {
        db.tagDao().insert(TagEntity("exclusive", id = 903, isExclusive = true))
        db.tagDao().updateParentTagName("review-a", "exclusive")
        db.tagDao().updateParentTagName("review-b", "exclusive")
        db.tagDao().updateEnableAi("review-b", false)
    }

    @Test fun validation_marksWholeItemsAndClearsOnlyAfterSuccessfulRecheck() {
        seed(); seedExclusiveRules(); open()
        compose.onNodeWithTag("review_validate").assertIsNotEnabled()
        act { it.setEditFilter(com.blitz.downloader.viewmodel.TagEditFilter.EDITED) }
        compose.waitUntil(10_000) { vm.uiState.value.groups.isEmpty() }
        assertFalse(vm.uiState.value.canValidateTags)
        act { it.validateTags() }
        assertTrue(vm.uiState.value.conflictingVideoIds.isEmpty())
        act { it.setEditFilter(com.blitz.downloader.viewmodel.TagEditFilter.ALL) }
        compose.waitUntil(10_000) { vm.uiState.value.groups.size == 2 }
        act { it.confirmGroup("review-a") }
        act { it.confirmGroup("review-b") }
        act { it.saveSingleVideoTags("failure", listOf("review-a", "review-b")) }
        compose.onNodeWithTag("review_validate").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { vm.uiState.value.busyAction == null && vm.uiState.value.conflictingVideoIds.size == 2 }
        assertEquals(setOf("success", "failure"), vm.uiState.value.conflictingVideoIds)
        val conflict = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "标签冲突")
        compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_all"))
        compose.onNodeWithTag("review_all_success").assert(conflict)
        compose.onNodeWithTag("review_all_failure").assert(conflict)
        screenshot("validation-conflicts")
        compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_failed"))
        compose.onNodeWithTag("review_failed_failure").assert(conflict)
        val editCount = runBlocking { db.downloadedVideoDao().getByAwemeId("success")!!.tagEditCount }
        val feedbackCount = runBlocking { db.videoTagFeedbackDao().countByAwemeId("success") }
        act { it.validateTags(); it.validateTags() }
        runBlocking {
            assertEquals(editCount, db.downloadedVideoDao().getByAwemeId("success")!!.tagEditCount)
            assertEquals(feedbackCount, db.videoTagFeedbackDao().countByAwemeId("success"))
        }
        act { it.saveSingleVideoTags("success", listOf("review-a")) }
        assertTrue("保存不等同于重新校验通过", "success" in vm.uiState.value.conflictingVideoIds)
        act { it.validateTags() }
        assertEquals(setOf("failure"), vm.uiState.value.conflictingVideoIds)
        compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_all"))
        compose.onNodeWithTag("review_all_success").assert(conflict.not())
        screenshot("validation-corrected")
        // 标签配置变更也必须重新读取，且不受修改次数显示筛选影响。
        runBlocking { db.tagDao().updateIsExclusive("exclusive", false) }
        act { it.setEditFilter(com.blitz.downloader.viewmodel.TagEditFilter.UNEDITED) }
        act { it.validateTags() }
        assertTrue(vm.uiState.value.conflictingVideoIds.isEmpty())
    }

    @Test fun validation_serializesEditsAndRejectsOldSessionAfterQueuedBatchChange() {
        seed(allFailed = true); seedExclusiveRules(); open()
        act { it.saveSingleVideoTags("failure", listOf("review-a", "review-b")) }
        act { it.validateTags() }
        assertEquals(setOf("failure"), vm.uiState.value.conflictingVideoIds)
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocker = CoroutineScope(Dispatchers.IO).launch {
            db.withTransaction {
                locked.complete(Unit)
                release.await()
                db.downloadedVideoDao().insert(video("new-video"))
                db.downloadBatchDao().insert(DownloadBatchEntity(createdAtMillis = 2, awemeIds = "new-video"))
            }
        }
        runBlocking { locked.await() }
        try {
            scenario!!.onActivity { vm.validateTags(); vm.validateTags(); vm.saveSingleVideoTags("failure", emptyList()) }
            compose.waitUntil(10_000) { vm.uiState.value.busyAction == "validate" }
            compose.onNodeWithTag("review_validate").assertIsNotEnabled()
        } finally {
            release.complete(Unit)
            runBlocking { blocker.join() }
        }
        compose.waitUntil(10_000) { vm.uiState.value.busyAction == null && vm.uiState.value.analysisItems.isEmpty() }
        assertTrue(vm.uiState.value.conflictingVideoIds.isEmpty())
        assertFalse(vm.uiState.value.canValidateTags)
        runBlocking { assertEquals(setOf("review-a", "review-b"), db.videoTagDao().getTagsForVideo("failure").toSet()) }
    }

    @Test fun validation_readFailureKeepsConflictAndAllowsRetry() {
        seed(allFailed = true); seedExclusiveRules(); open()
        act { it.saveSingleVideoTags("failure", listOf("review-a", "review-b")) }
        act { it.validateTags() }
        assertEquals(setOf("failure"), vm.uiState.value.conflictingVideoIds)
        // 专用测试数据库短暂隐藏标签表，注入真实的读取错误。
        db.openHelper.writableDatabase.execSQL("ALTER TABLE tags RENAME TO validation_hidden_tags")
        try {
            act { it.validateTags() }
            assertEquals(setOf("failure"), vm.uiState.value.conflictingVideoIds)
            assertTrue(vm.uiState.value.canValidateTags)
        } finally {
            db.openHelper.writableDatabase.execSQL("ALTER TABLE validation_hidden_tags RENAME TO tags")
        }
        act { it.saveSingleVideoTags("failure", listOf("review-a")) }
        act { it.validateTags() }
        assertTrue(vm.uiState.value.conflictingVideoIds.isEmpty())
    }

    @Test fun mixedResults_reviewEditRestoreAndOrdering() {
        seed(); open()
        assertEquals(setOf("success", "failure", "empty"), vm.uiState.value.allVideos.map { it.awemeId }.toSet())
        compose.onNodeWithTag("review_all").assertDoesNotExist()
        scenario!!.onActivity { vm.setEditFilter(com.blitz.downloader.viewmodel.TagEditFilter.EDITED) }
        compose.waitUntil(10_000) { vm.uiState.value.groups.isEmpty() }
        assertFalse(vm.uiState.value.isReviewCompleted)
        compose.onNodeWithTag("review_all").assertDoesNotExist()
        scenario!!.onActivity { vm.setEditFilter(com.blitz.downloader.viewmodel.TagEditFilter.ALL) }
        compose.waitUntil(10_000) { vm.uiState.value.groups.size == 2 }
        compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_group_review-a"))
        compose.onNodeWithTag("review_group_review-a").assertExists()
        screenshot("unprocessed")
        compose.onNode(hasText("反选") and hasAnyAncestor(hasTestTag("review_group_review-a"))).performClick()
        compose.waitUntil(10_000) { vm.uiState.value.busyAction == null }
        assertTrue(vm.uiState.value.groups.first { it.tagName == "review-a" }.selectedAwemeIds.isEmpty())
        act { it.invertGroupSelection("review-a") }
        // Two clicks in one main-thread turn must perform only one write.
        act { it.confirmGroup("review-a"); it.confirmGroup("review-a") }
        runBlocking { assertEquals(1, db.downloadedVideoDao().getByAwemeId("success")!!.tagEditCount) }
        assertFalse(vm.uiState.value.isReviewCompleted)
        compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_group_review-b"))
        compose.onNode(hasText("跳过") and hasAnyAncestor(hasTestTag("review_group_review-b"))).performClick()
        compose.waitUntil(10_000) { vm.uiState.value.busyAction == null && vm.uiState.value.isReviewCompleted }
        assertTrue(vm.uiState.value.isReviewCompleted)
        runBlocking {
            assertNull(db.aiTagSuggestionPendingDao().getByAwemeId("success"))
            assertEquals(2, db.videoTagFeedbackDao().countByAwemeId("success"))
        }
        compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_all"))
        compose.onNodeWithTag("review_all").assertIsDisplayed()
        compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_failed"))
        compose.onNodeWithTag("review_failed").assertExists()
        screenshot("completed")
        act { it.saveSingleVideoTags("success", listOf("review-a", "review-b")) }
        runBlocking { assertEquals(2, db.videoTagFeedbackDao().countByAwemeId("success")) }
        act { it.saveSingleVideoTags("failure", listOf("review-b")) }
        assertEquals(3, vm.uiState.value.sessionVideos.size)
        assertEquals(setOf("review-b"), vm.uiState.value.videoExistingTags["failure"])
        act { it.saveSingleVideoTags("failure", listOf("review-b")) }
        runBlocking { assertEquals(1, db.downloadedVideoDao().getByAwemeId("failure")!!.tagEditCount) }
        scenario!!.recreate()
        scenario!!.onActivity { vm = ViewModelProvider(it)[BatchTagReviewViewModel::class.java] }
        compose.waitUntil(10_000) { vm.uiState.value.isReviewCompleted }
        assertEquals(3, vm.uiState.value.sessionVideos.size)
        assertFalse(vm.uiState.value.allVideos.any { it.awemeId == "failure" })
        act { it.undoGroup("review-a") }
        assertFalse(vm.uiState.value.isReviewCompleted)
        compose.onNodeWithTag("review_all").assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        compose.onNodeWithTag("review_list").performScrollToIndex(0)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.filesDir, "batch-review-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun startRechecksEligibilityAndServicePersistsEveryFailureWithoutNetwork() {
        seed(allFailed = true); open()
        runBlocking { db.downloadedVideoDao().incrementTagEditCount(listOf("success")) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppSettings.setAiSuggestionEnabled(context, true)
        AppSettings.setGeminiApiKey(context, "test-key-never-sent-missing-local-covers")
        try {
            act { it.startBatchAnalysis() }
            compose.waitUntil(15_000) { vm.uiState.value.analysisItems.size == 2 && vm.uiState.value.isReviewCompleted }
            assertEquals(setOf("failure", "empty"), vm.uiState.value.analysisItems.map { it.awemeId }.toSet())
            assertTrue(vm.uiState.value.analysisItems.all { it.status == "failed" && it.error.isNotBlank() })
            assertFalse(vm.uiState.value.isAnalyzing)
        } finally {
            AppSettings.setAiSuggestionEnabled(context, false)
            AppSettings.setGeminiApiKey(context, "")
        }
    }

    @Test fun interruptedSessionIsRecoverableAndNotReportedAsSuccess() {
        seed(allFailed = true)
        runBlocking {
            db.batchAnalysisDao().saveSession(db.batchAnalysisDao().latestSession()!!.copy(finished = false))
            db.batchAnalysisDao().setResult("session", "failure", "running")
        }
        open()
        assertTrue(vm.uiState.value.isReviewCompleted)
        assertEquals("failed", vm.uiState.value.analysisItems.first { it.awemeId == "failure" }.status)
        assertTrue(vm.uiState.value.analysisItems.first { it.awemeId == "failure" }.error.contains("中断"))
    }

    @Test fun onlyImagesAndEditedVideosLeaveNoCandidates() {
        runBlocking {
            val videos = listOf(video("edited", 2), video("image", media = "image"))
            videos.forEach { db.downloadedVideoDao().insert(it) }
            db.downloadBatchDao().insert(DownloadBatchEntity(createdAtMillis = 1, awemeIds = "edited|image"))
        }
        scenario = ActivityScenario.launch(BatchTagReviewActivity::class.java)
        scenario!!.onActivity { vm = ViewModelProvider(it)[BatchTagReviewViewModel::class.java] }
        compose.waitUntil(10_000) { !vm.uiState.value.isLoading }
        assertTrue(vm.uiState.value.allVideos.isEmpty())
        assertTrue(vm.uiState.value.analysisItems.isEmpty())
        compose.onNodeWithTag("review_all").assertDoesNotExist()
    }

    @Test fun lateSuccessReopensProcessedGroupAndUndoRetainsAllAppliedMembers() {
        seed()
        runBlocking { db.batchAnalysisDao().setResult("session", "empty", "waiting") }
        open()
        act { it.confirmGroup("review-a") }
        runBlocking {
            db.batchAnalysisDao().setResult("session", "empty", "succeeded", analysisId = 101, tags = "review-a")
            db.aiTagSuggestionPendingDao().upsert(AiTagSuggestionPendingEntity("empty", 101, "review-a", 1))
        }
        compose.waitUntil(10_000) { vm.uiState.value.groups.any { it.tagName == "review-a" && !it.isProcessed } }
        assertEquals(listOf("empty"), vm.uiState.value.groups.first { it.tagName == "review-a" }.videos.map { it.awemeId })
        act { it.confirmGroup("review-a") }
        act { it.undoGroup("review-a") }
        runBlocking {
            assertEquals(0, db.downloadedVideoDao().getByAwemeId("success")!!.tagEditCount)
            assertEquals(0, db.downloadedVideoDao().getByAwemeId("empty")!!.tagEditCount)
        }
    }

    @Test fun newBatchClearsOldResultsWithoutLosingFourNewCandidates() {
        seed(allFailed = true); open()
        assertTrue(vm.uiState.value.isReviewCompleted)
        runBlocking {
            val fresh = (1..4).map { video("new-$it") }
            fresh.forEach { db.downloadedVideoDao().insert(it) }
            db.downloadBatchDao().insert(DownloadBatchEntity(createdAtMillis = 2,
                awemeIds = fresh.joinToString("|") { it.awemeId }))
        }
        // The same open page must react to the new download batch.
        compose.waitUntil(10_000) { vm.uiState.value.latestBatchCount == 4 && vm.uiState.value.analysisItems.isEmpty() }
        assertFalse(vm.uiState.value.isReviewCompleted)
        assertTrue(vm.uiState.value.groups.isEmpty())
        assertTrue(vm.uiState.value.sessionVideos.isEmpty())
        assertTrue((1..4).all { n -> vm.uiState.value.allVideos.any { it.awemeId == "new-$n" } })
        compose.onNodeWithTag("review_all").assertDoesNotExist()
        compose.onNodeWithTag("review_failed").assertDoesNotExist()
        // Re-entering must not revive the older session or turn old pending into a new session.
        scenario!!.recreate()
        scenario!!.onActivity { vm = ViewModelProvider(it)[BatchTagReviewViewModel::class.java] }
        compose.waitUntil(10_000) { !vm.uiState.value.isLoading }
        assertEquals(4, vm.uiState.value.latestBatchCount)
        assertTrue(vm.uiState.value.analysisItems.isEmpty())
        runBlocking { assertEquals("session", db.batchAnalysisDao().latestSession()!!.id) }
    }

    @Test fun editingSummaryKeepsItsItemAndScrollPositionWhileSaving() {
        seed(); open()
        act { it.confirmGroup("review-a") }
        act { it.skipGroup("review-b") }
        compose.onNodeWithTag("review_group_review-a").assertDoesNotExist()
        compose.onNodeWithTag("review_group_review-b").assertDoesNotExist()
        compose.onNodeWithTag("review_list").performScrollToIndex(0)
        compose.onNodeWithTag("review_list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 120f) }
        compose.waitForIdle()
        fun scrollPosition() = compose.onNodeWithTag("review_list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before = scrollPosition()
        assertTrue(before > 0f)
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocker = CoroutineScope(Dispatchers.IO).launch {
            db.withTransaction { locked.complete(Unit); release.await() }
        }
        runBlocking { locked.await() }
        try {
            scenario!!.onActivity { vm.saveSingleVideoTags("success", listOf("review-a", "review-b")) }
            compose.waitUntil(10_000) { vm.uiState.value.busyAction == "save" }
            compose.onNodeWithTag("review_all").assertExists()
            assertEquals(before, scrollPosition(), 0.01f)
        } finally {
            release.complete(Unit)
            runBlocking { blocker.join() }
        }
        compose.waitUntil(10_000) { vm.uiState.value.busyAction == null }
        compose.waitForIdle()
        compose.onNodeWithTag("review_all").assertExists()
        compose.onNodeWithTag("review_group_review-a").assertDoesNotExist()
        compose.onNodeWithTag("review_group_review-b").assertDoesNotExist()
        assertEquals(before, scrollPosition(), 0.01f)
    }

    @Test fun allFailedStillShowsCompleteEditableSummary() {
        seed(allFailed = true); open()
        assertTrue(vm.uiState.value.isReviewCompleted)
        compose.onNodeWithTag("review_all").assertExists()
        act { it.saveSingleVideoTags("failure", listOf("review-a")) }
        assertEquals("failed", vm.uiState.value.analysisItems.first { it.awemeId == "failure" }.status)
        assertEquals(3, vm.uiState.value.sessionVideos.size)
    }

    @Test fun failedDatabaseWriteRollsBackGroupAndAllowsRetry() {
        seed(); open()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_review BEFORE INSERT ON video_tags BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        try {
            act { it.confirmGroup("review-a") }
            assertFalse(vm.uiState.value.groups.first { it.tagName == "review-a" }.isProcessed)
            runBlocking { assertEquals(0, db.downloadedVideoDao().getByAwemeId("success")!!.tagEditCount) }
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_review") }
        act { it.confirmGroup("review-a") }
        assertTrue(vm.uiState.value.groups.first { it.tagName == "review-a" }.isProcessed)
    }
}
