package com.blitz.downloader.activity

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.blitz.downloader.R
import com.blitz.downloader.databinding.ActivityMainBinding
import com.blitz.downloader.ui.ListDownloadFragment
import com.blitz.downloader.ui.SingleDownloadFragment
import com.blitz.downloader.util.DouyinCookieStore
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    private val binding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DouyinCookieStore.init(this)
        DouyinCookieStore.restoreIntoClient()
//        enableEdgeToEdge()
        setContentView(binding.root)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        // status bar 高度 → Toolbar 顶部 padding，令深色 Toolbar 背景填满状态栏区域。
        // 导航栏高度 → 根布局底部 padding，避免内容被底部导航栏遮挡。
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(navBars.left, 0, navBars.right, navBars.bottom)
            toolbar.setPadding(0, statusBars.top, 0, 0)
            insets
        }

        val tabLayout: TabLayout = findViewById(R.id.tabLayout)
        val viewPager: ViewPager2 = findViewById(R.id.viewPager)

        viewPager.adapter = MainPagerAdapter(this)


        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "单视频下载"
                1 -> "列表下载"
                else -> ""
            }
        }.attach()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_manage -> {
                onManageClicked()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun onManageClicked() {
        startActivity(Intent(this, ManageActivity::class.java))
    }

    private class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> SingleDownloadFragment()
            1 -> ListDownloadFragment()
            else -> throw IllegalStateException()
        }
    }
}
