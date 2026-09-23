package com.blitz.downloader.data

import com.blitz.downloader.data.db.LikedListIndexSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LikedListIndexPolicyTest {
    @Test fun `来源身份隔离相同作品`() {
        assertEquals("like|owner-a|", likedListIndexSourceKey("owner-a"))
        assertEquals("like|owner-b|", likedListIndexSourceKey("owner-b"))
        assertEquals("like|owner-a|folder", likedListIndexSourceKey("owner-a", "folder"))
    }

    @Test fun `超过旧上限仍保留完整新分页且去重`() {
        val known = (1..1000).map { it.toString() }.toSet()
        val page = (990..1048).map { it.toString() } + "1048"
        assertEquals((1001..1048).map { it.toString() }, LikedListIndexPolicy.newIds(page, known))
        assertEquals(LikedListIndexSessionState.RUNNING, LikedListIndexPolicy.terminalState(true))
    }

    @Test fun `远端结束与恢复边界可区分`() {
        assertEquals(LikedListIndexSessionState.COMPLETE_REMOTE, LikedListIndexPolicy.terminalState(false))
        assertNotNull(LikedListIndexPolicy.continuationError(100, 100, 5, true))
        assertNotNull(LikedListIndexPolicy.continuationError(100, 200, 0, true))
        assertNull(LikedListIndexPolicy.continuationError(100, 200, 5, true))
    }
}
