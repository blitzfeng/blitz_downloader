package com.blitz.downloader.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import com.blitz.downloader.data.db.RecoveryFile
import com.blitz.downloader.model.RecoveryDetail
import com.blitz.downloader.model.RecoveryInputs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs

data class MatchChoice(val inputs: RecoveryInputs, val state: String, val method: String, val reason: String)

class MediaRecoveryMatcher(private val access: RecoveryMediaAccess, files: List<RecoveryFile>) {
    private val free = files.filterNot { it.referenced }
    private val media = free.filter { it.kind in setOf("VIDEO","IMAGE","LIVE") }
    private val byId = media.filter { it.awemeId != null }.groupBy { it.awemeId }
    private val byGroup = media.groupBy { it.groupKey }
    private val videoGroupSizes = files.filter { it.kind == "VIDEO" }.groupingBy { it.groupKey }.eachCount()
    private val covers = files.filter { it.kind == "COVER" }.groupBy { it.groupKey }
    private val evidence = files.filter { it.kind == "EVIDENCE" }.groupBy { it.awemeId }
    private val hashes = HashMap<String, Long?>()
    private val durations = HashMap<String, Long>()
    private val frameCache = android.util.LruCache<String,List<Long>>(512)
    private val coversByHash by lazy { covers.values.flatten().mapNotNull { f -> imageHash(f)?.let { f to it } } }

    suspend fun choices(detail: RecoveryDetail): List<MatchChoice> {
        val direct = byId[detail.awemeId].orEmpty()
        if (direct.isNotEmpty()) {
            val albums = direct.filter { it.kind != "VIDEO" }.groupBy { it.groupKey }
            val videos = direct.filter { it.kind == "VIDEO" }
            val ambiguous = albums.size + videos.size > 1
            return videos.map { file ->
                val valid = !ambiguous && detail.mediaType == "video" && durationFits(file, detail)
                candidate(file, if (valid) "READY" else "REVIEW", "文件名 ID", if (valid) "作品 ID、媒体类型与时长一致" else "有多个副本或类型、时长待核对")
            } + albums.values.map { group ->
                val ordered = group.sortedWith(compareBy<RecoveryFile> { it.ordinal ?: Int.MAX_VALUE }.thenBy { it.kind }.thenBy { it.logicalPath })
                val stills = ordered.filter { it.kind == "IMAGE" }
                val main = stills.firstOrNull() ?: ordered.first()
                MatchChoice(RecoveryInputs(main.logicalPath, ordered.map { it.logicalPath }, stills.firstOrNull()?.logicalPath),
                    "REVIEW","图集文件名","请核对图集顺序、缺页、重复图片及实况视频")
            }
        }
        val frames = evidence[detail.awemeId].orEmpty()
        val coverFrame = frames.firstOrNull { it.ordinal == 0 }
        val ref = coverFrame?.let(::imageHash)
        if (ref != null) {
            val ranked = coversByHash.map { (file, hash) -> file to java.lang.Long.bitCount(ref xor hash) }.sortedBy { it.second }
            val top = ranked.firstOrNull()
            if (top != null && top.second <= 8) {
                val separated = (ranked.getOrNull(1)?.second ?: 65) - top.second >= 6
                val plausible = ranked.takeWhile { it.second <= 8 && it.second - top.second < 6 }
                val choices = plausible.flatMap { (cover,distance) ->
                    byGroup[cover.groupKey].orEmpty().filter { it.kind == "VIDEO" && it.awemeId == null }.map { file ->
                        val ready = separated && videoGroupSizes[cover.groupKey] == 1 && detail.mediaType == "video" && durationFits(file,detail)
                        val item = candidate(file, if (ready) "READY" else "REVIEW", "本地封面", "封面哈希距离 $distance，请对照证据图")
                        item.copy(inputs = item.inputs.copy(cover = cover.logicalPath,evidence = listOf(coverFrame.logicalPath)))
                    }
                }.distinctBy { it.inputs.main }
                if (choices.isNotEmpty()) return choices
            }
        }
        val shortlist = media.asSequence().filter { it.kind == "VIDEO" && it.awemeId == null &&
            it.groupKey.contains(detail.author) && detail.description.take(8).let { hint -> hint.isNotBlank() && it.groupKey.contains(hint) }
        }.take(6).toList()
        if (shortlist.isEmpty() || shortlist.size > 5 || detail.mediaType != "video") return emptyList()
        val proofs = frames.filter { it.ordinal != 0 }.mapNotNull { f -> imageHash(f)?.let { it to f } }.distinctBy { it.first }
        val ranked = shortlist.map { file ->
            currentCoroutineContext().ensureActive()
            val samples = if (proofs.size >= 2) sample(file) else emptyList()
            file to com.blitz.downloader.model.framePairScore(proofs.map { it.first },samples)
        }.sortedBy { it.second }
        return ranked.mapIndexed { i, (file, score) ->
            val ok = i == 0 && score <= 16 && (ranked.getOrNull(1)?.second ?: 129) - score >= 12 && durationFits(file,detail)
            val item = candidate(file, if (ok) "READY" else "REVIEW", "本地抽帧", if (ok) "两张独立证据帧匹配" else "证据不足，请人工预览")
            item.copy(inputs = item.inputs.copy(evidence = proofs.map { it.second.logicalPath }))
        }
    }

    private fun candidate(f: RecoveryFile, state: String, method: String, reason: String) =
        MatchChoice(RecoveryInputs(f.logicalPath, listOf(f.logicalPath), covers[f.groupKey]?.singleOrNull()?.logicalPath), state, method, reason)

    private fun durationFits(file: RecoveryFile, detail: RecoveryDetail): Boolean {
        val duration = durations.getOrPut(file.logicalPath) {
            withVideo(file) { it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0 } ?: 0
        }
        return detail.durationMs > 0 && duration > 0 && abs(duration-detail.durationMs) <= maxOf(750, detail.durationMs / 50)
    }

    private inline fun <T> withVideo(file: RecoveryFile, read: (MediaMetadataRetriever) -> T): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            access.open(file.logicalPath).use { retriever.setDataSource(it.fileDescriptor); read(retriever) }
        } catch (e: RuntimeException) {
            if (e is kotlinx.coroutines.CancellationException || e is SecurityException) throw e
            null
        }
        finally { retriever.release() }
    }

    private suspend fun sample(file: RecoveryFile): List<Long> {
        frameCache.get(file.quickHash)?.let { return it }
        val result = withVideo(file) { reader ->
            val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            buildList {
                for (i in 1..12) {
                    currentCoroutineContext().ensureActive()
                    val time = duration * 1000 * i / 13
                    val frame = if (android.os.Build.VERSION.SDK_INT >= 27)
                        reader.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, 144, 128)
                    else reader.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (frame != null) try { add(dHash(frame)) } finally { frame.recycle() }
                }
            }
        }.orEmpty()
        frameCache.put(file.quickHash, result)
        return result
    }

    private fun imageHash(file: RecoveryFile): Long? {
        if (hashes.containsKey(file.logicalPath)) return hashes[file.logicalPath]
        val path = file.logicalPath
        val options = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        access.stream(path).use { BitmapFactory.decodeStream(it, null, options) }
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 256) options.inSampleSize *= 2
        val bmp = access.stream(path).use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        return try {
            val matrix = access.stream(path).use {
                val exif = ExifInterface(it)
                Matrix().apply { if (exif.isFlipped) postScale(-1f,1f); postRotate(exif.rotationDegrees.toFloat()) }
            }
            val oriented = Bitmap.createBitmap(bmp,0,0,bmp.width,bmp.height,matrix,true)
            try { dHash(oriented).also { hashes[path] = it } } finally { if (oriented !== bmp) oriented.recycle() }
        } finally { bmp.recycle() }
    }

    private fun dHash(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        fun gray(c: Int) = 299 * (c shr 16 and 255) + 587 * (c shr 8 and 255) + 114 * (c and 255)
        return try {
            var hash = 0L
            for (y in 0..7) for (x in 0..7) {
                hash = (hash shl 1) or if (gray(small.getPixel(x,y)) > gray(small.getPixel(x+1,y))) 1L else 0L
            }
            hash
        } finally { if (small !== bitmap) small.recycle() }
    }
}
