package com.blitz.downloader.activity

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.blitz.downloader.BlitzApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class MediaRecoveryScreenTest {
    @get:Rule val screen = createAndroidComposeRule<MediaRecoveryActivity>()

    @Test fun openingRotationAndHistoryHaveNoBusinessSideEffects() {
        val app = screen.activity.application as BlitzApp
        val before = runBlocking { app.database.downloadedVideoDao().getAll() }
        screen.onNodeWithText("扫描本地文件").assertExists()
        screen.onNodeWithText("历史",useUnmergedTree = true).performClick()
        screen.onNodeWithText("导出恢复报告").assertExists()
        screen.activityRule.scenario.recreate()
        screen.onNodeWithText("导出恢复报告").assertExists()
        screen.onNodeWithText("概况").performClick()
        screen.waitForIdle()
        assertFalse(com.blitz.downloader.service.MediaRecoveryService.running.value)
        assertEquals(before,runBlocking { app.database.downloadedVideoDao().getAll() })
        val png = screen.onRoot().captureToImage().asAndroidBitmap()
        File(app.filesDir,"recovery-overview.png").outputStream().use { png.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        png.recycle()
        screen.runOnIdle {
            val vm = androidx.lifecycle.ViewModelProvider(screen.activity)[com.blitz.downloader.viewmodel.MediaRecoveryViewModel::class.java]
            vm.setGrant(android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload"))
        }
        screen.waitUntil(10000) {
            screen.onAllNodesWithText("请选择内部存储的 Download/bDouyin 文件夹").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(before,runBlocking { app.database.downloadedVideoDao().getAll() })
    }
}
