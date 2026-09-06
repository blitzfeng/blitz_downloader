package com.blitz.downloader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一次「AI 建议标签」分析的元数据（`video_ai_analysis` 表），不含图片/大 JSON 字段本体
 * （结构化视觉证据见 [VideoVisualFeatureEntity]），方便单独查询"这个视频分析过几次、用的什么模型"。
 *
 * [suggestedTagIds] 是后续所有反馈写入的唯一数据源：单条编辑弹窗/批量分析的结果契约都只回传
 * 一个 [id]，不是原始建议集合本身，写反馈时必须能凭这个 id 单独查回"当时到底建议了什么"——
 * 批量场景的分组确认可能发生在分析完成后的几分钟到几小时之后，早已脱离任何内存态。
 */
@Entity(tableName = "video_ai_analysis")
data class VideoAiAnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val awemeId: String,
    /** LLM 供应商标识，如 "gemini"。 */
    val provider: String,
    /** 具体模型版本号，供比较模型升级前后的准确率。 */
    val model: String,
    /** [VideoVisualFeatureEntity] 里 VisualFeatureProfile 的 schema 版本。 */
    val profileVersion: Int,
    /**
     * 本次返回的候选标签 id 及其置信度，格式 `"id:confidence"`，`|` 分隔多项，对齐
     * `userRelation` 的既有编码约定（如 `"12:0.88|45:0.62"`）。写反馈时需要按标签区分置信度
     * （[VideoTagFeedbackEntity.confidence]），只存 id 不够——这是实现 `AiTagSuggestionRepository`
     * 时才发现的缺口，字段仍是单个 TEXT 列，不需要额外的表结构变更。
     */
    val suggestedTagIds: String = "",
    val succeeded: Boolean,
    val createdAtMillis: Long,
)
