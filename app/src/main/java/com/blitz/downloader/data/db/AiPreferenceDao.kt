package com.blitz.downloader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiPreferenceDao {
    @Query("""
        SELECT v.awemeId FROM downloaded_videos v
        WHERE (:after IS NULL OR v.awemeId > :after)
          AND NOT EXISTS (SELECT 1 FROM ai_reference_exclusion e WHERE e.awemeId = v.awemeId)
          AND (EXISTS (SELECT 1 FROM ai_preference_case c WHERE c.awemeId = v.awemeId)
               OR EXISTS (SELECT 1 FROM video_tag_feedback f WHERE f.awemeId = v.awemeId))
        ORDER BY v.awemeId LIMIT :limit
    """)
    suspend fun casePage(after: String?, limit: Int): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM ai_reference_exclusion WHERE awemeId = :id)")
    suspend fun isExcluded(id: String): Boolean

    @Query("SELECT * FROM ai_preference_case WHERE awemeId = :id")
    suspend fun findCase(id: String): AiPreferenceCaseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCase(row: AiPreferenceCaseEntity)

    @Query("SELECT * FROM video_tag_feedback WHERE awemeId = :id AND analysisId = (SELECT MAX(analysisId) FROM video_tag_feedback WHERE awemeId = :id) ORDER BY tagId, id")
    suspend fun latestFeedback(id: String): List<VideoTagFeedbackEntity>

    @Query("SELECT * FROM video_visual_feature WHERE analysisId = :id ORDER BY id DESC LIMIT 1")
    suspend fun visual(id: Long): VideoVisualFeatureEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRun(run: AiPreferenceRunEntity)

    @Query("SELECT * FROM ai_preference_learning_run WHERE runId = :id")
    suspend fun run(id: String): AiPreferenceRunEntity?

    @Query("SELECT * FROM ai_preference_learning_run ORDER BY createdAtMillis DESC")
    fun observeRuns(): Flow<List<AiPreferenceRunEntity>>

    @Query("""
        UPDATE ai_preference_learning_run SET status = 'RUNNING', lastError = NULL, updatedAtMillis = :now
        WHERE runId = :id AND status IN ('PLANNED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
    """)
    suspend fun beginRun(id: String, now: Long): Int

    @Query("""
        UPDATE ai_preference_learning_run SET status = 'INTERRUPTED', lastError = '学习服务已中断，完成的批次已保留'
        WHERE status IN ('RUNNING', 'MERGING')
    """)
    suspend fun interruptRuns()

    @Query("UPDATE ai_preference_learning_node SET status = 'UNCERTAIN' WHERE status = 'RUNNING'")
    suspend fun interruptNodes()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveNode(node: AiPreferenceNodeEntity)

    @Query("SELECT * FROM ai_preference_learning_node WHERE runId = :id ORDER BY nodeId")
    suspend fun nodes(id: String): List<AiPreferenceNodeEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM ai_preference_learning_node WHERE runId = :id AND status = 'UNCERTAIN')")
    suspend fun hasUncertainNode(id: String): Boolean

    @Query("SELECT COUNT(*) FROM ai_preference_learning_node WHERE runId = :id")
    suspend fun nodeCount(id: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProfile(profile: AiPreferenceRuleProfileEntity): Long

    @Query("SELECT * FROM ai_preference_rule_profile WHERE version = :version")
    suspend fun profile(version: Long): AiPreferenceRuleProfileEntity?

    @Query("SELECT * FROM ai_preference_rule_profile WHERE status = 'ACTIVE' ORDER BY version DESC LIMIT 1")
    suspend fun active(): AiPreferenceRuleProfileEntity?

    @Query("SELECT * FROM ai_preference_rule_profile WHERE activatedAtMillis IS NOT NULL ORDER BY activatedAtMillis DESC, version DESC LIMIT 1")
    suspend fun lastActivated(): AiPreferenceRuleProfileEntity?

    @Query("SELECT * FROM ai_preference_rule_profile WHERE runId IS NOT NULL ORDER BY version DESC")
    fun observeProfiles(): Flow<List<AiPreferenceRuleProfileEntity>>

    @Query("SELECT * FROM ai_preference_rule_profile WHERE status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')")
    suspend fun usableProfiles(): List<AiPreferenceRuleProfileEntity>

    @Query("SELECT * FROM ai_preference_learning_run WHERE status IN ('PLANNED', 'RUNNING', 'MERGING', 'FAILED', 'INTERRUPTED', 'CANCELLED')")
    suspend fun resumableRuns(): List<AiPreferenceRunEntity>

    @Query("UPDATE ai_preference_rule_profile SET status = 'ARCHIVED' WHERE status = 'ACTIVE'")
    suspend fun archiveActive()

    @Query("SELECT rulesJson FROM ai_preference_rule_profile WHERE status = 'LEGACY' AND NOT EXISTS (SELECT 1 FROM ai_preference_rule_profile WHERE activatedAtMillis IS NOT NULL) LIMIT 1")
    suspend fun legacySummary(): String?
}
