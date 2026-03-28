package com.blitz.downloader.ui

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
import com.blitz.downloader.activity.ManageActivity
import com.blitz.downloader.data.DownloadedVideoRepository
import com.blitz.downloader.data.db.DownloadedVideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ManageVideoFragment : Fragment(R.layout.fragment_manage_video), ManageTabFragment {

    private lateinit var adapter: ManageGridAdapter
    private lateinit var repo: DownloadedVideoRepository
    private var rvRef: RecyclerView? = null
    private var tvEmptyRef: TextView? = null

    private var currentOffset = 0
    private var isLoading = false
    private var hasMore = true

    override val inSelectionMode: Boolean get() = ::adapter.isInitialized && adapter.inSelectionMode
    override val selectedCount: Int get() = if (::adapter.isInitialized) adapter.getSelectedAwemeIds().size else 0
    override val supportsClearInvalid: Boolean get() = true

    override fun exitSelectionMode() {
        if (::adapter.isInitialized) adapter.exitSelectionMode()
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
            // 刷新整个列表以反映最新状态
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

        val recyclerView: RecyclerView = view.findViewById(R.id.rvManageVideos)
        val progress: ProgressBar = view.findViewById(R.id.progressManageVideo)
        val tvEmpty: TextView = view.findViewById(R.id.tvEmptyManageVideo)
        rvRef = recyclerView
        tvEmptyRef = tvEmpty

        adapter = ManageGridAdapter { active, count ->
            (activity as? ManageActivity)?.onSelectionChanged(active, count)
        }
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

    private fun loadNextPage(progress: ProgressBar, tvEmpty: TextView) {
        if (isLoading || !hasMore) return
        isLoading = true
        if (currentOffset == 0) progress.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val entities = withContext(Dispatchers.IO) {
                repo.getPageByMediaType(MEDIA_TYPE_VIDEO, PAGE_SIZE, currentOffset)
            }
            progress.visibility = View.GONE
            if (entities.size < PAGE_SIZE) hasMore = false
            currentOffset += entities.size

            if (entities.isEmpty() && currentOffset == 0) {
                tvEmpty.visibility = View.VISIBLE
            } else {
                tvEmpty.visibility = View.GONE
                adapter.addItems(entities.map { ManageGridItem(it) })
                checkFileExistence(entities)
            }
            isLoading = false
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

    companion object {
        private const val PAGE_SIZE = 20
        private const val MEDIA_TYPE_VIDEO = "video"
    }
}
