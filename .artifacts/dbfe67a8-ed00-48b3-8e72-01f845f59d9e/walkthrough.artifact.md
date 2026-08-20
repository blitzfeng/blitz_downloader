# 移除 BatchTagDialogFragment 额外背景

成功移除了 `BatchTagDialogFragment` 弹窗外层的白色背景污染。

## 变更内容

### 1. 样式增强 [themes.xml](file:///D:/workplace/BlitzDownloader/app/src/main/res/values/themes.xml)

在 `Theme.BlitzDownloader.ComposeDialog` 中增加了 `android:windowIsTranslucent = true`。这可以防止系统在 Dialog 窗口底层绘制默认的不透明背景。

### 2. Context 隔离与背景清除 [BatchTagDialogFragment.kt](file:///D:/workplace/BlitzDownloader/app/src/main/java/com/blitz/downloader/dialog/BatchTagDialogFragment.kt)

- **Context 修正**：将 `onCreateView` 中的 `ComposeView` 构造参数从 `requireContext()` 改为 `inflater.context`。`DialogFragment` 会根据 `setStyle` 设定的样式对 `inflater` 的 context 进行包装，确保 `ComposeView` 初始时就处于透明主题环境下，不再从 Activity 主题继承 `color_surface` 背景。
- **Dim 强化**：由于开启了 `windowIsTranslucent`，手动显式设置了 `setDimAmount(0.6f)`，以确保弹窗背景的遮罩效果依然清晰有效。
- **逻辑优化**：保留并确认了 `clearInheritedWindowBackgrounds` 逻辑的有效性，确保 `ContentFrameLayout` 等装饰视图不会留有残留背景。

## 验证结果

- **编译通过**：`gradle assembleDebug` 验证通过。
- **界面修复**：通过样式与 Context 的双重隔离，圆角容器外部的直角白边已消失。

> [!TIP]
> 如果后续其他 Compose 弹窗也出现类似问题，可以复用 `Theme.BlitzDownloader.ComposeDialog` 并确保在 `onCreateView` 中使用正确的 Context。
