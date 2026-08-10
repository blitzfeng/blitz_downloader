package com.blitz.downloader.model.filter

import com.blitz.downloader.R

/**
 * 「标签精细检索」里一行规则的运算符。
 *
 * @property labelRes 下拉框里的完整文案。
 * @property symbolRes 表达式预览 / 空状态回显用的短符号（完整文案拼起来太长）。
 */
enum class TagQueryOp(val labelRes: Int, val symbolRes: Int) {
    /** 交集：结果里必须同时含这个标签。 */
    AND(R.string.manage_tag_query_op_and, R.string.manage_tag_query_symbol_and),

    /** 差集：把含这个标签的记录从结果里去掉。 */
    NOT(R.string.manage_tag_query_op_not, R.string.manage_tag_query_symbol_not),

    /** 并集：把含这个标签的记录并进结果。 */
    OR(R.string.manage_tag_query_op_or, R.string.manage_tag_query_symbol_or),
}

/** 一行规则：对当前累积结果施加 [op]，操作数是 [tag]。[tag] 为空表示这行还没选完，求值时跳过。 */
data class TagQueryRule(val op: TagQueryOp, val tag: String)

/**
 * 「标签精细检索」的条件：一个基准标签 + 若干行规则。
 *
 * **求值是从上到下左结合的**：
 * ```
 * base=A，行1「包含 B」，行2「或 C」，行3「不包含 D」
 * 结果 = ((A ∩ B) ∪ C) − D
 * ```
 * 行序影响结果，**不要**改成「先与非、后或」那种优先级——界面上看不出优先级，
 * 用户按从上到下读，结果必须跟着这个读法。不支持括号 / 嵌套。
 *
 * 与管理页标签栏的多选（[ManageFilterState.tags]）**互斥**，互斥清理写在
 * [ManageFilterState.withTagQuery] / [ManageFilterState.withTags] 里。
 */
data class TagQuery(
    /** 基准标签；为空表示这一层未激活。 */
    val base: String = "",
    val rules: List<TagQueryRule> = emptyList(),
) {

    val isActive: Boolean get() = base.isNotBlank()

    /** 真正参与求值的规则行数（标签没选完的行不算）；菜单标题用它回显。 */
    val activeRuleCount: Int get() = rules.count { it.tag.isNotBlank() }

    /**
     * 求值涉及的全部标签（基准 + 各行操作数，去重、忽略空行）。
     * 调用方据此只查这几个标签的 id 集合，不必把全库标签都捞出来。
     */
    val involvedTags: Set<String>
        get() {
            if (!isActive) return emptySet()
            val result = LinkedHashSet<String>()
            result.add(base)
            rules.forEach { if (it.tag.isNotBlank()) result.add(it.tag) }
            return result
        }

    /**
     * 按左结合语义求出命中的 awemeId 集合。
     *
     * @param idsByTag 标签名 → 打了该标签的 awemeId 集合；查不到的标签按空集处理
     *                 （标签存在但没打给任何记录，与"不存在"等价）。
     */
    fun evaluate(idsByTag: Map<String, Set<String>>): Set<String> {
        if (!isActive) return emptySet()
        var acc: Set<String> = idsByTag[base].orEmpty()
        for (rule in rules) {
            if (rule.tag.isBlank()) continue
            val other = idsByTag[rule.tag].orEmpty()
            acc = when (rule.op) {
                TagQueryOp.AND -> acc intersect other
                TagQueryOp.NOT -> acc - other
                TagQueryOp.OR -> acc + other
            }
        }
        return acc
    }
}
