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

    @Query("UPDATE liked_list_index_session SET lastViewedOffset = :offset WHERE sourceKey = :sourceKey")
    suspend fun updateLastViewedOffset(sourceKey: String, offset: Int)

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
        upsertSession(session.copy(indexedCount = countItems(session.sourceKey)))
    }

    /** 显式重新索引在同一事务内替换旧条目与会话，绝不在恢复失败时自动调用。 */
    @Transaction
    suspend fun replaceSession(session: LikedListIndexSessionEntity) {
        deleteItems(session.sourceKey)
        upsertSession(session)
    }
}
