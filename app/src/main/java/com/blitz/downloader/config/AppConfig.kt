package com.blitz.downloader.config

/**
 * 全局应用配置常量，集中管理与账号/平台强绑定的固定值。
 */
object AppConfig {

    /**
     * 本 App 所有者的抖音 `sec_user_id`，从主页 URL 中提取。
     *
     * 用途：
     * - 数据库记录中，`downloadType` 为 `like`/`collect`/`collects` 时，
     *   `sourceOwnerSecUserId` 填入此值，表示「下载来自我的账户列表」。
     * - 管理页可据此过滤「来自我的账户 vs 来自他人帖子页」。
     *
     * 来源：[com.blitz.downloader.activity.DouyinWebBrowserActivity.DOUYIN_DEFAULT_HOME_URL]
     * `https://www.douyin.com/user/<MY_SEC_USER_ID>?from_tab_name=main`
     */
    const val MY_SEC_USER_ID =
        "MS4wLjABAAAA7ZinArXxNJlWd2iiRKUI3ruz4TwjqKN5F7iqF5nGKIAgCTDtscTfMCQMor1Fn9vr"
}
