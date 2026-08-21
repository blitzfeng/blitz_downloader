# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

Android 应用（Kotlin），用于批量下载抖音视频与图集。实现思路刻意对齐 Python 项目 [F2](https://github.com/Johnserf-Seed/f2)：**纯 HTTP API 模拟**（Retrofit/OkHttp + 签名查询参数 + Cookie），而非 WebView DOM 抓取。WebView 仅作为登录界面以及 Cookie / UA 的引导来源。

单 module `:app`，包名 `com.blitz.downloader`，applicationId 同上。minSdk 24 / target 与 compile SDK 36，Kotlin + Java 11，同时启用 View Binding 与 **Compose**（`buildFeatures { viewBinding = true; compose = true }`），Room 走 KSP 处理。版本号锁定在 `gradle/libs.versions.toml`（含 compose-bom 与 `kotlin-compose` 编译器插件）；零散依赖（cardview、viewpager2、fragment-ktx、lifecycle、okhttp 4.12、retrofit 2.9、gson 2.11、coil 2.7、kotlinx-coroutines 1.9）直接写在 `app/build.gradle.kts` 里。

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
fragment (DownloadFragment → ListDownloadFragment / SingleDownloadFragment（列表下载在前）,
          ManageFragment → ManageVideoFragment / ManageImageFragment, SettingsFragment)
adapter  (VideoGridAdapter, ManageGridAdapter, TagManageAdapter, TagFilterAdapter, AuthorFilterAdapter)
dialog   (PhotoSelectionBottomSheet)
   │  ▲  视图层：只渲染 uiState、转发用户操作、弹对话框与跳转
   ▼  │
viewmodel (ListDownloadViewModel, ManageViewModel — Activity 级,
           ManageTabViewModel → ManageVideoViewModel / ManageImageViewModel,
           SettingsViewModel, TagManageViewModel,
           ShellNavViewModel — Activity 级跨 tab 导航中转)
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
      UrlUtils, NumberFormatUtils, MediaPermissions, DiggBadgeStyle, MediaOrientationProbe)

data + data/db (Room: AppDatabase, DownloadedVideoEntity/Dao, TagEntity/Dao, VideoTagEntity/Dao
                + DownloadedVideoRepository, VideoTagRepository, DatabaseBackupManager)

model (VideoItemUiModel, ManageGridItem, MediaOrientation)
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
| `dialog/` | 对话框与 BottomSheet（新的一律 Compose，见「Compose 接入」） | 页面级 Fragment |
| `ui/theme/` | Compose 主题（`BlitzTheme`、配色） | 具体页面 / 弹窗的 Composable |
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

### Compose 接入

新增 UI（页面 / 对话框 / BottomSheet）一律 **Compose + Material 3**；存量 XML 页面不动，除非顺手重写。
基建：`kotlin-compose` 编译器插件 + `compose-bom`（版本在 `libs.versions.toml`）+ `buildFeatures.compose = true`。

- **主题只有 `ui/theme/BlitzTheme`**，色值照抄 `res/values/colors.xml` 的品牌色（primary = #667EEA）。
  它**固定浅色、且刻意不开动态取色**：XML 主题虽挂 `DayNight` 但没有 `values-night`，颜色全是写死的浅色，
  Compose 侧若跟随系统深色或跟随壁纸，会出现「深色弹窗盖在浅色页面上」。等 XML 补齐深色资源再加分支。
- **Compose 弹窗一律继承 `dialog/ComposeDialogFragment`**，只实现 `@Composable DialogContent()`，
  **不要**再各写一份窗口设置。系统 Dialog 只出「窗口 + 遮罩」，容器（28dp 圆角 / `surfaceContainerHigh` /
  最大 560dp 宽）由 Compose 侧 `Surface` 画。基类里那套「清背景」不是玄学，少一步就在圆角外露出一圈浅色直角：
  `Theme.BlitzDownloader.ComposeDialog`（清 `windowBackground` / `background`、开 `windowIsTranslucent`）
  → 窗口 `setBackgroundDrawable(透明)` + **显式 `setDimAmount`**（translucent 窗口不自带遮罩）
  → 从内容视图**沿父链一路清到 DecorView**。根因是 app 主题那句 `android:background = @color/color_surface`
  会被每个从 XML 膨胀出来的 View 继承（`themes.xml` 里为 TextInputLayout 踩过同一个坑），
  而 PhoneWindow 装的 `screen_simple.xml` 有 `DecorView → LinearLayout → ContentFrameLayout` 三层，
  `window.setBackgroundDrawable` 只管最外层、只清直接父级只盖住最内层，**中间那层就是那圈白框**。
- **不要**在 DialogFragment 内部再调 Compose 的 `AlertDialog`/`Dialog` Composable——那会再开一个窗口，遮罩叠两层。
  用 DialogFragment 而不是裸 Composable 是为了拿到参数 Bundle 与 `rememberSaveable`，**转屏不丢弹窗**。
- **结果回传走 `FragmentResult`，不走构造回调**：回调会把弹窗钉死在某一个宿主的 ViewModel 上；
  用结果契约则弹窗谁都能弹。待处理的 id 由弹窗原样回传，宿主不必自己缓存（缓存也扛不住进程重建）。
- 公共 Composable：`DialogContainer` / `DialogHeadline` / `DialogActions`（`ComposeDialogFragment.kt`）、
  标签多选栅格 `TagCheckGrid` + `rememberCheckedTags`（`TagCheckGrid.kt`）。栅格固定**两列**——标签有十几个，
  单列会把弹窗拉得很长；超出 320dp 才滚，长标签单行截断。

已改造（宿主都是 `ManageVideoFragment`，事件仍由 `ManageTabViewModel` 发）：

| 弹窗 | 语义 | 「确定」空选 |
|------|------|--------------|
| `TagEditDialogFragment` | 单条记录，**整体覆盖**（预勾当前标签） | **可点**——清空是有效操作 |
| `BatchTagDialogFragment` | 多选记录，**追加** | **禁用**——空集合等于什么都没做 |

两者「确定」的可用性相反，是语义决定的，别顺手统一。与旧 `AlertDialog` 版的其余有意差异：
批量弹窗没勾标签时不再 toast 提醒（`manage_set_tags_none_checked` 因此闲置未删）；
「仅次数 +1」的二次确认改为同一窗口内换页、取消可退回勾选页；勾选状态转屏不丢。

**页面级 Compose（首例：`ImageViewerActivity`）**

图片浏览页已从「ViewPager2 + 两个 XML 布局」整体改写为 Compose（`activity_image_viewer.xml`、
`item_image_viewer_page.xml` 已删除），行为与改造前**严格等价**：左右翻页、`ContentScale.Fit`、
顶栏悬浮在图片上、多图时底部页码。`findImageSet` 一字未动——`MediaExportManager` 与
`BatchDownloadCoordinator` 的 KDoc 都指向它，签名一改要连带改三处。

- 入口是 `AppCompatActivity` + `setContent`，依赖 `androidx.activity:activity-compose`
  （弹窗那条路走 `ComposeView`，不需要它）；图片加载走 `io.coil-kt:coil-compose`，
  **版本必须与已有的 `io.coil-kt:coil` 一致**，两个 artifact 分开声明。
- **实况图（Live Photo / 动图）在本浏览页播放**：抖音「动图」不是 animated webp，而是 **Live Photo**——
  一张静态 webp 封面 + 一段短 mp4（判据是**逐张看 `image.video` 是否非空**，`live_photo_type=1`；不是
  `aweme_type`，普通图集也是 68）。下载侧已把封面存 `base_NN.webp`、动图另存同基名 `base_NN.mp4`
  （见「下载管道」）。本页 `findImageSet` 扫封面 + 探同名 `.mp4` 兄弟，产出 `LivePhotoPage(cover, video?)`：
  有 mp4 的页走 `LivePhotoPlayer`（原生 `MediaPlayer` + `TextureView`，循环、静音、`ContentScale.Fit`
  居中缩放，**仅当前页播放**、滑走 `seekTo(0)` 暂停、`onDispose` 释放），否则走 `AsyncImage`。
  起播条件收敛在单个 `LaunchedEffect`（当前页 + 已 `onPrepared` + `surfaceReady`；`onPrepared` 只置标志
  不直接 start），且 **`start()`/`pause()`/`seekTo()` 必须用 `isPlaying` 守卫**——这条是「多页只第一页能播、
  连滑回第一页也黑」的**真正根因**（真机 logcat 定位）：`HorizontalPager` 会预加载相邻页，被预加载的**非当前页**
  `onPrepared` 后处于 **Prepared 态**，此时若因 `isActive=false` 对它调 `pause()`，MediaPlayer 报
  `error(-38)`（pause 只在 Started/Paused 合法）直接进 **Error 态**、之后 `start()` 全废（`start called in
  state 0`），翻到该页再也起不来。所以：非当前页的 Prepared 态**保持不动**，`if (isActive && surfaceReady)
  { if (!isPlaying) start() } else if (isPlaying) { pause(); seekTo(0) }`——绝不 pause 一个还没 Started 的播放器。
  `surfaceReady` 纳入条件是次要的稳妥项（后面的页 surface 晚于 prepare 就绪），不是主因。
  **不引 ExoPlayer**（复用 `VideoPlayerActivity` 的 MediaPlayer 模式）；`TextureView` 而非 `SurfaceView`
  是因为 pager 横滑时 SurfaceView 有独立窗口层会闪黑。Fit 靠 `applyFitTransform` 反算 matrix
  （TextureView 默认拉伸填满，以视图中心为轴还原视频比例）。**曾误判为 animated webp 加过 `coil-gif`，
  已回收**——文件本身是静态 webp，加解码器无用。
- **本页刻意不进 `BlitzTheme` 的配色**：黑底页面上套浅色 primary，白字会看不清。
  外层仍包 `BlitzTheme` 保证字体与形状统一，顶栏 / 页码角标的颜色在文件内写死
  （`ViewerBarColor` 对齐 `colors.xml` 的 `color_primary_dark`）。这是深色页面的个例，
  **不要**据此推广成「Compose 页面都自己写色值」。
- **不用 material-icons 依赖**：返回箭头复用存量矢量图 `R.drawable.ic_back_arrow`，
  `Icon` 会自行着色。项目至今没有引入 `material-icons-*`，新增图标优先走 drawable。
- **`painterResource` 只吃 VectorDrawable 与位图**，喂 `<layer-list>` / `<shape>` 会在首帧
  抛 `IllegalArgumentException: Only VectorDrawables and rasterized asset types are supported`
  （编译期无感，运行才崩）。`ic_video_placeholder` 正是 layer-list，所以占位 / 失败图走
  `ImageRequest.Builder.placeholder(resId) / .error(resId)`——Coil 用平台 inflater，什么 drawable 都能吃。
  复用存量 drawable 到 Compose 之前，先确认它的根标签。
- **没有 ViewModel**：本页既不发请求也不读写数据库，图集列表在 `onCreate` 里算好当参数传入，
  页内唯一状态是 `pagerState.currentPage`。按包结构约定，这类纯视图状态留在视图层。
- 已知行为（改造前就有，本次未修）：未声明 `configChanges`，转屏重建后回到第 1 页。

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

### ArgusSecurityPlugin — `uifid` 请求头（2026-08 抖音新增网关校验）

抖音在边缘网关（响应头 `Server: TLB`）挂了 `ArgusSecurityPlugin`，对**登录态列表接口**
（`aweme/favorite/`「喜欢」、`aweme/listcollection/`「收藏」、`collects/list/`「收藏夹」）在业务逻辑
**之前**做前置校验；`aweme/post/`（发布作品）**不在保护名单**，所以它一直正常。缺失时直接 403，
正文是明文而非 JSON（拿不到 `status_code`）：

- 缺 `uifid` → `Blocked by ArgusSecurityPlugin Uifid Not Found`
- 缺签名 → `Blocked by ArgusSecurityPlugin Signature Not Found`

**这道插件有两道校验，都在 `HeaderInterceptor` 里应对：**

1. **`uifid` 请求头**（缺 → `Uifid Not Found`）。**注意是 HTTP 请求头，不是 Cookie、也不是 query `webid`。**
   值复用从 Cookie 解析出的 `DouyinApiClient.webId`（即 UIFID），对所有抖音 API 请求统一附加。
2. **`x-tt-argus` 请求头**（缺 → `Signature Not Found`）。真机证实：query 里的真实 `a_bogus`**过不了**这道
   ——网关验的是独立的签名头，不是 `a_bogus`。**实测该版本只校验此头是否存在、不校验值**（任意/空值即放行），
   是「疑似 App 流量放宽 web 校验」的旁路，故填占位 `"1"` 即可。仅对受保护接口（favorite / listcollection /
   collects）附加，避免波及未受保护的 `post`。

排查方法（可复用）：裸 `curl` 逐层剥错误链——`Uifid Not Found` →（补 uifid 头）→ `Signature Not Found`
→（补 x-tt-argus 头）→ 200。**明文 403 正文里的 `ArgusSecurityPlugin ... Not Found` 直接就是根因**，
别往签名 / Cookie / msToken 深挖。`LoggingInterceptor` 会打印 403 明文正文（`Log.w`），网关再变时第一时间可见。

**`x-tt-argus` 是权宜之计**：网关一旦升级到真正校验签名值即失效（表现为补了头仍 `Signature Not Found`）。
届时不要去逆向抖音 web 风控 SDK（mssdk/Argus，猫鼠游戏、成本高），走 **WebView 内注入 JS 发真实 `fetch`**
——请求由 douyin.com 页面上下文发出，页面自带最新签名 SDK 自动补齐 Argus 头，我们只回传 JSON。这与「WebView
仅作登录 / Cookie 引导」的现有定位有偏差（等于让 WebView 也承担取数），是那种情况下的兜底方案。

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
- `Download/bDouyin/images/` — 图集图片（含**实况图**：静态封面 `base_NN.webp` + 同基名同序号的动图本体 `base_NN.mp4`）
- `Download/bDouyin/covers/` — 视频封面缩略图（图集复用第一张图作为封面，不重复写）

**实况图（Live Photo / 动图）**：抖音「动图」= 静态 webp 封面 + 一段 mp4，逐张由 `image.video` 非空判定
（`AwemeMapper.preferredImagePairs` 产出与静态封面**等长一一对应**的 `imageVideoUrls`，`VideoItemUiModel`
携带）。下载时每张图先下静态封面（现状不变），若该张有 mp4 就**额外**下同基名 `base_NN.mp4`
（`BatchDownloadCoordinator`，best-effort：mp4 失败只告警不判整体失败，退化为静图）。入库 `filePath` 仍指
第一张封面 webp，九宫格缩略图/封面逻辑**零改动**——mp4 是附属文件，靠浏览页/导出的同目录兄弟扫描发现，不入库。
历史记录只有 webp、无 mp4，需重新下载才有动图。播放见「页面级 Compose」的 `LivePhotoPlayer`。

### 相册可见性（`.nomedia` + 所有文件访问权限）

实现在 `util/MediaVisibilityManager`，设置页有「相册可见性」分组。**改这块之前先读完本节**，
这里每一条都是真机实验得出的，凭直觉改会静默弄坏所有媒体。

**两件事必须同时成立**：

1. **`.nomedia` 让相册看不到。** 注意：把文件写进 `MediaStore.Downloads` 集合**不足以**避开相册
   ——MediaStore 只是把 `is_download` 置 1，`media_type` 仍按 MIME 判成 IMAGE/VIDEO，相册 App
   照样列得出来（代码 KDoc 里曾写反，已改）。真正让它隐身的是 `.nomedia`：扫描器发现目录已隐藏，
   会把这些行的 `media_type` 置成 NONE(0)。
2. **`MANAGE_EXTERNAL_STORAGE` 让本 App 仍读得到。** 本 App 读媒体一律走**直接文件路径**
   （`ManageGridAdapter` / `ImageViewerActivity` / `VideoPlayerActivity` / `LanFileServer`）。
   这条路在 Android 11+ 由 MediaProvider 的 FUSE 把关：没有该权限时靠 `READ_MEDIA_IMAGES` /
   `READ_MEDIA_VIDEO`，而**这两个权限只覆盖"仍算媒体"的文件**。`media_type` 一变 NONE 就不再覆盖，
   只剩 owner 应用能访问。

**owner 靠不住，这是本方案的根据。** `owner_package_name` 由 MediaProvider 在 insert 时按调用方
盖章、**只读**（塞进 ContentValues 会被忽略），并在包被移除时由 `onPackageOrphaned` 清空。
**固定签名只能防止将来再丢，追不回已经丢掉的 owner。** 实测机上老封面的 owner 早已是 NULL。
真机对照实验（同一目录、同一个 `.nomedia`、同一次启动）：owner 非空的封面照常显示，
owner 为 NULL 的全变 `ic_video_placeholder`——所以**不能**把可用性押在 owner 上。

**据此定下的规则**：

- **开启隐藏前必须确认 `hasAllFilesAccess()`**，`MediaVisibilityManager.setHidden` 的"开启"方向由
  调用方保证（`SettingsViewModel.setFolderHidden` 还兜了一次底）。**"关闭"方向永远放行**，那是
  用户的自救出口。
- **改完 `.nomedia` 必须重扫该目录**（`MediaScannerConnection.scanFile` 逐文件，已验证有效）。
  不扫的话 MediaStore 里已有的行不会跟着变，只有以后新下载的文件受影响。
- **OEM 自带相册（小米 / OPPO）不会立刻消失，这不是 bug，别去"修"**。它们各自维护独立索引，
  按自己的节奏与 MediaStore 同步，实测**隔天才更新**（文件越多越慢）。Google 相册按标准查询，
  重扫后立即隐藏。所以判断本功能是否生效**只看 MediaStore**：

  ```bash
  adb shell "content query --uri content://media/external/file \
    --projection media_type --where \"relative_path='Download/bDouyin/videos/'\""
  # media_type=0(NONE) 即已生效；1=IMAGE / 3=VIDEO 表示仍可见
  ```

  曾经因为"切了开关相册还看得到"一路排查到怀疑 OEM 相册不认 `media_type`，差点动手改成
  「目录加点号」或「改扩展名」——真相只是索引还没刷新。**先等一天再下结论。**
- **`covers/` 没有开关，由 `MainActivity.onResume` → `ensureCoversHidden` 按权限自动维护**：
  有权限就隐藏（封面是内部产物，出现在相册纯属噪音），**没权限就主动取消隐藏**。这与
  videos / images **刻意不同**——那两个有设置项，用户撤权后能自己关；封面没有任何 UI，
  不自愈就是死局。放 `onResume` 而非 `onCreate`，是因为用户在系统页授权后 Activity 通常不重建。
- **videos / images 缺权限时只告警不擅自改**（`MainActivity.warnIfHiddenWithoutAccess`，
  `onCreate` 里一次）：那是用户显式开的，替他关太越权；但不提示，撤权后就只剩"媒体全变占位图"
  而毫无线索。
- **状态权威是磁盘**（`.nomedia` 在不在），不存 SharedPreferences。设置页每次 `onResume`
  重新探测——用户可能刚授权回来，也可能在 App 外用文件管理器动过。
- 授权跳转带包名直达 `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`，失败退回全局列表页。
  待办目录存 `savedInstanceState`（跳系统页期间 Fragment 可能被回收），回来在 `onResume` 补做。
- **API < 30 该功能直接不可用**（`hasAllFilesAccess()` 恒 false）：那里没有这个权限，
  本 App 也没有 `requestLegacyExternalStorage`。宁可不隐藏，也不能把媒体锁死。
- API 24–28 的 `downloadCoverViaFileApi` 仍会建 `.nomedia`，那里**安全**：没有分区存储，
  读文件靠 `READ_EXTERNAL_STORAGE`、不看 `media_type`，隐藏目录不会反噬自己。
- `ListDownloadFragment` 里原本冷启动建 `.nomedia` 那句已删除。它一直传相对路径
  `File(COVER_SUBDIR)`（解析成 `/bDouyin/covers`）**从未成功过**，"一直没生效"反而遮住了这个坑。
  **别再加回来**，封面隐藏现在归 `ensureCoversHidden` 管。
- `backup/` 有自己的 `.nomedia`（`DatabaseBackupManager` 管），`export/` **不隐藏**——ZIP 要靠 MTP 拷。

文件命名约定为 `<userName>+<desc>`（截断/转义后）。下载成功后必须调用 `DownloadedVideoRepository.recordSuccessfulDownload(...)` 写入数据库，否则管理页角标无法识别为"已下载"。

下载完成后的界面回刷走 `DownloadEvents`（进程内 `SharedFlow`，`replay = 0`）：服务写库成功后广播 aweme id 集合，`ListDownloadViewModel` 在 `init` 里收集并就地把对应项标为已下载、取消勾选；页面销毁期间错过的事件由 `onResume` → `ListDownloadViewModel.onScreenResumed()` 的整表回查兜底。**不要**给它加 replay 缓存来"修"漏事件，两条路径是有意分工的；也**不要**因为"ViewModel 活得比 Fragment 久、不会漏事件了"就删掉回查那条——进程被杀的场景事件不会补发。

**批量下载在前台服务里执行**（`DownloadService`，`foregroundServiceType=dataSync`），而非页面协程——离开页面 / 应用退到后台都不中断，进度走通知栏。链路：`ListDownloadViewModel` 预先算好每项的 `DownloadRecordMeta`（入库所需字段快照）→ 组成 `DownloadJob` → `DownloadService.start(...)`（大列表经**同进程内存队列**交接，不走 Intent 序列化）→ 服务内调 `BatchDownloadCoordinator.downloadSelected(..., onItemDone=...)` 下载并更新通知 → **成功项在服务内自行 `recordSuccessfulDownload` + 收藏夹标签关联**。不做跨进程重启的断点续传。改动写库逻辑时注意它现在有两处调用点的等价性（服务内 vs. 直接调用）。

选中状态的权威是 `ListDownloadViewModel` 的 `selectedIds` / `imageSelections`；`VideoItemUiModel.isSelected` 与 `selectedImageIndices` 只在 `compose()` 里合成出来——一份给 Adapter 渲染，一份喂给 `BatchDownloadCoordinator`（它靠 `isSelected` 筛选待下载项）。这样 `download/` 无需任何改动。

### 导出管道（管理页「导出选中」）

多选后有两条导出路径，共用 `MediaExportManager.resolveExportFiles(...)` 把选中记录解析成磁盘文件：

- **ZIP 导出**：`MediaExportManager.exportToZip(...)` 打包到 `Download/bDouyin/export/bDouyin_export_<时间戳>.zip`（`Deflater.NO_COMPRESSION` 只打包不二次压缩），完成后 `MediaScannerConnection.scanFile` 触发扫描,使其立刻能通过 MTP 看到。手机连电脑拷这一个文件。
- **局域网导出**：`net.LanFileServer`（零依赖手写 HTTP/1.1，仅 GET）。电脑同 WiFi 用浏览器打开 `http://<ip>:<port>/` 逐个或 `/all.zip` 打包下载。**服务生命周期由 `ManageViewModel` 持有**，`onCleared()` / 对话框 dismiss 时 `stop()`——放在 ViewModel 里是为了让转屏不掐断电脑那边正在下载的文件（原先由 Activity 持有、`onDestroy` 即停）。

**局域网导出的横屏 / 竖屏分包（视频 Tab 专属）**：电脑端页面除「打包下载全部」外，另有 `/landscape.zip` 与 `/portrait.zip` 两个方向包，文件列表也按方向分组。三条打包路由共用 `LanFileServer.serveZip(...)` 的同一份流式实现，`exportCount` 语义不变（整包完整写出 → 包内每条记录 +1）。

- **方向判定的唯一来源是本地文件**：`util/MediaOrientationProbe` 读 `MediaMetadataRetriever` 的宽高并按 `VIDEO_ROTATION` 修正（90/270 交换宽高），图片走 `BitmapFactory` 只读边界 + EXIF 修正。**不要**改成用抖音接口的 `video.width/height`——接口值不保证含旋转修正，历史记录也没有这个字段，混用会让同一张表出现两种口径。
- 结果缓存在 `downloaded_videos.mediaWidth/mediaHeight`（v14），**两个写入时机**：下载落盘后由 `DownloadService` 随写库一并写入；局域网导出前由 `ManageViewModel.backfillMediaSizes` 对 `mediaWidth == 0` 的记录懒探测回填。两条路径调同一个 probe，口径必然一致。
- 懒回填**只在视频 Tab 执行**（图片 Tab 不分包，探测纯属浪费用户时间），且回填后**必须同步更新内存里的 entities**——`resolveExportFiles` 读的是内存对象，只写库会让本次导出全部落进竖屏包。
- 方向由 `MediaOrientation.of(w, h)` 现算，`w > h` 才算横屏：方形、0（未探测/探测失败）、负数一律归竖屏。探测失败**不写哨兵值**，保持 0。
- `MediaExportManager.resolveExportFiles` **不做探测 IO**，只读 entity 字段；回填是调用方的职责。
- 图片 Tab 传 `splitByOrientation = false`，两条方向路由 404、页面不分组，行为与本功能上线前完全一致。**不要**顺手给图集也分包——图集是一条记录多个文件，拆开会把一个图集散进两个包。
- 首页分组渲染时，`/f?i=N` 的 `N` 仍是**完整 files 列表**里的下标，不按分组重排，否则单文件下载链接会指错文件。

关键约束：**图集的 `filePath` 只存了第一张图（`base_01.jpg`）**，导出时必须扫描同目录 `base_\d+.<ext>` 的全部兄弟文件——扫描规则与 `ImageViewerActivity.findImageSet` 一致，改一处需同步。**实况图**：兄弟扫描现在把封面图片扩展（webp/jpg/jpeg/png）**和 mp4 都纳入**，导出会把静态封面与动图 mp4 一起打包；浏览页那边同规则扫描，只是聚合成 `LivePhotoPage(cover, video?)`（判断每张封面是否有 mp4 兄弟），两者扫描口径相同、聚合形态不同。

导出计数（`exportCount`，v11）：**只有局域网导出会累加**——`LanFileServer` 把某条记录字节完整写出 socket 后回调 `TransferEvent`，由 `DownloadedVideoRepository.incrementExportCount(...)` 做 `SET exportCount = exportCount + 1` 的原子累加（**不要**改成"读实体→改→整行 update"，并发写会互相覆盖）。ZIP 导出、`HEAD` 探测、中途断连都不累加。它只表示"手机已完整发出"，不代表电脑落盘，只作提示与二次确认依据。语义细节见 `.cursor/rules/db-schema.md` 的 exportCount 小节。

### 跨 tab 导航（`ShellNavViewModel`）

底部导航三页之间的跳转请求走 Activity 级的 `ShellNavViewModel`，目前只有一条业务：
**管理页点卡片上的作者名 → 下载页加载 TA 的发布作品**。

中转站的形状是**一条 latch 一个消费者，谁消费谁清值**。一次业务要做几个动作，就开几条
`StateFlow`，每条恰好一个消费者：

```
ManageGridAdapter 作者行点击（videoAuthorSecUserId 非空、非多选态）
  → ManageVideoFragment / ManageImageFragment
  → ShellNavViewModel.requestAuthorPosts(
        secUserId, nickname, originTab = TAB_MANAGE, targetTab = TAB_DOWNLOAD)
      ├─ pendingTab              ─▶ MainActivity        切到 TAB_DOWNLOAD → consumePendingTab
      ├─ pendingDownloadListTab  ─▶ DownloadFragment    setCurrentItem(POS_LIST) → consumePendingDownloadListTab
      └─ authorPostsRequest      ─▶ ListDownloadFragment markAuthorPostsMode + enterAuthorPostsMode → consumeAuthorPostsRequest
```

三条 latch 互不相干，收集器谁先醒都不影响结果。`targetTab` 由调用方传入而非写死在
ViewModel 里，`ShellNavViewModel` 才不用 import `MainActivity`。

四条别踩的规则：

- **一条 latch 只能有一个消费者，绝不能多个地方观察同一条流再由其中之一清值。**
  这是本功能终审抓到的 Critical 缺陷（原实现让 `MainActivity` / `DownloadFragment` /
  `ListDownloadFragment` 三方共享 `authorPostsRequest`，只有终点清值）。`StateFlow` 是
  **合并（conflated）**语义：收集器的 slot 被唤醒后读的是 `_state.value` 的**当时值**，
  不是唤醒它的那个值。终点若先醒，它会在同一次 `setValue` 的派发里把值清成 null；另两方
  随后醒来读到 null，而它们的 `oldState` 本来也是 null，`StateFlowImpl.collect` 里
  `oldState != newState` 判定"值没变"→ **跳过 emit，连回调都不进**，两个切 tab 动作静默丢失。
  而唤醒顺序 = 内部 slots 数组下标序，`AbstractSharedFlow.allocateSlot()` 轮转分配，
  反复订阅/退订后会洗牌，**没有任何保证**；更有一条系统性倒序的路径：API 29+ 上 Activity 的
  `ON_START` 由 `ReportFragment.onActivityPostStarted` 派发，**晚于** `FragmentActivity.onStart()`
  里的 `mFragments.dispatchStart()`，所以每次 stop→start（按 Home 回来、转屏）之后 Fragment
  侧的收集器都先于 Activity 侧重新订阅。表现是"冷启动能用、按一次 Home 回来点作者名毫无反应"。
  **"幂等"不是充分条件**——它只保证重复收到没坏处，不保证收得到。
  也**不要**用"把清值推到下一帧"（`view?.post { … }`）绕过去，那是把正确性押在帧边界上。
- **请求用 `StateFlow<AuthorPostsRequest?>` + 显式 `consume()`，不要改成一次性事件。**
  `ListDownloadFragment` 是 ViewPager2 懒创建的，而 `DownloadFragment` 本身也由 `MainActivity`
  按需 `add`——点下载完成通知冷启动会落在 `TAB_MANAGE`（见 `DownloadService`），那条路径下它
  压根不存在，`SharedFlow(replay = 0)` 在无订阅者时会把 emit 直接丢弃。这与 `DownloadEvents`
  用一次性事件并不矛盾：那里有 `onResume` 整表回查兜底，这里没有兜底路径可用。
  **也不要**改成"靠把列表下载放在第一个子 tab 来保证它已被创建"——tab 顺序是产品决定。
- **外部入口不复用 `ListDownloadEvent.EnterAuthorPostsMode`**：Fragment 在同一个
  `repeatOnLifecycle` 块里 `launch` 多个收集器，若 shellNav 那个先跑并同步调进 ViewModel，
  `events` 收集器可能还没建立订阅。两个入口在 Fragment 侧汇合于 `enterAuthorPostsMode(url)`，
  共用 ViewModel 的 `markAuthorPostsMode(nickname)` 与 companion 里的 `authorPostsUrl(secUid)`。
- **返回键用 `isPageResumed` 标志位判定"本页在前台"，不能用 `!isHidden`**：`ListDownloadFragment`
  是孙辈（`MainActivity` → `DownloadFragment` → `ViewPager2` → 本页），父页被 `hide` 时它自己的
  `isHidden` 仍是 false，只看它会在管理页按返回时把事件抢走；`onResume`/`onPause` 里维护的
  `isPageResumed` 才等价于"它是可见 tab 的可见子页"。来源 tab 存在 `ShellNavViewModel` 的私有
  字段 `authorPostsOrigin` 里（与 request 分开存活：request 一被消费就清，来源要活到退出作者模式）。
  它**不是 flow**，`updateBackCallback()` 无法对它的变化自动反应——**任何改动它的调用点都必须
  自己再触发一次返回键状态刷新**。当前三处都补了；新增第四处漏一次，返回键就停在错误的启用态。

两条记录在案的已知行为，别当 bug 排查：

- **请求没有时效**：`authorPostsRequest` 会一直等到消费者上线，没有上界。点作者名后外壳已切到
  下载 tab、但 ViewPager2 还没完成首次 layout（`ListDownloadFragment` 未创建）时立刻切去「设置」，
  请求会一直挂着，直到用户以后某次回到下载 tab 才被消费，界面莫名进入某个作者的作品模式。
  **有意不加过期阈值**：窗口只有一两帧，而加过期要引入时间戳与时钟基准，收益不匹配。
- **连点同一个作者两次**：`MutableStateFlow` 的 setter 对相等值是 no-op，所以第二次点在请求已被
  消费后才有效（重新置值）；若在消费前连点，第二次是空操作——用户看到的结果一样，无副作用。

`videoAuthorSecUserId` 为空的旧记录（该列 v5 引入）作者行不显示图标、也不可点。昵称为空的记录
统一用 `awemeId` 兜底（`ManageGridAdapter` 的显示 / 无障碍文案与传给 `requestAuthorPosts` 的
`nickname` 是同一份口径）。

### 管理页的筛选栈

`ManageActivity` 只管 Toolbar / 菜单 / 抽屉 / 对话框；**筛选条件与多选状态的权威在 Activity 级的 `ManageViewModel`**（按 Tab 独立维护），取数在 `ManageTabViewModel` 的两个实现（`ManageVideoViewModel`、`ManageImageViewModel`）里。

Activity 与两个 Tab **不再直接互相引用**（旧实现靠 `findFragmentByTag("f$position") as? ManageTabFragment`，依赖 ViewPager2 的内部 tag 命名约定，不受 API 保证）。三条通路：

- **条件下行**：Activity 改 `ManageViewModel.filters` / `.selection` → Fragment 观察自己那一份 → 转发给自己的 `ManageTabViewModel`（两个 ViewModel 互不认识）。
- **动作下行**：Activity 发 `ManageCommand`（带目标 tab）→ 对应 Fragment 消费后在自己的 ViewModel 上执行。
- **数据上行**：Fragment 每次列表变化调 `ManageViewModel.setLoaded(tab, entities, hasMore)`，Activity 侧据此算「是否已全选」与「选中了哪些实体」。

七层筛选是**叠加**关系，不是互斥的单选，全部收敛在 `ManageFilterState` 里：

| 层 | `ManageFilterState` 字段 | 生效位置 |
|----|----------|----------|
| 搜索（作者昵称） | `searchQuery` | SQL |
| 作者（`sec_user_id` 优先，回退昵称） | `authorSecId` / `authorName` | SQL |
| 标签多选 | `tags` + `AppSettings.isTagFilterMatchAll` | SQL（`getVideosByTags(tags, matchAll)`） |
| 标签精细检索（基准标签 + 逐行 `包含/不包含/或`） | `tagQuery` | 只在内存（`TagQuery.evaluate`，取数前按标签查 id 集合） |
| 归属（点赞 / 收藏 / 收藏夹 / 无归属） | `relation` | 分页路径下沉 SQL，其余走 `apply()` |
| 标签数量（0..5+，**可多选取并集**） | `tagCounts`（空集 = 不筛选） | 只在内存（`postProcess`） |
| 标签修改次数（0..5 / >5） | `tagEditCount` | 只在内存（`postProcess`，读 `tagEditCount`） |

作者 / 搜索 / 标签 / 标签精细检索四者的互斥清理规则收敛在 `ManageFilterState.withAuthor` / `withSearchQuery` / `withTags` / `withTagQuery` 这四个 `with*` 方法里（`withAuthor` / `withTags` 会清 `tagQuery`；`withTagQuery` 会清 `tags` / `searchQuery` / `authorSecId` / `authorName`），**不要**在调用处手动清另外几个。

几条别踩的规则：

- 标签多选默认取**交集**（`AppSettings.isTagFilterMatchAll` 默认 true，设置页可切并集）。`AppSettings` 是运行时偏好、每次 getter 直读 SharedPreferences 不做内存缓存——**别加缓存**，否则设置页改完别的页面拿到旧值。
- 标签数量筛选是**多选取并集**（`Set<ManageTagCountFilter>`，空集 = 不筛选，判定走 `matchesAny`），与标签栏多选默认取交集不是一回事，别混。标签修改次数筛选仍是单选。
- 「标签数量筛选」「标签修改次数筛选」都没有 SQL 实现，任一激活就必须切成全量加载（`ManageFilterState.hasMemoryOnlyFilter`）：否则"一页 20 条筛剩 2 条、撑不满屏幕不触发滚动加载"看起来就像数据丢了。
- 这两层都**隶属于归属筛选之下**（共用 `scopeByRelation`）：归属为 OFF 时仍按 `EXCLUDE_UNASSIGNED` 收窄，只有用户显式选「仅看无归属」才听用户的。原因是他人主页 post 记录基本没打过标签、更没改过，不排掉「0 个标签」「改过 0 次」就全是它们。
- 非分页路径（搜索 / 标签 / 作者 / 全选）统一走 `ManageTabViewModel.postProcess(...)` = 归属 → 标签数 → 标签修改次数 → 排序。新增取数入口别绕过它，否则筛选会"漏一层"。
- 「标签精细检索」（`TagQuery`）与标签栏多选**互斥**：任一生效即清掉另一个，清理规则只写在 `ManageFilterState.withTagQuery` / `withTags` 里，调用处不手动清。求值是**从上到下左结合**（`((A ∩ B) ∪ C) − D`），行序影响结果——**不要**改成「先与非、后或」的优先级，界面上看不出优先级。
- 精细检索的取数分支必须排在 `queryPage` / `loadFullScopeEntities` 的 `when` 里 **`tags` 相关分支之前**：它激活时 `tags` 恒为空，排在 `f.tags.isEmpty() && …` 之后会被那些分支截走。但**不得**再往前压过 `searchQuery` / `hasAuthorFilter`——搜索与精细检索不像标签栏那样互斥（`withSearchQuery` 故意不清 `tagQuery`），搜索优先只是「搜索期间临时压住规则」，退出搜索（清空输入框）规则结果要自动回来；压过 search 会让搜索框输入任何文字都不生效，看起来像搜索被吞掉了。它自己就是 `oneShot` 全量路径，**不要**再塞进 `hasMemoryOnlyFilter`。

### 持久化（Room）

**数据库结构的权威文档是 `.cursor/rules/db-schema.md`，改 `data/db/` 之前先读它。** 要点：

- `AppDatabase` 当前 **version = 14**（v14 新增 `mediaWidth` / `mediaHeight`）。三张表：`downloaded_videos`、`video_tags`、`tags`。
- 所有迁移 `MIGRATION_1_2 .. MIGRATION_13_14` 都在 `AppDatabase` 里显式列出。builder 上虽然还挂着 `fallbackToDestructiveMigration()` 作兜底，但**不要**依赖它来"对付过去"——漏写迁移 = 用户数据被清空。新增字段时：写 `MIGRATION_14_15` → `version = 15` → `addMigrations(...)` 注册 → 同步更新 `.cursor/rules/db-schema.md`（新增列与版本行）。
- `watched`（是否已看过）只由**管理页进入视频播放页**置位：`ManageVideoViewModel.openVideoPlayer` 把 `awemeIds` 随 `createListFileIntent` 传给播放页，播放页每加载一条就写库（含上下滑动切到的）。列表侧「未看过」标记的刷新分两条路：点开那条就地标掉，滑动看过的靠 `ManageVideoFragment.onResume` → `refreshWatchedFlags()` 回查——**别把其中一条删掉当冗余**，也别指望 ViewModel 的 `init` 或 StateFlow 自动收集能替代 `onResume` 那条（ViewModel 不随 `onResume` 重建）。
- `mediaWidth` / `mediaHeight`（v14）存媒体的**呈现宽高**（已做旋转 / EXIF 修正），`0` = 未知。只服务于局域网导出的横屏/竖屏分包，方向由 `MediaOrientation.of` 现算、不落库。图集也会写（探首图），当前不用，为后续留数据。详见上方「导出管道」。
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
- Kotlin/JVM target 是 **11**；存量 UI 是 XML + View Binding + Fragment，**新增 UI 走 Compose**（见「Compose 接入」），两者共存。
- 新增页面时先想清楚"网络 / 数据库操作放 ViewModel、其余留视图层"这条线在哪，别把控件操作也搬进 ViewModel（那会引回 Context 依赖）。包结构与 ViewModel 的边界见上方「包结构约定」。
- 图片加载用 **Coil 2.7**，不是 Glide。
- 下载页的两个子 tab 顺序是**「列表下载」在前、「单视频下载」在后**（`DownloadFragment.POS_LIST = 0`），因为批量列表是主场景（`BatchListDownloadScope.PRIMARY_TARGET_IS_LOGGED_IN_LISTS = true`）。副作用是列表页成了冷启动第一屏，所以它的 `.nomedia` 创建已挪到 IO 线程——新增开屏逻辑时别再往主线程放磁盘 IO。

## 项目计划文件

- `.cursor/plans/f2-style-batch-prereqs_d02563bb.plan.md`：F2 移植剩余工作的单一事实来源；YAML frontmatter 中的 `todos[]` 含 `status`。
- `.cursor/CONTINUATION.md`：上面 todos 的可读索引，每条带"Agent 提示词"，用于跨机器恢复上下文。
- `.cursor/rules/db-schema.md`：数据库结构参考（见上）。

完成对应 todo 的任务后，更新 plan 文件 frontmatter 的 `status`，必要时同步刷新 `.cursor/CONTINUATION.md`。

## 开发规范
- 每次新增需求开发完代码后，都要完善文档
- ui设计风格要使用material design
- 新的ui页面、dialog等采用compose实现
