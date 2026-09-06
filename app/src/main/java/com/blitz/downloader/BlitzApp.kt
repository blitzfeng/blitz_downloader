package com.blitz.downloader

import android.app.Application
import com.blitz.downloader.data.AiTagSuggestionRepository
import com.blitz.downloader.data.DownloadedVideoRepository
import com.blitz.downloader.data.VideoTagRepository
import com.blitz.downloader.data.db.AppDatabase

class BlitzApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val downloadedVideoRepository: DownloadedVideoRepository by lazy {
        DownloadedVideoRepository(this)
    }

    val videoTagRepository: VideoTagRepository by lazy {
        VideoTagRepository(this)
    }

    /** 持有它是为了让 `GeminiProvider` 的 OkHttp 客户端等只构造一次，不随每次弹窗新建。 */
    val aiTagSuggestionRepository: AiTagSuggestionRepository by lazy {
        AiTagSuggestionRepository(this)
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
