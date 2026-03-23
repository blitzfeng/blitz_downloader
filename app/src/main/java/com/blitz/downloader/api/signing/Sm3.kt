package com.blitz.downloader.api.signing

/**
 * 国密 SM3 摘要（32 字节），算法与 [BouncyCastle SM3Digest](https://github.com/bcgit/bc-java) 一致，
 * 内联实现以避免 Android 工程对 `bcprov` 的解析/打包问题。
 */
internal object Sm3 {
    fun hash(message: ByteArray): ByteArray = Sm3Engine().apply {
        update(message, 0, message.size)
    }.doFinal()
}

/**
 * GeneralDigest（64 字节块）+ SM3 processBlock，与 BC 行为对齐。
 */
private class Sm3Engine {
    private val xBuf = ByteArray(4)
    private var xBufOff = 0
    private var byteCount = 0L

    private val v = IntArray(8)
    private val inwords = IntArray(16)
    private var inXOff = 0
    private val w = IntArray(68)

    companion object {
        private val T: IntArray = IntArray(64).also { arr ->
            for (i in 0 until 16) {
                val t = 0x79CC4519.toInt()
                arr[i] = (t shl i) or (t ushr (32 - i))
            }
            for (i in 16 until 64) {
                val n = i % 32
                val t = 0x7A879D8A.toInt()
                arr[i] = (t shl n) or (t ushr (32 - n))
            }
        }
    }

    init {
        resetV()
    }

    private fun resetV() {
        v[0] = 0x7380166F
        v[1] = 0x4914B2B9.toInt()
        v[2] = 0x172442D7.toInt()
        v[3] = 0xDA8A0600.toInt()
        v[4] = 0xA96F30BC.toInt()
        v[5] = 0x163138AA.toInt()
        v[6] = 0xE38DEE4D.toInt()
        v[7] = 0xB0FB0E4E.toInt()
        inXOff = 0
    }

    fun update(data: ByteArray, off: Int, len: Int) {
        var i = 0
        if (xBufOff != 0) {
            while (i < len) {
                xBuf[xBufOff++] = data[off + i++]
                if (xBufOff == 4) {
                    processWord(xBuf, 0)
                    xBufOff = 0
                    break
                }
            }
        }
        val limit = len - 3
        while (i < limit) {
            processWord(data, off + i)
            i += 4
        }
        while (i < len) {
            xBuf[xBufOff++] = data[off + i++]
        }
        byteCount += len.toLong()
    }

    fun doFinal(): ByteArray {
        val bitLength = byteCount shl 3
        updatePadByte(0x80.toByte())
        while (xBufOff != 0) {
            updatePadByte(0)
        }
        processLength(bitLength)
        processBlock()
        val out = ByteArray(32)
        var o = 0
        for (word in v) {
            out[o++] = ((word ushr 24) and 0xFF).toByte()
            out[o++] = ((word ushr 16) and 0xFF).toByte()
            out[o++] = ((word ushr 8) and 0xFF).toByte()
            out[o++] = (word and 0xFF).toByte()
        }
        return out
    }

    private fun updatePadByte(byte: Byte) {
        xBuf[xBufOff++] = byte
        if (xBufOff == 4) {
            processWord(xBuf, 0)
            xBufOff = 0
        }
        byteCount++
    }

    private fun processWord(input: ByteArray, inOff: Int) {
        val w0 =
            ((input[inOff].toInt() and 0xFF) shl 24) or
                ((input[inOff + 1].toInt() and 0xFF) shl 16) or
                ((input[inOff + 2].toInt() and 0xFF) shl 8) or
                (input[inOff + 3].toInt() and 0xFF)
        inwords[inXOff++] = w0
        if (inXOff >= 16) {
            processBlock()
        }
    }

    private fun processLength(bitLength: Long) {
        if (inXOff > 14) {
            inwords[inXOff] = 0
            inXOff++
            processBlock()
        }
        while (inXOff < 14) {
            inwords[inXOff++] = 0
        }
        inwords[inXOff++] = (bitLength ushr 32).toInt()
        inwords[inXOff++] = (bitLength and 0xFFFFFFFFL).toInt()
    }

    private fun p0(x: Int): Int {
        val r9 = (x shl 9) or (x ushr (32 - 9))
        val r17 = (x shl 17) or (x ushr (32 - 17))
        return x xor r9 xor r17
    }

    private fun p1(x: Int): Int {
        val r15 = (x shl 15) or (x ushr (32 - 15))
        val r23 = (x shl 23) or (x ushr (32 - 23))
        return x xor r15 xor r23
    }

    private fun ff0(x: Int, y: Int, z: Int) = x xor y xor z
    private fun ff1(x: Int, y: Int, z: Int) = (x and y) or (x and z) or (y and z)
    private fun gg0(x: Int, y: Int, z: Int) = x xor y xor z
    private fun gg1(x: Int, y: Int, z: Int) = (x and y) or (x.inv() and z)

    private fun processBlock() {
        for (j in 0 until 16) {
            w[j] = inwords[j]
        }
        for (j in 16 until 68) {
            val wj3 = w[j - 3]
            val r15 = (wj3 shl 15) or (wj3 ushr (32 - 15))
            val wj13 = w[j - 13]
            val r7 = (wj13 shl 7) or (wj13 ushr (32 - 7))
            w[j] = p1(w[j - 16] xor w[j - 9] xor r15) xor r7 xor w[j - 6]
        }

        var a = v[0]
        var b = v[1]
        var c = v[2]
        var d = v[3]
        var e = v[4]
        var f = v[5]
        var g = v[6]
        var h = v[7]

        for (j in 0 until 16) {
            val a12 = (a shl 12) or (a ushr (32 - 12))
            val s1 = a12 + e + T[j]
            val ss1 = (s1 shl 7) or (s1 ushr (32 - 7))
            val ss2 = ss1 xor a12
            val wj = w[j]
            val w1j = wj xor w[j + 4]
            val tt1 = ff0(a, b, c) + d + ss2 + w1j
            val tt2 = gg0(e, f, g) + h + ss1 + wj
            d = c
            c = (b shl 9) or (b ushr (32 - 9))
            b = a
            a = tt1
            h = g
            g = (f shl 19) or (f ushr (32 - 19))
            f = e
            e = p0(tt2)
        }
        for (j in 16 until 64) {
            val a12 = (a shl 12) or (a ushr (32 - 12))
            val s1 = a12 + e + T[j]
            val ss1 = (s1 shl 7) or (s1 ushr (32 - 7))
            val ss2 = ss1 xor a12
            val wj = w[j]
            val w1j = wj xor w[j + 4]
            val tt1 = ff1(a, b, c) + d + ss2 + w1j
            val tt2 = gg1(e, f, g) + h + ss1 + wj
            d = c
            c = (b shl 9) or (b ushr (32 - 9))
            b = a
            a = tt1
            h = g
            g = (f shl 19) or (f ushr (32 - 19))
            f = e
            e = p0(tt2)
        }

        v[0] = v[0] xor a
        v[1] = v[1] xor b
        v[2] = v[2] xor c
        v[3] = v[3] xor d
        v[4] = v[4] xor e
        v[5] = v[5] xor f
        v[6] = v[6] xor g
        v[7] = v[7] xor h
        inXOff = 0
    }
}
