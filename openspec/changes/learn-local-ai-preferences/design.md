## Context

整体路线见 [个人偏好学习整体方案](../../../docs/ai-preference-learning-plan.md)。当前为单模块 Android 项目，Room v30，AI 服务经 `LlmProvider` 统一调用，批量标签分析最多三条并发，抽帧限制为一条。

现有 `video_tag_feedback` 以标签行为粒度记录 ACCEPTED、REJECTED、MISSED，但来源不够细。现有摘要每次仅取五个视频，输入为文案和最终标签。现有 `ai_reference_exclusion` 按作品排除参考，`preference_profile` 保存旧自然语言摘要。这些原始资料继续作为兼容来源。

## Goals / Non-Goals

### 第一版目标

- 用户主动学习较大规模历史资料，覆盖清单可核对，分批成功结果可恢复。
- 保存可审核的结构化个人规则，供三个已接入服务使用。
- 生效规则版本与新增标签分析解耦，日常请求减少历史图片。
- 对人工判断来源、来源失效、旧数据库及任务中断有明确处理。
- 通过细分计时定位十秒准备阶段的主要开销。

### 后续阶段

增量合并、云端存储检索、关键帧缓存和自适应二次复核按整体路线分别实施。第一版的学习每次覆盖计划内完整有效案例；检查点恢复复用的是同一次全量任务的已完成节点。

## Decisions

### 学习案例采用独立的数据投影

增加 `AiPreferenceRepository` 负责案例、任务、版本和规则选择，避免继续扩张 `AiTagSuggestionRepository`。沿用按职责划分的包结构，通过 `BlitzApp` 持有仓库实例。

学习案例以 awemeId 为身份，同一视频的有效判断采用最新快照，revision 随其判断和视觉资料的有效变化递增。相同结果的重复确认只更新时间，不增加修订或独立样本数，内容哈希排除纯审计时间。原始分析及既有反馈保持原来的存储和统计语义。

逐标签判断包含 tagId、judgment、source、相关 analysisId 和修改时间。judgment 为 POSITIVE、NEGATIVE、UNKNOWN；source 为 HUMAN_CONFIRM、HUMAN_ADD、HUMAN_REMOVE、HUMAN_REPLACE、HUMAN_COEXIST、AUTO_EXISTING、SKIPPED、LEGACY_UNKNOWN。未操作的标签不从当前集合推断成已确认。

| 操作入口 | 学习资料中的处理 |
| --- | --- |
| 单条 AI 建议后保存 | 明确保存的建议项为人工确认，明确删除项为人工否定，记录原建议与当前集合 |
| 单条普通编辑 | 根据保存前后差异记录新增及删除判断，不要求产生旧 AI 对照反馈 |
| 普通批量加标签 | 只记录明确新增标签的正判断，其他标签状态继续保留 |
| 批量审核确认组 | 只确认该组实际涉及的标签，保留已选、明确取消选择和替换、共存动作 |
| 批量审核跳过 | 标为未评价；既有业务反馈可以保留原行为，但学习投影不把跳过解释为负例 |
| 已有标签组自动处理 | 标记自动来源，不增加人工支持数 |
| 撤销或再编辑 | 更新最新快照与 revision，使旧学习依据失效 |

在 `VideoTagRepository` 的用户编辑入口复用前后集合差异，并由明确保存、组确认等调用点补充来源；无变化的显式确认也能记录判断。案例写入应与标签修改或对应审核事务一起提交。禁止用 tagEditCount 大于零推断具体标签已被人工确认。

单条编辑页面需要记录实际勾选、取消勾选和应用替换的动作集合。未点击应用的替换建议默认未评价，不能仅因新标签未出现在最终集合就标记为人工拒绝。关闭或取消编辑不写入人工判断。

历史导入按视频分页，选取最新相关分析和当前仍有效的反馈。不同分析的重复反馈不能增加独立视频数。历史记录缺失明确来源时标为 LEGACY_UNKNOWN，自动标签保留自动上下文。当前数据无法恢复的人工动作不做猜测。

### 全量任务使用冻结清单和修订指纹

生成计划时记录有序案例清单、案例 revision 和内容哈希、被引用的标签定义、参考排除状态、输入图像指纹及学习策略版本。清单是该任务的实际范围，预览显示确切数量。

案例哈希包含逐标签判断、视觉资料和文案。标签语义指纹包含稳定 ID、描述、父级稳定 ID、互斥属性及 AI 参与状态，标签重命名以稳定 ID 重新显示名称。源文件缺失可作为该快照的既知状态；任务进行中被替换的图片需要使相关节点失效。

新增其他视频只增加待更新数，不破坏正在学习的快照。快照内案例被修订、删除或排除，以及被依赖的标签语义改变，视为不兼容。发送每个节点前和发布版本时重新核对，任务标记 STALE 并停止后续发送；已经发送的请求结果保留诊断，但不能启用。

### 分批提炼与分层合并

第一版固定学习并发为一。所有有效案例分页装配，不用现有最近五个视频和六十行反馈限制。

| 输入或输出预算 | 默认值 |
| --- | --- |
| 每个叶子批次的视频数 | 最多 8 |
| 每个叶子批次的证据图 | 最多 4，每视频最多 1 |
| 叶子批次序列化文字 | 最多 32 KiB UTF-8 |
| 每张历史证据图 | 最长边不超过 384px，低分辨率输入，最大 512 KiB |
| 单批文字及图片 Base64 合计 | 最多 3 MiB |
| 合并节点的子节点 | 最多 8，且摘要文字不超过 32 KiB |
| 单条规则正文 | 最多 512 个 Unicode 字符 |
| 单规则展示的代表案例 | 最多 8，额外覆盖关系保存在来源图中 |

预算集中在策略配置中，随 strategyVersion 固定。规划器按稳定顺序装包，图片优先用于拒绝、漏标和标签混淆案例，再补正例。一个批次可以携带无图案例，但要保留图片缺失标记。超长案例按标签判断片段拆成同一 caseId 的多个部分，不截断或遗漏判断集合，覆盖计数仍只算一个视频。

输入包含本地已有的结构化视觉特征、文案、原建议、实际判断、相关标签定义和预算内图片。学习阶段读取已保存的封面或证据图，不执行新视频全量抽帧。没有可靠图证的视觉结论必须输出 insufficientEvidence。

叶子节点返回结构化规则摘要。合并节点按 scope 与排序 tagIds 分组，只读取有预算的同组子摘要，递归生成各作用域最终规则。客户端汇总各组最终规则形成完整草稿，避免在最后一次模型调用中重新装入全库摘要。每个节点保存输入哈希、输出及子节点 ID。模型引用的标签、案例或子节点 ID 必须属于该节点输入。

支持数、拒绝数和独立视频数由客户端沿来源图计算，不采信模型填报的统计。完整来源图用于覆盖校验和版本失效检测，展示用的代表案例列表不承担完整覆盖记录。

只有所有计划节点成功、覆盖关系完整、输出符合 Schema 才生成可应用草稿。样本少的标签生成待核实规则，不能因最近某条视频覆盖其他标签的已有结论。

### 统一学习输出模型

扩展 `LlmProvider`，增加叶子提炼和摘要合并方法，共用领域模型与 Schema。OpenAI 使用 Responses 的严格 JSON Schema，Gemini 和 Vertex AI 使用 responseSchema，保持图像与服务现有编码方式。

建议的单规则结构如下，具体字段名在实施时保持三个提供方一致。

```json
{
  "scope": "tag",
  "tagIds": [2, 3],
  "criteria": "依据当前画面区分两个标签的判断标准",
  "positiveSignals": ["支持勾选的特征"],
  "negativeSignals": ["常见误判或证据不足情形"],
  "replacementConditions": ["倾向替换的条件"],
  "coexistenceConditions": ["反差兼具的条件"],
  "sourceNodeIds": ["chunk-001"],
  "representativeCaseIds": ["case-001"],
  "insufficientEvidence": false
}
```

规则范围支持 global、category 和 tag。多标签边界使用排序后的稳定 tagIds。至少两个不同的人工判断案例且至少一个可核查图证，才默认标为可审核启用；其余为待核实。该阈值表示资料充足程度，不代表客观准确率。用户可以查看并明确认可待核实规则，记录为人工审核。

人工覆写与模型生成字段分开保存。新学习结果携带已有人工覆写作为约束，生成后的草稿继续保留覆写，用户可以逐项恢复模型版本。

### 学习任务与日常分析协调

新增 `PreferenceLearningService`，声明 dataSync 前台服务并提供通知入口、取消和任务详情。页面只观察持久任务状态，离开页面继续执行。

使用进程内统一的 `AiWorkGate`，由学习服务和 `AiBatchAnalysisService.start` 在启动前原子争用所有权。已有批量分析的三工作协程属于同一所有权。新任务遇到占用直接提示，避免两个入口在竞态中同时启动。任务完成、失败和取消按 ownerId 释放，不能释放别的任务。

单条交互分析可以执行，但仍受共享客户端已有调度限制。学习并发始终为一，单节点使用专用超时上限 120 秒。服务选择以任务开始时的服务优先级及模型快照为准，每个节点可以按该快照的提供方顺序降级，每个提供方最多尝试一次；所有服务失败则暂停在 FAILED，用户可以重试。记录实际服务、耗时、Token 和失败原因。

降级只适用于现有策略允许的网络异常、HTTP 408、429 和 5xx。内容拒绝、鉴权和其他请求错误直接保留失败，不为绕过拒绝而切换服务。叶子节点数由装包计划确定，合并请求数及提供方降级调用量以估算区间展示，运行时记录实际消耗。

持久任务状态为 PLANNED、RUNNING、MERGING、READY_FOR_REVIEW、FAILED、INTERRUPTED、CANCELLED、STALE。节点状态为 PENDING、RUNNING、SUCCEEDED、FAILED、UNCERTAIN。应用启动识别失去执行者的 RUNNING 任务，改为 INTERRUPTED，用户主动继续。

取消通过结构化并发传播，未开始节点停止发送。前台服务销毁和 Android 服务超时都需要保存中断状态、释放工作所有权并结束通知，保留完整成功检查点。

成功节点以 inputHash 唯一复用。网络响应已返回但尚未写入数据库时进程结束，无法保证外部调用恰好一次；该节点标为 UNCERTAIN，继续前提示可能重复计费，由用户选择重试。凭据不写入任务快照，恢复时重新获取对应服务的当前凭据；模型或策略改变需要重建计划。

### 本地版本与 Room 迁移

基于当前 v30，计划迁移到 v31。实施前核对是否已有其他迁移，并在需要时顺延版本。所有表与现有备份一起保存，JSON 通过仓库层解析。

| 新表 | 关键字段与约束 |
| --- | --- |
| ai_preference_case | awemeId 主键、revision、analysisId 可空、judgmentsJson、suggestedJson、visualJson、evidenceJson、desc、contentHash、updatedAtMillis；同作品采用最新有效快照 |
| ai_preference_learning_run | runId 主键、status、manifestJson、strategyVersion、providerPlanJson、plannedNodeCount、completedNodeCount、processedCaseCount、lastError、createdAtMillis、updatedAtMillis |
| ai_preference_learning_node | nodeId 主键、runId 索引、stage、inputHash、inputJson、childNodeIdsJson、status、outputJson、diagnosticsJson；runId 与 inputHash 唯一 |
| ai_preference_rule_profile | version 主键、runId 可空、status、rulesJson、manifestJson、overridesJson、coverageJson、createdAtMillis、activatedAtMillis 可空；status 为 DRAFT、ACTIVE、ARCHIVED、INVALID |

规则 ID 可由 scope、排序 tagIds 和局部条目序号稳定生成，人工覆写按 scope 与稳定 tagIds 关联，在跨学习重建时保留关联。支持同一 scope 下的规则禁用及整段人工覆写。

启用事务先核验 manifest，编译用户审核后的规则，再将当前 ACTIVE 改为 ARCHIVED 并激活新版本。通过单一事务控制最多一个 ACTIVE，仓库不绕过该入口写状态。

新表 SQL 与 Entity 的默认值一致，显式注册迁移，导出 schema 并执行迁移测试。更新数据库权威文档。兼容旧备份的 DatabaseBackupManager 恢复路径，以及系统选择器恢复入口。

旧 preference_profile 读取与新版本分开。升级时记录旧摘要的兼容指纹；首次启用新规则后停止注入旧摘要。引用资料变化且不能确定旧摘要来源时，将该旧摘要标记无效并回退到案例方式，不重新发送未知来源的过期摘要。实现可在新 profile 表以 legacy 来源导入兼容文本及指纹，保留旧表数据。

### 日常请求按规则预算选择

在请求开始时固定有效规则版本，排除当前视频自身作为历史文本案例，并完成来源及标签兼容检查。一次请求的服务降级沿用同一份规则上下文。

| 个性化上下文 | 上限与选择 |
| --- | --- |
| 全局规则 | 最多 2 KiB UTF-8 |
| 标签与分类规则 | 最多 12 条，总计最多 8 KiB UTF-8 |
| 文本案例 | 最多 2 个，总计最多 4 KiB UTF-8 |
| 历史参考图片 | 有有效版本时为 0 |

顺序采用人工覆写优先，其次当前已有标签及其分类、作者高频标签相关分类、纠错较多标签、稳定标签 ID。以完整条目装包，不按字符中途截断规则。正文超过单规则上限时在草稿保存时提示用户缩短。覆盖不足的分类继续使用词表描述和当前视觉证据，日志明确哪些规则未入选。

两个文本案例按所选规则关联度、人工来源优先级、时间和稳定 ID 排序，保存拒绝、补加或替换差异，不重新加载其图片。没有相关文本案例时使用规则本身。

没有有效新版本时保留原有历史案例及兼容旧摘要回退。有有效新版本时采用规则优先输入，关闭日常请求的历史图片加载，不同时传入旧摘要。当前视频封面、关键帧及已有标签保持现有协议，add、replace、coexist 及其本地校验继续生效。

输入规则视为参考资料，不能修改系统词表限制、替换权限或覆盖用户手写的标签说明。把稳定词表和规则放在动态文案和图片之前，保留后续提示缓存优化空间。

### 失效与待更新分开处理

新案例增加待更新数量，现有有效版本继续使用。不同视频的有效修订按 awemeId 去重，数量达到二十时突出提示；用户可以随时主动学习。

启用版本的快照内案例被修改、删除或排除，或依赖标签的描述、层级、互斥性和 AI 参与状态改变，将整个生成版本标为 INVALID。回滚前也进行相同检查。新添加的无关标签和单纯重命名不使整份版本无效，但选择器实时采用当前候选词表。

参考恢复不会自动重新激活 INVALID 版本。用户重新学习或审核一个当前兼容的版本。旧版自然语言摘要的来源不明确时采用更保守的失效策略。

### 准备阶段的可解释计时

增加单条请求的准备阶段统计模型，使用单调时钟。记录并独立关联以下阶段。

- 本地标签及已有标签读取。
- 抽帧信号量等待。
- 视频候选帧解码。
- 人脸检测。
- 帧选择、缩放和压缩。
- 偏好版本及历史资料读取。
- 规则与文本案例选择。
- 请求组装及实际编码前准备。
- 其他未覆盖的准备时间。

抽帧工具返回图片与阶段耗时，避免全局可变计时器；可以保留现有调用适配器以兼容其他调用点。日志在准备开始时建立条目，准备失败也更新同一 ID。第一条 HTTP 调用开始作为准备阶段终点，后续服务降级的编码属于对应尝试，不重复计入首次准备。

统计必须覆盖耗时区间且避免嵌套相加造成重复；其他时间为总耗时减去互不重叠阶段之和。保留既有网络 EventListener 的时间、字节数和服务尝试记录。附带 ruleProfileVersion、selectedRuleIds、omittedRuleIds、currentImageCount、historyImageCount、textExampleCount。

### 页面与实际入口

新增 Compose Material 3 偏好学习页面，分为概览、任务详情和规则审核三个视图。设置页只增加入口和摘要，不在主设置页堆叠长表格。业务读写与网络操作在 ViewModel 和仓库，页面负责显示和导航。

规则页显示人工正例数、负例数、未知来源数、图证状态、代表案例以及规则与人工覆写差异。数据不足规则默认待核实，用户确认启用时保留人工审核标记。规则开关、修改、应用、回滚均有防重状态。

需要检查的现有代码入口如下。

| 路径 | 集成职责 |
| --- | --- |
| app/src/main/java/com/blitz/downloader/data/AiTagSuggestionRepository.kt | 停止阈值自动模型调用、规则上下文选择、引用修订和请求计时 |
| app/src/main/java/com/blitz/downloader/data/VideoTagRepository.kt | 人工修改前后差异与案例更新 |
| app/src/main/java/com/blitz/downloader/viewmodel/ManageVideoViewModel.kt | 单条保存及显式 AI 确认来源 |
| app/src/main/java/com/blitz/downloader/viewmodel/BatchTagReviewViewModel.kt | 组确认、跳过、自动组、撤销、手工保存的来源与事务 |
| app/src/main/java/com/blitz/downloader/data/db/AiReferenceDao.kt | 参考排除时使相关任务及版本失效 |
| app/src/main/java/com/blitz/downloader/service/AiBatchAnalysisService.kt | 共享 AI 批量任务所有权 |
| app/src/main/java/com/blitz/downloader/llm/LlmProvider.kt | 统一学习与合并方法及提供方实现 |
| app/src/main/java/com/blitz/downloader/util/VideoFrameExtractor.kt | 解码、检测和压缩计时 |
| app/src/main/java/com/blitz/downloader/util/FaceFrameSelector.kt | 人脸检测计时，保留现有检测行为 |
| app/src/main/java/com/blitz/downloader/ui/AiAnalysisLogSheet.kt | 准备阶段和个性化上下文统计 |

## Risks / Trade-offs

- 历史来源不够细 → 保留未知状态、展示可信度、用户审核规则，后续操作建立可靠来源。
- 全量任务调用较多 → 启动预览请求数，单节点预算与检查点复用，失败后由用户决定重试。
- 低分辨率证据不足以区分风格 → 标记资料不足，保留原图入口，后续疑难检索补充图证。
- 分层压缩可能遗漏少见反例 → 输出单独的反例及冲突字段，追踪来源图，测试少数类保留。
- 新资料可能推翻旧规则 → 修订指纹和失效机制，人工覆写独立保存，重新学习后审核。
- 请求历史图减少但准备时间仍较长 → 细分计时提供依据，缓存优化另行实施并测量。
- 服务端已完成而本地未落库 → UNCERTAIN 状态显式提示重复调用成本，避免宣称恰好一次。

## Migration Plan

1. 先检查最新 Git 和 Room 版本，确定迁移号及现有未提交文件边界。
2. 新增表、DAO、迁移及旧格式适配，测试当前数据库和旧备份恢复。
3. 接入人工案例来源与本地待更新统计，停止旧阈值自动摘要模型调用。
4. 完成学习任务、三个提供方输出、规则草稿与版本切换。
5. 接入规则优先请求及细分日志，运行协议、数据、并发和迁移测试。
6. 构建 Debug APK，在用户指定设备上验证后台任务和实际学习流程，再用留出的人工审核集比较质量与耗时。

运行时回滚使用仍兼容的规则版本或原案例回退。安装旧 APK 涉及 Room 降级兼容，不能以回滚规则版本替代数据库迁移设计。
