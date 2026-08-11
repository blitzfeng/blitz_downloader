# 管理页点击作者名 → 加载 TA 的发布作品

**日期**：2026-08-11
**状态**：已确认，待生成实施计划

## 目标

管理页宫格卡片里的作者昵称变成可点击入口：点一下切到「下载 → 列表下载」，用数据库里存的
`videoAuthorSecUserId` 加载该作者的发布作品（`/aweme/v1/web/aweme/post/`）。同时把昵称控件
本身改成方便点击的样式。

顺带把下载页的两个子 tab 换序：「列表下载」放第一个，「单视频下载」放第二个。批量列表是本项目
的主场景（`BatchListDownloadScope.PRIMARY_TARGET_IS_LOGGED_IN_LISTS = true`），它该当第一屏。

## 前提：接口侧不需要新代码

`/aweme/v1/web/aweme/post/` 的定位参数只有 `sec_user_id` + `max_cursor` + `count`
（`api/DouyinApiService.kt:23`），链路 `DouyinListApi.fetchUserPostPage` →
`AwemeWebUrls.userPostSignedUrl` → `dynamicGet` 已经齐备。

而且**列表下载页早就有「查看 TA 的 Post」模式**：`ListDownloadViewModel.onAuthorPostsClicked()`
拼出 `https://www.douyin.com/user/<secUid>?from_tab_name=main`，发 `EnterAuthorPostsMode` 事件，
Fragment 回填输入框、锁定 Post 选项、显示「返回我的主页」按钮
（`ListDownloadFragment.kt:348-374`）。目前只有列表页内部的 item 能触发它。

所以本次真正新增的只有两件事：**管理页的入口**，以及**跨 tab 把请求送过去**。

## 核心约束：`ListDownloadFragment` 是懒创建的

它挂在 `DownloadFragment` 的 ViewPager2 上（`FragmentStateAdapter`），而 `DownloadFragment`
本身又由 `MainActivity` 按需 `add`。两条路径下它在用户点昵称的那一刻并不存在：

1. **点下载完成通知冷启动**：`DownloadService.kt:183` 构造的是
   `MainActivity.intentFor(this, TAB_MANAGE)`，`selectTab` 只 `add` `ManageFragment`,
   `DownloadFragment` 压根没被创建。「下完一批 → 点通知进来 → 翻着看 → 想拉某个作者的主页」
   正是这个场景。
2. **ViewHolder 被回收**：`FragmentStateAdapter.onViewRecycled` 会
   `beginTransaction().remove(fragment)`。只有 2 页时 RecyclerView 的视图缓存通常留得住，
   但那是缓存行为，不是 API 保证。

换 tab 顺序能解决"冷启动落在下载 tab"这一条，但解决不了上面两条。**功能的正确性不能依赖
tab 顺序**——顺序是产品决定，可能再变。

因此中转站用 `StateFlow<AuthorPostsRequest?>` + 显式 `consume()`，而不是
`SharedFlow(replay = 0)`：请求要能在中转站里"等着"，直到懒创建出来的消费者上线。这与 CLAUDE.md
里 `DownloadEvents` 那条既有约定是同一个思路——一次性事件 + `onResume` 整表回查兜底，因为
"进程被杀的场景事件不会补发"。

## 架构

### 新增 `viewmodel/ShellNavViewModel`

Activity 级共享的外壳导航中转站，三方各自 `by activityViewModels()` 拿同一实例。只有导航状态，
不碰网络与数据库。

```kotlin
data class AuthorPostsRequest(
    val secUserId: String,
    val nickname: String,
    val originTab: Int,   // MainActivity.TAB_MANAGE
)

class ShellNavViewModel : ViewModel() {
    private val _authorPostsRequest = MutableStateFlow<AuthorPostsRequest?>(null)
    val authorPostsRequest: StateFlow<AuthorPostsRequest?>

    private var authorPostsOrigin: Int? = null

    fun requestAuthorPosts(secUserId: String, nickname: String, originTab: Int)
    fun consumeAuthorPostsRequest()      // 置 null，防止配置变更后重放跳转
    fun takeAuthorPostsOrigin(): Int?    // 返回键用：读并清
    fun clearAuthorPostsOrigin()         // 退出作者模式时清
}
```

`authorPostsOrigin` 与 request **分开存放**，因为两者生命周期不同：request 一被消费就该清掉
（否则转屏后重放一次跳转），而来源 tab 要活到用户退出作者模式为止。

### 数据流

```
ManageGridAdapter：昵称点击（videoAuthorSecUserId 非空、非多选态）
  │  onAuthorClick(entity)
  ▼
ManageVideoFragment / ManageImageFragment
  │  shellNav.requestAuthorPosts(secUserId, userName, TAB_MANAGE)
  ▼
ShellNavViewModel.authorPostsRequest ── StateFlow 持有，等消费者上线
  ├─▶ MainActivity 观察 → bottomNav.selectedItemId = nav_download（幂等）
  ├─▶ DownloadFragment 观察 → viewPagerDownload.setCurrentItem(POS_LIST, false)（幂等）
  └─▶ ListDownloadFragment 观察 → 消费并 consumeAuthorPostsRequest()
```

前两个观察者只做幂等的切 tab，**只有终点消费**。三者都用 `repeatOnLifecycle(STARTED)`：被隐藏的
tab 页停在 `STARTED`（`MainActivity` 的约定 2），订阅是活的；`StateFlow` 又会把当前值立刻发给新
订阅者，所以"页面刚被创建出来"这一刻正好能拿到。

### 必须避开的竞态：外部入口不走一次性事件

`onAuthorPostsClicked()` 现在靠 `emit(EnterAuthorPostsMode)` 通知 Fragment。外部入口**不能**
复用这条路：Fragment 在同一个 `repeatOnLifecycle` 块里 `launch` 多个收集器，如果 shellNav 那个
先跑并同步调进 ViewModel，`events` 收集器可能还没建立订阅——`SharedFlow(replay = 0)` 在无订阅者
时直接丢弃，跳转就静默失效了。依赖 `launch` 顺序来规避太脆。

两个入口在 **Fragment 侧**汇合：

| 入口 | 触发者 | 路径 |
|------|--------|------|
| 列表页内部（既有） | ViewModel | `onAuthorPostsClicked(id)` → 查 `items` → `markAuthorPostsMode()` + emit 事件 → Fragment `enterAuthorPostsMode(url)` |
| 管理页（新增） | Fragment | 消费 request → `viewModel.markAuthorPostsMode(nickname)` → 直接调 `enterAuthorPostsMode(url)` |

`ListDownloadViewModel` 从 `onAuthorPostsClicked` 抽出 `markAuthorPostsMode(nickname)`
（置 `authorPostsMode` + `status` + `publish()`），两个入口共用；Fragment 的私有
`enterAuthorPostsMode(authorUrl)` 保持原样，两个入口也共用——回填输入框、滚回顶部、
`parseAndLoad(url, ListKindChoice.Post)` 一行都不用改。URL 拼接收进 `ListDownloadViewModel`
的 companion，两处引用同一份。

### 返回键

`ListDownloadFragment` 注册自己的 `OnBackPressedCallback`（注册最晚 → 优先级高于 `MainActivity`
的兜底）：

```
isEnabled = 处于作者模式 && shellNav 有 originTab && 本页 RESUMED
handleOnBackPressed(): exitAuthorPostsMode(); 切回 takeAuthorPostsOrigin()
```

**用 `RESUMED` 而不是 `!isHidden` 判定"本页在前台"**：`ListDownloadFragment` 是孙辈，父
`DownloadFragment` 被 hide 时它自己的 `isHidden` 仍是 false，光看这个会在管理页按返回时被它抢走。
而 `FragmentStateAdapter` 只把当前页设为 `RESUMED`、`MainActivity` 只把可见 tab 设为 `RESUMED`,
两层叠加后"`ListDownloadFragment` 处于 RESUMED" 恰好等价于"它是可见 tab 的可见子页"。所以在
`onResume` / `onPause` 里开关这个 callback，外加 `render()` 里状态变化时刷一次。

「返回我的主页」按钮那条路径也要 `clearAuthorPostsOrigin()`——用户手动退出作者模式后，返回键
不该再回管理页。

作者模式期间用户改输入框重新解析（种类选项被锁在 Post，只能再解析成 Post），`authorPostsMode`
与 `originTab` 都**保持不变**，返回键仍回管理页。这是有意的：用户是从管理页进来的，中途换个作者
不改变"从哪来"。

## UI 改动

### 昵称做成可点击的作者行

`item_manage_video.xml` 里把裸的 `tvUsername` 包进横向容器，容器本身当点击目标：

```xml
<!-- 作者行：可点击，跳去加载 TA 的发布作品 -->
<LinearLayout
    android:id="@+id/authorRow"
    android:layout_width="wrap_content"          <!-- 热区只包图标+文字 -->
    android:layout_height="wrap_content"
    android:minHeight="32dp"                     <!-- Material 最小触控目标 -->
    android:gravity="center_vertical"
    android:orientation="horizontal"
    android:background="?attr/selectableItemBackground">

    <ImageView
        android:id="@+id/ivAuthorIcon"
        android:layout_width="13dp"
        android:layout_height="13dp"
        android:layout_marginEnd="4dp"
        android:src="@drawable/ic_author_posts"
        app:tint="?android:attr/textColorSecondary"
        android:contentDescription="@null" />

    <TextView android:id="@+id/tvUsername" ... />   <!-- 原属性不变 -->
</LinearLayout>
```

三个决定：

- **图标复用 `ic_author_posts`**（列表下载页「查看 TA 的 Post」按钮用的 Material person 图标），
  两个入口共用同一个视觉符号，语义可迁移。用独立 `ImageView` 而不是 `drawableStart`,
  是因为该 vector 固有尺寸 24dp，作为 compound drawable 会按固有尺寸渲染，在 11sp 文字旁太大，
  而 TextView 没有给 compound drawable 设尺寸的 XML 属性——那就只能再复制一份小尺寸 vector。
  多一层 `LinearLayout` 比多一份 pathData 副本划算。
- **`wrap_content` 而不是 `match_parent`**：右侧空白不可点，避免误触。父容器是 `match_parent`
  的纵向 `LinearLayout`，会把这一行夹在卡片宽度内，长昵称的 `ellipsize="end"` 照常生效。
- **`background` 常驻 XML**，不在代码里切。`videoAuthorSecUserId` 为空时只把 `isClickable`
  置 false + 隐藏图标，不可点的 View 自然不起水波，Adapter 里不用解析主题属性。

管理页宫格是 2 列（`GridLayoutManager(requireContext(), 2)`），卡片宽约 175dp，放得下。

### `ManageGridAdapter` 新增回调

```kotlin
class ManageGridAdapter(
    ...
    /** 点击作者行：请求加载该作者的发布作品。secUserId 为空的旧记录不会触发。 */
    private val onAuthorClick: (entity: DownloadedVideoEntity) -> Unit = {},
)
```

`bindClickListeners` 里的分支与既有 `userTagContainer` 那段严格对齐：

```kotlin
val canOpenAuthor = entity.videoAuthorSecUserId.isNotBlank()
ivAuthorIcon.visibility = if (canOpenAuthor) View.VISIBLE else View.GONE
if (canOpenAuthor) {
    authorRow.contentDescription = ctx.getString(R.string.manage_author_posts_desc, entity.userName)
    authorRow.setOnClickListener {
        if (inSelectionMode) onSelectionToggle(awemeId) else onAuthorClick(entity)
    }
} else {
    authorRow.setOnClickListener(null)
    authorRow.isClickable = false        // ViewHolder 复用，两个分支都得显式写
    authorRow.contentDescription = null
}
```

**多选态点昵称 = 切换选中，不跳转**——与标签行现有行为一致，多选时整张卡片任何位置都只做勾选。

`onAuthorClick` 默认空实现，**视频 Tab 和图片 Tab 都接上**（同一 Adapter、同一 entity 字段，
零额外成本；图集作者一样有主页）。两个 Fragment 各加一行：

```kotlin
onAuthorClick = { entity ->
    shellNav.requestAuthorPosts(entity.videoAuthorSecUserId, entity.userName, MainActivity.TAB_MANAGE)
}
```

### 交换下载页的两个子 tab

`DownloadFragment` 里改三处，都在同一文件：`POS_LIST = 0` / `POS_SINGLE = 1`、`createFragment`
的 when、`TabLayoutMediator` 里的 tab 文案。两个常量是 private companion，无外部引用；也没有把
`currentItem` 持久化到 SharedPreferences，所以交换顺序不会让老用户看到错位的选中项。

**顺带修一个由此暴露的启动开销**：`ListDownloadFragment.onViewCreated` 里
`BatchDownloadCoordinator.createNoMediaFile(...)` 是主线程磁盘 IO，换序后它从"用户切到列表 tab
时才付"变成"每次冷启动都付"。包进 `viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO)`
即可——它只是为封面目录建 `.nomedia`，没有任何东西等它完成。`onScreenResumed()` 本来就在协程里，
不动。

## 边界与降级

| 情况 | 行为 | 依据 |
|------|------|------|
| `videoAuthorSecUserId` 为空 | 昵称行不显示图标、点不动 | 该字段 v5 加入，当前库 v14，只有 v1–v4 时期的记录会空。做成"可点但报错"不如直接看不出可点——与 `diggCount <= 0` 就隐藏点赞徽标同一思路 |
| 作者账号私密 / 无公开作品 | 接口返回空列表，落到现有 `ListStatus.Loaded(total = 0)` 空态 | 无需新代码 |
| Cookie / 票据失效（403、419、200 空包） | `DouyinListApi.dynamicGetBody` 抛 `DouyinAuthException` → `handleListLoadError` → 弹「重新登录 / 同步 Cookie」引导，登录返回后自动重试 | **完全复用**：外部入口最终调的就是 `parseAndLoad(url, ListKindChoice.Post)`。`awaitingLoginRetry` 本来就在 ViewModel 里，跳 WebView 期间 Fragment 被回收也不丢 |
| 连点两个不同作者 | 后者覆盖 `StateFlow` 里的前者；`parseAndLoad` 开头的 `listLoadJob?.cancel()` 取消上一次加载 | 已有逻辑 |
| 加载中转屏 | request 已被 `consume()` 置 null，不重放跳转 | `consume()` 的目的 |
| 进程被杀后重启 | 两个 ViewModel 都是内存态，作者模式随之消失，返回键回归外壳兜底——无残留导航状态 | — |

### 已知限制（不在本次范围）

进程被杀重启后，`etUrlInput` 会被系统的 View 状态恢复填回作者主页 URL（`onViewStateRestored`
晚于 `onViewCreated` 里那句 `setText(DOUYIN_DEFAULT_HOME_URL)`），但 `authorPostsMode` 已丢，
界面显示的是"我的主页"那套 chrome、输入框里却是作者 URL。列表是空的，点一下 FAB 就自洽。这是
**既有行为**，列表页内部入口一样如此，不是本次引入的。

## 测试策略

**不加自动化测试**。改动全部落在 Fragment / Adapter / XML / 导航层，不引 Robolectric 就测不到；
CLAUDE.md 明确记着「没有为 ViewModel 写测试，也没有加测试依赖」，且 `libs.versions.toml` 里
`junit = "4.14-SNAPSHOT"` 当前解析不到，`./gradlew test` 会失败在依赖解析。为一个导航改动引入
Robolectric + 修 junit 版本，代价远大于收益。

代之以手动验证清单。前两条是重点——它们正好是本方案与"只换 tab 顺序"的分野：

1. 冷启动 → 管理页 → 点昵称 → 落在列表下载页，输入框是作者 URL，自动加载，「返回我的主页」可见
2. **点下载完成通知冷启动**（落 `TAB_MANAGE`，`DownloadFragment` 从未创建）→ 点昵称 → 同样成功。
   这条通不过，中转站的设计就白做了
3. 加载途中转屏 → 不重复跳转、不重复加载
4. 作者模式下按返回键 → 回到管理页，且筛选条件与滚动位置都还在
5. 跳过去后改点「返回我的主页」→ 留在下载页；再按返回 → 退出 App（origin 已清，不该再回管理页）
6. 多选态点昵称 → 只勾选，不跳转
7. 旧记录（`videoAuthorSecUserId` 空）→ 无图标、点不动
8. 图片 Tab 点昵称 → 同样跳转
9. Cookie 失效时点昵称 → 弹重新登录引导，登录回来自动重试
10. 冷启动第一屏是「列表下载」子 tab
11. `./gradlew assembleDebug` 通过

## 改动文件清单

| 文件 | 改动 |
|------|------|
| `viewmodel/ShellNavViewModel.kt` | **新增** |
| `activity/MainActivity.kt` | 观察 request → 切下载 tab |
| `fragment/DownloadFragment.kt` | 交换子 tab 顺序；观察 request → `setCurrentItem(POS_LIST)` |
| `fragment/ListDownloadFragment.kt` | 消费 request；返回键 callback；`createNoMediaFile` 挪 IO 线程 |
| `viewmodel/ListDownloadViewModel.kt` | 抽出 `markAuthorPostsMode(nickname)`，URL 拼接收进 companion |
| `adapter/ManageGridAdapter.kt` | 新增 `onAuthorClick` 回调与作者行绑定 |
| `fragment/ManageVideoFragment.kt`、`ManageImageFragment.kt` | 各接一行 `onAuthorClick` |
| `res/layout/item_manage_video.xml` | 昵称包成 `authorRow` |
| `res/values/strings.xml` | 新增 `manage_author_posts_desc`（无障碍描述） |

## 文档更新范围

**只改本功能相关段落**：CLAUDE.md 架构图加 `ShellNavViewModel`；新增一小节说明跨 tab 导航中转站
与"为什么是 StateFlow 而不是一次性事件"；记下下载页子 tab 顺序与管理页的新入口。

CLAUDE.md 里还有一批被上一个 commit（页面改造为 tab+页面结构）放陈的描述——架构图仍写着
`ManageActivity` / `SettingsActivity`，「管理页的筛选栈」一节还在说「`ManageActivity` 只管
Toolbar/菜单/抽屉」，「持久化」一节还在说「由 MainActivity Toolbar 的设置图标进入」。这些**不在
本次范围**，单列为后续待办，避免一个导航改动的 diff 里夹大篇文档重写。
