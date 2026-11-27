package com.blitz.downloader.llm

data class AiPreparationTiming(
    val totalMs: Long,
    val stageMs: Map<String, Long>,
    val otherMs: Long,
    val failureStage: String? = null,
)

data class AiRequestPersonalization(
    val ruleProfileVersion: Long?,
    val selectedRuleIds: List<String>,
    val omittedRuleIds: List<String>,
    val currentImageCount: Int,
    val historyImageCount: Int,
    val textExampleCount: Int,
)

internal class AiPreparationTracker(private val clock: () -> Long = System::nanoTime) {
    private val start = clock()
    private var last = start
    private var stage: String? = null
    private val totals = linkedMapOf<String, Long>()
    private var finished: AiPreparationTiming? = null

    @Synchronized
    fun enter(next: String?) {
        if (finished != null) return
        val now = clock()
        stage?.let { totals[it] = (totals[it] ?: 0) + (now - last).coerceAtLeast(0) }
        stage = next
        last = now
    }

    @Synchronized
    fun finish(failed: Boolean = false): AiPreparationTiming {
        finished?.let { return it }
        val failedStage = stage.takeIf { failed }
        enter(null)
        val total = (last - start).coerceAtLeast(0) / 1_000_000
        val parts = totals.mapValues { (_, nanos) -> nanos / 1_000_000 }
        return AiPreparationTiming(total, parts, (total - parts.values.sum()).coerceAtLeast(0), failedStage)
            .also { finished = it }
    }
}
