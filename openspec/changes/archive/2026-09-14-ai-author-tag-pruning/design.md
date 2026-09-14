## Context

当前 AI 建议标签流程中，`AiTagSuggestionRepository.requestSuggestion` 通过 `VideoTagRepository.getAuthorProfileForAi(secUserId, threshold)` 获取该作者的历史高频标签及出现次数、占比。
当前实现只以 `atf.count >= threshold`（默认 2）过滤并全量返回所有符合条件的标签行。如果一个作者下载的视频较多，会有十几个甚至几十个标签全部被塞进 Gemini 提示词中作为先验，不仅浪费 token、稀释视觉证据权重，且常并存父类标签与子类标签（如「颜值」与「纯欲」），导致大模型容易偏向泛化父标签。

现有代码库中已有：
- `TagHierarchy`（`com.blitz.downloader.model.TagHierarchy`）：纯函数，提供 `ancestorsOf(tagName, parents)` 与 `descendantsOf(tagName, parents)`，内置防环机制。
- `VideoTagRepository.getParentMap()`：获取 `tagName -> parentTagName` 映射。

## Goals / Non-Goals

**Goals:**
- 将传递给 AI 建议的作者高频标签数量限制为至多 4 个（`MAX_AI_AUTHOR_TAGS = 4`）。
- 实现纯函数算法 `pruneAuthorHighFreqTags`：
  1. 初始从满足频次阈值的高频池中按频次/占比降序取候选；
  2. 若已选标签中包含父子/祖先重叠，剔除父标签/祖先标签（保留更具象的子标签）；
  3. 从候选池中继续向下增选，跳过与已选标签存在祖先关系的候选，直至补足 4 个或候选池耗尽；
  4. 最终选出的标签集合中，任意两个标签之间均不存在祖先-后代包含关系。
- 逻辑提取为纯函数，编写详尽的 JVM 单元测试覆盖各类边界场景。

**Non-Goals:**
- 不改变批量打标签弹窗自动预勾选（`getHighFrequencyTagsForAuthor`）和管理页快捷筛选块。
- 不修改数据库表结构，不升级 `AppDatabase` 版本号。
- 不在 UI 设置项中暴露 4 的上限配置（用户确认固定为 4）。

## Decisions

### Decision 1: 算法位置与纯函数抽取
在 `com.blitz.downloader.model.TagHierarchy`（或新建纯函数对象）中增加通用的 `pruneAuthorHighFreqTags` 纯函数：
```kotlin
fun <T> pruneAuthorHighFreqTags(
    candidates: List<T>,
    getTagName: (T) -> String,
    parents: Map<String, String>,
    maxCount: Int = 4,
): List<T>
```
**理由**：
- 纯函数无 Android/Room 依赖，方便直接编写 JVM 单元测试；
- `VideoTagRepository.getAuthorProfileForAi` 查询出 `AuthorTagRatioRow` 列表和 `parents = getParentMap()` 后，直接调用此纯函数进行过滤与截断。

### Decision 2: 过滤与增选算法流程
设 `pool` 为按 `count DESC` 排序的候选列表：
1. 初始维护结果列表 `selected = mutableListOf<T>()`，以及已剔除的父标签集合 `discardedAncestors = mutableSetOf<String>()`。
2. 先从 `pool` 中顺序检视前 4 个元素；
   - 若这 4 个元素内部存在层级关系（某个标签是另一个标签的祖先），将所有祖先标签加入 `discardedAncestors`，其余标签加入 `selected`；
   - 若不存在层级关系，直接将这 4 个元素加入 `selected`。
3. 若 `selected.size < maxCount` 且 `pool` 仍有未处理的后续元素：
   - 依次遍历后续候选 `candidate`：
     - 若 `candidate` 的标签名已在 `selected` 或 `discardedAncestors` 中，跳过；
     - 若 `candidate` 是 `selected` 中任意标签的祖先（即属于更泛化的大类），跳过并加入 `discardedAncestors`；
     - 若 `candidate` 是 `selected` 中某个已有标签的后代：由于前序标签已入选且频次更高，为避免同类并存，跳过 `candidate`；
     - 否则，将 `candidate` 加入 `selected`；
     - 当 `selected.size == maxCount` 时终止遍历。
4. 返回 `selected`。

**替代方案考虑**：
- *流式贪心挑选*：直接从头逐个扫描，新元素若与已选元素冲突则踢出父级。
  *对比*：用户明确描述了"如果前 4 个标签中包含父子标签，则去掉父标签，再从高频标签池里增选一个"的认知模型，上述算法步骤严格复现了这一心智模型，同时保证结果中互无层级冲突。

## Risks / Trade-offs

- [候选池耗尽不足 4 个] → 正常返回实际去重后的个数（0~3 个），符合预期，无需且不能强行填充不满足频次的标签。
- [多层祖先关系（A -> B -> C）] → `TagHierarchy.ancestorsOf` 递归向上解析完整祖先链，无论嵌套多少层均能完整识别并剔除上层大类。
- [循环引用的脏数据] → `TagHierarchy.ancestorsOf` 已内置 `seen` 集合检测环，不会死循环。
