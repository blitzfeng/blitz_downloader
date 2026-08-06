# BlitzDownloader 数据库设计文档

> **当前版本：v13**
> 实现文件：`app/src/main/java/com/blitz/downloader/data/db/`

---

## 总体结构

数据库名：`blitz_downloader.db`（Room，SQLite）

| 表名 | 对应 Entity | 用途 |
|------|-------------|------|
| `downloaded_videos` | `DownloadedVideoEntity` | 已下载视频/图集的核心记录 |
| `video_tags` | `VideoTagEntity` | 视频-标签关联（多对多） |
| `tags` | `TagEntity` | 独立标签名册（支持先建标签再打给视频） |

---

## 版本演进历史

| 版本 | 关键变更 |
|------|---------|
| v1 | 初始表：`id`、`awemeId`、`downloadType`、`userName`、`createdAtMillis` |
| v2 | 新增 `mediaType`、`filePath` |
| v3 | 新增 `coverPath` |
| v4 | 新增 `desc`、`collectionType`；同时加了 `likeType`（冗余列，v5 删除） |
| v5 | 重建表删除 `likeType`，新增 `videoAuthorSecUserId`、`sourceOwnerSecUserId` |
| v6 | 新建 `video_tags` 表；新增 `collectId`、`userRelation` |
| v7 | 新建 `tags` 独立标签名册；预插入 8 个默认标签 |
| v8 | `tags` 表新增 `sortOrder` 列（展示顺序） |
| v9 | `downloaded_videos` 新增 `createTime`（视频原始发布时间，Unix 秒） |
| v10 | `downloaded_videos` 新增 `diggCount`（点赞数）、`collectCount`（收藏数，预留） |
| v11 | `downloaded_videos` 新增 `exportCount`（已成功导出到电脑的次数） |
| v12 | `downloaded_videos` 新增 `tagEditCount`（用户修改标签的次数） |
| v13 | `downloaded_videos` 新增 `watched`（是否已看过） |

> **注意**：v4 的 `likeType` 与 `downloadType` 语义重叠，v5 通过重建表删除，**后续不要再加同类冗余字段**。

---

## 表一：`downloaded_videos`

### 字段说明

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `id` | INTEGER PK | autoincrement | 行主键 |
| `awemeId` | TEXT UNIQUE | — | 抖音作品 ID，全局唯一（接口字段 `aweme_id`） |
| `downloadType` | TEXT | — | 来源类型，见下方枚举 |
| `userName` | TEXT | — | 视频作者昵称快照（可变，仅用于展示） |
| `createdAtMillis` | INTEGER | `System.currentTimeMillis()` | 下载入库时间戳（毫秒） |
| `mediaType` | TEXT | `"video"` | `"video"` 或 `"image"`（图集） |
| `filePath` | TEXT | `""` | 本地文件路径（视频 mp4 或图集第一张图） |
| `coverPath` | TEXT | `""` | 本地封面路径；图集与 `filePath` 相同 |
| `createTime` | INTEGER | `0` | 视频原始发布时间（Unix 秒，接口字段 `create_time`；0 表示未知） |
| `desc` | TEXT | `""` | 作者发布时的文案/标题（接口字段 `desc`） |
| `collectionType` | TEXT | `""` | `downloadType="collects"` 时填收藏夹名称 |
| `collectId` | TEXT | `""` | `downloadType="collects"` 时填收藏夹稳定 ID（与 `collectionType` 对应） |
| `videoAuthorSecUserId` | TEXT | `""` | 视频创作者的稳定 `sec_user_id`（不受改名影响，用于管理页按作者过滤） |
| `sourceOwnerSecUserId` | TEXT | `""` | 下载来源账户的 `sec_user_id`（见下方填写规则） |
| `userRelation` | TEXT | `""` | 视频与账户所有者的关系标签（仅我的账户有效，见下方编码规则） |
| `diggCount` | INTEGER | `0` | 视频点赞数，接口字段 `statistics.digg_count`（v10 新增） |
| `collectCount` | INTEGER | `0` | 视频收藏数，接口字段 `statistics.collect_count`（v10 新增，UI 暂未展示，预留） |
| `exportCount` | INTEGER | `0` | 已成功导出到电脑的次数（v11 新增，见下方规则） |
| `tagEditCount` | INTEGER | `0` | 用户修改标签的次数（v12 新增，见下方规则） |
| `watched` | INTEGER(Bool) | `0`(false) | 是否已看过（v13 新增，见下方规则） |

### `downloadType` 枚举值

来源：`DownloadSourceType.kt`

| 值 | 含义 | 来源账户 |
|----|------|---------|
| `"post"` | 他人主页帖子 | 他人 |
| `"like"` | 我的喜欢列表 | 我的账户 |
| `"collect"` | 我的收藏（通用） | 我的账户 |
| `"collects"` | 我的收藏夹（按夹分类） | 我的账户 |
| `"mix"` | 合集 | — |

**管理页过滤逻辑：**
- 「来自我的账户」：`downloadType IN ('like', 'collect', 'collects')`
- 「来自他人帖子」：`downloadType = 'post'`

### `sourceOwnerSecUserId` 填写规则

| `downloadType` | 填入值 |
|----------------|--------|
| `"post"` | 被下载目标用户的 `sec_user_id` |
| `"like"` / `"collect"` / `"collects"` | `AppConfig.MY_SEC_USER_ID`（App 所有者账号） |

### `userRelation` 编码规则

**仅对我的账户下载有效**（`downloadType = "like"` 或 `"collects"`），`post` 场景留空。

分隔符：`|`

| 场景 | API 字段 | `userRelation` 值 |
|------|----------|-------------------|
| 从喜欢列表下载，未收藏 | `collect_stat=0` | `"like"` |
| 从喜欢列表下载，已收藏 | `collect_stat=1` | `"like\|collect"` |
| 从收藏夹下载，未点赞 | `user_digged=0` | `"<收藏夹名称>"` 如 `"舞蹈"` |
| 从收藏夹下载，已点赞 | `user_digged=1` | `"like\|<收藏夹名称>"` 如 `"like\|舞蹈"` |

**构建方式（`DownloadedVideoRepository.companion`）：**
```kotlin
// 从喜欢列表
DownloadedVideoRepository.buildUserRelationFromLike(collectStat: Int)

// 从收藏夹
DownloadedVideoRepository.buildUserRelationFromCollection(userDigged: Int, folderName: String)
```

**管理页展示：** 按 `|` 拆分后渲染为多个标签 chip。

### `exportCount` 累加规则（v11）

**只有局域网导出会累加**。`LanFileServer` 把某条记录的字节完整写出 socket 且未报错时回调
`TransferEvent`，由 `DownloadedVideoRepository.incrementExportCount(awemeIds)` 做 `SET exportCount = exportCount + 1` 的原子累加（不要走"读实体→改→整行 update"，会覆盖并发写）。

| 场景 | 是否累加 |
|------|---------|
| 局域网单文件下载（`/f?i=N`）完整发出 | ✅ +1（图集的多张图共享同一 `awemeId`，仍只 +1） |
| 局域网整包下载（`/all.zip`）完整发出 | ✅ 包内每条记录各 +1 |
| 整包中途被取消 / 断连 | ❌ 不累加（半个包对电脑不可用） |
| `HEAD` 探测请求 | ❌ 不累加（只回响应头，不写 body） |
| ZIP 导出到 `Download/bDouyin/export/` | ❌ 不累加（只是生成了包，还没到电脑） |

**语义边界**：它表示"手机已把数据完整发出"，**不代表电脑确认落盘**——HTTP 没有反向通道，浏览器取消保存、写盘失败都探知不到。因此只做提示与二次确认依据，别当权威状态用。

**UI 用法**：`exportCount > 0` 时，管理页**多选态**在封面左上角显示「已导出」标记（多次显示 `已导出 ×N`）；点「发送到电脑」时若选中项含已导出记录，先弹三选一确认：**取消** / **过滤已导出**（只把 `exportCount == 0` 的记录挂到服务上）/ **确认**（全部导出，允许重复）。

### `tagEditCount` 累加规则（v12）

计数单位是**一次编辑操作**，不是标签个数：一次弹窗确认里新增 3 个标签也只 +1，下次再改再 +1。
只有编辑后标签集合**确实发生变化**才累加——点开弹窗原样确认不计数。

累加走 `VideoTagRepository` 的两个**用户编辑入口**，内部用
`DownloadedVideoDao.incrementTagEditCount` 做 `SET tagEditCount = tagEditCount + 1` 的原子累加
（与 `exportCount` 同理，不要改成"读实体→改→整行 update"）：

| 入口 | 场景 | 计数 |
|------|------|------|
| `VideoTagRepository.setTagsAsUserEdit(awemeId, tags)` | 管理页点标签行，弹窗覆盖式编辑单条 | ✅ 集合有变化才 +1 |
| `VideoTagRepository.addTagsAsUserEdit(awemeIds, tags)` | 管理页多选后「设置标签」批量追加 | ✅ 只对真的多出新标签的记录 +1 |
| `setTags` / `addTag` / `addTags`（程序侧原始方法） | — | ❌ 不累加 |
| `ensureCollectFolderTagLinked`（下载时关联收藏夹同名标签） | 下载流程自动打标签 | ❌ 不累加（不是用户改的） |

**UI 层新增标签编辑入口时必须走 `*AsUserEdit` 版本**，否则统计漏计；反过来下载 / 导入这类
程序自动打标签的流程若误用它们，会把计数虚增。

**筛选**：管理页「按标签修改次数筛选」（`ManageTagEditCountFilter`，档位 0/1/2/3/4/5/>5）读的就是这个字段。

### `watched` 置位规则（v13）

Room 的 Boolean 落库为 INTEGER（0/1），默认 0 = 未看过。**只置位、不回退**，没有"标为未看"的入口。

| 场景 | 是否置位 |
|------|---------|
| 管理页点开某条视频进入播放页 | ✅ 该条 |
| 在播放页里上下滑动切换到的视频 | ✅ 每切到一条标一条 |
| 图集（`ImageViewerActivity`） | ❌ 不走播放页 |
| 列表页的网络预览（`createNetworkIntent` / `createListNetworkIntent`） | ❌ 拿不到 aweme id |

链路：`ManageVideoFragment.openVideoPlayer` 把 `awemeIds` 与 `filePaths` 并行传给
`VideoPlayerActivity.createListFileIntent` → 播放页 `loadItemAtIndex` 每次加载都调 `markWatched(index)`
→ `DownloadedVideoRepository.markWatched` 写库（同一 id 一次会话只写一次；用独立
`CoroutineScope(Dispatchers.IO)` 而非 lifecycleScope，滑到下一条后立刻退出也要写完）。
**没传 `EXTRA_LIST_AWEME_IDS` 就完全不写库**，这是区分"管理页"与其他入口的唯一开关。

**UI**：管理页视频卡片在点赞数徽标**右侧**显示「未看过」（`watched == false`）。点开时列表先就地
标掉（`ManageGridAdapter.markWatched`），播放页里滑动看过的那些由 `ManageVideoFragment.onResume`
回查 `getWatchedAwemeIdSet` 补上——两条路径分工，别指望其中一条覆盖全部。
图片 Tab 不显示这个标记（adapter 的 `showWatchedBadge = false`），因为图集永远不会置位。

### 索引

| 索引名 | 列 | 类型 |
|--------|----|------|
| `index_downloaded_videos_awemeId` | `awemeId` | UNIQUE |

---

## 表二：`video_tags`

### 设计思路（方案 C，去规范化关联表）

不设独立的 `tags` 表，标签名直接存储于关联表。优点是结构简单、查询直观；唯一不足是重命名标签需批量 UPDATE，对个人 App 可接受。

### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `awemeId` | TEXT (PK, FK) | 关联 `downloaded_videos.awemeId`，级联删除 |
| `tagName` | TEXT (PK) | 用户自定义标签名称 |

复合主键 `(awemeId, tagName)` 保证同一视频不重复打同一标签。

### 索引

| 索引名 | 列 | 用途 |
|--------|----|------|
| `index_video_tags_tagName` | `tagName` | 按标签筛选视频（高频查询） |

### 常用查询

```sql
-- 列出所有标签（去重）
SELECT DISTINCT tagName FROM video_tags ORDER BY tagName

-- 按标签筛选视频
SELECT v.* FROM downloaded_videos v
INNER JOIN video_tags t ON v.awemeId = t.awemeId
WHERE t.tagName = '舞蹈'
ORDER BY v.createdAtMillis DESC

-- 每个标签的视频数统计
SELECT tagName, COUNT(*) AS count FROM video_tags GROUP BY tagName ORDER BY count DESC
```

**操作入口：** `VideoTagRepository`（封装 `VideoTagDao`）

---

## 表三：`tags`

### 设计思路

独立标签名册，解决"先建标签再打给视频"的需求。与 `video_tags` 配合使用：
- `tags` 管理标签名的生命周期（增删改）
- `video_tags` 管理视频与标签的关联关系

删除标签时需同步删除 `video_tags` 中的关联行（`VideoTagRepository.deleteTag` 负责）。

### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `tagName` | TEXT PK | 标签名，主键唯一 |
| `sortOrder` | INTEGER | 展示排列顺序，数值越小越靠前；用户在标签管理页拖拽后持久化（v8 新增） |

### 预设默认标签（v7 migration 预插入）

`美腿`、`可爱`、`纯欲`、`波霸`、`小沟`、`穿搭`、`舞蹈`、`黑丝`

常量来源：`DefaultTags.list`（`config/DefaultTags.kt`）

### 常用查询

```sql
-- 列出所有可用标签（按用户排序）
SELECT tagName FROM tags ORDER BY sortOrder ASC, tagName ASC

-- 判断标签是否存在
SELECT COUNT(*) FROM tags WHERE tagName = '美腿'
```

**操作入口：** `VideoTagRepository`（`createTag`、`deleteTag`、`renameTag`、`getAvailableTags`）

---

## 标签双表关系总结

```
tags(tagName)          video_tags(awemeId, tagName)
─────────────          ──────────────────────────────
"美腿"          ←──── ("aweme_001", "美腿")
"舞蹈"          ←──── ("aweme_001", "舞蹈")
"黑丝"                 ("aweme_002", "美腿")
"可爱"   ← 未使用，但存在于名册，可供选择
```

| 方法 | 说明 |
|------|------|
| `getAvailableTags()` | `tags` 表全量（含未使用）→ 打标签 UI 用 |
| `getAllUsedTags()` | `video_tags` 去重 → 已使用标签统计 |
| `getTagsWithCount()` | 标签 + 视频数 → 管理页统计展示 |
| `getVideosByTag(tag)` | 按标签筛选视频 → 管理页过滤 |

---

## 关联常量与配置

| 位置 | 内容 |
|------|------|
| `AppConfig.MY_SEC_USER_ID` | App 所有者抖音账号的 `sec_user_id`（从主页 URL 提取，硬编码） |
| `DefaultTags.list` | 预设标签名列表（v7 migration 数据源，同时作为 `getAvailableTags` 排序基准） |
| `DownloadSourceType` | `downloadType` 字段的所有合法枚举值 |
| `DownloadMediaType` | `mediaType` 字段的合法值（`"video"` / `"image"`） |

---

## 下一步开发提示

- **管理页展示**：`userRelation` 按 `|` 拆分渲染 chip；`videoAuthorSecUserId` 用于按作者分组/过滤。
- **下载写入时**：调用 `DownloadedVideoRepository.recordSuccessfulDownload()`，`like` 场景传 `buildUserRelationFromLike(aweme.collectStat)`，`collects` 场景传 `buildUserRelationFromCollection(aweme.userDigged, folderName)`。
- **标签功能**：通过 `VideoTagRepository` 操作，视频删除时标签自动级联删除，无需手动清理。
- **新增数据库字段**：当前版本为 **v13**，下次变更需在 `AppDatabase` 中新增 `MIGRATION_13_14` 并将 version 改为 14。
