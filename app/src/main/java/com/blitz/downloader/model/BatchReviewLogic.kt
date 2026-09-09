package com.blitz.downloader.model

import com.blitz.downloader.data.AiTagFeedbackKind
import com.blitz.downloader.data.db.AiTagSuggestionPendingEntity
import com.blitz.downloader.data.db.DownloadedVideoEntity
import com.blitz.downloader.viewmodel.TagEditFilter
import com.blitz.downloader.viewmodel.TagReviewGroup

/**
 * 批量标签整理业务逻辑封装，供 [com.blitz.downloader.viewmodel.BatchTagReviewViewModel] 与单元测试共用。
 */
object BatchReviewLogic {

    /**
     * 合并最近批次与上一批次未打标视频：
     * - 最近批次：全部保留
     * - 上一批次：仅保留 tagEditCount == 0 的未打标视频，且排除最近批次已包含的 awemeId
     */
    fun mergeBatchVideos(
        latestVideos: List<DownloadedVideoEntity>,
        prevVideos: List<DownloadedVideoEntity>,
    ): List<DownloadedVideoEntity> {
        val latestIds = latestVideos.map { it.awemeId }.toSet()
        val prevUnlabeled = prevVideos.filter { it.tagEditCount == 0 && it.awemeId !in latestIds }
        return (latestVideos + prevUnlabeled).distinctBy { it.awemeId }
    }

    /**
     * 按修改次数筛选视频
     */
    fun filterVideosByEditCount(
        videos: List<DownloadedVideoEntity>,
        filter: TagEditFilter,
    ): List<DownloadedVideoEntity> = when (filter) {
        TagEditFilter.ALL -> videos
        TagEditFilter.UNEDITED -> videos.filter { it.tagEditCount == 0 }
        TagEditFilter.EDITED -> videos.filter { it.tagEditCount > 0 }
    }

    /**
     * 过滤待参与 LLM 分析的视频列表，排除用户手动移出的条目。
     */
    fun filterVideosForAnalysis(
        videos: List<DownloadedVideoEntity>,
        excludedIds: Set<String>,
    ): List<DownloadedVideoEntity> {
        if (excludedIds.isEmpty()) return videos
        return videos.filter { it.awemeId !in excludedIds }
    }

    /**
     * 按建议标签构建分组：
     * 排序规则：未处理子标签（无父标签的普通标签）> 未处理父标签 > 已处理标签；
     * 同优先级内按组内视频数降序排列（数量相同按标签名升序）。
     * 支持差集扣减模式（方案一）：未处理的标签组动态扣除已持有该标签的视频（如已被子标签级联打标的视频）。
     * 防御性去重：确保单组内同一条视频至多出现一次，避免模型重复建议或管道脏数据引起视图膨胀。
     */
    fun buildTagGroups(
        pendingRows: List<AiTagSuggestionPendingEntity>,
        videos: List<DownloadedVideoEntity>,
        processedGroupNames: Set<String>,
        groupSelections: Map<String, Set<String>>,
        existingTagsByVideo: Map<String, Set<String>> = emptyMap(),
        manuallyUndoneGroupNames: Set<String> = emptySet(),
        parentTagNames: Set<String> = emptySet(),
    ): List<TagReviewGroup> {
        val videoMap = videos.associateBy { it.awemeId }
        val tagToVideos = mutableMapOf<String, MutableList<DownloadedVideoEntity>>()
        val tagToVideoIds = mutableMapOf<String, MutableSet<String>>()

        for (row in pendingRows) {
            val video = videoMap[row.awemeId] ?: continue
            val tags = row.suggestedTags.split('|').filter { it.isNotBlank() }.distinct()
            for (tag in tags) {
                val seen = tagToVideoIds.getOrPut(tag) { mutableSetOf() }
                if (seen.add(video.awemeId)) {
                    tagToVideos.getOrPut(tag) { mutableListOf() }.add(video)
                }
            }
        }

        return tagToVideos.map { (tagName, vList) ->
            val isProcessed = tagName in processedGroupNames
            // 差集模式：未处理的标签组扣除已持有该标签的视频（例如已被子标签级联打标的视频）
            // 若为用户手动长按撤销的组，展示全量以便用户审查纠偏
            val displayVideos = if (!isProcessed && tagName !in manuallyUndoneGroupNames) {
                val remaining = vList.filter { video ->
                    val tags = existingTagsByVideo[video.awemeId] ?: emptySet()
                    tagName !in tags
                }
                if (remaining.isNotEmpty()) remaining else vList
            } else {
                vList
            }

            val currentVideoIds = displayVideos.map { it.awemeId }.toSet()
            val selected = (groupSelections[tagName] ?: currentVideoIds).intersect(currentVideoIds)
            val taggedIds = if (isProcessed) {
                displayVideos.filter { video ->
                    val tags = existingTagsByVideo[video.awemeId] ?: emptySet()
                    tagName in tags
                }.map { it.awemeId }.toSet()
            } else {
                emptySet()
            }

            TagReviewGroup(
                tagName = tagName,
                videos = displayVideos,
                selectedAwemeIds = selected,
                isProcessed = isProcessed,
                taggedAwemeIds = taggedIds,
            )
        }.sortedWith(
            compareBy<TagReviewGroup> { group ->
                when {
                    !group.isProcessed && group.tagName !in parentTagNames -> 0
                    !group.isProcessed && group.tagName in parentTagNames -> 1
                    group.isProcessed && group.tagName !in parentTagNames -> 2
                    else -> 3
                }
            }
                .thenByDescending { it.videos.size }
                .thenBy { it.tagName },
        )
    }

    /**
     * 切换分组内视频的选中状态（若已勾选则取消，未勾选则添加）
     */
    fun toggleSelection(
        currentSelections: Set<String>,
        awemeId: String,
    ): Set<String> {
        return if (currentSelections.contains(awemeId)) {
            currentSelections - awemeId
        } else {
            currentSelections + awemeId
        }
    }

    /**
     * 反选：在当前分组全部视频范围内，切换选中集合
     */
    fun invertSelection(
        allVideoIds: Set<String>,
        currentSelections: Set<String>,
    ): Set<String> {
        return allVideoIds - currentSelections
    }

    /**
     * 计算应自动标记为已处理的标签集合：
     * 若某建议标签分组内的所有视频已全部持有该标签（例如从收藏夹下载时已自动打上同名标签），
     * 且用户在当前会话中未手动撤销过该标签，则直接视为已处理，无需用户重复确认。
     */
    fun findAutoProcessableTags(
        pendingRows: List<AiTagSuggestionPendingEntity>,
        videos: List<DownloadedVideoEntity>,
        existingTagsByVideo: Map<String, Set<String>>,
        manuallyUndoneTags: Set<String>,
    ): Set<String> {
        val videoMap = videos.associateBy { it.awemeId }
        val tagToVideoIds = mutableMapOf<String, MutableSet<String>>()

        for (row in pendingRows) {
            val video = videoMap[row.awemeId] ?: continue
            val tags = row.suggestedTags.split('|').filter { it.isNotBlank() }.distinct()
            for (tag in tags) {
                tagToVideoIds.getOrPut(tag) { mutableSetOf() }.add(video.awemeId)
            }
        }

        val autoTags = mutableSetOf<String>()
        for ((tagName, vIds) in tagToVideoIds) {
            if (tagName in manuallyUndoneTags) continue
            if (vIds.isNotEmpty() && vIds.all { awemeId ->
                val tags = existingTagsByVideo[awemeId] ?: emptySet()
                tagName in tags
            }) {
                autoTags.add(tagName)
            }
        }
        return autoTags
    }

    /**
     * 判定某条视频涉及的所有分组是否均已被处理（确认或跳过）
     */
    fun isAllGroupsProcessedForVideo(
        suggestedTags: String,
        processedGroupNames: Set<String>,
    ): Boolean {
        val tags = suggestedTags.split('|').filter { it.isNotBlank() }.toSet()
        if (tags.isEmpty()) return true
        return tags.all { it in processedGroupNames }
    }

    /**
     * 分类反馈：对齐 AiTagSuggestionRepository 规则
     */
    fun classifyFeedback(
        suggestedTags: Set<String>,
        confirmedTags: Set<String>,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (tag in suggestedTags) {
            result[tag] = if (tag in confirmedTags) {
                AiTagFeedbackKind.ACCEPTED
            } else {
                AiTagFeedbackKind.REJECTED
            }
        }
        for (tag in confirmedTags - suggestedTags) {
            result[tag] = AiTagFeedbackKind.MISSED
        }
        return result
    }

    /**
     * 判定当前批次视频的标签整理是否已全部完成：
     * 1. 存在分组时，所有分组均已被处理（确认或跳过）；
     * 2. 或列表不为空且所有视频均已打标（tagEditCount > 0）。
     */
    fun isReviewCompleted(
        groups: List<TagReviewGroup>,
        allVideos: List<DownloadedVideoEntity>,
    ): Boolean {
        if (groups.isNotEmpty()) {
            return groups.all { it.isProcessed }
        }
        return allVideos.isNotEmpty() && allVideos.all { it.tagEditCount > 0 }
    }
}
