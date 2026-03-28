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
}
