package com.blitz.downloader.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.data.db.AiTagSuggestionPendingEntity
import com.blitz.downloader.data.db.DownloadedVideoEntity
import com.blitz.downloader.service.AiBatchAnalysisEvents
import com.blitz.downloader.service.AiBatchAnalysisService
import com.blitz.downloader.service.AiBatchEvent
import com.blitz.downloader.service.AiBatchProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TagEditFilter(val label: String) {
    ALL("全部"),
    UNEDITED("未打标 (0次)"),
    EDITED("已打标 (≥1次)"),
}

data class TagReviewGroup(
    val tagName: String,
    val videos: List<DownloadedVideoEntity>,
    val selectedAwemeIds: Set<String>,
    val isProcessed: Boolean = false,
    val taggedAwemeIds: Set<String> = emptySet(),
)

data class BatchTagReviewUiState(
    val isLoading: Boolean = true,
    val hasBatches: Boolean = false,
    val allVideos: List<DownloadedVideoEntity> = emptyList(),
    val latestBatchCount: Int = 0,
    val prevBatchUnlabeledCount: Int = 0,
    val editFilter: TagEditFilter = TagEditFilter.ALL,
    val groups: List<TagReviewGroup> = emptyList(),
    val isAnalyzing: Boolean = false,
    val analysisProgress: AiBatchProgress = AiBatchProgress(),
    val excludedAwemeIds: Set<String> = emptySet(),
    val isReviewCompleted: Boolean = false,
    val allTags: List<String> = emptyList(),
    val parentMap: Map<String, String> = emptyMap(),
    val videoExistingTags: Map<String, Set<String>> = emptyMap(),
    val videoSuggestedTags: Map<String, Set<String>> = emptyMap(),
    val logs: List<com.blitz.downloader.llm.AiAnalysisLogEntry> = emptyList(),
    val isLogSheetVisible: Boolean = false,
)

sealed interface BatchTagReviewEvent {
    data object ShowAiDisabledHint : BatchTagReviewEvent
    data class ShowToast(val message: String) : BatchTagReviewEvent
}

class BatchTagReviewViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (getApplication<Application>() as BlitzApp).database
    private val batchDao = db.downloadBatchDao()
    private val videoRepo = (getApplication<Application>() as BlitzApp).downloadedVideoRepository
    private val tagRepo = (getApplication<Application>() as BlitzApp).videoTagRepository
    private val aiRepo = (getApplication<Application>() as BlitzApp).aiTagSuggestionRepository
    private val pendingDao = db.aiTagSuggestionPendingDao()
    private val videoTagDao = db.videoTagDao()
    private val downloadedVideoDao = db.downloadedVideoDao()
    private val feedbackDao = db.videoTagFeedbackDao()

    private val _uiState = MutableStateFlow(BatchTagReviewUiState())
    val uiState: StateFlow<BatchTagReviewUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<BatchTagReviewEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<BatchTagReviewEvent> = _events.asSharedFlow()

    /** 内存态：已被用户「确认」或「跳过」的分组标签名集合 */
    private val processedGroupNames = mutableSetOf<String>()

    /** 内存态：已被用户「确认」的分组标签名及其成功写入的各标签（含级联父标签）与 awemeId 集合映射，供撤销时精准回滚 */
    private val appliedCascadedTagsByGroup = mutableMapOf<String, Map<String, Set<String>>>()

    /** 内存态：自动标记为已处理的分组标签名集合（源自收藏夹等已有同名标签） */
    private val autoProcessedGroupNames = mutableSetOf<String>()

    /** 内存态：已被用户手动长按撤销的分组标签名集合，防止在 reload 时再次被自动标记为已处理 */
    private val manuallyUndoneGroupNames = mutableSetOf<String>()

    /** 内存态：组内勾选状态，tagName -> 选中的 awemeId 集合 */
    private val groupSelections = mutableMapOf<String, MutableSet<String>>()

    /** 内存态：当前会话内已接收到的建议行，避免在确认写反馈后由 DB 删除导致已确认分组直接消失 */
    private val sessionPendingRows = mutableMapOf<String, AiTagSuggestionPendingEntity>()

    init {
        loadData()
        observeAnalysisEvents()
    }

    private fun observeAnalysisEvents() {
        viewModelScope.launch {
            AiBatchAnalysisEvents.isAnalyzing.collect { analyzing ->
                _uiState.value = _uiState.value.copy(isAnalyzing = analyzing)
            }
        }
        viewModelScope.launch {
            AiBatchAnalysisEvents.progress.collect { progress ->
                _uiState.value = _uiState.value.copy(analysisProgress = progress)
            }
        }
        viewModelScope.launch {
            AiBatchAnalysisEvents.events.collect { event ->
                when (event) {
                    is AiBatchEvent.ItemDone, is AiBatchEvent.BatchFinished -> {
                        reloadPendingGroups()
                    }
                }
            }
        }
        viewModelScope.launch {
            com.blitz.downloader.llm.AiAnalysisLogStore.logs.collect { logList ->
                _uiState.value = _uiState.value.copy(logs = logList)
            }
        }
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val (allVideos, latestCount, prevUnlabeledCount, hasBatches) = withContext(Dispatchers.IO) {
                val batches = batchDao.getRecentBatches(2)
                if (batches.isEmpty()) {
                    return@withContext Quadruple(emptyList<DownloadedVideoEntity>(), 0, 0, false)
                }

                val latestBatch = batches[0]
                val latestIds = latestBatch.awemeIds.split('|').filter { it.isNotBlank() }.distinct()
                val latestVideos = videoRepo.getByAwemeIds(latestIds)

                val prevVideos = if (batches.size > 1) {
                    val prevBatch = batches[1]
                    val prevIds = prevBatch.awemeIds.split('|').filter { it.isNotBlank() }.distinct()
                    videoRepo.getByAwemeIds(prevIds)
                } else {
                    emptyList()
                }

                val combined = com.blitz.downloader.model.BatchReviewLogic.mergeBatchVideos(latestVideos, prevVideos)
                val prevUnlabeledCount = combined.size - latestVideos.size
                Quadruple(combined, latestVideos.size, prevUnlabeledCount, true)
            }

            sessionPendingRows.clear()
            processedGroupNames.clear()
            appliedCascadedTagsByGroup.clear()
            autoProcessedGroupNames.clear()
            manuallyUndoneGroupNames.clear()
            groupSelections.clear()
            val isCompleted = com.blitz.downloader.model.BatchReviewLogic.isReviewCompleted(
                groups = emptyList(),
                allVideos = allVideos,
            )

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                hasBatches = hasBatches,
                allVideos = allVideos,
                latestBatchCount = latestCount,
                prevBatchUnlabeledCount = prevUnlabeledCount,
                isReviewCompleted = isCompleted,
            )

            reloadPendingGroups()
        }
    }

    fun setEditFilter(filter: TagEditFilter) {
        _uiState.value = _uiState.value.copy(editFilter = filter)
        reloadPendingGroups()
    }

    /**
     * 切换视频是否参与 LLM 分析的排除状态
     */
    fun toggleVideoExclusion(awemeId: String) {
        if (_uiState.value.isAnalyzing) {
            _events.tryEmit(BatchTagReviewEvent.ShowToast("LLM 分析进行中，暂时无法修改待分析项"))
            return
        }
        val current = _uiState.value.excludedAwemeIds
        val updated = if (current.contains(awemeId)) {
            current - awemeId
        } else {
            current + awemeId
        }
        _uiState.value = _uiState.value.copy(excludedAwemeIds = updated)
        reloadPendingGroups()
    }

    /**
     * 恢复所有已排除的视频，重新全部参与 LLM 分析
     */
    fun restoreAllExcludedVideos() {
        if (_uiState.value.isAnalyzing) {
            _events.tryEmit(BatchTagReviewEvent.ShowToast("LLM 分析进行中，暂时无法修改待分析项"))
            return
        }
        _uiState.value = _uiState.value.copy(excludedAwemeIds = emptySet())
        reloadPendingGroups()
    }

    /**
     * 标记视频为已观看（写库并更新内存状态）
     */
    fun markVideoWatched(awemeId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                videoRepo.markWatched(listOf(awemeId))
            }
            val updated = _uiState.value.allVideos.map {
                if (it.awemeId == awemeId) it.copy(watched = true) else it
            }
            _uiState.value = _uiState.value.copy(allVideos = updated)
            reloadPendingGroups()
        }
    }

    private fun reloadPendingGroups() {
        viewModelScope.launch {
            val allVideos = _uiState.value.allVideos
            val excluded = _uiState.value.excludedAwemeIds
            val activeVideos = com.blitz.downloader.model.BatchReviewLogic.filterVideosForAnalysis(allVideos, excluded)
            if (activeVideos.isEmpty()) {
                val isCompleted = com.blitz.downloader.model.BatchReviewLogic.isReviewCompleted(
                    groups = emptyList(),
                    allVideos = allVideos,
                )
                val (allTags, parentMap) = withContext(Dispatchers.IO) {
                    Pair(tagRepo.getAvailableTags(), tagRepo.getParentMap())
                }
                _uiState.value = _uiState.value.copy(
                    groups = emptyList(),
                    allTags = allTags,
                    parentMap = parentMap,
                    isReviewCompleted = isCompleted,
                )
                return@launch
            }

            val (groups, allTags, parentMap, existingTagsByVideo, suggestedTagsByVideo) = withContext(Dispatchers.IO) {
                val awemeIds = activeVideos.map { it.awemeId }
                val dbPendingRows = pendingDao.getByAwemeIds(awemeIds)
                for (row in dbPendingRows) {
                    sessionPendingRows[row.awemeId] = row
                }
                val pendingRows = sessionPendingRows.values.filter { it.awemeId in awemeIds }

                val filteredVideos = com.blitz.downloader.model.BatchReviewLogic.filterVideosByEditCount(
                    activeVideos,
                    _uiState.value.editFilter,
                )

                // 自动识别视频已全部持有同名标签的分组并直接标记为已处理（例如收藏夹下载同名标签）
                val existingTagsList = videoTagDao.getTagsForVideos(awemeIds)
                val existingTagsByVideo = existingTagsList
                    .groupBy({ it.awemeId }, { it.tagName })
                    .mapValues { it.value.toSet() }

                val autoTags = com.blitz.downloader.model.BatchReviewLogic.findAutoProcessableTags(
                    pendingRows = pendingRows,
                    videos = activeVideos,
                    existingTagsByVideo = existingTagsByVideo,
                    manuallyUndoneTags = manuallyUndoneGroupNames,
                )
                if (autoTags.isNotEmpty()) {
                    val newlyAutoProcessed = autoTags - processedGroupNames
                    processedGroupNames.addAll(autoTags)
                    autoProcessedGroupNames.addAll(autoTags)
                    if (newlyAutoProcessed.isNotEmpty()) {
                        checkAndRecordFeedbacks()
                    }
                }

                val parentMap = tagRepo.getParentMap()
                val parentTagNames = parentMap.values.filter { it.isNotBlank() }.toSet()
                val allTags = tagRepo.getAvailableTags()
                val suggestedTagsByVideo = pendingRows.associate {
                    it.awemeId to it.suggestedTags.split('|').filter { t -> t.isNotBlank() }.toSet()
                }

                val tagGroups = com.blitz.downloader.model.BatchReviewLogic.buildTagGroups(
                    pendingRows = pendingRows,
                    videos = filteredVideos,
                    processedGroupNames = processedGroupNames,
                    groupSelections = groupSelections,
                    existingTagsByVideo = existingTagsByVideo,
                    manuallyUndoneGroupNames = manuallyUndoneGroupNames,
                    parentTagNames = parentTagNames,
                )

                Quintup(tagGroups, allTags, parentMap, existingTagsByVideo, suggestedTagsByVideo)
            }

            val isCompleted = com.blitz.downloader.model.BatchReviewLogic.isReviewCompleted(
                groups = groups,
                allVideos = allVideos,
            )

            _uiState.value = _uiState.value.copy(
                groups = groups,
                allTags = allTags,
                parentMap = parentMap,
                videoExistingTags = existingTagsByVideo,
                videoSuggestedTags = suggestedTagsByVideo,
                isReviewCompleted = isCompleted,
            )
        }
    }

    /** 组内取消/恢复单条视频勾选 */
    fun toggleVideoSelection(tagName: String, awemeId: String) {
        val group = _uiState.value.groups.find { it.tagName == tagName } ?: return
        val currentSelected = groupSelections[tagName] ?: group.selectedAwemeIds
        val updatedSelected = com.blitz.downloader.model.BatchReviewLogic.toggleSelection(currentSelected, awemeId)
        groupSelections[tagName] = updatedSelected.toMutableSet()
        val updatedGroups = _uiState.value.groups.map { g ->
            if (g.tagName == tagName) {
                g.copy(selectedAwemeIds = updatedSelected)
            } else {
                g
            }
        }
        _uiState.value = _uiState.value.copy(groups = updatedGroups)
    }

    /** 组内反选视频 */
    fun invertGroupSelection(tagName: String) {
        val group = _uiState.value.groups.find { it.tagName == tagName } ?: return
        val allIds = group.videos.map { it.awemeId }.toSet()
        val currentSelected = groupSelections[tagName] ?: group.selectedAwemeIds
        val updatedSelected = com.blitz.downloader.model.BatchReviewLogic.invertSelection(allIds, currentSelected)
        groupSelections[tagName] = updatedSelected.toMutableSet()
        val updatedGroups = _uiState.value.groups.map { g ->
            if (g.tagName == tagName) {
                g.copy(selectedAwemeIds = updatedSelected)
            } else {
                g
            }
        }
        _uiState.value = _uiState.value.copy(groups = updatedGroups)
    }

    /** 确认某组：写入组内当前选中的视频标签（含级联父标签），并从取消勾选的视频中剔除该标签（纠偏） */
    fun confirmGroup(tagName: String) {
        viewModelScope.launch {
            val group = _uiState.value.groups.find { it.tagName == tagName } ?: return@launch
            val selectedIds = group.selectedAwemeIds
            val unselectedIds = group.videos.map { it.awemeId }.toSet() - selectedIds

            withContext(Dispatchers.IO) {
                val parentMap = tagRepo.getParentMap()
                val ancestors = com.blitz.downloader.model.TagHierarchy.ancestorsOf(tagName, parentMap)
                val allTagsToAdd = listOf(tagName) + ancestors

                if (selectedIds.isNotEmpty()) {
                    // 查询打标前已有标签，记录本次确认真正新追加的标签（含级联父标签），供撤销时精准回滚
                    val beforeTags = videoTagDao.getTagsForVideos(selectedIds.toList())
                        .groupBy({ it.awemeId }, { it.tagName })
                        .mapValues { it.value.toSet() }

                    val actuallyAdded = mutableMapOf<String, Set<String>>()
                    for (tag in allTagsToAdd) {
                        val lackingIds = selectedIds.filter { id -> tag !in (beforeTags[id] ?: emptySet()) }.toSet()
                        if (lackingIds.isNotEmpty()) {
                            actuallyAdded[tag] = lackingIds
                        }
                    }

                    tagRepo.addTagsAsUserEdit(selectedIds, allTagsToAdd)
                    appliedCascadedTagsByGroup[tagName] = actuallyAdded
                } else {
                    appliedCascadedTagsByGroup[tagName] = emptyMap()
                }

                // 若用户在撤销后取消了某些视频的勾选（纠偏排除误打标签的视频），将该标签从这些视频中删除
                if (unselectedIds.isNotEmpty()) {
                    videoTagDao.deleteTagFromVideos(unselectedIds, tagName)
                }
                processedGroupNames.add(tagName)
                manuallyUndoneGroupNames.remove(tagName)
                checkAndRecordFeedbacks()
            }

            // 重新拉取视频数据（tagEditCount 与标签可能更新）并刷新分组
            refreshVideosAndGroups()
        }
    }

    /** 跳过某组：不写入标签；若为自动标记的已有标签组，跳过则表示纠偏清除该标签 */
    fun skipGroup(tagName: String) {
        viewModelScope.launch {
            val group = _uiState.value.groups.find { it.tagName == tagName }
            val groupVideoIds = group?.videos?.map { it.awemeId } ?: emptyList()

            withContext(Dispatchers.IO) {
                if (tagName in autoProcessedGroupNames && groupVideoIds.isNotEmpty()) {
                    videoTagDao.deleteTagFromVideos(groupVideoIds, tagName)
                }
                appliedCascadedTagsByGroup[tagName] = emptyMap()
                processedGroupNames.add(tagName)
                manuallyUndoneGroupNames.remove(tagName)
                checkAndRecordFeedbacks()
            }
            refreshVideosAndGroups()
        }
    }

    /** 撤销已处理（确认或跳过）的标签分组 */
    fun undoGroup(tagName: String) {
        viewModelScope.launch {
            val group = _uiState.value.groups.find { it.tagName == tagName } ?: return@launch
            val cascadedAdded = appliedCascadedTagsByGroup.remove(tagName) ?: emptyMap()
            val groupVideoIds = group.videos.map { it.awemeId }.toSet()

            withContext(Dispatchers.IO) {
                // 精准回滚本次确认写入的标签（包含子标签及其级联父标签）
                val affectedVideoIds = mutableSetOf<String>()
                for ((tag, ids) in cascadedAdded) {
                    if (ids.isNotEmpty()) {
                        videoTagDao.deleteTagFromVideos(ids, tag)
                        affectedVideoIds.addAll(ids)
                    }
                    // 如果该父标签此前因级联完全打标而自动进入已处理，连带解除其已处理状态
                    if (tag != tagName && tag in autoProcessedGroupNames) {
                        processedGroupNames.remove(tag)
                        autoProcessedGroupNames.remove(tag)
                    }
                }
                if (affectedVideoIds.isNotEmpty()) {
                    downloadedVideoDao.decrementTagEditCount(affectedVideoIds.toList())
                }
                processedGroupNames.remove(tagName)
                manuallyUndoneGroupNames.add(tagName)

                // 恢复相关视频的 pending 记录与清理已写入的 feedback（避免重复写 feedback）
                if (groupVideoIds.isNotEmpty()) {
                    feedbackDao.deleteByAwemeIds(groupVideoIds)
                    val pendingToRestore = sessionPendingRows.filterKeys { it in groupVideoIds }.values
                    for (pending in pendingToRestore) {
                        pendingDao.upsert(pending)
                    }
                }
            }

            _events.tryEmit(BatchTagReviewEvent.ShowToast("已撤销标签「$tagName」的处理"))
            refreshVideosAndGroups()
        }
    }

    /**
     * 检查是否有视频已完成其涉及的全部建议分组：
     * 若某条视频的所有建议标签分组都已在 [processedGroupNames] 中，
     * 则调用 [AiTagSuggestionRepository.recordFeedback] 写入逐标签反馈，并从 `ai_tag_suggestion_pending` 删除该行。
     */
    private suspend fun checkAndRecordFeedbacks() {
        val allVideos = _uiState.value.allVideos
        val awemeIds = allVideos.map { it.awemeId }
        val pendingRows = pendingDao.getByAwemeIds(awemeIds)
        if (pendingRows.isEmpty()) return

        val allTagEntities = tagRepo.getAvailableTagEntities()
        val tagNameToId = allTagEntities.associate { it.tagName to it.id }

        for (row in pendingRows) {
            val suggestedTags = row.suggestedTags.split('|').filter { it.isNotBlank() }.toSet()
            val allProcessed = suggestedTags.all { it in processedGroupNames }
            if (allProcessed) {
                // 该视频涉及的全部建议分组已处理完
                val actualTags = videoTagDao.getTagsForVideo(row.awemeId)
                val confirmedTagIds = actualTags.mapNotNull { tagNameToId[it] }.toSet()
                aiRepo.recordFeedback(row.analysisId, confirmedTagIds)
                pendingDao.deleteByAwemeId(row.awemeId)
            }
        }
    }

    private suspend fun refreshVideosAndGroups() {
        val allVideos = _uiState.value.allVideos
        val updatedVideos = withContext(Dispatchers.IO) {
            videoRepo.getByAwemeIds(allVideos.map { it.awemeId }).distinctBy { it.awemeId }
        }
        _uiState.value = _uiState.value.copy(allVideos = updatedVideos)
        reloadPendingGroups()
    }

    /** 点击「开启 LLM 分析」 */
    fun startBatchAnalysis() {
        val context = getApplication<Application>()
        if (!AiBatchAnalysisService.isConfigured(context)) {
            _events.tryEmit(BatchTagReviewEvent.ShowAiDisabledHint)
            return
        }
        val activeVideos = com.blitz.downloader.model.BatchReviewLogic.filterVideosForAnalysis(
            _uiState.value.allVideos,
            _uiState.value.excludedAwemeIds,
        )
        val awemeIds = activeVideos.map { it.awemeId }
        if (awemeIds.isEmpty()) {
            _events.tryEmit(BatchTagReviewEvent.ShowToast("没有待分析的视频"))
            return
        }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // 清理本次待分析视频的历史暂存记录，确保重新分析时结果完全纯净，杜绝历史脏数据冲突
                pendingDao.deleteByAwemeIds(awemeIds)
            }
            sessionPendingRows.clear()
            processedGroupNames.clear()
            appliedCascadedTagsByGroup.clear()
            autoProcessedGroupNames.clear()
            manuallyUndoneGroupNames.clear()
            groupSelections.clear()

            _uiState.value = _uiState.value.copy(
                groups = emptyList(),
                isAnalyzing = true,
                isReviewCompleted = false,
            )

            val started = AiBatchAnalysisService.start(context, awemeIds)
            if (!started) {
                _uiState.value = _uiState.value.copy(isAnalyzing = false)
            }
        }
    }

    /**
     * 单个视频更改标签：覆盖写入该视频的标签，并使修改次数计数，沉淀 AI 纠偏反馈，并同步更新建议与分组。
     */
    fun saveSingleVideoTags(awemeId: String, newTags: List<String>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val normalizedTags = newTags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                tagRepo.setTagsAsUserEdit(awemeId, normalizedTags)

                val pendingRow = sessionPendingRows[awemeId] ?: pendingDao.getByAwemeId(awemeId)
                if (pendingRow != null) {
                    val allTagEntities = tagRepo.getAvailableTagEntities()
                    val tagNameToId = allTagEntities.associate { it.tagName to it.id }
                    val confirmedTagIds = normalizedTags.mapNotNull { tagNameToId[it] }.toSet()
                    aiRepo.recordFeedback(pendingRow.analysisId, confirmedTagIds)
                    pendingDao.deleteByAwemeId(awemeId)

                    // 同步更新内存 sessionPendingRows，使得重算分组时自动反映该视频的最新分类归属
                    sessionPendingRows[awemeId] = pendingRow.copy(suggestedTags = normalizedTags.joinToString("|"))
                }

                // 清理不在新标签列表中的已选手势
                for ((tagName, set) in groupSelections) {
                    if (tagName !in normalizedTags) {
                        set.remove(awemeId)
                    }
                }
            }
            val tagText = newTags.joinToString("、").ifBlank { "已清空标签" }
            _events.tryEmit(BatchTagReviewEvent.ShowToast("已更新标签：$tagText"))
            refreshVideosAndGroups()
        }
    }

    fun setLogSheetVisible(visible: Boolean) {
        _uiState.value = _uiState.value.copy(isLogSheetVisible = visible)
    }

    fun clearLogs() {
        com.blitz.downloader.llm.AiAnalysisLogStore.clear()
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
    private data class Quintup<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
}
