package com.blitz.downloader.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.blitz.downloader.R

/**
 * 管理页标签过滤栏的横向 Adapter。
 *
 * 第一项固定为「全部」（[TAG_ALL]），后续为来自 `tags` 表的标签名。
 * 同时只允许一个标签处于选中状态。
 *
 * @param onTagSelected 选中某标签时回调；传 [TAG_ALL] 表示取消过滤。
 */
class TagFilterAdapter(
    private val onTagSelected: (tag: String) -> Unit,
) : RecyclerView.Adapter<TagFilterAdapter.ViewHolder>() {

    private val tags = mutableListOf(TAG_ALL)
    private var selectedTag: String = TAG_ALL

    fun submitTags(newTags: List<String>) {
        tags.clear()
        tags.add(TAG_ALL)
        tags.addAll(newTags)
        notifyDataSetChanged()
    }

    fun getSelectedTag(): String = selectedTag

    fun resetToAll() {
        if (selectedTag == TAG_ALL) return
        val old = tags.indexOf(selectedTag)
        selectedTag = TAG_ALL
        if (old >= 0) notifyItemChanged(old)
        notifyItemChanged(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tag_filter, parent, false) as TextView
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tag = tags[position]
        holder.bind(tag, tag == selectedTag)
        holder.itemView.setOnClickListener {
            if (selectedTag == tag) return@setOnClickListener
            val oldPos = tags.indexOf(selectedTag)
            selectedTag = tag
            if (oldPos >= 0) notifyItemChanged(oldPos)
            notifyItemChanged(position)
            onTagSelected(tag)
        }
    }

    override fun getItemCount(): Int = tags.size

    class ViewHolder(val tv: TextView) : RecyclerView.ViewHolder(tv) {
        fun bind(tag: String, selected: Boolean) {
            tv.text = if (tag == TAG_ALL) "全部" else tag
            if (selected) {
                tv.setBackgroundResource(R.drawable.bg_tag_chip_selected)
                tv.setTextColor(Color.WHITE)
            } else {
                tv.setBackgroundResource(R.drawable.bg_tag_chip_unselected)
                tv.setTextColor(Color.parseColor("#DD000000"))
            }
        }
    }

    companion object {
        const val TAG_ALL = "__all__"
    }
}
