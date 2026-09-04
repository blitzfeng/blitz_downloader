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
}
