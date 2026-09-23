package com.blitz.downloader.data.db

import androidx.room.Entity
import androidx.room.Index

/** 持久化列表来源。当前仅 [LIKE] 可创建；collectionId 为未来收藏夹预留。 */
object LikedListIndexSourceType {
    const val LIKE = "like"
}

object LikedListIndexSessionState {
    const val RUNNING = "running"
    const val COMPLETE_LIMIT = "complete_limit"
    const val COMPLETE_REMOTE = "complete_remote"
    const val RECOVERY_REQUIRED = "recovery_required"
}

/**
 * 一个不透明 cursor 链的检查点。sourceKey 是规范化的唯一身份；collectionId 保持可空，
 * 因而未来收藏夹不必重塑表结构。
 */
@Entity(tableName = "liked_list_index_session", primaryKeys = ["sourceKey"])
data class LikedListIndexSessionEntity(
    val sourceKey: String,
    val sourceType: String,
    val ownerSecUserId: String,
    val collectionId: String? = null,
    val state: String,
    val maxItems: Int,
    val indexedCount: Int = 0,
    val nextCursor: Long = 0L,
    val nextSourcePosition: Long = 0L,
    /** 已浏览到的本地条目偏移；恢复时从其前两项附近呈现。 */
    val lastViewedOffset: Int = 0,
    val lastSuccessfulPageAtMillis: Long = 0L,
    val recoveryError: String? = null,
)

/** 仅保存稳定展示/排序元数据；封面和媒体 URL 必须在消费时重新解析。 */
@Entity(
    tableName = "liked_list_index_item",
    primaryKeys = ["sourceKey", "awemeId"],
    indices = [
        Index(value = ["sourceKey", "sourcePosition"], name = "index_liked_list_index_item_source_order"),
        Index(value = ["sourceKey", "createTime"], name = "index_liked_list_index_item_source_create_time"),
    ],
)
data class LikedListIndexItemEntity(
    val sourceKey: String,
    val awemeId: String,
    val sourcePosition: Long,
    val title: String,
    val authorNickname: String,
    val authorSecUserId: String,
    val description: String,
    val createTime: Long,
    val isPhoto: Boolean,
    val collectStat: Int,
    val userDigged: Int,
    val diggCount: Long,
    val collectCount: Long,
    /** 可过期的浏览缓存；绝不能直接作为下载凭据。 */
    val coverUrl: String? = null,
    val mediaUrl: String? = null,
)
