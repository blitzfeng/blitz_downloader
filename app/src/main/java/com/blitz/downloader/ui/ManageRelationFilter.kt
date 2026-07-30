package com.blitz.downloader.ui

import com.blitz.downloader.R
import com.blitz.downloader.data.db.DownloadedVideoEntity

/**
 * 管理页「按归属筛选」：以 `userRelation` 与 `collectionType` **同时为空**（下称**条件A**，
 * 即既没有喜欢/收藏关系，也不属于任何收藏夹——通常是从他人主页 post 下载来的记录）为轴，
 * 提供三态开关。
 *
 * 这是**叠加**在其他筛选之上的一层：Tab（视频/图集）、标签、昵称搜索、按作者筛选、排序
 * 的结果都要再过这一层，而不是互斥关系。SQL 侧走 [unassignedOnly]（分页路径），
 * 内存侧走 [apply]（搜索 / 标签 / 作者 / 全选等一次性取全量的路径），两侧判定必须等价。
 */
enum class ManageRelationFilter(val labelRes: Int) {
    /** 不筛选，显示全部。 */
    OFF(R.string.manage_relation_off),

    /** 排除条件A（隐藏无归属记录）。 */
    EXCLUDE_UNASSIGNED(R.string.manage_relation_exclude),

    /** 反选：只显示条件A（无归属记录）。 */
    ONLY_UNASSIGNED(R.string.manage_relation_only),
    ;

    /**
     * 传给 Repository 分页查询的 SQL 侧参数：
     * `null` 不加条件，`true` 只留条件A，`false` 排除条件A。
     */
    val unassignedOnly: Boolean?
        get() = when (this) {
            OFF -> null
            ONLY_UNASSIGNED -> true
            EXCLUDE_UNASSIGNED -> false
        }

    /** 对内存中的实体列表应用同一判定（与 SQL 侧等价）。 */
    fun apply(list: List<DownloadedVideoEntity>): List<DownloadedVideoEntity> = when (this) {
        OFF -> list
        ONLY_UNASSIGNED -> list.filter { isUnassigned(it) }
        EXCLUDE_UNASSIGNED -> list.filterNot { isUnassigned(it) }
    }

    companion object {
        val DEFAULT = OFF

        /** 条件A：`userRelation` 与 `collectionType` 都为空（空白也算空，与 SQL 的 TRIM 对齐）。 */
        fun isUnassigned(entity: DownloadedVideoEntity): Boolean =
            entity.userRelation.isBlank() && entity.collectionType.isBlank()
    }
}
