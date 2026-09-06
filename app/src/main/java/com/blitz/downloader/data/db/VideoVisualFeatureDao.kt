package com.blitz.downloader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface VideoVisualFeatureDao {

    @Insert
    suspend fun insert(feature: VideoVisualFeatureEntity)

    /** 某次分析产出的视觉证据；一次分析至多一条。 */
    @Query("SELECT * FROM video_visual_feature WHERE analysisId = :analysisId")
    suspend fun getByAnalysisId(analysisId: Long): VideoVisualFeatureEntity?

    /** 某视频的历史视觉证据，按时间倒序，供相似案例检索按维度匹配用。 */
    @Query("SELECT * FROM video_visual_feature WHERE awemeId = :awemeId ORDER BY createdAtMillis DESC")
    suspend fun getByAwemeId(awemeId: String): List<VideoVisualFeatureEntity>
}
