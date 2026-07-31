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

    /** 批量查询多个视频的标签；返回所有匹配行，上层自行按 awemeId 分组。 */
    @Query("SELECT awemeId, tagName FROM video_tags WHERE awemeId IN (:awemeIds) ORDER BY tagName")
    suspend fun getTagsForVideos(awemeIds: List<String>): List<VideoTagEntity>

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
     * 多标签**并集**筛选：打了 [tagNames] 中任一标签即命中，按下载时间倒序。
     * 用子查询而非 JOIN，避免一条视频命中多个标签时出现重复行。
     */
    @Query(
        """
        SELECT v.* FROM downloaded_videos v
        WHERE v.awemeId IN (SELECT awemeId FROM video_tags WHERE tagName IN (:tagNames))
        ORDER BY v.createdAtMillis DESC
        """,
    )
    suspend fun getVideosByAnyTag(tagNames: List<String>): List<DownloadedVideoEntity>

    /**
     * 多标签**交集**筛选：同时打了 [tagNames] 全部标签才命中，按下载时间倒序。
     *
     * [tagCount] 必须等于 `tagNames.size`（去重后）；`video_tags` 主键是
     * (awemeId, tagName)，同一视频不会重复计同一标签，故 `COUNT(DISTINCT tagName)`
     * 达到 [tagCount] 即代表全部命中。
     */
    @Query(
        """
        SELECT v.* FROM downloaded_videos v
        WHERE v.awemeId IN (
            SELECT awemeId FROM video_tags
            WHERE tagName IN (:tagNames)
            GROUP BY awemeId
            HAVING COUNT(DISTINCT tagName) = :tagCount
        )
        ORDER BY v.createdAtMillis DESC
        """,
    )
    suspend fun getVideosByAllTags(tagNames: List<String>, tagCount: Int): List<DownloadedVideoEntity>

    /** 删除某标签在所有视频上的关联行（标签被删除时使用）。 */
    @Query("DELETE FROM video_tags WHERE tagName = :tagName")
    suspend fun deleteTagFromAllVideos(tagName: String)

    /**
     * 重命名标签（批量更新）。
     * 若目标名称已存在于同一视频，会触发主键冲突；调用前建议先用事务处理或去重。
     */
    @Query("UPDATE video_tags SET tagName = :newName WHERE tagName = :oldName")
    suspend fun renameTag(oldName: String, newName: String)

    /** 统计每个标签对应的视频数量，按数量倒序返回。 */
    @Query("SELECT tagName, COUNT(*) AS count FROM video_tags GROUP BY tagName ORDER BY count DESC")
    suspend fun getTagsWithCount(): List<TagCount>

    /**
     * 统计每条视频身上打了几个标签（供管理页「按标签数量筛选」用）。
     * 只返回**有标签**的视频；结果里查不到的 awemeId 即 0 个标签。
     */
    @Query("SELECT awemeId, COUNT(*) AS count FROM video_tags GROUP BY awemeId")
    suspend fun getTagCountPerVideo(): List<VideoTagCount>

    /** 标签与其对应视频数的聚合结果。 */
    data class TagCount(val tagName: String, val count: Int)

    /** 单条视频与其标签数的聚合结果。 */
    data class VideoTagCount(val awemeId: String, val count: Int)
}
