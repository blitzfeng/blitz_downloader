package com.blitz.downloader.dialog

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.blitz.downloader.R
import com.blitz.downloader.model.filter.TagQuery
import com.blitz.downloader.model.filter.TagQueryOp
import com.blitz.downloader.model.filter.TagQueryRule
import com.blitz.downloader.util.TagQueryFormatter
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView

/**
 * 「标签精细检索」的规则编辑对话框。
 *
 * 形态：顶部一个**基准标签**下拉，下面若干条规则行 `[运算符] [标签] [＋/－]`。
 * `＋` 只挂在最后一行，其余行是 `－`；只有一行时不显示 `－`（删光了没意义）。
 *
 * 基准标签**不进规则行**：它对所有行是同一个值，原设计里每行重复显示一遍（第二行起置灰）
 * 既占掉一整列——Material 的 exposed dropdown 三列并排会挤到每格只剩一个字——
 * 也带来「删掉首行后基准回退成旧值」这类只能靠手动同步兜住的问题。提到顶部后两者都不存在了。
 *
 * 两个下拉是 Material 的 exposed dropdown menu（`TextInputLayout` + [MaterialAutoCompleteTextView]），
 * 不是平台 `Spinner`——除了对齐 Material Design 规范，也绕开了 Spinner 在加权布局里
 * 缓存 0 宽文本布局导致「选中项文字画不出来」的坑（见 `item_tag_query_rule.xml` 的注释）。
 *
 * 状态**只存在于控件上**，「确定」时才一次性读出来组装成 [TagQuery]——行数很少，
 * 额外维护一份影子状态只会多一处不同步的可能。「没选」统一用**空文本**表示（对应
 * `TextInputLayout` 上浮起来的 hint），不再需要一个「请选择」占位项混在候选列表里。
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
        val baseInput: MaterialAutoCompleteTextView = content.findViewById(R.id.actTagQueryBase)

        val tagItems = allTags.toTypedArray()
        val opLabels = TagQueryOp.values().map { context.getString(it.labelRes) }.toTypedArray()
        val opByLabel = TagQueryOp.values().associateBy { context.getString(it.labelRes) }

        val rows = mutableListOf<View>()

        fun View.dropdown(id: Int): MaterialAutoCompleteTextView = findViewById(id)

        /** 标签回显：标签可能已在标签管理页被删除 / 改名，这时留空而不是显示一个不存在的名字。 */
        fun tagTextOf(name: String): String = if (name in allTags) name else ""

        fun baseTag(): String = baseInput.text.toString()

        fun buildQuery(): TagQuery {
            val base = baseTag()
            if (base.isBlank()) return TagQuery()
            val rules = rows.mapNotNull { row ->
                val tag = row.dropdown(R.id.actTagQueryTag).text.toString()
                if (tag.isBlank()) return@mapNotNull null
                val op = opByLabel[row.dropdown(R.id.actTagQueryOp).text.toString()] ?: TagQueryOp.AND
                TagQueryRule(op, tag)
            }
            return TagQuery(base, rules)
        }

        /** 行数 / 选中项变化后统一刷新：按钮符号与表达式预览。 */
        fun refreshChrome() {
            rows.forEachIndexed { index, row ->
                val button: MaterialButton = row.findViewById(R.id.btnTagQueryRowAction)
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

        // 局部函数而不是 lambda 变量：按钮回调里要递归调自己（点 ＋ 再加一行）
        fun addRow(op: TagQueryOp, tag: String) {
            val row = inflater.inflate(R.layout.item_tag_query_rule, rowsContainer, false)

            row.dropdown(R.id.actTagQueryOp).apply {
                setSimpleItems(opLabels)
                // setText 的第二个参数 false = 不触发过滤，否则候选列表会被当前值筛成一项
                setText(context.getString(op.labelRes), false)
                setOnItemClickListener { _, _, _, _ -> refreshChrome() }
            }
            row.dropdown(R.id.actTagQueryTag).apply {
                setSimpleItems(tagItems)
                setText(tagTextOf(tag), false)
                setOnItemClickListener { _, _, _, _ -> refreshChrome() }
            }
            row.findViewById<MaterialButton>(R.id.btnTagQueryRowAction).setOnClickListener {
                if (row == rows.lastOrNull()) {
                    addRow(TagQueryOp.AND, "")
                } else {
                    rows.remove(row)
                    rowsContainer.removeView(row)
                    refreshChrome()
                }
            }

            rows.add(row)
            rowsContainer.addView(row)
            refreshChrome()
        }

        baseInput.apply {
            setSimpleItems(tagItems)
            setText(tagTextOf(current.base), false)
            setOnItemClickListener { _, _, _, _ -> refreshChrome() }
        }

        // 回显：有条件就按条件建行，没有就起一行空规则
        if (current.isActive && current.rules.isNotEmpty()) {
            current.rules.forEach { addRow(it.op, it.tag) }
        } else {
            addRow(TagQueryOp.AND, "")
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
