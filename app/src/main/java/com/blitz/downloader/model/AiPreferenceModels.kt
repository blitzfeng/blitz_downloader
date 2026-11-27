package com.blitz.downloader.model

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest

enum class PreferenceJudgment { POSITIVE, NEGATIVE, UNKNOWN }

enum class PreferenceSource {
    HUMAN_CONFIRM, HUMAN_ADD, HUMAN_REMOVE, HUMAN_REPLACE, HUMAN_COEXIST,
    AUTO_EXISTING, SKIPPED, LEGACY_UNKNOWN;

    val isHuman: Boolean get() = name.startsWith("HUMAN_")
}

data class PreferenceDecision(
    val tagId: Long,
    val judgment: PreferenceJudgment,
    val source: PreferenceSource,
    val analysisId: Long? = null,
    val modifiedAtMillis: Long = 0,
    val legacyKind: String? = null,
)

data class PreferenceCaseStamp(val id: String, val revision: Long, val contentHash: String)
data class PreferenceManifest(
    val cases: List<PreferenceCaseStamp>,
    val tags: Map<Long, String>,
    val strategyVersion: Int,
    val overrides: Map<String, PreferenceRuleOverride> = emptyMap(),
)

data class PreferenceRule(
    val scope: String,
    val tagIds: List<Long>,
    val criteria: String,
    val positiveSignals: List<String>,
    val negativeSignals: List<String>,
    val replacementConditions: List<String>,
    val coexistenceConditions: List<String>,
    val sourceNodeIds: List<String>,
    val representativeCaseIds: List<String>,
    val insufficientEvidence: Boolean,
) {
    val scopeKey: String get() = "$scope:${tagIds.joinToString(",")}"
    val body: String get() = (listOf(criteria) + positiveSignals + negativeSignals +
        replacementConditions + coexistenceConditions).joinToString("\n")
}

data class PreferenceRuleCoverage(
    val caseIds: List<String>,
    val positiveIds: List<String>,
    val negativeIds: List<String>,
    val unknownIds: List<String>,
    val evidenceIds: List<String>,
)

data class PreferenceRuleOverride(
    val text: String? = null,
    val enabled: Boolean = true,
    val reviewed: Boolean = false,
)

data class PreferenceProvider(val providerId: String, val modelId: String)

object PreferenceLearningPolicy {
    const val VERSION = 1
    const val MAX_CASES = 8
    const val MAX_IMAGES = 4
    const val MAX_TEXT_BYTES = 32 * 1024
    const val MAX_IMAGE_SIDE = 384
    const val MAX_IMAGE_BYTES = 512 * 1024
    const val MAX_BODY_BYTES = 3 * 1024 * 1024
    const val MAX_CHILDREN = 8
    const val MAX_RULE_LENGTH = 512
    const val MAX_REPRESENTATIVES = 8
    const val TIMEOUT_MILLIS = 120_000L
    const val UPDATE_THRESHOLD = 20
    const val GLOBAL_RULE_BYTES = 2048
    const val TAG_RULE_BYTES = 8192
    const val TAG_RULE_COUNT = 12
    const val EXAMPLE_BYTES = 4096
    const val EXAMPLE_COUNT = 2
}

object PreferenceJson {
    @PublishedApi internal val gson = Gson()
    fun encode(value: Any): String = gson.toJson(value)
    inline fun <reified T> decode(text: String): T? = runCatching {
        gson.fromJson<T>(text, object : TypeToken<T>() {}.type)
    }.getOrNull()

    fun decisions(text: String): List<PreferenceDecision> = runCatching {
        JsonParser.parseString(text).asJsonArray.mapNotNull { value ->
            val item = value as? JsonObject ?: return@mapNotNull null
            val id = runCatching { item.get("tagId").asLong }.getOrNull()?.takeIf { it > 0 }
                ?: return@mapNotNull null
            val source = runCatching { PreferenceSource.valueOf(item.get("source").asString) }
                .getOrDefault(PreferenceSource.LEGACY_UNKNOWN)
            val judgment = runCatching { PreferenceJudgment.valueOf(item.get("judgment").asString) }
                .getOrDefault(PreferenceJudgment.UNKNOWN)
            PreferenceDecision(
                id, if (source.isHuman) judgment else PreferenceJudgment.UNKNOWN, source,
                runCatching { item.get("analysisId").asLong }.getOrNull(),
                runCatching { item.get("modifiedAtMillis").asLong }.getOrDefault(0),
                runCatching { item.get("legacyKind").asString }.getOrNull(),
            )
        }
    }.getOrDefault(emptyList())

    fun hash(text: String): String = hash(text.toByteArray(Charsets.UTF_8))
    fun hash(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    internal fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        val digits = "0123456789abcdef"
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }
}

fun String.preferenceBytes(): Int = toByteArray(Charsets.UTF_8).size
