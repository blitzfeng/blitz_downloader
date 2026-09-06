package com.blitz.downloader.data

/**
 * [com.blitz.downloader.data.db.VideoTagFeedbackEntity.kind] 的合法取值，对应评审文档的
 * Feedback 三分类：AI 建议且保留 / AI 建议但被删除 / AI 未建议但用户手工新增。
 */
object AiTagFeedbackKind {
    /** AI 建议了该标签，用户最终保留。 */
    const val ACCEPTED = "ACCEPTED"

    /** AI 建议了该标签，用户最终删除/未采用。 */
    const val REJECTED = "REJECTED"

    /** AI 未建议该标签，用户手工新增——重要的漏判样本。 */
    const val MISSED = "MISSED"
}
