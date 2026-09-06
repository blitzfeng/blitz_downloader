## Context

参考 `proposal.md` 了解动机；本设计基于当前代码库的实际约定：`api/` 包是 Retrofit + OkHttp + Gson 对接抖音接口的既有范式，`config/AppSettings.kt` 是运行时偏好的唯一入口（每次 getter 直读 SharedPreferences，不做内存缓存），Room 迁移必须显式书写、不依赖 `fallbackToDestructiveMigration`，`data/db/` 下已有的 `author_tag_frequency` 缓存表可以直接复用做作者历史上下文（`VideoTagRepository.getHighFrequencyTagsForAuthor` 是既有方法，服务于批量打标签弹窗预勾选，本变更不改动它；AI 建议改用新增的 `getAuthorProfileForAi` 方法，见 Decision 15），`util/MediaOrientationProbe` 已经在用 `MediaMetadataRetriever` 读取本地视频，抽帧可以复用同一套 API。**当前 `AppDatabase.version = 17`**（`tag-hierarchy` 的 `tags.parentTagName` 已经落地，比本变更最初撰写时的假设更新）。

本版本是对最初设计的**重写**，采纳了《AI 视频标签智能体方案与项目兼容性评审》文档的结构化方案（VisualFeatureProfile + 逐标签反馈 + 个人偏好学习），并结合项目实际情况做了以下关键调整：LLM 供应商改为 Gemini（原计划的 Claude/OpenAI 支付渠道受阻）、标签体系补一个独立数值 `id`（原 schema 用 `tagName` 做主键，没有数值 id）、人脸检测采用免费的 ML Kit 方案、API Key 暂不加密。

## Goals / Non-Goals

**Goals:**
- 在单条记录标签编辑弹窗新增一条"看得懂视频内容"的建议来源，输出结构化视觉证据而非一句话摘要，作为对纯统计式高频标签预勾选的补充。
- 建立逐标签粒度的反馈记录与统计（TagPreference），以及周期性生成的个人偏好摘要（PreferenceProfile），让"越用越准"的效果可衡量。
- LLM 供应商可插拔，但 V1 只做 Gemini 一个实现，不为假设的未来供应商预先设计接口的每一个细节。
- 复用现有架构范式（Retrofit/OkHttp/Gson、AppSettings、Room 迁移规范、Compose 弹窗结果契约），不引入与既有代码风格脱节的新模式。

**Non-Goals:**
- 不做批量/自动触发（已在 spec 里明确约束），本设计只覆盖单条编辑弹窗这一条路径；批量场景是 `batch-ai-tag-review` 的范围。
- 不做模型微调/训练管线；"经验积累"完全靠 TagPreference 统计 + PreferenceProfile 摘要 + few-shot 样例实现，不训练模型参数。
- 不做向量数据库/embedding 相似度检索；相似案例检索用"同作者 + 标签维度匹配"的规则查询。
- 不做反馈样例表的长期容量治理（清理/归档策略），规模到达个人使用量级前不是问题。
- 不做 API Key 加密存储的高安全方案（用户决策：当前阶段接受明文风险）。

## Decisions

### 1. LLM 供应商：可插拔接口 `llm/LlmProvider`，V1 只实现 Gemini
```kotlin
interface LlmProvider {
    suspend fun generateTagSuggestion(request: TagSuggestionRequest): Result<TagSuggestionResponse>
    suspend fun summarizePreference(request: PreferenceSummaryRequest): Result<String>
}
```
`TagSuggestionRequest`/`TagSuggestionResponse`/`PreferenceSummaryRequest` 是 provider 无关的领域模型（存于 `llm/LlmModels.kt`），各 Provider 内部自行转换成对应厂商的请求/响应格式。当前唯一实现 `llm/providers/GeminiProvider.kt`，对接 Google Generative Language API：
- Endpoint：`POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`，鉴权走 `x-goog-api-key` 请求头（不用 URL query 参数，避免 Key 出现在日志/代理记录的 URL 里）。
- 多模态输入：`contents[].parts[]` 混合 `inlineData`（base64 JPEG + `mimeType: image/jpeg`）与 `text`。
- 结构化输出：用 `generationConfig.responseMimeType: "application/json"` + `responseSchema` 强制模型按 VisualFeatureProfile + 候选标签的 JSON Schema 返回，对应文档第 14 节"必须使用严格 JSON/Structured Output 思路"的要求——Gemini 原生支持，不需要额外的"提示词里要求返回 JSON 再自己兜底解析"这类脆弱方案。
- 具体模型版本号（如 flash/pro 档位）留到实现阶段按当时可用版本选取，优先选成本较低的多模态档位，不影响本设计的行为契约。

**选型理由**：项目原计划接入 Claude（design.md 最初版本理由：项目运行在 Claude Code 生态、鉴权文档确定），但用户反馈 Claude/OpenAI 支付渠道目前受阻，暂时无法开通计费，转而使用已经可用的 Gemini API Key。

**关于"可插拔"是否过度设计**：最初设计明确否决过多供应商抽象架构（"过度设计"），但供应商已经因为现实原因（支付问题）实际更换过一次，说明这不是假设性需求——保留一层薄接口（两个方法）用于隔离"业务逻辑"与"具体厂商 SDK/协议细节"，成本很低（不是要做统一 OpenAI 兼容协议这种重的多供应商框架），值得保留。**V1 依然只交付一个 Provider 实现**，不为潜在的第三个供应商预先设计任何东西。

### 2. 新增独立 `llm/` 包，不并入 `api/`
`api/` 包整个是围绕抖音 Web API（签名、Cookie、UA 绑定）组织的，语义上是"抖音客户端"；LLM 调用是完全不同的外部依赖（认证方式、协议、失败模式都不同）。混进 `api/` 会污染那个包"专注抖音"的边界。新包结构：
- `llm/LlmProvider.kt`（接口）
- `llm/providers/GeminiProvider.kt`（Gemini 实现，内部持有自己的 OkHttp/Retrofit 客户端）
- `llm/LlmModels.kt`（provider 无关的领域模型：`TagSuggestionRequest`/`TagSuggestionResponse`/`VisualFeatureProfile`/`TagCandidate`）
- `llm/TagSuggestionRequestBuilder.kt`（组装封面/关键帧/文案/作者历史/Preference 摘要/few-shot 样例/标签词表成 `TagSuggestionRequest`）

### 3. API Key：暂不加密，明文存 `AppSettings`
用户决策：当前阶段不引入 `androidx.security:security-crypto`。Gemini API Key 与其余 `AppSettings` 偏好项（画质偏好、高频阈值等）同等对待，走同一个 `blitz_app_settings` SharedPreferences 文件，新增 `KEY_GEMINI_API_KEY` 与 `KEY_AI_SUGGESTION_ENABLED`（默认 `false`）。
**不做的事**：不在任何日志/异常堆栈里打印 Key 值，遵循项目现有"日志不打印 Cookie/msToken"的基调，即使这里的存储本身选择了明文。
**给用户的建议**（不是本设计要实现的代码）：在 Google Cloud Console / AI Studio 给这个 Key 加应用限制（Android 包名 + 签名指纹）与 API 限制（仅 Generative Language API），降低明文存储在自用设备上被提取后滥用的风险；这是账号侧配置，不是 App 代码的一部分。

### 4. 关键帧策略：12~20 候选帧 + ML Kit 人脸检测优选 + 固定时间点兜底
采纳评审文档"不建议只固定取极少数均匀帧"的意见，推翻最初设计"固定 10%/50%/90% 三点"的方案：
1. `util/VideoFrameExtractor`（复用 `MediaOrientationProbe` 同款 `MediaMetadataRetriever` 用法）按视频时长均匀抽取候选帧，数量随时长动态（例如每 2~3 秒一帧，上限 20 帧，具体阈值实现阶段按真机性能测试调整）。
2. `util/FaceFrameSelector` 用 **ML Kit Face Detection**（`com.google.android.gms:play-services-mlkit-face-detection`，bundled、免费、设备端处理、无用量计费）对候选帧跑人脸检测，按"检测到人脸 + 人脸边界框占比更大（更清晰的正脸/半正脸）"打分排序，取分数最高的若干张作为 Face Frames。
3. 从候选帧里额外按时间跨度均匀抽几张作为 Body/General Frames（覆盖动作、场景、服饰整体呈现），与 Face Frames 去重合并。
4. 最终上传张数（含封面）裁到 ≤ 8~12 张，对齐文档第 7 节的建议区间。
5. **降级路径**：候选帧全部检测不到人脸（无人物、正脸被遮挡/背对镜头等）时，`FaceFrameSelector` 返回空列表，`VideoFrameExtractor` 退回纯时间点均匀采样，不阻断请求——对应文档验收标准"无清晰人脸时必须降低置信度或不推荐相关标签"，由 Prompt 明确告知模型"以下证据不含清晰人脸"，让模型自行降低颜值类标签的置信度，而不是客户端强行拦截。
6. 抽出的帧统一缩放到最长边 ≤512px 并 JPEG 压缩，控制请求体大小与费用（沿用最初设计的压缩策略，仅采样逻辑改变）。
7. bundled 版本选型理由：项目当前没有任何 Play Services ML Kit 依赖，unbundled 版本首次使用需要联网下载检测模型，而分析场景可能发生在下载完成后立即触发（不保证有网），选 bundled 换取"首次即可用"，代价是 APK 增大约 1~1.5MB，对自用 App 可接受。

### 5. 标签体系补数值 `id`：新增列而非改主键
`TagEntity` 现状 `tagName: String` 是主键，`video_tags` 关联表用 `tagName` 做外键，标签管理页的重命名靠"批量 UPDATE `tagName`"实现（详见 `VideoTagEntity` KDoc「方案 C，去规范化关联表」）。改主键为数值 id 会牵动整个标签子系统的既有设计，成本和收益不成比例。
**做法**：`tags` 表新增 `id: Long = 0`（非主键，无 `@PrimaryKey`/`autoGenerate`，因为 Room 的 autoGenerate 只作用于主键列），`description: String = ""` 一并加入。
- **新建标签自动分配**：`VideoTagRepository.createTag` 内部 `SELECT MAX(id) FROM tags` 后 `+1` 赋值，不依赖数据库自增机制。与既有的 `sortOrder` 分配（`getMaxSortOrder()+1` 后插入）是同一种"读最大值后 +1"模式，两次 DAO 调用不包在 Room `@Transaction` 里——这与 `sortOrder` 现有实现的并发特性一致（单用户 App，理论并发创建标签的竞态可忽略），不为这一个字段引入与既有代码风格不一致的新事务机制。
- **历史数据回填**：设置页新增一个**临时**「补齐标签 ID」按钮（`SettingsViewModel.backfillTagIds()`），一次性把 `id == 0` 的历史标签按 `sortOrder` 升序分配连续递增 id（从当前 `MAX(id) + 1` 开始）。该操作**幂等**——重复点击只处理仍是 `id == 0` 的行，不会覆盖已分配的 id。这个按钮的历史使命是"帮存量数据补课"，全量用户跑过一次后即可在后续版本移除，不作为长期功能保留。
- `id` 的唯一用途是给 AI 相关的新表（`VideoAiAnalysisEntity` 建议结果、`TagPreferenceEntity` 统计）做稳定外键，避免用户重命名标签导致历史统计/建议记录的关联断裂（`tagName` 变了，`id` 不变）。**所有既有查询/关联（`video_tags`、标签管理页、筛选栈）继续用 `tagName`，不做任何改动**。

### 6. VisualFeatureProfile 结构化持久化
仿照评审文档第 6 节的结构，`VideoVisualFeatureEntity` 存放一次分析的完整视觉证据：
```
id INTEGER PK AUTOINCREMENT
analysisId INTEGER NOT NULL  -- 关联 VideoAiAnalysisEntity.id
awemeId TEXT NOT NULL
profileJson TEXT NOT NULL    -- Gson 序列化的 VisualFeatureProfile
createdAtMillis INTEGER NOT NULL
```
`profileJson` 直接存 Gson 序列化字符串，不引入 Room `TypeConverter` 存复杂对象——项目当前 `data/db/` 下没有任何 `TypeConverter` 先例，读取时在 Repository 层手动 `Gson().fromJson(...)` 反序列化即可，维持现有"表结构简单、复杂结构走 JSON 字符串"的一贯做法（类似 `userRelation` 用 `|` 分隔字符串编码多值,这里数据结构更复杂所以改用 JSON 而非 pipe）。

`VideoAiAnalysisEntity` 存分析元数据（不含图片/大 JSON 字段本体，方便单独查询"这个视频分析过几次、用的什么模型"）：
```
id INTEGER PK AUTOINCREMENT
awemeId TEXT NOT NULL
provider TEXT NOT NULL       -- 如 "gemini"
model TEXT NOT NULL          -- 具体模型版本号
profileVersion INTEGER NOT NULL  -- VisualFeatureProfile 的 schema 版本，模型升级时可比较
suggestedTagIds TEXT NOT NULL DEFAULT ''  -- 本次返回的候选标签 id，'|' 分隔，对齐 userRelation 的既有编码约定
succeeded INTEGER NOT NULL   -- 0/1
createdAtMillis INTEGER NOT NULL
```
**`suggestedTagIds` 是后续所有反馈写入的唯一数据源**：Decision 12 的结果契约只回传一个 `analysisId`，不是原始建议集合本身，写反馈时必须能凭这个 id 单独查回"当时到底建议了什么"——单条编辑弹窗场景下这个查询几乎立即发生（保存按钮点击后），但 `batch-ai-tag-review` 的分组确认可能发生在分析完成后的几分钟到几小时之后，早已脱离任何内存态，**没有这个字段，批量场景的反馈记录完全没有数据源可用**。这个字段在 `AiTagSuggestionRepository` 产出建议、落库 `VideoAiAnalysisEntity` 的那一刻就写入，之后只读不改。

### 7. 反馈粒度改为按标签行：`VideoTagFeedbackEntity`
最初设计只存"建议标签集合 vs 确认标签集合"两个 pipe 分隔字符串，无法回答"哪个标签经常被拒绝"这类按标签的问题。改为逐行记录：
```
id INTEGER PK AUTOINCREMENT
awemeId TEXT NOT NULL
analysisId INTEGER NOT NULL   -- 关联 VideoAiAnalysisEntity.id
tagId INTEGER NOT NULL        -- 关联 tags.id（見 Decision 5）
kind TEXT NOT NULL            -- "ACCEPTED" | "REJECTED" | "MISSED"
confidence REAL               -- 模型返回的置信度，MISSED 场景为 null（AI 未建议，无置信度）
createdAtMillis INTEGER NOT NULL
```
一次「AI 建议 → 用户确认」写入多行：AI 建议且保留的标签各一行 `ACCEPTED`，AI 建议但被删除的各一行 `REJECTED`，AI 未建议但用户手动勾选的各一行 `MISSED`——直接对应评审文档第 10 节的 Feedback 分类表。

### 8. `TagPreferenceEntity`：按标签物化统计缓存
```
tagId INTEGER PK             -- 关联 tags.id
suggestedCount INTEGER NOT NULL DEFAULT 0
acceptedCount INTEGER NOT NULL DEFAULT 0
rejectedCount INTEGER NOT NULL DEFAULT 0
missedCount INTEGER NOT NULL DEFAULT 0
acceptanceRate REAL NOT NULL DEFAULT 0
recommendedThreshold REAL NOT NULL DEFAULT 0.5
updatedAtMillis INTEGER NOT NULL
```
复算方式对齐 `author_tag_frequency` 缓存表的既有模式（`VideoTagRepository.recomputeAuthorTagFrequency` 的姊妹方法 `recomputeTagPreference`）：`DELETE` 全表再一条 `INSERT...SELECT` 从 `VideoTagFeedbackEntity` 聚合写入。触发点同样是两个：设置页手动按钮 + 每次写入新反馈后自动触发一次（不是每次分析都重算，是每次**反馈落库**后）。`recommendedThreshold` 字段预留给 V2"按标签设置自动采用阈值"，V1 阶段固定写默认值不使用。

### 9. `PreferenceProfileEntity`：周期性自然语言偏好摘要
```
id INTEGER PK AUTOINCREMENT
version INTEGER NOT NULL
profileText TEXT NOT NULL
sampleCount INTEGER NOT NULL   -- 生成这版摘要时依据的反馈样本数
updatedAtMillis INTEGER NOT NULL
```
只保留最新一条（查询取 `id` 最大的一行，旧版本不主动清理，量级很小）。生成时机：累计新增反馈达到阈值（沿用评审文档"每 20~50 个已确认视频"的区间，具体数字留实现阶段按实际使用频率定）后，调用 `GeminiProvider.summarizePreference`（纯文本输入：最近若干条 accepted/rejected 样例的标签名 + 视频描述摘要，不带图片），把返回文本存为新版本。这一步**不在每次单条编辑后触发**，避免过于频繁的额外调用产生费用。

### 10. Few-shot / 相似案例检索：规则匹配，不用向量库
查询时优先取该 `videoAuthorSecUserId` 最近 N 条 `VideoTagFeedbackEntity`（`ORDER BY createdAtMillis DESC`），不足 N 条时用全局最近样例补齐，N 定为 5。对颜值类标签（可爱/甜妹/纯欲），额外按评审文档第 12 节的维度优先级（可爱→Face，甜妹→Face+Expression，纯欲→Face+Expression+Body/Styling）在 `VideoVisualFeatureEntity` 里筛选对应维度证据完整的历史样例优先展示。不引入 embedding/向量数据库，数据量到达需要向量检索的规模之前，规则查询足够。

### 11. AI 建议采用"叠加"语义，不覆盖用户已勾选的标签
（沿用最初设计）点击「AI 建议」时，返回结果与当前已勾选集合取**并集**，不清空已有勾选——`TagEditDialogFragment` 本身语义是"整体覆盖式编辑"（预勾当前标签），AI 建议只是在这个基础上追加候选项。词表外的返回项按 `tagId` 精确匹配过滤后再并入。

### 12. `TagEditDialogFragment` 结果契约扩展
（沿用最初设计）新增可选的 `RESULT_AI_ANALYSIS_ID`（关联本次 `VideoAiAnalysisEntity.id`，未使用 AI 建议则不带这个 key），供宿主侧（`ManageVideoViewModel`）据此判断是否要调用反馈记录方法、以及写入 `VideoTagFeedbackEntity` 时关联哪次分析。

### 13.（可选增强）prompt 组装引用标签层级信息
（沿用最初设计，`tag-hierarchy` 已经落地）`TagSuggestionRequestBuilder` 组装请求时带上 `getParentMap()` 的父子关系，提示模型优先在同一大类下挑更贴切的细分标签。**只影响 prompt 内容，不改变行为契约**：返回结果依旧只按 `tagId` 精确匹配词表过滤，依旧和当前已勾选集合取并集；不做"选了细分标签就强制补大类标签"的自动注入（`tag-hierarchy` 已明确否决这个规则）。

### 14. 标签描述编辑入口：`TagManageActivity` + Compose 弹窗 `TagDescriptionDialogFragment`
`description` 字段（Decision 5）只是加了存储位置，需要一个真实入口让用户填写，否则永远是空字符串、对 AI 建议没有任何帮助。
- **宿主页面复用 `TagManageActivity`**，不新建页面——它已经是标签元数据（名称/排序/上级）的编辑入口，描述是同一类"标签元数据"，放在一起符合现有职责边界。
- **弹窗走 Compose，不再加一个 `AlertDialog`**：`TagManageActivity` 现有的"编辑名称/删除/设置上级"三个入口都是历史遗留的 `AlertDialog.Builder`（设置上级用的是 `setSingleChoiceItems`），但项目现在的硬约定是"新增 UI（页面/对话框）一律 Compose"。这三个存量弹窗不动（不属于本次改动范围），新增的「编辑描述」入口新建一个 `dialog/TagDescriptionDialogFragment`，继承既有的 `dialog/ComposeDialogFragment` 基类，与 `TagEditDialogFragment`/`BatchTagDialogFragment` 同一套弹窗范式（`DialogContainer`/`DialogHeadline`/`DialogActions`）。
- **交互**：单个多行文本框（预填当前 `description`，为空则占位提示"帮助 AI 理解这个标签的判断标准"），限长 **200 字符**——标签词表可能有几十个标签，每个都要带着 description 一起发给 LLM 组装进 prompt，单条描述过长会不必要地推高请求体积和成本，200 字符足够写清楚一条判断标准。超出长度在输入框内直接截断/禁止继续输入，不做提交时才报错的体验。
- **结果契约**：走 `FragmentResult`（新增 `RESULT_TAG_NAME` / `RESULT_DESCRIPTION`），宿主 `TagManageActivity` 消费后调用新增的 `VideoTagRepository.setTagDescription(tagName, description)`，直接 `UPDATE tags SET description = ? WHERE tagName = ?`。
- **不计入 `tagEditCount`**：`tagEditCount` 统计的是"视频打标签"这个操作被编辑的次数（见 CLAUDE.md 持久化章节），标签描述是标签本身的元数据编辑，是完全不同的维度，`setTagDescription` 不touch 任何 `downloaded_videos`/`video_tags` 行，语义上也不应该计数到那个字段里。
- **列表展示**：`TagManageAdapter.ViewHolder` 现有"有上级则在标签名下方展示一行父标签"的位置，追加"有描述则再展示一行单行省略号截断预览"。两者可以同时出现（既有上级又有描述），按现有从上到下堆叠即可，空字符串（无描述）不占用展示行，与 `parentTagName` 空字符串表示无上级的既有约定一致。

### 15. 作者先验携带占比（ratio），复用现有聚合表、不新增表
评审文档第 9 节的 `AuthorProfile` 示例是 `{authorId, sampleCount, topTags: [{tagId, count, ratio}]}`——一个数字（如"该作者 30 个视频里舞蹈占 24 个，ratio=0.80"）比一份不带权重的标签名单更能让模型判断"这个先验有多强"。项目现有 `author_tag_frequency` 表只存了 `(secUserId, tagName, count)`，没有该作者的视频总数，最初设计直接复用 `getHighFrequencyTagsForAuthor` 只拿到一份名单，丢了这层信息。

**做法**：不新建表——`downloaded_videos` 本身就能按 `videoAuthorSecUserId` 统计出总数，`ratio` 完全是可推导数据，不需要物化存储。`AuthorTagFrequencyDao` 新增一条查询，在 `author_tag_frequency` 基础上关联 `tags`（取 `id`）与一个"该作者视频总数"的子查询算出 `ratio`：
```sql
SELECT t.id AS tagId, atf.tagName AS tagName, atf.count AS count, s.total AS sampleCount,
       CAST(atf.count AS REAL) / NULLIF(s.total, 0) AS ratio
FROM author_tag_frequency atf
JOIN tags t ON t.tagName = atf.tagName
JOIN (SELECT COUNT(*) AS total FROM downloaded_videos WHERE videoAuthorSecUserId = :secUserId) s
WHERE atf.secUserId = :secUserId AND atf.count >= :threshold
ORDER BY atf.count DESC
```
`NULLIF(s.total, 0)` 防止分母为 0 时除零报错（SQLite 除零本身不抛异常但会得到无意义值，显式处理更清楚），对应到 Kotlin 侧 `ratio: Float?`，为 `null` 时 prompt 组装阶段直接跳过该标签的占比数字（只给标签名，不给一个虚假的 0 或 1）。

`VideoTagRepository` 新增：
```kotlin
data class AuthorTagRatio(val tagId: Long, val tagName: String, val count: Int, val ratio: Float?)
data class AuthorProfile(val secUserId: String, val sampleCount: Int, val topTags: List<AuthorTagRatio>)

suspend fun getAuthorProfileForAi(secUserId: String, threshold: Int): AuthorProfile?
```
`secUserId` 为空或查询结果为空时返回 `null`（作者无稳定 id 或没有达到阈值的标签），`TagSuggestionRequestBuilder` 按 `null` 处理为"不携带作者先验"，与最初设计的降级路径一致。

**不改动的既有方法**：`getHighFrequencyTagsForAuthor(secUserId, threshold): List<String>` 保持原样，继续服务于批量打标签弹窗的自动预勾选——那个场景只需要"选中哪些标签"，不需要占比数字，没有理由为它也搭上这层计算。两个方法读同一张缓存表，互不影响。

## Risks / Trade-offs

- **[风险] 单次调用产生真实费用** → 默认关闭 + 显式点击触发 + 用户自备 Key 缓解；不做用量提醒/限流。
- **[风险] 视频封面/关键帧发给第三方存在内容隐私问题** → 默认关闭、需要显式开启是唯一防线；设置页给出明确说明文案。
- **[风险] 模型返回内容不在标签词表内（幻觉）** → 按 `tagId` 精确匹配过滤，规避产生游离标签。
- **[风险] 网络延迟阻塞弹窗体验** → 异步请求 + 加载态，失败不影响手动打标签这条既有路径继续可用。
- **[风险] API Key 明文存储，设备被入侵/adb backup 时可被读出** → 用户在本次评审中明确接受该取舍（自用 App，费用记在自己账下）；建议在 Google Cloud 侧对 Key 加应用/API 限制降低滥用风险，这是账号配置而非代码变更。
- **[风险] ML Kit bundled 依赖增大 APK 体积、增加 Google Play Services 耦合** → 体积增量可接受（~1.5MB）；项目已经依赖 Google 生态（Play Services 在绝大多数 Android 设备预装），风险低。
- **[风险] 人脸检测在极端场景（强逆光、侧脸、低分辨率）误判或漏检** → 有降级路径（检测不到人脸时退回时间点采样 + prompt 告知模型证据不含清晰人脸，模型自行降低置信度），不做人脸检测结果的二次人工校验。
- **[风险] 逐标签反馈表/统计表数量随使用增长** → 个人使用量级下（文档场景是数百上千条视频）不会成为问题，不做清理/归档策略。
- **[风险] Gemini 的 `responseSchema` 结构化输出在极端情况下仍可能返回不完全符合 schema 的内容** → 解析失败按"分析失败"处理，不落库半成品数据，用户可重试或退回手动打标签。

## Migration Plan

Room `AppDatabase` 当前真实版本 **17**，本变更拆成两个迁移单元（对应两个逻辑变更，不是每张表一个版本号，避免版本号无谓膨胀）：

- **`MIGRATION_17_18`**：`tags` 表新增 `id INTEGER NOT NULL DEFAULT 0`、`description TEXT NOT NULL DEFAULT ''` 两列。不改动任何既有列，历史标签 `id` 全部是 0，需要走「补齐标签 ID」按钮或新建标签时的自动分配逐步补齐。
- **`MIGRATION_18_19`**：一次性建齐本变更需要的 5 张新表——`video_ai_analysis`、`video_visual_feature`、`video_tag_feedback`、`tag_preference`、`preference_profile`。全部是 `CREATE TABLE IF NOT EXISTS`，与任何既有表无外键强约束（用应用层保证引用一致性,不用 Room `ForeignKey`，因为这些表允许独立于 `downloaded_videos`/`tags` 的生命周期存在,例如标签被删除后历史分析记录仍应保留用于回溯）。

两个迁移都不会造成既有数据丢失——不修改、不删除任何现有列或表；`tags.id` 默认值 0 是"未分配"的合法状态，靠后续动作补齐，不阻塞正常使用。

- 依赖新增：`com.google.android.gms:play-services-mlkit-face-detection`（人脸检测）；LLM 网络调用复用既有 OkHttp/Retrofit/Gson，无需新增网络库；**不新增** `androidx.security:security-crypto`。
- 功能整体默认关闭，用户不主动开启则完全无感知（不发起任何新的网络请求）；数据库迁移在应用升级时自动跑，与是否开启 AI 功能无关（新表建了但不写入）。

## Open Questions

- 具体接入的 Gemini 模型版本号/请求参数细节（temperature、max_tokens 等）——不影响 spec 定义的行为契约，实现阶段按当时可用的模型直接定即可。
- `PreferenceProfileEntity` 重新生成的具体阈值（20 还是 50 个新反馈）——留到实际观察使用频率后再定，不影响本次任务范围。
- ML Kit 候选帧数量的具体上限（12/16/20）与最终上传张数（8/10/12）——需要真机实测性能与请求体大小后再收敛，不影响本设计的行为契约。
