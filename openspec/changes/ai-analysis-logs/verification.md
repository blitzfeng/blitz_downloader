# 验证记录

日期：2026-10-05。

## 已实现

- Gemini 通过原始响应解析保留外层诊断字段；内容拦截、异常结束、HTTP 错误及结构化结果解析失败携带响应诊断信息返回 Repository。
- 成功/失败日志均可查看、复制接口实际响应及已有 token 统计；失败说明包含中文提示与原始原因码。模型版本和响应标识保留在响应 JSON 中。
- 单视频打标签弹窗新增日志入口，通过父 ViewModel 按 `awemeId` 订阅，使用独立日志 ID 关联每次请求和返回；复制全部与清空只作用于当前视频。
- 日志公共内容移至 `ui/AiAnalysisLogSheet.kt`。单视频使用子 `ComposeDialogFragment`，不重建父弹窗的标签勾选状态，不发起或取消 AI 请求。
- 封面不可读导致未发送请求时，记录明确的本地失败原因，不伪造返回内容。

## 自动验证

- `openspec validate ai-analysis-logs --strict`：通过。
- `git diff --check`：通过。
- `./gradlew testDebugUnitTest --tests '*AiAnalysisLog*' --tests '*GeminiResponseParserTest' assembleDebug`：通过。
- 全量 JVM 测试：207 项，0 失败、0 跳过；Debug APK 构建通过。
- 新增测试覆盖用户提供的 `PROHIBITED_CONTENT` 响应、token 明细/模型版本/响应标识的导出、异常结束与部分文本、未知原因码及拦截优先级、空结果、HTTP 429、无效外层 JSON、结构化输出解析失败、网络超时、正常文本返回、图片/凭证脱敏，以及同文案不同视频的过滤、重试更新和范围清空。

全量测试首轮的 23 项失败位于已有相机目录/文件整理测试，原因是 macOS 默认临时目录经过符号链接，而该模块拒绝非规范路径。验证时通过仓库外的 Gradle init script 将 Test JVM 的 `java.io.tmpdir` 指向其 `canonicalPath` 后，全量通过；未修改相机整理代码或项目构建配置。使用的 init script 内容：

```groovy
allprojects {
    tasks.withType(Test).configureEach {
        systemProperty 'java.io.tmpdir', new File(System.getProperty('java.io.tmpdir')).canonicalPath
    }
}
```

最终命令：`./gradlew -I /tmp/ai-log-test-temp.gradle testDebugUnitTest assembleDebug --console=plain`。

## 尚待设备验证

当前 `adb devices` 显示连接设备为 `unauthorized`，且没有可用 AVD，因此未执行真机/模拟器 UI 测试。任务 6.5、7.5 中的自动测试部分已完成，现场交互部分仍待验证，保留未勾选状态：

- AI 功能开关、分析前/中/后及失败时的日志入口、空状态与实时更新。
- 失败详情和剪贴板中的原因码及诊断信息是否完整可见。
- 关闭按钮、系统返回键和旋转屏幕后，标签勾选与请求生命周期是否保持正常。
- 两个视频交替打开日志、单视频复制全部及清空的范围隔离。

尚未归档变更。

## 历史参考管理补充验证（2026-10-05）

- 页面入口：设置 → AI 建议 → 管理历史参考案例；单视频/批量日志卡片 → 管理历史参考案例（默认该作者）。搜索文案，选择对应视频，点击“移除参考”；在“已移除”筛选中可恢复。
- 数据按视频聚合，显示完整文案、采纳/拒绝/补充标签和去重后的参考图，点击图片打开 ComposeDialogFragment 预览。无图片及图片不可读均不阻碍管理操作。
- 数据库已升级 v30 并导出 Room schema。仅新增参考排除表，视频、标签及反馈保留，规则随数据库备份；规则修改事务同时清除旧偏好摘要，管理动作不触发额外模型调用。
- `./gradlew -I /tmp/ai-log-test-temp.gradle testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin` 通过：211 项 JVM 测试，0 失败；Debug APK 构建及设备测试代码编译通过。新增 4 项 JVM 测试覆盖按视频聚合、同名作者 ID 隔离、状态筛选、文案搜索和图片缺失。
- 使用 Python SQLite 在临时数据库中运行仓库导出的 v29 schema、实际 `MIGRATION_29_30` SQL 及 DAO 查询，验证：迁移保留旧反馈、迁移后的表结构匹配 v30 schema、六条参考查询在 LIMIT 前排除、同视频新增反馈仍排除、重开数据库规则保留、恢复资格后重新入选，以及标签/反馈保持完整。脚本 `/tmp/verify_ai_reference_sql.py` 执行通过。
- 新增 `AiReferenceManagementTest` 设备测试，覆盖 Room DAO 事务清除偏好摘要、六类参考过滤、记录保留、移除后再次反馈、恢复及 v29→v30 迁移/重开。现有 v28 迁移测试补齐至当前版本的迁移链。
- 当前 `adb devices` 无已连接设备，设备测试仅编译，未执行；尚未真机验证页面布局、参考图预览、筛选多选及导航返回。未自动移除用户任何实际案例，也未对该条失败请求进行重试，因此不能确认原失败是否由历史文案导致。
