## 1. 依赖与数据层

- [ ] 1.1 在 `app/build.gradle.kts` 添加 `androidx.security:security-crypto` 依赖，验证：`./gradlew.bat compileDebugKotlin` 编译通过且能 import `EncryptedSharedPreferences`
- [ ] 1.2 新增 `data/db/AiTagFeedbackEntity.kt`（`ai_tag_feedback` 表：`id`/`awemeId`/`videoAuthorSecUserId`/`suggestedTags`/`confirmedTags`/`createdAtMillis`），验证：字段与 design.md 「反馈样例表结构」一致
- [ ] 1.3 新增 `data/db/AiTagFeedbackDao.kt`（插入一条反馈；按 `videoAuthorSecUserId` 查最近 N 条；全局查最近 N 条兜底），验证：方法签名覆盖 design.md 描述的「同作者优先、不足补全局」采样策略
- [ ] 1.4 `AppDatabase.kt` 注册新 entity/DAO，版本号 16→17，新增 `MIGRATION_16_17` 只建表，验证：`./gradlew.bat testDebugUnitTest assembleDebug -q` 通过（含现有单元测试不受影响）
- [ ] 1.5 更新 `.cursor/rules/db-schema.md`：版本演进历史加 v17 一行，新增「表五：`ai_tag_feedback`」小节，验证：文档内容与迁移代码字段一致

## 2. API Key 安全存储与功能开关

- [ ] 2.1 新增 `config/AiApiKeyStore.kt`，基于 `EncryptedSharedPreferences` 单独存取 API Key（不复用 `AppSettings` 的明文文件），验证：写入后重新读取能拿到相同值，且该 SharedPreferences 文件内容不是明文
- [ ] 2.2 `AppSettings.kt` 新增「是否启用 AI 建议标签」开关（默认 `false`），验证：getter 每次直读不缓存，符合既有 `AppSettings` 约定

## 3. LLM 网络客户端

- [ ] 3.1 新增 `llm/LlmModels.kt`：请求体（图片数据、文案、作者历史标签、few-shot 样例、允许标签词表）与响应体（建议标签列表）的 Gson 数据类，验证：能正确序列化/反序列化一个手工构造的样例
- [ ] 3.2 新增 `llm/LlmApiService.kt` + `llm/LlmApiClient.kt`（Retrofit 接口 + OkHttp 客户端，鉴权头从 `AiApiKeyStore` 读取），验证：能对 Claude Messages API 发出一次真实请求并拿到响应（本地手动联调一次）
- [ ] 3.3 新增 `llm/TagSuggestionRequestBuilder.kt`：组装封面图/关键帧、`desc`、作者高频标签、few-shot 样例、标签词表为一次请求，验证：单元测试覆盖「作者无高频标签时上下文为空但请求仍正常构造」这条边界（对应 spec 场景）
- [ ] 3.4 （可选，依赖 `tag-hierarchy` 是否已落地）`TagSuggestionRequestBuilder` 组装请求时附带标签父子关系，帮模型在同一大类下选更精准的细分标签，验证：`tag-hierarchy` 未落地时该步骤跳过、请求仍正常构造；已落地时单元测试覆盖"层级信息正确出现在请求里"（对应 design.md Decision 9）

## 4. 视频关键帧抽取

- [ ] 4.1 新增 `util/VideoFrameExtractor.kt`，复用 `MediaMetadataRetriever`（同 `MediaOrientationProbe` 的用法），按 10%/50%/90% 时间点抽帧，缩放至最长边 ≤512px 并 JPEG 压缩，验证：对一个本地测试视频文件抽出 3 张符合尺寸约束的图片
- [ ] 4.2 处理抽帧失败/本地文件不存在的情况（返回空列表而非抛异常中断整个建议流程），验证：删除本地文件后触发流程，确认仍能发起请求（只是没有关键帧，只用封面图，对应 spec「视觉输入」场景的降级路径）

## 5. Repository 编排层

- [ ] 5.1 新增 `data/AiTagSuggestionRepository.kt`：组合 `VideoTagRepository.getAvailableTags()` / `getHighFrequencyTagsForAuthor()`、`VideoFrameExtractor`、`AiTagFeedbackDao` 的 few-shot 采样、`LlmApiClient`，对外暴露「请求建议」方法，验证：给定一条本地测试记录能跑通端到端拿到建议列表
- [ ] 5.2 建议结果按系统标签词表精确过滤，丢弃词表外的项，验证：单元测试覆盖「返回结果含词表外名字」场景（对应 spec 场景）
- [ ] 5.3 新增反馈样例写入方法（存 `AiTagFeedbackEntity`），验证：调用后能在 `ai_tag_feedback` 表查到一行，字段与传入的建议/确认集合一致

## 6. 标签编辑弹窗集成

- [ ] 6.1 `dialog/TagEditDialogFragment.kt`：功能开关关闭时不展示「AI 建议」按钮，开启时展示，验证：分别在开关开/关两种设置下打开弹窗，按钮可见性符合 spec 场景
- [ ] 6.2 点击「AI 建议」发起请求并展示加载态，成功后把返回标签**并入**（不覆盖）当前已勾选集合，验证：手动测试点击后已勾选标签不丢失、新建议追加进去（对应 design.md「叠加语义」决策）
- [ ] 6.3 请求失败时展示错误提示，弹窗保持可用、可继续手动勾选保存，验证：断网状态下点击「AI 建议」，确认弹窗仍可正常手动操作并保存（对应 spec 场景）
- [ ] 6.4 结果契约新增可选 `RESULT_AI_SUGGESTED_TAGS`：仅在本次编辑用过「AI 建议」时携带，验证：未点击「AI 建议」直接保存时，回传结果不含该 key（与既有行为等价）

## 7. ViewModel 接线与反馈落库

- [ ] 7.1 `ManageVideoFragment.listenTagDialogResults`/`ManageVideoViewModel` 在收到带 `RESULT_AI_SUGGESTED_TAGS` 的结果时，调用 `AiTagSuggestionRepository` 写入反馈样例，验证：手动测试一次「点 AI 建议→调整→保存」，确认反馈表新增一行且两个集合与操作过程一致
- [ ] 7.2 未使用 AI 建议的手动打标签路径保持不产生反馈记录，验证：手动打标签保存后查 `ai_tag_feedback` 表确认无新增行（对应 spec 场景）

## 8. 设置页 UI

- [ ] 8.1 设置页新增分组：AI 建议标签开关 + API Key 输入项，沿用现有 `itemXxx` 卡片行样式，验证：目测 UI 与现有设置页其余分组风格一致
- [ ] 8.2 确认 API Key 输入走 `AiApiKeyStore` 加密存储而非明文 `AppSettings`，验证：代码走查确认 Key 没有写进 `blitz_app_settings` 明文文件

## 9. 文档

- [ ] 9.1 更新 `CLAUDE.md`：补充 AI 建议标签的功能说明、隐私影响（封面/关键帧发给第三方）、默认关闭这几条关键约束，验证：文档内容与最终实现行为一致
- [ ] 9.2 更新「包结构约定」表格，补充新增的 `llm/` 包放什么/不放什么，验证：表格补充后阅读通顺，与其余行风格一致

## 10. 端到端验证

- [ ] 10.1 `./gradlew.bat testDebugUnitTest assembleDebug -q` 全量通过，验证：命令零错误退出
- [ ] 10.2 真机手动走查 spec 关键场景（默认关闭不显示入口 → 开启后可用 → 建议叠加不覆盖已有标签 → 失败降级 → 反馈样例落库 → 有历史样例时新请求携带 few-shot 上下文），验证：逐条对照 spec 场景描述确认行为一致
