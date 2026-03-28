package com.blitz.downloader.ui

import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.blitz.downloader.R
import com.blitz.downloader.data.db.DownloadedVideoEntity
import java.io.File

data class ManageGridItem(
    val entity: DownloadedVideoEntity,
    /** true = 文件存在（或未检查），false = 文件已不存在 → 显示失效蒙层。 */
    val fileExists: Boolean = true,
)

/**
 * 管理页宫格 Adapter，支持：
 * - 封面图从本地路径加载（[ManageGridItem.entity.coverPath]）；
 * - 失效蒙层（[ManageGridItem.fileExists] == false 时显示）；
 * - 多选模式：长按进入，点击切换选中状态，全部取消时自动退出。
 *
 * @param onSelectionChanged (inSelectionMode, selectedCount) 模式或数量变化时回调。
 */
class ManageGridAdapter(
    private val onSelectionChanged: (inSelectionMode: Boolean, selectedCount: Int) -> Unit,
) : RecyclerView.Adapter<ManageGridAdapter.ViewHolder>() {

    private val items = mutableListOf<ManageGridItem>()

    /** awemeId → list index，用于快速定位局部刷新。 */
    private val indexByAwemeId = mutableMapOf<String, Int>()

    var inSelectionMode: Boolean = false
        private set

    private val selectedIds = mutableSetOf<String>()

    // ── 公共 API ──────────────────────────────────────────────────────────────

    fun addItems(newItems: List<ManageGridItem>) {
        val insertStart = items.size
        items.addAll(newItems)
        newItems.forEachIndexed { i, item ->
            indexByAwemeId[item.entity.awemeId] = insertStart + i
        }
        notifyItemRangeInserted(insertStart, newItems.size)
    }

    /** 重置列表（用于刷新）。 */
    fun clearItems() {
        val size = items.size
        items.clear()
        indexByAwemeId.clear()
        selectedIds.clear()
        if (inSelectionMode) {
            inSelectionMode = false
            onSelectionChanged(false, 0)
        }
        notifyItemRangeRemoved(0, size)
    }

    fun updateFileExists(awemeId: String, exists: Boolean) {
        val pos = indexByAwemeId[awemeId] ?: return
        if (pos < items.size && items[pos].entity.awemeId == awemeId) {
            items[pos] = items[pos].copy(fileExists = exists)
            notifyItemChanged(pos, PAYLOAD_FILE_EXISTS)
        }
    }

    /** 批量移除指定 awemeId 的条目，移除后重建索引。 */
    fun removeItems(awemeIds: Set<String>) {
        if (awemeIds.isEmpty()) return
        items.removeAll { it.entity.awemeId in awemeIds }
        selectedIds.removeAll(awemeIds)
        rebuildIndex()
        notifyDataSetChanged()
        if (inSelectionMode && selectedIds.isEmpty()) {
            inSelectionMode = false
            onSelectionChanged(false, 0)
        }
    }

    fun getSelectedAwemeIds(): List<String> = selectedIds.toList()

    fun exitSelectionMode() {
        if (!inSelectionMode) return
        inSelectionMode = false
        selectedIds.clear()
        notifyItemRangeChanged(0, items.size, PAYLOAD_SELECTION_STATE)
        onSelectionChanged(false, 0)
    }

    // ── RecyclerView.Adapter ──────────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_manage_video, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], inSelectionMode, items[position].entity.awemeId in selectedIds)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val item = items[position]
        val isSelected = item.entity.awemeId in selectedIds
        if (payloads.contains(PAYLOAD_FILE_EXISTS)) {
            holder.bindInvalidState(item.fileExists)
        }
        if (payloads.contains(PAYLOAD_SELECTION_STATE)) {
            holder.bindSelectionState(inSelectionMode, isSelected)
        }
        // compound payload
        if (payloads.contains(PAYLOAD_FILE_EXISTS) || payloads.contains(PAYLOAD_SELECTION_STATE)) return
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount(): Int = items.size

    // ── ViewHolder ────────────────────────────────────────────────────────────

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ImageView = itemView.findViewById(R.id.ivCover)
        private val invalidOverlay: View = itemView.findViewById(R.id.viewInvalidOverlay)
        private val invalidBadge: TextView = itemView.findViewById(R.id.tvInvalidBadge)
        private val selectedOverlay: View = itemView.findViewById(R.id.viewSelectedOverlay)
        private val checkbox: CheckBox = itemView.findViewById(R.id.cbManageSelect)
        private val username: TextView = itemView.findViewById(R.id.tvUsername)

        fun bind(item: ManageGridItem, selectionMode: Boolean, isSelected: Boolean) {
            username.text = item.entity.userName.ifBlank { item.entity.awemeId }

            val coverPath = item.entity.coverPath
            if (coverPath.isNotBlank()) {
                val file = File(Environment.getExternalStorageDirectory(), coverPath)
                cover.load(file) {
                    crossfade(true)
                    placeholder(R.drawable.ic_video_placeholder)
                    error(R.drawable.ic_video_placeholder)
                    transformations(RoundedCornersTransformation(8f))
                }
            } else {
                cover.setImageResource(R.drawable.ic_video_placeholder)
            }

            bindInvalidState(item.fileExists)
            bindSelectionState(selectionMode, isSelected)
            bindClickListeners(item)
        }

        fun bindInvalidState(fileExists: Boolean) {
            invalidOverlay.visibility = if (fileExists) View.GONE else View.VISIBLE
            invalidBadge.visibility = if (fileExists) View.GONE else View.VISIBLE
        }

        fun bindSelectionState(selectionMode: Boolean, isSelected: Boolean) {
            checkbox.visibility = if (selectionMode) View.VISIBLE else View.GONE
            checkbox.isChecked = isSelected
            selectedOverlay.visibility = if (selectionMode && isSelected) View.VISIBLE else View.GONE
        }

        private fun bindClickListeners(item: ManageGridItem) {
            val awemeId = item.entity.awemeId

            itemView.setOnLongClickListener {
                if (!inSelectionMode) {
                    inSelectionMode = true
                    selectedIds.add(awemeId)
                    onSelectionChanged(true, selectedIds.size)
                    notifyItemRangeChanged(0, items.size, PAYLOAD_SELECTION_STATE)
                }
                true
            }

            itemView.setOnClickListener {
                if (!inSelectionMode) return@setOnClickListener
                if (awemeId in selectedIds) {
                    selectedIds.remove(awemeId)
                } else {
                    selectedIds.add(awemeId)
                }
                if (selectedIds.isEmpty()) {
                    // 取消全部 → 自动退出多选
                    exitSelectionMode()
                } else {
                    onSelectionChanged(true, selectedIds.size)
                    notifyItemChanged(bindingAdapterPosition, PAYLOAD_SELECTION_STATE)
                }
            }
        }
    }

    // ── 私有辅助 ──────────────────────────────────────────────────────────────

    private fun rebuildIndex() {
        indexByAwemeId.clear()
        items.forEachIndexed { i, item -> indexByAwemeId[item.entity.awemeId] = i }
    }

    companion object {
        private const val PAYLOAD_FILE_EXISTS = "file_exists"
        private const val PAYLOAD_SELECTION_STATE = "selection_state"
    }
}
