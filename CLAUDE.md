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
activity (MainActivity, DouyinWebBrowserActivity, ManageActivity, TagManageActivity,
          SettingsActivity, VideoPlayerActivity, ImageViewerActivity)
fragment (ListDownloadFragment, SingleDownloadFragment, ManageVideoFragment, ManageImageFragment)
adapter  (VideoGridAdapter, ManageGridAdapter, TagManageAdapter, TagFilterAdapter, AuthorFilterAdapter)
dialog   (PhotoSelectionBottomSheet)
   │  ▲  视图层：只渲染 uiState、转发用户操作、弹对话框与跳转
   ▼  │
viewmodel (ListDownloadViewModel, ManageViewModel — Activity 级,
           ManageTabViewModel → ManageVideoViewModel / ManageImageViewModel,
           SettingsViewModel, TagManageViewModel)
   │       业务层：网络请求、数据库读写、分页与筛选、状态持有
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
util (DouyinCookieStore, DouyinCookieSync, DouyinTokenBootstrap, DouyinVerifyFpGenerator,
      UrlUtils, NumberFormatUtils, MediaPermissions, DiggBadgeStyle)

data + data/db (Room: AppDatabase, DownloadedVideoEntity/Dao, TagEntity/Dao, VideoTagEntity/Dao
                + DownloadedVideoRepository, VideoTagRepository, DatabaseBackupManager)

model (VideoItemUiModel, ManageGridItem)
model/filter (ManageFilterState, ManageRelationFilter, ManageSortOrder,
              ManageTagCountFilter, ManageTagEditCountFilter)

config (AppConfig — 编译期常量, AppSettings — 运行时用户偏好,
        BatchListDownloadScope, DefaultTags)
```

`BlitzApp`（Application）持有 `AppDatabase` 与两个 Repository 的单例。需要时统一通过 `BlitzApp.instance.downloadedVideoRepository` / `videoTagRepository` 获取，**不要**自行 new。

### 包结构约定 — 新增类放哪

包按**职责**划分，不按功能模块。新增类时对照下表，**不要**再往某个"万能包"里堆：

| 包 | 放什么 | 不放什么 |
|----|--------|----------|
| `activity/` | Activity | 取数逻辑 |
| `fragment/` | Fragment | Adapter、对话框构造器 |
| `adapter/` | RecyclerView Adapter / ViewHolder | 数据模型 |
| `dialog/` | 对话框与 BottomSheet 的构造器 | 页面级 Fragment |
| `viewmodel/` | ViewModel 及其 UiState / Event / Command 类型 | Android View 引用、`R.string` 拼接 |
| `model/` | 跨层数据模型 | 只有一个 Adapter 用的私有类型 |
| `model/filter/` | 筛选与排序的枚举、筛选状态 | 筛选的执行逻辑（在 ViewModel 里） |
| `util/` | 无状态工具与样式常量 | 有生命周期的对象 |
| `api/` `download/` `data/` `net/` `config/` | 见上方架构图 | UI 相关的一切 |

几条硬约定：

- **ViewModel 只承载网络与数据库操作**（以及它们的直接前后处理：分页状态、筛选组合、入库元数据组装）。纯视图状态（控件显隐、对话框、滚动位置）留在 Activity / Fragment。
- **ViewModel 不碰 `R.string`**：状态用结构化类型表达（`ListStatus`、`ManageEmptyReason`、`ManageStats`），字符串拼接一律在视图层。这样以后加多语言不用动 ViewModel。
- **ViewModel 不持 Activity / Fragment / View 引用**，一律 `AndroidViewModel` + `getApplication()`。
- 列表数据走 `StateFlow` + `repeatOnLifecycle(STARTED)`；导航 / Toast / 对话框请求这类一次性动作走 `SharedFlow(replay = 0)`，与既有的 `DownloadEvents` 保持同一套事件模型。**不要**给一次性事件加 replay。
- Adapter **不持有状态权威**：列表数据与多选状态都由 ViewModel 决定，经 `submitItems` / `submitSelection` 写入，交互一律以回调上报。

### 本次架构改造**没有**做的事

避免误读成"已经是完整 MVVM"：

- **没有依赖注入**：ViewModel 直接 `BlitzApp.instance.xxxRepository`，没有接口、没有容器、没有 Hilt。
- **没有为 ViewModel 写测试**，也没有加测试依赖。`app/src/test` 仍是原来那 5 个纯 JVM 测试。
- **`libs.versions.toml` 里 `junit = "4.14-SNAPSHOT"` 是快照版，当前已解析不到**——`assembleDebug` 正常，但 `./gradlew test` / `compileDebugUnitTestKotlin` 会失败在依赖解析。要跑测试先把它钉到 `4.13.2`。
- **`api/` `download/` `data/` `net/` 的内部逻辑一行未改**，只被动更新了 import 与 KDoc 路径。`DownloadService` 里仍自建 `VideoTagRepository(applicationContext)`（用的是 application context，无泄漏）。
- **未抽 ViewModel 的页面**：`SingleDownloadFragment`（无 IO）、`ImageViewerActivity`、`DouyinWebBrowserActivity`（Cookie 同步紧贴 WebView 回调）、`VideoPlayerActivity`（只有一处写库）、`MainActivity`。
- **`AppSettings` 仍由 `SettingsActivity` 直接读写**（SharedPreferences 不属于"网络与数据库操作"），且必须保持每次 getter 直读、不加缓存。

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
- 这类失效已在接口层统一识别：`DouyinListApi` 对 HTTP 401/403/419 与「200 空包」抛 `DouyinAuthException`；`ListDownloadViewModel.handleListLoadError` 据此发事件让 Fragment 弹「重新登录 / 同步 Cookie」引导，并支持登录返回后自动重试。新增列表接口时沿用 `dynamicGetBody` 的失效判定，不要退化成普通 Toast。
- `awaitingLoginRetry` 与待重试动作**必须留在 ViewModel**：跳 WebView 登录期间 Fragment 可能被系统回收，放在 Fragment 里会连同标志一起丢失，自动重试就静默失效了（这正是它原先的 bug）。

`AppConfig.MY_SEC_USER_ID` 是当前 App 所有者抖音账号的 `sec_user_id`，用于把"我的账户"列表（喜欢/收藏）下载下来的记录与"他人主页帖子"的记录区分开（写入 `sourceOwnerSecUserId`）。换账号时同步更新这个常量。

### 下载管道

`BatchDownloadCoordinator` 处理被勾选的 `VideoItemUiModel` 列表，受限并发（`DEFAULT_MAX_CONCURRENT = 3`），单项重试（`DEFAULT_MAX_RETRIES = 3`，与 F2 一致）。文件落到公共 `Download/` 下：

- `Download/bDouyin/videos/` — 视频 mp4
- `Download/bDouyin/images/` — 图集图片
- `Download/bDouyin/covers/` — 视频封面缩略图（图集复用第一张图作为封面，不重复写）

文件命名约定为 `<userName>+<desc>`（截断/转义后）。下载成功后必须调用 `DownloadedVideoRepository.recordSuccessfulDownload(...)` 写入数据库，否则管理页角标无法识别为"已下载"。

下载完成后的界面回刷走 `DownloadEvents`（进程内 `SharedFlow`，`replay = 0`）：服务写库成功后广播 aweme id 集合，`ListDownloadViewModel` 在 `init` 里收集并就地把对应项标为已下载、取消勾选；页面销毁期间错过的事件由 `onResume` → `ListDownloadViewModel.onScreenResumed()` 的整表回查兜底。**不要**给它加 replay 缓存来"修"漏事件，两条路径是有意分工的；也**不要**因为"ViewModel 活得比 Fragment 久、不会漏事件了"就删掉回查那条——进程被杀的场景事件不会补发。

**批量下载在前台服务里执行**（`DownloadService`，`foregroundServiceType=dataSync`），而非页面协程——离开页面 / 应用退到后台都不中断，进度走通知栏。链路：`ListDownloadViewModel` 预先算好每项的 `DownloadRecordMeta`（入库所需字段快照）→ 组成 `DownloadJob` → `DownloadService.start(...)`（大列表经**同进程内存队列**交接，不走 Intent 序列化）→ 服务内调 `BatchDownloadCoordinator.downloadSelected(..., onItemDone=...)` 下载并更新通知 → **成功项在服务内自行 `recordSuccessfulDownload` + 收藏夹标签关联**。不做跨进程重启的断点续传。改动写库逻辑时注意它现在有两处调用点的等价性（服务内 vs. 直接调用）。

选中状态的权威是 `ListDownloadViewModel` 的 `selectedIds` / `imageSelections`；`VideoItemUiModel.isSelected` 与 `selectedImageIndices` 只在 `compose()` 里合成出来——一份给 Adapter 渲染，一份喂给 `BatchDownloadCoordinator`（它靠 `isSelected` 筛选待下载项）。这样 `download/` 无需任何改动。

### 导出管道（管理页「导出选中」）

多选后有两条导出路径，共用 `MediaExportManager.resolveExportFiles(...)` 把选中记录解析成磁盘文件：

- **ZIP 导出**：`MediaExportManager.exportToZip(...)` 打包到 `Download/bDouyin/export/bDouyin_export_<时间戳>.zip`（`Deflater.NO_COMPRESSION` 只打包不二次压缩），完成后 `MediaScannerConnection.scanFile` 触发扫描,使其立刻能通过 MTP 看到。手机连电脑拷这一个文件。
- **局域网导出**：`net.LanFileServer`（零依赖手写 HTTP/1.1，仅 GET）。电脑同 WiFi 用浏览器打开 `http://<ip>:<port>/` 逐个或 `/all.zip` 打包下载。**服务生命周期由 `ManageViewModel` 持有**，`onCleared()` / 对话框 dismiss 时 `stop()`——放在 ViewModel 里是为了让转屏不掐断电脑那边正在下载的文件（原先由 Activity 持有、`onDestroy` 即停）。

关键约束：**图集的 `filePath` 只存了第一张图（`base_01.jpg`）**，导出时必须扫描同目录 `base_\d+.<ext>` 的全部兄弟文件——逻辑与 `ImageViewerActivity.findImageSet` 一致，改一处需同步。

导出计数（`exportCount`，v11）：**只有局域网导出会累加**——`LanFileServer` 把某条记录字节完整写出 socket 后回调 `TransferEvent`，由 `DownloadedVideoRepository.incrementExportCount(...)` 做 `SET exportCount = exportCount + 1` 的原子累加（**不要**改成"读实体→改→整行 update"，并发写会互相覆盖）。ZIP 导出、`HEAD` 探测、中途断连都不累加。它只表示"手机已完整发出"，不代表电脑落盘，只作提示与二次确认依据。语义细节见 `.cursor/rules/db-schema.md` 的 exportCount 小节。

### 管理页的筛选栈

`ManageActivity` 只管 Toolbar / 菜单 / 抽屉 / 对话框；**筛选条件与多选状态的权威在 Activity 级的 `ManageViewModel`**（按 Tab 独立维护），取数在 `ManageTabViewModel` 的两个实现（`ManageVideoViewModel`、`ManageImageViewModel`）里。

Activity 与两个 Tab **不再直接互相引用**（旧实现靠 `findFragmentByTag("f$position") as? ManageTabFragment`，依赖 ViewPager2 的内部 tag 命名约定，不受 API 保证）。三条通路：

- **条件下行**：Activity 改 `ManageViewModel.filters` / `.selection` → Fragment 观察自己那一份 → 转发给自己的 `ManageTabViewModel`（两个 ViewModel 互不认识）。
- **动作下行**：Activity 发 `ManageCommand`（带目标 tab）→ 对应 Fragment 消费后在自己的 ViewModel 上执行。
- **数据上行**：Fragment 每次列表变化调 `ManageViewModel.setLoaded(tab, entities, hasMore)`，Activity 侧据此算「是否已全选」与「选中了哪些实体」。

六层筛选是**叠加**关系，不是互斥的单选，全部收敛在 `ManageFilterState` 里：

| 层 | `ManageFilterState` 字段 | 生效位置 |
|----|----------|----------|
| 搜索（作者昵称） | `searchQuery` | SQL |
| 作者（`sec_user_id` 优先，回退昵称） | `authorSecId` / `authorName` | SQL |
| 标签多选 | `tags` + `AppSettings.isTagFilterMatchAll` | SQL（`getVideosByTags(tags, matchAll)`） |
| 归属（点赞 / 收藏 / 收藏夹 / 无归属） | `relation` | 分页路径下沉 SQL，其余走 `apply()` |
| 标签数量（0..5+，**可多选取并集**） | `tagCounts`（空集 = 不筛选） | 只在内存（`postProcess`） |
| 标签修改次数（0..5 / >5） | `tagEditCount` | 只在内存（`postProcess`，读 `tagEditCount`） |

作者 / 搜索 / 标签三者互斥的清理规则收敛在 `ManageFilterState.withAuthor` / `withSearchQuery` / `withTags`，**不要**在调用处手动清另外两个。

几条别踩的规则：

- 标签多选默认取**交集**（`AppSettings.isTagFilterMatchAll` 默认 true，设置页可切并集）。`AppSettings` 是运行时偏好、每次 getter 直读 SharedPreferences 不做内存缓存——**别加缓存**，否则设置页改完别的页面拿到旧值。
- 标签数量筛选是**多选取并集**（`Set<ManageTagCountFilter>`，空集 = 不筛选，判定走 `matchesAny`），与标签栏多选默认取交集不是一回事，别混。标签修改次数筛选仍是单选。
- 「标签数量筛选」「标签修改次数筛选」都没有 SQL 实现，任一激活就必须切成全量加载（`ManageFilterState.hasMemoryOnlyFilter`）：否则"一页 20 条筛剩 2 条、撑不满屏幕不触发滚动加载"看起来就像数据丢了。
- 这两层都**隶属于归属筛选之下**（共用 `scopeByRelation`）：归属为 OFF 时仍按 `EXCLUDE_UNASSIGNED` 收窄，只有用户显式选「仅看无归属」才听用户的。原因是他人主页 post 记录基本没打过标签、更没改过，不排掉「0 个标签」「改过 0 次」就全是它们。
- 非分页路径（搜索 / 标签 / 作者 / 全选）统一走 `ManageTabViewModel.postProcess(...)` = 归属 → 标签数 → 标签修改次数 → 排序。新增取数入口别绕过它，否则筛选会"漏一层"。

### 持久化（Room）

**数据库结构的权威文档是 `.cursor/rules/db-schema.md`，改 `data/db/` 之前先读它。** 要点：

- `AppDatabase` 当前 **version = 13**（v13 新增 `watched`）。三张表：`downloaded_videos`、`video_tags`、`tags`。
- 所有迁移 `MIGRATION_1_2 .. MIGRATION_12_13` 都在 `AppDatabase` 里显式列出。builder 上虽然还挂着 `fallbackToDestructiveMigration()` 作兜底，但**不要**依赖它来"对付过去"——漏写迁移 = 用户数据被清空。新增字段时：写 `MIGRATION_13_14` → `version = 14` → `addMigrations(...)` 注册 → 同步更新 `.cursor/rules/db-schema.md`（新增列与版本行）。
- `watched`（是否已看过）只由**管理页进入视频播放页**置位：`ManageVideoViewModel.openVideoPlayer` 把 `awemeIds` 随 `createListFileIntent` 传给播放页，播放页每加载一条就写库（含上下滑动切到的）。列表侧「未看过」标记的刷新分两条路：点开那条就地标掉，滑动看过的靠 `ManageVideoFragment.onResume` → `refreshWatchedFlags()` 回查——**别把其中一条删掉当冗余**，也别指望 ViewModel 的 `init` 或 StateFlow 自动收集能替代 `onResume` 那条（ViewModel 不随 `onResume` 重建）。
- 备份/恢复走 `DatabaseBackupManager`（入口在 `SettingsActivity` 设置页，由 MainActivity Toolbar 的设置图标进入；实际读写在 `SettingsViewModel`，对话框与「恢复后重启进程」留在 `SettingsActivity`；管理页菜单已不再有这两项）——它直接操作 SQLite 文件，改库结构后要确认导入旧备份的兼容处理。备份写到公共 `Download/bDouyin/backup`（存活于卸载、可 MTP 看到）。**恢复有两条路径**：`restoreFrom(entry)` 直接 File 读取，但**重装/换签名后备份文件在 MediaStore 被孤儿化**（owner 清空 + `.db` 属非媒体，`READ_MEDIA_*` 不覆盖）会抛 `SecurityException`；此时 UI 引导改用 SAF（`ACTION_OPEN_DOCUMENT` → `restoreFromStream`），授权 Uri 绕过归属校验。别把恢复退回成"只 File 读取"。
- `userRelation` 是 `|` 分隔的多标签字符串（`"like"`、`"like|<夹名>"`、`"<夹名>"`）；写入时统一通过 `DownloadedVideoRepository.buildUserRelationFromLike(...)` / `buildUserRelationFromCollection(...)` 构造，**不要**手拼。
- 打标签有**两组入口**，别混用：程序自动用 `VideoTagRepository.setTags` / `addTags` / `ensureCollectFolderTagLinked`（只写 `video_tags`）；**UI 上的用户编辑一律走 `setTagsAsUserEdit` / `addTagsAsUserEdit`**，它们额外给 `downloaded_videos.tagEditCount` 累加（一次编辑算一次，集合没变化不计）。新增标签编辑入口时用错会让「改过几次」的统计漏计或虚增。
- 多选后「设置标签」弹窗还有个「仅次数 +1」（`bumpTagEditCountManually`）：只累加计数不动标签，用来补 v12 之前没记录的历史数据。它是无条件 `+1`、没有幂等标记，靠二次确认兜底。
- `DefaultTags.list` 是预设标签的唯一来源，也是 v6→v7 / v7→v8 迁移插入的内容；改 `DefaultTags.kt` 的同时检查迁移逻辑是否还一致。
- `downloadType` 合法值见 `DownloadSourceType`；`mediaType` 见 `DownloadMediaType`（`"video"` / `"image"`）。
- 管理页排序走 `getPageByMediaTypeSorted(...)`（`@RawQuery` + `SimpleSQLiteQuery`），排序列名来自固定枚举 `ManageSortOrder`（`createdAtMillis`/`createTime`/`diggCount`），**非用户输入，不接受任意字符串**，别改成拼用户串。筛选/全选等非分页路径在内存里按同一 `ManageSortOrder` 重排。统计面板用 `getAuthorCountsAll()` 与 `VideoTagDao.getTagsWithCount()` 聚合，占用空间由 `dirSize` 遍历 `Download/bDouyin/{videos,images,covers}` 求和。

### 接口响应样本

仓库根目录的 **`apiData/`**（`like.json`、`new_like.json`、`new_like_no_collect.json`、`collections.json`、`collection_no_like.json`、`collect_fold.json`、`post.json`、`photos.json`）是真实抓包下来的抖音接口响应，作为 **parser/mapper 调试的参考样本**，不是运行时资源、也不被单元测试加载——调整 `AwemeMapper` 或 `DouyinApiModels` 时对照对应 json 手工核字段。**不要**删。带 `no_like` / `no_collect` 后缀的是"未点赞/未收藏"对照样本，用于验证 `userRelation` 的编码分支。

## 约定 / 容易踩的坑

- 不要再引入新的"WebView DOM 抽取"式解析。新接口走 `DouyinApiService` + `DouyinParser`/`DouyinListApi` + 签名层。`api/DouyinSignatureGenerator.kt` 是早期"WebView 跑页面 JS 算签名"的**遗留占位类，当前无任何调用方**；现役签名一律在 `api/signing/`。别照着它写新代码，也别以为改它能影响请求。
- 日志里不要打印原始 Cookie 或 `msToken`——这些等同于完整账号权限。登录成功的 toast 已经主动做了脱敏，新增日志保持同样标准。
- `BatchDownloadCoordinator` 与 `DouyinList*` 层的并发数 / 重试 / 分页常量是为了对齐 F2 而调的，改之前先确认动机，不要随手调。
- 分页：列表接口返回 `has_more` + `max_cursor`。已知问题"接口返回 20 条但 UI 只显示十几条"是 ViewModel + Adapter 层的过滤 / 去重 / mapper 问题，**不是**网络层——优先查那一层再怀疑网络。
- Kotlin/JVM target 是 **11**；**没有**用 Compose，UI 全部是 XML + View Binding + Fragment。
- 新增页面时先想清楚"网络 / 数据库操作放 ViewModel、其余留视图层"这条线在哪，别把控件操作也搬进 ViewModel（那会引回 Context 依赖）。包结构与 ViewModel 的边界见上方「包结构约定」。
- 图片加载用 **Coil 2.7**，不是 Glide。

## 项目计划文件

- `.cursor/plans/f2-style-batch-prereqs_d02563bb.plan.md`：F2 移植剩余工作的单一事实来源；YAML frontmatter 中的 `todos[]` 含 `status`。
- `.cursor/CONTINUATION.md`：上面 todos 的可读索引，每条带"Agent 提示词"，用于跨机器恢复上下文。
- `.cursor/rules/db-schema.md`：数据库结构参考（见上）。

完成对应 todo 的任务后，更新 plan 文件 frontmatter 的 `status`，必要时同步刷新 `.cursor/CONTINUATION.md`。

## 注意项
- 每次新增需求开发完代码后，都要完善文档
