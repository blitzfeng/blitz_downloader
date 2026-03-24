package com.blitz.downloader.util

import java.util.Random

/**
 * 对齐 F2 [VerifyFpManager](https://github.com/Johnserf-Seed/f2/blob/main/f2/apps/douyin/utils.py)：
 * `verify_fp` 与 `s_v_web_id` **可在本地用固定算法生成**，不依赖 Cookie。
 *
 * 本类不访问网络；与 [DouyinTokenBootstrap] 中「从 Cookie 解析」互为补充——若接口需要且 Cookie 中缺失，可调用 [generate]。
 */
object DouyinVerifyFpGenerator {

    private const val BASE_STR = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    /**
     * 等价于 F2 `VerifyFpManager.gen_verify_fp()`。
     */
    fun generate(random: Random = Random()): String {
        var millis = System.currentTimeMillis()
        val base36 = buildString {
            while (millis > 0) {
                val remainder = (millis % 36).toInt()
                val c = if (remainder < 10) {
                    '0' + remainder
                } else {
                    'a' + (remainder - 10)
                }
                insert(0, c)
                millis /= 36
            }
        }
        val r = base36
        val o = Array(36) { "" }
        o[8] = "_"
        o[13] = "_"
        o[18] = "_"
        o[23] = "_"
        o[14] = "4"
        val t = BASE_STR.length
        for (i in 0 until 36) {
            if (o[i].isEmpty()) {
                var n = random.nextInt(t)
                if (i == 19) {
                    n = (3 and n) or 8
                }
                o[i] = BASE_STR[n].toString()
            }
        }
        return "verify_${r}_" + o.joinToString("")
    }

    /** 等价于 F2 `VerifyFpManager.gen_s_v_web_id()`（与 [generate] 相同）。 */
    fun generateSVerWebId(random: Random = Random()): String = generate(random)
}
