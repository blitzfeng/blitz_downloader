package com.blitz.downloader.llm

import com.blitz.downloader.data.AuthorProfile
import com.blitz.downloader.data.db.TagEntity
import com.blitz.downloader.data.db.VideoTagFeedbackEntity

/**
 * 组装一次 [TagSuggestionRequest]。**纯函数，不做任何 IO**——封面/关键帧/标签词表/作者画像/
 * few-shot 样例都由调用方（`AiTagSuggestionRepository`）预先取好传进来，这里只负责拼装，
 * 方便单元测试（对齐项目里 `AwemeMapper` 那类"无 Context、纯函数、可单测"的既有风格）。
 */
object TagSuggestionRequestBuilder {

    /**
     * @param tags 完整标签词表（`VideoTagRepository.getAllEntities` 或等价来源），用于组装
     *   `tagId`/`description`/`parentTagId`。
     * @param includeParentHierarchy 是否在词表里附带父子关系（design.md Decision 13 的可选增强，
     *   依赖 `tag-hierarchy` 已落地；调用方传 `false` 时词表不带 `parentTagId`，不影响其余字段）。
     * @param authorProfile 作者历史标签先验，`null` 表示无（作者无稳定 id 或没有达标签）。
     * @param preferenceProfileText 个人偏好摘要，空白/`null` 表示尚未生成。
     * @param feedbackExamples few-shot 样例来源（同作者优先，由调用方按 design.md Decision 10
     *   的采样规则查好传入），这里只负责转换成请求里的 [FewShotExample]，`confirmedTags` 取
     *   `ACCEPTED`/`MISSED` 两类（代表最终被采纳的标签），按 `awemeId` 分组、丢弃没有 desc 的样例。
     */
    fun build(
        coverImage: ImagePart,
        keyFrames: List<ImagePart>,
        desc: String,
        tags: List<TagEntity>,
        includeParentHierarchy: Boolean = true,
        authorProfile: AuthorProfile? = null,
        preferenceProfileText: String? = null,
        fewShotExamples: List<FewShotExample> = emptyList(),
    ): TagSuggestionRequest {
        val nameToId = tags.associate { it.tagName to it.id }
        val vocabulary = tags.map { tag ->
            TagWordEntry(
                tagId = tag.id,
                name = tag.tagName,
                description = tag.description,
                parentTagId = if (includeParentHierarchy && tag.parentTagName.isNotBlank()) {
                    nameToId[tag.parentTagName]
                } else {
                    null
                },
            )
        }
        return TagSuggestionRequest(
            coverImage = coverImage,
            keyFrames = keyFrames,
            desc = desc,
            tagVocabulary = vocabulary,
            authorProfile = authorProfile?.toContext(),
            preferenceProfileText = preferenceProfileText?.takeIf { it.isNotBlank() },
            fewShotExamples = fewShotExamples,
        )
    }

    private fun AuthorProfile.toContext(): AuthorProfileContext = AuthorProfileContext(
        sampleCount = sampleCount,
        topTags = topTags.map { AuthorTagContext(it.tagName, it.count, it.ratio) },
    )

    /**
     * 把一批同一视频的 [VideoTagFeedbackEntity]（`ACCEPTED`/`MISSED` 两类，代表最终确认的标签）
     * 转成一条 few-shot 样例。`tagIdToName` 用于把 `tagId` 换回可读的标签名。
     */
    fun toFewShotExample(desc: String, confirmedTagIds: List<Long>, tagIdToName: Map<Long, String>): FewShotExample =
        FewShotExample(desc = desc, confirmedTagNames = confirmedTagIds.mapNotNull { tagIdToName[it] })
}
