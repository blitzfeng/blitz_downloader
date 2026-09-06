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
            val (tags, parentMap, descriptionMap) = withContext(Dispatchers.IO) {
                Triple(repo.getAvailableTags(), repo.getParentMap(), repo.getDescriptionMap())
            }
            emit(TagManageEvent.TagsLoaded(tags, parentMap, descriptionMap))
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
            emit(TagManageEvent.TagRenamed(position, oldName, newName))
        }
    }

    /** 删除标签，所有关联该标签的视频同步解除关联。 */
    fun deleteTag(position: Int, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.deleteTag(name) }
            emit(TagManageEvent.TagDeleted(position, name))
        }
    }

    // ── 层级关系（上级标签） ──────────────────────────────────────────────────

    /**
     * 取该标签当前可选的上级候选列表（全部标签 − 自身 − 自身的全部后代，防环）与当前上级，
     * 供 Activity 弹出单选对话框。
     */
    fun requestParentPicker(position: Int, tagName: String) {
        viewModelScope.launch {
            val (allTags, descendants, currentParent) = withContext(Dispatchers.IO) {
                Triple(
                    repo.getAvailableTags(),
                    repo.getDescendants(tagName),
                    repo.getParentMap()[tagName].orEmpty(),
                )
            }
            val candidates = allTags.filter { it != tagName && it !in descendants }
            emit(TagManageEvent.ShowParentPicker(position, tagName, candidates, currentParent))
        }
    }

    /** 设置或清除（[parentTagName] 传空字符串）标签的上级。 */
    fun setParentTag(position: Int, tagName: String, parentTagName: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { repo.setParentTag(tagName, parentTagName) }
            if (ok) emit(TagManageEvent.TagParentSet(position, tagName, parentTagName))
        }
    }

    // ── 标签描述（辅助 ai-tag-suggestions 的 AI 建议） ───────────────────────────

    /** 保存标签描述（[description] 传空字符串即清空）。这是标签名册元数据编辑，不产生 `tagEditCount`。 */
    fun setTagDescription(tagName: String, description: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.setTagDescription(tagName, description) }
            emit(TagManageEvent.TagDescriptionSet(tagName, description))
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
    data class TagsLoaded(
        val tags: List<String>,
        val parentMap: Map<String, String>,
        val descriptionMap: Map<String, String>,
    ) : TagManageEvent
    data class TagCreated(val name: String) : TagManageEvent
    data class TagRenamed(val position: Int, val oldName: String, val newName: String) : TagManageEvent
    data class TagDeleted(val position: Int, val name: String) : TagManageEvent
    data class TagAlreadyExists(val name: String) : TagManageEvent

    /** 上级标签选择器数据就绪：[candidates] 已排除自身与后代，[currentParent] 空表示当前无上级。 */
    data class ShowParentPicker(
        val position: Int,
        val tagName: String,
        val candidates: List<String>,
        val currentParent: String,
    ) : TagManageEvent

    /** 上级设置成功；[parentTagName] 空表示清除为顶层标签。 */
    data class TagParentSet(val position: Int, val tagName: String, val parentTagName: String) : TagManageEvent

    /** 描述保存成功；[description] 空表示清空。 */
    data class TagDescriptionSet(val tagName: String, val description: String) : TagManageEvent
}
