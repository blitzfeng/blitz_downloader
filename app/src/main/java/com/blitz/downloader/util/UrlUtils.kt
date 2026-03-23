package com.blitz.downloader.util

import java.util.regex.Pattern

object UrlUtils {

    private val URL_PATTERN: Pattern = Pattern.compile(
        "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)",
        Pattern.CASE_INSENSITIVE
    )

    private val DOUYIN_HOSTS = listOf(
        "douyin.com",
        "iesdouyin.com",
        "v.douyin.com",
        "www.douyin.com",
        "www.iesdouyin.com"
    )

    fun extractFirstUrl(text: String): String? {
        val matcher = URL_PATTERN.matcher(text)
        return if (matcher.find()) matcher.group(0) else null
    }

    fun isDouyinUrl(url: String): Boolean {
        val lower = url.lowercase()
        return DOUYIN_HOSTS.any { lower.contains(it) }
    }

    fun extractDouyinUrl(text: String): String? {
        val url = extractFirstUrl(text) ?: return null
        return if (isDouyinUrl(url)) url else null
    }
}
