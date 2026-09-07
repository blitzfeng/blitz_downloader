package com.blitz.downloader.data

import android.content.Context
import com.blitz.downloader.config.AppSettings
import com.blitz.downloader.data.db.AppDatabase
import com.blitz.downloader.data.db.ConfirmedFeedbackRow
import com.blitz.downloader.data.db.PreferenceProfileEntity
import com.blitz.downloader.data.db.VideoAiAnalysisEntity
import com.blitz.downloader.data.db.VideoTagFeedbackEntity
import com.blitz.downloader.data.db.VideoVisualFeatureEntity
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

    /** 一次成功建议的结果：分析记录 id（反馈写入的唯一凭证）+ 结构化视觉证据 + 过滤后的候选标签 id。 */
    data class SuggestionOutcome(
        val analysisId: Long,
        val visualFeatureProfile: VisualFeatureProfile,
        val candidateTagIds: List<Long>,
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
    ): Result<SuggestionOutcome> {
        val tags = videoTagRepository.getAvailableTagEntities()
        if (tags.isEmpty()) return Result.failure(IllegalStateException("没有可用标签，无法发起 AI 建议"))
        val validTagIds = tags.mapTo(HashSet()) { it.id }

        val keyFrames = videoFile?.let { VideoFrameExtractor.extract(it) }.orEmpty()
        val coverImage = ImagePart(jpegBytes = coverBytes, hasFace = false)

        val threshold = AppSettings.getHighFrequencyTagThreshold(appContext)
        val authorProfile = videoTagRepository.getAuthorProfileForAi(secUserId, threshold)
        val preferenceProfileText = preferenceProfileDao.getLatest()?.profileText
        val fewShotExamples = sampleFewShotExamples(secUserId)

        val request = TagSuggestionRequestBuilder.build(
            coverImage = coverImage,
            keyFrames = keyFrames,
            desc = desc,
            tags = tags,
            authorProfile = authorProfile,
            preferenceProfileText = preferenceProfileText,
            fewShotExamples = fewShotExamples,
        )

        val response = llmProvider.generateTagSuggestion(request).getOrElse { return Result.failure(it) }

        // spec"建议结果仅限于系统现有标签词表"：按 tagId 精确匹配过滤，词表外的项直接丢弃
        val filteredCandidates = response.candidates.filter { it.tagId in validTagIds }

        val now = System.currentTimeMillis()
        val analysisId = videoAiAnalysisDao.insert(
            VideoAiAnalysisEntity(
                awemeId = awemeId,
                provider = llmProvider.providerId,
                model = llmProvider.modelId,
                profileVersion = PROFILE_SCHEMA_VERSION,
                suggestedTagIds = SuggestedTagCodec.encode(filteredCandidates),
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
                candidateTagIds = filteredCandidates.map { it.tagId },
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

    /** few-shot 采样：同作者优先，不足 [FEW_SHOT_VIDEO_LIMIT] 条视频时用全局最近样例补齐（design.md Decision 10）。 */
    private suspend fun sampleFewShotExamples(secUserId: String): List<FewShotExample> {
        val tagNameMap = videoAllTagNameMap()
        val authorRows = if (secUserId.isNotBlank()) {
            videoTagFeedbackDao.getRecentConfirmedByAuthor(secUserId, FEW_SHOT_ROW_LIMIT)
        } else {
            emptyList()
        }
        val authorVideoCount = authorRows.distinctBy { it.awemeId }.size
        val rows = if (authorVideoCount < FEW_SHOT_VIDEO_LIMIT) {
            authorRows + videoTagFeedbackDao.getRecentConfirmedGlobal(FEW_SHOT_ROW_LIMIT)
        } else {
            authorRows
        }
        return groupIntoFewShotExamples(rows, tagNameMap)
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
        /** [VisualFeatureProfile] 的 schema 版本，模型升级改变结构时递增。 */
        private const val PROFILE_SCHEMA_VERSION = 1

        /** few-shot 采样的目标视频条数，design.md Decision 10 定的 N=5。 */
        private const val FEW_SHOT_VIDEO_LIMIT = 5

        /** few-shot 查询的行数上限（一个视频可能贡献多行/多标签），留足冗余保证凑够 [FEW_SHOT_VIDEO_LIMIT] 个不同视频。 */
        private const val FEW_SHOT_ROW_LIMIT = 15

        /** 累计新增反馈达到这个数量才重新生成一次 PreferenceProfile，design.md 给的区间是 20~50，先取下限。 */
        private const val PREFERENCE_REFRESH_THRESHOLD = 20

        /** 生成 PreferenceProfile 摘要时取的样本行数上限。 */
        private const val PREFERENCE_SUMMARY_ROW_LIMIT = 60
    }
}
