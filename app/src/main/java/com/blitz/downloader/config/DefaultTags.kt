package com.blitz.downloader.config

/**
 * 预设标签名称列表，供打标签 UI 作为快速选项展示。
 *
 * 这些只是默认建议值，不会预插入数据库（[com.blitz.downloader.data.db.VideoTagEntity]
 * 需要关联具体 awemeId）。UI 展示时可将此列表与
 * [com.blitz.downloader.data.VideoTagRepository.getAllTags] 返回的已用标签合并去重。
 */
object DefaultTags {

    val list: List<String> = listOf(
        "美腿",
        "可爱",
        "纯欲",
        "波霸",
        "小沟",
        "穿搭",
        "舞蹈",
        "黑丝",
    )
}
