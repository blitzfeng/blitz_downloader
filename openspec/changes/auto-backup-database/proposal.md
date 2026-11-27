# Proposal

## Why

当前数据库只支持手动备份，用户容易长期没有可恢复的数据副本；历史备份也会持续占用存储空间。需要在用户打开应用时检查备份周期，自动创建备份并清理过期副本。

## What Changes

- 应用进入前台时异步检查最近一份有效备份；没有备份或距离完成时间严格超过 5 天时，自动备份数据库。
- 自动备份成功后用 Toast 提示；失败不更新成功时间，并提示失败原因。
- 每次前台检查清理严格超过 30 天的应用数据库备份，包含手动、自动及可验证的旧版备份；始终保留最近一份有效备份。
- 统一手动备份、自动备份、恢复及清理的互斥管理，使用完成标记和一致性校验识别成功备份，兼容原恢复入口。
- 不增加后台定时唤醒、云端同步或新的配置页面。

## Capabilities

### New Capabilities

- `database-backup`: 数据库备份的前台周期检查、有效副本发布、过期清理及手动恢复兼容行为。

### Modified Capabilities

无。`home-shell-navigation` 中的设置页备份恢复入口与 SAF 兜底保持兼容，本变更新增备份生命周期能力。

## Impact

- `DatabaseBackupManager`：备份发布、有效性验证、目录识别、恢复互斥、清理与兼容处理。
- 新增应用级备份协调器；由前台 Activity 生命周期触发，任务由应用级协程管理。
- `BlitzApp`、`MainActivity` 或统一 Activity 生命周期回调、`SettingsViewModel`、提示字符串及测试。
- 使用现有公共目录 `Download/bDouyin/backup/`；不备份视频、封面、Cookie 或 API Key。
- 不新增 Room 表、不要求提升数据库版本；偏好学习等并行开发的数据库结构通过完整快照覆盖。
- 详细方案见 [design.md](design.md)，实施步骤见 [tasks.md](tasks.md)。本次仅生成方案文档。
