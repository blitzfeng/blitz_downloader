package com.blitz.downloader.data

import android.content.Context
import android.net.Uri
import com.blitz.downloader.model.RecoveryNames
import com.blitz.downloader.util.DownloadedMediaFileManager
import java.io.File

class DownloadedMediaReadResolver(private val ctx: Context) {
    private val access = (ctx.applicationContext as com.blitz.downloader.BlitzApp).mediaRecovery.access
    enum class Status { READABLE, MISSING, UNAVAILABLE }
    fun model(path: String): Any {
        val file = if (path.startsWith(RecoveryNames.root + "/") && access.canUseFile())
            runCatching { access.local(path) }.getOrElse { return File("") }
        else DownloadedMediaFileManager.resolveFile(path)
        if (file.canRead() || !path.startsWith(RecoveryNames.root + "/")) return file
        return if (access.tree != null) runCatching { access.uri(path) }.getOrDefault(file) else file
    }
    fun status(path: String): Status {
        if (path.isBlank()) return Status.UNAVAILABLE
        val ref = model(path)
        if (ref is File && ref.isFile && ref.canRead()) return Status.READABLE
        if (ref is Uri && try { ctx.contentResolver.openFileDescriptor(ref,"r")?.use { true } == true }
            catch (_: Exception) { false }) return Status.READABLE
        return if (access.definitelyMissing(path)) Status.MISSING else Status.UNAVAILABLE
    }
}
