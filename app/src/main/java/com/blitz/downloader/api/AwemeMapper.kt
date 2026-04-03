package com.blitz.downloader.api

import com.blitz.downloader.ui.VideoItemUiModel

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
     * 与单视频 Tab 一致：将 `playwm` 换为 `play` 以尽量走无水印直链。
     * 顺序：`play_addr` → `download_addr` → `bit_rate[].play_addr`（与 F2 常见取址顺序一致），
     * 每段内取 [url_list] 中**第一个**非空 URL。
     */
    fun preferredPlayDownloadUrl(item: AwemeItem): String? {
        val v = item.video ?: return null
        val candidates = buildList {
            addAll(urlsFromPlayAddr(v.playAddr))
            addAll(urlsFromPlayAddr(v.downloadAddr))
            v.bitRate.orEmpty().forEach { addAll(urlsFromPlayAddr(it.playAddr)) }
        }
        val raw = candidates.firstOrNull { it.isNotBlank() } ?: return null
        return raw.trim().replace("playwm", "play", ignoreCase = false)
    }

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
