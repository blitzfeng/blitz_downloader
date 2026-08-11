package com.blitz.downloader.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.blitz.downloader.databinding.FragmentDownloadBinding
import com.blitz.downloader.viewmodel.ShellNavViewModel
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch

/**
 * 底部导航「下载」页：内部保留原有的「单视频下载 / 列表下载」两个子 Tab。
 *
 * 由 [com.blitz.downloader.activity.MainActivity] 以 add + show/hide 常驻挂载，
 * 因此切走再切回时子 Tab 位置与已加载的列表都还在。
 */
class DownloadFragment : Fragment() {

    private var _binding: FragmentDownloadBinding? = null
    private val binding get() = _binding!!

    private val shellNav: ShellNavViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // status bar 高度 → Toolbar 顶部 padding，令深色 Toolbar 背景填满状态栏区域。
        // 底部导航栏的 inset 由外壳处理，这里只管顶部。
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            binding.toolbarDownload.updatePadding(top = statusBars.top)
            insets
        }

        // 子 Fragment 挂在 childFragmentManager 上（FragmentStateAdapter 的 Fragment 构造器重载）
        binding.viewPagerDownload.adapter = DownloadPagerAdapter(this)

        TabLayoutMediator(binding.tabLayoutDownload, binding.viewPagerDownload) { tab, position ->
            tab.text = when (position) {
                POS_LIST -> "列表下载"
                POS_SINGLE -> "单视频下载"
                else -> ""
            }
        }.attach()

        // 「把子 tab 切到列表下载」这条 latch 只有本页一个消费者，切完自己清值。
        // 请求本体（作者是谁）走另一条 latch，由 ListDownloadFragment 消费——本页**不要**
        // 顺手去观察它：两个消费者共享一条 conflated StateFlow、由其中之一清值，正确性就
        // 依赖了收集器唤醒顺序，理由见 ShellNavViewModel 注释的「铁律」一节。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                shellNav.pendingDownloadListTab.collect { pending ->
                    if (!pending) return@collect
                    if (binding.viewPagerDownload.currentItem != POS_LIST) {
                        binding.viewPagerDownload.setCurrentItem(POS_LIST, false)
                    }
                    shellNav.consumePendingDownloadListTab()
                }
            }
        }
    }

    override fun onDestroyView() {
        // 不把 adapter 置空：FragmentStateAdapter 置空会在 onDestroyView 时机去拆
        // childFragmentManager 里的子 Fragment。ViewPager2 随视图丢弃，adapter 不会泄漏。
        _binding = null
        super.onDestroyView()
    }

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
}
