package com.blitz.downloader.api

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 抖音解析器
 * 封装 API 调用逻辑，对外提供简洁接口
 */
class DouyinParser {

    companion object {
        private const val TAG = "DouyinParser"
        private val gson = Gson()
    }

    /**
     * 从分享链接提取 sec_user_id（含 v.douyin.com 短链展开与用户页路径）。
     */
    suspend fun extractSecUserId(shareUrl: String): String? {
        return try {
            DouyinUrlParser.parse(shareUrl).secUserId
        } catch (e: Exception) {
            Log.e(TAG, "提取 sec_user_id 失败", e)
            null
        }
    }

    /**
     * 获取用户的所有视频（支持分页）
     * @param secUserId 用户的 sec_uid
     * @param maxCount 最多获取多少个视频（0 表示全部）
     */
    suspend fun fetchUserVideos(
        secUserId: String,
        maxCount: Int = 0
    ): List<AwemeItem> = withContext(Dispatchers.IO) {
        val allVideos = mutableListOf<AwemeItem>()
        var cursor = 0L
        var hasMore = true

        try {
            while (hasMore && (maxCount == 0 || allVideos.size < maxCount)) {
                Log.d(TAG, "正在获取第 ${allVideos.size} 个视频，cursor=$cursor")

                val url = AwemeWebUrls.userPostSignedUrl(
                    secUserId = secUserId,
                    maxCursor = cursor,
                    webid = DouyinApiClient.webId,
                    msToken = DouyinApiClient.msToken,
                    userAgent = DouyinApiClient.webUserAgent,
                )
                val response = DouyinApiClient.api.dynamicGet(url)

                if (response.isSuccessful) {
                    val raw = response.body()?.string() ?: break
                    val body = gson.fromJson(raw, DouyinUserVideosResponse::class.java)
                    if (body?.statusCode == 0) {
                        val videos = body.awemeList ?: emptyList()
                        allVideos.addAll(videos)
                        hasMore = body.hasMorePages()
                        cursor = body.maxCursor
                        Log.d(TAG, "成功获取 ${videos.size} 个视频，总计 ${allVideos.size}")
                    } else {
                        Log.e(TAG, "API 返回错误状态码: ${body?.statusCode}")
                        break
                    }
                } else {
                    Log.e(TAG, "请求失败: ${response.code()} ${response.message()}")
                    break
                }

                // 防止请求过快被限流
                kotlinx.coroutines.delay(1000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取视频列表失败", e)
        }

        allVideos.take(if (maxCount > 0) maxCount else allVideos.size)
    }

    /**
     * 获取单个视频详情
     */
    suspend fun fetchVideoDetail(awemeId: String): AwemeItem? = withContext(Dispatchers.IO) {
        try {
            val url = AwemeWebUrls.videoDetailSignedUrl(
                awemeId = awemeId,
                webid = DouyinApiClient.webId,
                msToken = DouyinApiClient.msToken,
                userAgent = DouyinApiClient.webUserAgent,
            )
            val response = DouyinApiClient.api.dynamicGet(url)

            if (response.isSuccessful) {
                val raw = response.body()?.string() ?: return@withContext null
                val body = gson.fromJson(raw, DouyinVideoDetailResponse::class.java)
                if (body?.statusCode == 0) {
                    return@withContext body.awemeDetail
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取视频详情失败", e)
        }
        null
    }

    /**
     * 提取视频的真实下载地址
     */
    fun extractDownloadUrl(aweme: AwemeItem): String? {
        return aweme.video?.playAddr?.urlList?.firstOrNull()
    }

    /**
     * 提取视频封面
     */
    fun extractCoverUrl(aweme: AwemeItem): String? {
        return aweme.video?.cover?.urlList?.firstOrNull()
    }
}

