package com.blitz.downloader.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.blitz.downloader.R
import com.blitz.downloader.data.db.DownloadedVideoDao.AuthorCount

/**
 * 作者筛选抽屉里的作者列表 Adapter：每行「作者昵称 + 作品数量」。
 *
 * 全量数据由 [submit] 传入（已按作品数倒序），内部用 [filter] 做客户端昵称模糊过滤，
 * 无需重新查库。选中项高亮，供用户看到当前正按哪个作者筛选。
 *
 * @param onAuthorClick 点击某作者时回调，传昵称。
 */
class AuthorFilterAdapter(
    private val onAuthorClick: (name: String) -> Unit,
) : RecyclerView.Adapter<AuthorFilterAdapter.ViewHolder>() {

    private val all = mutableListOf<AuthorCount>()
    private val shown = mutableListOf<AuthorCount>()
    private var query: String = ""
    private var selectedName: String? = null

    /** 传入全量作者数据（应已按作品数倒序），并保持当前搜索词过滤。 */
    fun submit(list: List<AuthorCount>) {
        all.clear()
        all.addAll(list)
        applyFilter()
    }

    /** 客户端昵称模糊过滤（大小写不敏感）。 */
    fun filter(q: String) {
        query = q.trim()
        applyFilter()
    }

    /** 设置当前选中的作者（高亮），传 null 取消。 */
    fun setSelected(name: String?) {
        selectedName = name
        notifyDataSetChanged()
    }

    fun itemCountShown(): Int = shown.size

    private fun applyFilter() {
        shown.clear()
        if (query.isEmpty()) {
            shown.addAll(all)
        } else {
            all.filterTo(shown) { it.name.contains(query, ignoreCase = true) }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_author_filter, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(shown[position], shown[position].name == selectedName)
    }

    override fun getItemCount(): Int = shown.size

    companion object {
        /** 选中行背景：半透明多巴胺粉。 */
        private const val SELECTED_BG = 0x22FF3D80
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvAuthorName)
        private val tvCount: TextView = itemView.findViewById(R.id.tvAuthorCount)

        fun bind(item: AuthorCount, selected: Boolean) {
            tvName.text = item.name.ifBlank { itemView.context.getString(R.string.manage_author_unknown) }
            tvCount.text = item.count.toString()
            // 选中项淡粉底色高亮（与多巴胺粉主色呼应），未选中透明
            itemView.setBackgroundColor(if (selected) SELECTED_BG else Color.TRANSPARENT)
            itemView.setOnClickListener { onAuthorClick(item.name) }
        }
    }
}
