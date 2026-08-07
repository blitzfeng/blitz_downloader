package com.blitz.downloader.model.filter

import com.blitz.downloader.R

/**
 * 管理页「按标签修改次数筛选」：按 `downloaded_videos.tagEditCount`（用户手工改过几次标签）过滤。
 *
 * 典型用途是找出从没整理过标签的记录（0 次），或反复改来改去的记录（> 5 次）。
 * 与 [ManageTagCountFilter] 的档位划分不同：这里 [COUNT_5] 是**恰好 5 次**，
 * 超过 5 次统一落到 [MORE_THAN_5]。
 *
 * 与「按归属筛选」「按标签数量筛选」一样是**叠加**的一层，不与搜索 / 标签 / 作者筛选互斥；
 * 同样**位于归属筛选之下**——只在有归属（点赞 / 收藏 / 收藏夹）的记录里数修改次数，
 * 无归属的他人主页 post 记录默认不参与，否则「0 次」会被这类记录淹没。收窄逻辑在
 * `ManageVideoFragment.scopeByRelation` 里，不在本枚举中。
 *
 * 判定只在内存里做（见 [matches]）：次数直接取自实体字段，不需要额外查询。
 */
enum class ManageTagEditCountFilter(val labelRes: Int) {
    /** 不筛选，显示全部。 */
    OFF(R.string.manage_tag_edit_count_off),
    COUNT_0(R.string.manage_tag_edit_count_0),
    COUNT_1(R.string.manage_tag_edit_count_1),
    COUNT_2(R.string.manage_tag_edit_count_2),
    COUNT_3(R.string.manage_tag_edit_count_3),
    COUNT_4(R.string.manage_tag_edit_count_4),

    /** 恰好 5 次（超过 5 次归入 [MORE_THAN_5]）。 */
    COUNT_5(R.string.manage_tag_edit_count_5),

    /** 大于 5 次（兜底，保证改得再多也能筛出来）。 */
    MORE_THAN_5(R.string.manage_tag_edit_count_more_than_5),
    ;

    val isActive: Boolean get() = this != OFF

    /** 某条视频的标签修改次数是否命中当前筛选。 */
    fun matches(editCount: Int): Boolean = when (this) {
        OFF -> true
        COUNT_0 -> editCount == 0
        COUNT_1 -> editCount == 1
        COUNT_2 -> editCount == 2
        COUNT_3 -> editCount == 3
        COUNT_4 -> editCount == 4
        COUNT_5 -> editCount == 5
        MORE_THAN_5 -> editCount > 5
    }

    companion object {
        val DEFAULT = OFF

        /** 逐档列出的修改次数上限，超过该值统一归入 [MORE_THAN_5]。 */
        const val MAX_COUNT = 5
    }
}
