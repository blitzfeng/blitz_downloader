# 验证记录

日期：2026-09-27。原有变更任务 18/18 已完成（包含新批次回归修复及全量编辑滚动优化）。

2026-10-03 补充：新增“完成后的标签冲突校验”已实现并验收，全部 23/23 项任务完成，第 8 节的验证结果见本文末尾。下方 2026-09-27 的构建、测试与截图记录仅覆盖原有功能。

## 构建与规范

- `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`：通过。
- `openspec validate improve-batch-ai-tag-review --strict`：通过。
- `git diff --check`：通过。
- Room v29 schema 已导出至 `app/schemas/com.blitz.downloader.data.db.AppDatabase/29.json`。

## JVM 测试

全量 `:app:testDebugUnitTest` 共 186 项通过（包括 24 项 BatchReviewLogicTest）。

首次使用系统默认临时目录时，相机整理模块 16 项测试因 macOS 临时路径中的符号链接被拒绝而失败。未改动该模块；使用真实路径作为测试 JVM 的临时目录后，全量通过。复现方式：创建 `/private/tmp/batch-review-jvm`，通过临时 Gradle init 文件配置：

```groovy
allprojects {
    tasks.withType(Test).configureEach {
        systemProperty 'java.io.tmpdir', '/private/tmp/batch-review-jvm'
    }
}
```

执行 `./gradlew -I <init-file> :app:testDebugUnitTest`。

## Android 设备测试

在独立 Android 15 / API 35 arm64 模拟器上安装 debug APK 与 androidTest APK，使用 AndroidJUnitRunner 运行以下测试类，共 8 项通过：

- `com.blitz.downloader.data.BatchAnalysisMigrationTest`：真实 SQLite v28→v29 迁移及 Room schema 校验，验证下载记录修改次数、标签和 pending 保留，新会话和失败记录可读写。
- `com.blitz.downloader.data.BatchTagReviewIntegrationTest`：混合媒体资格筛选；反选、跳过的实际 Compose 点击；确认连点防重；数据库触发器故障注入与回滚重试；失败人工编辑及不重复计数；成功项在 pending 清理后修正反馈且不产生重复反馈；页面重建恢复；筛选隐藏未处理组不提前完成；撤销后隐藏全部；全部失败汇总；后台迟到结果重新打开分组及撤销恢复；启动前复查资格；服务逐条失败持久化；中断恢复；全部自动排除。

设备测试运行命令（设备需为可清空应用测试数据的专用模拟器）：

```sh
adb shell am instrument -w -r \
  -e class com.blitz.downloader.data.BatchAnalysisMigrationTest,com.blitz.downloader.data.BatchTagReviewIntegrationTest \
  com.blitz.downloader.test/androidx.test.runner.AndroidJUnitRunner
```

页面截图已人工检查：未处理组的三按钮、已选数量、完成后的全量视频网格、成功/失败标识、当前标签、修改入口和 Snackbar 均正常。混合结果采用确定性数据库样例；服务测试使用缺失本地封面的失败路径，没有调用真实 Gemini 或产生模型费用。

## 范围与兼容性

- 仅升级后的新分析保证完整成功/失败清单。旧 pending 作为 legacy 会话保留审核能力，不推测此前未持久化的失败记录。
- 保留用户原有 `.idea/deploymentTargetSelector.xml` 与 `gradlew` 改动。
- 未归档本变更；实现与验证文档已就绪，可后续归档。

## 新下载批次回归修复

原因：读取最新分析会话时没有核对 `sourceBatchIds`，新批次候选已加载，但旧会话的已完成结果仍显示。

修复：仅恢复最新来源批次匹配的会话；观察最近下载批次的 Room Flow，新批次写入时重置旧展示、排除和筛选状态。创建分析会话使用同一次候选加载的批次标识快照，避免并发下载使旧成员被误标成新批次。

补充验证：
- 25 项 BatchReviewLogicTest 通过，覆盖同批恢复、新批拒绝、不能匹配上一批 ID、空来源等边界。
- Debug 与 androidTest 构建通过。
- 独立 Android 15 模拟器上 9 项设备测试通过；新增“已有完成会话 → 新下载 4 条 → 页面即时清除旧结果 → 重建仍不恢复旧结果”，同时回归同批次恢复、故障回滚和人工编辑。
- 未在用户手机上运行会清空测试数据的 instrumentation 测试。

## 全量编辑滚动与结果展示优化

- 已处理分组保留状态、渲染和撤销实现，但不加入列表；审核中显示“未处理 > 失败”，完成后显示“全部 > 失败”。
- 保存标签时不再因 busyAction 临时移除“全部”区域，保持列表项及 key 稳定。
- Debug 与 androidTest 构建通过，独立 Android 15 模拟器上 10 项设备测试通过。新增测试通过占用数据库事务延迟保存，确认保存前、保存中、保存后的滚动位置一致，且已处理分组不展示。


## 2026-10-03：完成后标签冲突校验

实现：预览后增加校验入口，复用完成状态与保存防重；事务读取完整标签规则和本次去重成员的实际标签；冲突项在全部/失败区域同步使用整体红色背景，保存后再次校验通过恢复。校验失败保留冲突，新会话/批次清除旧结果。

已执行：

- `./gradlew :app:testDebugUnitTest --tests com.blitz.downloader.model.BatchReviewLogicTest :app:assembleDebug :app:assembleDebugAndroidTest`：通过，28 项逻辑测试无失败，debug APK 与设备测试 APK 构建成功。
- 新增逻辑测试覆盖互斥直属子项、多层关系、父子共存、仅选子标签、非互斥多选、禁用 AI 的标签、空标签集合、修改规则与标签后的重新计算，以及按钮加载/分析/保存/校验状态。
- `openspec validate improve-batch-ai-tag-review --strict` 与 `git diff --check`：通过。

新增设备测试覆盖：审核完成前禁用、筛选不绕过完成判定、成功及失败项同步标红、修改后复校恢复、重读互斥规则、不增加编辑次数/反馈、排队期间新批次变化、校验防重及阻止同时保存、真实数据库读取异常后保留结果并重试。已在独立 Android 15 / API 35 arm64 模拟器执行完整 `BatchTagReviewIntegrationTest`，12 项全部通过，其中 3 项为新增校验集成测试。

设备命令：

```sh
adb -s emulator-5580 shell am instrument -w -r \
  -e class com.blitz.downloader.data.BatchTagReviewIntegrationTest \
  com.blitz.downloader.test/androidx.test.runner.AndroidJUnitRunner
```

已检查 `batch-review-validation-conflicts.png` 与 `batch-review-validation-corrected.png`：校验紧邻预览；成功与失败两个视频均为整体红色背景，无冲突视频正常；修改成功视频后复校，其背景恢复，仍冲突的失败视频保持红色，结果提示数量从 2 变为 1。按钮、作者与标签内容可读，布局正常。模拟器与样例数据库独立创建，测试未发起真实 AI 请求。


## 2026-10-04：移除 toolbar 校验入口

按用户要求移除顶部 toolbar 的校验按钮，仅保留页面内容区“预览”后的校验入口。同步需求、设计、任务与项目说明；校验逻辑不变。`./gradlew :app:compileDebugKotlin`、OpenSpec 严格校验及 `git diff --check` 通过。本次未重跑设备测试，前述截图属于调整前的验收记录。
