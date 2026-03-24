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
            Log.d(TAG, "← ${response.code} ${request.url}")

            return response
        }
    }
}

