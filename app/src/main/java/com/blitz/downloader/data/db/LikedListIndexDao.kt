package com.blitz.downloader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface LikedListIndexDao {
    @Query("SELECT * FROM liked_list_index_session WHERE sourceKey = :sourceKey")
    suspend fun getSession(sourceKey: String): LikedListIndexSessionEntity?

    @Query("SELECT * FROM liked_list_index_item WHERE sourceKey = :sourceKey ORDER BY sourcePosition ASC, awemeId ASC LIMIT :limit OFFSET :offset")
    suspend fun getBySourceOrder(sourceKey: String, limit: Int, offset: Int): List<LikedListIndexItemEntity>

    @Query("SELECT * FROM liked_list_index_item WHERE sourceKey = :sourceKey ORDER BY createTime ASC, sourcePosition ASC, awemeId ASC LIMIT :limit OFFSET :offset")
    suspend fun getByCreateTimeAsc(sourceKey: String, limit: Int, offset: Int): List<LikedListIndexItemEntity>

    @Query("SELECT COUNT(*) FROM liked_list_index_item WHERE sourceKey = :sourceKey")
    suspend fun countItems(sourceKey: String): Int

    @Query("UPDATE liked_list_index_session SET anchorAwemeId = :id, anchorSourcePosition = :position, anchorOffsetPx = :offsetPx WHERE sourceKey = :sourceKey")
    suspend fun updateAnchor(sourceKey: String, id: String, position: Long, offsetPx: Int)

    @Query("SELECT * FROM liked_list_index_item WHERE sourceKey = :sourceKey AND awemeId = :id")
    suspend fun getItem(sourceKey: String, id: String): LikedListIndexItemEntity?

    @Query("SELECT COUNT(*) FROM liked_list_index_item WHERE sourceKey = :sourceKey AND sourcePosition < :position")
    suspend fun countBefore(sourceKey: String, position: Long): Int

    /** 仅修复当前来源的媒体缓存，不改来源顺序、数量或会话游标。 */
    @Query("UPDATE liked_list_index_item SET isPhoto = :isPhoto, coverUrl = :coverUrl, mediaUrl = :mediaUrl, photoMediaJson = :photos WHERE sourceKey = :sourceKey AND awemeId = :id")
    suspend fun updateMedia(sourceKey: String, id: String, isPhoto: Boolean, coverUrl: String?, mediaUrl: String?, photos: String)

    @Query("SELECT awemeId FROM liked_list_index_item WHERE sourceKey = :sourceKey AND awemeId IN (:awemeIds)")
    suspend fun existingAwemeIds(sourceKey: String, awemeIds: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: LikedListIndexSessionEntity)

    /** IGNORE keeps the first source position when pages overlap. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreItems(items: List<LikedListIndexItemEntity>)

    @Query("DELETE FROM liked_list_index_item WHERE sourceKey = :sourceKey")
    suspend fun deleteItems(sourceKey: String)

    @Transaction
    suspend fun checkpointPage(session: LikedListIndexSessionEntity, items: List<LikedListIndexItemEntity>) {
        insertIgnoreItems(items)
        // 网络请求可能早于滚动保存发起；检查点不可用旧会话覆盖最新视口锚点。
        val current = getSession(session.sourceKey)
        upsertSession(session.copy(
            indexedCount = countItems(session.sourceKey),
            anchorAwemeId = current?.anchorAwemeId,
            anchorSourcePosition = current?.anchorSourcePosition,
            anchorOffsetPx = current?.anchorOffsetPx,
        ))
    }

    /** 显式重新索引在同一事务内替换旧条目与会话，绝不在恢复失败时自动调用。 */
    @Transaction
    suspend fun replaceSession(session: LikedListIndexSessionEntity) {
        deleteItems(session.sourceKey)
        upsertSession(session)
    }
}
