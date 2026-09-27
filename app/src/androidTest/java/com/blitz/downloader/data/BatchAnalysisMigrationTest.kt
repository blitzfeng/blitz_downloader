package com.blitz.downloader.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blitz.downloader.data.db.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchAnalysisMigrationTest {
    @Test
    fun upgrade28To29_preservesExistingDataAndValidatesNewSchema() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "batch-migration-test.db"
        context.deleteDatabase(name)
        val schema = JSONObject(instrumentation.context.assets.open(
            "com.blitz.downloader.data.db.AppDatabase/29.json").bufferedReader().readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(28) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        if (table.startsWith("batch_analysis_")) continue
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.getJSONArray("indices")
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    db.execSQL("INSERT INTO downloaded_videos VALUES (1, 'video', 'post', 'author', 1, 'video', '', '', 0, '', '', '', '', '', '', 0, 0, 0, 2, 0, 0, 0, 0)")
                    db.execSQL("INSERT INTO tags(tagName, sortOrder, parentTagName, id, description, collectFolderNames, enableAi, isExclusive) VALUES ('migration', 0, '', 901, '', '', 1, 0)")
                    db.execSQL("INSERT INTO ai_tag_suggestion_pending VALUES ('video', 123, 'migration', 1000)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase
        helper.close()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_28_29).build()
        try {
            assertEquals(2, db.downloadedVideoDao().getByAwemeId("video")!!.tagEditCount)
            assertEquals("migration", db.tagDao().getAll().single())
            assertEquals(123L, db.aiTagSuggestionPendingDao().getByAwemeId("video")!!.analysisId)
            val session = BatchAnalysisSessionEntity("test", 10, "1", finished = true)
            db.batchAnalysisDao().saveSession(session)
            db.batchAnalysisDao().saveItems(listOf(BatchAnalysisItemEntity("test", "video", 0, "{}", "failed", "network")))
            assertEquals("network", db.batchAnalysisDao().items("test").single().error)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
