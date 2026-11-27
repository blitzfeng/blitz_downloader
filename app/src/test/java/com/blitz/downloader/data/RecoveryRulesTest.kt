package com.blitz.downloader.data

import com.blitz.downloader.data.db.DownloadedVideoEntity
import com.blitz.downloader.model.*
import org.junit.Assert.*
import org.junit.Test

class RecoveryRulesTest {
    private val work = "7390000000000000001"

    @Test fun identitiesAndGroups() {
        val a = RecoveryNames.parse("videos", "同作者_同描述_$work (1).mp4")!!
        assertEquals(work, a.work)
        assertEquals(work, RecoveryNames.parse("images", "同作者_${work}_03.jpg")!!.work)
        assertEquals(3, RecoveryNames.parse("images", "同作者_${work}_03.mp4")!!.ordinal)
        assertEquals(0, RecoveryNames.parse("covers/evidence", "${work}_evidence_0.jpg")!!.ordinal)
        assertNull(RecoveryNames.parse("videos", "作者_" + "1".repeat(64) + ".mp4")!!.work)
        assertNull(RecoveryNames.parse("videos", "../${work}.mp4"))
        assertNull(RecoveryNames.parse("videos", ".nomedia"))
        assertNull(RecoveryNames.parse("covers/evidence", "x_${work}_evidence_1.jpg"))
        assertEquals(work, RecoveryNames.workId("https://www.douyin.com/note/$work"))
        assertNull(RecoveryNames.workId("https://www.douyin.com@other.test/video/$work"))
        assertTrue(RecoveryNames.shortLink("https://v.douyin.com/Test_id/"))
        assertFalse(RecoveryNames.shortLink("https://v.douyin.com.other.test/Test_id/"))
    }

    @Test fun independentFramesAndSamplesAreRequired() {
        assertEquals(0,framePairScore(listOf(0,-1),listOf(-1,0)))
        assertEquals(129,framePairScore(listOf(0,0),listOf(0,1)))
        assertEquals(129,framePairScore(listOf(0,1),listOf(0,0)))
        val random = java.util.Random(42)
        repeat(100) {
            val proofs = List(4) { random.nextInt(65536).toLong() }.distinct()
            val samples = List(5) { random.nextInt(65536).toLong() }.distinct()
            var expected = 129
            for (a in proofs) for (b in proofs.filterNot { it == a }) {
                for (x in samples) for (y in samples.filterNot { it == x }) {
                    val d1 = java.lang.Long.bitCount(a xor x)
                    val d2 = java.lang.Long.bitCount(b xor y)
                    if (maxOf(d1,d2) <= 8) expected = minOf(expected,d1+d2)
                }
            }
            assertEquals(expected,framePairScore(proofs,samples))
        }
    }

    @Test fun originalAndRecoveredZeroHaveDifferentMeanings() {
        val regular = DownloadedVideoEntity(awemeId = work, userName = "合成", downloadType = "post")
        val recovered = regular.copy(downloadType = DownloadSourceType.RECOVERED)
        assertTrue(regular.knownUnwatched)
        assertTrue(regular.knownNeverExported)
        assertNotNull(regular.originalDownloadTime)
        assertFalse(recovered.knownUnwatched)
        assertFalse(recovered.knownNeverExported)
        assertNull(recovered.originalDownloadTime)
        assertTrue(recovered.copy(watched = true, exportCount = 1).hasUnknownHistory)
    }
}
