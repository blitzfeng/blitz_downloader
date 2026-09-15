package com.blitz.downloader.llm

/**
 * AI 分析单次调用的执行状态。
 */
enum class AiAnalysisLogStatus {
    RUNNING,
    SUCCESS,
    FAILED,
}

/**
 * 单条视频的大模型接口分析日志条目，包含结构化摘要与脱敏后的原始交互报文。
 */
data class AiAnalysisLogEntry(
    val id: String,
    val awemeId: String,
    val authorName: String = "",
    val videoTitle: String,
    val videoDesc: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val status: AiAnalysisLogStatus = AiAnalysisLogStatus.RUNNING,
    val durationMs: Long = 0L,
    // 请求数据
    val requestPrompt: String = "",
    val fullPrompt: String = "",
    val requestAuthorTagsSummary: String = "",
    val requestEvidenceSamplesSummary: String = "",
    val requestFramesSummary: String = "",
    val requestVocabularySummary: String = "",
    val rawRequestBody: String = "",
    // 响应数据
    val rawResponseBody: String = "",
    val suggestedTags: List<TagCandidate> = emptyList(),
    val visualFeatureProfile: VisualFeatureProfile? = null,
    val tokenUsage: String? = null,
    val errorMessage: String? = null,
)
