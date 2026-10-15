package com.shinobi.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.SuccessResult
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Offline decode/controller/UI integration; deliberately exercises real storyboard painters,
 * not just the no-storyboard fallback that missed the fullscreen crash in 1.9. */
class ReleaseRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<PlayerRegressionActivity>()

    private val board = SeekStoryboard(listOf("https://storyboard.invalid/sheet"), 160, 90, 6, 30_000, 3, 2)

    private fun installOfflineImages(): ImageLoader {
        val context = compose.activity
        val loader = ImageLoader.Builder(context).crossfade(false).components {
            add(Interceptor { chain ->
                if (!chain.request.data.toString().startsWith("https://storyboard.invalid/")) {
                    chain.proceed(chain.request)
                } else {
                    // Force at least one draw with intrinsicSize == Size.Unspecified.
                    delay(250)
                    if (chain.request.data.toString().endsWith("missing")) {
                        ErrorResult(null, chain.request, java.io.IOException("offline missing sheet"))
                    } else {
                        val bitmap = Bitmap.createBitmap(480, 180, Bitmap.Config.RGB_565)
                        bitmap.eraseColor(android.graphics.Color.RED)
                        SuccessResult(BitmapDrawable(context.resources, bitmap), chain.request, DataSource.MEMORY_CACHE)
                    }
                }
            })
        }.build()
        Coil.setImageLoader(loader)
        return loader
    }

    private class Fixture(val backend: ExoPlayer, val session: MediaSession,
                          val controller: MediaController, val state: PlayerState, val scope: CoroutineScope)

    private fun fixture(asset: String, short: Boolean = false): Fixture {
        lateinit var backend: ExoPlayer
        lateinit var session: MediaSession
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        compose.runOnIdle {
            backend = ExoPlayer.Builder(compose.activity).build()
            backend.setMediaItem(MediaItem.Builder().setMediaId(asset).setUri("asset:///$asset").build())
            session = MediaSession.Builder(compose.activity, backend).build()
            future = MediaController.Builder(compose.activity, session.token).buildAsync()
        }
        val controller = future.get(10, TimeUnit.SECONDS)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var state: PlayerState
        compose.runOnIdle {
            state = PlayerState(scope) { id ->
                VideoDetails(VideoItem(id, "Offline seek test", "Creator", null, null, null, 180, 0, null,
                    isShort = short), "", 0, 0, emptyList(), true, null, emptyList(), storyboard = board)
            }
            state.controller = controller
            state.onConnected()
            controller.addListener(state.listener)
            controller.prepare()
        }
        compose.waitUntil(15_000) { controller.playbackState == Player.STATE_READY && state.details != null }
        compose.runOnIdle { state.syncFromPlayer() }
        return Fixture(backend, session, controller, state, scope)
    }

    private fun release(fixture: Fixture) = compose.runOnIdle {
        fixture.scope.cancel()
        fixture.controller.removeListener(fixture.state.listener)
        fixture.state.controller = null
        fixture.controller.release(); fixture.session.release(); fixture.backend.release()
    }

    @Test fun fullscreenTapsAndDragsAtEarlyMiddleAndEndOfThreeVideos() {
        val loader = installOfflineImages()
        var displayed by mutableStateOf<PlayerState?>(null)
        var landscape = false
        compose.setContent {
            landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            MaterialTheme {
                displayed?.let { state ->
                    CompositionLocalProvider(LocalVideoSurface provides { modifier ->
                        VideoSurface(state.controller, modifier, state::onVideoFrame)
                    }) { FullscreenPlayer(state, {}) }
                }
            }
        }
        compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { landscape }
        try {
            for (asset in listOf("player-fit.mp4", "player-landscape.mp4", "player-short.mp4")) {
                val f = fixture(asset)
                try {
                    compose.runOnIdle { displayed = f.state }
                    compose.onRoot().performTouchInput { click(center) }
                    val bar = compose.onNodeWithTag("player-scrubber")
                    bar.assertIsDisplayed()
                    repeat(3) {
                        for (fraction in listOf(0.02f, 0.5f, 0.96f, 0.2f)) {
                            bar.performTouchInput { click(Offset(width * fraction, height / 2f)) }
                            compose.runOnIdle {
                                val expected = (f.controller.duration * fraction).toLong()
                                assertTrue("tap: $asset at $fraction", kotlin.math.abs(expected - f.controller.currentPosition) <= 300)
                                assertFalse("seeking must not unpause", f.controller.playWhenReady)
                            }
                            val before = bar.fetchSemanticsNode().boundsInRoot
                            bar.performTouchInput {
                                down(Offset(width * 0.1f, height / 2f))
                                moveTo(Offset(width * 0.75f, height / 2f), 300)
                            }
                            compose.onNodeWithTag("seek-thumbnail").assertIsDisplayed()
                            val during = bar.fetchSemanticsNode().boundsInRoot
                            assertEquals("preview must not relocate the touch target", before, during)
                            bar.performTouchInput { up() }
                            compose.onNodeWithTag("seek-thumbnail").assertDoesNotExist()
                            compose.runOnIdle {
                                assertTrue(kotlin.math.abs((f.controller.duration * 0.75f).toLong() - f.controller.currentPosition) <= 300)
                                assertNull(f.backend.playerError)
                            }
                        }
                    }
                    bar.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.25f) }
                    compose.runOnIdle {
                        assertTrue(kotlin.math.abs((f.controller.duration * 0.25f).toLong() - f.controller.currentPosition) <= 300)
                    }
                } finally {
                    compose.runOnIdle { displayed = null }
                    release(f)
                }
            }
        } finally {
            compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            Coil.setImageLoader((compose.activity.application as ShinobiApplication).newImageLoader())
            loader.shutdown()
        }
    }

    @Test fun missingStoryboardSheetRemainsSafeAfterLoadingAndOnVideoSwitch() {
        val loader = installOfflineImages()
        var position by mutableStateOf(0L)
        var storyboard by mutableStateOf<SeekStoryboard?>(board.copy(urls = listOf("https://storyboard.invalid/missing")))
        compose.setContent { MaterialTheme { SeekThumbnail(storyboard, null, position) } }
        compose.onNodeWithTag("seek-thumbnail").assertIsDisplayed()
        // Let the asynchronous failure be delivered and drawn, then visit more frames.
        Thread.sleep(400)
        repeat(10) {
            compose.runOnIdle { position = it * 17_000L }
            compose.onNodeWithTag("seek-thumbnail").assertIsDisplayed()
        }
        compose.runOnIdle { storyboard = null }
        compose.onNodeWithTag("seek-thumbnail").assertIsDisplayed()
        Coil.setImageLoader((compose.activity.application as ShinobiApplication).newImageLoader())
        loader.shutdown()
    }

    @Test fun shortsRailMetadataAndSeekStripDoNotOverlapNavigationInEitherDirection() {
        val f = fixture("player-short.mp4", short = true)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction,
                    LocalVideoSurface provides { modifier -> Box(modifier.background(Color.Black)) }) {
                    Column(Modifier.fillMaxSize().widthIn(max = 320.dp)) {
                        Box(Modifier.weight(1f)) {
                            ShortPageContent(f.state.current!!, f.state, true, true, 0.dp, {}, {}, {})
                        }
                        ShortsNavigation(Tab.Shorts, {})
                    }
                }
            }
        }
        try {
            for (dir in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                compose.runOnIdle { direction = dir }
                val rail = compose.onNodeWithTag("shorts-action-rail").fetchSemanticsNode().boundsInRoot
                val meta = compose.onNodeWithTag("shorts-metadata").fetchSemanticsNode().boundsInRoot
                val seek = compose.onNodeWithTag("shorts-seek-bar").fetchSemanticsNode().boundsInRoot
                val nav = compose.onNodeWithTag("shorts-navigation").fetchSemanticsNode().boundsInRoot
                assertTrue("rail above seek: $rail / $seek", rail.bottom <= seek.top)
                assertTrue("metadata above seek: $meta / $seek", meta.bottom <= seek.top)
                assertTrue("seek above navigation: $seek / $nav", seek.bottom <= nav.top)
                assertTrue("rail stays on physical right in $dir", rail.center.x > meta.center.x)
                compose.onNodeWithTag("player-scrubber").performTouchInput { click(Offset(width * 0.6f, height / 2f)) }
                compose.runOnIdle {
                    assertTrue(kotlin.math.abs((f.controller.duration * 0.6f).toLong() - f.controller.currentPosition) <= 300)
                    assertFalse(f.controller.playWhenReady)
                }
            }
        } finally { release(f) }
    }
}
