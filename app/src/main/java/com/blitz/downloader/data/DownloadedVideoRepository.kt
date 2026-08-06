package com.blitz.downloader.data

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
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

    /** 精确匹配某作者昵称的全部作品（无稳定 ID 的老记录按昵称筛选时使用）。 */
    suspend fun getByMediaTypeAndUserName(mediaType: String, userName: String): List<DownloadedVideoEntity> =
        dao.getByMediaTypeAndUserName(mediaType, userName)

    /** 按作者稳定 `sec_user_id` 精确匹配的全部作品（改名前后一并取出）。 */
    suspend fun getByMediaTypeAndAuthorSecUserId(mediaType: String, secUserId: String): List<DownloadedVideoEntity> =
        dao.getByMediaTypeAndAuthorSecUserId(mediaType, secUserId)

    /** 按作者昵称聚合作品数，按作品数倒序返回（管理页作者抽屉使用）。 */
    suspend fun getAuthorCounts(mediaType: String): List<com.blitz.downloader.data.db.DownloadedVideoDao.AuthorCount> =
        dao.getAuthorCountsByMediaType(mediaType)

    /**
     * 导出成功计数 +1（局域网导出传输完成时调用），按 500 一批避免 SQLite 变量上限。
     * @return 实际更新的行数。
     */
    suspend fun incrementExportCount(awemeIds: Collection<String>): Int {
        if (awemeIds.isEmpty()) return 0
        val asList = awemeIds.distinct()
        var updated = 0
        var i = 0
        while (i < asList.size) {
            val part = asList.subList(i, (i + 500).coerceAtMost(asList.size))
            updated += dao.incrementExportCount(part)
            i += 500
        }
        return updated
    }

    /**
     * 标记这些记录为「已看过」（管理页进入视频播放页时调用），按 500 一批避免 SQLite 变量上限。
     * 只置位不回退，重复调用无副作用。
     * @return 实际更新的行数。
     */
    suspend fun markWatched(awemeIds: Collection<String>): Int {
        if (awemeIds.isEmpty()) return 0
        val asList = awemeIds.distinct()
        var updated = 0
        var i = 0
        while (i < asList.size) {
            val part = asList.subList(i, (i + 500).coerceAtMost(asList.size))
            updated += dao.markWatched(part)
            i += 500
        }
        return updated
    }

    /**
     * 这批 id 中已看过的那些，供管理页回到列表时刷新「未看过」标记
     * （播放页里滑动看过的条目不会通知列表，只能回查）。
     */
    suspend fun getWatchedAwemeIdSet(awemeIds: Collection<String>): Set<String> {
        if (awemeIds.isEmpty()) return emptySet()
        val asList = awemeIds.distinct()
        val out = mutableSetOf<String>()
        var i = 0
        while (i < asList.size) {
            val part = asList.subList(i, (i + 500).coerceAtMost(asList.size))
            out.addAll(dao.getWatchedAwemeIdsIn(part))
            i += 500
        }
        return out
    }

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
        createTime: Long = 0L,
        desc: String = "",
        collectionType: String = "",
        collectId: String = "",
        videoAuthorSecUserId: String = "",
        sourceOwnerSecUserId: String = "",
        userRelation: String = "",
        diggCount: Long = 0L,
        collectCount: Long = 0L,
    ) {
        dao.insert(
            DownloadedVideoEntity(
                awemeId = awemeId,
                downloadType = downloadType,
                userName = userName,
                mediaType = mediaType,
                filePath = filePath,
                coverPath = coverPath,
                createTime = createTime,
                desc = desc,
                collectionType = collectionType,
                collectId = collectId,
                videoAuthorSecUserId = videoAuthorSecUserId,
                sourceOwnerSecUserId = sourceOwnerSecUserId,
                userRelation = userRelation,
                diggCount = diggCount,
                collectCount = collectCount,
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

        /**
         * [DownloadedVideoEntity.userRelation] 是否含「我点赞过」。
         * 按 `|` 拆分后逐段比对，避免收藏夹名里带 "like" 子串被误判。
         */
        fun hasLikeRelation(userRelation: String?): Boolean =
            userRelation
                ?.split('|')
                ?.any { it.trim().equals(DownloadSourceType.LIKE, ignoreCase = true) } == true
    }

    suspend fun getPageByMediaType(mediaType: String, limit: Int, offset: Int): List<DownloadedVideoEntity> =
        dao.getPageByMediaType(mediaType, limit, offset)

    /**
     * 指定排序列的分页查询（管理页排序）。[orderColumn] 仅接受来自
     * [com.blitz.downloader.ui.ManageSortOrder] 的固定列名，无注入风险；一律倒序。
     *
     * [unassignedOnly] 为管理页「按归属筛选」（见
     * [com.blitz.downloader.ui.ManageRelationFilter]）的 SQL 侧参数：`null` 不筛选，
     * `true` 只留「无归属」，`false` 排除「无归属」。筛选发生在 LIMIT 之前，故分页计数仍正确。
     */
    suspend fun getPageByMediaTypeSorted(
        mediaType: String,
        orderColumn: String,
        limit: Int,
        offset: Int,
        unassignedOnly: Boolean? = null,
    ): List<DownloadedVideoEntity> {
        val sql = "SELECT * FROM downloaded_videos WHERE mediaType = ?" +
            unassignedClause(unassignedOnly) +
            " ORDER BY $orderColumn DESC, createdAtMillis DESC LIMIT ? OFFSET ?"
        return dao.getPageRawSorted(SimpleSQLiteQuery(sql, arrayOf(mediaType, limit, offset)))
    }

    /**
     * 「无归属」（`userRelation` 与 `collectionType` 均为空）的 SQL 片段，含前导 ` AND `。
     * 判定必须与 [com.blitz.downloader.ui.ManageRelationFilter.isUnassigned] 的内存实现等价：
     * 那边用 `isBlank()`，这边用 `TRIM(...) = ''`；`IFNULL` 兼容迁移前可能残留的 NULL。
     */
    private fun unassignedClause(unassignedOnly: Boolean?): String {
        if (unassignedOnly == null) return ""
        val cond = "(TRIM(IFNULL(userRelation, '')) = '' AND TRIM(IFNULL(collectionType, '')) = '')"
        return if (unassignedOnly) " AND $cond" else " AND NOT $cond"
    }

    suspend fun countByMediaType(mediaType: String): Int = dao.countByMediaType(mediaType)

    /** 跨类型作者聚合（统计面板）。 */
    suspend fun getAuthorCountsAll(): List<com.blitz.downloader.data.db.DownloadedVideoDao.AuthorCount> =
        dao.getAuthorCountsAll()

    /**
     * 管理页按作者昵称搜索：自动转义 LIKE 元字符（`%` / `_` / `\`）并加首尾 `%`。
     * 传入空白时返回空列表（上层不应触发搜索路径）。
     */
    suspend fun searchByUserName(mediaType: String, query: String): List<DownloadedVideoEntity> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val escaped = q
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        // 注：Room 默认不附带 ESCAPE 子句；当前 awemeId/userName 几乎不会出现这些字符，
        // 双反斜杠转义已足够；如未来出现误匹配再切到自定义 @Query 加 `ESCAPE '\\'`。
        return dao.searchByMediaTypeAndUserName(mediaType, "%$escaped%")
    }
}
