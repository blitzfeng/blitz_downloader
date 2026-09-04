## Context

见 `proposal.md` 了解动机。当前 `TagEntity`（`tags` 表）只有 `tagName`（PK）与 `sortOrder` 两个字段；`VideoTagRepository` 的标签名册管理（`createTag`/`renameTag`/`deleteTag`/`getAvailableTags`）与视频打标签（`addTag`/`addTags`/`setTags`/`setTagsAsUserEdit`/`addTagsAsUserEdit`）是同一个 Repository 里两组界限清楚的方法（见其类头 KDoc）。本变更建议排在 `ai-tag-suggestions` 之前或并行实现，理由见 proposal 的 Capabilities 小节。

## Goals / Non-Goals

**Goals:**
- 标签间可表达"至多一个上级"的树形包含关系（森林结构）。
- 层级关系驱动勾选界面的**默认值**（选子标签顺手带出父标签），但任何时候都可被用户独立覆盖，不做强制。
- 标签管理页能安全地查看/设置/清除上级关系，带环检测。

**Non-Goals:**
- 不支持一个标签有多个上级（不是 DAG，是森林/树）——多上级会让"祖先链"变成"祖先集合"，计算和环检测都复杂得多，而当前场景（大类→细分）不需要这种表达力。
- 不做可视化拖拽树编辑器——用选择器（列表 + 单选）编辑父子关系即可，可视化树是明显更大的 UI 投入，与当前"几十个标签、两三层"的实际规模不成比例。**这是用户明确认可的后续优化方向，不是被否决**：v1 先用选择器验证数据模型和交互是否顺手，树形可视化留到之后单独立项。
- **不做任何形式的写入时强制注入**——这是本设计明确否决的方案（见 Decision 2），层级关系只影响 UI 默认值与 AI 上下文，不影响实际写入哪些标签。
- 不限制层级深度——允许任意深度的链，虽然实际使用大概率就两三层。
- 不改变收藏夹自动打标签（`ensureCollectFolderTagLinked`）的行为——它不感知层级关系，也不需要感知。

## Decisions

### 1. 数据模型：`tags` 表加一列 `parentTagName`，不新建关联表
`TagEntity` 新增 `parentTagName: String = ""`（空字符串 = 无上级，对齐项目里 `videoAuthorSecUserId` 等字段"空字符串表示未设置"的既有惯例）。
**替代方案**：新建 `tag_hierarchy(tagName, parentTagName)` 关联表——因为是 1:1（每个标签至多一个上级），关联表纯属多余，`TagEntity` 已经在用列而不是关联表表达 `sortOrder` 这类简单属性，风格上保持一致。

### 2. 明确否决"写入时强制注入祖先标签"
最初设计是在 `VideoTagRepository` 的底层写入方法里自动把祖先标签一并写入，被否决——真实场景里"符合子标签但不符合父标签"的视频是存在的（比如内容符合某个细分标签的特征，但恰好不适合归进对应的大类），强制注入会让这种正确的标签组合打不出来，而且就算 UI 层允许取消，只要 Repository 在保存时又把父标签加回去，用户体验上等于"看似能取消、实则取消不掉"，比完全不做这个功能还糟。
**结论**：`VideoTagRepository` 的标签写入方法（`addTag`/`addTags`/`setTags` 及其 UserEdit 变体）**不做任何改动**，写入的标签集合完全由调用方决定。层级关系只通过只读查询方法（`getAncestors`/`getDescendants`）暴露给需要它的地方（UI 默认值计算、AI prompt 组装），不侵入写入路径本身。

### 3. 祖先链现查，不做物化闭包表
写入时从该标签的 `parentTagName` 开始逐级往上查，直到空字符串为止；环检测已经保证这条链一定有限长。标签规模是个人使用量级（几十个），链长不会超过个位数，不需要为查询性能预先物化闭包关系。

### 4. 环检测：设置 A 的上级为 B 前，先看 B 的祖先链里有没有 A
`VideoTagRepository` 新增 `setParentTag(tagName, parentTagName)`：先沿 `parentTagName` 走一遍其祖先链，若链上出现 `tagName` 自己，拒绝这次设置（返回失败/不生效，具体错误呈现方式留给 UI 层决定）。

### 5. 重命名 / 删除的级联处理
- `renameTag(old, new)`：现有实现只更新 `tags` 表自身的行与 `video_tags` 里的 `tagName` 引用；本变更追加一步——把所有 `parentTagName = old` 的行同步改成 `new`。
- `deleteTag(name)`：现有实现删标签名册行 + 该标签在所有视频上的关联；本变更追加一步——把所有 `parentTagName = name` 的行清空为 `""`（不级联删除这些子标签本身，见 spec 对应场景）。

### 6. 标签管理页：沿用现有 `item_tag_manage.xml` 行结构，加一个图标入口 + 单选弹窗
现有每行是「拖拽把手 / 标签名 / 编辑图标 / 删除图标」（纯 XML，`AlertDialog` 弹窗改名/删除）。本变更：
- 标签名下面加一行小灰字副标题，有上级就显示"上级：颜值"，没有则不显示该行（行高按内容变化，不强制拉高每一行）。
- 编辑/删除图标右边再加一个"设置上级"图标，点击弹出 `AlertDialog.setSingleChoiceItems` 单选列表——复用项目里设置页画质偏好/高频阈值那几个选择器已经用过的同一套模式，不引入新交互范式。第一项固定"无（顶层标签）"，其余候选 = 全部标签 − 自身 − 自身的全部后代（用 `getDescendants` 过滤，防止选出环）。选中后调 `setParentTag`/`clearParentTag`，成功则刷新该行副标题。
- **列表本身不做树形缩进/分组展示**，仍按 `sortOrder` 平铺，拖拽排序逻辑不受影响——显示顺序（`sortOrder`）与层级关系（`parentTagName`）是两个独立属性。可视化树形 UI 是用户认可的后续优化方向（见 Non-Goals），这一版先用选择器验证数据模型和基本交互。

### 7. `TagCheckGrid` 的默认值计算：改宿主的 `onToggle`，组件本身不用动
`TagCheckGrid` 组件继续保持"纯展示 + `checked`/`onToggle`"的哑组件形态，不需要感知层级关系。改动在两个宿主（`TagEditDialogFragment`、`BatchTagDialogFragment`）各自传给它的 `onToggle` lambda 里：

```kotlin
onToggle = { tag ->
    if (tag in checked) {
        checked.remove(tag)                                  // 取消：只影响这一个标签，不联动
    } else {
        checked.add(tag)
        val parent = parentMap[tag]
        if (!parent.isNullOrBlank()) checked.add(parent)      // 选中：顺手带上父标签默认值
    }
}
```

`checked` 本来就是驱动 Compose 渲染的状态，父标签被加进集合的瞬间对应 chip 立即显示选中，不需要额外刷新逻辑；取消动作永远只删被点的那一个，"子标签选中时父标签点不掉"这类阻拦不存在，直接对应上一轮反馈修正的问题。`parentMap: Map<String, String>`（标签名 → 上级标签名，只含有上级的条目）由 `VideoTagRepository` 新增方法提供，跟着 `allTags` 一起，用与 `preCheckedTags` 相同的方式往下传（`show()` 多一个参数 → `arguments` → Compose 内容）。不加"自动带上"的额外提示文案——这条规则是用户自己在标签管理页配置的，不是像 AI 建议那样来源不透明，联动本身可预期，不需要解释。

## Risks / Trade-offs

- **[风险] 层级关系的语义正确性（是否真的该是父子）系统不做校验** → 只保证结构一致（无环），语义对不对是用户自己的责任，与创建标签本身不做语义校验是一回事。
- **[风险] 环检测/祖先后代计算需要遍历标签链** → 个人使用量级下标签数与链长都很小，性能不是问题。
- **[风险] "顺手带上父标签"的默认值可能不是用户想要的，增加一次多余的取消操作** → 这正是本设计从"强制"改成"默认值"后主动接受的取舍：多一次可能的取消操作，换来"永远不会打不出正确标签组合"这个更重要的正确性保证。

## Migration Plan

- `tags` 表新增 `parentTagName TEXT NOT NULL DEFAULT ''`，纯增量迁移。
- **版本号排期**：这个变更、`ai-tag-suggestions`、`batch-ai-tag-review` 三个变更都会各自给 `AppDatabase.version` +1；实际版本号取决于三者的合入顺序，谁先合入谁占用当前的"下一个版本号"，不能预先假设自己是固定的某个版本——实现阶段按当时 `AppDatabase.kt` 的实际版本号顺延即可，这句只是提醒实现者别把三个变更的迁移都硬编码成同一个版本号。
- 回滚：新增列 + 默认值，不影响既有数据，回滚只需不发布这次迁移。
