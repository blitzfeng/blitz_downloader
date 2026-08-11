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
 * 只承载导航状态：没有 Context、没有网络与数据库操作。各方各自 `by activityViewModels()`
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
 * 所以请求必须能在中转站里"等着"，直到消费者上线并显式消费。
 * **不要**改成 `SharedFlow(replay = 0)`——无订阅者时 emit 会被直接丢弃，跳转静默失效。
 * 这与 `DownloadEvents` 那条约定并不矛盾：那里是"事件 + onResume 整表回查兜底"，
 * 两条路径分工；这里没有兜底路径可用，状态就得自己留住。
 *
 * ### 铁律：一条 latch 一个消费者，谁消费谁清值
 *
 * 本类的每条 `StateFlow` 都是一条**闩锁（latch）**：置值 → 唯一那个消费者读到 → 它自己清值。
 * 一次跨 tab 业务需要几个动作，就开几条 latch，而**不是**让多个地方观察同一条流、
 * 由其中之一负责清。后者曾经就是本类的实现，并且是错的：
 *
 * - `StateFlow` 是**合并（conflated）**语义。收集器的 slot 被唤醒后读的是 `_state.value` 的
 *   **当时值**，不是唤醒它的那个值。所以"负责清值"的那个消费者若先被唤醒，它会在同一次
 *   `setValue` 的派发过程中把值改回 null；其余收集器随后醒来读到 null，而它们的 `oldState`
 *   本来也是 null（订阅时的初始值），`StateFlowImpl.collect` 里 `oldState != newState` 的判定
 *   认为"值没变"→ **直接跳过 emit，连回调都不进**。它们的动作就此静默丢失。
 * - slot 唤醒顺序 = 内部 slots 数组的下标顺序，而 `AbstractSharedFlow.allocateSlot()` 从
 *   `nextIndex` 轮转分配，反复订阅/退订后顺序会洗牌，**没有任何保证**。
 * - 更糟的是有一条把顺序系统性倒过来的路径：API 29+ 上 Activity 的 `ON_START` 由
 *   `ReportFragment.onActivityPostStarted` 派发，**晚于** `FragmentActivity.onStart()` 里的
 *   `mFragments.dispatchStart()`。也就是每次 stop→start 循环（按 Home 再回来、转屏）之后，
 *   Fragment 侧的收集器都**先于** Activity 侧的收集器重新订阅。冷启动那一次可能侥幸是对的
 *   （懒创建的页面订阅最晚），按一次 Home 回来就坏——这正是终审抓到的 Critical 缺陷。
 * - "把清值推到下一帧"（`view?.post { ... }`）不是修法：那是把正确性押在帧边界上，更脆。
 *
 * 所以：**判断一条流是否安全，只看它有几个消费者。** 新增跨 tab 业务时照此拆 latch，
 * 不要图省事复用别人的流。当前三条 latch 与它们唯一的消费者：
 *
 * | latch | 唯一消费者 | 消费方法 |
 * |-------|-----------|----------|
 * | [pendingTab] | [com.blitz.downloader.activity.MainActivity] | [consumePendingTab] |
 * | [pendingDownloadListTab] | [com.blitz.downloader.fragment.DownloadFragment] | [consumePendingDownloadListTab] |
 * | [authorPostsRequest] | [com.blitz.downloader.fragment.ListDownloadFragment] | [consumeAuthorPostsRequest] |
 */
class ShellNavViewModel : ViewModel() {

    private val _authorPostsRequest = MutableStateFlow<AuthorPostsRequest?>(null)

    /**
     * 待处理的作者作品请求。**唯一消费者**是
     * [com.blitz.downloader.fragment.ListDownloadFragment]，读到非 null 后必须调
     * [consumeAuthorPostsRequest]。别再给它加第二个观察者，理由见类注释的「铁律」一节。
     *
     * ### 已知行为：请求没有时效，可能被"延迟传送"
     *
     * 本 latch 会一直等到消费者上线，**没有上界**——这是懒创建换来的代价。可达路径：
     * 用户点作者名 → 外壳切到下载 tab，但 ViewPager2 还没完成首次 layout
     * （[com.blitz.downloader.fragment.ListDownloadFragment] 尚未创建）→ 用户立刻点「设置」tab
     * → 请求仍挂着 → 等他以后某次回到下载 tab，本页才被创建并消费 → 界面莫名进入某个作者的
     * 作品模式，而用户已经不记得自己点过。
     *
     * **有意不加过期阈值**：时间窗只有"切到下载 tab 到首次 layout 完成"这一两帧，影响也仅是
     * 多加载一次列表；而加过期就得引入时间戳与时钟基准（`elapsedRealtime` 还要考虑进程重启），
     * 收益不匹配。写在这里是为了让后人别把它当 bug 反复排查——**它是记录在案的取舍**。
     */
    val authorPostsRequest: StateFlow<AuthorPostsRequest?> = _authorPostsRequest.asStateFlow()

    private val _pendingTab = MutableStateFlow<Int?>(null)

    /**
     * 待切换的底部导航 tab（取值见 `MainActivity.TAB_*`）。
     *
     * **唯一消费者**是外壳 [com.blitz.downloader.activity.MainActivity]，消费后调
     * [consumePendingTab]。
     */
    val pendingTab: StateFlow<Int?> = _pendingTab.asStateFlow()

    private val _pendingDownloadListTab = MutableStateFlow(false)

    /**
     * 待把下载页的子 tab 切到「列表下载」。
     *
     * **唯一消费者**是 [com.blitz.downloader.fragment.DownloadFragment]，消费后调
     * [consumePendingDownloadListTab]。
     *
     * 单独开一条流而不是让 `DownloadFragment` 跟着观察 [authorPostsRequest]：那样两个消费者
     * 共享一条 conflated 流，正确性会依赖 slot 唤醒顺序（见类注释的「铁律」一节）。
     */
    val pendingDownloadListTab: StateFlow<Boolean> = _pendingDownloadListTab.asStateFlow()

    /**
     * 发起作者作品请求的来源 tab。返回键据此回到来源。
     *
     * 与 [authorPostsRequest] 分开存放，理由见类注释。
     *
     * **它不是 flow，改动它的调用点必须自己触发一次返回键状态刷新。**
     * [com.blitz.downloader.fragment.ListDownloadFragment.updateBackCallback] 读的是
     * [hasAuthorPostsOrigin] 的当时值，没有任何机制能让它对本字段的变化自动做出反应。
     * 目前三个改动点（[requestAuthorPosts] 后由消费流程顺带 `render`、[takeAuthorPostsOrigin]、
     * [clearAuthorPostsOrigin]）都在调用侧补了刷新；将来新增第四个清来源的入口时漏一次，
     * 返回键就会停在错误的启用态（作者模式已退出却还拦返回，或反之）。
     * 现在**不**改成 flow 是因为收益不足——若哪天调用点多到记不住，再改。
     */
    private var authorPostsOrigin: Int? = null

    /**
     * 当前是否存在"从某个 tab 跳过来"的上下文，供返回键判断要不要拦。
     *
     * 这是**读一次就过期的瞬时值**，不是可观察状态：约束见 [authorPostsOrigin] 的注释。
     */
    val hasAuthorPostsOrigin: Boolean get() = authorPostsOrigin != null

    /**
     * 请求加载某作者的发布作品：一次置三条 latch（底部 tab、下载页子 tab、请求本身），
     * 三条各自被唯一的消费者取走，彼此顺序无关。
     *
     * @param secUserId 作者的 `sec_user_id`；空串直接忽略（旧记录没有这个字段）。
     * @param nickname  作者昵称，仅用于界面提示文案。
     * @param originTab 发起请求的 tab，取值见 `MainActivity.TAB_*`。
     * @param targetTab 请求要落地的底部 tab（即下载页），取值见 `MainActivity.TAB_*`。
     *                  由调用方传入而非在本类里写死，是为了不让 ViewModel 依赖 Activity。
     */
    fun requestAuthorPosts(secUserId: String, nickname: String, originTab: Int, targetTab: Int) {
        if (secUserId.isBlank()) return
        authorPostsOrigin = originTab
        _authorPostsRequest.value = AuthorPostsRequest(secUserId, nickname)
        _pendingTab.value = targetTab
        _pendingDownloadListTab.value = true
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

    fun consumePendingDownloadListTab() {
        _pendingDownloadListTab.value = false
    }

    /** 读并清来源 tab：返回键只该生效一次。调用后记得刷返回键状态，见 [authorPostsOrigin]。 */
    fun takeAuthorPostsOrigin(): Int? {
        val origin = authorPostsOrigin
        authorPostsOrigin = null
        return origin
    }

    /**
     * 用户手动退出作者模式（点「返回我的主页」）时清掉来源，返回键不该再回去。
     *
     * 调用后记得刷返回键状态，见 [authorPostsOrigin]。
     */
    fun clearAuthorPostsOrigin() {
        authorPostsOrigin = null
    }
}
