package com.blitz.downloader.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.model.AiReferenceVideo
import com.blitz.downloader.model.groupAiReferenceVideos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AiReferenceManageState(
    val videos: List<AiReferenceVideo> = emptyList(),
    val authorId: String = "",
    val authorName: String = "",
    val loading: Boolean = true,
    val busy: Boolean = false,
    val loadError: String? = null,
)

sealed interface AiReferenceManageEvent {
    data class Saved(val count: Int, val excluded: Boolean) : AiReferenceManageEvent
    data class Failed(val message: String) : AiReferenceManageEvent
}

class AiReferenceManageViewModel(app: Application) : AndroidViewModel(app) {
    private val repository get() = (getApplication<Application>() as BlitzApp).aiTagSuggestionRepository
    private val _state = MutableStateFlow(AiReferenceManageState())
    val state = _state.asStateFlow()
    private val _events = MutableSharedFlow<AiReferenceManageEvent>()
    val events = _events.asSharedFlow()
    private var loadJob: Job? = null

    fun load(sourceAwemeId: String) {
        if (loadJob?.isActive == true) return
        _state.update { it.copy(loading = true, loadError = null) }
        loadJob = viewModelScope.launch {
            try {
                val author = withContext(Dispatchers.IO) {
                    sourceAwemeId.takeIf(String::isNotBlank)?.let { repository.referenceAuthor(it) }
                }
                _state.update { it.copy(authorId = author?.videoAuthorSecUserId.orEmpty(), authorName = author?.userName.orEmpty()) }
                repository.observeReferenceRows().map(::groupAiReferenceVideos).flowOn(Dispatchers.Default)
                    .collect { videos -> _state.update { it.copy(videos = videos, loading = false, loadError = null) } }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update { it.copy(loading = false, loadError = error.message ?: error.javaClass.simpleName) }
            }
        }
    }

    fun setExcluded(awemeIds: Collection<String>, excluded: Boolean) {
        if (_state.value.busy || awemeIds.isEmpty()) return
        val ids = awemeIds.distinct()
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.setReferencesExcluded(ids, excluded) }
                _events.emit(AiReferenceManageEvent.Saved(ids.size, excluded))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _events.emit(AiReferenceManageEvent.Failed(error.message ?: error.javaClass.simpleName))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
