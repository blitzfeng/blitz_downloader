package com.blitz.downloader.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.blitz.downloader.databinding.FragmentDownloadBinding
import com.google.android.material.tabs.TabLayoutMediator

/**
 * 底部导航「下载」页：内部保留原有的「单视频下载 / 列表下载」两个子 Tab。
 *
 * 由 [com.blitz.downloader.activity.MainActivity] 以 add + show/hide 常驻挂载，
 * 因此切走再切回时子 Tab 位置与已加载的列表都还在。
 */
class DownloadFragment : Fragment() {

    private var _binding: FragmentDownloadBinding? = null
    private val binding get() = _binding!!

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
                POS_SINGLE -> "单视频下载"
                POS_LIST -> "列表下载"
                else -> ""
            }
        }.attach()
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
            POS_SINGLE -> SingleDownloadFragment()
            POS_LIST -> ListDownloadFragment()
            else -> throw IllegalStateException("Unknown download tab: $position")
        }
    }

    private companion object {
        const val POS_SINGLE = 0
        const val POS_LIST = 1
    }
}
