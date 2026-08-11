# 管理页点击作者名 → 加载 TA 的发布作品 · 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 管理页宫格卡片的作者昵称变成可点击入口，点一下切到「下载 → 列表下载」并用数据库里的 `videoAuthorSecUserId` 加载该作者的发布作品；同时把「列表下载」子 tab 提到第一位。

**Architecture:** 新增 Activity 级 `ShellNavViewModel` 作跨 tab 导航中转站，用 `StateFlow` + 显式 `consume()` 持有请求（`ListDownloadFragment` 是 ViewPager2 懒创建的，一次性事件会丢）。加载逻辑完全复用列表页已有的「查看 TA 的 Post」模式，只新增入口与传递机制。

**Tech Stack:** Kotlin / Android View 体系（XML + View Binding，无 Compose）、Material Components、ViewPager2 + FragmentStateAdapter、Kotlin Coroutines + StateFlow、Room（只读既有字段）。

**Spec:** `docs/superpowers/specs/2026-08-11-manage-author-post-jump-design.md`

## Global Constraints

- Kotlin / JVM target **11**；minSdk 24 / compile & target SDK 36。
- **无 Compose**，UI 全部 XML + View Binding + Fragment；UI 风格用 **Material Design**。
- **无依赖注入**：ViewModel 直接取 `BlitzApp.instance.xxxRepository`。本计划不新增任何 Gradle 依赖（`androidx.fragment:fragment-ktx:1.8.5` 已在，`androidx.activity.viewModels` 已被 `TagManageActivity` 使用，`activityViewModels` 可直接用）。
- **ViewModel 不碰 `R.string`**、不持 Activity / Fragment / View 引用。
- 列表数据走 `StateFlow` + `repeatOnLifecycle(STARTED)`；一次性动作走 `SharedFlow(replay = 0)`，**不加 replay**。
- 注释与文档一律**中文**。
- **不加自动化测试**（spec 已论证：改动全在 Fragment / Adapter / XML / 导航层，且 `libs.versions.toml` 里 `junit = "4.14-SNAPSHOT"` 当前解析不到，`./gradlew test` 会失败在依赖解析）。每个 Task 的验证门槛是 `./gradlew assembleDebug` 通过 + 该 Task 描述的静态检查。功能验证由开发者在真机自行完成。
- **不改** `api/` `download/` `data/` `net/` 的任何内部逻辑。
- 数据库**不动**：`videoAuthorSecUserId` 是既有列（v5 引入），当前 version 14 保持不变。

## 与 spec 的一处偏差

Spec 里 `AuthorPostsRequest` 同时带 `originTab`，又说来源 tab 要单独存放（因为两者生命周期不同）。两处存同一个值是冗余的，实施时**只保留 `ShellNavViewModel` 里的私有字段**，`AuthorPostsRequest` 只带 `secUserId` + `nickname`。行为与 spec 描述一致。

另外 spec 只说"返回键切回来源 tab"，没规定 Fragment 怎么让外壳切 tab。本计划新增一条 `ShellNavViewModel.pendingTab`（`StateFlow<Int?>`）由 `MainActivity` 观察消费——这样 Fragment 不用向下强转 `requireActivity() as MainActivity`，与"三条通路全部经 ViewModel"的既有约定一致。

## File Structure

| 文件 | 职责 |
|------|------|
| `viewmodel/ShellNavViewModel.kt`（**新增**） | 外壳导航中转站。两条 `StateFlow`（作者请求、待切换 tab）+ 一个私有来源 tab 字段。无 Context、无 IO。 |
| `activity/MainActivity.kt` | 观察两条 flow → 切底部导航 tab。 |
| `fragment/DownloadFragment.kt` | 子 tab 换序；观察作者请求 → 切到「列表下载」子页。 |
| `fragment/ListDownloadFragment.kt` | 消费作者请求；作者模式下的返回键；`createNoMediaFile` 挪 IO 线程。 |
| `viewmodel/ListDownloadViewModel.kt` | 抽出 `markAuthorPostsMode(nickname)` 供两个入口共用；URL 拼接收进 companion。 |
| `adapter/ManageGridAdapter.kt` | 新增 `onAuthorClick` 回调与作者行绑定。 |
| `fragment/ManageVideoFragment.kt`、`ManageImageFragment.kt` | 各接一行 `onAuthorClick` → 发请求。 |
| `res/layout/item_manage_video.xml` | 昵称包成可点击的 `authorRow`。 |
| `res/values/strings.xml` | 新增 `manage_author_posts_desc`。 |
| `CLAUDE.md` | 补充跨 tab 导航一节、子 tab 顺序、管理页新入口。 |

任务顺序是"先建接收侧、后接触发侧"：Task 1–4 之后链路已完整但没人触发，Task 5 接上入口即端到端可用。

---

### Task 1: `ShellNavViewModel` + 外壳观察

**Files:**
- Create: `app/src/main/java/com/blitz/downloader/viewmodel/ShellNavViewModel.kt`
- Modify: `app/src/main/java/com/blitz/downloader/activity/MainActivity.kt`

**Interfaces:**
- Consumes: `MainActivity.TAB_DOWNLOAD`、`MainActivity.itemIdOf(tab)`（现有 private 方法）。
- Produces（后续 Task 依赖这些确切签名）：
  - `data class AuthorPostsRequest(val secUserId: String, val nickname: String)`
  - `ShellNavViewModel.authorPostsRequest: StateFlow<AuthorPostsRequest?>`
  - `ShellNavViewModel.pendingTab: StateFlow<Int?>`
  - `ShellNavViewModel.hasAuthorPostsOrigin: Boolean`
  - `fun requestAuthorPosts(secUserId: String, nickname: String, originTab: Int)`
  - `fun consumeAuthorPostsRequest()`
  - `fun requestTab(tab: Int)`
  - `fun consumePendingTab()`
  - `fun takeAuthorPostsOrigin(): Int?`
  - `fun clearAuthorPostsOrigin()`

- [ ] **Step 1: 新建 `ShellNavViewModel.kt`**

```kotlin
package com.blitz.downloader.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「加载某作者的发布作品」的跨 tab 请求。
 *
 * 不带来源 tab —— 它存在 [ShellNavViewModel.authorPostsOrigin] 里，生命周期比本请求长：
 * 请求一被消费就置 null（否则配置变更后会重放一次跳转），而来源 tab 要活到用户退出作者模式为止。
 */
data class AuthorPostsRequest(
    val secUserId: String,
    val nickname: String,
)

/**
 * 外壳（底部导航）级别的导航中转站，作用域是 [com.blitz.downloader.activity.MainActivity]。
 *
 * 只承载导航状态：没有 Context、没有网络与数据库操作。三方各自 `by activityViewModels()`
 * （Activity 侧 `by viewModels()`）拿到同一实例。
 *
 * ### 为什么是 StateFlow 而不是一次性事件
 *
 * 请求的最终消费者 [com.blitz.downloader.fragment.ListDownloadFragment] 是**懒创建**的：
 * 它挂在 [com.blitz.downloader.fragment.DownloadFragment] 的 ViewPager2
 * （`FragmentStateAdapter`）上，而 `DownloadFragment` 本身又由 MainActivity 按需 `add`。
 * 两条路径下它在请求发出的那一刻并不存在：
 *
 * 1. **点下载完成通知冷启动**：`DownloadService` 构造的是
 *    `MainActivity.intentFor(this, TAB_MANAGE)`，`selectTab` 只 `add` `ManageFragment`，
 *    `DownloadFragment` 压根没被创建。
 * 2. `FragmentStateAdapter.onViewRecycled` 会 `beginTransaction().remove(fragment)`；
 *    只有两页时 RecyclerView 的视图缓存通常留得住，但那是缓存行为，不是 API 保证。
 *
 * 所以请求必须能在中转站里"等着"，直到消费者上线并显式 [consumeAuthorPostsRequest]。
 * **不要**改成 `SharedFlow(replay = 0)`——无订阅者时 emit 会被直接丢弃，跳转静默失效。
 * 这与 `DownloadEvents` 那条约定并不矛盾：那里是"事件 + onResume 整表回查兜底"，
 * 两条路径分工；这里没有兜底路径可用，状态就得自己留住。
 */
class ShellNavViewModel : ViewModel() {

    private val _authorPostsRequest = MutableStateFlow<AuthorPostsRequest?>(null)

    /** 待处理的作者作品请求。消费者读到非 null 后必须调 [consumeAuthorPostsRequest]。 */
    val authorPostsRequest: StateFlow<AuthorPostsRequest?> = _authorPostsRequest.asStateFlow()

    private val _pendingTab = MutableStateFlow<Int?>(null)

    /** 待切换的底部导航 tab（取值见 `MainActivity.TAB_*`）。外壳消费后调 [consumePendingTab]。 */
    val pendingTab: StateFlow<Int?> = _pendingTab.asStateFlow()

    /**
     * 发起作者作品请求的来源 tab。返回键据此回到来源。
     *
     * 与 [authorPostsRequest] 分开存放，理由见类注释。
     */
    private var authorPostsOrigin: Int? = null

    /** 当前是否存在"从某个 tab 跳过来"的上下文，供返回键判断要不要拦。 */
    val hasAuthorPostsOrigin: Boolean get() = authorPostsOrigin != null

    /**
     * 请求加载某作者的发布作品。
     *
     * @param secUserId 作者的 `sec_user_id`；空串直接忽略（旧记录没有这个字段）。
     * @param nickname  作者昵称，仅用于界面提示文案。
     * @param originTab 发起请求的 tab，取值见 `MainActivity.TAB_*`。
     */
    fun requestAuthorPosts(secUserId: String, nickname: String, originTab: Int) {
        if (secUserId.isBlank()) return
        authorPostsOrigin = originTab
        _authorPostsRequest.value = AuthorPostsRequest(secUserId, nickname)
    }

    /** 消费者处理完请求后调用；不清会让配置变更后重放一次跳转。 */
    fun consumeAuthorPostsRequest() {
        _authorPostsRequest.value = null
    }

    /** 请求外壳切到指定 tab（返回键回到来源 tab 用）。 */
    fun requestTab(tab: Int) {
        _pendingTab.value = tab
    }

    fun consumePendingTab() {
        _pendingTab.value = null
    }

    /** 读并清来源 tab：返回键只该生效一次。 */
    fun takeAuthorPostsOrigin(): Int? {
        val origin = authorPostsOrigin
        authorPostsOrigin = null
        return origin
    }

    /** 用户手动退出作者模式（点「返回我的主页」）时清掉来源，返回键不该再回去。 */
    fun clearAuthorPostsOrigin() {
        authorPostsOrigin = null
    }
}
```

- [ ] **Step 2: `MainActivity` 加导入与字段**

在 `MainActivity.kt` 的 import 区补上（保持字母序插入到既有 import 之间）：

```kotlin
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.blitz.downloader.viewmodel.ShellNavViewModel
import kotlinx.coroutines.launch
```

在 `currentTab` 字段下面加：

```kotlin
    /** 外壳导航中转站，跨 tab 的跳转请求都经它传递，见 [ShellNavViewModel]。 */
    private val shellNav: ShellNavViewModel by viewModels()
```

- [ ] **Step 3: `MainActivity.onCreate` 末尾加观察**

在 `onCreate` 里 `selectTab(startTab)` 这一行**之后**追加：

```kotlin
        observeShellNav()
```

然后在 `onNewIntent` 方法**之前**新增：

```kotlin
    /**
     * 观察 [ShellNavViewModel] 的两条导航请求。
     *
     * 外壳只负责"切到哪个底部 tab"，不关心请求内容——作者请求的实际消费在
     * [com.blitz.downloader.fragment.ListDownloadFragment]，那里才 `consume`。
     * 所以这里读到作者请求时**不清它**，只把 tab 切过去。
     */
    private fun observeShellNav() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    shellNav.authorPostsRequest.collect { request ->
                        if (request == null) return@collect
                        if (currentTab != TAB_DOWNLOAD) {
                            binding.bottomNav.selectedItemId = itemIdOf(TAB_DOWNLOAD)
                        }
                    }
                }
                launch {
                    shellNav.pendingTab.collect { tab ->
                        if (tab == null) return@collect
                        if (currentTab != tab) {
                            binding.bottomNav.selectedItemId = itemIdOf(tab)
                        }
                        shellNav.consumePendingTab()
                    }
                }
            }
        }
    }
```

`Lifecycle` 已在 import 里（既有代码用它做 `setMaxLifecycle`），不用再加。

- [ ] **Step 4: 编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL。此时中转站已能保存请求并切 tab，但还没有任何调用方。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/blitz/downloader/viewmodel/ShellNavViewModel.kt app/src/main/java/com/blitz/downloader/activity/MainActivity.kt
git commit -m "feature: 新增 ShellNavViewModel 作跨 tab 导航中转站"
```

---

### Task 2: 下载页子 tab 换序 + 观察作者请求

**Files:**
- Modify: `app/src/main/java/com/blitz/downloader/fragment/DownloadFragment.kt`

**Interfaces:**
- Consumes: `ShellNavViewModel.authorPostsRequest`（Task 1）。
- Produces: `DownloadFragment` 的 private companion 常量变为 `POS_LIST = 0` / `POS_SINGLE = 1`（无外部引用，仅本文件内使用）。

- [ ] **Step 1: 交换两个位置常量与 `createFragment`**

`DownloadFragment.kt` 底部的 `DownloadPagerAdapter` 与 companion 改成：

```kotlin
    private class DownloadPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int): Fragment = when (position) {
            POS_LIST -> ListDownloadFragment()
            POS_SINGLE -> SingleDownloadFragment()
            else -> throw IllegalStateException("Unknown download tab: $position")
        }
    }

    private companion object {
        /**
         * 「列表下载」置首：批量列表是本项目的主场景
         * （`BatchListDownloadScope.PRIMARY_TARGET_IS_LOGGED_IN_LISTS = true`）。
         *
         * 顺带的好处是冷启动落在下载 tab 时 [ListDownloadFragment] 会立刻被创建。
         * 但**不要**让任何功能的正确性依赖这一点——顺序是产品决定，而且"点下载完成通知
         * 冷启动"会落在管理 tab，那条路径下本页压根不会被创建。跨 tab 请求靠
         * [com.blitz.downloader.viewmodel.ShellNavViewModel] 的 StateFlow 兜住。
         */
        const val POS_LIST = 0
        const val POS_SINGLE = 1
    }
```

- [ ] **Step 2: 同步 `TabLayoutMediator` 的文案**

`onViewCreated` 里那段改成：

```kotlin
        TabLayoutMediator(binding.tabLayoutDownload, binding.viewPagerDownload) { tab, position ->
            tab.text = when (position) {
                POS_LIST -> "列表下载"
                POS_SINGLE -> "单视频下载"
                else -> ""
            }
        }.attach()
```

- [ ] **Step 3: 加导入与 `shellNav` 字段**

import 区补上：

```kotlin
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.blitz.downloader.viewmodel.ShellNavViewModel
import kotlinx.coroutines.launch
```

在 `binding` 属性下面加：

```kotlin
    private val shellNav: ShellNavViewModel by activityViewModels()
```

- [ ] **Step 4: 观察请求，切到列表子页**

`onViewCreated` 里 `TabLayoutMediator(...).attach()` 之后追加：

```kotlin
        // 跨 tab 的作者作品请求：本层只负责把子 tab 切到「列表下载」，请求本身交给
        // ListDownloadFragment 消费。切换是幂等的，重复收到同一请求不会有副作用。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                shellNav.authorPostsRequest.collect { request ->
                    if (request == null) return@collect
                    if (binding.viewPagerDownload.currentItem != POS_LIST) {
                        binding.viewPagerDownload.setCurrentItem(POS_LIST, false)
                    }
                }
            }
        }
```

- [ ] **Step 5: 编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/blitz/downloader/fragment/DownloadFragment.kt
git commit -m "feature: 列表下载置为下载页首个子 tab，并观察跨 tab 作者请求"
```

---

### Task 3: `ListDownloadViewModel` 抽出共用入口（纯重构）

**Files:**
- Modify: `app/src/main/java/com/blitz/downloader/viewmodel/ListDownloadViewModel.kt:706-722`（`onAuthorPostsClicked`）与 companion（约 `:800-809`）

**Interfaces:**
- Produces:
  - `fun markAuthorPostsMode(nickname: String)`（public，供 Fragment 的外部入口调用）
  - `ListDownloadViewModel.Companion.authorPostsUrl(secUserId: String): String`

行为不变，只是把"置模式状态"和"拼 URL"从 `onAuthorPostsClicked` 里抽出来，让 Task 4 的外部入口复用。

- [ ] **Step 1: 改写 `onAuthorPostsClicked` 并新增 `markAuthorPostsMode`**

把现有的

```kotlin
    fun onAuthorPostsClicked(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        val secUid = item.authorSecUserId.takeIf { it.isNotBlank() }
        if (secUid == null) {
            emit(ListDownloadEvent.AuthorIdMissing)
            return
        }
        authorPostsMode = true
        status = ListStatus.AuthorPostsMode(item.authorNickname)
        publish()
        emit(
            ListDownloadEvent.EnterAuthorPostsMode(
                authorUrl = "https://www.douyin.com/user/$secUid?from_tab_name=main",
                nickname = item.authorNickname,
            ),
        )
    }
```

替换为：

```kotlin
    fun onAuthorPostsClicked(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        val secUid = item.authorSecUserId.takeIf { it.isNotBlank() }
        if (secUid == null) {
            emit(ListDownloadEvent.AuthorIdMissing)
            return
        }
        markAuthorPostsMode(item.authorNickname)
        emit(
            ListDownloadEvent.EnterAuthorPostsMode(
                authorUrl = authorPostsUrl(secUid),
                nickname = item.authorNickname,
            ),
        )
    }

    /**
     * 置「查看 TA 的 Post」模式的状态。
     *
     * 两个入口共用：
     * - 列表页内部的 [onAuthorPostsClicked]，随后 emit [ListDownloadEvent.EnterAuthorPostsMode]
     *   驱动视图；
     * - 管理页跳转过来的外部入口，由 Fragment 消费 [com.blitz.downloader.viewmodel.ShellNavViewModel]
     *   的请求后**直接调用本方法**。
     *
     * 外部入口**不能**复用那个一次性事件：[events] 是 `replay = 0` 的 SharedFlow，
     * Fragment 刚创建时若 events 收集器还没建立订阅，emit 会被直接丢弃、跳转静默失效，
     * 而收集器的建立顺序取决于 `launch` 调度，靠排序规避太脆。
     */
    fun markAuthorPostsMode(nickname: String) {
        authorPostsMode = true
        status = ListStatus.AuthorPostsMode(nickname)
        publish()
    }
```

- [ ] **Step 2: companion 里加 URL 拼接**

在 `companion object` 内（`PREVIEW_SUBTITLE_MAX` 之后）加：

```kotlin
        /**
         * 作者主页 URL。两个入口共用同一份拼接，改这里即可同时影响列表内跳转与管理页跳转。
         *
         * `from_tab_name=main` 与抖音 PC 站真实跳转参数保持一致。
         */
        fun authorPostsUrl(secUserId: String): String =
            "https://www.douyin.com/user/$secUserId?from_tab_name=main"
```

- [ ] **Step 3: 确认没有遗漏的硬编码 URL**

Run: `git grep -n "from_tab_name=main" -- app/src/main/java`
Expected: 只有两处——`ListDownloadViewModel` companion 里新加的这处，以及 `ListDownloadFragment` 的 `indexMainPage`（那是"我的主页"，用 `AppConfig.MY_SEC_USER_ID`，与本次无关，**不要**改）。

- [ ] **Step 4: 编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/blitz/downloader/viewmodel/ListDownloadViewModel.kt
git commit -m "refactor: 抽出 markAuthorPostsMode 与 authorPostsUrl 供两个入口共用"
```

---

### Task 4: `ListDownloadFragment` 消费请求 + 返回键 + 启动开销

**Files:**
- Modify: `app/src/main/java/com/blitz/downloader/fragment/ListDownloadFragment.kt`

**Interfaces:**
- Consumes: `ShellNavViewModel.authorPostsRequest` / `consumeAuthorPostsRequest()` / `hasAuthorPostsOrigin` / `takeAuthorPostsOrigin()` / `clearAuthorPostsOrigin()` / `requestTab(tab)`（Task 1）；`ListDownloadViewModel.markAuthorPostsMode(nickname)` 与 `ListDownloadViewModel.authorPostsUrl(secUserId)`（Task 3）。
- Produces: 端到端接收侧完成。本 Task 之后链路完整，只差 Task 5 的触发入口。

- [ ] **Step 1: 加导入**

import 区补上：

```kotlin
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.activityViewModels
import com.blitz.downloader.activity.MainActivity
import com.blitz.downloader.viewmodel.AuthorPostsRequest
import com.blitz.downloader.viewmodel.ShellNavViewModel
import kotlinx.coroutines.Dispatchers
```

- [ ] **Step 2: 加 `shellNav` 字段与返回键 callback**

在 `private val viewModel: ListDownloadViewModel by viewModels()` 之后加：

```kotlin
    private val shellNav: ShellNavViewModel by activityViewModels()

    /**
     * 作者模式下拦返回键，退出作者模式并切回跳转来源 tab。
     *
     * 初始 `false`，启停条件见 [updateBackCallback]。切 tab 不直接向下强转
     * `requireActivity() as MainActivity`，而是经 [ShellNavViewModel.requestTab] 让外壳去做,
     * 与「三条通路全部经 ViewModel」的既有约定一致。
     */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // 先取来源（读并清），再退出作者模式：exitAuthorPostsMode 里的 clear 就成了幂等空操作
            val origin = shellNav.takeAuthorPostsOrigin()
            exitAuthorPostsMode()
            if (origin != null) shellNav.requestTab(origin)
        }
    }
```

- [ ] **Step 3: `createNoMediaFile` 挪到 IO 线程**

`onViewCreated` 里把

```kotlin
        BatchDownloadCoordinator.createNoMediaFile(File(BatchDownloadCoordinator.COVER_SUBDIR))
```

替换为

```kotlin
        // 建 .nomedia 是纯磁盘 IO，且没有任何东西等它完成。本页现在是冷启动第一屏
        // （见 DownloadFragment 的 POS_LIST），留在主线程会直接计入启动耗时。
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            BatchDownloadCoordinator.createNoMediaFile(File(BatchDownloadCoordinator.COVER_SUBDIR))
        }
```

- [ ] **Step 4: 注册返回键 callback**

`onViewCreated` 里 `observeViewModel()` 之后追加：

```kotlin
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
```

- [ ] **Step 5: 观察并消费作者请求**

`observeViewModel()` 里，在既有三个 `launch` **之后**追加第四个：

```kotlin
                // 放在 events 收集器之后只是书写顺序，本路径不依赖它——见 consumeAuthorPostsRequest
                launch {
                    shellNav.authorPostsRequest.collect { request ->
                        if (request != null) consumeAuthorPostsRequest(request)
                    }
                }
```

- [ ] **Step 6: 新增消费方法与返回键开关**

在「`「查看 TA 的 Post」模式的视图切换`」注释块下、`renderAuthorPostsChrome` **之前**插入：

```kotlin
    /**
     * 消费管理页发来的「加载 TA 的发布作品」请求。
     *
     * 复用与列表内部入口相同的 [enterAuthorPostsMode]，但**不经过**
     * [com.blitz.downloader.viewmodel.ListDownloadEvent.EnterAuthorPostsMode] 事件：
     * 那是 `replay = 0` 的 SharedFlow，本页刚被 ViewPager2 创建出来时 events 收集器
     * 可能还没建立订阅，emit 会被丢弃。所以这里自己置状态 + 自己触发加载。
     */
    private fun consumeAuthorPostsRequest(request: AuthorPostsRequest) {
        viewModel.markAuthorPostsMode(request.nickname)
        enterAuthorPostsMode(ListDownloadViewModel.authorPostsUrl(request.secUserId))
        shellNav.consumeAuthorPostsRequest()
    }

    /**
     * 只有「处于作者模式」+「有跳转来源」+「本页在前台」三条同时成立才拦返回键。
     *
     * **用 RESUMED 判定前台，不能用 `!isHidden`**：本 Fragment 是孙辈
     * （MainActivity → DownloadFragment → ViewPager2 → 本页），父页被 `hide` 时自己的
     * `isHidden` 仍是 false，只看它会在管理页按返回时把事件抢走。而 `FragmentStateAdapter`
     * 只把当前子页设为 RESUMED、MainActivity 只把可见 tab 设为 RESUMED，两层叠加后
     * 「本页 RESUMED」恰好等价于「它是可见 tab 的可见子页」。
     */
    private fun updateBackCallback() {
        if (_binding == null) return
        backCallback.isEnabled =
            viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                viewModel.uiState.value.isAuthorPostsMode &&
                shellNav.hasAuthorPostsOrigin
    }
```

- [ ] **Step 7: 在生命周期与渲染处调用开关**

`onResume` 改成：

```kotlin
    override fun onResume() {
        super.onResume()
        viewModel.onScreenResumed()
        updateBackCallback()
    }
```

在 `onResume` 之后新增：

```kotlin
    /**
     * 让出返回键。
     *
     * 这里**直接置 false** 而不调 [updateBackCallback]：`onPause()` 回调时
     * `viewLifecycleOwner.lifecycle.currentState` 还是 RESUMED（ON_PAUSE 是在
     * `performPause()` 调完 `onPause()` 之后才分发的），走 updateBackCallback 会误判成在前台。
     */
    override fun onPause() {
        super.onPause()
        backCallback.isEnabled = false
    }
```

`render(state: ListDownloadUiState)` 末尾（`binding.tvStatus.text = statusText(state)` 之后）加：

```kotlin
        updateBackCallback()
```

- [ ] **Step 8: 「返回我的主页」按钮也要清来源**

把 `exitAuthorPostsMode()` 改成：

```kotlin
    /** 退出「查看 TA 的 Post」模式，恢复默认状态。 */
    private fun exitAuthorPostsMode() {
        binding.etUrlInput.setText(indexMainPage)
        binding.etUrlInput.clearFocus()
        binding.rgListKind.check(R.id.rbKindPost)
        viewModel.exitAuthorPostsMode()
        // 用户手动退出后，返回键不该再回管理页
        shellNav.clearAuthorPostsOrigin()
        updateBackCallback()
    }
```

- [ ] **Step 9: 确认返回键优先级不会互抢**

静态检查，不用运行：`ManageFragment` 的 `backCallback` 只在「本页可见 且（抽屉打开 或 多选中）」时 `isEnabled = true`，本 Fragment 的只在「本页 RESUMED 且作者模式且有来源」时为 true——两者的启用条件互斥（一个要求管理页可见，一个要求下载页可见），所以谁注册得晚都不会互抢。确认 `ManageFragment.kt` 的 `updateBackCallback()` 仍是这个条件，若已被改动则在此处记录并停下询问。

- [ ] **Step 10: 编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 11: 提交**

```bash
git add app/src/main/java/com/blitz/downloader/fragment/ListDownloadFragment.kt
git commit -m "feature: 列表下载页消费跨 tab 作者请求，作者模式下返回键回到来源 tab"
```

---

### Task 5: 管理页作者行入口（布局 + Adapter + 两个 Tab）

**Files:**
- Modify: `app/src/main/res/layout/item_manage_video.xml:147-156`（作者名那一段）
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/blitz/downloader/adapter/ManageGridAdapter.kt`
- Modify: `app/src/main/java/com/blitz/downloader/fragment/ManageVideoFragment.kt`
- Modify: `app/src/main/java/com/blitz/downloader/fragment/ManageImageFragment.kt`

**Interfaces:**
- Consumes: `ShellNavViewModel.requestAuthorPosts(secUserId, nickname, originTab)`（Task 1）、`MainActivity.TAB_MANAGE`。
- Produces: `ManageGridAdapter` 构造参数新增 `onAuthorClick: (entity: DownloadedVideoEntity) -> Unit = {}`（有默认值，不破坏其他调用方）。

- [ ] **Step 1: 布局——昵称包成可点击的作者行**

`item_manage_video.xml` 里把

```xml
            <!-- 作者名 -->
            <TextView
                android:id="@+id/tvUsername"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:maxLines="1"
                android:ellipsize="end"
                android:textSize="11sp"
                android:textStyle="bold"
                android:textColor="?android:attr/textColorPrimary" />
```

替换为

```xml
            <!--
                作者行：点击加载 TA 的发布作品。
                wrap_content 而不是 match_parent —— 热区只包住图标+文字，右侧空白不可点，避免误触。
                父容器是 match_parent 的纵向 LinearLayout，会把本行夹在卡片宽度内，
                所以长昵称的 ellipsize 照常生效。
                background 常驻这里、不在代码里切：没有 sec_user_id 的旧记录只把 isClickable
                置 false（不可点的 View 不会起水波），Adapter 里就不用解析主题属性。
            -->
            <LinearLayout
                android:id="@+id/authorRow"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:minHeight="32dp"
                android:gravity="center_vertical"
                android:orientation="horizontal"
                android:background="?attr/selectableItemBackground">

                <!-- 与列表下载页「查看TA的Post」按钮共用同一个图标，语义可迁移 -->
                <ImageView
                    android:id="@+id/ivAuthorIcon"
                    android:layout_width="13dp"
                    android:layout_height="13dp"
                    android:layout_marginEnd="4dp"
                    android:src="@drawable/ic_author_posts"
                    app:tint="?android:attr/textColorSecondary"
                    android:contentDescription="@null" />

                <TextView
                    android:id="@+id/tvUsername"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:maxLines="1"
                    android:ellipsize="end"
                    android:textSize="11sp"
                    android:textStyle="bold"
                    android:textColor="?android:attr/textColorPrimary" />
            </LinearLayout>
```

`xmlns:app` 已在根元素声明，不用再加。

- [ ] **Step 2: 新增无障碍描述字符串**

`strings.xml` 里 `<string name="manage_unwatched_label">未看过</string>` 之前插入：

```xml
    <!-- 管理页卡片作者行的无障碍描述：点击加载该作者的发布作品 -->
    <string name="manage_author_posts_desc">加载 %1$s 的发布作品</string>
```

- [ ] **Step 3: `ManageGridAdapter` 加构造参数**

在类注释的交互列表里，`- **单击用户标签行**...` 之后插入一行：

```
 * - **单击作者行**：非多选模式下调用 [onAuthorClick]（跳去加载该作者的发布作品）；
 *   `videoAuthorSecUserId` 为空的旧记录不可点。
```

`@param onTagAreaClick` 之后加：

```
 * @param onAuthorClick       非多选模式下点击作者行时回调，传递 [DownloadedVideoEntity]。
```

构造参数在 `onTagAreaClick` 之后、`supportsUserTags` 之前插入：

```kotlin
    private val onAuthorClick: (entity: DownloadedVideoEntity) -> Unit = {},
```

- [ ] **Step 4: ViewHolder 加两个视图引用**

`inner class ViewHolder` 里 `private val username: TextView = ...` 这一行**之前**插入：

```kotlin
        private val authorRow: LinearLayout = itemView.findViewById(R.id.authorRow)
        private val authorIcon: ImageView = itemView.findViewById(R.id.ivAuthorIcon)
```

- [ ] **Step 5: 新增 `bindAuthorRow` 并在 `bind` 里调用**

在 `bindClickListeners` 方法**之前**插入：

```kotlin
        /**
         * 作者行绑定：可点则显示图标并注册点击，否则彻底不可点。
         *
         * `videoAuthorSecUserId` 是 v5 才加入的列（当前库 v14），只有很早期下载的记录会为空。
         * 做成"可点但弹 Toast 报错"不如让"点不动"在视觉上就看得出来——与 `diggCount <= 0`
         * 就隐藏点赞徽标是同一个思路。
         */
        fun bindAuthorRow(entity: DownloadedVideoEntity) {
            val canOpen = entity.videoAuthorSecUserId.isNotBlank()
            authorIcon.visibility = if (canOpen) View.VISIBLE else View.GONE
            if (canOpen) {
                val label = entity.userName.ifBlank { entity.awemeId }
                authorRow.contentDescription =
                    itemView.context.getString(R.string.manage_author_posts_desc, label)
                authorRow.setOnClickListener {
                    // 多选态下卡片任何位置都只做勾选，与 userTagContainer 一致
                    if (inSelectionMode) onSelectionToggle(entity.awemeId) else onAuthorClick(entity)
                }
            } else {
                authorRow.contentDescription = null
                // ViewHolder 会复用，两个分支都要显式写：setOnClickListener 会把 clickable 置 true
                authorRow.setOnClickListener(null)
                authorRow.isClickable = false
            }
        }
```

`bind(...)` 方法里 `bindClickListeners(entity.awemeId)` 这一行**之前**插入：

```kotlin
            bindAuthorRow(entity)
```

- [ ] **Step 6: `ManageVideoFragment` 接上入口**

import 区补上：

```kotlin
import androidx.fragment.app.activityViewModels
import com.blitz.downloader.activity.MainActivity
import com.blitz.downloader.viewmodel.ShellNavViewModel
```

`manageViewModel` 属性之后加：

```kotlin
    /** 外壳级导航中转站（作用域是 Activity），点作者名跳批量下载用。 */
    private val shellNav: ShellNavViewModel by activityViewModels()
```

`setupGrid` 里的 `ManageGridAdapter(...)` 加一行参数（放在 `onTagAreaClick` 之后）：

```kotlin
            onAuthorClick = { entity ->
                shellNav.requestAuthorPosts(
                    secUserId = entity.videoAuthorSecUserId,
                    nickname = entity.userName,
                    originTab = MainActivity.TAB_MANAGE,
                )
            },
```

- [ ] **Step 7: `ManageImageFragment` 接上同一个入口**

import 区补上：

```kotlin
import androidx.fragment.app.activityViewModels
import com.blitz.downloader.activity.MainActivity
import com.blitz.downloader.viewmodel.ShellNavViewModel
```

`manageViewModel` 属性之后加：

```kotlin
    /** 外壳级导航中转站（作用域是 Activity），点作者名跳批量下载用。 */
    private val shellNav: ShellNavViewModel by activityViewModels()
```

`ManageGridAdapter(...)` 加一行参数（放在 `onSelectionToggle` 之后、`supportsUserTags` 之前）：

```kotlin
            onAuthorClick = { entity ->
                shellNav.requestAuthorPosts(
                    secUserId = entity.videoAuthorSecUserId,
                    nickname = entity.userName,
                    originTab = MainActivity.TAB_MANAGE,
                )
            },
```

图片 Tab 一并接上：同一个 Adapter、同一个 entity 字段，零额外成本，图集作者一样有主页。

- [ ] **Step 8: 编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Lint 检查布局改动**

Run: `./gradlew lint`
Expected: 不出现关于 `item_manage_video.xml` 的**新增**错误（既有 warning 不用管；`tools:ignore="SmallSp"` 那几处是原有的）。若报 `app:tint` 无法解析，说明根元素少了 `xmlns:app`——回到 Step 1 确认。

- [ ] **Step 10: 提交**

```bash
git add app/src/main/res/layout/item_manage_video.xml app/src/main/res/values/strings.xml app/src/main/java/com/blitz/downloader/adapter/ManageGridAdapter.kt app/src/main/java/com/blitz/downloader/fragment/ManageVideoFragment.kt app/src/main/java/com/blitz/downloader/fragment/ManageImageFragment.kt
git commit -m "feature: 管理页作者名可点击，跳批量下载加载 TA 的发布作品"
```

---

### Task 6: 文档

**Files:**
- Modify: `CLAUDE.md`

只改与本功能相关的段落。CLAUDE.md 里另有一批被上一个 commit（页面改造为 tab+页面结构）放陈的描述（架构图仍写 `ManageActivity` / `SettingsActivity`，「管理页的筛选栈」一节仍说「`ManageActivity` 只管 Toolbar/菜单/抽屉」，「持久化」一节仍说「由 MainActivity Toolbar 的设置图标进入」）——**不在本次范围**，不要顺手改。

- [ ] **Step 1: 架构图补上新 ViewModel**

「整体架构」代码块里 viewmodel 那几行，在 `SettingsViewModel, TagManageViewModel)` 之后补一行：

```
           ShellNavViewModel — Activity 级跨 tab 导航中转)
```

- [ ] **Step 2: 新增「跨 tab 导航」一节**

在「### 管理页的筛选栈」这一节**之前**插入：

```markdown
### 跨 tab 导航（`ShellNavViewModel`）

底部导航三页之间的跳转请求走 Activity 级的 `ShellNavViewModel`，目前只有一条业务：
**管理页点卡片上的作者名 → 下载页加载 TA 的发布作品**。

链路（每一跳都幂等，只有终点消费）：

```
ManageGridAdapter 作者行点击（videoAuthorSecUserId 非空、非多选态）
  → ManageVideoFragment / ManageImageFragment
  → ShellNavViewModel.requestAuthorPosts(secUserId, nickname, originTab = TAB_MANAGE)
      ├─ MainActivity 观察 → 切到 TAB_DOWNLOAD
      ├─ DownloadFragment 观察 → setCurrentItem(POS_LIST)
      └─ ListDownloadFragment 观察 → markAuthorPostsMode + enterAuthorPostsMode，然后 consume
```

三条别踩的规则：

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
- **返回键用 `RESUMED` 判定"本页在前台"，不能用 `!isHidden`**：`ListDownloadFragment` 是孙辈，
  父页被 `hide` 时它自己的 `isHidden` 仍是 false。来源 tab 存在 `ShellNavViewModel` 的私有
  字段里（与 request 分开存活：request 一被消费就清，来源要活到退出作者模式）。

`videoAuthorSecUserId` 为空的旧记录（该列 v5 引入）作者行不显示图标、也不可点。
```

- [ ] **Step 3: 记下下载页子 tab 顺序**

「整体架构」代码块里 fragment 那一行，把

```
fragment (ListDownloadFragment, SingleDownloadFragment, ManageVideoFragment, ManageImageFragment)
```

改成

```
fragment (DownloadFragment → ListDownloadFragment / SingleDownloadFragment（列表下载在前）,
          ManageFragment → ManageVideoFragment / ManageImageFragment, SettingsFragment)
```

并在「约定 / 容易踩的坑」列表末尾追加一条：

```markdown
- 下载页的两个子 tab 顺序是**「列表下载」在前、「单视频下载」在后**（`DownloadFragment.POS_LIST = 0`），因为批量列表是主场景（`BatchListDownloadScope.PRIMARY_TARGET_IS_LOGGED_IN_LISTS = true`）。副作用是列表页成了冷启动第一屏，所以它的 `.nomedia` 创建已挪到 IO 线程——新增开屏逻辑时别再往主线程放磁盘 IO。
```

- [ ] **Step 4: 校对**

Run: `git diff CLAUDE.md`
Expected: 只有上面四处改动，没有顺手改到 `ManageActivity` / `SettingsActivity` 等超范围段落。

- [ ] **Step 5: 提交**

```bash
git add CLAUDE.md
git commit -m "docs: 补充跨 tab 导航中转站与下载页子 tab 顺序"
```

---

## 完成后

功能验证由开发者在真机自行完成。若发现问题，重点怀疑这三处（都是本设计里最容易出错的地方）：

1. 点下载完成通知冷启动（落管理 tab，`DownloadFragment` 从未创建）后点作者名——这条通不过说明 `StateFlow` 中转没起作用。
2. 作者模式下按返回键——回不到管理页说明 `updateBackCallback` 的 RESUMED 判定或 `originTab` 存取有问题。
3. 多选态点作者名——若跳走了说明 `bindAuthorRow` 里的 `inSelectionMode` 分支没生效。
