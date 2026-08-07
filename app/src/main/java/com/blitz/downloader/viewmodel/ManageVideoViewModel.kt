package com.blitz.downloader.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
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

    // -----------------------------------------------------------------------
    // 标签编辑
    // -----------------------------------------------------------------------

    /** 点击单条记录的标签行：取全部可用标签后发事件，由 Fragment 弹窗。 */
    fun requestTagEditor(awemeId: String, currentTags: List<String>) {
        viewModelScope.launch {
            val allTags = withContext(Dispatchers.IO) { tagRepo.getAvailableTags() }
            if (allTags.isEmpty()) {
                emit(ManageTabEvent.NoTagsAvailable)
                return@launch
            }
            emit(ManageTabEvent.ShowTagEditor(awemeId, allTags, currentTags.toSet()))
        }
    }

    /** 多选后「设置标签」：取全部可用标签后发事件，由 Fragment 弹窗。 */
    fun requestBatchTagPicker(awemeIds: List<String>) {
        if (awemeIds.isEmpty()) return
        viewModelScope.launch {
            val allTags = withContext(Dispatchers.IO) { tagRepo.getAvailableTags() }
            if (allTags.isEmpty()) {
                emit(ManageTabEvent.NoTagsAvailable)
                return@launch
            }
            emit(ManageTabEvent.ShowBatchTagPicker(awemeIds, allTags))
        }
    }

    /**
     * 单条记录的标签整体覆盖写库。
     *
     * 走**用户编辑入口** `setTagsAsUserEdit`：它会在标签集合确有变化时给
     * `downloaded_videos.tagEditCount` 累加，不能换成 `setTags`，否则「改过几次」会漏计。
     */
    fun applyTagsToVideo(awemeId: String, tags: List<String>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { tagRepo.setTagsAsUserEdit(awemeId, tags) }
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
