package com.blitz.downloader.ui

import android.content.Context
import com.blitz.downloader.R

/**
 * 管理页「按标签数量筛选」：按一条视频身上打了几个标签来过滤。
 *
 * **可多选，档位之间取并集**（命中任一档即显示）——例如同时勾「0 个」「5 个及以上」可以一次
 * 捞出"还没整理"和"标签打太多"两头。状态载体是 `Set<ManageTagCountFilter>`，
 * **空集合 = 不筛选**（所以枚举里不再有 OFF 档，判定统一走 [matchesAny]）。
 *
 * 典型用途是找出还没打标签（0 个）或标签打太多的记录。上限是 5——
 * [COUNT_5_PLUS] 取「5 个及以上」而非恰好 5 个，否则标签超过 5 个的视频
 * 在任何选项下都筛不出来。
 *
 * 与「按归属筛选」一样是**叠加**的一层，不与搜索 / 标签 / 作者筛选互斥；但它**位于归属筛选
 * 之下**——只在有归属（点赞 / 收藏 / 收藏夹）的记录里数标签，无归属的他人主页 post 记录
 * 默认不参与，否则「0 个标签」会被这类记录淹没。这个收窄逻辑在
 * `ManageVideoFragment.scopeByRelation` 里，不在本枚举中。
 *
 * 判定只在内存里做（见 [matches]）：标签数来自 `video_tags` 的
 * `GROUP BY awemeId` 聚合，没有关联行的视频即 0 个。
 *
 * @property labelRes 选择对话框里的完整文案。
 * @property shortLabelRes 菜单标题 / 空状态里回显用的短文案（多选时要拼接，完整文案太长）。
 */
enum class ManageTagCountFilter(val labelRes: Int, val shortLabelRes: Int) {
    COUNT_0(R.string.manage_tag_count_0, R.string.manage_tag_count_short_0),
    COUNT_1(R.string.manage_tag_count_1, R.string.manage_tag_count_short_1),
    COUNT_2(R.string.manage_tag_count_2, R.string.manage_tag_count_short_2),
    COUNT_3(R.string.manage_tag_count_3, R.string.manage_tag_count_short_3),
    COUNT_4(R.string.manage_tag_count_4, R.string.manage_tag_count_short_4),

    /** 5 个及以上（兜底，保证标签数超过上限的记录仍可筛出）。 */
    COUNT_5_PLUS(R.string.manage_tag_count_5_plus, R.string.manage_tag_count_short_5_plus),
    ;

    /** 某条视频的标签数是否命中**这一档**；多选判定用 [matchesAny]。 */
    fun matches(tagCount: Int): Boolean = when (this) {
        COUNT_0 -> tagCount == 0
        COUNT_1 -> tagCount == 1
        COUNT_2 -> tagCount == 2
        COUNT_3 -> tagCount == 3
        COUNT_4 -> tagCount == 4
        COUNT_5_PLUS -> tagCount >= 5
    }

    companion object {
        /** 默认不筛选 = 空集合。 */
        val DEFAULT: Set<ManageTagCountFilter> = emptySet()

        /** 可筛选的标签数量上限，超过该值统一归入 [COUNT_5_PLUS]。 */
        const val MAX_COUNT = 5

        /** 多选判定：空集合视为不筛选，否则命中任一勾选档位即通过（并集）。 */
        fun matchesAny(filters: Set<ManageTagCountFilter>, tagCount: Int): Boolean =
            filters.isEmpty() || filters.any { it.matches(tagCount) }

        /** 勾选档位的短文案拼接（如 `0 个、5 个及以上`），顺序固定按枚举声明序，与勾选先后无关。 */
        fun shortSummary(context: Context, filters: Set<ManageTagCountFilter>): String =
            values().filter { it in filters }
                .joinToString("、") { context.getString(it.shortLabelRes) }
    }
}
