package com.blitz.downloader.activity

import android.app.ActivityManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.data.db.AiPreferenceRunEntity
import com.blitz.downloader.service.PreferenceLearningService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PreferenceLearningBackgroundTest {
    @Test fun foregroundServiceFinishesLocalValidationAfterHome() = runBlocking {
        checkLocalValidation(screenOff = false)
    }

    @Test fun foregroundServiceFinishesLocalValidationWhileDisplayIsOff() = runBlocking {
        // 真机测试套件默认不熄灭用户的屏幕。
        assumeTrue(InstrumentationRegistry.getArguments().getString("testScreenOff") == "true")
        checkLocalValidation(screenOff = true)
    }

    private suspend fun checkLocalValidation(screenOff: Boolean): Unit = kotlinx.coroutines.coroutineScope {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }
        val app = instrumentation.targetContext.applicationContext as BlitzApp
        app.preferenceRecovery.await()
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val dao = app.database.aiPreferenceDao()
        val power = app.getSystemService(PowerManager::class.java)
        val wakeLockTag = "${app.packageName}:${PreferenceLearningService.WAKE_LOCK_TAG}"
        fun wakeLockHeld() = shell("dumpsys power").lineSequence().any {
            it.contains("PARTIAL_WAKE_LOCK") && it.contains(wakeLockTag)
        }
        // 空清单只会触发本地过期判断，测试始终不会发出模型请求。
        dao.saveRun(AiPreferenceRunEntity(id, "PLANNED", "{}", 1, "[]", 0, 0, 0, null, now, now))
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transaction = launch(Dispatchers.IO) {
            app.database.withTransaction {
                locked.complete(Unit)
                release.await()
            }
        }
        try {
            withTimeout(5_000) { locked.await() }
            ActivityScenario.launch<PreferenceLearningActivity>(PreferenceLearningActivity.intent(app)).use { scenario ->
                scenario.onActivity { activity -> assertTrue(PreferenceLearningService.start(activity, id, false)) }
                val manager = app.getSystemService(ActivityManager::class.java)
                @Suppress("DEPRECATION")
                suspend fun waitForForegroundService() = withTimeout(8_000) {
                    while (manager.getRunningServices(100).none {
                        it.service.className == PreferenceLearningService::class.java.name && it.foreground
                    }) delay(50)
                }
                waitForForegroundService()
                shell("input keyevent KEYCODE_HOME")
                withTimeout(8_000) {
                    while (scenario.state == Lifecycle.State.RESUMED) delay(50)
                }
                waitForForegroundService()
                if (screenOff) {
                    shell("input keyevent KEYCODE_SLEEP")
                    withTimeout(8_000) { while (power.isInteractive) delay(50) }
                    assertTrue("息屏后，学习服务应保持 CPU 唤醒锁", wakeLockHeld())
                }
                release.complete(Unit)
                withTimeout(8_000) {
                    while (dao.run(id)?.status != "STALE") delay(50)
                }
                if (screenOff) {
                    assertFalse("任务落库时屏幕应保持关闭", power.isInteractive)
                    withTimeout(8_000) { while (wakeLockHeld()) delay(50) }
                }
            }
        } finally {
            if (screenOff) shell("input keyevent KEYCODE_WAKEUP")
            release.complete(Unit)
            transaction.join()
            app.database.withTransaction {
                app.database.openHelper.writableDatabase.execSQL(
                    "DELETE FROM ai_preference_learning_run WHERE runId = ?", arrayOf(id),
                )
            }
        }
    }
}
