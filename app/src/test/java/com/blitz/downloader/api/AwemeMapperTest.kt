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
        assertEquals("", ui.authorNickname)
        assertEquals("标题测试", ui.descRaw)
        assertEquals("https://example.com/cover.jpg", ui.coverUrl)
        assertEquals(false, ui.isSelected)
        assertEquals(null, ui.downloadUrl)
        assertEquals(false, ui.isDownloaded)
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

    @Test
    fun toGridItem_usesIdStrWhenAwemeIdBlank() {
        val item = AwemeItem(
            awemeId = "",
            idStr = "7620359043416093876",
            desc = "t",
            createTime = 0L,
            author = null,
            video = Video(
                playAddr = null,
                cover = ImageUrl(listOf("https://example.com/c.jpg")),
                dynamicCover = null,
                duration = 0,
                ratio = null,
                width = 0,
                height = 0,
            ),
            statistics = null,
            shareUrl = null,
        )
        val ui = AwemeMapper.toGridItemOrNull(item)
        assertNotNull(ui)
        assertEquals("7620359043416093876", ui!!.id)
    }

    @Test
    fun toGridItem_usesStatisticsAwemeIdWhenOthersBlank() {
        val item = AwemeItem(
            awemeId = "",
            desc = "t",
            createTime = 0L,
            author = null,
            video = null,
            statistics = Statistics(awemeId = "7616284975469803471"),
            shareUrl = null,
        )
        val ui = AwemeMapper.toGridItemOrNull(item)
        assertNotNull(ui)
        assertEquals("7616284975469803471", ui!!.id)
    }

    @Test
    fun toGridItem_photoItem_isPhotoTrueAndDownloadUrlNull() {
        val imageUrl = "https://p3.douyinpic.com/img/photo1~q75.webp?x=1"
        val item = AwemeItem(
            awemeId = "7700000000000000001",
            desc = "春日图集",
            createTime = 0L,
            author = Author(uid = "u1", secUid = "", nickname = "拍客", avatarThumb = null),
            video = null,
            images = listOf(
                AwemeImage(
                    uri = "img/photo1",
                    urlList = listOf(imageUrl),
                    downloadUrlList = listOf("https://p3.douyinpic.com/img/photo1~wm.webp?x=1"),
                    watermarkFreeDownloadUrlList = null,
                ),
            ),
            statistics = null,
            shareUrl = null,
        )
        val ui = AwemeMapper.toGridItemOrNull(item)
        assertNotNull(ui)
        assertEquals("7700000000000000001", ui!!.id)
        assertEquals(true, ui.isPhoto)
        assertEquals(null, ui.downloadUrl)
        assertEquals(1, ui.imageUrls.size)
        assertEquals(imageUrl, ui.imageUrls[0])
        assertEquals(imageUrl, ui.coverUrl)
    }

    @Test
    fun preferredImageUrls_preferWatermarkFree() {
        val wm = "https://p3.douyinpic.com/img/wm.webp"
        val free = "https://p3.douyinpic.com/img/free.webp"
        val item = AwemeItem(
            awemeId = "7700000000000000002",
            desc = "x",
            createTime = 0L,
            author = null,
            video = null,
            images = listOf(
                AwemeImage(
                    urlList = listOf(wm),
                    downloadUrlList = listOf(wm),
                    watermarkFreeDownloadUrlList = listOf(free),
                ),
            ),
            statistics = null,
            shareUrl = null,
        )
        val urls = AwemeMapper.preferredImageUrls(item)
        assertEquals(1, urls.size)
        assertEquals(free, urls[0])
    }
}
