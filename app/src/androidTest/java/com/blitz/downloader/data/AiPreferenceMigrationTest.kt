package com.blitz.downloader.data

import android.content.ContextWrapper
import androidx.room.Room
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.data.db.DatabaseBackupManager
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blitz.downloader.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiPreferenceMigrationTest {
    @Test
    fun version30PreservesDataAndValidatesEmptyLearningTables() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "preference-migration-test.db"
        context.deleteDatabase(name)
        val schema = JSONObject(instrumentation.context.assets.open(
            "com.blitz.downloader.data.db.AppDatabase/30.json",
        ).bufferedReader().use { it.readText() }).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(30) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = entity.getJSONArray("indices")
                            for (j in 0 until indices.length()) {
                                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                            }
                        }
                        db.execSQL("INSERT INTO downloaded_videos VALUES (1, 'preserved', 'post', 'author', 1, 'video', '', '', 0, '', '', '', '', '', '', 0, 0, 0, 2, 0, 0, 0, 0)")
                        db.execSQL("INSERT INTO tags VALUES ('测试', 0, '', 901, '', '', 1, 0)")
                        db.execSQL("INSERT INTO video_tag_feedback (awemeId,analysisId,tagId,kind,confidence,createdAtMillis,evidenceImagePath) VALUES ('preserved',10,901,'ACCEPTED',NULL,99,NULL)")
                        db.execSQL("INSERT INTO video_tag_feedback (awemeId,analysisId,tagId,kind,confidence,createdAtMillis,evidenceImagePath) VALUES ('preserved',9,901,'REJECTED',NULL,90,NULL)")
                        db.execSQL("INSERT INTO ai_reference_exclusion VALUES ('excluded-record', 123)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        helper.writableDatabase
        helper.close()
        (context.applicationContext as BlitzApp).preferenceRecovery.await()
        val source = context.getDatabasePath(name)
        val copiedName = "preference-migration-file.db"
        val streamName = "preference-migration-stream.db"
        fun restoreTarget(target: String) = object : ContextWrapper(context) {
            override fun getDatabasePath(name: String): java.io.File = context.getDatabasePath(target)
        }
        DatabaseBackupManager.restoreFrom(restoreTarget(copiedName),
            DatabaseBackupManager.BackupEntry(checkNotNull(source.parentFile), source, 100))
        source.inputStream().use { DatabaseBackupManager.restoreFromStream(restoreTarget(streamName), it) }
        for (databaseName in listOf(name, copiedName, streamName)) {
            val db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
                .addMigrations(AppDatabase.MIGRATION_30_31).build()
            try {
                assertEquals(2, db.downloadedVideoDao().getByAwemeId("preserved")!!.tagEditCount)
                assertEquals(901L, db.tagDao().getAllEntities().single().id)
                assertEquals(2, db.videoTagFeedbackDao().countAll())
                assertEquals(listOf("excluded-record"), db.aiReferenceDao().excludedVideoIds())
                assertNull(db.aiPreferenceDao().findCase("preserved"))
                assertNull(db.aiPreferenceDao().active())
                assertTrue(db.aiPreferenceDao().resumableRuns().isEmpty())
                assertTrue(db.aiPreferenceDao().nodes("absent").isEmpty())
                val projected = AiPreferenceCases(db).all(db.tagDao().getAllEntities()).single()
                assertEquals("preserved", projected.id)
                assertEquals(10L, projected.analysisId)
                assertEquals(com.blitz.downloader.model.PreferenceSource.LEGACY_UNKNOWN, projected.decisions.single().source)
            } finally {
                db.close()
                context.deleteDatabase(databaseName)
            }
        }
    }
}
