package com.blitz.downloader.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract as Docs
import androidx.core.content.ContextCompat
import com.blitz.downloader.model.RecoveryNames
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** 同一逻辑路径对应本机文件或系统外部存储文档，不接受云盘、外部路径或任意 content URI。 */
class RecoveryMediaAccess internal constructor(
    private val context: Context,
    private val fixtureStorage: File? = null,
    private val documentTree: (() -> Uri?)? = null,
) {
    data class Entry(val name: String, val locator: String, val size: Long, val modified: Long, val directory: Boolean)
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("media_recovery", Context.MODE_PRIVATE)
    val authorizationRevision: Long get() = prefs.getLong("tree_revision", 0)
    val rootId = "primary:" + RecoveryNames.root
    val tree: Uri? get() {
        documentTree?.let { return it() }
        return prefs.getString("tree", null)?.let(Uri::parse)?.takeIf { uri ->
            resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        }
    }
    fun grant(uri: Uri) {
        require(uri.authority == "com.android.externalstorage.documents" && Docs.isTreeUri(uri) &&
            Docs.getTreeDocumentId(uri) == rootId) { "请选择内部存储的 Download/bDouyin 文件夹" }
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try { resolver.takePersistableUriPermission(uri, read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        catch (_: SecurityException) { resolver.takePersistableUriPermission(uri, read) }
        check(prefs.edit().putString("tree", uri.toString()).putLong("tree_revision", authorizationRevision + 1).commit())
    }
    fun canUseFile(): Boolean = when {
        documentTree != null -> false
        fixtureStorage != null -> true
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT == 29 -> false
        tree != null -> false
        else -> ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    fun local(path: String): File {
        require(path == RecoveryNames.root || path.startsWith(RecoveryNames.root + "/"))
        require(path.split('/').all(RecoveryNames::safeName))
        val storage = (fixtureStorage ?: Environment.getExternalStorageDirectory()).canonicalFile
        val file = File(storage, path)
        if (file.canonicalFile != file) throw IOException("目录中存在链接，无法读取")
        return file
    }

    fun uri(path: String): Uri {
        val granted = tree ?: throw SecurityException("请重新授权 Download/bDouyin")
        require(path == RecoveryNames.root || path.startsWith(RecoveryNames.root + "/"))
        require(path.split('/').all(RecoveryNames::safeName))
        return Docs.buildDocumentUriUsingTree(granted, "primary:$path")
    }

    fun list(path: String): List<Entry> {
        if (canUseFile()) {
            val dir = local(path)
            val children = dir.listFiles() ?: throw IOException("目录不可读取，请重新检查授权")
            return children.mapNotNull { child ->
                if (!RecoveryNames.safeName(child.name) || child.canonicalFile != child) null
                else Entry(child.name, child.absolutePath, child.length(), child.lastModified(), child.isDirectory)
            }
        }
        val parent = uri(path)
        val childrenUri = Docs.buildChildDocumentsUriUsingTree(parent, Docs.getDocumentId(parent))
        val columns = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME,
            Docs.Document.COLUMN_MIME_TYPE, Docs.Document.COLUMN_SIZE, Docs.Document.COLUMN_LAST_MODIFIED)
        return resolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
            if (cursor.extras.getBoolean(Docs.EXTRA_LOADING) || cursor.extras.getString(Docs.EXTRA_ERROR) != null)
                throw IOException("系统仍在加载目录，稍后重试")
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    if (!RecoveryNames.safeName(name) || cursor.getString(0) != "primary:$path/$name")
                        throw IOException("系统文档路径与授权目录不一致")
                    add(Entry(name, uri("$path/$name").toString(), cursor.getLong(3), cursor.getLong(4),
                        cursor.getString(2) == Docs.Document.MIME_TYPE_DIR))
                }
            }
        } ?: throw IOException("系统未返回目录清单")
    }

    fun metadata(path: String): Pair<Long,Long> {
        if (canUseFile()) {
            val f = local(path)
            check(f.isFile && f.canRead()) { "所选文件不可读取" }
            return f.length() to f.lastModified()
        }
        val fields = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID,Docs.Document.COLUMN_SIZE,Docs.Document.COLUMN_LAST_MODIFIED)
        return resolver.query(uri(path),fields,null,null,null)?.use { c ->
            check(c.moveToFirst() && c.getString(0) == "primary:$path") { "所选文档已移动或不可读" }
            c.getLong(1) to c.getLong(2)
        } ?: throw IOException("无法读取文档属性")
    }

    fun open(path: String): ParcelFileDescriptor {
        return if (canUseFile()) ParcelFileDescriptor.open(local(path), ParcelFileDescriptor.MODE_READ_ONLY)
        else resolver.openFileDescriptor(uri(path), "r") ?: throw FileNotFoundException(path)
    }

    fun stream(path: String) = ParcelFileDescriptor.AutoCloseInputStream(open(path))

    fun imageSize(path: String): com.blitz.downloader.util.MediaOrientationProbe.Size? {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        stream(path).use { android.graphics.BitmapFactory.decodeStream(it,null,options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        val rotate = stream(path).use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees }
        return if (rotate == 90 || rotate == 270) com.blitz.downloader.util.MediaOrientationProbe.Size(options.outHeight,options.outWidth)
        else com.blitz.downloader.util.MediaOrientationProbe.Size(options.outWidth,options.outHeight)
    }

    fun videoSize(path: String): com.blitz.downloader.util.MediaOrientationProbe.Size? {
        val reader = android.media.MediaMetadataRetriever()
        return try {
            open(path).use { fd ->
                reader.setDataSource(fd.fileDescriptor)
                val w = reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
                val h = reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
                val rotation = reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                if (rotation == "90" || rotation == "270") com.blitz.downloader.util.MediaOrientationProbe.Size(h,w)
                else com.blitz.downloader.util.MediaOrientationProbe.Size(w,h)
            }
        } catch (_: RuntimeException) { null } finally { reader.release() }
    }

    /** 缺失必须由成功读取的父目录清单证明；目录本身不可读时保留记录。 */
    fun definitelyMissing(path: String): Boolean = try {
        val folder = path.substringBeforeLast('/')
        folder.removePrefix(RecoveryNames.root + "/") in RecoveryNames.folders &&
            list(folder).none { it.name == path.substringAfterLast('/') } &&
            (!canUseFile() || !android.system.OsConstants.S_ISLNK(android.system.Os.lstat(local(folder).path + "/" + path.substringAfterLast('/')).st_mode))
    } catch (e: android.system.ErrnoException) {
        e.errno == android.system.OsConstants.ENOENT
    } catch (_: Exception) { false }
}
