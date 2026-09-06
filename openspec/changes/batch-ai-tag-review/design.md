## Context

见 `proposal.md` 了解动机。本设计假定 `ai-tag-suggestions` 已经落地，可直接复用其 `AiTagSuggestionRepository`（单条视频建议请求，含词表过滤）、`llm/` 网络客户端、`util/VideoFrameExtractor`+`FaceFrameSelector`、以及 `video_tag_feedback` 逐标签反馈表——本变更不重新实现这些，只是多一个批量调用方。批量下载已有 `DownloadService`（前台服务、通知栏进度、可离开页面不中断）这套成熟架构可以直接照搬到批量分析场景。

## Goals / Non-Goals

**Goals:**
- 批量下载后自动记一笔"批次"，不需要用户手动标记。
- 新页面只加载"最近批次 + 上一批次未打标的部分"，不做历史批次浏览器。
- 分析过程可后台执行、可离开页面，体验与现有下载一致。
- 按建议标签分组确认，把操作量从"N 条视频"压到"M 个标签组"。

**Non-Goals:**
- 不做批次历史列表/切换（只有"最近"与"上一次里没打完的"两层，不回溯更早）。
- 不做分析过程中的增量展示——等整批分析完再统一展示分组结果，简化状态管理。
- 不做单次 LLM 调用分析多个视频的合并请求优化——沿用 `ai-tag-suggestions` 定的单视频请求契约，一个视频一次调用，简单可靠优先于省成本；要不要做批量合并请求留作后续优化。
- 不做"清理长期未处理的待建议记录"的自动化——个人使用量级下不是问题。

## Decisions

### 1. 下载批次用单表 + `|` 分隔的 awemeId 列表存储
`download_batch(id PK autoincrement, createdAtMillis, awemeIds TEXT)`，`awemeIds` 用 `|` 分隔，对齐项目里 `userRelation` 已有的同款约定。批次大小是几条到几十条量级，不需要为此新建一张关联表——单行存整批 id 列表读写都更简单。写入点在 `DownloadService.processJob`，与本次改动一起加在"成功入库后"那一步（紧邻已有的 `author_tag_frequency` 重算调用）：`recordedIds.size > 2` 才写。

### 2. 待处理建议用独立表，不复用 `video_tag_feedback`
`video_tag_feedback`（`ai-tag-suggestions` 定义，按标签逐行记录 ACCEPTED/REJECTED/MISSED）语义是"已经确认完的对照记录"；分析完成但用户还没在分组界面处理的建议是另一种状态（"待处理"），混进同一张表会让"哪些是待确认、哪些是历史记录"难以区分。新增：
```
ai_tag_suggestion_pending(
  awemeId PK,
  analysisId INTEGER NOT NULL,   -- 关联 ai-tag-suggestions 的 VideoAiAnalysisEntity.id
  suggestedTags TEXT NOT NULL,   -- 标签名，'|' 分隔，供分组 UI 直接展示，避免每次渲染都反查 analysisId
  generatedAtMillis INTEGER NOT NULL
)
```
服务每分析完一条视频就 upsert 一行——`analysisId` 与 `suggestedTags` 都来自同一次 `AiTagSuggestionRepository` 调用的返回结果，不是两次独立查询拼出来的。**`analysisId` 是反馈写入时的必需字段**：`video_tag_feedback` 的每一行都要求非空 `analysisId`（见 `ai-tag-suggestions` design.md Decision 7），批量场景的确认时刻可能发生在分析完成后很久，早已没有任何内存态，必须靠这个字段才能调用 `ai-tag-suggestions` 统一的反馈写入方法；`suggestedTags` 是展示层面的冗余副本，只为分组 UI 省一次 join，不作为反馈写入的数据源。页面据此构建分组；某条视频涉及的所有分组都被确认/跳过后，删除该行并把结果写入 `video_tag_feedback`（复用 `ai-tag-suggestions` 已定义的反馈写入方法，传入 `analysisId` + 最终确认集合，按标签分类落多行）。用户中途离开页面：未处理完的视频保留在这张表里，下次打开页面依然能看到，不会丢失分析结果。

### 3. 新增独立前台服务 `AiBatchAnalysisService`，不扩展 `DownloadService`
架构上照搬 `DownloadService`（前台通知、串行处理队列、进度更新、完成后停止），但**不复用同一个 Service 类**：下载是网络 IO，批量分析是 LLM 调用，两者失败模式、进度语义、通知文案都不同，硬塞进一个类会让 `DownloadService` 职责膨胀。新服务内部逐条调用 `AiTagSuggestionRepository`（`ai-tag-suggestions` 产出），每条结果 upsert 进 `ai_tag_suggestion_pending`，更新通知进度；单条失败记录日志、跳过，不中断队列。
**替代方案**：合并进 `DownloadService`，用一个 job 类型字段区分——多写一层分支判断，收益是少一个 Service 类，权衡后选择拆开保持单一职责，与项目里 `VideoTagRepository`/`DownloadedVideoRepository` 按关注点拆分的既有风格一致。

### 4. 分组构建与排序
批量标签整理页的 ViewModel 读取当前批次视频集合对应的全部 `ai_tag_suggestion_pending` 行，在内存里展开成 `标签 → 视频列表` 的映射，按**组内视频数量降序**排列展示——大组排前面，确认一次覆盖的视频最多，最符合"尽量减少人工操作"的目标。

### 5. 组确认/跳过的写入与反馈记录时机
- **确认**：调用既有的 `VideoTagRepository.addTagsAsUserEdit`（批量追加、只对真正新增标签的记录计 `tagEditCount`），传入组内当前勾选的视频 id 集合与该组标签。
- **跳过**：纯状态标记，不写标签、不影响其他组。
- **反馈记录时机**：以视频为单位跟踪"涉及它的所有分组是否都已确认或跳过"，全部处理完那一刻，取该视频 `ai_tag_suggestion_pending.analysisId`，调用 `ai-tag-suggestions` 统一的反馈写入方法（传入 `analysisId` + 它此刻在 `video_tags` 里的实际标签集合），按标签分类落 `video_tag_feedback` 多行，然后删除待处理行。这个"是否全部处理完"的跟踪状态留在页面会话内维护（内存/`ViewModel` 级），不需要单独持久化——用户中途退出会话未完成的视频，下次进页面时那些分组会重新出现，等同于"还没处理过"，行为上是安全的（不会重复写反馈，因为反馈只在"全部处理完"那一刻触发一次）。
- **分类语义**：某标签被确认组写入该视频 → 该标签记为 `ACCEPTED`；某标签所在的组被跳过、或该视频在组内被取消勾选 → 该标签记为 `REJECTED`（等同于单条编辑弹窗里"AI 建议但被删除"）；批量页面本身不提供"手动新增一个未被任何组建议的标签"的入口，因此正常流程下不会产生 `MISSED` 分类的行——但反馈写入方法是通用的按集合差异分类，如果用户在分析等待期间通过其他入口（如单条编辑弹窗）给同一条视频加了词表外的标签，diff 出来的额外标签仍会被如实分类为 `MISSED`，这是共享分类逻辑的自然结果，不需要批量页面专门处理这个交叉场景。

### 6. 与单条建议弹窗的关系
两条路径（单条弹窗 `ai-tag-suggestions`、批量页面本变更）都只是 `AiTagSuggestionRepository`/`VideoTagRepository` 的不同调用方，互相之间没有共享的可变状态需要协调——都是各自发起请求、各自写库，写库路径本身（`addTagsAsUserEdit`/`setTagsAsUserEdit`）已经是这两条路径改动之前就存在、且支撑多选批量打标签弹窗并发使用的既有方法，不需要为此新增并发保护。

### 7. 触发批量分析前检查 AI 建议标签功能是否已启用
批量分析复用 `ai-tag-suggestions` 的建议服务，而那个功能本身默认关闭、需要用户在设置页显式开启并配置有效的 Gemini API Key。批量页面的入口（管理页菜单项）本身只依赖"是否存在批次记录"，不需要 AI 功能已启用就能打开——用户可以只是想看看列表、按标签编辑次数筛一下，不一定马上要用 AI。但**点击「开启 LLM 分析」这个动作**必须先检查 AI 建议标签功能是否已启用：未启用/未配置有效 Key 时，不发起任何请求，引导用户先去设置页完成配置。
**替代方案**：批量页面自己再加一个独立的开关/配置入口——会和 `ai-tag-suggestions` 已有的设置项重复，两处配置容易不同步，不采用。

## Risks / Trade-offs

- **[风险] 批次越大，LLM 调用次数越多，费用线性增加** → 页面在触发分析前展示当前列表条数，用户能直观判断规模后再决定是否触发；不做用量提醒/上限之外的额外控制。
- **[风险] 大批次分析耗时长，前台服务被系统在内存压力下杀死** → 与 `DownloadService` 同款前台通知策略，已有先例证明可行；不做跨进程重启续传（与 `DownloadService` 现有的文档化限制一致）。
- **[风险] 待处理建议表可能因用户长期不回来处理而持续累积** → 数据量级小（每行几十字节，个人使用场景批次数量有限），不是当前需要解决的问题。
- **[风险] 分组批量确认可能让用户在没有逐条细看的情况下应用标签** → 默认全选但允许组内取消，属于用户已经明确选择接受的交互取舍（对应 spec 里的分组确认场景），不额外加约束。
- **[风险] 大组优先排序可能让占比小但更准确的建议被排到后面、容易被忽略** → 排序只影响展示顺序，不影响可操作性，用户仍可滚动处理所有组；如果后续观察到这是真实问题，再调整排序策略。

## Migration Plan

- `AppDatabase` 版本在 `ai-tag-suggestions` 落地后的版本号基础上再 +1（`ai-tag-suggestions` 当前设计跨两个迁移单元 v17→v18→v19，本变更接在其后，即 v19→v20），新增 `download_batch` 与 `ai_tag_suggestion_pending` 两张表，纯增量迁移，不改动任何既有表。
- 依赖顺序：`ai-tag-suggestions` 的 Repository/网络客户端/反馈表必须先实现，本变更的服务与页面直接调用它们；建议按 `ai-tag-suggestions` → `batch-ai-tag-review` 的顺序实现和合入，避免中途需要返工接口。
- 回滚：两张新表均为纯新增、不影响既有数据，回滚只需不发布这次迁移与相关代码。

## Open Questions

（无——"上一批次"的定义（仅指紧邻的前一条批次记录，不做多批次回溯）已在 spec 里明确；服务的通知渠道 ID、具体 UI 文案等纯实现细节不影响 spec/任务拆分，留给编码阶段直接定。）
