package com.blitz.downloader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AiReferenceDao {
    /** 修改规则和使旧摘要失效必须同时提交，失败时一起回滚。 */
    @Transaction
    suspend fun setExcluded(awemeIds: List<String>, excluded: Boolean, changedAtMillis: Long) {
        val ids = awemeIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return
        if (excluded) exclude(ids.map { AiReferenceExclusionEntity(it, changedAtMillis) })
        else ids.chunked(900).forEach { restore(it) }
        invalidatePreferenceProfiles()
    }

    @Query("DELETE FROM preference_profile")
    suspend fun invalidatePreferenceProfiles()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun exclude(rows: List<AiReferenceExclusionEntity>)

    @Query("DELETE FROM ai_reference_exclusion WHERE awemeId IN (:awemeIds)")
    suspend fun restore(awemeIds: List<String>)

    @Query("SELECT awemeId FROM ai_reference_exclusion ORDER BY awemeId")
    suspend fun excludedVideoIds(): List<String>

    @Query("""
        SELECT f.id AS feedbackId, f.awemeId, COALESCE(v.userName, '') AS authorName,
            COALESCE(v.videoAuthorSecUserId, '') AS secUserId, COALESCE(v.desc, '') AS `desc`,
            COALESCE(t.tagName, '已删除标签 #' || f.tagId) AS tagName,
            f.kind, f.evidenceImagePath, f.createdAtMillis,
            CASE WHEN e.awemeId IS NULL THEN 0 ELSE 1 END AS excluded
        FROM video_tag_feedback f
        LEFT JOIN downloaded_videos v ON v.awemeId = f.awemeId
        LEFT JOIN tags t ON t.id = f.tagId
        LEFT JOIN ai_reference_exclusion e ON e.awemeId = f.awemeId
        ORDER BY f.createdAtMillis DESC, f.id DESC
    """)
    fun observeReferenceRows(): Flow<List<AiReferenceRow>>
}

/** 管理页使用完整反馈历史，不能沿用已经过滤或限制数量的请求采样查询。 */
data class AiReferenceRow(
    val feedbackId: Long,
    val awemeId: String,
    val authorName: String,
    val secUserId: String,
    val desc: String,
    val tagName: String,
    val kind: String,
    val evidenceImagePath: String?,
    val createdAtMillis: Long,
    val excluded: Boolean,
)
