package com.blitz.downloader.model.filter

/**
 * 管理页七层筛选的完整状态快照。
 *
 * 七层是**叠加**关系而非互斥的单选（详见 CLAUDE.md「管理页的筛选栈」）：
 * 搜索 / 作者 / 标签多选 / 标签精细检索 / 归属 / 标签数量 / 标签修改次数。
 * 其中作者与搜索、标签之间互斥，标签多选与标签精细检索之间也互斥
 * （同一时刻只有一种标签口径），其余各层可同时生效。
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
    /** 标签精细检索，与 [tags] 互斥；`isActive` 为 false 表示这一层未激活。 */
    val tagQuery: TagQuery = TagQuery(),
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

    /**
     * 会影响 Toolbar 菜单**标题**的那几层筛选。
     *
     * 菜单标题回显归属 / 标签数量 / 标签修改次数 / 标签精细检索四层，订阅方据此判断要不要
     * `invalidateOptionsMenu()`。**不要**改成整个 [ManageFilterState] 都订阅：
     * 搜索词每敲一个字都会变，菜单跟着重建会把 SearchView 一起重建掉，导致无法输入。
     */
    val menuTitleSignature: MenuTitleSignature
        get() = MenuTitleSignature(relation, tagCounts, tagEditCount, tagQuery)

    /**
     * 选中标签时调用：标签与搜索、作者互斥，不清就会出现「点了标签但列表还按搜索/作者筛」；
     * 与标签精细检索同样互斥，否则两种标签口径会同时生效。
     */
    fun withTags(newTags: Set<String>): ManageFilterState =
        copy(
            tags = newTags,
            tagQuery = TagQuery(),
            searchQuery = "",
            authorSecId = "",
            authorName = "",
        )

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
            copy(
                authorSecId = sec,
                authorName = name,
                searchQuery = "",
                tags = emptySet(),
                tagQuery = TagQuery(),
            )
        }
    }

    /**
     * 应用标签精细检索：与标签多选、搜索、作者三者互斥，一并清掉。
     * 传 `TagQuery()`（未激活）即清除本层筛选。
     */
    fun withTagQuery(query: TagQuery): ManageFilterState =
        copy(
            tagQuery = query,
            tags = emptySet(),
            searchQuery = "",
            authorSecId = "",
            authorName = "",
        )
}

/**
 * 影响 Toolbar 菜单标题的筛选层快照。
 *
 * 独立成类而不是继续用 `Triple`：位置到头了，而且具名字段能让「为什么只订阅这几层」
 * 一目了然（见 [ManageFilterState.menuTitleSignature]）。
 */
data class MenuTitleSignature(
    val relation: ManageRelationFilter,
    val tagCounts: Set<ManageTagCountFilter>,
    val tagEditCount: ManageTagEditCountFilter,
    val tagQuery: TagQuery,
)
