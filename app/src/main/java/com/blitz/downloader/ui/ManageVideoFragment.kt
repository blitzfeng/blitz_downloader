package com.blitz.downloader.ui

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blitz.downloader.R
import com.blitz.downloader.activity.ManageActivity
import com.blitz.downloader.activity.VideoPlayerActivity
import com.blitz.downloader.data.DownloadedVideoRepository
import com.blitz.downloader.data.VideoTagRepository
import com.blitz.downloader.data.db.DownloadedVideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ManageVideoFragment : Fragment(R.layout.fragment_manage_video), ManageTabFragment {

    private lateinit var adapter: ManageGridAdapter
    private lateinit var tagFilterAdapter: TagFilterAdapter
    private lateinit var repo: DownloadedVideoRepository
    private lateinit var tagRepo: VideoTagRepository
    private var rvRef: RecyclerView? = null
    private var tvEmptyRef: TextView? = null

    private var currentOffset = 0
    private var isLoading = false
    private var hasMore = true

    /** 当前激活的标签过滤，null 或 TAG_ALL 表示不过滤。 */
    private var activeTag: String = TagFilterAdapter.TAG_ALL

    /** 当前激活的搜索关键词（trim 后非空时启用搜索路径，忽略 [activeTag]）。 */
    private var activeSearchQuery: String = ""

    /** 当前激活的作者昵称筛选（非空时优先于搜索/标签，精确匹配 userName）。 */
    private var activeAuthor: String = ""

    override val inSelectionMode: Boolean get() = ::adapter.isInitialized && adapter.inSelectionMode
    override val selectedCount: Int get() = if (::adapter.isInitialized) adapter.getSelectedAwemeIds().size else 0
    override val supportsClearInvalid: Boolean get() = true

    override fun exitSelectionMode() {
        if (::adapter.isInitialized) adapter.exitSelectionMode()
    }

    override fun getSelectedEntities(): List<DownloadedVideoEntity> {
        if (!::adapter.isInitialized) return emptyList()
        val selected = adapter.getSelectedAwemeIds().toSet()
        if (selected.isEmpty()) return emptyList()
        return adapter.getItems().filter { it.entity.awemeId in selected }.map { it.entity }
    }

    // 「已全选」= 当前范围全部记录都已加载（无更多分页）且全部选中。
    // 仅当满足此条件时按钮才显示「取消全选」，否则显示「全选」（点击会拉取全库该范围记录）。
    override val isAllSelected: Boolean
        get() = ::adapter.isInitialized && !hasMore && adapter.isAllSelected()

    override fun handleToggleSelectAll() {
        if (!::adapter.isInitialized) return
        // 当前范围已全部加载：纯内存切换，不重建列表、不丢滚动位置。
        if (!hasMore) {
            if (adapter.isAllSelected()) adapter.deselectAll() else adapter.selectAll()
            return
        }
        // 仍有未加载的分页：先把当前范围（搜索 / 标签 / 全部）在数据库中的全部记录拉齐再全选。
        viewLifecycleOwner.lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) { loadFullScopeEntities() }
            if (all.isEmpty()) return@launch
            adapter.clearItems()
            currentOffset = all.size
            hasMore = false
            isLoading = false
            tvEmptyRef?.visibility = View.GONE
            adapter.addItems(all.map { ManageGridItem(it) })
            adapter.selectAll()
            checkFileExistence(all)
            loadAndApplyTags(all)
        }
    }

    /** 当前范围（搜索 / 标签 / 全部）在数据库中的全部记录，供全选使用。 */
    private suspend fun loadFullScopeEntities(): List<DownloadedVideoEntity> = withContext(Dispatchers.IO) {
        when {
            activeAuthor.isNotBlank() -> repo.getByMediaTypeAndUserName(MEDIA_TYPE_VIDEO, activeAuthor)
            activeSearchQuery.isNotBlank() -> repo.searchByUserName(MEDIA_TYPE_VIDEO, activeSearchQuery)
            activeTag == TagFilterAdapter.TAG_ALL -> repo.getAllByMediaType(MEDIA_TYPE_VIDEO)
            else -> tagRepo.getVideosByTag(activeTag).filter { it.mediaType == MEDIA_TYPE_VIDEO }
        }
    }

    override fun handleDeleteSelected() {
        if (!::adapter.isInitialized) return
        val ids = adapter.getSelectedAwemeIds()
        if (ids.isEmpty()) return

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manage_confirm_delete_title)
            .setMessage(getString(R.string.manage_confirm_delete_msg, ids.size))
            .setPositiveButton(R.string.manage_confirm_ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) { repo.deleteByAwemeIds(ids) }
                    adapter.removeItems(ids.toSet())
                    checkEmptyState()
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.manage_delete_done, deleted),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.manage_confirm_cancel, null)
            .show()
    }

    override fun handleSetTagsSelected() {
        if (!::adapter.isInitialized) return
        val ids = adapter.getSelectedAwemeIds()
        if (ids.isEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch {
            val allTags = withContext(Dispatchers.IO) { tagRepo.getAvailableTags() }
            if (allTags.isEmpty()) {
                Toast.makeText(requireContext(), R.string.manage_set_tags_no_tags, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val checkedItems = BooleanArray(allTags.size) { false }
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.manage_set_tags_title, ids.size))
                .setMultiChoiceItems(allTags.toTypedArray(), checkedItems) { _, which, isChecked ->
                    checkedItems[which] = isChecked
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val chosen = allTags.filterIndexed { i, _ -> checkedItems[i] }
                    if (chosen.isEmpty()) {
                        Toast.makeText(requireContext(), R.string.manage_set_tags_none_checked, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        val tagsMap = withContext(Dispatchers.IO) {
                            ids.forEach { awemeId -> tagRepo.addTags(awemeId, chosen) }
                            tagRepo.getTagsMapForVideos(ids)
                        }
                        // 局部刷新每条受影响 item 的标签行
                        ids.forEach { id ->
                            adapter.updateItemTags(id, tagsMap[id] ?: emptyList())
                        }
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.manage_set_tags_done, ids.size),
                            Toast.LENGTH_SHORT
                        ).show()
                        adapter.exitSelectionMode()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun handleClearInvalid() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manage_menu_clear_invalid)
            .setMessage(R.string.manage_clear_invalid_confirm)
            .setPositiveButton(R.string.manage_confirm_ok) { _, _ ->
                doClearInvalid()
            }
            .setNegativeButton(R.string.manage_confirm_cancel, null)
            .show()
    }

    private fun doClearInvalid() {
        viewLifecycleOwner.lifecycleScope.launch {
            val invalidIds = withContext(Dispatchers.IO) {
                repo.getAllByMediaType(MEDIA_TYPE_VIDEO)
                    .filter { it.filePath.isNotBlank() && !File(Environment.getExternalStorageDirectory(), it.filePath).exists() }
                    .map { it.awemeId }
            }
            if (invalidIds.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.manage_clear_invalid_none), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val deleted = withContext(Dispatchers.IO) { repo.deleteByAwemeIds(invalidIds) }
            refreshList()
            Toast.makeText(
                requireContext(),
                getString(R.string.manage_clear_invalid_done, deleted),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repo = DownloadedVideoRepository(requireContext())
        tagRepo = VideoTagRepository(requireContext())

        // ── 标签过滤栏 ──────────────────────────────────────────────────────────
        val rvTagFilter: RecyclerView = view.findViewById(R.id.rvTagFilter)
        tagFilterAdapter = TagFilterAdapter { tag ->
            // 选择标签时强制清掉搜索词与作者筛选；标签与二者互斥，
            // 不清就会出现「点了标签但列表还按搜索/作者筛」的迷之结果。
            activeSearchQuery = ""
            activeAuthor = ""
            activeTag = tag
            refreshList()
        }
        rvTagFilter.layoutManager = LinearLayoutManager(
            requireContext(), LinearLayoutManager.HORIZONTAL, false
        )
        rvTagFilter.adapter = tagFilterAdapter
        loadTagFilterBar()

        // ── 视频网格 ──────────────────────────────────────────────────────────
        val recyclerView: RecyclerView = view.findViewById(R.id.rvManageVideos)
        val progress: ProgressBar = view.findViewById(R.id.progressManageVideo)
        val tvEmpty: TextView = view.findViewById(R.id.tvEmptyManageVideo)
        rvRef = recyclerView
        tvEmptyRef = tvEmpty

        adapter = ManageGridAdapter(
            onSelectionChanged = { active, count ->
                (activity as? ManageActivity)?.onSelectionChanged(active, count)
            },
            onItemClick = { entity ->
                openVideoPlayer(entity)
            },
            onTagAreaClick = { awemeId, currentTags ->
                showTagEditDialog(awemeId, currentTags)
            },
            supportsUserTags = true,
        )
        val gridManager = GridLayoutManager(requireContext(), 2)
        recyclerView.layoutManager = gridManager
        recyclerView.adapter = adapter

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lastVisible = gridManager.findLastVisibleItemPosition()
                if (!isLoading && hasMore && lastVisible >= adapter.itemCount - 4) {
                    loadNextPage(progress, tvEmpty)
                }
            }
        })

        loadNextPage(progress, tvEmpty)
    }

    override fun onDestroyView() {
        rvRef = null
        tvEmptyRef = null
        super.onDestroyView()
    }

    /** 从 tags 表加载所有可用标签，填充标签过滤栏。 */
    private fun loadTagFilterBar() {
        viewLifecycleOwner.lifecycleScope.launch {
            val tags = withContext(Dispatchers.IO) { tagRepo.getAvailableTags() }
            tagFilterAdapter.submitTags(tags)
        }
    }

    private fun loadNextPage(progress: ProgressBar, tvEmpty: TextView) {
        if (isLoading || !hasMore) return
        isLoading = true
        if (currentOffset == 0) progress.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val entities: List<DownloadedVideoEntity>
            when {
                activeAuthor.isNotBlank() -> {
                    // 作者筛选：精确匹配昵称，一次性返回全部，不再分页
                    entities = if (currentOffset == 0) {
                        withContext(Dispatchers.IO) {
                            repo.getByMediaTypeAndUserName(MEDIA_TYPE_VIDEO, activeAuthor)
                        }
                    } else {
                        emptyList()
                    }
                    hasMore = false
                    currentOffset += entities.size
                }
                activeSearchQuery.isNotBlank() -> {
                    // 搜索路径：按昵称全量查询，忽略标签过滤
                    entities = if (currentOffset == 0) {
                        withContext(Dispatchers.IO) {
                            repo.searchByUserName(MEDIA_TYPE_VIDEO, activeSearchQuery)
                        }
                    } else {
                        emptyList()
                    }
                    hasMore = false
                    currentOffset += entities.size
                }
                activeTag == TagFilterAdapter.TAG_ALL -> {
                    // 全量分页
                    entities = withContext(Dispatchers.IO) {
                        repo.getPageByMediaType(MEDIA_TYPE_VIDEO, PAGE_SIZE, currentOffset)
                    }
                    if (entities.size < PAGE_SIZE) hasMore = false
                    currentOffset += entities.size
                }
                else -> {
                    // 按标签全量加载（标签结果集通常不大，不做额外分页）
                    entities = if (currentOffset == 0) {
                        withContext(Dispatchers.IO) {
                            tagRepo.getVideosByTag(activeTag)
                                .filter { it.mediaType == MEDIA_TYPE_VIDEO }
                        }
                    } else {
                        emptyList()
                    }
                    hasMore = false
                    currentOffset += entities.size
                }
            }

            progress.visibility = View.GONE

            if (entities.isEmpty() && currentOffset == 0) {
                tvEmpty.text = when {
                    activeAuthor.isNotBlank() -> getString(R.string.manage_author_empty, activeAuthor)
                    activeSearchQuery.isNotBlank() -> getString(R.string.manage_search_empty, activeSearchQuery)
                    else -> getString(R.string.manage_empty_videos)
                }
                tvEmpty.visibility = View.VISIBLE
            } else {
                tvEmpty.visibility = View.GONE
                adapter.addItems(entities.map { ManageGridItem(it) })
                checkFileExistence(entities)
                loadAndApplyTags(entities)
            }
            isLoading = false
        }
    }

    override fun applySearchQuery(query: String?) {
        val q = query?.trim().orEmpty()
        if (q == activeSearchQuery) return
        activeSearchQuery = q
        // 主搜索启用时清掉作者筛选（二者互斥）；activeTag 不动，退出搜索后仍回到之前选中的标签
        if (q.isNotBlank()) activeAuthor = ""
        refreshList()
    }

    override val activeAuthorName: String?
        get() = activeAuthor.ifBlank { null }

    override fun applyAuthorFilter(userName: String?) {
        val name = userName?.trim().orEmpty()
        if (name == activeAuthor) return
        activeAuthor = name
        if (name.isNotBlank()) {
            // 作者筛选与搜索/标签互斥
            activeSearchQuery = ""
            activeTag = TagFilterAdapter.TAG_ALL
            tagFilterAdapter.resetToAll()
        }
        refreshList()
    }

    /**
     * 在 IO 线程批量查询 [entities] 中每条视频的用户自定义标签，
     * 回主线程通过 [ManageGridAdapter.updateItemTags] 局部刷新标签行。
     */
    private fun loadAndApplyTags(entities: List<DownloadedVideoEntity>) {
        if (entities.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val awemeIds = entities.map { it.awemeId }
            val tagsMap = tagRepo.getTagsMapForVideos(awemeIds)
            withContext(Dispatchers.Main) {
                awemeIds.forEach { id ->
                    val tags = tagsMap[id] ?: emptyList()
                    adapter.updateItemTags(id, tags)
                }
            }
        }
    }

    /**
     * 在 IO 线程检查文件存在性；不存在时回主线程更新 Adapter 显示失效蒙层。
     * 只检查 filePath 非空的记录（旧记录无路径，保守不标失效）。
     */
    private fun checkFileExistence(entities: List<DownloadedVideoEntity>) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            entities.forEach { entity ->
                if (entity.filePath.isBlank()) return@forEach
                val exists = File(Environment.getExternalStorageDirectory(), entity.filePath).exists()
                if (!exists) {
                    withContext(Dispatchers.Main) { adapter.updateFileExists(entity.awemeId, false) }
                }
            }
        }
    }

    private fun refreshList() {
        currentOffset = 0
        hasMore = true
        adapter.clearItems()
        val progress = view?.findViewById<ProgressBar>(R.id.progressManageVideo) ?: return
        val tvEmpty = view?.findViewById<TextView>(R.id.tvEmptyManageVideo) ?: return
        loadNextPage(progress, tvEmpty)
    }

    private fun checkEmptyState() {
        if (adapter.itemCount == 0 && !hasMore) {
            tvEmptyRef?.visibility = View.VISIBLE
        }
    }

    /**
     * 点击标签行时弹出标签设置弹窗：
     * - 显示系统中全部可用标签，当前已打的预先勾选；
     * - 勾选即添加、取消即删除，确认后整体覆盖写库并局部刷新卡片。
     */
    private fun showTagEditDialog(awemeId: String, currentTags: List<String>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val allTags = withContext(Dispatchers.IO) { tagRepo.getAvailableTags() }
            if (allTags.isEmpty()) {
                Toast.makeText(requireContext(), R.string.manage_set_tags_no_tags, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val currentSet = currentTags.toSet()
            val checkedItems = BooleanArray(allTags.size) { allTags[it] in currentSet }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.manage_edit_tags_title)
                .setMultiChoiceItems(allTags.toTypedArray(), checkedItems) { _, which, isChecked ->
                    checkedItems[which] = isChecked
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val selected = allTags.filterIndexed { i, _ -> checkedItems[i] }
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { tagRepo.setTags(awemeId, selected) }
                        adapter.updateItemTags(awemeId, selected)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun openVideoPlayer(entity: DownloadedVideoEntity) {
        if (entity.filePath.isBlank()) {
            Toast.makeText(requireContext(), R.string.player_file_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        @Suppress("DEPRECATION")
        if (!File(Environment.getExternalStorageDirectory(), entity.filePath).exists()) {
            Toast.makeText(requireContext(), R.string.player_file_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        // 构建当前已加载列表，传入播放器以支持上下滑动切换
        val allItems = adapter.getItems().filter { it.entity.filePath.isNotBlank() }
        val position = allItems.indexOfFirst { it.entity.awemeId == entity.awemeId }

        startActivity(
            VideoPlayerActivity.createListFileIntent(
                context = requireContext(),
                filePaths = ArrayList(allItems.map { it.entity.filePath }),
                titles = ArrayList(allItems.map { item ->
                    item.entity.desc.trim().ifBlank { item.entity.userName.ifBlank { item.entity.awemeId } }
                }),
                subtitles = ArrayList(allItems.map { item -> item.entity.userName }),
                position = if (position >= 0) position else 0,
            )
        )
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val MEDIA_TYPE_VIDEO = "video"
    }
}
