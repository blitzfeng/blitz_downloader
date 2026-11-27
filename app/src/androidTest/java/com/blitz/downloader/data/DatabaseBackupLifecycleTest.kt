package com.blitz.downloader.data

import android.app.Activity
import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blitz.downloader.data.db.BackupFileStore
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 手动派发生命周期回调，避免为校验弹权限窗口或触碰公共备份。 */
@RunWith(AndroidJUnit4::class)
class DatabaseBackupLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test fun coldStartRotationNavigationAndBackgroundServiceOnlyAreDeduplicated() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as Application
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val scans = AtomicInteger()
        val notifications = Collections.synchronizedList(mutableListOf<String>())
        val now = System.currentTimeMillis()
        val storage = object : BackupMaintenance.Storage {
            override fun scan(): BackupFileStore.Scan {
                assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                scans.incrementAndGet()
                return BackupFileStore.Scan(listOf(BackupFileStore.Entry(File("recent"), now)), 0)
            }
            override suspend fun create(kind: String): BackupFileStore.Entry = error("未到期")
            override fun cleanup(now: Long, entries: List<BackupFileStore.Entry>, protectedId: String?) = BackupFileStore.Cleanup()
        }
        val maintenance = BackupMaintenance(storage, { now }, { 0 }, { null }, { })
        val coordinator = DatabaseBackupCoordinator(app, scope, maintenance, { notifications += it }, { false }, { false })
        lateinit var activity: Activity
        onMain { activity = Activity() }
        try {
            delay(100)
            assertEquals(0, scans.get()) // 只创建协调器/后台服务不触发。
            onMain { coordinator.onActivityStarted(activity); coordinator.onActivityResumed(activity) }
            withTimeout(5000) { while (scans.get() != 1) delay(10) }
            onMain {
                coordinator.onActivityPaused(activity); coordinator.onActivityStopped(activity)
                coordinator.onActivityStarted(activity); coordinator.onActivityResumed(activity)
            }
            delay(850)
            assertEquals(1, scans.get())
            onMain { coordinator.onActivityPaused(activity); coordinator.onActivityStopped(activity) }
            delay(850)
            onMain { coordinator.onActivityStarted(activity); coordinator.onActivityResumed(activity) }
            withTimeout(5000) { while (scans.get() != 2) delay(10) }
            assertTrue(notifications.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun backgroundResultIsConsumedOnceAndPermissionChangeAllowsRetry() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as Application
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val notifications = Collections.synchronizedList(mutableListOf<String>())
        var permission = false
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val now = System.currentTimeMillis()
        val storage = object : BackupMaintenance.Storage {
            override fun scan() = BackupFileStore.Scan(emptyList(), 0)
            override suspend fun create(kind: String): BackupFileStore.Entry {
                if (calls.incrementAndGet() == 1) {
                    entered.complete(Unit); release.await(); throw IOException("No space left on device")
                }
                return BackupFileStore.Entry(File("test-complete"), now)
            }
            override fun cleanup(now: Long, entries: List<BackupFileStore.Entry>, protectedId: String?) = BackupFileStore.Cleanup(failed = 1)
        }
        val maintenance = BackupMaintenance(storage, { now }, { 0 }, { null }, { })
        val coordinator = DatabaseBackupCoordinator(app, scope, maintenance, { notifications += it }, { permission }, { false })
        lateinit var activity: Activity
        onMain { activity = Activity() }
        try {
            onMain { coordinator.onActivityStarted(activity); coordinator.onActivityResumed(activity) }
            withTimeout(5000) { entered.await() }
            onMain { coordinator.onActivityPaused(activity); coordinator.onActivityStopped(activity) }
            release.complete(Unit)
            delay(850)
            assertTrue(notifications.isEmpty())
            onMain { coordinator.onActivityStarted(activity); coordinator.onActivityResumed(activity) }
            assertEquals(1, notifications.size)
            assertTrue(notifications.single().contains("存储空间不足"))
            onMain { coordinator.onActivityPaused(activity); coordinator.onActivityResumed(activity) }
            assertEquals(1, notifications.size)
            onMain { permission = true; coordinator.onActivityPaused(activity); coordinator.onActivityResumed(activity) }
            withTimeout(5000) { while (notifications.size < 2) delay(10) }
            assertTrue(notifications[1].contains("数据库已自动备份"))
            assertTrue(notifications[1].contains("部分过期备份未能删除"))
            assertEquals(2, calls.get())
        } finally { scope.cancel() }
    }
}
