package com.blitz.downloader.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "media_recovery_session",
    primaryKeys = ["sessionId"],
    indices = [Index(value = ["state"])],
)
data class RecoverySession(
    val sessionId: String,
    val generation: Long,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val state: String = "SCANNING",
    val stage: String = "SCAN",
    val policyVersion: Int = 1,
    val authScopeHash: String = "",
    val fileCount: Int = 0,
    val retryAfter: Long = 0,
    val errorCode: String = "",
)

@Entity(
    tableName = "media_recovery_file",
    primaryKeys = ["sessionId", "logicalPath"],
    foreignKeys = [
        ForeignKey(entity = RecoverySession::class, parentColumns = ["sessionId"],
            childColumns = ["sessionId"]),
    ],
    indices = [Index(value = ["sessionId", "awemeId"]), Index(value = ["sessionId", "groupKey"])],
)
data class RecoveryFile(
    val sessionId: String,
    val logicalPath: String,
    val locator: String,
    val kind: String,
    val groupKey: String,
    val size: Long,
    val modified: Long,
    val quickHash: String,
    val awemeId: String?,
    val ordinal: Int?,
    val referenced: Boolean,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val fullSha256: String? = null,
)

@Entity(
    tableName = "media_recovery_work",
    primaryKeys = ["sessionId", "awemeId"],
    foreignKeys = [
        ForeignKey(entity = RecoverySession::class, parentColumns = ["sessionId"],
            childColumns = ["sessionId"]),
    ],
    indices = [Index(value = ["sessionId", "state"])],
)
data class RecoveryWork(
    val sessionId: String,
    val awemeId: String,
    val state: String = "PENDING",
    val detailJson: String? = null,
    val detailHash: String = "",
    val authScopeHash: String = "",
    val fetchedAt: Long = 0,
    val attempts: Int = 0,
    val httpCode: Int = 0,
    val elapsedMs: Long = 0,
)

@Entity(
    tableName = "media_recovery_candidate",
    primaryKeys = ["sessionId", "awemeId", "groupKey"],
    foreignKeys = [
        ForeignKey(entity = RecoveryWork::class, parentColumns = ["sessionId", "awemeId"],
            childColumns = ["sessionId", "awemeId"]),
    ],
    indices = [Index(value = ["sessionId", "state"])],
)
data class RecoveryCandidate(
    val sessionId: String,
    val awemeId: String,
    val groupKey: String,
    val inputsJson: String,
    val method: String,
    val reason: String,
    val state: String,
    val revision: Long = 1,
    val fingerprint: String = "",
    val confirmedAt: Long = 0,
)

@Entity(
    tableName = "media_recovery_batch",
    primaryKeys = ["batchId"],
    foreignKeys = [
        ForeignKey(entity = RecoverySession::class, parentColumns = ["sessionId"],
            childColumns = ["sessionId"]),
    ],
    indices = [Index(value = ["sessionId"]), Index(value = ["operationId", "part"], unique = true)],
)
data class RecoveryBatch(
    val batchId: String,
    val operationId: String,
    val sessionId: String,
    val part: Int,
    val manifestJson: String,
    val manifestHash: String,
    val createdAt: Long,
    val state: String = "PLANNED",
    val backupLocator: String = "",
    val backupSha256: String = "",
    val backupSize: Long = 0,
    val verifiedAt: Long = 0,
    val completedAt: Long = 0,
    val inserted: Int = 0,
    val skipped: Int = 0,
    val errorCode: String = "",
)

@Entity(
    tableName = "media_recovery_batch_item",
    primaryKeys = ["batchId", "awemeId"],
    foreignKeys = [
        ForeignKey(entity = RecoveryBatch::class, parentColumns = ["batchId"],
            childColumns = ["batchId"]),
    ],
    indices = [Index(value = ["batchId", "state"])],
)
data class RecoveryBatchItem(
    val batchId: String,
    val awemeId: String,
    val groupKey: String,
    val fingerprint: String,
    val revision: Long,
    val payloadJson: String,
    val payloadHash: String,
    val state: String = "PENDING",
    val rowId: Long? = null,
)

@Entity(
    tableName = "media_recovery_record",
    primaryKeys = ["awemeId"],
    foreignKeys = [
        ForeignKey(entity = RecoveryBatch::class, parentColumns = ["batchId"],
            childColumns = ["batchId"]),
        ForeignKey(entity = DownloadedVideoEntity::class, parentColumns = ["id"],
            childColumns = ["videoRowId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["videoRowId"], unique = true), Index(value = ["batchId"])],
)
data class RecoveryRecord(
    val awemeId: String,
    val videoRowId: Long,
    val batchId: String,
    val importedAt: Long,
    val inputsJson: String,
    val unknownHistory: String = "downloadTime,watched,exports,tagEdits",
)
