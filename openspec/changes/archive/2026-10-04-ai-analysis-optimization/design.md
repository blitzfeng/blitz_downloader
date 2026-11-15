## Context

参见 `proposal.md`。
当前 Android 客户端通过 `TagSuggestionRequestBuilder` 组装向大模型（如 Gemini）发起的分析请求，使用 `VideoTagFeedbackEntity` 记录人工审核的反馈（`ACCEPTED` / `REJECTED` / `MISSED`）。
目前存在的问题：
1. 标签平铺多选且缺乏分类互斥约束，模型容易产生过度打标与风格标签堆叠；
2. 非视觉系统标签（如「不导出」、「图片」）混入 AI 候选词表；
3. 人工审核仅有文本记录，缺乏视觉依据帧的沉淀，无法在新视频中提供同作者的多模态对齐样例。

## Goals / Non-Goals

**Goals:**
- **全动态 ID 驱动与互斥约束**：Prompt 生成完全基于标签稳定 `id`、树形层级与 `isExclusive` 属性，消除硬编码；注入严格主体显著原则（宁缺毋滥）。
- **非视觉标签剔除**：通过 `enableAi` 标志动态过滤系统标签与非视觉标签。
- **本地父级自动补齐**：子标签被建议或确认时，客户端本地根据 `parentTagName` / `parentId` 自动继承父标签，减轻模型推理负担。
- **推断关联图存储与降采样**：在 `covers/evidence` 下持久化关键证据帧，图片宽高减半（50% Downscale，像素减少 75%）以节约 Token 与存储，单视频上限 3~4 张。
- **多模态 Few-Shot 闭环**：新视频请求时，自动检索同作者高频接受与拒绝的证据图与决策结果，作为多模态上下文注入请求。
- **修正现有描述笔误**：统一标签描述中的术语（如修复「小沟」为「乳沟」）。

**Non-Goals:**
- 搭建或依赖远程后端服务器（所有逻辑纯本地运行在 Android 客户端）。
- 修改现有的视频下载、媒体导出或基础播放器逻辑。
- 引入重量级第三方图片处理库（直接使用 Android 原生 `Bitmap` 矩阵缩放与压缩）。

## Decisions

### Decision 1: TagEntity 属性扩展与 Room 数据库迁移
在 `TagEntity` 中增加两个字段：
```kotlin
val enableAi: Boolean = true,
val isExclusive: Boolean = false,
```
- **Rationale**: `enableAi` 用于将「不导出」、「图片」等系统控制或文件类型标签排除在 Prompt 候选之外；`isExclusive` 用于父标签，标识该分类下的子标签属于互斥单选组（如「颜值风格」）。
- **Migration**: 升级 `AppDatabase` 版本，增加迁移脚本：`ALTER TABLE tags ADD COLUMN enableAi INTEGER NOT NULL DEFAULT 1;` 及 `ALTER TABLE tags ADD COLUMN isExclusive INTEGER NOT NULL DEFAULT 0;`。

### Decision 2: 标签管理弹窗轻量扩展
在 `TagManageActivity` 的标签编辑对话框中增加两个 Switch / Checkbox：
- 「参与 AI 分析」（默认开启；针对所有标签）；
- 「子标签互斥单选」（仅当该标签有子标签时可见/可用）。
- **Rationale**: 界面极简扩展，无需重写整个标签管理页面或开发复杂的规则设计器。

### Decision 3: 证据帧（关联图）提取、尺寸减半与持久化
- **存储路径**: `<外部存储>/Download/bDouyin/covers/evidence/${awemeId}_evidence_${index}.jpg`。
- **尺寸减半处理**:
  - 从视频抽取到的原关键帧（或大模型返回关联的帧）通过 `BitmapFactory.decodeFile` 读取。
  - 使用 `Bitmap.createScaledBitmap` 将宽和高各缩放为原来的 50%（面积缩小为 25%），以大幅削减 JPEG 体积与大模型输入 Token 消耗。
  - 采用 80% 质量压缩保存为 JPEG。
- **数量限制**: 每个视频保存最多 3~4 张最具代表性的证据帧（兼顾 Accepted 与 Rejected 标签）。
- **孤儿清理保护**: 在 `DownloadedMediaFileManager` 中注册 `covers/evidence` 目录，避免被误当孤儿文件清理。

### Decision 4: 反馈表扩展证据图相对路径
在 `VideoTagFeedbackEntity` 中增加字段：
```kotlin
val evidenceImagePath: String? = null,
```
- **Rationale**: 记录单次标签决策（无论是 ACCEPTED 还是 REJECTED）所对应的证据帧图片相对路径。
- **Migration**: `ALTER TABLE video_tag_feedback ADD COLUMN evidenceImagePath TEXT DEFAULT NULL;`。

### Decision 5: 动态 Prompt 组装、互斥单选与父标签兜底
在 `TagSuggestionRequestBuilder` 中动态遍历标签词表：
1. 过滤掉 `enableAi == false` 的标签；
2. 识别具有 `isExclusive == true` 的父分类，在 Prompt 中为其定义单选互斥组：
   - 包含该分类下的全部子标签，同时**显式将父标签自身作为兜底项（Fallback Option）**（例如：`若颜值出众但属于清冷、端庄、知性等其他非典型风格，直接选择 [ID: 15] 颜值 本身作为兜底`）；
   - 明确规则：子标签与父标签互斥，至多单选 1 项；并在 `reason` 字段中简述兜底的具体气质理由；
3. 普通分类或独立标签标记 `【可多选：仅选择特征显著的】`；
4. 注入全局硬约束：`【核心主体原则：仅当该特征为画面的核心主体、特写或主要看点时勾选；背景掠过、姿势模糊或勉强沾边一律严禁勾选，宁缺毋滥】`；
5. 输出要求：要求模型返回 JSON 对象，包含匹配的数字 `tag_id` 列表及各标签关联的帧索引（如 `evidence_frame_indices`）。

### Decision 6: 本地父标签自动补齐与继承
当大模型返回命中的子标签 ID 列表时：
- 本地遍历选中的标签 ID，查找其 `parentTagName` / `parentId`；
- 若命中的是具体子标签，本地自动将父标签加入建议/预勾选列表；
- 若命中的本就是兜底的父标签，则直接保留父标签；
- 大模型自身仅需专注于具象特征或兜底判断，不再做重复层级推理。

### Decision 7: 同作者多模态 In-Context Few-Shot 与强制 Low 级别分辨率
在为指定作者的视频发起建议请求时：
- 从 `VideoTagFeedbackDao` 中查询该作者出现频次最高的 ACCEPTED 标签（最多 2 个）与 REJECTED 标签（最多 2 个）；
- 若这些记录附带有本地存在的证据帧图片，读取为 `ImagePart`，作为图文并茂的 Few-Shot 上下文放入 Prompt；
- **强制分辨率级别为 Low**：在 `GeminiProvider` 中，所有历史参考关联图（证据图）以及 Few-Shot 图片的 `mediaResolution` 级别**严格设置为 `MEDIA_RESOLUTION_LOW`（不使用 medium）**，极度压低输入 Token；
- 若无图片或图片已被删除，优雅降级为纯文本提示或直接忽略，保证健壮性。

## Risks / Trade-offs

- **[Risk: 多模态 Few-Shot 带来额外的 Token 消耗与耗时]**  
  → **Mitigation**: 严格对证据帧实施 50% 尺寸缩放，API 级别强制 `MEDIA_RESOLUTION_LOW`，限制单次请求携带的作者历史参考图总数不超过 2~4 张，单图 Token 控制在 60~85 以内。
- **[Risk: 用户手动删除或移动证据帧文件]**  
  → **Mitigation**: 访问图片文件前进行 `file.exists()` 检查，如果不存在则自动降级为文本上下文，不影响正常分析流程。
- **[Risk: 孤儿媒体扫描误删证据图]**  
  → **Mitigation**: 在 `DownloadedMediaFileManager` 中显式登记 `covers/evidence` 目录与 `VideoTagFeedbackDao` 路径校验。
