package com.blitz.downloader.viewmodel

import android.app.Application
import androidx.room.withTransaction
import com.blitz.downloader.data.db.BatchAnalysisSessionEntity
import com.blitz.downloader.data.db.BatchAnalysisItemEntity
import com.blitz.downloader.model.BatchReviewLogic
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.data.db.AiTagSuggestionPendingEntity
import com.blitz.downloader.data.db.DownloadedVideoEntity
import com.blitz.downloader.service.AiBatchAnalysisEvents
import com.blitz.downloader.service.AiBatchAnalysisService
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
    val sessionVideos: List<DownloadedVideoEntity> = emptyList(),
    val analysisItems: List<BatchAnalysisItemEntity> = emptyList(),
    val busyAction: String? = null,
    val latestBatchCount: Int = 0,
    val prevBatchUnlabeledCount: Int = 0,
    val editFilter: TagEditFilter = TagEditFilter.ALL,
    val groups: List<TagReviewGroup> = emptyList(),
    val isAnalyzing: Boolean = false,
    val analysisProgress: AiBatchProgress = AiBatchProgress(),
    val excludedAwemeIds: Set<String> = emptySet(),
    val isReviewCompleted: Boolean = false,
    val conflictingVideoIds: Set<String> = emptySet(),
    val allTags: List<String> = emptyList(),
    val parentMap: Map<String, String> = emptyMap(),
    val videoExistingTags: Map<String, Set<String>> = emptyMap(),
    val videoSuggestedTags: Map<String, Set<String>> = emptyMap(),
    val logs: List<com.blitz.downloader.llm.AiAnalysisLogEntry> = emptyList(),
    val isLogSheetVisible: Boolean = false,
) {
    val canValidateTags: Boolean
        get() = isReviewCompleted && sessionVideos.isNotEmpty() && !isLoading && !isAnalyzing && busyAction == null
}

sealed interface BatchTagReviewEvent {
    data class ValidationCompleted(val conflictCount: Int) : BatchTagReviewEvent
    data object ValidationOutdated : BatchTagReviewEvent
    data object ShowAiDisabledHint : BatchTagReviewEvent
    data class ShowToast(val message: String) : BatchTagReviewEvent
    data class ActionDone(val action: String, val count: Int = 0) : BatchTagReviewEvent
    data class ActionFailed(val reason: String) : BatchTagReviewEvent
}

class BatchTagReviewViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (getApplication<Application>() as BlitzApp).database
    private val batchDao = db.downloadBatchDao()
    private val sessionDao = db.batchAnalysisDao()
    private val stateMutex = Mutex()
    private val gson = Gson()
    private var loadedLatestBatchId: Long? = null
    private var loadedSourceBatchIds: String = ""
    private var currentSession: BatchAnalysisSessionEntity? = null
    private val knownSuccessfulIds = mutableSetOf<String>()

    private data class ReviewState(
        val processed: Set<String> = emptySet(),
        val applied: Map<String, Map<String, Set<String>>> = emptyMap(),
        val automatic: Set<String> = emptySet(),
        val undone: Set<String> = emptySet(),
        val selections: Map<String, Set<String>> = emptyMap(),
        val knownIds: Set<String> = emptySet(),
    )

    private fun reviewState() = ReviewState(processedGroupNames.toSet(), appliedCascadedTagsByGroup.toMap(),
        autoProcessedGroupNames.toSet(), manuallyUndoneGroupNames.toSet(), groupSelections.mapValues { it.value.toSet() }, knownSuccessfulIds.toSet())

    private fun restoreReview(state: ReviewState) {
        knownSuccessfulIds.clear()
        knownSuccessfulIds.addAll(state.knownIds)
        processedGroupNames.clear()
        processedGroupNames.addAll(state.processed)
        appliedCascadedTagsByGroup.clear()
        appliedCascadedTagsByGroup.putAll(state.applied)
        autoProcessedGroupNames.clear()
        autoProcessedGroupNames.addAll(state.automatic)
        manuallyUndoneGroupNames.clear()
        manuallyUndoneGroupNames.addAll(state.undone)
        groupSelections.clear()
        state.selections.forEach { (tag, ids) -> groupSelections[tag] = ids.toMutableSet() }
    }

    private suspend fun persistReview() {
        val session = currentSession ?: return
        val json = gson.toJson(reviewState())
        if (session.reviewJson != json) {
            sessionDao.saveReview(session.id, json)
            currentSession = session.copy(reviewJson = json)
        }
    }

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
            combine(sessionDao.observeSession(), sessionDao.observeItems(), batchDao.observeRecentBatches()) { _, _, _ -> Unit }
                .collect { reloadPendingGroups() }
        }
        viewModelScope.launch {
            com.blitz.downloader.llm.AiAnalysisLogStore.logs.collect { logList ->
                _uiState.value = _uiState.value.copy(logs = logList)
            }
        }
    }

    fun loadData() {
        viewModelScope.launch {
            stateMutex.withLock {
                _uiState.value = _uiState.value.copy(isLoading = true)
                try {
                    reloadLocked()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _events.emit(BatchTagReviewEvent.ActionFailed(e.message.orEmpty()))
                } finally {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            }
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
        if (_uiState.value.isAnalyzing || _uiState.value.busyAction != null) {
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
        if (_uiState.value.isAnalyzing || _uiState.value.busyAction != null) {
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
            stateMutex.withLock {
                try { reloadLocked() } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _events.emit(BatchTagReviewEvent.ActionFailed(e.message.orEmpty()))
                }
            }
        }
    }

    /** All state mutations and DB refreshes are serialized, including service updates. */
    private suspend fun reloadLocked() {
        val batches = withContext(Dispatchers.IO) { batchDao.getRecentBatches(2) }
        val latestBatchId = batches.firstOrNull()?.id
        loadedSourceBatchIds = batches.joinToString("|") { it.id.toString() }
        if (latestBatchId != loadedLatestBatchId) {
            loadedLatestBatchId = latestBatchId
            _uiState.value = _uiState.value.copy(excludedAwemeIds = emptySet(), editFilter = TagEditFilter.ALL, conflictingVideoIds = emptySet())
        }
        val batchVideos = withContext(Dispatchers.IO) {
            batches.map { batch ->
                val ids = batch.awemeIds.split('|').filter { it.isNotBlank() }.distinct()
                val found = videoRepo.getByAwemeIds(ids).associateBy { it.awemeId }
                ids.mapNotNull(found::get)
            }
        }
        val latest = batchVideos.getOrElse(0) { emptyList() }
        val candidates = BatchReviewLogic.mergeBatchVideos(latest, batchVideos.getOrElse(1) { emptyList() })
        val storedSession = withContext(Dispatchers.IO) { sessionDao.latestSession() }
        var session = storedSession?.takeIf {
            BatchReviewLogic.matchesLatestBatch(it.sourceBatchIds, latestBatchId)
        }
        if (storedSession == null) {
            // Upgrade compatibility: only known successful pending rows, never fabricated failures.
            val videos = batchVideos.flatten().distinctBy { it.awemeId }.filter { it.mediaType == "video" }
            val rows = withContext(Dispatchers.IO) { pendingDao.getByAwemeIds(videos.map { it.awemeId }) }
            if (rows.isNotEmpty()) {
                val legacy = BatchAnalysisSessionEntity(java.util.UUID.randomUUID().toString(),
                    System.currentTimeMillis(), batches.joinToString("|") { it.id.toString() }, finished = true, isLegacy = true)
                val videoMap = videos.associateBy { it.awemeId }
                db.withTransaction {
                    sessionDao.saveSession(legacy)
                    sessionDao.saveItems(rows.mapIndexed { index, row ->
                        BatchAnalysisItemEntity(legacy.id, row.awemeId, index, gson.toJson(videoMap.getValue(row.awemeId)),
                            BatchAnalysisItemEntity.SUCCEEDED, analysisId = row.analysisId, suggestedTags = row.suggestedTags)
                    })
                }
                session = legacy
            }
        }
        if (session != null && !session.finished && !AiBatchAnalysisService.isSessionActive(session.id)) {
            val interrupted = session
            db.withTransaction {
                sessionDao.failUnfinished(interrupted.id, "分析已中断，请重新分析或人工打标签")
                sessionDao.finish(interrupted.id)
            }
            session = interrupted.copy(finished = true)
        }
        if (session?.id != currentSession?.id) {
            _uiState.value = _uiState.value.copy(conflictingVideoIds = emptySet())
            restoreReview(session?.let { gson.fromJson(it.reviewJson, ReviewState::class.java) } ?: ReviewState())
        }
        currentSession = session
        val items = withContext(Dispatchers.IO) { session?.let { sessionDao.items(it.id) }.orEmpty() }
        val currentVideos = withContext(Dispatchers.IO) { videoRepo.getByAwemeIds(items.map { it.awemeId }).associateBy { it.awemeId } }
        val sessionVideos = items.map { currentVideos[it.awemeId] ?: gson.fromJson(it.videoJson, DownloadedVideoEntity::class.java) }
        sessionPendingRows.clear()
        items.filter { it.status == BatchAnalysisItemEntity.SUCCEEDED && it.analysisId != null }.forEach {
            sessionPendingRows[it.awemeId] = AiTagSuggestionPendingEntity(it.awemeId, it.analysisId!!,
                it.reviewTags ?: it.suggestedTags, session?.createdAtMillis ?: 0L)
        }
        val allTags = withContext(Dispatchers.IO) { tagRepo.getAvailableTags() }
        val parentMap = withContext(Dispatchers.IO) { tagRepo.getParentMap() }
        val existing = withContext(Dispatchers.IO) {
            videoTagDao.getTagsForVideos(sessionVideos.map { it.awemeId })
                .groupBy({ it.awemeId }, { it.tagName }).mapValues { it.value.toSet() }
        }
        // Results can arrive while an earlier group is already confirmed/skipped.
        val incoming = items.filter { it.status == BatchAnalysisItemEntity.SUCCEEDED && it.awemeId !in knownSuccessfulIds }
        if (incoming.isNotEmpty()) {
            for (item in incoming) {
                for (tag in item.suggestedTags.split('|').filter(String::isNotBlank)) {
                    processedGroupNames.remove(tag)
                    autoProcessedGroupNames.remove(tag)
                    groupSelections[tag]?.add(item.awemeId)
                }
                knownSuccessfulIds.add(item.awemeId)
            }
            persistReview()
        }
        val autoTags = BatchReviewLogic.findAutoProcessableTags(sessionPendingRows.values.toList(),
            sessionVideos, existing, manuallyUndoneGroupNames)
        val newlyAuto = autoTags - processedGroupNames
        if (newlyAuto.isNotEmpty()) {
            val before = reviewState()
            try {
                db.withTransaction {
                    processedGroupNames.addAll(newlyAuto)
                    autoProcessedGroupNames.addAll(newlyAuto)
                    checkAndRecordFeedbacks()
                    persistReview()
                }
            } catch (e: Exception) { restoreReview(before); throw e }
        }
        val allGroups = BatchReviewLogic.buildTagGroups(sessionPendingRows.values.toList(), sessionVideos,
            processedGroupNames, groupSelections, existing, manuallyUndoneGroupNames, parentMap.values.toSet())
        val visibleIds = BatchReviewLogic.filterVideosByEditCount(sessionVideos, _uiState.value.editFilter).map { it.awemeId }.toSet()
        val groups = allGroups.mapNotNull { group ->
            val visible = group.videos.filter { it.awemeId in visibleIds }
            if (visible.isEmpty()) null else group.copy(videos = visible, selectedAwemeIds = group.selectedAwemeIds.intersect(visibleIds))
        }
        _uiState.value = _uiState.value.copy(
            hasBatches = batches.isNotEmpty(), allVideos = candidates, sessionVideos = sessionVideos,
            analysisItems = items, groups = groups, allTags = allTags, parentMap = parentMap,
            latestBatchCount = latest.count(BatchReviewLogic::isEligibleForAnalysis),
            prevBatchUnlabeledCount = candidates.count { v -> latest.none { it.awemeId == v.awemeId } },
            excludedAwemeIds = _uiState.value.excludedAwemeIds.intersect(candidates.map { it.awemeId }.toSet()),
            videoExistingTags = existing,
            videoSuggestedTags = items.associate { it.awemeId to it.suggestedTags.split('|').filter(String::isNotBlank).toSet() },
            isReviewCompleted = session?.isLegacy != true && BatchReviewLogic.isSessionReviewCompleted(items, session?.finished == true, allGroups),
        )
    }

    /** 只读校验；与审核/保存共用互斥锁和 busy 状态，不产生标签写入或 AI 反馈。 */
    fun validateTags() {
        if (!_uiState.value.canValidateTags) return
        val expectedSession = currentSession ?: return
        _uiState.value = _uiState.value.copy(busyAction = "validate")
        viewModelScope.launch {
            stateMutex.withLock {
                try {
                    val conflictCount = db.withTransaction {
                        val session = sessionDao.latestSession()
                        val latestBatchId = batchDao.getRecentBatches(1).firstOrNull()?.id
                        val items = sessionDao.items(expectedSession.id)
                        // 排队期间可能有新下载、迟到的分析结果或其他页面改动审核状态。
                        if (session == null || session != expectedSession || !session.finished || session.isLegacy ||
                            !BatchReviewLogic.matchesLatestBatch(session.sourceBatchIds, latestBatchId) ||
                            items != _uiState.value.analysisItems || items.isEmpty() || items.any { !it.isTerminal }
                        ) return@withTransaction null

                        val tags = tagRepo.getAvailableTagEntities()
                        val actualTags = videoTagDao.getTagsForVideos(items.map { it.awemeId }.distinct())
                            .groupBy({ it.awemeId }, { it.tagName }).mapValues { it.value.toSet() }
                        val conflicts = BatchReviewLogic.findConflictingVideos(tags, actualTags)
                        // 事务内发布，避免读取完成到结果生效之间夹入标签或规则写入。
                        withContext(Dispatchers.Main.immediate) {
                            _uiState.value = _uiState.value.copy(
                                conflictingVideoIds = conflicts,
                                videoExistingTags = actualTags,
                                allTags = tags.map { it.tagName },
                                parentMap = tags.filter { it.parentTagName.isNotBlank() }
                                    .associate { it.tagName to it.parentTagName },
                            )
                        }
                        conflicts.size
                    }
                    if (conflictCount == null) {
                        reloadLocked()
                        _events.emit(BatchTagReviewEvent.ValidationOutdated)
                    } else {
                        _events.emit(BatchTagReviewEvent.ValidationCompleted(conflictCount))
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _events.emit(BatchTagReviewEvent.ActionFailed(e.message.orEmpty()))
                } finally {
                    _uiState.value = _uiState.value.copy(busyAction = null)
                }
            }
        }
    }

    /** Preference summarization can call the network; never hold the review transaction for it. */
    private fun refreshPreferenceProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            try { aiRepo.maybeRefreshPreferenceProfile() } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.w("BatchTagReview", "Preference summary refresh failed", e)
            }
        }
    }

    /** Prevent duplicate clicks before launching; roll back memory together with the DB transaction. */
    private fun edit(action: String, count: () -> Int = { 0 }, block: suspend () -> Unit) {
        if (_uiState.value.busyAction != null) return
        _uiState.value = _uiState.value.copy(busyAction = action)
        viewModelScope.launch {
            stateMutex.withLock {
                val before = reviewState()
                val oldRows = sessionPendingRows.toMap()
                val oldSession = currentSession
                try {
                    db.withTransaction { block(); persistReview() }
                    // Once committed, a refresh failure must not roll back the in-memory decision.
                    try { reloadLocked() } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        _events.emit(BatchTagReviewEvent.ActionFailed(e.message.orEmpty()))
                    }
                    _events.emit(BatchTagReviewEvent.ActionDone(action, count()))
                    if (action == "confirm" || action == "skip") refreshPreferenceProfile()
                } catch (e: Exception) {
                    restoreReview(before)
                    currentSession = oldSession
                    sessionPendingRows.clear(); sessionPendingRows.putAll(oldRows)
                    if (e is CancellationException) throw e
                    _events.emit(BatchTagReviewEvent.ActionFailed(e.message.orEmpty()))
                } finally {
                    _uiState.value = _uiState.value.copy(busyAction = null)
                }
            }
        }
    }

    /** 组内取消/恢复单条视频勾选 */
    fun toggleVideoSelection(tagName: String, awemeId: String) {
        if (_uiState.value.busyAction != null) return
        val group = _uiState.value.groups.find { it.tagName == tagName && !it.isProcessed } ?: return
        val currentSelected = groupSelections[tagName] ?: group.selectedAwemeIds
        val updatedSelected = com.blitz.downloader.model.BatchReviewLogic.toggleSelection(currentSelected, awemeId)
        edit("selection", { updatedSelected.size }) {
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
    }

    /** 组内反选视频 */
    fun invertGroupSelection(tagName: String) {
        if (_uiState.value.busyAction != null) return
        val group = _uiState.value.groups.find { it.tagName == tagName && !it.isProcessed } ?: return
        val allIds = group.videos.map { it.awemeId }.toSet()
        val currentSelected = groupSelections[tagName] ?: group.selectedAwemeIds
        val updatedSelected = com.blitz.downloader.model.BatchReviewLogic.invertSelection(allIds, currentSelected)
        edit("selection", { updatedSelected.size }) {
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
    }

    /** 确认某组：写入组内当前选中的视频标签（含级联父标签），并从取消勾选的视频中剔除该标签（纠偏） */
    fun confirmGroup(tagName: String) {
        val group = _uiState.value.groups.find { it.tagName == tagName && !it.isProcessed } ?: return
        var writtenCount = 0
        edit("confirm", { writtenCount }) {
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

                    writtenCount = tagRepo.addTagsAsUserEdit(selectedIds, allTagsToAdd)
                    val previous = appliedCascadedTagsByGroup[tagName].orEmpty()
                    appliedCascadedTagsByGroup[tagName] = (previous.keys + actuallyAdded.keys).associateWith { tag ->
                        previous[tag].orEmpty() + actuallyAdded[tag].orEmpty()
                    }
                }

                // 若用户在撤销后取消了某些视频的勾选（纠偏排除误打标签的视频），将该标签从这些视频中删除
                if (unselectedIds.isNotEmpty()) {
                    videoTagDao.deleteTagFromVideos(unselectedIds, tagName)
                }
                processedGroupNames.add(tagName)
                manuallyUndoneGroupNames.remove(tagName)
                checkAndRecordFeedbacks()
            }

        }
    }

    /** 跳过某组：不写入标签；若为自动标记的已有标签组，跳过则表示纠偏清除该标签 */
    fun skipGroup(tagName: String) {
        if (_uiState.value.groups.none { it.tagName == tagName && !it.isProcessed }) return
        edit("skip") {
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

        }
    }

    /** 撤销已处理（确认或跳过）的标签分组 */
    fun undoGroup(tagName: String) {
        val group = _uiState.value.groups.find { it.tagName == tagName && it.isProcessed } ?: return
        edit("undo") {
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
                    sessionPendingRows.values.filter { it.awemeId in groupVideoIds }.forEach { feedbackDao.deleteByAnalysisId(it.analysisId) }
                    db.tagPreferenceDao().recomputeAll(System.currentTimeMillis())
                    val pendingToRestore = sessionPendingRows.filterKeys { it in groupVideoIds }.values
                    for (pending in pendingToRestore) {
                        pendingDao.upsert(pending)
                    }
                }
            }


        }
    }

    /**
     * 检查是否有视频已完成其涉及的全部建议分组：
     * 若某条视频的所有建议标签分组都已在 [processedGroupNames] 中，
     * 则调用 [AiTagSuggestionRepository.recordFeedback] 写入逐标签反馈，并从 `ai_tag_suggestion_pending` 删除该行。
     */
    private suspend fun checkAndRecordFeedbacks() {
        val pendingRows = pendingDao.getByAwemeIds(sessionPendingRows.keys)
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
                aiRepo.recordFeedback(row.analysisId, confirmedTagIds, refreshProfile = false)
                pendingDao.deleteByAwemeId(row.awemeId)
            }
        }
    }

    /** 点击「开启 LLM 分析」：重新核验，不从显示筛选或历史会话推导成员。 */
    fun startBatchAnalysis() {
        val context = getApplication<Application>()
        if (_uiState.value.isAnalyzing || _uiState.value.busyAction != null) return
        if (!AiBatchAnalysisService.isConfigured(context)) {
            _events.tryEmit(BatchTagReviewEvent.ShowAiDisabledHint)
            return
        }
        _uiState.value = _uiState.value.copy(busyAction = "start")
        viewModelScope.launch {
            stateMutex.withLock {
                try {
                    reloadLocked()
                    val videos = withContext(Dispatchers.IO) {
                        val ids = _uiState.value.allVideos.map { it.awemeId }
                        val fresh = videoRepo.getByAwemeIds(ids).associateBy { it.awemeId }
                        BatchReviewLogic.filterVideosForAnalysis(ids.mapNotNull(fresh::get), _uiState.value.excludedAwemeIds)
                    }
                    if (videos.isEmpty()) {
                        _events.emit(BatchTagReviewEvent.ShowToast("没有待分析的视频"))
                        return@withLock
                    }
                    val session = BatchAnalysisSessionEntity(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(),
                        loadedSourceBatchIds)
                    db.withTransaction {
                        sessionDao.saveSession(session)
                        sessionDao.saveItems(videos.mapIndexed { index, video ->
                            BatchAnalysisItemEntity(session.id, video.awemeId, index, gson.toJson(video))
                        })
                        pendingDao.deleteByAwemeIds(videos.map { it.awemeId })
                    }
                    val started = AiBatchAnalysisService.start(context, videos.map { it.awemeId }, session.id)
                    if (!started) {
                        db.withTransaction {
                            sessionDao.failUnfinished(session.id, "无法启动分析服务，请重试")
                            sessionDao.finish(session.id)
                        }
                        _events.emit(BatchTagReviewEvent.ActionFailed("无法启动分析服务，请重试"))
                    } else {
                        db.withTransaction {
                            sessionDao.deleteOldItems(session.id)
                            sessionDao.deleteOldSessions(session.id)
                        }
                    }
                    reloadLocked()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _events.emit(BatchTagReviewEvent.ActionFailed(e.message.orEmpty()))
                } finally {
                    _uiState.value = _uiState.value.copy(busyAction = null)
                }
            }
        }
    }

    /**
     * 单个视频更改标签：覆盖写入该视频的标签，并使修改次数计数，沉淀 AI 纠偏反馈，并同步更新建议与分组。
     */
    fun saveSingleVideoTags(awemeId: String, newTags: List<String>) {
        edit("save") {
            withContext(Dispatchers.IO) {
                val normalizedTags = newTags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                check(videoRepo.getByAwemeIds(listOf(awemeId)).isNotEmpty()) { "视频记录不存在" }
                val changed = tagRepo.setTagsAsUserEdit(awemeId, normalizedTags)
                if (!changed) return@withContext
                for ((group, tags) in appliedCascadedTagsByGroup.toMap()) {
                    appliedCascadedTagsByGroup[group] = tags.mapValues { (_, ids) -> ids - awemeId }
                }

                val pendingRow = sessionPendingRows[awemeId]
                if (pendingRow != null) {
                    val allTagEntities = tagRepo.getAvailableTagEntities()
                    val tagNameToId = allTagEntities.associate { it.tagName to it.id }
                    val confirmedTagIds = normalizedTags.mapNotNull { tagNameToId[it] }.toSet()
                    aiRepo.recordFeedback(pendingRow.analysisId, confirmedTagIds, refreshProfile = false)
                    pendingDao.deleteByAwemeId(awemeId)

                    // 同步更新内存 sessionPendingRows，使得重算分组时自动反映该视频的最新分类归属
                    sessionPendingRows[awemeId] = pendingRow.copy(suggestedTags = normalizedTags.joinToString("|"))
                    currentSession?.let { sessionDao.setReviewTags(it.id, awemeId, normalizedTags.joinToString("|")) }
                }

                // 清理不在新标签列表中的已选手势
                for ((tagName, set) in groupSelections) {
                    if (tagName !in normalizedTags) {
                        set.remove(awemeId)
                    }
                }
            }

        }
    }

    fun setLogSheetVisible(visible: Boolean) {
        _uiState.value = _uiState.value.copy(isLogSheetVisible = visible)
    }

    fun clearLogs() {
        com.blitz.downloader.llm.AiAnalysisLogStore.clear()
    }

}
