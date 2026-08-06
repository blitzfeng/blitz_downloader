package com.blitz.downloader.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MenuItem
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.R
import com.blitz.downloader.databinding.ActivityVideoPlayerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * 全屏视频播放页，支持循环播放与上下滑动切换（列表预览模式）。
 *
 * 单视频入参：
 * - [EXTRA_FILE_PATH]：本地文件相对路径
 * - [EXTRA_URL]：网络视频直链（优先级高于 EXTRA_FILE_PATH）
 * - [EXTRA_TITLE] / [EXTRA_SUBTITLE]：标题 / 副标题
 *
 * 列表预览入参（任一非空时进入列表模式，支持上下滑动切换）：
 * - [EXTRA_LIST_URLS]：网络 URL 列表
 * - [EXTRA_LIST_FILE_PATHS]：本地文件路径列表
 * - [EXTRA_LIST_TITLES] / [EXTRA_LIST_SUBTITLES]：标题 / 副标题并行列表
 * - [EXTRA_LIST_POSITION]：起始位置（默认 0）
 */
class VideoPlayerActivity : AppCompatActivity() {

    private val binding by lazy { ActivityVideoPlayerBinding.inflate(layoutInflater) }

    // ── MediaPlayer 状态 ──────────────────────────────────────────────────────
    private var mediaPlayer: MediaPlayer? = null
    private var videoFile: File? = null
    private var networkUrl: String? = null
    private var savedPosition: Int = 0
    private var wasPlaying = false
    private var videoDuration: Int = 0
    private var isUserSeeking = false

    // ── 列表预览状态 ───────────────────────────────────────────────────────────
    private val previewSources = mutableListOf<String>()   // URL 或文件路径
    private val previewTitles = mutableListOf<String>()
    private val previewSubtitles = mutableListOf<String>()

    /** 与 [previewSources] 并行的 aweme id；为空表示调用方没传，不做「已看过」标记。 */
    private val previewAwemeIds = mutableListOf<String>()

    /** 本次已写过库的 id，避免来回滑动时重复 UPDATE。 */
    private val markedWatchedIds = mutableSetOf<String>()
    private var previewIsNetwork = false
    private var currentIndex = 0
    private var isSwitchingVideo = false

    // ── Handler ───────────────────────────────────────────────────────────────
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            progressHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    // ── 手势检测 ──────────────────────────────────────────────────────────────
    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (previewSources.size <= 1 && previewSources.isEmpty()) return false
                val absVY = abs(velocityY)
                val absVX = abs(velocityX)
                // 必须以垂直方向为主，且速度足够
                if (absVY < MIN_FLING_VELOCITY || absVX > absVY * 0.8f) return false
                return if (velocityY < 0) {   // 向上 fling → 下一个
                    switchVideo(currentIndex + 1, direction = -1)
                    true
                } else {                       // 向下 fling → 上一个
                    switchVideo(currentIndex - 1, direction = 1)
                    true
                }
            }
        })
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT

        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusH = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navH = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            binding.statusBarSpacer.updateLayoutParams { height = statusH }
            binding.navigationBarSpacer.updateLayoutParams { height = navH }
            insets
        }

        // ── 解析 Intent：列表模式优先，其次单视频模式 ─────────────────────────
        val listUrls = intent.getStringArrayListExtra(EXTRA_LIST_URLS)
        val listFilePaths = intent.getStringArrayListExtra(EXTRA_LIST_FILE_PATHS)
        val listTitles = intent.getStringArrayListExtra(EXTRA_LIST_TITLES) ?: arrayListOf()
        val listSubtitles = intent.getStringArrayListExtra(EXTRA_LIST_SUBTITLES) ?: arrayListOf()
        val listPosition = intent.getIntExtra(EXTRA_LIST_POSITION, 0)
        intent.getStringArrayListExtra(EXTRA_LIST_AWEME_IDS)?.let { previewAwemeIds.addAll(it) }

        when {
            !listUrls.isNullOrEmpty() -> {
                // 列表网络模式
                previewSources.addAll(listUrls)
                previewTitles.addAll(listTitles)
                previewSubtitles.addAll(listSubtitles)
                previewIsNetwork = true
                currentIndex = listPosition.coerceIn(0, listUrls.lastIndex)
                loadItemAtIndex(currentIndex, updateUi = true)
            }
            !listFilePaths.isNullOrEmpty() -> {
                // 列表本地文件模式
                previewSources.addAll(listFilePaths)
                previewTitles.addAll(listTitles)
                previewSubtitles.addAll(listSubtitles)
                previewIsNetwork = false
                currentIndex = listPosition.coerceIn(0, listFilePaths.lastIndex)
                loadItemAtIndex(currentIndex, updateUi = true)
                // 检查起始文件是否存在
                if (videoFile?.exists() == false) {
                    Toast.makeText(this, R.string.player_file_not_found, Toast.LENGTH_SHORT).show()
                    finish(); return
                }
            }
            else -> {
                // 单视频模式（向后兼容）
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                if (url.isNotBlank()) {
                    networkUrl = url
                } else {
                    val filePath = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
                    if (filePath.isBlank()) {
                        Toast.makeText(this, R.string.player_file_not_found, Toast.LENGTH_SHORT).show()
                        finish(); return
                    }
                    @Suppress("DEPRECATION")
                    val file = File(Environment.getExternalStorageDirectory(), filePath)
                    if (!file.exists()) {
                        Toast.makeText(this, R.string.player_file_not_found, Toast.LENGTH_SHORT).show()
                        finish(); return
                    }
                    videoFile = file
                }
            }
        }

        // ── 标题（列表模式已在 loadItemAtIndex 中设置，单视频从 extra 读取）──
        if (previewSources.isEmpty()) {
            val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            val subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()
            setTitle(title.ifBlank { getString(R.string.video_player_title) })
            if (subtitle.isNotBlank()) supportActionBar?.subtitle = subtitle
        }
        updatePositionBadge()

        savedPosition = savedInstanceState?.getInt(KEY_POSITION, 0) ?: 0

        setupSeekBar()
        setupGestureDetector()

        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                val surface = Surface(st)
                val mp = mediaPlayer
                if (mp != null) {
                    mp.setSurface(surface)
                    if (wasPlaying) mp.start()
                } else {
                    initPlayer(surface)
                }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                mediaPlayer?.setSurface(null); return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        if (binding.textureView.isAvailable) {
            initPlayer(Surface(binding.textureView.surfaceTexture!!))
        }

        showControls()
    }

    override fun onResume() {
        super.onResume()
        if (wasPlaying) {
            mediaPlayer?.start()
            startProgressUpdates()
        }
        showControls()
    }

    override fun onPause() {
        super.onPause()
        mediaPlayer?.let {
            savedPosition = it.currentPosition
            wasPlaying = it.isPlaying
            if (it.isPlaying) it.pause()
        }
        stopProgressUpdates()
        hideHandler.removeCallbacks(hideRunnable)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_POSITION, mediaPlayer?.currentPosition ?: savedPosition)
        outState.putInt(KEY_INDEX, currentIndex)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressUpdates()
        hideHandler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── 手势设置 ──────────────────────────────────────────────────────────────

    private fun setupGestureDetector() {
        binding.textureView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    // ── 控制栏显示/隐藏 ────────────────────────────────────────────────────────

    private fun showControls() {
        hideHandler.removeCallbacks(hideRunnable)
        listOf(binding.topControls, binding.bottomControls).forEach { v ->
            v.animate().cancel()
            v.visibility = View.VISIBLE
            v.alpha = 1f
        }
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = true
        hideHandler.postDelayed(hideRunnable, AUTO_HIDE_DELAY_MS)
    }

    private fun hideControls() {
        listOf(binding.topControls, binding.bottomControls).forEach { v ->
            v.animate()
                .alpha(0f)
                .setDuration(FADE_DURATION_MS)
                .withEndAction { v.visibility = View.GONE }
                .start()
        }
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

    // ── 进度条 ────────────────────────────────────────────────────────────────

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && videoDuration > 0) {
                    binding.tvCurrentTime.text = formatMs((progress.toLong() * videoDuration / 1000).toInt())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                isUserSeeking = true
                hideHandler.removeCallbacks(hideRunnable)
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                isUserSeeking = false
                if (videoDuration > 0) {
                    mediaPlayer?.seekTo((sb.progress.toLong() * videoDuration / 1000).toInt())
                }
                hideHandler.postDelayed(hideRunnable, AUTO_HIDE_DELAY_MS)
            }
        })
    }

    private fun startProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.post(progressRunnable)
    }

    private fun stopProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
    }

    private fun updateProgress() {
        val mp = mediaPlayer ?: return
        if (!mp.isPlaying || isUserSeeking) return
        val current = mp.currentPosition
        if (videoDuration > 0) binding.seekBar.progress = (current.toLong() * 1000 / videoDuration).toInt()
        binding.tvCurrentTime.text = formatMs(current)
    }

    // ── 列表切换 ──────────────────────────────────────────────────────────────

    /**
     * 切换到列表中的另一个视频。
     * @param newIndex  目标索引
     * @param direction -1=向上滑（下一个），1=向下滑（上一个）
     */
    private fun switchVideo(newIndex: Int, direction: Int) {
        if (isSwitchingVideo) return

        when {
            newIndex < 0 -> {
                Toast.makeText(this, R.string.player_first_video, Toast.LENGTH_SHORT).show()
                return
            }
            newIndex >= previewSources.size -> {
                Toast.makeText(this, R.string.player_last_video, Toast.LENGTH_SHORT).show()
                return
            }
        }

        isSwitchingVideo = true
        stopProgressUpdates()

        val containerH = binding.textureView.height.toFloat()
        if (containerH == 0f) { isSwitchingVideo = false; return }

        // 当前视频滑出
        binding.textureView.animate()
            .translationY(direction * (containerH))
            .setDuration(SWITCH_ANIM_MS)
            .withEndAction {
                // 黑色遮罩盖住旧 Surface，防止旧帧闪现
                binding.vSwitchOverlay.visibility = View.VISIBLE
                binding.vSwitchOverlay.alpha = 1f

                // 切换数据源，重置播放状态
                currentIndex = newIndex
                loadItemAtIndex(newIndex, updateUi = true)

                // 释放旧播放器
                mediaPlayer?.release()
                mediaPlayer = null

                // TextureView 移到对侧（屏幕外），开始新播放器
                binding.textureView.translationY = direction * containerH
                val st = binding.textureView.surfaceTexture
                if (binding.textureView.isAvailable && st != null) {
                    initPlayer(Surface(st))
                }

                // 新视频从对侧滑入
                binding.textureView.animate()
                    .translationY(0f)
                    .setDuration(SWITCH_ANIM_MS)
                    .withEndAction { isSwitchingVideo = false }
                    .start()
            }
            .start()
    }

    /**
     * 根据索引加载对应的播放源和标题，并重置播放器进度 UI。
     * @param updateUi 是否更新标题栏/副标题/序号徽标。列表模式首次进入与后续切换都传 true，
     *   使初始（点击进入的那条）也正确显示标题+昵称，而不是停留在 Activity 默认 label。
     */
    private fun loadItemAtIndex(index: Int, updateUi: Boolean) {
        val source = previewSources.getOrNull(index) ?: return
        if (previewIsNetwork) {
            networkUrl = source
            videoFile = null
        } else {
            networkUrl = null
            @Suppress("DEPRECATION")
            videoFile = File(Environment.getExternalStorageDirectory(), source)
        }

        videoDuration = 0
        savedPosition = 0
        isUserSeeking = false
        binding.seekBar.progress = 0
        binding.tvCurrentTime.text = formatMs(0)
        binding.tvTotalTime.text = formatMs(0)

        if (updateUi) {
            val title = previewTitles.getOrNull(index) ?: ""
            val subtitle = previewSubtitles.getOrNull(index) ?: ""
            setTitle(title.ifBlank { getString(R.string.video_player_title) })
            supportActionBar?.subtitle = subtitle.takeIf { it.isNotBlank() }
            updatePositionBadge()
        }

        markWatched(index)
    }

    /**
     * 把索引 [index] 处的作品标记为「已看过」（写库）。
     *
     * 覆盖初次进入与上下滑动切换两条路径——都会先走 [loadItemAtIndex]。调用方没传
     * [EXTRA_LIST_AWEME_IDS] 时（如网络预览）直接跳过；同一 id 一次会话内只写一次。
     * 用 applicationScope 而非 lifecycleScope：滑到下一个后立刻退出页面也要写完。
     */
    private fun markWatched(index: Int) {
        val awemeId = previewAwemeIds.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return
        if (!markedWatchedIds.add(awemeId)) return
        val repo = BlitzApp.instance.downloadedVideoRepository
        CoroutineScope(Dispatchers.IO).launch { repo.markWatched(listOf(awemeId)) }
    }

    private fun updatePositionBadge() {
        if (previewSources.size <= 1) {
            binding.tvVideoPosition.visibility = View.GONE
            return
        }
        binding.tvVideoPosition.visibility = View.VISIBLE
        binding.tvVideoPosition.text = "${currentIndex + 1} / ${previewSources.size}"
    }

    // ── MediaPlayer ───────────────────────────────────────────────────────────

    private fun initPlayer(surface: Surface) {
        val url = networkUrl
        val file = videoFile
        if (url == null && file == null) return

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setSurface(surface)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            if (url != null) {
                setDataSource(
                    this@VideoPlayerActivity,
                    Uri.parse(url),
                    mapOf(
                        "Referer" to "https://www.douyin.com/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    )
                )
            } else {
                setDataSource(file!!.absolutePath)
            }
            isLooping = true
            setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0) binding.textureView.post { fitVideoInContainer(w, h) }
            }
            setOnPreparedListener { mp ->
                videoDuration = mp.duration
                binding.tvTotalTime.text = formatMs(videoDuration)
                binding.seekBar.progress = 0
                if (savedPosition > 0) mp.seekTo(savedPosition)
                mp.start()
                startProgressUpdates()
                // 播放就绪后淡出切换遮罩
                if (binding.vSwitchOverlay.visibility == View.VISIBLE) {
                    binding.vSwitchOverlay.animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction { binding.vSwitchOverlay.visibility = View.GONE }
                        .start()
                }
            }
            setOnErrorListener { _, _, _ ->
                Toast.makeText(this@VideoPlayerActivity, R.string.player_play_error, Toast.LENGTH_SHORT).show()
                if (previewSources.isEmpty()) finish()
                else { isSwitchingVideo = false }
                true
            }
            prepareAsync()
        }
    }

    private fun fitVideoInContainer(videoWidth: Int, videoHeight: Int) {
        val container = binding.textureView.parent as? FrameLayout ?: return
        val cW = container.width; val cH = container.height
        if (cW <= 0 || cH <= 0) return
        val vA = videoWidth.toFloat() / videoHeight
        val cA = cW.toFloat() / cH
        val (nW, nH) = if (vA > cA) cW to (cW / vA).toInt() else (cH * vA).toInt() to cH
        val p = binding.textureView.layoutParams as FrameLayout.LayoutParams
        p.width = nW; p.height = nH; p.gravity = Gravity.CENTER
        binding.textureView.layoutParams = p
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private fun formatMs(ms: Int): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    // ── 常量 ──────────────────────────────────────────────────────────────────

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SUBTITLE = "extra_subtitle"
        const val EXTRA_URL = "extra_url"

        const val EXTRA_LIST_URLS = "extra_list_urls"
        const val EXTRA_LIST_FILE_PATHS = "extra_list_file_paths"
        const val EXTRA_LIST_TITLES = "extra_list_titles"
        const val EXTRA_LIST_SUBTITLES = "extra_list_subtitles"
        const val EXTRA_LIST_POSITION = "extra_list_position"

        /**
         * 与列表并行的 aweme id 列表（可选）。传了才会把播放到的条目写库标记为「已看过」，
         * 网络预览等拿不到 id 的入口不传即可。
         */
        const val EXTRA_LIST_AWEME_IDS = "extra_list_aweme_ids"

        private const val KEY_POSITION = "key_position"
        private const val KEY_INDEX = "key_index"
        private const val AUTO_HIDE_DELAY_MS = 3000L
        private const val FADE_DURATION_MS = 300L
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val SWITCH_ANIM_MS = 280L
        private const val MIN_FLING_VELOCITY = 600f

        /** 单视频网络预览（向后兼容）。 */
        fun createNetworkIntent(
            context: Context, url: String, title: String = "", subtitle: String = "",
        ): Intent = Intent(context, VideoPlayerActivity::class.java)
            .putExtra(EXTRA_URL, url)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_SUBTITLE, subtitle)

        /** 从网络 URL 列表打开预览，支持上下滑动切换。 */
        fun createListNetworkIntent(
            context: Context,
            urls: ArrayList<String>,
            titles: ArrayList<String> = arrayListOf(),
            subtitles: ArrayList<String> = arrayListOf(),
            position: Int = 0,
        ): Intent = Intent(context, VideoPlayerActivity::class.java)
            .putStringArrayListExtra(EXTRA_LIST_URLS, urls)
            .putStringArrayListExtra(EXTRA_LIST_TITLES, titles)
            .putStringArrayListExtra(EXTRA_LIST_SUBTITLES, subtitles)
            .putExtra(EXTRA_LIST_POSITION, position)

        /**
         * 从本地文件路径列表打开预览，支持上下滑动切换。
         *
         * [awemeIds] 与 [filePaths] 一一对应，传了就会把播放到的条目标记为「已看过」；
         * 管理页走这条路径，其他入口可省略。
         */
        fun createListFileIntent(
            context: Context,
            filePaths: ArrayList<String>,
            titles: ArrayList<String> = arrayListOf(),
            subtitles: ArrayList<String> = arrayListOf(),
            position: Int = 0,
            awemeIds: ArrayList<String>? = null,
        ): Intent = Intent(context, VideoPlayerActivity::class.java)
            .putStringArrayListExtra(EXTRA_LIST_FILE_PATHS, filePaths)
            .putStringArrayListExtra(EXTRA_LIST_TITLES, titles)
            .putStringArrayListExtra(EXTRA_LIST_SUBTITLES, subtitles)
            .putExtra(EXTRA_LIST_POSITION, position)
            .apply { if (awemeIds != null) putStringArrayListExtra(EXTRA_LIST_AWEME_IDS, awemeIds) }
    }
}
