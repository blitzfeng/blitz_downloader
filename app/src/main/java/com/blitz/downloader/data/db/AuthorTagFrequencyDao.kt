package com.blitz.downloader.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface AuthorTagFrequencyDao {

    /** 某作者出现次数 >= [threshold] 的标签，按出现次数倒序。 */
    @Query(
        """
        SELECT tagName FROM author_tag_frequency
        WHERE secUserId = :secUserId AND count >= :threshold
        ORDER BY count DESC
        """,
    )
    suspend fun getHighFrequencyTags(secUserId: String, threshold: Int): List<String>

    @Query("DELETE FROM author_tag_frequency")
    suspend fun clearAll()

    /**
     * 全量重算：按 `videoAuthorSecUserId + tagName` 聚合 `video_tags` 与 `downloaded_videos`。
     * 空 `videoAuthorSecUserId`（老记录）被 WHERE 天然排除，不会把互不相关的老视频
     * 错误聚合成"同一作者"。全部在 SQLite 内完成，不经过 Kotlin 侧循环。
     */
    @Query(
        """
        INSERT INTO author_tag_frequency (secUserId, tagName, count)
        SELECT v.videoAuthorSecUserId, t.tagName, COUNT(*)
        FROM downloaded_videos v
        INNER JOIN video_tags t ON v.awemeId = t.awemeId
        WHERE v.videoAuthorSecUserId != ''
        GROUP BY v.videoAuthorSecUserId, t.tagName
        """,
    )
    suspend fun insertFromAggregate()

    /** 清空重建：设置页「重新分析标签数据」的入口。 */
    @Transaction
    suspend fun recompute() {
        clearAll()
        insertFromAggregate()
    }

    @Query("SELECT COUNT(DISTINCT secUserId) FROM author_tag_frequency")
    suspend fun countDistinctAuthors(): Int

    @Query("SELECT COUNT(*) FROM author_tag_frequency")
    suspend fun countRows(): Int

    /**
     * 某作者出现次数 >= [threshold] 的标签，附带该作者已下载视频总数（[AuthorTagRatioRow.sampleCount]）
     * 与该标签的占比（[AuthorTagRatioRow.ratio] = count / sampleCount）——供 `ai-tag-suggestions`
     * 的 AuthorProfile 上下文使用，比只给一份不带权重的标签名单更能体现"这个先验有多强"。
     *
     * `NULLIF(s.total, 0)` 防止分母为 0 时除零：`sampleCount` 理论上不会是 0（能出现在
     * `author_tag_frequency` 里说明该作者至少有过打标签的视频），但仍做防御，除零时
     * [AuthorTagRatioRow.ratio] 为 `null` 而不是抛异常或给出无意义的值。
     *
     * **不是** [getHighFrequencyTags] 的替代——那个方法继续服务批量打标签弹窗的自动预勾选，
     * 只需要标签名单不需要占比数字，两者各自独立读同一张缓存表。
     */
    @Query(
        """
        SELECT t.id AS tagId, atf.tagName AS tagName, atf.count AS count, s.total AS sampleCount,
               CAST(atf.count AS REAL) / NULLIF(s.total, 0) AS ratio
        FROM author_tag_frequency atf
        INNER JOIN tags t ON t.tagName = atf.tagName
        INNER JOIN (SELECT COUNT(*) AS total FROM downloaded_videos WHERE videoAuthorSecUserId = :secUserId) s
        WHERE atf.secUserId = :secUserId AND atf.count >= :threshold
        ORDER BY atf.count DESC
        """,
    )
    suspend fun getHighFrequencyTagsWithRatio(secUserId: String, threshold: Int): List<AuthorTagRatioRow>
}

/** [AuthorTagFrequencyDao.getHighFrequencyTagsWithRatio] 的查询投影。 */
data class AuthorTagRatioRow(
    val tagId: Long,
    val tagName: String,
    val count: Int,
    val sampleCount: Int,
    val ratio: Float?,
)
