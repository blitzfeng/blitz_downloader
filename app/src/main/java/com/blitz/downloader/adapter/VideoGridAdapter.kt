package com.blitz.downloader.adapter

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
import com.blitz.downloader.model.VideoItemUiModel
import com.blitz.downloader.util.DiggBadgeStyle
import com.blitz.downloader.util.NumberFormatUtils

class VideoGridAdapter(
    private var items: List<VideoItemUiModel>,
    private val onItemClicked: (VideoItemUiModel) -> Unit,
    /** 点击「查看TA的Post」图标，后续实现时在 Fragment 中补充逻辑。 */
    private val onAuthorPostsClicked: ((VideoItemUiModel) -> Unit)? = null,
    /** 点击「预览」图标，后续实现时在 Fragment 中补充逻辑。 */
    private val onPreviewClicked: ((VideoItemUiModel) -> Unit)? = null,
) : RecyclerView.Adapter<VideoGridAdapter.VideoViewHolder>() {

    /**
     * 当前列表处于 UserPost 模式时为 true。
     * 此时「查看TA的Post」按钮置灰不可点击（已经在看 Post，无需重复触发）。
     */
    private var isUserPostMode: Boolean = false

    fun setUserPostMode(active: Boolean) {
        if (isUserPostMode == active) return
        isUserPostMode = active
        notifyItemRangeChanged(0, items.size, PAYLOAD_AUTHOR_BTN)
    }

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

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.contains(PAYLOAD_AUTHOR_BTN)) {
            holder.bindAuthorPostsButton(items[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ImageView = itemView.findViewById(R.id.ivCover)
        private val title: TextView = itemView.findViewById(R.id.tvTitle)
        private val checkbox: CheckBox = itemView.findViewById(R.id.cbSelected)
        private val overlay: View = itemView.findViewById(R.id.selectionOverlay)
        private val downloadedBadge: TextView = itemView.findViewById(R.id.tvDownloadedBadge)
        private val photoBadge: TextView = itemView.findViewById(R.id.tvPhotoBadge)
        private val diggBadge: TextView = itemView.findViewById(R.id.tvDiggCount)
        private val btnAuthorPosts: View = itemView.findViewById(R.id.btnAuthorPosts)
        private val btnPreview: View = itemView.findViewById(R.id.btnPreview)

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
                // 只选了部分图片时徽标改成 `3/9张`，避免"选完就看不出选了几张"
                photoBadge.text = if (item.hasPartialImageSelection) {
                    itemView.context.getString(
                        R.string.grid_badge_photo_partial,
                        item.selectedImageIndices?.size ?: item.imageUrls.size,
                        item.imageUrls.size,
                    )
                } else {
                    itemView.context.getString(R.string.grid_badge_photo, item.imageUrls.size)
                }
            } else {
                photoBadge.visibility = View.GONE
            }

            // 点赞数徽标：接口未下发或为 0 时直接隐藏；心形默认白色，接口 user_digged=1（我点赞过）着抖音红
            val diggText = NumberFormatUtils.formatChineseCount(item.diggCount)
            if (diggText.isEmpty()) {
                diggBadge.visibility = View.GONE
            } else {
                diggBadge.visibility = View.VISIBLE
                diggBadge.text = diggText
                DiggBadgeStyle.tintHeart(diggBadge, item.userDigged == 1)
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

            // 操作栏按钮对所有 item（含已下载）均可点击
            btnPreview.setOnClickListener {
                onPreviewClicked?.invoke(item)
            }

            bindAuthorPostsButton(item)
        }

        /** 单独刷新「查看TA的Post」按钮的可用状态，供 payload 局部更新复用。 */
        fun bindAuthorPostsButton(item: VideoItemUiModel) {
            val enabled = !isUserPostMode
            btnAuthorPosts.isEnabled = enabled
            btnAuthorPosts.alpha = if (enabled) 1f else 0.35f
            btnAuthorPosts.setOnClickListener(
                if (enabled) View.OnClickListener { onAuthorPostsClicked?.invoke(item) } else null
            )
        }
    }

    companion object {
        private const val PAYLOAD_AUTHOR_BTN = "payload_author_btn"
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
