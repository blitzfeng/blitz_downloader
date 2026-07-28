package com.blitz.downloader.download

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 进程内的下载完成事件总线。
 *
 * [DownloadService] 把「本次已写库成功」的作品 id 集合广播出来，仍停留在批量下载页的界面据此把对应项
 * 就地标成已下载、取消勾选，不必离开页面再回来才刷新。
 *
 * 有意做成 `replay = 0`：页面被销毁期间错过的事件由 `onResume` 时的整表回查（`reapplyDownloadedFlagsToList`）
 * 兜底，不需要在这里缓存历史。
 */
object DownloadEvents {

    private val _recorded = MutableSharedFlow<Set<String>>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 已成功下载并写入数据库的作品 id 集合。 */
    val recorded: SharedFlow<Set<String>> = _recorded

    fun notifyRecorded(awemeIds: Set<String>) {
        if (awemeIds.isEmpty()) return
        _recorded.tryEmit(awemeIds)
    }
}
