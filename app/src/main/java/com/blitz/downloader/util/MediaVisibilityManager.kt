package com.blitz.downloader.util

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.util.Log
import com.blitz.downloader.download.BatchDownloadCoordinator
import java.io.File
import java.io.IOException

/**
 * 「对系统相册隐藏下载目录」的开关实现。
 *
 * ## 机制（两件事必须同时成立，缺一个就出事）
 *
 * 1. **`.nomedia` 让相册看不到**。注意仅仅把文件写进 `MediaStore.Downloads` 集合**不足以**避开
 *    相册：MediaStore 只是把 `is_download` 置 1，`media_type` 仍按 MIME 判成 IMAGE/VIDEO，
 *    任何查 `MediaStore.Images` / `MediaStore.Video` 的相册 App 照样列得出来。真正让它隐身的是
 *    目录里的 `.nomedia`——扫描器发现目录已隐藏，会把这些行的 `media_type` 置为 NONE(0)。
 *
 * 2. **[hasAllFilesAccess] 让本 App 仍然读得到**。本 App 读媒体走的是**直接文件路径**
 *    （`ManageGridAdapter` / `ImageViewerActivity` / `VideoPlayerActivity` / `LanFileServer` 都是），
 *    这条路在 Android 11+ 由 MediaProvider 的 FUSE 层把关：没有 `MANAGE_EXTERNAL_STORAGE` 时靠
 *    `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`，而**这两个权限只覆盖"仍算媒体"的文件**。
 *    一旦 `media_type` 变成 NONE，它们就不再覆盖，只剩 owner 应用能访问——而
 *    `owner_package_name` 会在包被移除时由 MediaProvider 的 `onPackageOrphaned` 清空
 *    （**固定签名只能防止将来再丢，追不回已经丢掉的 owner**）。
 *
 * 也就是说：**没有所有文件访问权限就加 `.nomedia`，等于把自己的媒体锁死**，管理页封面、
 * 图集浏览、视频播放会集体变成占位图或打不开，而且**没有任何报错**。这不是推测，是在真机上
 * 复现并验证过的：同一目录下 owner 非空的封面照常显示，owner 为 NULL 的全变占位图。
 *
 * 所以 [setHidden] 的「开启」方向由调用方负责先确认 [hasAllFilesAccess]；「关闭」方向永远放行，
 * 那是用户的自救出口。
 */
object MediaVisibilityManager {

    private const val TAG = "MediaVisibility"

    private const val NO_MEDIA = ".nomedia"

    /** 可切换相册可见性的下载目录。 */
    enum class MediaFolder(private val subDir: String) {
        VIDEOS(BatchDownloadCoordinator.VIDEO_SUBDIR),
        IMAGES(BatchDownloadCoordinator.IMAGE_SUBDIR),

        /**
         * 封面目录**没有** UI 开关，由 [ensureCoversHidden] 按权限自动维护：
         * 它是纯内部产物，出现在相册里纯属噪音。
         */
        COVERS(BatchDownloadCoordinator.COVER_SUBDIR),
        ;

        fun dir(): File = BatchDownloadCoordinator.downloadsSubDir(subDir)
    }

    /**
     * 是否已拿到「所有文件访问权限」。
     *
     * API < 30 一律返回 false：那里没有这个权限，且本 App 也没有 `requestLegacyExternalStorage`，
     * 于是隐藏功能在老系统上直接不可用（宁可不隐藏，也不能把媒体锁死）。
     */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** 该目录当前是否对相册隐藏（即是否存在 `.nomedia`）。磁盘就是唯一事实来源，不另存偏好。 */
    fun isHidden(folder: MediaFolder): Boolean = File(folder.dir(), NO_MEDIA).exists()

    /**
     * 切换某个目录的相册可见性。
     *
     * @return 本次调用是否真的改变了状态（已经是目标状态、或操作失败都返回 false）。
     */
    fun setHidden(context: Context, folder: MediaFolder, hidden: Boolean): Boolean {
        val dir = folder.dir()
        val marker = File(dir, NO_MEDIA)
        val changed = if (hidden) createMarker(dir, marker) else deleteMarker(marker)
        // 改完必须重扫：MediaStore 里已有的行是下载时主动 insert 进去的，
        // MediaProvider 不会自己回头复查，不扫就只有以后新下载的文件受影响。
        if (changed) rescan(context, dir)
        return changed
    }

    /**
     * 封面目录跟着权限走：有权限就隐藏，没权限就必须恢复可见。
     *
     * 与 [MediaFolder.VIDEOS] / [MediaFolder.IMAGES] 的处理**刻意不同**——那两个有设置页开关，
     * 用户撤权后能自己关掉；封面没有任何 UI，只能在这里自愈，否则用户会遇到「封面全没了、
     * 设置里还找不到地方改」的死局。
     */
    fun ensureCoversHidden(context: Context) {
        if (hasAllFilesAccess()) {
            setHidden(context, MediaFolder.COVERS, true)
        } else if (isHidden(MediaFolder.COVERS)) {
            Log.i(TAG, "no all-files access, un-hiding covers to keep them readable")
            setHidden(context, MediaFolder.COVERS, false)
        }
    }

    /** 有开关的两个目录里，当前处于隐藏状态的那些（用于缺权限时的告警）。 */
    fun hiddenSwitchableFolders(): List<MediaFolder> =
        listOf(MediaFolder.VIDEOS, MediaFolder.IMAGES).filter { isHidden(it) }

    private fun createMarker(dir: File, marker: File): Boolean {
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "mkdirs failed: ${dir.absolutePath}")
            return false
        }
        if (marker.exists()) return false
        return try {
            marker.createNewFile()
        } catch (e: IOException) {
            Log.w(TAG, "create .nomedia failed: ${marker.absolutePath}", e)
            false
        }
    }

    private fun deleteMarker(marker: File): Boolean =
        if (!marker.exists()) false else marker.delete()

    /** 重扫目录内的普通文件（不含 `.nomedia` 自身），让它们的 `media_type` 跟上当前隐藏状态。 */
    private fun rescan(context: Context, dir: File) {
        val paths = dir.listFiles { f -> f.isFile && f.name != NO_MEDIA }
            ?.map { it.absolutePath }
            ?.toTypedArray()
            ?: return
        if (paths.isEmpty()) return
        Log.i(TAG, "rescan ${paths.size} file(s) under ${dir.absolutePath}")
        MediaScannerConnection.scanFile(context.applicationContext, paths, null, null)
    }
}
