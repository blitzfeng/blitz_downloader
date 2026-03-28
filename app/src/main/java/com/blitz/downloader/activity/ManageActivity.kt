package com.blitz.downloader.activity

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.blitz.downloader.R
import com.blitz.downloader.databinding.ActivityManageBinding
import com.blitz.downloader.ui.ManageImageFragment
import com.blitz.downloader.ui.ManageTabFragment
import com.blitz.downloader.ui.ManageVideoFragment
import com.google.android.material.tabs.TabLayoutMediator

class ManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManageBinding
    private var isInSelectionMode = false
    private var currentSelectionCount = 0
    /** onCreate 时保存的默认返回箭头图标，退出多选模式时恢复。 */
    private var defaultNavIcon: Drawable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        defaultNavIcon = binding.toolbar.navigationIcon

        binding.viewPager.adapter = ManagePagerAdapter(this)

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.manage_tab_videos)
                1 -> getString(R.string.manage_tab_images)
                else -> ""
            }
        }.attach()

        // Tab 切换时退出当前 Tab 的多选模式
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            private var previousItem = 0
            override fun onPageSelected(position: Int) {
                if (isInSelectionMode) {
                    getTabFragment(previousItem)?.exitSelectionMode()
                }
                previousItem = position
                invalidateOptionsMenu()
            }
        })

        // 返回键：多选模式时先退出多选
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val fragment = getCurrentTabFragment()
                if (fragment != null && fragment.inSelectionMode) {
                    fragment.exitSelectionMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /** 由 Fragment 回调：多选模式或选中数量发生变化。 */
    fun onSelectionChanged(active: Boolean, count: Int) {
        isInSelectionMode = active
        currentSelectionCount = count
        updateToolbar()
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_manage, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val clearInvalid = menu.findItem(R.id.action_clear_invalid)
        val deleteSelected = menu.findItem(R.id.action_delete_selected)

        if (isInSelectionMode) {
            clearInvalid?.isVisible = false
            deleteSelected?.isVisible = true
            deleteSelected?.title = getString(R.string.manage_menu_delete_selected_count, currentSelectionCount)
        } else {
            // 「清除已失效」仅在视频 Tab（position==0）下显示
            val onVideoTab = binding.viewPager.currentItem == 0
            clearInvalid?.isVisible = onVideoTab
            deleteSelected?.isVisible = false
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                handleHomeButton()
                true
            }
            R.id.action_delete_selected -> {
                getCurrentTabFragment()?.handleDeleteSelected()
                true
            }
            R.id.action_clear_invalid -> {
                getCurrentTabFragment()?.handleClearInvalid()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun handleHomeButton() {
        val fragment = getCurrentTabFragment()
        if (fragment != null && fragment.inSelectionMode) {
            fragment.exitSelectionMode()
        } else {
            finish()
        }
    }

    private fun updateToolbar() {
        if (isInSelectionMode) {
            binding.toolbar.setNavigationIcon(R.drawable.ic_close_manage)
            binding.toolbar.title = getString(R.string.manage_selected_count, currentSelectionCount)
        } else {
            binding.toolbar.navigationIcon = defaultNavIcon
            binding.toolbar.title = getString(R.string.manage_title)
        }
    }

    private fun getCurrentTabFragment(): ManageTabFragment? =
        getTabFragment(binding.viewPager.currentItem)

    private fun getTabFragment(position: Int): ManageTabFragment? =
        supportFragmentManager.findFragmentByTag("f$position") as? ManageTabFragment

    private class ManagePagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> ManageVideoFragment()
            1 -> ManageImageFragment()
            else -> throw IllegalStateException("Unknown manage tab: $position")
        }
    }
}
