package com.blitz.downloader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "downloaded_videos",
    indices = [Index(value = ["awemeId"], unique = true)],
)
data class DownloadedVideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 接口侧作品 id，与网格项 id 一致。 */
    val awemeId: String,
    /** [com.blitz.downloader.data.DownloadSourceType] 之一（post/like/collect/mix/collects）。 */
    val downloadType: String,
    /** 下载时的作者昵称快照。 */
    val userName: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    /**
     * 媒体类型：[com.blitz.downloader.data.DownloadMediaType.VIDEO] 或
     * [com.blitz.downloader.data.DownloadMediaType.IMAGE]。
     */
    val mediaType: String = "video",
    /**
     * 文件在设备存储中的可读路径，如 `Download/bDouyin/videos/author_title.mp4` 或
     * `Download/bDouyin/images/author_title_01.jpg`（图集时为第一张图路径）。
     * 旧记录默认为空字符串。
     */
    val filePath: String = "",
    /**
     * 封面缩略图的本地路径。
     * - 视频：`Download/bDouyin/covers/author_title.jpg`（下载时同步保存，URL 失效后仍可用）。
     * - 图集：与 [filePath] 相同（第一张图即封面，无需重复保存）。
     * - 旧记录默认为空字符串，显示时可回退到占位图。
     */
    val coverPath: String = "",
    /**
     * 视频/图集的文字描述（即作者发布时填写的文案/标题）。
     * 旧记录默认为空字符串。
     */
    val desc: String = "",
    /**
     * 当 [downloadType] 为 `"collects"` 时，记录所属收藏夹的名称；其余场景留空。
     * 旧记录默认为空字符串。
     */
    val collectionType: String = "",
    /**
     * 当 [downloadType] 为 `"collects"` 时，记录所属收藏夹的稳定 ID（与 [collectionType] 对应）；
     * 其余场景留空。旧记录默认为空字符串。
     */
    val collectId: String = "",
    /**
     * 视频原始创作者的稳定 `sec_user_id`。
     * 用于管理页「按作者过滤」，不受作者改名影响。
     * 旧记录默认为空字符串。
     */
    val videoAuthorSecUserId: String = "",
    /**
     * 下载来源账户/主页的 `sec_user_id`。
     * - [downloadType] 为 `"post"` 时：填被下载的目标用户 `sec_user_id`。
     * - [downloadType] 为 `"like"`/`"collect"`/`"collects"` 时：填 App 所有者账号
     *   [com.blitz.downloader.config.AppConfig.MY_SEC_USER_ID]，表示「来自我的账户列表」。
     * 旧记录默认为空字符串。
     */
    val sourceOwnerSecUserId: String = "",
    /**
     * 视频与账户所有者的关系标签，仅对「我的账户」下载有效（[downloadType] 为
     * `"like"`/`"collects"` 时填写，`"post"` 场景留空）。
     *
     * 编码规则（分隔符为 `|`）：
     * - 从喜欢列表下载，未收藏（`collect_stat=0`）→ `"like"`
     * - 从喜欢列表下载，已收藏（`collect_stat=1`）→ `"like|collect"`
     * - 从收藏夹下载，未点赞（`user_digged=0`）→ `"<收藏夹名称>"`（如 `"舞蹈"`）
     * - 从收藏夹下载，已点赞（`user_digged=1`）→ `"like|<收藏夹名称>"`（如 `"like|舞蹈"`）
     *
     * 管理页可直接展示此字段，或按 `|` 拆分后渲染为多个标签。
     * 旧记录默认为空字符串。
     */
    val userRelation: String = "",
)
