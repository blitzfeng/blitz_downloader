## Why

当前 AI 标签建议与批量分析存在过度打标、风格标签泛滥以及人工审核偏好沉淀利用不足的问题：
1. **标签边界模糊与过度打标**：候选标签缺乏互斥约束（如「可爱/纯欲/甜妹/魅态/御姐」容易被同时命中），且非视觉/系统标签（如「图片」、「不导出」）混入 AI 词表，缺乏明确的「主体突出、宁缺毋滥」门槛约束。
2. **人工审核资产未充分闭环**：历史审核中记录的「同意/拒绝」仅停留在文本统计层，无法得知 AI 是基于哪一帧做出的判断；在新视频分析时，未能将作者高频的「接受与拒绝典型案例及关联视觉帧」作为多模态 Few-Shot 上下文传递给模型，导致相同偏好的误判重复发生。

本次改造旨在构建全动态、自适应的 AI 打标引擎，并建立包含多模态证据帧的审核偏好闭环。

## What Changes

- **标签属性与元数据扩展**：
  - `TagEntity` 增加 `enableAi`（是否参与 AI 分析，默认为 true）与 `isExclusive`（父标签属性：子标签是否互斥单选）。
  - 标签管理页支持配置 `enableAi` 和父标签的 `isExclusive`，便于排除系统标签或定义单选风格组。
- **动态 Prompt 组装与防过度打标约束**：
  - 基于标签稳定 `id` 与 `parentId` 动态构建层级 Prompt，消除代码硬编码。
  - 动态注入互斥单选指令（`isExclusive` 组至多选 1 个）与「核心主体、特写显著、宁缺毋滥」的严格负向约束。
  - AI 输出结构化标签 ID，客户端本地自动补全父标签（继承 `parentId`）。
- **AI 推断关联图（证据帧）持久化**：
  - 在 `covers` 目录下新建 `evidence/`（或 `ai_evidence/`）子目录。
  - AI 响应解析关联的推断关键帧后，对图像进行尺寸减半（50% Downscale）处理以大幅节省存储与 Token。
  - 每个视频最多保存 3~4 张关联图（兼顾 Accepted 与 Rejected 结果），并记录本地路径到数据库关联表。
- **基于作者高频偏好的多模态 In-Context Few-Shot**：
  - 发起新视频 AI 建议时，查询同作者最高频的接受（ACCEPTED）与拒绝（REJECTED）历史反馈。
  - 携带历史案例及其对应的尺寸减半证据图作为多模态参考样例输入大模型，实现作者维度的自适应对齐。

## Capabilities

### New Capabilities
<!-- 无新增顶级独立 capability -->

### Modified Capabilities
- `tag-hierarchy`: 标签实体增加 `enableAi` 与 `isExclusive` 属性，并在标签管理页提供配置能力；明确子标签命中时父标签由本地动态继承。
- `ai-tag-suggestions`: 升级 Prompt 生成器支持动态互斥与严格主体约束；支持提取、尺寸减半压缩并持久化推断关联图到 `covers/evidence`；支持同作者高频接受/拒绝案例与关联图的多模态 Few-Shot 上下文注入。

## Impact

- **数据层 (Room)**：
  - `TagEntity` 新增字段 `enableAi: Boolean = true`、`isExclusive: Boolean = false`（Room 数据库版本升级与迁移）。
  - `VideoTagFeedbackEntity` 或新增/扩展证据帧字段（记录 `evidenceImagePath`），关联推断图。
- **存储与文件系统**：
  - 在 `Download/bDouyin/covers/evidence` 下管理证据帧缩略图，更新孤儿文件扫描机制以防误删。
- **LLM 模块**：
  - `TagSuggestionRequestBuilder` 与 `GeminiProvider` 提示词模板重构，支持 ID 交互、动态互斥、以及图文结合的多模态 Few-Shot 请求体组装。
- **UI 与交互**：
  - 标签管理页（`TagManageActivity` / 适配器）新增「参与 AI」和「互斥单选」配置。
