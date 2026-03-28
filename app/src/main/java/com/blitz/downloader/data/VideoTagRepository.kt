package com.blitz.downloader.data

import android.content.Context
import com.blitz.downloader.data.db.AppDatabase
import com.blitz.downloader.data.db.VideoTagDao
import com.blitz.downloader.data.db.VideoTagEntity
import com.blitz.downloader.data.db.DownloadedVideoEntity

/**
 * 视频自定义标签的持久化与查询。
 *
 * ### 常用场景
 * - 打标签：[addTag] / [setTags]
 * - 移除标签：[removeTag] / [clearTags]
 * - 标签筛选：[getVideosByTag]
 * - 标签管理：[getAllTags]、[getTagsWithCount]、[renameTag]
 */
class VideoTagRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).videoTagDao()

    /** 为视频添加单个标签；重复调用幂等。 */
    suspend fun addTag(awemeId: String, tagName: String) {
        dao.insert(VideoTagEntity(awemeId = awemeId, tagName = tagName.trim()))
    }

    /** 为视频批量添加标签；已存在的自动跳过。 */
    suspend fun addTags(awemeId: String, tagNames: Collection<String>) {
        dao.insertAll(tagNames.map { VideoTagEntity(awemeId = awemeId, tagName = it.trim()) })
    }

    /**
     * 用新标签集合**覆盖**某视频的所有标签（先清空再写入）。
     * 用于标签编辑确认保存场景。
     */
    suspend fun setTags(awemeId: String, tagNames: Collection<String>) {
        dao.deleteAllForVideo(awemeId)
        dao.insertAll(tagNames.map { VideoTagEntity(awemeId = awemeId, tagName = it.trim()) })
    }

    /** 删除某视频的某个标签。 */
    suspend fun removeTag(awemeId: String, tagName: String) {
        dao.delete(awemeId, tagName)
    }

    /** 清空某视频的所有标签。 */
    suspend fun clearTags(awemeId: String) {
        dao.deleteAllForVideo(awemeId)
    }

    /** 查询某视频的所有标签，按字母顺序返回。 */
    suspend fun getTagsForVideo(awemeId: String): List<String> =
        dao.getTagsForVideo(awemeId)

    /** 列出库中全部出现过的标签（去重，按字母顺序）。 */
    suspend fun getAllTags(): List<String> =
        dao.getAllTags()

    /** 列出每个标签及其对应的视频数，按数量倒序。 */
    suspend fun getTagsWithCount(): List<VideoTagDao.TagCount> =
        dao.getTagsWithCount()

    /**
     * 按标签筛选视频，返回完整 [DownloadedVideoEntity] 列表，按下载时间倒序。
     *
     * 管理页过滤示例：
     * ```kotlin
     * val videos = videoTagRepository.getVideosByTag("搞笑")
     * ```
     */
    suspend fun getVideosByTag(tagName: String): List<DownloadedVideoEntity> =
        dao.getVideosByTag(tagName)

    /**
     * 重命名标签（库中所有打了旧名标签的视频，统一更新为新名）。
     *
     * 注意：若同一视频已同时拥有旧名和新名标签，会触发主键冲突导致部分更新失败；
     * 调用方需事先提示用户避免此情况，或在此处做去重处理。
     */
    suspend fun renameTag(oldName: String, newName: String) {
        dao.renameTag(oldName, newName.trim())
    }
}
