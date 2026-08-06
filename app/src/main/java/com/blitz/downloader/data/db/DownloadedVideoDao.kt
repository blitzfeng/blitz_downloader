package com.blitz.downloader.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface DownloadedVideoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadedVideoEntity)

    @Update
    suspend fun update(entity: DownloadedVideoEntity)

    @Delete
    suspend fun delete(entity: DownloadedVideoEntity)

    @Query("DELETE FROM downloaded_videos WHERE id = :rowId")
    suspend fun deleteByRowId(rowId: Long)

    @Query("DELETE FROM downloaded_videos WHERE awemeId = :awemeId")
    suspend fun deleteByAwemeId(awemeId: String)

    @Query("DELETE FROM downloaded_videos WHERE awemeId IN (:awemeIds)")
    suspend fun deleteByAwemeIds(awemeIds: List<String>): Int

    /**
     * 导出成功计数 +1（局域网导出的传输完成信号触发）。
     * 单条 SQL 原子累加，避免"读实体→改→整行 update"覆盖并发写。
     * [awemeIds] 由 Repository 分批传入（SQLite 变量上限）。
     */
    @Query("UPDATE downloaded_videos SET exportCount = exportCount + 1 WHERE awemeId IN (:awemeIds)")
    suspend fun incrementExportCount(awemeIds: List<String>): Int

    /**
     * 标签修改次数 +1（用户在管理页改完标签且集合确实变化时触发）。
     * 与 [incrementExportCount] 同样走单条 SQL 原子累加，别退化成"读实体→改→整行 update"。
     * [awemeIds] 由调用方分批传入（SQLite 变量上限）。
     */
    @Query("UPDATE downloaded_videos SET tagEditCount = tagEditCount + 1 WHERE awemeId IN (:awemeIds)")
    suspend fun incrementTagEditCount(awemeIds: List<String>): Int

    /**
     * 标记为已看过（管理页进入视频播放页时触发）。只置位、不回退，重复调用无副作用。
     * [awemeIds] 由 Repository 分批传入（SQLite 变量上限）。
     */
    @Query("UPDATE downloaded_videos SET watched = 1 WHERE awemeId IN (:awemeIds)")
    suspend fun markWatched(awemeIds: List<String>): Int

    /** 这批 id 中已看过的那些（管理页回到列表时刷新「未看过」标记用）。 */
    @Query("SELECT awemeId FROM downloaded_videos WHERE watched = 1 AND awemeId IN (:awemeIds)")
    suspend fun getWatchedAwemeIdsIn(awemeIds: List<String>): List<String>

    @Query("SELECT * FROM downloaded_videos WHERE id = :rowId LIMIT 1")
    suspend fun getByRowId(rowId: Long): DownloadedVideoEntity?

    @Query("SELECT * FROM downloaded_videos WHERE awemeId = :awemeId LIMIT 1")
    suspend fun getByAwemeId(awemeId: String): DownloadedVideoEntity?

    @Query("SELECT * FROM downloaded_videos ORDER BY createdAtMillis DESC")
    suspend fun getAll(): List<DownloadedVideoEntity>

    @Query("SELECT * FROM downloaded_videos WHERE mediaType = :mediaType ORDER BY createdAtMillis DESC")
    suspend fun getAllByMediaType(mediaType: String): List<DownloadedVideoEntity>

    @Query("SELECT awemeId FROM downloaded_videos")
    suspend fun getAllAwemeIds(): List<String>

    @Query("SELECT awemeId FROM downloaded_videos WHERE awemeId IN (:ids)")
    suspend fun getAwemeIdsContainedIn(ids: List<String>): List<String>

    @Query("SELECT COUNT(*) FROM downloaded_videos WHERE mediaType = :mediaType")
    suspend fun countByMediaType(mediaType: String): Int

    @Query("SELECT * FROM downloaded_videos WHERE mediaType = :mediaType ORDER BY createdAtMillis DESC LIMIT :limit OFFSET :offset")
    suspend fun getPageByMediaType(mediaType: String, limit: Int, offset: Int): List<DownloadedVideoEntity>

    /**
     * 可指定排序列的分页查询（管理页排序用）。排序列名由 Repository 从固定枚举映射后传入，
     * **不接受用户输入**，无注入风险。
     */
    @RawQuery
    suspend fun getPageRawSorted(query: SupportSQLiteQuery): List<DownloadedVideoEntity>

    /**
     * 按作者昵称模糊搜索（管理页搜索栏使用）。
     * [userNameLike] 由 Repository 拼为 `%query%`；空查询不应走此方法（让上层走分页路径）。
     * 结果集预计不大，一次性返回；如未来体量增大再加 LIMIT 分页。
     */
    @Query("SELECT * FROM downloaded_videos WHERE mediaType = :mediaType AND userName LIKE :userNameLike ORDER BY createdAtMillis DESC")
    suspend fun searchByMediaTypeAndUserName(mediaType: String, userNameLike: String): List<DownloadedVideoEntity>

    /** 精确匹配某作者昵称的全部作品（无稳定 ID 的老记录按昵称筛选时使用）。 */
    @Query("SELECT * FROM downloaded_videos WHERE mediaType = :mediaType AND userName = :userName ORDER BY createdAtMillis DESC")
    suspend fun getByMediaTypeAndUserName(mediaType: String, userName: String): List<DownloadedVideoEntity>

    /**
     * 按作者稳定 `sec_user_id` 精确匹配的全部作品（管理页「按作者筛选」主路径）。
     * 作者改名不影响 `videoAuthorSecUserId`，故改名前后的作品都能一并取出。
     */
    @Query("SELECT * FROM downloaded_videos WHERE mediaType = :mediaType AND videoAuthorSecUserId = :secUserId ORDER BY createdAtMillis DESC")
    suspend fun getByMediaTypeAndAuthorSecUserId(mediaType: String, secUserId: String): List<DownloadedVideoEntity>

    /**
     * 按作者聚合作品数（管理页作者抽屉使用）。**按稳定 `sec_user_id` 归并**：
     * 有 ID 的按 ID 分组（改名也算一人），无 ID 的老记录退回按昵称分组。
     *
     * `name` 借助 SQLite「单个 MAX() 时裸列取自最大值所在行」的特性，取该组**最新一条**的昵称；
     * `secUserId` 为该组的稳定 ID（无 ID 组为空串）。按作品数倒序、同数量按昵称升序。
     */
    @Query(
        "SELECT userName AS name, COUNT(*) AS count, videoAuthorSecUserId AS secUserId, " +
            "MAX(createdAtMillis) AS latest FROM downloaded_videos WHERE mediaType = :mediaType " +
            "GROUP BY (CASE WHEN videoAuthorSecUserId != '' THEN videoAuthorSecUserId ELSE userName END) " +
            "ORDER BY count DESC, name ASC"
    )
    suspend fun getAuthorCountsByMediaType(mediaType: String): List<AuthorCount>

    /** 跨类型（视频+图集）的作者聚合，用于统计面板；同样按 `sec_user_id` 归并。 */
    @Query(
        "SELECT userName AS name, COUNT(*) AS count, videoAuthorSecUserId AS secUserId, " +
            "MAX(createdAtMillis) AS latest FROM downloaded_videos " +
            "GROUP BY (CASE WHEN videoAuthorSecUserId != '' THEN videoAuthorSecUserId ELSE userName END) " +
            "ORDER BY count DESC, name ASC"
    )
    suspend fun getAuthorCountsAll(): List<AuthorCount>

    /**
     * 作者聚合投影：显示昵称（最新）+ 作品数 + 稳定 ID。
     * [secUserId] 为空表示该组无稳定 ID（老记录），此时上层按 [name] 匹配。
     */
    data class AuthorCount(val name: String, val count: Int, val secUserId: String = "")
}
