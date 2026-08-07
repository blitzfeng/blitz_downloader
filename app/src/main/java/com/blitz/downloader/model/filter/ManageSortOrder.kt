package com.blitz.downloader.model.filter

import com.blitz.downloader.R

/**
 * 管理页列表排序方式。[column] 是 `downloaded_videos` 表的合法列名（固定枚举，非用户输入，
 * 供 [com.blitz.downloader.data.DownloadedVideoRepository.getPageByMediaTypeSorted] 拼 ORDER BY 用），
 * 一律倒序。[labelRes] 为选择对话框中显示的文案。
 */
enum class ManageSortOrder(val column: String, val labelRes: Int) {
    /** 下载时间（默认）。 */
    DOWNLOAD_TIME("createdAtMillis", R.string.manage_sort_download_time),

    /** 作品发布时间。 */
    PUBLISH_TIME("createTime", R.string.manage_sort_publish_time),

    /** 点赞数。 */
    DIGG("diggCount", R.string.manage_sort_digg),
    ;

    companion object {
        val DEFAULT = DOWNLOAD_TIME
    }
}
