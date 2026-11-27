package com.blitz.downloader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "ai_preference_case")
data class AiPreferenceCaseEntity(
    @PrimaryKey val awemeId: String,
    val revision: Long,
    val analysisId: Long?,
    val judgmentsJson: String,
    val suggestedJson: String,
    val visualJson: String,
    val evidenceJson: String,
    val desc: String,
    val contentHash: String,
    val updatedAtMillis: Long,
)

@Entity(tableName = "ai_preference_learning_run")
data class AiPreferenceRunEntity(
    @PrimaryKey val runId: String,
    val status: String,
    val manifestJson: String,
    val strategyVersion: Int,
    val providerPlanJson: String,
    val plannedNodeCount: Int,
    val completedNodeCount: Int,
    val processedCaseCount: Int,
    val lastError: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "ai_preference_learning_node",
    indices = [Index("runId"), Index(value = ["runId", "inputHash"], unique = true)],
)
data class AiPreferenceNodeEntity(
    @PrimaryKey val nodeId: String,
    val runId: String,
    val stage: String,
    val inputHash: String,
    val inputJson: String,
    val childNodeIdsJson: String,
    val status: String,
    val outputJson: String?,
    val diagnosticsJson: String?,
)

@Entity(tableName = "ai_preference_rule_profile")
data class AiPreferenceRuleProfileEntity(
    @PrimaryKey(autoGenerate = true) val version: Long = 0,
    val runId: String?,
    val status: String,
    val rulesJson: String,
    val manifestJson: String,
    val overridesJson: String,
    val coverageJson: String,
    val createdAtMillis: Long,
    val activatedAtMillis: Long?,
)
