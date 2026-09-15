## 1. 数据模型扩展与 Room 数据库迁移

- [x] 1.1 在 `TagEntity` 中新增 `enableAi: Boolean = true` 与 `isExclusive: Boolean = false`，更新 `AppDatabase` 版本并添加对应 Migration，验证编译与表结构正确。
- [x] 1.2 在 `VideoTagFeedbackEntity` 中新增 `evidenceImagePath: String? = null`，更新 Migration 并在 DAO 中增加按作者查询高频反馈与证据图的接口。
- [x] 1.3 统一预置标签与描述数据（保留通俗且含蓄的「小沟」标签，完善其与「波霸」在曲线程度上的阶梯描述）。

## 2. 标签管理界面轻量扩展

- [x] 2.1 在 `TagManageActivity` 的标签编辑弹窗中增加「参与 AI 分析」Switch 开关，绑定并持久化 `enableAi` 字段。
- [x] 2.2 在标签编辑弹窗中针对拥有子标签的父标签展示「子标签互斥单选」Switch 开关，绑定并持久化 `isExclusive` 字段。

## 3. 证据帧抽取、降采样与持久化存储

- [x] 3.1 新增 `EvidenceFileManager` 工具类，实现将关键帧图片按宽和高各 50% 进行矩阵缩放（降采样）并以 80% 质量压缩为 JPEG，保存至 `Download/bDouyin/covers/evidence/` 目录。
- [x] 3.2 在 `EvidenceFileManager` 中实现单视频证据帧保存逻辑，限制每个视频最多保存 3~4 张关联图（兼顾 Accepted 与 Rejected），并返回本地相对路径。
- [x] 3.3 更新 `DownloadedMediaFileManager` 的孤儿媒体文件扫描与清理逻辑，将 `covers/evidence` 目录及数据库引用纳入保护，避免误删证据帧。

## 4. 动态 Prompt 组装与本地父标签自动继承

- [x] 4.1 在 `TagSuggestionRequestBuilder` 中重构 Prompt 生成逻辑：过滤未启用 AI 的标签，按 `isExclusive` 动态注入互斥单选与父标签兜底指令，注入严格主体显著原则（宁缺毋滥），并要求输出结构化数字标签 ID 与证据帧索引。
- [x] 4.2 在 `AiTagSuggestionRepository` 中实现建议结果解析与父标签本地自动补全：大模型仅返回子标签 ID 时，根据 `parentId` / `parentTagName` 本地自动补充对应的父标签；返回父标签兜底项时直接保留。

## 5. 多模态 In-Context Few-Shot 与审核偏好闭环

- [x] 5.1 在 `AiTagSuggestionRepository` 中实现同作者高频偏好检索：优先获取同作者最高频的接受（ACCEPTED）与拒绝（REJECTED）反馈记录及其关联证据图。
- [x] 5.2 在 `TagSuggestionRequestBuilder` 与 `GeminiProvider` 中支持多模态 Few-Shot：将同作者历史证据图（50% 降采样图）与对应接受/拒绝结果作为多模态样例注入请求，在请求体中强制将证据关联图媒体分辨率级别设为 `MEDIA_RESOLUTION_LOW`（不使用 medium），并在图片不存在时优雅降级。
- [x] 5.3 在单条标签保存（`saveFeedback`）与批量审核确认流程中，将 AI 依据的关键帧降采样持久化为证据帧，并将图片路径写入 `VideoTagFeedbackEntity`。

## 6. 验证与回归测试

- [x] 6.1 编写纯 JVM 单元测试覆盖动态 Prompt 组装逻辑（互斥单选标记、未启用过滤、父标签本地继承解析）。
- [x] 6.2 运行 `./gradlew testDebugUnitTest` 验证所有单元测试通过，确保既有逻辑无回归。

## 7. AI 分析日志展示升级与透明度追踪

- [x] 7.1 在 `AiAnalysisLogModels.kt` 的 `AiAnalysisLogEntry` 中新增 `requestEvidenceSamplesSummary` 字段，记录同作者历史采纳/拒绝反馈样例及证据图数量概览。
- [x] 7.2 在 `AiTagSuggestionRepository.kt` 中完善脱敏请求体 JSON 与日志摘要，显式记录本次请求携带的多模态证据样本（`multimodalEvidenceSamples`）。
- [x] 7.3 在 `AiAnalysisLogSheet.kt` 抽屉卡片与 `AiAnalysisLogFormatter.kt` 纯文本导出中呈现「历史审核参考」数据，便于直观观察 Few-Shot 样本携带情况。
- [x] 7.4 补充并更新单元测试，执行 `./gradlew testDebugUnitTest` 验证全量通过。
