# BlitzDownloader 数据库设计文档

> **当前版本：v8**
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
| v8 | `tags` 表新增 `sortOrder` 列，支持用户自定义标签排列顺序 |

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
| `desc` | TEXT | `""` | 作者发布时的文案/标题（接口字段 `desc`） |
| `collectionType` | TEXT | `""` | `downloadType="collects"` 时填收藏夹名称 |
| `collectId` | TEXT | `""` | `downloadType="collects"` 时填收藏夹稳定 ID（与 `collectionType` 对应） |
| `videoAuthorSecUserId` | TEXT | `""` | 视频创作者的稳定 `sec_user_id`（不受改名影响，用于管理页按作者过滤） |
| `sourceOwnerSecUserId` | TEXT | `""` | 下载来源账户的 `sec_user_id`（见下方填写规则） |
| `userRelation` | TEXT | `""` | 视频与账户所有者的关系标签（仅我的账户有效，见下方编码规则） |

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
- **新增数据库字段**：当前版本为 **v8**，下次变更需在 `AppDatabase` 中新增 `MIGRATION_8_9` 并将 version 改为 9。
