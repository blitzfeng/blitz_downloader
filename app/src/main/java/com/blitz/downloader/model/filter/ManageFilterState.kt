package com.blitz.downloader.model.filter

/**
 * 管理页六层筛选的完整状态快照。
 *
 * 六层是**叠加**关系而非互斥的单选（详见 CLAUDE.md「管理页的筛选栈」）：
 * 搜索 / 作者 / 标签多选 / 归属 / 标签数量 / 标签修改次数。
 * 其中作者与搜索、标签之间互斥（设置其一时清掉另外两个），其余各层可同时生效。
 *
 * 这个类是 Activity（Toolbar / 抽屉 / 筛选对话框）与各 Tab 取数之间的唯一契约：
 * 条件从一处产生、整体传下去，避免每加一层筛选就多一对 getter/setter。
 */
data class ManageFilterState(
    /** 作者昵称模糊搜索词，空表示不搜索。 */
    val searchQuery: String = "",
    /** 作者筛选的稳定 ID（优先于 [authorName]）。 */
    val authorSecId: String = "",
    /** 作者筛选的昵称（老记录无稳定 ID 时使用）；同时用于空状态文案与抽屉回显。 */
    val authorName: String = "",
    /** 标签多选，空集合表示不过滤（标签栏上的「全部」）。交集 / 并集由设置页决定。 */
    val tags: Set<String> = emptySet(),
    val sort: ManageSortOrder = ManageSortOrder.DEFAULT,
    val relation: ManageRelationFilter = ManageRelationFilter.DEFAULT,
    /** 标签数量档位，**空集合 = 不筛选**，多个档位取并集。 */
    val tagCounts: Set<ManageTagCountFilter> = ManageTagCountFilter.DEFAULT,
    val tagEditCount: ManageTagEditCountFilter = ManageTagEditCountFilter.DEFAULT,
) {

    val hasAuthorFilter: Boolean get() = authorSecId.isNotBlank() || authorName.isNotBlank()

    /**
     * 有没有只能在内存里判定的筛选层激活。
     *
     * 「标签数量」「标签修改次数」都没有 SQL 实现，任一激活就必须切成全量加载——
     * 否则「一页 20 条筛剩 2 条、列表撑不满不触发滚动加载」看起来就像数据丢了。
     */
    val hasMemoryOnlyFilter: Boolean get() = tagCounts.isNotEmpty() || tagEditCount.isActive

    /** 作者筛选的分组键（`secUserId` 优先，无则昵称）；未按作者筛选时为 null。 */
    val authorKey: String? get() = authorSecId.ifBlank { authorName }.ifBlank { null }

    /** 选中标签时调用：标签与搜索、作者互斥，不清就会出现「点了标签但列表还按搜索/作者筛」。 */
    fun withTags(newTags: Set<String>): ManageFilterState =
        copy(tags = newTags, searchQuery = "", authorSecId = "", authorName = "")

    /** 启用搜索时清掉作者筛选（二者互斥）；[tags] 不动，退出搜索后仍回到之前选中的标签。 */
    fun withSearchQuery(query: String): ManageFilterState {
        val q = query.trim()
        return if (q.isBlank()) copy(searchQuery = "") else copy(searchQuery = q, authorSecId = "", authorName = "")
    }

    /** 设置作者筛选时清掉搜索与标签（三者互斥）；传空表示清除作者筛选。 */
    fun withAuthor(secUserId: String?, userName: String?): ManageFilterState {
        val sec = secUserId?.trim().orEmpty()
        val name = userName?.trim().orEmpty()
        return if (sec.isBlank() && name.isBlank()) {
            copy(authorSecId = "", authorName = "")
        } else {
            copy(authorSecId = sec, authorName = name, searchQuery = "", tags = emptySet())
        }
    }
}
