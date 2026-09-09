package com.blitz.downloader.model

/**
 * 标签父子关系的纯函数计算——不依赖 Room/Compose，供 [com.blitz.downloader.data.VideoTagRepository]
 * 与 Compose 侧（`TagCheckGrid` 的 `onToggle`）共用同一份逻辑，避免各写一份产生偏差。
 *
 * [parents] 统一约定为"标签名 → 上级标签名"的映射，只含有上级的条目，对齐
 * [com.blitz.downloader.data.db.TagEntity.parentTagName] 的语义：查不到即无上级。
 */
object TagHierarchy {

    /**
     * [tagName] 的完整祖先链，从近到远（父、祖父……）；没有上级则返回空列表。
     * 用 [seen] 兜底防环——正常情况下 `setParentTag` 的环检测已经保证数据不会成环，
     * 这里只是防御性处理，避免万一出现脏数据时死循环。
     */
    fun ancestorsOf(tagName: String, parents: Map<String, String>): List<String> {
        val result = mutableListOf<String>()
        val seen = mutableSetOf(tagName)
        var current = parents[tagName]
        while (!current.isNullOrBlank() && current !in seen) {
            result += current
            seen += current
            current = parents[current]
        }
        return result
    }

    /** [tagName] 的全部后代（不限层级），用于设置上级时的候选列表过滤（排除自身后代，防环）。 */
    fun descendantsOf(tagName: String, parents: Map<String, String>): Set<String> {
        val childrenOf = parents.entries.groupBy({ it.value }, { it.key })
        val result = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue += tagName
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (child in childrenOf[current].orEmpty()) {
                if (result.add(child)) queue += child
            }
        }
        return result
    }

    /**
     * 对作者高频候选标签进行层级去重与上限截断，供 AI 建议（`AuthorProfile`）使用：
     * 1. 优先检视频次降序的前 [maxCount] 个候选标签；
     * 2. 若检视集合中存在父子/祖先包含关系（如同时包含「颜值」与「纯欲」），
     *    剔除较宽泛的父标签/祖先标签（保留更具体的细分标签）；
     * 3. 剔除后若数量不足 [maxCount]，继续从后续候选池中顺延增选；
     *    增选时若候选是已选标签的祖先、或已选标签中已有其祖先，自动跳过，避免层级重叠；
     * 4. 最终选出的标签集合中，任意两个标签互无祖先/后代包含关系，且总数不超过 [maxCount]。
     *
     * @param candidates 按频次倒序排列的候选标签项列表
     * @param getTagName 提取标签名称的函数
     * @param parents 标签名 -> 上级标签名 的映射
     * @param maxCount 最大保留数量，默认 4
     */
    fun <T> pruneAuthorHighFreqTags(
        candidates: List<T>,
        getTagName: (T) -> String,
        parents: Map<String, String>,
        maxCount: Int = 4,
    ): List<T> {
        if (candidates.isEmpty() || maxCount <= 0) return emptyList()

        val initialBatch = candidates.take(maxCount)
        val initialNames = initialBatch.map(getTagName).toSet()
        val discardedAncestors = mutableSetOf<String>()

        // 识别 initialBatch 内部所有的祖先标签（大类标签）
        for (item in initialBatch) {
            val name = getTagName(item)
            val ancestors = ancestorsOf(name, parents)
            for (anc in ancestors) {
                if (anc in initialNames) {
                    discardedAncestors.add(anc)
                }
            }
        }

        val selected = mutableListOf<T>()
        val selectedNames = mutableSetOf<String>()

        for (item in initialBatch) {
            val name = getTagName(item)
            if (name !in discardedAncestors && selectedNames.add(name)) {
                selected.add(item)
            }
        }

        // 若剔除父标签后未达到 maxCount，从剩余候选池顺延增选
        if (selected.size < maxCount && candidates.size > maxCount) {
            val remainingPool = candidates.drop(maxCount)
            for (item in remainingPool) {
                if (selected.size >= maxCount) break
                val name = getTagName(item)
                if (name in discardedAncestors || name in selectedNames) continue

                // 若该候选是已选标签中任意一个的祖先，跳过并记录
                val isAncestorOfSelected = selectedNames.any { selectedName ->
                    name in ancestorsOf(selectedName, parents)
                }
                if (isAncestorOfSelected) {
                    discardedAncestors.add(name)
                    continue
                }

                // 若该候选的祖先已在已选标签中，避免同类细分重叠，跳过
                val ancestors = ancestorsOf(name, parents)
                val hasAncestorInSelected = ancestors.any { it in selectedNames }
                if (hasAncestorInSelected) {
                    continue
                }

                if (selectedNames.add(name)) {
                    selected.add(item)
                }
            }
        }

        return selected
    }
}
