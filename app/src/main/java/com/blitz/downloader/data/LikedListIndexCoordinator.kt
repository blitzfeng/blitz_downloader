package com.blitz.downloader.data

import com.blitz.downloader.api.AwemeItem
import com.blitz.downloader.api.AwemeMapper
import com.blitz.downloader.api.DouyinListApi
import com.blitz.downloader.api.DouyinListPage
import com.blitz.downloader.data.db.LikedListIndexItemEntity
import com.blitz.downloader.data.db.LikedListIndexSessionEntity
import com.blitz.downloader.data.db.LikedListIndexSessionState
import com.blitz.downloader.data.db.LikedListIndexSourceType

/**
 * 点赞索引的唯一写入入口。每一页成功后由 repository 在同一 Room 事务写入条目与 cursor 检查点。
 * cursor 始终直接来自服务端；这里不会把它解释成页号或由条目数量推导。
 */
class LikedListIndexCoordinator(private val repository: LikedListIndexRepository) {

    /** 兼容旧版条数上限：保留条目和游标，取消 100/500/1000 条的停止条件。 */
    suspend fun prepareForBrowsing(owner: String): LikedListIndexSessionEntity? {
        val old = repository.session(owner) ?: return null
        val updated = old.copy(
            maxItems = Int.MAX_VALUE,
            state = if (old.state == LikedListIndexSessionState.COMPLETE_LIMIT)
                LikedListIndexSessionState.RUNNING else old.state,
        )
        if (updated != old) repository.checkpoint(updated, emptyList())
        return updated
    }

    suspend fun start(
        ownerSecUserId: String,
        maxItems: Int,
        replace: Boolean = false,
        onCheckpoint: (LikedListIndexSessionEntity) -> Unit = {},
    ): LikedListIndexSessionEntity {
        require(maxItems > 0) { "最大索引条数必须大于 0" }
        val existing = repository.session(ownerSecUserId)
        val session = if (replace || existing == null) {
            LikedListIndexSessionEntity(
                sourceKey = repository.sourceKey(ownerSecUserId),
                sourceType = LikedListIndexSourceType.LIKE,
                ownerSecUserId = ownerSecUserId,
                state = LikedListIndexSessionState.RUNNING,
                maxItems = maxItems,
            ).also { repository.replace(it) }
        } else {
            when (existing.state) {
                LikedListIndexSessionState.RECOVERY_REQUIRED ->
                    throw IllegalStateException("此索引需要显式重新索引，不能自动覆盖已有条目")
                LikedListIndexSessionState.COMPLETE_LIMIT,
                LikedListIndexSessionState.COMPLETE_REMOTE -> return existing
                else -> existing
            }
        }
        return indexUntilStopped(session, onCheckpoint)
    }

    suspend fun resume(ownerSecUserId: String, onCheckpoint: (LikedListIndexSessionEntity) -> Unit = {}): LikedListIndexSessionEntity {
        val session = repository.session(ownerSecUserId) ?: throw IllegalStateException("没有可恢复的点赞索引")
        if (session.state != LikedListIndexSessionState.RUNNING) return session
        return indexUntilStopped(session, onCheckpoint)
    }

    private suspend fun indexUntilStopped(
        initial: LikedListIndexSessionEntity,
        onCheckpoint: (LikedListIndexSessionEntity) -> Unit,
    ): LikedListIndexSessionEntity {
        var session = initial
        if (session.state == LikedListIndexSessionState.RUNNING) {
            val cursor = session.nextCursor
            val page = DouyinListApi.fetchUserLikePage(session.ownerSecUserId, cursor, PAGE_SIZE)
                .getOrElse { return requireRecovery(session, it.message ?: "请求点赞列表失败") }
            LikedListIndexPolicy.continuationError(cursor, page.nextCursor, page.items.size, page.hasMore)
                ?.let { return requireRecovery(session, it) }
            session = checkpoint(session, page)
            onCheckpoint(session)
        }
        return session
    }

    private suspend fun checkpoint(
        old: LikedListIndexSessionEntity,
        page: DouyinListPage,
    ): LikedListIndexSessionEntity {
        val stable = page.items.mapNotNull { aweme -> AwemeMapper.resolveStableAwemeId(aweme).takeIf { it.isNotBlank() }?.let { aweme to it } }
        val known = repository.existingIds(old.ownerSecUserId, stable.map { it.second })
        val insertIds = LikedListIndexPolicy.newIds(stable.map { it.second }, known)
        val awemesById = stable.associate { it.second to it.first }
        val inserts = insertIds.mapIndexed { index, id -> awemesById.getValue(id).toIndexItem(old.sourceKey, id, old.nextSourcePosition + index) }
            .toList()
        val expectedCount = old.indexedCount + inserts.size
        val terminal = LikedListIndexPolicy.terminalState(page.hasMore)
        return old.copy(
            state = terminal,
            nextCursor = page.nextCursor,
            nextSourcePosition = old.nextSourcePosition + page.items.size,
            indexedCount = expectedCount,
            lastSuccessfulPageAtMillis = System.currentTimeMillis(),
            recoveryError = null,
        ).also { repository.checkpoint(it, inserts) }
    }

    private suspend fun requireRecovery(session: LikedListIndexSessionEntity, reason: String): LikedListIndexSessionEntity =
        session.copy(state = LikedListIndexSessionState.RECOVERY_REQUIRED, recoveryError = reason)
            .also { repository.checkpoint(it, emptyList()) }

    private fun AwemeItem.toIndexItem(sourceKey: String, id: String, sourcePosition: Long): LikedListIndexItemEntity =
        LikedListIndexItemEntity(
            sourceKey = sourceKey, awemeId = id, sourcePosition = sourcePosition,
            title = desc?.trim().orEmpty().ifBlank { "（无标题）" }.take(120),
            authorNickname = author?.nickname?.trim().orEmpty(), authorSecUserId = author?.secUid?.trim().orEmpty(),
            description = desc?.trim().orEmpty(), createTime = createTime,
            isPhoto = AwemeMapper.isPhotoItem(this), collectStat = collectStat, userDigged = userDigged,
            diggCount = statistics?.diggCount ?: 0L, collectCount = statistics?.collectCount ?: 0L,
            coverUrl = video?.cover?.urlList?.firstOrNull() ?: images?.firstOrNull()?.urlList?.firstOrNull(),
            mediaUrl = if (AwemeMapper.isPhotoItem(this)) AwemeMapper.preferredImageUrls(this).firstOrNull()
                else AwemeMapper.preferredPlayDownloadUrl(this),
        )

    companion object { const val PAGE_SIZE = 50 }
}
