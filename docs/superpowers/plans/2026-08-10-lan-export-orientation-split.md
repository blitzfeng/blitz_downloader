# 局域网导出按横屏/竖屏分包 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 管理页「发送到电脑」（局域网导出）时，把选中的视频按画面方向拆成横屏、竖屏两个 ZIP 包，电脑端可分别下载。

**Architecture:** 新增一个无状态的 `MediaOrientationProbe`（读本地文件元数据得宽高，含旋转修正），在**下载落盘后**与**局域网导出前**两个时机把宽高缓存进 Room（v14 新增 `mediaWidth` / `mediaHeight`）。方向是由宽高派生的纯函数值（`w > h` 才算横屏），不落库。`LanFileServer` 新增 `splitByOrientation` 开关与 `/landscape.zip`、`/portrait.zip` 两条路由，三条打包路由共用同一份流式打包实现。图片 Tab 不分包，行为与改动前完全一致。

**Tech Stack:** Kotlin / Android（minSdk 24、JVM target 11）、Room + KSP、View Binding、XML 布局（无 Compose）、`MediaMetadataRetriever`、`BitmapFactory`、`androidx.exifinterface`。

**规格来源：** `docs/superpowers/specs/2026-08-10-lan-export-orientation-split-design.md`

## Global Constraints

- Kotlin / JVM target **11**；UI 全部是 XML + View Binding + Fragment，**不用 Compose**。
- **不加单元测试**，也不动 `gradle/libs.versions.toml` 里 `junit = "4.14-SNAPSHOT"` 的已知问题。因此本计划的每个任务用 `./gradlew assembleDebug` 作为验证手段，**不要**运行 `./gradlew test`（当前依赖解析就会失败）。
- **ViewModel 不碰 `R.string`**：状态用结构化类型表达，所有字符串拼接留在 Activity / Fragment。
- **ViewModel 不持 Activity / Fragment / View 引用**，一律 `AndroidViewModel` + `getApplication()`。
- 新增类按**职责**放包：无状态工具 → `util/`；跨层数据模型 → `model/`；HTTP 服务 → `net/`；导出/下载 → `download/`。
- Room 迁移**必须显式写出**并注册。漏写迁移 = 用户数据被清空。`fallbackToDestructiveMigration()` 只是兜底，不要依赖它。
- **不改 `exportCount` 的既有语义**：仍只表示「手机已把字节完整发出 socket」，不代表电脑落盘。累加必须保持 `SET exportCount = exportCount + 1` 的原子写法。
- 图片加载用 **Coil 2.7**，不是 Glide（本计划不涉及，但不要顺手引入 Glide）。
- 日志里不要打印原始 Cookie 或 `msToken`。
- 每个任务结束都要 commit，commit message 用中文、遵循仓库现有的 `feat:` / `refactor:` / `docs:` 前缀风格。

## 文件结构

| 文件 | 状态 | 职责 |
|---|---|---|
| `app/build.gradle.kts` | 修改 | 新增 `androidx.exifinterface` 依赖 |
| `app/src/main/java/com/blitz/downloader/util/MediaOrientationProbe.kt` | 新建 | 读本地媒体文件的真实呈现宽高（含旋转 / EXIF 修正） |
| `app/src/main/java/com/blitz/downloader/model/MediaOrientation.kt` | 新建 | 方向枚举 + 由宽高派生的纯函数 |
| `app/src/main/java/com/blitz/downloader/data/db/DownloadedVideoEntity.kt` | 修改 | 新增 `mediaWidth` / `mediaHeight` 两列 |
| `app/src/main/java/com/blitz/downloader/data/db/DownloadedVideoDao.kt` | 修改 | 新增 `updateMediaSize` |
| `app/src/main/java/com/blitz/downloader/data/db/AppDatabase.kt` | 修改 | `MIGRATION_13_14`、`version = 14`、注册迁移 |
| `app/src/main/java/com/blitz/downloader/data/DownloadedVideoRepository.kt` | 修改 | `recordSuccessfulDownload` 加参数；新增 `updateMediaSize` |
| `app/src/main/java/com/blitz/downloader/download/DownloadService.kt` | 修改 | 写入时机一：下载落盘后探测 |
| `app/src/main/java/com/blitz/downloader/download/MediaExportManager.kt` | 修改 | `ExportFile` 新增 `orientation` 字段 |
| `app/src/main/java/com/blitz/downloader/net/LanFileServer.kt` | 修改 | `splitByOrientation` 开关、两条方向路由、首页分组渲染 |
| `app/src/main/java/com/blitz/downloader/viewmodel/ManageViewModel.kt` | 修改 | 写入时机二：导出前懒回填 + 进度状态 + 传 split 开关 |
| `app/src/main/java/com/blitz/downloader/activity/ManageActivity.kt` | 修改 | 回填进度对话框、对话框显示横/竖数量 |
| `app/src/main/res/values/strings.xml` | 修改 | 新增 3 条文案 |
| `CLAUDE.md` | 修改 | 「导出管道」与「持久化」段同步 |
| `.cursor/rules/db-schema.md` | 修改 | 新增两列与 v14 版本行 |

## 任务依赖顺序

```
Task 1 (MediaOrientation 枚举)  ──┐
Task 2 (MediaOrientationProbe)  ──┼──> Task 4 (DownloadService 写入)
Task 3 (DB v14)                 ──┘         │
                                             ├──> Task 6 (ManageViewModel 回填)
Task 5 (ExportFile.orientation) ─────────────┤
                                             │
Task 7 (LanFileServer 分包) ─────────────────┤
                                             └──> Task 8 (ManageActivity UI)
                                                        │
                                                        └──> Task 9 (文档)
```

---

### Task 1: `MediaOrientation` 方向枚举

方向是**派生值**，不落库。DB 只存原始事实（宽高），后续想按分辨率筛选/排序无需再加列。

**Files:**
- Create: `app/src/main/java/com/blitz/downloader/model/MediaOrientation.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `enum class MediaOrientation { LANDSCAPE, PORTRAIT }`
  - `MediaOrientation.Companion.of(width: Int, height: Int): MediaOrientation`

- [ ] **Step 1: 创建枚举文件**

创建 `app/src/main/java/com/blitz/downloader/model/MediaOrientation.kt`：

```kotlin
package com.blitz.downloader.model

/**
 * 媒体文件的画面方向。
 *
 * 这是个**派生值**，不落库：数据库只存原始事实
 * （[com.blitz.downloader.data.db.DownloadedVideoEntity.mediaWidth] /
 * [com.blitz.downloader.data.db.DownloadedVideoEntity.mediaHeight]），
 * 方向由 [of] 现算。这样以后想按分辨率筛选/排序也够用，不必再加列。
 *
 * 当前唯一消费方是局域网导出的分包（[com.blitz.downloader.net.LanFileServer]）。
 */
enum class MediaOrientation {
    LANDSCAPE,
    PORTRAIT,
    ;

    companion object {
        /**
         * `width > height` 才算横屏。
         *
         * 方形（1:1）、`0`（未探测或探测失败）、负数一律归 [PORTRAIT]——
         * 一个判断兜住三种边界，因此不需要「未知」哨兵值。
         * 抖音内容绝大多数是竖屏，把不确定的归进竖屏包也最符合直觉。
         */
        fun of(width: Int, height: Int): MediaOrientation =
            if (width > height) LANDSCAPE else PORTRAIT
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/blitz/downloader/model/MediaOrientation.kt
git commit -m "feat: 新增 MediaOrientation 方向枚举（由宽高派生，不落库）"
```

---

### Task 2: `MediaOrientationProbe` 本地文件探测

**这是整个需求里最容易翻车的一环。** 1920×1080 但带 90° 旋转元数据的视频，裸读宽高是横屏，播放器里却是竖的。必须读 `METADATA_KEY_VIDEO_ROTATION` 并在 90 / 270 时交换宽高。

**一份逻辑，两个调用点**（Task 4 的下载后、Task 6 的导出前），保证两个写入时机口径永远一致。

**Files:**
- Modify: `app/build.gradle.kts:63-80`（依赖块）
- Create: `app/src/main/java/com/blitz/downloader/util/MediaOrientationProbe.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `MediaOrientationProbe.Size(val width: Int, val height: Int)`
  - `MediaOrientationProbe.probe(file: File): Size?`

- [ ] **Step 1: 添加 ExifInterface 依赖**

在 `app/build.gradle.kts` 的 `dependencies { }` 块里，紧跟在 `implementation("androidx.fragment:fragment-ktx:1.8.5")` 之后加一行：

```kotlin
    implementation("androidx.exifinterface:exifinterface:1.3.7")
```

版本号直接写在 `app/build.gradle.kts` 里，与该文件里既有的零散依赖（cardview / viewpager2 / okhttp 等）风格一致，不进 `libs.versions.toml`。

用 androidx 版而非 `android.media.ExifInterface`，是因为后者已废弃、会触发 lint 警告，而 androidx 版体积极小且是官方推荐路径。

- [ ] **Step 2: 创建探测工具**

创建 `app/src/main/java/com/blitz/downloader/util/MediaOrientationProbe.kt`：

```kotlin
package com.blitz.downloader.util

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * 读本地媒体文件的**真实呈现宽高**（即播放器 / 图片查看器里看到的那个方向）。
 *
 * 之所以读文件而不用抖音接口下发的 `video.width/height`：
 * - 接口字段不保证已经过旋转修正，可能与实际呈现方向相反；
 * - 历史下载记录根本没有这个字段，读文件对新老数据一视同仁。
 *
 * **一份逻辑，两个调用点**——下载落盘后（[com.blitz.downloader.download.DownloadService]）
 * 与局域网导出前的懒回填（[com.blitz.downloader.viewmodel.ManageViewModel]）都走这里，
 * 保证同一个文件在两条路径上算出的方向永远一致。
 *
 * **所有方法必须在 IO 线程调用。**
 */
object MediaOrientationProbe {

    private const val TAG = "MediaOrientationProbe"

    /** 修正旋转后的呈现宽高。 */
    data class Size(val width: Int, val height: Int)

    private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "mkv", "webm", "3gp", "avi")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

    /**
     * 探测 [file] 的呈现宽高。
     *
     * 读不出一律返回 `null`（文件损坏、格式不支持、未知扩展名、底层抛异常）。
     * 调用方把 `null` 当作「未知」处理并保持宽高为 0，**不要**写哨兵值——
     * `0` 语义单一（不知道），不必区分「没探过」和「探过但失败」。
     */
    fun probe(file: File): Size? {
        if (!file.isFile) return null
        return when (file.extension.lowercase()) {
            in VIDEO_EXTENSIONS -> probeVideo(file)
            in IMAGE_EXTENSIONS -> probeImage(file)
            else -> null
        }
    }

    /**
     * 视频：读 `VIDEO_WIDTH` / `VIDEO_HEIGHT` / `VIDEO_ROTATION`。
     *
     * **旋转修正不能省。** 1920×1080 且带 90° 旋转元数据的视频，裸读宽高是横屏，
     * 但播放器里呈现为竖屏；不交换就会判反方向，整个分包功能失去意义。
     */
    private fun probeVideo(file: File): Size? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: return null
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: return null
            if (w <= 0 || h <= 0) return null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) Size(h, w) else Size(w, h)
        } catch (e: Exception) {
            Log.w(TAG, "probeVideo failed: ${file.name}", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 图片：`inJustDecodeBounds` 只读文件头、不解码像素（大图也不会 OOM）。
     * JPEG 再读 EXIF `TAG_ORIENTATION`，横置类同样交换宽高。
     */
    private fun probeImage(file: File): Size? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val w = options.outWidth
            val h = options.outHeight
            if (w <= 0 || h <= 0) return null
            if (isExifTransposed(file)) Size(h, w) else Size(w, h)
        } catch (e: Exception) {
            Log.w(TAG, "probeImage failed: ${file.name}", e)
            null
        }
    }

    /** EXIF 方向是否会把宽高对调（旋转 90/270 与两种转置）。读不到 EXIF 一律按「不对调」。 */
    private fun isExifTransposed(file: File): Boolean {
        return runCatching {
            val orientation = ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                orientation == ExifInterface.ORIENTATION_TRANSVERSE
        }.getOrDefault(false)
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/build.gradle.kts app/src/main/java/com/blitz/downloader/util/MediaOrientationProbe.kt
git commit -m "feat: 新增 MediaOrientationProbe，读本地文件的呈现宽高（含旋转/EXIF 修正）"
```

---

### Task 3: 数据库 v13 → v14，缓存宽高

**漏写迁移 = 用户数据被清空。** 这个任务的三处改动（entity 字段、migration、version 号）必须同时到位，缺一个就会在真机上炸。

**Files:**
- Modify: `app/src/main/java/com/blitz/downloader/data/db/DownloadedVideoEntity.kt`（文件末尾 `watched` 之后）
- Modify: `app/src/main/java/com/blitz/downloader/data/db/AppDatabase.kt:12`（version）、`:234-240` 之后（新迁移）、`:252-257`（注册）
- Modify: `app/src/main/java/com/blitz/downloader/data/db/DownloadedVideoDao.kt`（新增 `updateMediaSize`）
- Modify: `app/src/main/java/com/blitz/downloader/data/DownloadedVideoRepository.kt:115-151`

**Interfaces:**
- Consumes: 无
- Produces:
  - `DownloadedVideoEntity.mediaWidth: Int`、`DownloadedVideoEntity.mediaHeight: Int`（默认 0）
  - `DownloadedVideoDao.updateMediaSize(awemeId: String, width: Int, height: Int)`（suspend）
  - `DownloadedVideoRepository.updateMediaSize(awemeId: String, width: Int, height: Int)`（suspend）
  - `DownloadedVideoRepository.recordSuccessfulDownload(...)` 新增两个尾部具名参数 `mediaWidth: Int = 0`、`mediaHeight: Int = 0`

- [ ] **Step 1: Entity 新增两列**

在 `DownloadedVideoEntity.kt` 里，`val watched: Boolean = false,` 这一行**之后**（仍在构造参数列表内）追加：

```kotlin
    /**
     * 媒体文件的**呈现宽度**（像素，v14 新增）。`0` = 未知。
     *
     * 由 [com.blitz.downloader.util.MediaOrientationProbe] 读本地文件得出，已做旋转 / EXIF 修正，
     * **不是**抖音接口下发的 `video.width`——接口值不保证含旋转修正，且历史记录根本没有。
     *
     * 两个写入时机：
     * 1. 下载落盘后，由 [com.blitz.downloader.download.DownloadService] 随写库一并写入；
     * 2. 局域网导出前（仅视频 Tab），由 [com.blitz.downloader.viewmodel.ManageViewModel]
     *    对 `mediaWidth == 0` 的记录懒探测并回填。
     *
     * 探测失败**保持 0，不写哨兵值**：下次导出会再探一次（几毫秒），换来 `0` 语义单一。
     * 消费方是 [com.blitz.downloader.model.MediaOrientation.of]，`0` 会被判为竖屏。
     * 旧记录默认 0，不做历史批量回填。
     */
    val mediaWidth: Int = 0,
    /** 媒体文件的**呈现高度**（像素，v14 新增）。`0` = 未知，语义与 [mediaWidth] 完全一致。 */
    val mediaHeight: Int = 0,
```

- [ ] **Step 2: 新增迁移**

在 `AppDatabase.kt` 里，`MIGRATION_12_13` 的定义块**之后**、`@Volatile private var instance` **之前**插入：

```kotlin
        /**
         * v13 → v14：新增 `mediaWidth` / `mediaHeight`（媒体呈现宽高，用于局域网导出分横屏/竖屏包）。
         * 旧记录默认 0（未知），不做历史回填——导出时按需懒探测。
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE downloaded_videos ADD COLUMN mediaWidth INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE downloaded_videos ADD COLUMN mediaHeight INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
```

- [ ] **Step 3: 提升版本号并注册迁移**

`AppDatabase.kt:12` 把 `version = 13,` 改为：

```kotlin
    version = 14,
```

同文件 `addMigrations(...)` 调用里，把 `MIGRATION_12_13,` 那一行改为：

```kotlin
                        MIGRATION_12_13, MIGRATION_13_14,
```

- [ ] **Step 4: Dao 新增更新方法**

在 `DownloadedVideoDao.kt` 里，`data class AuthorCount(...)` **之前**（即接口的最后一个 `@Query` 之后）插入：

```kotlin
    /**
     * 回填媒体呈现宽高（v14）。只更新这两列，不动其他字段——
     * 懒回填发生在导出流程中，不该顺带覆盖用户可能刚改过的标签计数等。
     */
    @Query("UPDATE downloaded_videos SET mediaWidth = :width, mediaHeight = :height WHERE awemeId = :awemeId")
    suspend fun updateMediaSize(awemeId: String, width: Int, height: Int)
```

- [ ] **Step 5: Repository 传参与新方法**

在 `DownloadedVideoRepository.kt` 的 `recordSuccessfulDownload` 参数列表里，`collectCount: Long = 0L,` **之后**追加两个参数：

```kotlin
        mediaWidth: Int = 0,
        mediaHeight: Int = 0,
```

在同一函数体的 `DownloadedVideoEntity(...)` 构造里，`collectCount = collectCount,` **之后**追加：

```kotlin
                mediaWidth = mediaWidth,
                mediaHeight = mediaHeight,
```

在 `recordSuccessfulDownload` 函数**之后**、`companion object` **之前**插入：

```kotlin
    /**
     * 回填媒体呈现宽高（v14），供局域网导出前的懒探测使用。
     * 只更新这两列，不整行覆盖。
     */
    suspend fun updateMediaSize(awemeId: String, width: Int, height: Int) {
        dao.updateMediaSize(awemeId, width, height)
    }
```

- [ ] **Step 6: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL。KSP 会重新生成 Room 代码；若报「Schema export directory is not provided」之类的既有警告可忽略，但**任何 Migration 相关的 error 都必须解决**。

- [ ] **Step 7: 迁移自检（人工核对，不跑测试）**

逐条确认：
1. `AppDatabase` 的 `version` 是 `14`；
2. `MIGRATION_13_14` 已定义且 `Migration(13, 14)` 的两个数字正确；
3. `addMigrations(...)` 的参数列表里出现了 `MIGRATION_13_14`；
4. Entity 新增的两个字段名与 SQL 里的列名**逐字符一致**（`mediaWidth` / `mediaHeight`）。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/blitz/downloader/data/
git commit -m "feat: DB v14 新增 mediaWidth/mediaHeight，缓存媒体呈现宽高"
```

---

### Task 4: 写入时机一 —— 下载落盘后探测

**Files:**
- Modify: `app/src/main/java/com/blitz/downloader/download/DownloadService.kt:1-27`（imports）、`:107-129`（写库块）

**Interfaces:**
- Consumes: `MediaOrientationProbe.probe(file: File): Size?`（Task 2）、`DownloadedVideoRepository.recordSuccessfulDownload(..., mediaWidth, mediaHeight)`（Task 3）
- Produces: 无（行为改动）

- [ ] **Step 1: 补充 import**

在 `DownloadService.kt` 顶部 import 块中加入（保持字母序，`android.os.IBinder` 之后、`android.util.Log` 之前插 `Environment`；`com.blitz.downloader.model.VideoItemUiModel` 之后插 probe；`java.util.concurrent...` 之前插 `File`）：

```kotlin
import android.os.Environment
import com.blitz.downloader.util.MediaOrientationProbe
import java.io.File
```

- [ ] **Step 2: 写库前插入探测**

在 `DownloadService.processJob` 的 `result.succeededItems.forEach { item -> ... }` 块内，把

```kotlin
            val meta = job.metas[item.id] ?: return@forEach
            repo.recordSuccessfulDownload(
```

改为：

```kotlin
            val meta = job.metas[item.id] ?: return@forEach
            // 落盘后立刻探测呈现宽高并随写库一并存下（v14）。
            // filePath 是相对 Environment.getExternalStorageDirectory() 的路径，
            // 与 MediaExportManager.resolveExportFiles 的拼接方式保持一致。
            // 图集的 filePath 指向首图，即探测首图。
            val relPath = result.succeededPaths[item.id].orEmpty()
            @Suppress("DEPRECATION")
            val size = relPath.takeIf { it.isNotBlank() }?.let { p ->
                MediaOrientationProbe.probe(File(Environment.getExternalStorageDirectory(), p))
            }
            repo.recordSuccessfulDownload(
```

- [ ] **Step 3: 传入新参数**

在同一个 `repo.recordSuccessfulDownload(...)` 调用里，把最后一行

```kotlin
                collectCount = meta.collectCount,
            )
```

改为：

```kotlin
                collectCount = meta.collectCount,
                mediaWidth = size?.width ?: 0,
                mediaHeight = size?.height ?: 0,
            )
```

`DownloadService` 的协程作用域是 `Dispatchers.Default`（不是 IO）；`BatchDownloadCoordinator.downloadSelected` 内部虽有自己的 `withContext(Dispatchers.IO)`，但函数返回后协程已经切回 `Default`。因此这段写库循环必须显式包一层 `withContext(Dispatchers.IO)`，`MediaOrientationProbe` 的「必须在 IO 线程调用」要求才算满足。

`recordSuccessfulDownload` 在全仓库只有这一个调用点，不存在多处等价性问题。

- [ ] **Step 4: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 真机验证（有设备时）**

Run: `./gradlew installDebug`

操作：从列表页下载 1 个视频 → 进管理页确认记录出现。
用 `adb shell` 查库确认宽高已写入：

```bash
adb shell "run-as com.blitz.downloader sqlite3 databases/blitz_downloader.db 'SELECT awemeId, mediaWidth, mediaHeight FROM downloaded_videos ORDER BY createdAtMillis DESC LIMIT 3;'"
```

Expected: 最新一条的 `mediaWidth` / `mediaHeight` 均 > 0。
（数据库文件名以 `AppDatabase.DB_NAME` 常量为准；若 `run-as` 不可用，跳过本步，改由 Task 6 完成后在 UI 上看「横屏 N · 竖屏 M」验证。）

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/blitz/downloader/download/DownloadService.kt
git commit -m "feat: 下载落盘后探测并写入媒体呈现宽高"
```

---

### Task 5: `ExportFile` 携带方向

`resolveExportFiles` **保持纯粹——只读 entity 字段，不做任何探测 IO**。回填是 ViewModel 在调用它之前的职责（Task 6）。

**Files:**
- Modify: `app/src/main/java/com/blitz/downloader/download/MediaExportManager.kt:1-16`（imports）、`:39-47`（`ExportFile`）、`:63-78`（`resolveExportFiles`）

**Interfaces:**
- Consumes: `MediaOrientation.of(width, height)`（Task 1）、`DownloadedVideoEntity.mediaWidth` / `.mediaHeight`（Task 3）
- Produces: `MediaExportManager.ExportFile.orientation: MediaOrientation`

- [ ] **Step 1: 补充 import**

在 `MediaExportManager.kt` 的 import 块中，`import com.blitz.downloader.data.db.DownloadedVideoEntity` **之后**加入：

```kotlin
import com.blitz.downloader.model.MediaOrientation
```

- [ ] **Step 2: `ExportFile` 新增字段**

把

```kotlin
    data class ExportFile(val file: File, val entryName: String, val awemeId: String)
```

改为：

```kotlin
    data class ExportFile(
        val file: File,
        val entryName: String,
        val awemeId: String,
        /**
         * 所属记录的画面方向，由 [DownloadedVideoEntity.mediaWidth] / `mediaHeight` 派生。
         *
         * 只有局域网导出的分包用它（[com.blitz.downloader.net.LanFileServer]）；
         * ZIP 导出拿到但不使用。图集解析出的多个文件**共享所属记录的方向**——
         * 图片 Tab 不参与分包，无需逐张判定。
         *
         * 宽高为 0（未探测 / 探测失败）时按 [MediaOrientation.PORTRAIT] 处理。
         */
        val orientation: MediaOrientation,
    )
```

- [ ] **Step 3: `resolveExportFiles` 填入方向**

在 `resolveExportFiles` 的循环里，把

```kotlin
            val files = if (e.mediaType.equals("image", ignoreCase = true)) findImageSet(first) else listOf(first)
            for (f in files) {
                if (!f.isFile) continue
                out.add(ExportFile(f, uniqueName(f.name, usedNames), e.awemeId))
            }
```

改为：

```kotlin
            val files = if (e.mediaType.equals("image", ignoreCase = true)) findImageSet(first) else listOf(first)
            // 方向只读 entity 字段，不在这里做探测 IO——回填是调用方（ManageViewModel）的职责。
            val orientation = MediaOrientation.of(e.mediaWidth, e.mediaHeight)
            for (f in files) {
                if (!f.isFile) continue
                out.add(ExportFile(f, uniqueName(f.name, usedNames), e.awemeId, orientation))
            }
```

- [ ] **Step 4: 更新 `resolveExportFiles` 的 KDoc**

把该函数 KDoc 的最后一行**连同它下面的 `*/` 一起**，即这两行：

```kotlin
     * [ExportFile.entryName] 已做同名去重（追加 `_2` / `_3` …），可直接用作 ZIP 条目名或下载文件名。
     */
```

替换为这四行：

```kotlin
     * [ExportFile.entryName] 已做同名去重（追加 `_2` / `_3` …），可直接用作 ZIP 条目名或下载文件名。
     *
     * [ExportFile.orientation] 直接由记录的 `mediaWidth` / `mediaHeight` 派生，**本函数不做探测 IO**。
     * 需要准确方向的调用方（局域网导出）应在调用前完成宽高回填。
     */
```

替换后确认整个 KDoc 只有一个 `*/`。

- [ ] **Step 5: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL。若 `LanFileServer` 或 `ManageViewModel` 因构造 `ExportFile` 报参数不足，说明有遗漏的构造点，一并补上 `orientation`。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/blitz/downloader/download/MediaExportManager.kt
git commit -m "feat: ExportFile 携带画面方向，供局域网导出分包使用"
```

---

### Task 6: 写入时机二 —— 导出前懒回填 + 进度状态

**回填后必须同步更新内存里那份 `entities`**——紧接着的 `resolveExportFiles` 读的是内存对象，不是数据库。只写库不更新内存，本次导出会全部落进竖屏包。

**懒回填只在视频 Tab 执行。** 图片 Tab 不分包，探测对本次导出毫无用处，白白让用户等几秒。

**Files:**
- Modify: `app/src/main/java/com/blitz/downloader/viewmodel/ManageViewModel.kt:1-31`（imports）、`:317-362`（局域网导出段）、`:459-466`（`LanExportState`）

**Interfaces:**
- Consumes: `MediaOrientationProbe.probe`（Task 2）、`MediaOrientation.of`（Task 1）、`DownloadedVideoRepository.updateMediaSize`（Task 3）、`MediaExportManager.ExportFile.orientation`（Task 5）、`LanFileServer(files, splitByOrientation, onTransfer)`（Task 7 —— 本任务先按新签名写，Task 7 补上实现；两任务必须连续完成才编译得过，如需分开验证请先做 Task 7）
- Produces:
  - `ManageViewModel.lanPreparing: StateFlow<LanPrepareProgress?>`
  - `data class LanPrepareProgress(val done: Int, val total: Int)`
  - `LanExportState.landscapeCount: Int`、`.portraitCount: Int`、`.splitByOrientation: Boolean`

> **执行顺序提示：** Task 6 与 Task 7 互相引用（本任务调用新的 `LanFileServer` 构造签名）。建议**先完成 Task 7 再做 Task 6**，或把两者当成一次连续改动、在 Task 7 结束后统一编译。计划按依赖顺序列在 Task 7 之前是为了叙述连贯，执行时以能编译为准。

- [ ] **Step 1: 补充 import**

在 `ManageViewModel.kt` 的 import 块中加入（保持字母序）：

```kotlin
import com.blitz.downloader.model.MediaOrientation
import com.blitz.downloader.util.MediaOrientationProbe
```

（`java.io.File` 与 `android.os.Environment` 该文件已有，无需重复导入。）

- [ ] **Step 2: 新增回填进度状态**

在 `ManageViewModel` 的「局域网导出」段里，`private var lanServer: LanFileServer? = null` **之后**插入：

```kotlin
    private val _lanPreparing = MutableStateFlow<LanPrepareProgress?>(null)

    /**
     * 非 null 表示正在做导出前的宽高回填探测（v14）。
     *
     * 只在**确有待探测项**时非 null。历史记录第一次导出可能要探几百个文件、耗时数秒，
     * 没有进度提示界面会像卡死；之后是纯读库，这个状态不会再出现。
     */
    val lanPreparing: StateFlow<LanPrepareProgress?> = _lanPreparing.asStateFlow()
```

- [ ] **Step 3: 新增回填私有方法**

在 `startLanExport` **之前**插入：

```kotlin
    /**
     * 导出前的宽高懒回填（v14）：对 `mediaWidth == 0` 的记录探测本地文件，写库并**同步更新内存副本**。
     *
     * 返回回填后的实体列表——`resolveExportFiles` 读的是内存对象而不是数据库，
     * 只写库不更新内存会让本次导出全部落进竖屏包。
     *
     * 探测失败保持 0（归竖屏），**不写哨兵值**：下次导出再探一次，代价几毫秒，
     * 换来 `0` 语义单一（只表示「不知道」）。写库失败也不影响本次导出（内存里已是探测结果）。
     *
     * **必须在 IO 线程调用。**
     */
    private suspend fun backfillMediaSizes(entities: List<DownloadedVideoEntity>): List<DownloadedVideoEntity> {
        val pending = entities.filter { it.mediaWidth == 0 && it.filePath.isNotBlank() }
        if (pending.isEmpty()) return entities

        _lanPreparing.value = LanPrepareProgress(done = 0, total = pending.size)
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory()
        val probed = HashMap<String, MediaOrientationProbe.Size>(pending.size)
        pending.forEachIndexed { index, e ->
            MediaOrientationProbe.probe(File(root, e.filePath))?.let { size ->
                probed[e.awemeId] = size
                runCatching { repo.updateMediaSize(e.awemeId, size.width, size.height) }
            }
            _lanPreparing.value = LanPrepareProgress(done = index + 1, total = pending.size)
        }
        _lanPreparing.value = null

        if (probed.isEmpty()) return entities
        return entities.map { e ->
            probed[e.awemeId]?.let { e.copy(mediaWidth = it.width, mediaHeight = it.height) } ?: e
        }
    }
```

- [ ] **Step 4: 改写 `startLanExport`**

把整个 `startLanExport` 函数替换为：

```kotlin
    fun startLanExport(tab: Int, entities: List<DownloadedVideoEntity>) {
        val ip = LanFileServer.localIpv4()
        if (ip == null) {
            _lanError.tryEmit(LanStartFailure.NoWifi)
            return
        }
        // 只有视频 Tab 分横屏/竖屏包；图片 Tab 的行为与改动前完全一致。
        val split = tab == TAB_VIDEO
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) {
                // 图片 Tab 不分包，探测对本次导出毫无用处，直接跳过回填。
                val resolved = if (split) backfillMediaSizes(entities) else entities
                MediaExportManager.resolveExportFiles(resolved)
            }
            if (files.isEmpty()) {
                _lanError.tryEmit(LanStartFailure.NothingToExport)
                return@launch
            }
            stopLanExport() // 若已有服务在跑，先停掉
            val server = LanFileServer(files, split) { event -> onLanTransferComplete(tab, event) }
            val port = try {
                server.start()
            } catch (e: Exception) {
                _lanError.tryEmit(LanStartFailure.StartFailed(e.message ?: e.javaClass.simpleName))
                return@launch
            }
            lanServer = server
            _lanState.value = LanExportState(
                url = "http://$ip:$port/",
                fileCount = files.size,
                splitByOrientation = split,
                landscapeCount = if (split) files.count { it.orientation == MediaOrientation.LANDSCAPE } else 0,
                portraitCount = if (split) files.count { it.orientation == MediaOrientation.PORTRAIT } else 0,
            )
        }
    }
```

- [ ] **Step 5: `stopLanExport` 清理回填进度**

把 `stopLanExport` 替换为：

```kotlin
    fun stopLanExport() {
        lanServer?.stop()
        lanServer = null
        _lanState.value = null
        _lanPreparing.value = null
    }
```

- [ ] **Step 6: 扩展 `LanExportState` 并新增 `LanPrepareProgress`**

把文件末尾的 `LanExportState` 定义替换为：

```kotlin
data class LanExportState(
    val url: String,
    val fileCount: Int,
    /** 是否按横屏/竖屏分包（仅视频 Tab 为 true）。 */
    val splitByOrientation: Boolean = false,
    /** 横屏文件数；[splitByOrientation] 为 false 时恒为 0。 */
    val landscapeCount: Int = 0,
    /** 竖屏文件数；[splitByOrientation] 为 false 时恒为 0。 */
    val portraitCount: Int = 0,
    val transferCount: Int = 0,
    val lastTransfer: Transfer? = null,
) {
    data class Transfer(val isZip: Boolean, val label: String, val itemCount: Int)
}

/** 局域网导出前的宽高回填探测进度（v14）。 */
data class LanPrepareProgress(val done: Int, val total: Int)
```

注意：`splitByOrientation` / `landscapeCount` / `portraitCount` 插在 `fileCount` 之后、`transferCount` 之前。`onLanTransferComplete` 里用的是 `current.copy(transferCount = ..., lastTransfer = ...)` 具名参数形式，不受字段顺序影响，无需改动。

- [ ] **Step 7: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL（**前提是 Task 7 已完成**，否则 `LanFileServer(files, split) { ... }` 会报参数不匹配）。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/blitz/downloader/viewmodel/ManageViewModel.kt
git commit -m "feat: 局域网导出前懒回填媒体宽高，视频 Tab 启用分包"
```

---

### Task 7: `LanFileServer` 分包路由与首页分组渲染

三条打包路由**共用同一份流式打包实现**，不复制代码。既有语义（`Deflater.NO_COMPRESSION`、中断即整包不计数、`sentIds` 收集、`HEAD` 不计数）全部原样保留。

**Files:**
- Modify: `app/src/main/java/com/blitz/downloader/net/LanFileServer.kt:1-19`（imports）、`:36-39`（构造）、`:145-151`（路由分派）、`:159-202`（首页）、`:234-273`（打包）、`:357-366`（常量）

**Interfaces:**
- Consumes: `MediaExportManager.ExportFile.orientation`（Task 5）、`MediaOrientation`（Task 1）
- Produces:
  - `LanFileServer(files: List<ExportFile>, splitByOrientation: Boolean = false, onTransfer: ((TransferEvent) -> Unit)? = null)`
  - `LanFileServer.ZIP_LABEL_ALL` / `ZIP_LABEL_LANDSCAPE` / `ZIP_LABEL_PORTRAIT`

- [ ] **Step 1: 补充 import**

在 `LanFileServer.kt` 的 import 块中，`import com.blitz.downloader.download.MediaExportManager` **之后**加入：

```kotlin
import com.blitz.downloader.model.MediaOrientation
```

- [ ] **Step 2: 构造参数新增分包开关**

把类声明替换为：

```kotlin
class LanFileServer(
    private val files: List<MediaExportManager.ExportFile>,
    /**
     * 是否按画面方向分包（横屏 / 竖屏）。
     *
     * 只有管理页**视频 Tab** 的导出传 `true`：图片 Tab 不分包，
     * `false` 时两条方向路由一律 404、首页不渲染方向按钮也不分组，
     * 行为与本功能上线前逐字节一致。
     */
    private val splitByOrientation: Boolean = false,
    private val onTransfer: ((TransferEvent) -> Unit)? = null,
) {
```

同时在类的 KDoc 里，把路由说明那一行

```kotlin
 * - 路由：`/` 列表页；`/f?i=N` 下载第 N 个文件；`/all.zip` 流式打包全部文件。
```

改为：

```kotlin
 * - 路由：`/` 列表页；`/f?i=N` 下载第 N 个文件；`/all.zip` 流式打包全部文件；
 *   开启 [splitByOrientation] 时另有 `/landscape.zip`、`/portrait.zip` 按画面方向分包。
```

- [ ] **Step 3: 新增方向子集懒加载属性**

在 `@Volatile private var running = false` **之前**插入：

```kotlin
    /** 横屏子集；[splitByOrientation] 为 false 时恒为空。 */
    private val landscapeFiles: List<MediaExportManager.ExportFile> by lazy {
        if (splitByOrientation) files.filter { it.orientation == MediaOrientation.LANDSCAPE } else emptyList()
    }

    /** 竖屏子集；[splitByOrientation] 为 false 时恒为空。 */
    private val portraitFiles: List<MediaExportManager.ExportFile> by lazy {
        if (splitByOrientation) files.filter { it.orientation == MediaOrientation.PORTRAIT } else emptyList()
    }
```

- [ ] **Step 4: 路由分派新增两条**

把 `handle` 里的 `when` 块替换为：

```kotlin
        when {
            path == "/" -> serveIndex(out, writeBody)
            path == "/all.zip" -> serveZip(out, writeBody, files, "bDouyin_export.zip", ZIP_LABEL_ALL)
            path == "/landscape.zip" -> serveOrientationZip(
                out, writeBody, landscapeFiles, "bDouyin_export_landscape.zip", ZIP_LABEL_LANDSCAPE,
            )
            path == "/portrait.zip" -> serveOrientationZip(
                out, writeBody, portraitFiles, "bDouyin_export_portrait.zip", ZIP_LABEL_PORTRAIT,
            )
            path == "/f" -> serveFile(out, parseIntParam(query, "i"), writeBody)
            else -> writeText(out, "404 Not Found", "not found")
        }
```

- [ ] **Step 5: 新增方向包入口（含 404 守卫）**

在 `serveZip` 函数**之前**插入：

```kotlin
    /**
     * 方向包入口。未开启分包、或该方向一个文件都没有时返回 404——
     * 首页本就不渲染空方向的按钮，正常操作点不到，直接访问 URL 时明确报 404 比返回空 zip 更诚实。
     */
    private fun serveOrientationZip(
        out: OutputStream,
        writeBody: Boolean,
        subset: List<MediaExportManager.ExportFile>,
        downloadName: String,
        label: String,
    ) {
        if (!splitByOrientation || subset.isEmpty()) {
            writeText(out, "404 Not Found", "not found")
            return
        }
        serveZip(out, writeBody, subset, downloadName, label)
    }
```

- [ ] **Step 6: `serveZip` 改为接受子集**

把整个 `serveZip` 函数替换为：

```kotlin
    /**
     * 流式打包 [subset] 并写出。三条打包路由（`/all.zip`、`/landscape.zip`、`/portrait.zip`）
     * 共用本实现，语义完全一致。
     *
     * @param downloadName 浏览器另存为的文件名。
     * @param label        [TransferEvent.label]，用于手机端状态行展示。
     */
    private fun serveZip(
        out: OutputStream,
        writeBody: Boolean,
        subset: List<MediaExportManager.ExportFile>,
        downloadName: String,
        label: String,
    ) {
        // zip 长度未知：不发 Content-Length，靠 Connection: close 通知浏览器传输结束。
        writeHeader(
            out, "200 OK",
            linkedMapOf(
                "Content-Type" to "application/zip",
                "Content-Disposition" to contentDisposition(downloadName),
                "Connection" to "close",
            ),
        )
        if (!writeBody) {
            out.flush()
            return
        }
        val zos = ZipOutputStream(out)
        zos.setLevel(Deflater.NO_COMPRESSION) // 视频/图片已压缩，仅打包
        // 整包语义：任一条目失败（客户端断开等）即整包不可用，不计数
        val sentIds = LinkedHashSet<String>()
        var aborted = false
        for (ef in subset) {
            if (!ef.file.isFile) continue
            try {
                zos.putNextEntry(ZipEntry(ef.entryName))
                FileInputStream(ef.file).use { it.copyTo(zos, STREAM_BUFFER) }
                zos.closeEntry()
                sentIds.add(ef.awemeId)
            } catch (e: Exception) {
                Log.w(TAG, "zip entry failed: ${ef.entryName}", e)
                aborted = true
                break // 客户端断开等：停止即可
            }
        }
        val finished = runCatching {
            zos.finish()
            out.flush()
        }.isSuccess
        if (!aborted && finished && sentIds.isNotEmpty()) {
            notifyTransfer(TransferEvent(sentIds, label, isZip = true))
        }
    }
```

- [ ] **Step 7: 常量拆成三个**

把 companion object 里的

```kotlin
        /** 整包传输事件的展示名。 */
        const val ZIP_LABEL = "all.zip"
```

替换为：

```kotlin
        /** `/all.zip` 传输事件的展示名。 */
        const val ZIP_LABEL_ALL = "all.zip"

        /** `/landscape.zip` 传输事件的展示名。 */
        const val ZIP_LABEL_LANDSCAPE = "landscape.zip"

        /** `/portrait.zip` 传输事件的展示名。 */
        const val ZIP_LABEL_PORTRAIT = "portrait.zip"
```

- [ ] **Step 8: 检查 `ZIP_LABEL` 的其他引用**

Run: `grep -rn "ZIP_LABEL" app/src/main/java/`
Expected: 只在 `LanFileServer.kt` 内出现（三处定义 + 三处路由使用）。若 `ManageViewModel` 或 `ManageActivity` 引用了旧的 `ZIP_LABEL`，改为 `ZIP_LABEL_ALL`。

- [ ] **Step 9: 首页样式补充**

在 `serveIndex` 的 `<style>` 拼接里，把

```kotlin
        sb.append("ul{list-style:none;padding:0;margin:0}")
```

改为（在其前面插入方向按钮与分组标题的样式）：

```kotlin
        sb.append(".splitrow{display:flex;gap:10px;margin-bottom:16px}")
        sb.append(".splitbtn{flex:1;text-align:center;background:#fff;color:#0071e3;text-decoration:none;")
        sb.append("padding:12px 8px;border-radius:12px;font-size:15px;border:1px solid #d2d2d7}")
        sb.append(".splitbtn .sub{display:block;color:#8e8e93;font-size:12px;margin-top:2px}")
        sb.append("h2{font-size:13px;color:#6e6e73;font-weight:600;margin:18px 0 8px;")
        sb.append("text-transform:none;letter-spacing:.02em}")
        sb.append("ul{list-style:none;padding:0;margin:0}")
```

- [ ] **Step 10: 首页正文改为条件分组渲染**

把 `serveIndex` 中从 `if (files.isNotEmpty()) {` 到 `sb.append("</ul></div></body></html>")` 这一整段替换为：

```kotlin
        if (files.isNotEmpty()) {
            sb.append("<a class=\"allbtn\" href=\"/all.zip\">⬇ 打包下载全部 (zip)</a>")
        }
        if (splitByOrientation) {
            // 方向按钮：数量为 0 的那个不渲染（对应路由也会 404）
            val hasAny = landscapeFiles.isNotEmpty() || portraitFiles.isNotEmpty()
            if (hasAny) {
                sb.append("<div class=\"splitrow\">")
                appendSplitButton(sb, "/landscape.zip", "⬇ 横屏", landscapeFiles)
                appendSplitButton(sb, "/portrait.zip", "⬇ 竖屏", portraitFiles)
                sb.append("</div>")
            }
            appendGroup(sb, "横屏", landscapeFiles)
            appendGroup(sb, "竖屏", portraitFiles)
        } else {
            sb.append("<ul>")
            files.forEach { ef -> appendFileItem(sb, ef) }
            sb.append("</ul>")
        }
        sb.append("</div></body></html>")
```

- [ ] **Step 11: 新增三个渲染辅助方法**

在 `serveIndex` 函数**之后**、`serveFile` **之前**插入：

```kotlin
    /** 方向包按钮；该方向没有文件时不渲染（对应路由也会 404）。 */
    private fun appendSplitButton(
        sb: StringBuilder,
        href: String,
        title: String,
        subset: List<MediaExportManager.ExportFile>,
    ) {
        if (subset.isEmpty()) return
        val bytes = subset.sumOf { it.file.length() }
        sb.append("<a class=\"splitbtn\" href=\"").append(href).append("\">")
        sb.append(escapeHtml(title)).append(" ").append(subset.size).append(" 个")
        sb.append("<span class=\"sub\">").append(humanSize(bytes)).append("</span></a>")
    }

    /** 一个方向分组：小标题 + 文件列表；空分组整个跳过。 */
    private fun appendGroup(
        sb: StringBuilder,
        title: String,
        subset: List<MediaExportManager.ExportFile>,
    ) {
        if (subset.isEmpty()) return
        sb.append("<h2>── ").append(escapeHtml(title)).append(" (").append(subset.size).append(") ──</h2>")
        sb.append("<ul>")
        subset.forEach { ef -> appendFileItem(sb, ef) }
        sb.append("</ul>")
    }

    /**
     * 单个文件条目。
     *
     * `/f?i=N` 的 `N` 是该文件在**完整 [files] 列表**中的下标，分组渲染时不重排索引——
     * 否则分组会把单文件下载链接指到错误的文件上。
     */
    private fun appendFileItem(sb: StringBuilder, ef: MediaExportManager.ExportFile) {
        val index = files.indexOf(ef)
        sb.append("<li><a class=\"file\" href=\"/f?i=$index\">")
        sb.append(escapeHtml(ef.entryName))
        sb.append("</a><span class=\"size\">").append(humanSize(ef.file.length())).append("</span></li>")
    }
```

`files.indexOf(ef)` 依赖 `ExportFile` 是 data class（结构相等）。同一次导出内 `entryName` 已做去重，`file` 路径也唯一，因此不会出现两个相等的 `ExportFile` 导致索引指错。

- [ ] **Step 12: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 13: 提交**

```bash
git add app/src/main/java/com/blitz/downloader/net/LanFileServer.kt
git commit -m "feat: LanFileServer 支持按横屏/竖屏分包，首页分组渲染"
```

---

### Task 8: 手机端对话框 —— 回填进度 + 横竖数量

**Files:**
- Modify: `app/src/main/res/values/strings.xml:224-228` 附近
- Modify: `app/src/main/res/layout/dialog_lan_export.xml`（在 `tvLanHint` 之后插一个 TextView）
- Modify: `app/src/main/java/com/blitz/downloader/activity/ManageActivity.kt:79-81`（字段）、`:184-187`（collect）、`:755-782`（`showLanDialog`）、`:788-795`（`onDestroy`）

**Interfaces:**
- Consumes: `ManageViewModel.lanPreparing: StateFlow<LanPrepareProgress?>`、`LanExportState.splitByOrientation` / `.landscapeCount` / `.portraitCount`（Task 6）
- Produces: 无

- [ ] **Step 1: 新增文案**

在 `app/src/main/res/values/strings.xml` 里，`manage_export_lan_status_note` 那一行**之后**插入：

```xml
    <string name="manage_export_lan_split_hint">已按方向分包：横屏 %1$d 个 · 竖屏 %2$d 个。电脑上可分别下载，也可一次打包全部。</string>
    <string name="manage_export_lan_preparing">正在识别视频方向…</string>
```

- [ ] **Step 2: 布局新增一行**

在 `app/src/main/res/layout/dialog_lan_export.xml` 里，`tvLanHint` 那个 `<TextView>` **之后**、`tvLanUrl` **之前**插入：

```xml
    <TextView
        android:id="@+id/tvLanSplit"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:textSize="13sp"
        android:visibility="gone"
        android:textColor="?android:attr/textColorSecondary" />
```

- [ ] **Step 3: Activity 新增进度对话框字段**

在 `ManageActivity` 里，`private var lanStatusView: TextView? = null` **之后**插入：

```kotlin
    private var lanPrepareDialog: ProgressDialog? = null
```

（`ProgressDialog` 该文件已 import，`zipProgressDialog` 用的就是它。）

- [ ] **Step 4: 收集回填进度**

在 `repeatOnLifecycle` 块里，`launch { viewModel.lanError.collect { onLanStartFailed(it) } }` **之后**插入：

```kotlin
                launch { viewModel.lanPreparing.collect { renderLanPreparing(it) } }
```

- [ ] **Step 5: 新增回填进度渲染**

在 `renderLanState` 函数**之前**插入：

```kotlin
    /**
     * 导出前的宽高回填探测进度（v14）。历史记录首次导出可能要探几百个文件、耗时数秒，
     * 没有提示界面会像卡死；之后是纯读库，这个对话框不会再出现。
     */
    @Suppress("DEPRECATION")
    private fun renderLanPreparing(progress: LanPrepareProgress?) {
        if (progress == null) {
            lanPrepareDialog?.dismiss()
            lanPrepareDialog = null
            return
        }
        val dialog = lanPrepareDialog ?: ProgressDialog(this).apply {
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setMessage(getString(R.string.manage_export_lan_preparing))
            setCancelable(false)
            isIndeterminate = false
            show()
            lanPrepareDialog = this
        }
        dialog.max = progress.total
        dialog.progress = progress.done
    }
```

- [ ] **Step 6: 补充 import**

在 `ManageActivity.kt` 的 import 块中，`import com.blitz.downloader.viewmodel.LanExportState` **之后**加入：

```kotlin
import com.blitz.downloader.viewmodel.LanPrepareProgress
```

- [ ] **Step 7: 对话框显示横竖数量**

在 `showLanDialog` 里，把

```kotlin
        content.findViewById<TextView>(R.id.tvLanUrl).text = state.url
```

**之前**插入：

```kotlin
        content.findViewById<TextView>(R.id.tvLanSplit).apply {
            if (state.splitByOrientation && (state.landscapeCount > 0 || state.portraitCount > 0)) {
                text = getString(
                    R.string.manage_export_lan_split_hint,
                    state.landscapeCount,
                    state.portraitCount,
                )
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
```

若 `android.view.View` 未在该文件 import，补上 `import android.view.View`（先 `grep -n "^import android.view" app/src/main/java/com/blitz/downloader/activity/ManageActivity.kt` 确认）。

- [ ] **Step 8: `onDestroy` 清理**

在 `ManageActivity.onDestroy` 里，`zipProgressDialog = null` **之后**插入：

```kotlin
        lanPrepareDialog?.dismiss()
        lanPrepareDialog = null
```

- [ ] **Step 9: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: 真机端到端验证**

Run: `./gradlew installDebug`

在设备上依次验证：

1. **视频 Tab 分包**：管理页视频 Tab → 多选若干视频（最好横竖都有）→ 菜单「发送到电脑」→ 首次会看到「正在识别视频方向…」进度条 → 对话框显示「已按方向分包：横屏 N 个 · 竖屏 M 个」。
2. **电脑端页面**：同 WiFi 的电脑浏览器打开对话框上的网址 → 应看到「打包下载全部」按钮 + 横屏/竖屏两个按钮（数量为 0 的那个不出现）+ 文件列表按方向分两组。
3. **分包内容正确**：点「横屏」下载，解压后确认里面都是横屏视频；「竖屏」同理。
4. **单文件链接不错位**：在分组列表里点某个具体文件名，下载下来的应该正是那个文件（验证 `/f?i=N` 索引没被分组打乱）。
5. **导出计数**：下载完某个方向包后，回手机管理页多选态，包内视频封面左上角应出现「已导出」标记。
6. **图片 Tab 未受影响**：切到图片 Tab → 多选 → 「发送到电脑」→ **不应**出现方向进度条、对话框**不应**有分包那行、电脑页面只有「打包下载全部」且列表不分组。
7. **方向路由 404**：图片 Tab 服务运行时，浏览器直接访问 `http://<ip>:<port>/landscape.zip` → 应返回 404。
8. **二次导出更快**：停止服务后对同一批视频再导出一次 → 不应再出现「正在识别视频方向…」（宽高已回填进库）。

- [ ] **Step 11: 提交**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/layout/dialog_lan_export.xml app/src/main/java/com/blitz/downloader/activity/ManageActivity.kt
git commit -m "feat: 局域网导出对话框显示分包结果与方向识别进度"
```

---

### Task 9: 文档同步

CLAUDE.md 里明确要求「每次新增需求开发完代码后，都要完善文档」。

**Files:**
- Modify: `CLAUDE.md`（「导出管道（管理页「导出选中」）」段、「持久化（Room）」段、架构图的 `util/` 与 `model/` 行）
- Modify: `.cursor/rules/db-schema.md`（版本行 + `downloaded_videos` 列表）

**Interfaces:**
- Consumes: 前 8 个任务的全部产出
- Produces: 无

- [ ] **Step 1: 更新 `CLAUDE.md` 架构图**

在架构图的 `util (...)` 那一行的括号内，末尾补上 `MediaOrientationProbe`；在 `model (VideoItemUiModel, ManageGridItem)` 那一行补上 `MediaOrientation`。

- [ ] **Step 2: 更新 `CLAUDE.md` 的「导出管道」段**

在「局域网导出」那一条 bullet 之后、「关键约束」那段之前，插入：

```markdown
**局域网导出的横屏 / 竖屏分包（视频 Tab 专属）**：电脑端页面除「打包下载全部」外，另有 `/landscape.zip` 与 `/portrait.zip` 两个方向包，文件列表也按方向分组。三条打包路由共用 `LanFileServer.serveZip(...)` 的同一份流式实现，`exportCount` 语义不变（整包完整写出 → 包内每条记录 +1）。

- **方向判定的唯一来源是本地文件**：`util/MediaOrientationProbe` 读 `MediaMetadataRetriever` 的宽高并按 `VIDEO_ROTATION` 修正（90/270 交换宽高），图片走 `BitmapFactory` 只读边界 + EXIF 修正。**不要**改成用抖音接口的 `video.width/height`——接口值不保证含旋转修正，历史记录也没有这个字段，混用会让同一张表出现两种口径。
- 结果缓存在 `downloaded_videos.mediaWidth/mediaHeight`（v14），**两个写入时机**：下载落盘后由 `DownloadService` 随写库一并写入；局域网导出前由 `ManageViewModel.backfillMediaSizes` 对 `mediaWidth == 0` 的记录懒探测回填。两条路径调同一个 probe，口径必然一致。
- 懒回填**只在视频 Tab 执行**（图片 Tab 不分包，探测纯属浪费用户时间），且回填后**必须同步更新内存里的 entities**——`resolveExportFiles` 读的是内存对象，只写库会让本次导出全部落进竖屏包。
- 方向由 `MediaOrientation.of(w, h)` 现算，`w > h` 才算横屏：方形、0（未探测/探测失败）、负数一律归竖屏。探测失败**不写哨兵值**，保持 0。
- `MediaExportManager.resolveExportFiles` **不做探测 IO**，只读 entity 字段；回填是调用方的职责。
- 图片 Tab 传 `splitByOrientation = false`，两条方向路由 404、页面不分组，行为与本功能上线前完全一致。**不要**顺手给图集也分包——图集是一条记录多个文件，拆开会把一个图集散进两个包。
- 首页分组渲染时，`/f?i=N` 的 `N` 仍是**完整 files 列表**里的下标，不按分组重排，否则单文件下载链接会指错文件。
```

- [ ] **Step 3: 更新 `CLAUDE.md` 的「持久化（Room）」段**

把

```markdown
- `AppDatabase` 当前 **version = 13**（v13 新增 `watched`）。三张表：`downloaded_videos`、`video_tags`、`tags`。
```

改为：

```markdown
- `AppDatabase` 当前 **version = 14**（v14 新增 `mediaWidth` / `mediaHeight`）。三张表：`downloaded_videos`、`video_tags`、`tags`。
```

把

```markdown
- 所有迁移 `MIGRATION_1_2 .. MIGRATION_12_13` 都在 `AppDatabase` 里显式列出。
```

改为：

```markdown
- 所有迁移 `MIGRATION_1_2 .. MIGRATION_13_14` 都在 `AppDatabase` 里显式列出。
```

把同一条 bullet 末尾的

```markdown
新增字段时：写 `MIGRATION_13_14` → `version = 14` → `addMigrations(...)` 注册 → 同步更新 `.cursor/rules/db-schema.md`（新增列与版本行）。
```

改为：

```markdown
新增字段时：写 `MIGRATION_14_15` → `version = 15` → `addMigrations(...)` 注册 → 同步更新 `.cursor/rules/db-schema.md`（新增列与版本行）。
```

在 `watched` 那条 bullet 之后插入：

```markdown
- `mediaWidth` / `mediaHeight`（v14）存媒体的**呈现宽高**（已做旋转 / EXIF 修正），`0` = 未知。只服务于局域网导出的横屏/竖屏分包，方向由 `MediaOrientation.of` 现算、不落库。图集也会写（探首图），当前不用，为后续留数据。详见上方「导出管道」。
```

- [ ] **Step 4: `.cursor/rules/db-schema.md` — 版本演进历史加一行**

在「## 版本演进历史」表格里，`| v13 | ... |` 那一行（约 36 行）之后追加：

```markdown
| v14 | `downloaded_videos` 新增 `mediaWidth` / `mediaHeight`（媒体呈现宽高，用于局域网导出分横屏/竖屏包） |
```

- [ ] **Step 5: `.cursor/rules/db-schema.md` — 字段说明表加两行**

在「### 字段说明」表格里，`| \`watched\` | ... |` 那一行（约 67 行）之后追加（该表是 4 列：字段 / 类型 / 默认 / 说明）：

```markdown
| `mediaWidth` | INTEGER | `0` | 媒体呈现宽度（像素，v14 新增，见下方规则） |
| `mediaHeight` | INTEGER | `0` | 媒体呈现高度（像素，v14 新增，见下方规则） |
```

- [ ] **Step 6: `.cursor/rules/db-schema.md` — 新增规则小节**

在「### `watched` 置位规则（v13）」小节的**末尾**（即「### 索引」小节之前）插入：

```markdown
### `mediaWidth` / `mediaHeight` 写入规则（v14）

媒体文件的**呈现宽高**（播放器/查看器里看到的那个方向），只服务于局域网导出的横屏/竖屏分包。

**唯一来源是本地文件**：`util/MediaOrientationProbe` 用 `MediaMetadataRetriever` 读宽高并按
`METADATA_KEY_VIDEO_ROTATION` 修正（90/270 交换宽高），图片走 `BitmapFactory` 只读边界 + EXIF 修正。
**不要**改成用抖音接口的 `video.width/height`——接口值不保证含旋转修正，历史记录也没有这个字段，
混用会让同一张表出现两种口径。

**两个写入时机**（调同一个 probe，口径必然一致）：

1. 下载落盘后，由 `DownloadService` 随 `recordSuccessfulDownload` 一并写入（图集探首图）；
2. 局域网导出前（**仅视频 Tab**），由 `ManageViewModel.backfillMediaSizes` 对 `mediaWidth == 0`
   的记录懒探测，`DownloadedVideoRepository.updateMediaSize` 只更新这两列、不整行覆盖。

`0` = 未知，语义单一——**探测失败不写哨兵值**，下次导出再探一次（几毫秒），换来不必区分
「没探过」和「探过但失败」。旧记录默认 0，不做历史批量回填。

**方向不落库**：由 `MediaOrientation.of(w, h)` 现算，`w > h` 才算横屏；方形（1:1）、`0`、负数
一律归竖屏。存原始宽高而非方向枚举，是为了以后想按分辨率筛选/排序时不必再加列。

图集记录也会写入（探首图），当前分包用不上——图片 Tab 不参与分包——纯为后续留数据。
```

- [ ] **Step 7: `.cursor/rules/db-schema.md` — 更新「下一步开发提示」**

把约 299 行的

```markdown
- **新增数据库字段**：当前版本为 **v13**，下次变更需在 `AppDatabase` 中新增 `MIGRATION_13_14` 并将 version 改为 14。
```

改为：

```markdown
- **新增数据库字段**：当前版本为 **v14**，下次变更需在 `AppDatabase` 中新增 `MIGRATION_14_15` 并将 version 改为 15。
```

- [ ] **Step 8: 提交**

```bash
git add CLAUDE.md .cursor/rules/db-schema.md
git commit -m "docs: 同步局域网导出分包与 DB v14 的文档"
```

---

## 完成标准

全部任务完成后应满足：

1. `./gradlew assembleDebug` 通过。
2. 视频 Tab 局域网导出：电脑端页面有三个下载按钮、列表按方向分两组，分包内容正确。
3. 图片 Tab 局域网导出：行为与改动前完全一致（无方向按钮、无分组、方向路由 404）。
4. 从旧版本 APK 覆盖安装（v13 → v14）后**数据不丢**，历史记录首次导出时能看到方向识别进度、第二次不再出现。
5. `CLAUDE.md` 与 `.cursor/rules/db-schema.md` 已同步。
