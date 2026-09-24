package com.blitz.downloader.data

import com.blitz.downloader.data.db.LikedListIndexDao
import com.blitz.downloader.data.db.LikedListIndexItemEntity
import com.blitz.downloader.data.db.LikedListIndexSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 验证 DAO 默认检查点逻辑；不替代设备上的 SQLite 事务/迁移验证。 */
class LikedBrowseCheckpointTest {
    private fun session(owner: String) = LikedListIndexSessionEntity(
        sourceKey = "like|$owner|", sourceType = "like", ownerSecUserId = owner,
        state = "running", maxItems = Int.MAX_VALUE, nextCursor = 987654L,
    )

    @Test fun staleNetworkCheckpointPreservesNewViewportAndOtherSource() = runBlocking {
        val dao = MemoryDao()
        val old = session("one")
        val other = session("two")
        dao.upsertSession(old)
        dao.upsertSession(other)
        dao.updateAnchor(old.sourceKey, "item40", 39, -25)
        dao.checkpointPage(old.copy(nextCursor = 123456789L), emptyList())
        val saved = dao.getSession(old.sourceKey)!!
        assertEquals("item40", saved.anchorAwemeId)
        assertEquals(39L, saved.anchorSourcePosition)
        assertEquals(-25, saved.anchorOffsetPx)
        assertEquals(123456789L, saved.nextCursor)
        assertEquals(other, dao.getSession(other.sourceKey))
        dao.updateAnchor(old.sourceKey, "item37", 36, -10)
        assertEquals(123456789L, dao.getSession(old.sourceKey)!!.nextCursor)
        dao.replaceSession(old.copy(nextCursor = 0))
        assertNull(dao.getSession(old.sourceKey)!!.anchorAwemeId)
    }

    private class MemoryDao : LikedListIndexDao {
        private val sessions = mutableMapOf<String, LikedListIndexSessionEntity>()
        override suspend fun getSession(sourceKey: String) = sessions[sourceKey]
        override suspend fun upsertSession(session: LikedListIndexSessionEntity) { sessions[session.sourceKey] = session }
        override suspend fun updateAnchor(sourceKey: String, id: String, position: Long, offsetPx: Int) {
            sessions[sourceKey]?.let { sessions[sourceKey] = it.copy(anchorAwemeId = id,
                anchorSourcePosition = position, anchorOffsetPx = offsetPx) }
        }
        override suspend fun countItems(sourceKey: String) = 0
        override suspend fun countBefore(sourceKey: String, position: Long) = 0
        override suspend fun getItem(sourceKey: String, id: String): LikedListIndexItemEntity? = null
        override suspend fun getBySourceOrder(sourceKey: String, limit: Int, offset: Int) = emptyList<LikedListIndexItemEntity>()
        override suspend fun getByCreateTimeAsc(sourceKey: String, limit: Int, offset: Int) = emptyList<LikedListIndexItemEntity>()
        override suspend fun existingAwemeIds(sourceKey: String, awemeIds: List<String>) = emptyList<String>()
        override suspend fun insertIgnoreItems(items: List<LikedListIndexItemEntity>) = Unit
        override suspend fun deleteItems(sourceKey: String) = Unit
        override suspend fun updateMedia(sourceKey: String, id: String, isPhoto: Boolean, coverUrl: String?, mediaUrl: String?, photos: String) = Unit
    }
}
