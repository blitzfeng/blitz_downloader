package com.blitz.downloader.config

/**
 * 列表批量下载的范围与 Cookie 策略（对齐 F2 中按登录态区分接口能力的方式）。
 *
 * ### 当前主场景：需登录（喜欢 / 收藏等）
 * - 目标：在 [com.blitz.downloader.ui.ListDownloadFragment] 的 WebView 内**先登录抖音**，再打开「喜欢」「收藏」等个人列表页；用 [android.webkit.CookieManager] 同步完整登录 Cookie 到
 *   [com.blitz.downloader.api.DouyinApiClient.globalCookie] 后调列表 API。
 *
 * ### 公开页（保留，勿删相关实现）
 * - [SUPPORTS_PUBLIC_GUEST_LIST_BATCH] 为 true 表示：**公开主页 / 公开合集** 等访客可访问列表仍保留为合法目标，与登录场景共用同一套解析与 HTTP 层；后续若要单独优化公开路径，可据此分支。
 *
 * ### Cookie
 * - 主路径仍为 WebView → CookieManager → `globalCookie`；登录场景必须包含 `sessionid` 等登录态键（以实际 Cookie 为准）。
 * - 403 / 419 / 空包时：在 WebView 内重新登录或刷新页面后再同步 Cookie。
 */
object BatchListDownloadScope {

    /** 批量列表的**主**使用方式：拉取需登录才能看的列表（喜欢、收藏等）。 */
    const val PRIMARY_TARGET_IS_LOGGED_IN_LISTS: Boolean = true

    /** 是否支持依赖完整登录 Cookie 的列表批量（喜欢、收藏等）。与主场景一致，应为 true。 */
    const val SUPPORTS_LOGIN_ONLY_LIST_BATCH: Boolean = true

    /**
     * 是否保留并支持**访客/公开**列表（公开主页作品、公开合集等）。
     * 与登录场景并行保留，便于以后扩展；不要删除依赖此能力的解析或接口封装。
     */
    const val SUPPORTS_PUBLIC_GUEST_LIST_BATCH: Boolean = true

    /** 列表请求 Cookie 的主要来源。 */
    val primaryCookieSource: BatchCookieSource = BatchCookieSource.WEBVIEW_COOKIE_MANAGER

    enum class BatchCookieSource {
        /** WebView 访问抖音域后读取 CookieManager，同步到 API 客户端（主路径）。 */
        WEBVIEW_COOKIE_MANAGER,

        /** 用户从剪贴板粘贴整段 Cookie（调试或与 Web 合并补充键）。 */
        MANUAL_COOKIE_PASTE,
    }
}
