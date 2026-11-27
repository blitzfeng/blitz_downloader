package com.blitz.downloader.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.blitz.downloader.data.db.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RecoveryDatabaseTest {
    private inline fun <T> AppDatabase.use(block: (AppDatabase) -> T): T = try { block(this) } finally { close() }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "fixture-${UUID.randomUUID()}.db"
    private val path get() = context.getDatabasePath(name)

    /** 所有列使用合成值，同时按字段名回读，覆盖学习数据和关联表。 */
    private fun migrate(version: Int) {
        val schema = instrumentation.context.assets.open("com.blitz.downloader.data.db.AppDatabase/31.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database").getJSONArray("entities") }
        val original = mutableMapOf<String, Map<String, String>>()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            for (i in 0 until schema.length()) {
                val table = schema.getJSONObject(i)
                val name = table.getString("tableName")
                if (version == 18 && name !in setOf("downloaded_videos", "tags", "video_tags", "author_tag_frequency")) continue
                val sql = table.getString("createSql").replace("\${TABLE_NAME}", name)
                db.execSQL(if (version == 18 && name == "tags")
                    sql.substringBefore(", `collectFolderNames`") + ", PRIMARY KEY(`tagName`))" else sql)
                val indices = table.getJSONArray("indices")
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
                }
                val fields = ContentValues()
                val expected = mutableMapOf<String, String>()
                db.rawQuery("PRAGMA table_info(`$name`)", null).use { c ->
                    while (c.moveToNext()) {
                        val col = c.getString(1)
                        when (c.getString(2)) {
                            "INTEGER" -> fields.put(col, 2L)
                            "REAL" -> fields.put(col, 0.5)
                            else -> fields.put(col, "sample-$col")
                        }
                        expected[col] = fields.getAsString(col)
                    }
                }
                db.insertOrThrow(name, null, fields)
                original[name] = expected
            }
            db.version = version
        }
        AppDatabase.open(context, name).use { room ->
            val db = room.openHelper.writableDatabase
            assertEquals(32, db.version)
            for ((name, fields) in original) {
                db.query("SELECT * FROM `$name`").use { c ->
                    assertTrue(name, c.moveToFirst())
                    fields.forEach { (col, value) -> assertEquals("$name.$col", value, c.getString(c.getColumnIndexOrThrow(col))) }
                    assertFalse(name, c.moveToNext())
                }
            }
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name LIKE 'media_recovery_%'").use {
                it.moveToFirst()
                assertEquals(7, it.getInt(0))
            }
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test fun migrateV18() = migrate(18)
    @Test fun migrateV31() = migrate(31)

    @Test fun recoverOnUpgradedDatabaseAndReopen() = runBlocking {
        migrate(31)
        val app = context.applicationContext as com.blitz.downloader.RecoveryValidationApp
        val storage = java.io.File(app.cacheDir, "upgraded-$name")
        val access = RecoveryMediaAccess(app,storage)
        val media = java.io.File(storage,"Download/bDouyin/videos/合成_7390000000000000001.mp4")
        media.parentFile!!.mkdirs()
        val bytes = instrumentation.context.assets.open("video-tap.mp4").use { it.readBytes() }
        media.writeBytes(bytes)
        val id = "7390000000000000001"
        val calls = app.requests.get()
        AppDatabase.open(context,name).use { db ->
            val old = db.downloadedVideoDao().getAll().single()
            val tags = db.videoTagDao().getTagsForVideo(old.awemeId)
            val feedback = db.videoTagFeedbackDao().countAll()
            suspend fun checkOriginal() {
                assertEquals(old,db.downloadedVideoDao().getByAwemeId(old.awemeId))
                assertEquals(tags,db.videoTagDao().getTagsForVideo(old.awemeId))
                assertEquals(feedback,db.videoTagFeedbackDao().countAll())
                assertArrayEquals(bytes,media.readBytes())
            }
            app.fixtureRepository(db,access).scan()
            val dao = db.mediaRecoveryDao()
            val session = dao.lastCompleteScan()!!
            checkOriginal()
            dao.update(session.copy(state = "FETCHING"))
            val resumed = app.fixtureRepository(db,access)
            resumed.initialize()
            assertEquals("INTERRUPTED",dao.session(session.sessionId)!!.state)
            assertEquals(calls,app.requests.get())
            resumed.query(session.sessionId)
            checkOriginal()
            resumed.review(dao.candidates(session.sessionId,id).single(),true)
            val plan = resumed.writer.preview(dao.candidates(session.sessionId,id))
            checkOriginal()
            resumed.writer.commit(plan)
            checkOriginal()
            val batch = dao.batch(plan.parts.single())!!
            assertEquals("COMMITTED",batch.state)
            assertEquals(1L,DatabaseSnapshot.validate(java.io.File(storage,batch.backupLocator + "/blitz_downloader.db"))
                .counts.getValue("downloaded_videos"))
        }
        AppDatabase.open(context,name).use { db ->
            val repo = app.fixtureRepository(db,access)
            repo.initialize()
            val row = db.downloadedVideoDao().getByAwemeId(id)!!
            assertEquals(2,db.downloadedVideoDao().getAll().size)
            assertNotNull(access.videoSize(row.filePath))
            val export = com.blitz.downloader.download.MediaExportManager.resolveExportFiles(listOf(row),repo).single()
            assertArrayEquals(bytes,export.open().use { it.readBytes() })
            assertEquals(calls + 1,app.requests.get())
        }
    }

    @Test fun missingMigrationPreservesOldDatabase() {
        SQLiteDatabase.openOrCreateDatabase(path, null).use {
            it.execSQL("CREATE TABLE preserved(id INTEGER PRIMARY KEY)")
            it.execSQL("INSERT INTO preserved VALUES(998)")
            it.version = 90
        }
        AppDatabase.open(context, name).use {
            assertTrue(runCatching { it.openHelper.writableDatabase }.isFailure)
        }
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(90, db.version)
            db.rawQuery("SELECT id FROM preserved", null).use { c -> assertTrue(c.moveToFirst()); assertEquals(998, c.getInt(0)) }
        }
    }

    @Test fun creationRacesKeepOriginalRowAndHistory() = runBlocking {
        AppDatabase.open(context, name).use { db ->
            val downloads = db.downloadedVideoDao()
            val recovery = db.mediaRecoveryDao()
            recovery.insert(RecoverySession("s", 1, 1))
            recovery.insert(RecoveryBatch("b","o","s",1,"[]","digest",1))
            for (first in listOf("post","recovered")) {
                val old = DownloadedVideoEntity(awemeId = first, downloadType = first, userName = "合成",
                    watched = true, tagEditCount = 4, exportCount = 2)
                val row = downloads.insert(old)
                if (first == "recovered") recovery.insert(RecoveryRecord(first,row,"b",1,"{}"))
                db.openHelper.writableDatabase.execSQL("INSERT INTO video_tags(awemeId,tagName) VALUES(?,?)", arrayOf(first,"保留"))
                assertEquals(-1L, downloads.insert(old.copy(userName = "竞争记录", watched = false)))
                assertEquals(old.copy(id = row), downloads.getByAwemeId(first))
                assertEquals(first == "recovered", recovery.provenance(first) != null)
                db.openHelper.readableDatabase.query("SELECT tagName FROM video_tags WHERE awemeId=?", arrayOf(first)).use {
                    assertTrue(it.moveToFirst()); assertEquals("保留", it.getString(0))
                }
            }
        }
    }

    @Test fun protectedLifecycleDoesNotScheduleMaintenance(): Unit = runBlocking {
        val app = context.applicationContext as android.app.Application
        assertTrue(RecoveryProtection.enabled(app))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val checks = java.util.concurrent.atomic.AtomicInteger()
        val storage = object : BackupMaintenance.Storage {
            override fun scan(): BackupFileStore.Scan {
                checks.incrementAndGet()
                error("保护模式不应扫描备份")
            }
            override suspend fun create(kind: String): BackupFileStore.Entry = error("不应备份")
            override fun cleanup(now: Long, entries: List<BackupFileStore.Entry>, protectedId: String?): BackupFileStore.Cleanup = error("不应清理")
        }
        val coordinator = DatabaseBackupCoordinator(app, scope, BackupMaintenance(storage, { 0 }, { 0 }, { null }, {}))
        instrumentation.runOnMainSync {
            val activity = android.app.Activity()
            coordinator.onActivityStarted(activity)
            coordinator.onActivityResumed(activity)
        }
        kotlinx.coroutines.delay(150)
        assertEquals(0, checks.get())
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
