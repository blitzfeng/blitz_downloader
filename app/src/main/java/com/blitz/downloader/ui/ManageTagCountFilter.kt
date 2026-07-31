package com.blitz.downloader.ui

import com.blitz.downloader.R

/**
 * 管理页「按标签数量筛选」：按一条视频身上打了几个标签来过滤。
 *
 * 典型用途是找出还没打标签（0 个）或标签打太多的记录。上限是 5——
 * [COUNT_5_PLUS] 取「5 个及以上」而非恰好 5 个，否则标签超过 5 个的视频
 * 在任何选项下都筛不出来。
 *
 * 与「按归属筛选」一样是**叠加**的一层，不与搜索 / 标签 / 作者筛选互斥；但它**位于归属筛选
 * 之下**——只在有归属（点赞 / 收藏 / 收藏夹）的记录里数标签，无归属的他人主页 post 记录
 * 默认不参与，否则「0 个标签」会被这类记录淹没。这个收窄逻辑在
 * `ManageVideoFragment.filterByTagCount` 里，不在本枚举中。
 *
 * 判定只在内存里做（见 [matches]）：标签数来自 `video_tags` 的
 * `GROUP BY awemeId` 聚合，没有关联行的视频即 0 个。
 */
enum class ManageTagCountFilter(val labelRes: Int) {
    /** 不筛选，显示全部。 */
    OFF(R.string.manage_tag_count_off),
    COUNT_0(R.string.manage_tag_count_0),
    COUNT_1(R.string.manage_tag_count_1),
    COUNT_2(R.string.manage_tag_count_2),
    COUNT_3(R.string.manage_tag_count_3),
    COUNT_4(R.string.manage_tag_count_4),

    /** 5 个及以上（兜底，保证标签数超过上限的记录仍可筛出）。 */
    COUNT_5_PLUS(R.string.manage_tag_count_5_plus),
    ;

    val isActive: Boolean get() = this != OFF

    /** 某条视频的标签数是否命中当前筛选。 */
    fun matches(tagCount: Int): Boolean = when (this) {
        OFF -> true
        COUNT_0 -> tagCount == 0
        COUNT_1 -> tagCount == 1
        COUNT_2 -> tagCount == 2
        COUNT_3 -> tagCount == 3
        COUNT_4 -> tagCount == 4
        COUNT_5_PLUS -> tagCount >= 5
    }

    companion object {
        val DEFAULT = OFF

        /** 可筛选的标签数量上限，超过该值统一归入 [COUNT_5_PLUS]。 */
        const val MAX_COUNT = 5
    }
}
