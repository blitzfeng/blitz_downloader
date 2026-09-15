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
        multimodalEvidenceSamples: List<MultimodalEvidenceSample> = emptyList(),
    ): TagSuggestionRequest {
        // 过滤未启用 AI 分析的标签（如「不导出」、「图片」等）
        val activeTags = tags.filter { it.enableAi }
        val nameToId = activeTags.associate { it.tagName to it.id }
        val parentEntitiesByName = activeTags.associateBy { it.tagName }
        val parentNamesWithChildren = activeTags.filter { it.parentTagName.isNotBlank() }.map { it.parentTagName }.toSet()

        val vocabulary = activeTags.map { tag ->
            val isParent = tag.tagName in parentNamesWithChildren
            val isExclusiveCategory = if (isParent) {
                tag.isExclusive
            } else {
                parentEntitiesByName[tag.parentTagName]?.isExclusive ?: false
            }

            TagWordEntry(
                tagId = tag.id,
                name = tag.tagName,
                description = tag.description,
                parentTagId = if (includeParentHierarchy && tag.parentTagName.isNotBlank()) {
                    nameToId[tag.parentTagName]
                } else {
                    null
                },
                isExclusiveCategory = isExclusiveCategory,
                isParent = isParent,
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
            multimodalEvidenceSamples = multimodalEvidenceSamples,
        )
    }

    /**
     * 生成供展示/预览的当前 Prompt 模板内容（包含系统角色设定、可选标签词表、描述与互斥规则）。
     */
    fun buildPreviewPrompt(tags: List<TagEntity>): String {
        val dummyImage = ImagePart(jpegBytes = byteArrayOf(), hasFace = false)
        val request = build(
            coverImage = dummyImage,
            keyFrames = emptyList(),
            desc = "示例短视频文案（实际分析时将替换为目标视频的具体文案）",
            tags = tags,
            authorProfile = null,
            preferenceProfileText = null,
            fewShotExamples = emptyList(),
            multimodalEvidenceSamples = emptyList(),
        )
        return buildTagSuggestionPrompt(request)
    }

    /**
     * 动态生成 AI 打标签的 Prompt 文本。
     * 包含核心主体显著原则（宁缺毋滥）、互斥单选组规则与父标签兜底项、普通分类与独立标签。
     */
    fun buildTagSuggestionPrompt(request: TagSuggestionRequest): String = buildString {
        appendLine("你是一个短视频内容标签助手。请仔细观察后面提供的图片（第一张是待分析视频的封面，其余是从视频中抽取的关键帧），结合视频文案，完成两件事：")
        appendLine("1. 输出结构化的视觉证据 visualFeatureProfile：按 face/expression/bodyAndStyling/clothing/action 五个维度，分别说明该维度在图片中是否可见（visibility: high/medium/low/none）、观察到的具体特征、以及依据的图片序号（图片序号从 0 开始，0 是封面，之后依次是关键帧）。某个维度在图片里完全看不出来时，visibility 填 \"none\"，不要编造证据。")
        appendLine("2. 从下面给出的标签词表中选择候选标签，必须返回词表中已有标签的 tagName 与对应的 tagId，不要创造新标签或返回词表之外的名字。")
        appendLine("   每个候选标签给出 confidence（0~1）与支撑该标签的图片序号 evidenceFrames。")
        appendLine("   注意：evidenceFrames 只需填写观察到相关特征的少数关键图片序号，不要无脑填入全部图片序号。")
        appendLine()
        appendLine("【核心主体原则（宁缺毋滥）】")
        appendLine("- 严格主体显著：仅当该特征/风格为画面的核心主体、特写、主要镜头语言或突出视觉看点时才允许勾选。")
        appendLine("- 严禁过度打标：背景掠过、姿势模糊、画面占比极小、勉强沾边或缺乏清晰画面证据的一律严禁勾选。")
        appendLine("- 颜值类标签必须以清晰的人脸/正面特写为依据，若画面只有背影、远景或人脸模糊，绝不可给出颜值类建议。宁缺毋滥，宁可不选也不要误选。")
        appendLine()
        if (request.desc.isNotBlank()) {
            appendLine("待分析视频文案：${request.desc}")
            appendLine()
        }
        appendLine("【可选标签词表与分类规则】")
        appendLine("（大模型只需输出具体命中的标签 ID 与名称，系统本地会自动继承对应的父分类，无需重复推理父分类）：")
        appendLine()

        val vocabulary = request.tagVocabulary
        val parents = vocabulary.filter { it.isParent }
        val nonParents = vocabulary.filter { !it.isParent }
        val childrenByParentId = nonParents.filter { it.parentTagId != null }.groupBy { it.parentTagId!! }
        val standaloneTags = nonParents.filter { it.parentTagId == null }

        // 1. 主导风格分类（常规单选 + 反差兼具例外）
        val exclusiveParents = parents.filter { it.isExclusiveCategory }
        if (exclusiveParents.isNotEmpty()) {
            appendLine("--- 气质风格分类规则（主导单选原则与反差兼具例外） ---")
            exclusiveParents.forEach { parent ->
                val children = childrenByParentId[parent.tagId].orEmpty()
                appendLine("【分类：${parent.name}】（主导风格单选分类）：")
                appendLine("  * [常规约束]：该分类下的各种风格在绝大多数情况下属于互斥关系，原则上至多单选 1 项最核心、最突出的主导风格特征；若画面特征不显著则一项都不选。")
                appendLine("  * [反差兼具例外]：仅当画面呈现极其强烈的双重反差特质（例如面部笑容呈现甜美阳光的初恋治愈感，同时镜头姿态与穿着又带有极其明确的纯欲诱惑），且【该作者历史高频标签先验或参考样例中证实过兼具此类风格】时，才允许破例同时选择至多 2 项！未见强烈双重反差或缺乏历史兼具先验时，严禁随意多选。")
                if (children.isNotEmpty()) {
                    appendLine("  [具体子风格候选]：")
                    children.forEach { child ->
                        val descStr = if (child.description.isNotBlank()) "（说明：${child.description}）" else ""
                        appendLine("  - [ID: ${child.tagId}] ${child.name}$descStr")
                    }
                }
                appendLine("  [分类兜底单选项]：")
                val parentDescStr = if (parent.description.isNotBlank()) "（说明：${parent.description}）" else ""
                appendLine("  - [ID: ${parent.tagId}] ${parent.name}$parentDescStr：若人物/画面整体符合该分类大类特征，但气质或风格偏向其他非典型类型（例如颜值出众但属于清冷、端庄、知性、英气等未列入子标签的具体风格），或没有特定子风格偏向，请直接选择此父标签 [ID: ${parent.tagId}] 本身作为兜底单选项！")
                appendLine()
            }
        }

        // 2. 普通多选分类
        val normalParents = parents.filter { !it.isExclusiveCategory }
        if (normalParents.isNotEmpty()) {
            appendLine("--- 普通分类（可多选，仅选择特征显著的） ---")
            normalParents.forEach { parent ->
                val children = childrenByParentId[parent.tagId].orEmpty()
                val parentDesc = if (parent.description.isNotBlank()) " - 说明：${parent.description}" else ""
                appendLine("【分类：${parent.name}】[ID: ${parent.tagId}]$parentDesc")
                children.forEach { child ->
                    val descStr = if (child.description.isNotBlank()) "（说明：${child.description}）" else ""
                    appendLine("  - [ID: ${child.tagId}] ${child.name}$descStr")
                }
                appendLine()
            }
        }

        // 3. 独立标签
        if (standaloneTags.isNotEmpty()) {
            appendLine("--- 独立标签（可多选，仅选择特征显著的） ---")
            standaloneTags.forEach { tag ->
                val descStr = if (tag.description.isNotBlank()) "（说明：${tag.description}）" else ""
                appendLine("- [ID: ${tag.tagId}] ${tag.name}$descStr")
            }
            appendLine()
        }

        request.authorProfile?.takeIf { it.topTags.isNotEmpty() }?.let { profile ->
            val tagsSummary = profile.topTags.joinToString("、") { tag ->
                val ratioText = tag.ratio?.let { "占比 ${(it * 100).toInt()}%" } ?: "出现 ${tag.count} 次"
                "${tag.tagName} ($ratioText)"
            }
            appendLine("该视频作者的历史高频标签先验（该作者共有 ${profile.sampleCount} 个已下载视频，出现频率最高的目标标签为：$tagsSummary。")
            appendLine("**仅作先验参考，不能替代当前图片证据**——历史上常打某标签不代表这条视频也符合，必须以当前图片证据为准）：")
            appendLine()
        }
        request.preferenceProfileText?.takeIf { it.isNotBlank() }?.let { profileText ->
            appendLine("这是根据用户过往采纳/拒绝建议总结出的个人标签偏好，供参考：")
            appendLine(profileText)
            appendLine()
        }
        appendLine("请严格按照给定的 JSON Schema 返回结果。")
    }

    /**
     * 解析 AI 建议返回的候选标签，并在客户端本地自动补齐父标签：
     * 1. 过滤不在词表 [allTags] 中的标签（防幻觉）；
     * 2. 对同一标签去重，取最高置信度并合并证据帧；
     * 3. 若命中子标签，根据子标签的 [TagEntity.parentId] / [TagEntity.parentTagName] 本地自动补齐父标签；
     *    父标签的置信度继承自子标签的最大置信度，证据帧取子标签证据帧的并集；
     * 4. 若模型直接返回父标签（如兜底项），保留父标签并合并证据帧。
     */
    fun resolveCandidatesWithParentHierarchy(
        rawCandidates: List<TagCandidate>,
        allTags: List<TagEntity>,
    ): List<TagCandidate> {
        val tagByName = allTags.associateBy { it.tagName }
        val tagById = allTags.filter { it.id > 0 }.associateBy { it.id }

        // 1. 过滤幻觉并校准 tagId 与 tagName
        val resolved = rawCandidates.mapNotNull { candidate ->
            val entity = (if (candidate.tagName.isNotBlank()) tagByName[candidate.tagName] else null)
                ?: tagById[candidate.tagId]
                ?: return@mapNotNull null
            candidate.copy(tagId = entity.id, tagName = entity.tagName)
        }

        // 2. 初步按 tagId 去重
        val candidateMap = resolved.groupBy { it.tagId }.mapValues { (_, items) ->
            val best = items.maxByOrNull { it.confidence } ?: items.first()
            val mergedFrames = items.flatMap { it.evidenceFrames }.distinct().sorted()
            TagCandidate(
                tagId = best.tagId,
                confidence = best.confidence,
                evidenceFrames = mergedFrames,
                tagName = best.tagName,
            )
        }.toMutableMap()

        // 3. 本地自动补齐父标签
        for (candidate in candidateMap.values.toList()) {
            val entity = tagById[candidate.tagId] ?: continue
            if (entity.parentTagName.isBlank()) continue
            val parentEntity = tagByName[entity.parentTagName] ?: continue

            val existingParent = candidateMap[parentEntity.id]
            if (existingParent != null) {
                // 父标签已存在（可能是模型直接作为兜底项返回，或已被其他子标签补齐），合并置信度与证据帧
                candidateMap[parentEntity.id] = existingParent.copy(
                    confidence = maxOf(existingParent.confidence, candidate.confidence),
                    evidenceFrames = (existingParent.evidenceFrames + candidate.evidenceFrames).distinct().sorted(),
                )
            } else {
                // 补齐父标签
                candidateMap[parentEntity.id] = TagCandidate(
                    tagId = parentEntity.id,
                    confidence = candidate.confidence,
                    evidenceFrames = candidate.evidenceFrames,
                    tagName = parentEntity.tagName,
                )
            }
        }

        return candidateMap.values.toList()
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
