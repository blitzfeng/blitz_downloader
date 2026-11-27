package com.blitz.downloader.model

import com.blitz.downloader.data.DownloadSourceType
import com.blitz.downloader.data.db.DownloadedVideoEntity
import java.net.URI
import java.security.MessageDigest

/** 审核、备份与预览共享的版本化字段集合；成员顺序也是身份的一部分。 */
data class RecoveryInputs(
    val main: String,
    val members: List<String>,
    val cover: String? = null,
    val evidence: List<String> = emptyList(),
    val schemaVersion: Int = 1,
    val fileDigests: Map<String,String>? = null,
)

data class RecoveryDetail(
    val awemeId: String,
    val mediaType: String,
    val author: String,
    val authorId: String,
    val description: String,
    val createTime: Long,
    val durationMs: Long,
    val diggCount: Long,
    val collectCount: Long,
    val schemaVersion: Int = 1,
)

val DownloadedVideoEntity.hasUnknownHistory: Boolean
    get() = downloadType == DownloadSourceType.RECOVERED

val DownloadedVideoEntity.originalDownloadTime: Long?
    get() = createdAtMillis.takeUnless { hasUnknownHistory }

val DownloadedVideoEntity.knownUnwatched: Boolean
    get() = !watched && !hasUnknownHistory

val DownloadedVideoEntity.knownNeverExported: Boolean
    get() = exportCount == 0 && !hasUnknownHistory

object RecoveryNames {
    const val root = "Download/bDouyin"
    val folders = listOf("videos", "images", "covers", "covers/evidence")
    private val numeric = Regex("[0-9]{15,22}")
    private val suffix = Regex("(?:^|[_ -])([0-9]{15,22})$")
    private val duplicate = Regex("""\s*\([0-9]{1,5}\)$""")
    private val numbered = Regex("""^(.+)_([0-9]{1,4})$""")
    private val evidence = Regex("""^([0-9]{15,22})_evidence_([0-9]{1,5})\.jpg$""")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

    data class Item(val kind: String, val group: String, val work: String?, val ordinal: Int?)

    fun logical(path: String): String {
        val normalized = path.replace('\\','/')
        val marker = "/$root/"
        return if (normalized.startsWith('/') && normalized.contains(marker)) "$root/" + normalized.substringAfter(marker)
            else normalized.trimStart('/')
    }

    fun safeName(name: String) = name.isNotBlank() && name !in setOf(".", "..") &&
        name.none { it == '/' || it == '\\' || it.code < 32 }

    fun parse(folder: String, name: String): Item? {
        if (folder !in folders || !safeName(name) || name.startsWith(".")) return null
        if (folder == "covers/evidence") {
            val match = evidence.matchEntire(name) ?: return null
            return Item("EVIDENCE", match.groupValues[1], match.groupValues[1], match.groupValues[2].toInt())
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        val stem = name.substringBeforeLast('.').replace(duplicate, "")
        val seq = if (folder == "images") numbered.matchEntire(stem) else null
        val group = seq?.groupValues?.get(1) ?: stem
        val id = suffix.find(group)?.groupValues?.get(1)
        val kind = when {
            folder == "videos" && ext == "mp4" -> "VIDEO"
            folder == "images" && ext in imageExtensions -> "IMAGE"
            folder == "images" && ext == "mp4" -> "LIVE"
            folder == "covers" && ext in imageExtensions -> "COVER"
            else -> return null
        }
        return Item(kind, group, id, seq?.groupValues?.get(2)?.toInt())
    }

    fun workId(input: String): String? {
        val value = input.trim()
        if (numeric.matches(value)) return value
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1 ||
            uri.host !in setOf("www.douyin.com", "douyin.com", "m.douyin.com")) return null
        return Regex("""/(?:video|note)/([0-9]{15,22})/?""").matchEntire(uri.rawPath)?.groupValues?.get(1)
    }

    fun shortLink(input: String): Boolean {
        val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.rawAuthority == "v.douyin.com" &&
            Regex("/[a-zA-Z0-9_-]{1,64}/?").matches(uri.rawPath)
    }
}

internal fun ByteArray.hex(): String = buildString(size * 2) {
    val digits = "0123456789abcdef"
    for (byte in this@hex) {
        val n = byte.toInt() and 255
        append(digits[n ushr 4])
        append(digits[n and 15])
    }
}

internal fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).hex()
