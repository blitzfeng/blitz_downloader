package com.blitz.downloader.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
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
    /** 用户在管理页手动打的自定义标签列表（来自 video_tags 表）。 */
    val userTags: List<String> = emptyList(),
)

/**
 * 管理页宫格 Adapter。
 *
 * 交互：
 * - **单击 item**：非多选模式下立即调用 [onItemClick]（打开播放页/图片浏览页）。
 * - **单击用户标签行**：当 [supportsUserTags] 为 true 时调用 [onTagAreaClick]（标签设置弹窗）。
 * - **长按 item**：进入多选模式。
 * - **多选模式单击**：切换该条目的选中状态。
 *
 * @param onSelectionChanged  多选模式/数量变化时回调。
 * @param onItemClick         非多选模式下单击 item 时回调，传递 [DownloadedVideoEntity]。
 * @param onTagAreaClick      非多选模式下点击用户标签行时回调，传递 awemeId 及当前标签列表。
 * @param supportsUserTags    是否展示用户自定义标签行并支持点击编辑（视频 Tab 为 true，图片 Tab 为 false）。
 */
class ManageGridAdapter(
    private val onSelectionChanged: (inSelectionMode: Boolean, selectedCount: Int) -> Unit,
    private val onItemClick: (entity: DownloadedVideoEntity) -> Unit = {},
    private val onTagAreaClick: (awemeId: String, currentTags: List<String>) -> Unit = { _, _ -> },
    private val supportsUserTags: Boolean = false,
) : RecyclerView.Adapter<ManageGridAdapter.ViewHolder>() {

    private val items = mutableListOf<ManageGridItem>()
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

    /** 更新某条目的用户标签，触发局部刷新（仅重绑标签行）。 */
    fun updateItemTags(awemeId: String, tags: List<String>) {
        val pos = indexByAwemeId[awemeId] ?: return
        if (pos < items.size && items[pos].entity.awemeId == awemeId) {
            items[pos] = items[pos].copy(userTags = tags)
            notifyItemChanged(pos, PAYLOAD_TAGS)
        }
    }

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
        val item = items[position]
        holder.bind(item, inSelectionMode, item.entity.awemeId in selectedIds)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val item = items[position]
        val isSelected = item.entity.awemeId in selectedIds
        if (PAYLOAD_FILE_EXISTS in payloads) holder.bindInvalidState(item.fileExists)
        if (PAYLOAD_SELECTION_STATE in payloads) holder.bindSelectionState(inSelectionMode, isSelected)
        if (PAYLOAD_TAGS in payloads) holder.bindUserTags(item.userTags)
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
        private val tvDesc: TextView = itemView.findViewById(R.id.tvDesc)
        private val tagContainer: LinearLayout = itemView.findViewById(R.id.tagContainer)
        private val userTagContainer: LinearLayout = itemView.findViewById(R.id.userTagContainer)

        fun bind(item: ManageGridItem, selectionMode: Boolean, isSelected: Boolean) {
            val entity = item.entity
            val ctx = itemView.context

            // 作者名
            username.text = entity.userName.ifBlank { entity.awemeId }

            // 描述
            val desc = entity.desc.trim()
            if (desc.isNotEmpty()) {
                tvDesc.text = desc
                tvDesc.visibility = View.VISIBLE
            } else {
                tvDesc.visibility = View.GONE
            }

            // 封面
            val coverPath = entity.coverPath
            if (coverPath.isNotBlank()) {
                cover.load(File(Environment.getExternalStorageDirectory(), coverPath)) {
                    crossfade(true)
                    placeholder(R.drawable.ic_video_placeholder)
                    error(R.drawable.ic_video_placeholder)
                    transformations(RoundedCornersTransformation(8f))
                }
            } else {
                cover.setImageResource(R.drawable.ic_video_placeholder)
            }

            // 标签行
            tagContainer.removeAllViews()
            // downloadType chip（始终存在）
//            tagContainer.addView(
//                makeChip(ctx, labelForDownloadType(entity.downloadType), colorForDownloadType(entity.downloadType))
//            )
            // userRelation chips（按 | 拆分）
            entity.userRelation
                .split("|")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { part ->
                    tagContainer.addView(makeChip(ctx, labelForRelation(part), colorForRelation(part)))
                }

            bindInvalidState(item.fileExists)
            bindSelectionState(selectionMode, isSelected)
            bindUserTags(item.userTags)
            bindClickListeners(entity.awemeId)
        }

        fun bindUserTags(tags: List<String>) {
            userTagContainer.removeAllViews()
            val ctx = itemView.context
            if (tags.isEmpty()) {
                if (supportsUserTags) {
                    // 显示"＋ 添加标签"占位，保持行可点击
                    userTagContainer.visibility = View.VISIBLE
                    userTagContainer.addView(makeAddTagPlaceholder(ctx))
                } else {
                    userTagContainer.visibility = View.GONE
                }
                return
            }
            userTagContainer.visibility = View.VISIBLE
            tags.forEach { tag ->
                userTagContainer.addView(makeChip(ctx, tag, USER_TAG_COLOR))
            }
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

        private fun bindClickListeners(awemeId: String) {
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
                if (!inSelectionMode) {
                    val pos = indexByAwemeId[awemeId] ?: return@setOnClickListener
                    val entity = if (pos < items.size) items[pos].entity else return@setOnClickListener
                    onItemClick(entity)
                    return@setOnClickListener
                }
                toggleSelection(awemeId)
            }
            // 用户标签行独立点击区域（仅在 supportsUserTags 模式下注册）
            if (supportsUserTags) {
                userTagContainer.setOnClickListener {
                    if (!inSelectionMode) {
                        val pos = indexByAwemeId[awemeId] ?: return@setOnClickListener
                        val tags = if (pos < items.size) items[pos].userTags else emptyList()
                        onTagAreaClick(awemeId, tags)
                    } else {
                        toggleSelection(awemeId)
                    }
                }
            }
        }

        private fun toggleSelection(awemeId: String) {
            if (awemeId in selectedIds) selectedIds.remove(awemeId) else selectedIds.add(awemeId)
            if (selectedIds.isEmpty()) {
                exitSelectionMode()
            } else {
                onSelectionChanged(true, selectedIds.size)
                notifyItemChanged(bindingAdapterPosition, PAYLOAD_SELECTION_STATE)
            }
        }
    }

    // ── 私有辅助 ──────────────────────────────────────────────────────────────

    private fun rebuildIndex() {
        indexByAwemeId.clear()
        items.forEachIndexed { i, item -> indexByAwemeId[item.entity.awemeId] = i }
    }

    /**
     * 创建一个圆角胶囊状标签 TextView，填充多巴胺配色背景。
     * 不使用 Chip 控件，避免 Material 主题依赖和焦点抢占问题。
     */
    private fun makeChip(ctx: android.content.Context, text: String, bgColor: Int): TextView {
        val density = ctx.resources.displayMetrics.density
        val hPad = (8 * density).toInt()
        val vPad = (3 * density).toInt()
        val radius = 20 * density

        val bg = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = radius
        }

        return TextView(ctx).apply {
            this.text = text
            textSize = 9.5f
            setTextColor(Color.WHITE)
            setPadding(hPad, vPad, hPad, vPad)
            background = bg
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (5 * density).toInt() }
        }
    }

    /**
     * "添加标签"占位 chip：虚线边框灰色，无背景色，提示用户可点击标签行添加标签。
     */
    private fun makeAddTagPlaceholder(ctx: android.content.Context): TextView {
        val density = ctx.resources.displayMetrics.density
        val hPad = (8 * density).toInt()
        val vPad = (3 * density).toInt()
        val radius = 20 * density

        val bg = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = radius
            setStroke((1 * density).toInt(), 0xFF9E9E9E.toInt())
        }
        return TextView(ctx).apply {
            text = "＋ 添加标签"
            textSize = 9.5f
            setTextColor(0xFF9E9E9E.toInt())
            setPadding(hPad, vPad, hPad, vPad)
            background = bg
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    companion object {
        private const val PAYLOAD_FILE_EXISTS = "file_exists"
        private const val PAYLOAD_SELECTION_STATE = "selection_state"
        private const val PAYLOAD_TAGS = "tags"

        /** 用户自定义标签的背景色（深蓝紫，与系统 chip 区分）。 */
        private val USER_TAG_COLOR = 0xFF5C6BC0.toInt()  // Indigo 400

        // ── downloadType 配色与标签 ────────────────────────────────────────────

        /**
         * 多巴胺配色方案：每种来源类型固定颜色，高饱和度强对比。
         * 珊瑚红 post / 玫瑰粉 like / 薰衣草紫 collect / 碧绿青 collects / 琥珀黄 mix
         */
        private val DOWNLOAD_TYPE_COLORS = mapOf(
            "post"     to 0xFFFF6B6B.toInt(),   // 珊瑚红
            "like"     to 0xFFFF3D80.toInt(),   // 玫瑰粉
            "collect"  to 0xFF8C52FF.toInt(),   // 薰衣草紫
            "collects" to 0xFF00D4AA.toInt(),   // 碧绿青
            "mix"      to 0xFFFFB300.toInt(),   // 琥珀黄
        )
        private val DOWNLOAD_TYPE_DEFAULT_COLOR = 0xFF78909C.toInt()  // 灰蓝（兜底）

        private val DOWNLOAD_TYPE_LABELS = mapOf(
            "post"     to "帖子",
            "like"     to "喜欢",
            "collect"  to "收藏",
            "collects" to "收藏夹",
            "mix"      to "合集",
        )

        // ── userRelation 配色与标签 ────────────────────────────────────────────

        /**
         * "like" / "collect" 是固定语义关键字，使用固定颜色；
         * 其余视为收藏夹名称，统一使用薄荷绿。
         */
        private val RELATION_KEY_COLORS = mapOf(
            "like"    to 0xFFFF3D80.toInt(),  // 同 like 类型：玫瑰粉
            "collect" to 0xFF2979FF.toInt(),  // 宝蓝
        )
        private val RELATION_FOLDER_COLOR = 0xFF00C896.toInt()   // 薄荷绿（收藏夹名称）

        private val RELATION_KEY_LABELS = mapOf(
            "like"    to "♥ 喜欢",
            "collect" to "★ 收藏",
        )

        // ── 辅助函数 ──────────────────────────────────────────────────────────

        fun colorForDownloadType(type: String): Int =
            DOWNLOAD_TYPE_COLORS[type] ?: DOWNLOAD_TYPE_DEFAULT_COLOR

        fun labelForDownloadType(type: String): String =
            DOWNLOAD_TYPE_LABELS[type] ?: type

        fun colorForRelation(part: String): Int =
            RELATION_KEY_COLORS[part.lowercase()] ?: RELATION_FOLDER_COLOR

        fun labelForRelation(part: String): String =
            RELATION_KEY_LABELS[part.lowercase()] ?: part   // 收藏夹名直接展示
    }
}
