package com.blitz.downloader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PreferenceProfileDao {

    @Insert
    suspend fun insert(profile: PreferenceProfileEntity)

    /** 最新一版摘要；从未生成过时返回 `null`。 */
    @Query("SELECT * FROM preference_profile ORDER BY updatedAtMillis DESC LIMIT 1")
    suspend fun getLatest(): PreferenceProfileEntity?

    /** 累计反馈样本数达到阈值判断所需的"上一版摘要覆盖到第几条"参考——即最新一条的 [PreferenceProfileEntity.sampleCount]。 */
    @Query("SELECT sampleCount FROM preference_profile ORDER BY updatedAtMillis DESC LIMIT 1")
    suspend fun getLatestSampleCount(): Int?
}
