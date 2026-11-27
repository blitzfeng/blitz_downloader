package com.blitz.downloader.data

import android.os.Debug
import androidx.room.withTransaction
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.blitz.downloader.api.RecoveryDetailFetcher
import com.blitz.downloader.data.db.*
import com.blitz.downloader.model.RecoveryNames
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** 合成目录和库均使用独立验证包的私有空间，网络在 OkHttp 拦截器内返回。 */
class RecoveryWorkflowTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as com.blitz.downloader.BlitzApp
    private val token = UUID.randomUUID().toString()
    private val storage = File(app.cacheDir,"media-fixture-$token")
    private val root = File(storage,RecoveryNames.root)
    private val work = "7390000000000000001"
    private val another = "7390000000000000002"
    private val db = AppDatabase.open(app,"workflow-$token.db")
    private val access = RecoveryMediaAccess(app,storage)
    private val video by lazy { InstrumentationRegistry.getInstrumentation().context.assets.open("video-tap.mp4").use { it.readBytes() } }
    private val calls = AtomicInteger()
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        calls.incrementAndGet()
        val id = chain.request().url.queryParameter("aweme_id")!!
        val json = """{"status_code":0,"aweme_detail":{"aweme_id":"$id","create_time":1234567890,"desc":"合成描述","author":{"nickname":"合成作者","sec_uid":"fixture"},"video":{"duration":1000}}}"""
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(json.toResponseBody("application/json".toMediaType())).build()
    }.build()
    private val repo = MediaRecoveryRepository(db,access,RecoveryDetailFetcher(client),
        RecoveryBackupStore(app,db,access), app.databaseBackupCoordinator.maintenance, cookie = { "sessionid=fixture" })
    private val dao = db.mediaRecoveryDao()
    private fun file(name: String,value: String): File = File(root,name).also { f ->
        f.parentFile!!.mkdirs()
        f.writeText(value)
    }
    @org.junit.After fun close() { db.close() }

    private suspend fun prepare(vararg ids: String): RecoveryPreview {
        ids.forEach { file("videos/作者_描述_${it}.mp4","").writeBytes(video) }
        repo.initialize()
        assertEquals(0,calls.get())
        repo.scan()
        val id = dao.latest().first()!!.sessionId
        assertEquals(0,db.downloadedVideoDao().getAll().size)
        assertEquals(0,calls.get())
        repo.query(id)
        assertEquals(ids.size,calls.get())
        ids.forEach {
            val c = dao.candidates(id,it).single()
            assertTrue(c.state in setOf("READY","REVIEW"))
            repo.review(c,true)
            assertTrue(runCatching { repo.review(c,false) }.isFailure)
        }
        assertTrue(db.downloadedVideoDao().getAll().isEmpty())
        return repo.writer.preview(ids.map { dao.candidates(id,it).single() })
    }

    @Test fun backupAndCommitSurviveReopen(): Unit = runBlocking {
        val preview = prepare(work)
        repo.writer.commit(preview)
        val b = dao.batch(preview.parts.single())!!
        assertEquals("COMMITTED",b.state)
        assertEquals(1,b.inserted)
        val snapshot = File(storage,b.backupLocator + "/blitz_downloader.db")
        val verified = DatabaseSnapshot.validate(snapshot)
        assertEquals(0L,verified.counts["downloaded_videos"])
        assertEquals(7,verified.counts.keys.count { it.startsWith("media_recovery_") })
        assertTrue(File(snapshot.parentFile,"recovery-manifest.json").isFile)
        assertEquals(dao.items(b.batchId).single().rowId,dao.provenance(work)!!.videoRowId)
        repo.writer.commit(preview)
        assertEquals(1,db.downloadedVideoDao().getAll().size)
        assertTrue(db.videoTagDao().getTagsWithCount().isEmpty())
        assertEquals(0,db.videoTagFeedbackDao().countAll())
        assertArrayEquals(video,File(root,"videos/作者_描述_${work}.mp4").readBytes())
        val restored = db.downloadedVideoDao().getAll().single()
        val export = com.blitz.downloader.download.MediaExportManager.resolveExportFiles(listOf(restored),repo).single()
        assertArrayEquals(video,export.open().use { it.readBytes() })
        assertEquals(video.size.toLong(),export.length())
        assertTrue(access.videoSize(restored.filePath)!!.width > 0)
        db.videoTagDao().insert(VideoTagEntity(awemeId = work,tagName = "恢复后添加"))
        db.downloadedVideoDao().incrementTagEditCount(listOf(work))
        assertEquals(listOf("恢复后添加"),db.videoTagDao().getTagsForVideo(work))
        assertEquals(1,db.downloadedVideoDao().getByAwemeId(work)!!.tagEditCount)
        AppDatabase.open(app,"workflow-$token.db").let { reopened ->
            try { assertEquals("COMMITTED",reopened.mediaRecoveryDao().batch(b.batchId)?.state) } finally { reopened.close() }
        }
    }

    @Test fun changedContentsAndFailedBackupStopAllWrites(): Unit = runBlocking {
        val preview = prepare(work)
        val f = File(root,"videos/作者_描述_${work}.mp4")
        val old = f.readBytes()
        val time = f.lastModified()
        f.writeBytes(old.reversedArray())
        f.setLastModified(time)
        assertTrue(runCatching { repo.writer.commit(preview) }.isFailure)
        assertTrue(db.downloadedVideoDao().getAll().isEmpty())
        assertEquals("STALE",dao.candidates(preview.session,work).single().state)
        assertEquals("STALE",dao.items(preview.parts.single()).single().state)
        f.writeBytes(old)
        f.setLastModified(time)
        repo.review(dao.candidates(preview.session,work).single(),true)
        val retry = repo.writer.preview(dao.candidates(preview.session,work))
        file("recovery-backups","阻止创建备份目录")
        assertTrue(runCatching { repo.writer.commit(retry) }.isFailure)
        assertTrue(db.downloadedVideoDao().getAll().isEmpty())
        assertEquals("PENDING",dao.items(retry.parts.single()).single().state)
    }

    @Test fun failureOnSecondInsertRollsBackLedgerAndFirstRow(): Unit = runBlocking {
        val preview = prepare(work,another)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fixture_abort BEFORE INSERT ON media_recovery_record WHEN NEW.awemeId='$another' BEGIN SELECT RAISE(ABORT,'fixture'); END")
        assertTrue(runCatching { repo.writer.commit(preview) }.isFailure)
        assertTrue(db.downloadedVideoDao().getAll().isEmpty())
        assertNull(dao.provenance(work))
        assertEquals(listOf("PENDING","FAILED"),dao.items(preview.parts.single()).map { it.state })
        assertEquals("FAILED",dao.batch(preview.parts.single())!!.state)
    }

    @Test fun interruptedScanKeepsPublishedGenerationAndUncertainPaths(): Unit = runBlocking {
        file("videos/作者_${work}.mp4","sample")
        file("covers/evidence/${work}_evidence_0.jpg","frame")
        file("videos/.nomedia","")
        file("videos/未知文件名.mp4","other")
        file("videos/ignored.txt","ignore")
        val outside = File(storage,"outside.mp4").also { it.writeText("outside") }
        android.system.Os.symlink(outside.path,File(root,"videos/link.mp4").path)
        repo.scan()
        val good = dao.lastCompleteScan()!!
        assertEquals(3,good.fileCount)
        assertEquals(1,dao.works(good.sessionId).size)
        assertTrue(dao.files(good.sessionId).any { it.kind == "EVIDENCE" })
        assertFalse(access.definitelyMissing(RecoveryNames.root + "/videos/link.mp4"))
        assertFalse(access.definitelyMissing(RecoveryNames.root + "/unknown/abc.mp4"))
        assertTrue(access.definitelyMissing(RecoveryNames.root + "/videos/missing.mp4"))
        assertTrue(root.renameTo(File(storage,"fixture-hidden")))
        assertTrue(runCatching { repo.scan() }.isFailure)
        assertEquals(good.sessionId,dao.lastCompleteScan()!!.sessionId)
        assertTrue(db.downloadedVideoDao().getAll().isEmpty())
    }

    @Test fun duplicateAtWriteAndProcessRestartOnlyChangeStaging(): Unit = runBlocking {
        val preview = prepare(work)
        val original = DownloadedVideoEntity(awemeId = work,downloadType = "post",userName = "其他",tagEditCount = 3)
        val row = db.downloadedVideoDao().insert(original)
        repo.writer.commit(preview)
        assertEquals(original.copy(id=row),db.downloadedVideoDao().getByAwemeId(work))
        val batch = dao.batch(preview.parts.single())!!
        assertEquals(1,batch.skipped)
        assertTrue(batch.backupLocator.isEmpty())
        val session = dao.session(preview.session)!!
        dao.update(session.copy(state = "FETCHING"))
        val reopened = MediaRecoveryRepository(db,access,RecoveryDetailFetcher(client),repo.backups,
            app.databaseBackupCoordinator.maintenance, cookie = { "sessionid=fixture" })
        reopened.initialize()
        assertEquals("INTERRUPTED",dao.session(session.sessionId)!!.state)
        assertEquals("COMMITTED",dao.batch(batch.batchId)!!.state)
        assertEquals(1,calls.get())
        reopened.query(session.sessionId)
        assertEquals(1,calls.get())
    }

    @Test fun queuePacesRetriesAndStopsOnRateLimit(): Unit = runBlocking {
        suspend fun run(status: Int): Pair<RecoverySession,List<Long>> {
            listOf(work,another,"7390000000000000003","7390000000000000004").forEach { file("videos/f_${it}.mp4","f") }
            val starts = java.util.Collections.synchronizedList(mutableListOf<Long>())
            val gate = java.util.concurrent.atomic.AtomicInteger(0)
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                starts += SystemClock.elapsedRealtime()
                val n = gate.incrementAndGet()
                val code = if (status == 429 || n == 1) status else 404
                Response.Builder().protocol(Protocol.HTTP_1_1).request(chain.request()).code(code).message("mock")
                    .header("Retry-After","60").body("{}".toResponseBody("application/json".toMediaType())).build()
            }.build()
            val repository = MediaRecoveryRepository(db,access,RecoveryDetailFetcher(transport),repo.backups,
                app.databaseBackupCoordinator.maintenance, cookie = { "sessionid=fixture" })
            repository.scan()
            val id = dao.latest().first()!!.sessionId
            repository.query(id)
            return dao.session(id)!! to starts
        }
        val retry = run(503)
        assertEquals(5,retry.second.size)
        assertTrue(retry.second.zipWithNext().all { (a,b) -> b-a >= 930 })
        val limited = run(429)
        assertEquals("RATE_LIMITED",limited.first.state)
        assertEquals(1,limited.second.size)
        assertTrue(limited.first.retryAfter > System.currentTimeMillis())
        assertTrue(runCatching { repo.query(limited.first.sessionId) }.isFailure)
    }

    @Test fun visualTiesAndAlbumHolesRequireReview(): Unit = runBlocking {
        file("videos/合成甲.mp4","").writeBytes(video)
        file("videos/合成乙.mp4","").writeBytes(video)
        val sample = android.graphics.Bitmap.createBitmap(32,24,android.graphics.Bitmap.Config.ARGB_8888)
        for (x in 0 until 32) for (y in 0 until 24) sample.setPixel(x,y,android.graphics.Color.rgb(x*8,y*10,120))
        for (path in listOf("covers/合成甲.jpg","covers/合成乙.jpg","covers/evidence/${work}_evidence_0.jpg",
            "images/图集_${another}_01.jpg","images/图集_${another}_03.jpg")) {
            file(path,"").outputStream().use { sample.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,it) }
        }
        sample.recycle()
        file("images/图集_${another}_03.mp4","").writeBytes(video)
        repo.scan()
        val session = dao.latest().first()!!
        val files = dao.files(session.sessionId)
        val matcher = MediaRecoveryMatcher(access,files)
        val detail = com.blitz.downloader.model.RecoveryDetail(work,"video","合成","fixture","描述",12345,1000,0,0)
        val tied = matcher.choices(detail)
        assertTrue(tied.isNotEmpty())
        assertTrue(tied.all { it.state == "REVIEW" })
        assertFalse(tied.any { it.state == "CONFIRMED" })
        val album = matcher.choices(detail.copy(awemeId = another,mediaType = "image")).single()
        assertEquals("REVIEW",album.state)
        assertEquals(3,album.inputs.members.size)
        assertTrue(album.inputs.main.endsWith("_01.jpg"))
        assertTrue(album.inputs.members.last().endsWith("_03.mp4"))
        val workRow = dao.work(session.sessionId,another)!!
        val json = repo.gson.toJson(detail.copy(awemeId=another,mediaType="image"))
        dao.update(workRow.copy(state="SUCCESS",detailJson=json,detailHash=com.blitz.downloader.model.sha256(json),
            authScopeHash=repo.scope(),fetchedAt=System.currentTimeMillis()))
        repo.match(session.sessionId)
        val pick = dao.candidates(session.sessionId,another).single()
        val members = album.inputs.members.filter { it.contains("_03.") }
        val chosen = album.inputs.copy(main=members.first(),members=members,cover=members.first())
        repo.review(pick,true,chosen)
        val preview = repo.writer.preview(dao.candidates(session.sessionId,another))
        repo.writer.commit(preview)
        val saved = db.downloadedVideoDao().getByAwemeId(another)!!
        assertTrue(saved.hasLivePhoto)
        assertEquals(chosen.main,saved.filePath)
        assertTrue(File(storage,album.inputs.main).isFile)
        val exported = com.blitz.downloader.download.MediaExportManager.resolveExportFiles(listOf(saved),repo)
        assertEquals(members.map { it.substringAfterLast('/') },exported.map { it.entryName })
        assertTrue(matcher.choices(detail.copy(awemeId="7390000000000000009")).isEmpty())
    }

    @Test fun cacheAuthAndPermissionChangesRequireFreshConfirmation(): Unit = runBlocking {
        val preview = prepare(work)
        repo.query(preview.session)
        assertEquals(1,calls.get())
        val daoWork = dao.work(preview.session,work)!!
        dao.update(daoWork.copy(fetchedAt = daoWork.fetchedAt - 86400001L))
        assertTrue(runCatching { repo.writer.commit(preview) }.isFailure)
        repo.query(preview.session)
        assertEquals(2,calls.get())
        assertTrue(dao.candidates(preview.session,work).none { it.state == "CONFIRMED" })
        repo.review(dao.candidates(preview.session,work).single(),true)
        val attempt = repo.writer.preview(dao.candidates(preview.session,work))
        val prefs = app.getSharedPreferences("media_recovery",0)
        prefs.edit().putLong("tree_revision",access.authorizationRevision + 1).commit()
        assertTrue(runCatching { repo.writer.commit(attempt) }.isFailure)
        val changedAccount = MediaRecoveryRepository(db,access,RecoveryDetailFetcher(client),repo.backups,
            app.databaseBackupCoordinator.maintenance,cookie = { "sessionid=second-fixture" })
        changedAccount.query(preview.session)
        assertEquals(3,calls.get())
        assertTrue(dao.candidates(preview.session,work).none { it.state == "CONFIRMED" })
        assertTrue(db.downloadedVideoDao().getAll().isEmpty())
    }

    @Test fun frozenBatchesKeepEarlierCommitAndReconcilePublishedBackup(): Unit = runBlocking {
        val ids = (0..50).map { (7390000000000000000L + it).toString() }
        ids.forEach { id -> file("videos/合成_${id}.mp4","").writeBytes(video) }
        repo.scan()
        val s = dao.lastCompleteScan()!!
        db.withTransaction {
            for (id in ids) {
                val work = dao.work(s.sessionId,id)!!
                val dto = com.blitz.downloader.model.RecoveryDetail(id,"video","合成","fixture","",12345,3000,0,0)
                val json = repo.gson.toJson(dto)
                dao.update(work.copy(state = "SUCCESS",detailJson = json,detailHash = com.blitz.downloader.model.sha256(json),
                    authScopeHash = repo.scope(),fetchedAt = System.currentTimeMillis()))
            }
        }
        repo.match(s.sessionId)
        val selected = ids.map { id ->
            repo.review(dao.candidates(s.sessionId,id).single(),true)
            dao.candidates(s.sessionId,id).single()
        }
        val plan = repo.writer.preview(selected)
        assertEquals(listOf(50,1),plan.parts.map { dao.items(it).size })
        val second = dao.batch(plan.parts.last())!!
        file("recovery-backups/${second.operationId}/${second.batchId}","已存在的同名文件")
        assertTrue(runCatching { repo.writer.commit(plan) }.isFailure)
        val first = dao.batch(plan.parts.first())!!
        assertEquals(50,first.inserted)
        assertEquals(50,db.downloadedVideoDao().getAll().size)
        assertEquals("PENDING",dao.items(second.batchId).single().state)
        val next = repo.writer.preview(dao.candidates(s.sessionId,ids.last()))
        repo.writer.commit(next)
        assertEquals(51,db.downloadedVideoDao().getAll().size)
        val audit = dao.batch(next.parts.single())!!
        val orphan = audit.copy(batchId = "orphan-" + audit.batchId,operationId = "orphan-operation",state = "BACKING_UP",
            backupLocator = "",backupSha256 = "",verifiedAt = 0)
        dao.insert(orphan)
        val backup = repo.backups.publish(orphan)
        val reopened = MediaRecoveryRepository(db,access,RecoveryDetailFetcher(client),repo.backups,app.databaseBackupCoordinator.maintenance)
        reopened.initialize()
        assertEquals("INTERRUPTED",dao.batch(orphan.batchId)!!.state)
        assertEquals(backup.manifest.sha256,dao.batch(orphan.batchId)!!.backupSha256)
        assertEquals(51,db.downloadedVideoDao().getAll().size)
    }

    @Test fun directIdRequiresMatchingTypeAndDuration() = runBlocking {
        file("videos/作者_${work}.mp4","").writeBytes(video)
        repo.scan()
        val matcher = MediaRecoveryMatcher(access,dao.files(dao.lastCompleteScan()!!.sessionId))
        val detail = com.blitz.downloader.model.RecoveryDetail(work,"video","合成","fixture","",12345,3000,0,0)
        assertEquals("READY",matcher.choices(detail).single().state)
        assertEquals("REVIEW",matcher.choices(detail.copy(mediaType = "image")).single().state)
        assertEquals("REVIEW",matcher.choices(detail.copy(durationMs = 60000)).single().state)
    }

    @Test fun newCopyAfterPreviewInvalidatesFrozenSelection(): Unit = runBlocking {
        val preview = prepare(work)
        file("videos/另一个副本_${work}.mp4","").writeBytes(video)
        assertTrue(runCatching { repo.writer.commit(preview) }.isFailure)
        assertTrue(db.downloadedVideoDao().getAll().isEmpty())
        assertEquals("STALE",dao.items(preview.parts.single()).single().state)
        assertEquals("STALE",dao.candidates(preview.session,work).single().state)
    }

    @Test fun movedMediaAfterPreviewRemainsUnwritten(): Unit = runBlocking {
        val plan = prepare(work)
        val media = File(root,"videos/作者_描述_${work}.mp4")
        val moved = File(storage,"moved-fixture.mp4")
        assertTrue(media.renameTo(moved))
        assertTrue(runCatching { repo.writer.commit(plan) }.isFailure)
        assertEquals("STALE",dao.items(plan.parts.single()).single().state)
        assertTrue(db.downloadedVideoDao().getAll().isEmpty())
        assertArrayEquals(video,moved.readBytes())
    }

    @Test fun nearScaleScan(): Unit = runBlocking {
        val begin = SystemClock.elapsedRealtime()
        repeat(2500) { i -> file("videos/fixture_${7390000000000000000L + i%1200} ($i).mp4","video-$i") }
        repeat(3300) { i -> file("covers/evidence/${7390000000000000000L + i%1200}_evidence_${i/1200}.jpg","frame-$i") }
        File(root,"videos/fixture_7390000000000000000 (0).mp4").writeBytes(video)
        val peak = java.util.concurrent.atomic.AtomicLong(Debug.getPss())
        val sampler = launch(Dispatchers.Default) {
            while (isActive) {
                peak.set(maxOf(peak.get(),Debug.getPss()))
                delay(100)
            }
        }
        val start = SystemClock.elapsedRealtime()
        repo.scan()
        val finish = SystemClock.elapsedRealtime()
        val session = dao.latest().first()!!
        assertEquals(5800,session.fileCount)
        assertEquals(1200,dao.works(session.sessionId).size)
        assertEquals(0,calls.get())
        val works = dao.works(session.sessionId)
        db.withTransaction {
            works.forEach { w ->
                val detail = com.blitz.downloader.model.RecoveryDetail(w.awemeId,"video","合成作者","fixture","合成描述",12345,1000,0,0)
                val json = repo.gson.toJson(detail)
                dao.update(w.copy(state = "SUCCESS",detailJson = json,detailHash = com.blitz.downloader.model.sha256(json),
                    authScopeHash = repo.scope(),fetchedAt = System.currentTimeMillis()))
            }
        }
        val matchStart = SystemClock.elapsedRealtime()
        repo.match(session.sessionId)
        val matchEnd = SystemClock.elapsedRealtime()
        val first = works.first()
        val alternatives = dao.candidates(session.sessionId,first.awemeId)
        assertTrue(alternatives.size > 1 && alternatives.all { it.state == "REVIEW" })
        val pick = alternatives.first()
        repo.review(pick,true)
        val preview = repo.writer.preview(dao.candidates(session.sessionId,first.awemeId).filter { it.state == "CONFIRMED" })
        val writeStart = SystemClock.elapsedRealtime()
        repo.writer.commit(preview)
        val writeEnd = SystemClock.elapsedRealtime()
        sampler.cancelAndJoin()
        val bytes = dao.batch(preview.parts.single())!!.backupSize
        val stats = android.os.Bundle().apply {
            putString("stream","\n建样 ${start-begin} ms，5800 文件扫描 ${finish-start} ms，1200 作品匹配 ${matchEnd-matchStart} ms，单条备份写回 ${writeEnd-writeStart} ms，备份 $bytes B，采样峰值 PSS ${peak.get()} KiB\n")
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0,stats)
    }
}
