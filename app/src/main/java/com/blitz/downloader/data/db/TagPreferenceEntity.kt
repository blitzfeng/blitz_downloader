package com.blitz.downloader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 按标签物化的建议准确率统计缓存（`tag_preference` 表），从 [VideoTagFeedbackEntity] 聚合而来。
 *
 * 复算方式对齐 `author_tag_frequency` 缓存表的既有模式（[TagPreferenceDao.recomputeAll]：
 * `DELETE` 全表再一条 `INSERT...SELECT` 聚合写入），触发点是每次反馈写入之后，而不是每次
 * 分析请求都重算。**只由 [TagPreferenceDao.recomputeAll] 写入，没有独立的 `@Insert` 方法**——
 * 与 `author_tag_frequency` 完全同一套模式，见其 KDoc。
 */
@Entity(tableName = "tag_preference")
data class TagPreferenceEntity(
    /** 关联 `tags.id`（[TagEntity.id]）。 */
    @PrimaryKey val tagId: Long,
    /** AI 建议过该标签的次数（`ACCEPTED` + `REJECTED`）。 */
    val suggestedCount: Int,
    val acceptedCount: Int,
    val rejectedCount: Int,
    /** AI 未建议、用户手工新增的次数。 */
    val missedCount: Int,
    /** `acceptedCount / suggestedCount`；`suggestedCount` 为 0 时是 0，不是除零错误。 */
    val acceptanceRate: Float,
    /** V2 预留：按标签设置自动采用阈值，V1 阶段固定写默认值不使用。 */
    val recommendedThreshold: Float,
    val updatedAtMillis: Long,
)
