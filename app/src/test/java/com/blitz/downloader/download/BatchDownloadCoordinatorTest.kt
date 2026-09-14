package com.blitz.downloader.download

import com.blitz.downloader.model.VideoItemUiModel
import org.junit.Assert.assertEquals
import org.junit.Test

class BatchDownloadCoordinatorTest {

    @Test
    fun buildFileName_userAndDesc_stripsHashtags() {
        val item = VideoItemUiModel(
            id = "7123456789012345678",
            title = "展示",
            authorNickname = "作者A",
            descRaw = "四选一 #短发控 到了",
            coverUrl = null,
            downloadUrl = "https://x",
            isSelected = true,
        )
        assertEquals("作者A_四选一 到了.mp4", BatchDownloadCoordinator.buildFileName(item))
    }

    @Test
    fun buildFileName_onlyHashtags_usesVideoId() {
        val item = VideoItemUiModel(
            id = "7616284975469803471",
            title = "（无标题）",
            authorNickname = "作者B",
            descRaw = "#短发控#",
            coverUrl = null,
            downloadUrl = "https://x",
            isSelected = true,
        )
        assertEquals("作者B_7616284975469803471.mp4", BatchDownloadCoordinator.buildFileName(item))
    }

    @Test
    fun buildFileName_emptyDesc_usesVideoId() {
        val item = VideoItemUiModel(
            id = "7620359043416093876",
            title = "（无标题）",
            authorNickname = "作者C",
            descRaw = "",
            coverUrl = null,
            downloadUrl = "https://x",
            isSelected = true,
        )
        assertEquals("作者C_7620359043416093876.mp4", BatchDownloadCoordinator.buildFileName(item))
    }

    @Test
    fun buildFileName_sanitizesIllegalChars() {
        val item = VideoItemUiModel(
            id = "1",
            title = "x",
            authorNickname = "a/b",
            descRaw = "c:d",
            coverUrl = null,
            downloadUrl = "https://x",
            isSelected = true,
        )
        assertEquals("a_b_c_d.mp4", BatchDownloadCoordinator.buildFileName(item))
    }

    @Test
    fun stripHashtagTopics_fullwidthHash() {
        assertEquals("hello", BatchDownloadCoordinator.stripHashtagTopics("hello＃话题"))
    }

    @Test
    fun retryDelay_matchesF2StyleThreeAttempts() {
        assertEquals(0L, BatchDownloadCoordinator.retryDelayMsForAttemptAfterFailure(0))
        assertEquals(200L, BatchDownloadCoordinator.retryDelayMsForAttemptAfterFailure(1))
        assertEquals(500L, BatchDownloadCoordinator.retryDelayMsForAttemptAfterFailure(2))
    }

    @Test
    fun buildFileNameBase_noExtension() {
        val item = VideoItemUiModel(
            id = "7123",
            title = "图集标题",
            authorNickname = "摄影师",
            descRaw = "春日写真",
            coverUrl = null,
            downloadUrl = null,
            isSelected = true,
            isPhoto = true,
        )
        assertEquals("摄影师_春日写真", BatchDownloadCoordinator.buildFileNameBase(item))
    }

    @Test
    fun extractImageExtension_webp() {
        val url = "https://p3.douyinpic.com/img/abc~q75.webp?x=1"
        assertEquals("webp", BatchDownloadCoordinator.extractImageExtension(url))
    }

    @Test
    fun extractImageExtension_jpeg() {
        val url = "https://p3.douyinpic.com/img/abc~q75.jpeg?x=1"
        assertEquals("jpg", BatchDownloadCoordinator.extractImageExtension(url))
    }

    @Test
    fun extractImageExtension_unknown_defaultsToJpg() {
        val url = "https://p3.douyinpic.com/img/abc"
        assertEquals("jpg", BatchDownloadCoordinator.extractImageExtension(url))
    }

    @Test
    fun truncateUtf8Bytes_asciiAndChineseAndEmoji() {
        assertEquals("hello", BatchDownloadCoordinator.truncateUtf8Bytes("hello world", 5))
        // 4 Chinese characters = 12 bytes. Limit to 8 bytes -> 2 Chinese characters (6 bytes).
        assertEquals("你好", BatchDownloadCoordinator.truncateUtf8Bytes("你好世界", 8))
        // Emoji surrogate pair 😀 is 4 bytes. Limit to 5 bytes with 'a' -> 'a😀' (5 bytes).
        val emojiStr = "a😀b"
        assertEquals("a", BatchDownloadCoordinator.truncateUtf8Bytes(emojiStr, 4))
        assertEquals("a😀", BatchDownloadCoordinator.truncateUtf8Bytes(emojiStr, 5))
    }

    @Test
    fun buildFileNameBase_longChineseTitle_truncatesToWithin200Bytes() {
        val longAuthor = "超长作者昵称测试名字非常非常非常长的一个作者名用于边界测试" // 30 chars = 90 bytes
        val longDesc = "这是一条极度漫长的视频描述信息包含许多中文字符用来测试系统底层文件系统最大255字节限制是否会被安全防御住" // 50 chars = 150 bytes
        val item = VideoItemUiModel(
            id = "7999888777",
            title = "长标题",
            authorNickname = longAuthor,
            descRaw = longDesc,
            coverUrl = null,
            downloadUrl = null,
            isSelected = true,
        )
        val base = BatchDownloadCoordinator.buildFileNameBase(item)
        val byteSize = base.toByteArray(Charsets.UTF_8).size
        org.junit.Assert.assertTrue("Base byte size should be <= 200, was $byteSize", byteSize <= 200)
    }
}
