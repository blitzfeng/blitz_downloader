package com.blitz.downloader.api.signing

import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
/**
 * X-Bogus 签名（与 [f2.utils.xbogus](https://github.com/Johnserf-Seed/f2/blob/main/f2/utils/xbogus.py) 同源逻辑）。
 * [userAgent] 须与 HTTP 请求头 **User-Agent** 完全一致，否则服务端校验失败。
 */
class XBogusSigner(
    private val userAgent: String,
    private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val resolvedUa = userAgent.ifBlank { DEFAULT_UA }
    private val uaKey = byteArrayOf(0x00, 0x01, 0x0c)
    private val character =
        "Dkdpgh4ZKsQB80/Mfvw36XI1R25-WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe="

    /** @return (url 拼上 `&X-Bogus=`, X-Bogus 值, UA) */
    @RequiresApi(Build.VERSION_CODES.O)
    fun getXBogus(urlParams: String): Triple<String, String, String> {
        val uaRc4 = rc4Encrypt(uaKey, resolvedUa.toByteArray(StandardCharsets.ISO_8859_1))
        val uaB64 = Base64.getEncoder().encodeToString(uaRc4)
        val array1 = md5StrToArray(md5(uaB64))
        val array2 = md5StrToArray(md5(md5StrToArray("d41d8cd98f00b204e9800998ecf8427e")))
        val urlParamsArray = md5Encrypt(urlParams)
        val timer = epochSeconds().toInt()
        val ct = 536919696
        // Python `int(0.00390625) == 0`，与 F2 new_array 第二项一致
        val newArray = mutableListOf(
            64, 0, 1, 12,
            urlParamsArray[14], urlParamsArray[15], array2[14], array2[15], array1[14], array1[15],
            timer shr 24 and 0xFF, timer shr 16 and 0xFF, timer shr 8 and 0xFF, timer and 0xFF,
            ct shr 24 and 0xFF, ct shr 16 and 0xFF, ct shr 8 and 0xFF, ct and 0xFF,
        )
        var xorResult = newArray[0]
        for (i in 1 until newArray.size) {
            xorResult = xorResult xor newArray[i]
        }
        newArray.add(xorResult)

        val array3 = mutableListOf<Int>()
        val array4 = mutableListOf<Int>()
        var idx = 0
        while (idx < newArray.size) {
            array3.add(newArray[idx])
            if (idx + 1 < newArray.size) {
                array4.add(newArray[idx + 1])
            }
            idx += 2
        }
        val mergeArray = array3 + array4

        val garbledCode = encodingConversion2(
            2,
            255,
            rc4Encrypt(
                byteArrayOf(0xFF.toByte()),
                encodingConversion(mergeArray).toByteArray(StandardCharsets.ISO_8859_1),
            ).toString(StandardCharsets.ISO_8859_1),
        )

        val xb = StringBuilder()
        var gi = 0
        while (gi < garbledCode.length) {
            xb.append(
                calculation(
                    garbledCode[gi].code,
                    garbledCode[gi + 1].code,
                    garbledCode[gi + 2].code,
                ),
            )
            gi += 3
        }
        val xbStr = xb.toString()
        val params = "$urlParams&X-Bogus=$xbStr"
        return Triple(params, xbStr, resolvedUa)
    }

    private fun md5StrToArray(md5Str: String): IntArray {
        if (md5Str.length > 32) {
            return IntArray(md5Str.length) { md5Str[it].code and 0xFF }
        }
        val out = IntArray(md5Str.length / 2)
        var oi = 0
        var i = 0
        while (i < md5Str.length) {
            val hi = arrayLookup(md5Str[i].code)
            val lo = arrayLookup(md5Str[i + 1].code)
            out[oi++] = (hi shl 4) or lo
            i += 2
        }
        return out
    }

    private fun arrayLookup(ord: Int): Int {
        val v = NIBBLE_ARRAY.getOrNull(ord) ?: -1
        require(v >= 0) { "invalid MD5 hex char" }
        return v
    }

    private fun md5Encrypt(urlParams: String): IntArray {
        return md5StrToArray(md5(md5StrToArray(md5(urlParams))))
    }

    private fun md5(input: String): String {
        val array = md5StrToArray(input)
        return md5(array)
    }

    private fun md5(array: IntArray): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = ByteArray(array.size) { (array[it] and 0xFF).toByte() }
        md.update(bytes)
        return md.digest().joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
    }

    /** Python `encoding_conversion(a,b,c,e,d,t,f,r,n,o,i,_,x,u,s,l,v,h,p)` 与 `merge_array` 顺序一致。 */
    private fun encodingConversion(merge: List<Int>): String {
        require(merge.size == 19) { "merge size ${merge.size}" }
        val a = merge[0]
        val b = merge[1]
        val c = merge[2]
        val e = merge[3]
        val d = merge[4]
        val t = merge[5]
        val f = merge[6]
        val r = merge[7]
        val n = merge[8]
        val o = merge[9]
        val i = merge[10]
        val under = merge[11]
        val x = merge[12]
        val u = merge[13]
        val s = merge[14]
        val l = merge[15]
        val v = merge[16]
        val h = merge[17]
        val p = merge[18]
        val y = mutableListOf<Byte>()
        y.add((a and 0xFF).toByte())
        y.add((i and 0xFF).toByte())
        for (v0 in listOf(b, under, c, x, e, u, d, s, t, l, f, v, r, h, n, p, o)) {
            y.add((v0 and 0xFF).toByte())
        }
        return String(y.toByteArray(), StandardCharsets.ISO_8859_1)
    }

    private fun encodingConversion2(a: Int, b: Int, c: String): String {
        return Char(a).toString() + Char(b).toString() + c
    }

    private fun rc4Encrypt(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) % 256
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
        }
        var ii = 0
        var jj = 0
        val out = ByteArray(data.size)
        for (k in data.indices) {
            ii = (ii + 1) % 256
            jj = (jj + s[ii]) % 256
            val t0 = s[ii]
            s[ii] = s[jj]
            s[jj] = t0
            val enc = (data[k].toInt() xor s[(s[ii] + s[jj]) % 256]) and 0xFF
            out[k] = enc.toByte()
        }
        return out
    }

    private fun calculation(a1: Int, a2: Int, a3: Int): String {
        val x1 = (a1 and 255) shl 16
        val x2 = (a2 and 255) shl 8
        val x3 = x1 or x2 or (a3 and 255)
        return buildString(4) {
            append(character[(x3 and 16515072) shr 18])
            append(character[(x3 and 258048) shr 12])
            append(character[(x3 and 4032) shr 6])
            append(character[x3 and 63])
        }
    }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"

        /** 与 F2 `self.Array` 一致（MD5 十六进制串拆分为半字节）。 */
        private val NIBBLE_ARRAY = IntArray(256) { -1 }.also { a ->
            for (i in 0..9) a['0'.code + i] = i
            for (i in 0..5) {
                a['a'.code + i] = 10 + i
                a['A'.code + i] = 10 + i
            }
        }
    }
}
