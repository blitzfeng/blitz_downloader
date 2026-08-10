# 标签精细检索（TagQuery）设计

日期：2026-08-10
范围：管理页（`ManageActivity`）视频 Tab 的标签筛选

## 背景与目标

现有标签筛选只有「标签栏多选 + 设置页交集/并集开关」一种口径：选中的若干标签要么全取交集、要么全取并集，无法表达「A 且 B，或 C，且非 D」这类混合条件，也无法排除某个标签。

本功能新增一个独立的标签筛选入口——**标签精细检索**：用户以一个基准标签为起点，逐行追加「包含 / 不包含 / 或」规则，得到一个可混用三种集合运算的结果集。

## 用户交互

溢出菜单点「标签精细检索」弹出对话框：

```
行1: [美女  ▾] [包含   ▾] [舞蹈  ▾]  (+)
行2: [美女    ] [或     ▾] [唱歌  ▾]  (−)
行3: [美女    ] [不包含 ▾] [广告  ▾]  (+)
     ────────────────────────────
     美女 且 舞蹈 或 唱歌 且非 广告
     [清除筛选]        [取消]  [确定]
```

- 三个下拉都是 `Spinner`，可选标签沿用标签栏那份数据（`VideoTagRepository.getAvailableTags()` = `tags` 表全量，含未关联任何视频的预设标签）。
- 第一行首列是**基准标签**，可选；第二行起首列是置灰 `TextView`，显示同一个基准标签名、不可点。改基准标签时所有置灰行同步刷新。
- `+` 只挂在最后一行，中间行显示 `−`；只剩一行时不显示 `−`。
- 底部一行实时表达式预览。左结合语义靠置灰的基准标签名看不出来，这行文字用于消除误解。
- 「清除筛选」= 提交空 `TagQuery`，回到不按精细检索筛选的状态。

## 求值语义：从上到下左结合

结果集从基准标签的记录集合出发，**按行序**依次运算：

```
label1 = A，行1「包含 B」，行2「或 C」，行3「不包含 D」
结果 = ((A ∩ B) ∪ C) − D
```

- `包含` → `∩`，`不包含` → `−`，`或（取并集）` → `∪`
- 行序影响结果。本期不做拖拽排序，用户要调顺序就删了重加。
- 不支持括号 / 嵌套。
- label2 未选的行在「确定」时直接忽略；一行规则都没有 = 只按基准标签筛。
- 基准标签为空 = 该层未激活。

## 数据模型

新增 `model/filter/TagQuery.kt`：

```kotlin
enum class TagQueryOp { AND, NOT, OR }          // 包含 / 不包含 / 或（取并集）

data class TagQueryRule(val op: TagQueryOp, val tag: String)

data class TagQuery(
    val base: String = "",
    val rules: List<TagQueryRule> = emptyList(),
) {
    val isActive: Boolean get() = base.isNotBlank()

    /** base 与各行 tag 的并集，供调用方一次性把这些标签的 id 集合查出来。 */
    val involvedTags: Set<String>

    /** 左结合求值。入参是「标签名 → 该标签下全部 awemeId」，本函数不做任何 IO。 */
    fun evaluate(idsByTag: Map<String, Set<String>>): Set<String>
}
```

`evaluate` 是纯函数、无 Android 依赖，可直接写 JVM 单测。

## 接入筛选栈

`ManageFilterState` 新增第七层 `tagQuery: TagQuery = TagQuery()`。

**与标签栏多选互斥**（同一时刻只有一种标签口径，避免「标签栏选了 A、规则又说不包含 A」的矛盾）：

- 新增 `withTagQuery(q)`：设置 `tagQuery`，清 `tags` / `searchQuery` / `authorSecId` / `authorName`。
- 已有的 `withTags` / `withSearchQuery` / `withAuthor` 反向清 `tagQuery`。

互斥清理规则仍然只写在这四个 `with*` 里，调用处不手动清另外几项。

归属 / 标签数量 / 标签修改次数三层与本层是**叠加**关系，照旧经 `postProcess` 生效。

`menuTitleSignature` 当前是 `Triple<ManageRelationFilter, Set<ManageTagCountFilter>, ManageTagEditCountFilter>`，位置不够，改成具名 data class 并加入 `tagQuery` 的规则指纹——菜单标题要回显规则数，不加进去菜单不会重建。仍然**不要**把整个 `ManageFilterState` 塞进去：`searchQuery` 每敲一个字都变，菜单跟着重建会把 SearchView 一起重建掉。

## 取数链路

`ManageTabViewModel.queryPage` 与 `loadFullScopeEntities` 各加一条分支，**放在 `when` 的最前面**：

```
f.tagQuery.isActive -> oneShot(firstPage) { postProcess(loadTagQueryEntities()) }
```

必须放最前：现有的 `f.tags.isEmpty() && f.hasMemoryOnlyFilter` 分支在 `tags` 为空时会命中，而精细检索激活时 `tags` 恒为空（两者互斥），不排在前面就会被那条分支截走。

`hasMemoryOnlyFilter` **不**加入 `tagQuery`：该标志的作用是让「本来能分页的路径」切成全量，而精细检索分支自己就是 `oneShot`，加进去只会造成两处表达同一件事。

`loadTagQueryEntities()`：

1. 对 `involvedTags` 里每个标签调一次已有的 `VideoTagDao.getAwemeIdsByTag`（涉及标签最多几个，交集/并集的粗筛在 SQL 侧完成）。
2. `TagQuery.evaluate(idsByTag)` 算出 awemeId 集合。
3. `repo.getAllByMediaType(mediaType).filter { it.awemeId in ids }`。

选这条路径而不是新增 `WHERE awemeId IN (:ids)` 的 DAO：零新增 DAO、绕开 SQLite 的变量数上限，代价是全量读一次该 mediaType——与现有「标签数量 / 标签修改次数」两层的做法一致，库规模是个人下载量，可以接受。

因此本层与那两层同属**只有内存实现**的筛选：激活时必须走 `oneShot` 全量路径、不分页，否则会出现「一页 20 条筛剩 2 条、撑不满屏不触发滚动加载」看起来像数据丢了。

`VideoTagRepository` 补一个薄封装 `getAwemeIdsByTag(tagName): List<String>`（DAO 方法已存在，Repository 层还没暴露）。

## UI 与入口

- 新增 `dialog/TagQueryDialog.kt`（按包结构约定，对话框构造器放 `dialog/`），布局 `dialog_tag_query.xml` + 行布局 `item_tag_query_rule.xml`。规则行用竖向 `LinearLayout` 动态增删，行数少，不上 RecyclerView。
- `ManageActivity` 溢出菜单在「按标签修改次数筛选」下面加一项 `action_filter_tag_query`，**只在视频 Tab 显示**（与「按标签数量筛选」等同一套显隐逻辑），标题回显规则数：`标签精细检索` / `标签精细检索 (2)`。
- `ManageViewModel` 加 `applyTagQuery(tab, query)`，条件下行链路与现有各层一致（Activity 改 `ManageViewModel.filters` → Fragment 观察 → 转发给自己的 `ManageTabViewModel`）。
- `ManageEmptyReason` 新增 `TagQuery(query)`，在 `emptyReason()` 的 `when` 里排在 `f.tags.isNotEmpty()` 之前（与取数分支同理）；具体文案在 `ManageVideoFragment` 里用 `R.string` 拼——ViewModel 不碰 `R.string`。

## 测试

新增纯 JVM 单测 `TagQueryTest`，覆盖：

- 左结合行序（`((A ∩ B) ∪ C) − D` 与调换行序的结果不同）
- 三种运算符各自的行为
- label2 为空的行被忽略
- 基准标签查无记录时结果为空
- 无规则时结果 = 基准标签集合

前置：`gradle/libs.versions.toml` 里 `junit = "4.14-SNAPSHOT"` 是快照版、当前解析不到，`./gradlew test` 会失败在依赖解析。本次一并钉到 `4.13.2`。

## 不做

- 筛选条件持久化（与其他筛选层一致，只活在 `ManageViewModel` 内存里，退出管理页即失效）
- 规则行拖拽排序
- 括号 / 嵌套表达式
- 图片 Tab 的入口（图片 Tab 无标签功能，`loadsUserTags = false`）

## 文档同步

- `CLAUDE.md`「管理页的筛选栈」：表格加一行「标签精细检索 → `tagQuery` → 只在内存」，并补一段左结合语义与「与标签栏多选互斥」的约束。
- 无数据库结构变更，`.cursor/rules/db-schema.md` 不动。
