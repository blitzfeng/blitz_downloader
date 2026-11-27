# 失联媒体恢复第一版验收与交接

本版在「设置 → 恢复失联记录」提供扫描、查询、人工审核和新增记录的入口。扫描可以单独运行。查询由用户发起，从本地文件名或证据图提取作品 ID，向抖音作品详情接口查询作者、描述和发布时间。本地匹配与预览使用手机上的媒体。作品 ID 缺失时，可由用户补充 ID 或分享链接。原来的标签和观看历史无法从作品详情取回。

## 验收环境

Android 14（API 34）模拟器，固定序列号 `emulator-5562`，使用独立包 `com.blitz.downloader.recoveryvalidation`。媒体、登录态、数据库记录和详情响应均为合成数据。设备验证使用实际的 Room、SQLite、文件描述符、前台服务和 Compose 页面。SAF 测试通过 Android DocumentsContract 连接进程内的合成 DocumentsProvider。

原电脑留存的验收包为 `app/build/outputs/recovery/BlitzDownloader-recovery-v32-debug.apk`，大小 19,978,563 字节，包名 `com.blitz.downloader`，Room 版本 32。已从 APK 读取 Manifest 并验证 V2 签名。下表的文件摘要对应这份留存副本。此轮真机安装及真实账户查询次数为零。

| 校验项 | SHA-256 |
| --- | --- |
| APK 文件 | `84fc6cb3017cbce78ad41b2e70b142e0e7fed646732dde4b41698c2e1ff3f86c` |
| 签名证书 | `84e3c4e7fe4a940f2ef74efe2186e400f5492267ef4e86ff84c3ed8532539436` |

2026-11-27 验收通过 272 项 JVM 单元测试和 22 项 Android 模拟器测试，两组均为零失败、零跳过。OpenSpec 严格校验与 Debug 构建通过。当前只安装了 API 34 系统镜像。最后针对 API 24–28 调整了已授权 SAF 目录的优先级，重新构建主包并复核 JVM 测试；旧系统分支仍待相应系统镜像验收。

### 场景记录

| 场景 | 核对内容 |
| --- | --- |
| 数据库升级 | 合成 v18 与 v31 升到 v32，逐表比对样例行和外键。另在迁移后的 v31 样例库上执行扫描、模拟中断、手动继续查询、确认、备份和提交。重新打开后核对原行、标签、反馈、媒体字节及导出。缺少迁移时能重新读取原始数据库。 |
| 读取权限 | File 和 SAF 均可读取视频、获取尺寸、按原始字节导出。通过模拟器回环 HTTP 实际下载 SAF 媒体生成的 ZIP，解包后核对文件名和全部字节。撤销授权后保留下载记录和媒体。 |
| 查询 | 模拟成功、登录或访问受限、限流、网络错误、非法详情、作品 ID 不一致及响应超限。缓存、重试及账号变化单独覆盖。 |
| 匹配及复核 | 多副本、类型或时长不符、相似封面、图集缺号进入复核。批量确认只包含本页可确认的作品。图集可以更换主图，选择保留图片与实况视频，导出顺序与选择一致。无合理召回时没有打开视频。取消抽帧后 SAF 文件描述符已关闭。 |
| 写回 | 同一 ID 被普通下载抢先插入时跳过。预览后增加同作品副本、移走主文件或用同大小内容替换，原选择均会过期。事务中第二项失败会回滚第一项。51 项拆成 50+1，第二批备份失败后保留已提交的 50 项，剩余 1 项可重新预览和备份。 |
| 备份 | 写前独立快照可回读，包含已有业务数据和七张恢复表。空间不足、不可写、摘要损坏时阻断提交。已发布备份与中断批次可在重开时重新核验。 |
| 页面 | 打开页面只显示概况。通过页面扫描、查询、查看本地视频、确认关联、取消预览、再次提交并打开历史。停止服务后手动续跑，按系统 Home 键后任务继续，返回同一页面显示持久化结果。测试检查了媒体字节、标签、观看与偏好版本。 |
| 管理 | 合成数据验证媒体尺寸、导出内容、图集成员顺序及后续手动标签修改。恢复时的原下载时间、观看、导出与标签编辑历史保持未知。 |

## 性能样本

规模用例创建 2,500 个视频文件项和 3,300 张证据文件项，去重后有 1,200 个作品 ID。大多数文件使用微型合成内容，另含可解码视频；详情由测试直接提供。因此耗时只描述本机模拟器的索引、匹配与数据库写回。完整视频的读取时长及真实账号的接口耗时需在实际使用时测量。

最后一次完整运行中，扫描 5,800 个文件耗时 15.618 秒，匹配 1,200 个作品耗时 6.965 秒，单条已确认视频的备份和提交耗时 4.893 秒。备份文件为 6,684,672 字节，按 100 毫秒间隔采样的进程 PSS 峰值为 135,585 KiB。主机当时也在运行 Gradle 构建。

## 尚待验收

- Android API 24–28 的系统读取权限、API 29 的目录选择器，以及手机厂商提供的 SAF 授权和撤销流程。
- 已登录真实账号通过普通 HTTPS 请求取得详情的可用性。接口返回 401/403 时会暂停。只有文件名而且也没有证据图或分享链接的作品，可能长期停留在待识别列表。
- 用户点击系统通知中的暂停、系统强制终止整个进程，以及在真实手机设置中撤销授权后的完整使用体验。当前测试覆盖停止和重启服务、实际 Home 键、Activity 重建、数据库重开和中断状态重建。
- 手机和另一台电脑经实际 Wi-Fi 传输，以及不同厂商视频解码器对用户原始媒体的支持。自动化测试中的源媒体和接收端都在模拟器上。

## 换电脑继续开发

本次交接分支为 `codex/media-recovery-handoff`。首次检出可执行 `git clone --branch codex/media-recovery-handoff https://github.com/blitzfeng/blitz_downloader.git`。已有检出时，在仓库根目录运行以下命令。Windows 示例在 PowerShell 7 上验证。

```shell
git fetch origin
git switch codex/media-recovery-handoff
git pull --ff-only
```

换机前额外导出了一份仅包含 Git 暂存文件的独立副本，使用本机 SDK 和已缓存的依赖重新生成主包 Debug APK，272 项 JVM 测试全部通过。构建全过程使用合成测试数据。

| 变更 | 该从哪里接手 |
| --- | --- |
| 失联媒体恢复 | Room v32，首版已实现。OpenSpec 已核对 39/40 项，新机器可优先验收任务 2.1 中的 API 24–28 和 API 29 存储权限。[任务清单](../openspec/changes/recover-unlinked-media/tasks.md) |
| 自动备份 | 快照、手动备份及协调器均已接入。失联恢复保护默认暂停五天自动备份与三十天清理。自动备份的独立任务清单仍待逐项验收。[使用说明](database-auto-backup.md) · [任务清单](../openspec/changes/auto-backup-database/tasks.md) |
| 偏好学习 | Room v31 引入的案例、任务与规则表保留在 v32。接着核对开放的验收项。真实个人样本对标签质量、耗时及费用的影响尚待衡量。[使用说明](ai-preference-learning-v1.md) · [任务清单](../openspec/changes/learn-local-ai-preferences/tasks.md) |

1. 配置 JDK 17 和 Android SDK Platform 36，安装 SDK Build-Tools 与 Platform-Tools。让 Android Studio 为此检出生成 `local.properties`，或在本机配置正确的 SDK 路径。使用仓库的 Gradle Wrapper。首次构建需要下载缺少的依赖。
2. 只构建主包及运行 JVM 测试时，在 Windows 执行 `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`；macOS/Linux 使用 `./gradlew :app:testDebugUnitTest :app:assembleDebug`。主包输出到 `app/build/outputs/apk/debug/app-debug.apk`。两种包共用此输出路径，构建验证包后若需主包，须再次执行这条无验证参数的构建命令。
3. Windows 模拟器回归使用仓库中的 PowerShell 脚本。用 Android Studio 创建所需 API 的 AVD，在已配置 SDK 命令路径的终端执行 `emulator -list-avds`，选定测试 AVD 后运行 `emulator -avd <AVD名称> -port 5562`。用 `adb -s emulator-5562 shell getprop ro.build.version.sdk` 核对系统版本。每次只在该端口运行一台测试模拟器。
4. 构建独立验证包后立即运行以下脚本。脚本核对模拟器、两个 APK 的包名、签名以及 instrumentation 的目标，再覆盖安装到固定模拟器。个人手机、登录 Cookie 和 AI Key 都不是复测依赖。

```powershell
.\gradlew.bat -PrecoveryValidation=true :app:assembleDebug :app:assembleDebugAndroidTest
.\tools\recovery\run-emulator-tests.ps1 -Class 'com.blitz.downloader.data.RecoveryDatabaseTest,com.blitz.downloader.data.RecoveryDocumentsTest,com.blitz.downloader.data.RecoveryWorkflowTest,com.blitz.downloader.activity.MediaRecoveryScreenTest,com.blitz.downloader.activity.MediaRecoveryInteractionTest'
```

上面的完整回归在 API 34 通过，测试夹具使用了较新平台的 DocumentsProvider 测试 API。补验旧版本时，分别在 API 24–28 和 API 29 的独立模拟器核对权限授予、拒绝、重新授权、只读扫描及备份写回，按实际运行系统记录结果。

Git 同步源代码、Room schema、合成测试素材及规范。原电脑的验收 APK、`recovery-local/` 内的个人备份、媒体清单与事故盘点原稿需单独传递。换电脑继续开发或模拟器验证可直接使用仓库。若要继续同一台手机的任务，扫描、查询和已提交批次的进度保存在手机数据库中。[中断续跑说明](media-recovery.md#中断后从哪里继续)
