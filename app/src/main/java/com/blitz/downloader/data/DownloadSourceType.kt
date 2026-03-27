package com.blitz.downloader.data

/**
 * 记录批量下载时的列表来源，与 [com.blitz.downloader.ui.ListDownloadFragment] 中列表模式对应。
 * 需求最小集合：`post` / `like` / `collect`；合集、收藏夹为扩展取值，便于区分场景。
 */
object DownloadSourceType {
    const val POST = "post"
    const val LIKE = "like"
    const val COLLECT = "collect"
    const val MIX = "mix"
    const val COLLECTS = "collects"
}
