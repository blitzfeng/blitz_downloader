package com.blitz.downloader.data

import com.blitz.downloader.data.db.BackupFileStore
import com.blitz.downloader.data.db.BackupPolicy
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupFileStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun source() = temp.newFile().apply { writeText("validated-sqlite-fixture") }

    @Test fun publishedManifestChecksHashAndIgnoresInterruptedAndUnknownEntries() {
        val root = temp.newFolder("backups")
        val store = BackupFileStore(root)
        val entry = store.publish(source(), "MANUAL", 1, 2, 31)
        assertEquals("MANUAL", entry.manifest!!.kind)
        assertEquals(1, store.scan { }.entries.size)
        File(root, ".pending_" + "a".repeat(32)).mkdir()
        File(root, "backup_20261127_000000").mkdir()
        assertEquals(1, store.scan { }.entries.size)
        entry.dbFile.appendText("corrupt")
        assertEquals(0, store.scan { }.entries.size)
        assertTrue(entry.dir.exists())
    }

    @Test fun badManifestUnknownFilesAndInvalidLegacyDatesArePreserved() {
        val store = BackupFileStore(temp.newFolder())
        val entry = store.publish(source(), "AUTO", 1, 2, 31)
        File(entry.dir, "manifest.json").writeText("{bad")
        assertTrue(store.scan { }.entries.isEmpty())
        val badDate = File(store.root, "backup_20260230_120000").apply { mkdir() }
        File(badDate, BackupFileStore.DB_NAME).writeText("db")
        assertNull(store.inspect(badDate))
        val unknown = File(store.root, "backup_20260101_120000").apply { mkdir() }
        File(unknown, BackupFileStore.DB_NAME).writeText("db")
        File(unknown, "notes.txt").writeText("keep")
        assertNull(store.inspect(unknown))
        assertTrue(File(unknown, "notes.txt").exists())
    }

    @Test fun mixedLegacyNewAndStrictRetentionProtectLatest() {
        val store = BackupFileStore(temp.newFolder())
        val legacyDir = File(store.root, "backup_20260101_120000").apply { mkdir() }
        File(legacyDir, BackupFileStore.DB_NAME).writeText("legacy")
        val legacy = checkNotNull(store.inspect(legacyDir))
        val now = legacy.completedAt + 40 * BackupPolicy.DAY
        val recent = store.publish(source(), "AUTO", now, now, 31)
        val entries = store.scan { }.entries
        assertEquals(2, entries.size)
        val result = store.cleanup(now, entries, recent.id) { }
        assertEquals(1, result.deleted)
        assertFalse(legacyDir.exists())
        assertTrue(recent.dir.exists())
        assertEquals(0, store.cleanup(now + 100 * BackupPolicy.DAY, listOf(recent), recent.id) { }.deleted)
    }

    @Test fun cleanupRechecksUnknownFilesAndIsolatesDeletionFailure() {
        val root = temp.newFolder()
        val store = BackupFileStore(root, delete = { false })
        val old = store.publish(source(), "AUTO", 1, 2, 31)
        val result = store.cleanup(100 * BackupPolicy.DAY, listOf(old), null) { }
        assertEquals(1, result.failed)
        assertTrue(old.dbFile.exists())
        File(old.dir, "notes.txt").writeText("keep")
        assertEquals(1, store.cleanup(100 * BackupPolicy.DAY, listOf(old), null) { }.skipped)
    }

    @Test fun failedRenameDoesNotPublishOrRemoveExistingBackup() {
        val root = temp.newFolder()
        val existing = BackupFileStore(root).publish(source(), "AUTO", 1, 2, 31)
        val failing = BackupFileStore(root, rename = { _, _ -> false })
        assertThrows(java.io.IOException::class.java) { failing.publish(source(), "AUTO", 3, 4, 31) }
        assertTrue(existing.dbFile.exists())
        assertEquals(1, root.listFiles()!!.count { it.isDirectory })
    }

    @Test fun symlinksAreNotFollowedWhenAvailable() {
        val store = BackupFileStore(temp.newFolder())
        val outside = temp.newFolder()
        File(outside, BackupFileStore.DB_NAME).writeText("keep")
        val link = File(store.root, "backup_20260101_000000")
        try { Files.createSymbolicLink(link.toPath(), outside.toPath()) }
        catch (_: Exception) { return } // Windows 未授予创建链接权限时由设备测试覆盖。
        assertNull(store.inspect(link))
        assertTrue(File(outside, BackupFileStore.DB_NAME).exists())
    }
}
