package com.blitz.downloader.data

import com.blitz.downloader.model.CameraVideoCandidate
import com.blitz.downloader.model.CameraVideoRules
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** 不依赖 Android 的文件适配，便于用真实临时文件验证不覆盖及失败行为。 */
class DirectCameraVideoStorage(
    private val source: File,
    private val videoTarget: File,
    private val mime: (File) -> String?,
    private val access: () -> Unit = {},
    private val link: ((File, File) -> Boolean)? = null,
) : CameraVideoStorage {
    private val imageTarget = File(videoTarget.parentFile, "history_img")
    private var target = videoTarget
    private fun validateDirectories() {
        if (listOf(source, videoTarget, imageTarget).any { it.canonicalFile != it.absoluteFile }) {
            throw IOException("目录包含链接，无法确认整理范围")
        }
    }

    private fun checked(id: String): File {
        validateDirectories()
        val file = File(id)
        if (file.canonicalFile != file.absoluteFile ||
            (file.parentFile != source && file.parentFile != videoTarget && file.parentFile != imageTarget)) {
            throw IOException("文件不在允许的直接子目录中")
        }
        return file
    }

    override fun checkAccess() { access(); validateDirectories() }

    override fun scan(): List<CameraVideoCandidate> {
        checkAccess()
        if (!source.exists()) return emptyList()
        val files = source.listFiles() ?: throw IOException("无法读取 DCIM/Camera")
        return files.mapNotNull { file ->
            if (!file.isFile || file.canonicalFile != file.absoluteFile) null else snapshot(file.absolutePath)
        }.filter { CameraVideoRules.isCandidate(it.name, it.mime) }.sortedBy { it.name }
    }

    override fun snapshot(id: String): CameraVideoCandidate? {
        val file = checked(id)
        if (!file.isFile) return null
        return CameraVideoCandidate(id, file.name, file.length(), file.lastModified(), mime(file).orEmpty())
    }

    override fun prepareDestination(candidate: CameraVideoCandidate) {
        target = when (CameraVideoRules.destinationFolder(candidate.mime)) {
            "history" -> videoTarget
            "history_img" -> imageTarget
            else -> throw IOException("不支持的媒体类型")
        }
        checkAccess()
        if (!target.isDirectory && !target.mkdirs()) throw IOException("无法创建 Download/${target.name}")
        validateDirectories()
        if (!target.canWrite()) throw IOException("Download/${target.name} 不可写")
    }

    override fun createTemporary(): String {
        val file = File(target, ".blitz-camera-${UUID.randomUUID()}.partial")
        if (!file.createNewFile()) throw IOException("无法创建临时文件")
        return file.absolutePath
    }

    override fun read(id: String) = checked(id).inputStream()
    override fun write(id: String): FileOutputStream {
        val file = checked(id)
        require(file.parentFile == target)
        return object : FileOutputStream(file) {
            override fun close() {
                try { fd.sync() } finally { super.close() }
            }
        }
    }

    override fun publish(temporary: String, name: String, mime: String, record: (String) -> Unit): String {
        val temp = checked(temporary)
        while (true) {
            val file = File(target, CameraVideoRules.availableName(name) { File(target, it).exists() })
            // 硬链接为原子不覆盖提交；公共存储不支持时退化为独占创建并复制。
            if (link?.invoke(temp, file) == true) {
                record(file.absolutePath)
                return file.absolutePath
            }
            if (!file.createNewFile()) continue
            record(file.absolutePath)
            read(temporary).use { input -> write(file.absolutePath).use { input.copyTo(it) } }
            return file.absolutePath
        }
    }

    override fun delete(id: String): Boolean {
        val file = checked(id)
        return !file.exists() || (file.isFile && file.delete())
    }

    override fun displayPath(id: String) = checked(id).absolutePath
}
