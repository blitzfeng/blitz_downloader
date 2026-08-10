package com.blitz.downloader.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blitz.downloader.BlitzApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 标签管理页的数据库读写：加载、新建、重命名、删除、顺序持久化。
 *
 * 列表的**顺序**仍由 [com.blitz.downloader.adapter.TagManageAdapter] 在内存中维护——
 * 拖拽需要 `notifyItemMoved` 才能有跟手的动画，绕一圈 StateFlow 反而会卡。
 * Activity 在退出时把 Adapter 的最终顺序交给 [persistOrder] 落库。
 */
class TagManageViewModel(app: Application) : AndroidViewModel(app) {

    private val repo get() = (getApplication<Application>() as BlitzApp).videoTagRepository

    private val _events = MutableSharedFlow<TagManageEvent>(replay = 0, extraBufferCapacity = 8)
    val events: SharedFlow<TagManageEvent> = _events.asSharedFlow()

    fun loadTags() {
        viewModelScope.launch {
            emit(TagManageEvent.TagsLoaded(withContext(Dispatchers.IO) { repo.getAvailableTags() }))
        }
    }

    fun createTag(name: String) {
        viewModelScope.launch {
            val exists = withContext(Dispatchers.IO) { repo.getAvailableTags().any { it == name } }
            if (exists) {
                emit(TagManageEvent.TagAlreadyExists(name))
                return@launch
            }
            withContext(Dispatchers.IO) { repo.createTag(name) }
            emit(TagManageEvent.TagCreated(name))
        }
    }

    /** 重命名标签，同步到 `tags` 与 `video_tags` 两张表。 */
    fun renameTag(position: Int, oldName: String, newName: String) {
        if (newName == oldName) return
        viewModelScope.launch {
            val exists = withContext(Dispatchers.IO) { repo.getAvailableTags().any { it == newName } }
            if (exists) {
                emit(TagManageEvent.TagAlreadyExists(newName))
                return@launch
            }
            withContext(Dispatchers.IO) { repo.renameTag(oldName, newName) }
            emit(TagManageEvent.TagRenamed(position, newName))
        }
    }

    /** 删除标签，所有关联该标签的视频同步解除关联。 */
    fun deleteTag(position: Int, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.deleteTag(name) }
            emit(TagManageEvent.TagDeleted(position, name))
        }
    }

    /**
     * 持久化标签顺序。
     *
     * 由 Activity 在 `onPause` 时以 Adapter 的当前顺序调用；跑在 [viewModelScope] 里，
     * 不会因为页面已经开始销毁而被取消掉。
     */
    fun persistOrder(orderedTags: List<String>) {
        if (orderedTags.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.reorderTags(orderedTags) }
        }
    }

    private fun emit(event: TagManageEvent) {
        _events.tryEmit(event)
    }
}

sealed interface TagManageEvent {
    data class TagsLoaded(val tags: List<String>) : TagManageEvent
    data class TagCreated(val name: String) : TagManageEvent
    data class TagRenamed(val position: Int, val newName: String) : TagManageEvent
    data class TagDeleted(val position: Int, val name: String) : TagManageEvent
    data class TagAlreadyExists(val name: String) : TagManageEvent
}
