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
import com.blitz.downloader.data.DownloadedVideoRepository
import com.blitz.downloader.data.db.DownloadedVideoEntity
import com.blitz.downloader.util.NumberFormatUtils
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
 * @param showWatchedBadge    是否展示「未看过」标记（只有视频 Tab 为 true：图集不走播放页，
 *                            `watched` 永远不会置位，显示了就是个消不掉的标记）。
 */
class ManageGridAdapter(
    private val onSelectionChanged: (inSelectionMode: Boolean, selectedCount: Int) -> Unit,
    private val onItemClick: (entity: DownloadedVideoEntity) -> Unit = {},
    private val onTagAreaClick: (awemeId: String, currentTags: List<String>) -> Unit = { _, _ -> },
    private val supportsUserTags: Boolean = false,
    private val showWatchedBadge: Boolean = false,
) : RecyclerView.Adapter<ManageGridAdapter.ViewHolder>() {

    private val items = mutableListOf<ManageGridItem>()
    private val indexByAwemeId = mutableMapOf<String, Int>()

    var inSelectionMode: Boolean = false
        private set
    private val selectedIds = mutableSetOf<String>()

    // ── 公共 API ──────────────────────────────────────────────────────────────

    /** 返回当前已加载的所有 item 快照，供打开播放器时构建列表使用。 */
    fun getItems(): List<ManageGridItem> = items.toList()

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

    /** 当前已加载项是否已全部选中（无项时返回 false）。 */
    fun isAllSelected(): Boolean = items.isNotEmpty() && selectedIds.size == items.size

    /**
     * 全选当前**已加载**的所有条目（分页未加载的不在内）。
     * 若尚未进入多选模式则一并进入。
     */
    fun selectAll() {
        if (items.isEmpty()) return
        if (!inSelectionMode) inSelectionMode = true
        selectedIds.clear()
        items.forEach { selectedIds.add(it.entity.awemeId) }
        notifyItemRangeChanged(0, items.size, PAYLOAD_SELECTION_STATE)
        onSelectionChanged(true, selectedIds.size)
    }

    /**
     * 取消全部选中，但**保持多选模式**（区别于 [exitSelectionMode]），便于用户重新挑选。
     */
    fun deselectAll() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        notifyItemRangeChanged(0, items.size, PAYLOAD_SELECTION_STATE)
        onSelectionChanged(inSelectionMode, 0)
    }

    /**
     * 局域网导出成功后把这些条目的 `exportCount` 就地 +1，刷新左上角「已导出」标记。
     * 与数据库侧的原子累加是两笔独立更新——这里只为让当前列表立刻显示正确，
     * 下次重新查询时以数据库为准。
     */
    fun markExported(awemeIds: Set<String>) {
        if (awemeIds.isEmpty()) return
        awemeIds.forEach { awemeId ->
            val pos = indexByAwemeId[awemeId] ?: return@forEach
            if (pos >= items.size || items[pos].entity.awemeId != awemeId) return@forEach
            val entity = items[pos].entity
            items[pos] = items[pos].copy(entity = entity.copy(exportCount = entity.exportCount + 1))
            notifyItemChanged(pos, PAYLOAD_EXPORTED)
        }
    }

    /**
     * 把这些条目就地标为「已看过」，去掉封面上的「未看过」标记。
     * 与数据库侧的 `UPDATE ... SET watched = 1` 是两笔独立更新——这里只为让当前列表立刻
     * 显示正确（点开播放页时先标本条，播放页里滑动看过的那些由 `onResume` 回查补上）。
     */
    fun markWatched(awemeIds: Set<String>) {
        if (awemeIds.isEmpty()) return
        awemeIds.forEach { awemeId ->
            val pos = indexByAwemeId[awemeId] ?: return@forEach
            if (pos >= items.size || items[pos].entity.awemeId != awemeId) return@forEach
            val entity = items[pos].entity
            if (entity.watched) return@forEach
            items[pos] = items[pos].copy(entity = entity.copy(watched = true))
            notifyItemChanged(pos, PAYLOAD_WATCHED)
        }
    }

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
        if (PAYLOAD_SELECTION_STATE in payloads) {
            holder.bindSelectionState(inSelectionMode, isSelected)
            // 「已导出」标记只在多选态显示，故随多选状态一起重绑
            holder.bindExportedState(inSelectionMode, item.entity.exportCount)
        }
        if (PAYLOAD_EXPORTED in payloads) holder.bindExportedState(inSelectionMode, item.entity.exportCount)
        if (PAYLOAD_WATCHED in payloads) holder.bindWatchedState(item.entity.watched)
        if (PAYLOAD_TAGS in payloads) holder.bindUserTags(item.userTags)
    }

    override fun getItemCount(): Int = items.size

    // ── ViewHolder ────────────────────────────────────────────────────────────

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ImageView = itemView.findViewById(R.id.ivCover)
        private val invalidOverlay: View = itemView.findViewById(R.id.viewInvalidOverlay)
        private val invalidBadge: TextView = itemView.findViewById(R.id.tvInvalidBadge)
        private val diggBadge: TextView = itemView.findViewById(R.id.tvDiggCount)
        private val exportedBadge: TextView = itemView.findViewById(R.id.tvExportedBadge)
        private val unwatchedBadge: TextView = itemView.findViewById(R.id.tvUnwatchedBadge)
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

            // 点赞数徽标：旧记录或接口未带 → 0 → 隐藏；心形默认白色，userRelation 含 like（我点赞过）着抖音红
            val diggText = NumberFormatUtils.formatChineseCount(entity.diggCount)
            if (diggText.isEmpty()) {
                diggBadge.visibility = View.GONE
            } else {
                diggBadge.visibility = View.VISIBLE
                diggBadge.text = diggText
                DiggBadgeStyle.tintHeart(
                    diggBadge,
                    DownloadedVideoRepository.hasLikeRelation(entity.userRelation),
                )
            }

            bindInvalidState(item.fileExists)
            bindSelectionState(selectionMode, isSelected)
            bindExportedState(selectionMode, entity.exportCount)
            bindWatchedState(entity.watched)
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
            // 容器尚未走过 layout（首次 bind）时宽度为 0，post 一次再排版；
            // 已有宽度则同步排版，避免出现「先全塞一行再跳成两行」的闪烁。
            val w = userTagContainer.width
            if (w > 0) {
                layoutTagsInTwoRows(tags, w)
            } else {
                userTagContainer.post {
                    if (userTagContainer.width > 0) {
                        layoutTagsInTwoRows(tags, userTagContainer.width)
                    }
                }
            }
        }

        /**
         * 把 [tags] 排进 [userTagContainer]，最多两行；
         * 第二行容纳不下剩余 chip 时，尾部用「+N」概要 chip 表示剩余数量。
         */
        private fun layoutTagsInTwoRows(tags: List<String>, maxWidth: Int) {
            userTagContainer.removeAllViews()
            val ctx = itemView.context
            val density = ctx.resources.displayMetrics.density
            val rowGap = (3 * density).toInt()
            // maxWidth 异常时退化为「全放第一行」，由系统裁剪兜底
            val safe = if (maxWidth > 0) maxWidth else Int.MAX_VALUE
            val mw = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            val mh = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

            fun chipOuterWidth(v: View): Int {
                v.measure(mw, mh)
                val lp = v.layoutParams as? LinearLayout.LayoutParams
                return v.measuredWidth + (lp?.marginEnd ?: 0)
            }

            fun newRow(topGap: Int): LinearLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = topGap }
            }

            val maxRows = 2
            val rows = mutableListOf(newRow(0))
            var rowWidth = 0
            var overflowFromIndex = -1

            for ((idx, tag) in tags.withIndex()) {
                val chip = makeChip(ctx, tag, USER_TAG_COLOR)
                val w = chipOuterWidth(chip)
                if (rowWidth + w > safe && rows.last().childCount > 0) {
                    if (rows.size >= maxRows) {
                        overflowFromIndex = idx
                        break
                    }
                    rows.add(newRow(rowGap))
                    rowWidth = 0
                }
                rows.last().addView(chip)
                rowWidth += w
            }

            if (overflowFromIndex >= 0) {
                // 末尾换 "+N" 概要 chip；放不下就回撤一格再试，直到能塞下或第二行清空。
                var remaining = tags.size - overflowFromIndex
                val lastRow = rows.last()
                while (true) {
                    val plus = makeChip(ctx, "+$remaining", USER_TAG_OVERFLOW_COLOR)
                    val plusW = chipOuterWidth(plus)
                    var curW = 0
                    for (i in 0 until lastRow.childCount) {
                        val c = lastRow.getChildAt(i)
                        val lp = c.layoutParams as? LinearLayout.LayoutParams
                        curW += c.measuredWidth + (lp?.marginEnd ?: 0)
                    }
                    if (curW + plusW <= safe || lastRow.childCount == 0) {
                        lastRow.addView(plus)
                        break
                    }
                    // 撤掉一个已渲染 chip 给 "+N" 让位，被撤的 chip 也要计入未展示数量
                    lastRow.removeViewAt(lastRow.childCount - 1)
                    remaining += 1
                }
            }

            rows.forEach { userTagContainer.addView(it) }
        }

        fun bindInvalidState(fileExists: Boolean) {
            invalidOverlay.visibility = if (fileExists) View.GONE else View.VISIBLE
            invalidBadge.visibility = if (fileExists) View.GONE else View.VISIBLE
        }

        /**
         * 左上角「已导出」标记：仅多选态且 [exportCount] > 0 时显示（导出多次时带次数）。
         * 计数来源见 [com.blitz.downloader.data.db.DownloadedVideoEntity.exportCount]。
         */
        fun bindExportedState(selectionMode: Boolean, exportCount: Int) {
            if (!selectionMode || exportCount <= 0) {
                exportedBadge.visibility = View.GONE
                return
            }
            val ctx = itemView.context
            exportedBadge.text = if (exportCount > 1) {
                ctx.getString(R.string.manage_exported_label_n, exportCount)
            } else {
                ctx.getString(R.string.manage_exported_label)
            }
            exportedBadge.visibility = View.VISIBLE
        }

        /**
         * 点赞数右侧的「未看过」标记：[showWatchedBadge] 开启（视频 Tab）且尚未看过时显示。
         * 置位来源见 [com.blitz.downloader.data.db.DownloadedVideoEntity.watched]。
         */
        fun bindWatchedState(watched: Boolean) {
            unwatchedBadge.visibility =
                if (showWatchedBadge && !watched) View.VISIBLE else View.GONE
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
        private const val PAYLOAD_EXPORTED = "exported"
        private const val PAYLOAD_WATCHED = "watched"

        /** 用户自定义标签的背景色（深蓝紫，与系统 chip 区分）。 */
        private val USER_TAG_COLOR = 0xFF5C6BC0.toInt()  // Indigo 400

        /** 两行装不下时的「+N」概要 chip 背景色（中性灰，弱化视觉权重）。 */
        private val USER_TAG_OVERFLOW_COLOR = 0xFF9E9E9E.toInt()  // Grey 500

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
