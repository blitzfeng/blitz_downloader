package com.blitz.downloader.download

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.blitz.downloader.data.db.DownloadedVideoEntity
import com.blitz.downloader.model.MediaOrientation
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 管理页「导出选中」的共用核心：把选中的 [DownloadedVideoEntity] 解析为磁盘上的实际文件，
 * 供两条导出路径复用：
 * - ZIP 导出（[exportToZip]）：打包到 `Download/bDouyin/export/`，手机连电脑后拷这一个文件。
 * - 局域网导出（[com.blitz.downloader.net.LanFileServer]）：把 [resolveExportFiles] 的结果挂到内嵌 HTTP 服务。
 *
 * 文件解析规则与 [com.blitz.downloader.activity.ImageViewerActivity.findImageSet] 保持一致：
 * - 视频：`filePath` 指向的单个 mp4。
 * - 图集：`filePath` 只存了第一张（`base_01.jpg`），需扫描同目录下 `base_\d+.<ext>` 的全部兄弟文件。
 *
 * 所有磁盘操作**必须在 IO 线程调用**。
 */
object MediaExportManager {

    /** 导出根目录：`Download/bDouyin/export`。 */
    const val EXPORT_SUBDIR: String = "${BatchDownloadCoordinator.RELATIVE_DOWNLOAD_SUBDIR}/export"

    private val ZIP_NAME_TIME_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private const val COPY_BUFFER = 64 * 1024

    /**
     * 待导出的单个文件：磁盘文件 + 在 ZIP / URL 中使用的（去重后）展示名 +
     * 它所属记录的 [DownloadedVideoEntity.awemeId]。
     *
     * [awemeId] 是导出计数的回溯键：局域网导出传输完成后据它给
     * `downloaded_videos.exportCount` 累加。图集会解析成多个 [ExportFile]，
     * 它们共享同一个 [awemeId]（计数按记录算，不按文件算）。
     */
    data class ExportFile(
        val file: File,
        val entryName: String,
        val awemeId: String,
        /**
         * 所属记录的画面方向，由 [DownloadedVideoEntity.mediaWidth] / `mediaHeight` 派生。
         *
         * 只有局域网导出的分包用它（[com.blitz.downloader.net.LanFileServer]）；
         * ZIP 导出拿到但不使用。图集解析出的多个文件**共享所属记录的方向**——
         * 图片 Tab 不参与分包，无需逐张判定。
         *
         * 宽高为 0（未探测 / 探测失败）时按 [MediaOrientation.PORTRAIT] 处理。
         */
        val orientation: MediaOrientation,
    )

    /** ZIP 导出结果。 */
    data class ZipResult(val zipFile: File, val fileCount: Int)

    /** 公共 `Download/bDouyin/export` 目录（不保证存在）。 */
    fun exportRootDir(): File {
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloads, EXPORT_SUBDIR)
    }

    /**
     * 把选中记录解析为磁盘上实际存在的文件列表；`filePath` 为空或文件已被删除的项自动跳过。
     * [ExportFile.entryName] 已做同名去重（追加 `_2` / `_3` …），可直接用作 ZIP 条目名或下载文件名。
     *
     * [ExportFile.orientation] 直接由记录的 `mediaWidth` / `mediaHeight` 派生，**本函数不做探测 IO**。
     * 需要准确方向的调用方（局域网导出）应在调用前完成宽高回填。
     */
    fun resolveExportFiles(entities: List<DownloadedVideoEntity>): List<ExportFile> {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory()
        val usedNames = HashSet<String>()
        val out = ArrayList<ExportFile>()
        for (e in entities) {
            if (e.filePath.isBlank()) continue
            val first = File(root, e.filePath)
            val files = if (e.mediaType.equals("image", ignoreCase = true)) findImageSet(first) else listOf(first)
            // 方向只读 entity 字段，不在这里做探测 IO——回填是调用方（ManageViewModel）的职责。
            val orientation = MediaOrientation.of(e.mediaWidth, e.mediaHeight)
            for (f in files) {
                if (!f.isFile) continue
                out.add(ExportFile(f, uniqueName(f.name, usedNames), e.awemeId, orientation))
            }
        }
        return out
    }

    /**
     * 把选中记录打包成一个 ZIP，落到 `Download/bDouyin/export/bDouyin_export_<时间戳>.zip`。
     * 视频/图片本身已是压缩格式，ZIP 仅做「打包」不再二次压缩（[Deflater.NO_COMPRESSION]），省 CPU 且更快。
     *
     * @param onProgress 进度回调 `(已完成文件数, 文件总数)`，在 IO 线程触发，UI 层需自行切主线程。
     * @throws IllegalStateException 选中项在磁盘上均已不存在时抛出。
     */
    @Throws(Exception::class)
    fun exportToZip(
        context: Context,
        entities: List<DownloadedVideoEntity>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ZipResult {
        val files = resolveExportFiles(entities)
        if (files.isEmpty()) {
            throw IllegalStateException("没有可导出的文件（本地文件可能已被删除）")
        }

        val dir = exportRootDir()
        if (!dir.exists() && !dir.mkdirs()) {
            throw java.io.IOException("无法创建导出目录: ${dir.absolutePath}")
        }

        val zipFile = File(dir, "bDouyin_export_${ZIP_NAME_TIME_FORMAT.format(Date(System.currentTimeMillis()))}.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            zos.setLevel(Deflater.NO_COMPRESSION)
            files.forEachIndexed { index, ef ->
                onProgress(index, files.size)
                zos.putNextEntry(ZipEntry(ef.entryName))
                FileInputStream(ef.file).use { input -> input.copyTo(zos, COPY_BUFFER) }
                zos.closeEntry()
            }
        }
        onProgress(files.size, files.size)

        // 触发媒体扫描，确保 ZIP 立刻能通过 MTP（手机连电脑）看到，而不必等系统下次重扫。
        runCatching {
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(zipFile.absolutePath),
                arrayOf("application/zip"),
                null,
            )
        }
        return ZipResult(zipFile, files.size)
    }

    // ── 私有辅助 ───────────────────────────────────────────────────────────────

    /**
     * 图集兄弟枚举，扫描规则与 [com.blitz.downloader.activity.ImageViewerActivity.findImageSet] 一致：
     * 去掉第一张图文件名末尾 `_\d+` 得到基础名，扫描同目录、匹配 `base_\d+` 的全部文件，按名升序。
     * 找不到兄弟（如单张图片）时退回该文件本身。
     *
     * **实况图**：导出要把静态封面（`base_NN.webp`）与动图本体（`base_NN.mp4`）**都打包**，
     * 所以这里不再限定「同封面扩展名」，而是收全封面图片扩展（webp/jpg/jpeg/png）+ mp4。
     * 浏览页的同名方法只需知道每张封面**是否**有 mp4 兄弟，聚合形态不同、扫描口径相同。
     */
    private fun findImageSet(firstFile: File): List<File> {
        val dir = firstFile.parentFile ?: return listOf(firstFile)
        val baseName = firstFile.nameWithoutExtension.replace(Regex("_\\d+$"), "")
        val pattern = Regex("^${Regex.escape(baseName)}_\\d+$")
        val exts = setOf("webp", "jpg", "jpeg", "png", "mp4")
        val files = dir.listFiles { f ->
            f.isFile &&
                f.extension.lowercase() in exts &&
                f.nameWithoutExtension.matches(pattern)
        }
        return if (files.isNullOrEmpty()) listOf(firstFile) else files.sortedBy { it.name }
    }

    /** 保证展示名在一次导出内唯一：同名时在扩展名前追加 `_2` / `_3` …。 */
    private fun uniqueName(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (true) {
            val candidate = "${stem}_$i$ext"
            if (used.add(candidate)) return candidate
            i++
        }
    }
}
