package com.blitz.downloader.activity

import android.graphics.Color
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.config.AiConnectionConfig
import com.blitz.downloader.config.AiService
import com.blitz.downloader.config.AppSettings
import com.blitz.downloader.data.db.DownloadedVideoEntity
import com.blitz.downloader.data.db.TagEntity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferenceLearningActivityTest {
    @get:Rule val compose = createAndroidComposeRule<PreferenceLearningActivity>()

    @Test fun previewRequiresConfigurationAndShowsDataBeforeConfirmation() {
        val app = compose.activity.application as BlitzApp
        val previous = AppSettings.getAiConnections(app).first { it.service == AiService.OPENAI }
        val previouslyEnabled = AppSettings.isAiSuggestionEnabled(app)
        val videoId = "test-preference-preview"
        val tag = "测试预览标签"
        try {
            assertToolbarClearsStatusBar("学习我的偏好")
            AppSettings.setAiSuggestionEnabled(app, false)
            compose.onNodeWithText("刷新概览").performClick()
            compose.onNodeWithText("预览全量学习计划").assertIsNotEnabled()
            compose.onNodeWithText("请在设置中启用 AI 建议并配置可用服务。").assertExists()

            AppSettings.setAiSuggestionEnabled(app, true)
            AppSettings.setOpenAiConfig(app, "fixture-unused-key", AiConnectionConfig.DEFAULT_OPENAI_MODEL)
            runBlocking {
                app.database.downloadedVideoDao().insert(DownloadedVideoEntity(
                    awemeId = videoId, downloadType = "post", userName = "测试作者", desc = "预览文案",
                ))
                app.database.tagDao().insert(TagEntity(tag, id = 901_123))
                app.videoTagRepository.addTagsAsUserEdit(listOf(videoId), listOf(tag))
            }
            compose.onNodeWithText("刷新概览").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("可用案例 1 · 有证据图 0").fetchSemanticsNodes().isNotEmpty()
            }
            assertTextDrawnOnScreen("可用案例 1 · 有证据图 0")
            compose.onNodeWithText("预览全量学习计划").assertIsEnabled().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("确认开始").fetchSemanticsNodes().isNotEmpty()
            }
            assertToolbarClearsStatusBar("确认学习计划")
            compose.onNodeWithText("将发送计划内的文案、标签、逐标签判断、已有视觉特征与证据图。确认后才会调用服务。").assertExists()
            captureIfRequested(app, "learning-plan.png")
            compose.onNodeWithText("返回概览").performClick()
            compose.onNodeWithText("确认开始").assertDoesNotExist()
        } finally {
            AppSettings.setOpenAiConfig(app, previous.apiKey, previous.model)
            AppSettings.setAiSuggestionEnabled(app, previouslyEnabled)
            runBlocking {
                app.downloadedVideoRepository.deleteByAwemeId(videoId)
                app.videoTagRepository.deleteTag(tag)
            }
        }
    }

    private fun assertToolbarClearsStatusBar(title: String) {
        val statusBarHeight = compose.runOnIdle {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        }
        val titleTop = compose.onNodeWithText(title).fetchSemanticsNode().boundsInWindow.top
        assertTrue("标题顶部 $titleTop 应低于状态栏 $statusBarHeight", statusBarHeight > 0 && titleTop >= statusBarHeight)
        assertTextDrawnOnScreen(title)
    }

    private fun assertTextDrawnOnScreen(text: String) {
        val origin = compose.runOnIdle {
            IntArray(2).also { compose.activity.window.decorView.getLocationOnScreen(it) }
        }
        val bounds = compose.onNodeWithText(text).fetchSemanticsNode().boundsInWindow
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // 检查合成后的实际屏幕，避免只有语义节点可见、画面却被不透明 View 覆盖。
        compose.waitUntil(timeoutMillis = 5_000) {
            val screenshot = automation.takeScreenshot() ?: return@waitUntil false
            try {
                val x0 = (bounds.left.toInt() + origin[0]).coerceIn(0, screenshot.width)
                val x1 = (bounds.right.toInt() + origin[0]).coerceIn(0, screenshot.width)
                val y0 = (bounds.top.toInt() + origin[1]).coerceIn(0, screenshot.height)
                val y1 = (bounds.bottom.toInt() + origin[1]).coerceIn(0, screenshot.height)
                (y0 until y1 step 3).any { y ->
                    (x0 until x1 step 3).count { x ->
                        val pixel = screenshot.getPixel(x, y)
                        Color.red(pixel) < 140 && Color.green(pixel) < 140 && Color.blue(pixel) < 140
                    } > 4
                }
            } finally {
                screenshot.recycle()
            }
        }
    }

    private fun captureIfRequested(app: BlitzApp, name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureLearningUi") != "true") return
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(app.cacheDir, name).outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
