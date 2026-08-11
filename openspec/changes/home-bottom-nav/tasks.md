## 1. 外壳骨架与资源

- [ ] 1.1 新增 `res/menu/menu_bottom_nav.xml`：三项 `nav_download` / `nav_manage` / `nav_settings`，标题走新增的 `strings.xml` 条目（`nav_download` / `nav_manage` / `nav_settings`）
- [ ] 1.2 新增三个 Material 风格 vector 图标 `ic_nav_download.xml` / `ic_nav_manage.xml` / `ic_nav_settings.xml`（设置项可复用 `ic_settings.xml` 的路径数据）
- [ ] 1.3 新增 `res/color/bottom_nav_item_color.xml`：`state_checked=true` → `color_dopamine_pink`，默认 → `text_color_secondary`
- [ ] 1.4 重写 `res/layout/activity_main.xml`：`LinearLayout(vertical)` = `FragmentContainerView`（`layout_weight=1`，id `navHost`）+ `BottomNavigationView`（id `bottomNav`，`labelVisibilityMode="labeled"`，`itemIconTint` / `itemTextColor` 指向 1.3）
- [ ] 1.5 `MainActivity` 改为外壳：定义 `EXTRA_TAB` 与 `TAB_DOWNLOAD` / `TAB_MANAGE` / `TAB_SETTINGS` 常量、`intentFor(context, tab)` 工厂、固定 tag 常量
- [ ] 1.6 `MainActivity` 实现 `selectTab(tab)`：首次 `add` 目标 Fragment，其余 `show` / `hide`；显示页 `setMaxLifecycle(RESUMED)`，隐藏页 `setMaxLifecycle(STARTED)`；`BottomNavigationView.setOnItemSelectedListener` 接上，重复点当前项直接返回 true 不做事
- [ ] 1.7 `MainActivity` 内边距重划：`navigationBars` 的 left/right 给根布局、bottom 给 `BottomNavigationView`；移除原先施加在内容根上的 bottom padding 与 `findViewById(R.id.toolbar)`
- [ ] 1.8 `MainActivity` 注册最低优先级的外壳 `OnBackPressedCallback`：不在「下载」tab → 切回「下载」tab；已在「下载」tab → `isEnabled = false` 后转交系统
- [ ] 1.9 `MainActivity` 实现 `applyTabFromIntent(intent)`，在 `onCreate` 与 `onNewIntent`（含 `setIntent`）各调一次；保留 `MediaPermissions.registerAndRequestIfNeeded` 与 `DouyinCookieStore` 初始化的既有时序（在 `setContentView` 之前）

## 2. 下载页 Fragment

- [ ] 2.1 新增 `res/layout/fragment_download.xml`：`Toolbar`（id `toolbarDownload`）+ `TabLayout` + `ViewPager2`，样式与原 `activity_main.xml` 逐属性一致
- [ ] 2.2 新增 `fragment/DownloadFragment.kt`：原样搬运 `MainActivity` 的 `MainPagerAdapter`（改用 `FragmentStateAdapter(this)`，子 Fragment 走 `childFragmentManager`）与 `TabLayoutMediator`（子 Tab 文案「单视频下载」/「列表下载」顺序不变）
- [ ] 2.3 `DownloadFragment` 自行处理 `statusBars.top` → `toolbarDownload` 的顶部内边距
- [ ] 2.4 接进外壳跑通：底部导航第一项显示下载页，「管理」「设置」两项**暂时仍 `startActivity`** 到原 Activity；`assembleDebug` 通过、手动验证子 Tab 与列表下载可用

## 3. 设置页 Fragment

- [ ] 3.1 新增 `res/layout/fragment_settings.xml`：以 `activity_settings.xml` 为蓝本，`Toolbar` id 改 `toolbarSettings`，去掉返回箭头（根 tab 不需要向上导航）
- [ ] 3.2 新增 `fragment/SettingsFragment.kt`：搬运 `SettingsActivity` 的全部逻辑（`SettingsViewModel` 观察、`busy` 进度对话框、`SettingsEvent` 分发、标签筛选模式对话框与摘要回显、备份 / 恢复的二次确认）
- [ ] 3.3 `registerForActivityResult(OpenDocument())` 迁到 Fragment 顶层属性（保证在 `onCreate` 之前注册），SAF 恢复路径与「孤儿化 → 引导文件选择器」分支照搬
- [ ] 3.4 `restartApp()` 搬到 Fragment：`requireActivity().packageManager.getLaunchIntentForPackage(...)`、`finishAffinity()`、`Process.killProcess` 逻辑不变
- [ ] 3.5 进度对话框在 `onDestroyView` 中 dismiss；`Toolbar` 顶部内边距同 2.3
- [ ] 3.6 底部导航第三项切到 `SettingsFragment`；手动验证备份、恢复（含从文件选择）、标签筛选模式三条路径

## 4. 管理页 Fragment —— 骨架与菜单

- [ ] 4.1 新增 `res/layout/fragment_manage.xml`：以 `activity_manage.xml` 为蓝本，`DrawerLayout` 作根，`Toolbar` id 改 `toolbarManage`，其余（内容容器、`TabLayout`、`ViewPager2`、作者抽屉的搜索框 / 列表 / 空态）保持一致
- [ ] 4.2 新增 `fragment/ManageFragment.kt` 骨架：`by viewModels<ManageViewModel>()`、`ManagePagerAdapter` 改用 `FragmentStateAdapter(this)`、`TabLayoutMediator`、`ViewPager2.OnPageChangeCallback`（切 Tab 退多选 + 折叠搜索）照搬
- [ ] 4.3 `ManageVideoFragment` / `ManageImageFragment` 的 `activityViewModels()` 改为 `viewModels(ownerProducer = { requireParentFragment() })`，并更新两处类 KDoc 中「与 Activity 通信」的措辞
- [ ] 4.4 菜单改造：`toolbarManage.inflateMenu(R.menu.menu_manage)` 一次性装填，`onCreateOptionsMenu` / `onPrepareOptionsMenu` / `onOptionsItemSelected` 分别改写为 `setupMenu()` / `applyMenuState(menu)` / `setOnMenuItemClickListener`，原 `android.R.id.home` 分支改由 `setNavigationOnClickListener` 承接
- [ ] 4.5 把原先所有 `invalidateOptionsMenu()` 调用点替换为 `applyMenuState(toolbarManage.menu)`（只改既有 `MenuItem` 的 `isVisible` / `title`，**不重新 inflate**）；`viewModel.filters` 的 `menuTitleSignature + distinctUntilChanged` 过滤保留
- [ ] 4.6 `SearchView`：从 `toolbarManage.menu` 取 `action_search` 的 `actionView` 装配监听，`MenuItemCompat` 展开 / 折叠监听与 `collapseAndResetSearch()` 照搬
- [ ] 4.7 多选态 Toolbar 外观：导航图标在默认（无图标）与 `ic_close_manage` 之间切换、标题在 `manage_title` 与已选数量之间切换、点击 Toolbar 弹数量 Toast —— 三处照搬并适配 Fragment 上下文

## 5. 管理页 Fragment —— 筛选、抽屉与导出

- [ ] 5.1 搬运作者抽屉：`AuthorFilterAdapter` 装配、搜索框 `TextWatcher`、清除按钮、`LOCK_MODE_LOCKED_CLOSED` 锁闭与 `SimpleDrawerListener` 重新锁闭逻辑
- [ ] 5.2 抽屉内边距按设计调整：`statusBars.top` 照旧，`bottom` 改为 0（系统导航栏区域已由底部导航占据）
- [ ] 5.3 搬运七层筛选相关的全部对话框与菜单标题回显：排序、归属、标签数量（多选 + 清除）、标签修改次数、标签精细检索（含「对话框展示期间捕获 tab」的注释与写法）
- [ ] 5.4 搬运统计面板：`showStatsDialog` / `buildStatsText` / `humanSize`
- [ ] 5.5 搬运导出：`handleExportZip` + `renderZipProgress` + `onZipFinished`；`handleExportLan`（含「tab 与 entities 同一时刻捕获」）、`startLanExport`、`onLanStartFailed`、`renderLanPreparing`、`renderLanState` / `showLanDialog` / `lanStatusText`
- [ ] 5.6 搬运其余菜单动作：删除选中二次确认、清除已失效二次确认、全选 / 取消全选、设置标签、标签管理页跳转
- [ ] 5.7 对话框清理从 `onDestroy` 改到 `onDestroyView`（`lanDialog` 先 `setOnDismissListener(null)` 再 dismiss 的顺序不能变，否则会误停服务）
- [ ] 5.8 `ManageFragment` 注册自己的 `OnBackPressedCallback`（`viewLifecycleOwner` 作用域）：抽屉打开 → 关抽屉；多选中 → 退多选；`isEnabled` 由「本页可见 且（抽屉打开 或 多选中）」决定，在 `onHiddenChanged`、多选状态变化、抽屉开关处刷新
- [ ] 5.9 `onHiddenChanged(hidden = true)` 时折叠搜索框
- [ ] 5.10 底部导航第二项切到 `ManageFragment`

## 6. 拆除旧入口

- [ ] 6.1 删除 `activity/ManageActivity.kt` 与 `activity/SettingsActivity.kt`
- [ ] 6.2 删除 `res/menu/menu_main.xml`，并移除 `MainActivity` 中对应的 `onCreateOptionsMenu` / `onOptionsItemSelected`
- [ ] 6.3 `AndroidManifest.xml`：删除两个 Activity 声明；`TagManageActivity` / `VideoPlayerActivity` / `ImageViewerActivity` 的 `parentActivityName` 改为 `.activity.MainActivity`
- [ ] 6.4 `DownloadService` 通知 `PendingIntent` 改为 `MainActivity.intentFor(this, TAB_MANAGE)` + `FLAG_ACTIVITY_SINGLE_TOP or FLAG_ACTIVITY_CLEAR_TOP`（`PendingIntent` 带 `FLAG_UPDATE_CURRENT`）
- [ ] 6.5 全仓搜索 `ManageActivity` / `SettingsActivity` 残留引用（含 KDoc 与注释，如 `LanFileServer`、`DownloadedVideoEntity`、`MediaOrientationProbe`、`TagManageActivity` 的类注释），逐处改为新的 Fragment 名
- [ ] 6.6 `./gradlew assembleDebug` 与 `./gradlew lint` 通过

## 7. 验收

- [ ] 7.1 导航：冷启动落「下载」；三项互切正确；重复点当前项无重建
- [ ] 7.2 状态保留：管理页设好筛选 + 滚动后切走再切回，条件、滚动位置、多选状态均不变；下载页子 Tab 与已加载列表不变
- [ ] 7.3 局域网导出跨 tab 存活：启动导出后切到「下载」tab，电脑端仍可下载
- [ ] 7.4 菜单逐条对照原 `ManageActivity`：15 项菜单在「视频 / 图片 Tab × 多选 / 非多选」四种组合下的显隐与标题回显完全一致
- [ ] 7.5 搜索框连续输入不折叠、不丢焦点；切 Tab 与切走本页时自动折叠并恢复非搜索态
- [ ] 7.6 返回键四条优先级逐条验证，含「隐藏页面不抢返回键」这一条（管理页处于多选态时在设置页按返回）
- [ ] 7.7 外部入口：点击下载完成通知落在「管理」tab；主界面已在前台时不产生第二个实例且「下载」tab 状态不丢
- [ ] 7.8 设置页三条路径（备份、列表恢复、SAF 恢复）与恢复后重启进程验证通过
- [ ] 7.9 内边距：三键导航设备上底部导航不被遮挡、各页 Toolbar 填满状态栏区域、抽屉底部无多余空白

## 8. 文档

- [ ] 8.1 更新 `CLAUDE.md`：架构图（`MainActivity` 为外壳、新增三个 Fragment、移除两个 Activity）、包结构约定表、以及新增一节说明「底部导航外壳：show/hide + setMaxLifecycle、页面自带 Toolbar 菜单、返回键优先级」这三条容易踩的约定
- [ ] 8.2 更新 `CLAUDE.md` 中提及 `ManageActivity` / `SettingsActivity` 的段落（管理页筛选栈、导出管道、持久化的备份恢复入口）
- [ ] 8.3 按仓库约定更新 `.cursor/plans/f2-style-batch-prereqs_d02563bb.plan.md` 的 `todos[]` 状态与 `.cursor/CONTINUATION.md`（若本次工作与其中条目相关）
