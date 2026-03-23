package com.blitz.downloader.api

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * 抖音 API 接口定义
 * 参考 f2 项目：https://github.com/Johnserf-Seed/f2
 */
interface DouyinApiService {

    /**
     * 获取用户主页视频列表
     * 对应 f2 的 fetch_user_post_videos
     */
    @GET("/aweme/v1/web/aweme/post/")
    suspend fun getUserVideos(
        @Query("sec_user_id") secUserId: String,
        @Query("max_cursor") maxCursor: Long = 0,
        @Query("locate_query") locateQuery: Boolean = false,
        @Query("show_live_replay_strategy") showLiveReplayStrategy: Int = 1,
        @Query("count") count: Int = 18,
        @Query("publish_video_strategy_type") publishVideoStrategyType: Int = 2,
        @Query("device_platform") devicePlatform: String = "webapp",
        @Query("aid") aid: String = "6383",
        @Query("channel") channel: String = "channel_pc_web",
        @Query("pc_client_type") pcClientType: Int = 1,
        @Query("version_code") versionCode: String = "190500",
        @Query("version_name") versionName: String = "19.5.0",
        @Query("cookie_enabled") cookieEnabled: String = "true",
        @Query("screen_width") screenWidth: Int = 1920,
        @Query("screen_height") screenHeight: Int = 1080,
        @Query("browser_language") browserLanguage: String = "zh-CN",
        @Query("browser_platform") browserPlatform: String = "Win32",
        @Query("browser_name") browserName: String = "Chrome",
        @Query("browser_version") browserVersion: String = "119.0.0.0",
        @Query("browser_online") browserOnline: String = "true",
        @Query("engine_name") engineName: String = "Blink",
        @Query("engine_version") engineVersion: String = "119.0.0.0",
        @Query("os_name") osName: String = "Windows",
        @Query("os_version") osVersion: String = "10",
        @Query("cpu_core_num") cpuCoreNum: Int = 8,
        @Query("device_memory") deviceMemory: Int = 8,
        @Query("platform") platform: String = "PC",
        @Query("downlink") downlink: Int = 10,
        @Query("effective_type") effectiveType: String = "4g",
        @Query("round_trip_time") roundTripTime: Int = 50,
        @Query("webid") webid: String? = null,
        @Query("msToken") msToken: String? = null,
        @Query("X-Bogus") xBogus: String? = null,
        @Query("a_bogus") aBogus: String? = null,
        @Query("_signature") signature: String? = null
    ): Response<DouyinUserVideosResponse>

    /**
     * 获取视频详情
     */
    @GET("/aweme/v1/web/aweme/detail/")
    suspend fun getVideoDetail(
        @Query("aweme_id") awemeId: String,
        @Query("aid") aid: String = "6383",
        @Query("device_platform") devicePlatform: String = "webapp",
        @Query("pc_client_type") pcClientType: Int = 1,
        @Query("version_code") versionCode: String = "190500",
        @Query("version_name") versionName: String = "19.5.0",
        @Query("msToken") msToken: String? = null,
        @Query("X-Bogus") xBogus: String? = null,
        @Query("a_bogus") aBogus: String? = null
    ): Response<DouyinVideoDetailResponse>

    /**
     * 动态 URL 请求（用于处理重定向后的真实 API）
     */
    @GET
    suspend fun dynamicGet(@Url url: String): Response<ResponseBody>

    /** F2 `listcollection`：完整 URL（含签名 query）+ JSON body */
    @POST
    @Headers("Content-Type: application/json; charset=UTF-8")
    suspend fun dynamicPost(@Url url: String, @Body body: RequestBody): Response<ResponseBody>
}

