## 1. 数据模型与内存日志管理

- [x] 1.1 创建 `AiAnalysisLogEntry` 数据类与 `AiAnalysisLogStatus` 枚举，包含视频标识、视频文案、脱敏请求体摘要、接口原始响应、结构化标签建议、视觉维度解析、耗时与 Token 统计，并在单元测试中验证数据模型完整性
- [x] 1.2 创建单例或由生命周期管理的 `AiAnalysisLogStore` 内存日志仓库，提供 `addEntry`、`updateEntry`、`clear` 与 `StateFlow<List<AiAnalysisLogEntry>>` 订阅流，设置最多 100 条环形限制，并编写单元测试验证增删改与容量上限

## 2. 接口调用日志捕获与脱敏

- [x] 2.1 在 `AiTagSuggestionRepository` / `AiBatchAnalysisService` 中，在发起建议请求前后捕获实际请求内容（Prompt、选帧说明、词表）与响应数据（脱敏图片 Base64 替换为 `[图片内容，约 NKB]`），写入 `AiAnalysisLogStore`
- [x] 2.2 在请求失败或发生网络/解析异常时，将异常信息与错误码记录到对应条目的 `AiAnalysisLogEntry` 中，并通过单测验证异常链路的日志记录准确性

## 3. 长数据格式化与空格分隔排版工具

- [x] 3.1 实现日志排版工具类 `AiAnalysisLogFormatter`，提供对标签词表、候选置信度列表、视觉维度特征的长数据空格与换行排版，以及对 JSON 的 Pretty Print 缩进处理，输出适合普通用户阅读的明晰文本
- [x] 3.2 编写 `AiAnalysisLogFormatterTest` 单元测试，验证长数据空格分隔、Base64 脱敏及纯文本格式化输出符合预期

## 4. UI 界面与交互集成

- [x] 4.1 在 `BatchTagReviewViewModel` 中引入 `AiAnalysisLogStore` 的日志状态流，并在 `BatchTagReviewUiState` 中暴露日志列表与日志面板显示状态
- [x] 4.2 在 `BatchTagReviewActivity` 的 TopAppBar 的 `actions` 中添加日志图标按钮（`ic_log` 或文档图标），点击触发打开日志面板
- [x] 4.3 使用 Jetpack Compose 实现 `AiAnalysisLogSheet`（ModalBottomSheet），卡片化展示每条视频的分析记录，支持展开查看请求与返回详情、长数据空格排版、单条复制与全部复制

## 5. 验证与回归

- [x] 5.1 运行 `./gradlew testDebugUnitTest` 确保全部单测通过
- [x] 5.2 走查 `BatchTagReviewActivity` 的 TopAppBar 布局与日志查看体验，确保在分析前、分析中、分析后均能正常展示格式明晰的请求与返回数据

## 6. 补充：显示接口返回的失败原因

- [x] 6.1 扩展 Gemini 响应解析，提取 `promptFeedback.blockReason`、补充说明及导致生成失败的 `finishReason`，支持未知原因码与无具体原因时的兜底提示
- [x] 6.2 将失败原因、失败响应 JSON 和已有诊断元数据传递到同一条分析日志，保留 token 消耗、模型版本、响应标识与耗时；兼容 HTTP、网络和解析异常
- [x] 6.3 更新日志卡片摘要、返回详情、单条复制及全部复制，显示中文说明与原始原因码，并沿用脱敏规则
- [x] 6.4 使用用户提供的 `PROHIBITED_CONTENT` 无候选结果响应编写回归测试，同时覆盖异常 `finishReason`、未知原因码、空结果兜底、HTTP/网络/解析错误和正常成功响应
- [ ] 6.5 运行相关单元测试并走查失败日志查看与复制，确认失败原因及已有诊断信息完整可见

## 7. 补充：单个视频 AI 建议日志入口

- [x] 7.1 在 `TagEditDialogViewModel` 中提供按当前 `awemeId` 过滤的日志状态流，复用共享日志收集链路，确保多次调用独立记录、请求返回正确关联及状态实时更新
- [x] 7.2 在 `TagEditDialogFragment` 的 AI 建议区域添加日志入口，跟随功能开关显示，覆盖分析前、进行中、成功、失败和无日志状态
- [x] 7.3 抽取公共日志展示内容并使用符合现有 Compose 弹窗规范的容器，支持查看脱敏请求、返回及具体失败原因；关闭后保留打标签状态
- [x] 7.4 将单条复制、复制全部及可选清空操作限定到当前视频，避免泄漏或误删其他视频日志
- [ ] 7.5 验证不同视频（含相同文案）日志隔离、同视频重试、实时更新、功能开关、空状态、失败原因与复制内容，并走查关闭/返回行为不影响选中标签及进行中的请求

验证说明：新增实现及 JVM 测试已完成，全量 207 项测试与 Debug 构建通过。6.5、7.5 的设备交互走查因 ADB 未授权仍待完成，详见 `verification.md`。

## 8. 历史参考案例管理

- [x] 8.1 新增视频级参考排除表、DAO 与 v29→v30 迁移，保留已有数据并支持移除/恢复
- [x] 8.2 所有历史参考查询先过滤排除视频再采样，规则变化时使偏好摘要失效并防止并发旧摘要回写
- [x] 8.3 实现按视频聚合的管理数据流和 Compose 页面，支持作者范围、文案搜索、状态筛选、多选移除/恢复、参考图预览及空/错误状态
- [x] 8.4 在设置页和单视频/批量日志中加入入口，日志默认定位当前作者
- [x] 8.5 添加过滤、移除/恢复与迁移验证，运行单元测试和 Debug 构建，更新使用文档并记录设备验证限制
