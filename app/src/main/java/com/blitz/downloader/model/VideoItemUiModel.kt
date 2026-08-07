package com.blitz.downloader.model

/**
 * 列表页网格条目模型。
 *
 * 虽然名字带 `UiModel`，它实际是**跨层的领域模型**：由 [com.blitz.downloader.api.AwemeMapper]
 * 从接口响应生成，被 [com.blitz.downloader.adapter.VideoGridAdapter] 渲染，
 * 也被 [com.blitz.downloader.download.BatchDownloadCoordinator] 直接消费。
 * 因此它放在 `model/` 而不是 UI 包下。
 */
data class VideoItemUiModel(
    val id: String,
    val title: String,
    /** 作者昵称；用于批量下载文件名 `{昵称}_{描述}`。 */
    val authorNickname: String,
    /** 接口原始文案（trim），空则无描述；文件名会去掉 #话题 后再用。 */
    val descRaw: String,
    val coverUrl: String?,
    /** 列表接口 [com.blitz.downloader.api.AwemeItem] 中 play_addr 的首选直链，已做 `playwm`→`play` 处理；无则无法批量下载。图集类型为 null。 */
    val downloadUrl: String?,
    /**
     * 是否被用户勾选。
     *
     * **这是给下载链路的适配位**：[com.blitz.downloader.download.BatchDownloadCoordinator]
     * 靠它筛选要下载的条目（见该类头部注释的契约）。选中状态的**权威来源**是
     * ViewModel 里的选中集合，这个字段只在调用下载链路前于边界处组装出来。
     */
    val isSelected: Boolean,
    /** 本地库中已记录下载（按作品 id）；列表中禁止勾选。 */
    val isDownloaded: Boolean = false,
    /** 是否为图集/图文类型（aweme_type=68，[imageUrls] 字段非空）。 */
    val isPhoto: Boolean = false,
    /** 图集所有图片的最优下载 URL 列表（[isPhoto]=true 时非空）。 */
    val imageUrls: List<String> = emptyList(),
    /**
     * 用户在图集选图弹窗（[com.blitz.downloader.dialog.PhotoSelectionBottomSheet]）里勾选的图片下标（相对 [imageUrls]）。
     *
     * `null` = 没做过子选择，按**全选**处理——批量「全选」按钮勾中的图集就是这个状态，
     * 不会因此弹窗。全选完成时也归一化回 `null`，保证"全选"只有一种表示。
     *
     * 空集合是**非法停留状态**：一张都不选等于不下载这条，
     * [com.blitz.downloader.fragment.ListDownloadFragment] 会连同
     * `isSelected` 一起取消，不会把空集合留在列表里。
     */
    val selectedImageIndices: Set<Int>? = null,
    /** 视频创作者的稳定 `sec_user_id`，来自 [com.blitz.downloader.api.Author.secUid]。写入 DB 时用。 */
    val authorSecUserId: String = "",
    /**
     * 喜欢列表接口返回的 `collect_stat`（0=未收藏，1=已收藏）。
     * 用于构建 [com.blitz.downloader.data.db.DownloadedVideoEntity.userRelation]。
     */
    val collectStat: Int = 0,
    /**
     * 收藏夹接口返回的 `user_digged`（0=未点赞，1=已点赞）。
     * 用于构建 [com.blitz.downloader.data.db.DownloadedVideoEntity.userRelation]。
     */
    val userDigged: Int = 0,
    /**
     * 视频发布时间，来自接口 `create_time`（Unix 秒级时间戳）。
     * 写入 [com.blitz.downloader.data.db.DownloadedVideoEntity.createTime]。
     */
    val createTime: Long = 0L,
    /**
     * 视频点赞数（接口 `statistics.digg_count`）。<=0 时封面不展示徽标。
     * 写入 [com.blitz.downloader.data.db.DownloadedVideoEntity.diggCount]。
     */
    val diggCount: Long = 0L,
    /**
     * 视频收藏数（接口 `statistics.collect_count`），预留字段。
     * 写入 [com.blitz.downloader.data.db.DownloadedVideoEntity.collectCount]。
     */
    val collectCount: Long = 0L,
) {
    /**
     * 实际参与下载的图集图片 URL：做过子选择时只留勾中的那几张，否则是全部。
     * 下载链路（[com.blitz.downloader.download.BatchDownloadCoordinator]）一律走这个属性，
     * **不要**直接用 [imageUrls]，否则用户的选图会被忽略。
     */
    val downloadImageUrls: List<String>
        get() = selectedImageIndices
            ?.let { sel -> imageUrls.filterIndexed { index, _ -> index in sel } }
            ?: imageUrls

    /** 是否只选了图集里的一部分图片（用于网格徽标显示 `3/9张`）。 */
    val hasPartialImageSelection: Boolean
        get() = selectedImageIndices != null && selectedImageIndices.size < imageUrls.size
}
