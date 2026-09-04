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
}
