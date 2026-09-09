package com.blitz.downloader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 批量下载批次记录（`download_batch` 表）。
 *
 * 在一次批量下载成功入库的视频数超过 2 条时写入一条记录（≤2 条不记录）。
 * [awemeIds] 字段用 `|` 分隔保存该批次包含的抖音作品 ID 列表，
 * 供管理页批量标签整理入口读取「最近一次批次」及「上一次批次中未打标视频」。
 */
@Entity(tableName = "download_batch")
data class DownloadBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAtMillis: Long,
    val awemeIds: String,
)
