package com.blitz.downloader.api

import com.blitz.downloader.util.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class DouyinPageKind {
    VIDEO,
    USER,
    MIX,
    /** v.douyin.com 展开失败或未 302 到带 ID 的落地页 */
    SHORT_UNRESOLVED,
    UNKNOWN
}

data class ParsedDouyinLink(
    val awemeId: String? = null,
    val secUserId: String? = null,
    val mixId: String? = null,
    val kind: DouyinPageKind,
    /** 短链展开后的 URL，否则为规范化后的输入链接 */
    val canonicalUrl: String
)

/**
 * 从分享文案、短链或 PC/移动页 URL 中解析 aweme_id、sec_user_id、mix_id。
 * 短链仅对 `v.douyin.com` 做有限次 302 跟随；其余路径依赖本地正则与 query 提取。
 */
@Suppress("unused")
object DouyinUrlParser {

    private const val MAX_REDIRECTS = 15

    private val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"

    private val redirectClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val videoPathRegex =
        Regex("""/(?:share/)?video/(\d+)""", RegexOption.IGNORE_CASE)
    private val notePathRegex =
        Regex("""/(?:share/)?note/(\d+)""", RegexOption.IGNORE_CASE)
    private val userPathRegex =
        Regex("""/(?:share/)?user/([^/?#]+)""", RegexOption.IGNORE_CASE)
    private val collectionPathRegex =
        Regex("""/(?:share/)?collection/(\d+)""", RegexOption.IGNORE_CASE)
    private val mixDetailRegex =
        Regex("""/(?:share/)?mix/detail/(\d+)""", RegexOption.IGNORE_CASE)
    private val seriesPathRegex =
        Regex("""/(?:share/)?series/(\d+)""", RegexOption.IGNORE_CASE)
    private val itemIdsInTextRegex =
        Regex("""item[_\s-]*ids?[=：:\s]+(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * 先取文案中首个 http(s) 链接（若无则整段当 URL），再展开 `v.douyin.com`，最后提取 ID。
     */
    suspend fun parse(input: String): ParsedDouyinLink = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return@withContext ParsedDouyinLink(kind = DouyinPageKind.UNKNOWN, canonicalUrl = "")
        }
        val urlSeed = UrlUtils.extractFirstUrl(trimmed) ?: trimmed
        val expanded = expandShortChainSync(urlSeed)
        parseExpandedUrl(expanded, fallbackText = trimmed)
    }

    /**
     * 将 `v.douyin.com/...` 跟随重定向至落地页；其它输入只做 `https://` 补全，不发起请求。
     */
    suspend fun expandShortChain(urlOrBare: String): String = withContext(Dispatchers.IO) {
        expandShortChainSync(urlOrBare)
    }

    private fun expandShortChainSync(urlOrBare: String): String {
        var s = urlOrBare.trim()
        if (s.isEmpty()) return s
        if (!s.contains("://", ignoreCase = true)) s = "https://$s"
        val http = s.toHttpUrlOrNull() ?: return s
        return if (http.host.equals("v.douyin.com", ignoreCase = true)) {
            followRedirects(s)
        } else {
            s
        }
    }

    /**
     * 从已是落地页的 URL 提取 ID，不发起网络请求。
     *
     * @param fallbackText 当路径/query 无作品 id 时，尝试从分享全文匹配 item ids 等。
     */
    fun parseExpandedUrl(urlString: String, fallbackText: String? = null): ParsedDouyinLink {
        val normalized = urlString.trim()
        if (normalized.isEmpty()) {
            return ParsedDouyinLink(kind = DouyinPageKind.UNKNOWN, canonicalUrl = "")
        }
        val http = normalized.toHttpUrlOrNull()
        val canonical = http?.toString() ?: normalized
        val host = http?.host?.lowercase().orEmpty()
        val isDouyinHost = host.contains("douyin.com") || host.contains("iesdouyin.com")
        if (http == null || !isDouyinHost) {
            val fromText = fallbackText?.let { extractAwemeFromFreeText(it) }
            return ParsedDouyinLink(
                awemeId = fromText,
                kind = if (fromText != null) DouyinPageKind.VIDEO else DouyinPageKind.UNKNOWN,
                canonicalUrl = canonical
            )
        }

        val path = http.encodedPath

        val modalId = http.queryParameter("modal_id")?.takeIf { it.all(Char::isDigit) }
        val awemeIdQ = http.queryParameter("aweme_id")?.takeIf { it.all(Char::isDigit) }
        val mixIdQ = (http.queryParameter("mix_id") ?: http.queryParameter("mixId"))
            ?.takeIf { it.all(Char::isDigit) }
        val secFromQ = http.queryParameter("sec_user_id") ?: http.queryParameter("sec_uid")

        val videoMatch = videoPathRegex.find(path)
        val noteMatch = notePathRegex.find(path)
        val userMatch = userPathRegex.find(path)
        val collMatch = collectionPathRegex.find(path)
        val mixDetailMatch = mixDetailRegex.find(path)
        val seriesMatch = seriesPathRegex.find(path)

        val awemeId = videoMatch?.groupValues?.getOrNull(1)
            ?: noteMatch?.groupValues?.getOrNull(1)
            ?: modalId
            ?: awemeIdQ
            ?: fallbackText?.let { extractAwemeFromFreeText(it) }

        val secUserId = userMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: secFromQ?.takeIf { it.isNotBlank() }

        val mixId = collMatch?.groupValues?.getOrNull(1)
            ?: mixDetailMatch?.groupValues?.getOrNull(1)
            ?: seriesMatch?.groupValues?.getOrNull(1)
            ?: mixIdQ

        val kind = when {
            awemeId != null -> DouyinPageKind.VIDEO
            mixId != null -> DouyinPageKind.MIX
            secUserId != null -> DouyinPageKind.USER
            host.equals("v.douyin.com", ignoreCase = true) -> DouyinPageKind.SHORT_UNRESOLVED
            else -> DouyinPageKind.UNKNOWN
        }

        return ParsedDouyinLink(
            awemeId = awemeId,
            secUserId = secUserId,
            mixId = mixId,
            kind = kind,
            canonicalUrl = canonical
        )
    }

    private fun extractAwemeFromFreeText(text: String): String? {
        val m = itemIdsInTextRegex.find(text) ?: return null
        return m.groupValues.getOrNull(1)
    }

    private fun followRedirects(seed: String): String {
        var current = seed
        repeat(MAX_REDIRECTS) {
            val httpUrl = current.toHttpUrlOrNull() ?: return current
            val req = Request.Builder()
                .url(httpUrl)
                .header("User-Agent", CHROME_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()
            redirectClient.newCall(req).execute().use { resp ->
                when (resp.code) {
                    in 300..308 -> {
                        val loc = resp.header("Location") ?: return current
                        current = resolveLocation(current, loc)
                    }
                    else -> return current
                }
            }
        }
        return current
    }

    private fun resolveLocation(current: String, location: String): String {
        location.toHttpUrlOrNull()?.let { return it.toString() }
        return current.toHttpUrlOrNull()?.resolve(location)?.toString() ?: location
    }
}
