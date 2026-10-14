package com.shinobi.app

import android.content.ComponentName
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The UI's view of playback. Holds the MediaController plus everything the player
 * screens render, as snapshot state so only readers recompose.
 */
@Stable
class PlayerState(private val scope: CoroutineScope) {
    var controller by mutableStateOf<MediaController?>(null)
        internal set

    /** What's loaded (or loading). Null means nothing — no mini-player. */
    var current by mutableStateOf<VideoItem?>(null)
        private set
    var details by mutableStateOf<VideoDetails?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var bufferedMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    /** The player's playback speed (see [PlaybackSpeed]). */
    var speed by mutableFloatStateOf(1f)
        private set
    var speedPanelOpen by mutableStateOf(false)

    init {
        // The service's stream recovery has no player event for its outcome: follow its state.
        scope.launch {
            snapshotFlow { StreamRecovery.recovering to StreamRecovery.failed }.collect { (_, failed) ->
                syncFromPlayer()
                if (failed != null && failed.first == current?.id) {
                    error = failed.second
                    isBuffering = false
                }
            }
        }
    }
    var videoAspect by mutableFloatStateOf(16f / 9f)
        private set
    /** Set once real frames have reported their size; the stream's own numbers no longer apply. */
    private var aspectFromFrames = false

    /**
     * The portrait player's shape: the video's own, within [StageMinAspect]..[StageMaxAspect];
     * in audio only, the short strip that holds just the controls.
     */
    val stageAspect: Float get() = if (AudioOnly.enabled) AudioStageAspect else videoAspect.coerceIn(StageMinAspect, StageMaxAspect)
    var firstFrameRendered by mutableStateOf(false)
        private set
    /** What the decoder is really producing, e.g. "1920×1080 · H.264 · itag 299". */
    var playingFormat by mutableStateOf<String?>(null)
        private set
    /** Height of the frames actually decoded; the quality label shows this when known. */
    var decodedHeight by mutableStateOf<Int?>(null)
        private set

    /** Caption language and parsed cue timeline for the currently open video. */
    var captionTrack by mutableStateOf<CaptionTrack?>(null)
        private set
    var captionCues by mutableStateOf<List<CaptionCue>>(emptyList())
        private set
    var captionsLoading by mutableStateOf(false)
        private set
    var captionsError by mutableStateOf<String?>(null)
        private set
    private var captionsJob: Job? = null
    val captionTracks: List<CaptionTrack> get() = details?.captions.orEmpty()
    val currentCaption: CaptionCue? get() = CaptionParser.cueAt(captionCues, positionMs)

    var expanded by mutableStateOf(false)
    /** True while the vertical Shorts feed owns playback (no mini-player, loops on end). */
    var inShortsMode by mutableStateOf(false)
        private set
    /** Landscape fullscreen requested (button) or entered (rotation). */
    var fullscreen by mutableStateOf(false)
    /** Which bottom sheet is open over the player, if any. */
    var sheet by mutableStateOf<PlayerSheet?>(null)

    val comments = CommentsState(scope)

    // ── Signed-in account state for the open video (full sync) ──
    /** Like/subscribe state from the account; null while loading or signed out. */
    var accountState by mutableStateOf<VideoAccountState?>(null)
        private set
    private var originalLike = LikeStatus.None
    var inWatchLater by mutableStateOf(false)
        private set
    /** A short message after an account action fails (shown briefly over the player). */
    var actionMessage by mutableStateOf<String?>(null)

    private var loadJob: Job? = null
    /** A video picked before the service connection finished; started on connect. */
    private var pendingStart: VideoItem? = null

    // ── Queue ──
    // The player's playlist is always [current, ...queue]: opening a video swaps the
    // current item, auto-advancing drops the finished one. Items carry only an id across
    // the controller boundary, so the full VideoItems are remembered here.
    private val known = HashMap<String, VideoItem>()
    /** Videos after the current one, in play order. */
    var queue by mutableStateOf<List<VideoItem>>(emptyList())
        private set

    fun open(item: VideoItem, expand: Boolean = true) {
        if (inShortsMode) {
            inShortsMode = false
            controller?.repeatMode = Player.REPEAT_MODE_OFF
        }
        if (expand) expanded = true
        if (current?.id == item.id && error == null) return
        known[item.id] = item
        show(item, startPlayback = true)
    }

    /** Plays [item] inside the vertical Shorts feed (looping, no mini-player). */
    fun openShort(item: VideoItem) {
        val shortItem = item.copy(isShort = true)
        inShortsMode = true
        expanded = false
        fullscreen = false
        controller?.repeatMode = Player.REPEAT_MODE_OFF
        if (current?.id == shortItem.id && error == null) {
            if (controller?.isPlaying == false) controller?.play()
            return
        }
        controller?.pause()
        known[shortItem.id] = shortItem
        show(shortItem, startPlayback = true)
    }

    /** Keep just the next Short in the service playlist for bounded Media3 preloading. */
    fun preloadShort(item: VideoItem?) {
        if (!inShortsMode || item == null) return
        val player = controller ?: return
        val next = player.currentMediaItemIndex + 1
        if (next < player.mediaItemCount && player.getMediaItemAt(next).mediaId == item.id) return
        if (next < player.mediaItemCount) player.removeMediaItems(next, player.mediaItemCount)
        known[item.id] = item
        player.addMediaItem(mediaItemFor(item))
    }

    var looping by mutableStateOf(false)
        private set
    fun updateLooping(value: Boolean) {
        looping = value
        controller?.repeatMode = if (value && !inShortsMode) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    /** Stops Shorts playback when leaving the Shorts tab so no mini-player is left behind. */
    fun exitShortsMode() {
        if (!inShortsMode) return
        inShortsMode = false
        controller?.repeatMode = Player.REPEAT_MODE_OFF
        close()
    }

    /**
     * Shows [item] as the current video: details, comments and account state load; with
     * [startPlayback] it also becomes the playing item (otherwise the player already moved
     * to it, e.g. the queue advanced).
     */
    private var openedAt = 0L

    private fun show(item: VideoItem, startPlayback: Boolean) {
        openedAt = android.os.SystemClock.elapsedRealtime()
        Log.i("ShinobiTime", "open ${item.id}")
        if (startPlayback) Net.prewarmMediaConnection()
        current = item
        if (known.size > 128) {
            val keep = queue.mapTo(mutableSetOf(item.id)) { it.id }
            known.keys.filter { it !in keep }.take(known.size - 128).forEach(known::remove)
        }
        details = null
        error = null
        firstFrameRendered = false
        videoAspect = if (item.isShort) 9f / 16f else 16f / 9f
        aspectFromFrames = false
        decodedHeight = null
        playingFormat = null
        loading = true
        sheet = null
        comments.clearFor(item.id)
        accountState = null
        inWatchLater = LocalHistory.isInWatchLater(item.id)
        speedPanelOpen = false
        captionsJob?.cancel()
        captionTrack = null
        captionCues = emptyList()
        captionsLoading = false
        captionsError = null
        LocalHistory.record(item)
        if (Account.signedIn) {
            scope.launch {
                runCatching { InnerTube.videoState(item.id) }
                    .onSuccess { if (current?.id == item.id) { accountState = it; originalLike = it.like } }
                    .onFailure { Log.w(TAG, "account state for ${item.id}", it) }
            }
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            runCatching { YouTube.details(item.id) }
                .onSuccess { d ->
                    if (current?.id != item.id) return@onSuccess
                    Log.i("ShinobiTime", "details +${android.os.SystemClock.elapsedRealtime() - openedAt}ms")
                    details = d
                    current = d.item.copy(isShort = item.isShort || d.item.isShort)
                    LocalHistory.record(current!!)
                    LocalHistory.cacheRelated(item.id, d.related)
                    applyPreferredCaption(d.captions)
                    // The player takes the video's shape before its first frame arrives.
                    if (!aspectFromFrames) d.aspect?.let { videoAspect = it }
                    // Comments wait for the stream data: their request duplicates the video
                    // page fetch and only slowed down the critical path.
                    if (!inShortsMode) comments.load(item.id)
                    known[item.id] = current!!
                    if (!d.playable) error = Loc.t("No playable stream was found for this video.")
                    else if (startPlayback) startPlayback(current!!)
                    // The likeliest next taps (up next, the queue head) extract in the background
                    // now, so they start without the extraction wait when the time comes.
                    if (!inShortsMode) {
                        queue.firstOrNull()?.let { StreamCache.prefetch(it.id) }
                        d.related.firstOrNull { !it.isShort && it.id != item.id }?.let { StreamCache.prefetch(it.id) }
                    }
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    if (current?.id != item.id) return@onFailure
                    Log.w(TAG, "details failed for ${item.id}", it)
                    error = YouTube.friendlyError(it)
                    // Comments wait for details; without this their spinner never stopped.
                    comments.load(item.id)
                }
            loading = false
        }
    }

    private fun mediaItemFor(item: VideoItem) = MediaItem.Builder()
        .setMediaId(item.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(item.title)
                .setArtist(item.channel)
                .setArtworkUri(item.thumbnail?.toUri())
                .setExtras(android.os.Bundle().apply { putBoolean("shinobi_short", inShortsMode && item.isShort) })
                .build(),
        )
        .build()

    private fun startPlayback(item: VideoItem) {
        LocalHistory.record(item)
        val player = controller ?: run { pendingStart = item; return }
        // Pick up where this video was left off (ResumeStore; written by the service).
        val preloaded = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == item.id }
        if (inShortsMode && preloaded != null) {
            swapping = true
            try {
                player.seekTo(preloaded, 0L)
                if (preloaded > 0) player.removeMediaItems(0, preloaded)
            } finally { swapping = false }
            player.prepare()
        } else replaceCurrent(player, item, positionMs = if (inShortsMode) 0L else ResumeStore.startPosition(item.id))
        player.play()
    }

    /**
     * Puts [item] in the current slot, keeping the queue behind it: insert after, jump to
     * it, drop the old one. (setMediaItem would wipe the queue; replaceMediaItem with the
     * same id would keep the old media source, so a quality change wouldn't apply.)
     */
    private fun replaceCurrent(player: MediaController, item: VideoItem, positionMs: Long) {
        if (player.mediaItemCount == 0) {
            player.setMediaItem(mediaItemFor(item), positionMs)
        } else {
            // The controller reports the transition synchronously inside seekTo; the flag
            // stops onPlayerMovedTo from trimming while this swap is half done.
            swapping = true
            try {
                val index = player.currentMediaItemIndex
                player.addMediaItem(index + 1, mediaItemFor(item))
                player.seekTo(index + 1, positionMs)
                player.removeMediaItem(index)
            } finally {
                swapping = false
            }
        }
        player.prepare()
    }

    /** Plays right after the current video. Nothing playing: starts it in the mini-player. */
    fun playNext(item: VideoItem) {
        val player = controller ?: return
        known[item.id] = item
        if (current == null || player.mediaItemCount == 0) { open(item, expand = false); return }
        player.addMediaItem(player.currentMediaItemIndex + 1, mediaItemFor(item))
        actionMessage = Loc.t("Playing next")
        continueIfEnded(player)
    }

    /** Queued after the current video already finished: carry on into it, as YouTube does. */
    private fun continueIfEnded(player: MediaController) {
        if (player.playbackState == Player.STATE_ENDED && player.currentMediaItemIndex + 1 < player.mediaItemCount) {
            player.seekTo(player.currentMediaItemIndex + 1, 0L)
            player.play()
        }
    }

    fun addToQueue(item: VideoItem) {
        val player = controller ?: return
        known[item.id] = item
        if (current == null || player.mediaItemCount == 0) { open(item, expand = false); return }
        player.addMediaItem(mediaItemFor(item))
        actionMessage = Loc.t("Added to queue")
        continueIfEnded(player)
    }

    /** [index] into [queue]. */
    fun playFromQueue(index: Int) {
        val player = controller ?: return
        player.seekTo(player.currentMediaItemIndex + 1 + index, 0L)
        player.play()
    }

    fun removeFromQueue(index: Int) {
        val player = controller ?: return
        player.removeMediaItem(player.currentMediaItemIndex + 1 + index)
    }

    fun clearQueue() {
        val player = controller ?: return
        val from = player.currentMediaItemIndex + 1
        if (from < player.mediaItemCount) player.removeMediaItems(from, player.mediaItemCount)
    }

    private fun rebuildQueue() {
        val player = controller ?: return
        val from = player.currentMediaItemIndex + 1
        queue = (from until player.mediaItemCount).map { i -> itemFor(player.getMediaItemAt(i)) }
        // The service builds a queue item's streams from the cache when the playlist reaches
        // it; extracting the head now means the advance is seamless instead of a stall.
        queue.firstOrNull()?.let { StreamCache.prefetch(it.id) }
    }

    private fun itemFor(media: MediaItem): VideoItem = known[media.mediaId] ?: VideoItem(
        id = media.mediaId,
        title = media.mediaMetadata.title?.toString().orEmpty(),
        channel = media.mediaMetadata.artist?.toString().orEmpty(),
        channelUrl = null,
        thumbnail = media.mediaMetadata.artworkUri?.toString(),
        avatar = null,
        durationSec = 0,
        views = -1,
        uploaded = null,
    ).also { known[media.mediaId] = it }

    /** The player moved on by itself (queue advanced, notification "next"): follow it. */
    private var swapping = false

    private fun onPlayerMovedTo(media: MediaItem) {
        val player = controller ?: return
        if (swapping || media.mediaId.isBlank()) return
        // Keep the playlist as [current, ...queue]: finished items fall off the front.
        if (player.currentMediaItemIndex > 0) player.removeMediaItems(0, player.currentMediaItemIndex)
        if (media.mediaId != current?.id) show(itemFor(media), startPlayback = false)
    }

    // ── SponsorBlock (segments fetched by the service; read here for the UI) ──
    val segments: List<SponsorBlock.Segment>
        get() = current?.id?.let { SponsorBlock.forUi[it] }.orEmpty()

    /** The "Ask" segment playback is inside right now, if any — drives the Skip pill. */
    val askSegment: SponsorBlock.Segment?
        get() = if (!SponsorBlock.enabled) null else segments.firstOrNull {
            it.action == SponsorBlock.Action.Button && positionMs in it.startMs until it.endMs - 500
        }

    fun skipSegment(segment: SponsorBlock.Segment) = seekTo(segment.endMs)

    fun undoSkip(skip: SponsorBlock.Skip) {
        if (skip.videoId == current?.id) seekTo(skip.segment.startMs)
        SponsorBlock.lastSkip = null
    }

    /** After sign-in or sign-out: reload (or drop) the open video's account state. */
    fun refreshAccountState() {
        val item = current ?: return
        accountState = null
        inWatchLater = LocalHistory.isInWatchLater(item.id)
        if (!Account.signedIn) return
        scope.launch {
            runCatching { InnerTube.videoState(item.id) }
                .onSuccess { if (current?.id == item.id) { accountState = it; originalLike = it.like } }
                .onFailure { Log.w(TAG, "account state for ${item.id}", it) }
        }
    }

    val effectiveLikeStatus: LikeStatus
        get() = accountState?.like ?: current?.id?.let(LocalHistory::likeStatus) ?: LikeStatus.None

    val effectiveSubscribed: Boolean?
        get() = accountState?.subscribed ?: run {
            val item = details?.item ?: current ?: return@run null
            val key = item.channelUrl ?: item.channel.takeIf { it.isNotBlank() } ?: return@run null
            LocalHistory.isSubscribed(key)
        }

    /** Like count adjusted for the user's own change since the video opened. */
    val displayLikes: Long
        get() {
            val base = details?.likes ?: return -1
            val now = effectiveLikeStatus
            return base + when {
                now == LikeStatus.Like && originalLike != LikeStatus.Like -> 1
                now != LikeStatus.Like && originalLike == LikeStatus.Like -> -1
                else -> 0
            }
        }

    fun setLike(target: LikeStatus) {
        val item = details?.item ?: current ?: return
        val beforeStatus = effectiveLikeStatus
        val next = if (beforeStatus == target) LikeStatus.None else target
        LocalHistory.setLike(item, next)
        val beforeAccount = accountState
        if (beforeAccount != null) {
            accountState = beforeAccount.copy(like = next)
            scope.launch {
                runCatching { InnerTube.setLike(item.id, next) }
                    .onFailure { accountState = beforeAccount; actionMessage = Loc.t("Couldn't update your like.") }
            }
        }
    }

    fun toggleSubscribe() {
        val item = details?.item ?: current ?: return
        val before = accountState
        val channelId = before?.channelId
            ?: item.channelUrl?.substringAfter("/channel/", "")?.takeIf { it.startsWith("UC") }
        val subscribe = effectiveSubscribed != true
        if (item.channel.isNotBlank()) {
            val refId = channelId ?: item.channelUrl ?: item.channel
            LocalHistory.setSubscribed(
                ChannelRef(id = refId, name = item.channel, avatar = item.avatar, url = item.channelUrl),
                subscribe = subscribe,
            )
        }
        if (before != null && channelId != null) {
            accountState = before.copy(subscribed = subscribe, channelId = channelId)
            scope.launch {
                runCatching { InnerTube.setSubscribed(channelId, subscribe) }
                    .onFailure { accountState = before; actionMessage = Loc.t("Couldn't update your subscription.") }
            }
        } else {
            actionMessage = if (subscribe) "Following ${item.channel}" else "Unfollowed ${item.channel}"
        }
    }

    fun toggleWatchLater() {
        val item = details?.item ?: current ?: return
        val add = !inWatchLater
        inWatchLater = add
        LocalHistory.setWatchLater(item, add)
        if (Account.signedIn) {
            scope.launch {
                runCatching { InnerTube.setInWatchLater(item.id, add) }
                    .onSuccess { actionMessage = if (add) Loc.t("Saved to Watch later") else Loc.t("Removed from Watch later") }
                    .onFailure { inWatchLater = !add; actionMessage = Loc.t("Saved locally on this device"); Log.w(TAG, "watch later ${item.id}", it) }
            }
        } else {
            actionMessage = if (add) Loc.t("Saved to Watch later") else Loc.t("Removed from Watch later")
        }
    }

    /** Long-press Save. Runs here, not in the sheet: closing the sheet would cancel its scope mid-request. */
    fun saveToWatchLater(video: VideoItem) {
        LocalHistory.setWatchLater(video, true)
        if (current?.id == video.id) inWatchLater = true
        if (Account.signedIn) {
            scope.launch {
                runCatching { InnerTube.setInWatchLater(video.id, true) }
                    .onSuccess { actionMessage = Loc.t("Saved to Watch later") }
                    .onFailure { actionMessage = Loc.t("Saved to Watch later on this device"); Log.w(TAG, "watch later ${video.id}", it) }
            }
        } else {
            actionMessage = Loc.t("Saved to Watch later")
        }
    }

    /** Select a caption track (null turns captions off); the language is remembered locally. */
    fun setCaption(track: CaptionTrack?) {
        val safeTrack = track?.let { wanted -> captionTracks.firstOrNull { it.key == wanted.key } }
        if (track != null && safeTrack == null) return
        captionTrack = safeTrack
        CaptionPreference.setLanguage(safeTrack?.languageTag)
        loadCaptionTrack(safeTrack)
    }

    private fun applyPreferredCaption(tracks: List<CaptionTrack>) {
        val preferred = CaptionPreference.languageTag
        val selected = preferred?.let { tag ->
            tracks.firstOrNull { it.languageTag.equals(tag, ignoreCase = true) && !it.autoGenerated }
                ?: tracks.firstOrNull { it.languageTag.equals(tag, ignoreCase = true) }
        }
        captionTrack = selected
        loadCaptionTrack(selected)
    }

    private fun loadCaptionTrack(track: CaptionTrack?) {
        captionsJob?.cancel()
        captionCues = emptyList()
        captionsError = null
        captionsLoading = track != null
        if (track == null) return
        val key = track.key
        captionsJob = scope.launch {
            try {
                val cues = CaptionLoader.load(track)
                if (captionTrack?.key == key) {
                    captionCues = cues
                    if (cues.isEmpty()) captionsError = Loc.t("This video has no readable captions in that track.")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (captionTrack?.key == key) {
                    captionsError = Loc.t("Captions couldn't be loaded. Check your connection and try again.")
                    Log.w(TAG, "captions for ${current?.id} failed", failure)
                }
            } finally {
                if (captionTrack?.key == key) captionsLoading = false
            }
        }
    }

    /**
     * Switches quality (null = Auto). The media source is rebuilt from the cached stream
     * info at the same position, keeping play/pause as it was.
     */
    fun setQuality(height: Int?) {
        QualityPreference.set(height)
        val player = controller ?: return
        val item = details?.item ?: current ?: return
        val position = player.currentPosition
        val playWhenReady = player.playWhenReady
        replaceCurrent(player, item, position)
        player.playWhenReady = playWhenReady
        val info = StreamCache.get(item.id) ?: return
        details = details?.copy(quality = StreamSelection.pick(info).playedHeight?.let { "${it}p" })
    }

    /** Fresh links too: the cached ones may be exactly what YouTube refused. */
    fun retry() = current?.let { item -> StreamCache.invalidate(item.id); current = null; open(item) }

    fun togglePlay() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    /** Seeks by [deltaMs], clamped to the video; returns how far it actually moved. */
    fun seekBy(deltaMs: Long): Long {
        val player = controller ?: return 0L
        val from = player.currentPosition
        seekTo(from + deltaMs)
        return positionMs - from
    }

    fun seekTo(ms: Long) {
        val player = controller ?: return
        val max = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo(ms.coerceIn(0L, max))
        positionMs = player.currentPosition
    }

    /** The ruler is moving: play at [value] now (remembering waits for [commitSpeed]). */
    fun previewSpeed(value: Float) {
        val player = controller ?: return
        val s = PlaybackSpeed.snap(value)
        if (player.playbackParameters.speed != s) player.setPlaybackSpeed(s)
        speed = s
    }

    /**
     * The ruler has landed on [value]. While remembering, it becomes the remembered speed —
     * unless it is 1× coming from another speed, which ends remembering (product owner: "until the
     * user resets it back to 1×").
     */
    fun commitSpeed(value: Float) {
        previewSpeed(value)
        val s = PlaybackSpeed.snap(value)
        if (!PlaybackSpeed.remember) return
        if (PlaybackSpeed.isNormal(s) && !PlaybackSpeed.isNormal(PlaybackSpeed.saved)) PlaybackSpeed.setRemember(false, 1f)
        else PlaybackSpeed.save(s)
    }

    fun toggleRememberSpeed() = PlaybackSpeed.setRemember(!PlaybackSpeed.remember, speed)

    /** Audio only for the whole app: the video track goes off (or back on) right away. */
    fun setAudioOnly(on: Boolean) {
        AudioOnly.set(on)
        applyVideoTrack()
    }

    /**
     * App left the screen: stop fetching video and keep only audio (saves data and battery);
     * back on screen, video resumes. Done through the player's track selection, so the
     * video stream simply stops loading.
     */
    fun setBackground(background: Boolean) {
        wantBackground = background
        applyVideoTrack()
    }

    /**
     * What the screen wants, remembered even before the player connection exists. The
     * service keeps its track settings across screens, so a rebuilt screen (dark-mode switch,
     * process trim) that came back while disconnected left video off for good; the state is
     * now applied on every connect too.
     */
    private var wantBackground = false

    /** Video is off in the background and whenever audio only is on. */
    private fun applyVideoTrack() {
        val off = wantBackground || AudioOnly.enabled
        val player = controller ?: return
        if (player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO) == off) return
        Log.i(TAG, if (off) "video off (${if (AudioOnly.enabled) "audio only" else "background"})" else "video on")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, off)
            .build()
    }

    fun close() {
        loadJob?.cancel()
        captionsJob?.cancel()
        captionTrack = null
        captionCues = emptyList()
        captionsLoading = false
        captionsError = null
        controller?.run {
            repeatMode = Player.REPEAT_MODE_OFF
            stop()
            clearMediaItems()
        }
        queue = emptyList()
        inShortsMode = false
        expanded = false
        fullscreen = false
        sheet = null
        current = null
        details = null
        error = null
        loading = false
    }

    /**
     * Called once the MediaController connects. Starts anything picked while connecting,
     * and if the service was already playing (app reopened mid-video), rebuilds the
     * mini-player from the session's current item.
     */
    internal fun onConnected() {
        syncFromPlayer()
        applyVideoTrack()
        controller?.videoSize?.let(::takeVideoSize)
        pendingStart?.let { pendingStart = null; startPlayback(it); return }
        val player = controller ?: return
        rebuildQueue()
        val playing = player.currentMediaItem ?: return
        if (current != null || playing.mediaId.isBlank()) return
        val meta = playing.mediaMetadata
        val item = VideoItem(
            id = playing.mediaId,
            title = meta.title?.toString().orEmpty(),
            channel = meta.artist?.toString().orEmpty(),
            channelUrl = null,
            thumbnail = meta.artworkUri?.toString(),
            avatar = null,
            durationSec = player.duration.takeIf { it > 0 }?.div(1000) ?: 0L,
            views = -1,
            uploaded = null,
        )
        current = item
        LocalHistory.record(item)
        loadJob = scope.launch {
            runCatching { YouTube.details(item.id) }.onSuccess {
                details = it
                current = it.item
                applyPreferredCaption(it.captions)
            }
        }
    }

    internal fun syncFromPlayer() {
        val player = controller ?: return
        isPlaying = player.isPlaying
        // While the service refetches refused links the player sits idle; show the spinner.
        isBuffering = player.playbackState == Player.STATE_BUFFERING || StreamRecovery.recovering == current?.id
        positionMs = player.currentPosition.coerceAtLeast(0L)
        bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
        durationMs = player.duration.takeIf { it > 0 } ?: ((current?.durationSec ?: 0L) * 1000L)
        speed = player.playbackParameters.speed
    }

    internal val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromPlayer()
            looping = !inShortsMode && player.repeatMode == Player.REPEAT_MODE_ONE
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) rebuildQueue()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem != null) onPlayerMovedTo(mediaItem)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && AppSettings.autoplayNext && !inShortsMode && !looping && queue.isEmpty()) {
                details?.related?.firstOrNull { !it.isShort && it.id != current?.id && it.id !in LocalHistory.dislikedIds }
                    ?.let { open(it, expand = expanded) }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = takeVideoSize(videoSize)

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            controller?.videoSize?.height?.takeIf { it > 0 }?.let(::describeFormat)
        }

        override fun onRenderedFirstFrame() {
            Log.i("ShinobiTime", "first frame +${android.os.SystemClock.elapsedRealtime() - openedAt}ms")
            if (controller?.currentMediaItem?.mediaId == current?.id) firstFrameRendered = true
        }

        override fun onPlayerError(e: PlaybackException) {
            val status = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
            Log.w(TAG, "playback error ${e.errorCodeName}${status?.let { " (HTTP $it)" } ?: ""} for ${current?.id}", e)
            // A refused or expired stream URL: the service is fetching fresh links (StreamRecoverer).
            if (e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && StreamRecovery.recovering == current?.id) return
            error = when (e.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> Loc.t("Lost the connection. Check your network and try again.")
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> Loc.t("YouTube refused the stream. Try again.")
                else -> Loc.t("This video couldn't be played.")
            }
        }
    }

    private fun takeVideoSize(videoSize: VideoSize) {
        if (videoSize.width <= 0 || videoSize.height <= 0 || controller?.currentMediaItem?.mediaId != current?.id) return
        videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        aspectFromFrames = true
        // Auto switches resolution mid-play; the label follows the real frames.
        describeFormat(videoSize.height)
    }

    /** Fills the quality label and the "Playing …" line from the frames actually decoded. */
    private fun describeFormat(height: Int) {
        if (decodedHeight != height) Log.i("ShinobiTime", "now ${height}p at +${android.os.SystemClock.elapsedRealtime() - openedAt}ms")
        decodedHeight = height
        val formats = controller?.currentTracks?.groups.orEmpty()
            .filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
            .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it) } }
        val format = formats.firstOrNull { it.height == height }
        if (format == null) { playingFormat = "${height}p"; return }
        run {
            val codec = when {
                format.codecs?.startsWith("avc") == true -> "H.264"
                format.codecs?.startsWith("vp9") == true || format.codecs?.startsWith("vp09") == true -> "VP9"
                format.codecs?.startsWith("av01") == true -> "AV1"
                else -> format.codecs ?: format.sampleMimeType ?: "?"
            }
            val auto = if (formats.size > 1) Loc.t("Auto") else null
            playingFormat = listOfNotNull(auto, "${format.width}×${format.height}", codec, format.id?.substringAfterLast(":")?.let { "itag $it" }).joinToString(" · ")
        }
    }
}

@Composable
fun rememberPlayerState(): PlayerState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { PlayerState(scope) }
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.getOrNull()?.let { controller ->
                state.controller = controller
                controller.addListener(state.listener)
                state.onConnected()
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            state.controller?.removeListener(state.listener)
            MediaController.releaseFuture(future)
            state.controller = null
        }
    }
    // Position isn't evented; poll it only while something is actually playing.
    LaunchedEffect(state.isPlaying, state.captionTrack?.key) {
        while (isActive && state.isPlaying) {
            state.syncFromPlayer()
            delay(if (state.captionTrack != null) 100 else 250)
        }
    }
    return state
}

enum class PlayerSheet { Quality, Captions, Comments, Queue, Options }

private const val TAG = "Shinobi"
