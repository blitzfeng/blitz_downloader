package com.blitz.downloader.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface DownloadedVideoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadedVideoEntity)

    @Update
    suspend fun update(entity: DownloadedVideoEntity)

    @Delete
    suspend fun delete(entity: DownloadedVideoEntity)

    @Query("DELETE FROM downloaded_videos WHERE id = :rowId")
    suspend fun deleteByRowId(rowId: Long)

    @Query("DELETE FROM downloaded_videos WHERE awemeId = :awemeId")
    suspend fun deleteByAwemeId(awemeId: String)

    @Query("DELETE FROM downloaded_videos WHERE awemeId IN (:awemeIds)")
    suspend fun deleteByAwemeIds(awemeIds: List<String>): Int

    @Query("SELECT * FROM downloaded_videos WHERE id = :rowId LIMIT 1")
    suspend fun getByRowId(rowId: Long): DownloadedVideoEntity?

    @Query("SELECT * FROM downloaded_videos WHERE awemeId = :awemeId LIMIT 1")
    suspend fun getByAwemeId(awemeId: String): DownloadedVideoEntity?

    @Query("SELECT * FROM downloaded_videos ORDER BY createdAtMillis DESC")
    suspend fun getAll(): List<DownloadedVideoEntity>

    @Query("SELECT * FROM downloaded_videos WHERE mediaType = :mediaType ORDER BY createdAtMillis DESC")
    suspend fun getAllByMediaType(mediaType: String): List<DownloadedVideoEntity>

    @Query("SELECT awemeId FROM downloaded_videos")
    suspend fun getAllAwemeIds(): List<String>

    @Query("SELECT awemeId FROM downloaded_videos WHERE awemeId IN (:ids)")
    suspend fun getAwemeIdsContainedIn(ids: List<String>): List<String>

    @Query("SELECT COUNT(*) FROM downloaded_videos WHERE mediaType = :mediaType")
    suspend fun countByMediaType(mediaType: String): Int

    @Query("SELECT * FROM downloaded_videos WHERE mediaType = :mediaType ORDER BY createdAtMillis DESC LIMIT :limit OFFSET :offset")
    suspend fun getPageByMediaType(mediaType: String, limit: Int, offset: Int): List<DownloadedVideoEntity>

    /**
     * 按作者昵称模糊搜索（管理页搜索栏使用）。
     * [userNameLike] 由 Repository 拼为 `%query%`；空查询不应走此方法（让上层走分页路径）。
     * 结果集预计不大，一次性返回；如未来体量增大再加 LIMIT 分页。
     */
    @Query("SELECT * FROM downloaded_videos WHERE mediaType = :mediaType AND userName LIKE :userNameLike ORDER BY createdAtMillis DESC")
    suspend fun searchByMediaTypeAndUserName(mediaType: String, userNameLike: String): List<DownloadedVideoEntity>

    /** 精确匹配某作者昵称的全部作品（管理页「按作者筛选」使用）。 */
    @Query("SELECT * FROM downloaded_videos WHERE mediaType = :mediaType AND userName = :userName ORDER BY createdAtMillis DESC")
    suspend fun getByMediaTypeAndUserName(mediaType: String, userName: String): List<DownloadedVideoEntity>

    /**
     * 按作者昵称聚合作品数（管理页作者抽屉使用），按作品数倒序、同数量按昵称升序。
     * 空昵称记录也会成组（分组键即空串），由上层决定是否展示。
     */
    @Query(
        "SELECT userName AS name, COUNT(*) AS count FROM downloaded_videos " +
            "WHERE mediaType = :mediaType GROUP BY userName ORDER BY count DESC, userName ASC"
    )
    suspend fun getAuthorCountsByMediaType(mediaType: String): List<AuthorCount>

    /** 作者聚合投影：昵称 + 作品数。 */
    data class AuthorCount(val name: String, val count: Int)
}
