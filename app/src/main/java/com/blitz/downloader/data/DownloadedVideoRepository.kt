package com.blitz.downloader.data

import android.content.Context
import com.blitz.downloader.data.db.AppDatabase
import com.blitz.downloader.data.db.DownloadedVideoEntity

/**
 * 已下载视频的持久化与查询：对外提供完整 CRUD，列表页用 [getDownloadedAwemeIdSet] 做角标与不可选。
 */
class DownloadedVideoRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).downloadedVideoDao()

    suspend fun insert(entity: DownloadedVideoEntity) = dao.insert(entity)

    suspend fun update(entity: DownloadedVideoEntity) = dao.update(entity)

    suspend fun delete(entity: DownloadedVideoEntity) = dao.delete(entity)

    suspend fun deleteByRowId(rowId: Long) = dao.deleteByRowId(rowId)

    suspend fun deleteByAwemeId(awemeId: String) = dao.deleteByAwemeId(awemeId)

    suspend fun getByRowId(rowId: Long): DownloadedVideoEntity? = dao.getByRowId(rowId)

    suspend fun getByAwemeId(awemeId: String): DownloadedVideoEntity? = dao.getByAwemeId(awemeId)

    suspend fun getAll(): List<DownloadedVideoEntity> = dao.getAll()

    /** 用于网格：是否存在本地已记录下载（按作品 id）。 */
    suspend fun getDownloadedAwemeIdSet(ids: Collection<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val asList = ids.distinct()
        val chunk = 500
        val out = mutableSetOf<String>()
        var i = 0
        while (i < asList.size) {
            val part = asList.subList(i, (i + chunk).coerceAtMost(asList.size))
            out.addAll(dao.getAwemeIdsContainedIn(part))
            i += chunk
        }
        return out
    }

    suspend fun recordSuccessfulDownload(
        awemeId: String,
        downloadType: String,
        userName: String,
        mediaType: String = DownloadMediaType.VIDEO,
        filePath: String = "",
        coverPath: String = "",
    ) {
        dao.insert(
            DownloadedVideoEntity(
                awemeId = awemeId,
                downloadType = downloadType,
                userName = userName,
                mediaType = mediaType,
                filePath = filePath,
                coverPath = coverPath,
            ),
        )
    }

    suspend fun getPageByMediaType(mediaType: String, limit: Int, offset: Int): List<DownloadedVideoEntity> =
        dao.getPageByMediaType(mediaType, limit, offset)

    suspend fun countByMediaType(mediaType: String): Int = dao.countByMediaType(mediaType)
}
