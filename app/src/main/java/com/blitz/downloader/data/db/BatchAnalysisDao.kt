package com.blitz.downloader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BatchAnalysisDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSession(session: BatchAnalysisSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveItems(items: List<BatchAnalysisItemEntity>)

    @Query("SELECT * FROM batch_analysis_session ORDER BY createdAtMillis DESC LIMIT 1")
    suspend fun latestSession(): BatchAnalysisSessionEntity?

    @Query("SELECT * FROM batch_analysis_session ORDER BY createdAtMillis DESC LIMIT 1")
    fun observeSession(): Flow<BatchAnalysisSessionEntity?>

    @Query("SELECT * FROM batch_analysis_item ORDER BY position")
    fun observeItems(): Flow<List<BatchAnalysisItemEntity>>

    @Query("SELECT * FROM batch_analysis_item WHERE sessionId = :sessionId ORDER BY position")
    suspend fun items(sessionId: String): List<BatchAnalysisItemEntity>

    @Query("UPDATE batch_analysis_session SET reviewJson = :json WHERE id = :sessionId")
    suspend fun saveReview(sessionId: String, json: String)

    @Query("UPDATE batch_analysis_session SET finished = 1 WHERE id = :sessionId")
    suspend fun finish(sessionId: String)

    @Query("UPDATE batch_analysis_item SET status = :status, error = :error, analysisId = :analysisId, suggestedTags = :tags WHERE sessionId = :sessionId AND awemeId = :awemeId")
    suspend fun setResult(sessionId: String, awemeId: String, status: String, error: String = "", analysisId: Long? = null, tags: String = "")

    @Query("UPDATE batch_analysis_item SET reviewTags = :tags WHERE sessionId = :sessionId AND awemeId = :awemeId")
    suspend fun setReviewTags(sessionId: String, awemeId: String, tags: String)

    @Query("UPDATE batch_analysis_item SET status = 'failed', error = :reason WHERE sessionId = :sessionId AND status IN ('waiting', 'running')")
    suspend fun failUnfinished(sessionId: String, reason: String)

    @Query("DELETE FROM batch_analysis_item WHERE sessionId != :sessionId")
    suspend fun deleteOldItems(sessionId: String)

    @Query("DELETE FROM batch_analysis_session WHERE id != :sessionId")
    suspend fun deleteOldSessions(sessionId: String)
}
