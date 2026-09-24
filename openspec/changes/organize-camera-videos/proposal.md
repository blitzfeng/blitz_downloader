## Why

`DCIM/Camera` 中混入了下载或其他来源的视频和图片，用户需要在设置页按既定文件名规则将视频移入 `Download/history`、图片移入 `Download/history_img`，减少手工整理。文件名仅作为整理依据，不代表对真实拍摄来源的鉴定。

## What Changes

- 设置页新增手动入口「整理相机目录」，先扫描、预览，再由用户确认移动。
- 默认仅处理主共享存储 `DCIM/Camera` 的直接子文件：识别为视频，且文件名不以 `img` 或 `vid` 开头（忽略大小写）。图片另按 `img`、`mvimg`、`pano`、`retouch` 前缀规则整理；其他文件和子目录不处理。
- 视频移入同一存储的 `Download/history`；图片（`image/*`）保留以 `img`、`mvimg`、`pano` 或 `retouch` 开头的文件（忽略大小写），其余移入 `Download/history_img`。图片以 `vid` 开头也属于移动目标。
- 两个目标目录分别按需创建，同名自动追加序号，不覆盖已有文件；预览逐项显示目标目录。
- 处理存储授权、候选变更、空间不足、部分失败和中断；保留未成功移动的源文件，展示进度及逐项结果，并同步系统媒体索引。
- 复用现有权限入口，兼顾应用支持的 Android 版本；不引入自动定时整理、拍摄来源识别或下载记录导入。

## Capabilities

### New Capabilities

- `camera-video-organization`: 设置页的视频与图片扫描、分类型文件名前缀过滤、预览确认、分目录安全移动及结果反馈。

### Modified Capabilities

无。

## Impact

- 设置页 `SettingsFragment` 及其布局接入入口；新增交互使用 Compose Material 3 与既有 `ComposeDialogFragment`。
- 新增文件整理执行器及纯规则模型，复用存储权限跳转；文件操作放在 IO 线程。
- Android 11+ 使用已有所有文件访问权限；Android 6–9 使用运行时存储权限；Android 10 使用目录授权访问。
- 涉及公共存储文件和系统媒体索引，不改变 Room schema、下载目录布局、下载流程和现有相册隐藏设置。
