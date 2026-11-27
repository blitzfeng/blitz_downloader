package com.blitz.downloader.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.data.PreferenceOverview
import com.blitz.downloader.data.PreferencePlanPreview
import com.blitz.downloader.data.PreferenceReview
import com.blitz.downloader.data.PreferenceSourceReview
import com.blitz.downloader.model.PreferenceRuleOverride
import com.blitz.downloader.service.PreferenceLearningService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PreferenceLearningViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = (app as BlitzApp).aiPreferenceRepository
    val runs = repository.observeRuns().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val profiles = repository.observeProfiles().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    data class State(
        val overview: PreferenceOverview? = null,
        val preview: PreferencePlanPreview? = null,
        val review: PreferenceReview? = null,
        val sourceDescriptions: Map<String, PreferenceSourceReview> = emptyMap(),
        val configured: Boolean = false,
        val busy: Boolean = false,
        val message: String? = null,
    )

    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()

    init { refresh() }

    private suspend fun readOverview() {
        mutable.value = mutable.value.copy(overview = repository.overview(), configured = repository.serviceConfigured())
    }

    fun refresh() = action { readOverview() }

    fun plan() = action {
        mutable.value = mutable.value.copy(preview = repository.createPlan())
    }

    fun closePlan() { mutable.value = mutable.value.copy(preview = null) }

    fun start(
        id: String,
        retryUncertain: Boolean,
        onRetryConfirmationRequired: (() -> Unit)? = null,
        onStarted: () -> Unit,
    ) = action {
        if (!retryUncertain && repository.hasUncertainNode(id)) {
            onRetryConfirmationRequired?.invoke()
            return@action
        }
        check(repository.serviceConfigured()) { "请返回设置启用 AI 并配置服务" }
        check(PreferenceLearningService.start(getApplication(), id, retryUncertain)) { "另一个 AI 批量任务正在运行，或系统暂不允许启动" }
        closePlan()
        onStarted()
    }

    fun cancel() = PreferenceLearningService.cancelLearning()

    fun review(version: Long) = action {
        val result = repository.review(version)
        mutable.value = mutable.value.copy(review = result, sourceDescriptions = emptyMap())
    }

    fun sources(ids: List<String>) = action {
        val descriptions = repository.sourceDetails(ids)
        mutable.value = mutable.value.copy(sourceDescriptions = mutable.value.sourceDescriptions + descriptions)
    }

    fun edit(version: Long, key: String, edit: PreferenceRuleOverride) = action {
        repository.editRule(version, key, edit)
        mutable.value = mutable.value.copy(review = repository.review(version))
    }

    fun activate(version: Long) = action {
        val success = repository.activate(version)
        mutable.value = mutable.value.copy(review = repository.review(version))
        readOverview()
        check(success) { "请检查待核实规则是否已确认，并确认版本仍兼容当前资料" }
    }

    fun clearMessage() { mutable.value = mutable.value.copy(message = null) }

    private fun action(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = mutable.value.copy(message = error.message ?: "操作失败") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}
