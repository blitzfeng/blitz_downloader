package com.blitz.downloader.util

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameExtractorTest {

    @Test
    fun extract_fileDoesNotExist_returnsEmptyListWithoutTouchingMediaFramework() {
        // 文件不存在时应在触碰 MediaMetadataRetriever（Android 框架类，纯 JVM 测试用不了）之前
        // 就提前返回——这条早退路径本身就是"抽帧失败不阻断整个建议流程"的降级保证。
        val result = VideoFrameExtractor.extract(File("/definitely/not/a/real/path.mp4"))
        assertTrue(result.isEmpty())
    }
}
