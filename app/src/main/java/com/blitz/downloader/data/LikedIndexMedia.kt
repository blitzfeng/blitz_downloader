package com.blitz.downloader.data

import com.blitz.downloader.data.db.LikedListIndexItemEntity
import com.blitz.downloader.model.VideoItemUiModel
import com.google.gson.Gson

/** 图片与对应实况视频一起存取，避免 null 项被滤掉后下标错配。 */
object LikedIndexMedia {
    private val gson = Gson()
    private data class Photo(val image: String, val video: String?)

    fun encode(item: VideoItemUiModel): String = gson.toJson(
        item.imageUrls.mapIndexed { index, url -> Photo(url, item.imageVideoUrls.getOrNull(index)) })

    fun restore(item: LikedListIndexItemEntity): VideoItemUiModel {
        val photos = try {
            item.photoMediaJson?.let { gson.fromJson(it, Array<Photo>::class.java)?.toList() }
                .orEmpty().filter { !it.image.isNullOrBlank() }
        } catch (_: RuntimeException) { emptyList() }
        val isPhoto = item.isPhoto || photos.isNotEmpty()
        return VideoItemUiModel(
            id = item.awemeId, title = item.title, authorNickname = item.authorNickname,
            descRaw = item.description, coverUrl = item.coverUrl,
            downloadUrl = if (isPhoto) null else item.mediaUrl,
            isSelected = false, isPhoto = isPhoto,
            imageUrls = photos.map { it.image }, imageVideoUrls = photos.map { it.video },
            authorSecUserId = item.authorSecUserId, collectStat = item.collectStat,
            userDigged = item.userDigged, createTime = item.createTime,
            diggCount = item.diggCount, collectCount = item.collectCount,
        )
    }
}
