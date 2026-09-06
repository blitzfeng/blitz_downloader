package com.blitz.downloader.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.blitz.downloader.config.AppSettings
import com.blitz.downloader.data.DownloadMediaType
import com.blitz.downloader.data.db.DownloadedVideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 管理页「视频」Tab 的取数与写库。在 [ManageTabViewModel] 之上多了标签读写、
 * 清除失效、以及「已看过」标记的维护。
 */
class ManageVideoViewModel(app: Application) : ManageTabViewModel(app) {

    override val mediaType: String = DownloadMediaType.VIDEO
    override val checksFileExistence: Boolean = true
    override val loadsUserTags: Boolean = true

    private val _availableTags = MutableStateFlow<List<String>>(emptyList())

    /** 标签过滤栏的可选标签。 */
    val availableTags: StateFlow<List<String>> = _availableTags.asStateFlow()

    fun loadTagFilterBar() {
        viewModelScope.launch {
            _availableTags.value = withContext(Dispatchers.IO) { tagRepo.getAvailableTags() }
        }
    }

    private val _authorHighFreqTags = MutableStateFlow<List<String>>(emptyList())

    /** 当前作者筛选下的高频标签（作者筛选后在列表上方展示的快捷筛选块），未按作者筛选时为空。 */
    val authorHighFreqTags: StateFlow<List<String>> = _authorHighFreqTags.asStateFlow()

    /**
     * 按当前作者筛选（`secUserId`）加载高频标签。空 ID（未按作者筛选，或老记录无稳定 ID）
     * 直接清空——`getHighFrequencyTagsForAuthor` 本身也会对空 ID 短路返回空列表，这里提前判断
     * 只是省一次协程调度。
     */
    fun loadAuthorHighFreqTags(secUserId: String) {
        if (secUserId.isBlank()) {
            _authorHighFreqTags.value = emptyList()
            return
        }
        viewModelScope.launch {
            val threshold = AppSettings.getHighFrequencyTagThreshold(getApplication())
            _authorHighFreqTags.value = withContext(Dispatchers.IO) {
                tagRepo.getHighFrequencyTagsForAuthor(secUserId, threshold)
            }
        }
    }

    // -----------------------------------------------------------------------
    // 标签编辑
    // -----------------------------------------------------------------------

    /**
     * 点击单条记录的标签行：取全部可用标签 + 层级关系后发事件，由 Fragment 弹窗。
     * 顺带从当前已加载条目里取该记录的 `secUserId`/`desc`/封面/文件路径，供弹窗内「AI 建议」
     * 使用——同 [resolveHighFrequencyPreCheckedTags]，从内存反查，不需要额外查库。
     */
    fun requestTagEditor(awemeId: String, currentTags: List<String>) {
        viewModelScope.launch {
            val (allTags, parentMap) = withContext(Dispatchers.IO) {
                tagRepo.getAvailableTags() to tagRepo.getParentMap()
            }
            if (allTags.isEmpty()) {
                emit(ManageTabEvent.NoTagsAvailable)
                return@launch
            }
            val entity = uiState.value.items.firstOrNull { it.entity.awemeId == awemeId }?.entity
            emit(
                ManageTabEvent.ShowTagEditor(
                    awemeId = awemeId,
                    allTags = allTags,
                    currentTags = currentTags.toSet(),
                    parentMap = parentMap,
                    secUserId = entity?.videoAuthorSecUserId.orEmpty(),
                    desc = entity?.desc.orEmpty(),
                    coverPath = entity?.coverPath.orEmpty(),
                    filePath = entity?.filePath.orEmpty(),
                ),
            )
        }
    }

    /** 多选后「设置标签」：取全部可用标签 + 层级关系 + 自动预勾选后发事件，由 Fragment 弹窗。 */
    fun requestBatchTagPicker(awemeIds: List<String>) {
        if (awemeIds.isEmpty()) return
        viewModelScope.launch {
            val (allTags, parentMap) = withContext(Dispatchers.IO) {
                tagRepo.getAvailableTags() to tagRepo.getParentMap()
            }
            if (allTags.isEmpty()) {
                emit(ManageTabEvent.NoTagsAvailable)
                return@launch
            }
            val preChecked = withContext(Dispatchers.IO) { resolveHighFrequencyPreCheckedTags(awemeIds) }
            emit(ManageTabEvent.ShowBatchTagPicker(awemeIds, allTags, preChecked, parentMap))
        }
    }

    /**
     * 按选中记录涉及的每个作者分别查 `author_tag_frequency` 缓存表的高频标签，取并集。
     * 跨作者时可能出现与部分选中视频无关的标签——这是有意为之（减少选择动作优先于精确性）。
     * 空 `videoAuthorSecUserId`（老记录）不参与统计。作者信息直接从当前 Tab 已加载的
     * [uiState] 反查，不需要额外查库——多选操作本身就是对已加载条目做的。
     */
    private suspend fun resolveHighFrequencyPreCheckedTags(awemeIds: List<String>): Set<String> {
        val ids = awemeIds.toSet()
        val authors = uiState.value.items.asSequence()
            .filter { it.entity.awemeId in ids }
            .map { it.entity.videoAuthorSecUserId }
            .filter { it.isNotBlank() }
            .toSet()
        if (authors.isEmpty()) return emptySet()
        val threshold = AppSettings.getHighFrequencyTagThreshold(getApplication())
        return authors.flatMapTo(mutableSetOf()) { tagRepo.getHighFrequencyTagsForAuthor(it, threshold) }
    }

    /**
     * 单条记录的标签整体覆盖写库。
     *
     * 走**用户编辑入口** `setTagsAsUserEdit`：它会在标签集合确有变化时给
     * `downloaded_videos.tagEditCount` 累加，不能换成 `setTags`，否则「改过几次」会漏计。
     *
     * [aiAnalysisId] 非空表示本次编辑用过「AI 建议」
     * （[com.blitz.downloader.dialog.TagEditDialogFragment.RESULT_AI_ANALYSIS_ID]），
     * 写完标签后额外调用 [com.blitz.downloader.data.AiTagSuggestionRepository.recordFeedback]
     * 记录反馈样例；未使用 AI 建议时
     * 传 `null`，不产生任何反馈记录（对应 spec"未使用 AI 建议的手动打标签路径不产生反馈记录"）。
     */
    fun applyTagsToVideo(awemeId: String, tags: List<String>, aiAnalysisId: Long? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                tagRepo.setTagsAsUserEdit(awemeId, tags)
                if (aiAnalysisId != null) {
                    val confirmedTagIds = tagRepo.getAvailableTagEntities()
                        .filter { it.tagName in tags }
                        .mapTo(mutableSetOf()) { it.id }
                    aiTagSuggestionRepo.recordFeedback(aiAnalysisId, confirmedTagIds)
                }
            }
            applyTagsToItem(awemeId, tags)
        }
    }

    /**
     * 多选记录批量**追加**标签。
     *
     * 同样走用户编辑入口 `addTagsAsUserEdit`，理由见 [applyTagsToVideo]。
     */
    fun addTagsToVideos(awemeIds: List<String>, tags: List<String>) {
        if (awemeIds.isEmpty() || tags.isEmpty()) return
        viewModelScope.launch {
            val tagsMap = withContext(Dispatchers.IO) {
                tagRepo.addTagsAsUserEdit(awemeIds, tags)
                tagRepo.getTagsMapForVideos(awemeIds)
            }
            applyTagsToItems(tagsMap)
            emit(ManageTabEvent.TagsApplied(awemeIds.size))
        }
    }

    /**
     * 只给已选记录的 `tagEditCount` +1，不动标签：补 v12 之前手工改过标签、
     * 但库里没留下计数的历史数据。
     *
     * 无条件累加、没有幂等标记，重复执行会重复加——调用方必须先二次确认。
     */
    fun bumpTagEditCount(awemeIds: List<String>) {
        if (awemeIds.isEmpty()) return
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) { tagRepo.bumpTagEditCountManually(awemeIds) }
            emit(ManageTabEvent.TagEditCountBumped(updated))
            // 计数变了但内存里的实体还是旧值；正按修改次数筛选时必须重查，否则筛选结果对不上
            if (filters.tagEditCount.isActive) refresh()
        }
    }

    // -----------------------------------------------------------------------
    // 清除失效
    // -----------------------------------------------------------------------

    fun clearInvalid() {
        viewModelScope.launch {
            val invalidIds = withContext(Dispatchers.IO) {
                repo.getAllByMediaType(mediaType)
                    .filter { it.filePath.isNotBlank() && !resolveFile(it.filePath).exists() }
                    .map { it.awemeId }
            }
            if (invalidIds.isEmpty()) {
                emit(ManageTabEvent.ClearInvalidNone)
                return@launch
            }
            val deleted = withContext(Dispatchers.IO) { repo.deleteByAwemeIds(invalidIds) }
            refresh()
            emit(ManageTabEvent.ClearInvalidDone(deleted))
        }
    }

    // -----------------------------------------------------------------------
    // 播放
    // -----------------------------------------------------------------------

    /**
     * 打开播放页：把当前已加载的可播放条目一并传过去以支持上下滑动切换，
     * 并就地把本条标为「已看过」（写库由播放页负责）。
     */
    fun openVideoPlayer(entity: DownloadedVideoEntity) {
        viewModelScope.launch {
            val playable = uiState.value.items.filter { it.entity.filePath.isNotBlank() }
            val exists = entity.filePath.isNotBlank() &&
                withContext(Dispatchers.IO) { resolveFile(entity.filePath).exists() }
            if (!exists) {
                emit(ManageTabEvent.FileNotFound)
                return@launch
            }
            val position = playable.indexOfFirst { it.entity.awemeId == entity.awemeId }
            emit(
                ManageTabEvent.OpenVideoPlayer(
                    filePaths = playable.map { it.entity.filePath },
                    titles = playable.map { item ->
                        item.entity.desc.trim().ifBlank {
                            item.entity.userName.ifBlank { item.entity.awemeId }
                        }
                    },
                    subtitles = playable.map { it.entity.userName },
                    position = if (position >= 0) position else 0,
                    // 播放页据此把播放到的条目写库标记为「已看过」（含在里面上下滑动切换到的）
                    awemeIds = playable.map { it.entity.awemeId },
                ),
            )
            // 点开这条立刻去掉「未看过」标记，不等回到列表
            markWatched(setOf(entity.awemeId))
        }
    }

    /**
     * 从播放页返回时补齐「已看过」标记。
     *
     * 在播放页里上下滑动看过的条目只写了库、没通知列表，这里按当前已加载的 id 回查一次补上
     * （点开的那条在 [openVideoPlayer] 里已就地标过）。
     *
     * 这是两条刷新路径中的**兜底那条**，由 Fragment 的 `onResume` 触发——
     * ViewModel 不随 onResume 重建，`init` 或 StateFlow 自动收集代替不了它。
     */
    fun refreshWatchedFlags() {
        val ids = uiState.value.items.filterNot { it.entity.watched }.map { it.entity.awemeId }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val watched = withContext(Dispatchers.IO) { repo.getWatchedAwemeIdSet(ids) }
            markWatched(watched)
        }
    }
}
