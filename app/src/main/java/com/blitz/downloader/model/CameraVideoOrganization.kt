package com.blitz.downloader.model

data class CameraVideoCandidate(
    val id: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val mime: String,
)

object CameraVideoRules {
    private val preservedImagePrefixes = listOf("img", "mvimg", "pano", "retouch")

    fun destinationFolder(mime: String?): String? = when {
        mime?.startsWith("video/", ignoreCase = true) == true -> "history"
        mime?.startsWith("image/", ignoreCase = true) == true -> "history_img"
        else -> null
    }

    fun isCandidate(name: String, mime: String?): Boolean =
        when (destinationFolder(mime)) {
            "history" -> !name.startsWith("img", true) && !name.startsWith("vid", true)
            "history_img" -> preservedImagePrefixes.none { name.startsWith(it, ignoreCase = true) }
            else -> false
        }

    fun availableName(name: String, exists: (String) -> Boolean): String {
        require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != "..")
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val extension = name.substring(dot)
        var candidate = name
        var index = 1
        while (exists(candidate)) candidate = "$base (${index++})$extension"
        return candidate
    }
}

enum class CameraMoveOutcome { SUCCESS, SKIPPED, FAILED }

data class CameraMoveResult(
    val source: String,
    val outcome: CameraMoveOutcome,
    val destination: String? = null,
    val detail: String? = null,
    val indexWarning: String? = null,
)

enum class CameraOrganizationPhase { IDLE, SCANNING, PREVIEW, MOVING, FINISHED }

data class CameraOrganizationState(
    val batchId: String = "",
    val phase: CameraOrganizationPhase = CameraOrganizationPhase.IDLE,
    val candidates: List<CameraVideoCandidate> = emptyList(),
    val results: List<CameraMoveResult> = emptyList(),
    val currentName: String? = null,
    val message: String? = null,
    val leftovers: List<String> = emptyList(),
) {
    val busy get() = phase == CameraOrganizationPhase.SCANNING || phase == CameraOrganizationPhase.MOVING
    val unprocessed get() = candidates.size - results.size
}
