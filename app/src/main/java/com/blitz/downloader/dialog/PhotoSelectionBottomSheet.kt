package com.blitz.downloader.dialog

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.blitz.downloader.R
import com.blitz.downloader.fragment.ListDownloadFragment
import com.blitz.downloader.model.VideoItemUiModel
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

/**
 * 图集选图底部弹窗：横向滑动逐张预览，每张图下面一个勾选框，**默认全选**。
 *
 * 只负责收集"哪几张要下载"，不碰列表状态——关闭时把结果通过 [show] 的 `onResult` 回调交还给
 * [ListDownloadFragment]，由它决定是否保留该图集的勾选。无论是点「完成」、点弹窗外部、
 * 侧滑还是按返回键关闭，回调都只走一次（见 [show] 里的 `delivered` 标志）。
 *
 * 结果的归一化约定与 [VideoItemUiModel.selectedImageIndices] 一致：
 * 全选返回 `null`（而不是全量下标集合），一张都没选返回空集合。
 */
object PhotoSelectionBottomSheet {

    /**
     * @param imageUrls 图集全部图片 URL（顺序即展示顺序）。
     * @param initialSelection 初始勾选下标；`null` 表示全选。
     * @param editable false 时隐藏所有勾选框，纯预览（用于已下载的图集）。
     * @param onResult 弹窗关闭时回调一次：`null` = 全选，空集合 = 一张都没选。
     *                 `editable = false` 时不会回调。
     */
    fun show(
        context: Context,
        imageUrls: List<String>,
        initialSelection: Set<Int>?,
        editable: Boolean,
        onResult: (Set<Int>?) -> Unit,
    ) {
        if (imageUrls.isEmpty()) return

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_photo_selection, null)
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(view)

        val tvTitle = view.findViewById<TextView>(R.id.tvPhotoSheetTitle)
        val tvCount = view.findViewById<TextView>(R.id.tvPhotoSheetCount)
        val tvIndicator = view.findViewById<TextView>(R.id.tvPhotoPageIndicator)
        val btnToggleAll = view.findViewById<MaterialButton>(R.id.btnPhotoToggleAll)
        val btnDone = view.findViewById<MaterialButton>(R.id.btnPhotoDone)
        val pager = view.findViewById<ViewPager2>(R.id.photoPager)

        // 预览区高度按屏幕算（布局里的 360dp 只是占位）：小屏上写死高度会把底部的
        // 「全不选 / 完成」挤出可视区域，而这两个按钮是关闭弹窗前唯一的操作入口。
        pager.layoutParams = pager.layoutParams.apply {
            height = (context.resources.displayMetrics.heightPixels * 0.55f).toInt()
        }
        // 一上来就展开到完整高度，不停在折叠态（默认 peek 高度会截掉半个预览区）
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED

        // 勾选状态用可变集合承载，翻页时由适配器回读，页面被回收也不会丢。
        val selected = (initialSelection ?: imageUrls.indices.toSet()).toMutableSet()

        tvTitle.setText(if (editable) R.string.photo_pick_title else R.string.photo_pick_title_preview)

        fun refreshCount() {
            tvCount.text = context.getString(R.string.photo_pick_count, selected.size, imageUrls.size)
            btnToggleAll.setText(
                if (selected.size == imageUrls.size) R.string.photo_pick_deselect_all
                else R.string.photo_pick_select_all
            )
        }

        fun refreshIndicator(position: Int) {
            tvIndicator.text = context.getString(
                R.string.photo_pick_page_indicator, position + 1, imageUrls.size,
            )
        }

        val adapter = PhotoPagerAdapter(
            imageUrls = imageUrls,
            editable = editable,
            isChecked = { index -> index in selected },
            onCheckedChanged = { index, checked ->
                if (checked) selected.add(index) else selected.remove(index)
                refreshCount()
            },
        )
        pager.adapter = adapter
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = refreshIndicator(position)
        })

        if (editable) {
            btnToggleAll.setOnClickListener {
                if (selected.size == imageUrls.size) selected.clear() else selected.addAll(imageUrls.indices)
                refreshCount()
                // 勾选框在页面内，改完要重绑当前页与左右缓存页
                adapter.notifyItemRangeChanged(0, imageUrls.size)
            }
        } else {
            btnToggleAll.visibility = View.GONE
            tvCount.visibility = View.GONE
        }
        btnDone.setOnClickListener { dialog.dismiss() }

        refreshCount()
        refreshIndicator(0)

        // 点外部、侧滑、返回键、点「完成」都会走到 dismiss，这里统一交付一次结果。
        var delivered = false
        dialog.setOnDismissListener {
            if (!editable || delivered) return@setOnDismissListener
            delivered = true
            onResult(if (selected.size == imageUrls.size) null else selected.toSet())
        }
        dialog.show()
    }

    private class PhotoPagerAdapter(
        private val imageUrls: List<String>,
        private val editable: Boolean,
        private val isChecked: (Int) -> Boolean,
        private val onCheckedChanged: (Int, Boolean) -> Unit,
    ) : RecyclerView.Adapter<PhotoPagerAdapter.PageViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo_selection_page, parent, false)
            return PageViewHolder(view)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            holder.bind(imageUrls[position], position)
        }

        override fun getItemCount(): Int = imageUrls.size

        inner class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val image: ImageView = view.findViewById(R.id.ivPhotoPage)
            private val checkBox: CheckBox = view.findViewById(R.id.cbPhotoPage)

            fun bind(url: String, position: Int) {
                image.load(url) {
                    crossfade(true)
                    placeholder(R.drawable.ic_video_placeholder)
                    error(R.drawable.ic_video_placeholder)
                }
                if (!editable) {
                    checkBox.visibility = View.GONE
                    return
                }
                checkBox.visibility = View.VISIBLE
                checkBox.setText(R.string.photo_pick_item_checkbox)
                // 先摘监听再回填，避免复用页面时把上一页的状态当成用户操作写回去
                checkBox.setOnCheckedChangeListener(null)
                checkBox.isChecked = isChecked(position)
                checkBox.setOnCheckedChangeListener { _, checked ->
                    onCheckedChanged(position, checked)
                }
            }
        }
    }
}
