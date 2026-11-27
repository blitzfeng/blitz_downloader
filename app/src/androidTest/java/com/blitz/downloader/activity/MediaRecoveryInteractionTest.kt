package com.blitz.downloader.activity

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.blitz.downloader.RecoveryValidationApp
import com.blitz.downloader.model.RecoveryNames
import com.blitz.downloader.service.MediaRecoveryService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch

class MediaRecoveryInteractionTest {
    @get:Rule val screen = createAndroidComposeRule<MediaRecoveryActivity>()
    private fun click(text: String) = screen.onNodeWithText(text)
        .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    private fun awaitText(text: String) {
        try {
            screen.waitUntil(15000) { screen.onAllNodesWithText(text,substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("等待「$text」超时\n" + screen.onAllNodes(isRoot()).printToString(),e)
        }
    }

    @Test fun userReviewsCancelsAndCommitsOnlySelectedMedia() {
        val app = screen.activity.application as RecoveryValidationApp
        val requestsBefore = app.requests.get()
        val id = (System.currentTimeMillis()*1000).toString()
        val path = File(app.fixture,RecoveryNames.root + "/videos/" + "长文件名".repeat(16) + "_$id.mp4")
        path.parentFile!!.mkdirs()
        val source = InstrumentationRegistry.getInstrumentation().context.assets.open("video-tap.mp4").use { it.readBytes() }
        path.writeBytes(source)
        val ambiguous = (id.toLong() + 1).toString()
        listOf("同名_${ambiguous}.mp4", "同名_${ambiguous} (1).mp4").forEach { name ->
            File(path.parentFile, name).writeBytes(source)
        }
        val feedbackBefore = runBlocking { app.database.videoTagFeedbackDao().countAll() }
        val versionBefore = runBlocking { app.database.aiPreferenceDao().active()?.version }
        click("扫描本地文件")
        screen.waitUntil(15000) {
            runBlocking { app.mediaRecovery.dao.lastCompleteScan()?.let { app.mediaRecovery.dao.work(it.sessionId,id) } != null } &&
                !MediaRecoveryService.running.value
        }
        assertEquals(requestsBefore,app.requests.get())
        val latch = CountDownLatch(1)
        app.waitForQuery = latch
        click("查询 / 继续")
        screen.waitUntil(10000) { app.requests.get() > requestsBefore }
        app.stopService(android.content.Intent(app,MediaRecoveryService::class.java))
        screen.waitUntil(15000) { !MediaRecoveryService.running.value }
        latch.countDown()
        assertNull(runBlocking { app.database.downloadedVideoDao().getByAwemeId(id) })
        val beforeResume = app.requests.get()
        val resumedQuery = CountDownLatch(1)
        app.waitForQuery = resumedQuery
        click("查询 / 继续")
        screen.waitUntil(10000) { app.requests.get() > beforeResume }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
        screen.waitUntil(10000) { screen.activityRule.scenario.state == Lifecycle.State.CREATED }
        resumedQuery.countDown()
        screen.waitUntil(15000) { !MediaRecoveryService.running.value }
        app.startActivity(android.content.Intent(app,MediaRecoveryActivity::class.java).addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        screen.waitUntil(10000) { screen.activityRule.scenario.state == Lifecycle.State.RESUMED }
        awaitText("2 个有效详情")
        screen.onNodeWithTag("view-" + id).performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        awaitText("预览本地视频")
        click("预览本地视频")
        var previewClosed = false
        screen.waitUntil(10000) {
            instrumentation.runOnMainSync {
                val activities = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                activities.filterIsInstance<VideoPlayerActivity>().firstOrNull()?.let {
                    it.finish()
                    previewClosed = true
                }
            }
            previewClosed
        }
        assertNull(runBlocking { app.database.downloadedVideoDao().getByAwemeId(id) })
        awaitText("确认关联")
        click("关闭")
        click("确认本页建议")
        awaitText("确认本页 1 个对应关系")
        click("确认关联")
        awaitText("1 个已确认关联")
        val ambiguousRows = runBlocking {
            val dao = app.mediaRecovery.dao
            dao.candidates(dao.lastCompleteScan()!!.sessionId,ambiguous)
        }
        assertEquals(2,ambiguousRows.size)
        assertTrue(ambiguousRows.all { it.state == "REVIEW" })
        screen.onNodeWithText("预览写回（0）").assertIsNotEnabled()
        screen.onNodeWithTag("write-select-$id").performClick()
        click("预览写回（1）")
        awaitText("确认备份并写回")
        click("取消")
        assertNull(runBlocking { app.database.downloadedVideoDao().getByAwemeId(id) })
        click("预览写回（1）")
        awaitText("确认备份并写回")
        click("备份并新增")
        screen.waitUntil(20000) { runBlocking { app.database.downloadedVideoDao().getByAwemeId(id) != null } }
        val row = runBlocking { app.database.downloadedVideoDao().getByAwemeId(id)!! }
        assertEquals("recovered",row.downloadType)
        assertFalse(row.watched)
        assertEquals(beforeResume + 2,app.requests.get())
        assertArrayEquals(source,path.readBytes())
        val export = runBlocking { com.blitz.downloader.download.MediaExportManager.resolveExportFiles(listOf(row)) }.single()
        assertArrayEquals(source,export.open().use { it.readBytes() })
        runBlocking {
            assertTrue(app.database.videoTagDao().getTagsForVideo(id).isEmpty())
            assertEquals(feedbackBefore,app.database.videoTagFeedbackDao().countAll())
            assertEquals(versionBefore,app.database.aiPreferenceDao().active()?.version)
            app.videoTagRepository.setTagsAsUserEdit(id,listOf("合成用户标签"))
            assertEquals(listOf("合成用户标签"),app.database.videoTagDao().getTagsForVideo(id))
            assertEquals(1,app.database.downloadedVideoDao().getByAwemeId(id)!!.tagEditCount)
        }
        click("历史")
        awaitText("已提交")
    }
}
