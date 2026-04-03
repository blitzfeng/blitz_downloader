package com.blitz.downloader.data

import android.content.Context
import com.blitz.downloader.data.db.AppDatabase
import com.blitz.downloader.data.db.DownloadedVideoEntity
import com.blitz.downloader.data.db.TagEntity
import com.blitz.downloader.data.db.VideoTagDao
import com.blitz.downloader.data.db.VideoTagEntity

/**
 * 标签的完整持久化与查询入口，统一管理：
 * - **标签名册**（[TagEntity] / `tags` 表）：先建标签、重命名、删除标签，独立于视频。
 * - **视频-标签关联**（[VideoTagEntity] / `video_tags` 表）：打标签、取消、按标签筛选视频。
 *
 * ### 典型流程
 * 1. [createTag] 新建标签（写入 `tags` 表，与视频无关）
 * 2. [addTag] / [setTags] 为视频打标签（写入 `video_tags` 表）
 * 3. [getAvailableTags] 展示所有可用标签（含未使用的预设标签）
 * 4. [getVideosByTag] 按标签筛选视频
 */
class VideoTagRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val videoTagDao = db.videoTagDao()
    private val tagDao = db.tagDao()

    // ──────────────────── 标签名册管理（tags 表） ────────────────────

    /**
     * 新建标签（写入 `tags` 名册）；同名标签已存在则忽略（幂等）。
     * sortOrder 自动设为当前最大值 + 1，新标签排在末尾。
     * 创建后可通过 [getAvailableTags] 查到，与任何视频无关。
     */
    suspend fun createTag(tagName: String) {
        val nextOrder = tagDao.getMaxSortOrder() + 1
        tagDao.insert(TagEntity(tagName = tagName.trim(), sortOrder = nextOrder))
    }

    /**
     * **收藏夹批量下载**：用收藏夹名对齐 `tags` 名册与 `video_tags`。
     * - 若 [folderName] 已在 `tags` 表中：仅为该视频写入 `video_tags` 关联。
     * - 若不存在：先 [createTag] 再 [addTag]（两者均为幂等）。
     *
     * 与工程内数据库文档中 `collects`、`collectionType` 及标签双表约定一致。
     */
    suspend fun ensureCollectFolderTagLinked(awemeId: String, folderName: String) {
        val name = folderName.trim()
        if (name.isEmpty()) return
        createTag(name)
        addTag(awemeId, name)
    }

    /**
     * 删除标签：同步从 `tags` 名册和 `video_tags` 所有视频关联中移除。
     */
    suspend fun deleteTag(tagName: String) {
        tagDao.delete(tagName)
        videoTagDao.deleteTagFromAllVideos(tagName)
    }

    /**
     * 重命名标签：同步更新 `tags` 名册和 `video_tags` 所有关联行。
     *
     * 注意：若同一视频已同时持有旧名和新名两个标签，`video_tags` 的批量
     * UPDATE 会触发主键冲突；UI 层应提前提示用户避免此情况。
     */
    suspend fun renameTag(oldName: String, newName: String) {
        val trimmed = newName.trim()
        tagDao.rename(oldName, trimmed)
        videoTagDao.renameTag(oldName, trimmed)
    }

    /**
     * 返回所有可用标签（`tags` 表全量，含未关联任何视频的预设标签）。
     * 按 [TagEntity.sortOrder] 升序排列（由用户在标签管理页拖拽设定）。
     * 供打标签 UI 展示可选列表。
     */
    suspend fun getAvailableTags(): List<String> = tagDao.getAll()

    /**
     * 批量更新标签排序：将 [orderedNames] 的下标写入各标签的 `sortOrder`。
     * 由标签管理页在用户拖拽结束后调用。
     */
    suspend fun reorderTags(orderedNames: List<String>) {
        orderedNames.forEachIndexed { index, name ->
            tagDao.updateSortOrder(name, index)
        }
    }

    // ──────────────────── 视频打标签（video_tags 表） ────────────────────

    /** 为视频添加单个标签；重复调用幂等。 */
    suspend fun addTag(awemeId: String, tagName: String) {
        videoTagDao.insert(VideoTagEntity(awemeId = awemeId, tagName = tagName.trim()))
    }

    /** 为视频批量添加标签；已存在的自动跳过。 */
    suspend fun addTags(awemeId: String, tagNames: Collection<String>) {
        videoTagDao.insertAll(tagNames.map { VideoTagEntity(awemeId = awemeId, tagName = it.trim()) })
    }

    /**
     * 用新标签集合**覆盖**某视频的所有标签（先清空再写入）。
     * 用于标签编辑确认保存场景。
     */
    suspend fun setTags(awemeId: String, tagNames: Collection<String>) {
        videoTagDao.deleteAllForVideo(awemeId)
        videoTagDao.insertAll(tagNames.map { VideoTagEntity(awemeId = awemeId, tagName = it.trim()) })
    }

    /** 删除某视频的某个标签。 */
    suspend fun removeTag(awemeId: String, tagName: String) {
        videoTagDao.delete(awemeId, tagName)
    }

    /** 清空某视频的所有标签。 */
    suspend fun clearTags(awemeId: String) {
        videoTagDao.deleteAllForVideo(awemeId)
    }

    /** 查询某视频已打的所有标签，按字母顺序返回。 */
    suspend fun getTagsForVideo(awemeId: String): List<String> =
        videoTagDao.getTagsForVideo(awemeId)

    /**
     * 批量查询多个视频的标签，返回 awemeId → 标签列表 的 Map。
     * 未打过标签的视频不在 Map 中（取时用 [getOrDefault] 返回空列表）。
     */
    suspend fun getTagsMapForVideos(awemeIds: List<String>): Map<String, List<String>> {
        if (awemeIds.isEmpty()) return emptyMap()
        return videoTagDao.getTagsForVideos(awemeIds)
            .groupBy({ it.awemeId }, { it.tagName })
    }

    /** 列出 `video_tags` 中实际使用过的标签（去重，按字母顺序）。 */
    suspend fun getAllUsedTags(): List<String> =
        videoTagDao.getAllTags()

    /**
     * 按标签筛选视频，返回完整 [DownloadedVideoEntity] 列表，按下载时间倒序。
     */
    suspend fun getVideosByTag(tagName: String): List<DownloadedVideoEntity> =
        videoTagDao.getVideosByTag(tagName)

    /** 统计每个标签对应的视频数，按数量倒序。 */
    suspend fun getTagsWithCount(): List<VideoTagDao.TagCount> =
        videoTagDao.getTagsWithCount()
}
