package com.blitz.downloader.data

import com.blitz.downloader.api.AwemeItem
import com.blitz.downloader.api.AwemeMapper
import com.blitz.downloader.data.db.LikedListIndexItemEntity
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class LikedIndexMediaTest {
    private fun row(photo: Boolean, json: String? = null) = LikedListIndexItemEntity(
        sourceKey = "like|owner|", awemeId = "123", sourcePosition = 0,
        title = "图集", authorNickname = "作者", authorSecUserId = "author",
        description = "图集", createTime = 1, isPhoto = photo, collectStat = 0,
        userDigged = 1, diggCount = 0, collectCount = 0,
        mediaUrl = "https://example.com/legacy", photoMediaJson = json,
    )

    @Test fun photoAndLivePhotoSurviveCacheRoundTrip() {
        val aweme = Gson().fromJson("""
            {"aweme_id":"123","images":[
              {"url_list":["https://example.com/1.webp"]},
              {"url_list":["https://example.com/2.webp"],
               "video":{"play_addr":{"url_list":["https://example.com/2.mp4"]}}},
              {"url_list":["https://example.com/3.webp"]}
            ]}
        """.trimIndent(), AwemeItem::class.java)
        val original = AwemeMapper.toGridItemOrNull(aweme)!!
        val restored = LikedIndexMedia.restore(row(true, LikedIndexMedia.encode(original)))
        assertTrue(restored.isPhoto)
        assertNull(restored.downloadUrl)
        assertEquals(3, restored.imageUrls.size)
        assertEquals(original.imageUrls, restored.imageUrls)
        assertEquals(listOf(null, "https://example.com/2.mp4", null), restored.imageVideoUrls)
        assertTrue(restored.hasLivePhoto)
        assertEquals(listOf("https://example.com/2.webp"),
            restored.copy(selectedImageIndices = setOf(1)).downloadImageUrls)
    }

    @Test fun photoPayloadOverridesIncorrectLegacyVideoFlag() {
        val restored = LikedIndexMedia.restore(row(false,
            """[{"image":"https://example.com/1.webp","video":null}]"""))
        assertTrue(restored.isPhoto)
        assertNull(restored.downloadUrl)
    }

    @Test fun legacyPhotoDoesNotBecomeVideoOrPretendFirstImageIsWholeAlbum() {
        val restored = LikedIndexMedia.restore(row(true))
        assertTrue(restored.isPhoto)
        assertNull(restored.downloadUrl)
        assertTrue(restored.imageUrls.isEmpty())
    }

    @Test fun videoAndBrokenPhotoCacheAreHandled() {
        assertEquals("https://example.com/legacy", LikedIndexMedia.restore(row(false)).downloadUrl)
        val photo = LikedIndexMedia.restore(row(true, "broken json"))
        assertTrue(photo.isPhoto)
        assertTrue(photo.imageUrls.isEmpty())
        assertNull(photo.downloadUrl)
    }
}
