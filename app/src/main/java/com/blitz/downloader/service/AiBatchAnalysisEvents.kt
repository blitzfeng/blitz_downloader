package com.blitz.downloader.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class AiBatchProgress(
    val done: Int = 0,
    val total: Int = 0,
)

sealed interface AiBatchEvent {
    data class ItemDone(val awemeId: String, val succeeded: Boolean) : AiBatchEvent
    data class BatchFinished(val succeededCount: Int, val failedCount: Int) : AiBatchEvent
}

/**
 * 进程内的批量 AI 分析事件总线。
 *
 * [AiBatchAnalysisService] 更新进度与每条分析结果，[BatchTagReviewViewModel] 观察此总线
 * 刷新分析中状态与已生成建议的分组列表。
 */
object AiBatchAnalysisEvents {

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _progress = MutableStateFlow(AiBatchProgress())
    val progress: StateFlow<AiBatchProgress> = _progress.asStateFlow()

    private val _events = MutableSharedFlow<AiBatchEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AiBatchEvent> = _events.asSharedFlow()

    fun setAnalyzing(analyzing: Boolean, total: Int = 0) {
        _isAnalyzing.value = analyzing
        if (analyzing) {
            _progress.value = AiBatchProgress(done = 0, total = total)
        }
    }

    fun updateProgress(done: Int, total: Int) {
        _progress.value = AiBatchProgress(done = done, total = total)
    }

    fun notifyItemDone(awemeId: String, succeeded: Boolean) {
        _events.tryEmit(AiBatchEvent.ItemDone(awemeId, succeeded))
    }

    fun notifyBatchFinished(succeededCount: Int, failedCount: Int) {
        _isAnalyzing.value = false
        _events.tryEmit(AiBatchEvent.BatchFinished(succeededCount, failedCount))
    }
}
