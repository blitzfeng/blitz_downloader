## Context

参考 `proposal.md` 了解动机；本设计基于当前代码库的实际约定：`api/` 包是 Retrofit + OkHttp + Gson 对接抖音接口的既有范式，`config/AppSettings.kt` 是运行时偏好的唯一入口（每次 getter 直读 SharedPreferences，不做内存缓存），Room 迁移必须显式书写、不依赖 `fallbackToDestructiveMigration`，`data/db/` 下已有的 `author_tag_frequency` 缓存表与 `VideoTagRepository.getHighFrequencyTagsForAuthor` 可以直接复用做作者历史上下文，`util/MediaOrientationProbe` 已经在用 `MediaMetadataRetriever` 读取本地视频，抽帧可以复用同一套 API。当前 `AppDatabase.version = 16`。

## Goals / Non-Goals

**Goals:**
- 在单条记录标签编辑弹窗新增一条"看得懂视频内容"的建议来源，作为对纯统计式高频标签预勾选的补充。
- 建立一套轻量的反馈样例积累机制（存 Room，不训练模型），并在后续调用时把最近样例当 few-shot 上下文喂回去。
- 复用现有架构范式（Retrofit/OkHttp/Gson、AppSettings、Room 迁移规范、Compose 弹窗结果契约），不引入与既有代码风格脱节的新模式。

**Non-Goals:**
- 不做批量/自动触发（已在 spec 里明确约束），本设计只覆盖单条编辑弹窗这一条路径。
- 不做多 LLM 供应商可插拔架构——v1 先接一家，把结构做对，不为假设的未来需求过度设计。
- 不做模型微调/训练管线；"经验积累"完全靠 prompt 里塞 few-shot 样例实现。
- 不做反馈样例表的长期容量治理（清理/归档策略），规模到达个人使用量级前不是问题，留作后续按需处理。

## Decisions

### 1. LLM 供应商：先接 Anthropic Claude Messages API（vision），不做多供应商抽象
项目本身运行在 Claude Code 生态里，Claude 的 Messages API 原生支持图片输入，文档与鉴权方式确定。
多供应商抽象（比如统一成 OpenAI 兼容协议）会在 v1 就引入不必要的接口层，而目前只有一个真实使用场景。
**替代方案**：做一层 provider 无关的抽象接口——推迟到真的要接第二家时再重构，现在做是过度设计。

### 2. 新增独立 `llm/` 包，不并入 `api/`
`api/` 包整个是围绕抖音 Web API（签名、Cookie、UA 绑定）组织的，语义上是"抖音客户端"；LLM 调用是完全不同的外部依赖（认证方式、协议、失败模式都不同）。混进 `api/` 会污染那个包"专注抖音"的边界。新包内部仍然复用项目已有的 OkHttp/Retrofit/Gson 技术栈，只是接口定义、拦截器、模型类分开放：`llm/LlmApiClient.kt`（OkHttp/Retrofit 客户端）、`llm/LlmApiService.kt`（Retrofit 接口）、`llm/LlmModels.kt`（请求/响应 Gson 模型）、`llm/TagSuggestionRequestBuilder.kt`（组装封面/关键帧/文案/作者历史/few-shot 样例成请求体）。

### 3. API Key 加密存储，不进普通 SharedPreferences
`AppSettings` 现有的 key（画质偏好、高频阈值等）都是非敏感偏好，明文存 SharedPreferences 没问题。API Key 是等同密码的凭证，新增依赖 `androidx.security:security-crypto`，用 `EncryptedSharedPreferences`（Android Keystore 支持的主密钥）单独存这一个值，不复用 `AppSettings` 现有的 `blitz_app_settings` 文件——避免明文偏好文件被 adb backup / root 场景下轻易读出 Key。功能开关（是否启用）仍是非敏感偏好，留在 `AppSettings` 里正常处理。
**替代方案**：明文存 `AppSettings`——实现更简单，但项目自己的隐私基调（日志不打印 Cookie/msToken）已经说明这类凭证要认真对待，不采用。

### 4. 关键帧抽取：本地 `MediaMetadataRetriever`，固定采样点 + 压缩
复用 `MediaOrientationProbe` 已经在用的 `MediaMetadataRetriever` API，在视频时长的 10%/50%/90% 三个时间点各取一帧，加封面图共 4 张以内；抽出的帧在上传前统一缩放到最长边 ≤512px 并 JPEG 压缩，控制请求体大小与费用。不做镜头切分/关键帧检测算法——那是明显过度设计，固定采样点已经能覆盖视频的开头/中段/结尾。

### 5. AI 建议采用"叠加"语义，不覆盖用户已勾选的标签
点击「AI 建议」时，返回结果与当前已勾选集合取**并集**，不清空已有勾选——`TagEditDialogFragment` 本身语义是"整体覆盖式编辑"（预勾当前标签），AI 建议只是在这个基础上追加候选项，不能因为用户点了一下建议按钮就丢掉本来已经确认的标签。词表外的返回项按 spec 要求丢弃后再并入。

### 6. 反馈样例表结构与查询策略
新表 `ai_tag_feedback`：
```
id INTEGER PK AUTOINCREMENT
awemeId TEXT NOT NULL
videoAuthorSecUserId TEXT NOT NULL DEFAULT ''  -- 同作者优先采样用
suggestedTags TEXT NOT NULL   -- '|' 分隔，格式对齐现有 userRelation 的既有约定
confirmedTags TEXT NOT NULL   -- '|' 分隔
createdAtMillis INTEGER NOT NULL
```
Few-shot 采样：查询时优先取该 `videoAuthorSecUserId` 最近 N 条（`ORDER BY createdAtMillis DESC LIMIT N`），不足 N 条时用全局最近样例补齐，N 定为 5（足够体现风格、又不至于把 prompt 撑得太大）。这张表只在"用户经由 AI 建议入口完成编辑"时写入一行，不建索引之外的额外聚合（数据量级不需要）。

### 7. 新增独立 `AiTagSuggestionRepository`，不塞进 `VideoTagRepository`
`VideoTagRepository` 的既有职责是"标签名册 + 视频标签关联"的读写，边界清楚（见其类头 KDoc）。反馈样例与 LLM 调用编排是另一个关注点（涉及网络、加密存储读取、prompt 组装），塞进去会让 `VideoTagRepository` 职责膨胀。新 Repository 内部会调用 `VideoTagRepository.getAvailableTags()` / `getHighFrequencyTagsForAuthor()` 读取既有数据，两者是组合关系，不是继承或合并。

### 8. `TagEditDialogFragment` 结果契约扩展：新增可选的"本次是否用过 AI 建议"标记
现有 `FragmentResult`（`RESULT_AWEME_ID` / `RESULT_TAGS`）只携带最终标签集合。要在宿主侧写入反馈样例，需要知道"AI 建议时的原始建议集合"。做法：弹窗内部把「点击 AI 建议那一刻返回的标签集合」存进 `rememberSaveable`，随结果一并回传一个可空的 `RESULT_AI_SUGGESTED_TAGS`（未使用 AI 建议则不带这个 key）。宿主（`ManageVideoViewModel`）据此判断是否要调用反馈记录方法——不影响未使用 AI 建议路径的现有行为。

### 9.（可选增强）prompt 组装引用标签层级信息，帮模型选更精准的细分标签
若 `tag-hierarchy` 变更已经落地，`TagSuggestionRequestBuilder` 在组装请求时额外带上标签词表的父子关系（例如"颜值"下有"纯欲/可爱/甜妹"这几个细分），提示模型优先在同一大类下挑更贴切的细分标签，而不是笼统只给大类、或者在同一层级上重复堆砌语义相近的标签。这**只影响 prompt 内容和模型的选择倾向，不改变本变更已经定义的行为契约**：返回结果依旧只按现有标签词表精确匹配过滤（Requirement「建议结果仅限于系统现有标签词表」），依旧和当前已勾选集合取并集（Decision 5）。`tag-hierarchy` 明确否决了"写入时强制补全祖先标签"，这里同样不例外——模型建议了细分标签但没建议大类标签时，就只应用细分标签，不做任何自动补全。
**依赖关系是单向可选的**：`tag-hierarchy` 未落地时，这一步简单跳过（prompt 里不带层级信息），其余行为不受影响；不因为这条增强而让本变更依赖 `tag-hierarchy` 先合入才能工作。

## Risks / Trade-offs

- **[风险] 单次调用产生真实费用** → 通过默认关闭 + 显式点击触发 + 用户自备 Key（已在 spec 约束）缓解；不做用量提醒/限流，成本完全由用户自行判断。
- **[风险] 把视频封面/关键帧发给第三方存在内容隐私问题** → 默认关闭、需要显式开启是唯一防线；在设置页开启入口旁给出明确说明文案，CLAUDE.md 记录这一隐私影响，供后续维护者不会误以为是"纯本地"功能。
- **[风险] 模型返回内容不在标签词表内（幻觉）** → spec 已要求按词表精确匹配过滤，规避产生游离标签。
- **[风险] 网络延迟阻塞弹窗体验** → 异步请求 + 加载态，失败不影响手动打标签这条既有路径继续可用（spec 已覆盖）。
- **[风险] Few-shot 样例里混入用户误操作/后悔修改的坏样例，反而带偏后续建议** → 按最近 N 条优先，坏样例会被后续更多正确样例自然稀释；v1 不做样例质量筛选，视实际使用效果再决定要不要加。
- **[风险] `EncryptedSharedPreferences` 引入新依赖 `androidx.security:security-crypto`** → 该库是 Jetpack 官方维护、体量小，风险可控；不引入的替代方案（明文存储）风险更高，权衡后接受这个新依赖。

## Migration Plan

- Room `AppDatabase` 版本 `16 → 17`，新增 `MIGRATION_16_17` 只做一件事：`CREATE TABLE IF NOT EXISTS ai_tag_feedback (...)`。不改动任何既有表结构，纯增量迁移，回滚只需不发布这次迁移（不会造成既有数据丢失，因为没有对旧表做任何变更）。
- 功能整体是新增能力，不涉及存量用户数据迁移；上线即默认关闭，用户不主动开启则完全无感知（不发起任何新的网络请求、不新增任何 UI 元素）。
- 依赖新增：`androidx.security:security-crypto`（API Key 加密存储）；LLM 网络调用复用既有 OkHttp/Retrofit/Gson，无需新增网络库。

## Open Questions

- 具体接入的 Claude 模型版本号/请求参数细节（temperature、max_tokens 等）——不影响 spec 定义的行为契约，实现阶段按当时可用的模型直接定即可，之后要换型号也不需要改 spec/任务拆分。
- 反馈样例表长期增长后是否需要清理策略——个人使用量级下短期内不会成为问题，留到实际观察到体量问题时再处理，不影响本次任务范围。
