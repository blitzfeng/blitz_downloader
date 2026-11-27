package com.blitz.downloader.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import com.blitz.downloader.data.db.*
import com.blitz.downloader.model.RecoveryNames
import com.blitz.downloader.model.hex
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

class RecoveryBackupStore(private val context: Context, private val db: AppDatabase, private val access: RecoveryMediaAccess) {
    data class Manifest(val operationId: String, val batchId: String, val schemaVersion: Int, val bytes: Long,
        val sha256: String, val manifestHash: String, val verifiedAt: Long, val protected: Boolean = true, val format: Int = 1)
    data class Published(val folder: String, val manifest: Manifest)
    private val gson = Gson()
    private val resolver = context.contentResolver

    suspend fun publish(batch: RecoveryBatch): Published {
        val root = RecoveryNames.root + "/recovery-backups"
        val folder = root + "/" + batch.operationId + "/" + batch.batchId
        val temp = File.createTempFile("recovery-", ".db", context.cacheDir)
        try {
            val estimate = db.openHelper.readableDatabase.let { sql ->
                fun pragma(key: String) = sql.query("PRAGMA $key").use { it.moveToFirst(); it.getLong(0) }
                pragma("page_count") * pragma("page_size")
            }
            check(context.cacheDir.usableSpace > estimate) { "内部空间不足" }
            val info = DatabaseSnapshot.create(db, temp)
            val expectedHash = temp.inputStream().use(::digest)
            if (access.canUseFile()) {
                check(access.local(RecoveryNames.root).usableSpace > temp.length()) { "外部空间不足" }
                check(access.local(root + "/" + batch.operationId).let { it.isDirectory || it.mkdirs() })
                check(access.local(folder).mkdir()) { "备份目录已存在或不可写" }
            } else {
                val parent = ensureDir(RecoveryNames.root, "recovery-backups")
                val operation = ensureDir(parent,batch.operationId)
                require(access.list(operation).none { it.name == batch.batchId })
                ensureDir(operation,batch.batchId)
            }
            write(folder,"blitz_downloader.db","application/octet-stream") { out -> temp.inputStream().use { it.copyTo(out,65536) } }
            val manifest = Manifest(batch.operationId,batch.batchId,info.version,temp.length(),expectedHash,batch.manifestHash,System.currentTimeMillis())
            check(inspectDb(folder,manifest)) { "外部备份大小或摘要不符" }
            write(folder,"recovery-manifest.json","application/json") { it.write(gson.toJson(manifest).toByteArray()) }
            return Published(folder,manifest).also { verify(it) }
        } finally { temp.delete() }
    }

    private fun readManifest(folder: String): Manifest =
        access.stream(folder + "/recovery-manifest.json").bufferedReader().use {
            val buf = CharArray(4096)
            var n = 0
            while (n < buf.size) {
                val read = it.read(buf,n,buf.size-n)
                if (read < 0) break
                n += read
            }
            require(n in 1 until buf.size)
            gson.fromJson(String(buf,0,n), Manifest::class.java)
        }
    fun recoverPublished(batch: RecoveryBatch): Published? {
        val folder = RecoveryNames.root + "/recovery-backups/" + batch.operationId + "/" + batch.batchId
        return try {
            val m = readManifest(folder)
            if (m.batchId == batch.batchId && m.operationId == batch.operationId && m.manifestHash == batch.manifestHash &&
                m.format == 1 && m.protected && inspectDb(folder,m)) Published(folder,m) else null
        } catch (_: Exception) { null }
    }

    fun verify(published: Published) {
        val manifest = readManifest(published.folder)
        check(manifest == published.manifest && inspectDb(published.folder, manifest)) { "独立备份已变更或无法回读" }
    }

    private fun inspectDb(folder: String, manifest: Manifest): Boolean {
        val path = folder + "/blitz_downloader.db"
        val bytes = access.list(folder).singleOrNull { it.name == "blitz_downloader.db" }?.size
        return bytes == manifest.bytes && access.stream(path).use(::digest) == manifest.sha256
    }

    private fun digest(input: InputStream): String {
        val d = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            d.update(buffer,0,n)
        }
        return d.digest().hex()
    }
    private fun ensureDir(parent: String, name: String): String {
        if (access.list(parent).none { it.name == name && it.directory }) {
            val created = Docs.createDocument(resolver, access.uri(parent), Docs.Document.MIME_TYPE_DIR, name)
            check(created != null && Docs.getDocumentId(created) == "primary:$parent/$name")
        }
        return "$parent/$name"
    }
    private fun write(parent: String, name: String, mime: String, block: (OutputStream) -> Unit) {
        val path = "$parent/$name"
        if (access.canUseFile()) {
            val file = access.local(path)
            check(file.createNewFile())
            file.outputStream().use { block(it); it.fd.sync() }
        } else {
            val uri: Uri = Docs.createDocument(resolver, access.uri(parent),mime,name) ?: throw IOException("备份目录不可写")
            check(Docs.getDocumentId(uri) == "primary:$path")
            resolver.openOutputStream(uri,"w")?.use(block) ?: throw IOException("无法发布备份")
        }
    }
}
