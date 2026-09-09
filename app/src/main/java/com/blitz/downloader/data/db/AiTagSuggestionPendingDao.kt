package com.blitz.downloader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AiTagSuggestionPendingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pending: AiTagSuggestionPendingEntity)

    @Query("SELECT * FROM ai_tag_suggestion_pending WHERE awemeId = :awemeId")
    suspend fun getByAwemeId(awemeId: String): AiTagSuggestionPendingEntity?

    @Query("SELECT * FROM ai_tag_suggestion_pending WHERE awemeId IN (:awemeIds)")
    suspend fun getByAwemeIds(awemeIds: Collection<String>): List<AiTagSuggestionPendingEntity>

    @Query("DELETE FROM ai_tag_suggestion_pending WHERE awemeId = :awemeId")
    suspend fun deleteByAwemeId(awemeId: String): Int

    @Query("DELETE FROM ai_tag_suggestion_pending WHERE awemeId IN (:awemeIds)")
    suspend fun deleteByAwemeIds(awemeIds: Collection<String>): Int
}
