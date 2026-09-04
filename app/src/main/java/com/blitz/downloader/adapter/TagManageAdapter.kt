package com.blitz.downloader.adapter

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
 * - 点击"设置上级"按钮触发层级设置回调，副标题展示当前上级（若有）
 *
 * 拖拽结束后调用方应调用 [getTagList] 取当前顺序并持久化。
 *
 * 上级关系（[parentMap]）与标签顺序一样是本 Adapter 在内存中维护的局部状态、不经 StateFlow：
 * 重命名/删除会连带影响别的标签对它的上级引用，这里用 [renameParentReferences]/
 * [clearParentReferences] 同步更新，避免为了一次小改动重新拉取整个列表。
 */
class TagManageAdapter(
    private val onEdit: (position: Int, tagName: String) -> Unit,
    private val onDelete: (position: Int, tagName: String) -> Unit,
    private val onSetParent: (position: Int, tagName: String) -> Unit,
) : RecyclerView.Adapter<TagManageAdapter.ViewHolder>() {

    private val tags = mutableListOf<String>()

    /** 标签名 → 上级标签名，只含有上级的条目，语义与 `VideoTagRepository.getParentMap()` 一致。 */
    private var parentMap: Map<String, String> = emptyMap()

    /** 由 Activity 在初始化和刷新时调用。 */
    fun submitList(list: List<String>, parents: Map<String, String> = emptyMap()) {
        tags.clear()
        tags.addAll(list)
        parentMap = parents
        notifyDataSetChanged()
    }

    /** 单个标签的上级设置成功后调用，只刷新这一行。 */
    fun updateParent(position: Int, tagName: String, parentTagName: String) {
        parentMap = if (parentTagName.isBlank()) {
            parentMap - tagName
        } else {
            parentMap + (tagName to parentTagName)
        }
        if (position in tags.indices) notifyItemChanged(position)
    }

    /**
     * 标签重命名后同步更新引用：重命名的标签若本身有上级，键要跟着改名；
     * 若它是别的标签的上级，那些标签的引用值也要跟着改。
     */
    fun renameParentReferences(oldName: String, newName: String) {
        parentMap = parentMap.entries.associate { (child, parent) ->
            val newChild = if (child == oldName) newName else child
            val newParent = if (parent == oldName) newName else parent
            newChild to newParent
        }
        notifyDataSetChanged()
    }

    /** 标签删除后同步更新引用：它自己的条目移除，以它为上级的条目也清空（与 Repository 的级联一致）。 */
    fun clearParentReferences(deletedName: String) {
        parentMap = parentMap.filterKeys { it != deletedName }.filterValues { it != deletedName }
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
        val tagName = tags[position]
        holder.bind(tagName, parentMap[tagName])
    }

    override fun getItemCount(): Int = tags.size

    // ── ViewHolder ────────────────────────────────────────────────────────────

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val dragHandle: ImageView = itemView.findViewById(R.id.ivDragHandle)
        private val tvTagName: TextView = itemView.findViewById(R.id.tvTagName)
        private val tvTagParent: TextView = itemView.findViewById(R.id.tvTagParent)
        private val btnEdit: ImageView = itemView.findViewById(R.id.btnEditTag)
        private val btnDelete: ImageView = itemView.findViewById(R.id.btnDeleteTag)
        private val btnSetParent: ImageView = itemView.findViewById(R.id.btnSetParentTag)

        fun bind(tagName: String, parentTagName: String?) {
            tvTagName.text = tagName
            if (parentTagName.isNullOrBlank()) {
                tvTagParent.visibility = View.GONE
            } else {
                tvTagParent.text = "上级：$parentTagName"
                tvTagParent.visibility = View.VISIBLE
            }

            btnEdit.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_ID.toInt()) onEdit(pos, tags[pos])
            }
            btnDelete.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_ID.toInt()) onDelete(pos, tags[pos])
            }
            btnSetParent.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_ID.toInt()) onSetParent(pos, tags[pos])
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
