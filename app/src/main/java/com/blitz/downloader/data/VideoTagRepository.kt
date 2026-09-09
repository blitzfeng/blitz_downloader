package com.blitz.downloader.data

import android.content.Context
import com.blitz.downloader.data.db.AppDatabase
import com.blitz.downloader.data.db.DownloadedVideoEntity
import com.blitz.downloader.data.db.TagEntity
import com.blitz.downloader.data.db.VideoTagDao
import com.blitz.downloader.data.db.VideoTagEntity
import com.blitz.downloader.model.TagHierarchy

/**
 * 标签的完整持久化与查询入口，统一管理：
 * - **标签名册**（[TagEntity] / `tags` 表）：先建标签、重命名、删除标签，独立于视频。
 * - **视频-标签关联**（[VideoTagEntity] / `video_tags` 表）：打标签、取消、按标签筛选视频。
 *
 * ### 典型流程
 * 1. [createTag] 新建标签（写入 `tags` 表，与视频无关）
 * 2. [addTag] / [setTags] 为视频打标签（写入 `video_tags` 表）
 * 3. [getAvailableTags] 展示所有可用标签（含未使用的预设标签）
 * 4. [getVideosByTag] 按标签筛选视频
 *
 * ### 打标签的两组入口（别混用）
 * - **程序自动**：[addTag] / [addTags] / [setTags] / [ensureCollectFolderTagLinked]，只写 `video_tags`。
 * - **用户手动编辑**：[setTagsAsUserEdit] / [addTagsAsUserEdit]，除写 `video_tags` 外还给
 *   `downloaded_videos.tagEditCount` 累加。UI 层的标签编辑一律走这两个，
 *   否则「用户改过几次标签」的统计会漏计；反过来下载流程走它们则会虚增。
 */
class VideoTagRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val videoTagDao = db.videoTagDao()
    private val tagDao = db.tagDao()
    private val authorTagFrequencyDao = db.authorTagFrequencyDao()

    /** 仅用于给 `downloaded_videos.tagEditCount` 累加，标签本身的读写不经过它。 */
    private val downloadedVideoDao = db.downloadedVideoDao()

    // ──────────────────── 标签名册管理（tags 表） ────────────────────

    /**
     * 新建标签（写入 `tags` 名册）；同名标签已存在则忽略（幂等）。
     * sortOrder 自动设为当前最大值 + 1，新标签排在末尾。
     * 创建后可通过 [getAvailableTags] 查到，与任何视频无关。
     */
    suspend fun createTag(tagName: String) {
        val nextOrder = tagDao.getMaxSortOrder() + 1
        val nextId = tagDao.getMaxId() + 1
        tagDao.insert(TagEntity(tagName = tagName.trim(), sortOrder = nextOrder, id = nextId))
    }

    /**
     * 一次性把迁移前就存在、尚未分配稳定数值标识（[TagEntity.id] 仍是 0）的历史标签补齐 id，
     * 按 [TagEntity.sortOrder] 升序分配连续递增值，从当前 `MAX(id) + 1` 开始。
     *
     * **幂等**：只处理 `id == 0` 的行，已分配过的标签不受影响，可安全重复点击。
     * 由设置页「补齐标签 ID」触发，是迁移前历史数据的一次性维护动作，不是常规调用路径——
     * 新建标签在 [createTag] 里已经自动分配 id，不依赖这个方法。
     */
    suspend fun backfillTagIds() {
        val pending = tagDao.getTagNamesWithoutId()
        if (pending.isEmpty()) return
        var nextId = tagDao.getMaxId() + 1
        for (tagName in pending) {
            tagDao.updateId(tagName, nextId)
            nextId += 1
        }
    }

    /**
     * **收藏夹批量下载**：用收藏夹名对齐 `tags` 名册与 `video_tags`。
     * - 若 [folderName] 已在 `tags` 表中：仅为该视频写入 `video_tags` 关联。
     * - 若不存在：先 [createTag] 再 [addTag]（两者均为幂等）。
     *
     * 与工程内数据库文档中 `collects`、`collectionType` 及标签双表约定一致。
     */
    suspend fun ensureCollectFolderTagLinked(awemeId: String, folderName: String) {
        val name = folderName.trim()
        if (name.isEmpty()) return
        createTag(name)
        addTag(awemeId, name)
    }

    /**
     * 删除标签：同步从 `tags` 名册和 `video_tags` 所有视频关联中移除。
     * 若有其他标签把它设为上级，那些标签的上级引用被清空（回到顶层），**不级联删除**它们本身。
     */
    suspend fun deleteTag(tagName: String) {
        tagDao.delete(tagName)
        videoTagDao.deleteTagFromAllVideos(tagName)
        tagDao.reassignChildren(oldParent = tagName, newParent = "")
    }

    /**
     * 重命名标签：同步更新 `tags` 名册和 `video_tags` 所有关联行。
     * 若有其他标签把它设为上级，那些标签的上级引用同步更新为新名字。
     *
     * 注意：若同一视频已同时持有旧名和新名两个标签，`video_tags` 的批量
     * UPDATE 会触发主键冲突；UI 层应提前提示用户避免此情况。
     */
    suspend fun renameTag(oldName: String, newName: String) {
        val trimmed = newName.trim()
        tagDao.rename(oldName, trimmed)
        videoTagDao.renameTag(oldName, trimmed)
        tagDao.reassignChildren(oldParent = oldName, newParent = trimmed)
    }

    /**
     * 返回所有可用标签（`tags` 表全量，含未关联任何视频的预设标签）。
     * 按 [TagEntity.sortOrder] 升序排列（由用户在标签管理页拖拽设定）。
     * 供打标签 UI 展示可选列表。
     */
    suspend fun getAvailableTags(): List<String> = tagDao.getAll()

    /**
     * 全量标签实体（含 [TagEntity.id]/[TagEntity.description]/[TagEntity.parentTagName]），
     * 供 `ai-tag-suggestions` 组装标签词表使用——[getAvailableTags] 只返回名字，AI 建议需要
     * 稳定 id 与描述文本。按 [TagEntity.sortOrder] 升序，与 [getAvailableTags] 同一顺序。
     */
    suspend fun getAvailableTagEntities(): List<TagEntity> = tagDao.getAllEntities()

    /**
     * 批量更新标签排序：将 [orderedNames] 的下标写入各标签的 `sortOrder`。
     * 由标签管理页在用户拖拽结束后调用。
     */
    suspend fun reorderTags(orderedNames: List<String>) {
        orderedNames.forEachIndexed { index, name ->
            tagDao.updateSortOrder(name, index)
        }
    }

    // ──────────────────── 标签层级关系（parentTagName 字段） ────────────────────
    //
    // 表达"细分标签属于某个大类标签"（如「甜妹」的上级是「颜值」），只服务于：
    // (1) 标签管理页展示/编辑层级；(2) 勾选界面（TagCheckGrid）选中子标签时顺手带出父标签
    // 的默认值；(3) AI 建议 prompt 的上下文。**不是写入约束**——[addTag]/[addTags]/[setTags]
    // 等打标签方法完全不读这个字段，写入的标签集合永远与调用方给出的一致，见 [setParentTag] KDoc。

    /**
     * 全量"标签名 → 上级标签名"映射，只含有上级的条目。供 [getAncestors]/[getDescendants]/
     * [setParentTag] 内部复用，也可直接供 UI（勾选界面默认值计算）使用。
     */
    suspend fun getParentMap(): Map<String, String> =
        tagDao.getAllEntities()
            .filter { it.parentTagName.isNotBlank() }
            .associate { it.tagName to it.parentTagName }

    /** [tagName] 的完整祖先链，从近到远（父、祖父……）；没有上级则为空列表。 */
    suspend fun getAncestors(tagName: String): List<String> =
        TagHierarchy.ancestorsOf(tagName, getParentMap())

    /** [tagName] 的全部后代（不限层级）；设置上级标签时用于过滤候选列表，防止选出环。 */
    suspend fun getDescendants(tagName: String): Set<String> =
        TagHierarchy.descendantsOf(tagName, getParentMap())

    /**
     * 设置 [tagName] 的上级为 [parentTagName]。设置前做环检测——若 [parentTagName] 是
     * [tagName] 自己或它的某个后代，拒绝这次设置，层级关系保持不变。
     *
     * 这个方法**只影响标签名册里的层级元数据**，不会给任何视频补写标签：细分标签成立、
     * 大类标签不成立的视频是真实存在的场景（例如内容符合某个细分特征但不适合归进对应大类），
     * 系统不应该、也不会强制"打了子标签就一定有父标签"。
     *
     * @return true 表示设置成功；false 表示会成环而被拒绝。
     */
    suspend fun setParentTag(tagName: String, parentTagName: String): Boolean {
        val parent = parentTagName.trim()
        if (parent.isEmpty()) {
            clearParentTag(tagName)
            return true
        }
        if (parent == tagName || parent in getDescendants(tagName)) return false
        tagDao.updateParentTagName(tagName, parent)
        return true
    }

    /** 清除 [tagName] 的上级，使其回到顶层标签。 */
    suspend fun clearParentTag(tagName: String) {
        tagDao.updateParentTagName(tagName, "")
    }

    // ──────────────────── 标签描述（description 字段） ────────────────────
    //
    // 人工填写的标签判断标准，供 ai-tag-suggestions 发起 AI 建议请求时理解标签语义、
    // 避免模型按自己的通用语义自行发挥。与视频打标签无关，是标签名册本身的元数据。

    /**
     * 全量"标签名 → 描述"映射，只含有已填写描述（非空）的条目。
     * 供标签管理页展示副标题预览，以及组装 AI 建议请求的标签词表上下文使用。
     */
    suspend fun getDescriptionMap(): Map<String, String> =
        tagDao.getAllEntities()
            .filter { it.description.isNotBlank() }
            .associate { it.tagName to it.description }

    /**
     * 设置或清除（传空字符串）单个标签的描述文本。
     * 这是标签名册本身的元数据编辑，**不计入** `downloaded_videos.tagEditCount`——
     * 那个字段统计的是"打标签"操作被编辑的次数，与标签描述完全是两个维度。
     */
    suspend fun setTagDescription(tagName: String, description: String) {
        tagDao.updateDescription(tagName, description.trim())
    }

    // ──────────────────── 视频打标签（video_tags 表） ────────────────────

    /** 为视频添加单个标签；重复调用幂等。 */
    suspend fun addTag(awemeId: String, tagName: String) {
        videoTagDao.insert(VideoTagEntity(awemeId = awemeId, tagName = tagName.trim()))
    }

    /** 为视频批量添加标签；已存在的自动跳过。 */
    suspend fun addTags(awemeId: String, tagNames: Collection<String>) {
        videoTagDao.insertAll(tagNames.map { VideoTagEntity(awemeId = awemeId, tagName = it.trim()) })
    }

    /**
     * 用新标签集合**覆盖**某视频的所有标签（先清空再写入）。
     * 用于标签编辑确认保存场景。
     */
    suspend fun setTags(awemeId: String, tagNames: Collection<String>) {
        videoTagDao.deleteAllForVideo(awemeId)
        videoTagDao.insertAll(tagNames.map { VideoTagEntity(awemeId = awemeId, tagName = it.trim()) })
    }

    // ──────────────── 用户编辑标签（额外累加 tagEditCount） ────────────────

    /**
     * **用户编辑入口**：用 [tagNames] 覆盖某视频的标签，并在集合确实变化时把
     * [DownloadedVideoEntity.tagEditCount] +1（一次编辑算一次，与增删了几个标签无关）。
     *
     * 与 [setTags] 的区别只在计数：程序自动打标签（如下载时关联收藏夹同名标签）
     * 走 [setTags]/[addTags]，不该污染"用户改过几次"的统计。
     *
     * @return true 表示标签集合发生了变化（已计数），false 表示原样确认（未计数）。
     */
    suspend fun setTagsAsUserEdit(awemeId: String, tagNames: Collection<String>): Boolean {
        val normalized = tagNames.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val before = videoTagDao.getTagsForVideo(awemeId).toSet()
        setTags(awemeId, normalized)
        val changed = before != normalized
        if (changed) incrementTagEditCount(listOf(awemeId))
        return changed
    }

    /**
     * **用户编辑入口**：给 [awemeIds] 批量追加 [tagNames]（多选后「设置标签」），
     * 并对**真的多出了新标签**的那些视频把 [DownloadedVideoEntity.tagEditCount] +1。
     *
     * 已经全部持有这些标签的视频不计数（这次操作对它没有实际改动）。
     *
     * @return 实际被计数（标签有变化）的视频条数。
     */
    suspend fun addTagsAsUserEdit(awemeIds: Collection<String>, tagNames: Collection<String>): Int {
        val normalized = tagNames.map { it.trim() }.filter { it.isNotEmpty() }
        if (normalized.isEmpty()) return 0
        val changedIds = mutableListOf<String>()
        awemeIds.distinct().forEach { awemeId ->
            // 逐条比对而非一次 IN 查询：批量选择可能上千条，避开 SQLite 变量上限。
            val before = videoTagDao.getTagsForVideo(awemeId).toSet()
            if (normalized.any { it !in before }) changedIds += awemeId
            addTags(awemeId, normalized)
        }
        if (changedIds.isNotEmpty()) incrementTagEditCount(changedIds)
        return changedIds.size
    }

    /**
     * `tagEditCount` 的原子累加（`SET tagEditCount = tagEditCount + 1`），
     * 按 500 一批规避 SQLite 变量上限。不要改成"读实体→改→整行 update"。
     */
    private suspend fun incrementTagEditCount(awemeIds: List<String>): Int {
        var updated = 0
        var i = 0
        while (i < awemeIds.size) {
            val part = awemeIds.subList(i, (i + 500).coerceAtMost(awemeIds.size))
            updated += downloadedVideoDao.incrementTagEditCount(part)
            i += 500
        }
        return updated
    }

    /**
     * 手工把 [awemeIds] 的 `tagEditCount` +1，**不动标签本身**。
     *
     * 补 v12 之前手工改过标签、库里没留下计数的历史记录：管理页先用「按标签修改次数筛选」
     * 筛出改过 0 次的，多选后从「设置标签」弹窗的「仅次数 +1」按钮触发。
     * SQL 是无条件 `+1`、**没有幂等标记**，重复点会重复累加，由 UI 的二次确认兜底。
     *
     * @return 实际被 +1 的记录数。
     */
    suspend fun bumpTagEditCountManually(awemeIds: Collection<String>): Int =
        incrementTagEditCount(awemeIds.distinct())

    /** 删除某视频的某个标签。 */
    suspend fun removeTag(awemeId: String, tagName: String) {
        videoTagDao.delete(awemeId, tagName)
    }

    /** 清空某视频的所有标签。 */
    suspend fun clearTags(awemeId: String) {
        videoTagDao.deleteAllForVideo(awemeId)
    }

    /** 查询某视频已打的所有标签，按字母顺序返回。 */
    suspend fun getTagsForVideo(awemeId: String): List<String> =
        videoTagDao.getTagsForVideo(awemeId)

    /**
     * 批量查询多个视频的标签，返回 awemeId → 标签列表 的 Map。
     * 未打过标签的视频不在 Map 中（取时用 [getOrDefault] 返回空列表）。
     */
    suspend fun getTagsMapForVideos(awemeIds: List<String>): Map<String, List<String>> {
        if (awemeIds.isEmpty()) return emptyMap()
        return videoTagDao.getTagsForVideos(awemeIds)
            .groupBy({ it.awemeId }, { it.tagName })
    }

    /** 列出 `video_tags` 中实际使用过的标签（去重，按字母顺序）。 */
    suspend fun getAllUsedTags(): List<String> =
        videoTagDao.getAllTags()

    /**
     * 打了该标签的全部 awemeId（供「标签精细检索」在内存里做集合运算）。
     *
     * 精细检索要的是几个标签各自的 id 集合、而不是实体，交给调用方按
     * [com.blitz.downloader.model.filter.TagQuery] 的左结合语义组合。
     */
    suspend fun getAwemeIdsByTag(tagName: String): List<String> =
        videoTagDao.getAwemeIdsByTag(tagName)

    /**
     * 按标签筛选视频，返回完整 [DownloadedVideoEntity] 列表，按下载时间倒序。
     */
    suspend fun getVideosByTag(tagName: String): List<DownloadedVideoEntity> =
        videoTagDao.getVideosByTag(tagName)

    /**
     * 按多个标签筛选视频（管理页标签栏多选），按下载时间倒序。
     *
     * @param matchAll true = 交集（同时含全部标签），false = 并集（含任一标签）。
     *                 匹配方式由用户在设置页决定，见 `AppSettings.isTagFilterMatchAll`。
     * @return [tagNames] 为空时返回空列表——「没选标签」由调用方走不筛选的路径，不该落到这里。
     */
    suspend fun getVideosByTags(
        tagNames: Collection<String>,
        matchAll: Boolean,
    ): List<DownloadedVideoEntity> {
        val distinct = tagNames.distinct()
        if (distinct.isEmpty()) return emptyList()
        if (distinct.size == 1) return videoTagDao.getVideosByTag(distinct.first())
        return if (matchAll) {
            videoTagDao.getVideosByAllTags(distinct, distinct.size)
        } else {
            videoTagDao.getVideosByAnyTag(distinct)
        }
    }

    /** 统计每个标签对应的视频数，按数量倒序。 */
    suspend fun getTagsWithCount(): List<VideoTagDao.TagCount> =
        videoTagDao.getTagsWithCount()

    /**
     * 每条视频的标签数映射（awemeId → 标签数），供管理页「按标签数量筛选」使用。
     * 只包含**有标签**的视频；map 里查不到的 awemeId 表示 0 个标签，
     * 调用方用 `map[id] ?: 0` 取值即可，不必先查全库 id。
     */
    suspend fun getTagCountMap(): Map<String, Int> =
        videoTagDao.getTagCountPerVideo().associate { it.awemeId to it.count }

    // ──────────────── 作者高频标签缓存（author_tag_frequency 表） ────────────────

    /** [recomputeAuthorTagFrequency] 的结果摘要，供设置页提示文案用。 */
    data class TagFrequencyAnalysisResult(val authorCount: Int, val tagRowCount: Int)

    /**
     * 全量重算 `author_tag_frequency` 缓存表：清空后按 `videoAuthorSecUserId + tagName`
     * 重新聚合。**不随打标签/下载操作自动增量更新**，只由设置页「重新分析标签数据」触发。
     */
    suspend fun recomputeAuthorTagFrequency(): TagFrequencyAnalysisResult {
        authorTagFrequencyDao.recompute()
        return TagFrequencyAnalysisResult(
            authorCount = authorTagFrequencyDao.countDistinctAuthors(),
            tagRowCount = authorTagFrequencyDao.countRows(),
        )
    }

    /**
     * 某作者出现次数达到 [threshold] 的高频标签（按次数倒序），供批量打标签弹窗自动预勾选。
     * [threshold] 只在读取时过滤——改阈值不需要重新调用 [recomputeAuthorTagFrequency]。
     * [secUserId] 为空（老记录无稳定作者 ID）时直接返回空列表。
     */
    suspend fun getHighFrequencyTagsForAuthor(secUserId: String, threshold: Int): List<String> =
        if (secUserId.isBlank()) emptyList() else authorTagFrequencyDao.getHighFrequencyTags(secUserId, threshold)

    /**
     * 供 `ai-tag-suggestions` 组装 AI 建议请求的作者先验：附带该作者已下载视频总数与
     * 每个高频标签的占比（而不是只给一份不带权重的标签名单），见 design.md Decision 15。
     * [secUserId] 为空或没有达到 [threshold] 的标签时返回 `null`——`TagSuggestionRequestBuilder`
     * 按 `null` 处理为"不携带作者先验"。
     *
     * 数量与层级控制（`ai-author-tag-pruning`）：
     * - 至多选取 4 个高频标签（[TagHierarchy.pruneAuthorHighFreqTags]）；
     * - 若前 4 个中包含父子/祖先标签（如「颜值」与「纯欲」），自动剔除父标签并顺延增选，
     *   保证提供给大模型的先验聚焦于具体细分特征，避免因作者视频多导致标签泛滥全量倾倒。
     *
     * **不影响** [getHighFrequencyTagsForAuthor]：两者各自独立读同一张 `author_tag_frequency`
     * 缓存表，批量打标签弹窗的预勾选行为不受这个方法影响。
     */
    suspend fun getAuthorProfileForAi(secUserId: String, threshold: Int): AuthorProfile? {
        if (secUserId.isBlank()) return null
        val rows = authorTagFrequencyDao.getHighFrequencyTagsWithRatio(secUserId, threshold)
        if (rows.isEmpty()) return null
        val parents = getParentMap()
        val prunedRows = TagHierarchy.pruneAuthorHighFreqTags(
            candidates = rows,
            getTagName = { it.tagName },
            parents = parents,
            maxCount = 4,
        )
        if (prunedRows.isEmpty()) return null
        return AuthorProfile(
            secUserId = secUserId,
            sampleCount = rows.first().sampleCount,
            topTags = prunedRows.map { AuthorTagRatio(it.tagId, it.tagName, it.count, it.ratio) },
        )
    }
}

/** [VideoTagRepository.getAuthorProfileForAi] 的返回结果，对应评审文档第 9 节的 AuthorProfile。 */
data class AuthorProfile(
    val secUserId: String,
    /** 该作者已下载视频总数。 */
    val sampleCount: Int,
    val topTags: List<AuthorTagRatio>,
)

/** [AuthorProfile.topTags] 单项；[ratio] 为 `null` 表示分母为 0（理论不应发生，仅作防御）。 */
data class AuthorTagRatio(
    val tagId: Long,
    val tagName: String,
    val count: Int,
    val ratio: Float?,
)
