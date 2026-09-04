## 1. 数据层

- [x] 1.1 `TagEntity.kt` 新增 `parentTagName: String = ""`，验证：字段与 design.md 数据模型一致
- [x] 1.2 `TagDao.kt` 新增按 `tagName` 查 `parentTagName`、按 `parentTagName = X` 查子标签列表、更新 `parentTagName` 这几个方法，验证：能正确读写单行的上级引用
- [x] 1.3 `AppDatabase.kt` 新增迁移（v16→v17），只加这一列，验证：`./gradlew.bat testDebugUnitTest assembleDebug -q` 通过
- [x] 1.4 更新 `.cursor/rules/db-schema.md`：`tags` 表字段说明加一行，版本演进历史加一行，验证：文档与迁移代码一致

## 2. Repository 层：层级关系维护

- [x] 2.1 新增共享纯函数 `model/TagHierarchy.kt`：给定 parent 映射，计算某标签的祖先链、后代集合，验证：`TagHierarchyTest` 单元测试覆盖多层链/无上级/环兜底/多层后代场景，全部通过
- [x] 2.2 `VideoTagRepository` 新增 `getAncestors(tagName)`/`getDescendants(tagName)`（内部调用 2.1 的纯函数 + `getParentMap()`），验证：编译通过；核心递归逻辑已由 2.1 的纯函数单测覆盖，Repository 侧是薄封装，走人工验证（无 Robolectric，与项目现有 DB 相关代码同一测试策略）
- [x] 2.3 新增 `setParentTag(tagName, parentTagName)`：设置前用 `getDescendants` 做环检测，检测到环则拒绝，验证：编译通过，环检测逻辑复用已单测的 `descendantsOf`，人工走查见 6.2
- [x] 2.4 新增 `clearParentTag(tagName)`（`setParentTag` 传空字符串时也会走到同一实现），验证：编译通过
- [x] 2.5 `renameTag` 追加 `tagDao.reassignChildren(oldName, trimmed)`，验证：编译通过，人工走查见 6.2
- [x] 2.6 `deleteTag` 追加 `tagDao.reassignChildren(tagName, "")`，验证：编译通过，人工走查见 6.2

## 3. 标签管理页 UI

- [x] 3.1 `item_tag_manage.xml` 标签名下方加一行小灰字副标题（`tvTagParent`，有上级才显示，格式"上级：颜值"），`TagManageAdapter.bind` 按 `parentMap` 决定该行是否展示，行高改 `wrap_content` 不再固定 52dp
- [x] 3.2 新增图标 `ic_tag_parent.xml` 与按钮 `btnSetParentTag`，点击调用 `TagManageViewModel.requestParentPicker` → `AlertDialog.setSingleChoiceItems`，第一项固定"无（顶层标签）"，候选来自 ViewModel 侧算好的"全部标签 − 自身 − 全部后代"
- [x] 3.3 选中候选项统一走 `VideoTagRepository.setParentTag`（空字符串即清除，内部转发给 `clearParentTag`），成功后 `TagManageAdapter.updateParent` 只刷新该行
- [x] 3.4 排序（`sortOrder`）与拖拽逻辑未改动；重命名/删除时额外调用 `renameParentReferences`/`clearParentReferences` 同步 Adapter 本地 `parentMap`，与 Repository 侧的级联保持一致

## 4. TagCheckGrid 可覆盖的默认值

- [x] 4.1 `VideoTagRepository` 新增 `getParentMap(): Map<String, String>`（标签名 → 上级标签名，只含有上级的标签）——已在 2.x 阶段实现，供 `getAncestors`/`getDescendants` 与本节共用
- [x] 4.2 `TagEditDialogFragment`/`BatchTagDialogFragment` 的 `show()` 新增 `parentMap` 参数（两个并行 `ArrayList<String>` 编解码，因 Bundle 不能直接存 Map），`ManageVideoViewModel.requestTagEditor`/`requestBatchTagPicker` 取数时一并查 `getParentMap()`，通过 `ManageTabEvent`→`ManageVideoFragment` 透传到 `show()` 调用点
- [x] 4.3 两个弹窗各自的 `onToggle` lambda 按 design.md Decision 7 调整：选中时若该标签有上级则一并加入 `checked`；取消时只移除被点的那一个，不做任何联动。编译通过，人工走查见 6.2

## 5. 文档

- [x] 5.1 更新 `CLAUDE.md`：补充标签层级关系的数据模型、"默认值而非强制"这条关键设计决策（含否决"写入时强制注入"的原因）、以及它对 `ai-tag-suggestions` prompt 组装的可选增强

## 6. 端到端验证

- [x] 6.1 `./gradlew.bat testDebugUnitTest assembleDebug -q` 全量通过（含新增 `TagHierarchyTest`）
- [ ] 6.2 真机手动走查（待用户在设备上验证）：标签管理页设置"甜妹→颜值"层级 → 在标签编辑界面勾选「甜妹」，确认「颜值」被顺手带上 → 独立取消「颜值」，确认「甜妹」不受影响且保存后该视频确实只有「甜妹」没有「颜值」→ 重命名「颜值」为「外貌」，确认「甜妹」的上级引用同步更新 → 删除「外貌」，确认「甜妹」仍存在且回到顶层
