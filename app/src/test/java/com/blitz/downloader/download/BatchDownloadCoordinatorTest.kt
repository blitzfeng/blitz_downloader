package com.blitz.downloader.download

import com.blitz.downloader.ui.VideoItemUiModel
import org.junit.Assert.assertEquals
import org.junit.Test

class BatchDownloadCoordinatorTest {

    @Test
    fun buildFileName_usesSanitizedTitleAndId() {
        val item = VideoItemUiModel(
            id = "7123456789012345678",
            title = "a/b:c",
            coverUrl = null,
            downloadUrl = "https://x",
            isSelected = true,
        )
        assertEquals("a_b_c_7123456789012345678.mp4", BatchDownloadCoordinator.buildFileName(item))
    }

    @Test
    fun retryDelay_matchesF2StyleThreeAttempts() {
        assertEquals(0L, BatchDownloadCoordinator.retryDelayMsForAttemptAfterFailure(0))
        assertEquals(200L, BatchDownloadCoordinator.retryDelayMsForAttemptAfterFailure(1))
        assertEquals(500L, BatchDownloadCoordinator.retryDelayMsForAttemptAfterFailure(2))
    }
}
