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
    /** 接口有时为 0/1、有时为 boolean，与 [DouyinCollectsListResponse.has_more] 类似 */
    @SerializedName("has_more") val hasMore: JsonElement? = null,
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

/** 是否还有更多（抖音常见为 0/1，亦可能为 boolean / 字符串）。 */
fun DouyinUserVideosResponse.hasMorePages(): Boolean {
    val el = hasMore ?: return false
    if (!el.isJsonPrimitive) return false
    val p = el.asJsonPrimitive
    return when {
        p.isBoolean -> p.asBoolean
        p.isNumber -> p.asInt != 0
        p.isString -> {
            val s = p.asString.trim()
            s == "1" || s.equals("true", ignoreCase = true)
        }
        else -> false
    }
}

/**
 * 列表接口一页结果（供 UI / 批量协调器使用）。
 */
data class DouyinListPage(
    val items: List<AwemeItem>,
    val hasMore: Boolean,
    val nextCursor: Long,
    val statusCode: Int,
    val statusMessage: String? = null,
    /** 喜欢列表等接口翻页时与 [nextCursor]（max_cursor）成对使用，见接口返回的 min_cursor。 */
    val nextMinCursor: Long? = null,
)

data class AwemeItem(
    @SerializedName("aweme_id") val awemeId: String = "",
    /** 部分列表项仅下发字符串 id（与 aweme_id 同义）。 */
    @SerializedName("id_str") val idStr: String? = null,
    /** 与 aweme_id 常一致；轻量列表可能仅有此项。 */
    @SerializedName("group_id") val groupId: String? = null,
    @SerializedName("desc") val desc: String?, // 视频描述
    @SerializedName("create_time") val createTime: Long,
    @SerializedName("author") val author: Author?,
    @SerializedName("video") val video: Video?,
    /** 图集/图文类型（aweme_type=68）时不为空，列表中每个元素对应一张图片。 */
    @SerializedName("images") val images: List<AwemeImage>? = null,
    @SerializedName("statistics") val statistics: Statistics?,
    @SerializedName("share_url") val shareUrl: String?
)

data class Author(
    @SerializedName("uid") val uid: String = "",
    @SerializedName("sec_uid") val secUid: String = "",
    @SerializedName("nickname") val nickname: String = "",
    @SerializedName("avatar_thumb") val avatarThumb: ImageUrl?
)

data class Video(
    @SerializedName("play_addr") val playAddr: PlayAddr?,
    /** 部分接口仅下发下载地址，列表里 play_addr 可能为空。 */
    @SerializedName("download_addr") val downloadAddr: PlayAddr? = null,
    /** 多档清晰度；部分接口主地址在 bit_rate[].play_addr 中。 */
    @SerializedName("bit_rate") val bitRate: List<DouyinBitRateEntry>? = null,
    @SerializedName("cover") val cover: ImageUrl?,
    @SerializedName("dynamic_cover") val dynamicCover: ImageUrl?,
    @SerializedName("duration") val duration: Int = 0, // 毫秒
    @SerializedName("ratio") val ratio: String?,
    @SerializedName("width") val width: Int = 0,
    @SerializedName("height") val height: Int = 0,
)

data class PlayAddr(
    @SerializedName("uri") val uri: String?,
    @SerializedName("url_list") val urlList: List<String>?, // 视频真实下载地址
    @SerializedName("data_size") val dataSize: Long?,
    @SerializedName("url_key") val urlKey: String?
)

/** [Video.bitRate] 单档，含独立 [play_addr]。 */
data class DouyinBitRateEntry(
    @SerializedName("play_addr") val playAddr: PlayAddr?,
    @SerializedName("gear_name") val gearName: String? = null,
)

data class ImageUrl(
    @SerializedName("url_list") val urlList: List<String>?
)

/**
 * 图集中单张图片（`aweme_type=68` 时 [AwemeItem.images] 中的元素）。
 * 下载优先级：[watermarkFreeDownloadUrlList] > [urlList]（原图压缩，无水印）> [downloadUrlList]（含水印）。
 */
data class AwemeImage(
    @SerializedName("uri") val uri: String? = null,
    @SerializedName("url_list") val urlList: List<String>? = null,
    @SerializedName("download_url_list") val downloadUrlList: List<String>? = null,
    @SerializedName("watermark_free_download_url_list") val watermarkFreeDownloadUrlList: List<String>? = null,
    @SerializedName("width") val width: Int = 0,
    @SerializedName("height") val height: Int = 0,
)

data class Statistics(
    @SerializedName("aweme_id") val awemeId: String? = null,
    @SerializedName("comment_count") val commentCount: Int = 0,
    @SerializedName("digg_count") val diggCount: Int = 0, // 点赞数
    @SerializedName("share_count") val shareCount: Int = 0,
    @SerializedName("play_count") val playCount: Int = 0,
)

// ========== 收藏夹列表（F2：`USER_COLLECTS` → `collects_list`，结构见 activity/data.json）==========
data class DouyinCollectsListResponse(
    @SerializedName("status_code") val statusCode: Int,
    @SerializedName("status_msg") val statusMsg: String? = null,
    @SerializedName("collects_list") val collectsList: List<DouyinCollectsListItem>?,
    /** 实际响应多为 boolean（见 data.json）；旧版可能为 0/1，用 JsonElement 兼容 */
    @SerializedName("has_more") val hasMore: JsonElement? = null,
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

fun DouyinCollectsListResponse.hasMorePages(): Boolean {
    val el = hasMore ?: return false
    if (!el.isJsonPrimitive) return false
    val p = el.asJsonPrimitive
    return when {
        p.isBoolean -> p.asBoolean
        p.isNumber -> p.asInt != 0
        else -> false
    }
}

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

