package com.blitz.downloader.api

import com.blitz.downloader.model.VideoItemUiModel

/**
 * 将列表接口 [AwemeItem] 转为网格 [VideoItemUiModel]。
 */
object AwemeMapper {

    fun toGridItems(items: List<AwemeItem>): List<VideoItemUiModel> =
        items.mapNotNull { toGridItemOrNull(it) }
            .distinctBy { it.id }

    /** 判断是否为图集/图文类型（[AwemeItem.images] 非空即为图集）。 */
    fun isPhotoItem(item: AwemeItem): Boolean = !item.images.isNullOrEmpty()

    /**
     * 选取视频下载直链，**优先 1080p**：
     * 1. 先在 `bit_rate[]` 各清晰度档里挑分辨率为 1080 的一档（同为 1080 取码率最高）；
     *    没有 1080 时退而取分辨率最高的一档（再按码率）。
     * 2. `bit_rate` 无可用地址时，回退到 `play_addr` → `download_addr`（旧行为）。
     *
     * 分辨率从 [DouyinBitRateEntry.gearName]（形如 `normal_1080_0`）解析。
     * 最后统一把 `playwm` 换为 `play` 以尽量走无水印直链。
     */
    fun preferredPlayDownloadUrl(item: AwemeItem): String? {
        val v = item.video ?: return null
        val raw = pickBestBitRateUrl(v.bitRate)
            ?: urlsFromPlayAddr(v.playAddr).firstOrNull { it.isNotBlank() }
            ?: urlsFromPlayAddr(v.downloadAddr).firstOrNull { it.isNotBlank() }
            ?: return null
        return raw.trim().replace("playwm", "play", ignoreCase = false)
    }

    /** 目标分辨率档位：抖音 web 一般最高即 1080。 */
    private const val PREFERRED_RESOLUTION = 1080

    /**
     * 从 `bit_rate[]` 里选出最佳一档的直链：1080p 优先，其次按分辨率、再按码率。
     * 无任何带地址的档位时返回 null（交由上层回退到 play_addr/download_addr）。
     */
    private fun pickBestBitRateUrl(entries: List<DouyinBitRateEntry>?): String? {
        val usable = entries.orEmpty().filter { !it.playAddr?.urlList.isNullOrEmpty() }
        if (usable.isEmpty()) return null
        val best = usable.maxWith(
            compareBy(
                { if (resolutionOf(it) == PREFERRED_RESOLUTION) 1 else 0 }, // 1080 档一律优先
                { resolutionOf(it) },                                        // 否则分辨率越高越好
                { it.bitRateBps },                                           // 同分辨率码率越高越清晰
            )
        )
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
    fun preferredImageUrls(item: AwemeItem): List<String> {
        val images = item.images ?: return emptyList()
        return images.mapNotNull { img ->
            val candidates = buildList {
                addAll(img.watermarkFreeDownloadUrlList.orEmpty())
                addAll(img.urlList.orEmpty())
                addAll(img.downloadUrlList.orEmpty())
            }
            candidates.firstOrNull { it.isNotBlank() }
        }
    }

    private fun urlsFromPlayAddr(addr: PlayAddr?): List<String> =
        addr?.urlList.orEmpty().mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }

    fun toGridItemOrNull(item: AwemeItem): VideoItemUiModel? {
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
        val imageUrls = if (isPhoto) preferredImageUrls(item) else emptyList()
        return VideoItemUiModel(
            id = id,
            title = title,
            authorNickname = nickname,
            descRaw = rawDesc,
            coverUrl = cover,
            downloadUrl = if (isPhoto) null else preferredPlayDownloadUrl(item),
            isSelected = false,
            isPhoto = isPhoto,
            imageUrls = imageUrls,
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
