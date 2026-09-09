package com.blitz.downloader.model

import com.blitz.downloader.data.AiTagFeedbackKind
import com.blitz.downloader.data.db.AiTagSuggestionPendingEntity
import com.blitz.downloader.data.db.DownloadedVideoEntity
import com.blitz.downloader.viewmodel.TagEditFilter
import com.blitz.downloader.viewmodel.TagReviewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchReviewLogicTest {

    private fun createFakeVideo(awemeId: String, tagEditCount: Int = 0): DownloadedVideoEntity {
        return DownloadedVideoEntity(
            id = 0,
            awemeId = awemeId,
            downloadType = "post",
            userName = "test_user",
            createdAtMillis = 1000L,
            tagEditCount = tagEditCount,
        )
    }

    @Test
    fun mergeBatchVideos_combinesLatestAll_andPrevUnlabeledOnly() {
        val latestVideos = listOf(
            createFakeVideo("latest_1", tagEditCount = 0),
            createFakeVideo("latest_2", tagEditCount = 3), // 最新批次即使已改过也保留
        )
        val prevVideos = listOf(
            createFakeVideo("prev_unlabeled", tagEditCount = 0), // 上一批次未打标：保留
            createFakeVideo("prev_labeled", tagEditCount = 1),   // 上一批次已打标：排除
            createFakeVideo("latest_1", tagEditCount = 0),       // 重复项：排除
        )

        val merged = BatchReviewLogic.mergeBatchVideos(latestVideos, prevVideos)

        val expectedIds = listOf("latest_1", "latest_2", "prev_unlabeled")
        assertEquals(expectedIds, merged.map { it.awemeId })
    }

    @Test
    fun filterVideosByEditCount_filtersCorrectly() {
        val videos = listOf(
            createFakeVideo("v1", tagEditCount = 0),
            createFakeVideo("v2", tagEditCount = 1),
            createFakeVideo("v3", tagEditCount = 0),
            createFakeVideo("v4", tagEditCount = 5),
        )

        val all = BatchReviewLogic.filterVideosByEditCount(videos, TagEditFilter.ALL)
        assertEquals(listOf("v1", "v2", "v3", "v4"), all.map { it.awemeId })

        val unedited = BatchReviewLogic.filterVideosByEditCount(videos, TagEditFilter.UNEDITED)
        assertEquals(listOf("v1", "v3"), unedited.map { it.awemeId })

        val edited = BatchReviewLogic.filterVideosByEditCount(videos, TagEditFilter.EDITED)
        assertEquals(listOf("v2", "v4"), edited.map { it.awemeId })
    }

    @Test
    fun filterVideosForAnalysis_removesExcludedItems() {
        val videos = listOf(
            createFakeVideo("v1"),
            createFakeVideo("v2"),
            createFakeVideo("v3"),
            createFakeVideo("v4"),
        )

        // 空排除集合：返回全部
        val all = BatchReviewLogic.filterVideosForAnalysis(videos, emptySet())
        assertEquals(listOf("v1", "v2", "v3", "v4"), all.map { it.awemeId })

        // 排除部分视频：只保留未排除项
        val filtered = BatchReviewLogic.filterVideosForAnalysis(videos, setOf("v2", "v4"))
        assertEquals(listOf("v1", "v3"), filtered.map { it.awemeId })
    }

    @Test
    fun buildTagGroups_sortsByVideoCountDescending_andDefaultsToAllSelected() {
        val videos = listOf(
            createFakeVideo("v1"),
            createFakeVideo("v2"),
            createFakeVideo("v3"),
        )
        val pendingRows = listOf(
            AiTagSuggestionPendingEntity("v1", 101, "舞蹈|可爱", 1000L),
            AiTagSuggestionPendingEntity("v2", 102, "舞蹈|美腿", 1000L),
            AiTagSuggestionPendingEntity("v3", 103, "舞蹈", 1000L),
        )

        val groups = BatchReviewLogic.buildTagGroups(
            pendingRows = pendingRows,
            videos = videos,
            processedGroupNames = emptySet(),
            groupSelections = emptyMap(),
        )

        // 舞蹈: 3条; 可爱: 1条; 美腿: 1条
        assertEquals(3, groups.size)
        assertEquals("舞蹈", groups[0].tagName)
        assertEquals(3, groups[0].videos.size)
        assertEquals(setOf("v1", "v2", "v3"), groups[0].selectedAwemeIds)

        // 次大组按名称排序
        assertEquals(1, groups[1].videos.size)
        assertEquals(1, groups[2].videos.size)
    }

    @Test
    fun isAllGroupsProcessedForVideo_tracksCompletion() {
        val suggestedTags = "舞蹈|可爱"

        assertFalse(BatchReviewLogic.isAllGroupsProcessedForVideo(suggestedTags, emptySet()))
        assertFalse(BatchReviewLogic.isAllGroupsProcessedForVideo(suggestedTags, setOf("舞蹈")))
        assertTrue(BatchReviewLogic.isAllGroupsProcessedForVideo(suggestedTags, setOf("舞蹈", "可爱")))
        assertTrue(BatchReviewLogic.isAllGroupsProcessedForVideo(suggestedTags, setOf("舞蹈", "可爱", "美腿")))
    }

    @Test
    fun classifyFeedback_uncheckVersusSkipProduceIdenticalRejectedOutcome() {
        // Task 6.6: 单元测试覆盖"组内取消勾选"与"整组跳过"两种操作对同一标签产生相同的分类结果
        val suggestedTags = setOf("舞蹈")

        // 场景 A：整组跳过 -> 视频没有写入 "舞蹈"
        val confirmedTagsAfterSkip = emptySet<String>()
        val feedbackA = BatchReviewLogic.classifyFeedback(suggestedTags, confirmedTagsAfterSkip)

        // 场景 B：组内取消该视频勾选后确认该组 -> 视频没有写入 "舞蹈"
        val confirmedTagsAfterUncheck = emptySet<String>()
        val feedbackB = BatchReviewLogic.classifyFeedback(suggestedTags, confirmedTagsAfterUncheck)

        // 两者对 "舞蹈" 均产生 REJECTED
        assertEquals(AiTagFeedbackKind.REJECTED, feedbackA["舞蹈"])
        assertEquals(AiTagFeedbackKind.REJECTED, feedbackB["舞蹈"])
        assertEquals(feedbackA, feedbackB)
    }

    @Test
    fun classifyFeedback_confirmedGroupProducesAccepted_andUnionOfMultipleGroups() {
        // Task 6.5 & 7.1: 同一视频命中多组，两组确认后标签为并集，反馈均为 ACCEPTED
        val suggestedTags = setOf("舞蹈", "可爱")

        // 场景 1：两组都确认 -> 持有 "舞蹈" 与 "可爱"
        val confirmedTagsBoth = setOf("舞蹈", "可爱")
        val feedbackBoth = BatchReviewLogic.classifyFeedback(suggestedTags, confirmedTagsBoth)
        assertEquals(AiTagFeedbackKind.ACCEPTED, feedbackBoth["舞蹈"])
        assertEquals(AiTagFeedbackKind.ACCEPTED, feedbackBoth["可爱"])

        // 场景 2：一组确认、一组跳过 -> 持有 "舞蹈"
        val confirmedTagsOne = setOf("舞蹈")
        val feedbackOne = BatchReviewLogic.classifyFeedback(suggestedTags, confirmedTagsOne)
        assertEquals(AiTagFeedbackKind.ACCEPTED, feedbackOne["舞蹈"])
        assertEquals(AiTagFeedbackKind.REJECTED, feedbackOne["可爱"])
    }

    @Test
    fun isReviewCompleted_verifiesBatchCompletionAccurately() {
        val v1 = createFakeVideo("v1", tagEditCount = 0)
        val v2 = createFakeVideo("v2", tagEditCount = 0)
        val v1Labeled = createFakeVideo("v1", tagEditCount = 1)
        val v2Labeled = createFakeVideo("v2", tagEditCount = 1)

        // 场景 1：所有分组已处理 -> 完成
        val allProcessedGroups = listOf(
            TagReviewGroup("舞蹈", listOf(v1), setOf("v1"), isProcessed = true),
            TagReviewGroup("美食", listOf(v2), setOf("v2"), isProcessed = true),
        )
        assertTrue(BatchReviewLogic.isReviewCompleted(allProcessedGroups, listOf(v1, v2)))

        // 场景 2：存在未处理分组 -> 未完成
        val mixedGroups = listOf(
            TagReviewGroup("舞蹈", listOf(v1), setOf("v1"), isProcessed = true),
            TagReviewGroup("美食", listOf(v2), setOf("v2"), isProcessed = false),
        )
        assertFalse(BatchReviewLogic.isReviewCompleted(mixedGroups, listOf(v1, v2)))

        // 场景 3：无分组，但所有视频均已打标（tagEditCount > 0） -> 完成
        assertTrue(BatchReviewLogic.isReviewCompleted(emptyList(), listOf(v1Labeled, v2Labeled)))

        // 场景 4：无分组，存在未打标视频 -> 未完成
        assertFalse(BatchReviewLogic.isReviewCompleted(emptyList(), listOf(v1, v2Labeled)))

        // 场景 5：列表为空 -> 未完成
        assertFalse(BatchReviewLogic.isReviewCompleted(emptyList(), emptyList()))
    }

    @Test
    fun toggleSelection_unselectsTargetAndLeavesOtherItemsSelected() {
        val initialSelections = setOf("v1", "v2", "v3")

        // 点击 v1 取消选中：v1 移出，v2 与 v3 依然保持选中
        val afterUncheckV1 = BatchReviewLogic.toggleSelection(initialSelections, "v1")
        assertEquals(setOf("v2", "v3"), afterUncheckV1)

        // 再次点击 v1 恢复选中：v1 重新加入
        val afterRecheckV1 = BatchReviewLogic.toggleSelection(afterUncheckV1, "v1")
        assertEquals(setOf("v1", "v2", "v3"), afterRecheckV1)

        // 点击 v2 取消选中：v2 移出，v1 与 v3 保持选中
        val afterUncheckV2 = BatchReviewLogic.toggleSelection(afterRecheckV1, "v2")
        assertEquals(setOf("v1", "v3"), afterUncheckV2)
    }

    @Test
    fun buildTagGroups_preservesUserSelectionsAndIntersectsWithCurrentVideos() {
        val videos = listOf(
            createFakeVideo("v1"),
            createFakeVideo("v2"),
            createFakeVideo("v3"),
        )
        val pendingRows = listOf(
            AiTagSuggestionPendingEntity("v1", 101, "舞蹈", 1000L),
            AiTagSuggestionPendingEntity("v2", 102, "舞蹈", 1000L),
            AiTagSuggestionPendingEntity("v3", 103, "舞蹈", 1000L),
        )

        // 用户已在 groupSelections 中取消勾选了 v1，只保留 v2 与 v3
        val userSelections = mapOf("舞蹈" to setOf("v2", "v3"))
        val groups = BatchReviewLogic.buildTagGroups(
            pendingRows = pendingRows,
            videos = videos,
            processedGroupNames = emptySet(),
            groupSelections = userSelections,
        )

        assertEquals(1, groups.size)
        assertEquals(setOf("v2", "v3"), groups[0].selectedAwemeIds)
    }

    @Test
    fun invertSelection_invertsAllAndSubsetCorrectly() {
        val allIds = setOf("v1", "v2", "v3")

        // 全选状态下反选 -> 全不选
        val invertedFromAll = BatchReviewLogic.invertSelection(allIds, setOf("v1", "v2", "v3"))
        assertEquals(emptySet<String>(), invertedFromAll)

        // 全不选状态下反选 -> 全选
        val invertedFromEmpty = BatchReviewLogic.invertSelection(allIds, emptySet())
        assertEquals(setOf("v1", "v2", "v3"), invertedFromEmpty)

        // 仅选中 v1 时反选 -> 选中 v2 与 v3
        val invertedFromV1 = BatchReviewLogic.invertSelection(allIds, setOf("v1"))
        assertEquals(setOf("v2", "v3"), invertedFromV1)

        // 选中 v2 和 v3 时反选 -> 仅选中 v1
        val invertedFromV2V3 = BatchReviewLogic.invertSelection(allIds, setOf("v2", "v3"))
        assertEquals(setOf("v1"), invertedFromV2V3)
    }

    @Test
    fun findAutoProcessableTags_autoProcessesWhenAllVideosHaveTag() {
        val videos = (1..5).map { createFakeVideo("v$it") }
        val pendingRows = (1..5).map {
            AiTagSuggestionPendingEntity("v$it", 100L + it, "颜值", 1000L)
        }
        // 5 条视频从「颜值」收藏夹下载，已全部打上「颜值」标签
        val existingTagsByVideo = (1..5).associate { "v$it" to setOf("颜值") }

        val autoTags = BatchReviewLogic.findAutoProcessableTags(
            pendingRows = pendingRows,
            videos = videos,
            existingTagsByVideo = existingTagsByVideo,
            manuallyUndoneTags = emptySet(),
        )

        assertEquals(setOf("颜值"), autoTags)
    }

    @Test
    fun findAutoProcessableTags_doesNotAutoProcessWhenSomeVideosLackTag() {
        val videos = (1..5).map { createFakeVideo("v$it") }
        val pendingRows = (1..5).map {
            AiTagSuggestionPendingEntity("v$it", 100L + it, "颜值", 1000L)
        }
        // 前 4 条有「颜值」，第 5 条没有
        val existingTagsByVideo = mapOf(
            "v1" to setOf("颜值"),
            "v2" to setOf("颜值"),
            "v3" to setOf("颜值"),
            "v4" to setOf("颜值"),
            "v5" to emptySet<String>(),
        )

        val autoTags = BatchReviewLogic.findAutoProcessableTags(
            pendingRows = pendingRows,
            videos = videos,
            existingTagsByVideo = existingTagsByVideo,
            manuallyUndoneTags = emptySet(),
        )

        // 存在视频尚未持有该标签，需要人工审核确认，不自动标记
        assertTrue(autoTags.isEmpty())
    }

    @Test
    fun findAutoProcessableTags_doesNotAutoProcessWhenManuallyUndone() {
        val videos = (1..5).map { createFakeVideo("v$it") }
        val pendingRows = (1..5).map {
            AiTagSuggestionPendingEntity("v$it", 100L + it, "颜值", 1000L)
        }
        val existingTagsByVideo = (1..5).associate { "v$it" to setOf("颜值") }

        // 用户已长按撤销过该标签分组，用于纠偏
        val autoTags = BatchReviewLogic.findAutoProcessableTags(
            pendingRows = pendingRows,
            videos = videos,
            existingTagsByVideo = existingTagsByVideo,
            manuallyUndoneTags = setOf("颜值"),
        )

        // 处于撤销状态时不被二次自动标记为已处理
        assertTrue(autoTags.isEmpty())
    }

    @Test
    fun findAutoProcessableTags_handlesMultipleTagsIndependently() {
        val videos = listOf(createFakeVideo("v1"), createFakeVideo("v2"))
        val pendingRows = listOf(
            AiTagSuggestionPendingEntity("v1", 101L, "颜值|甜美", 1000L),
            AiTagSuggestionPendingEntity("v2", 102L, "颜值|御姐", 1000L),
        )
        // 两条视频均已有「颜值」，但没有「甜美」与「御姐」
        val existingTagsByVideo = mapOf(
            "v1" to setOf("颜值"),
            "v2" to setOf("颜值"),
        )

        val autoTags = BatchReviewLogic.findAutoProcessableTags(
            pendingRows = pendingRows,
            videos = videos,
            existingTagsByVideo = existingTagsByVideo,
            manuallyUndoneTags = emptySet(),
        )

        // 仅「颜值」被自动标记，其他未持有标签保持待确认
        assertEquals(setOf("颜值"), autoTags)
    }

    @Test
    fun buildTagGroups_differenceMode_deductsVideosAlreadyHoldingTag() {
        // 场景：方案一差集扣减模式
        // 「颜值」建议包含 v1, v2, v3 3条视频
        // 其中 v1 与 v2 已持有「颜值」（如确认子标签「纯欲」时级联写入，或其它途径打上）
        // v3 尚未持有「颜值」
        val videos = listOf(createFakeVideo("v1"), createFakeVideo("v2"), createFakeVideo("v3"))
        val pendingRows = listOf(
            AiTagSuggestionPendingEntity("v1", 101L, "颜值", 1000L),
            AiTagSuggestionPendingEntity("v2", 102L, "颜值", 1000L),
            AiTagSuggestionPendingEntity("v3", 103L, "颜值", 1000L),
        )
        val existingTagsByVideo = mapOf(
            "v1" to setOf("颜值"),
            "v2" to setOf("颜值"),
            "v3" to emptySet<String>(),
        )

        val groups = BatchReviewLogic.buildTagGroups(
            pendingRows = pendingRows,
            videos = videos,
            processedGroupNames = emptySet(),
            groupSelections = emptyMap(),
            existingTagsByVideo = existingTagsByVideo,
            manuallyUndoneGroupNames = emptySet(),
        )

        // 未处理状态下，「颜值」卡片只展示尚未持有该标签的差集视频（仅 v3，共 1 条）
        assertEquals(1, groups.size)
        val yanzhiGroup = groups[0]
        assertEquals("颜值", yanzhiGroup.tagName)
        assertFalse(yanzhiGroup.isProcessed)
        assertEquals(listOf("v3"), yanzhiGroup.videos.map { it.awemeId })
        assertEquals(setOf("v3"), yanzhiGroup.selectedAwemeIds)
    }

    @Test
    fun buildTagGroups_displaysAllVideosWhenManuallyUndoneForCorrection() {
        // 场景：用户手动长按撤销了某分组，需纠偏恢复全量展示
        val videos = listOf(createFakeVideo("v1"), createFakeVideo("v2"), createFakeVideo("v3"))
        val pendingRows = listOf(
            AiTagSuggestionPendingEntity("v1", 101L, "颜值", 1000L),
            AiTagSuggestionPendingEntity("v2", 102L, "颜值", 1000L),
            AiTagSuggestionPendingEntity("v3", 103L, "颜值", 1000L),
        )
        val existingTagsByVideo = mapOf(
            "v1" to setOf("颜值"),
            "v2" to setOf("颜值"),
            "v3" to emptySet<String>(),
        )

        val groups = BatchReviewLogic.buildTagGroups(
            pendingRows = pendingRows,
            videos = videos,
            processedGroupNames = emptySet(),
            groupSelections = emptyMap(),
            existingTagsByVideo = existingTagsByVideo,
            manuallyUndoneGroupNames = setOf("颜值"), // 手动撤销
        )

        // 撤销状态下展示全量 3 条视频供用户调整
        assertEquals(1, groups.size)
        val yanzhiGroup = groups[0]
        assertEquals("颜值", yanzhiGroup.tagName)
        assertEquals(listOf("v1", "v2", "v3"), yanzhiGroup.videos.map { it.awemeId })
        assertEquals(setOf("v1", "v2", "v3"), yanzhiGroup.selectedAwemeIds)
    }

    @Test
    fun buildTagGroups_displaysAllVideosWhenProcessed() {
        // 场景：已处理状态展示该标签全量视频以供查阅
        val videos = listOf(createFakeVideo("v1"), createFakeVideo("v2"), createFakeVideo("v3"))
        val pendingRows = listOf(
            AiTagSuggestionPendingEntity("v1", 101L, "颜值", 1000L),
            AiTagSuggestionPendingEntity("v2", 102L, "颜值", 1000L),
            AiTagSuggestionPendingEntity("v3", 103L, "颜值", 1000L),
        )
        val existingTagsByVideo = mapOf(
            "v1" to setOf("颜值"),
            "v2" to setOf("颜值"),
            "v3" to setOf("颜值"),
        )

        val groups = BatchReviewLogic.buildTagGroups(
            pendingRows = pendingRows,
            videos = videos,
            processedGroupNames = setOf("颜值"),
            groupSelections = emptyMap(),
            existingTagsByVideo = existingTagsByVideo,
            manuallyUndoneGroupNames = emptySet(),
        )

        assertEquals(1, groups.size)
        val yanzhiGroup = groups[0]
        assertTrue(yanzhiGroup.isProcessed)
        assertEquals(listOf("v1", "v2", "v3"), yanzhiGroup.videos.map { it.awemeId })
    }

    @Test
    fun buildTagGroups_deduplicatesDuplicateTagsInRow_andDuplicateVideoOccurrences() {
        // Bug 修复场景：模型对多个关键帧重复返回同一标签（如 "颜值|颜值|颜值"），
        // 或者管道产生重复数据时，确保组内同一条视频至多出现一次
        val video1 = createFakeVideo("v1")
        val video2 = createFakeVideo("v2")
        val pendingRows = listOf(
            // v1 建议标签重复写了 3 次 "颜值" 与 2 次 "可爱"
            AiTagSuggestionPendingEntity("v1", 101L, "颜值|颜值|颜值|可爱|可爱", 1000L),
            // v2 建议标签也包含 "颜值"
            AiTagSuggestionPendingEntity("v2", 102L, "颜值|颜值", 1000L),
        )

        val groups = BatchReviewLogic.buildTagGroups(
            pendingRows = pendingRows,
            videos = listOf(video1, video2),
            processedGroupNames = emptySet(),
            groupSelections = emptyMap(),
        )

        assertEquals(2, groups.size)
        val yanzhiGroup = groups.first { it.tagName == "颜值" }
        // 颜值分组应只包含 v1 和 v2 各一次（共 2 条，而不是 3+2=5 条）
        assertEquals(2, yanzhiGroup.videos.size)
        assertEquals(listOf("v1", "v2"), yanzhiGroup.videos.map { it.awemeId })

        val keaiGroup = groups.first { it.tagName == "可爱" }
        // 可爱分组应只包含 v1 一次（共 1 条，而不是 2 条）
        assertEquals(1, keaiGroup.videos.size)
        assertEquals(listOf("v1"), keaiGroup.videos.map { it.awemeId })
    }

    @Test
    fun mergeBatchVideos_deduplicatesIdenticalAwemeIdInBatch() {
        val latestVideos = listOf(
            createFakeVideo("v1"),
            createFakeVideo("v1"), // 重复条目
            createFakeVideo("v2"),
        )
        val prevVideos = listOf(
            createFakeVideo("v3", tagEditCount = 0),
        )

        val merged = BatchReviewLogic.mergeBatchVideos(latestVideos, prevVideos)
        assertEquals(listOf("v1", "v2", "v3"), merged.map { it.awemeId })
    }

    @Test
    fun buildTagGroups_calculatesTaggedAwemeIds_forProcessedGroup() {
        // 场景：推荐「颜值」标签下共 5 个视频，其中 3 个选中并打标（v1, v2, v3），2 个未打标（v4, v5）
        val videos = (1..5).map { createFakeVideo("v$it") }
        val pendingRows = (1..5).map {
            AiTagSuggestionPendingEntity("v$it", 100L + it, "颜值", 1000L)
        }
        val existingTagsByVideo = mapOf(
            "v1" to setOf("颜值"),
            "v2" to setOf("颜值"),
            "v3" to setOf("颜值"),
            // v4, v5 未被打上「颜值」
        )

        val groups = BatchReviewLogic.buildTagGroups(
            pendingRows = pendingRows,
            videos = videos,
            processedGroupNames = setOf("颜值"), // 已处理
            groupSelections = emptyMap(),
            existingTagsByVideo = existingTagsByVideo,
        )

        assertEquals(1, groups.size)
        val yanzhiGroup = groups[0]
        assertTrue(yanzhiGroup.isProcessed)
        assertEquals(5, yanzhiGroup.videos.size)
        // taggedAwemeIds 精确只包含实际打上「颜值」标签的 3 个视频
        assertEquals(setOf("v1", "v2", "v3"), yanzhiGroup.taggedAwemeIds)
    }

    @Test
    fun buildTagGroups_sortsSubAndNormalTags_beforeParentTags_beforeProcessed() {
        val videos = (1..6).map { createFakeVideo("v$it") }
        val pendingRows = listOf(
            // 父标签「颜值」：包含 5 个视频（数量最多）
            AiTagSuggestionPendingEntity("v1", 101L, "颜值|纯欲", 1000L),
            AiTagSuggestionPendingEntity("v2", 102L, "颜值|可爱", 1000L),
            AiTagSuggestionPendingEntity("v3", 103L, "颜值|纯欲", 1000L),
            AiTagSuggestionPendingEntity("v4", 104L, "颜值", 1000L),
            AiTagSuggestionPendingEntity("v5", 105L, "颜值", 1000L),
            // 普通独立标签「美食」（无父无子）：1 个视频
            AiTagSuggestionPendingEntity("v6", 106L, "美食", 1000L),
        )

        // 父标签集合：仅「颜值」是父标签（旗下有子标签「纯欲」与「可爱」）
        val parentTagNames = setOf("颜值")

        // 场景 1：全部未处理
        // 期望排序：
        // Tier 0（子标签 & 普通标签）：纯欲(2条) > 可爱(1条) == 美食(1条, 字母序在后)
        // Tier 1（父标签）：颜值(5条) —— 虽视频数最多但必须排在子标签与普通标签之后
        val groups = BatchReviewLogic.buildTagGroups(
            pendingRows = pendingRows,
            videos = videos,
            processedGroupNames = emptySet(),
            groupSelections = emptyMap(),
            parentTagNames = parentTagNames,
        )

        assertEquals(4, groups.size)
        assertEquals(listOf("纯欲", "可爱", "美食", "颜值"), groups.map { it.tagName })

        // 场景 2：部分已处理（如「纯欲」已处理）
        // 期望排序：
        // 未处理子标签/普通：可爱 > 美食
        // 未处理父标签：颜值
        // 已处理标签：纯欲（排在最后）
        val groupsWithProcessed = BatchReviewLogic.buildTagGroups(
            pendingRows = pendingRows,
            videos = videos,
            processedGroupNames = setOf("纯欲"),
            groupSelections = emptyMap(),
            parentTagNames = parentTagNames,
        )

        assertEquals(listOf("可爱", "美食", "颜值", "纯欲"), groupsWithProcessed.map { it.tagName })
        assertFalse(groupsWithProcessed[0].isProcessed)
        assertFalse(groupsWithProcessed[1].isProcessed)
        assertFalse(groupsWithProcessed[2].isProcessed)
        assertTrue(groupsWithProcessed[3].isProcessed)
    }
}

