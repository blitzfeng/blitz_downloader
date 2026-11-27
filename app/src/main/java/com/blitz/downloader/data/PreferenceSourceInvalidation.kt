package com.blitz.downloader.data

import com.blitz.downloader.data.db.AppDatabase
import com.blitz.downloader.model.PreferenceJson
import com.blitz.downloader.model.PreferenceManifest

internal class PreferenceSourceInvalidation(db: AppDatabase) {
    private val dao = db.aiPreferenceDao()

    suspend fun casesChanged(ids: Set<String>) {
        if (ids.isEmpty()) return
        invalidate("引用案例已变化或被排除") { manifest -> manifest.cases.any { it.id in ids } }
    }

    suspend fun tagsChanged(fingerprints: Map<Long, String>) {
        invalidate("引用标签的定义已变化") { manifest -> manifest.tags.any { (id, hash) -> fingerprints[id] != hash } }
    }

    private suspend fun invalidate(message: String, matches: (PreferenceManifest) -> Boolean) {
        fun affected(json: String) = PreferenceJson.decode<PreferenceManifest>(json)?.let {
            runCatching { matches(it) }.getOrDefault(true)
        } ?: true
        for (profile in dao.usableProfiles()) {
            if (affected(profile.manifestJson)) dao.saveProfile(profile.copy(status = "INVALID"))
        }
        for (run in dao.resumableRuns()) {
            if (affected(run.manifestJson)) {
                dao.saveRun(run.copy(status = "STALE", lastError = message, updatedAtMillis = System.currentTimeMillis()))
            }
        }
    }
}
