package com.blitz.downloader.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.system.Os
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import com.blitz.downloader.model.CameraMoveOutcome
import com.blitz.downloader.model.CameraOrganizationPhase
import com.blitz.downloader.model.CameraOrganizationState
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 进程内单批次文件任务，不持有页面；进程重启后绝不恢复移动。 */
class CameraVideoOrganizer(context: Context) {
    private val context = context.applicationContext
    private val preferences = context.getSharedPreferences("camera_video_organization", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun tree(source: Boolean): Uri? = preferences.getString(if (source) "sourceTree" else "downloadTree", null)?.let(Uri::parse)

    fun hasTree(source: Boolean): Boolean {
        val uri = tree(source) ?: return false
        return SafCameraVideoStorage.validTree(uri, source) && context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    fun saveTree(uri: Uri, source: Boolean) {
        require(SafCameraVideoStorage.validTree(uri, source)) { "请选择主存储的 ${if (source) "DCIM/Camera" else "Download"} 目录" }
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (!preferences.edit().putString(if (source) "sourceTree" else "downloadTree", uri.toString()).commit()) throw IOException("无法保存目录授权")
    }

    fun hasAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT == 29 -> hasTree(true) && hasTree(false)
        else -> legacyPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    private val journal = object : CameraTemporaryJournal {
        override fun add(id: String) = update(id, true)
        override fun remove(id: String) = update(id, false)
        private fun update(id: String, add: Boolean) {
            val ids = preferences.getStringSet("temporary", emptySet()).orEmpty().toMutableSet()
            if (add) ids.add(id) else ids.remove(id)
            if (!preferences.edit().putStringSet("temporary", ids).commit()) throw IOException("无法记录临时文件状态")
        }
    }

    private fun leftovers() = preferences.getStringSet("temporary", emptySet()).orEmpty().sorted()

    private val runner = CameraOrganizationRunner(scope, ::createStorage, journal, ::leftovers, ::refreshMedia)
    val state get() = runner.state
    fun scan() = runner.scan()
    fun cancelPreview(id: String) = runner.cancelPreview(id)
    fun confirm(id: String) = runner.confirm(id)
    @Suppress("DEPRECATION")
    private fun createStorage(): CameraVideoStorage {
        if (!hasAccess()) throw SecurityException("请先授予目录读写权限")
        if (Build.VERSION.SDK_INT == 29) return SafCameraVideoStorage(context, tree(true)!!, tree(false)!!)
        return DirectCameraVideoStorage(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "history"),
            mime = { file -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT)) },
            access = { if (!hasAccess()) throw SecurityException("存储权限已撤回，整理已停止") },
            link = { from, to -> runCatching { Os.link(from.path, to.path); true }.getOrDefault(false) },
        )
    }

    @Suppress("DEPRECATION")
    private fun refreshMedia(sourceName: String, destination: String, moved: Boolean) {
        val target = if (File(destination).isAbsolute) destination else File(Environment.getExternalStorageDirectory(), destination).path
        val paths = buildList {
            add(target)
            if (moved) add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera/$sourceName").path)
        }
        val pending = CountDownLatch(paths.size)
        val indexed = ConcurrentHashMap<String, Boolean>()
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { path, uri ->
            indexed[path] = uri != null
            pending.countDown()
        }
        if (!pending.await(20, TimeUnit.SECONDS)) throw IOException("等待系统扫描超时")
        // 已有 .nomedia 的目标允许不返回媒体 URI；不改变用户的相册隐藏设置。
        val hidden = generateSequence(File(target).parentFile) { it.parentFile }.any { File(it, ".nomedia").exists() }
        if (indexed[target] != true && !hidden) throw IOException("目标尚未进入媒体库")
    }

    companion object {
        val legacyPermissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}
