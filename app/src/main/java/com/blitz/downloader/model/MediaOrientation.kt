package com.blitz.downloader.model

/**
 * 媒体文件的画面方向。
 *
 * 这是个**派生值**，不落库：数据库只存原始事实
 * （[com.blitz.downloader.data.db.DownloadedVideoEntity.mediaWidth] /
 * [com.blitz.downloader.data.db.DownloadedVideoEntity.mediaHeight]），
 * 方向由 [of] 现算。这样以后想按分辨率筛选/排序也够用，不必再加列。
 *
 * 当前唯一消费方是局域网导出的分包（[com.blitz.downloader.net.LanFileServer]）。
 */
enum class MediaOrientation {
    LANDSCAPE,
    PORTRAIT,
    ;

    companion object {
        /**
         * `width > height` 才算横屏。
         *
         * 方形（1:1）、`0`（未探测或探测失败）、负数一律归 [PORTRAIT]——
         * 一个判断兜住三种边界，因此不需要「未知」哨兵值。
         * 抖音内容绝大多数是竖屏，把不确定的归进竖屏包也最符合直觉。
         */
        fun of(width: Int, height: Int): MediaOrientation =
            if (width > height) LANDSCAPE else PORTRAIT
    }
}
