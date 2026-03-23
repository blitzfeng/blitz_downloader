package com.blitz.downloader.util

import com.blitz.downloader.api.DouyinApiClient

/**
 * **Token / 票据来源设计**（仅客户端：WebView 预热 + 剪贴板粘贴，无服务端协同）。
 *
 * 与 F2 文档一致，抖音 Web 接口除签名（X-Bogus / a_bogus 等）外，常见依赖两类输入：
 *
 * 1. **完整 Cookie 行** → 经 [DouyinApiClient] 作为 `Cookie` 请求头原样发送（含登录态 `sessionid`、访客 `ttwid`、`msToken` 等）。
 * 2. **从 Cookie 解析出的少数键** → 同步到 Retrofit `@Query`，与桌面站行为对齐（键名随抖音版本可能变化，以下为本项目当前解析策略）。
 *
 * | 用途 | 典型 Cookie 键名 | [DouyinApiClient] 字段 | 备注 |
 * |------|------------------|------------------------|------|
 * | 列表/详情常见 query | `msToken` | [DouyinApiClient.msToken] | 与 UA、签名同源刷新 |
 * | `webid` query | `webid` / `UIFID` / `uifid` | [DouyinApiClient.webId] | 取首个非空 |
 * | 访客/会话 | `ttwid` | [DouyinApiClient.ttwid] | 主要仍在 Cookie 头；字段便于排查与未来扩 query |
 * | 指纹类（部分接口） | `s_v_web_id` / `verify_fp` | [DouyinApiClient.verifyFp] | 若仅出现在 Cookie，先解析备用 |
 * | 登录态 | `sessionid` / `sessionid_ss` 等 | （不单独存） | 留在 [DouyinApiClient.globalCookie] 整行中 |
 *
 * **来源路径**
 * - **主路径**：列表页 [android.webkit.WebView] 打开 `douyin.com`，[CookieManager] 写入后 [DouyinCookieSync.syncFromCookieManager]。
 * - **备用路径**：浏览器导出或抓包得到的 `Cookie` 字符串 → [DouyinCookieSync.applyPastedCookieHeader]（**整段替换**存储，避免旧键残留）。
 * - **从 Web 同步**时：若已有 globalCookie，会与 CookieManager 读到的键 **合并**，同名键以 Web 为准，便于先粘贴部分键再登录补全。
 *
 * 失效时（403 / 419 / 空包）：在 WebView 内刷新或重新登录后再点「同步 Cookie」。
 */
object DouyinTokenBootstrap {

    /** 自 [DouyinApiClient.globalCookie] 刷新各派生 query 字段（例如粘贴后未走 WebView 时也可以调用）。 */
    fun refreshDerivedFromStoredCookie() {
        val line = DouyinApiClient.globalCookie ?: return
        if (line.isBlank()) return
        DouyinCookieSync.applyDerivedTokensFromCookieLine(line)
    }
}
