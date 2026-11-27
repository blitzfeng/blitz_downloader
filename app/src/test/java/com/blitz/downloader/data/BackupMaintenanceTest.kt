package com.blitz.downloader.data

import com.blitz.downloader.data.db.BackupFileStore
import com.blitz.downloader.data.db.BackupPolicy
import com.blitz.downloader.data.db.BackupForegroundState
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupMaintenanceTest {
    private fun entry(id: String, time: Long) = BackupFileStore.Entry(File(id), time)

    @Test fun strictTimeBoundariesAndFutureCalibration() {
        val now = 100 * BackupPolicy.DAY
        assertTrue(BackupPolicy.decide(now, emptyList(), null).create)
        assertFalse(BackupPolicy.decide(now, listOf(entry("manual", now - BackupPolicy.INTERVAL)), null).create)
        assertTrue(BackupPolicy.decide(now + 1, listOf(entry("manual", now - BackupPolicy.INTERVAL)), null).create)
        assertFalse(BackupPolicy.expired(now, entry("old", now - BackupPolicy.RETENTION), "new"))
        assertTrue(BackupPolicy.expired(now + 1, entry("old", now - BackupPolicy.RETENTION), "new"))
        assertFalse(BackupPolicy.expired(now, entry("old", 1), "old"))
        val future = entry("future", now + 1)
        assertTrue(BackupPolicy.decide(now, listOf(future), null).create)
        assertFalse(BackupPolicy.decide(now, listOf(future), null).allowCleanup)
        val decision = BackupPolicy.decide(now, listOf(future, entry("calibrated", now)), "calibrated")
        assertFalse(decision.create)
        assertFalse(decision.allowCleanup)
    }

    private class Storage(var now: Long) : BackupMaintenance.Storage {
        val entries = mutableListOf<BackupFileStore.Entry>()
        var fail: Exception? = null
        var failCleanup = false
        var cleanups = 0
        var creations = 0
        var beforeCreate: (suspend () -> Unit)? = null
        override fun scan() = BackupFileStore.Scan(entries.toList(), 0)
        override suspend fun create(kind: String): BackupFileStore.Entry {
            creations++
            beforeCreate?.invoke()
            fail?.let { throw it }
            return BackupFileStore.Entry(File("$kind-$creations"), now).also { entries += it }
        }
        override fun cleanup(now: Long, entries: List<BackupFileStore.Entry>, protectedId: String?): BackupFileStore.Cleanup {
            cleanups++
            if (failCleanup) throw IOException("cleanup failure")
            val remove = entries.filter { BackupPolicy.expired(now, it, protectedId) }
            this.entries.removeAll(remove.toSet())
            return BackupFileStore.Cleanup(deleted = remove.size)
        }
    }

    @Test fun failureKeepsRecoveryPointAndMonotonicCooldownThenRetry() = runBlocking {
        val storage = Storage(100 * BackupPolicy.DAY)
        val old = entry("old", BackupPolicy.DAY)
        storage.entries += old
        storage.fail = IOException("No space left on device")
        var saved: String? = old.id
        var elapsed = 0L
        val maintenance = BackupMaintenance(storage, { storage.now }, { elapsed }, { saved }, { saved = it.id })
        assertNotNull(maintenance.check().error)
        assertEquals(old.id, saved)
        assertEquals(listOf(old), storage.entries)
        assertEquals(0, storage.cleanups)
        storage.now += 10 * BackupPolicy.DAY
        assertNull(maintenance.check().error)
        assertEquals(1, storage.creations)
        elapsed += BackupPolicy.RETRY_DELAY
        storage.fail = null
        assertNotNull(maintenance.check().created)
        assertEquals(1, storage.entries.size)
        assertNotEquals(old.id, saved)
    }

    @Test fun cleanupRunsWithoutNewBackupAndCleanupFailureDoesNotUndoSuccess() = runBlocking {
        val storage = Storage(100 * BackupPolicy.DAY)
        storage.entries += entry("recent", storage.now - 1)
        storage.entries += entry("expired", 1)
        var saved: String? = null
        val maintenance = BackupMaintenance(storage, { storage.now }, { 0 }, { saved }, { saved = it.id })
        assertEquals(1, maintenance.check().cleanup.deleted)
        assertEquals(0, storage.creations)
        storage.now += BackupPolicy.INTERVAL + 1
        storage.failCleanup = true
        val result = maintenance.check()
        assertNotNull(result.created)
        assertNull(result.error)
        assertEquals(1, result.cleanup.failed)
        assertEquals(result.created!!.id, saved)
    }

    @Test fun waitingAutomaticCheckRechecksManualBackupAndRestoreStopsChecks() = runBlocking {
        val storage = Storage(100 * BackupPolicy.DAY)
        var saved: String? = null
        val maintenance = BackupMaintenance(storage, { storage.now }, { 0 }, { saved }, { saved = it.id })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        storage.beforeCreate = { entered.complete(Unit); release.await() }
        val manual = async { maintenance.manualBackup() }
        entered.await()
        val auto = async { maintenance.check() }
        release.complete(Unit)
        manual.await()
        assertNull(auto.await().created)
        assertEquals(1, storage.creations)
        maintenance.restore { }
        storage.now += BackupPolicy.INTERVAL + 1
        assertNull(maintenance.check().created)
    }

    @Test fun protectionAndBackupThroughCommitShareTheRestoreLock() = runBlocking {
        val storage = Storage(100 * BackupPolicy.DAY)
        val maintenance = BackupMaintenance(storage, { storage.now }, { 0 }, { null }, { })
        maintenance.check { true }
        assertEquals(0, storage.cleanups)
        assertEquals(0, storage.creations)
        val entered = CompletableDeferred<Unit>()
        val commit = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val write = async {
            maintenance.exclusive {
                order += "backup"
                entered.complete(Unit)
                commit.await()
                order += "commit"
            }
        }
        entered.await()
        val restore = async { maintenance.restore { order += "restore" } }
        kotlinx.coroutines.yield()
        assertFalse(restore.isCompleted)
        commit.complete(Unit)
        write.await()
        restore.await()
        assertEquals(listOf("backup", "commit", "restore"), order)
    }

    @Test fun foregroundChangesCoalesceActivitiesAndRotation() {
        val state = BackupForegroundState()
        assertFalse(state.foreground)
        assertTrue(state.start())
        state.stop()
        assertFalse(state.start()) // 旋转/页面切换在延迟确认前接续。
        state.settleBackground()
        assertTrue(state.foreground)
        state.stop()
        state.settleBackground()
        assertFalse(state.foreground)
        assertTrue(state.start())
    }
}
