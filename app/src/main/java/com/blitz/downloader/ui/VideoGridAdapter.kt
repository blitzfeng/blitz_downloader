package com.blitz.downloader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.blitz.downloader.R

data class VideoItemUiModel(
    val id: String,
    val title: String,
    val coverUrl: String?,
    /** 列表接口 [com.blitz.downloader.api.AwemeItem] 中 play_addr 的首选直链，已做 `playwm`→`play` 处理；无则无法批量下载。 */
    val downloadUrl: String?,
    val isSelected: Boolean,
)

class VideoGridAdapter(
    private var items: List<VideoItemUiModel>,
    private val onItemClicked: (VideoItemUiModel) -> Unit
) : RecyclerView.Adapter<VideoGridAdapter.VideoViewHolder>() {

    fun submitList(newItems: List<VideoItemUiModel>) {
        val diff = DiffUtil.calculateDiff(VideoDiffCallback(items, newItems))
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_grid, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ImageView = itemView.findViewById(R.id.ivCover)
        private val title: TextView = itemView.findViewById(R.id.tvTitle)
        private val checkbox: CheckBox = itemView.findViewById(R.id.cbSelected)
        private val overlay: View = itemView.findViewById(R.id.selectionOverlay)

        fun bind(item: VideoItemUiModel) {
            title.text = item.title

            if (!item.coverUrl.isNullOrBlank()) {
                cover.load(item.coverUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_video_placeholder)
                    error(R.drawable.ic_video_placeholder)
                    transformations(RoundedCornersTransformation(8f))
                }
            } else {
                cover.setImageResource(R.drawable.ic_video_placeholder)
            }

            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = item.isSelected
            overlay.visibility = if (item.isSelected) View.VISIBLE else View.GONE

            itemView.setOnClickListener { onItemClicked(item) }
            checkbox.setOnClickListener { onItemClicked(item) }
        }
    }

    private class VideoDiffCallback(
        private val old: List<VideoItemUiModel>,
        private val new: List<VideoItemUiModel>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) = old[oldPos].id == new[newPos].id
        override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == new[newPos]
    }
}
