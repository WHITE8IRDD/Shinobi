package com.shinobi.app

import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

// ── Home ───────────────────────────────────────────────────────────────────────

@Composable
fun HomeScreen(
    lib: LibraryData,
    personalized: PersonalizedHomeState,
    contentPadding: PaddingValues,
    onOpen: (VideoItem) -> Unit,
    onOpenShort: (VideoItem, List<VideoItem>) -> Unit,
    onOpenShortsTab: () -> Unit,
    onSearch: () -> Unit,
    onSignIn: () -> Unit,
    onAccount: () -> Unit,
    scrollToTop: Int = 0,
) {
    val signedIn = Account.signedIn
    val tasteKey = LocalHistory.tasteKey
    var showTrending by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val trending = remember { TrendingState(scope) }
    LaunchedEffect(tasteKey, signedIn, Loc.language) {
        delay(300)
        personalized.syncWithTaste("$signedIn|${Loc.language}|$tasteKey")
    }
    CollapsingHeaderLayout(
        resetKey = Triple(scrollToTop, personalized.refreshToken, showTrending),
        header = {
            Column {
                ScreenHeader(title = "Shinobi", brandMark = true,
                    leadingActions = { CircleAction(Icons.Rounded.Search, Loc.t("Search"), onSearch) },
                ) {
                    ThemeToggleAction()
                    AccountAction(onAccount)
                }
                HomeTasteFilterRow(
                    topics = personalized.topics,
                    selected = personalized.selectedTopic,
                    onSelectTopic = { showTrending = false; personalized.selectTopic(it) },
                    onOpenShorts = onOpenShortsTab,
                    onRefresh = { if (showTrending) trending.load(AppSettings.trendingCountry, refresh = true) else personalized.refresh() },
                    trending = showTrending,
                    onTrending = { showTrending = true },
                )
            }
        },
    ) { top ->
        if (showTrending) TrendingContent(trending, contentPadding.withTop(top), top, onOpen)
        else PersonalizedHomeContent(
            state = personalized,
            contentPadding = contentPadding.withTop(top),
            indicatorTop = top,
            scrollToTop = scrollToTop,
            onOpen = onOpen,
            onOpenShort = onOpenShort,
            onOpenShortsTab = onOpenShortsTab,
        )
    }
}

/**
 * One row of the Home "For you" list. With mixing on, Shorts sit between the long videos
 * instead of being confined to their own shelf — the "both together" feed of the setting.
 */
private sealed interface ForYouEntry {
    val key: String

    data class Long(val video: VideoItem) : ForYouEntry {
        override val key: String get() = "fy-v-" + video.id
    }

    data class Short(val video: VideoItem, val shelf: List<VideoItem>) : ForYouEntry {
        override val key: String get() = "fy-s-" + video.id
    }
}

/** Interleaves Shorts among long videos: one after every third video, in the same order. */
private fun buildForYouFeed(
    videos: List<VideoItem>,
    shorts: List<VideoItem>,
    mix: Boolean,
): List<ForYouEntry> {
    val shelf = shorts.distinctBy { it.id }.take(20)
    if (!mix) return videos.map { ForYouEntry.Long(it) }
    if (videos.isEmpty()) return shelf.map { ForYouEntry.Short(it, shelf) }
    val out = ArrayList<ForYouEntry>(videos.size + shelf.size)
    var next = 0
    videos.forEachIndexed { index, video ->
        out += ForYouEntry.Long(video)
        if ((index + 1) % 3 == 0 && next < shelf.size) {
            out += ForYouEntry.Short(shelf[next], shelf)
            next++
        }
    }
    return out
}

@Composable
private fun PersonalizedHomeContent(
    state: PersonalizedHomeState,
    contentPadding: PaddingValues,
    indicatorTop: Dp,
    scrollToTop: Int,
    onOpen: (VideoItem) -> Unit,
    onOpenShort: (VideoItem, List<VideoItem>) -> Unit,
    onOpenShortsTab: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val list = rememberScrollToTop(scrollToTop)
    // Back to the top only when the user asked for a refresh — never on a background load
    // (which used to yank the list to the top while it was being scrolled).
    LaunchedEffect(state.refreshToken) {
        if (state.refreshToken > 0) list.scrollToItem(0)
    }

    val mix = AppSettings.mixShortsInFeed
    val entries = remember(state.videos, state.shorts, mix) {
        buildForYouFeed(state.videos, state.shorts.drop(12), mix)
    }
    val shortsShelf = remember(state.shorts) { state.shorts.distinctBy { it.id }.take(20) }

    PullToRefresh(
        enabled = true,
        refreshing = state.refreshing,
        onRefresh = state::refresh,
        topPadding = indicatorTop,
    ) {
        LazyColumn(state = list, contentPadding = contentPadding) {
            if (shortsShelf.isNotEmpty()) {
                item(key = "home-shorts-top", contentType = "shorts-shelf") {
                    ShortsShelf(shortsShelf.take(12), onOpenShort = onOpenShort, onViewAll = onOpenShortsTab)
                }
            }

            if (entries.isEmpty()) {
                if (state.error != null) {
                    item(key = "home-error") {
                        EmptyState(
                            icon = Icons.Rounded.CloudOff,
                            title = Loc.t("Couldn't load suggestions"),
                            body = state.error.orEmpty(),
                            action = { PillButton(Loc.t("Try again"), state::refresh) },
                        )
                    }
                } else if (state.loading) {
                    // The first load draws its own shape straight away instead of an empty page.
                    item(key = "home-skeleton") { ListSkeleton() }
                } else if (!state.loading && shortsShelf.isEmpty()) {
                    item(key = "home-empty") {
                        EmptyState(
                            icon = Icons.Rounded.AutoAwesome,
                            title = Loc.t("Nothing here yet"),
                            body = Loc.t("Videos and Shorts you watch stay privately on this phone and power your Home feed."),
                            action = { PillButton(Loc.t("Watch Shorts"), onOpenShortsTab) },
                        )
                    }
                }
            } else {
                items(entries, key = { it.key }, contentType = { if (it is ForYouEntry.Long) "video" else "short" }) { entry ->
                    when (entry) {
                        is ForYouEntry.Long -> VideoCard(entry.video, onClick = { onOpen(entry.video) })
                        is ForYouEntry.Short -> ShortFeedCard(
                            short = entry.video,
                            onClick = { onOpenShort(entry.video, entry.shelf) },
                        )
                    }
                }
                // The footer *is* the pagination trigger: it only asks for the next page while
                // it is on screen, and stops for good once a page brings nothing new.
                if (state.error != null) {
                    item(key = "home-page-error") { PillButton(Loc.t("Try again"), state::refresh, modifier = Modifier.padding(16.dp)) }
                } else if (state.hasMore) {
                    item(key = "home-more") {
                        LaunchedEffect(state.videos.size, state.shorts.size) {
                            if (!state.loading && state.hasMore) state.loadMore()
                        }
                        Box(Modifier.fillMaxWidth().padding(28.dp), Alignment.Center) {
                            CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(32.dp))
                        }
                    }
                } else if (state.loading) {
                    item(key = "home-loading") {
                        Box(Modifier.fillMaxWidth().padding(28.dp), Alignment.Center) {
                            CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * A Short mixed into the Home feed: same card family as [VideoCard], portrait thumbnail and a
 * Short badge, and one tap drops straight into the vertical Shorts player at this Short.
 */
@Composable
private fun ShortFeedCard(short: VideoItem, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val menu = LocalVideoMenu.current
    val view = LocalView.current
    val shape = RoundedCornerShape(28.dp)
    val thumbUrl = short.thumbnail ?: "https://i.ytimg.com/vi/${short.id}/oar2.jpg"
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 7.dp)
            .fillMaxWidth()
            .prefetchOnPress(short.id)
            .pressable(
                pressedScale = 0.97f,
                onLongClick = { menu(short) },
                onClick = { view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); onClick() },
            )
            .clip(shape)
            .background(c.surfaceContainerLow)
            .padding(6.dp),
    ) {
        Box {
            Thumbnail(thumbUrl, Modifier.fillMaxWidth().aspectRatio(3f / 4f))
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text("SHORT", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
            }
        }
        Row(modifier = Modifier.padding(start = 8.dp, end = 6.dp, top = 12.dp, bottom = 10.dp)) {
            val openChannel = LocalChannelOpener.current
            Box(Modifier.pressable(enabled = short.channelUrl != null) { short.channelUrl?.let(openChannel) }) {
                ChannelAvatar(short.avatar, short.channel)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    BidiText.clean(short.title),
                    style = TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Content),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    metaLine(short.channel, short.views, null),
                    color = c.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun HomeTasteFilterRow(
    topics: List<String>,
    selected: String?,
    onSelectTopic: (String?) -> Unit,
    onOpenShorts: () -> Unit,
    onRefresh: () -> Unit,
    trending: Boolean,
    onTrending: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterPill(
            label = Loc.t("For you"),
            selected = !trending && selected == null,
            icon = Icons.Rounded.AutoAwesome,
            onClick = { onSelectTopic(null) },
        )
        FilterPill(label = Loc.t("Trending"), selected = trending, onClick = onTrending)
        FilterPill(
            label = Loc.t("Shorts"),
            selected = false,
            icon = Icons.Rounded.PlayArrow,
            onClick = onOpenShorts,
        )
        topics.distinctBy { it.lowercase(java.util.Locale.ROOT) }.filterNot { topic ->
            listOf("For you", "Trending", "Shorts", "Mix Shorts with videos").any { topic.equals(Loc.t(it), true) || topic.equals(it, true) }
        }.take(8).forEach { topic ->
            FilterPill(
                label = topic,
                selected = !trending && selected.equals(topic, ignoreCase = true),
                onClick = { onSelectTopic(topic) },
            )
        }
        // One tap between "Shorts mixed into For you" and "Shorts kept in their own shelf".
        val mix = AppSettings.mixShortsInFeed
        FilterPill(
            label = Loc.t("Mix Shorts with videos"),
            selected = mix,
            icon = Icons.Rounded.SmartDisplay,
            onClick = {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                AppSettings.mixShortsInFeed = !mix
            },
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .pressable(onClick = onRefresh)
                .clip(CircleShape)
                .background(c.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Refresh, Loc.t("Refresh recommendations"), tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .pressable(onClick = onClick)
            .clip(CircleShape)
            .background(if (selected) c.primary else c.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = if (selected) c.primary else c.outlineVariant.copy(alpha = 0.28f),
                shape = CircleShape,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) c.onPrimary else c.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            color = if (selected) c.onPrimary else c.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

/**
 * Shown on the very first launch before the user has searched for or watched any video:
 * keeps the feed empty of random videos while presenting a complete, inviting layout.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FirstTimeHomeWelcome(
    contentPadding: PaddingValues,
    onSearch: () -> Unit,
    onOpenShortsTab: () -> Unit,
    onSignIn: () -> Unit,
    onPickTopic: (String) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    // Rebuilt when the language changes, so the chips read (and search) in that language.
    val starterTopics = remember(Loc.version) {
        listOf(
            Loc.t("Science & Space"), Loc.t("Tech Reviews"), Loc.t("Lo-Fi Beats"), Loc.t("Game Design"),
            Loc.t("Nature 4K"), Loc.t("Cooking"), Loc.t("Coding & AI"), Loc.t("Filmmaking"),
        )
    }
    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "welcome-hero") {
            val shape = RoundedCornerShape(28.dp)
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                c.primaryContainer.copy(alpha = 0.55f),
                                c.surfaceContainerLow,
                            ),
                        ),
                    )
                    .border(1.dp, c.outlineVariant.copy(alpha = 0.30f), shape)
                    .padding(22.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CookieShape)
                            .background(c.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = c.onPrimary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            Loc.t("Personalized on this device"),
                            color = c.primary,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.5.sp,
                        )
                        Text(
                            Loc.t("Watch or search to start"),
                            fontFamily = RoundedHeavy,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 22.sp,
                            letterSpacing = (-0.6).sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    Loc.t("Your Home feed starts clean. As soon as you search for or watch a video, Shinobi gathers videos and Shorts in that same style — privately on your phone, with no account needed."),
                    color = c.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PillButton(
                        text = Loc.t("Search videos"),
                        onClick = onSearch,
                        icon = Icons.Rounded.Search,
                        modifier = Modifier.weight(1f),
                    )
                    TonalPillButton(
                        text = Loc.t("Watch Shorts"),
                        onClick = onOpenShortsTab,
                        icon = Icons.Rounded.PlayArrow,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item(key = "welcome-topics") {
            val shape = RoundedCornerShape(26.dp)
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(c.surfaceContainerLow)
                    .border(1.dp, c.outlineVariant.copy(alpha = 0.22f), shape)
                    .padding(18.dp),
            ) {
                Text(
                    Loc.t("Or pick a topic to seed your feed"),
                    fontFamily = RoundedHeavy,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 17.sp,
                    letterSpacing = (-0.4).sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    Loc.t("Tap any interest below to populate Home with videos and Shorts right away."),
                    color = c.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    starterTopics.forEach { topic ->
                        Row(
                            modifier = Modifier
                                .pressable { onPickTopic(topic) }
                                .clip(CircleShape)
                                .background(c.surfaceContainerHigh)
                                .border(1.dp, c.outlineVariant.copy(alpha = 0.30f), CircleShape)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Explore, null, tint = c.primary, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(topic, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.onSurface)
                        }
                    }
                }
            }
        }

        item(key = "welcome-bento") {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FeatureBentoCard(
                        icon = Icons.Rounded.AutoAwesome,
                        title = Loc.t("Learns your taste"),
                        body = Loc.t("Every video you watch refines Home in that creator and topic style."),
                        modifier = Modifier.weight(1f),
                    )
                    FeatureBentoCard(
                        icon = Icons.Rounded.Shield,
                        title = Loc.t("Zero ads, ever"),
                        body = Loc.t("Ad-free streams plus automatic SponsorBlock segment skipping."),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FeatureBentoCard(
                        icon = Icons.Rounded.Lock,
                        title = Loc.t("Private history"),
                        body = Loc.t("Watched videos, likes and Watch later stay locally on this phone."),
                        modifier = Modifier.weight(1f),
                    )
                    FeatureBentoCard(
                        icon = Icons.Rounded.AccountCircle,
                        title = Loc.t("Optional Google sync"),
                        body = Loc.t("Tap to sign in if you want your cloud subscriptions and playlists."),
                        modifier = Modifier.weight(1f),
                        onClick = onSignIn,
                    )
                }
            }
        }
    }
}

// ── Subscriptions ──────────────────────────────────────────────────────────────

@Composable
fun SubscriptionsScreen(
    lib: LibraryData,
    localSubs: LocalSubscriptionsFeedState,
    contentPadding: PaddingValues,
    onOpen: (VideoItem) -> Unit,
    onSearch: () -> Unit,
    onSignIn: () -> Unit,
    onAccount: () -> Unit,
    scrollToTop: Int = 0,
) {
    val signedIn = Account.signedIn
    val localChannels = LocalHistory.activeChannels()

    LaunchedEffect(signedIn) {
        if (signedIn) {
            lib.subscriptions.ensureLoaded()
            if (lib.channels.isEmpty()) lib.loadChannels()
        }
    }
    LaunchedEffect(signedIn, localChannels) {
        if (!signedIn) {
            localSubs.sync(localChannels)
        }
    }

    CollapsingHeaderLayout(
        resetKey = scrollToTop to lib.subscriptions.generation,
        header = {
            ScreenHeader(title = Loc.t("Subscriptions")) {
                CircleAction(Icons.Rounded.Search, Loc.t("Search"), onSearch)
                ThemeToggleAction()
                AccountAction(onAccount)
            }
        },
    ) { top ->
        when {
            signedIn -> {
                FeedList(
                    feed = lib.subscriptions,
                    contentPadding = contentPadding.withTop(top),
                    onOpen = onOpen,
                    header = {
                        item(key = "channels") {
                            if (lib.channels.isNotEmpty()) ChannelRail(lib.channels)
                        }
                    },
                    scrollToTop = scrollToTop,
                    indicatorTop = top,
                )
            }
            localChannels.isNotEmpty() -> {
                val c = MaterialTheme.colorScheme
                val list = rememberScrollToTop(scrollToTop)
                PullToRefresh(
                    enabled = true,
                    refreshing = localSubs.refreshing,
                    onRefresh = { localSubs.refresh(localChannels) },
                    topPadding = top,
                ) {
                    LazyColumn(state = list, contentPadding = contentPadding.withTop(top)) {
                        item(key = "local-channels-rail") {
                            ChannelRail(localChannels)
                        }
                        items(localSubs.videos, key = { "lsub-" + it.id }) { video ->
                            VideoCard(video, onClick = { onOpen(video) })
                        }
                        if (localSubs.loading) {
                            item(key = "lsub-loading") {
                                Box(Modifier.fillMaxWidth().padding(28.dp), Alignment.Center) {
                                    CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(32.dp))
                                }
                            }
                        }
                        item(key = "lsub-sync-card") {
                            SyncBannerCard(
                                title = Loc.t("Sync your YouTube subscriptions"),
                                body = Loc.t("Channels you watch or subscribe to appear above automatically. Sign in with Google anytime for cloud sync."),
                                actionLabel = Loc.t("Sign in with Google"),
                                onAction = onSignIn,
                            )
                        }
                    }
                }
            }
            else -> {
                SignedOutSubscriptionsWelcome(
                    contentPadding = contentPadding.withTop(top),
                    onSearch = onSearch,
                    onSignIn = onSignIn,
                )
            }
        }
    }
}

@Composable
internal fun SignedOutSubscriptionsWelcome(
    contentPadding: PaddingValues,
    onSearch: () -> Unit,
    onSignIn: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "sub-hero") {
            val shape = RoundedCornerShape(28.dp)
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                c.primaryContainer.copy(alpha = 0.50f),
                                c.surfaceContainerLow,
                            ),
                        ),
                    )
                    .border(1.dp, c.outlineVariant.copy(alpha = 0.28f), shape)
                    .padding(22.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(44.dp).clip(CookieShape).background(c.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Subscriptions, null, tint = c.onPrimary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(Loc.t("Channels you follow"), color = c.primary, fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp)
                        Text(
                            Loc.t("Follow creators your way"),
                            fontFamily = RoundedHeavy,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 22.sp,
                            letterSpacing = (-0.6).sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    Loc.t("Subscribe to any channel while watching a video — even without signing in — or connect your Google account to bring in all the channels you already follow on YouTube."),
                    color = c.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(18.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PillButton(
                        text = Loc.t("Find channels"),
                        onClick = onSearch,
                        icon = Icons.Rounded.Search,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TonalPillButton(
                        text = Loc.t("Sign in"),
                        onClick = onSignIn,
                        icon = Icons.Rounded.AccountCircle,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item(key = "sub-bento") {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FeatureBentoCard(
                        icon = Icons.Rounded.NotificationsActive,
                        title = Loc.t("Local following"),
                        body = Loc.t("Tap Subscribe on any video or channel to follow it right on this device."),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FeatureBentoCard(
                        icon = Icons.Rounded.History,
                        title = Loc.t("Automatic discovery"),
                        body = Loc.t("Channels from your recent watch history appear here with their latest uploads."),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRail(channels: List<ChannelRef>) {
    val c = MaterialTheme.colorScheme
    val openChannel = LocalChannelOpener.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
    ) {
        items(channels, key = { it.id }) { ch ->
            Column(
                modifier = Modifier
                    .width(66.dp)
                    .pressable(pressedScale = 0.95f) { openChannel(ch.resolvedUrl) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ChannelAvatar(ch.avatar, ch.name, size = 56)
                Spacer(Modifier.height(6.dp))
                Text(
                    ch.name,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── Library ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    lib: LibraryData,
    contentPadding: PaddingValues,
    onOpen: (VideoItem) -> Unit,
    onOpenPlaylist: (PlaylistRef) -> Unit,
    onSearch: () -> Unit,
    onSelectSearchQuery: (String) -> Unit,
    onSignIn: () -> Unit,
    onAccount: () -> Unit,
    scrollToTop: Int = 0,
) {
    val signedIn = Account.signedIn
    val c = MaterialTheme.colorScheme
    LaunchedEffect(signedIn) {
        if (signedIn) {
            lib.history.ensureLoaded()
            if (lib.playlists.isEmpty()) lib.loadPlaylists()
        }
    }
    CollapsingHeaderLayout(
        resetKey = scrollToTop to lib.history.generation,
        header = {
            ScreenHeader(title = Loc.t("Library")) {
                ThemeToggleAction()
                CircleAction(Icons.Rounded.Tune, Loc.t("Settings & Appearance"), onAccount)
            }
        },
    ) { top ->
        PullToRefresh(
            enabled = signedIn,
            refreshing = lib.history.refreshing,
            onRefresh = { lib.history.refresh(); lib.loadPlaylists() },
            topPadding = top,
        ) {
            LazyColumn(state = rememberScrollToTop(scrollToTop), contentPadding = contentPadding.withTop(top)) {
                if (!signedIn) {
                    val recent = LocalHistory.entries
                    val watchLater = LocalHistory.watchLaterVideos
                    val liked = LocalHistory.likedVideos
                    val searches = LocalHistory.recentQueries

                    // 1. On-device collections grid (Watch later & Liked videos)
                    item(key = "localCollectionsTitle") {
                        SectionTitle(Loc.t("Collections on this device"))
                    }
                    item(key = "localCollectionsGrid") {
                        val localPlaylists = listOf(
                            PlaylistRef(
                                id = "LOCAL_WL",
                                title = Loc.t("Watch later"),
                                thumbnail = watchLater.firstOrNull()?.thumbnail,
                                subtitle = Loc.videos(watchLater.size),
                            ),
                            PlaylistRef(
                                id = "LOCAL_LL",
                                title = Loc.t("Liked videos"),
                                thumbnail = liked.firstOrNull()?.thumbnail,
                                subtitle = Loc.videos(liked.size),
                            ),
                        )
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            localPlaylists.forEach { pl ->
                                PlaylistTile(pl, Modifier.weight(1f)) { onOpenPlaylist(pl) }
                            }
                        }
                    }

                    // 2. Recent watch history section
                    item(key = "localHistoryTitle") {
                        Row(
                            Modifier.fillMaxWidth().padding(end = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SectionTitle(Loc.t("Recent on this device"), modifier = Modifier.weight(1f))
                            if (recent.isNotEmpty()) TextLink(Loc.t("Clear"), onClick = LocalHistory::clear)
                        }
                    }

                    if (recent.isEmpty()) {
                        item(key = "localHistoryEmptyCard") {
                            val shape = RoundedCornerShape(24.dp)
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .fillMaxWidth()
                                    .clip(shape)
                                    .background(c.surfaceContainerLow)
                                    .border(1.dp, c.outlineVariant.copy(alpha = 0.22f), shape)
                                    .padding(20.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier.size(42.dp).clip(CookieShape).background(c.primaryContainer),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Rounded.History, null, tint = c.onPrimaryContainer, modifier = Modifier.size(21.dp))
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(Loc.t("No videos watched yet"), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                                        Text(
                                            Loc.t("Videos and Shorts you watch stay privately on this phone and power your Home feed."),
                                            color = c.onSurfaceVariant,
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                PillButton(Loc.t("Search videos"), onSearch, icon = Icons.Rounded.Search)
                            }
                        }
                    } else {
                        item(key = "localHistoryCarousel") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(recent.take(15), key = { "lhc-" + it.video.id }) { entry ->
                                    HistoryCard(entry.video) { onOpen(entry.video) }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        items(recent.take(30), key = { "local-" + it.video.id }) { entry ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                VideoRow(entry.video, onClick = { onOpen(entry.video) }) {
                                    Box(
                                        Modifier.size(40.dp).pressable(onClick = { LocalHistory.remove(entry.video.id) }),
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(Icons.Rounded.Close, Loc.t("Remove from local history"), tint = c.onSurfaceVariant) }
                                }
                                Text(
                                    Loc.watched(YouTube.relativeTime(java.time.Instant.ofEpochMilli(entry.watchedAtMs))),
                                    color = c.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                                )
                            }
                        }
                    }

                    // 3. Recent search queries (if any)
                    if (searches.isNotEmpty()) {
                        item(key = "localSearchesTitle") {
                            Row(
                                Modifier.fillMaxWidth().padding(end = 20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SectionTitle(Loc.t("Recent searches"), modifier = Modifier.weight(1f))
                                TextLink(Loc.t("Clear"), onClick = LocalHistory::clearSearches)
                            }
                        }
                        item(key = "localSearchesChips") {
                            FlowRow(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                searches.take(10).forEach { q ->
                                    Row(
                                        modifier = Modifier
                                            .pressable { onSelectSearchQuery(q) }
                                            .clip(CircleShape)
                                            .background(c.surfaceContainerLow)
                                            .border(1.dp, c.outlineVariant.copy(alpha = 0.25f), CircleShape)
                                            .padding(start = 13.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Rounded.Search, null, tint = c.primary, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(q, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                                        Spacer(Modifier.width(4.dp))
                                        Box(
                                            Modifier.size(20.dp).pressable { LocalHistory.removeSearch(q) },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(Icons.Rounded.Close, Loc.t("Remove"), tint = c.onSurfaceVariant, modifier = Modifier.size(13.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. Sync & account card
                    item(key = "localAccountCard") {
                        SyncBannerCard(
                            title = Loc.t("Sync with your YouTube account"),
                            body = Loc.t("Sign in with Google to access your cloud playlists, Watch later and synced watch history."),
                            actionLabel = Loc.t("Sign in with Google"),
                            onAction = onSignIn,
                        )
                    }
                    return@LazyColumn
                }

                // Signed-in Library
                item(key = "historyTitle") { SectionTitle(Loc.t("History")) }
                item(key = "history") {
                    val history = lib.history.videos
                    when {
                        history.isNotEmpty() -> LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(history.take(20), key = { "h-" + it.id }) { video -> HistoryCard(video) { onOpen(video) } }
                        }
                        lib.history.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                            CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(28.dp))
                        }
                        else -> Text(
                            lib.history.error ?: Loc.t("Nothing watched yet."),
                            color = c.onSurfaceVariant, fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
                item(key = "playlistsTitle") { SectionTitle(Loc.t("Playlists")) }
                val pinned = listOf(
                    PlaylistRef("WL", Loc.t("Watch later"), null, null),
                    PlaylistRef("LL", Loc.t("Liked videos"), null, null),
                )
                items((pinned + lib.playlists).chunked(2), key = { row -> row.joinToString { it.id } }) { row ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { pl -> PlaylistTile(pl, Modifier.weight(1f)) { onOpenPlaylist(pl) } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncBannerCard(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(c.surfaceContainerLow)
            .border(1.dp, c.outlineVariant.copy(alpha = 0.25f), shape)
            .padding(18.dp),
    ) {
        Text(title, fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(body, color = c.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(14.dp))
        TonalPillButton(actionLabel, onAction, icon = Icons.Rounded.AccountCircle)
    }
}

@Composable
private fun HistoryCard(video: VideoItem, onClick: () -> Unit) {
    val menu = LocalVideoMenu.current
    Column(Modifier.width(168.dp).prefetchOnPress(video.id).pressable(pressedScale = 0.97f, onLongClick = { menu(video) }, onClick = onClick)) {
        Box {
            Thumbnail(video.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 20)
            DurationPill(video.durationSec, Modifier.align(Alignment.BottomEnd).padding(6.dp), small = true)
            ResumeBar(video.id, Modifier.align(Alignment.BottomStart))
        }
        Spacer(Modifier.height(7.dp))
        Text(BidiText.clean(video.title), style = TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Content), fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlaylistTile(playlist: PlaylistRef, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(26.dp)
    val (icon, tone) = when (playlist.id) {
        "WL", "LOCAL_WL" -> Icons.Rounded.WatchLater to c.primaryContainer
        "LL", "LOCAL_LL" -> Icons.Rounded.ThumbUp to c.tertiaryContainer
        else -> Icons.AutoMirrored.Rounded.PlaylistPlay to c.secondaryContainer
    }
    Column(
        modifier = modifier
            .height(132.dp)
            .pressable(pressedScale = 0.97f, onClick = onClick)
            .clip(shape)
            .background(c.surfaceContainerLow)
            .border(1.dp, c.outlineVariant.copy(alpha = 0.22f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        if (playlist.thumbnail != null) {
            Thumbnail(playlist.thumbnail, Modifier.size(width = 72.dp, height = 44.dp), radius = 12)
        } else {
            Box(Modifier.size(52.dp).clip(CookieShape).background(tone), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = contentColorFor(tone))
            }
        }
        Column {
            Text(playlist.title, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            playlist.subtitle?.let { Text(it, color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}

/** A playlist's videos (Watch later, Liked, or the user's own — both local and signed-in). */
@Composable
fun PlaylistScreen(playlist: PlaylistRef, feed: FeedState?, contentPadding: PaddingValues, onBack: () -> Unit, onOpen: (VideoItem) -> Unit) {
    val isLocalWl = playlist.id == "LOCAL_WL"
    val isLocalLl = playlist.id == "LOCAL_LL"
    val isLocal = isLocalWl || isLocalLl

    LaunchedEffect(playlist.id) { if (!isLocal) feed?.ensureLoaded() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        CollapsingHeaderLayout(
            resetKey = feed?.generation ?: 0,
            header = {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleAction(Icons.AutoMirrored.Rounded.ArrowBack, Loc.t("Back"), onBack)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        playlist.title,
                        fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, letterSpacing = (-0.8).sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        ) { top ->
            if (isLocal) {
                val items = if (isLocalWl) LocalHistory.watchLaterVideos else LocalHistory.likedVideos
                if (items.isEmpty()) {
                    Box(Modifier.padding(top = top)) {
                        EmptyState(
                            icon = if (isLocalWl) Icons.Rounded.WatchLater else Icons.Rounded.ThumbUp,
                            title = Loc.t("Nothing saved here yet"),
                            body = Loc.t(
                            if (isLocalWl) "Tap Save on any video or Short to keep it in Watch later on this device."
                            else "Tap Like on any video or Short to add it to your Liked videos on this device.",
                        ),
                        )
                    }
                } else {
                    LazyColumn(contentPadding = contentPadding.withTop(top)) {
                        items(items, key = { "lpl-" + it.id }) { video ->
                            VideoCard(video, onClick = { onOpen(video) })
                        }
                    }
                }
            } else if (feed != null) {
                if (feed.videos.isEmpty() && !feed.loading && feed.error == null && !feed.hasMore) {
                    Box(Modifier.padding(top = top)) {
                        EmptyState(Icons.Rounded.VideoLibrary, Loc.t("Nothing here yet"), Loc.t("Videos you add to this playlist will show up here."))
                    }
                } else {
                    FeedList(feed, contentPadding.withTop(top), onOpen, indicatorTop = top)
                }
            }
        }
    }
}

@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 14.sp,
        modifier = Modifier.pressable(onClick = onClick).padding(8.dp),
    )
}

// ── Search ─────────────────────────────────────────────────────────────────────

/** Lives above the tab switcher so results survive switching tabs. */
@Stable
class SearchState(private val scope: CoroutineScope) {
    val suggestions = SearchSuggestions(scope)
    fun updateQuery(value: String) { query = value; suggestions.update(value, Loc.language) }

    var query by mutableStateOf("")
    var submitted by mutableStateOf("")
        private set
    var results by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var shorts by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var generation by mutableIntStateOf(0)
        private set
    private var job: Job? = null

    fun submit(overrideQuery: String? = null) {
        val q = (overrideQuery ?: query).trim()
        if (q.isEmpty()) return
        query = q
        submitted = q
        suggestions.dismiss()
        LocalHistory.recordSearch(q)
        loading = true
        error = null
        job?.cancel()
        job = scope.launch {
            runCatching {
                val inner = try { InnerTube.searchPage(q) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { null }
                val vids = if (inner == null || (inner.videos.isEmpty() && inner.shorts.isEmpty())) YouTube.search(q) else inner.videos
                val sh = inner?.shorts.orEmpty() + vids.filter { it.isShort }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                vids.filterNot { it.isShort }.distinctBy { it.id } to sh.distinctBy { it.id }
            }.onSuccess { (vids, sh) ->
                results = vids
                shorts = sh
                generation++
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                error = YouTube.friendlyError(it)
            }
            loading = false
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    state: SearchState,
    contentPadding: PaddingValues,
    onOpen: (VideoItem) -> Unit,
    onOpenShort: (VideoItem, List<VideoItem>) -> Unit = { v, _ -> onOpen(v) },
    scrollToTop: Int = 0,
) {
    val c = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val view = LocalView.current
    androidx.compose.runtime.DisposableEffect(state) { onDispose { state.suggestions.dismiss() } }
    androidx.activity.compose.BackHandler(enabled = state.suggestions.visible) { state.suggestions.dismiss() }

    fun submit(overrideQuery: String? = null) {
        focus.clearFocus(force = true)
        keyboard?.hide()
        view.postDelayed({
            view.context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }, 120)
        state.submit(overrideQuery)
    }

    CollapsingHeaderLayout(
        resetKey = scrollToTop to state.generation,
        header = {
            Column {
                ScreenHeader(title = Loc.t("Search"))
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .height(58.dp)
                        .clip(CircleShape)
                        .background(c.surfaceContainerHigh)
                        .border(1.dp, c.outlineVariant.copy(alpha = 0.28f), CircleShape)
                        .padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, tint = c.primary)
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) {
                        if (state.query.isEmpty()) {
                            Text(Loc.t("Search YouTube videos & Shorts"), color = c.onSurfaceVariant, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        BasicTextField(
                            value = state.query,
                            onValueChange = state::updateQuery,
                            singleLine = true,
                            textStyle = TextStyle(color = c.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textDirection = androidx.compose.ui.text.style.TextDirection.Content),
                            cursorBrush = SolidColor(c.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { submit() }, onDone = { submit() }),
                            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                                if (e.key == Key.Enter || e.key == Key.NumPadEnter) {
                                    if (e.type == KeyEventType.KeyDown) submit()
                                    true
                                } else false
                            },
                        )
                    }
                    if (state.query.isNotEmpty()) {
                        Box(
                            modifier = Modifier.size(44.dp).pressable { state.updateQuery("") },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Close, Loc.t("Clear"), tint = c.onSurfaceVariant) }
                    }
                }
                if (state.loading) androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                Spacer(Modifier.height(8.dp))
            }
        },
    ) { top ->
        Box(Modifier.fillMaxSize()) {
            val hasResults = (state.suggestions.visible && state.suggestions.items.isNotEmpty()) || (state.results.isNotEmpty() || state.shorts.isNotEmpty()) && state.error == null
            Box(Modifier.padding(top = if (hasResults || state.submitted.isEmpty()) 0.dp else top)) {
                when {
                    state.suggestions.visible && state.suggestions.items.isNotEmpty() -> {
                        LazyColumn(contentPadding = contentPadding.withTop(top)) {
                            items(state.suggestions.items, key = { it }, contentType = { "suggestion" }) { suggestion ->
                                Row(Modifier.fillMaxWidth().pressable { submit(suggestion) }.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Search, null, tint = c.onSurfaceVariant)
                                    Spacer(Modifier.width(16.dp))
                                    Text(suggestion, modifier = Modifier.weight(1f), maxLines = 2,
                                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Content))
                                }
                            }
                        }
                    }
                    state.loading && state.results.isEmpty() && state.shorts.isEmpty() -> Box(
                        Modifier.fillMaxWidth().padding(top = top + 48.dp),
                        Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(36.dp))
                    }
                    state.error != null -> EmptyState(
                        icon = Icons.Rounded.CloudOff,
                        title = Loc.t("Search didn't load"),
                        body = state.error.orEmpty(),
                        action = { PillButton(Loc.t("Try again"), { state.submit() }) },
                    )
                    state.submitted.isEmpty() -> {
                        val recentQueries = LocalHistory.recentQueries
                        LazyColumn(contentPadding = contentPadding.withTop(top)) {
                            if (recentQueries.isNotEmpty()) {
                                item(key = "search-recent-header") {
                                    Row(
                                        Modifier.fillMaxWidth().padding(end = 20.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        SectionTitle(Loc.t("Recent searches"), modifier = Modifier.weight(1f))
                                        TextLink(Loc.t("Clear"), onClick = LocalHistory::clearSearches)
                                    }
                                }
                                item(key = "search-recent-chips") {
                                    FlowRow(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        recentQueries.forEach { q ->
                                            Row(
                                                modifier = Modifier
                                                    .pressable { submit(q) }
                                                    .clip(CircleShape)
                                                    .background(c.surfaceContainerLow)
                                                    .border(1.dp, c.outlineVariant.copy(alpha = 0.25f), CircleShape)
                                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(Icons.Rounded.History, null, tint = c.primary, modifier = Modifier.size(15.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text(q, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }
                            }
                            item(key = "search-empty-card") {
                                EmptyState(
                                    icon = Icons.Rounded.TravelExplore,
                                    title = Loc.t("Search without the ads"),
                                    body = Loc.t("Videos and Shorts play straight away with no ads, and automatically teach your Home feed what you like."),
                                )
                            }
                        }
                    }
                    state.results.isEmpty() && state.shorts.isEmpty() -> EmptyState(
                        icon = Icons.Rounded.History,
                        title = Loc.t("Nothing found"),
                        body = Loc.t("No videos matched") + " “${state.submitted}”.",
                    )
                    else -> {
                        val list = rememberScrollToTop(scrollToTop)
                        LaunchedEffect(state.generation) { list.scrollToItem(0) }
                        LazyColumn(state = list, contentPadding = contentPadding.withTop(top)) {
                            if (state.shorts.isNotEmpty()) {
                                item(key = "search-shorts-shelf") {
                                    ShortsShelf(
                                        shorts = state.shorts,
                                        onOpenShort = onOpenShort,
                                    )
                                }
                            }
                            items(state.results, key = { it.id }, contentType = { "video" }) { video ->
                                VideoCard(video, onClick = { onOpen(video) })
                            }
                        }
                    }
                }
            }
        }
    }
}
