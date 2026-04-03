package com.blitz.downloader.ui

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.blitz.downloader.R
import java.util.Collections

/**
 * 标签管理页的列表 Adapter，支持：
 * - 长按/拖拽把手触发拖拽排序（配合外部 [ItemTouchHelper]）
 * - 点击编辑按钮触发重命名回调
 * - 点击删除按钮触发删除回调
 *
 * 拖拽结束后调用方应调用 [getTagList] 取当前顺序并持久化。
 */
class TagManageAdapter(
    private val onEdit: (position: Int, tagName: String) -> Unit,
    private val onDelete: (position: Int, tagName: String) -> Unit,
) : RecyclerView.Adapter<TagManageAdapter.ViewHolder>() {

    private val tags = mutableListOf<String>()

    /** 由 Activity 在初始化和刷新时调用。 */
    fun submitList(list: List<String>) {
        tags.clear()
        tags.addAll(list)
        notifyDataSetChanged()
    }

    /** 返回当前排列顺序（用于拖拽结束后持久化）。 */
    fun getTagList(): List<String> = tags.toList()

    /** ItemTouchHelper 拖拽时每步调用，实时交换相邻条目。 */
    fun moveItem(from: Int, to: Int) {
        if (from < to) {
            for (i in from until to) Collections.swap(tags, i, i + 1)
        } else {
            for (i in from downTo to + 1) Collections.swap(tags, i, i - 1)
        }
        notifyItemMoved(from, to)
    }

    /** 从列表中移除一项（删除确认后调用）。 */
    fun removeAt(position: Int) {
        if (position in tags.indices) {
            tags.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    /** 重命名某项（重命名确认后调用）。 */
    fun renameAt(position: Int, newName: String) {
        if (position in tags.indices) {
            tags[position] = newName
            notifyItemChanged(position)
        }
    }

    /** 在末尾添加一个新标签（新建后调用）。 */
    fun addItem(tagName: String) {
        tags.add(tagName)
        notifyItemInserted(tags.size - 1)
    }

    // ItemTouchHelper 需要访问 ViewHolder 上的 dragHandle
    private var itemTouchHelper: ItemTouchHelper? = null

    fun attachItemTouchHelper(helper: ItemTouchHelper) {
        itemTouchHelper = helper
    }

    // ── RecyclerView.Adapter ──────────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tag_manage, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(tags[position])
    }

    override fun getItemCount(): Int = tags.size

    // ── ViewHolder ────────────────────────────────────────────────────────────

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val dragHandle: ImageView = itemView.findViewById(R.id.ivDragHandle)
        private val tvTagName: TextView = itemView.findViewById(R.id.tvTagName)
        private val btnEdit: ImageView = itemView.findViewById(R.id.btnEditTag)
        private val btnDelete: ImageView = itemView.findViewById(R.id.btnDeleteTag)

        fun bind(tagName: String) {
            tvTagName.text = tagName

            btnEdit.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_ID.toInt()) onEdit(pos, tags[pos])
            }
            btnDelete.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_ID.toInt()) onDelete(pos, tags[pos])
            }

            // 触摸拖拽把手时立即启动拖拽
            dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper?.startDrag(this)
                }
                false
            }
        }
    }
}
