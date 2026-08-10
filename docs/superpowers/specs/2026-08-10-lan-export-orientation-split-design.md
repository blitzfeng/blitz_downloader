# 局域网导出按横屏/竖屏分包 — 设计

- 日期：2026-08-10
- 状态：已确认，待实现
- 影响面：`data/db`（v13 → v14）、`util/`、`model/`、`download/`、`net/`、`viewmodel/`、`activity/`

## 需求

管理页「发送到电脑」（局域网导出）时，把选中的视频按画面方向分成**横屏**与**竖屏**两个 ZIP 包，供电脑端分别下载。

## 已确认的决策

| 决策点 | 结论 |
|---|---|
| 宽高来源 | 探测本地文件（非抖音接口字段），结果落库缓存 |
| 落库方式 | 新增 v14 列 `mediaWidth` / `mediaHeight` |
| 写入时机一 | 下载落盘后，在 `DownloadService` 内探测并随写库一并写入 |
| 写入时机二 | 局域网导出前，对 `mediaWidth == 0` 的记录懒探测并回填 |
| 下载时口径 | 探测刚落盘的本地文件（与导出时同一个探测函数） |
| 图集分包 | **图片 Tab 不参与分包**，行为与现状完全一致 |
| 图集写入 | 仍探测首图并写入宽高（当前不用，为后续留数据） |
| 方形 / 探测失败 | 一律归竖屏（`w > h` 才算横屏） |
| 电脑端页面 | 三个按钮（全部 / 横屏 / 竖屏）+ 文件列表按方向分组 |
| 单元测试 | 本次不加；`junit` 快照版问题不在本需求范围内处理 |

## 架构

### 1. `util/MediaOrientationProbe.kt`（新增）

无状态工具对象，符合 `util/` 包约定（放无状态工具，不放有生命周期的对象）。

```kotlin
object MediaOrientationProbe {
    data class Size(val width: Int, val height: Int)

    /** 必须在 IO 线程调用；读不出返回 null。 */
    fun probe(file: File): Size?
}
```

**一份逻辑，两个调用点**（下载后、导出前），保证两个写入时机的口径永远一致。

- **视频**（`.mp4`）：`MediaMetadataRetriever` 读 `METADATA_KEY_VIDEO_WIDTH` / `METADATA_KEY_VIDEO_HEIGHT` / `METADATA_KEY_VIDEO_ROTATION`。**`rotation` 为 90 或 270 时交换宽高。**

  这一步不能省：1920×1080 且带 90° 旋转元数据的视频，播放器里呈现为竖屏，但裸读宽高是横屏。不修正会判反。

- **图片**（`.jpg` / `.png` / `.webp` 等）：`BitmapFactory.Options(inJustDecodeBounds = true)` 只读文件头、不解码像素。JPEG 额外读 `ExifInterface.TAG_ORIENTATION`，为 `ORIENTATION_ROTATE_90` / `ROTATE_270` / `TRANSPOSE` / `TRANSVERSE` 时交换宽高。

- 任何异常（文件损坏、格式不支持、retriever 抛错）一律 `runCatching` 吞掉并返回 `null`。`MediaMetadataRetriever` 需 `use` / `finally release()`。

按扩展名分派到视频还是图片分支；未知扩展名返回 `null`。

### 2. `model/MediaOrientation.kt`（新增）

方向是**派生值**，不落库。DB 只存原始事实（宽高），这样后续想按分辨率筛选/排序无需再加列。

```kotlin
enum class MediaOrientation {
    LANDSCAPE, PORTRAIT;

    companion object {
        /** `w > h` 才算横屏。方形、0（未知）、负数一律归 [PORTRAIT]。 */
        fun of(width: Int, height: Int): MediaOrientation =
            if (width > height) LANDSCAPE else PORTRAIT
    }
}
```

一行代码兜住三种边界（1:1、未探测、探测失败），无需哨兵值。

### 3. 数据库 v13 → v14

| 文件 | 改动 |
|---|---|
| `DownloadedVideoEntity` | 新增 `mediaWidth: Int = 0`、`mediaHeight: Int = 0`，带 KDoc 说明 `0 = 未知（未探测或探测失败）` |
| `AppDatabase` | 新增 `MIGRATION_13_14`（两条 `ALTER TABLE downloaded_videos ADD COLUMN ... INTEGER NOT NULL DEFAULT 0`）；`version = 14`；注册进 `addMigrations(...)` |
| `DownloadedVideoDao` | 新增 `@Query("UPDATE downloaded_videos SET mediaWidth = :w, mediaHeight = :h WHERE awemeId = :awemeId") suspend fun updateMediaSize(awemeId: String, w: Int, h: Int)` |
| `DownloadedVideoRepository` | `recordSuccessfulDownload(...)` 新增 `mediaWidth: Int = 0` / `mediaHeight: Int = 0` 两个参数并写入实体；新增 `suspend fun updateMediaSize(awemeId, w, h)` 转发 dao |
| `.cursor/rules/db-schema.md` | 同步新增两列与版本行 |

**探测失败不写哨兵值，保持 0。** 下次导出会再探一次，代价是几毫秒（`resolveExportFiles` 已跳过磁盘上不存在的文件，能走到探测的基本都是好文件）。换来的是 `0` 语义单一——只表示「不知道」，不必区分「没探过」与「探过但失败」。

### 4. 写入时机一 — 下载落盘后

`DownloadService.processJob` 中，在 `repo.recordSuccessfulDownload(...)` 之前对刚落盘的文件探测：

```kotlin
val relPath = result.succeededPaths[item.id].orEmpty()
val size = relPath.takeIf { it.isNotBlank() }?.let { p ->
    MediaOrientationProbe.probe(File(Environment.getExternalStorageDirectory(), p))
}
repo.recordSuccessfulDownload(
    /* ...现有参数... */,
    mediaWidth = size?.width ?: 0,
    mediaHeight = size?.height ?: 0,
)
```

- `filePath` 是**相对 `Environment.getExternalStorageDirectory()` 的路径**（与 `MediaExportManager.resolveExportFiles` 的拼接方式一致），探测时用同样方式还原绝对路径。
- 图集的 `filePath` 指向首图，即探测首图，符合「图集也写入」的决策。
- `recordSuccessfulDownload` 全仓库只有 `DownloadService` 一个调用点，不存在多处等价性问题。
- 这段代码已在 IO 上下文中（服务的下载协程），无需额外切线程。

### 5. 写入时机二 — 局域网导出前懒回填

`ManageViewModel.startLanExport(tab, entities)` 流程改为：

```
检查 IP（不变）
  → [新] 仅当 tab == TAB_VIDEO 时，IO：
           筛出 mediaWidth == 0 的记录 → MediaOrientationProbe.probe
           → repo.updateMediaSize 回库
           → 同步更新内存中的 entities 副本
  → resolveExportFiles(回填后的 entities)
  → LanFileServer(files, splitByOrientation = (tab == TAB_VIDEO))
```

**懒回填只在视频 Tab 执行。** 图片 Tab 不分包，探测对本次导出毫无用处，白白让用户等几秒。图集记录的宽高由「下载时」那条路径写入即可，历史图集记录的宽高保持 0 —— 这符合「图集写入宽高只是为后续留数据」的定位。

**回填后必须同步更新内存里那份 `entities`**——紧接着的 `resolveExportFiles` 读的是内存对象，不是数据库。只写库不更新内存，本次导出会全部落进竖屏包。

新增进度状态，避免大批量历史记录首次导出时界面像卡死：

```kotlin
data class LanPrepareProgress(val done: Int, val total: Int)

private val _lanPreparing = MutableStateFlow<LanPrepareProgress?>(null)
val lanPreparing: StateFlow<LanPrepareProgress?> = _lanPreparing.asStateFlow()
```

只在确有待探测项（`total > 0`）时置为非 null，完成后置回 null。`ManageActivity` 观察它显示 / 更新一个进度对话框，与现有 `zipProgress` 的处理方式保持一致。

历史记录只有**第一次**导出慢，之后是纯读库。

### 6. `MediaExportManager.ExportFile` — 只多一个字段

```kotlin
data class ExportFile(
    val file: File,
    val entryName: String,
    val awemeId: String,
    val orientation: MediaOrientation,
)
```

`resolveExportFiles` 中由 `MediaOrientation.of(e.mediaWidth, e.mediaHeight)` 得出；图集解析出的多个文件共享所属记录的方向（反正图片 Tab 不分包）。

**`resolveExportFiles` 保持纯粹——只读 entity 字段，不做任何探测 IO。** 回填是 ViewModel 在调用它之前的职责。

ZIP 导出路径（`exportToZip`）同样会拿到这个字段但不使用，行为不变。

### 7. `LanFileServer` — 新增开关与两条路由

构造参数新增 `splitByOrientation: Boolean = false`。

| 路由 | 内容 | 下载文件名 | `TransferEvent.label` |
|---|---|---|---|
| `/all.zip` | 全部文件（保留不变） | `bDouyin_export.zip` | `all.zip` |
| `/landscape.zip` | 仅 `orientation == LANDSCAPE` | `bDouyin_export_landscape.zip` | `landscape.zip` |
| `/portrait.zip` | 仅 `orientation == PORTRAIT` | `bDouyin_export_portrait.zip` | `portrait.zip` |

现有 `serveZip(out, writeBody)` 重构为：

```kotlin
private fun serveZip(
    out: OutputStream,
    writeBody: Boolean,
    subset: List<MediaExportManager.ExportFile>,
    downloadName: String,
    label: String,
)
```

三条路由共用它，**流式打包、`Deflater.NO_COMPRESSION`、中断即整包不计数、`sentIds` 收集与回调等既有语义全部原样保留**，不复制代码。

`ZIP_LABEL` 常量拆为 `ZIP_LABEL_ALL` / `ZIP_LABEL_LANDSCAPE` / `ZIP_LABEL_PORTRAIT`（`ZIP_LABEL_ALL` 沿用原值 `"all.zip"`）。

边界处理：

- `splitByOrientation == false`（图片 Tab）时，`/landscape.zip` 与 `/portrait.zip` **一律返回 404**，首页不渲染方向按钮、文件列表不分组 —— 图片 Tab 的行为与改动前完全一致。
- 子集为空（例如一条横屏都没选中）时同样返回 404；首页本就不渲染该按钮，正常操作点不到。

**`exportCount` 语义不变**：方向包被完整写出 socket 后，包内每条记录 `+1`，与 `all.zip` 同规则；`isZip` 仍为 `true`；`HEAD` 探测与中途断连仍不计数。

### 8. 电脑端首页渲染

`splitByOrientation == true` 时：

```
bDouyin 导出
共 42 个文件 · 3.10 GB

[ ⬇ 打包下载全部 (zip) ]
[ ⬇ 横屏 8 个 · 780 MB ]  [ ⬇ 竖屏 34 个 · 2.34 GB ]

── 横屏 (8) ──
  作者A+标题.mp4              98 MB
  ...
── 竖屏 (34) ──
  作者B+标题.mp4              72 MB
  ...
```

- 顶部「全部」按钮沿用现有全宽蓝色样式。
- 两个方向按钮并排一行，各显示该方向的**文件数与总体积**；数量为 0 的那个不渲染。
- 文件列表按方向分两组，每组一个小标题（含数量）；组内顺序沿用 `files` 的原始顺序。
- `/f?i=N` 的 `N` 仍是**在完整 `files` 列表中的下标**，分组渲染时不重排索引 —— 保证单文件下载链接不受分组影响。

`splitByOrientation == false` 时：维持现有渲染，一行不改。

### 9. `ManageViewModel.LanExportState` 与手机端对话框

```kotlin
data class LanExportState(
    val url: String,
    val fileCount: Int,
    val landscapeCount: Int = 0,   // 新增
    val portraitCount: Int = 0,    // 新增
    val splitByOrientation: Boolean = false,  // 新增
    val transferCount: Int = 0,
    val lastTransfer: Transfer? = null,
)
```

`ManageActivity.showLanDialog` 在 `splitByOrientation == true` 时多显示一行「横屏 8 · 竖屏 34」，用户在手机上就能确认分包结果是否符合预期。新增对应 `strings.xml` 条目（**ViewModel 不碰 `R.string`**，字符串拼接留在 Activity）。

## 数据流

```
下载：DownloadService.processJob
        → MediaOrientationProbe.probe(落盘文件)
        → recordSuccessfulDownload(mediaWidth, mediaHeight)   [DB v14]

导出：ManageActivity 菜单「发送到电脑」
        → ManageViewModel.startLanExport(tab, entities)
        → [IO] 对 mediaWidth == 0 者 probe → updateMediaSize + 更新内存副本
        → MediaExportManager.resolveExportFiles → ExportFile(orientation)
        → LanFileServer(files, splitByOrientation = tab == TAB_VIDEO)
        → 电脑浏览器 GET /landscape.zip 或 /portrait.zip
        → 完整写出 → TransferEvent → incrementExportCount
```

## 错误处理

| 情况 | 行为 |
|---|---|
| 探测抛异常 / 文件损坏 | 返回 `null`，宽高保持 0，归竖屏包，**不写哨兵值** |
| 未知扩展名 | 同上 |
| 回填写库失败 | `runCatching` 吞掉；本次仍用内存中的探测结果分包，不影响导出 |
| 方向包为空 | 首页不渲染该按钮；直接访问 URL 返回 404 |
| 图片 Tab 访问方向路由 | 返回 404 |
| 打包中途客户端断开 | 沿用现有逻辑：整包视为不可用，不触发 `TransferEvent`、不计数 |

## 边界与不做的事

- **不改 `exportCount` 的既有语义**：仍只表示「手机已完整发出字节」，不代表电脑落盘。
- **不给图片 Tab 分包**，也不改它的任何现有行为。
- **不用抖音接口的 `video.width/height`**，避免同一张表里混着两种口径的数据。
- **不做历史数据批量回填**，只在导出时按需懒探测。
- **不加单元测试**，也不动 `libs.versions.toml` 里 `junit = "4.14-SNAPSHOT"` 的已知问题。
- **不引入依赖注入**，`ManageViewModel` 仍直接用 `BlitzApp.instance.downloadedVideoRepository`。

## 文档同步

- `CLAUDE.md`「导出管道」段：补充分包规则、tab 边界、`MediaOrientationProbe` 的两个调用点。
- `CLAUDE.md`「持久化（Room）」段：版本号 13 → 14。
- `.cursor/rules/db-schema.md`：新增 `mediaWidth` / `mediaHeight` 两列与 v14 版本行。
- F2 移植相关文档（`API_IMPLEMENTATION.md`、`IMPLEMENTATION_SUMMARY.md`、`INTEGRATION_GUIDE.md`）与本需求无关，不动。
