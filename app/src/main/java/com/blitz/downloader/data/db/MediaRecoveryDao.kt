package com.blitz.downloader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaRecoveryDao {
    @Insert suspend fun insert(session: RecoverySession)
    @Insert suspend fun insertFiles(files: List<RecoveryFile>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertWorks(works: List<RecoveryWork>)
    @Insert suspend fun insert(candidate: RecoveryCandidate)
    @Insert suspend fun insert(batch: RecoveryBatch)
    @Insert suspend fun insertItems(items: List<RecoveryBatchItem>)
    @Insert suspend fun insert(record: RecoveryRecord)
    @Update suspend fun update(session: RecoverySession)
    @Update suspend fun update(work: RecoveryWork)
    @Update suspend fun update(batch: RecoveryBatch)
    @Update suspend fun update(item: RecoveryBatchItem)

    @Query("SELECT * FROM media_recovery_session ORDER BY generation DESC LIMIT 1")
    fun latest(): Flow<RecoverySession?>
    @Query("SELECT IFNULL(MAX(retryAfter),0) FROM media_recovery_session")
    suspend fun nextQueryTime(): Long
    @Query("SELECT * FROM media_recovery_session ORDER BY generation DESC")
    suspend fun sessions(): List<RecoverySession>
    @Query("SELECT * FROM media_recovery_session WHERE sessionId=:id")
    suspend fun session(id: String): RecoverySession?
    @Query("SELECT * FROM media_recovery_session WHERE state NOT IN ('SCANNING','SCAN_FAILED') AND stage != 'SCAN' ORDER BY generation DESC LIMIT 1")
    suspend fun lastCompleteScan(): RecoverySession?
    @Query("SELECT * FROM media_recovery_file WHERE sessionId=:id ORDER BY logicalPath")
    suspend fun files(id: String): List<RecoveryFile>
    @Query("SELECT logicalPath FROM media_recovery_file WHERE sessionId=:id")
    suspend fun scannedPaths(id: String): List<String>
    @Query("UPDATE media_recovery_file SET fullSha256=:digest WHERE sessionId=:id AND logicalPath=:path")
    suspend fun cacheFullHash(id: String, path: String,digest: String)
    @Query("SELECT * FROM media_recovery_file WHERE sessionId=:id AND logicalPath=:path")
    suspend fun file(id: String, path: String): RecoveryFile?
    @Query("SELECT * FROM media_recovery_work WHERE sessionId=:id ORDER BY awemeId")
    suspend fun works(id: String): List<RecoveryWork>
    @Query("SELECT * FROM media_recovery_work WHERE sessionId=:id AND awemeId=:work")
    suspend fun work(id: String, work: String): RecoveryWork?
    @Query("SELECT * FROM media_recovery_candidate WHERE sessionId=:id AND awemeId=:work ORDER BY groupKey")
    suspend fun candidates(id: String, work: String): List<RecoveryCandidate>
    @Query("SELECT * FROM media_recovery_candidate WHERE sessionId=:id AND state IN (:states) ORDER BY awemeId,groupKey LIMIT 50 OFFSET :offset")
    fun page(id: String, states: List<String>, offset: Int): Flow<List<RecoveryCandidate>>
    @Query("UPDATE media_recovery_candidate SET state=:state,revision=revision+1,fingerprint=:hash,confirmedAt=:at,inputsJson=COALESCE(:inputs,inputsJson) WHERE sessionId=:id AND awemeId=:work AND groupKey=:groupKey AND revision=:revision")
    suspend fun review(id: String, work: String, groupKey: String, revision: Long, state: String, hash: String, at: Long, inputs: String? = null): Int
    @Query("UPDATE media_recovery_session SET state='INTERRUPTED' WHERE state IN ('SCANNING','FETCHING','MATCHING','WRITING')")
    suspend fun interruptSessions()
    @Query("SELECT * FROM media_recovery_batch WHERE state IN ('BACKING_UP','BACKUP_VERIFIED','COMMITTING')")
    suspend fun interruptedWrites(): List<RecoveryBatch>
    @Query("UPDATE media_recovery_batch SET state='INTERRUPTED' WHERE state IN ('PLANNED','BACKING_UP','BACKUP_VERIFIED','COMMITTING')")
    suspend fun interruptBatches()
    @Query("SELECT * FROM media_recovery_batch ORDER BY createdAt DESC,part")
    fun history(): Flow<List<RecoveryBatch>>
    @Query("SELECT * FROM media_recovery_batch_item WHERE batchId=:id ORDER BY awemeId")
    suspend fun items(id: String): List<RecoveryBatchItem>
    @Query("SELECT * FROM media_recovery_work WHERE sessionId=:id AND state NOT IN ('SUCCESS','PENDING','ALREADY_PRESENT') ORDER BY awemeId LIMIT 50 OFFSET :offset")
    fun workErrors(id: String, offset: Int): Flow<List<RecoveryWork>>
    @Query("SELECT COUNT(*) FROM media_recovery_record")
    suspend fun recoveredCount(): Int
    @Query("SELECT COUNT(*) FROM downloaded_videos")
    fun downloadCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM media_recovery_work WHERE sessionId=:id")
    fun workCount(id: String): Flow<Int>
    @Query("SELECT COUNT(*) FROM media_recovery_work WHERE sessionId=:id AND state='SUCCESS'")
    fun successCount(id: String): Flow<Int>
    @Query("SELECT COUNT(*) FROM media_recovery_candidate WHERE sessionId=:id AND state='CONFIRMED'")
    fun confirmedCount(id: String): Flow<Int>
    @Query("SELECT * FROM media_recovery_batch WHERE batchId=:id")
    suspend fun batch(id: String): RecoveryBatch?
    @Query("DELETE FROM media_recovery_candidate WHERE sessionId=:id AND awemeId=:work AND state NOT IN ('APPLIED','ALREADY_PRESENT')")
    suspend fun resetCandidates(id: String, work: String)
    @Query("SELECT * FROM media_recovery_file WHERE sessionId=:id AND kind IN ('VIDEO','IMAGE') AND referenced=0 AND awemeId IS NULL ORDER BY logicalPath LIMIT 50 OFFSET :offset")
    fun unbound(id: String, offset: Int): Flow<List<RecoveryFile>>
    @Query("UPDATE media_recovery_file SET awemeId=:work WHERE sessionId=:id AND logicalPath=:path AND awemeId IS NULL AND referenced=0 AND kind IN ('VIDEO','IMAGE','LIVE')")
    suspend fun bind(id: String, path: String, work: String): Int
    @Query("SELECT inputsJson FROM media_recovery_record")
    suspend fun provenanceInputs(): List<String>
    @Query("SELECT * FROM media_recovery_record WHERE awemeId=:id")
    suspend fun provenance(id: String): RecoveryRecord?
}
