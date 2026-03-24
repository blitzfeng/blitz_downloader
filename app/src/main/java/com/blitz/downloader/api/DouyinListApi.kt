package com.blitz.downloader.api

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 列表类接口（对齐 F2：`fetch_user_post`、`fetch_user_mix`），经 [AwemeWebUrls] 签名后 [DouyinApiClient.api.dynamicGet] 拉取 JSON，
 * 并解析 [DouyinUserVideosResponse]、[cursor]/[max_cursor] 翻页。
 */
object DouyinListApi {

    private val gson = Gson()
    private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

    suspend fun fetchUserPostPage(
        secUserId: String,
        maxCursor: Long,
        count: Int = 18,
    ): Result<DouyinListPage> = withContext(Dispatchers.IO) {
        try {
            val url = AwemeWebUrls.userPostSignedUrl(
                secUserId = secUserId,
                maxCursor = maxCursor,
                webid = DouyinApiClient.webId,
                msToken = DouyinApiClient.msToken,
                userAgent = DouyinApiClient.webUserAgent,
                count = count,
            )
            parseAwemeListBody(dynamicGetBody(url))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchMixAwemePage(
        mixId: String,
        cursor: Long,
        count: Int = 18,
    ): Result<DouyinListPage> = withContext(Dispatchers.IO) {
        try {
            val url = AwemeWebUrls.mixAwemeSignedUrl(
                mixId = mixId,
                cursor = cursor,
                webid = DouyinApiClient.webId,
                msToken = DouyinApiClient.msToken,
                userAgent = DouyinApiClient.webUserAgent,
                count = count,
            )
            parseAwemeListBody(dynamicGetBody(url))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchUserLikePage(
        secUserId: String,
        maxCursor: Long,
        count: Int = 18,
    ): Result<DouyinListPage> = withContext(Dispatchers.IO) {
        try {
            val url = AwemeWebUrls.userFavoriteSignedUrl(
                secUserId = secUserId,
                maxCursor = maxCursor,
                msToken = DouyinApiClient.msToken,
                userAgent = DouyinApiClient.webUserAgentFavorite,
                count = count,
            )
            parseAwemeListBody(dynamicGetBody(url))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 当前登录账号的收藏夹目录（F2 `fetch_user_collects` → `GET .../collects/list/`）。
     */
    suspend fun fetchCollectsListPage(
        cursor: Long,
        count: Int = 20,
    ): Result<DouyinCollectsListPage> = withContext(Dispatchers.IO) {
        try {
            val url = AwemeWebUrls.collectsListSignedUrl(
                cursor = cursor,
                count = count,
                webid = DouyinApiClient.webId,
                msToken = DouyinApiClient.msToken,
                userAgent = DouyinApiClient.webUserAgent,
            )
            parseCollectsListBody(dynamicGetBody(url))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 翻页拉取全部收藏夹（用于弹窗选择），带次数上限防止异常死循环。
     */
    suspend fun fetchAllCollectsFolders(): Result<List<DouyinCollectsFolderRow>> = withContext(Dispatchers.IO) {
        val all = mutableListOf<DouyinCollectsFolderRow>()
        var cursor = 0L
        var hasMore = true
        var pageGuard = 0
        while (hasMore && pageGuard++ < 50) {
            val page = fetchCollectsListPage(cursor, 20).getOrElse { return@withContext Result.failure(it) }
            all.addAll(page.folders)
            hasMore = page.hasMore
            cursor = page.nextCursor
            if (!hasMore) break
            delay(400)
        }
        Result.success(all)
    }

    /**
     * 指定收藏夹内视频（F2 `fetch_user_collects_video`，响应与作品列表同构 `aweme_list`）。
     */
    suspend fun fetchCollectsVideoPage(
        collectsId: String,
        cursor: Long,
        count: Int = 18,
    ): Result<DouyinListPage> = withContext(Dispatchers.IO) {
        try {
            val url = AwemeWebUrls.collectsVideoSignedUrl(
                collectsId = collectsId,
                cursor = cursor,
                count = count,
                webid = DouyinApiClient.webId,
                msToken = DouyinApiClient.msToken,
                userAgent = DouyinApiClient.webUserAgent,
            )
            parseAwemeListBody(dynamicGetBody(url))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 当前登录账号的「收藏列表」视频（F2 `fetch_user_collection` / `listcollection`），仅 Cookie。
     */
    suspend fun fetchUserCollectionPage(
        cursor: Long,
        count: Int = 18,
    ): Result<DouyinListPage> = withContext(Dispatchers.IO) {
        try {
            val (url, json) = AwemeWebUrls.userCollectionSignedPostUrl(
                cursor = cursor,
                count = count,
                webid = DouyinApiClient.webId,
                msToken = DouyinApiClient.msToken,
                userAgent = DouyinApiClient.webUserAgent,
            )
            val body = json.toRequestBody(JSON_MEDIA_TYPE)
            val resp = DouyinApiClient.api.dynamicPost(url, body)
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code()}")
            }
            val raw = resp.body()?.use { it.string() }
                ?: throw IllegalStateException("empty body")
            parseAwemeListBody(raw)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun dynamicGetBody(url: String): String {
        val resp = DouyinApiClient.api.dynamicGet(url)
        val code = resp.code()
        val raw = resp.body()?.use { it.string() } ?: ""
        if (!resp.isSuccessful) {
            val hint = raw.take(300).ifBlank { "(无正文)" }
            throw IllegalStateException("HTTP $code: $hint")
        }
        if (raw.isBlank()) {
            val ct = resp.headers()["Content-Type"].orEmpty()
            val cl = resp.headers()["Content-Length"].orEmpty()
            throw IllegalStateException(
                "HTTP $code 响应体为空 (Content-Type=$ct, Content-Length=$cl)。常见原因：风控空包、Cookie 未登录或失效；请同步 Cookie 后重试。"
            )
        }
        return raw
    }

    private fun parseCollectsListBody(json: String): Result<DouyinCollectsListPage> {
        val data = try {
            gson.fromJson(json, DouyinCollectsListResponse::class.java)
        } catch (e: JsonSyntaxException) {
            return Result.failure(IllegalStateException("JSON 解析失败", e))
        }
        if (data.statusCode != 0) {
            val msg = data.statusMsg?.takeIf { it.isNotBlank() } ?: "status_code=${data.statusCode}"
            return Result.failure(IllegalStateException(msg))
        }
        val folders = data.collectsList.orEmpty().map {
            val idStr = it.collectsIdStr?.takeIf { s -> s.isNotBlank() }
                ?: it.collectsId?.toString()
                ?: ""
            DouyinCollectsFolderRow(
                id = idStr,
                name = it.collectsName?.trim().orEmpty().ifBlank { idStr },
            )
        }
        val page = DouyinCollectsListPage(
            folders = folders,
            hasMore = data.hasMorePages(),
            nextCursor = data.cursor,
            statusCode = data.statusCode,
            statusMessage = data.statusMsg,
        )
        return Result.success(page)
    }

    private fun parseAwemeListBody(json: String): Result<DouyinListPage> {
        if (json.isBlank()) {
            return Result.failure(
                IllegalStateException("响应 JSON 为空字符串（上一请求可能未返回 body，或 Gson 解析前未读到内容）"),
            )
        }
        val data = try {
            gson.fromJson(json, DouyinUserVideosResponse::class.java)
        } catch (e: JsonSyntaxException) {
            return Result.failure(IllegalStateException("JSON 解析失败", e))
        }
        if (data == null) {
            return Result.failure(
                IllegalStateException("Gson 解析结果为 null（常见于空 JSON 字符串 \"\"）"),
            )
        }
        if (data.statusCode != 0) {
            val msg = data.statusMsg?.takeIf { it.isNotBlank() } ?: "status_code=${data.statusCode}"
            return Result.failure(IllegalStateException(msg))
        }
        val list = data.awemeList.orEmpty()
        val page = DouyinListPage(
            items = list,
            hasMore = data.hasMorePages(),
            nextCursor = data.nextPageCursor(),
            statusCode = data.statusCode,
            statusMessage = data.statusMsg,
            nextMinCursor = data.minCursor,
        )
        return Result.success(page)
    }
}
