package com.shinobi.app

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
                onDispose { player.release() }
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
    }
}
