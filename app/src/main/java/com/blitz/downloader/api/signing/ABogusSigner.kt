package com.blitz.downloader.api.signing

import kotlin.random.Random

/**
 * a_bogus（A-Bogus）签名，逻辑对齐 [f2.utils.abogus](https://github.com/Johnserf-Seed/f2/blob/main/f2/utils/abogus.py)。
 * [userAgent]、浏览器指纹须与请求环境一致；UA 与 [XBogusSigner]、[DouyinApiClient] 请求头应相同。
 */
class ABogusSigner(
    private val browserFingerprint: String,
    private val userAgent: String,
    private val options: IntArray = intArrayOf(0, 1, 14),
    private val millisProvider: () -> Long = { System.currentTimeMillis() },
    private val random: Random = Random.Default,
) {
    private val resolvedUa = userAgent.ifBlank { DEFAULT_UA }
    private val salt = "cus"
    private val uaKey = byteArrayOf(0x00, 0x01, 0x0E)
    private val character =
        "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe"
    private val character2 =
        "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe"
    private val characterList = listOf(character, character2)
    private val crypto = CryptoUtility(salt, characterList)

    private val aid = 6383
    private val pageId = 0
    private val boe = false
    private val ddrt = 8.5
    private val paths = listOf(
        "^/webcast/", "^/aweme/v1/", "^/aweme/v2/",
        "/v1/message/send", "^/live/", "^/captcha/", "^/ecom/",
    )

    private val sortIndex = intArrayOf(
        18, 20, 52, 26, 30, 34, 58, 38, 40, 53, 42, 21, 27, 54, 55, 31, 35, 57, 39,
        41, 43, 22, 28, 32, 60, 36, 23, 29, 33, 37, 44, 45, 59, 46, 47, 48, 49, 50, 24, 25, 65, 66, 70, 71,
    )
    private val sortIndex2 = intArrayOf(
        18, 20, 26, 30, 34, 38, 40, 42, 21, 27, 31, 35, 39, 41, 43, 22, 28, 32, 36, 23, 29, 33, 37,
        44, 45, 46, 47, 48, 49, 50, 24, 25, 52, 53, 54, 55, 57, 58, 59, 60, 65, 66, 70, 71,
    )

    /**
     * @param params 不含 `a_bogus` 的 query 串（可与 X-Bogus 已拼接，取决于调用方）
     * @param body POST 正文；GET 为空
     * @return (完整 query 含 `a_bogus`, a_bogus 值, ua, body)
     */
    fun generateAbogus(params: String, body: String = ""): Quadruple {
        val abDir = mutableMapOf<Int, Any>()
        abDir[8] = 3
        abDir[15] = mapOf(
            "aid" to aid,
            "pageId" to pageId,
            "boe" to boe,
            "ddrt" to ddrt,
            "paths" to paths,
            "track" to mapOf("mode" to 0, "delay" to 300, "paths" to emptyList<Any>()),
            "dump" to true,
            "rpU" to "",
        )
        abDir[18] = 44
        abDir[19] = listOf(1, 0, 1, 0, 1)
        abDir[66] = 0
        abDir[69] = 0
        abDir[70] = 0
        abDir[71] = 0

        val startEncryption = millisProvider()
        val array1 = crypto.sm3OfIntDigest(crypto.paramsToArraySalted(params))
        val array2 = crypto.sm3OfIntDigest(crypto.paramsToArraySalted(body))
        val uaRc4 = crypto.rc4Encrypt(uaKey, resolvedUa)
        val uaOrdStr = uaRc4.toString(Charsets.ISO_8859_1)
        val uaB64 = crypto.base64Encode(uaOrdStr, 1)
        val array3 = crypto.paramsToArrayRawUtf8(uaB64)

        val endEncryption = millisProvider()

        abDir[20] = ((startEncryption shr 24) and 255).toInt()
        abDir[21] = ((startEncryption shr 16) and 255).toInt()
        abDir[22] = ((startEncryption shr 8) and 255).toInt()
        abDir[23] = (startEncryption and 255).toInt()
        abDir[24] = (startEncryption / 256 / 256 / 256 / 256).toInt() shr 0
        abDir[25] = (startEncryption / 256 / 256 / 256 / 256 / 256).toInt() shr 0

        abDir[26] = (options[0] shr 24) and 255
        abDir[27] = (options[0] shr 16) and 255
        abDir[28] = (options[0] shr 8) and 255
        abDir[29] = options[0] and 255

        abDir[30] = (options[1] / 256) and 255
        abDir[31] = (options[1] % 256) and 255
        abDir[32] = (options[1] shr 24) and 255
        abDir[33] = (options[1] shr 16) and 255

        abDir[34] = (options[2] shr 24) and 255
        abDir[35] = (options[2] shr 16) and 255
        abDir[36] = (options[2] shr 8) and 255
        abDir[37] = options[2] and 255

        abDir[38] = array1[21]
        abDir[39] = array1[22]
        abDir[40] = array2[21]
        abDir[41] = array2[22]
        abDir[42] = array3[23]
        abDir[43] = array3[24]

        abDir[44] = ((endEncryption shr 24) and 255).toInt()
        abDir[45] = ((endEncryption shr 16) and 255).toInt()
        abDir[46] = ((endEncryption shr 8) and 255).toInt()
        abDir[47] = (endEncryption and 255).toInt()
        abDir[48] = abDir[8] as Int
        abDir[49] = (endEncryption / 256 / 256 / 256 / 256).toInt() shr 0
        abDir[50] = (endEncryption / 256 / 256 / 256 / 256 / 256).toInt() shr 0

        abDir[51] = (pageId shr 24) and 255
        abDir[52] = (pageId shr 16) and 255
        abDir[53] = (pageId shr 8) and 255
        abDir[54] = pageId and 255
        abDir[55] = pageId
        abDir[56] = aid
        abDir[57] = aid and 255
        abDir[58] = (aid shr 8) and 255
        abDir[59] = (aid shr 16) and 255
        abDir[60] = (aid shr 24) and 255

        abDir[64] = browserFingerprint.length
        abDir[65] = browserFingerprint.length

        val sortedValues = sortIndex.map { k -> intFromAbDir(abDir, k) }.toMutableList()
        val edgeFpArray = browserFingerprint.map { it.code and 0xFF }
        var abXor = (browserFingerprint.length and 255) shr 8 and 255
        for (index in 0 until sortIndex2.size - 1) {
            if (index == 0) {
                abXor = intFromAbDir(abDir, sortIndex2[index])
                abXor = abXor xor intFromAbDir(abDir, sortIndex2[index + 1])
            }
        }
        sortedValues.addAll(edgeFpArray)
        sortedValues.add(abXor)

        val abogusBytesStr = generateRandomBytes() + crypto.transformBytes(sortedValues.toIntArray())
        val abogus = crypto.abogusEncode(abogusBytesStr, 0)
        val full = "$params&a_bogus=$abogus"
        return Quadruple(full, abogus, resolvedUa, body)
    }

    private fun intFromAbDir(m: Map<Int, Any>, k: Int): Int =
        when (val v = m[k]) {
            is Int -> v
            null -> 0
            else -> 0
        }

    private fun generateRandomBytes(length: Int = 3): String = buildString {
        repeat(length) {
            val rd = (random.nextDouble() * 10000).toInt()
            append((((rd and 255) and 170) or 1).toChar())
            append((((rd and 255) and 85) or 2).toChar())
            append(((jsShiftRight(rd, 8) and 170) or 5).toChar())
            append(((jsShiftRight(rd, 8) and 85) or 40).toChar())
        }
    }

    private fun jsShiftRight(v: Int, n: Int): Int =
        (v.toLong() and 0xFFFFFFFFL).toInt() ushr n

    data class Quadruple(
        val paramsWithAbogus: String,
        val aBogus: String,
        val userAgent: String,
        val body: String,
    )

    private class CryptoUtility(
        private val salt: String,
        private val base64Alphabets: List<String>,
    ) {
        private val bigArray: IntArray = BIG_ARRAY_TEMPLATE.clone()

        /** 第一层：query/body  UTF-8 再加盐后 SM3。 */
        fun paramsToArraySalted(param: String): IntArray =
            sm3ToArray((param + salt).toByteArray(Charsets.UTF_8))

        /** 第二层：对 32 字节摘要数组再 SM3（不加盐）。 */
        fun sm3OfIntDigest(digestInts: IntArray): IntArray {
            val bytes = ByteArray(digestInts.size) { (digestInts[it] and 0xFF).toByte() }
            return sm3ToArray(bytes)
        }

        fun paramsToArrayRawUtf8(param: String): IntArray =
            sm3ToArray(param.toByteArray(Charsets.UTF_8))

        fun base64Encode(inputString: String, selectedAlphabet: Int): String {
            val binary = buildString {
                for (ch in inputString) {
                    append(Integer.toBinaryString(0x10000 or ch.code).substring(1))
                }
            }
            var pad = (6 - binary.length % 6) % 6
            val padded = binary + "0".repeat(pad)
            val indices = padded.chunked(6).map { it.toInt(2) }
            val alphabet = base64Alphabets[selectedAlphabet]
            val out = indices.joinToString("") { alphabet[it].toString() }
            return out + "=".repeat(pad / 2)
        }

        fun abogusEncode(abogusBytesStr: String, selectedAlphabet: Int): String {
            val alphabet = base64Alphabets[selectedAlphabet]
            val ab = StringBuilder()
            var i = 0
            while (i < abogusBytesStr.length) {
                val n = when {
                    i + 2 < abogusBytesStr.length ->
                        (abogusBytesStr[i].code shl 16) or
                            (abogusBytesStr[i + 1].code shl 8) or
                            abogusBytesStr[i + 2].code
                    i + 1 < abogusBytesStr.length ->
                        (abogusBytesStr[i].code shl 16) or (abogusBytesStr[i + 1].code shl 8)
                    else -> abogusBytesStr[i].code shl 16
                }
                val js = intArrayOf(18, 12, 6, 0)
                val masks = intArrayOf(0xFC0000, 0x03F000, 0x0FC0, 0x3F)
                for (t in 0..3) {
                    val j = js[t]
                    val k = masks[t]
                    if (j == 6 && i + 1 >= abogusBytesStr.length) break
                    if (j == 0 && i + 2 >= abogusBytesStr.length) break
                    ab.append(alphabet[(n and k) shr j])
                }
                i += 3
            }
            val pad = (4 - ab.length % 4) % 4
            repeat(pad) { ab.append('=') }
            return ab.toString()
        }

        fun transformBytes(bytesList: IntArray): String {
            val bytesStr = bytesList.joinToString("") { (it and 0xFF).toChar().toString() }
            val result = StringBuilder()
            var indexB = bigArray[1]
            var initialValue = 0
            var valueE = 0
            bytesStr.forEachIndexed { index, char ->
                val sumInitial = if (index == 0) {
                    initialValue = bigArray[indexB]
                    val s = indexB + initialValue
                    bigArray[1] = initialValue
                    bigArray[indexB] = indexB
                    s
                } else {
                    initialValue + valueE
                }
                val charValue = char.code
                var si = sumInitial % bigArray.size
                val valueF = bigArray[si]
                result.append((charValue xor valueF).toChar())
                valueE = bigArray[(index + 2) % bigArray.size]
                si = (indexB + valueE) % bigArray.size
                initialValue = bigArray[si]
                bigArray[si] = bigArray[(index + 2) % bigArray.size]
                bigArray[(index + 2) % bigArray.size] = initialValue
                indexB = si
            }
            return result.toString()
        }

        fun rc4Encrypt(key: ByteArray, plaintext: String): ByteArray {
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0 until 256) {
                j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) % 256
                val t = s[i]
                s[i] = s[j]
                s[j] = t
            }
            var ii = 0
            var jj = 0
            val out = ByteArray(plaintext.length)
            for (idx in plaintext.indices) {
                ii = (ii + 1) % 256
                jj = (jj + s[ii]) % 256
                val t0 = s[ii]
                s[ii] = s[jj]
                s[jj] = t0
                val k = s[(s[ii] + s[jj]) % 256]
                out[idx] = ((plaintext[idx].code xor k) and 0xFF).toByte()
            }
            return out
        }

        private fun sm3ToArray(input: ByteArray): IntArray {
            val out = Sm3.hash(input)
            return IntArray(out.size) { out[it].toInt() and 0xFF }
        }

        companion object {
            private val BIG_ARRAY_TEMPLATE = intArrayOf(
                121, 243, 55, 234, 103, 36, 47, 228, 30, 231, 106, 6, 115, 95, 78, 101, 250, 207, 198, 50,
                139, 227, 220, 105, 97, 143, 34, 28, 194, 215, 18, 100, 159, 160, 43, 8, 169, 217, 180, 120,
                247, 45, 90, 11, 27, 197, 46, 3, 84, 72, 5, 68, 62, 56, 221, 75, 144, 79, 73, 161,
                178, 81, 64, 187, 134, 117, 186, 118, 16, 241, 130, 71, 89, 147, 122, 129, 65, 40, 88, 150,
                110, 219, 199, 255, 181, 254, 48, 4, 195, 248, 208, 32, 116, 167, 69, 201, 17, 124, 125, 104,
                96, 83, 80, 127, 236, 108, 154, 126, 204, 15, 20, 135, 112, 158, 13, 1, 188, 164, 210, 237,
                222, 98, 212, 77, 253, 42, 170, 202, 26, 22, 29, 182, 251, 10, 173, 152, 58, 138, 54, 141,
                185, 33, 157, 31, 252, 132, 233, 235, 102, 196, 191, 223, 240, 148, 39, 123, 92, 82, 128, 109,
                57, 24, 38, 113, 209, 245, 2, 119, 153, 229, 189, 214, 230, 174, 232, 63, 52, 205, 86, 140,
                66, 175, 111, 171, 246, 133, 238, 193, 99, 60, 74, 91, 225, 51, 76, 37, 145, 211, 166, 151,
                213, 206, 0, 200, 244, 176, 218, 44, 184, 172, 49, 216, 93, 168, 53, 21, 183, 41, 67, 85,
                224, 155, 226, 242, 87, 177, 146, 70, 190, 12, 162, 19, 137, 114, 25, 165, 163, 192, 23, 59,
                9, 94, 179, 107, 35, 7, 142, 131, 239, 203, 149, 136, 61, 249, 14, 156,
            )
        }
    }

    companion object {
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
    }
}

/** 与 F2 [BrowserFingerprintGenerator] 行为一致的 Win32/Edge 风格指纹（每次随机）。 */
object DouyinBrowserFingerprint {
    fun generate(browserType: BrowserType = BrowserType.EDGE, rnd: Random = Random.Default): String =
        when (browserPlatform(browserType)) {
            "Win32" -> generateWin32Like(rnd)
            "MacIntel" -> generateMacLike(rnd)
            else -> generateWin32Like(rnd)
        }

    private fun browserPlatform(t: BrowserType): String =
        when (t) {
            BrowserType.SAFARI -> "MacIntel"
            else -> "Win32"
        }

    private fun generateWin32Like(rnd: Random): String {
        val innerW = rnd.nextInt(1024, 1921)
        val innerH = rnd.nextInt(768, 1081)
        val outerW = innerW + rnd.nextInt(24, 33)
        val outerH = innerH + rnd.nextInt(75, 91)
        val screenX = 0
        val screenY = if (rnd.nextBoolean()) 0 else 30
        val sizeW = rnd.nextInt(1024, 1921)
        val sizeH = rnd.nextInt(768, 1081)
        val availW = rnd.nextInt(1280, 1921)
        val availH = rnd.nextInt(800, 1081)
        return "$innerW|$innerH|$outerW|$outerH|$screenX|$screenY|0|0|$sizeW|$sizeH|$availW|$availH|$innerW|$innerH|24|24|Win32"
    }

    private fun generateMacLike(rnd: Random): String {
        val innerW = rnd.nextInt(1024, 1921)
        val innerH = rnd.nextInt(768, 1081)
        val outerW = innerW + rnd.nextInt(24, 33)
        val outerH = innerH + rnd.nextInt(75, 91)
        val screenX = 0
        val screenY = if (rnd.nextBoolean()) 0 else 30
        val sizeW = rnd.nextInt(1024, 1921)
        val sizeH = rnd.nextInt(768, 1081)
        val availW = rnd.nextInt(1280, 1921)
        val availH = rnd.nextInt(800, 1081)
        return "$innerW|$innerH|$outerW|$outerH|$screenX|$screenY|0|0|$sizeW|$sizeH|$availW|$availH|$innerW|$innerH|24|24|MacIntel"
    }

    enum class BrowserType { CHROME, FIREFOX, SAFARI, EDGE }
}
