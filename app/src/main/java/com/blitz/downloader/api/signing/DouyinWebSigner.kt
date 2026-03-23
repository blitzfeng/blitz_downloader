package com.blitz.downloader.api.signing

import kotlin.random.Random

/**
 * 抖音 Web 列表/详情 GET：先 [XBogusSigner] 再 [ABogusSigner]，与 F2 侧 UA、指纹绑定方式一致。
 * [encodedQuery] 须为 **已与最终请求一致的** `encodedQuery`（不含 `X-Bogus` / `a_bogus`），通常来自 [AwemeWebUrls]。
 */
object DouyinWebSigner {

    fun signGetEncodedQuery(
        encodedQueryWithoutSignatures: String,
        userAgent: String,
        random: Random = Random.Default,
    ): String {
        val fp = DouyinBrowserFingerprint.generate(DouyinBrowserFingerprint.BrowserType.EDGE, random)
        val xb = XBogusSigner(userAgent).getXBogus(encodedQueryWithoutSignatures).second
        val withXb = "$encodedQueryWithoutSignatures&X-Bogus=$xb"
        return ABogusSigner(
            browserFingerprint = fp,
            userAgent = userAgent,
            random = random,
        ).generateAbogus(withXb, "").paramsWithAbogus
    }

    /**
     * POST JSON：与 F2 `fetch_user_collection` 一致，[encodedQueryWithoutSignatures] 与 [jsonBody] 为同一套参数（query + body），
     * [ABogusSigner] 第二段为正文。
     */
    fun signPostJsonEncodedQuery(
        encodedQueryWithoutSignatures: String,
        jsonBody: String,
        userAgent: String,
        random: Random = Random.Default,
    ): String {
        val fp = DouyinBrowserFingerprint.generate(DouyinBrowserFingerprint.BrowserType.EDGE, random)
        val xb = XBogusSigner(userAgent).getXBogus(encodedQueryWithoutSignatures).second
        val withXb = "$encodedQueryWithoutSignatures&X-Bogus=$xb"
        return ABogusSigner(
            browserFingerprint = fp,
            userAgent = userAgent,
            random = random,
        ).generateAbogus(withXb, jsonBody).paramsWithAbogus
    }
}
