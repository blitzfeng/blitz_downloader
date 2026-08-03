# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

Android 应用（Kotlin），用于批量下载抖音视频与图集。实现思路刻意对齐 Python 项目 [F2](https://github.com/Johnserf-Seed/f2)：**纯 HTTP API 模拟**（Retrofit/OkHttp + 签名查询参数 + Cookie），而非 WebView DOM 抓取。WebView 仅作为登录界面以及 Cookie / UA 的引导来源。

单 module `:app`，包名 `com.blitz.downloader`，applicationId 同上。minSdk 24 / target 与 compile SDK 36，Kotlin + Java 11，启用 View Binding，Room 走 KSP 处理。版本号锁定在 `gradle/libs.versions.toml`；零散依赖（cardview、viewpager2、fragment-ktx、lifecycle、okhttp 4.12、retrofit 2.9、gson 2.11、coil 2.7、kotlinx-coroutines 1.9）直接写在 `app/build.gradle.kts` 里。

## 构建与运行

仓库使用 Gradle Wrapper。Windows 用 `gradlew.bat`，POSIX shell 用 `./gradlew`。

```bash
./gradlew assembleDebug              # 构建 debug APK
./gradlew installDebug               # 构建并安装到已连接设备
./gradlew assembleRelease            # 构建 release APK（同一把签名，见下）
./gradlew test                       # JVM 单元测试（app/src/test）
./gradlew connectedAndroidTest       # 设备/模拟器上的仪器化测试
./gradlew testDebugUnitTest --tests "com.blitz.downloader.api.AwemeMapperTest"
./gradlew lint                       # Android Lint
./gradlew clean
```

JVM 单元测试目前只有 5 个，都是纯 JVM、不读外部 fixture：`AwemeMapperTest`、`DouyinListApiModelsTest`、`DouyinUrlParserParseTest`、`signing/Sm3Test`、`download/BatchDownloadCoordinatorTest`。改 mapper / 解析 / 签名时优先扩这几个，别引入 Robolectric。

`local.properties` 由本地生成（SDK 路径），不提交。`release` 构建类型当前 `isMinifyEnabled = false`；ProGuard 规则在 `app/proguard-rules.pro`。**debug 与 release 共用仓库根目录的 `keystore`**（`app/build.gradle.kts` 里两个 signingConfig 指向同一把 key），所以两种包可以互相覆盖安装、不会因签名变化丢数据库——但换签名/重装仍会让备份文件在 MediaStore 被孤儿化（见下方"持久化"）。

## 整体架构

```
ui (Fragments / Adapters)        activity (MainActivity, DouyinWebBrowserActivity,
   │                                       ManageActivity, TagManageActivity,
   │                                       SettingsActivity,
   │                                       VideoPlayerActivity, ImageViewerActivity)
   ▼
download (BatchDownloadCoordinator, DouyinVideoHttp, MediaExportManager,
          DownloadService — 前台下载服务, DownloadJob/DownloadRecordMeta,
          DownloadEvents — 进程内下载完成事件总线)
   │
   ├─▶ net (LanFileServer — 「发送到电脑」局域网导出)
   ▼
api (DouyinApiClient, DouyinApiService, DouyinApiModels, DouyinParser, DouyinListApi,
     AwemeMapper, DouyinUrlParser, AwemeWebUrls)
   │            ▲
   │            │
   ▼            │
api/signing (DouyinWebSigner, XBogusSigner, ABogusSigner, Sm3)
   │
   ▼
util (DouyinCookieStore, DouyinCookieSync, DouyinTokenBootstrap, DouyinVerifyFpGenerator, UrlUtils)

data + data/db (Room: AppDatabase, DownloadedVideoEntity/Dao, TagEntity/Dao, VideoTagEntity/Dao
                + DownloadedVideoRepository, VideoTagRepository, DatabaseBackupManager)

config (AppConfig — 编译期常量, AppSettings — 运行时用户偏好,
        BatchListDownloadScope, DefaultTags)
```

`BlitzApp`（Application）持有 `AppDatabase` 与两个 Repository 的单例。需要时统一通过 `BlitzApp.instance.downloadedVideoRepository` / `videoTagRepository` 获取，**不要**自行 new。

### F2 对齐 — 必读

`API_IMPLEMENTATION.md`、`IMPLEMENTATION_SUMMARY.md`、`INTEGRATION_GUIDE.md` 是这个 F2 移植项目的架构说明。下表的对应关系是刻意维护、且具有约束力的：

| F2 (Python)                | 本仓库 (Kotlin)                            |
|----------------------------|--------------------------------------------|
| `aiohttp` 客户端           | `DouyinApiClient`（OkHttp + Retrofit）     |
| `f2/apps/douyin/api.py`    | `DouyinApiService`（Retrofit 接口）        |
| `f2/apps/douyin/model.py`  | `DouyinApiModels`（Gson 数据类）           |
| `f2/apps/douyin/handler.py`| `DouyinParser`、`DouyinListApi`            |
| `algorithm/`（X-Bogus 等） | `api/signing/`（`XBogusSigner`、`ABogusSigner`、`Sm3`、`DouyinWebSigner`） |
| `f2/dl/base_downloader.py` | `BatchDownloadCoordinator`（`retry_attempts = 3`）|

新增或修复接口时，**先看 F2 对应文件**：`retry_attempts`、默认 `count`、分页大小、超时、参数集合等常量是有意保持一致的。

### 签名与 UA — 必须匹配

`DouyinApiClient.HeaderInterceptor` 按接口选择 `User-Agent`：

- `/aweme/v1/web/aweme/favorite` → `webUserAgentFavorite`（Edge 130，对应 F2 `BaseRequestModel` 默认值；该接口要求 `browser_name=Edge` 且仅含 `a_bogus`）。
- 其他 → `webUserAgent`（Chrome 119）。

签名（`X-Bogus` / `a_bogus`）与计算它所用的 UA **绑定**。**改一处 UA 必须同时改签名侧的 UA**——UA 不一致最常见的现象是 HTTP 200 但响应体为空，是这套代码里最隐蔽的失败模式。

### Cookie 管道

登录与 Cookie 获取都发生在 WebView 里（`DouyinWebBrowserActivity` 与 `ListDownloadFragment`）。流向：

```
WebView（登录 / 加载 www.douyin.com）→ CookieManager → DouyinCookieStore / DouyinCookieSync
                                                    → DouyinApiClient.globalCookie（以及 msToken / ttwid / webId / verifyFp）
```

`BatchListDownloadScope` 定义了批量列表下载的范围契约：

- `PRIMARY_TARGET_IS_LOGGED_IN_LISTS = true`：**主场景**是需登录的列表（喜欢 / 收藏 / 收藏夹）。
- `SUPPORTS_PUBLIC_GUEST_LIST_BATCH = true`：访客可访问的公开列表（公开主页、公开合集）作为并行支持的路径保留，**不要**删除处理这条路径的代码。
- 遇到 403 / 419 / 空包时，正确做法是回到 WebView 重新登录或刷新页面后重新同步 Cookie，**不要**通过硬编码 token 绕过。
- 这类失效已在接口层统一识别：`DouyinListApi` 对 HTTP 401/403/419 与「200 空包」抛 `DouyinAuthException`；`ListDownloadFragment.handleListLoadError` 据此弹「重新登录 / 同步 Cookie」引导，并支持登录返回后自动重试（`awaitingLoginRetry`）。新增列表接口时沿用 `dynamicGetBody` 的失效判定，不要退化成普通 Toast。

`AppConfig.MY_SEC_USER_ID` 是当前 App 所有者抖音账号的 `sec_user_id`，用于把"我的账户"列表（喜欢/收藏）下载下来的记录与"他人主页帖子"的记录区分开（写入 `sourceOwnerSecUserId`）。换账号时同步更新这个常量。

### 下载管道

`BatchDownloadCoordinator` 处理被勾选的 `VideoItemUiModel` 列表，受限并发（`DEFAULT_MAX_CONCURRENT = 3`），单项重试（`DEFAULT_MAX_RETRIES = 3`，与 F2 一致）。文件落到公共 `Download/` 下：

- `Download/bDouyin/videos/` — 视频 mp4
- `Download/bDouyin/images/` — 图集图片
- `Download/bDouyin/covers/` — 视频封面缩略图（图集复用第一张图作为封面，不重复写）

文件命名约定为 `<userName>+<desc>`（截断/转义后）。下载成功后必须调用 `DownloadedVideoRepository.recordSuccessfulDownload(...)` 写入数据库，否则管理页角标无法识别为"已下载"。

下载完成后的界面回刷走 `DownloadEvents`（进程内 `SharedFlow`，`replay = 0`）：服务写库成功后广播 aweme id 集合，仍停在列表页的 `ListDownloadFragment` 就地把对应项标为已下载并取消勾选；页面销毁期间错过的事件由 `onResume` 的整表回查（`reapplyDownloadedFlagsToList`）兜底。**不要**给它加 replay 缓存来"修"漏事件，两条路径是有意分工的。

**批量下载在前台服务里执行**（`DownloadService`，`foregroundServiceType=dataSync`），而非 Fragment 协程——离开页面 / 应用退到后台都不中断，进度走通知栏。链路：`ListDownloadFragment` 预先算好每项的 `DownloadRecordMeta`（入库所需字段快照）→ 组成 `DownloadJob` → `DownloadService.start(...)`（大列表经**同进程内存队列**交接，不走 Intent 序列化）→ 服务内调 `BatchDownloadCoordinator.downloadSelected(..., onItemDone=...)` 下载并更新通知 → **成功项在服务内自行 `recordSuccessfulDownload` + 收藏夹标签关联**。不做跨进程重启的断点续传。改动写库逻辑时注意它现在有两处调用点的等价性（服务内 vs. 直接调用）。

### 导出管道（管理页「导出选中」）

多选后有两条导出路径，共用 `MediaExportManager.resolveExportFiles(...)` 把选中记录解析成磁盘文件：

- **ZIP 导出**：`MediaExportManager.exportToZip(...)` 打包到 `Download/bDouyin/export/bDouyin_export_<时间戳>.zip`（`Deflater.NO_COMPRESSION` 只打包不二次压缩），完成后 `MediaScannerConnection.scanFile` 触发扫描,使其立刻能通过 MTP 看到。手机连电脑拷这一个文件。
- **局域网导出**：`net.LanFileServer`（零依赖手写 HTTP/1.1，仅 GET）。电脑同 WiFi 用浏览器打开 `http://<ip>:<port>/` 逐个或 `/all.zip` 打包下载。服务生命周期由 `ManageActivity` 持有，`onDestroy` / 对话框 dismiss 时 `stop()`。

关键约束：**图集的 `filePath` 只存了第一张图（`base_01.jpg`）**，导出时必须扫描同目录 `base_\d+.<ext>` 的全部兄弟文件——逻辑与 `ImageViewerActivity.findImageSet` 一致，改一处需同步。

导出计数（`exportCount`，v11）：**只有局域网导出会累加**——`LanFileServer` 把某条记录字节完整写出 socket 后回调 `TransferEvent`，由 `DownloadedVideoRepository.incrementExportCount(...)` 做 `SET exportCount = exportCount + 1` 的原子累加（**不要**改成"读实体→改→整行 update"，并发写会互相覆盖）。ZIP 导出、`HEAD` 探测、中途断连都不累加。它只表示"手机已完整发出"，不代表电脑落盘，只作提示与二次确认依据。语义细节见 `.cursor/rules/db-schema.md` 的 exportCount 小节。

### 管理页的筛选栈

`ManageActivity` 只管 Toolbar / 菜单 / 抽屉，取数与筛选都在 `ManageTabFragment` 的两个实现（`ManageVideoFragment`、`ManageImageFragment`）里。五层筛选是**叠加**关系，不是互斥的单选：

| 层 | 状态载体 | 生效位置 |
|----|----------|----------|
| 搜索（作者昵称） | `activeSearchQuery` | SQL |
| 作者（`sec_user_id` 优先，回退昵称） | `activeAuthorSecId` / `activeAuthorName` | SQL |
| 标签多选 | `activeTags` + `AppSettings.isTagFilterMatchAll` | SQL（`getVideosByTags(tags, matchAll)`） |
| 归属（点赞 / 收藏 / 收藏夹 / 无归属） | `ManageRelationFilter` | 分页路径下沉 SQL，其余走 `apply()` |
| 标签数量（0..5+） | `ManageTagCountFilter` | 只在内存（`postProcess`） |

几条别踩的规则：

- 标签多选默认取**交集**（`AppSettings.isTagFilterMatchAll` 默认 true，设置页可切并集）。`AppSettings` 是运行时偏好、每次 getter 直读 SharedPreferences 不做内存缓存——**别加缓存**，否则设置页改完别的页面拿到旧值。
- 「标签数量筛选」没有 SQL 实现，一激活就必须切成全量加载：否则"一页 20 条筛剩 2 条、撑不满屏幕不触发滚动加载"看起来就像数据丢了。
- 标签数量筛选**隶属于归属筛选之下**：归属为 OFF 时仍按 `EXCLUDE_UNASSIGNED` 收窄，只有用户显式选「仅看无归属」才听用户的。原因是他人主页 post 记录基本没打过标签，不排掉「0 个标签」就全是它们。
- 非分页路径（搜索 / 标签 / 作者 / 全选）统一走 `postProcess(...)` = 归属 → 标签数 → 排序。新增取数入口别绕过它，否则筛选会"漏一层"。

### 持久化（Room）

**数据库结构的权威文档是 `.cursor/rules/db-schema.md`，改 `data/db/` 之前先读它。** 要点：

- `AppDatabase` 当前 **version = 11**（v11 新增 `exportCount`）。三张表：`downloaded_videos`、`video_tags`、`tags`。
- 所有迁移 `MIGRATION_1_2 .. MIGRATION_10_11` 都在 `AppDatabase` 里显式列出。builder 上虽然还挂着 `fallbackToDestructiveMigration()` 作兜底，但**不要**依赖它来"对付过去"——漏写迁移 = 用户数据被清空。新增字段时：写 `MIGRATION_11_12` → `version = 12` → `addMigrations(...)` 注册 → 同步更新 `.cursor/rules/db-schema.md`（新增列与版本行）。
- 备份/恢复走 `DatabaseBackupManager`（入口在 `SettingsActivity` 设置页，由 MainActivity Toolbar 的设置图标进入；UI 逻辑都在 `SettingsActivity` 里，管理页菜单已不再有这两项）——它直接操作 SQLite 文件，改库结构后要确认导入旧备份的兼容处理。备份写到公共 `Download/bDouyin/backup`（存活于卸载、可 MTP 看到）。**恢复有两条路径**：`restoreFrom(entry)` 直接 File 读取，但**重装/换签名后备份文件在 MediaStore 被孤儿化**（owner 清空 + `.db` 属非媒体，`READ_MEDIA_*` 不覆盖）会抛 `SecurityException`；此时 UI 引导改用 SAF（`ACTION_OPEN_DOCUMENT` → `restoreFromStream`），授权 Uri 绕过归属校验。别把恢复退回成"只 File 读取"。
- `userRelation` 是 `|` 分隔的多标签字符串（`"like"`、`"like|<夹名>"`、`"<夹名>"`）；写入时统一通过 `DownloadedVideoRepository.buildUserRelationFromLike(...)` / `buildUserRelationFromCollection(...)` 构造，**不要**手拼。
- `DefaultTags.list` 是预设标签的唯一来源，也是 v6→v7 / v7→v8 迁移插入的内容；改 `DefaultTags.kt` 的同时检查迁移逻辑是否还一致。
- `downloadType` 合法值见 `DownloadSourceType`；`mediaType` 见 `DownloadMediaType`（`"video"` / `"image"`）。
- 管理页排序走 `getPageByMediaTypeSorted(...)`（`@RawQuery` + `SimpleSQLiteQuery`），排序列名来自固定枚举 `ManageSortOrder`（`createdAtMillis`/`createTime`/`diggCount`），**非用户输入，不接受任意字符串**，别改成拼用户串。筛选/全选等非分页路径在内存里按同一 `ManageSortOrder` 重排。统计面板用 `getAuthorCountsAll()` 与 `VideoTagDao.getTagsWithCount()` 聚合，占用空间由 `dirSize` 遍历 `Download/bDouyin/{videos,images,covers}` 求和。

### 接口响应样本

仓库根目录的 **`apiData/`**（`like.json`、`new_like.json`、`new_like_no_collect.json`、`collections.json`、`collection_no_like.json`、`collect_fold.json`、`post.json`、`photos.json`）是真实抓包下来的抖音接口响应，作为 **parser/mapper 调试的参考样本**，不是运行时资源、也不被单元测试加载——调整 `AwemeMapper` 或 `DouyinApiModels` 时对照对应 json 手工核字段。**不要**删。带 `no_like` / `no_collect` 后缀的是"未点赞/未收藏"对照样本，用于验证 `userRelation` 的编码分支。

## 约定 / 容易踩的坑

- 不要再引入新的"WebView DOM 抽取"式解析。新接口走 `DouyinApiService` + `DouyinParser`/`DouyinListApi` + 签名层。`api/DouyinSignatureGenerator.kt` 是早期"WebView 跑页面 JS 算签名"的**遗留占位类，当前无任何调用方**；现役签名一律在 `api/signing/`。别照着它写新代码，也别以为改它能影响请求。
- 日志里不要打印原始 Cookie 或 `msToken`——这些等同于完整账号权限。登录成功的 toast 已经主动做了脱敏，新增日志保持同样标准。
- `BatchDownloadCoordinator` 与 `DouyinList*` 层的并发数 / 重试 / 分页常量是为了对齐 F2 而调的，改之前先确认动机，不要随手调。
- 分页：列表接口返回 `has_more` + `max_cursor`。已知问题"接口返回 20 条但 UI 只显示十几条"是 Fragment + Adapter 层的过滤 / 去重 / mapper 问题，**不是**网络层——优先查那一层再怀疑网络。
- Kotlin/JVM target 是 **11**；**没有**用 Compose，UI 全部是 XML + View Binding + Fragment。
- 图片加载用 **Coil 2.7**，不是 Glide。

## 项目计划文件

- `.cursor/plans/f2-style-batch-prereqs_d02563bb.plan.md`：F2 移植剩余工作的单一事实来源；YAML frontmatter 中的 `todos[]` 含 `status`。
- `.cursor/CONTINUATION.md`：上面 todos 的可读索引，每条带"Agent 提示词"，用于跨机器恢复上下文。
- `.cursor/rules/db-schema.md`：数据库结构参考（见上）。

完成对应 todo 的任务后，更新 plan 文件 frontmatter 的 `status`，必要时同步刷新 `.cursor/CONTINUATION.md`。
