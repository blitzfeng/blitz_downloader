package com.blitz.downloader.activity

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.blitz.downloader.R
import com.google.android.material.appbar.MaterialToolbar
import java.io.File

/**
 * 图片浏览页：支持左右滑动查看同一图集的全部图片。
 *
 * 入参（Intent extras）：
 * - [EXTRA_FILE_PATH]：图集第一张图的相对路径（如 `Download/bDouyin/images/xxx_01.jpg`）
 * - [EXTRA_TITLE]：显示在 Toolbar 上的标题
 *
 * 通过扫描同目录下具有相同基础名称（去掉末尾 `_\d+` 后缀）的文件，自动枚举图集中的所有图片。
 */
class ImageViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()

        if (filePath.isBlank()) {
            Toast.makeText(this, getString(R.string.player_file_not_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        @Suppress("DEPRECATION")
        val firstFile = File(Environment.getExternalStorageDirectory(), filePath)
        if (!firstFile.exists()) {
            Toast.makeText(this, getString(R.string.player_file_not_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val images = findImageSet(firstFile)
        if (images.isEmpty()) {
            Toast.makeText(this, getString(R.string.player_file_not_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val displayTitle = title.ifBlank { getString(R.string.image_viewer_title) }
        supportActionBar?.title = displayTitle

        val tvIndicator = findViewById<TextView>(R.id.tvPageIndicator)
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)

        if (images.size > 1) {
            tvIndicator.visibility = View.VISIBLE
            tvIndicator.text = getString(R.string.image_viewer_page_indicator, 1, images.size)
        }

        viewPager.adapter = ImagePagerAdapter(images)
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (images.size > 1) {
                    tvIndicator.text = getString(
                        R.string.image_viewer_page_indicator,
                        position + 1,
                        images.size,
                    )
                }
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * 根据图集第一张图，扫描同目录下具有相同基础名（去掉末尾 `_\d+` 后缀）的所有图片文件，
     * 按文件名升序返回。单张图片直接返回该文件。
     */
    private fun findImageSet(firstFile: File): List<File> {
        val dir = firstFile.parentFile ?: return listOf(firstFile)
        val nameNoExt = firstFile.nameWithoutExtension        // e.g., "author_desc_01"
        val baseName = nameNoExt.replace(Regex("_\\d+$"), "")  // e.g., "author_desc"
        val ext = firstFile.extension

        val pattern = Regex("^${Regex.escape(baseName)}_\\d+$")
        val files = dir.listFiles { f ->
            f.isFile &&
                f.extension.equals(ext, ignoreCase = true) &&
                f.nameWithoutExtension.matches(pattern)
        }
        return if (files.isNullOrEmpty()) listOf(firstFile)
        else files.sortedBy { it.name }
    }

    private class ImagePagerAdapter(private val images: List<File>) :
        RecyclerView.Adapter<ImagePagerAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_image_viewer_page, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(images[position])
        }

        override fun getItemCount(): Int = images.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val imageView: ImageView = view.findViewById(R.id.ivPage)

            fun bind(file: File) {
                imageView.load(file) {
                    crossfade(true)
                    placeholder(R.drawable.ic_video_placeholder)
                    error(R.drawable.ic_video_placeholder)
                }
            }
        }
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_TITLE = "extra_title"
    }
}
