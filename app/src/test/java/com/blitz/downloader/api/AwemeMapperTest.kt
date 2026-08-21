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
        // 纯静态图集 → hasLivePhoto=false
        assertEquals(false, ui.hasLivePhoto)
    }

    @Test
    fun preferredPlayUrl_prefers1080OverPlayAddrAndLowerGears() {
        val item = AwemeItem(
            awemeId = "7123456789012345678",
            desc = "t",
            createTime = 0L,
            author = null,
            video = Video(
                // 默认 play_addr 是较低档，应被 1080 档覆盖
                playAddr = PlayAddr(null, listOf("https://cdn.example.com/play/default?x=1"), null, null),
                bitRate = listOf(
                    DouyinBitRateEntry(
                        PlayAddr(null, listOf("https://cdn.example.com/play/720?x=1"), null, null),
                        gearName = "normal_720_0", bitRateBps = 1_000_000,
                    ),
                    DouyinBitRateEntry(
                        PlayAddr(null, listOf("https://cdn.example.com/play/1080?x=1"), null, null),
                        gearName = "normal_1080_0", bitRateBps = 2_000_000,
                    ),
                ),
                cover = null, dynamicCover = null, duration = 0, ratio = null, width = 1080, height = 1920,
            ),
            statistics = null,
            shareUrl = null,
        )
        assertEquals("https://cdn.example.com/play/1080?x=1", AwemeMapper.toGridItemOrNull(item)!!.downloadUrl)
    }

    @Test
    fun preferredPlayUrl_picksHighestBitRateAmong1080Gears() {
        val item = AwemeItem(
            awemeId = "7123456789012345678",
            desc = "t",
            createTime = 0L,
            author = null,
            video = Video(
                playAddr = null,
                bitRate = listOf(
                    DouyinBitRateEntry(
                        PlayAddr(null, listOf("https://cdn.example.com/play/1080_low?x=1"), null, null),
                        gearName = "normal_1080_0", bitRateBps = 1_500_000,
                    ),
                    DouyinBitRateEntry(
                        PlayAddr(null, listOf("https://cdn.example.com/play/1080_high?x=1"), null, null),
                        gearName = "adapt_1080_1", bitRateBps = 3_000_000,
                    ),
                ),
                cover = null, dynamicCover = null, duration = 0, ratio = null, width = 1080, height = 1920,
            ),
            statistics = null,
            shareUrl = null,
        )
        assertEquals("https://cdn.example.com/play/1080_high?x=1", AwemeMapper.toGridItemOrNull(item)!!.downloadUrl)
    }

    @Test
    fun preferredPlayUrl_fallsBackToHighestResolutionWhenNo1080() {
        val item = AwemeItem(
            awemeId = "7123456789012345678",
            desc = "t",
            createTime = 0L,
            author = null,
            video = Video(
                playAddr = null,
                bitRate = listOf(
                    DouyinBitRateEntry(
                        PlayAddr(null, listOf("https://cdn.example.com/play/540?x=1"), null, null),
                        gearName = "normal_540_0", bitRateBps = 800_000,
                    ),
                    DouyinBitRateEntry(
                        PlayAddr(null, listOf("https://cdn.example.com/play/720?x=1"), null, null),
                        gearName = "normal_720_0", bitRateBps = 1_200_000,
                    ),
                ),
                cover = null, dynamicCover = null, duration = 0, ratio = null, width = 720, height = 1280,
            ),
            statistics = null,
            shareUrl = null,
        )
        assertEquals("https://cdn.example.com/play/720?x=1", AwemeMapper.toGridItemOrNull(item)!!.downloadUrl)
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

    @Test
    fun preferredImagePairs_livePhoto_extractsMp4AlignedWithStatic() {
        val staticA = "https://p3.douyinpic.com/img/a_01~q75.webp"
        val staticB = "https://p3.douyinpic.com/img/a_02~q75.webp"
        val mp4A = "https://v26-web.douyinvod.com/a_01/?mime_type=video_mp4"
        val item = AwemeItem(
            awemeId = "7700000000000000010",
            desc = "实况图集",
            createTime = 0L,
            author = null,
            video = null,
            images = listOf(
                // 第 1 张：实况图（带 video.play_addr 的 mp4）
                AwemeImage(
                    urlList = listOf(staticA),
                    livePhotoType = 1,
                    video = Video(
                        playAddr = PlayAddr(null, listOf(mp4A), null, null),
                        cover = null, dynamicCover = null, ratio = null,
                    ),
                ),
                // 第 2 张：普通静态图（无 video）
                AwemeImage(urlList = listOf(staticB)),
            ),
            statistics = null,
            shareUrl = null,
        )
        val ui = AwemeMapper.toGridItemOrNull(item)
        assertNotNull(ui)
        assertEquals(true, ui!!.isPhoto)
        // 静态封面列表不变
        assertEquals(listOf(staticA, staticB), ui.imageUrls)
        // mp4 列表与静态封面等长、一一对应：动图项非空、静态图为 null
        assertEquals(2, ui.imageVideoUrls.size)
        assertEquals(mp4A, ui.imageVideoUrls[0])
        assertNull(ui.imageVideoUrls[1])
        // 含至少一张动图 → hasLivePhoto=true
        assertEquals(true, ui.hasLivePhoto)
    }

    @Test
    fun preferredImagePairs_livePhoto_prefers1080Mp4FromBitRate() {
        val item = AwemeItem(
            awemeId = "7700000000000000011",
            desc = "t",
            createTime = 0L,
            author = null,
            video = null,
            images = listOf(
                AwemeImage(
                    urlList = listOf("https://p3.douyinpic.com/img/b_01~q75.webp"),
                    livePhotoType = 1,
                    video = Video(
                        playAddr = PlayAddr(null, listOf("https://cdn.example.com/live/default"), null, null),
                        bitRate = listOf(
                            DouyinBitRateEntry(
                                PlayAddr(null, listOf("https://cdn.example.com/live/720"), null, null),
                                gearName = "normal_720_0", bitRateBps = 1_000_000,
                            ),
                            DouyinBitRateEntry(
                                PlayAddr(null, listOf("https://cdn.example.com/live/1080"), null, null),
                                gearName = "normal_1080_0", bitRateBps = 2_000_000,
                            ),
                        ),
                        cover = null, dynamicCover = null, ratio = null,
                    ),
                ),
            ),
            statistics = null,
            shareUrl = null,
        )
        val ui = AwemeMapper.toGridItemOrNull(item)
        assertNotNull(ui)
        assertEquals("https://cdn.example.com/live/1080", ui!!.imageVideoUrls[0])
    }
}
