package com.blitz.downloader.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DouyinUrlParserParseTest {

    @Test
    fun videoPath_awemeId() {
        val p = DouyinUrlParser.parseExpandedUrl("https://www.douyin.com/video/7612611576776209401")
        assertEquals("7612611576776209401", p.awemeId)
        assertEquals(DouyinPageKind.VIDEO, p.kind)
        assertNull(p.mixId)
    }

    @Test
    fun shareVideoPath_awemeId() {
        val p = DouyinUrlParser.parseExpandedUrl("https://www.iesdouyin.com/share/video/7612611576776209401")
        assertEquals("7612611576776209401", p.awemeId)
        assertEquals(DouyinPageKind.VIDEO, p.kind)
    }

    @Test
    fun videoWithSecUidQuery() {
        val p = DouyinUrlParser.parseExpandedUrl(
            "https://www.douyin.com/video/7612611576776209401?modeFrom=userPost&sec_uid=MS4wLjABAAAA_test"
        )
        assertEquals("7612611576776209401", p.awemeId)
        assertEquals("MS4wLjABAAAA_test", p.secUserId)
        assertEquals(DouyinPageKind.VIDEO, p.kind)
    }

    @Test
    fun modalIdQuery_awemeId() {
        val p = DouyinUrlParser.parseExpandedUrl(
            "https://www.douyin.com/jingxuan?modal_id=7123456789012345678"
        )
        assertEquals("7123456789012345678", p.awemeId)
        assertEquals(DouyinPageKind.VIDEO, p.kind)
    }

    @Test
    fun userPath_secUserId() {
        val p = DouyinUrlParser.parseExpandedUrl(
            "https://www.douyin.com/user/MS4wLjABAAAAAbCdEfGhIjKlMnOpQr"
        )
        assertEquals("MS4wLjABAAAAAbCdEfGhIjKlMnOpQr", p.secUserId)
        assertEquals(DouyinPageKind.USER, p.kind)
        assertNull(p.awemeId)
    }

    @Test
    fun shareUserPath_secUserId() {
        val p = DouyinUrlParser.parseExpandedUrl(
            "https://www.douyin.com/share/user/MS4wLjABAAAAx"
        )
        assertEquals("MS4wLjABAAAAx", p.secUserId)
        assertEquals(DouyinPageKind.USER, p.kind)
    }

    @Test
    fun collectionPath_mixId() {
        val p = DouyinUrlParser.parseExpandedUrl(
            "https://www.douyin.com/collection/7189456705919496187"
        )
        assertEquals("7189456705919496187", p.mixId)
        assertEquals(DouyinPageKind.MIX, p.kind)
    }

    @Test
    fun mixDetailPath_mixId() {
        val p = DouyinUrlParser.parseExpandedUrl("https://www.douyin.com/mix/detail/7189456705919496187")
        assertEquals("7189456705919496187", p.mixId)
        assertEquals(DouyinPageKind.MIX, p.kind)
    }

    @Test
    fun mixIdQuery_mixId() {
        val p = DouyinUrlParser.parseExpandedUrl(
            "https://www.douyin.com/some/page?mix_id=7189456705919496187"
        )
        assertEquals("7189456705919496187", p.mixId)
        assertEquals(DouyinPageKind.MIX, p.kind)
    }

    @Test
    fun seriesPath_mixId() {
        val p = DouyinUrlParser.parseExpandedUrl("https://www.douyin.com/series/7189456705919496187")
        assertEquals("7189456705919496187", p.mixId)
        assertEquals(DouyinPageKind.MIX, p.kind)
    }

    @Test
    fun notePath_awemeId() {
        val p = DouyinUrlParser.parseExpandedUrl("https://www.douyin.com/note/7123456789012345678")
        assertEquals("7123456789012345678", p.awemeId)
        assertEquals(DouyinPageKind.VIDEO, p.kind)
    }

    @Test
    fun fallbackText_itemIds() {
        val p = DouyinUrlParser.parseExpandedUrl(
            "https://example.com/nothing",
            fallbackText = "复制打开抖音，item ids=7612611576776209401"
        )
        assertEquals("7612611576776209401", p.awemeId)
        assertEquals(DouyinPageKind.VIDEO, p.kind)
    }

    @Test
    fun shortHostUnresolved() {
        val p = DouyinUrlParser.parseExpandedUrl("https://v.douyin.com/abc123/")
        assertEquals(DouyinPageKind.SHORT_UNRESOLVED, p.kind)
    }

    @Test
    fun nonDouyin_unknownWithoutFallback() {
        val p = DouyinUrlParser.parseExpandedUrl("https://example.com/video/123")
        assertEquals(DouyinPageKind.UNKNOWN, p.kind)
        assertNull(p.awemeId)
    }
}
