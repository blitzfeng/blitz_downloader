package com.blitz.downloader.model

import com.blitz.downloader.data.db.DownloadedVideoEntity

/**
 * 管理页宫格条目模型：数据库实体 + 展示所需的附加信息。
 *
 * 由管理页 ViewModel 组装、[com.blitz.downloader.adapter.ManageGridAdapter] 渲染，
 * 跨 viewmodel / fragment / adapter 三处使用，故放在 `model/`。
 */
data class ManageGridItem(
    val entity: DownloadedVideoEntity,
    /** true = 文件存在（或未检查），false = 文件已不存在 → 显示失效蒙层。 */
    val fileExists: Boolean = true,
    /** 用户在管理页手动打的自定义标签列表（来自 video_tags 表）。 */
    val userTags: List<String> = emptyList(),
)
