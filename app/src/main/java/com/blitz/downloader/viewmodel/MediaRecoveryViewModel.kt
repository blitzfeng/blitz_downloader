package com.blitz.downloader.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.data.*
import com.blitz.downloader.data.db.*
import com.blitz.downloader.service.MediaRecoveryService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MediaRecoveryViewModel(app: Application): AndroidViewModel(app) {
    val repo = (app as BlitzApp).mediaRecovery
    private val dao = repo.dao
    val session = dao.latest().stateIn(viewModelScope,SharingStarted.Eagerly,null)
    val downloads = dao.downloadCount()
    val history = dao.history()
    val selected = MutableStateFlow<List<RecoveryCandidate>>(emptyList())
    val preview = MutableStateFlow<RecoveryPreview?>(null)
    val error = MutableStateFlow("")
    val localBusy = MutableStateFlow(false)
    init { perform { repo.initialize() } }
    suspend fun lastScan() = dao.lastCompleteScan()
    suspend fun batchItems(id: String) = dao.items(id)
    fun errors(id: String, offset: Int) = dao.workErrors(id,offset)
    suspend fun detail(c: RecoveryCandidate) = dao.work(c.sessionId,c.awemeId)?.detailJson?.let {
        repo.gson.fromJson(it,com.blitz.downloader.model.RecoveryDetail::class.java)
    }
    fun counts(id: String) = combine(dao.workCount(id),dao.successCount(id),dao.confirmedCount(id)) { a,b,c -> listOf(a,b,c) }
    fun page(id: String, states: List<String>, offset: Int) = dao.page(id,states,offset)
    fun inputs(candidate: RecoveryCandidate) = if (candidate.groupKey.isBlank()) null else
        repo.gson.fromJson(candidate.inputsJson, com.blitz.downloader.model.RecoveryInputs::class.java)
    fun unbound(id: String, offset: Int) = dao.unbound(id,offset)
    fun setGrant(uri: Uri) = perform { repo.access.grant(uri); selected.value = emptyList(); preview.value = null }
    fun attach(id: String,path: String,input: String) = perform { repo.attach(id,path,input) }
    fun review(c: RecoveryCandidate, accept: Boolean, selection: com.blitz.downloader.model.RecoveryInputs? = null) = perform {
        repo.review(c,accept,selection)
        selected.update { rows -> rows.filterNot { it.awemeId == c.awemeId } }
    }
    fun confirmReady(rows: List<RecoveryCandidate>) = perform { rows.filter { it.state == "READY" }.forEach { repo.review(it,true) } }
    fun toggle(c: RecoveryCandidate) {
        if (c.state != "CONFIRMED") return
        selected.update { if (c in it) it - c else it.filterNot { row -> row.awemeId == c.awemeId } + c }
    }
    fun prepare() = perform { preview.value = repo.writer.preview(selected.value) }
    fun start(action: MediaRecoveryService.Action,id: String) {
        val frozen = preview.value
        if (action == MediaRecoveryService.Action.WRITE && frozen == null) return
        try {
            MediaRecoveryService.start(getApplication(),MediaRecoveryService.Command(action,id,frozen))
            preview.value = null
            selected.value = emptyList()
        } catch (e: Exception) { error.value = e.localizedMessage.orEmpty() }
    }
    fun pause() { val app = getApplication<Application>(); app.stopService(Intent(app,MediaRecoveryService::class.java)) }
    fun report(uri: Uri) = perform {
        val sessions = dao.sessions()
        val batches = dao.history().first()
        val report = mapOf("schemaVersion" to 1,"sessions" to sessions.map { s ->
            mapOf("session" to s.sessionId,"generation" to s.generation,"state" to s.state,"files" to s.fileCount,
                "works" to dao.works(s.sessionId).map { w ->
                    mapOf("awemeId" to w.awemeId,"lookup" to w.state,"httpStatus" to w.httpCode,
                        "candidates" to dao.candidates(s.sessionId,w.awemeId).map { c ->
                            mapOf("main" to inputs(c)?.main,"method" to c.method,"reason" to c.reason,"review" to c.state)
                        })
                })
        },"batches" to batches.map { batch ->
            val items = dao.items(batch.batchId)
            mapOf("id" to batch.batchId,"status" to batch.state,"backup" to batch.backupLocator,
                "planned" to items.size,"inserted" to batch.inserted,"skipped" to batch.skipped,
                "stale" to items.count { it.state == "STALE" },"failed" to items.count { it.state == "FAILED" },
                "pending" to items.count { it.state == "PENDING" },"reason" to batch.errorCode,"items" to items.map {
                    mapOf("awemeId" to it.awemeId,"main" to repo.gson.fromJson(it.payloadJson,DownloadedVideoEntity::class.java).filePath,
                        "state" to it.state,"rowId" to it.rowId)
                })
        })
        checkNotNull(getApplication<Application>().contentResolver.openOutputStream(uri,"wt")).bufferedWriter().use {
            repo.gson.toJson(report,it)
        }
    }
    private fun perform(block: suspend () -> Unit) {
        if (!localBusy.compareAndSet(false,true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try { error.value = ""; block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.localizedMessage?.take(150) ?: "操作失败" }
            finally { localBusy.value = false }
        }
    }
}
