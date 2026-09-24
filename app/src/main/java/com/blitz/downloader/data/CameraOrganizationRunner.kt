package com.blitz.downloader.data

import com.blitz.downloader.model.CameraMoveOutcome
import com.blitz.downloader.model.CameraMoveResult
import com.blitz.downloader.model.CameraOrganizationPhase
import com.blitz.downloader.model.CameraOrganizationState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 不依赖界面或 Android 的批次状态机，确认必须匹配当前预览身份。 */
class CameraOrganizationRunner(
    private val scope: CoroutineScope,
    private val createStorage: () -> CameraVideoStorage,
    private val journal: CameraTemporaryJournal,
    private val leftovers: () -> List<String>,
    private val refreshMedia: (String, String, Boolean) -> Unit,
) {
    private val mutableState = MutableStateFlow(CameraOrganizationState())
    val state = mutableState.asStateFlow()
    private var storage: CameraVideoStorage? = null

    @Synchronized
    fun scan() {
        if (state.value.busy || state.value.phase == CameraOrganizationPhase.PREVIEW) return
        val id = UUID.randomUUID().toString()
        mutableState.value = CameraOrganizationState(batchId = id, phase = CameraOrganizationPhase.SCANNING)
        scope.launch {
            try {
                val backend = createStorage()
                val candidates = backend.scan()
                storage = backend
                mutableState.value = CameraOrganizationState(id, CameraOrganizationPhase.PREVIEW, candidates, leftovers = leftovers())
            } catch (e: Exception) {
                mutableState.value = CameraOrganizationState(id, CameraOrganizationPhase.FINISHED, message = e.message ?: "扫描失败", leftovers = leftovers())
            }
        }
    }

    @Synchronized
    fun cancelPreview(id: String) {
        if (state.value.batchId == id && state.value.phase == CameraOrganizationPhase.PREVIEW) {
            storage = null
            mutableState.value = CameraOrganizationState()
        }
    }

    @Synchronized
    fun confirm(id: String) {
        val preview = state.value
        if (preview.batchId != id || preview.phase != CameraOrganizationPhase.PREVIEW || preview.candidates.isEmpty()) return
        val backend = storage ?: return
        mutableState.value = preview.copy(phase = CameraOrganizationPhase.MOVING)
        scope.launch {
            val results = mutableListOf<CameraMoveResult>()
            var message: String? = null
            try {
                val mover = CameraVideoMover(backend, journal)
                for (candidate in preview.candidates) {
                    backend.checkAccess()
                    backend.prepareDestination(candidate)
                    mutableState.value = mutableState.value.copy(currentName = candidate.name)
                    var result = mover.move(candidate)
                    if (result.outcome == CameraMoveOutcome.SUCCESS || result.destination != null) {
                        val warning = runCatching { refreshMedia(candidate.name, result.destination!!, result.outcome == CameraMoveOutcome.SUCCESS) }
                            .exceptionOrNull()?.let { "媒体索引刷新失败：${it.message}" }
                        result = result.copy(indexWarning = warning)
                    }
                    results += result
                    mutableState.value = mutableState.value.copy(results = results.toList(), leftovers = leftovers())
                }
            } catch (e: Exception) {
                message = e.message ?: "整理已停止"
            } finally {
                mutableState.value = mutableState.value.copy(
                    phase = CameraOrganizationPhase.FINISHED, currentName = null,
                    results = results.toList(), message = message, leftovers = leftovers(),
                )
            }
        }
    }
}
