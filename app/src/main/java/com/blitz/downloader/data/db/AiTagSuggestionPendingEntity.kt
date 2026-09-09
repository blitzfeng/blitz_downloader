package com.blitz.downloader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 待处理 AI 标签建议暂存表（`ai_tag_suggestion_pending` 表）。
 *
 * 存放批量 LLM 分析完成但用户尚未在分组确认界面处理完的建议结果。
 * [awemeId] 为主键（单视频同一时刻至多一条待处理建议），
 * [analysisId] 关联 [VideoAiAnalysisEntity.id]，是后续写入反馈记录的必需凭证；
 * [suggestedTags] 存储候选标签名（以 `|` 分隔），供分组 UI 直接聚合展示，避免每次界面渲染反查 analysisId。
 */
@Entity(tableName = "ai_tag_suggestion_pending")
data class AiTagSuggestionPendingEntity(
    @PrimaryKey val awemeId: String,
    val analysisId: Long,
    val suggestedTags: String,
    val generatedAtMillis: Long,
)
