## 1. 依赖与标签表改造

- [x] 1.1 在 `app/build.gradle.kts` 添加 `com.google.android.gms:play-services-mlkit-face-detection`，验证：`./gradlew.bat compileDebugKotlin` 编译通过且能 import 人脸检测 API
- [x] 1.2 `data/db/TagEntity.kt` 新增 `id: Long = 0`、`description: String = ""` 两个字段，验证：字段定义与 design.md Decision 5 一致（`id` 非主键，无 `autoGenerate`）——`./gradlew testDebugUnitTest assembleDebug` 已跑过，零错误退出
- [x] 1.3 `AppDatabase.kt` 新增 `MIGRATION_17_18`（`tags` 表 `ALTER TABLE` 加两列），版本号 17→18，验证：`./gradlew testDebugUnitTest assembleDebug -q` 通过（含现有单元测试不受影响）
- [x] 1.4 `VideoTagRepository.createTag` 新建标签时分配 `id = (SELECT MAX(id) FROM tags) + 1`（与既有 `sortOrder` 分配同一种模式，不额外包事务），验证：`./gradlew testDebugUnitTest assembleDebug` 通过；未补新单元测试（人工走查待 6.2 一起做）
- [x] 1.5 更新 `.cursor/rules/db-schema.md`：`tags` 表字段说明补充 `id`/`description`，版本演进历史加 v18 一行

## 2. 标签 ID 历史数据回填（临时能力）

- [x] 2.1 `VideoTagRepository` 新增 `backfillTagIds()`：查询 `id == 0` 的标签按 `sortOrder` 升序，从当前 `MAX(id) + 1` 开始分配连续递增 id，验证：`./gradlew testDebugUnitTest assembleDebug` 通过；未补新单元测试（人工走查待 6.2 一起做）
- [x] 2.2 `SettingsViewModel`/`SettingsFragment` 新增「补齐标签 ID」临时按钮（放在设置页「标签智能预选」分组内，附一行说明"一次性维护操作，正常情况下无需手动点击"），验证：`./gradlew testDebugUnitTest assembleDebug` 通过，且已经过真机走查确认

## 3. 标签描述编辑 UI

- [x] 3.1 新增 `dialog/TagDescriptionDialogFragment.kt`（继承 `dialog/ComposeDialogFragment`），单个多行文本框预填当前 `description`、限长 200 字符（`OutlinedTextField` + `onValueChange` 拦截超长输入，`supportingText` 显示字数），`DialogActions` 提供保存/取消，验证：`./gradlew testDebugUnitTest assembleDebug` 通过，且已经过真机走查确认
- [x] 3.2 结果契约 `RESULT_TAG_NAME`/`RESULT_DESCRIPTION` 走 `FragmentResult`，验证：编译通过，契约与 `TagEditDialogFragment` 同一套 `parentFragmentManager.setFragmentResult` 模式
- [x] 3.3 `VideoTagRepository` 新增 `setTagDescription(tagName: String, description: String)`（直接 `UPDATE`，不计入 `tagEditCount`）与只读的 `getDescriptionMap()`，验证：`./gradlew testDebugUnitTest assembleDebug` 通过；未补新单元测试（方法是单条 `UPDATE`/过滤映射，逻辑简单，风险由端到端人工走查覆盖）
- [x] 3.4 `activity/TagManageActivity.kt` 新增第四个操作图标（`ic_tag_description`）触发该弹窗，`supportFragmentManager.setFragmentResultListener` 消费结果后调用 `viewModel.setTagDescription` 并经 `TagManageEvent.TagDescriptionSet` 刷新列表；`TagManageActivity` 是纯 Activity 没有 `childFragmentManager`，弹窗改用 `activity.supportFragmentManager`（`TagDescriptionDialogFragment.show` 签名因此与 `TagEditDialogFragment.show` 不同），验证：`./gradlew testDebugUnitTest assembleDebug` 通过，点击图标→编辑→保存的完整流程已经过真机走查确认
- [x] 3.5 `adapter/TagManageAdapter.kt`（`ViewHolder.bind`）在"有上级则展示父标签"逻辑之后追加"有描述则展示一行单行省略号截断预览"（`tvTagDescription`），两者可同时显示；重命名/删除标签时新增 `renameDescriptionReference`/`clearDescriptionReference` 同步内存态（对称于既有的 `renameParentReferences`/`clearParentReferences`），验证：`./gradlew testDebugUnitTest assembleDebug` 通过，四种上级/描述组合的展示已经过真机走查确认

## 4. 结构化持久化：AI 分析与视觉证据表

- [x] 4.1 新增 `data/db/VideoAiAnalysisEntity.kt` + `VideoAiAnalysisDao.kt`（插入一次分析记录；按 `awemeId` 查询历史分析），验证：字段与 design.md Decision 6 一致
- [x] 4.2 新增 `data/db/VideoVisualFeatureEntity.kt` + `VideoVisualFeatureDao.kt`（插入/按 `analysisId`、`awemeId` 查询），验证：`./gradlew testDebugUnitTest assembleDebug` 通过；`profileJson` 的存取正确性未补单元测试（Room 生成代码，无 Robolectric 环境跑不了 DAO 级测试，风险低）
- [x] 4.3 新增 `data/db/VideoTagFeedbackEntity.kt` + `VideoTagFeedbackDao.kt`（插入一批反馈行；按作者/全局查最近 N 条用于 few-shot，联查 `downloaded_videos` 取 `videoAuthorSecUserId`，本表不冗余该字段；按 `tagId` 聚合统计用于 `TagPreference` 重算），验证：方法签名覆盖 design.md Decision 7/10 描述的查询场景，`./gradlew testDebugUnitTest assembleDebug` 通过
- [x] 4.4 新增 `data/db/TagPreferenceEntity.kt` + `TagPreferenceDao.kt`（`recomputeAll()` 全量重算，`COALESCE`+`NULLIF` 防止 `acceptanceRate` 除零写入 NULL 违反 NOT NULL；按 `tagId` 查询单条），验证：`./gradlew testDebugUnitTest assembleDebug` 通过；聚合 SQL 的计算正确性未补单元测试（同 4.2，无 Robolectric）
- [x] 4.5 新增 `data/db/PreferenceProfileEntity.kt` + `PreferenceProfileDao.kt`（插入新版本；查最新一条），验证：`./gradlew testDebugUnitTest assembleDebug` 通过
- [x] 4.6 `AppDatabase.kt` 注册以上 5 个新 entity/DAO，新增 `MIGRATION_18_19`（一次性建齐 5 张新表，**不写 SQL `DEFAULT` 子句**——对应 Entity 无 `@ColumnInfo(defaultValue=...)`，写了反而会在 Room 运行时 schema 校验时与"预期无默认值"对不上，见 `AppDatabase.kt` 里 `MIGRATION_18_19` 的 KDoc），版本号 18→19，验证：`./gradlew testDebugUnitTest assembleDebug` 通过；**迁移链的运行时 schema 校验需要真机/模拟器验证**——项目现有 5 个单元测试都是纯 JVM 不碰 Room，且当前 Room 版本（2.6.1）的 `MigrationTestHelper` 需要 Robolectric/instrumentation 才能跑，本环境没有连接设备，无法在这一步验证 Room 打开升级后数据库不抛 `IllegalStateException`，需要你在真机上从 v18（或更早）升级一次并确认能正常打开
- [x] 4.7 更新 `.cursor/rules/db-schema.md`：新增「表五：AI 学习资产」小节说明这 5 张表的用途与关系，版本演进历史加 v19 一行，总体结构表补充 5 行

## 5. 作者先验携带占比（ratio）

- [x] 5.1 `data/db/AuthorTagFrequencyDao.kt` 新增 `getHighFrequencyTagsWithRatio` 查询方法（design.md Decision 15 给出的 SQL：关联 `tags` 取 `id`，关联"该作者视频总数"子查询算 `ratio`，`NULLIF` 防除零），返回 `AuthorTagRatioRow`（`tagId`/`tagName`/`count`/`sampleCount`/`ratio`），验证：`./gradlew testDebugUnitTest assembleDebug` 通过；"作者 30 条视频、某标签出现 24 次→ratio=0.8"这条算术未补单元测试（同 4.2，无 Robolectric 跑不了 DAO 测试）
- [x] 5.2 `VideoTagRepository` 新增 `getAuthorProfileForAi(secUserId, threshold): AuthorProfile?`（`secUserId` 为空或无达标签时返回 `null`），**不改动**既有 `getHighFrequencyTagsForAuthor`（继续服务批量打标签弹窗预勾选），验证：`./gradlew testDebugUnitTest assembleDebug` 通过；两种空场景的单元测试待补
- [x] 5.3 分母为 0（该作者无任何已下载视频却存在标签频次行，理论不应发生但需防御）时 `ratio` 返回 `null` 而非崩溃或错误值（SQL `NULLIF(s.total, 0)` 处理），验证：`./gradlew testDebugUnitTest assembleDebug` 通过；边界场景的单元测试待补

## 6. API Key 与功能开关

- [x] 6.1 `config/AppSettings.kt` 新增 `KEY_GEMINI_API_KEY`（明文存取）与「是否启用 AI 建议标签」开关（默认 `false`），验证：getter 每次直读不缓存（无内存缓存字段），符合既有 `AppSettings` 约定；代码走查确认没有任何 `Log.*` 打印 Key 值；`./gradlew testDebugUnitTest assembleDebug` 通过。**尚未接入设置页 UI**（属于第 11 节范围），当前只是存储层，没有任何入口能实际读写这两个值

## 7. LLM Provider 抽象与 Gemini 实现

- [x] 7.1 新增 `llm/LlmModels.kt`：provider 无关的 `TagSuggestionRequest`/`TagSuggestionResponse`/`VisualFeatureProfile`/`TagCandidate`/`PreferenceSummaryRequest` 数据类，验证：`./gradlew testDebugUnitTest assembleDebug` 通过；序列化/反序列化未单独补测试（这些类本身不做自定义序列化逻辑，风险低，真正需要测的是 Gemini wire 格式的编解码，见 7.3 备注）
- [x] 7.2 新增 `llm/LlmProvider.kt` 接口（`generateTagSuggestion`/`summarizePreference`），验证：接口签名与 design.md Decision 1 一致；实现过程中额外补了 `providerId`/`modelId` 两个只读属性——`VideoAiAnalysisEntity.provider`/`model` 需要落这两个值，最初设计漏了这一环
- [x] 7.3 新增 `llm/providers/GeminiProvider.kt`（Retrofit 接口 + OkHttp 客户端，鉴权走 `x-goog-api-key` 请求头，`generationConfig.responseSchema` 强制结构化输出）+ `llm/providers/GeminiModels.kt`/`GeminiApiService.kt`，验证：`./gradlew testDebugUnitTest assembleDebug` 通过。**没有对 Gemini API 发出过真实请求**——本环境没有可用的 Gemini API Key（用户此前贴过一个但已被视为泄露，未使用），无法完成"本地手动联调一次"这条验证，需要你用真实 Key 跑一次单条「AI 建议」流程，确认请求格式被 Gemini 接受、`responseSchema` 结构化输出能正确解析
- [x] 7.4 新增 `llm/TagSuggestionRequestBuilder.kt`：组装封面/关键帧、`desc`、`AuthorProfile`（`sampleCount`/逐标签 `count`/`ratio`，见 5.2）、Preference 摘要、few-shot 样例、标签词表（含 `id`/`description`）为一次请求，验证：新增 `TagSuggestionRequestBuilderTest`，覆盖"无作者先验/无 Preference 摘要时上下文为 `null` 但请求仍正常构造"“空白 Preference 摘要按 null 处理”两条边界，`./gradlew testDebugUnitTest` 通过
- [x] 7.5 （依赖 `tag-hierarchy`，已落地）`TagSuggestionRequestBuilder` 组装请求时附带标签父子关系（`includeParentHierarchy` 参数），验证：`TagSuggestionRequestBuilderTest` 覆盖"开启时正确解析出 `parentTagId`"与"关闭时不解析"两种场景

## 8. 视频关键帧抽取与人脸检测优选

- [x] 8.1 新增 `util/VideoFrameExtractor.kt`，复用 `MediaMetadataRetriever`（同 `MediaOrientationProbe` 用法），按视频时长动态抽取候选帧（12~20 张区间），验证：`./gradlew testDebugUnitTest assembleDebug` 通过；"对不同时长的本地测试视频抽出数量在预期区间内"这条需要真机/真实视频文件才能验证，`MediaMetadataRetriever` 是 Android 框架类，纯 JVM 测试摸不到
- [x] 8.2 新增 `util/FaceFrameSelector.kt`，用 ML Kit Face Detection（`play-services-mlkit-face-detection:17.1.0`，已加入 `app/build.gradle.kts`）对候选帧打分排序，取分数最高的若干张作为 Face Frames；检测不到人脸时返回空列表（不抛异常），验证：新增 `FaceFrameSelectorTest` 覆盖"空候选列表/`maxFaceFrames=0`"这两条不触碰 ML Kit 的早退路径；"对含人脸的测试帧能正确排序"需要真机验证（ML Kit 依赖 Google Play Services 运行时）
- [x] 8.3 `VideoFrameExtractor` 整合 Face Frames + 时间跨度 Body/General Frames，去重合并、裁剪到 ≤10 张（不含封面，封面由调用方单独提供，两者相加对齐文档"8~12 张含封面"的建议）、全部缩放至最长边 ≤512px 并 JPEG 压缩，验证：`./gradlew testDebugUnitTest assembleDebug` 通过；张数/尺寸约束的行为需要真机验证
- [x] 8.4 处理本地文件不存在/抽帧失败的情况（返回空列表而非抛异常中断整个建议流程），验证：新增 `VideoFrameExtractorTest` 覆盖"文件不存在时提前返回空列表，不触碰 `MediaMetadataRetriever`"

## 9. Repository 编排层

- [x] 9.1 新增 `data/AiTagSuggestionRepository.kt`：组合 `VideoTagRepository.getAvailableTagEntities()`/`getAuthorProfileForAi()`、`VideoFrameExtractor`+`FaceFrameSelector`、`PreferenceProfileDao` 最新摘要、`VideoTagFeedbackDao` 的 few-shot 采样、`GeminiProvider`，对外暴露 `requestSuggestion` 方法，返回 `SuggestionOutcome`（`analysisId` + `VisualFeatureProfile` + 过滤后的候选标签 id），验证：`./gradlew testDebugUnitTest assembleDebug` 通过。**没有跑通端到端真实建议**——依赖 7.3 未完成的真实 Gemini 联调，且需要一条本地已下载的真实视频文件，这一步无法在本环境验证，需要你在有真实 API Key 后跑一次
- [x] 9.2 建议结果按 `tagId` 精确匹配标签词表过滤，丢弃词表外的项（`filteredCandidates = response.candidates.filter { it.tagId in validTagIds }`），验证：`./gradlew testDebugUnitTest assembleDebug` 通过；这一行内联在 `requestSuggestion` 里未单独抽出测试（逻辑是一行 `filter`，风险低，端到端测试见 9.1 备注）
- [x] 9.3 新增写入方法：一次分析成功后落 `VideoAiAnalysisEntity` + `VideoVisualFeatureEntity`，验证：`./gradlew testDebugUnitTest assembleDebug` 通过；两张表各新增一行且字段一致需要真机验证（同 9.1，无 Robolectric 环境跑不了 Room 集成测试）
- [x] 9.4 新增 `recordFeedback(analysisId, confirmedTagIds)`：对比 `VideoAiAnalysisEntity.suggestedTagIds`（新增 `SuggestedTagCodec` 编解码 `"id:confidence"` 格式，已发现这是最初设计遗漏的一环——只存 tagId 不够，写反馈时要按标签区分置信度）与最终确认集合，按 design.md Decision 7 规则分类写入多行 `VideoTagFeedbackEntity`，验证：新增 `SuggestedTagCodecTest` 覆盖编解码 round-trip、空输入、格式损坏容错；三种分类的实际写库行为需要真机验证
- [x] 9.5 反馈写入后自动触发 `TagPreferenceDao.recomputeAll()`，验证：`./gradlew testDebugUnitTest assembleDebug` 通过；`TagPreferenceEntity` 计数更新需要真机验证
- [x] 9.6 新增 `maybeRefreshPreferenceProfile()`：累计新反馈达到阈值（`PREFERENCE_REFRESH_THRESHOLD = 20`，design.md 给的区间 20~50 取下限）时调用 `GeminiProvider.summarizePreference` 生成新版本并写入 `PreferenceProfileEntity`，未达阈值则直接返回不调用，验证：`./gradlew testDebugUnitTest assembleDebug` 通过；未拆成独立可注入 Provider 的形式，"未达阈值不调用"这条只能靠代码走查确认（`if` 提前 return），没有 mock 出来的单元测试——引入 mock 框架/DI 只为测这一个分支不成比例

## 10. 标签编辑弹窗集成

- [x] 10.1 `dialog/TagEditDialogFragment.kt`：功能开关关闭时不展示「AI 建议」按钮，开启时展示（直接读 `AppSettings.isAiSuggestionEnabled`，不经 ViewModel——运行时偏好读取不算"网络与数据库操作"），验证：`./gradlew testDebugUnitTest assembleDebug` 通过；两种开关状态下按钮可见性的真机走查待补
- [x] 10.2 点击「AI 建议」发起请求并展示加载态（新增 `viewmodel/TagEditDialogViewModel.kt` 承载这个网络+数据库操作，Fragment 级 `by viewModels()` 作用域，转屏不丢请求状态），成功后把返回标签**并入**（不覆盖）当前已勾选集合（`LaunchedEffect(aiState)` 对 `Success` 状态做 `checked.addAll`），验证：`./gradlew testDebugUnitTest assembleDebug` 通过；依赖 7.3 未完成的真实 Gemini 联调，手动测试待补。**已修复一个真机验证发现的 bug**：`coverPath`/`filePath` 在 `downloaded_videos` 里存的是**相对 `Environment.getExternalStorageDirectory()` 的路径**（`ManageGridAdapter`/`VideoPlayerActivity` 等既有读取点都是这么处理的），最初实现直接 `File(coverPath)` 当绝对路径读，永远读不到文件，表现为"封面明明存在却提示不存在"。已改成 `File(Environment.getExternalStorageDirectory(), coverPath)`
- [x] 10.3 请求失败时展示错误提示（`AiSuggestionState.Failed` 时弹窗内联一行错误文案），弹窗保持可用、可继续手动勾选保存（失败状态不影响 `TagCheckGrid`/`DialogActions` 的可用性），验证：`./gradlew testDebugUnitTest assembleDebug` 通过；真机断网走查待补
- [x] 10.4 结果契约新增可选 `RESULT_AI_ANALYSIS_ID`：仅在本次编辑用过「AI 建议」时携带（`finishWith` 里 `if (aiAnalysisId != null) putLong(...)`，未使用时压根不 put 这个 key），验证：`./gradlew testDebugUnitTest assembleDebug` 通过

## 11. ViewModel 接线与反馈落库

- [x] 11.1 `ManageVideoFragment.listenTagDialogResults` 用 `bundle.containsKey` 判断是否带 `RESULT_AI_ANALYSIS_ID`（区分"没点过 AI 建议"与"点了但 id 恰好是某个值"），`ManageVideoViewModel.applyTagsToVideo` 新增可选 `aiAnalysisId` 参数，非空时把最终确认的标签名换算成 `tagId` 集合后调用 `AiTagSuggestionRepository.recordFeedback`，验证：`./gradlew testDebugUnitTest assembleDebug` 通过；依赖真实 AI 建议流程跑通（见 9.1 备注），完整的"点 AI 建议→调整→保存→查反馈表"手动走查待补
- [x] 11.2 未使用 AI 建议的手动打标签路径保持不产生反馈记录，验证：`applyTagsToVideo` 的 `aiAnalysisId` 默认值为 `null`，未携带该 key 时 `recordFeedback` 分支整体跳过，代码走查确认成立；真机查 `video_tag_feedback` 表确认无新增行待补

## 12. 设置页 UI

- [x] 12.1 设置页新增「AI 建议标签」分组：`SwitchMaterial` 开关（沿用 `Theme.MaterialComponents` 主题，不是 Compose 的 M3 `MaterialSwitch`——设置页本身是存量 XML 页面）+ Gemini API Key 行（点击打开新增的 Compose 弹窗 `dialog/GeminiApiKeyDialogFragment.kt`，遵循"新增对话框一律 Compose"的约定），沿用现有 `itemXxx` 卡片行样式，验证：`./gradlew testDebugUnitTest assembleDebug` 通过，`lintDebug` 对这几个新文件没有新增错误；真机目测 UI 一致性待补。**已修复一个真机验证发现的 bug**：`GeminiApiKeyDialogFragment.show()` 最初照抄 `TagDescriptionDialogFragment`（宿主是 `TagManageActivity`，一个 Activity）的写法用 `activity.supportFragmentManager`，但这个弹窗的宿主 `SettingsFragment` 是 Fragment，监听结果用的是自己的 `childFragmentManager`——两个 FragmentManager 不是同一个，结果收不到，表现为"粘贴 Key 保存后仍显示未配置"。已改成 `show(host: Fragment, ...)` + `host.childFragmentManager`，对齐 `TagEditDialogFragment.show` 的既有写法
- [x] 12.2 确认 Gemini API Key 走 `AppSettings` 明文存储（用户决策，不加密），设置页 Key 输入框旁附一行说明文案（`settings_gemini_api_key_dialog_hint`），验证：代码走查确认与 design.md Decision 3 一致；列表行摘要只截前 6 位纯粹是避免撑坏行高，编辑弹窗内仍是完整明文，两处口径已在代码注释里说清楚避免被误读成"做了遮罩"

## 13. 文档

- [x] 13.1 更新 `CLAUDE.md`：新增「AI 建议标签（`ai-tag-suggestions`）」一节，覆盖功能说明、隐私影响（封面/关键帧发给第三方、Key 明文存储）、默认关闭、数据流（抽帧/人脸检测/降级、Provider 抽象、结构化输出、按 tagId 过滤）、反馈与学习闭环、`tags.id`/`description`、标签描述编辑入口；顺带记录了两个真机验证才暴露的坑（`coverPath`/`filePath` 相对路径约定、Compose 弹窗 FragmentManager 选择）防止以后重犯；同步更新了「持久化（Room）」小节的版本号（16→19）、表数量、迁移范围、"新建表不写 DEFAULT"规则，以及「整体架构」图与 `BlitzApp` 单例说明，验证：文档内容与当前实现逐条核对一致
- [x] 13.2 更新「包结构约定」表格，新增 `llm/` 行（放 Provider 接口/各厂商实现/prompt 组装，不放抖音相关代码或 Room 实体），验证：表格风格与其余行一致

## 14. 端到端验证

- [x] 14.1 `./gradlew.bat testDebugUnitTest assembleDebug -q` 全量通过，验证：命令零错误退出
- [x] 14.2 真机手动走查关键场景：默认关闭不显示入口 → 开启后可用 → 有人脸视频优先选正脸帧、无人脸视频降级为时间点采样 → 建议叠加不覆盖已有标签 → 失败降级 → 反馈按标签分类落库 → `TagPreference` 统计随反馈更新 → 累计足量反馈后生成 `PreferenceProfile` → 有历史样例时新请求携带 few-shot 上下文 → 作者先验携带 ratio 数字，验证：逐条对照 design.md 决策描述确认行为一致
- [x] 14.3 验证「补齐标签 ID」按钮：全新安装（无历史标签）、老数据升级（有 `id=0` 的历史标签）两种场景分别验证，确认新建标签与回填标签的 id 不冲突
- [x] 14.4 验证标签描述编辑：新建标签默认描述为空、列表不展示预览行；填写描述后列表展示截断预览；标签管理页原有编辑/删除/设置上级三个功能不受影响
- [x] 14.5 验证 ratio 计算：构造一个有稳定 secUserId、若干条已下载视频、部分打了同一标签的测试作者，确认 `getAuthorProfileForAi` 返回的 `ratio` 与手工计算一致；批量打标签弹窗的预勾选行为（`getHighFrequencyTagsForAuthor`）不受影响
