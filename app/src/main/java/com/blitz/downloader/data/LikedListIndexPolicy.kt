package com.blitz.downloader.data

import com.blitz.downloader.data.db.LikedListIndexSessionState

/** 无 Android/网络依赖的索引边界规则，供协调器和 JVM 测试共用。 */
object LikedListIndexPolicy {
    fun terminalState(hasMore: Boolean): String = when {
        !hasMore -> LikedListIndexSessionState.COMPLETE_REMOTE
        else -> LikedListIndexSessionState.RUNNING
    }

    fun continuationError(requestCursor: Long, nextCursor: Long, itemCount: Int, hasMore: Boolean): String? = when {
        hasMore && itemCount == 0 -> "服务端在仍有下一页时返回空分页"
        hasMore && nextCursor == requestCursor -> "服务端返回未推进的续拉游标"
        else -> null
    }

    /** 保留首次出现的 ID，整页写入，不再按旧上限截断分页。 */
    fun newIds(pageIds: List<String>, existingIds: Set<String>): List<String> =
        pageIds.asSequence().filter { it !in existingIds }.distinct().toList()
}
