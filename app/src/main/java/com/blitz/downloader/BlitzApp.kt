package com.blitz.downloader

import android.app.Application
import com.blitz.downloader.data.DownloadedVideoRepository
import com.blitz.downloader.data.db.AppDatabase

class BlitzApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val downloadedVideoRepository: DownloadedVideoRepository by lazy {
        DownloadedVideoRepository(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: BlitzApp
            private set
    }
}
