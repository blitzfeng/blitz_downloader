package com.blitz.downloader.data

import com.blitz.downloader.llm.TagCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestedTagCodecTest {

    @Test
    fun encodeThenDecode_roundTrips() {
        val candidates = listOf(
            TagCandidate(tagId = 12L, confidence = 0.88f, evidenceFrames = listOf(1, 3)),
            TagCandidate(tagId = 45L, confidence = 0.62f),
        )
        val paths = mapOf(12L to "Download/bDouyin/covers/evidence/test_evidence_1.jpg")

        val encoded = SuggestedTagCodec.encode(candidates, paths)
        val decoded = SuggestedTagCodec.decode(encoded)

        assertEquals(2, decoded.size)
        assertEquals(0.88f, decoded.getValue(12L).confidence!!, 0.001f)
        assertEquals("Download/bDouyin/covers/evidence/test_evidence_1.jpg", decoded.getValue(12L).evidencePath)
        assertEquals(0.62f, decoded.getValue(45L).confidence!!, 0.001f)
        assertEquals(null, decoded.getValue(45L).evidencePath)
    }

    @Test
    fun decode_legacyFormat_backwardsCompatible() {
        val legacy = "12:0.88|45:0.62"
        val decoded = SuggestedTagCodec.decode(legacy)

        assertEquals(2, decoded.size)
        assertEquals(0.88f, decoded.getValue(12L).confidence!!, 0.001f)
        assertEquals(null, decoded.getValue(12L).evidencePath)
        assertEquals(0.62f, decoded.getValue(45L).confidence!!, 0.001f)
        assertEquals(null, decoded.getValue(45L).evidencePath)
    }

    @Test
    fun encode_emptyList_producesEmptyString() {
        assertEquals("", SuggestedTagCodec.encode(emptyList()))
    }

    @Test
    fun decode_emptyString_producesEmptyMap() {
        assertTrue(SuggestedTagCodec.decode("").isEmpty())
    }

    @Test
    fun decode_malformedEntry_skippedWithoutThrowing() {
        val decoded = SuggestedTagCodec.decode("12:0.5|not-a-number:0.9|45:0.3")

        assertEquals(2, decoded.size)
        assertTrue(decoded.containsKey(12L))
        assertTrue(decoded.containsKey(45L))
    }
}
