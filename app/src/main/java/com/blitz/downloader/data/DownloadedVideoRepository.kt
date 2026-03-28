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

    suspend fun deleteByAwemeIds(awemeIds: List<String>): Int = dao.deleteByAwemeIds(awemeIds)

    suspend fun getByRowId(rowId: Long): DownloadedVideoEntity? = dao.getByRowId(rowId)

    suspend fun getByAwemeId(awemeId: String): DownloadedVideoEntity? = dao.getByAwemeId(awemeId)

    suspend fun getAll(): List<DownloadedVideoEntity> = dao.getAll()

    suspend fun getAllByMediaType(mediaType: String): List<DownloadedVideoEntity> =
        dao.getAllByMediaType(mediaType)

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
        desc: String = "",
        collectionType: String = "",
        collectId: String = "",
        videoAuthorSecUserId: String = "",
        sourceOwnerSecUserId: String = "",
        userRelation: String = "",
    ) {
        dao.insert(
            DownloadedVideoEntity(
                awemeId = awemeId,
                downloadType = downloadType,
                userName = userName,
                mediaType = mediaType,
                filePath = filePath,
                coverPath = coverPath,
                desc = desc,
                collectionType = collectionType,
                collectId = collectId,
                videoAuthorSecUserId = videoAuthorSecUserId,
                sourceOwnerSecUserId = sourceOwnerSecUserId,
                userRelation = userRelation,
            ),
        )
    }

    companion object {
        /**
         * 从喜欢列表下载时，根据 `collect_stat` 构建 [DownloadedVideoEntity.userRelation]。
         *
         * @param collectStat 接口返回的 `collect_stat` 字段：0=未收藏，1=已收藏。
         */
        fun buildUserRelationFromLike(collectStat: Int): String =
            if (collectStat == 1) "like|collect" else "like"

        /**
         * 从收藏夹下载时，根据 `user_digged` 和收藏夹名称构建 [DownloadedVideoEntity.userRelation]。
         *
         * @param userDigged 接口返回的 `user_digged` 字段：0=未点赞，1=已点赞。
         * @param folderName 收藏夹名称（对应 [DownloadedVideoEntity.collectionType]）。
         */
        fun buildUserRelationFromCollection(userDigged: Int, folderName: String): String =
            if (userDigged == 1) "like|$folderName" else folderName
    }

    suspend fun getPageByMediaType(mediaType: String, limit: Int, offset: Int): List<DownloadedVideoEntity> =
        dao.getPageByMediaType(mediaType, limit, offset)

    suspend fun countByMediaType(mediaType: String): Int = dao.countByMediaType(mediaType)
}
