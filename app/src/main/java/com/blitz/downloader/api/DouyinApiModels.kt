package com.blitz.downloader.api

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/**
 * 抖音 API 数据模型
 * 参考 f2 返回的 JSON 结构
 */

// ========== 用户视频列表 / 合集列表（F2：USER_POST、MIX_AWEME 等同构 aweme_list）==========
data class DouyinUserVideosResponse(
    @SerializedName("status_code") val statusCode: Int,
    @SerializedName("status_msg") val statusMsg: String? = null,
    @SerializedName("aweme_list") val awemeList: List<AwemeItem>?,
    @SerializedName("has_more") val hasMore: Int,
    /** 用户发布列表：下一页游标（与请求参数 max_cursor 对应）。 */
    @SerializedName("max_cursor") val maxCursor: Long = 0,
    @SerializedName("min_cursor") val minCursor: Long? = null,
    /** 合集等接口可能仅返回 cursor 作为下一页游标。 */
    @SerializedName("cursor") val cursor: Long? = null,
)

/**
 * 解析下一页请求游标：合集优先 [DouyinUserVideosResponse.cursor]，否则 [DouyinUserVideosResponse.maxCursor]。
 */
fun DouyinUserVideosResponse.nextPageCursor(): Long =
    cursor?.takeIf { it != 0L } ?: maxCursor

/** 是否还有更多（抖音常见为 0/1）。 */
fun DouyinUserVideosResponse.hasMorePages(): Boolean = hasMore == 1

/**
 * 列表接口一页结果（供 UI / 批量协调器使用）。
 */
data class DouyinListPage(
    val items: List<AwemeItem>,
    val hasMore: Boolean,
    val nextCursor: Long,
    val statusCode: Int,
    val statusMessage: String? = null,
)

data class AwemeItem(
    @SerializedName("aweme_id") val awemeId: String = "",
    @SerializedName("desc") val desc: String?, // 视频描述
    @SerializedName("create_time") val createTime: Long,
    @SerializedName("author") val author: Author?,
    @SerializedName("video") val video: Video?,
    @SerializedName("statistics") val statistics: Statistics?,
    @SerializedName("share_url") val shareUrl: String?
)

data class Author(
    @SerializedName("uid") val uid: String,
    @SerializedName("sec_uid") val secUid: String,
    @SerializedName("nickname") val nickname: String,
    @SerializedName("avatar_thumb") val avatarThumb: ImageUrl?
)

data class Video(
    @SerializedName("play_addr") val playAddr: PlayAddr?,
    @SerializedName("cover") val cover: ImageUrl?,
    @SerializedName("dynamic_cover") val dynamicCover: ImageUrl?,
    @SerializedName("duration") val duration: Int, // 毫秒
    @SerializedName("ratio") val ratio: String?,
    @SerializedName("width") val width: Int,
    @SerializedName("height") val height: Int
)

data class PlayAddr(
    @SerializedName("uri") val uri: String?,
    @SerializedName("url_list") val urlList: List<String>?, // 视频真实下载地址
    @SerializedName("data_size") val dataSize: Long?,
    @SerializedName("url_key") val urlKey: String?
)

data class ImageUrl(
    @SerializedName("url_list") val urlList: List<String>?
)

data class Statistics(
    @SerializedName("aweme_id") val awemeId: String,
    @SerializedName("comment_count") val commentCount: Int,
    @SerializedName("digg_count") val diggCount: Int, // 点赞数
    @SerializedName("share_count") val shareCount: Int,
    @SerializedName("play_count") val playCount: Int
)

// ========== 收藏夹列表（F2：`USER_COLLECTS` → `collects_list`，结构见 activity/data.json）==========
data class DouyinCollectsListResponse(
    @SerializedName("status_code") val statusCode: Int,
    @SerializedName("status_msg") val statusMsg: String? = null,
    @SerializedName("collects_list") val collectsList: List<DouyinCollectsListItem>?,
    /** 实际响应为 boolean（如 false），与部分列表接口的 0/1 不同 */
    @SerializedName("has_more") val hasMore: Boolean? = null,
    @SerializedName("cursor") val cursor: Long = 0,
    @SerializedName("total_number") val totalNumber: Int? = null,
    @SerializedName("extra") val extra: DouyinCollectsListExtra? = null,
    @SerializedName("log_pb") val logPb: DouyinCollectsLogPb? = null,
)

data class DouyinCollectsListExtra(
    @SerializedName("fatal_item_ids") val fatalItemIds: List<String>? = null,
    @SerializedName("logid") val logid: String? = null,
    @SerializedName("now") val now: Long? = null,
)

data class DouyinCollectsLogPb(
    @SerializedName("impr_id") val imprId: String? = null,
)

data class DouyinCollectsListItem(
    @SerializedName("app_id") val appId: Int? = null,
    @SerializedName("collects_cover") val collectsCover: CollectsCover? = null,
    @SerializedName("collects_id") val collectsId: Long? = null,
    @SerializedName("collects_id_str") val collectsIdStr: String? = null,
    @SerializedName("collects_name") val collectsName: String? = null,
    @SerializedName("create_time") val createTime: Long? = null,
    @SerializedName("follow_status") val followStatus: Int? = null,
    @SerializedName("followed_count") val followedCount: Int? = null,
    @SerializedName("is_normal_status") val isNormalStatus: Boolean? = null,
    @SerializedName("item_type") val itemType: Int? = null,
    @SerializedName("last_collect_time") val lastCollectTime: Long? = null,
    @SerializedName("play_count") val playCount: Int? = null,
    @SerializedName("states") val states: Int? = null,
    @SerializedName("status") val status: Int? = null,
    @SerializedName("system_type") val systemType: Int? = null,
    @SerializedName("total_number") val totalNumber: Int? = null,
    @SerializedName("user_id") val userId: Long? = null,
    @SerializedName("user_id_str") val userIdStr: String? = null,
    @SerializedName("user_info") val userInfo: CollectsUserInfo? = null,
)

data class CollectsCover(
    @SerializedName("uri") val uri: String? = null,
    @SerializedName("url_list") val urlList: List<String>? = null,
)

data class CollectsUserInfo(
    @SerializedName("avatar_larger") val avatarLarger: CollectsUserAvatar? = null,
    @SerializedName("avatar_medium") val avatarMedium: CollectsUserAvatar? = null,
    @SerializedName("avatar_thumb") val avatarThumb: CollectsUserAvatar? = null,
    @SerializedName("nickname") val nickname: String? = null,
    @SerializedName("uid") val uid: String? = null,
)

data class CollectsUserAvatar(
    @SerializedName("height") val height: Int? = null,
    @SerializedName("uri") val uri: String? = null,
    @SerializedName("url_list") val urlList: List<String>? = null,
    @SerializedName("width") val width: Int? = null,
)

fun DouyinCollectsListResponse.hasMorePages(): Boolean = hasMore == true

/** 收藏夹列表一页（供 UI 选夹） */
data class DouyinCollectsListPage(
    val folders: List<DouyinCollectsFolderRow>,
    val hasMore: Boolean,
    val nextCursor: Long,
    val statusCode: Int,
    val statusMessage: String? = null,
)

data class DouyinCollectsFolderRow(
    val id: String,
    val name: String,
)

// ========== 视频详情 ==========
data class DouyinVideoDetailResponse(
    @SerializedName("status_code") val statusCode: Int,
    @SerializedName("aweme_detail") val awemeDetail: AwemeItem?
)

