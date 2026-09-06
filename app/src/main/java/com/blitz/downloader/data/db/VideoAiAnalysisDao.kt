package com.blitz.downloader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface VideoAiAnalysisDao {

    /** 插入一次分析记录，返回自增 [VideoAiAnalysisEntity.id]。 */
    @Insert
    suspend fun insert(analysis: VideoAiAnalysisEntity): Long

    /** 单次分析记录，供反馈写入时查回原始建议集合（[VideoAiAnalysisEntity.suggestedTagIds]）。 */
    @Query("SELECT * FROM video_ai_analysis WHERE id = :id")
    suspend fun getById(id: Long): VideoAiAnalysisEntity?

    /** 某视频的历史分析记录，按时间倒序。 */
    @Query("SELECT * FROM video_ai_analysis WHERE awemeId = :awemeId ORDER BY createdAtMillis DESC")
    suspend fun getByAwemeId(awemeId: String): List<VideoAiAnalysisEntity>
}
