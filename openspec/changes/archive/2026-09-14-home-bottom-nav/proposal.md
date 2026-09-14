## Why

当前「下载 / 管理 / 设置」是三个彼此独立的 Activity，管理页与设置页只能从首页 Toolbar 的溢出菜单进入——入口藏得深、来回切换要走 startActivity / finish，每次返回都重建页面（管理页的筛选条件、滚动位置、抽屉状态全部丢失）。把首页改成「底部导航栏 + 三个页面」后，三个高频区域平级可见、一键互切，且切换时各自状态原地保留。

## What Changes

- `MainActivity` 改为应用外壳：`FragmentContainerView` + `BottomNavigationView`（Material 3），三个 tab —— **下载**（当前首页内容）、**管理**（原管理页）、**设置**（原设置页）。
- 三个页面 Fragment 之间用 `add` + `show/hide` 切换（不是 `replace`），页面实例常驻，切走再切回保留滚动位置、筛选条件与列表数据；`ManageViewModel` 持有的局域网导出服务也不会因切 tab 被掐断。
- 新增 `DownloadFragment`：承载现有的 `TabLayout` + `ViewPager2`（「单视频下载」/「列表下载」子 Tab 保持不变），只是从 Activity 搬进 Fragment。
- **BREAKING** `ManageActivity` 改造为 `ManageFragment` 并删除 Activity：Toolbar、菜单、七层筛选对话框、作者抽屉（`DrawerLayout` 留在 Fragment 内部）、多选模式、ZIP / 局域网导出对话框整体迁入。
- **BREAKING** `SettingsActivity` 改造为 `SettingsFragment` 并删除 Activity：备份 / 恢复（含 SAF 兜底）、标签筛选模式设置整体迁入。
- 每个 Fragment 自带 `Toolbar`，且改用 `Toolbar` 自身的菜单 API（`inflateMenu` / `setOnMenuItemClickListener`）而非 `setSupportActionBar` + `onCreateOptionsMenu` —— 三个页面同时存活，Activity 级 ActionBar 只有一份，继续用 `setSupportActionBar` 会互相抢占。
- `MainActivity` 的溢出菜单（`menu_main.xml` 的「管理」「设置」两项）删除；其入口由底部导航替代。
- 返回键语义：管理页抽屉打开 → 先收抽屉；多选模式 → 先退多选；非「下载」tab → 回到「下载」tab；「下载」tab → 退出应用。
- `DownloadService` 的下载完成通知改为跳 `MainActivity` 并直接落在「管理」tab；`VideoPlayerActivity` / `ImageViewerActivity` / `TagManageActivity` 的 `parentActivityName` 由 `ManageActivity` 改为 `MainActivity`。

## Capabilities

### New Capabilities

- `home-shell-navigation`: 应用主外壳的底部导航结构 —— 三个 tab 的构成、切换时的状态保留、Toolbar 与菜单归属、返回键优先级、以及外部入口（通知 / 子页面向上导航）落到指定 tab 的行为。

### Modified Capabilities

（无既有 spec；`openspec/specs/` 目前为空。）

## Impact

- **删除**：`activity/ManageActivity.kt`、`activity/SettingsActivity.kt`、`res/menu/menu_main.xml`。
- **新增**：`fragment/DownloadFragment.kt`、`fragment/ManageFragment.kt`、`fragment/SettingsFragment.kt`，对应 `res/layout/fragment_download.xml`、`fragment_manage.xml`、`fragment_settings.xml`，底部导航菜单 `res/menu/menu_bottom_nav.xml` 与三个导航图标 + 选中态 color state list。
- **改写**：`activity/MainActivity.kt`（外壳 + tab 路由 + `onNewIntent`）、`res/layout/activity_main.xml`、`AndroidManifest.xml`（删两个 Activity 声明、改三处 `parentActivityName`）、`download/DownloadService.kt`（通知 Intent）。
- **受影响但不改逻辑**：`ManageVideoFragment` / `ManageImageFragment` 取 `ManageViewModel` 的作用域从 `activityViewModels()` 变为父 Fragment（`ManageFragment`）；`ListDownloadFragment` / `SingleDownloadFragment` 仅换宿主。
- **依赖**：`com.google.android.material` 已在依赖里（`TabLayout` 在用），`BottomNavigationView` 无需新增依赖。
- **不涉及**：`api/`、`download/` 的下载与签名逻辑、`data/db` 数据库结构与迁移（version 保持 14）、`net/LanFileServer`。
