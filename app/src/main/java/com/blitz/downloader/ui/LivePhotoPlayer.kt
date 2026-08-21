package com.blitz.downloader.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.blitz.downloader.R

/** 实况图播放故障日志 TAG（仅在 MediaPlayer 报错 / setup 异常时打）。 */
private const val TAG_LIVE = "LivePhoto"

/**
 * 实况图（Live Photo）播放器：用原生 [MediaPlayer] + [TextureView] 循环、静音播放，画面按
 * `ContentScale.Fit` 居中缩放（黑底留边）。浏览页（本地文件路径）与下载页预览（网络 mp4 URL）
 * 共用同一份实现——[mediaSource] 既可是本地绝对路径，也可是 http(s) URL（`MediaPlayer.setDataSource`
 * 两者都吃）。**不引 ExoPlayer**；用 `TextureView` 而非 `SurfaceView` 是因为 pager 横滑时 SurfaceView
 * 有独立窗口层会闪黑。
 *
 * 起播条件三者齐（[isActive] + 已 `onPrepared` + surface 就绪），收敛在单个 `LaunchedEffect`。
 * **关键：`start()`/`pause()`/`seekTo()` 必须用 `isPlaying` 守卫**——pager 会预加载相邻页，被预加载的
 * 非当前页 `onPrepared` 后处于 Prepared 态，此时若对它调 `pause()` 会让 MediaPlayer 报 `error(-38)`
 * 直接进 Error 态、之后 `start()` 全废（「多页只第一页能播、滑回第一页也黑」的根因）。所以非当前页的
 * Prepared 态保持不动，等它成为当前页再 start，绝不 pause 一个还没 Started 的播放器。
 *
 * @param mediaSource mp4 数据源：本地绝对路径或网络 URL。
 * @param coverModel 兜底封面（[coil] 的 model，可为本地 File 或封面 URL），视频就绪前显示。
 * @param isActive 是否当前页；仅当前页播放，滑走暂停并回到首帧。
 */
@Composable
fun LivePhotoPlayer(
    mediaSource: String,
    coverModel: Any?,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mediaPlayer = remember { MediaPlayer() }
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var videoW by remember { mutableStateOf(0) }
    var videoH by remember { mutableStateOf(0) }
    var prepared by remember { mutableStateOf(false) }
    var surfaceReady by remember { mutableStateOf(false) }

    Box(modifier) {
        // 封面兜底：视频未就绪 / 播放失败时显示静态封面
        if (!prepared) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(coverModel)
                    .placeholder(R.drawable.ic_video_placeholder)
                    .error(R.drawable.ic_video_placeholder)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            runCatching { mediaPlayer.setSurface(Surface(st)) }
                            surfaceReady = true
                            applyFitTransform(this@apply, videoW, videoH)
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                            applyFitTransform(this@apply, videoW, videoH)
                        }

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            surfaceReady = false
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                    textureView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    DisposableEffect(mediaSource) {
        try {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(mediaSource)
            mediaPlayer.isLooping = true
            mediaPlayer.setVolume(0f, 0f)   // 实况图静音
            mediaPlayer.setOnVideoSizeChangedListener { _, w, h ->
                videoW = w
                videoH = h
            }
            mediaPlayer.setOnPreparedListener { prepared = true }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                Log.w(TAG_LIVE, "MediaPlayer ERROR what=$what extra=$extra src=${mediaSource.take(80)}")
                false
            }
            mediaPlayer.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG_LIVE, "setup failed src=${mediaSource.take(80)}", e)
        }
        onDispose { runCatching { mediaPlayer.release() } }
    }

    // 播放决策单点；start/pause/seek 全部 isPlaying 守卫（见类头 KDoc 的 error(-38) 坑）。
    LaunchedEffect(isActive, prepared, surfaceReady) {
        if (!prepared) return@LaunchedEffect
        runCatching {
            if (isActive && surfaceReady) {
                if (!mediaPlayer.isPlaying) mediaPlayer.start()
            } else if (mediaPlayer.isPlaying) {
                mediaPlayer.pause()
                mediaPlayer.seekTo(0)
            }
        }
    }

    // 视频尺寸就绪后按 Fit 重算变换
    LaunchedEffect(videoW, videoH) {
        val tv = textureView ?: return@LaunchedEffect
        applyFitTransform(tv, videoW, videoH)
    }
}

/**
 * 把 [TextureView] 的内容按 `ContentScale.Fit` 居中缩放到视频原始宽高比。
 * TextureView 默认把内容拉伸填满自身，这里以视图中心为轴反算缩放系数还原比例。
 */
private fun applyFitTransform(tv: TextureView, videoW: Int, videoH: Int) {
    if (videoW <= 0 || videoH <= 0) return
    val viewW = tv.width.toFloat()
    val viewH = tv.height.toFloat()
    if (viewW <= 0f || viewH <= 0f) return
    val scale = minOf(viewW / videoW, viewH / videoH)
    val drawnW = videoW * scale
    val drawnH = videoH * scale
    val matrix = Matrix().apply {
        setScale(drawnW / viewW, drawnH / viewH, viewW / 2f, viewH / 2f)
    }
    tv.setTransform(matrix)
}
