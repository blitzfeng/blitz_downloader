package com.blitz.downloader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 独立标签名称表，管理标签的生命周期（创建、重命名、删除），与视频无关。
 *
 * 与 [VideoTagEntity] 的关系：
 * - [TagEntity] 存"有哪些标签"（标签名册）。
 * - [VideoTagEntity] 存"哪个视频打了哪个标签"（关联关系）。
 * - 删除 [TagEntity] 时，需同步删除 [VideoTagEntity] 中对应的行（Repository 层负责）。
 */
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val tagName: String,
    /** 展示顺序，数值越小越靠前；用户在标签管理页拖拽排序后持久化。 */
    val sortOrder: Int = 0,
    /**
     * 上级标签名，空字符串表示无上级（顶层标签）。构成森林（每个标签至多一个上级）。
     * 仅作为勾选界面的默认值来源与 AI 建议的上下文，**不是写入时的强制约束**——
     * 详见 [com.blitz.downloader.data.VideoTagRepository] 中层级相关方法的 KDoc。
     */
    val parentTagName: String = "",
    /**
     * 稳定数值标识，`tagName` 改名不受影响。`0` 表示尚未分配（迁移前的历史标签，或早期版本
     * 通过原始 SQL 预插入的默认标签）——新建标签由 [com.blitz.downloader.data.VideoTagRepository.createTag]
     * 自动分配，历史数据由设置页「补齐标签 ID」一次性回填（[com.blitz.downloader.data.VideoTagRepository.backfillTagIds]）。
     * **不是主键**，`tagName` 仍是主键与既有查询/外键语义的基础；这个字段只服务于 AI 建议相关的新表
     * （反馈记录、准确率统计）按 id 关联标签，避免改名导致历史关联错配或丢失。
     */
    val id: Long = 0,
    /**
     * 人工填写的标签判断标准，供 AI 建议标签时理解标签语义、避免模型按通用语义自行发挥。
     * 空字符串表示未填写；发起 AI 建议请求时随标签词表一并提供（非空才带上）。
     */
    val description: String = "",
)
