package com.blitz.downloader.api

import com.google.gson.Gson
import okhttp3.HttpUrl
import com.blitz.downloader.api.signing.DouyinWebSigner

/**
 * 构造与 [DouyinApiService.getUserVideos] / [DouyinApiService.getVideoDetail] 默认参数一致的 URL，
 * 再经 [DouyinWebSigner] 附加 **X-Bogus** 与 **a_bogus**（与 [DouyinApiClient.webUserAgent] 同源）。
 */
object AwemeWebUrls {

    private val gson = Gson()

    fun userPostSignedUrl(
        secUserId: String,
        maxCursor: Long,
        webid: String?,
        msToken: String?,
        userAgent: String,
        count: Int = 18,
    ): String {
        val q = userPostEncodedQuery(secUserId, maxCursor, webid, msToken, count)
        val signed = DouyinWebSigner.signGetEncodedQuery(q, userAgent)
        return HttpUrl.Builder()
            .scheme("https")
            .host("www.douyin.com")
            .encodedPath("/aweme/v1/web/aweme/post/")
            .encodedQuery(signed)
            .build()
            .toString()
    }

    /**
     * F2 [USER_FAVORITE_A]：`/aweme/v1/web/aweme/favorite/`（用户「喜欢」列表），参数与作品列表一致。
     */
    fun userFavoriteSignedUrl(
        secUserId: String,
        maxCursor: Long,
        webid: String?,
        msToken: String?,
        userAgent: String,
        count: Int = 18,
    ): String {
        val q = userFavoriteEncodedQuery(secUserId, maxCursor, webid, msToken, count)
        val signed = DouyinWebSigner.signGetEncodedQuery(q, userAgent)
        return HttpUrl.Builder()
            .scheme("https")
            .host("www.douyin.com")
            .encodedPath("/aweme/v1/web/aweme/favorite/")
            .encodedQuery(signed)
            .build()
            .toString()
    }

    /**
     * F2 `USER_COLLECTION`：`POST /aweme/v1/web/aweme/listcollection/`，仅依赖登录 Cookie，与主页 URL 无强绑定。
     */
    fun userCollectionSignedPostUrl(
        cursor: Long,
        count: Int,
        webid: String?,
        msToken: String?,
        userAgent: String,
    ): Pair<String, String> {
        val q = userCollectionEncodedQuery(cursor, count, webid, msToken)
        val jsonBody = userCollectionJsonBody(cursor, count, webid, msToken)
        val signed = DouyinWebSigner.signPostJsonEncodedQuery(q, jsonBody, userAgent)
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("www.douyin.com")
            .encodedPath("/aweme/v1/web/aweme/listcollection/")
            .encodedQuery(signed)
            .build()
            .toString()
        return url to jsonBody
    }

    /**
     * F2 `USER_COLLECTS`：`GET .../collects/list/`（收藏夹目录）。
     */
    fun collectsListSignedUrl(
        cursor: Long,
        count: Int,
        webid: String?,
        msToken: String?,
        userAgent: String,
    ): String {
        val q = collectsListEncodedQuery(cursor, count, webid, msToken)
        val signed = DouyinWebSigner.signGetEncodedQuery(q, userAgent)
        return HttpUrl.Builder()
            .scheme("https")
            .host("www.douyin.com")
            .encodedPath("/aweme/v1/web/collects/list/")
            .encodedQuery(signed)
            .build()
            .toString()
    }

    /**
     * F2 `USER_COLLECTS_VIDEO`：`GET .../collects/video/list/`（某收藏夹内作品，与 [UserPostFilter] 同构 `aweme_list`）。
     */
    fun collectsVideoSignedUrl(
        collectsId: String,
        cursor: Long,
        count: Int,
        webid: String?,
        msToken: String?,
        userAgent: String,
    ): String {
        val q = collectsVideoEncodedQuery(collectsId, cursor, count, webid, msToken)
        val signed = DouyinWebSigner.signGetEncodedQuery(q, userAgent)
        return HttpUrl.Builder()
            .scheme("https")
            .host("www.douyin.com")
            .encodedPath("/aweme/v1/web/collects/video/list/")
            .encodedQuery(signed)
            .build()
            .toString()
    }

    /**
     * F2 `MIX_AWEME`：`https://www.douyin.com/aweme/v1/web/mix/aweme/`（见 f2 `DouyinAPIEndpoints.MIX_AWEME`）。
     */
    fun mixAwemeSignedUrl(
        mixId: String,
        cursor: Long,
        webid: String?,
        msToken: String?,
        userAgent: String,
        count: Int = 18,
    ): String {
        val q = mixAwemeEncodedQuery(mixId, cursor, webid, msToken, count)
        val signed = DouyinWebSigner.signGetEncodedQuery(q, userAgent)
        return HttpUrl.Builder()
            .scheme("https")
            .host("www.douyin.com")
            .encodedPath("/aweme/v1/web/mix/aweme/")
            .encodedQuery(signed)
            .build()
            .toString()
    }

    fun videoDetailSignedUrl(
        awemeId: String,
        webid: String?,
        msToken: String?,
        userAgent: String,
    ): String {
        val q = videoDetailEncodedQuery(awemeId, webid, msToken)
        val signed = DouyinWebSigner.signGetEncodedQuery(q, userAgent)
        return HttpUrl.Builder()
            .scheme("https")
            .host("www.douyin.com")
            .encodedPath("/aweme/v1/web/aweme/detail/")
            .encodedQuery(signed)
            .build()
            .toString()
    }

    fun userPostEncodedQuery(
        secUserId: String,
        maxCursor: Long,
        webid: String?,
        msToken: String?,
        count: Int = 18,
    ): String {
        val b = HttpUrl.Builder()
            .scheme("https")
            .host("www.douyin.com")
            .encodedPath("/aweme/v1/web/aweme/post/")
            .addQueryParameter("sec_user_id", secUserId)
            .addQueryParameter("max_cursor", maxCursor.toString())
            .addQueryParameter("locate_query", "false")
            .addQueryParameter("show_live_replay_strategy", "1")
            .addQueryParameter("count", count.coerceIn(1, 50).toString())
            .addQueryParameter("publish_video_strategy_type", "2")
            .addQueryParameter("device_platform", "webapp")
            .addQueryParameter("aid", "6383")
            .addQueryParameter("channel", "channel_pc_web")
            .addQueryParameter("pc_client_type", "1")
            .addQueryParameter("version_code", "190500")
            .addQueryParameter("version_name", "19.5.0")
            .addQueryParameter("cookie_enabled", "true")
            .addQueryParameter("screen_width", "1920")
            .addQueryParameter("screen_height", "1080")
            .addQueryParameter("browser_language", "zh-CN")
            .addQueryParameter("browser_platform", "Win32")
            .addQueryParameter("browser_name", "Chrome")
            .addQueryParameter("browser_version", "119.0.0.0")
            .addQueryParameter("browser_online", "true")
            .addQueryParameter("engine_name", "Blink")
            .addQueryParameter("engine_version", "119.0.0.0")
            .addQueryParameter("os_name", "Windows")
            .addQueryParameter("os_version", "10")
            .addQueryParameter("cpu_core_num", "8")
            .addQueryParameter("device_memory", "8")
            .addQueryParameter("platform", "PC")
            .addQueryParameter("downlink", "10")
            .addQueryParameter("effective_type", "4g")
            .addQueryParameter("round_trip_time", "50")
        if (!webid.isNullOrBlank()) b.addQueryParameter("webid", webid)
        if (!msToken.isNullOrBlank()) b.addQueryParameter("msToken", msToken)
        return b.build().encodedQuery ?: ""
    }

    fun userFavoriteEncodedQuery(
        secUserId: String,
        maxCursor: Long,
        webid: String?,
        msToken: String?,
        count: Int = 18,
    ): String {
        val b = HttpUrl.Builder()
            .scheme("https")
            .host("www.douyin.com")
            .encodedPath("/aweme/v1/web/aweme/favorite/")
            .addQueryParameter("sec_user_id", secUserId)
            .addQueryParameter("max_cursor", maxCursor.toString())
            .addQueryParameter("locate_query", "false")
            .addQueryParameter("show_live_replay_strategy", "1")
            .addQueryParameter("count", count.coerceIn(1, 50).toString())
            .addQueryParameter("publish_video_strategy_type", "2")
            .addQueryParameter("device_platform", "webapp")
            .addQueryParameter("aid", "6383")
            .addQueryParameter("channel", "channel_pc_web")
            .addQueryParameter("pc_client_type", "1")
            .addQueryParameter("version_code", "190500")
            .addQueryParameter("version_name", "19.5.0")
            .addQueryParameter("cookie_enabled", "true")
            .addQueryParameter("screen_width", "1920")
            .addQueryParameter("screen_height", "1080")
            .addQueryParameter("browser_language", "zh-CN")
            .addQueryParameter("browser_platform", "Win32")
            .addQueryParameter("browser_name", "Chrome")
            .addQueryParameter("browser_version", "119.0.0.0")
            .addQueryParameter("browser_online", "true")
            .addQueryParameter("engine_name", "Blink")
            .addQueryParameter("engine_version", "119.0.0.0")
            .addQueryParameter("os_name", "Windows")
            .addQueryParameter("os_version", "10")
            .addQueryParameter("cpu_core_num", "8")
            .addQueryParameter("device_memory", "8")
            .addQueryParameter("platform", "PC")
            .addQueryParameter("downlink", "10")
            .addQueryParameter("effective_type", "4g")
            .addQueryParameter("round_trip_time", "50")
        if (!webid.isNullOrBlank()) b.addQueryParameter("webid", webid)
        if (!msToken.isNullOrBlank()) b.addQueryParameter("msToken", msToken)
        return b.build().encodedQuery ?: ""
    }

    fun userCollectionEncodedQuery(
        cursor: Long,
        count: Int,
        webid: String?,
        msToken: String?,
    ): String {
        val b = HttpUrl.Builder()
            .scheme("https")
            .host("www.douyin.com")
            .encodedPath("/aweme/v1/web/aweme/listcollection/")
            .addQueryParameter("cursor", cursor.toString())
            .addQueryParameter("count", count.coerceIn(1, 50).toString())
            .addQueryParameter("publish_video_strategy_type", "2")
            .addQueryParameter("device_platform", "webapp")
            .addQueryParameter("aid", "6383")
            .addQueryParameter("channel", "channel_pc_web")
            .addQueryParameter("pc_client_type", "1")
            .addQueryParameter("version_code", "190500")
            .addQueryParameter("version_name", "19.5.0")
            .addQueryParameter("cookie_enabled", "true")
            .addQueryParameter("screen_width", "1920")
            .addQueryParameter("screen_height", "1080")
            .addQueryParameter("browser_language", "zh-CN")
            .addQueryParameter("browser_platform", "Win32")
            .addQueryParameter("browser_name", "Chrome")
            .addQueryParameter("browser_version", "119.0.0.0")
            .addQueryParameter("browser_online", "true")
            .addQueryParameter("engine_name", "Blink")
            .addQueryParameter("engine_version", "119.0.0.0")
            .addQueryParameter("os_name", "Windows")
            .addQueryParameter("os_version", "10")
            .addQueryParameter("cpu_core_num", "8")
            .addQueryParameter("device_memory", "8")
            .addQueryParameter("platform", "PC")
            .addQueryParameter("downlink", "10")
            .addQueryParameter("effective_type", "4g")
            .addQueryParameter("round_trip_time", "50")
        if (!webid.isNullOrBlank()) b.addQueryParameter("webid", webid)
        if (!msToken.isNullOrBlank()) b.addQueryParameter("msToken", msToken)
        return b.build().encodedQuery ?: ""
    }

    /** 与 [userCollectionEncodedQuery] 字段一致，供 POST body（F2 `UserCollection` + Base）。 */
    fun userCollectionJsonBody(
        cursor: Long,
        count: Int,
        webid: String?,
        msToken: String?,
    ): String {
        val m = linkedMapOf<String, Any>(
            "cursor" to cursor,
            "count" to count.coerceIn(1, 50),
            "publish_video_strategy_type" to 2,
            "device_platform" to "webapp",
            "aid" to "6383",
            "channel" to "channel_pc_web",
            "pc_client_type" to 1,
            "version_code" to "190500",
            "version_name" to "19.5.0",
            "cookie_enabled" to "true",
            "screen_width" to 1920,
            "screen_height" to 1080,
            "browser_language" to "zh-CN",
            "browser_platform" to "Win32",
            "browser_name" to "Chrome",
            "browser_version" to "119.0.0.0",
            "browser_online" to "true",
            "engine_name" to "Blink",
            "engine_version" to "119.0.0.0",
            "os_name" to "Windows",
            "os_version" to "10",
            "cpu_core_num" to 8,
            "device_memory" to 8,
            "platform" to "PC",
            "downlink" to 10,
            "effective_type" to "4g",
            "round_trip_time" to 50,
        )
        if (!webid.isNullOrBlank()) m["webid"] = webid
        if (!msToken.isNullOrBlank()) m["msToken"] = msToken
        return gson.toJson(m)
    }

    fun collectsListEncodedQuery(
        cursor: Long,
        count: Int,
        webid: String?,
        msToken: String?,
    ): String {
        val b = HttpUrl.Builder()
            .scheme("https")
            .host("www.douyin.com")
            .encodedPath("/aweme/v1/web/collects/list/")
            .addQueryParameter("cursor", cursor.toString())
            .addQueryParameter("count", count.coerceIn(1, 50).toString())
            .addQueryParameter("publish_video_strategy_type", "2")
            .addQueryParameter("device_platform", "webapp")
            .addQueryParameter("aid", "6383")
            .addQueryParameter("channel", "channel_pc_web")
            .addQueryParameter("pc_client_type", "1")
            .addQueryParameter("version_code", "190500")
            .addQueryParameter("version_name", "19.5.0")
            .addQueryParameter("cookie_enabled", "true")
            .addQueryParameter("screen_width", "1920")
            .addQueryParameter("screen_height", "1080")
            .addQueryParameter("browser_language", "zh-CN")
            .addQueryParameter("browser_platform", "Win32")
            .addQueryParameter("browser_name", "Chrome")
            .addQueryParameter("browser_version", "119.0.0.0")
            .addQueryParameter("browser_online", "true")
            .addQueryParameter("engine_name", "Blink")
            .addQueryParameter("engine_version", "119.0.0.0")
            .addQueryParameter("os_name", "Windows")
            .addQueryParameter("os_version", "10")
            .addQueryParameter("cpu_core_num", "8")
            .addQueryParameter("device_memory", "8")
            .addQueryParameter("platform", "PC")
            .addQueryParameter("downlink", "10")
            .addQueryParameter("effective_type", "4g")
            .addQueryParameter("round_trip_time", "50")
        if (!webid.isNullOrBlank()) b.addQueryParameter("webid", webid)
        if (!msToken.isNullOrBlank()) b.addQueryParameter("msToken", msToken)
        return b.build().encodedQuery ?: ""
    }

    fun collectsVideoEncodedQuery(
        collectsId: String,
        cursor: Long,
        count: Int,
        webid: String?,
        msToken: String?,
    ): String {
        val b = HttpUrl.Builder()
            .scheme("https")
            .host("www.douyin.com")
            .encodedPath("/aweme/v1/web/collects/video/list/")
            .addQueryParameter("collects_id", collectsId)
            .addQueryParameter("cursor", cursor.toString())
            .addQueryParameter("count", count.coerceIn(1, 50).toString())
            .addQueryParameter("publish_video_strategy_type", "2")
            .addQueryParameter("device_platform", "webapp")
            .addQueryParameter("aid", "6383")
            .addQueryParameter("channel", "channel_pc_web")
            .addQueryParameter("pc_client_type", "1")
            .addQueryParameter("version_code", "190500")
            .addQueryParameter("version_name", "19.5.0")
            .addQueryParameter("cookie_enabled", "true")
            .addQueryParameter("screen_width", "1920")
            .addQueryParameter("screen_height", "1080")
            .addQueryParameter("browser_language", "zh-CN")
            .addQueryParameter("browser_platform", "Win32")
            .addQueryParameter("browser_name", "Chrome")
            .addQueryParameter("browser_version", "119.0.0.0")
            .addQueryParameter("browser_online", "true")
            .addQueryParameter("engine_name", "Blink")
            .addQueryParameter("engine_version", "119.0.0.0")
            .addQueryParameter("os_name", "Windows")
            .addQueryParameter("os_version", "10")
            .addQueryParameter("cpu_core_num", "8")
            .addQueryParameter("device_memory", "8")
            .addQueryParameter("platform", "PC")
            .addQueryParameter("downlink", "10")
            .addQueryParameter("effective_type", "4g")
            .addQueryParameter("round_trip_time", "50")
        if (!webid.isNullOrBlank()) b.addQueryParameter("webid", webid)
        if (!msToken.isNullOrBlank()) b.addQueryParameter("msToken", msToken)
        return b.build().encodedQuery ?: ""
    }

    fun mixAwemeEncodedQuery(
        mixId: String,
        cursor: Long,
        webid: String?,
        msToken: String?,
        count: Int = 18,
    ): String {
        val b = HttpUrl.Builder()
            .scheme("https")
            .host("www.douyin.com")
            .encodedPath("/aweme/v1/web/mix/aweme/")
            .addQueryParameter("mix_id", mixId)
            .addQueryParameter("cursor", cursor.toString())
            .addQueryParameter("count", count.coerceIn(1, 50).toString())
            .addQueryParameter("publish_video_strategy_type", "2")
            .addQueryParameter("device_platform", "webapp")
            .addQueryParameter("aid", "6383")
            .addQueryParameter("channel", "channel_pc_web")
            .addQueryParameter("pc_client_type", "1")
            .addQueryParameter("version_code", "190500")
            .addQueryParameter("version_name", "19.5.0")
            .addQueryParameter("cookie_enabled", "true")
            .addQueryParameter("screen_width", "1920")
            .addQueryParameter("screen_height", "1080")
            .addQueryParameter("browser_language", "zh-CN")
            .addQueryParameter("browser_platform", "Win32")
            .addQueryParameter("browser_name", "Chrome")
            .addQueryParameter("browser_version", "119.0.0.0")
            .addQueryParameter("browser_online", "true")
            .addQueryParameter("engine_name", "Blink")
            .addQueryParameter("engine_version", "119.0.0.0")
            .addQueryParameter("os_name", "Windows")
            .addQueryParameter("os_version", "10")
            .addQueryParameter("cpu_core_num", "8")
            .addQueryParameter("device_memory", "8")
            .addQueryParameter("platform", "PC")
            .addQueryParameter("downlink", "10")
            .addQueryParameter("effective_type", "4g")
            .addQueryParameter("round_trip_time", "50")
        if (!webid.isNullOrBlank()) b.addQueryParameter("webid", webid)
        if (!msToken.isNullOrBlank()) b.addQueryParameter("msToken", msToken)
        return b.build().encodedQuery ?: ""
    }

    fun videoDetailEncodedQuery(
        awemeId: String,
        webid: String?,
        msToken: String?,
    ): String {
        val b = HttpUrl.Builder()
            .scheme("https")
            .host("www.douyin.com")
            .encodedPath("/aweme/v1/web/aweme/detail/")
            .addQueryParameter("aweme_id", awemeId)
            .addQueryParameter("aid", "6383")
            .addQueryParameter("device_platform", "webapp")
            .addQueryParameter("pc_client_type", "1")
            .addQueryParameter("version_code", "190500")
            .addQueryParameter("version_name", "19.5.0")
        if (!webid.isNullOrBlank()) b.addQueryParameter("webid", webid)
        if (!msToken.isNullOrBlank()) b.addQueryParameter("msToken", msToken)
        return b.build().encodedQuery ?: ""
    }
}
