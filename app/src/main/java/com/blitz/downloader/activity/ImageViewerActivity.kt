package com.blitz.downloader.activity

import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
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
import com.blitz.downloader.ui.theme.BlitzTheme
import java.io.File

/**
 * 图片浏览页：支持左右滑动查看同一图集的全部图片。
 *
 * 入参（Intent extras）：
 * - [EXTRA_FILE_PATH]：图集第一张图的相对路径（如 `Download/bDouyin/images/xxx_01.jpg`）
 * - [EXTRA_TITLE]：显示在标题栏上的标题
 *
 * 通过扫描同目录下具有相同基础名称（去掉末尾 `_\d+` 后缀）的文件，自动枚举图集中的所有图片。
 *
 * UI 为 Compose 实现（页面级 Compose 的首例）：图集列表在 [onCreate] 里一次算好后当参数传入，
 * 页内唯一的状态就是当前页码。**没有 ViewModel**——本页既不发网络请求也不读写数据库，
 * 按 `CLAUDE.md` 的边界约定，这类纯视图状态留在视图层。
 */
class ImageViewerActivity : AppCompatActivity() {

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

        val images = findImageSet(firstFile)
        if (images.isEmpty()) {
            Toast.makeText(this, getString(R.string.player_file_not_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            BlitzTheme {
                ImageViewerScreen(
                    title = title.ifBlank { stringResource(R.string.image_viewer_title) },
                    images = images,
                    onBack = { finish() },
                )
            }
        }
    }

    /**
     * 根据图集第一张图，扫描同目录下具有相同基础名（去掉末尾 `_\d+` 后缀）的所有图片文件，
     * 按文件名升序返回。单张图片直接返回该文件。
     *
     * 与 [com.blitz.downloader.download.MediaExportManager] 的兄弟文件枚举是同一套规则，改一处需同步。
     */
    private fun findImageSet(firstFile: File): List<File> {
        val dir = firstFile.parentFile ?: return listOf(firstFile)
        val nameNoExt = firstFile.nameWithoutExtension        // e.g., "author_desc_01"
        val baseName = nameNoExt.replace(Regex("_\\d+$"), "")  // e.g., "author_desc"
        val ext = firstFile.extension

        val pattern = Regex("^${Regex.escape(baseName)}_\\d+$")
        val files = dir.listFiles { f ->
            f.isFile &&
                f.extension.equals(ext, ignoreCase = true) &&
                f.nameWithoutExtension.matches(pattern)
        }
        return if (files.isNullOrEmpty()) listOf(firstFile)
        else files.sortedBy { it.name }
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
    images: List<File>,
    onBack: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { images.size })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            AsyncImage(
                // 占位 / 失败图走 ImageRequest 而非 painterResource：ic_video_placeholder 是
                // layer-list，painterResource 只吃 VectorDrawable 与位图，会直接抛异常
                model = ImageRequest.Builder(LocalContext.current)
                    .data(images[page])
                    .crossfade(true)
                    .placeholder(R.drawable.ic_video_placeholder)
                    .error(R.drawable.ic_video_placeholder)
                    .build(),
                contentDescription = stringResource(R.string.image_viewer_title),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
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

        if (images.size > 1) {
            Text(
                text = stringResource(
                    R.string.image_viewer_page_indicator,
                    pagerState.currentPage + 1,
                    images.size,
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
