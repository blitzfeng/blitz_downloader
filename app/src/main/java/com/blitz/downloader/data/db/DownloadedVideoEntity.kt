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
     * 视频原始发布时间（Unix 秒级时间戳），来自接口 `create_time`。
     * 旧记录默认为 0（表示未知）。
     */
    val createTime: Long = 0L,
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
    /**
     * 视频点赞数，来自接口 `statistics.digg_count`（v10 新增）。
     * 旧记录默认为 0（封面上不展示徽标）；当前不做历史回填。
     */
    val diggCount: Long = 0L,
    /**
     * 视频收藏数，来自接口 `statistics.collect_count`（v10 新增，预留字段）。
     * 暂未在 UI 展示，但落库时一并写入，供后续排序/筛选使用。
     * 旧记录默认为 0。
     */
    val collectCount: Long = 0L,
    /**
     * 已成功导出到电脑的次数（v11 新增）。
     *
     * 当前**只有局域网导出**会累加：`LanFileServer` 把该记录的字节完整写出 socket 后回调
     * （单文件下载 / `all.zip` 整包下载各算一次），由
     * [com.blitz.downloader.data.DownloadedVideoRepository.incrementExportCount] 写入。
     * 注意这是"服务端已发完"而非"电脑确认落盘"——HTTP 没有回传通道，属于提示性计数。
     *
     * ZIP 导出（落到 `Download/bDouyin/export/`）不计入，因为那只是生成了包，还没到电脑。
     * `> 0` 时管理页多选态在封面左上角显示「已导出」标记。旧记录默认为 0。
     */
    val exportCount: Int = 0,
    /**
     * 用户修改标签的次数（v12 新增）。
     *
     * 计数单位是**一次编辑操作**而非标签个数：一次弹窗确认里新增 3 个标签也只 +1，
     * 下次再改再 +1。只有编辑后标签集合**真的发生变化**才累加——点开弹窗原样确认
     * （没勾没取消）不计数。
     *
     * 累加入口只有管理页的两处用户操作（单条标签行编辑、多选后「设置标签」），
     * 由 [com.blitz.downloader.data.VideoTagRepository.setTagsAsUserEdit] /
     * [com.blitz.downloader.data.VideoTagRepository.addTagsAsUserEdit] 写入。
     * 下载时自动关联收藏夹同名标签（`ensureCollectFolderTagLinked`）**不算**用户修改，不累加。
     *
     * 旧记录默认为 0（不做历史回填）。
     */
    val tagEditCount: Int = 0,
    /**
     * 是否已看过（v13 新增，默认 false = 未看过）。
     *
     * 只由**管理页进入视频播放页**这一条路径置为 true：点开某条时标记该条，在播放页里上下
     * 滑动切换到的那些也逐条标记（[com.blitz.downloader.activity.VideoPlayerActivity] 收到
     * `EXTRA_LIST_AWEME_IDS` 时才写；网络预览等无 id 的入口不写）。
     * 置位后不提供"标为未看"的回退入口。
     *
     * 管理页视频卡片在点赞数徽标右侧显示「未看过」标记（`watched == false` 时）。
     * 图片 Tab 不显示——图集不走播放页，显示了也永远不会消。
     *
     * 旧记录默认为 false（不做历史回填）。
     */
    val watched: Boolean = false,
    /**
     * 媒体文件的**呈现宽度**（像素，v14 新增）。`0` = 未知。
     *
     * 由 [com.blitz.downloader.util.MediaOrientationProbe] 读本地文件得出，已做旋转 / EXIF 修正，
     * **不是**抖音接口下发的 `video.width`——接口值不保证含旋转修正，且历史记录根本没有。
     *
     * 两个写入时机：
     * 1. 下载落盘后，由 [com.blitz.downloader.download.DownloadService] 随写库一并写入；
     * 2. 局域网导出前（仅视频 Tab），由 [com.blitz.downloader.viewmodel.ManageViewModel]
     *    对 `mediaWidth == 0` 的记录懒探测并回填。
     *
     * 探测失败**保持 0，不写哨兵值**：下次导出会再探一次（几毫秒），换来 `0` 语义单一。
     * 消费方是 [com.blitz.downloader.model.MediaOrientation.of]，`0` 会被判为竖屏。
     * 旧记录默认 0，不做历史批量回填。
     */
    val mediaWidth: Int = 0,
    /** 媒体文件的**呈现高度**（像素，v14 新增）。`0` = 未知，语义与 [mediaWidth] 完全一致。 */
    val mediaHeight: Int = 0,
    /**
     * 是否为**实况图（Live Photo / 动图）图集**（v15 新增）：图集里至少有一张带 mp4。
     * 下载时算出（`imageVideoUrls` 有非空项）并写入，供下载页 / 管理页列表显示动图角标。
     * 旧记录默认 false，**不做历史批量回填**（列表渲染时逐项探测 mp4 兄弟文件的 IO 太重）；
     * 下载页不依赖本字段（内存里的 `imageVideoUrls` 现算），只有管理页读它。
     */
    val hasLivePhoto: Boolean = false,
)
