package com.blitz.downloader.util

import org.junit.Assert.assertTrue
import org.junit.Test

class FaceFrameSelectorTest {

    @Test
    fun selectFaceFrames_emptyCandidates_returnsEmptyListWithoutTouchingMlKit() {
        // 空候选列表在触碰 ML Kit（Google Play Services，纯 JVM 测试用不了）之前就应该提前返回。
        val result = FaceFrameSelector.selectFaceFrames(emptyList(), maxFaceFrames = 4)
        assertTrue(result.isEmpty())
    }

    @Test
    fun selectFaceFrames_zeroMaxFaceFrames_returnsEmptyList() {
        val result = FaceFrameSelector.selectFaceFrames(listOf(), maxFaceFrames = 0)
        assertTrue(result.isEmpty())
    }
}
