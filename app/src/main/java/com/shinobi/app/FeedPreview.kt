package com.shinobi.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.ByteArrayInputStream
import kotlin.math.abs

/**
 * Silent inline previews on Home, as the YouTube app plays them: when the list settles with a
 * video card in view, that card's thumbnail gives way to a few seconds of the video, muted,
 * then returns. A tap still opens the video as before.
 *
 * One small in-process player serves every card in turn (never two at once), separate from
 * the playback service so the real player, its notification and audio focus are untouched.
 * It plays a single low rung (360p or so) through the same generated-DASH route the player
 * uses, and the extraction it needs is the one the player would do on a tap — so a previewed
 * video also opens faster. Previews step aside while a video is playing, and stop the moment
 * the list scrolls, so they never compete with a scroll for frames.
 */
@OptIn(UnstableApi::class)
object FeedPreview {
    const val ALWAYS = "always"
    const val WIFI = "wifi"
    const val OFF = "off"

    /** How long a preview runs before the thumbnail returns. */
    private const val PREVIEW_MS = 12_000L

    /** The card being previewed (loading or playing); null when none. */
    var activeId by mutableStateOf<String?>(null)
        private set

    /** True once the first frame has rendered, i.e. the surface should be shown. */
    var showing by mutableStateOf(false)
        private set

    /** Set by the app shell: previews wait while a video is playing. */
    @Volatile var suppressed: () -> Boolean = { false }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var job: Job? = null

    /**
     * Whether previews may play right now, per the setting and the connection. On low-RAM
     * phones (2–3 GB) previews are off unless the user explicitly chose "Always": the
     * second decoder they keep warm is exactly what those devices cannot spare.
     */
    fun allowed(): Boolean = when (AppSettings.feedPreviews) {
        OFF -> false
        WIFI -> Net.isUnmetered()
        ALWAYS -> true
        else -> false
    } && !(DeviceRam.lowRam && AppSettings.feedPreviews != ALWAYS) && !suppressed()

    /**
     * The card in view changed. [videoId] null (or the same id) is a no-op; otherwise the
     * running preview stops and the new one starts once its streams are known.
     */
    fun focus(context: Context, videoId: String?) {
        if (videoId == activeId) return
        stop()
        if (videoId == null || !allowed()) return
        activeId = videoId
        val app = context.applicationContext
        job = scope.launch {
            try {
                // Extraction on IO: shared with the player's cache, so a tap after a preview is instant.
                val source = withContext(Dispatchers.IO) {
                    val info = StreamCache.getOrLoad(videoId)
                    previewSource(app, info, videoId)
                } ?: run { Log.i("Shinobi", "no preview stream for $videoId"); stop(); return@launch }
                if (activeId != videoId) return@launch
                val p = obtain(app)
                p.setMediaSource(source)
                p.prepare()
                p.playWhenReady = true
                delay(PREVIEW_MS + 4_000L) // a slow start still gets its few seconds
                if (activeId == videoId) stop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("Shinobi", "preview of $videoId failed: ${e.javaClass.simpleName}: ${e.message}")
                if (activeId == videoId) stop()
            }
        }
    }

    /** Back to the thumbnail; the player is kept (idle) for the next card. */
    fun stop() {
        job?.cancel()
        job = null
        activeId = null
        showing = false
        player?.let { p ->
            p.playWhenReady = false
            p.stop()
            p.clearMediaItems()
        }
    }

    /** Frees the player entirely (the app went to the background). */
    fun release() {
        stop()
        player?.release()
        player = null
    }

    /** A card's TextureView is ready to show the picture. */
    internal fun attach(s: Surface) {
        surface = s
        player?.setVideoSurface(s)
    }

    /** That TextureView went away; ignored if another card's surface has taken over since. */
    internal fun detach(s: Surface) {
        if (surface !== s) return
        surface = null
        player?.clearVideoSurface(s)
    }

    private fun obtain(context: Context): ExoPlayer = player ?: ExoPlayer.Builder(context)
        .setLoadControl(
            // Start after half a second of video; keep the buffer short — this is a glimpse.
            DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 12_000, 500, 1_000).setPrioritizeTimeOverSizeThresholds(true).build(),
        )
        .build()
        .apply {
            volume = 0f
            setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ false)
            repeatMode = Player.REPEAT_MODE_OFF
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            setVideoSurface(surface)
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { showing = true }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) stop()
                }
                override fun onPlayerError(error: PlaybackException) {
                    Log.i("Shinobi", "preview error: ${error.errorCodeName}")
                    stop()
                }
            })
            player = this
        }

    /**
     * A video-only source at a modest height, wrapped in a generated DASH manifest like the
     * player's streams (a whole-file request would be throttled). Null when the video offers
     * no suitable stream.
     */
    private fun previewSource(context: Context, info: StreamInfo, videoId: String): MediaSource? {
        val durationSec = info.duration.takeIf { it > 0 } ?: return null
        val ladder = StreamSelection.autoLadder(info)
        val stream = ladder.filter { it.height <= 480 }.minByOrNull { abs(it.height - 360) } ?: ladder.firstOrNull() ?: return null
        val dataSourceFactory = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(Net.client).setUserAgent(Net.USER_AGENT))
        return runCatching {
            val xml = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(stream.content, stream.itagItem!!, durationSec)
            val manifest = DashManifestParser().parse(Uri.parse(stream.content), ByteArrayInputStream(xml.toByteArray()))
            val item = MediaItem.Builder().setMediaId(videoId).setUri(stream.content).build()
            DashMediaSource.Factory(dataSourceFactory).createMediaSource(manifest, item)
        }.onFailure { Log.i("Shinobi", "preview manifest for $videoId failed: ${it.javaClass.simpleName}") }.getOrNull()
    }
}

/**
 * The preview picture over a card's thumbnail: a TextureView the preview player draws into.
 * It is transparent until the first frame exists (so the thumbnail stays in view), then fades
 * in; the card composes it only while it is the active card, so there is at most one surface
 * in the list.
 */
@Composable
fun FeedPreviewSurface(modifier: Modifier = Modifier) {
    val showing = FeedPreview.showing
    val alpha by animateFloatAsState(if (showing) 1f else 0f, tween(240), label = "previewAlpha")
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
            factory = { context ->
                TextureView(context).apply {
                    isOpaque = false
                    var current: Surface? = null
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                            current = Surface(st).also { FeedPreview.attach(it) }
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            current?.let { FeedPreview.detach(it); it.release() }
                            current = null
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
        )
        if (showing) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.VolumeOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}
