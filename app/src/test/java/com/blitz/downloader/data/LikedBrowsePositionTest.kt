package com.blitz.downloader.data

import org.junit.Assert.*
import org.junit.Test

class LikedBrowsePositionTest {
    @Test fun resumeFortiethItemWithoutJumpingToSixtieth() {
        val start = LikedBrowsePosition.windowStart(39)
        assertEquals(36, start)
        assertFalse(LikedBrowsePosition.needsRemote(start, 60, true))
        val cachedWindow = (1..60).toList().drop(start)
        assertEquals(40, cachedWindow[39 - start])
        assertEquals(60, cachedWindow.last())
        assertTrue(LikedBrowsePosition.needsRemote(start + cachedWindow.size, 60, true))
        // 读完缓存不代表浏览位置已经变化。
        assertEquals(36, LikedBrowsePosition.windowStart(39))
    }

    @Test fun firstRowAndLegacySessionStartAtCacheBeginning() {
        for (index in listOf(-1, 0, 1, 2)) assertEquals(0, LikedBrowsePosition.windowStart(index))
        assertFalse(LikedBrowsePosition.needsRemote(0, 60, true))
        assertFalse(LikedBrowsePosition.needsRemote(60, 60, false))
    }

    @Test fun filteredAnchorFallsForwardThenBackwardWithoutUsingFilteredIndexAsSourcePosition() {
        val positions = mapOf("a" to 36L, "b" to 42L, "c" to 45L)
        assertEquals("b", LikedBrowsePosition.visibleAnchor(listOf("a", "b", "c"), positions, 39))
        assertEquals("a", LikedBrowsePosition.visibleAnchor(listOf("a"), positions, 39))
        assertNull(LikedBrowsePosition.visibleAnchor(emptyList(), positions, 39))
    }
}
