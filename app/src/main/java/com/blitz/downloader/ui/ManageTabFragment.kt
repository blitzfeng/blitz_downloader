package com.blitz.downloader.ui

/**
 * 管理页各 Tab Fragment 的公共契约，由 [ManageActivity] 调用以驱动菜单行为。
 */
interface ManageTabFragment {
    /** 当前是否处于多选模式。 */
    val inSelectionMode: Boolean

    /** 当前已选条数。 */
    val selectedCount: Int

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
}
