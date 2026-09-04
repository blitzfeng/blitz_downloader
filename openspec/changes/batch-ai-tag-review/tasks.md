## 1. 数据层

- [ ] 1.1 新增 `data/db/DownloadBatchEntity.kt`（`download_batch` 表：`id`/`createdAtMillis`/`awemeIds`，`|` 分隔），验证：字段与 design.md「批次存储」一致
- [ ] 1.2 新增 `data/db/DownloadBatchDao.kt`（插入一条批次；查最近两条批次），验证：能正确插入并按时间倒序取回最近两条
- [ ] 1.3 新增 `data/db/AiTagSuggestionPendingEntity.kt` + `AiTagSuggestionPendingDao.kt`（`ai_tag_suggestion_pending` 表：`awemeId` PK/`suggestedTags`/`generatedAtMillis`；upsert 一条；按 awemeId 集合批量查；删除一条），验证：upsert 幂等（同一 awemeId 重复写入只保留一行）
- [ ] 1.4 `AppDatabase.kt` 注册两张新表，版本号在 `ai-tag-suggestions` 落地版本基础上 +1，新增对应 `MIGRATION` 只建表，验证：`./gradlew.bat testDebugUnitTest assembleDebug -q` 通过
- [ ] 1.5 更新 `.cursor/rules/db-schema.md`：版本演进历史加一行，新增两张新表的说明小节，验证：文档字段与迁移代码一致

## 2. 下载批次记录

- [ ] 2.1 `DownloadService.processJob` 在成功入库后（与 `author_tag_frequency` 重算同一步）判断 `recordedIds.size > 2`，是则写入一条 `download_batch` 记录，验证：手动测试一次 >2 条与一次 ≤2 条的批量下载，分别确认是否生成批次记录

## 3. 管理页入口与页面骨架

- [ ] 3.1 管理页菜单新增「最近下载-设置标签」项，仅在存在至少一条批次记录时显示，验证：无批次记录时菜单不显示该项，产生一条批次记录后重新打开菜单可见
- [ ] 3.2 新建批量标签整理页面（Activity 或 Fragment，Compose 实现，遵循项目「新增 UI 一律 Compose」约定），点击入口能跳转进入，验证：能从管理页正常打开该页面并返回

## 4. 列表加载与筛选

- [ ] 4.1 页面 ViewModel 加载最近一条批次的全部视频，验证：展示条数与 `download_batch` 最新一行的 `awemeIds` 数量一致
- [ ] 4.2 合并加载上一条批次中标签编辑次数为 0 的视频，已打标的不加载，验证：构造一个"上一批次里部分已打标"的测试数据，确认列表只包含未打标的那部分
- [ ] 4.3 提供按标签编辑次数筛选的 UI 控件并接入过滤逻辑，验证：切换筛选条件后列表内容相应变化（对应 spec 场景）

## 5. 批量 LLM 分析服务

- [ ] 5.1 新增前台服务 `AiBatchAnalysisService`，参照 `DownloadService` 的通知栏/前台/串行队列架构，验证：启动后能在通知栏看到进度更新
- [ ] 5.2 服务内逐条调用 `AiTagSuggestionRepository`（`ai-tag-suggestions` 产出）获取建议，成功则 upsert 进 `ai_tag_suggestion_pending`，失败则记录日志并跳过继续处理下一条，验证：手动制造一条会失败的请求（如断网单条重试期间），确认其余视频仍正常出结果
- [ ] 5.3 全部处理完成后发出完成通知，验证：通知文案与实际成功/失败数量一致
- [ ] 5.4 分析期间离开页面/切后台不中断，验证：触发分析后立即退出页面，等待期间不打开 App，回来后确认分析已完成或仍在继续

## 6. 按标签分组批量确认 UI

- [ ] 6.1 页面读取当前批次视频集合对应的 `ai_tag_suggestion_pending` 全部行，在内存里按标签分组并按组内视频数降序排列，验证：构造多视频命中同一建议标签的测试数据，确认分组与排序符合 design.md 描述
- [ ] 6.2 每组默认全选，支持组内取消单条视频勾选，验证：取消后再确认，只有仍勾选的视频被处理
- [ ] 6.3 「确认」按钮调用 `VideoTagRepository.addTagsAsUserEdit` 写入组内当前勾选的视频，验证：确认后对应视频的标签与 `tagEditCount` 按预期更新
- [ ] 6.4 「跳过」按钮标记该组已处理但不写入标签，验证：跳过后组内视频标签不变，其他组不受影响
- [ ] 6.5 同一视频被多个已确认组命中时最终标签为并集，验证：构造一个视频同时属于两个不同建议标签分组的场景，两组都确认后该视频同时持有两个标签

## 7. 反馈样例记录

- [ ] 7.1 跟踪每条视频"涉及的分组是否已全部确认/跳过"，全部处理完成后对比 `ai_tag_suggestion_pending` 里的原始建议集合与视频当前实际标签集合，写入 `ai_tag_feedback`（复用 `ai-tag-suggestions` 的反馈写入方法）并删除该待处理行，验证：走完一条视频的全部相关分组后，反馈表新增一行且内容符合预期，待处理表对应行被删除
- [ ] 7.2 用户中途离开页面，未处理完的视频保留在 `ai_tag_suggestion_pending`，验证：离开后重新进入页面，未处理完的分组仍然存在

## 8. 文档

- [ ] 8.1 更新 `CLAUDE.md`：补充批次记录、批量分析服务、分组确认交互与反馈记录时机这几条关键设计，验证：文档内容与最终实现行为一致
- [ ] 8.2 更新「包结构约定」表格（如新增了独立包），验证：与其余行风格一致

## 9. 端到端验证

- [ ] 9.1 `./gradlew.bat testDebugUnitTest assembleDebug -q` 全量通过，验证：命令零错误退出
- [ ] 9.2 真机手动走查完整流程：批量下载 >2 条 → 管理页出现入口 → 进入页面看到最近批次+上一批次未打标视频 → 触发分析、离开页面、通知栏看进度 → 返回页面看分组结果 → 确认/跳过若干组 → 核对标签写入与反馈样例，验证：逐步对照 spec 场景确认行为一致
