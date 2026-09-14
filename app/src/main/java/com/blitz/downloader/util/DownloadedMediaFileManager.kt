package com.blitz.downloader.util

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.blitz.downloader.data.DownloadMediaType
import com.blitz.downloader.data.db.DownloadedVideoEntity
import com.blitz.downloader.download.BatchDownloadCoordinator
import java.io.File

/**
 * 管理已下载媒体文件（视频、图集、动图实况、封面）的磁盘路径解析与删除。
 *
 * 兼顾直接 File API 与 Android MediaStore 内容解析器的协调清理，
 * 并在删除后通知 MediaScanner 刷新系统媒体索引。
 */
object DownloadedMediaFileManager {

    private const val TAG = "DownloadedMediaFile"

    /**
     * 解析相对路径或绝对路径为 [File]。
     */
    fun resolveFile(
        path: String,
        rootDir: File = getExternalStorageDirectorySafely(),
    ): File {
        if (path.isBlank()) return File("")
        val file = File(path)
        return if (file.isAbsolute) file else File(rootDir, path)
    }

    /**
     * 图集兄弟文件扫描：
     * 去掉首张图片文件名末尾 `_\d+` 得到基础名，扫描同目录下匹配 `base_\d+` 的全部文件
     * （支持常见图片格式以及实况图动图本体 `.mp4`）。
     *
     * 规则与 [com.blitz.downloader.download.MediaExportManager] 以及
     * [com.blitz.downloader.activity.ImageViewerActivity] 的逻辑保持一致。
     */
    fun findImageSetFiles(firstFile: File): List<File> {
        val dir = firstFile.parentFile ?: return listOf(firstFile)
        val baseName = firstFile.nameWithoutExtension.replace(Regex("_\\d+$"), "")
        val pattern = Regex("^${Regex.escape(baseName)}_\\d+$")
        val exts = setOf("webp", "jpg", "jpeg", "png", "mp4")
        val files = dir.listFiles { f ->
            f.isFile &&
                f.extension.lowercase() in exts &&
                f.nameWithoutExtension.matches(pattern)
        }
        val list = if (files.isNullOrEmpty()) listOf(firstFile) else files.sortedBy { it.name }
        return if (firstFile !in list) list + firstFile else list
    }

    /**
     * 解析一条下载记录所涉及的全部物理文件（含主体文件、图集全部图片及实况图 mp4、封面缩略图）。
     */
    fun resolveEntityFiles(
        entity: DownloadedVideoEntity,
        rootDir: File = getExternalStorageDirectorySafely(),
    ): List<File> {
        val files = mutableListOf<File>()
        if (entity.filePath.isNotBlank()) {
            val mainFile = resolveFile(entity.filePath, rootDir)
            if (entity.mediaType.equals(DownloadMediaType.IMAGE, ignoreCase = true)) {
                files.addAll(findImageSetFiles(mainFile))
            } else {
                files.add(mainFile)
            }
        }
        if (entity.coverPath.isNotBlank()) {
            val coverFile = resolveFile(entity.coverPath, rootDir)
            if (coverFile !in files) {
                files.add(coverFile)
            }
        }
        return files
    }

    /**
     * 扫描下载目录下所有孤儿媒体文件（在磁盘上存在，但数据库中没有任何记录引用该文件）。
     *
     * 扫描范围包含：
     * - `Download/bDouyin/videos`
     * - `Download/bDouyin/images`
     * - `Download/bDouyin/covers`
     *
     * 自动排除 `.nomedia` 等系统标记文件。
     */
    fun findOrphanMediaFiles(
        allEntities: Collection<DownloadedVideoEntity>,
        rootDir: File = getExternalStorageDirectorySafely(),
    ): List<OrphanMediaFile> {
        val validPaths = HashSet<String>()
        val validRelativeKeys = HashSet<String>()

        for (entity in allEntities) {
            val files = resolveEntityFiles(entity, rootDir)
            for (f in files) {
                validPaths.add(f.absolutePath)
                runCatching { f.canonicalPath }.getOrNull()?.let { validPaths.add(it) }
                normalizeRelativeKey(f.absolutePath)?.let { validRelativeKeys.add(it) }
            }
            normalizeRelativeKey(entity.filePath)?.let { validRelativeKeys.add(it) }
            normalizeRelativeKey(entity.coverPath)?.let { validRelativeKeys.add(it) }
        }

        val downloadsDir = File(rootDir, "Download")
        val targets = listOf(
            File(downloadsDir, BatchDownloadCoordinator.VIDEO_SUBDIR) to OrphanMediaKind.VIDEO,
            File(downloadsDir, BatchDownloadCoordinator.IMAGE_SUBDIR) to OrphanMediaKind.IMAGE,
            File(downloadsDir, BatchDownloadCoordinator.COVER_SUBDIR) to OrphanMediaKind.COVER,
        )

        val orphans = mutableListOf<OrphanMediaFile>()

        for ((dir, kind) in targets) {
            if (!dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile) continue
                if (file.name.equals(".nomedia", ignoreCase = true)) continue

                if (!isFileReferenced(file, validPaths, validRelativeKeys)) {
                    val relPath = file.absolutePath
                        .substringAfter(rootDir.absolutePath)
                        .trimStart(File.separatorChar, '/')
                    orphans.add(
                        OrphanMediaFile(
                            file = file,
                            name = file.name,
                            relativePath = relPath,
                            sizeBytes = runCatching { file.length() }.getOrDefault(0L),
                            mediaKind = kind,
                        )
                    )
                }
            }
        }

        return orphans.sortedByDescending { runCatching { it.file.lastModified() }.getOrDefault(0L) }
    }

    internal fun isFileReferenced(
        file: File,
        validPaths: Set<String>,
        validRelativeKeys: Set<String>,
    ): Boolean {
        val absolute = file.absolutePath
        if (absolute in validPaths) return true
        val canonical = runCatching { file.canonicalPath }.getOrNull()
        if (canonical != null && canonical in validPaths) return true

        val relativeKey = normalizeRelativeKey(absolute)
        if (relativeKey != null) {
            if (relativeKey in validRelativeKeys) return true

            // 容错处理：历史记录若文件名超长（>255字节），部分系统底层落盘时可能将其按字节截断，
            // 导致数据库中的长路径与磁盘上的截断文件名无法完全匹配。
            // 若同子目录下存在以磁盘文件名（不含扩展名）为前缀的记录，且前缀长度足够长（>=30字符），则视为同一文件引用。
            val scannedDirAndPrefix = relativeKey.substringBeforeLast('.', "")
            if (scannedDirAndPrefix.length >= 30) {
                val hasPrefixMatch = validRelativeKeys.any { validKey ->
                    validKey.startsWith(scannedDirAndPrefix)
                }
                if (hasPrefixMatch) return true
            }
        }

        return false
    }

    internal fun normalizeRelativeKey(path: String): String? {
        if (path.isBlank()) return null
        val normalized = path.replace('\\', '/')
        val idx = normalized.indexOf("bDouyin/", ignoreCase = true)
        return if (idx >= 0) {
            normalized.substring(idx).trimEnd('/').lowercase()
        } else {
            null
        }
    }

    /**
     * 删除指定的文件集合：
     * 1. 尝试直接通过 [File.delete] 删除。
     * 2. 若文件仍存在（如 Scoped Storage 限制），尝试通过 [MediaStore] 的 [android.content.ContentResolver] 删除。
     * 3. 无论原先文件是否存在，均批量请求 [MediaScannerConnection.scanFile]，让系统媒体库及时移除索引。
     *
     * @return 成功删除的文件数量。
     */
    fun deleteMediaFiles(context: Context?, files: Collection<File>): Int {
        if (files.isEmpty()) return 0
        var deletedCount = 0
        val pathsToRescan = mutableListOf<String>()

        for (file in files.distinct()) {
            val existed = runCatching { file.exists() }.getOrDefault(false)
            var deleted = false

            if (existed) {
                deleted = try {
                    file.delete()
                } catch (e: Exception) {
                    safeLogW("Direct file delete failed: ${file.absolutePath}", e)
                    false
                }
            }

            if (!deleted && (runCatching { file.exists() }.getOrDefault(false)) && context != null) {
                deleted = deleteViaContentResolver(context, file)
                if (!deleted && (runCatching { file.exists() }.getOrDefault(false))) {
                    try {
                        deleted = file.delete()
                    } catch (_: Exception) { }
                }
            }

            if (deleted || !(runCatching { file.exists() }.getOrDefault(false))) {
                deletedCount++
            }

            pathsToRescan.add(file.absolutePath)
        }

        if (pathsToRescan.isNotEmpty() && context != null) {
            try {
                MediaScannerConnection.scanFile(
                    context.applicationContext,
                    pathsToRescan.toTypedArray(),
                    null,
                    null,
                )
            } catch (e: Exception) {
                safeLogW("MediaScannerConnection.scanFile failed", e)
            }
        }

        return deletedCount
    }

    private fun deleteViaContentResolver(context: Context, file: File): Boolean {
        return try {
            val resolver = context.contentResolver
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(MediaStore.Files.FileColumns._ID)
            val selection = "${MediaStore.Files.FileColumns.DATA} = ?"
            val selectionArgs = arrayOf(file.absolutePath)
            var count = 0
            resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                if (idColumn >= 0) {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val itemUri = ContentUris.withAppendedId(uri, id)
                        count += try {
                            resolver.delete(itemUri, null, null)
                        } catch (e: Exception) {
                            safeLogW("resolver.delete failed for $itemUri", e)
                            0
                        }
                    }
                }
            }
            count > 0 || !(runCatching { file.exists() }.getOrDefault(false))
        } catch (e: Exception) {
            safeLogW("deleteViaContentResolver failed for ${file.absolutePath}", e)
            false
        }
    }

    private fun getExternalStorageDirectorySafely(): File {
        return try {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory()
        } catch (_: Throwable) {
            File("/")
        }
    }

    private fun safeLogW(message: String, throwable: Throwable? = null) {
        try {
            if (throwable != null) {
                Log.w(TAG, message, throwable)
            } else {
                Log.w(TAG, message)
            }
        } catch (_: Throwable) {
            // JVM unit test stub defense
        }
    }
}

/** 孤儿媒体文件类型。 */
enum class OrphanMediaKind {
    VIDEO,
    IMAGE,
    COVER,
}

/**
 * 孤儿媒体文件描述信息。
 */
data class OrphanMediaFile(
    val file: File,
    val name: String,
    val relativePath: String,
    val sizeBytes: Long,
    val mediaKind: OrphanMediaKind,
)

