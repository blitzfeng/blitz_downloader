package com.blitz.downloader.data.db

import androidx.room.Entity

/**
 * 作者-标签高频统计缓存（`author_tag_frequency` 表）。
 *
 * 由 [AuthorTagFrequencyDao.recompute] 全量重算写入，只服务于管理页批量打标签弹窗的
 * 自动预勾选（见 [com.blitz.downloader.data.VideoTagRepository.getHighFrequencyTagsForAuthor]）。
 * **不随打标签/下载操作实时增量更新**，需要设置页「重新分析标签数据」手动触发。
 */
@Entity(tableName = "author_tag_frequency", primaryKeys = ["secUserId", "tagName"])
data class AuthorTagFrequencyEntity(
    val secUserId: String,
    val tagName: String,
    val count: Int,
)
