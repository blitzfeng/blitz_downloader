package com.blitz.downloader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TagDao {

    /** 新建标签；若已存在则忽略（幂等）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity)

    /** 批量新建标签；已存在的自动跳过。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tags: List<TagEntity>)

    /** 删除标签名称（调用方需同步删除 video_tags 中的关联行）。 */
    @Query("DELETE FROM tags WHERE tagName = :tagName")
    suspend fun delete(tagName: String)

    /** 重命名标签（仅更新标签名册；调用方需同步更新 video_tags）。 */
    @Query("UPDATE tags SET tagName = :newName WHERE tagName = :oldName")
    suspend fun rename(oldName: String, newName: String)

    /** 返回所有标签名，按 [TagEntity.sortOrder] 升序（相同时按字典序）。 */
    @Query("SELECT tagName FROM tags ORDER BY sortOrder ASC, tagName ASC")
    suspend fun getAll(): List<String>

    /** 返回所有标签实体，按 sortOrder 升序，供管理页展示与重排序使用。 */
    @Query("SELECT * FROM tags ORDER BY sortOrder ASC, tagName ASC")
    suspend fun getAllEntities(): List<TagEntity>

    /** 查询当前最大 sortOrder；表为空时返回 -1，新标签应使用 MAX+1。 */
    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM tags")
    suspend fun getMaxSortOrder(): Int

    /** 更新单个标签的排序值。 */
    @Query("UPDATE tags SET sortOrder = :sortOrder WHERE tagName = :tagName")
    suspend fun updateSortOrder(tagName: String, sortOrder: Int)

    /** 判断标签名是否已存在。 */
    @Query("SELECT COUNT(*) FROM tags WHERE tagName = :tagName")
    suspend fun exists(tagName: String): Int

    /** 单个标签当前的上级标签名；标签不存在时返回 null，存在但无上级时返回空字符串。 */
    @Query("SELECT parentTagName FROM tags WHERE tagName = :tagName")
    suspend fun getParentTagName(tagName: String): String?

    /** 以 [tagName] 为上级的直接子标签列表。 */
    @Query("SELECT tagName FROM tags WHERE parentTagName = :tagName")
    suspend fun getChildren(tagName: String): List<String>

    /** 设置或清除单个标签的上级（传空字符串即清除）。 */
    @Query("UPDATE tags SET parentTagName = :parentTagName WHERE tagName = :tagName")
    suspend fun updateParentTagName(tagName: String, parentTagName: String)

    /**
     * 把所有以 [oldParent] 为上级的标签，批量改成以 [newParent] 为上级。
     * 重命名标签时同步引用（[newParent] 传新名字）、删除标签时清空引用（[newParent] 传空字符串）。
     */
    @Query("UPDATE tags SET parentTagName = :newParent WHERE parentTagName = :oldParent")
    suspend fun reassignChildren(oldParent: String, newParent: String)
}
