package com.blitz.downloader.ui

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blitz.downloader.R
import com.blitz.downloader.data.DownloadedVideoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ManageImageFragment : Fragment(R.layout.fragment_manage_image) {

    private lateinit var adapter: ManageGridAdapter
    private lateinit var repo: DownloadedVideoRepository

    private var currentOffset = 0
    private var isLoading = false
    private var hasMore = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repo = DownloadedVideoRepository(requireContext())

        val recyclerView: RecyclerView = view.findViewById(R.id.rvManageImages)
        val progress: ProgressBar = view.findViewById(R.id.progressManageImage)
        val tvEmpty: TextView = view.findViewById(R.id.tvEmptyManageImage)

        adapter = ManageGridAdapter()
        val gridManager = GridLayoutManager(requireContext(), 2)
        recyclerView.layoutManager = gridManager
        recyclerView.adapter = adapter

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lastVisible = gridManager.findLastVisibleItemPosition()
                val total = adapter.itemCount
                if (!isLoading && hasMore && lastVisible >= total - 4) {
                    loadNextPage(progress, tvEmpty)
                }
            }
        })

        loadNextPage(progress, tvEmpty)
    }

    private fun loadNextPage(progress: ProgressBar, tvEmpty: TextView) {
        if (isLoading || !hasMore) return
        isLoading = true
        progress.visibility = if (currentOffset == 0) View.VISIBLE else View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val entities = withContext(Dispatchers.IO) {
                repo.getPageByMediaType(MEDIA_TYPE_IMAGE, PAGE_SIZE, currentOffset)
            }

            progress.visibility = View.GONE

            if (entities.size < PAGE_SIZE) hasMore = false
            currentOffset += entities.size

            if (entities.isEmpty() && currentOffset == 0) {
                tvEmpty.visibility = View.VISIBLE
            } else {
                tvEmpty.visibility = View.GONE
                // 图片不做文件存在性校验，fileExists 默认 true
                val newItems = entities.map { ManageGridItem(it, fileExists = true) }
                adapter.addItems(newItems)
            }

            isLoading = false
        }
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val MEDIA_TYPE_IMAGE = "image"
    }
}
