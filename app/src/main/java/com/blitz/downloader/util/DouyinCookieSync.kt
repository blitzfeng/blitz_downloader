package com.blitz.downloader.util

import android.webkit.CookieManager
import com.blitz.downloader.api.DouyinApiClient

/**
 * 将 WebView/CookieManager 中的抖音域 Cookie 同步到 [DouyinApiClient.globalCookie]，
 * 或从用户粘贴的字符串写入（登录主场景与手动导入共用同一存储）。
 *
 * 票据字段如何从 Cookie 映射到 [DouyinApiClient] 见 [DouyinTokenBootstrap]。
 */
object DouyinCookieSync {

    private val cookieLookupUrls = listOf(
        "https://www.douyin.com",
        "https://www.douyin.com/",
        "https://www.iesdouyin.com",
        "https://www.iesdouyin.com/",
    )

    data class CookieTokenSnapshot(
        val pairCount: Int,
        val lineLength: Int,
        val hasMsToken: Boolean,
        val hasWebId: Boolean,
        val hasTtwid: Boolean,
        val hasVerifyFp: Boolean,
        val hasLoginSession: Boolean,
    )

    fun cookieTokenSnapshot(cookieLine: String?): CookieTokenSnapshot {
        val line = cookieLine?.trim().orEmpty()
        if (line.isEmpty()) {
            return CookieTokenSnapshot(0, 0, false, false, false, false, false)
        }
        val pairs = line.split(";").count { part ->
            val p = part.trim()
            p.isNotEmpty() && p.contains("=")
        }
        return CookieTokenSnapshot(
            pairCount = pairs,
            lineLength = line.length,
            hasMsToken = parseCookieValue(line, "msToken") != null,
            hasWebId = webIdFromCookie(line) != null,
            hasTtwid = parseCookieValue(line, "ttwid") != null,
            hasVerifyFp = parseCookieValue(line, "s_v_web_id") != null
                || parseCookieValue(line, "verify_fp") != null,
            hasLoginSession = parseCookieValue(line, "sessionid") != null
                || parseCookieValue(line, "sessionid_ss") != null,
        )
    }

    /**
     * 读取系统 [CookieManager] 中抖音相关 URL 的 Cookie，合并去重后写入 [DouyinApiClient.globalCookie]。
     * 若本地已有粘贴/历史 Cookie，会与本次读到的按键合并，**同名键以本次 WebView/CookieManager 为准**。
     *
     * @return 写入后的 Cookie 字符串；若均为空则返回 null 且不修改 globalCookie（保留粘贴导入的值）。
     */
    fun syncFromCookieManager(cookieManager: CookieManager = CookieManager.getInstance()): String? {
        val chunks = cookieLookupUrls.mapNotNull { url ->
            cookieManager.getCookie(url)?.takeIf { it.isNotBlank() }
        }
        if (chunks.isEmpty()) return null
        val fromWeb = mergeCookieHeaderChunks(chunks)
        if (fromWeb.isBlank()) return null
        val existing = DouyinApiClient.globalCookie?.takeIf { it.isNotBlank() }
        val merged = if (existing.isNullOrBlank()) {
            fromWeb
        } else {
            normalizePastedCookie("$existing; $fromWeb")
        }
        DouyinApiClient.globalCookie = merged
        applyDerivedTokensFromCookieLine(merged)
        DouyinCookieStore.persistCurrentCookie()
        return merged
    }

    /**
     * 从剪贴板粘贴的整段 Cookie（`a=b; c=d` 或多行）**整段替换** [DouyinApiClient.globalCookie]。
     * （与 [syncFromCookieManager] 的「合并」策略不同：粘贴通常来自浏览器完整导出，应避免残留旧键。）
     *
     * @return 是否识别为有效键值对并写入。
     */
    fun applyPastedCookieHeader(raw: String): Boolean {
        val merged = normalizePastedCookie(raw)
        if (merged.isBlank()) return false
        DouyinApiClient.globalCookie = merged
        applyDerivedTokensFromCookieLine(merged)
        DouyinCookieStore.persistCurrentCookie()
        return true
    }

    /**
     * 根据一整条 Cookie 头字符串，更新 [DouyinApiClient] 上用于 query 的派生字段。
     */
    fun applyDerivedTokensFromCookieLine(cookieLine: String) {
        DouyinApiClient.msToken = parseCookieValue(cookieLine, "msToken")
        DouyinApiClient.webId = webIdFromCookie(cookieLine)
        DouyinApiClient.ttwid = parseCookieValue(cookieLine, "ttwid")
        DouyinApiClient.verifyFp = parseCookieValue(cookieLine, "s_v_web_id")
            ?: parseCookieValue(cookieLine, "verify_fp")
    }

    private fun webIdFromCookie(cookieLine: String): String? {
        return parseCookieValue(cookieLine, "webid")
            ?: parseCookieValue(cookieLine, "UIFID")
            ?: parseCookieValue(cookieLine, "uifid")
    }

    private fun normalizePastedCookie(raw: String): String {
        val lines = raw.trim().replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val map = linkedMapOf<String, String>()
        for (line in lines) {
            val segments = line.split(";")
            for (segment in segments) {
                val part = segment.trim()
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val name = part.substring(0, eq).trim()
                val value = part.substring(eq + 1).trim()
                if (name.isEmpty()) continue
                map[name] = value
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun mergeCookieHeaderChunks(chunks: List<String>): String {
        return normalizePastedCookie(chunks.joinToString("; "))
    }

    private fun parseCookieValue(cookieLine: String, name: String): String? {
        val prefix = "$name="
        var start = 0
        while (start < cookieLine.length) {
            val idx = cookieLine.indexOf(prefix, start, ignoreCase = true)
            if (idx == -1) return null
            val boundaryBefore = idx == 0 || cookieLine[idx - 1] == ' ' || cookieLine[idx - 1] == ';'
            if (!boundaryBefore) {
                start = idx + 1
                continue
            }
            val valueStart = idx + prefix.length
            val semi = cookieLine.indexOf(';', valueStart)
            val value = if (semi == -1) cookieLine.substring(valueStart) else cookieLine.substring(valueStart, semi)
            return value.trim().ifBlank { null }
        }
        return null
    }
}
