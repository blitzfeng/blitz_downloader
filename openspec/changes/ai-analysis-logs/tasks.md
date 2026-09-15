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
