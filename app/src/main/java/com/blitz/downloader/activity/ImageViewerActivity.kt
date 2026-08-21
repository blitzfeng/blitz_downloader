package com.blitz.downloader.activity

import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.blitz.downloader.R
import com.blitz.downloader.ui.LivePhotoPlayer
import com.blitz.downloader.ui.theme.BlitzTheme
import java.io.File

/**
 * 图片浏览页：支持左右滑动查看同一图集的全部图片，**实况图（Live Photo / 动图）自动循环播放**。
 *
 * 入参（Intent extras）：
 * - [EXTRA_FILE_PATH]：图集第一张图的相对路径（如 `Download/bDouyin/images/xxx_01.webp`）
 * - [EXTRA_TITLE]：显示在标题栏上的标题
 *
 * 通过扫描同目录下具有相同基础名称（去掉末尾 `_\d+` 后缀）的封面文件，自动枚举图集所有图片；
 * 每张封面再探测同名 `.mp4` 兄弟文件——存在即为实况图，用 [LivePhotoPlayer] 播放；否则是静态图。
 *
 * UI 为 Compose 实现。**没有 ViewModel**——本页既不发网络请求也不读写数据库，
 * 按 `CLAUDE.md` 的边界约定，这类纯视图状态留在视图层。播放器与下载页预览共用 [LivePhotoPlayer]。
 */
class ImageViewerActivity : AppCompatActivity() {

    /** 图集中的一页：静态封面 + 可选的实况图 mp4（非空即动图）。 */
    internal data class LivePhotoPage(val cover: File, val video: File?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()

        if (filePath.isBlank()) {
            Toast.makeText(this, getString(R.string.player_file_not_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        @Suppress("DEPRECATION")
        val firstFile = File(Environment.getExternalStorageDirectory(), filePath)
        if (!firstFile.exists()) {
            Toast.makeText(this, getString(R.string.player_file_not_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val pages = findImageSet(firstFile)
        if (pages.isEmpty()) {
            Toast.makeText(this, getString(R.string.player_file_not_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            BlitzTheme {
                ImageViewerScreen(
                    title = title.ifBlank { stringResource(R.string.image_viewer_title) },
                    pages = pages,
                    onBack = { finish() },
                )
            }
        }
    }

    /**
     * 根据图集第一张图，扫描同目录下具有相同基础名（去掉末尾 `_\d+` 后缀）的所有**封面图片**文件，
     * 按文件名升序返回；每张封面再探测同名 `.mp4` 决定是否为实况图。单张图片直接返回该文件。
     *
     * 扫描规则（`base_\d+` + 探 mp4）与 [com.blitz.downloader.download.MediaExportManager.findImageSet] 一致，
     * 改一处需同步：那边把 mp4 也纳入导出文件列表，这边只判断每张封面**是否**有 mp4 兄弟。
     */
    private fun findImageSet(firstFile: File): List<LivePhotoPage> {
        val dir = firstFile.parentFile ?: return listOf(LivePhotoPage(firstFile, siblingVideoOf(firstFile)))
        val baseName = firstFile.nameWithoutExtension.replace(Regex("_\\d+$"), "")
        val pattern = Regex("^${Regex.escape(baseName)}_\\d+$")
        val imageExts = setOf("webp", "jpg", "jpeg", "png")
        val covers = dir.listFiles { f ->
            f.isFile &&
                f.extension.lowercase() in imageExts &&
                f.nameWithoutExtension.matches(pattern)
        }
        val ordered = if (covers.isNullOrEmpty()) listOf(firstFile) else covers.sortedBy { it.name }
        return ordered.map { LivePhotoPage(it, siblingVideoOf(it)) }
    }

    /** 封面同名的 `.mp4` 兄弟文件（实况图本体）；不存在返回 null。 */
    private fun siblingVideoOf(cover: File): File? {
        val mp4 = File(cover.parentFile, "${cover.nameWithoutExtension}.mp4")
        return mp4.takeIf { it.isFile }
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_TITLE = "extra_title"
    }
}

/** 顶栏与页码角标的配色写死，不取 [BlitzTheme] 的浅色方案——黑底页面上套浅色容器会让白字看不清。 */
private val ViewerBarColor = Color(0xFF5469D4)   // 对齐 colors.xml 的 color_primary_dark
private val IndicatorScrim = Color(0x80000000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageViewerScreen(
    title: String,
    pages: List<ImageViewerActivity.LivePhotoPage>,
    onBack: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = pages[page]
            if (item.video != null) {
                // 实况图：本地 mp4，仅当前页播放
                LivePhotoPlayer(
                    mediaSource = item.video.absolutePath,
                    coverModel = item.cover,
                    isActive = pagerState.currentPage == page,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AsyncImage(
                    // 占位 / 失败图走 ImageRequest 而非 painterResource：ic_video_placeholder 是
                    // layer-list，painterResource 只吃 VectorDrawable 与位图，会直接抛异常
                    model = ImageRequest.Builder(context)
                        .data(item.cover)
                        .crossfade(true)
                        .placeholder(R.drawable.ic_video_placeholder)
                        .error(R.drawable.ic_video_placeholder)
                        .build(),
                    contentDescription = stringResource(R.string.image_viewer_title),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 顶栏悬浮在图片之上，与改造前的 FrameLayout 叠放一致
        TopAppBar(
            title = {
                Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    // 复用存量矢量图，不引 material-icons 依赖
                    Icon(
                        painter = painterResource(R.drawable.ic_back_arrow),
                        contentDescription = stringResource(R.string.image_viewer_back),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = ViewerBarColor,
                titleContentColor = Color.White,
                navigationIconContentColor = Color.White,
            ),
            modifier = Modifier.align(Alignment.TopCenter),
        )

        if (pages.size > 1) {
            Text(
                text = stringResource(
                    R.string.image_viewer_page_indicator,
                    pagerState.currentPage + 1,
                    pages.size,
                ),
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .background(IndicatorScrim)
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            )
        }
    }
}
