package com.blitz.downloader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadBatchDao {

    @Insert
    suspend fun insert(batch: DownloadBatchEntity): Long

    /**
     * 按创建时间倒序获取最近的批次，用于批量标签整理（取最近一条及上一条）。
     */
    @Query("SELECT * FROM download_batch ORDER BY createdAtMillis DESC, id DESC LIMIT :limit")
    suspend fun getRecentBatches(limit: Int = 2): List<DownloadBatchEntity>

    /**
     * 是否存在至少一条批次记录，供管理页菜单动态判断是否显示入口。
     */
    @Query("SELECT COUNT(*) FROM download_batch")
    suspend fun getBatchCount(): Int

    /**
     * 响应式观察批次总数，供管理页菜单/ViewModel 监听。
     */
    @Query("SELECT COUNT(*) FROM download_batch")
    fun observeBatchCount(): Flow<Int>
}
