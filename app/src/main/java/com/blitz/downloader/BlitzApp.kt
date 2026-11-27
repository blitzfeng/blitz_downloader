package com.blitz.downloader

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import com.blitz.downloader.data.AiPreferenceRepository
import com.blitz.downloader.data.AiTagSuggestionRepository
import com.blitz.downloader.data.DownloadedVideoRepository
import com.blitz.downloader.data.LikedListIndexRepository
import com.blitz.downloader.data.VideoTagRepository
import com.blitz.downloader.data.db.AppDatabase

open class BlitzApp : Application() {

    val cameraVideoOrganizer by lazy { com.blitz.downloader.data.CameraVideoOrganizer(this) }

    val aiPreferenceRepository by lazy { AiPreferenceRepository(this) }

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    open val mediaRecovery by lazy {
        val access = com.blitz.downloader.data.RecoveryMediaAccess(this)
        com.blitz.downloader.data.MediaRecoveryRepository(database, access,
            com.blitz.downloader.api.RecoveryDetailFetcher(),
            com.blitz.downloader.data.RecoveryBackupStore(this,database,access), databaseBackupCoordinator.maintenance)
    }

    val downloadedVideoRepository: DownloadedVideoRepository by lazy {
        DownloadedVideoRepository(this)
    }

    val videoTagRepository: VideoTagRepository by lazy {
        VideoTagRepository(this)
    }

    val likedListIndexRepository: LikedListIndexRepository by lazy { LikedListIndexRepository(this) }

    /** 复用 AI repository；Gemini/Vertex 的 HTTP 客户端由 provider 内部共享。 */
    val aiTagSuggestionRepository: AiTagSuggestionRepository by lazy {
        AiTagSuggestionRepository(this)
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val databaseBackupCoordinator by lazy { com.blitz.downloader.data.DatabaseBackupCoordinator(this, appScope) }
    internal val preferenceRecovery by lazy { appScope.async { aiPreferenceRepository.recoverInterrupted() } }

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferenceRecovery
        if (!com.blitz.downloader.data.RecoveryProtection.enabled(this)) {
            registerActivityLifecycleCallbacks(databaseBackupCoordinator)
        }
    }

    companion object {
        lateinit var instance: BlitzApp
            private set
    }
}
