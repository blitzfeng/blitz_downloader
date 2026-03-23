package com.blitz.downloader.api

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinListApiModelsTest {

    private val gson = Gson()

    @Test
    fun nextPageCursor_prefersNonZeroCursorOverMax() {
        val json = """{"status_code":0,"aweme_list":[],"has_more":1,"max_cursor":0,"cursor":999}"""
        val r = gson.fromJson(json, DouyinUserVideosResponse::class.java)
        assertEquals(999L, r.nextPageCursor())
    }

    @Test
    fun nextPageCursor_fallsBackToMaxCursor() {
        val json = """{"status_code":0,"aweme_list":[],"has_more":1,"max_cursor":12345}"""
        val r = gson.fromJson(json, DouyinUserVideosResponse::class.java)
        assertEquals(12345L, r.nextPageCursor())
    }

    @Test
    fun hasMorePages() {
        val more = gson.fromJson(
            """{"status_code":0,"aweme_list":[],"has_more":1,"max_cursor":0}""",
            DouyinUserVideosResponse::class.java,
        )
        val done = gson.fromJson(
            """{"status_code":0,"aweme_list":[],"has_more":0,"max_cursor":0}""",
            DouyinUserVideosResponse::class.java,
        )
        assertTrue(more.hasMorePages())
        assertFalse(done.hasMorePages())
    }
}
