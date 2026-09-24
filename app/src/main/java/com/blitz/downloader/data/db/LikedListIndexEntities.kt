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
    /** 旧版本的加载边界，仅保留兼容，不能当作真实浏览位置。 */
    val lastViewedOffset: Int = 0,
    val anchorAwemeId: String? = null,
    val anchorSourcePosition: Long? = null,
    val anchorOffsetPx: Int? = null,
    val lastSuccessfulPageAtMillis: Long = 0L,
    val recoveryError: String? = null,
)

/** 展示元数据与可过期的媒体缓存；下载前重新解析详情。 */
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
    /** 完整图集及逐图实况视频配对；null 表示旧版缺失，需要从详情补齐。 */
    val photoMediaJson: String? = null,
)
