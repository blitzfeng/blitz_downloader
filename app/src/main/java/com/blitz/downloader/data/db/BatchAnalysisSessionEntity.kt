package com.blitz.downloader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "batch_analysis_session")
data class BatchAnalysisSessionEntity(
    @PrimaryKey val id: String,
    val createdAtMillis: Long,
    val sourceBatchIds: String,
    val finished: Boolean = false,
    val reviewJson: String = "{}",
    val isLegacy: Boolean = false,
)

@Entity(tableName = "batch_analysis_item", primaryKeys = ["sessionId", "awemeId"])
data class BatchAnalysisItemEntity(
    val sessionId: String,
    val awemeId: String,
    val position: Int,
    val videoJson: String,
    val status: String = WAITING,
    val error: String = "",
    val analysisId: Long? = null,
    val suggestedTags: String = "",
    val reviewTags: String? = null,
) {
    val isTerminal: Boolean get() = status == SUCCEEDED || status == FAILED

    companion object {
        const val WAITING = "waiting"
        const val RUNNING = "running"
        const val SUCCEEDED = "succeeded"
        const val FAILED = "failed"
    }
}
