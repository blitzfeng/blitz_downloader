package com.blitz.downloader.data

import com.blitz.downloader.model.CameraMoveOutcome
import com.blitz.downloader.model.CameraMoveResult
import com.blitz.downloader.model.CameraVideoCandidate
import com.blitz.downloader.model.CameraVideoRules
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** 存储适配只允许访问固定来源及目标；创建操作必须拒绝覆盖。 */
interface CameraVideoStorage {
    fun checkAccess()
    fun scan(): List<CameraVideoCandidate>
    fun snapshot(id: String): CameraVideoCandidate?
    fun prepareDestination(candidate: CameraVideoCandidate)
    fun createTemporary(): String
    fun read(id: String): InputStream
    fun write(id: String): OutputStream
    fun publish(temporary: String, name: String, mime: String, record: (String) -> Unit): String
    fun delete(id: String): Boolean
    fun displayPath(id: String): String
}

interface CameraTemporaryJournal {
    fun add(id: String)
    fun remove(id: String)
}

/** 复制、回读校验、提交、再次校验源，最后删除源。失败不牺牲原文件。 */
class CameraVideoMover(
    private val storage: CameraVideoStorage,
    private val journal: CameraTemporaryJournal,
) {
    fun move(candidate: CameraVideoCandidate): CameraMoveResult {
        val owned = mutableListOf<String>()
        var committed: String? = null
        var preserveCommitted = false
        fun record(id: String) {
            owned += id
            journal.add(id)
        }
        fun unchanged() = storage.snapshot(candidate.id) == candidate
        try {
            storage.checkAccess()
            if (!CameraVideoRules.isCandidate(candidate.name, candidate.mime) ||
                candidate.size <= 0 || candidate.modified <= 0 || !unchanged()
            ) return CameraMoveResult(candidate.name, CameraMoveOutcome.SKIPPED, detail = "文件已变化、为空或无法确认状态，请重新扫描")

            storage.prepareDestination(candidate)
            val temporary = storage.createTemporary()
            record(temporary)
            val originalDigest = storage.read(candidate.id).use { input ->
                copyDigest(input, storage.write(temporary))
            }
            val copiedDigest = digest(storage.read(temporary))
            if (originalDigest != copiedDigest || originalDigest.first != candidate.size) {
                throw IOException("复制校验失败，原文件已保留")
            }
            if (!unchanged()) return CameraMoveResult(candidate.name, CameraMoveOutcome.SKIPPED, detail = "复制期间源文件发生变化，原文件已保留")

            committed = storage.publish(temporary, candidate.name, candidate.mime, ::record)
            if (digest(storage.read(committed)) != originalDigest) throw IOException("目标校验失败，原文件已保留")
            if (!unchanged()) return CameraMoveResult(candidate.name, CameraMoveOutcome.SKIPPED, detail = "提交期间源文件发生变化，原文件已保留")

            // 从这里开始，正式副本绝不作为临时文件清理。崩溃也不会自动重试删除源。
            preserveCommitted = true
            journal.remove(committed)
            if (!storage.delete(candidate.id)) {
                return CameraMoveResult(candidate.name, CameraMoveOutcome.FAILED, storage.displayPath(committed), "源文件无法删除，完整目标副本已保留")
            }
            return CameraMoveResult(candidate.name, CameraMoveOutcome.SUCCESS, storage.displayPath(committed))
        } catch (e: Exception) {
            return CameraMoveResult(
                candidate.name, CameraMoveOutcome.FAILED,
                committed?.takeIf { preserveCommitted }?.let(storage::displayPath),
                e.message ?: "文件操作失败，原文件已保留",
            )
        } finally {
            owned.filterNot { preserveCommitted && it == committed }.forEach { id ->
                // 清理只针对本次独占创建的文件；未能清理的身份继续保留，供界面提示。
                runCatching { if (storage.delete(id)) journal.remove(id) }
            }
        }
    }

    companion object {
        private fun copyDigest(input: InputStream, output: OutputStream): Pair<Long, String> =
            output.use { target -> hash(input) { bytes, count -> target.write(bytes, 0, count) } }

        private fun digest(input: InputStream): Pair<Long, String> = hash(input) { _, _ -> }

        private fun hash(input: InputStream, consume: (ByteArray, Int) -> Unit): Pair<Long, String> = input.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(128 * 1024)
            var total = 0L
            while (true) {
                val count = it.read(bytes)
                if (count < 0) break
                if (count == 0) continue
                consume(bytes, count)
                digest.update(bytes, 0, count)
                total += count
            }
            total to digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
