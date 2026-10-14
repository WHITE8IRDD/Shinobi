package com.shinobi.app

import android.content.Intent
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * State for the vertical Shorts feed. Learns from [LocalHistory] so Shorts match the style
 * and taste of the user's recent videos and searches, and supports jumping directly to any
 * Short tapped on the Home or Search screen.
 */
@Stable
class ShortsFeedState(private val scope: CoroutineScope) {
    var shorts by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var selectedTopic by mutableStateOf<String?>(null)
        private set
    var jumpIndex by mutableIntStateOf(0)
        private set
    var jumpGeneration by mutableIntStateOf(0)
        private set

    private var job: Job? = null
    private var moreJob: Job? = null
    private var nextToken: Pair<String, String>? = null
    private var pageCounter = 0
    /** Set when a page adds nothing new, so the feed stops asking for more of the same. */
    private var exhausted = false

    val hasMore: Boolean get() = !exhausted

    private val fallbackTopics = listOf(
        "satisfying science #shorts",
        "amazing technology #shorts",
        "nature wildlife #shorts",
        "cooking tips #shorts",
        "space astronomy #shorts",
        "creative design #shorts",
    )

    fun ensureLoaded() {
        if (shorts.isEmpty() && !loading) loadInitial()
    }

    /** Opens the Shorts feed starting immediately at [initial], followed by [shelf]. */
    fun openWith(initial: VideoItem, shelf: List<VideoItem> = emptyList()) {
        val first = initial.copy(isShort = true)
        val rest = shelf.filter { it.id != first.id }.map { it.copy(isShort = true) }
        val existing = shorts.filter { s -> s.id != first.id && rest.none { it.id == s.id } }
        shorts = listOf(first) + rest + existing
        jumpIndex = 0
        jumpGeneration++
        StreamCache.prefetch(first.id)
        rest.take(2).forEach { StreamCache.prefetch(it.id) }
        if (shorts.size < 8 && !loading) {
            loadMore(seedItem = first)
        }
    }

    fun selectTopic(topic: String?) {
        val next = topic?.takeIf { it != selectedTopic }
        selectedTopic = next
        shorts = emptyList()
        exhausted = false
        loadInitial()
    }

    fun refresh() {
        shorts = emptyList()
        exhausted = false
        loadInitial()
    }

    private fun buildQueries(seedItem: VideoItem? = null): List<String> {
        val topic = selectedTopic
        if (topic != null) {
            return listOf("$topic #shorts", topic)
        }
        val out = LinkedHashSet<String>()
        seedItem?.let {
            val q = TasteEngine.extractTasteQuery(it)
            if (q.isNotBlank()) out.add("$q #shorts")
        }
        val seeds = TasteEngine.buildTasteSeeds(LocalHistory.entries, LocalHistory.recentQueries)
        seeds.tasteQueries.forEach { q ->
            out.add("$q #shorts")
        }
        if (out.isEmpty()) {
            val start = (pageCounter++) % fallbackTopics.size
            out.add(fallbackTopics[start])
            out.add(fallbackTopics[(start + 1) % fallbackTopics.size])
        }
        return out.take(3)
    }

    private fun loadInitial() {
        loading = true
        error = null
        job?.cancel()
        val queries = buildQueries()
        job = scope.launch {
            runCatching {
                fetchShortsBatch(queries)
            }.onSuccess { (items, next) ->
                shorts = items
                nextToken = next
                jumpIndex = 0
                jumpGeneration++
                items.take(3).forEach { StreamCache.prefetch(it.id) }
            }.onFailure {
                error = YouTube.friendlyError(it)
            }
            loading = false
        }
    }

    fun loadMore(seedItem: VideoItem? = null) {
        if (loading || exhausted || moreJob?.isActive == true) return
        val tokenPair = nextToken
        val queries = buildQueries(seedItem)
        moreJob = scope.launch {
            runCatching {
                coroutineScope {
                    val fromContinuation = tokenPair?.let { (q, tok) ->
                        async(Dispatchers.IO) {
                            runCatching { InnerTube.searchPage(q, tok) }.getOrNull()?.let { q to it }
                        }
                    }
                    val freshQuery = queries.firstOrNull { it != tokenPair?.first } ?: fallbackTopics[(pageCounter++) % fallbackTopics.size]
                    val fromFresh = async(Dispatchers.IO) {
                        runCatching { InnerTube.searchPage(freshQuery) }.getOrNull()?.let { freshQuery to it }
                    }
                    val contRes = fromContinuation?.await()
                    val freshRes = fromFresh.await()
                    val gathered = buildList {
                        contRes?.second?.let { p ->
                            addAll(p.shorts)
                            addAll(p.videos.filter { it.isShort || it.durationSec in 1..60 }.map { it.copy(isShort = true) })
                        }
                        freshRes?.second?.let { p ->
                            addAll(p.shorts)
                            addAll(p.videos.filter { it.isShort || it.durationSec in 1..60 }.map { it.copy(isShort = true) })
                        }
                    }
                    nextToken = contRes?.second?.next?.let { contRes.first to it }
                        ?: freshRes?.second?.next?.let { freshRes.first to it }
                    val seen = shorts.mapTo(HashSet()) { it.id }
                    val added = gathered.filter { it.id.isNotBlank() && it.id !in LocalHistory.dislikedIds && seen.add(it.id) }
                    if (added.isNotEmpty()) {
                        shorts = shorts + added
                    } else if (gathered.isNotEmpty()) {
                        // Everything the page offered was already on screen: this query is spent.
                        exhausted = true
                    } else {
                        exhausted = true
                    }
                }
            }
        }
    }

    private suspend fun fetchShortsBatch(queries: List<String>): Pair<List<VideoItem>, Pair<String, String>?> = coroutineScope {
        val pages = queries.map { q ->
            async(Dispatchers.IO) {
                val page = runCatching { InnerTube.searchPage(q) }.getOrNull()
                val items = if (page != null && (page.shorts.isNotEmpty() || page.videos.isNotEmpty())) {
                    page.shorts + page.videos.filter { it.isShort || it.durationSec in 1..90 }.map { it.copy(isShort = true) }
                } else {
                    runCatching { YouTube.search(q) }.getOrDefault(emptyList())
                        .filter { it.isShort || it.durationSec in 1..90 }
                        .map { it.copy(isShort = true) }
                }
                Triple(q, items, page?.next)
            }
        }.awaitAll()

        val combined = LinkedHashMap<String, VideoItem>()
        val disliked = LocalHistory.dislikedIds
        val maxLen = pages.maxOfOrNull { it.second.size } ?: 0
        for (i in 0 until maxLen) {
            for ((_, list, _) in pages) {
                val item = list.getOrNull(i) ?: continue
                if (item.id.isNotBlank() && item.id !in disliked) {
                    combined.putIfAbsent(item.id, item.copy(isShort = true))
                }
            }
        }
        val next = pages.firstNotNullOfOrNull { (q, _, tok) -> tok?.let { q to it } }
        if (combined.isEmpty()) throw java.io.IOException(Loc.t("Couldn't load Shorts"))
        combined.values.toList() to next
    }
}

// ── Shorts Shelf (Home & Search) ──────────────────────────────────────────────

@Composable
fun ShortsBadgeIcon(modifier: Modifier = Modifier, size: Dp = 26.dp) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(Brush.linearGradient(listOf(c.primary, c.primary.copy(alpha = 0.82f)))),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.48f)) {
            val path = Path().apply {
                moveTo(this@Canvas.size.width * 0.22f, this@Canvas.size.height * 0.12f)
                lineTo(this@Canvas.size.width * 0.88f, this@Canvas.size.height * 0.50f)
                lineTo(this@Canvas.size.width * 0.22f, this@Canvas.size.height * 0.88f)
                close()
            }
            drawPath(path, color = c.onPrimary, style = Fill)
        }
    }
}

/**
 * Horizontal shelf of vertical 9:16 Short cards shown on Home and in Search results.
 * Tapping any card opens the vertical Shorts player starting at that Short.
 */
@Composable
fun ShortsShelf(
    shorts: List<VideoItem>,
    onOpenShort: (VideoItem, List<VideoItem>) -> Unit,
    modifier: Modifier = Modifier,
    title: String = Loc.t("Shorts"),
    onViewAll: (() -> Unit)? = null,
) {
    if (shorts.isEmpty()) return
    val c = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShortsBadgeIcon(size = 26.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                modifier = Modifier.weight(1f),
                fontFamily = RoundedHeavy,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp,
                letterSpacing = (-0.5).sp,
            )
            if (onViewAll != null) {
                Text(
                    Loc.t("View all"),
                    color = c.primary,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .pressable(onClick = onViewAll)
                        .background(c.primaryContainer.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(shorts.take(14), key = { "short-shelf-" + it.id }) { short ->
                ShortShelfCard(short, onClick = { onOpenShort(short, shorts) })
            }
        }
    }
}

@Composable
private fun ShortShelfCard(short: VideoItem, onClick: () -> Unit) {
    val menu = LocalVideoMenu.current
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    val thumbUrl = short.thumbnail ?: "https://i.ytimg.com/vi/${short.id}/oar2.jpg"
    Box(
        modifier = Modifier
            .width(146.dp)
            .aspectRatio(9f / 16f)
            .prefetchOnPress(short.id)
            .pressable(pressedScale = 0.96f, onLongClick = { menu(short) }, onClick = onClick)
            .clip(shape)
            .background(c.surfaceContainerHighest)
            .border(1.dp, c.outlineVariant.copy(alpha = 0.22f), shape),
    ) {
        Thumbnail(thumbUrl, Modifier.fillMaxSize(), radius = 22, blur = SensitiveFilter.shouldBlur(short))
        // Bottom gradient scrim so title and view count stay readable over any frame.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.52f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.82f),
                    ),
                ),
        )
        // Small Shorts pill in the top-right corner.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Text("SHORT", color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Black, letterSpacing = 0.4.sp)
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 10.dp),
        ) {
            Text(
                short.title,
                style = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Content),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (short.views > 0) {
                Spacer(Modifier.height(3.dp))
                Text(
                    Loc.views(short.views),
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

// ── Full-screen Vertical Shorts Feed ──────────────────────────────────────────

/**
 * The backdrop every Shorts surface shares. One constant, so the vertical feed, the shell's
 * bottom vignette and the loading state can never be three slightly different blacks (they
 * were, and the seam was visible under the tab bar).
 */
val ShortsBackdrop = Color(0xFF0B090B)

/**
 * Focus state for the Shorts tab, shared with the app shell: while a Short plays, the screen
 * belongs to the video — the floating tab bar leaves with the rest of the controls, exactly as
 * it does in YouTube's Shorts (product owner, 2026-10-14).
 */
@Stable
class ShortsFocusState {
    /** Overlay controls (header, chips, action rail, title) are on screen. */
    var chromeVisible by mutableStateOf(true)
    /** True while the Shorts tab owns the screen, so the shell can hide the tab bar. */
    var engaged by mutableStateOf(false)
}

@Composable
fun ShortsScreen(
    state: ShortsFeedState,
    player: PlayerState,
    focus: ShortsFocusState,
    bottomInset: Dp,
    showSurface: Boolean,
    onSearch: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val view = LocalView.current
    val context = LocalContext.current
    var commentsOpenFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { state.ensureLoaded() }

    // Leave Shorts playback mode when ShortsScreen leaves composition.
    DisposableEffect(Unit) {
        onDispose {
            if (player.inShortsMode) player.exitShortsMode()
        }
    }

    val shorts = state.shorts
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { shorts.size },
    )

    // Jump to target page when a Short is opened from Home or Search.
    LaunchedEffect(state.jumpGeneration) {
        if (shorts.isNotEmpty() && state.jumpIndex in shorts.indices) {
            pagerState.scrollToPage(state.jumpIndex)
        }
    }

    // Play the active Short and prefetch the next two pages.
    val currentShort = shorts.getOrNull(pagerState.currentPage)
    LaunchedEffect(currentShort?.id, showSurface) {
        if (currentShort != null && showSurface) {
            commentsOpenFor = null
            player.openShort(currentShort)
            shorts.getOrNull(pagerState.currentPage + 1)?.let { StreamCache.prefetch(it.id) }
            shorts.getOrNull(pagerState.currentPage + 2)?.let { StreamCache.prefetch(it.id) }
        }
    }

    // Load more Shorts as the user swipes near the end of the feed.
    LaunchedEffect(pagerState.currentPage, shorts.size) {
        if (shorts.isNotEmpty() && pagerState.currentPage >= shorts.size - 3) {
            state.loadMore(seedItem = currentShort)
        }
    }

    // ── Focus mode ────────────────────────────────────────────────────────────
    // While a Short plays, every control fades away and the video has the whole screen. A tap
    // brings the controls back (and pauses, as a tap does anywhere else in Shorts). Landing on
    // a new Short, or coming back to the tab, always starts with the controls on screen.
    SideEffect { focus.engaged = showSurface }
    LaunchedEffect(currentShort?.id, showSurface) { if (showSurface) focus.chromeVisible = true }
    LaunchedEffect(focus.chromeVisible, player.isPlaying, AppSettings.shortsAutoHide, commentsOpenFor) {
        if (AppSettings.shortsAutoHide && focus.chromeVisible && player.isPlaying && commentsOpenFor == null) {
            delay(2_600)
            focus.chromeVisible = false
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            focus.chromeVisible = true
            focus.engaged = false
        }
    }

    val tasteTopics = remember(LocalHistory.tasteKey) {
        TasteEngine.buildTasteSeeds(LocalHistory.entries, LocalHistory.recentQueries).topicPills.take(5)
    }
    val chrome = focus.chromeVisible

    Box(
        Modifier
            .fillMaxSize()
            .background(ShortsBackdrop),
    ) {
        when {
            state.loading && shorts.isEmpty() -> {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.height(14.dp))
                    Text(Loc.t("Loading Shorts…"), color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            state.error != null && shorts.isEmpty() -> {
                ShortsMessage(
                    icon = Icons.Rounded.CloudOff,
                    title = Loc.t("Couldn't load Shorts"),
                    body = state.error.orEmpty(),
                    actionLabel = Loc.t("Try again"),
                    onAction = state::refresh,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            shorts.isNotEmpty() -> {
                VerticalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    key = { index -> shorts.getOrNull(index)?.id ?: index },
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val item = shorts.getOrNull(page) ?: return@VerticalPager
                    val isActivePage = page == pagerState.currentPage && showSurface
                    ShortPageContent(
                        item = item,
                        player = player,
                        isActivePage = isActivePage,
                        chromeVisible = chrome,
                        bottomInset = bottomInset,
                        onOpenComments = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            commentsOpenFor = item.id
                        },
                        onShare = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/shorts/${item.id}")
                                .putExtra(Intent.EXTRA_SUBJECT, item.title)
                            context.startActivity(Intent.createChooser(send, null))
                        },
                        onUserTap = { focus.chromeVisible = true },
                    )
                }
            }
        }

        // Top overlay: Shorts header + topic filter chips + Search button. It leaves with the
        // rest of the chrome, so nothing of the app sits over the video in focus mode.
        AnimatedVisibility(
            visible = chrome,
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 5 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(180)) { -it / 5 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.68f),
                            0.7f to Color.Black.copy(alpha = 0.25f),
                            1f to Color.Transparent,
                        ),
                    )
                    .statusBarsPadding()
                    .padding(top = 10.dp, bottom = 14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShortsBadgeIcon(size = 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        Loc.t("Shorts"),
                        color = Color.White,
                        fontFamily = RoundedHeavy,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 24.sp,
                        letterSpacing = (-0.7).sp,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .pressable(onClick = onSearch)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Search, Loc.t("Search"), tint = Color.White, modifier = Modifier.size(21.dp))
                    }
                }
                if (tasteTopics.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ShortsTopicChip(
                            label = Loc.t("For you"),
                            selected = state.selectedTopic == null,
                            icon = Icons.Rounded.AutoAwesome,
                            onClick = { state.selectTopic(null) },
                        )
                        tasteTopics.forEach { topic ->
                            ShortsTopicChip(
                                label = topic,
                                selected = state.selectedTopic.equals(topic, ignoreCase = true),
                                onClick = { state.selectTopic(topic) },
                            )
                        }
                    }
                }
            }
        }

        // Comments panel over the active Short
        if (commentsOpenFor != null) {
            val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val screenH = LocalConfiguration.current.screenHeightDp.dp
            CommentsPanel(
                state = player.comments,
                partialTop = statusTop + screenH * 0.28f,
                onClose = { commentsOpenFor = null },
            )
        }
    }
}

/**
 * A message on the Shorts backdrop, in Shorts' own ink: the themed [EmptyState] is built for
 * paper-colored surfaces and reads as a bright card floating on the black video space.
 */
@Composable
private fun ShortsMessage(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Boolean = false,
) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(64.dp).clip(SunnyShape).background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            if (progress) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
            } else {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            color = Color.White,
            fontFamily = RoundedHeavy,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 20.sp,
            letterSpacing = (-0.5).sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (body.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier
                .pressable(onClick = onAction)
                .height(44.dp)
                .clip(CircleShape)
                .background(c.primary)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(actionLabel, color = c.onPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ShortsTopicChip(
    label: String,
    selected: Boolean,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .pressable(onClick = onClick)
            .clip(CircleShape)
            .background(if (selected) c.primary else Color.Black.copy(alpha = 0.48f))
            .border(
                width = 1.dp,
                color = if (selected) c.primary else Color.White.copy(alpha = 0.22f),
                shape = CircleShape,
            )
            .padding(horizontal = 13.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) c.onPrimary else Color.White,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            label,
            color = if (selected) c.onPrimary else Color.White,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun ShortPageContent(
    item: VideoItem,
    player: PlayerState,
    isActivePage: Boolean,
    chromeVisible: Boolean,
    bottomInset: Dp,
    onOpenComments: () -> Unit,
    onShare: () -> Unit,
    onUserTap: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val view = LocalView.current
    val menu = LocalVideoMenu.current
    val openChannel = LocalChannelOpener.current

    val details = if (isActivePage && player.current?.id == item.id) player.details else null
    val resolvedItem = details?.item ?: item
    val channelName = resolvedItem.channel.ifBlank { item.channel }
    val channelUrl = resolvedItem.channelUrl ?: item.channelUrl
    val avatarUrl = resolvedItem.avatar ?: item.avatar

    // Pause/Play feedback indicator on single tap
    var showTapPauseIcon by remember { mutableStateOf(false) }
    var tapIndicatorPlaying by remember { mutableStateOf(true) }
    LaunchedEffect(showTapPauseIcon, tapIndicatorPlaying) {
        if (showTapPauseIcon) {
            delay(650)
            showTapPauseIcon = false
        }
    }

    // Double-tap heart burst animation
    val heartScale = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val likeStatus = if (isActivePage && player.current?.id == item.id) {
        player.effectiveLikeStatus
    } else {
        LocalHistory.likeStatus(item.id)
    }
    val isSaved = if (isActivePage && player.current?.id == item.id) {
        player.inWatchLater
    } else {
        LocalHistory.isInWatchLater(item.id)
    }
    val isSubscribed = if (isActivePage && player.current?.id == item.id) {
        player.effectiveSubscribed
    } else {
        LocalHistory.isSubscribed(channelUrl)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B090A))
            .pointerInput(item.id, isActivePage) {
                detectTapGestures(
                    onTap = {
                        if (isActivePage) {
                            onUserTap()
                            tapIndicatorPlaying = !player.isPlaying
                            showTapPauseIcon = true
                            player.togglePlay()
                        }
                    },
                    onDoubleTap = {
                        if (isActivePage) {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            if (player.effectiveLikeStatus != LikeStatus.Like) {
                                player.setLike(LikeStatus.Like)
                            }
                            scope.launch {
                                heartScale.snapTo(0.3f)
                                heartScale.animateTo(1.15f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
                                delay(320)
                                heartScale.animateTo(0f, tween(200))
                            }
                        }
                    },
                    onLongPress = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        menu(resolvedItem)
                    },
                )
            },
    ) {
        val thumbUrl = item.thumbnail ?: "https://i.ytimg.com/vi/${item.id}/oar2.jpg"
        Thumbnail(thumbUrl, Modifier.fillMaxSize(), radius = 0, blur = SensitiveFilter.shouldBlur(resolvedItem))

        if (isActivePage && !AudioOnly.enabled) {
            val aspect = player.videoAspect.takeIf { it > 0f } ?: (9f / 16f)
            Box(
                Modifier
                    .fillMaxSize()
                    .aspectFit(aspect, cover = aspect <= 1.05f)
                    .clipToBounds(),
            ) {
                LocalVideoSurface.current(Modifier.fillMaxSize())
            }
        }

        // Bottom gradient vignette for crisp text legibility
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to Color.Transparent,
                        0.82f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Black.copy(alpha = 0.88f),
                    ),
                ),
        )

        // Buffering spinner when active and loading
        if (isActivePage && (player.isBuffering || (player.loading && player.details == null))) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center).size(42.dp),
            )
        }

        // Single-tap Play/Pause pop indicator
        AnimatedVisibility(
            visible = showTapPauseIcon,
            enter = fadeIn(tween(100)) + scaleIn(initialScale = 0.7f, animationSpec = spring(dampingRatio = 0.6f)),
            exit = fadeOut(tween(220)) + scaleOut(targetScale = 1.2f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (tapIndicatorPlaying) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(38.dp),
                )
            }
        }

        // Double-tap Like burst
        if (heartScale.value > 0.01f) {
            Icon(
                Icons.Rounded.Favorite,
                contentDescription = null,
                tint = c.primary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(96.dp)
                    .graphicsLayer {
                        scaleX = heartScale.value
                        scaleY = heartScale.value
                        alpha = heartScale.value.coerceIn(0f, 1f)
                    },
            )
        }

        // Right-side vertical action rail. In focus mode it is gone entirely (not merely
        // transparent), so nothing invisible can swallow a tap meant for the video.
        AnimatedVisibility(
            visible = chromeVisible || !isActivePage,
            enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.94f),
            exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.94f),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
        Column(
            modifier = Modifier
                .padding(end = 12.dp, bottom = bottomInset + 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val likesCount = if (isActivePage && player.current?.id == item.id) player.displayLikes else -1L
            ShortActionItem(
                icon = if (likeStatus == LikeStatus.Like) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp,
                label = likesCount.takeIf { it > 0 }?.let(::formatCount) ?: Loc.t("Like"),
                active = likeStatus == LikeStatus.Like,
                flourish = Flourish.Pop,
                onClick = { if (isActivePage) player.setLike(LikeStatus.Like) },
            )
            ShortActionItem(
                icon = if (likeStatus == LikeStatus.Dislike) Icons.Rounded.ThumbDown else Icons.Outlined.ThumbDown,
                label = Loc.t("Dislike"),
                active = likeStatus == LikeStatus.Dislike,
                flourish = Flourish.Droop,
                onClick = { if (isActivePage) player.setLike(LikeStatus.Dislike) },
            )
            val commentTotal = if (isActivePage) player.comments.total else -1
            ShortActionItem(
                icon = Icons.Outlined.ChatBubbleOutline,
                label = commentTotal.takeIf { it > 0 }?.let { formatCount(it.toLong()) } ?: Loc.t("Comments"),
                active = false,
                onClick = onOpenComments,
            )
            ShortActionItem(
                icon = if (isSaved) Icons.Rounded.WatchLater else Icons.Outlined.WatchLater,
                label = if (isSaved) Loc.t("Saved") else Loc.t("Save"),
                active = isSaved,
                flourish = Flourish.Drop,
                onClick = { if (isActivePage) player.toggleWatchLater() },
            )
            ShortActionItem(
                icon = Icons.Rounded.Share,
                label = Loc.t("Share"),
                active = false,
                onClick = onShare,
            )
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .pressable { menu(resolvedItem) }
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.MoreVert, Loc.t("More options"), tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        }

        // Bottom-left metadata (creator + Subscribe + title + views)
        AnimatedVisibility(
            visible = chromeVisible || !isActivePage,
            enter = fadeIn(tween(180)) + slideInVertically(tween(200)) { it / 4 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(170)) { it / 4 },
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 78.dp, bottom = bottomInset + 22.dp),
        ) {
            if (channelName.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .pressable(enabled = channelUrl != null) { channelUrl?.let(openChannel) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChannelAvatar(avatarUrl, channelName, size = 36)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (channelName.startsWith("@")) channelName else "@${channelName.replace(" ", "")}",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (channelUrl != null || channelName.isNotBlank()) {
                        Spacer(Modifier.width(10.dp))
                        val subActive = isSubscribed == true
                        Box(
                            modifier = Modifier
                                .squishOn(subActive)
                                .pressable { if (isActivePage) player.toggleSubscribe() }
                                .clip(CircleShape)
                                .background(if (subActive) Color.White.copy(alpha = 0.22f) else c.primary)
                                .padding(horizontal = 13.dp, vertical = 6.dp),
                        ) {
                            Text(
                                if (subActive) Loc.t("Subscribed") else Loc.t("Subscribe"),
                                color = if (subActive) Color.White else c.onPrimary,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
            Text(
                resolvedItem.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val metaParts = listOfNotNull(
                resolvedItem.views.takeIf { it > 0 }?.let { Loc.views(it) },
                resolvedItem.uploaded?.ifBlank { null },
            )
            if (metaParts.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    BidiText.join(metaParts, rtl = Loc.isRtl),
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        }

        // Bottom seek line: the ticking position is read inside its own leaf, so
        // a playing Short never recomposes this whole page. Drag it to scrub.
        ShortSeekBar(player, isActivePage, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun ShortActionItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    flourish: Flourish = Flourish.Pop,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.pressable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(if (active) c.primary else Color.Black.copy(alpha = 0.44f)),
            contentAlignment = Alignment.Center,
        ) {
            FlourishIcon(
                icon = icon,
                description = label,
                active = active,
                kind = flourish,
                tint = if (active) c.onPrimary else Color.White,
                size = 22.dp,
                sparkColor = c.primary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = Color.White,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * The seek line under a Short: a thin progress bar at the very bottom with drag-to-scrub,
 * like YouTube Shorts. The drag strip is the slim band along the bottom edge only, so it never
 * overlaps the action rail, the title block or the tap/double-tap/long-press gestures on the
 * video, and a vertical swipe still turns the page. While scrubbing, a small time chip rides
 * the finger ("0:07 / 0:45"). Reading the ticking position in here keeps the page around it
 * out of the per-tick recomposition while the video runs.
 */
@Composable
private fun BoxScope.ShortSeekBar(player: PlayerState, isActivePage: Boolean, modifier: Modifier = Modifier) {
    if (!isActivePage || player.durationMs <= 0) return
    val c = MaterialTheme.colorScheme
    val duration = player.durationMs
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val progress by animateFloatAsState(
        targetValue = dragFraction ?: (player.positionMs.toFloat() / duration).coerceIn(0f, 1f),
        animationSpec = if (dragFraction != null) snap() else tween(150),
        label = "shortProgress",
    )
    val dragging = dragFraction != null
    Box(modifier.fillMaxWidth().height(22.dp)) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(if (dragging) 5.dp else 3.dp)
                .background(Color.White.copy(alpha = 0.24f)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(c.primary),
            )
        }
        // Tap to jump; horizontal drag to scrub. Neither claims vertical moves, so the pager
        // still turns the page and the video's own gestures are untouched outside this strip.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        dragFraction = null
                        player.seekTo((offset.x / size.width * duration).toLong())
                    }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            dragFraction?.let { player.seekTo((it * duration).toLong()) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
        )
        val drag = dragFraction
        if (drag != null) {
            Text(
                formatDuration((drag * duration).toLong()) + " / " + formatDuration(duration),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            val x = (drag * constraints.maxWidth - placeable.width / 2f)
                                .coerceIn(0f, (constraints.maxWidth - placeable.width).coerceAtLeast(0f))
                            placeable.place(x.toInt(), 0)
                        }
                    }
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
