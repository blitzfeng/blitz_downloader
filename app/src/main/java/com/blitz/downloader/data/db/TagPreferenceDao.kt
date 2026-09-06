package com.blitz.downloader.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface TagPreferenceDao {

    @Query("SELECT * FROM tag_preference WHERE tagId = :tagId")
    suspend fun getByTagId(tagId: Long): TagPreferenceEntity?

    @Query("SELECT * FROM tag_preference")
    suspend fun getAll(): List<TagPreferenceEntity>

    @Query("DELETE FROM tag_preference")
    suspend fun clearAll()

    /**
     * 从 [VideoTagFeedbackEntity] 按 `tagId` 全量聚合重算。`acceptanceRate` 用 `COALESCE` 兜底
     * 除零（某标签只有 `MISSED` 反馈、从未被建议过时 `suggestedCount = 0`），避免写入 `NULL`
     * 违反 [TagPreferenceEntity.acceptanceRate] 的 `NOT NULL` 约束。
     */
    @Query(
        """
        INSERT INTO tag_preference
            (tagId, suggestedCount, acceptedCount, rejectedCount, missedCount, acceptanceRate, recommendedThreshold, updatedAtMillis)
        SELECT
            tagId,
            SUM(CASE WHEN kind IN ('ACCEPTED', 'REJECTED') THEN 1 ELSE 0 END) AS suggestedCount,
            SUM(CASE WHEN kind = 'ACCEPTED' THEN 1 ELSE 0 END) AS acceptedCount,
            SUM(CASE WHEN kind = 'REJECTED' THEN 1 ELSE 0 END) AS rejectedCount,
            SUM(CASE WHEN kind = 'MISSED' THEN 1 ELSE 0 END) AS missedCount,
            COALESCE(
                CAST(SUM(CASE WHEN kind = 'ACCEPTED' THEN 1 ELSE 0 END) AS REAL)
                    / NULLIF(SUM(CASE WHEN kind IN ('ACCEPTED', 'REJECTED') THEN 1 ELSE 0 END), 0),
                0
            ) AS acceptanceRate,
            0.5 AS recommendedThreshold,
            :nowMillis AS updatedAtMillis
        FROM video_tag_feedback
        GROUP BY tagId
        """,
    )
    suspend fun insertFromAggregate(nowMillis: Long)

    /** 清空重建：每次反馈写入之后触发（不是每次分析请求都重算）。 */
    @Transaction
    suspend fun recomputeAll(nowMillis: Long) {
        clearAll()
        insertFromAggregate(nowMillis)
    }
}
