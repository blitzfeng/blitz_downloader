package com.blitz.downloader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "downloaded_videos",
    indices = [Index(value = ["awemeId"], unique = true)],
)
data class DownloadedVideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 接口侧作品 id，与网格项 id 一致。 */
    val awemeId: String,
    /** [com.blitz.downloader.data.DownloadSourceType] 之一（post/like/collect/mix/collects）。 */
    val downloadType: String,
    /** 下载时的作者昵称快照。 */
    val userName: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    /**
     * 媒体类型：[com.blitz.downloader.data.DownloadMediaType.VIDEO] 或
     * [com.blitz.downloader.data.DownloadMediaType.IMAGE]。
     */
    val mediaType: String = "video",
    /**
     * 文件在设备存储中的可读路径，如 `Download/bDouyin/videos/author_title.mp4` 或
     * `Download/bDouyin/images/author_title_01.jpg`（图集时为第一张图路径）。
     * 旧记录默认为空字符串。
     */
    val filePath: String = "",
    /**
     * 封面缩略图的本地路径。
     * - 视频：`Download/bDouyin/covers/author_title.jpg`（下载时同步保存，URL 失效后仍可用）。
     * - 图集：与 [filePath] 相同（第一张图即封面，无需重复保存）。
     * - 旧记录默认为空字符串，显示时可回退到占位图。
     */
    val coverPath: String = "",
)
