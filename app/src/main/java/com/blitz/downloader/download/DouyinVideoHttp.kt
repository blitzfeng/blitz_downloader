package com.blitz.downloader.download

import com.blitz.downloader.api.DouyinApiClient
import java.net.HttpURLConnection

/**
 * 抖音视频文件 CDN（如 `*.bytecdn.cn`、`*.snssdk.com`）常校验 **Referer / Cookie**，
 * 裸 `HttpURLConnection` 仅拉流易被 **403**。与列表 API 使用的 UA/Cookie 环境对齐。
 */
object DouyinVideoHttp {

    fun applyCdnHeaders(conn: HttpURLConnection) {
        conn.setRequestProperty("User-Agent", DouyinApiClient.webUserAgent)
        conn.setRequestProperty("Referer", "https://www.douyin.com/")
        conn.setRequestProperty("Origin", "https://www.douyin.com")
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        DouyinApiClient.globalCookie?.takeIf { it.isNotBlank() }?.let { cookie ->
            conn.setRequestProperty("Cookie", cookie)
        }
    }
}
