package com.blitz.downloader.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blitz.downloader.data.db.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiReferenceManagementTest {
    @Test fun removeRestoreFiltersBeforeLimitAndPreservesFeedbackAndTags() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.tagDao().insert(TagEntity("穿搭", id = 101))
            for (id in listOf("old", "new")) {
                db.downloadedVideoDao().insert(DownloadedVideoEntity(awemeId = id,
                    downloadType = "post", userName = "作者", videoAuthorSecUserId = "author", desc = id))
                db.videoTagDao().insert(VideoTagEntity(id, "穿搭"))
            }
            val feedback = db.videoTagFeedbackDao()
            feedback.insertAll(listOf(
                VideoTagFeedbackEntity(awemeId = "old", analysisId = 1, tagId = 101, kind = "ACCEPTED",
                    confidence = 0.8f, createdAtMillis = 1, evidenceImagePath = "old.jpg"),
                VideoTagFeedbackEntity(awemeId = "new", analysisId = 2, tagId = 101, kind = "ACCEPTED",
                    confidence = 0.8f, createdAtMillis = 2, evidenceImagePath = "new.jpg"),
                VideoTagFeedbackEntity(awemeId = "new", analysisId = 2, tagId = 101, kind = "REJECTED",
                    confidence = 0.8f, createdAtMillis = 3, evidenceImagePath = "new.jpg"),
            ))
            val policy = db.aiReferenceDao()
            assertEquals("new", feedback.getRecentEvidenceByAuthor("author", "ACCEPTED", 1).single().awemeId)
            db.preferenceProfileDao().insert(PreferenceProfileEntity(version = 1, profileText = "旧摘要", sampleCount = 3, updatedAtMillis = 1))
            policy.setExcluded(listOf("new"), true, 10)
            assertNull(db.preferenceProfileDao().getLatest())
            assertEquals("old", feedback.getRecentEvidenceByAuthor("author", "ACCEPTED", 1).single().awemeId)
            assertTrue(feedback.getRecentEvidenceByAuthor("author", "REJECTED", 1).isEmpty())
            assertEquals("old", feedback.getRecentEvidenceGlobal("ACCEPTED", 1).single().awemeId)
            assertEquals("old", feedback.getRecentConfirmedGlobal(1).single().awemeId)
            assertEquals("old", feedback.getRecentConfirmedByAuthor("author", 1).single().awemeId)
            assertEquals("old", feedback.getRecentByAuthor("author", 1).single().awemeId)
            assertEquals("old", feedback.getRecentGlobal(1).single().awemeId)
            assertEquals(3, feedback.countAll())
            assertEquals(listOf("穿搭"), db.videoTagDao().getTagsForVideo("new"))
            assertNotNull(db.downloadedVideoDao().getByAwemeId("new"))
            assertTrue(policy.observeReferenceRows().first().filter { it.awemeId == "new" }.all { it.excluded })
            feedback.insertAll(listOf(VideoTagFeedbackEntity(awemeId = "new", analysisId = 3, tagId = 101,
                kind = "ACCEPTED", confidence = 0.9f, createdAtMillis = 20, evidenceImagePath = "new2.jpg")))
            assertEquals("old", feedback.getRecentEvidenceByAuthor("author", "ACCEPTED", 1).single().awemeId)
            policy.setExcluded(listOf("new"), false, 30)
            assertEquals("new", feedback.getRecentEvidenceByAuthor("author", "ACCEPTED", 1).single().awemeId)
            assertTrue(policy.excludedVideoIds().isEmpty())
        } finally { db.close() }
    }

    @Test fun migration29To30PreservesDataAndExclusionSurvivesReopen() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "ai-reference-migration-test.db"
        context.deleteDatabase(name)
        val schema = JSONObject(instrumentation.context.assets.open(
            "com.blitz.downloader.data.db.AppDatabase/29.json").bufferedReader().use { it.readText() })
            .getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(29) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.getJSONArray("indices")
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j)
                            .getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    db.execSQL("INSERT INTO video_tag_feedback (id, awemeId, analysisId, tagId, kind, confidence, createdAtMillis, evidenceImagePath) VALUES (1, 'old', 1, 101, 'ACCEPTED', 0.8, 1, 'old.jpg')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase
        helper.close()
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_29_30).build()
        try {
            val db = open()
            try {
                assertEquals(1, db.videoTagFeedbackDao().countAll())
                assertTrue(db.aiReferenceDao().excludedVideoIds().isEmpty())
                db.aiReferenceDao().setExcluded(listOf("old"), true, 1)
            } finally { db.close() }
            val reopened = open()
            try {
                assertEquals(listOf("old"), reopened.aiReferenceDao().excludedVideoIds())
                assertEquals(1, reopened.videoTagFeedbackDao().countAll())
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
