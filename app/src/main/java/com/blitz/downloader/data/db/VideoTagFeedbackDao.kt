package com.blitz.downloader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface VideoTagFeedbackDao {

    /** 一次「AI 建议 → 用户确认」按标签分类写入的多行。 */
    @Insert
    suspend fun insertAll(rows: List<VideoTagFeedbackEntity>)

    /**
     * 该视频是否已经产生过反馈——供上层判断"未使用 AI 建议的手动打标签路径不产生反馈记录"
     * 之外的场景使用；当前主要用途是测试断言。
     */
    @Query("SELECT COUNT(*) FROM video_tag_feedback WHERE awemeId = :awemeId")
    suspend fun countByAwemeId(awemeId: String): Int

    /**
     * 删除指定视频的反馈记录（在撤销整组处理恢复待整理状态时使用）。
     */
    @Query("DELETE FROM video_tag_feedback WHERE awemeId IN (:awemeIds)")
    suspend fun deleteByAwemeIds(awemeIds: Collection<String>): Int

    /**
     * 某作者最近 [limit] 条反馈样例，按时间倒序——few-shot 采样"同作者优先"。
     * 需要联查 `downloaded_videos` 取 `videoAuthorSecUserId`（本表不冗余该字段）。
     */
    @Query(
        """
        SELECT f.* FROM video_tag_feedback f
        INNER JOIN downloaded_videos v ON v.awemeId = f.awemeId
        WHERE v.videoAuthorSecUserId = :secUserId
        ORDER BY f.createdAtMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentByAuthor(secUserId: String, limit: Int): List<VideoTagFeedbackEntity>

    /** 全局最近 [limit] 条反馈样例，按时间倒序——同作者样例不足时补齐用。 */
    @Query("SELECT * FROM video_tag_feedback ORDER BY createdAtMillis DESC LIMIT :limit")
    suspend fun getRecentGlobal(limit: Int): List<VideoTagFeedbackEntity>

    /** 反馈总行数，供 [com.blitz.downloader.data.AiTagSuggestionRepository] 判断是否达到 PreferenceProfile 重算阈值。 */
    @Query("SELECT COUNT(*) FROM video_tag_feedback")
    suspend fun countAll(): Int

    /**
     * 某作者最近 [limit] 行"最终确认"反馈（`ACCEPTED`/`MISSED`，代表视频最终持有的标签），
     * 联查 `downloaded_videos.desc` 供组装 few-shot 样例——一个视频可能贡献多行（多个标签），
     * 调用方需要按 [ConfirmedFeedbackRow.awemeId] 分组还原成"一条视频对应一份样例"。
     */
    @Query(
        """
        SELECT f.awemeId AS awemeId, v.desc AS `desc`, f.tagId AS tagId
        FROM video_tag_feedback f
        INNER JOIN downloaded_videos v ON v.awemeId = f.awemeId
        WHERE v.videoAuthorSecUserId = :secUserId AND f.kind IN ('ACCEPTED', 'MISSED')
        ORDER BY f.createdAtMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentConfirmedByAuthor(secUserId: String, limit: Int): List<ConfirmedFeedbackRow>

    /** 全局版 [getRecentConfirmedByAuthor]，同作者样例不足时补齐用，也是生成 PreferenceProfile 摘要的输入来源。 */
    @Query(
        """
        SELECT f.awemeId AS awemeId, v.desc AS `desc`, f.tagId AS tagId
        FROM video_tag_feedback f
        INNER JOIN downloaded_videos v ON v.awemeId = f.awemeId
        WHERE f.kind IN ('ACCEPTED', 'MISSED')
        ORDER BY f.createdAtMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentConfirmedGlobal(limit: Int): List<ConfirmedFeedbackRow>
}

/** [VideoTagFeedbackDao.getRecentConfirmedByAuthor]/[VideoTagFeedbackDao.getRecentConfirmedGlobal] 的查询投影。 */
data class ConfirmedFeedbackRow(val awemeId: String, val desc: String, val tagId: Long)
