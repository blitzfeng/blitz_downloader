package com.blitz.downloader.dialog

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.blitz.downloader.R
import com.blitz.downloader.model.filter.TagQuery
import com.blitz.downloader.model.filter.TagQueryOp
import com.blitz.downloader.model.filter.TagQueryRule
import com.blitz.downloader.util.TagQueryFormatter

/**
 * 「标签精细检索」的规则编辑对话框。
 *
 * 形态：每行 `[基准标签] [运算符] [标签] [＋/－]`，第一行的基准标签可选，
 * 第二行起同位置显示同一个标签名但置灰（左侧真正参与运算的是「上一行的累积结果」）。
 * `＋` 只挂在最后一行，其余行是 `－`；只有一行时不显示 `－`（删光了没意义）。
 *
 * 状态**只存在于控件上**，「确定」时才一次性读出来组装成 [TagQuery]——行数很少，
 * 额外维护一份影子状态只会多一处不同步的可能。
 */
object TagQueryDialog {

    /**
     * @param allTags 可选标签（`tags` 表全量，与标签栏同源）；调用方保证非空。
     * @param current 当前生效的条件，用于回显。
     * @param onApply 「确定」或「清除筛选」时回调；传 [TagQuery] 未激活值表示清除本层。
     */
    fun show(
        context: Context,
        allTags: List<String>,
        current: TagQuery,
        onApply: (TagQuery) -> Unit,
    ) {
        val inflater = LayoutInflater.from(context)
        val content = inflater.inflate(R.layout.dialog_tag_query, null)
        val rowsContainer: LinearLayout = content.findViewById(R.id.llTagQueryRows)
        val preview: TextView = content.findViewById(R.id.tvTagQueryPreview)

        val opLabels = TagQueryOp.values().map { context.getString(it.labelRes) }
        // 标签下拉的第 0 项是占位符「请选择」，选中它表示这一行还没填完，提交时忽略
        val tagHint = context.getString(R.string.manage_tag_query_tag_hint)
        val tagItems = listOf(tagHint) + allTags

        val rows = mutableListOf<View>()

        fun baseTag(): String {
            val first = rows.firstOrNull() ?: return ""
            val spinner: Spinner = first.findViewById(R.id.spTagQueryBase)
            return spinner.selectedItem as? String ?: ""
        }

        fun buildQuery(): TagQuery {
            val base = baseTag()
            if (base.isBlank()) return TagQuery()
            val rules = rows.mapNotNull { row ->
                val opIndex = row.findViewById<Spinner>(R.id.spTagQueryOp).selectedItemPosition
                val tag = row.findViewById<Spinner>(R.id.spTagQueryTag).selectedItem as? String
                if (tag == null || tag == tagHint) null else TagQueryRule(TagQueryOp.values()[opIndex], tag)
            }
            return TagQuery(base, rules)
        }

        /** 行数 / 选中项变化后统一刷新：首列显隐、置灰文案、按钮符号、表达式预览。 */
        fun refreshChrome() {
            val base = baseTag()
            rows.forEachIndexed { index, row ->
                val spBase: Spinner = row.findViewById(R.id.spTagQueryBase)
                val tvBase: TextView = row.findViewById(R.id.tvTagQueryBase)
                val isFirst = index == 0
                spBase.visibility = if (isFirst) View.VISIBLE else View.GONE
                tvBase.visibility = if (isFirst) View.GONE else View.VISIBLE
                if (!isFirst) tvBase.text = base

                val button: Button = row.findViewById(R.id.btnTagQueryRowAction)
                val isLast = index == rows.lastIndex
                button.setText(
                    if (isLast) R.string.manage_tag_query_add else R.string.manage_tag_query_remove,
                )
            }
            preview.text = context.getString(
                R.string.manage_tag_query_preview,
                TagQueryFormatter.format(context, buildQuery()),
            )
        }

        /** 选中项变化时只需要刷新 chrome；不区分是哪个下拉变的。 */
        val onAnySelection = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                refreshChrome()

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        fun spinnerAdapter(items: List<String>): ArrayAdapter<String> =
            ArrayAdapter(context, android.R.layout.simple_spinner_item, items).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

        // 局部函数而不是 lambda 变量：按钮回调里要递归调自己（点 ＋ 再加一行）
        fun addRow(op: TagQueryOp, tag: String) {
            val row = inflater.inflate(R.layout.item_tag_query_rule, rowsContainer, false)

            row.findViewById<Spinner>(R.id.spTagQueryBase).apply {
                adapter = spinnerAdapter(allTags)
                val index = allTags.indexOf(current.base).takeIf { it >= 0 } ?: 0
                setSelection(index)
                onItemSelectedListener = onAnySelection
            }
            row.findViewById<Spinner>(R.id.spTagQueryOp).apply {
                adapter = spinnerAdapter(opLabels)
                setSelection(op.ordinal)
                onItemSelectedListener = onAnySelection
            }
            row.findViewById<Spinner>(R.id.spTagQueryTag).apply {
                adapter = spinnerAdapter(tagItems)
                setSelection(tagItems.indexOf(tag).takeIf { it >= 0 } ?: 0)
                onItemSelectedListener = onAnySelection
            }
            row.findViewById<Button>(R.id.btnTagQueryRowAction).setOnClickListener {
                if (row == rows.lastOrNull()) {
                    addRow(TagQueryOp.AND, tagHint)
                } else {
                    // 每行的 spTagQueryBase 只在 addRow() 时按当时的 current.base（或占位符）
                    // seed 过一次，此后互不同步。删掉当前首行时，被提升为首行的那一行必须
                    // 显式把自己隐藏的 spTagQueryBase 掰到删除前的实际取值——否则它会暴露出
                    // 自己那份从未更新过的旧选中项，用户在首行改过的基准标签会被静默丢弃。
                    val baseBeforeRemoval = baseTag()
                    rows.remove(row)
                    rowsContainer.removeView(row)
                    rows.firstOrNull()?.let { newFirst ->
                        val spBase: Spinner = newFirst.findViewById(R.id.spTagQueryBase)
                        val index = allTags.indexOf(baseBeforeRemoval).takeIf { it >= 0 } ?: 0
                        spBase.setSelection(index)
                    }
                    refreshChrome()
                }
            }

            rows.add(row)
            rowsContainer.addView(row)
            refreshChrome()
        }

        // 回显：有条件就按条件建行，没有就起一行空规则
        if (current.isActive && current.rules.isNotEmpty()) {
            current.rules.forEach { addRow(it.op, it.tag) }
        } else {
            addRow(TagQueryOp.AND, tagHint)
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.manage_tag_query_title)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ -> onApply(buildQuery()) }
            .setNeutralButton(R.string.manage_tag_query_clear) { _, _ -> onApply(TagQuery()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
