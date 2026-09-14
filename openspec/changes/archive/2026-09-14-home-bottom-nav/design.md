## Context

现状是三个平级 Activity：

- `MainActivity`：`Toolbar` + `TabLayout` + `ViewPager2`（`SingleDownloadFragment` / `ListDownloadFragment`），溢出菜单里藏着「管理」「设置」两个 `startActivity` 入口。
- `ManageActivity`：最重的一页 —— `DrawerLayout`（右侧作者抽屉）包着 `Toolbar` + `TabLayout` + `ViewPager2`（`ManageVideoFragment` / `ManageImageFragment`），Activity 级 `ManageViewModel` 持有按 Tab 的筛选状态、多选状态、局域网导出服务（`LanFileServer`），Toolbar 在多选态会改导航图标与标题，菜单有 15 项且按「当前 Tab × 是否多选」动态显隐。
- `SettingsActivity`：`Toolbar` + 滚动列表，备份 / 恢复（含 SAF 兜底与恢复后杀进程重启）。

约束：项目无 Compose、无依赖注入、无 Navigation Component，UI 全部是 XML + ViewBinding + Fragment；Material Components 已在依赖里（`TabLayout` 在用）。CLAUDE.md 明确要求 UI 用 Material Design，且「Activity 与 Tab Fragment 不直接互相引用、只经 ViewModel 通信」。

## Goals / Non-Goals

**Goals:**

- `MainActivity` 成为唯一主外壳：`FragmentContainerView` + `BottomNavigationView`，三 tab 平级。
- 切 tab 不重建页面，各页状态（筛选 / 滚动 / 多选 / 输入 / 局域网服务）原地保留。
- 管理页、设置页的功能行为**逐项等价**，不借改造之机改语义。
- 返回键有确定的优先级链，且被隐藏的页面不参与。

**Non-Goals:**

- 不引入 Navigation Component / Compose / DI。
- 不改 `api/`、`download/`、`data/db`、`net/` 的任何逻辑；数据库 version 保持 14。
- 不动 `TagManageActivity`、`VideoPlayerActivity`、`ImageViewerActivity`、`DouyinWebBrowserActivity` 的形态（仍是独立 Activity），只改它们的 `parentActivityName`。
- 不重排管理页的七层筛选逻辑、不动 `ManageViewModel` 的内部实现。

## Decisions

### D1：`add` + `show/hide` + `setMaxLifecycle`，不用 `replace`，也不用 ViewPager2

三个页面首次被选中时 `add` 进 `FragmentContainerView` 并以固定 tag（`"tab_download"` / `"tab_manage"` / `"tab_settings"`）记名，之后只 `show` / `hide`。

- **不用 `replace`**：会销毁被切走页面的 Fragment，筛选条件、滚动位置全丢，`ManageViewModel`（Fragment 作用域）随之 `onCleared`，正在跑的局域网导出会被掐断 —— 这正是本次改造要消灭的问题。
- **不用 ViewPager2 承载三 tab**：其一，底部导航不应支持左右滑动换页；其二，管理页与下载页内部已各有一层 `ViewPager2`，嵌套同方向滑动容器会产生手势冲突；其三，外层 ViewPager2 的 Fragment tag 是内部实现（`"f$position"`），CLAUDE.md 已明确不再依赖它。
- **`setMaxLifecycle`**：显示中的页面设 `RESUMED`，隐藏的设 `STARTED`。全用默认（隐藏页也停在 `RESUMED`）会让三页同时认为自己可见，`onPause` / `onResume` 语义失真；降到 `CREATED` 又会销毁视图、等价于 `replace`。停在 `STARTED` 既保住视图与 `repeatOnLifecycle(STARTED)` 的数据订阅，又让 `onResume` 只在真正切到该页时触发。
  - 影响面确认：`ManageVideoFragment.onResume → refreshWatchedFlags()` 与 `ListDownloadFragment.onResume → onScreenResumed()` 这两条**兜底回查**依然成立 —— 从播放页 / WebView 返回时 Activity 级 resume 会带动当前页 `onResume`；额外多出的一次触发（切回该 tab 时）只是一次轻量查询，不改变语义。CLAUDE.md 明令这两条回查不得删除，本方案保留。

### D2：`ManageViewModel` 作用域下沉到 `ManageFragment`

`ManageFragment` 用 `by viewModels()`；两个 Tab 子 Fragment 由 `activityViewModels()` 改为 `viewModels(ownerProducer = { requireParentFragment() })`。子 Fragment 由 `ManageFragment` 的 `childFragmentManager` 承载（`FragmentStateAdapter(this)` 的 Fragment 构造器重载），因此 `requireParentFragment()` 必定是 `ManageFragment`。

备选是继续用 `activityViewModels()`：能少改两行，但会让「管理页的状态」挂到整个 App 外壳上，`ManageViewModel` 里那句「转屏不掐断传输、真正的关闭在 `onCleared()`」的语义变成「App 存活期间永不关闭」，责任边界糊掉。下沉后作用域与页面一一对应，而页面常驻这一点由 D1 保证，实际存活期与原先一致。

### D3：页面菜单走 `Toolbar` 自身的菜单 API，弃用 `setSupportActionBar`

三个页面同时存活，而 Activity 级 ActionBar 只有一份 —— 谁 `setSupportActionBar` 谁赢，`invalidateOptionsMenu()` 会打到错的页面上。改法：

```
toolbar.inflateMenu(R.menu.menu_manage)      // onViewCreated 里一次
toolbar.setOnMenuItemClickListener { … }     // 取代 onOptionsItemSelected
toolbar.setNavigationOnClickListener { … }   // 取代 android.R.id.home
applyMenuState(toolbar.menu)                 // 取代 onPrepareOptionsMenu
```

`invalidateOptionsMenu()` 的每处调用改成 `applyMenuState(toolbar.menu)` —— 它直接改既有 `MenuItem` 的 `isVisible` / `title`，**不重新 inflate**，所以 `SearchView` 实例始终是同一个，不会被折叠 / 清空 / 丢焦点。原先为了绕开「重新 inflate 打断输入」而加的 `menuTitleSignature + distinctUntilChanged` 过滤器**保留**（少做无用功，且不引入行为变化）。`MenuItemCompat` 的展开 / 折叠监听在 `Toolbar` 的菜单上同样有效。

备选是 `MenuProvider` + Activity 单 Toolbar：视觉更统一，但管理页多选态要跨层改外壳 Toolbar 的图标与标题，搜索框与抽屉的耦合全部要重写，风险明显更高 —— 已排除。

### D4：返回键 —— 每页自己注册 callback，外壳兜底

优先级靠 `OnBackPressedDispatcher` 的 LIFO 语义天然形成：

1. `MainActivity.onCreate` 里先注册**外壳 callback**：当前不在「下载」tab → 切回「下载」tab；在「下载」tab → `isEnabled = false` 并转交系统。它注册最早，优先级最低。
2. 各页在 `onViewCreated` 里以 `viewLifecycleOwner` 注册自己的 callback（注册更晚 → 优先级更高）：`ManageFragment` 处理「抽屉打开 → 关抽屉」「多选中 → 退多选」。
3. **隐藏的页面必须让路**：`setMaxLifecycle(STARTED)` 下隐藏页的 callback 依然在册，所以 `isEnabled` 由「本页可见 且（抽屉打开 或 多选中）」共同决定，在 `onHiddenChanged` 与多选 / 抽屉状态变化处刷新。

备选是外壳定义 `interface BackHandler` 反查当前页：耦合方向变成 Activity → Fragment，与 CLAUDE.md 的通路约定相悖，且要维护「谁是当前页」的第二份真相 —— 已排除。

### D5：外部入口用 `EXTRA_TAB` + `SINGLE_TOP | CLEAR_TOP`

`MainActivity` 暴露 `EXTRA_TAB`（`TAB_DOWNLOAD` / `TAB_MANAGE` / `TAB_SETTINGS`，Int 常量）与 `intentFor(context, tab)` 工厂。`onCreate` 与 `onNewIntent` 走同一条 `applyTabFromIntent(intent)`；`onNewIntent` 里记得 `setIntent(intent)`。

`DownloadService` 的通知 `PendingIntent` 由指向 `ManageActivity` 改为 `MainActivity.intentFor(this, TAB_MANAGE)` 加 `FLAG_ACTIVITY_SINGLE_TOP or FLAG_ACTIVITY_CLEAR_TOP`，命中既有实例时走 `onNewIntent` 切 tab，不新建外壳（`PendingIntent` 记得带 `FLAG_UPDATE_CURRENT`）。

`VideoPlayerActivity` / `ImageViewerActivity` / `TagManageActivity` 的 `parentActivityName` 改成 `MainActivity`：它们都由管理页启动，返回时 `finish()` 落回既有外壳、tab 仍停在「管理」，`parentActivityName` 只在无栈场景下兜底。

### D6：抽屉留在 `ManageFragment` 内，内边距重新分配

`DrawerLayout` 成为 `fragment_manage.xml` 的根，抽屉滑出范围止于底部导航之上（已确认接受）。内边距职责重新划分：

- 外壳：`navigationBars` 的 `left/right` 给根布局，`bottom` 给 `BottomNavigationView`（原先给内容根的 bottom padding 由导航栏接管）。
- 各页：`statusBars.top` 给自己的 `Toolbar`。
- 管理页抽屉：仍自己吃 `statusBars.top`，但 `bottom` 改为 0 —— 系统导航栏那块现在被底部导航占着，再补一次会多出一段空白。

### D7：布局与资源

- `activity_main.xml` 重写为 `LinearLayout(vertical)`：`FragmentContainerView`（`layout_weight=1`）+ `com.google.android.material.bottomnavigation.BottomNavigationView`（`labelVisibilityMode="labeled"`）。
- 新增 `menu/menu_bottom_nav.xml`（三项）、三个 Material 风格 vector 图标（`ic_nav_download` / `ic_nav_manage` / `ic_nav_settings`，设置项可复用现有 `ic_settings` 的路径数据）、`color/bottom_nav_item_color.xml`（选中 `color_dopamine_pink`，未选中 `text_color_secondary`），与现有 `TabLayout` 的配色保持一致。
- 删除 `menu/menu_main.xml`。
- 三页布局的控件 id 会同时存在于视图树（如三个 `Toolbar`），因此**一律走 ViewBinding，不用 `findViewById`** —— `MainActivity` 现有的 `findViewById(R.id.toolbar)` 一并改掉。id 也按页面加前缀（`toolbarDownload` / `toolbarManage` / `toolbarSettings`）避免歧义。

### D8：迁移按「每步都能编译运行」切分

1. 抽 `DownloadFragment`（原样搬运 `MainActivity` 的 Tab 逻辑），外壳换成底部导航，「管理」「设置」两项**暂时仍 `startActivity`**。
2. 迁 `SettingsFragment`（最简单、无抽屉无多选），底部导航第三项接上。
3. 迁 `ManageFragment`（菜单 API 改造、抽屉、多选、导出对话框、子 Fragment 作用域），第二项接上。
4. 删两个 Activity、改 manifest、改 `DownloadService` 通知 Intent、删 `menu_main.xml`。
5. 补文档（CLAUDE.md 架构图与包结构表、`.cursor/CONTINUATION.md`）。

回滚粒度即上述步骤：任一步出问题，回退该步即可，前序步骤仍是可用状态。

## Risks / Trade-offs

- **三页常驻 → 内存占用上升**（三份 RecyclerView + Coil 位图）→ `setMaxLifecycle(STARTED)` 让隐藏页停止动画与 `RESUMED` 级工作；图片由 Coil 自身的内存缓存按压力回收；实测若内存吃紧，可把「设置」页退回 `replace`（它无状态可留）。
- **`ManageActivity` 900 行整体搬迁 → 漏搬某个对话框 / 菜单分支**→ 任务清单按「菜单项逐条对照」验收（15 项菜单 + 7 层筛选 + 2 条导出路径 + 多选态显隐规则），并保留原文件到最后一步再删，便于逐段比对。
- **`SearchView` 在切 tab 时残留展开态** → 保留原有 `collapseAndResetSearch()`，并在 `onHiddenChanged(hidden = true)` 时再折叠一次。
- **多选态下切 tab 后误操作** → 按 spec，多选状态跨 tab 保留（切回还在多选），与「状态保留」原则一致；Toolbar 标题会持续显示已选数量，用户可见。
- **旧版本已发出的下载通知指向被删除的 `ManageActivity`** → 升级安装会清掉旧进程的通知，实际不可达；不做兼容处理。
- **`DrawerLayout` 从 Activity 根降为 Fragment 根** → 抽屉不再覆盖底部导航（已确认接受）；同时要确认 `LOCK_MODE_LOCKED_CLOSED` 的锁闭逻辑照搬，否则右缘滑动会与下载页/管理页内层 `ViewPager2` 抢手势。

## Migration Plan

见 D8。数据层零改动，无需数据库迁移，无需数据回填；回滚仅涉及代码回退。

## Open Questions

- 底部导航是否需要在下载进行中给「管理」项加角标（`BottomNavigationView.getOrCreateBadge`）？本次**不做**，留待后续。
- 「下载」tab 的 Toolbar 移除管理 / 设置菜单后只剩标题，是否干脆去掉该 Toolbar、让子 `TabLayout` 直接顶到状态栏？本次**保留 Toolbar**（承接状态栏内边距与标题，改动最小），如需精简可在验收后单独提。
