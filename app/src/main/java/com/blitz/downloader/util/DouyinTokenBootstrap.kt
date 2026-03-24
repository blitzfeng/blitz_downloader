package com.blitz.downloader.util

import com.blitz.downloader.api.DouyinApiClient

/**
 * **Token / 票据来源设计**（仅客户端：WebView 预热 + 剪贴板粘贴，无服务端协同）。
 *
 * ### 与 F2（`f2/apps/douyin/utils.py`）对照
 *
 * F2 中 [TokenManager] 说明：**msToken / ttwid / webid 并非只能从浏览器 Cookie 字符串里抄**，还可通过 **HTTP 请求 + 响应** 获取（配置见 F2 的 `douyin` 配置段 `msToken`、`ttwid`、`webid`）：
 *
 * - **msToken**
 *   - `gen_real_msToken`：向配置的 `url` **POST** JSON（`magic`、`version`、`dataType`、`strData`、`ulr`、`tspFromClient` 等），从 **响应 Set-Cookie** 读取 `msToken`，并校验长度约 164 或 184。
 *   - `gen_false_msToken`：**本地随机**生成长度约 182 的字符串 + `"=="`（占位/降级，非服务端签发）。
 * - **ttwid**：`gen_ttwid`：向配置的 `url` **POST** 固定 body，从 **响应 Cookie** 取 `ttwid`。
 * - **webid**：`gen_webid`：向配置的 `url` **POST** JSON（`app_id`、`referer`、`url`、`user_agent` 等），从 **响应 JSON** 字段 `web_id` 读取（不是 Cookie）。
 * - **verify_fp / s_v_web_id**：`VerifyFpManager` — **纯本地算法**生成（与 Cookie 无关），见 [DouyinVerifyFpGenerator]。
 *
 * ### 本 App 策略
 *
 * 1. **完整 Cookie 行** → [DouyinApiClient.globalCookie] 原样作为 `Cookie` 头（含浏览器已下发的 `msToken`、`ttwid`、登录态等）。
 * 2. **从 Cookie 解析 query 辅助字段** → [DouyinCookieSync.applyDerivedTokensFromCookieLine]（键名可能随抖音变更）。
 * 3. **未实现** F2 那套「独立 POST 换 msToken/ttwid/webid」：需维护 endpoint 与 body、且与反爬策略强相关；当前以 **WebView 与真实站点一致** 为主路径。
 * 4. 若后续接口需要 **verify_fp** 且 Cookie 中无，可对齐 F2 使用 [DouyinVerifyFpGenerator.generate]。
 *
 * | 票据 | F2 典型获取方式 | 本项目中 |
 * |------|-----------------|----------|
 * | msToken | POST 换 Cookie / 假随机 | Cookie 解析；未接独立 POST |
 * | ttwid | POST 换 Cookie | Cookie 解析 |
 * | webid | POST JSON `web_id` | Cookie：`webid`/`UIFID`/`uifid` |
 * | verify_fp | 本地算法 | [DouyinVerifyFpGenerator] 可选 |
 *
 * ### 仅「本地解析 Cookie 字符串」是否可行
 *
 * **可行**，且与抖音 **PC Web** 常见导出形态一致：
 * - **鉴权与访客标识**主要依赖 **`Cookie` 请求头整段**（`sessionid`、`ttwid`、`odin_tt`、`passport_*`、`s_v_web_id` 等），[DouyinApiClient] 会原样带上，**不依赖**先把每个键拆进 query。
 * - **派生 query**（`msToken`、`webid` 等）是从同名或等价键（如 `UIFID`）解析的**补充**；若某键在 Cookie 里**不存在**（例如当前 PC 导出里常**没有** `msToken=`），则对应字段为 null，一般由 Retrofit **省略**该 query 参数。
 * - 你提供的样例中含 `UIFID`、`UIFID_TEMP`、`s_v_web_id`、`ttwid`、`sessionid` 等：当前解析逻辑可正确识别 **`UIFID`→webId、`s_v_web_id`→verifyFp、`ttwid`→ttwid**；**无 `msToken` 键时 [DouyinApiClient.msToken] 为 null 属正常**，若某接口仍要求 query `msToken`，需另从带该 Cookie 的环境同步，或再评估 F2 式独立请求（见上文）。
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
