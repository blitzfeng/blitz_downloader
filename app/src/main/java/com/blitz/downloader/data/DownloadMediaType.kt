package com.blitz.downloader.data

/**
 * 记录已下载项的媒体类型，存入 [com.blitz.downloader.data.db.DownloadedVideoEntity.mediaType]。
 */
object DownloadMediaType {
    /** 普通视频（mp4）。 */
    const val VIDEO = "video"

    /** 图集/图文（多张图片）。 */
    const val IMAGE = "image"
}
