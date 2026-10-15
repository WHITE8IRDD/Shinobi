package com.shinobi.app

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import kotlinx.coroutines.*

/** Benchmark-only, deterministic fixtures using production components and real local decoding.
 * Never compiled into debug/release. Does NOT benchmark network, extraction or account APIs. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class PerformanceActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var backend: ExoPlayer? = null
    private var session: MediaSession? = null
    private var controller: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scenario = intent.getStringExtra("scenario") ?: "feed"
        if (scenario == "player") requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val cards = (0 until 120).map { index ->
            VideoItem("fixture-$index", "Offline video $index · production feed components", "Creator $index",
                null, null, null, 180, 100_000, "1 day ago", isShort = scenario == "shorts")
        }
        val state = PlayerState(scope) { id ->
            VideoDetails(cards.first().copy(id = id), "Offline benchmark", 0, 0, emptyList(),
                true, null, emptyList())
        }
        if (scenario != "feed") {
            if (scenario == "shorts") state.enterShortsMode()
            val p = ExoPlayer.Builder(this).build().also { backend = it }
            p.setMediaItem(mediaItem(0, scenario))
            p.repeatMode = Player.REPEAT_MODE_ONE
            val s = MediaSession.Builder(this, p).setId("benchmark").build().also { session = it }
            val future = MediaController.Builder(this, s.token).buildAsync()
            future.addListener({
                val c = future.get()
                controller = c
                state.controller = c
                state.onConnected()
                c.addListener(state.listener)
                c.prepare(); c.play()
            }, ContextCompat.getMainExecutor(this))
        }
        setContent {
            ShinobiTheme {
                LaunchedEffect(state) { while (isActive) { state.syncFromPlayer(); delay(250) } }
                val surface = remember {
                    movableContentOf { modifier: Modifier ->
                        VideoSurface(state.controller, modifier, state::onVideoFrame)
                    }
                }
                CompositionLocalProvider(LocalVideoSurface provides surface) {
                    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                        when (scenario) {
                            "feed" -> CollapsingHeaderLayout(header = { ScreenHeader("Offline feed") }) { top ->
                                LazyColumn(Modifier.fillMaxSize().testTag("benchmark-feed"),
                                    contentPadding = PaddingValues(top = top, bottom = 32.dp)) {
                                    items(cards, key = { it.id }, contentType = { "video" }) { VideoCard(it, {}) }
                                }
                            }
                            "player" -> FullscreenPlayer(state, {})
                            "shorts" -> {
                                val pager = rememberPagerState { cards.size }
                                LaunchedEffect(pager.settledPage) {
                                    if (pager.settledPage > 0) {
                                        controller?.setMediaItem(mediaItem(pager.settledPage, scenario))
                                        controller?.prepare(); controller?.play()
                                    }
                                }
                                Column(Modifier.fillMaxSize().background(ShortsBackdrop).statusBarsPadding()) {
                                    VerticalPager(pager, key = { cards[it].id }, beyondViewportPageCount = 0,
                                        modifier = Modifier.weight(1f).testTag("benchmark-shorts")) { page ->
                                        ShortPageContent(cards[page], state, page == pager.settledPage,
                                            true, 0.dp, {}, {}, {})
                                    }
                                    ShortsNavigation(Tab.Shorts, {})
                                }
                            }
                        }
                        // Readiness is a real condition, not a timed guess by the test driver.
                        if (scenario == "feed" || (state.firstFrameRendered && !state.loading)) {
                            Text("Ready", modifier = Modifier.testTag("benchmark-ready"))
                        }
                    }
                }
            }
        }
    }

    private fun mediaItem(page: Int, scenario: String) = MediaItem.Builder()
        .setMediaId("fixture-$page")
        .setUri(if (scenario == "shorts") "asset:///player-short.mp4" else "asset:///player-landscape.mp4")
        .setMediaMetadata(MediaMetadata.Builder().setTitle("Offline benchmark $page").setArtist("Creator").build())
        .build()

    override fun onDestroy() {
        scope.cancel()
        controller?.release(); session?.release(); backend?.release()
        super.onDestroy()
    }
}
