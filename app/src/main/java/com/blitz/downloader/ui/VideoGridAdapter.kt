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
    /** 作者昵称；用于批量下载文件名 `{昵称}_{描述}`。 */
    val authorNickname: String,
    /** 接口原始文案（trim），空则无描述；文件名会去掉 #话题 后再用。 */
    val descRaw: String,
    val coverUrl: String?,
    /** 列表接口 [com.blitz.downloader.api.AwemeItem] 中 play_addr 的首选直链，已做 `playwm`→`play` 处理；无则无法批量下载。图集类型为 null。 */
    val downloadUrl: String?,
    val isSelected: Boolean,
    /** 本地库中已记录下载（按作品 id）；列表中禁止勾选。 */
    val isDownloaded: Boolean = false,
    /** 是否为图集/图文类型（aweme_type=68，[images] 字段非空）。 */
    val isPhoto: Boolean = false,
    /** 图集所有图片的最优下载 URL 列表（[isPhoto]=true 时非空）。 */
    val imageUrls: List<String> = emptyList(),
    /** 视频创作者的稳定 `sec_user_id`，来自 [com.blitz.downloader.api.Author.secUid]。写入 DB 时用。 */
    val authorSecUserId: String = "",
    /**
     * 喜欢列表接口返回的 `collect_stat`（0=未收藏，1=已收藏）。
     * 用于构建 [com.blitz.downloader.data.db.DownloadedVideoEntity.userRelation]。
     */
    val collectStat: Int = 0,
    /**
     * 收藏夹接口返回的 `user_digged`（0=未点赞，1=已点赞）。
     * 用于构建 [com.blitz.downloader.data.db.DownloadedVideoEntity.userRelation]。
     */
    val userDigged: Int = 0,
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
        private val downloadedBadge: TextView = itemView.findViewById(R.id.tvDownloadedBadge)
        private val photoBadge: TextView = itemView.findViewById(R.id.tvPhotoBadge)

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

            if (item.isPhoto && item.imageUrls.isNotEmpty()) {
                photoBadge.visibility = View.VISIBLE
                photoBadge.text = itemView.context.getString(R.string.grid_badge_photo, item.imageUrls.size)
            } else {
                photoBadge.visibility = View.GONE
            }

            if (item.isDownloaded) {
                downloadedBadge.visibility = View.VISIBLE
                checkbox.isEnabled = false
                checkbox.alpha = 0.45f
                checkbox.setOnCheckedChangeListener(null)
                checkbox.isChecked = false
                overlay.visibility = View.GONE
                itemView.setOnClickListener(null)
                checkbox.setOnClickListener(null)
            } else {
                downloadedBadge.visibility = View.GONE
                checkbox.isEnabled = true
                checkbox.alpha = 1f
                checkbox.setOnCheckedChangeListener(null)
                checkbox.isChecked = item.isSelected
                overlay.visibility = if (item.isSelected) View.VISIBLE else View.GONE
                itemView.setOnClickListener { onItemClicked(item) }
                checkbox.setOnClickListener { onItemClicked(item) }
            }
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
