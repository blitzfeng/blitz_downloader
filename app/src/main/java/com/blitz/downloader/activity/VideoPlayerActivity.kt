package com.blitz.downloader.activity

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MenuItem
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import com.blitz.downloader.R
import com.blitz.downloader.databinding.ActivityVideoPlayerBinding
import java.io.File

/**
 * 全屏视频播放页，支持循环播放。
 *
 * - 使用 [TextureView] + [MediaPlayer] 代替 [android.widget.VideoView]，
 *   避免 SurfaceView 在部分设备上"有声无画"（Surface 合成失败）的问题。
 * - 顶部控制栏（状态栏 + Toolbar）白色背景，进入后 [AUTO_HIDE_DELAY_MS] 毫秒自动淡出隐藏；
 *   点击视频画面可重新显示，之后再次自动隐藏。
 *
 * 入参（Intent extras）：
 * - [EXTRA_FILE_PATH]：视频文件相对于外部存储根目录的路径
 * - [EXTRA_TITLE]：显示在 Toolbar 上的标题
 */
class VideoPlayerActivity : AppCompatActivity() {

    private val binding by lazy { ActivityVideoPlayerBinding.inflate(layoutInflater) }

    private var mediaPlayer: MediaPlayer? = null
    private var videoFile: File? = null
    private var savedPosition: Int = 0
    private var wasPlaying = false

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 内容延伸到状态栏/导航栏后方（边到边），由 statusBarSpacer 填充状态栏区域
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT

        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 动态设置 statusBarSpacer 高度 = 状态栏高度，令白色控制栏恰好覆盖状态栏区域
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.statusBarSpacer.updateLayoutParams { height = statusBarHeight }
            insets
        }

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.toolbar.title = title.ifBlank { getString(R.string.video_player_title) }

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
        if (filePath.isBlank()) {
            Toast.makeText(this, R.string.player_file_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        @Suppress("DEPRECATION")
        val file = File(Environment.getExternalStorageDirectory(), filePath)
        if (!file.exists()) {
            Toast.makeText(this, R.string.player_file_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        videoFile = file
        savedPosition = savedInstanceState?.getInt(KEY_POSITION, 0) ?: 0

        // 点击视频区域：若控制栏可见则隐藏，否则显示并重置计时
        binding.textureView.setOnClickListener { toggleControls() }

        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                val surface = Surface(st)
                val mp = mediaPlayer
                if (mp != null) {
                    // Activity 停止后 Surface 被销毁，重建后重新绑定
                    mp.setSurface(surface)
                    if (wasPlaying) mp.start()
                } else {
                    initPlayer(surface)
                }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                mediaPlayer?.setSurface(null)
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        if (binding.textureView.isAvailable) {
            initPlayer(Surface(binding.textureView.surfaceTexture!!))
        }

        // 进入页面先显示控制栏，之后自动隐藏
        showControls()
    }

    override fun onResume() {
        super.onResume()
        if (wasPlaying) mediaPlayer?.start()
        // 从后台返回时重新显示控制栏（几秒后自动隐藏）
        showControls()
    }

    override fun onPause() {
        super.onPause()
        mediaPlayer?.let {
            savedPosition = it.currentPosition
            wasPlaying = it.isPlaying
            if (it.isPlaying) it.pause()
        }
        hideHandler.removeCallbacks(hideRunnable)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_POSITION, mediaPlayer?.currentPosition ?: savedPosition)
    }

    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ── 控制栏显示/隐藏 ────────────────────────────────────────────────────────

    private fun showControls() {
        hideHandler.removeCallbacks(hideRunnable)
        binding.topControls.animate().cancel()
        binding.topControls.visibility = View.VISIBLE
        binding.topControls.alpha = 1f
        // 白色背景下状态栏图标切换为深色
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = true
        hideHandler.postDelayed(hideRunnable, AUTO_HIDE_DELAY_MS)
    }

    private fun hideControls() {
        binding.topControls.animate()
            .alpha(0f)
            .setDuration(FADE_DURATION_MS)
            .withEndAction { binding.topControls.visibility = View.GONE }
            .start()
        // 控制栏隐藏后切回白色图标（视频/黑色背景下）
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = false
    }

    private fun toggleControls() {
        if (binding.topControls.visibility == View.VISIBLE && binding.topControls.alpha > 0f) {
            hideHandler.removeCallbacks(hideRunnable)
            hideControls()
        } else {
            showControls()
        }
    }

    // ── MediaPlayer ───────────────────────────────────────────────────────────

    private fun initPlayer(surface: Surface) {
        val file = videoFile ?: return
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setSurface(surface)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            setDataSource(file.absolutePath)
            isLooping = true
            setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0) {
                    binding.textureView.post { fitVideoInContainer(w, h) }
                }
            }
            setOnPreparedListener {
                if (savedPosition > 0) seekTo(savedPosition)
                start()
            }
            setOnErrorListener { _, _, _ ->
                Toast.makeText(
                    this@VideoPlayerActivity,
                    R.string.player_play_error,
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                true
            }
            prepareAsync()
        }
    }

    /**
     * 按 fit-center 规则调整 TextureView 尺寸，保持视频宽高比。
     */
    private fun fitVideoInContainer(videoWidth: Int, videoHeight: Int) {
        val container = binding.textureView.parent as? FrameLayout ?: return
        val containerW = container.width
        val containerH = container.height
        if (containerW <= 0 || containerH <= 0) return

        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val containerAspect = containerW.toFloat() / containerH.toFloat()

        val newWidth: Int
        val newHeight: Int
        if (videoAspect > containerAspect) {
            newWidth = containerW
            newHeight = (containerW / videoAspect).toInt()
        } else {
            newWidth = (containerH * videoAspect).toInt()
            newHeight = containerH
        }

        val params = binding.textureView.layoutParams as FrameLayout.LayoutParams
        params.width = newWidth
        params.height = newHeight
        params.gravity = Gravity.CENTER
        binding.textureView.layoutParams = params
    }

    // ── 常量 ──────────────────────────────────────────────────────────────────

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_TITLE = "extra_title"
        private const val KEY_POSITION = "key_position"
        /** 无操作后控制栏自动隐藏的等待时间（毫秒） */
        private const val AUTO_HIDE_DELAY_MS = 3000L
        private const val FADE_DURATION_MS = 300L
    }
}
