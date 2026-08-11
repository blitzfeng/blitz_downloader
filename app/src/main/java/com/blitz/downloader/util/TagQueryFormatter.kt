package com.blitz.downloader.util

import android.content.Context
import com.blitz.downloader.R
import com.blitz.downloader.model.filter.TagQuery

/**
 * 把 [TagQuery] 渲染成一行人读的表达式，如 `美女 且 舞蹈 或 唱歌 且非 广告`。
 *
 * 对话框的实时预览、菜单标题、空状态文案共用这一份，避免三处各拼一遍拼出不同口径。
 * 放在视图层（`util/`）而不是 [TagQuery] 上，是因为它要 `Context` 取 `R.string`。
 *
 * 词与词之间用空格分隔、**不加括号**：求值本来就是从上到下左结合，读的顺序即算的顺序。
 */
object TagQueryFormatter {

    fun format(context: Context, query: TagQuery): String {
        if (!query.isActive) return context.getString(R.string.manage_tag_query_none)
        return buildString {
            append(query.base)
            query.rules.forEach { rule ->
                if (rule.tag.isBlank()) return@forEach
                append(' ')
                append(context.getString(rule.op.symbolRes))
                append(' ')
                append(rule.tag)
            }
        }
    }
}
