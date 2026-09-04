package com.blitz.downloader.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.RecyclerView
import com.blitz.downloader.R

/**
 * 管理页按作者筛选后，列表上方展示的「该作者高频标签」快捷筛选块。
 *
 * 与 [TagFilterAdapter] 语义不同：没有「全部」伪选项，每个标签独立可切换；点击回调
 * [onToggle] 只传单个标签名，由宿主（[com.blitz.downloader.viewmodel.ManageViewModel.toggleAuthorHighFreqTag]）
 * 决定怎么改筛选条件——这里不维护选中集合，选中态由 [submit] 每次传入的 [selected] 决定，
 * 保证与筛选条件的唯一权威（`ManageFilterState.tags`）始终一致。
 *
 * 多巴胺配色：按下标从 6 色调色板循环取色（不按标签名哈希——避免短列表里两个不同标签
 * 凑巧分到同一个颜色）。选中态不额外做描边/阴影，只在文字前加「✓ 」前缀，背景色不变。
 */
class AuthorHighFreqTagAdapter(
    private val onToggle: (tag: String) -> Unit,
) : RecyclerView.Adapter<AuthorHighFreqTagAdapter.ViewHolder>() {

    private var tags: List<String> = emptyList()
    private var selected: Set<String> = emptySet()

    fun submit(newTags: List<String>, newSelected: Set<String>) {
        tags = newTags
        selected = newSelected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_author_high_freq_tag, parent, false) as TextView
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tag = tags[position]
        holder.bind(tag, tag in selected, PALETTE[position % PALETTE.size])
        holder.itemView.setOnClickListener { onToggle(tag) }
    }

    override fun getItemCount(): Int = tags.size

    class ViewHolder(val tv: TextView) : RecyclerView.ViewHolder(tv) {
        fun bind(tag: String, isSelected: Boolean, colorRes: Int) {
            tv.text = if (isSelected) "✓ $tag" else tag
            val tinted = DrawableCompat.wrap(tv.background.mutate())
            DrawableCompat.setTint(tinted, ContextCompat.getColor(tv.context, colorRes))
            tv.background = tinted
        }
    }

    private companion object {
        /** 6 色循环，见 colors.xml 里 `dopamine_chip_*` 的注释。 */
        val PALETTE = intArrayOf(
            R.color.dopamine_chip_magenta,
            R.color.dopamine_chip_orange,
            R.color.dopamine_yellow,
            R.color.dopamine_fourth_green,
            R.color.dopamine_chip_teal,
            R.color.dopamine_chip_purple,
        )
    }
}
