## Why

打标签仍然完全靠人工看视频判断。已有的"作者高频标签"预勾选（`author_tag_frequency` 缓存表）只能覆盖"这个作者以前打过什么标签"，对新作者、或作者本身标签历史很少的情况完全帮不上忙——本质是纯统计，不看视频内容本身。给标签编辑弹窗接入一个基于视觉的多模态模型，用视频封面/关键帧 + 文案 + 作者历史高频标签 + 现有标签词表去生成建议，能把"减少手动打标签"这条主线从"复用作者自己的历史"扩展到"看得懂新内容"，覆盖面更广。

**本次修订**基于《AI 视频标签智能体方案与项目兼容性评审》文档的兼容性评审结论重写：采纳该文档"结构化 VisualFeatureProfile + 逐标签反馈统计 + 个人偏好学习"的完整设计，而不是本变更最初版本"直接出标签、粗粒度反馈快照"的简化方案。这意味着一次数据库定位的转变——`downloaded_videos`/`video_tags` 继续承载"视频位置 + 标签"这条已有职责不变，但新增的持久层不再只是给 AI 建议做个"临时缓存"，而是要长期沉淀**视觉理解证据、逐标签准确率统计、个人审美偏好摘要**这些"AI 学习资产"，供后续调用持续复用、变得更准。

## What Changes

- **LLM 供应商改为可插拔接口**（`llm/LlmProvider`），当前只有一个真实实现：**Google Gemini**（Generative Language API，原生多模态、支持 `responseSchema` 强制结构化 JSON 输出）。选型原因：原计划接入的 Claude/OpenAI 目前支付渠道受阻，无法验证；可插拔不是预先设计的过度抽象——供应商已经因为现实原因换过一次，这个抽象有真实收益。**V1 只做 Gemini 这一个 Provider**，不为假设的第三个供应商预先搭桥。
- **标签表增加稳定数值标识**：`tags` 表新增 `id: Long`（非主键，`tagName` 仍是主键，所有既有查询/外键语义不变，完全向后兼容）与 `description: String`（人工定义的标签判断标准，供模型理解语义，避免模型按自己的通用语义发挥）两个字段。新建标签自动分配递增 id；**设置页新增一个临时「补齐标签 ID」按钮**，一次性为迁移前就存在的历史标签补上 id（幂等，只处理 `id == 0` 的行，可重复点击，后续版本确认全量用户都跑过一次后可以移除这个按钮）。
- **标签管理页（`TagManageActivity`）新增「编辑描述」入口**：与现有"编辑/删除/设置上级"三个操作图标并排，新增第四个图标，点击弹出 Compose 弹窗 `dialog/TagDescriptionDialogFragment`（多行文本框，限长，编辑该标签的 `description`，辅助模型理解标签判断标准，提升 AI 建议候选标签的命中率）。`TagManageActivity` 本身是存量 XML 页面（现有三个操作走 `AlertDialog`），本次只新增这一个入口是 Compose，不重写整个页面、也不改动已有三个 `AlertDialog`。有描述的标签在列表里追加一行截断预览，与"有上级则展示副标题"的现有展示逻辑并存、互不影响。
- **单条记录标签编辑弹窗（`TagEditDialogFragment`）新增「AI 建议」按钮**，点击后：
  1. 从本地已下载的 mp4 用 `MediaMetadataRetriever` 按视频时长均匀抽取候选帧（12~20 张，具体数量随时长和性能实测调整），用 **ML Kit Face Detection**（免费、设备端处理、无用量计费）从候选帧里挑出含清晰正脸/半正脸、无明显遮挡的 Face Frames；同时保留若干覆盖时间跨度的 Body/General Frames（用于服饰、姿态、动作、场景判断）；最终裁剪到 ≤ 8~12 张（含封面）一并上传。全程检测不到人脸也不报错，自动降级为纯时间点均匀采样。
  2. 连同 `desc`、标签词表（含 `id`/`name`/`description`/父子关系，父子关系可选依赖 `tag-hierarchy`）、该作者的 **AuthorProfile**（新增 `VideoTagRepository.getAuthorProfileForAi`：作者已下载视频总数 `sampleCount` + 达到阈值的标签各自的出现次数与**占比 `ratio`**，而不是最初版本只给一份没有权重的标签名单）、已生成的 Global Preference Profile（若存在）一并发给 Gemini。
  3. 模型返回**结构化 VisualFeatureProfile**（face/expression/bodyAndStyling/clothing/action 等维度的可见证据，而不是一句话摘要）+ 候选标签列表（`tagId`/`confidence`/`evidenceFrames`）。只允许模型从现有标签词表中选择（按 `tagId` 精确匹配过滤），词表外的返回项一律丢弃。
  4. 返回结果与当前已勾选集合取**并集**（不覆盖），灌进已有的 `TagCheckGrid`/`rememberCheckedTags` 机制，用户确认/调整后照常走 `setTagsAsUserEdit` 写库——AI 只提供预勾选建议，不直接落库，这一步和现在的手动打标签是同一条写入路径。
- **新增结构化持久化**（数据库角色转变的具体落地）：
  - `VideoAiAnalysisEntity`——一次分析的元数据：供应商/模型名、profile 版本、发起时间、请求是否成功。
  - `VideoVisualFeatureEntity`——该次分析产出的 VisualFeatureProfile（JSON 存储，关联 `VideoAiAnalysisEntity`）。
  - `VideoTagFeedbackEntity`——**按标签行**记录反馈：AI 建议且保留 / AI 建议但被删除 / AI 未建议但用户手工新增，取代最初版本"整段建议 vs 整段确认"的粗粒度快照，能支撑按标签统计准确率。
  - `TagPreferenceEntity`——每个标签的物化统计缓存（建议次数/接受次数/拒绝次数/漏判次数/接受率/推荐阈值），复算方式与既有 `author_tag_frequency` 缓存表同一套模式（手动重算按钮 + 下载/反馈写入后自动触发）。
  - `PreferenceProfileEntity`——压缩后的自然语言个人偏好摘要（`version`/`profileText`/`sampleCount`/`updatedAt`），累计一定数量新反馈后调用 Gemini（纯文本总结，不带图片）重新生成一次，不做模型微调。
- **Few-shot / 相似案例检索**：从 `VideoTagFeedbackEntity` + `VideoVisualFeatureEntity` 中按"同作者优先 + 按标签所需的视觉维度匹配"规则检索少量历史样例（不用向量数据库，规模到向量检索有意义之前先用规则查询）。
- **API Key 存储：暂不加密**，走 `AppSettings` 明文存储（用户决策：当前阶段不引入 `androidx.security:security-crypto`），设置页提供输入框与显式开启开关，默认关闭——涉及把视频封面/关键帧、文案发给第三方，不能默认开启。
- 批量打标签弹窗（`BatchTagDialogFragment`）本次**不接入** AI 建议，维持最初决策：多选场景一次要分析多条视频、成本和延迟都线性放大，建议先在单条场景验证有效后再决定是否扩展（批量场景由 `batch-ai-tag-review` 独立覆盖）。
- 不做"下载完自动触发"：所有 LLM 调用都由用户显式点击「AI 建议」触发，不在后台/下载完成时自动跑（成本 + 隐私）。

## Capabilities

### New Capabilities
- `ai-tag-suggestions`：标签编辑弹窗的 AI 建议标签能力——包括 LLM Provider 抽象与 Gemini 实现、关键帧抽取与人脸检测优选、结构化 VisualFeatureProfile 的生成与持久化、建议标签的词表过滤与预勾选、逐标签反馈记录、按标签的准确率统计（TagPreference）、周期性生成的个人偏好摘要（PreferenceProfile），以及这些资产如何作为后续调用的先验/few-shot 上下文被复用。

### Modified Capabilities
（无——不改动任何既有 spec 描述的行为；批量打标签弹窗、高频标签快捷筛选块、`tag-hierarchy` 的层级管理等既有功能均不受影响，只新增只读引用。）

## Impact

- **新增依赖面**：
  - Gemini 网络调用复用项目已有的 OkHttp/Retrofit/Gson，无需新增网络库。
  - 新增 `com.google.android.gms:play-services-mlkit-face-detection`（bundled 版本，免费、设备端处理、无用量计费，APK 体积增加约 1~1.5MB；选 bundled 是为了避免 unbundled 版本依赖运行时联网下载模型，导致离线场景下第一次人脸检测就不可用）。
  - **不引入** `androidx.security:security-crypto`（用户决策：API Key 暂不加密）。
- **新增本地存储**：`tags` 表加两列 + 5 张新表（`VideoAiAnalysisEntity`/`VideoVisualFeatureEntity`/`VideoTagFeedbackEntity`/`TagPreferenceEntity`/`PreferenceProfileEntity`）。`AppDatabase` 版本号在当前真实版本 **17**（已含 `tag-hierarchy` 的 `parentTagName`）基础上递增，具体迁移拆分见 design.md。需要用户自备 Gemini API Key，设置页新增配置项与开关。
- **改动文件面**（后续 tasks 阶段已细化，这里列受影响的既有模块）：
  - `data/db/TagEntity.kt`（+`id` +`description`）、`data/db/AppDatabase.kt`（新增迁移）
  - `data/VideoTagRepository.kt`（`createTag` 分配自增 id；新增只读方法供 AI 上下文使用）
  - `dialog/TagEditDialogFragment.kt`（新增「AI 建议」入口与加载态）
  - 新增 `dialog/TagDescriptionDialogFragment.kt`（Compose，标签描述编辑弹窗）；`activity/TagManageActivity.kt` / `adapter/TagManageAdapter.kt`（新增入口图标、消费 `FragmentResult`、列表展示描述预览）
  - `config/AppSettings.kt`（Gemini API Key、功能开关等运行时偏好）
  - `fragment/SettingsFragment.kt` / `viewmodel/SettingsViewModel.kt`（临时「补齐标签 ID」按钮 + AI 功能设置分组）
  - 新增 `llm/` 包（Provider 接口、Gemini 实现、请求/响应模型、prompt 组装）
  - 新增 `util/FaceFrameSelector.kt`（ML Kit 封装，检测失败自动降级）
  - 新增 `data/AiTagSuggestionRepository.kt`（编排：抽帧 → 人脸检测 → 组装请求 → 调用 Provider → 词表过滤 → 落库 → 反馈记录）
- **隐私影响**：视频封面/关键帧与文案会被发送给第三方 LLM 服务，仅在用户显式开启并点击「AI 建议」时发生，不涉及批量/自动场景；需要在设置页与 CLAUDE.md 里明确记录这一点。**Gemini API Key 不加密存储**，与项目"日志不打印 Cookie/msToken"的隐私基调有出入，属于用户在本次评审中明确接受的取舍——不要在日志里打印 Key 本身。
- **成本影响**：调用产生的费用记在用户自己的 Gemini API Key 账下，项目不代付、不做用量限制之外的计费逻辑。
- **不影响**：批量打标签弹窗、高频标签快捷筛选块、`author_tag_frequency` 缓存表、`tag-hierarchy` 的既有读写逻辑——本次只新增只读引用，不改动其写入/重算路径。
