package com.shinobi.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class PlayerRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<PlayerRegressionActivity>()

    // Cancelled scopes deliberately prevent network/detail fetches: all these checks are offline.
    private fun offlineState() = PlayerState(CoroutineScope(Job().apply { cancel() } + Dispatchers.Main))

    @Test fun localeWrapperFollowsRealRotationAndFullscreenScrubberRemainsInsideWindow() {
        var observed = Configuration.ORIENTATION_UNDEFINED
        compose.setContent {
            observed = LocalConfiguration.current.orientation
            MaterialTheme {
                CompositionLocalProvider(LocalVideoSurface provides { modifier -> Box(modifier.background(Color.Red)) }) {
                    FullscreenPlayer(remember { offlineState() }, {})
                }
            }
        }
        compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10000) { observed == Configuration.ORIENTATION_LANDSCAPE }
        compose.onRoot().performTouchInput { click(center) }
        compose.waitForIdle()
        compose.onNodeWithTag("player-scrubber").assertIsDisplayed()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val scrub = compose.onNodeWithTag("player-scrubber").fetchSemanticsNode().boundsInRoot
        val fit = compose.onNodeWithTag("fitted-video").fetchSemanticsNode().boundsInRoot
        assertTrue("landscape root: $root", root.width > root.height)
        assertTrue("scrubber outside display: $scrub in $root", scrub.bottom <= root.bottom && scrub.left >= root.left && scrub.right <= root.right)
        assertTrue("frame must fit: $fit", fit.left >= root.left && fit.top >= root.top && fit.right <= root.right && fit.bottom <= root.bottom)
        assertTrue("16:9 frame distorted: $fit", abs(fit.width / fit.height - 16f / 9f) < 0.02f)
        compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10000) { observed == Configuration.ORIENTATION_PORTRAIT }
    }

    @Test fun fitCentersTallAndWideVideosWithoutCropping() {
        compose.setContent {
            Column {
                Box(Modifier.size(300.dp, 120.dp).testTag("wide-box")) {
                    Box(Modifier.fillMaxSize().aspectFit(9f / 16f).testTag("tall-frame"))
                }
                Box(Modifier.size(120.dp, 240.dp).testTag("tall-box")) {
                    Box(Modifier.fillMaxSize().aspectFit(2.4f).testTag("wide-frame"))
                }
            }
        }
        listOf("wide-box" to "tall-frame", "tall-box" to "wide-frame").forEach { (container, frame) ->
            val outer = compose.onNodeWithTag(container).fetchSemanticsNode().boundsInRoot
            val inner = compose.onNodeWithTag(frame).fetchSemanticsNode().boundsInRoot
            assertTrue("$inner outside $outer", inner.left >= outer.left && inner.right <= outer.right && inner.top >= outer.top && inner.bottom <= outer.bottom)
            assertEquals(outer.center.x, inner.center.x, 1f)
            assertEquals(outer.center.y, inner.center.y, 1f)
        }
    }

    @Test fun scrubberSupportsTapDragPreviewAndAccessibilitySeeking() {
        var sought = -1L
        var preview: Long? = null
        compose.setContent {
            var shown by remember { mutableStateOf<Long?>(null) }
            MaterialTheme {
                Column(Modifier.width(300.dp)) {
                    Box(Modifier.height(130.dp)) { shown?.let { SeekThumbnail(null, null, it) } }
                    Scrubber(emptyList(), 0, 30000, 60000, true,
                        onPreview = { preview = it; shown = it },
                        onSeek = { sought = it; shown = null },
                        onCancel = { shown = null }, touchHeight = 40.dp)
                }
            }
        }
        val bar = compose.onNodeWithTag("player-scrubber")
        bar.performTouchInput { click(Offset(width * 0.5f, height / 2f)) }
        compose.runOnIdle { assertTrue(sought in 29000L..31000L) }
        bar.performTouchInput { down(Offset(width * 0.2f, height / 2f)); moveTo(Offset(width * 0.7f, height / 2f)) }
        compose.onNodeWithTag("seek-thumbnail").assertIsDisplayed()
        compose.runOnIdle { assertTrue(preview != null && preview!! > 30000) }
        // Preview insertion moves the bar in this isolated harness; coordinates are irrelevant on up.
        bar.performTouchInput { up() }
        compose.onNodeWithTag("seek-thumbnail").assertDoesNotExist()
        compose.runOnIdle { assertTrue(sought > 30000) }
        bar.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.25f) }
        compose.runOnIdle { assertEquals(15000L, sought) }
    }

    @Test fun shortPagesMaskRetainedFrameAndHaveOnlyOneTextureAcrossSwipes() {
        compose.setContent {
            val pager = rememberPagerState(pageCount = { 3 })
            val state = remember { offlineState() }
            MaterialTheme {
                CompositionLocalProvider(LocalVideoSurface provides { modifier ->
                    Box(modifier.background(Color.Magenta).testTag("short-texture"))
                }) {
                    VerticalPager(pager, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize().clipToBounds()) { page ->
                        ShortPageContent(
                            VideoItem("offline-$page", "Short $page", "", null, null, null, 20, 0, null, isShort = true),
                            state, page == pager.settledPage, true, 8.dp, {}, {}, {},
                        )
                    }
                }
            }
        }
        repeat(2) {
            compose.onAllNodesWithTag("short-texture", useUnmergedTree = true).assertCountEquals(1)
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            // The shared surface contains a fake previous magenta frame: none of it may
            // leak through the new page's cover, even though the AndroidView stays attached.
            for (y in 0 until pixels.height step 17) for (x in 0 until pixels.width step 17) {
                assertNotEquals("retained frame at $x,$y", Color.Magenta, pixels[x, y])
            }
            compose.onRoot().performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
    }

    @Test fun shortsTemporarilyOwnsDecoderAndRestoresPausedMiniPlayerAndQueue() {
        lateinit var backend: ExoPlayer
        lateinit var session: MediaSession
        lateinit var future: ListenableFuture<MediaController>
        lateinit var controller: MediaController
        lateinit var state: PlayerState
        compose.runOnIdle {
            backend = ExoPlayer.Builder(compose.activity).build()
            val normal = MediaItem.Builder().setMediaId("offline-normal").setUri("asset:///offline.mp4")
                .setMediaMetadata(MediaMetadata.Builder().setTitle("Normal video").build()).build()
            backend.setMediaItems(listOf(normal, normal.buildUpon().setMediaId("offline-queued").build()), 0, 12000)
            session = MediaSession.Builder(compose.activity, backend).build()
            future = MediaController.Builder(compose.activity, session.token).buildAsync()
        }
        controller = future.get(10, TimeUnit.SECONDS)
        try {
            compose.runOnIdle {
                state = offlineState()
                state.controller = controller
                state.onConnected()
                assertEquals("offline-normal", state.current?.id)
                state.enterShortsMode()
                assertTrue(state.inShortsMode)
                assertFalse(state.expanded)
                assertFalse(controller.playWhenReady)
                // A second entry (e.g. recomposition) must not overwrite the suspended video.
                state.enterShortsMode()
                state.openShort(VideoItem("offline-short", "Short", "", null, null, null, 20, 0, null, isShort = true))
                assertEquals("offline-short", state.current?.id)
                assertFalse(state.firstFrameRendered)
                state.exitShortsMode()
                assertFalse(state.inShortsMode)
                assertFalse(state.expanded)
                assertEquals("offline-normal", state.current?.id)
                assertEquals(2, controller.mediaItemCount)
                assertEquals("offline-queued", controller.getMediaItemAt(1).mediaId)
                assertEquals(12000L, controller.currentPosition)
                assertFalse(controller.playWhenReady)
                // Repeated exit must not discard the restored mini-player.
                state.exitShortsMode()
                assertEquals("offline-normal", state.current?.id)
            }
        } finally {
            compose.runOnIdle { controller.release(); session.release(); backend.release() }
        }
    }
}
