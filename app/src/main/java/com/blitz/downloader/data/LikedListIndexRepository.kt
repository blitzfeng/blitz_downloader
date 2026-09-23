package com.blitz.downloader.data

import android.content.Context
import com.blitz.downloader.data.db.AppDatabase
import com.blitz.downloader.data.db.LikedListIndexDao
import com.blitz.downloader.data.db.LikedListIndexItemEntity
import com.blitz.downloader.data.db.LikedListIndexSessionEntity

class LikedListIndexRepository(context: Context) {
    private val dao: LikedListIndexDao = AppDatabase.getInstance(context).likedListIndexDao()

    fun sourceKey(ownerSecUserId: String, collectionId: String? = null): String =
        likedListIndexSourceKey(ownerSecUserId, collectionId)

    suspend fun session(ownerSecUserId: String) = dao.getSession(sourceKey(ownerSecUserId))
    suspend fun sourceOrder(ownerSecUserId: String, limit: Int, offset: Int) =
        dao.getBySourceOrder(sourceKey(ownerSecUserId), limit, offset)
    suspend fun createTimeOrder(ownerSecUserId: String, limit: Int, offset: Int) =
        dao.getByCreateTimeAsc(sourceKey(ownerSecUserId), limit, offset)
    suspend fun checkpoint(session: LikedListIndexSessionEntity, items: List<LikedListIndexItemEntity>) =
        dao.checkpointPage(session, items)
    suspend fun replace(session: LikedListIndexSessionEntity) = dao.replaceSession(session)
    suspend fun existingIds(ownerSecUserId: String, awemeIds: List<String>): Set<String> =
        dao.existingAwemeIds(sourceKey(ownerSecUserId), awemeIds).toSet()
    suspend fun updateLastViewedOffset(ownerSecUserId: String, offset: Int) =
        dao.updateLastViewedOffset(sourceKey(ownerSecUserId), offset)
}
