package com.blitz.downloader.activity

import android.media.MediaPlayer
import android.os.Environment
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.doubleClick
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blitz.downloader.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 使用合成的 3 秒无声渐变视频验证真实播放器与触摸事件，不访问网络或修改业务数据。 */
@RunWith(AndroidJUnit4::class)
class VideoPlayerInteractionTest {
    private lateinit var scenario: ActivityScenario<VideoPlayerActivity>
    private lateinit var fixture: File
    private val playerField = VideoPlayerActivity::class.java.getDeclaredField("mediaPlayer").apply { isAccessible = true }
    private val preparedField = VideoPlayerActivity::class.java.getDeclaredField("isPlayerPrepared").apply { isAccessible = true }

    @Before fun openPlayer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fixture = File(context.getExternalFilesDir(null), "video-tap-test.mp4")
        instrumentation.context.assets.open("video-tap.mp4").use { source ->
            fixture.outputStream().use { source.copyTo(it) }
        }
        @Suppress("DEPRECATION")
        val path = fixture.relativeTo(Environment.getExternalStorageDirectory()).path
        scenario = ActivityScenario.launch(VideoPlayerActivity.createListFileIntent(context,
            arrayListOf(path, path), titles = arrayListOf("测试视频一", "测试视频二")))
        await { prepared(it) && player(it).isPlaying }
    }

    @After fun closePlayer() {
        if (::scenario.isInitialized) scenario.close()
        if (::fixture.isInitialized) fixture.delete()
    }

    private fun prepared(activity: VideoPlayerActivity) = preparedField.getBoolean(activity)
    private fun player(activity: VideoPlayerActivity) = playerField.get(activity) as MediaPlayer
    private fun controlsVisible(activity: VideoPlayerActivity) =
        activity.findViewById<View>(R.id.topControls).let { it.visibility == View.VISIBLE && it.alpha > 0f }

    private fun await(condition: (VideoPlayerActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            scenario.onActivity { done = condition(it) }
            if (done) return
            SystemClock.sleep(50)
        }
        fail("等待播放器状态超时")
    }

    @Test fun firstTapShowsTitle_secondTapPauses_nextTapResumes() {
        await { !controlsVisible(it) }
        onView(withId(R.id.textureView)).perform(click())
        scenario.onActivity { assertTrue(controlsVisible(it)); assertTrue(player(it).isPlaying) }
        onView(withId(R.id.textureView)).perform(click())
        await { !player(it).isPlaying }
        // 暂停后即使超过原来的 3 秒超时也保留标题与暂停状态。
        SystemClock.sleep(3_500)
        scenario.onActivity { assertTrue(controlsVisible(it)); assertFalse(player(it).isPlaying) }
        onView(withId(R.id.textureView)).perform(click())
        await { player(it).isPlaying }
        await { !controlsVisible(it) }
        onView(withId(R.id.textureView)).perform(click())
        scenario.onActivity { assertTrue(player(it).isPlaying) }
    }

    @Test fun rapidTapsPause_andPauseSurvivesBackgroundAndRecreation() {
        await { !controlsVisible(it) }
        onView(withId(R.id.textureView)).perform(doubleClick())
        await { !player(it).isPlaying }
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.onActivity { assertFalse(player(it).isPlaying) }
        scenario.recreate()
        await { prepared(it) }
        scenario.onActivity { assertFalse(player(it).isPlaying); assertTrue(controlsVisible(it)) }
        onView(withId(R.id.textureView)).perform(click())
        await { player(it).isPlaying }
    }

    @Test fun swipeWhilePausedStartsNextVideo_andRecreationKeepsCurrentItem() {
        onView(withId(R.id.textureView)).perform(click())
        await { !player(it).isPlaying }
        onView(withId(R.id.textureView)).perform(swipeUp())
        await { prepared(it) && player(it).isPlaying &&
            it.findViewById<TextView>(R.id.tvVideoPosition).text.toString() == "2 / 2" }
        scenario.recreate()
        await { prepared(it) && player(it).isPlaying }
        scenario.onActivity { assertEquals("2 / 2", it.findViewById<TextView>(R.id.tvVideoPosition).text.toString()) }
    }
}
