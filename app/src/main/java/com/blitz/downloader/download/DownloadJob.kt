package com.blitz.downloader.download

import com.blitz.downloader.model.VideoItemUiModel

/**
 * 一次批量下载任务的完整描述，从 UI 层（[com.blitz.downloader.fragment.ListDownloadFragment]）
 * 交给 [DownloadService] 在后台执行。
 *
 * 之所以把「入库所需的元数据」也一并带上（而非在 UI 层下载后再写库），是因为下载被迁到了
 * 前台服务里执行，服务需要在下载成功后**自己**完成 `recordSuccessfulDownload` 与收藏夹标签关联，
 * 这样即使用户离开页面 / 应用退到后台，记录也不会丢。
 *
 * @param items 选中的待下载项（视频与图集）。
 * @param metas awemeId → 入库元数据；下载成功后按此写库。
 */
data class DownloadJob(
    val items: List<VideoItemUiModel>,
    val metas: Map<String, DownloadRecordMeta>,
)

/**
 * 单条记录写库所需的全部字段快照（对应 [com.blitz.downloader.data.DownloadedVideoRepository.recordSuccessfulDownload]），
 * 在 UI 层根据当前列表模式预先算好，避免服务再依赖 UI 上下文。
 */
data class DownloadRecordMeta(
    val downloadType: String,
    val userName: String,
    val mediaType: String,
    val createTime: Long,
    val desc: String,
    val collectionType: String,
    val collectId: String,
    val videoAuthorSecUserId: String,
    val sourceOwnerSecUserId: String,
    val userRelation: String,
    val diggCount: Long,
    val collectCount: Long,
    /** 是否为收藏夹来源，需要额外 `ensureCollectFolderTagLinked(awemeId, collectionType)`。 */
    val linkCollectFolderTag: Boolean,
)
