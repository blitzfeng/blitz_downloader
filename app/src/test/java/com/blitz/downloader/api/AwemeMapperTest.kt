package com.blitz.downloader.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AwemeMapperTest {

    @Test
    fun toGridItem_mapsCoverDescAndId() {
        val item = AwemeItem(
            awemeId = "7123456789012345678",
            desc = " 标题测试 ",
            createTime = 0L,
            author = null,
            video = Video(
                playAddr = null,
                cover = ImageUrl(listOf("https://example.com/cover.jpg")),
                dynamicCover = null,
                duration = 1000,
                ratio = null,
                width = 720,
                height = 1280,
            ),
            statistics = null,
            shareUrl = null,
        )
        val ui = AwemeMapper.toGridItemOrNull(item)
        assertNotNull(ui)
        assertEquals("7123456789012345678", ui!!.id)
        assertEquals("标题测试", ui.title)
        assertEquals("https://example.com/cover.jpg", ui.coverUrl)
        assertEquals(false, ui.isSelected)
        assertEquals(null, ui.downloadUrl)
    }

    @Test
    fun toGridItem_mapsPlayUrlWithPlaywmReplaced() {
        val item = AwemeItem(
            awemeId = "7123456789012345678",
            desc = "t",
            createTime = 0L,
            author = null,
            video = Video(
                playAddr = PlayAddr(
                    uri = null,
                    urlList = listOf("https://aweme.example.com/playwm/path?x=1"),
                    dataSize = null,
                    urlKey = null,
                ),
                cover = null,
                dynamicCover = null,
                duration = 1000,
                ratio = null,
                width = 720,
                height = 1280,
            ),
            statistics = null,
            shareUrl = null,
        )
        val ui = AwemeMapper.toGridItemOrNull(item)
        assertNotNull(ui)
        assertEquals("https://aweme.example.com/play/path?x=1", ui!!.downloadUrl)
    }

    @Test
    fun toGridItem_nullWhenAwemeIdBlank() {
        val item = AwemeItem(
            awemeId = "   ",
            desc = "x",
            createTime = 0L,
            author = null,
            video = null,
            statistics = null,
            shareUrl = null,
        )
        assertNull(AwemeMapper.toGridItemOrNull(item))
    }
}
