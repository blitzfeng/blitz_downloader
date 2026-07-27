package com.blitz.downloader.ui

import com.blitz.downloader.data.db.DownloadedVideoEntity

/**
 * 管理页各 Tab Fragment 的公共契约，由 [ManageActivity] 调用以驱动菜单行为。
 */
interface ManageTabFragment {
    /** 当前是否处于多选模式。 */
    val inSelectionMode: Boolean

    /** 当前已选条数。 */
    val selectedCount: Int

    /** 当前已加载条目是否已全部选中（决定全选按钮显示「全选」还是「取消全选」）。 */
    val isAllSelected: Boolean get() = false

    /** 是否支持「清除已失效」（图片 Tab 不支持）。 */
    val supportsClearInvalid: Boolean

    /** 退出多选模式，取消所有选中。 */
    fun exitSelectionMode()

    /**
     * 删除所有已选条目（DB + Adapter）。
     * 实现应在 [androidx.lifecycle.lifecycleScope] 内运行，完成后通过 Toast 告知结果。
     */
    fun handleDeleteSelected()

    /**
     * 扫描 DB 中所有同类型记录，删除本地文件不存在的条目。
     * 视频 Tab 实现真正逻辑；图片 Tab 可空实现。
     */
    fun handleClearInvalid()

    /**
     * 弹出多选标签对话框，为所有已选条目批量**追加**标签。
     * 默认空实现，有标签功能的 Tab 覆盖此方法。
     */
    fun handleSetTagsSelected() {}

    /**
     * 应用搜索关键词（按作者昵称模糊匹配）。
     * - `query` 为 null 或空白：退出搜索，恢复正常分页/筛选；
     * - 非空：按 `%query%` 在当前 Tab 的 mediaType 下全量查询并替换列表。
     *
     * 由 [com.blitz.downloader.activity.ManageActivity] 的 SearchView 监听器调用。
     */
    fun applySearchQuery(query: String?) {}

    /**
     * 返回当前已选中条目的完整实体（含 `filePath`），供「导出选中」使用。
     * 未初始化或未选中时返回空列表。
     */
    fun getSelectedEntities(): List<DownloadedVideoEntity> = emptyList()

    /**
     * 切换全选：已全选则取消全选（保持多选模式），否则全选当前已加载条目。
     */
    fun handleToggleSelectAll() {}

    /**
     * 当前作者筛选的分组键（`secUserId` 优先，无则昵称）；未按作者筛选时为 null。
     * 供作者抽屉预选中当前作者。
     */
    val activeAuthorKey: String? get() = null

    /**
     * 按作者筛选当前 Tab 列表：优先按稳定 [secUserId]（改名前后一并显示），
     * [secUserId] 为空时按 [userName]（老记录无稳定 ID）。二者都为空 / null 表示清除筛选。
     * 作者筛选与搜索、标签互斥（设置作者时应清空后两者）。
     */
    fun applyAuthorFilter(secUserId: String?, userName: String?) {}

    /** 当前排序方式（供排序对话框预选中）。 */
    val activeSortOrder: ManageSortOrder get() = ManageSortOrder.DEFAULT

    /** 切换排序方式并从头刷新列表。 */
    fun applySort(sort: ManageSortOrder) {}
}
