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
     */
    fun preferredPlayDownloadUrl(item: AwemeItem): String? {
        val raw = item.video?.playAddr?.urlList?.firstOrNull()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return raw.replace("playwm", "play", ignoreCase = false)
    }

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
