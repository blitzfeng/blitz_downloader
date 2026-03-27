package com.blitz.downloader.ui

import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.blitz.downloader.R
import com.blitz.downloader.data.db.DownloadedVideoEntity
import java.io.File

data class ManageGridItem(
    val entity: DownloadedVideoEntity,
    /** true = 文件存在（或未检查），false = 文件已不存在 → 显示失效蒙层。 */
    val fileExists: Boolean = true,
)

class ManageGridAdapter : RecyclerView.Adapter<ManageGridAdapter.ViewHolder>() {

    private val items = mutableListOf<ManageGridItem>()

    /** awemeId → list index，用于快速定位失效状态更新。 */
    private val indexByAwemeId = mutableMapOf<String, Int>()

    fun addItems(newItems: List<ManageGridItem>) {
        val insertStart = items.size
        items.addAll(newItems)
        newItems.forEachIndexed { i, item ->
            indexByAwemeId[item.entity.awemeId] = insertStart + i
        }
        notifyItemRangeInserted(insertStart, newItems.size)
    }

    fun updateFileExists(awemeId: String, exists: Boolean) {
        val pos = indexByAwemeId[awemeId] ?: return
        if (pos < items.size && items[pos].entity.awemeId == awemeId) {
            items[pos] = items[pos].copy(fileExists = exists)
            notifyItemChanged(pos, PAYLOAD_FILE_EXISTS)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_manage_video, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_FILE_EXISTS)) {
            holder.bindInvalidState(items[position].fileExists)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ImageView = itemView.findViewById(R.id.ivCover)
        private val invalidOverlay: View = itemView.findViewById(R.id.viewInvalidOverlay)
        private val invalidBadge: TextView = itemView.findViewById(R.id.tvInvalidBadge)
        private val username: TextView = itemView.findViewById(R.id.tvUsername)

        fun bind(item: ManageGridItem) {
            username.text = item.entity.userName.ifBlank { item.entity.awemeId }

            val coverPath = item.entity.coverPath
            if (coverPath.isNotBlank()) {
                val file = File(Environment.getExternalStorageDirectory(), coverPath)
                cover.load(file) {
                    crossfade(true)
                    placeholder(R.drawable.ic_video_placeholder)
                    error(R.drawable.ic_video_placeholder)
                    transformations(RoundedCornersTransformation(8f))
                }
            } else {
                cover.setImageResource(R.drawable.ic_video_placeholder)
            }

            bindInvalidState(item.fileExists)
        }

        fun bindInvalidState(fileExists: Boolean) {
            val gone = View.GONE
            val visible = View.VISIBLE
            invalidOverlay.visibility = if (fileExists) gone else visible
            invalidBadge.visibility = if (fileExists) gone else visible
        }
    }

    companion object {
        private const val PAYLOAD_FILE_EXISTS = "file_exists"
    }
}
