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
import androidx.recyclerview.widget.RecyclerView
import com.blitz.downloader.R
import com.blitz.downloader.activity.ImageViewerActivity
import com.blitz.downloader.activity.ManageActivity
import com.blitz.downloader.data.DownloadedVideoRepository
import com.blitz.downloader.data.db.DownloadedVideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ManageImageFragment : Fragment(R.layout.fragment_manage_image), ManageTabFragment {

    private lateinit var adapter: ManageGridAdapter
    private lateinit var repo: DownloadedVideoRepository
    private var tvEmptyRef: TextView? = null

    private var currentOffset = 0
    private var isLoading = false
    private var hasMore = true

    /** 当前激活的搜索关键词（trim 后非空时启用搜索路径）。 */
    private var activeSearchQuery: String = ""

    /**
     * 当前作者筛选：优先按稳定 ID [activeAuthorSecId]，无 ID 时按昵称 [activeAuthorName]。
     * 二者都为空表示未按作者筛选。
     */
    private var activeAuthorSecId: String = ""
    private var activeAuthorName: String = ""

    private fun hasAuthorFilter(): Boolean = activeAuthorSecId.isNotBlank() || activeAuthorName.isNotBlank()
    private fun clearAuthorFilterState() {
        activeAuthorSecId = ""
        activeAuthorName = ""
    }

    /** 当前排序方式（默认下载时间倒序）。 */
    private var activeSort: ManageSortOrder = ManageSortOrder.DEFAULT

    override val inSelectionMode: Boolean get() = ::adapter.isInitialized && adapter.inSelectionMode
    override val selectedCount: Int get() = if (::adapter.isInitialized) adapter.getSelectedAwemeIds().size else 0
    override val supportsClearInvalid: Boolean get() = false

    override fun exitSelectionMode() {
        if (::adapter.isInitialized) adapter.exitSelectionMode()
    }

    override fun getSelectedEntities(): List<DownloadedVideoEntity> {
        if (!::adapter.isInitialized) return emptyList()
        val selected = adapter.getSelectedAwemeIds().toSet()
        if (selected.isEmpty()) return emptyList()
        return adapter.getItems().filter { it.entity.awemeId in selected }.map { it.entity }
    }

    // 「已全选」= 当前范围全部记录都已加载（无更多分页）且全部选中；否则按钮显示「全选」。
    override val isAllSelected: Boolean
        get() = ::adapter.isInitialized && !hasMore && adapter.isAllSelected()

    override fun handleToggleSelectAll() {
        if (!::adapter.isInitialized) return
        // 当前范围已全部加载：纯内存切换，不重建列表、不丢滚动位置。
        if (!hasMore) {
            if (adapter.isAllSelected()) adapter.deselectAll() else adapter.selectAll()
            return
        }
        // 仍有未加载的分页：先把当前范围（搜索 / 全部）在数据库中的全部记录拉齐再全选。
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
        }
    }

    /** 当前范围（搜索 / 全部）在数据库中的全部记录，供全选使用。 */
    private suspend fun loadFullScopeEntities(): List<DownloadedVideoEntity> = withContext(Dispatchers.IO) {
        val list = when {
            hasAuthorFilter() -> loadAuthorEntities(MEDIA_TYPE_IMAGE)
            activeSearchQuery.isNotBlank() -> repo.searchByUserName(MEDIA_TYPE_IMAGE, activeSearchQuery)
            else -> repo.getAllByMediaType(MEDIA_TYPE_IMAGE)
        }
        sortEntities(list)
    }

    /** 按当前 [activeSort] 对内存中的实体列表倒序排序。 */
    private fun sortEntities(list: List<DownloadedVideoEntity>): List<DownloadedVideoEntity> = when (activeSort) {
        ManageSortOrder.DOWNLOAD_TIME -> list.sortedByDescending { it.createdAtMillis }
        ManageSortOrder.PUBLISH_TIME -> list.sortedByDescending { it.createTime }
        ManageSortOrder.DIGG -> list.sortedByDescending { it.diggCount }
    }

    override val activeSortOrder: ManageSortOrder get() = activeSort

    override fun applySort(sort: ManageSortOrder) {
        if (sort == activeSort) return
        activeSort = sort
        refreshList()
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
                    if (adapter.itemCount == 0 && !hasMore) {
                        tvEmptyRef?.visibility = View.VISIBLE
                    }
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

    override fun handleClearInvalid() {
        // 图片 Tab 不支持此操作
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repo = DownloadedVideoRepository(requireContext())

        val recyclerView: RecyclerView = view.findViewById(R.id.rvManageImages)
        val progress: ProgressBar = view.findViewById(R.id.progressManageImage)
        val tvEmpty: TextView = view.findViewById(R.id.tvEmptyManageImage)
        tvEmptyRef = tvEmpty

        adapter = ManageGridAdapter(
            onSelectionChanged = { active, count ->
                (activity as? ManageActivity)?.onSelectionChanged(active, count)
            },
            onItemClick = { entity ->
                openImageViewer(entity)
            },
            supportsUserTags = false,
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
        tvEmptyRef = null
        super.onDestroyView()
    }

    private fun loadNextPage(progress: ProgressBar, tvEmpty: TextView) {
        if (isLoading || !hasMore) return
        isLoading = true
        if (currentOffset == 0) progress.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val entities = withContext(Dispatchers.IO) {
                when {
                    // 作者筛选 / 搜索：一次性返回匹配集，后续不再分页
                    hasAuthorFilter() ->
                        if (currentOffset == 0) sortEntities(loadAuthorEntities(MEDIA_TYPE_IMAGE)) else emptyList()
                    activeSearchQuery.isNotBlank() ->
                        if (currentOffset == 0) sortEntities(repo.searchByUserName(MEDIA_TYPE_IMAGE, activeSearchQuery)) else emptyList()
                    else -> repo.getPageByMediaTypeSorted(MEDIA_TYPE_IMAGE, activeSort.column, PAGE_SIZE, currentOffset)
                }
            }
            progress.visibility = View.GONE
            when {
                hasAuthorFilter() -> hasMore = false
                activeSearchQuery.isNotBlank() -> hasMore = false
                entities.size < PAGE_SIZE -> hasMore = false
            }
            currentOffset += entities.size

            if (entities.isEmpty() && currentOffset == 0) {
                tvEmpty.text = when {
                    hasAuthorFilter() -> getString(R.string.manage_author_empty, activeAuthorName)
                    activeSearchQuery.isNotBlank() -> getString(R.string.manage_search_empty, activeSearchQuery)
                    else -> getString(R.string.manage_empty_images)
                }
                tvEmpty.visibility = View.VISIBLE
            } else {
                tvEmpty.visibility = View.GONE
                adapter.addItems(entities.map { ManageGridItem(it) })
            }
            isLoading = false
        }
    }

    override fun applySearchQuery(query: String?) {
        val q = query?.trim().orEmpty()
        if (q == activeSearchQuery) return
        activeSearchQuery = q
        if (q.isNotBlank()) clearAuthorFilterState()
        refreshList()
    }

    override val activeAuthorKey: String?
        get() = activeAuthorSecId.ifBlank { activeAuthorName }.ifBlank { null }

    override fun applyAuthorFilter(secUserId: String?, userName: String?) {
        val sec = secUserId?.trim().orEmpty()
        val name = userName?.trim().orEmpty()
        if (sec == activeAuthorSecId && name == activeAuthorName) return
        activeAuthorSecId = sec
        activeAuthorName = name
        if (sec.isNotBlank() || name.isNotBlank()) activeSearchQuery = ""
        refreshList()
    }

    /** 按当前作者筛选取该 mediaType 下的全部作品：有稳定 ID 走 ID，无则退回昵称。 */
    private suspend fun loadAuthorEntities(mediaType: String): List<DownloadedVideoEntity> =
        if (activeAuthorSecId.isNotBlank()) {
            repo.getByMediaTypeAndAuthorSecUserId(mediaType, activeAuthorSecId)
        } else {
            repo.getByMediaTypeAndUserName(mediaType, activeAuthorName)
        }

    /** 从头加载（搜索切换 / 退出搜索时调用）。 */
    private fun refreshList() {
        currentOffset = 0
        hasMore = true
        adapter.clearItems()
        val progress = view?.findViewById<ProgressBar>(R.id.progressManageImage) ?: return
        val tvEmpty = view?.findViewById<TextView>(R.id.tvEmptyManageImage) ?: return
        loadNextPage(progress, tvEmpty)
    }

    private fun openImageViewer(entity: DownloadedVideoEntity) {
        if (entity.filePath.isBlank()) {
            Toast.makeText(requireContext(), R.string.player_file_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        @Suppress("DEPRECATION")
        val file = File(Environment.getExternalStorageDirectory(), entity.filePath)
        if (!file.exists()) {
            Toast.makeText(requireContext(), R.string.player_file_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        val title = entity.desc.trim().ifBlank { entity.userName.ifBlank { entity.awemeId } }
        val intent = Intent(requireContext(), ImageViewerActivity::class.java).apply {
            putExtra(ImageViewerActivity.EXTRA_FILE_PATH, entity.filePath)
            putExtra(ImageViewerActivity.EXTRA_TITLE, title)
        }
        startActivity(intent)
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val MEDIA_TYPE_IMAGE = "image"
    }
}
