package com.blitz.downloader.api

import com.blitz.downloader.model.VideoItemUiModel

/**
 * 将列表接口 [AwemeItem] 转为网格 [VideoItemUiModel]。
 */
object AwemeMapper {

    fun toGridItems(
        items: List<AwemeItem>,
        preferredResolution: Int? = null,
    ): List<VideoItemUiModel> =
        items.mapNotNull { toGridItemOrNull(it, preferredResolution) }
            .distinctBy { it.id }

    /** 判断是否为图集/图文类型（[AwemeItem.images] 非空即为图集）。 */
    fun isPhotoItem(item: AwemeItem): Boolean = !item.images.isNullOrEmpty()

    /**
     * 选取视频下载直链，画质由 [preferredResolution] 决定（null = 沿用旧行为，**优先 1080p**）：
     * 1. 先在 `bit_rate[]` 各清晰度档里按 [pickBestBitRateUrl] 的规则挑一档。
     * 2. `bit_rate` 无可用地址时，回退到 `play_addr` → `download_addr`（旧行为，此时画质不可控）。
     *
     * 分辨率从 [DouyinBitRateEntry.gearName]（形如 `normal_1080_0`）解析。
     * 最后统一把 `playwm` 换为 `play` 以尽量走无水印直链。
     */
    fun preferredPlayDownloadUrl(item: AwemeItem, preferredResolution: Int? = null): String? =
        item.video?.let { bestVideoUrl(it, preferredResolution) }

    /**
     * 从一个 [Video]（普通视频或实况图 `image.video`）选出最佳 mp4 直链：
     * `bit_rate[]` 按 [preferredResolution]（见 [com.blitz.downloader.config.VideoQualityPreference]）挑档，
     * 回退 play_addr / download_addr，最后 `playwm`→`play`。无可用地址返回 null。
     */
    fun bestVideoUrl(v: Video, preferredResolution: Int? = null): String? {
        val raw = pickBestBitRateUrl(v.bitRate, preferredResolution)
            ?: urlsFromPlayAddr(v.playAddr).firstOrNull { it.isNotBlank() }
            ?: urlsFromPlayAddr(v.downloadAddr).firstOrNull { it.isNotBlank() }
            ?: return null
        return raw.trim().replace("playwm", "play", ignoreCase = false)
    }

    /** [preferredResolution] 为 null 时的默认档位：抖音 web 一般最高即 1080。 */
    private const val PREFERRED_RESOLUTION = 1080

    /**
     * 从 `bit_rate[]` 里选出最佳一档的直链：
     * - [preferredResolution] 为 null：1080p 优先，其次按分辨率、再按码率（旧行为，不变）。
     * - 非 null：优先选**不超过该分辨率的最高档**（同分辨率取码率最高）；一档都没有
     *   （该视频所有档位都比目标分辨率还高，比如目标 540 但只有 720/1080）则退而选现有最低档
     *   ——语义是「画质封顶省流量」，宁可比目标低也不超标，没有更低的只能取当前能拿到的最小值。
     *
     * 无任何带地址的档位时返回 null（交由上层回退到 play_addr/download_addr）。
     */
    private fun pickBestBitRateUrl(
        entries: List<DouyinBitRateEntry>?,
        preferredResolution: Int?,
    ): String? {
        val usable = entries.orEmpty().filter { !it.playAddr?.urlList.isNullOrEmpty() }
        if (usable.isEmpty()) return null
        val best = if (preferredResolution == null) {
            usable.maxWith(
                compareBy(
                    { if (resolutionOf(it) == PREFERRED_RESOLUTION) 1 else 0 }, // 1080 档一律优先
                    { resolutionOf(it) },                                        // 否则分辨率越高越好
                    { it.bitRateBps },                                           // 同分辨率码率越高越清晰
                )
            )
        } else {
            val notExceeding = usable.filter { resolutionOf(it) in 1..preferredResolution }
            if (notExceeding.isNotEmpty()) {
                notExceeding.maxWith(compareBy({ resolutionOf(it) }, { it.bitRateBps }))
            } else {
                usable.minWith(compareBy({ resolutionOf(it) }, { it.bitRateBps }))
            }
        }
        return urlsFromPlayAddr(best.playAddr).firstOrNull { it.isNotBlank() }
    }

    /** 从 `gear_name`（如 `normal_1080_0` / `adapt_lowest_720_1`）解析分辨率数字；解析失败返回 0。 */
    private fun resolutionOf(entry: DouyinBitRateEntry): Int =
        entry.gearName?.let { Regex("(\\d{3,4})").find(it)?.value?.toIntOrNull() } ?: 0

    /**
     * 提取图集所有图片的最优下载 URL（每张图一个 URL）。
     * 优先级：[AwemeImage.watermarkFreeDownloadUrlList] > [AwemeImage.urlList]（原图压缩，无水印）
     * > [AwemeImage.downloadUrlList]（含水印）。
     */
    fun preferredImageUrls(item: AwemeItem): List<String> =
        preferredImagePairs(item).map { it.first }

    /**
     * 图集每张图的 (静态封面 URL, 实况图 mp4 URL?) 配对。静态封面缺失的图整张丢弃，
     * 保证结果与 [preferredImageUrls] 一致、且第二元素与之**一一对应**（动图非空、静态图 null）。
     * 静态封面优先级同 [preferredImageUrls]；mp4 走 [bestVideoUrl]（同样受 [preferredResolution] 影响）。
     */
    fun preferredImagePairs(
        item: AwemeItem,
        preferredResolution: Int? = null,
    ): List<Pair<String, String?>> {
        val images = item.images ?: return emptyList()
        return images.mapNotNull { img ->
            val candidates = buildList {
                addAll(img.watermarkFreeDownloadUrlList.orEmpty())
                addAll(img.urlList.orEmpty())
                addAll(img.downloadUrlList.orEmpty())
            }
            val staticUrl = candidates.firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
            staticUrl to img.video?.let { bestVideoUrl(it, preferredResolution) }
        }
    }

    private fun urlsFromPlayAddr(addr: PlayAddr?): List<String> =
        addr?.urlList.orEmpty().mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }

    fun toGridItemOrNull(item: AwemeItem, preferredResolution: Int? = null): VideoItemUiModel? {
        val id = resolveStableAwemeId(item)
        if (id.isEmpty()) return null
        val isPhoto = isPhotoItem(item)
        val cover = if (isPhoto) {
            item.images?.firstOrNull()?.urlList?.firstOrNull()
                ?: item.video?.cover?.urlList?.firstOrNull()
        } else {
            item.video?.cover?.urlList?.firstOrNull()
                ?: item.video?.dynamicCover?.urlList?.firstOrNull()
        }
        val rawDesc = item.desc?.trim().orEmpty()
        val title = rawDesc.ifBlank { "（无标题）" }.take(120)
        val nickname = item.author?.nickname?.trim().orEmpty()
        val imagePairs = if (isPhoto) preferredImagePairs(item, preferredResolution) else emptyList()
        return VideoItemUiModel(
            id = id,
            title = title,
            authorNickname = nickname,
            descRaw = rawDesc,
            coverUrl = cover,
            downloadUrl = if (isPhoto) null else preferredPlayDownloadUrl(item, preferredResolution),
            isSelected = false,
            isPhoto = isPhoto,
            imageUrls = imagePairs.map { it.first },
            imageVideoUrls = imagePairs.map { it.second },
            authorSecUserId = item.author?.secUid?.trim().orEmpty(),
            collectStat = item.collectStat,
            userDigged = item.userDigged,
            createTime = item.createTime,
            diggCount = item.statistics?.diggCount ?: 0L,
            collectCount = item.statistics?.collectCount ?: 0L,
        )
    }

    /**
     * 列表侧常见仅缺 [AwemeItem.awemeId]、但有 [AwemeItem.idStr] / [AwemeItem.group_id] / [Statistics.awemeId] 的情况，
     * 原先会被 [mapNotNull] 整段丢弃，导致一页 20 条只显示十几条。
     */
    internal fun resolveStableAwemeId(item: AwemeItem): String =
        sequenceOf(
            item.awemeId,
            item.idStr,
            item.groupId,
            item.statistics?.awemeId,
        )
            .mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .firstOrNull()
            .orEmpty()
}
