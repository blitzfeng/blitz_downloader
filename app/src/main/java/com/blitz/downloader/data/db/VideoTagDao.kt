package com.blitz.downloader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface VideoTagDao {

    /** 为视频添加一个标签；若已存在则忽略（复合主键保证幂等）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: VideoTagEntity)

    /** 批量添加标签，已存在的自动跳过。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tags: List<VideoTagEntity>)

    /** 删除某视频的某个标签。 */
    @Query("DELETE FROM video_tags WHERE awemeId = :awemeId AND tagName = :tagName")
    suspend fun delete(awemeId: String, tagName: String)

    /** 删除某视频的所有标签。 */
    @Query("DELETE FROM video_tags WHERE awemeId = :awemeId")
    suspend fun deleteAllForVideo(awemeId: String)

    /** 查询某视频的所有标签，按字母顺序返回。 */
    @Query("SELECT tagName FROM video_tags WHERE awemeId = :awemeId ORDER BY tagName")
    suspend fun getTagsForVideo(awemeId: String): List<String>

    /** 列出库中所有出现过的标签（去重，按字母顺序）。 */
    @Query("SELECT DISTINCT tagName FROM video_tags ORDER BY tagName")
    suspend fun getAllTags(): List<String>

    /**
     * 按标签筛选视频：返回打了该标签的所有 [DownloadedVideoEntity.awemeId]。
     * 上层配合 [DownloadedVideoDao.getByAwemeId] 或 IN 查询使用。
     */
    @Query("SELECT awemeId FROM video_tags WHERE tagName = :tagName")
    suspend fun getAwemeIdsByTag(tagName: String): List<String>

    /** 按标签筛选，直接返回完整视频实体列表，按下载时间倒序。 */
    @Query(
        """
        SELECT v.* FROM downloaded_videos v
        INNER JOIN video_tags t ON v.awemeId = t.awemeId
        WHERE t.tagName = :tagName
        ORDER BY v.createdAtMillis DESC
        """,
    )
    suspend fun getVideosByTag(tagName: String): List<DownloadedVideoEntity>

    /**
     * 重命名标签（批量更新）。
     * 若目标名称已存在于同一视频，会触发主键冲突；调用前建议先用事务处理或去重。
     */
    @Query("UPDATE video_tags SET tagName = :newName WHERE tagName = :oldName")
    suspend fun renameTag(oldName: String, newName: String)

    /** 统计每个标签对应的视频数量，按数量倒序返回。 */
    @Query("SELECT tagName, COUNT(*) AS count FROM video_tags GROUP BY tagName ORDER BY count DESC")
    suspend fun getTagsWithCount(): List<TagCount>

    /** 标签与其对应视频数的聚合结果。 */
    data class TagCount(val tagName: String, val count: Int)
}
