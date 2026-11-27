package com.blitz.downloader

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.blitz.downloader.api.RecoveryDetailFetcher
import com.blitz.downloader.data.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.util.UUID

/** 仅独立验证变体选择该 runner，页面和前台服务共用合成存储与模拟请求。 */
class RecoveryValidationRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        check(context.packageName == "com.blitz.downloader.recoveryvalidation")
        return super.newApplication(javaClass.classLoader,RecoveryValidationApp::class.java.name,context)
    }
}

class RecoveryValidationApp : BlitzApp() {
    val requests = java.util.concurrent.atomic.AtomicInteger()
    @Volatile var waitForQuery: java.util.concurrent.CountDownLatch? = null
    val fixture by lazy { File(cacheDir,"ui-" + UUID.randomUUID()).also { it.mkdirs() } }
    override val mediaRecovery by lazy {
        fixtureRepository(database, RecoveryMediaAccess(this,fixture))
    }

    fun fixtureRepository(db: com.blitz.downloader.data.db.AppDatabase, access: RecoveryMediaAccess): MediaRecoveryRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            waitForQuery?.await(10, java.util.concurrent.TimeUnit.SECONDS)
            val id = chain.request().url.queryParameter("aweme_id")!!
            val body = """{"status_code":0,"aweme_detail":{"aweme_id":"$id","create_time":1234567890,"desc":"合成画面","author":{"nickname":"测试作者","sec_uid":"test"},"video":{"duration":3000}}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return MediaRecoveryRepository(db,access,RecoveryDetailFetcher(client),RecoveryBackupStore(this,db,access),
            databaseBackupCoordinator.maintenance,cookie = { "sessionid=simulated-only" })
    }
}
