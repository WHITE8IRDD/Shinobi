package com.shinobi.app

import android.os.Bundle
import android.view.FrameMetrics
import android.view.Window
import android.os.Handler
import android.os.HandlerThread
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Real local decoder/rendering smoke test, independent of YouTube/network availability. */
class PlaybackRotationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun decodedVideoKeepsItsAspectAndPlaybackAcrossRotation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "polish-player-test.mp4")
        instrumentation.context.assets.open("player-test.mp4").use { source -> file.outputStream().use { source.copyTo(it) } }
        val frameTimes = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val metricsThread = HandlerThread("test-frame-metrics").apply { start() }
        val metrics = Window.OnFrameMetricsAvailableListener { _, frame, _ -> frameTimes.add(frame.getMetric(FrameMetrics.TOTAL_DURATION)) }
        compose.activity.window.addOnFrameMetricsAvailableListener(metrics, Handler(metricsThread.looper))
        var firstFrame = false
        var orientation = Configuration.ORIENTATION_UNDEFINED
        lateinit var player: ExoPlayer
        compose.setContent {
            orientation = LocalConfiguration.current.orientation
            var aspect by remember { mutableFloatStateOf(16f / 9f) }
            player = remember { ExoPlayer.Builder(compose.activity).build() }
            DisposableEffect(player) {
                player.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { firstFrame = true }
                    override fun onVideoSizeChanged(size: VideoSize) {
                        if (size.height > 0) aspect = size.width.toFloat() / size.height
                    }
                })
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                player.prepare()
                player.play()
                onDispose {
                    compose.activity.window.removeOnFrameMetricsAvailableListener(metrics)
                    metricsThread.quitSafely()
                    player.release()
                }
            }
            Box(Modifier.fillMaxSize().aspectFit(aspect)) {
                VideoSurface(player, Modifier.fillMaxSize().testTag("decoded-video"))
            }
        }
        compose.waitUntil(20_000) { firstFrame }
        fun assertAspect() {
            val bounds = compose.onNodeWithTag("decoded-video").fetchSemanticsNode().boundsInRoot
            assertTrue("Stretched video: $bounds", abs(bounds.width / bounds.height - 16f / 9f) < 0.02f)
            compose.runOnIdle { assertTrue(player.isPlaying); assertEquals(360, player.videoSize.height) }
        }
        compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { orientation == Configuration.ORIENTATION_LANDSCAPE }
        compose.waitForIdle()
        assertAspect()
        compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) { orientation == Configuration.ORIENTATION_PORTRAIT }
        compose.waitForIdle()
        assertAspect()
        val sample = frameTimes.sorted()
        Screenshots.saveText(instrumentation.targetContext, "09-player-frame-metrics",
            "Offline 640x360 H.264 rotation smoke test; emulator, NOT a physical-device benchmark.\n" +
            "frames=${sample.size} p95Ms=${sample.getOrNull((sample.size * 0.95).toInt())?.div(1_000_000.0)} " +
            "maxMs=${sample.lastOrNull()?.div(1_000_000.0)}\n")
    }

    @Test fun shortsLoopWithoutAutoAdvanceAndPreloadTheNextItem() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "short-loop-test.mp4")
        instrumentation.context.assets.open("player-test.mp4").use { source -> file.outputStream().use { source.copyTo(it) } }
        var loops = 0
        var nextLoaded = false
        lateinit var player: ExoPlayer
        compose.setContent {
            player = remember { ExoPlayer.Builder(compose.activity).build() }
            DisposableEffect(player) {
                player.addListener(ShortsLoopKeeper(player))
                player.addListener(object : Player.Listener {
                    override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK && new.positionMs == 0L) loops++
                    }
                })
                player.addAnalyticsListener(object : AnalyticsListener {
                    override fun onLoadCompleted(time: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) {
                        if (time.windowIndex == 1) nextLoaded = true
                    }
                })
                player.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(3_000_000L))
                player.setMediaItems(listOf("first", "next").map { id ->
                    MediaItem.Builder().setUri(file.toURI().toString()).setMediaId(id)
                        .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply { putBoolean("shinobi_short", true) }).build()).build()
                })
                player.prepare()
                player.play()
                onDispose { player.release() }
            }
            VideoSurface(player, Modifier.fillMaxSize())
        }
        compose.waitUntil(20_000) { loops >= 2 && nextLoaded }
        compose.runOnIdle {
            assertEquals("first", player.currentMediaItem?.mediaId)
            assertTrue("Upcoming Short was not preloaded", nextLoaded)
            player.seekTo(1, 0)
        }
        compose.runOnIdle { assertEquals("next", player.currentMediaItem?.mediaId) }
    }
}
