package com.blitz.downloader.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.data.DownloadedVideoRepository
import com.blitz.downloader.util.DownloadedMediaFileManager
import com.blitz.downloader.util.OrphanMediaFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface CleanOrphanUiState {
    data object Scanning : CleanOrphanUiState
    data object Empty : CleanOrphanUiState
    data class Ready(
        val files: List<OrphanMediaFile>,
        val selectedPaths: Set<String>,
        val isDeleting: Boolean = false,
    ) : CleanOrphanUiState
}

sealed interface CleanOrphanEvent {
    data class DeleteDone(val count: Int, val freedBytes: Long) : CleanOrphanEvent
}

class CleanOrphanedFilesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: DownloadedVideoRepository
        get() = (getApplication<Application>() as BlitzApp).downloadedVideoRepository

    private val _uiState = MutableStateFlow<CleanOrphanUiState>(CleanOrphanUiState.Scanning)
    val uiState: StateFlow<CleanOrphanUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CleanOrphanEvent>(replay = 0, extraBufferCapacity = 8)
    val events: SharedFlow<CleanOrphanEvent> = _events.asSharedFlow()

    init {
        scan()
    }

    fun scan() {
        _uiState.value = CleanOrphanUiState.Scanning
        viewModelScope.launch {
            val orphans = withContext(Dispatchers.IO) {
                val entities = repo.getAll()
                DownloadedMediaFileManager.findOrphanMediaFiles(entities)
            }
            if (orphans.isEmpty()) {
                _uiState.value = CleanOrphanUiState.Empty
            } else {
                // 默认全选
                val allPaths = orphans.map { it.file.absolutePath }.toSet()
                _uiState.value = CleanOrphanUiState.Ready(
                    files = orphans,
                    selectedPaths = allPaths,
                )
            }
        }
    }

    fun toggleSelection(filePath: String) {
        val current = _uiState.value as? CleanOrphanUiState.Ready ?: return
        val nextSelected = current.selectedPaths.toMutableSet()
        if (nextSelected.contains(filePath)) {
            nextSelected.remove(filePath)
        } else {
            nextSelected.add(filePath)
        }
        _uiState.value = current.copy(selectedPaths = nextSelected)
    }

    fun selectAll(select: Boolean) {
        val current = _uiState.value as? CleanOrphanUiState.Ready ?: return
        val next = if (select) current.files.map { it.file.absolutePath }.toSet() else emptySet()
        _uiState.value = current.copy(selectedPaths = next)
    }

    fun deleteSelected() {
        val current = _uiState.value as? CleanOrphanUiState.Ready ?: return
        if (current.isDeleting || current.selectedPaths.isEmpty()) return

        _uiState.value = current.copy(isDeleting = true)
        viewModelScope.launch {
            val (deletedCount, freedBytes) = withContext(Dispatchers.IO) {
                val toDelete = current.files.filter { it.file.absolutePath in current.selectedPaths }
                val freed = toDelete.sumOf { it.sizeBytes }
                val count = DownloadedMediaFileManager.deleteMediaFiles(
                    getApplication(),
                    toDelete.map { it.file },
                )
                count to freed
            }
            _events.tryEmit(CleanOrphanEvent.DeleteDone(deletedCount, freedBytes))
        }
    }
}
