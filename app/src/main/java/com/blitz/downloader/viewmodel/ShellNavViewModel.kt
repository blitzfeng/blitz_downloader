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
