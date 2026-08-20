package com.blitz.downloader.api

import android.util.Log
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 抖音 API 客户端
 * 负责构建 HTTP 请求、添加签名、管理 Cookie
 */
object DouyinApiClient {

    private const val BASE_URL = "https://www.douyin.com"
    private const val TAG = "DouyinApiClient"

    // 存储从 WebView 或登录获取的 Cookie
    var globalCookie: String? = null
    var msToken: String? = null
    var webId: String? = null
    var ttwid: String? = null
    var verifyFp: String? = null

    /** 与 Web 列表/签名请求一致的桌面 Chrome UA（与 HeaderInterceptor 中一致）。 */
    const val webUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"

    /**
     * F2 `BaseRequestModel` 默认浏览器为 Edge 130；「喜欢」接口抓包 URL 中 browser_name=Edge 且仅含 a_bogus。
     * 签名与 HTTP User-Agent 必须一致，否则易 200 空包。
     */
    const val webUserAgentFavorite =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HeaderInterceptor())
        .addInterceptor(LoggingInterceptor())
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: DouyinApiService = retrofit.create(DouyinApiService::class.java)

    /**
     * 请求头拦截器
     * 添加必要的 Header 模拟浏览器
     */
    class HeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val requestBuilder = original.newBuilder()
                .header(
                    "User-Agent",
                    if (original.url.encodedPath.contains("/aweme/v1/web/aweme/favorite")) {
                        webUserAgentFavorite
                    } else {
                        webUserAgent
                    },
                )
                .header("Referer", "https://www.douyin.com/")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Origin", "https://www.douyin.com")

            // ArgusSecurityPlugin（抖音边缘网关，2026 起对「喜欢/收藏/收藏夹」等登录态列表接口生效）
            // 要求把 UIFID 作为 **HTTP 请求头 `uifid`** 上送——注意不是 Cookie、也不是 query `webid`。
            // 缺失时网关在业务逻辑前直接 403 `Blocked by ArgusSecurityPlugin Uifid Not Found`。
            // 值复用从 Cookie 解析出的 [webId]（UIFID/uifid），浏览器对全部 web 接口都带它，故对所有请求统一附加。
            webId?.takeIf { it.isNotBlank() }?.let {
                requestBuilder.header("uifid", it)
            }

            // ArgusSecurityPlugin 第二道校验：缺 `Signature` 头时 403 `... Signature Not Found`。
            // 实测网关此版本只校验该头**是否存在**、不校验值（`x-tt-argus` 任意/空值即放行 200），
            // 属「疑似 App 流量放宽 web 校验」的旁路；仅对受保护接口附加，避免影响 post 等未受保护接口。
            // 注意这是权宜之计：网关一旦升级到真正校验签名值即失效，届时需走 WebView 内发真实请求方案。
            val path = original.url.encodedPath
            val argusProtected = path.contains("/aweme/v1/web/aweme/favorite") ||
                path.contains("/aweme/v1/web/aweme/listcollection") ||
                path.contains("/aweme/v1/web/collects/")
            if (argusProtected) {
                requestBuilder.header("x-tt-argus", "1")
            }

            // 添加 Cookie
            if (!globalCookie.isNullOrBlank()) {
                requestBuilder.header("Cookie", globalCookie!!)
            }

            return chain.proceed(requestBuilder.build())
        }
    }

    /**
     * 日志拦截器
     */
    class LoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            Log.d(TAG, "→ ${request.method} ${request.url}")

            val response = chain.proceed(request)
            // Argus 网关拦截时正文是明文（非 JSON、不含 Cookie），安全可打印，且能第一时间看出网关又改了哪道校验
            // （Uifid / Signature Not Found）。保留此行以便未来网关升级时快速定位，不要因“功能已通”而删除。
            if (response.code == 403) {
                Log.w(TAG, "← 403 ${request.url.encodedPath} body=${response.peekBody(512).string()}")
            } else {
                Log.d(TAG, "← ${response.code} ${request.url}")
            }

            return response
        }
    }
}

