## Why

在 AI 批量标签分析过程中，用户只能看到进度条和分析后的标签结果，无法直观了解每次请求发送了哪些数据（如文案、抽取关键帧说明、候选词表等）以及大模型接口返回的具体响应内容（如视觉维度判断、各标签置信度、token 消耗等）。

目前接口调用的请求体和响应体仅输出在系统的 Android Logcat 中（需要连接电脑通过 adb 查看），且包含图片 Base64 编码等冗余信息。普通用户在遇到分析结果不符合预期或接口异常时，无法方便、直观地排查和查看。因此需要在 AI 分析页提供前台日志查看能力，以普通人易读、结构明晰、排版舒适的方式展示接口请求与返回数据。

## What Changes

- **AI 分析页 Toolbar 新增日志按钮**：在 `BatchTagReviewActivity` 的 TopAppBar 右侧添加日志入口图标按钮。
- **请求与响应数据内存日志收集**：在 LLM 调用管道（或批量分析服务）中捕获每次分析的请求与响应数据，剔除/脱敏冗长的图片 Base64 原始数据，转换为结构清晰的日志条目。
- **友好易读的日志查看界面**：点击日志按钮后弹出日志查看面板（BottomSheet 或 Dialog），按视频/任务条目清晰列出请求内容（视频描述、提示词结构、关键帧分析、词表）与返回内容（视觉特征观察、候选标签与置信度、消耗统计等）。
- **格式明晰与长数据排版优化**：对长文本、长 JSON 及长列表数据进行格式化，采用空格分隔与层次缩进，避免文字拥挤，方便普通用户阅读。

## Capabilities

### New Capabilities
<!-- 无新增顶级独立 capability -->

### Modified Capabilities
- `batch-ai-tag-review`: 在批量标签整理页面 Toolbar 增加日志查看按钮，收集并以适合普通人阅读的方式呈现大模型请求与返回数据。

## Impact

- **UI 模块**：`BatchTagReviewActivity.kt` 的 TopAppBar 增加日志 Action 按钮，新增日志查看组件 `AiAnalysisLogSheet`。
- **ViewModel / 状态流**：`BatchTagReviewViewModel.kt` 维护当前分析任务的日志列表并在 UI State 中暴露。
- **数据与服务层**：`AiBatchAnalysisService.kt` / `AiTagSuggestionRepository.kt` 或 `LlmProvider` 传递每次请求/响应的关键日志数据至内存日志容器。
- **依赖与存储**：纯内存日志管理（单次生命周期内有效，无需侵入数据库表），不增加外部第三方依赖。
