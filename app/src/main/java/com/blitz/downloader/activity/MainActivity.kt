package com.blitz.downloader.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.blitz.downloader.R
import com.blitz.downloader.databinding.ActivityMainBinding
import com.blitz.downloader.fragment.DownloadFragment
import com.blitz.downloader.fragment.ManageFragment
import com.blitz.downloader.fragment.SettingsFragment
import com.blitz.downloader.util.DouyinCookieStore
import com.blitz.downloader.util.MediaPermissions

/**
 * 应用主外壳：底部导航栏 + 三个常驻页面（下载 / 管理 / 设置）。
 *
 * 三条约定，改这里之前先看清楚：
 *
 * 1. **页面用 `add` + `show`/`hide`，不用 `replace`**。`replace` 会销毁被切走页面的
 *    Fragment，管理页的筛选条件、滚动位置全丢，其 [com.blitz.downloader.viewmodel.ManageViewModel]
 *    也随之 `onCleared`，正在跑的局域网导出会被掐断。
 * 2. **显示中的页面 `RESUMED`、隐藏的 `STARTED`**（[androidx.fragment.app.FragmentTransaction.setMaxLifecycle]）。
 *    全停在 `RESUMED` 会让三页同时认为自己可见，`onResume` 语义失真；降到 `CREATED` 又会销毁
 *    视图、等价于 `replace`。停在 `STARTED` 既保住视图与 `repeatOnLifecycle(STARTED)` 的订阅，
 *    又让 `onResume` 只在真正切到该页时触发。
 * 3. **本类不持有 Toolbar**，三个页面各自带 Toolbar 并用 Toolbar 自身的菜单 API
 *    （Activity 级 ActionBar 只有一份，三页同时存活时会互相抢占）。
 *
 * 外部入口（下载完成通知、子页面向上导航）经 [EXTRA_TAB] 指定落地 tab，见 [intentFor]。
 */
class MainActivity : AppCompatActivity() {

    private val binding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    /** 当前显示的 tab，取值见 [TAB_DOWNLOAD] / [TAB_MANAGE] / [TAB_SETTINGS]。 */
    private var currentTab: Int = TAB_DOWNLOAD

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必须在 setContentView 之前注册 ActivityResultLauncher
        MediaPermissions.registerAndRequestIfNeeded(this)
        DouyinCookieStore.init(this)
        DouyinCookieStore.restoreIntoClient()
        setContentView(binding.root)

        // 导航栏高度 → 底部导航的底部 padding（否则导航项被系统导航栏压住）；
        // 左右 inset 给根布局。状态栏由各页自己的 Toolbar 承接。
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(navBars.left, 0, navBars.right, 0)
            binding.bottomNav.updatePadding(bottom = navBars.bottom)
            insets
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            selectTab(tabOf(item.itemId))
            true
        }

        // 外壳兜底的返回处理：注册最早 → 优先级最低，页面自己的 callback 先拿到返回事件。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTab != TAB_DOWNLOAD) {
                    binding.bottomNav.selectedItemId = R.id.nav_download
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val startTab = savedInstanceState?.getInt(KEY_CURRENT_TAB) ?: tabFromIntent(intent)
        // 设置 selectedItemId 只在「与当前选中项不同」时才回调监听，所以下面必须再显式调一次
        // selectTab（它自身幂等），否则冷启动落在第一项时页面不会被 add 进来。
        binding.bottomNav.selectedItemId = itemIdOf(startTab)
        selectTab(startTab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyTabFromIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_TAB, currentTab)
    }

    /** 外部 Intent 指定了 tab 就切过去；没指定则保持现状。 */
    private fun applyTabFromIntent(intent: Intent) {
        if (!intent.hasExtra(EXTRA_TAB)) return
        binding.bottomNav.selectedItemId = itemIdOf(tabFromIntent(intent))
    }

    private fun tabFromIntent(intent: Intent): Int =
        intent.getIntExtra(EXTRA_TAB, TAB_DOWNLOAD).takeIf { it in TAB_DOWNLOAD..TAB_SETTINGS }
            ?: TAB_DOWNLOAD

    /**
     * 切到指定 tab：目标页首次出现时 `add`，之后一律 `show` / `hide`。
     * 幂等——重复点当前项不会重建页面，也不会产生多余事务。
     *
     * **必须 `commitNow()`**：`commit()` 是异步排队的，紧接着再调一次本方法时
     * `findFragmentByTag` 还读不到上一次 add 进去的 Fragment，会把同一个页面 add 两份
     * （onCreate 里「设置 selectedItemId 触发监听」+「显式补一次 selectTab」正是这种连调）。
     */
    private fun selectTab(tab: Int) {
        val target = supportFragmentManager.findFragmentByTag(tagOf(tab))
        if (tab == currentTab && target != null && !target.isHidden) return
        currentTab = tab

        supportFragmentManager.beginTransaction().apply {
            for (t in TAB_DOWNLOAD..TAB_SETTINGS) {
                val tag = tagOf(t)
                val existing = supportFragmentManager.findFragmentByTag(tag)
                when {
                    t == tab && existing == null -> {
                        val fragment = createFragment(t)
                        add(R.id.navHost, fragment, tag)
                        setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
                    }
                    t == tab -> {
                        show(existing!!)
                        setMaxLifecycle(existing, Lifecycle.State.RESUMED)
                    }
                    existing != null -> {
                        hide(existing)
                        setMaxLifecycle(existing, Lifecycle.State.STARTED)
                    }
                }
            }
        }.commitNow()
    }

    private fun createFragment(tab: Int): Fragment = when (tab) {
        TAB_DOWNLOAD -> DownloadFragment()
        TAB_MANAGE -> ManageFragment()
        TAB_SETTINGS -> SettingsFragment()
        else -> throw IllegalArgumentException("Unknown tab: $tab")
    }

    private fun tagOf(tab: Int): String = when (tab) {
        TAB_DOWNLOAD -> TAG_DOWNLOAD
        TAB_MANAGE -> TAG_MANAGE
        TAB_SETTINGS -> TAG_SETTINGS
        else -> throw IllegalArgumentException("Unknown tab: $tab")
    }

    private fun itemIdOf(tab: Int): Int = when (tab) {
        TAB_DOWNLOAD -> R.id.nav_download
        TAB_MANAGE -> R.id.nav_manage
        TAB_SETTINGS -> R.id.nav_settings
        else -> throw IllegalArgumentException("Unknown tab: $tab")
    }

    private fun tabOf(itemId: Int): Int = when (itemId) {
        R.id.nav_download -> TAB_DOWNLOAD
        R.id.nav_manage -> TAB_MANAGE
        R.id.nav_settings -> TAB_SETTINGS
        else -> TAB_DOWNLOAD
    }

    companion object {
        /** Intent 里指定落地 tab 的 extra，取值见下面三个 TAB_* 常量。 */
        const val EXTRA_TAB = "com.blitz.downloader.extra.TAB"

        const val TAB_DOWNLOAD = 0
        const val TAB_MANAGE = 1
        const val TAB_SETTINGS = 2

        private const val KEY_CURRENT_TAB = "current_tab"

        // 自定义 tag：不依赖任何容器的内部命名约定，show/hide 与恢复都靠它反查
        private const val TAG_DOWNLOAD = "tab_download"
        private const val TAG_MANAGE = "tab_manage"
        private const val TAG_SETTINGS = "tab_settings"

        /**
         * 构造落在指定 tab 的外壳 Intent。
         *
         * 调用方（下载完成通知等）自行追加 `FLAG_ACTIVITY_SINGLE_TOP or FLAG_ACTIVITY_CLEAR_TOP`，
         * 命中既有实例时走 [onNewIntent] 切 tab，不会新建第二个外壳。
         */
        fun intentFor(context: Context, tab: Int): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_TAB, tab)
    }
}
