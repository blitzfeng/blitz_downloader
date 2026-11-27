package com.blitz.downloader.data

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract as Docs
import android.provider.DocumentsProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.blitz.downloader.data.db.*
import com.blitz.downloader.model.RecoveryNames
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** 通过系统 DocumentsContract 与本进程合成 provider 执行真实 FD、查询与 createDocument 调用。 */
class RecoveryDocumentsTest {
    private class Fixture(private val root: File): DocumentsProvider() {
        val rootId = "primary:" + RecoveryNames.root
        var failCreate = false
        var onVideoOpen: (() -> Unit)? = null
        var onVideoClose: (() -> Unit)? = null
        fun file(id: String): File {
            require(id == rootId || id.startsWith(rootId + "/"))
            return if (id == rootId) root else File(root,id.removePrefix(rootId + "/")).canonicalFile.also {
                require(it.path.startsWith(root.canonicalPath + "/"))
            }
        }
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?) = MatrixCursor(projection ?: emptyArray())
        override fun isChildDocument(parentDocumentId: String, documentId: String) =
            documentId.startsWith(parentDocumentId + "/")
        private fun rows(files: List<File>, projection: Array<out String>?): Cursor {
            val columns = projection ?: arrayOf(Docs.Document.COLUMN_DOCUMENT_ID,Docs.Document.COLUMN_DISPLAY_NAME,Docs.Document.COLUMN_MIME_TYPE,Docs.Document.COLUMN_SIZE)
            return MatrixCursor(columns).apply {
                for (f in files) addRow(columns.map { col -> when(col) {
                    Docs.Document.COLUMN_DOCUMENT_ID -> (rootId + "/" + f.relativeTo(root).invariantSeparatorsPath).trimEnd('/')
                    Docs.Document.COLUMN_DISPLAY_NAME -> f.name
                    Docs.Document.COLUMN_SIZE -> f.length()
                    Docs.Document.COLUMN_LAST_MODIFIED -> f.lastModified()
                    Docs.Document.COLUMN_FLAGS -> Docs.Document.FLAG_DIR_SUPPORTS_CREATE or Docs.Document.FLAG_SUPPORTS_WRITE
                    Docs.Document.COLUMN_MIME_TYPE -> if (f.isDirectory) Docs.Document.MIME_TYPE_DIR else "application/octet-stream"
                    else -> null
                } })
            }
        }
        override fun queryDocument(documentId: String,projection: Array<out String>?) = rows(listOf(file(documentId)),projection)
        override fun queryChildDocuments(parentDocumentId: String,projection: Array<out String>?,sortOrder: String?) =
            rows(file(parentDocumentId).listFiles()!!.toList(),projection)
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            val f = file(documentId)
            val access = ParcelFileDescriptor.parseMode(mode)
            if (!documentId.endsWith(".mp4") || onVideoOpen == null) return ParcelFileDescriptor.open(f,access)
            val descriptor = ParcelFileDescriptor.open(f,access,android.os.Handler(android.os.Looper.getMainLooper())) { onVideoClose?.invoke() }
            onVideoOpen?.invoke()
            return descriptor
        }
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            if (failCreate) throw java.io.IOException("fixture space full")
            val id = "$parentDocumentId/$displayName"
            check(if (mimeType == Docs.Document.MIME_TYPE_DIR) file(id).mkdir() else file(id).createNewFile())
            return id
        }
    }
    @Test fun documentStreamsBackupAndRevocation(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir,UUID.randomUUID().toString()).canonicalFile.also { it.mkdir() }
        val authority = app.packageName + ".fixture"
        val fixture = Fixture(root)
        fixture.attachInfo(app,ProviderInfo().apply {
            applicationInfo = app.applicationInfo
            this.authority = authority; exported = true; grantUriPermissions = true
            readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
            writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
        })
        val docs = ContentResolver.wrap(fixture)
        val context = object : ContextWrapper(app) { override fun getContentResolver() = docs }
        var grant: Uri? = Docs.buildTreeDocumentUri(authority,fixture.rootId)
        val access = RecoveryMediaAccess(context,fixtureStorage = File(app.cacheDir,"unmounted-fixture"), documentTree = { grant })
        val sample = InstrumentationRegistry.getInstrumentation().context.assets.open("video-tap.mp4").use { it.readBytes() }
        val video = File(root,"videos/example.mp4").also { it.parentFile!!.mkdir(); it.writeBytes(sample) }
        val path = RecoveryNames.root + "/videos/example.mp4"
        assertEquals(video.length(),access.list(RecoveryNames.root + "/videos").single().size)
        assertArrayEquals(sample,access.stream(path).use { it.readBytes() })
        assertTrue(access.videoSize(path)!!.width > 0)
        verifySamplingCancellation(access,fixture,root,path)
        val db = AppDatabase.open(app,"docs-${UUID.randomUUID()}.db")
        try {
            val backup = RecoveryBackupStore(context,db,access)
            val b = RecoveryBatch("batch","operation","session",1,"[]","test",1)
            val dao = db.mediaRecoveryDao()
            dao.insert(RecoverySession("session",1,1))
            dao.insert(b)
            val row = DownloadedVideoEntity(awemeId = "fixture",downloadType = "recovered",userName = "合成",filePath = path)
            val id = db.downloadedVideoDao().insert(row)
            val inputs = com.blitz.downloader.model.RecoveryInputs(path,listOf(path))
            dao.insert(RecoveryRecord("fixture",id,"batch",1,com.google.gson.Gson().toJson(inputs)))
            val repo = MediaRecoveryRepository(db,access,com.blitz.downloader.api.RecoveryDetailFetcher(),backup,
                (app.applicationContext as com.blitz.downloader.BlitzApp).databaseBackupCoordinator.maintenance)
            val export = com.blitz.downloader.download.MediaExportManager.resolveExportFiles(listOf(row),repo).single()
            assertArrayEquals(sample,export.open().use { it.readBytes() })
            assertEquals(video.length(),export.length())
            val server = com.blitz.downloader.net.LanFileServer(listOf(export))
            val port = server.start(0)
            try {
                val url = java.net.URL("http://127.0.0.1:$port/all.zip")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                try {
                    assertEquals(200,connection.responseCode)
                    java.util.zip.ZipInputStream(connection.inputStream).use { zip ->
                        assertEquals(export.entryName,zip.nextEntry.name)
                        assertArrayEquals(sample,zip.readBytes())
                        assertNull(zip.nextEntry)
                    }
                } finally { connection.disconnect() }
            } finally { server.stop() }
            val published = backup.publish(b)
            backup.verify(published)
            val manifest = File(root,"recovery-backups/operation/batch/recovery-manifest.json")
            assertTrue(manifest.isFile)
            val dbFile = File(manifest.parentFile,"blitz_downloader.db")
            assertEquals(32, DatabaseSnapshot.validate(dbFile).version)
            val moved = File(manifest.parentFile,"moved-fixture.db")
            assertTrue(dbFile.renameTo(moved))
            assertTrue(runCatching { backup.verify(published) }.isFailure)
            assertTrue(moved.renameTo(dbFile))
            java.io.RandomAccessFile(dbFile,"rw").use { it.writeByte(0) }
            assertTrue(runCatching { backup.verify(published) }.isFailure)
            fixture.failCreate = true
            assertTrue(runCatching { backup.publish(b.copy(batchId = "failed")) }.isFailure)
            assertFalse(File(root,"recovery-backups/operation/failed/recovery-manifest.json").exists())
            grant = null
            assertTrue(runCatching { access.stream(path).close() }.exceptionOrNull() is SecurityException)
            assertFalse(access.definitelyMissing(path))
            assertTrue(video.isFile)
            assertNotNull(db.downloadedVideoDao().getByAwemeId("fixture"))
            assertTrue(runCatching { export.open().close() }.isFailure)
        } finally { db.close() }
    }

    private suspend fun verifySamplingCancellation(access: RecoveryMediaAccess, fixture: Fixture, root: File, video: String) =
        kotlinx.coroutines.coroutineScope {
            val work = "7390000000000000005"
            val media = RecoveryFile("fixture",video,video,"VIDEO","example",0,0,"sample",null,null,false)
            val proofs = (1..2).map { index ->
                val name = "covers/evidence/${work}_evidence_$index.jpg"
                val f = File(root,name)
                f.parentFile!!.mkdirs()
                val bmp = android.graphics.Bitmap.createBitmap(32,24,android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    for (x in 0 until 32) for (y in 0 until 24) {
                        val gray = if (index == 1) x * 8 else 255 - x * 8
                        bmp.setPixel(x,y,android.graphics.Color.rgb(gray,gray,gray))
                    }
                    f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,it) }
                } finally { bmp.recycle() }
                val path = RecoveryNames.root + "/" + name
                RecoveryFile("fixture",path,path,"EVIDENCE",work,f.length(),0,"proof-$index",work,index,false)
            }
            val detail = com.blitz.downloader.model.RecoveryDetail(work,"video","ex","fixture","ample",12345,3000,0,0)
            val matcher = MediaRecoveryMatcher(access,proofs + media)
            fixture.onVideoOpen = { error("缺少合理候选时不应打开视频") }
            assertTrue(matcher.choices(detail.copy(description = "其他描述")).isEmpty())
            val closed = java.util.concurrent.CountDownLatch(1)
            val sampling = launch(kotlinx.coroutines.Dispatchers.IO,start = kotlinx.coroutines.CoroutineStart.LAZY) { matcher.choices(detail) }
            try {
                fixture.onVideoClose = closed::countDown
                fixture.onVideoOpen = { sampling.cancel() }
                sampling.start()
                sampling.join()
                assertTrue(sampling.isCancelled)
                assertTrue("取消抽帧后应释放文件描述符",closed.await(5,java.util.concurrent.TimeUnit.SECONDS))
            } finally {
                fixture.onVideoOpen = null
                fixture.onVideoClose = null
            }
        }
}
