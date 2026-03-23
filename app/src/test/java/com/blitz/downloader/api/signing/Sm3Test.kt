package com.blitz.downloader.api.signing

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Test

class Sm3Test {

    /** 与 BouncyCastle [SM3DigestTest](https://github.com/bcgit/bc-java) 非标准向量一致。 */
    @Test
    fun emptyMessage_matchesBouncycastleVector() {
        val h = Sm3.hash(ByteArray(0)).joinToString("") { "%02x".format(it) }
        assertEquals(
            "1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b",
            h,
        )
    }

    @Test
    fun abc_matchesBouncycastleVector() {
        val h = Sm3.hash("abc".toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            h,
        )
    }
}
