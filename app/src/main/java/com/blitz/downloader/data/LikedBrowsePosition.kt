package com.blitz.downloader.data

/** 浏览锚点与本地加载边界、服务端游标互不推导。 */
object LikedBrowsePosition {
    /** 保留锚点前一行，并对齐三列网格；旧版本没有锚点时从缓存起点恢复。 */
    fun windowStart(anchorIndex: Int, columns: Int = 3): Int =
        ((anchorIndex.coerceAtLeast(0) / columns - 1).coerceAtLeast(0)) * columns

    fun needsRemote(loadedEnd: Int, cachedCount: Int, hasMore: Boolean): Boolean =
        loadedEnd >= cachedCount && hasMore

    /** 筛选隐藏锚点时优先选择其后的最近可见项，再回退到前方。 */
    fun visibleAnchor(ids: List<String>, positions: Map<String, Long>, anchor: Long?): String? =
        if (anchor == null) ids.firstOrNull()
        else ids.firstOrNull { (positions[it] ?: Long.MIN_VALUE) >= anchor } ?: ids.lastOrNull()

    fun isChildVisibleInViewport(childTop: Int, childHeight: Int, viewportTop: Int, viewportBottom: Int): Boolean =
        childTop + childHeight > viewportTop && childTop < viewportBottom

    fun childOffsetInViewport(childTop: Int, viewportTop: Int): Int =
        childTop - viewportTop

    fun computeRestoreScrollY(currentScrollY: Int, childTop: Int, viewportTop: Int, offsetPx: Int): Int =
        (currentScrollY + childTop - viewportTop - offsetPx).coerceAtLeast(0)
}
