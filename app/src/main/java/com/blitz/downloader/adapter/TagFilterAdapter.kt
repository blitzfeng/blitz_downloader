package com.blitz.downloader.adapter

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
 * 标签支持**多选叠加**：再次点击已选中的标签取消该标签；
 * 「全部」是互斥项——点它会清空所有已选标签，选中集合为空时它自动回到选中态。
 *
 * 多个标签之间取交集还是并集由设置页决定（`AppSettings.isTagFilterMatchAll`），
 * 与本 Adapter 无关，这里只负责维护选中集合。
 *
 * @param onSelectionChanged 选中集合变化时回调；空集合表示不按标签过滤（即「全部」）。
 */
class TagFilterAdapter(
    private val onSelectionChanged: (tags: Set<String>) -> Unit,
) : RecyclerView.Adapter<TagFilterAdapter.ViewHolder>() {

    private val tags = mutableListOf(TAG_ALL)

    /** 已选中的标签名集合；为空即「全部」。用 LinkedHashSet 保持用户点击顺序，便于调试。 */
    private val selectedTags = linkedSetOf<String>()

    fun submitTags(newTags: List<String>) {
        tags.clear()
        tags.add(TAG_ALL)
        tags.addAll(newTags)
        // 标签可能已被删除/重命名，剔除不再存在的选中项，避免筛出空列表且无法取消
        val stale = selectedTags.filter { it !in newTags }
        if (stale.isNotEmpty()) selectedTags.removeAll(stale.toSet())
        notifyDataSetChanged()
    }

    /** 当前选中的标签集合；空集合表示「全部」（不按标签过滤）。 */
    fun getSelectedTags(): Set<String> = selectedTags.toSet()

    /** 清空选中回到「全部」（供作者筛选等互斥筛选调用）；不触发 [onSelectionChanged]。 */
    fun resetToAll() {
        if (selectedTags.isEmpty()) return
        selectedTags.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tag_filter, parent, false) as TextView
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tag = tags[position]
        val selected = if (tag == TAG_ALL) selectedTags.isEmpty() else tag in selectedTags
        holder.bind(tag, selected)
        holder.itemView.setOnClickListener {
            if (tag == TAG_ALL) {
                // 「全部」是清空动作：已经是空集合就什么都不做
                if (selectedTags.isEmpty()) return@setOnClickListener
                selectedTags.clear()
            } else {
                // 普通标签：切换选中状态，取消最后一个时自动回到「全部」
                if (!selectedTags.remove(tag)) selectedTags.add(tag)
            }
            notifyDataSetChanged()
            onSelectionChanged(getSelectedTags())
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
