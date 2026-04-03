package com.blitz.downloader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 独立标签名称表，管理标签的生命周期（创建、重命名、删除），与视频无关。
 *
 * 与 [VideoTagEntity] 的关系：
 * - [TagEntity] 存"有哪些标签"（标签名册）。
 * - [VideoTagEntity] 存"哪个视频打了哪个标签"（关联关系）。
 * - 删除 [TagEntity] 时，需同步删除 [VideoTagEntity] 中对应的行（Repository 层负责）。
 */
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val tagName: String,
    /** 展示顺序，数值越小越靠前；用户在标签管理页拖拽排序后持久化。 */
    val sortOrder: Int = 0,
)
