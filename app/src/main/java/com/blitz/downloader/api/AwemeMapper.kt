package com.blitz.downloader.api

import com.blitz.downloader.ui.VideoItemUiModel

/**
 * 将列表接口 [AwemeItem] 转为网格 [VideoItemUiModel]。
 */
object AwemeMapper {

    fun toGridItems(items: List<AwemeItem>): List<VideoItemUiModel> =
        items.mapNotNull { toGridItemOrNull(it) }

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

    private fun urlsFromPlayAddr(addr: PlayAddr?): List<String> =
        addr?.urlList.orEmpty().mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }

    fun toGridItemOrNull(item: AwemeItem): VideoItemUiModel? {
        val id = item.awemeId.trim()
        if (id.isEmpty()) return null
        val cover = item.video?.cover?.urlList?.firstOrNull()
            ?: item.video?.dynamicCover?.urlList?.firstOrNull()
        val title = item.desc?.trim().orEmpty().ifBlank { "（无标题）" }
        return VideoItemUiModel(
            id = id,
            title = title.take(120),
            coverUrl = cover,
            downloadUrl = preferredPlayDownloadUrl(item),
            isSelected = false,
        )
    }
}
