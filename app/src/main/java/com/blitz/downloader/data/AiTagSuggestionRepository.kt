package com.blitz.downloader.data

import android.content.Context
import android.util.Log
import com.blitz.downloader.config.AppSettings
import com.blitz.downloader.data.db.AppDatabase
import com.blitz.downloader.data.db.ConfirmedFeedbackRow
import com.blitz.downloader.data.db.PreferenceProfileEntity
import com.blitz.downloader.data.db.VideoAiAnalysisEntity
import com.blitz.downloader.data.db.VideoTagFeedbackEntity
import com.blitz.downloader.data.db.VideoVisualFeatureEntity
import com.blitz.downloader.llm.AiAnalysisLogEntry
import com.blitz.downloader.llm.AiAnalysisLogFormatter
import com.blitz.downloader.llm.AiAnalysisLogStatus
import com.blitz.downloader.llm.AiAnalysisLogStore
import com.blitz.downloader.llm.FewShotExample
import com.blitz.downloader.llm.ImagePart
import com.blitz.downloader.llm.LlmProvider
import com.blitz.downloader.llm.PreferenceSummaryRequest
import com.blitz.downloader.llm.TagSuggestionRequestBuilder
import com.blitz.downloader.llm.VisualFeatureProfile
import com.blitz.downloader.llm.providers.GeminiProvider
import com.blitz.downloader.util.VideoFrameExtractor
import com.google.gson.Gson
import java.io.File
import java.util.UUID

/**
 * AI 建议标签的编排层：组装请求 → 调用 [LlmProvider] → 按标签词表过滤幻觉 → 落库分析记录/
 * 视觉证据 → （用户确认后）写入逐标签反馈 → 触发 [com.blitz.downloader.data.db.TagPreferenceDao]
 * 重算 → 视情况刷新 [com.blitz.downloader.data.db.PreferenceProfileEntity]。
 *
 * V1 只接 Gemini，持有类型是接口 [LlmProvider] 而不是具体实现——换供应商只改这一处构造。
 */
class AiTagSuggestionRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val videoAiAnalysisDao = db.videoAiAnalysisDao()
    private val videoVisualFeatureDao = db.videoVisualFeatureDao()
    private val videoTagFeedbackDao = db.videoTagFeedbackDao()
    private val tagPreferenceDao = db.tagPreferenceDao()
    private val preferenceProfileDao = db.preferenceProfileDao()

    private val videoTagRepository = VideoTagRepository(appContext)
    private val llmProvider: LlmProvider = GeminiProvider(appContext)
    private val gson = Gson()

    /** 设置页「测试连接」按钮用：只验证 Key/网络/模型可用，不组装标签词表、不落库。 */
    suspend fun testConnection(): Result<String> = llmProvider.testConnection()

    data class SuggestionOutcome(
        val analysisId: Long,
        val visualFeatureProfile: VisualFeatureProfile,
        val candidateTagIds: List<Long>,
        val candidateTagNames: List<String> = emptyList(),
    )

    /**
     * 发起一次 AI 建议标签请求并落库分析记录。
     *
     * @param videoFile 本地已下载的 mp4；`null` 或文件不存在时只用封面图（[VideoFrameExtractor]
     *   自身也会在文件缺失时返回空列表，这里允许调用方直接传 `null` 跳过一次无意义的文件系统访问）。
     * @param coverBytes 封面图 JPEG 字节，调用方负责读取（不同来源——本地文件/网络图——由调用方决定）。
     */
    suspend fun requestSuggestion(
        awemeId: String,
        secUserId: String,
        desc: String,
        coverBytes: ByteArray,
        videoFile: File?,
        authorName: String = "",
    ): Result<SuggestionOutcome> {
        val logId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        val resolvedAuthorName = authorName.ifBlank {
            runCatching {
                db.downloadedVideoDao().getByAwemeId(awemeId)?.userName.orEmpty()
            }.getOrDefault("")
        }
        val videoTitle = when {
            resolvedAuthorName.isNotBlank() && desc.isNotBlank() -> "$resolvedAuthorName - $desc"
            resolvedAuthorName.isNotBlank() -> resolvedAuthorName
            desc.isNotBlank() -> desc
            else -> awemeId
        }

        // 自动补齐尚未分配数值标识（id == 0）的历史标签，确保所有标签都有唯一 id
        videoTagRepository.backfillTagIds()
        val tags = videoTagRepository.getAvailableTagEntities()
        if (tags.isEmpty()) {
            val err = IllegalStateException("没有可用标签，无法发起 AI 建议")
            AiAnalysisLogStore.addEntry(
                AiAnalysisLogEntry(
                    id = logId,
                    awemeId = awemeId,
                    authorName = resolvedAuthorName,
                    videoTitle = videoTitle,
                    videoDesc = desc,
                    timestamp = startTime,
                    status = AiAnalysisLogStatus.FAILED,
                    errorMessage = err.message,
                ),
            )
            return Result.failure(err)
        }
        val tagByName = tags.associateBy { it.tagName }
        val tagById = tags.filter { it.id > 0 }.associateBy { it.id }

        val keyFrames = videoFile?.let { VideoFrameExtractor.extract(it) }.orEmpty()
        val coverImage = ImagePart(jpegBytes = coverBytes, hasFace = false)

        val threshold = AppSettings.getHighFrequencyTagThreshold(appContext)
        val authorProfile = videoTagRepository.getAuthorProfileForAi(secUserId, threshold)
        val preferenceProfileText = preferenceProfileDao.getLatest()?.profileText

        val request = TagSuggestionRequestBuilder.build(
            coverImage = coverImage,
            keyFrames = keyFrames,
            desc = desc,
            tags = tags,
            authorProfile = authorProfile,
            preferenceProfileText = preferenceProfileText,
            fewShotExamples = emptyList(),
        )

        val faceCount = keyFrames.count { it.hasFace }
        val framesSummary = "封面 1 张，关键帧 ${keyFrames.size} 张 (含人脸 $faceCount 张)"
        val vocabSummary = AiAnalysisLogFormatter.formatVocabulary(request.tagVocabulary)
        val authorTagsSummary = if (authorProfile != null && authorProfile.topTags.isNotEmpty()) {
            "已下载 ${authorProfile.sampleCount} 个视频，高频标签: " + authorProfile.topTags.joinToString("、") { tag ->
                val ratioText = tag.ratio?.let { "占比 ${(it * 100).toInt()}%" } ?: "${tag.count}次"
                "${tag.tagName} ($ratioText)"
            }
        } else {
            "无 (未达到高频阈值或无历史标签)"
        }
        val promptSummary = buildString {
            append("目标：按五个视觉维度提供证据，并从词表中选择候选标签。\n")
            if (desc.isNotBlank()) append("文案：$desc\n")
            if (authorProfile != null && authorProfile.topTags.isNotEmpty()) {
                val tagsText = authorProfile.topTags.joinToString("、") { tag ->
                    val ratioText = tag.ratio?.let { "${(it * 100).toInt()}%" } ?: "${tag.count}次"
                    "${tag.tagName}($ratioText)"
                }
                append("作者高频标签：$tagsText\n")
            }
            append("候选词表：共 ${request.tagVocabulary.size} 个标签")
        }

        val authorTagsJsonList = authorProfile?.topTags?.map { tag ->
            val ratioText = tag.ratio?.let { "${(it * 100).toInt()}%" } ?: "${tag.count}次"
            mapOf("tagName" to tag.tagName, "count" to tag.count, "ratio" to ratioText)
        } ?: emptyList()

        val redactedRequestJson = buildString {
            append("{\n")
            append("  \"promptTarget\": \"五个视觉维度证据 + 词表候选推荐\",\n")
            if (resolvedAuthorName.isNotBlank()) {
                append("  \"authorName\": \"${resolvedAuthorName.replace("\"", "\\\"")}\",\n")
            }
            append("  \"videoDesc\": \"${desc.replace("\"", "\\\"")}\",\n")
            append("  \"authorHighFreqTags\": ${gson.toJson(authorTagsJsonList)},\n")
            append("  \"framesSummary\": \"$framesSummary\",\n")
            append("  \"tagVocabularyCount\": ${request.tagVocabulary.size}\n")
            append("}")
        }

        AiAnalysisLogStore.addEntry(
            AiAnalysisLogEntry(
                id = logId,
                awemeId = awemeId,
                authorName = resolvedAuthorName,
                videoTitle = videoTitle,
                videoDesc = desc,
                timestamp = startTime,
                status = AiAnalysisLogStatus.RUNNING,
                requestPrompt = promptSummary,
                requestAuthorTagsSummary = authorTagsSummary,
                requestFramesSummary = framesSummary,
                requestVocabularySummary = vocabSummary,
                rawRequestBody = AiAnalysisLogFormatter.formatJson(redactedRequestJson),
            ),
        )

        val response = llmProvider.generateTagSuggestion(request).getOrElse { err ->
            val durationMs = System.currentTimeMillis() - startTime
            AiAnalysisLogStore.updateEntry(logId) {
                it.copy(
                    status = AiAnalysisLogStatus.FAILED,
                    durationMs = durationMs,
                    errorMessage = err.message ?: err.javaClass.simpleName,
                )
            }
            return Result.failure(err)
        }

        // spec"建议结果仅限于系统现有标签词表"：优先按直观的 tagName 匹配，备选按 tagId 匹配，词表外的项直接丢弃
        val resolvedCandidates = response.candidates.mapNotNull { candidate ->
            val entity = (if (candidate.tagName.isNotBlank()) tagByName[candidate.tagName] else null)
                ?: tagById[candidate.tagId]
                ?: return@mapNotNull null
            candidate.copy(tagId = entity.id, tagName = entity.tagName)
        }

        // 去重：同一标签若被模型针对多帧重复输出，合并 evidenceFrames 并取最高置信度
        val deduplicatedCandidates = resolvedCandidates
            .groupBy { it.tagName }
            .map { (tagName, items) ->
                val best = items.maxByOrNull { it.confidence } ?: items.first()
                val mergedFrames = items.flatMap { it.evidenceFrames }.distinct().sorted()
                com.blitz.downloader.llm.TagCandidate(
                    tagId = best.tagId,
                    confidence = best.confidence,
                    evidenceFrames = mergedFrames,
                    tagName = tagName,
                )
            }

        Log.i(TAG, "[$awemeId] AI 建议标签结果: ${deduplicatedCandidates.map { "${it.tagName}(${it.confidence})" }}")

        val now = System.currentTimeMillis()
        val durationMs = now - startTime
        AiAnalysisLogStore.updateEntry(logId) {
            it.copy(
                status = AiAnalysisLogStatus.SUCCESS,
                durationMs = durationMs,
                suggestedTags = deduplicatedCandidates,
                visualFeatureProfile = response.visualFeatureProfile,
                rawResponseBody = AiAnalysisLogFormatter.formatJson(gson.toJson(response)),
            )
        }

        val analysisId = videoAiAnalysisDao.insert(
            VideoAiAnalysisEntity(
                awemeId = awemeId,
                provider = llmProvider.providerId,
                model = llmProvider.modelId,
                profileVersion = PROFILE_SCHEMA_VERSION,
                suggestedTagIds = SuggestedTagCodec.encode(deduplicatedCandidates),
                succeeded = true,
                createdAtMillis = now,
            ),
        )
        videoVisualFeatureDao.insert(
            VideoVisualFeatureEntity(
                analysisId = analysisId,
                awemeId = awemeId,
                profileJson = gson.toJson(response.visualFeatureProfile),
                createdAtMillis = now,
            ),
        )

        return Result.success(
            SuggestionOutcome(
                analysisId = analysisId,
                visualFeatureProfile = response.visualFeatureProfile,
                candidateTagIds = deduplicatedCandidates.map { it.tagId },
                candidateTagNames = deduplicatedCandidates.map { it.tagName },
            ),
        )
    }

    /**
     * 用户确认（或批量分组确认）完成后调用：对比 [analysisId] 对应的原始建议集合与
     * [confirmedTagIds]（该视频此刻实际持有的标签 id 集合），按 design.md Decision 7 规则
     * 分类写入 [VideoTagFeedbackEntity]（`ACCEPTED`/`REJECTED`/`MISSED`），随后触发
     * [com.blitz.downloader.data.db.TagPreferenceDao] 重算与（视情况）[maybeRefreshPreferenceProfile]。
     *
     * `analysisId` 查不到对应分析记录时静默返回，不抛异常——理论上不应发生（结果契约保证
     * 只有真正发起过建议才会带这个 id），但防御性处理优于崩溃。
     */
    suspend fun recordFeedback(analysisId: Long, confirmedTagIds: Set<Long>) {
        val analysis = videoAiAnalysisDao.getById(analysisId) ?: return
        val suggested = SuggestedTagCodec.decode(analysis.suggestedTagIds)
        val now = System.currentTimeMillis()

        val rows = mutableListOf<VideoTagFeedbackEntity>()
        for ((tagId, confidence) in suggested) {
            val kind = if (tagId in confirmedTagIds) AiTagFeedbackKind.ACCEPTED else AiTagFeedbackKind.REJECTED
            rows += VideoTagFeedbackEntity(
                awemeId = analysis.awemeId,
                analysisId = analysisId,
                tagId = tagId,
                kind = kind,
                confidence = confidence,
                createdAtMillis = now,
            )
        }
        for (tagId in confirmedTagIds - suggested.keys) {
            rows += VideoTagFeedbackEntity(
                awemeId = analysis.awemeId,
                analysisId = analysisId,
                tagId = tagId,
                kind = AiTagFeedbackKind.MISSED,
                confidence = null,
                createdAtMillis = now,
            )
        }
        if (rows.isEmpty()) return

        videoTagFeedbackDao.insertAll(rows)
        tagPreferenceDao.recomputeAll(now)
        maybeRefreshPreferenceProfile()
    }

    /**
     * 累计新反馈达到 [PREFERENCE_REFRESH_THRESHOLD] 时调用 [LlmProvider.summarizePreference]
     * 生成新版本并写入 [com.blitz.downloader.data.db.PreferenceProfileEntity]；未达阈值直接返回，
     * 不产生任何调用（避免过于频繁的额外费用）。生成失败（网络异常等）静默忽略——
     * 摘要是锦上添花的上下文，不应该因为这一步失败而影响调用方主流程。
     */
    private suspend fun maybeRefreshPreferenceProfile() {
        val currentTotal = videoTagFeedbackDao.countAll()
        val lastSampleCount = preferenceProfileDao.getLatestSampleCount() ?: 0
        if (currentTotal - lastSampleCount < PREFERENCE_REFRESH_THRESHOLD) return

        val rows = videoTagFeedbackDao.getRecentConfirmedGlobal(PREFERENCE_SUMMARY_ROW_LIMIT)
        val examples = groupIntoFewShotExamples(rows, videoAllTagNameMap())
        if (examples.isEmpty()) return

        val summaryResult = llmProvider.summarizePreference(PreferenceSummaryRequest(examples))
        val summaryText = summaryResult.getOrNull() ?: return

        val latestVersion = preferenceProfileDao.getLatest()?.version ?: 0
        preferenceProfileDao.insert(
            PreferenceProfileEntity(
                version = latestVersion + 1,
                profileText = summaryText,
                sampleCount = currentTotal,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun groupIntoFewShotExamples(
        rows: List<ConfirmedFeedbackRow>,
        tagNameMap: Map<Long, String>,
    ): List<FewShotExample> = rows.groupBy { it.awemeId }
        .entries
        .take(FEW_SHOT_VIDEO_LIMIT)
        .map { (_, groupRows) ->
            TagSuggestionRequestBuilder.toFewShotExample(
                desc = groupRows.first().desc,
                confirmedTagIds = groupRows.map { it.tagId },
                tagIdToName = tagNameMap,
            )
        }

    private suspend fun videoAllTagNameMap(): Map<Long, String> =
        videoTagRepository.getAvailableTagEntities().associate { it.id to it.tagName }

    companion object {
        private const val TAG = "AiTagSuggestionRepo"

        /** [VisualFeatureProfile] 的 schema 版本，模型升级改变结构时递增。 */
        private const val PROFILE_SCHEMA_VERSION = 1

        /** 偏好摘要采样的目标视频条数。 */
        private const val FEW_SHOT_VIDEO_LIMIT = 5

        /** 累计新增反馈达到这个数量才重新生成一次 PreferenceProfile，design.md 给的区间是 20~50，先取下限。 */
        private const val PREFERENCE_REFRESH_THRESHOLD = 20

        /** 生成 PreferenceProfile 摘要时取的样本行数上限。 */
        private const val PREFERENCE_SUMMARY_ROW_LIMIT = 60
    }
}
