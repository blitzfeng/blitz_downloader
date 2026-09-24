package com.blitz.downloader.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.blitz.downloader.model.CameraVideoCandidate
import com.blitz.downloader.model.CameraVideoRules
import java.io.IOException
import java.util.UUID

/** 仅接受系统主共享存储 provider 的两个固定目录，不接受云盘或任意替代目录。 */
class SafCameraVideoStorage(
    context: Context,
    private val sourceTree: Uri,
    private val downloadTree: Uri,
) : CameraVideoStorage {
    private val resolver = context.applicationContext.contentResolver
    private var target: Uri? = null
    private val source = document(sourceTree)
    private val downloads = document(downloadTree)

    companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
        fun validTree(uri: Uri, source: Boolean): Boolean = runCatching {
            uri.authority == AUTHORITY && DocumentsContract.isTreeUri(uri) &&
                DocumentsContract.getTreeDocumentId(uri) == if (source) "primary:DCIM/Camera" else "primary:Download"
        }.getOrDefault(false)

        private fun document(tree: Uri) = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }

    override fun checkAccess() {
        if (!validTree(sourceTree, true) || !validTree(downloadTree, false)) throw SecurityException("目录授权不符合要求")
        for (tree in listOf(sourceTree, downloadTree)) {
            if (resolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission && it.isWritePermission }) {
                throw SecurityException("目录访问授权已失效，请重新授权")
            }
        }
    }

    private fun children(parent: Uri): List<CameraVideoCandidate> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val columns = arrayOf("document_id", "_display_name", "_size", "last_modified", "mime_type")
        return resolver.query(uri, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    // provider 返回的身份仍需验证父目录，不能仅信任显示名。
                    if (id.substringBeforeLast('/') != DocumentsContract.getDocumentId(parent)) continue
                    add(CameraVideoCandidate(
                        DocumentsContract.buildDocumentUriUsingTree(parent, id).toString(),
                        cursor.getString(1), if (cursor.isNull(2)) -1 else cursor.getLong(2),
                        if (cursor.isNull(3)) -1 else cursor.getLong(3), cursor.getString(4).orEmpty(),
                    ))
                }
            }
        } ?: throw IOException("无法读取授权目录")
    }

    private fun checked(id: String): Uri {
        val uri = Uri.parse(id)
        val doc = DocumentsContract.getDocumentId(uri)
        val parent = doc.substringBeforeLast('/')
        if (uri.authority != AUTHORITY || parent !in listOf("primary:DCIM/Camera", "primary:Download/history", "primary:Download/history_img") ||
            doc.substringAfterLast('/') in listOf(".", "..")
        ) throw IOException("文件不在允许的目录内")
        return uri
    }

    override fun scan(): List<CameraVideoCandidate> {
        checkAccess()
        return children(source).filter { CameraVideoRules.isCandidate(it.name, it.mime) }.sortedBy { it.name }
    }

    override fun snapshot(id: String): CameraVideoCandidate? {
        val uri = checked(id)
        val parent = if (DocumentsContract.getDocumentId(uri).startsWith("primary:DCIM/Camera/")) source else target ?: return null
        return children(parent).find { it.id == id }
    }

    override fun prepareDestination(candidate: CameraVideoCandidate) {
        checkAccess()
        val folder = CameraVideoRules.destinationFolder(candidate.mime) ?: throw IOException("不支持的媒体类型")
        val existing = children(downloads).find { it.name == folder }
        if (existing != null && existing.mime != DocumentsContract.Document.MIME_TYPE_DIR) throw IOException("Download/$folder 已被文件占用")
        target = existing?.id?.let(Uri::parse) ?: DocumentsContract.createDocument(
            resolver, downloads, DocumentsContract.Document.MIME_TYPE_DIR, folder,
        ) ?: throw IOException("无法创建 Download/$folder")
        if (DocumentsContract.getDocumentId(target!!) != "primary:Download/$folder") throw IOException("目标目录不符合要求")
    }

    override fun createTemporary(): String = create(".blitz-camera-${UUID.randomUUID()}.partial", "application/octet-stream")

    private fun create(name: String, mime: String): String =
        DocumentsContract.createDocument(resolver, target ?: throw IOException("目标未就绪"), mime, name)
            ?.toString() ?: throw IOException("无法创建目标文件")

    override fun read(id: String) = resolver.openInputStream(checked(id)) ?: throw IOException("无法读取文件")
    override fun write(id: String) = resolver.openOutputStream(checked(id), "wt") ?: throw IOException("无法写入文件")

    override fun publish(temporary: String, name: String, mime: String, record: (String) -> Unit): String {
        val existing = children(target ?: throw IOException("目标未就绪"))
        val selected = CameraVideoRules.availableName(name) { candidate -> existing.any { it.name == candidate } }
        // 系统 ExternalStorageProvider 的 createDocument 创建唯一文件，不打开已有同名文件。
        val id = create(selected, mime)
        if (existing.any { it.id == id }) throw IOException("provider 未创建独立文件，已停止写入")
        record(id)
        read(temporary).use { input -> write(id).use { input.copyTo(it) } }
        return id
    }

    override fun delete(id: String) = DocumentsContract.deleteDocument(resolver, checked(id))
    override fun displayPath(id: String) = DocumentsContract.getDocumentId(checked(id)).removePrefix("primary:")
}
