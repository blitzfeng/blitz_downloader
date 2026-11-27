package com.blitz.downloader.data

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.data.db.AppDatabase
import com.blitz.downloader.data.db.BackupFileStore
import com.blitz.downloader.data.db.BackupPolicy
import com.blitz.downloader.data.db.DatabaseBackupManager
import com.blitz.downloader.data.db.DatabaseSnapshot
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseBackupTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun fixture(block: suspend (AppDatabase, File) -> Unit) {
        val id = "backup-test-${UUID.randomUUID()}"
        val root = File(context.cacheDir, id).apply { mkdir() }
        val room = Room.databaseBuilder(context, AppDatabase::class.java, "$id.db").build()
        try { room.openHelper.writableDatabase; block(room, root) }
        finally { room.close(); context.deleteDatabase("$id.db"); root.deleteRecursively() }
    }

    @Test fun completeRoomSnapshotPreservesWalTypesSchemaSequenceAndIdentity() = runBlocking {
        fixture { room, root ->
            val source = room.openHelper.writableDatabase
            source.execSQL("INSERT INTO tags VALUES ('备份样例',0,'',999,'一致性','','1',0)")
            source.execSQL("CREATE TABLE snapshot_values (id INTEGER PRIMARY KEY AUTOINCREMENT, n TEXT, b BLOB, f REAL, tag TEXT REFERENCES tags(tagName))")
            source.execSQL("CREATE TABLE snapshot_audit (id INTEGER PRIMARY KEY)")
            source.execSQL("CREATE INDEX snapshot_index ON snapshot_values(tag)")
            source.execSQL("CREATE VIEW snapshot_view AS SELECT id,tag FROM snapshot_values")
            source.execSQL("CREATE TRIGGER snapshot_trigger AFTER INSERT ON snapshot_values BEGIN INSERT INTO snapshot_audit VALUES (new.id); END")
            source.execSQL("INSERT INTO snapshot_values VALUES (50,NULL,?,1.25,'备份样例')", arrayOf(byteArrayOf(0, 1, -1)))
            val file = File(root, BackupFileStore.DB_NAME)
            val info = DatabaseSnapshot.create(room, file)
            assertEquals(32, info.version)
            assertTrue(info.counts.containsKey("ai_preference_rule_profile"))
            assertTrue(info.counts.containsKey("room_master_table"))
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { dest ->
                dest.rawQuery("SELECT n,b,f,tag FROM snapshot_values", null).use {
                    assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)); assertArrayEquals(byteArrayOf(0, 1, -1), it.getBlob(1))
                    assertEquals(1.25, it.getDouble(2), 0.0); assertEquals("备份样例", it.getString(3))
                }
                dest.execSQL("INSERT INTO snapshot_values (tag) VALUES ('备份样例')")
                dest.rawQuery("SELECT max(id) FROM snapshot_values", null).use { it.moveToFirst(); assertEquals(51, it.getInt(0)) }
                dest.rawQuery("SELECT count(*) FROM snapshot_audit", null).use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
            }
            val name = "restore-${UUID.randomUUID()}.db"
            file.copyTo(context.getDatabasePath(name))
            val restored = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try { assertEquals("备份样例", restored.tagDao().getAllEntities().single().tagName) }
            finally { restored.close(); context.deleteDatabase(name) }
            // 快照操作没有关闭源 Room；后续业务仍能写入。
            source.execSQL("UPDATE tags SET description='仍可写入'")
        }
    }

    @Test fun largeMetadataAndConcurrentWriterKeepTransactionView() = runBlocking {
        fixture { room, root ->
            val source = room.openHelper.writableDatabase
            room.withTransaction {
                source.compileStatement("INSERT INTO tags VALUES (?,0,'',?,'0','0',1,0)").use { statement ->
                    repeat(10_000) { i -> statement.bindString(1, "样例$i"); statement.bindLong(2, i + 1L); statement.executeInsert() }
                }
            }
            val start = android.os.SystemClock.elapsedRealtime()
            val writer = launch(Dispatchers.IO) {
                repeat(20) { i -> room.withTransaction {
                    source.execSQL("UPDATE tags SET description=?,collectFolderNames=? WHERE tagName='样例0'", arrayOf("$i", "$i"))
                } }
            }
            val snapshot = async(Dispatchers.IO) { DatabaseSnapshot.create(room, File(root, BackupFileStore.DB_NAME)) }
            assertEquals(10_000L, snapshot.await().counts["tags"])
            writer.join()
            SQLiteDatabase.openDatabase(File(root, BackupFileStore.DB_NAME).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT description,collectFolderNames FROM tags WHERE tagName='样例0'", null).use {
                    it.moveToFirst(); assertEquals(it.getString(0), it.getString(1))
                }
            }
            Log.i("DatabaseBackupTest", "rows=10000 concurrentWriterAndSnapshotMs=${android.os.SystemClock.elapsedRealtime() - start}")
        }
    }

    @Test fun legacyWalIsValidatedInIsolationAndUnknownLinksNeverDeleted() = runBlocking {
        fixture { room, root ->
            val source = room.openHelper.writableDatabase
            source.execSQL("INSERT INTO tags VALUES ('旧 WAL 样例',0,'',998,'','',1,0)")
            val store = BackupFileStore(File(root, "backups").apply { mkdir() })
            val legacy = File(store.root, "backup_20260101_010101").apply { mkdir() }
            val live = File(checkNotNull(source.path))
            for (suffix in listOf("", "-wal", "-shm")) {
                File(live.path + suffix).takeIf { it.exists() }?.copyTo(File(legacy, BackupFileStore.DB_NAME + suffix))
            }
            val before = legacy.listFiles()!!.associate { it.name to BackupFileStore.digest(it) }
            val entries = store.scan { DatabaseBackupManager.validateEntry(context, it) }.entries
            assertEquals(1, entries.size)
            assertEquals(before, legacy.listFiles()!!.associate { it.name to BackupFileStore.digest(it) })
            val outside = File(root, "outside").apply { mkdir() }
            File(outside, BackupFileStore.DB_NAME).writeText("keep")
            Os.symlink(outside.path, File(store.root, "backup_20260102_010101").path)
            assertNull(store.inspect(File(store.root, "backup_20260102_010101")))
            val result = store.cleanup(1000 * BackupPolicy.RETENTION, entries, entries.single().id) { }
            assertEquals(0, result.deleted)
            assertTrue(File(outside, BackupFileStore.DB_NAME).exists())
            File(store.root, "backup_20260102_010101").delete()
        }
    }

    @Test fun interruptedAndCorruptCopiesDoNotPublishOrLoseRecoveryPoints() = runBlocking {
        fixture { room, root ->
            val file = File(root, BackupFileStore.DB_NAME)
            DatabaseSnapshot.create(room, file)
            val store = BackupFileStore(File(root, "backups").apply { mkdir() })
            val old = store.publish(file, "MANUAL", 1, 2, 31)
            val pending = File(store.root, ".pending_" + "a".repeat(32)).apply { mkdir() }
            File(pending, BackupFileStore.DB_NAME).writeBytes(file.readBytes().take(128).toByteArray())
            val corrupt = File(root, "corrupt.db").apply { writeText("not a database") }
            assertThrows(Exception::class.java) { DatabaseSnapshot.validate(corrupt) }
            assertEquals(listOf(old.id), store.scan { DatabaseBackupManager.validateEntry(context, it) }.entries.map { it.id })
        }
    }

    @Test fun fileAndAuthorizedStreamRestoreUseValidatedSingleFile() = runBlocking {
        (context.applicationContext as BlitzApp).preferenceRecovery.await()
        fixture { room, root ->
            room.openHelper.writableDatabase.execSQL("INSERT INTO tags VALUES ('恢复样例',0,'',997,'','',1,0)")
            val file = File(root, BackupFileStore.DB_NAME)
            DatabaseSnapshot.create(room, file)
            for (stream in listOf(false, true)) {
                val name = "restore-${UUID.randomUUID()}.db"
                val target = object : ContextWrapper(context) {
                    override fun getDatabasePath(ignored: String) = context.getDatabasePath(name)
                }
                try {
                    if (stream) file.inputStream().use { DatabaseBackupManager.restoreFromStream(target, it) }
                    else DatabaseBackupManager.restoreFrom(target, DatabaseBackupManager.BackupEntry(root, file, 1))
                    val restored = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
                    try { assertEquals("恢复样例", restored.tagDao().getAllEntities().single().tagName) }
                    finally { restored.close() }
                } finally { context.deleteDatabase(name) }
            }
        }
    }
}
