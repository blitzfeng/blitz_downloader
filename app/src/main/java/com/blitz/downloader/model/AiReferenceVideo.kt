package com.blitz.downloader.model

import com.blitz.downloader.data.db.AiReferenceRow

/** 一张视频卡片包含该视频的全部历史反馈，移除时不会遗漏同视频的其他标签案例。 */
data class AiReferenceVideo(
    val awemeId: String,
    val authorName: String,
    val secUserId: String,
    val desc: String,
    val acceptedTags: List<String>,
    val rejectedTags: List<String>,
    val missedTags: List<String>,
    val evidencePaths: List<String>,
    val feedbackCount: Int,
    val latestFeedbackAt: Long,
    val excluded: Boolean,
)

fun groupAiReferenceVideos(rows: List<AiReferenceRow>): List<AiReferenceVideo> =
    rows.groupBy { it.awemeId }.map { (id, feedback) ->
        val latest = feedback.maxBy { it.createdAtMillis }
        fun tags(kind: String) = feedback.filter { it.kind == kind }.map { it.tagName }.distinct()
        AiReferenceVideo(
            awemeId = id, authorName = latest.authorName, secUserId = latest.secUserId, desc = latest.desc,
            acceptedTags = tags("ACCEPTED"), rejectedTags = tags("REJECTED"), missedTags = tags("MISSED"),
            evidencePaths = feedback.mapNotNull { it.evidenceImagePath?.takeIf(String::isNotBlank) }.distinct(),
            feedbackCount = feedback.size, latestFeedbackAt = latest.createdAtMillis, excluded = latest.excluded,
        )
    }.sortedByDescending { it.latestFeedbackAt }

fun filterAiReferenceVideos(
    videos: List<AiReferenceVideo>,
    query: String,
    authorId: String?,
    excluded: Boolean,
): List<AiReferenceVideo> {
    val search = query.trim()
    return videos.filter {
        it.excluded == excluded && (authorId.isNullOrBlank() || it.secUserId == authorId) &&
            (search.isEmpty() || it.desc.contains(search, ignoreCase = true) ||
                it.authorName.contains(search, ignoreCase = true) || it.awemeId.contains(search))
    }
}
