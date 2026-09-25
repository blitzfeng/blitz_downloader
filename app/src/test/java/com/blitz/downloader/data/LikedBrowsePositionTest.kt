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

    @Test fun childViewportIntersectionDetectsVisibleRangeCorrectly() {
        val viewportTop = 200
        val viewportBottom = 1800
        val height = 300

        // Completely above
        assertFalse(LikedBrowsePosition.isChildVisibleInViewport(childTop = -200, childHeight = height, viewportTop = viewportTop, viewportBottom = viewportBottom))
        assertFalse(LikedBrowsePosition.isChildVisibleInViewport(childTop = -100, childHeight = height, viewportTop = viewportTop, viewportBottom = viewportBottom))

        // Partially visible at top (child extends into viewport)
        assertTrue(LikedBrowsePosition.isChildVisibleInViewport(childTop = 0, childHeight = height, viewportTop = viewportTop, viewportBottom = viewportBottom))
        assertEquals(-200, LikedBrowsePosition.childOffsetInViewport(childTop = 0, viewportTop = viewportTop))

        // Fully inside viewport
        assertTrue(LikedBrowsePosition.isChildVisibleInViewport(childTop = 500, childHeight = height, viewportTop = viewportTop, viewportBottom = viewportBottom))
        assertEquals(300, LikedBrowsePosition.childOffsetInViewport(childTop = 500, viewportTop = viewportTop))

        // Partially visible at bottom
        assertTrue(LikedBrowsePosition.isChildVisibleInViewport(childTop = 1700, childHeight = height, viewportTop = viewportTop, viewportBottom = viewportBottom))
        assertEquals(1500, LikedBrowsePosition.childOffsetInViewport(childTop = 1700, viewportTop = viewportTop))

        // Completely below
        assertFalse(LikedBrowsePosition.isChildVisibleInViewport(childTop = 1800, childHeight = height, viewportTop = viewportTop, viewportBottom = viewportBottom))
        assertFalse(LikedBrowsePosition.isChildVisibleInViewport(childTop = 2000, childHeight = height, viewportTop = viewportTop, viewportBottom = viewportBottom))
    }

    @Test fun computeRestoreScrollYAlignsChildWithSavedOffset() {
        // Child is currently at 500 in window, viewportTop is 200, currentScrollY is 0.
        // If saved offset was 100, child needs to move from 300px below top to 100px below top (scroll down by 200px).
        val targetScrollY = LikedBrowsePosition.computeRestoreScrollY(
            currentScrollY = 0,
            childTop = 500,
            viewportTop = 200,
            offsetPx = 100,
        )
        assertEquals(200, targetScrollY)

        // Negative offset (child partially scrolled above top)
        val negativeOffsetScrollY = LikedBrowsePosition.computeRestoreScrollY(
            currentScrollY = 0,
            childTop = 500,
            viewportTop = 200,
            offsetPx = -50,
        )
        assertEquals(350, negativeOffsetScrollY)

        // Upper boundary protection (never scroll below 0)
        val clampedScrollY = LikedBrowsePosition.computeRestoreScrollY(
            currentScrollY = 0,
            childTop = 100,
            viewportTop = 200,
            offsetPx = 500,
        )
        assertEquals(0, clampedScrollY)
    }
}
