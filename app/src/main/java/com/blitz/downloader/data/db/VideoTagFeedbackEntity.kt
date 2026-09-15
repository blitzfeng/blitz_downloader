package com.blitz.downloader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 按标签逐行记录的 AI 建议反馈（`video_tag_feedback` 表），取代"整段建议 vs 整段确认"的粗粒度
 * 快照——能支撑按标签统计准确率（见 [TagPreferenceEntity]）。
 *
 * [kind] 取值见 [com.blitz.downloader.data.AiTagFeedbackKind]。一次「AI 建议 → 用户确认」写入
 * 多行：AI 建议且保留的标签各一行 `ACCEPTED`，AI 建议但被删除的各一行 `REJECTED`，
 * AI 未建议但用户手动勾选的各一行 `MISSED`。
 *
 * 与任何既有表无 Room 外键强约束（见 `ai-tag-suggestions` design.md Migration Plan）——
 * 标签被删除后历史反馈记录仍应保留用于回溯，`tagId` 引用失效不影响这张表本身的完整性。
 */
@Entity(
    tableName = "video_tag_feedback",
    indices = [Index("tagId"), Index("awemeId")],
)
data class VideoTagFeedbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val awemeId: String,
    /** 关联 [VideoAiAnalysisEntity.id]。 */
    val analysisId: Long,
    /** 关联 `tags.id`（[TagEntity.id]）。 */
    val tagId: Long,
    /** "ACCEPTED" | "REJECTED" | "MISSED"，见 [com.blitz.downloader.data.AiTagFeedbackKind]。 */
    val kind: String,
    /** 模型返回的置信度；`MISSED`（AI 未建议）场景没有意义，为 `null`。 */
    val confidence: Float?,
    val createdAtMillis: Long,
    /**
     * AI 推断该标签所依据的关键证据帧（关联图）的本地相对路径（如 `covers/evidence/xxx.jpg`）。
     * 仅在有对应证据帧时记录，空/null 表示无关联图或已失效。
     */
    val evidenceImagePath: String? = null,
)
