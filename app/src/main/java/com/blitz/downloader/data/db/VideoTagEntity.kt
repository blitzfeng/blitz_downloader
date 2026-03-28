package com.blitz.downloader.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 视频自定义标签关联表。
 *
 * 设计原则（方案 C，去规范化关联表）：
 * - 标签名直接存储，无独立 `tags` 表；重命名标签通过批量 UPDATE 实现。
 * - `(awemeId, tagName)` 复合主键保证同一视频不重复打同一标签。
 * - `tagName` 单独建索引，支持「按标签筛选视频」的高效查询。
 * - 外键级联删除：[DownloadedVideoEntity] 被删除时，对应标签行自动清理。
 */
@Entity(
    tableName = "video_tags",
    primaryKeys = ["awemeId", "tagName"],
    indices = [Index("tagName")],
    foreignKeys = [
        ForeignKey(
            entity = DownloadedVideoEntity::class,
            parentColumns = ["awemeId"],
            childColumns = ["awemeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class VideoTagEntity(
    /** 对应 [DownloadedVideoEntity.awemeId]，作品唯一标识。 */
    val awemeId: String,
    /** 用户自定义标签名称，同一视频可有多个不同标签。 */
    val tagName: String,
)
