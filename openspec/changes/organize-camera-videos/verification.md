# 实现与验证记录

日期：2026-09-24。

## 当前行为

- 设置页入口为「整理相机目录」，仅扫描主共享存储 `DCIM/Camera` 的直接子文件。
- 视频：忽略大小写，保留 `img`、`vid` 开头的文件，其余进入 `Download/history`。
- 图片：忽略大小写，保留 `img`、`mvimg`、`pano`、`retouch` 开头的文件，其余进入 `Download/history_img`。例如 `VID_001.jpg` 会移动，`IMG_001.jpg` 保留。
- 预览逐项显示目标目录，确认后执行；同名追加序号，不覆盖，复制回读校验成功后才删除源。
- 删除源失败时显示失败及保留副本的路径；未完成临时文件记录显示在结果中，由用户通过文件管理器核对。需要撤销时手动将目标文件移回，不自动执行反向移动。
- Android 11+ 使用所有文件访问权限；Android 10 选择主存储的 Camera 与 Download 目录；Android 6–9 使用存储运行时权限。

## 已执行验证

命令：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests "com.blitz.downloader.data.Camera*Test"
openspec validate organize-camera-videos --strict
```

- Debug 构建成功，产物：`app/build/outputs/apk/debug/app-debug.apk`。
- `CameraVideoMoverTest`：12 项通过，零失败。覆盖双类型前缀、图片与视频分目录、各自同名冲突、未知类型、直接子文件扫描、复制损坏、写入失败、源变化、删除失败、临时文件清理失败、权限撤回。
- `CameraOrganizationRunnerTest`：5 项通过，零失败。覆盖重复确认、过期批次、取消不创建目录、预览后新增与变化、权限撤回停止、索引失败独立提示。
- OpenSpec 严格校验通过。
- 图片保留前缀扩展为 `img`、`mvimg`、`pano`、`retouch` 后，重新执行上述构建和测试通过；覆盖混合大小写、无下划线前缀、扫描排除、名称中间出现前缀仍可移动，以及新增前缀不影响视频规则。

## 尚未完成的设备验收

本轮未在 Android 设备或模拟器上执行权限、SAF、相册刷新及界面重建验收，不能将 JVM 测试视为这些项目已通过。需要按任务清单验证 Android 6–9、10、11+，包括实际媒体文件、目标播放或查看、权限拒绝及撤回、屏幕旋转、相册索引、磁盘不足与真实进程中断。相关任务保留未勾选。
