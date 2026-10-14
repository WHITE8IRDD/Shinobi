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
import kotlinx.coroutines.launch

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
    val signature = LocalHistory.tasteSignature
    val hasTaste = LocalHistory.hasTasteSignal

    LaunchedEffect(signedIn) {
        if (signedIn) lib.home.ensureLoaded()
    }
    LaunchedEffect(signature) {
        personalized.syncWithTaste(signature)
    }

    CollapsingHeaderLayout(
        resetKey = scrollToTop to (if (signedIn) lib.home.generation else personalized.generation),
        header = {
            ScreenHeader(title = "Shinobi", brandMark = true) {
                CircleAction(Icons.Rounded.Search, "Search", onSearch)
                AccountAction(onAccount)
            }
        },
    ) { top ->
        when {
            signedIn -> {
                FeedList(
                    feed = lib.home,
                    contentPadding = contentPadding.withTop(top),
                    onOpen = onOpen,
                    header = {
                        if (personalized.shorts.isNotEmpty()) {
                            item(key = "home-shorts-shelf") {
                                ShortsShelf(
                                    shorts = personalized.shorts,
                                    onOpenShort = onOpenShort,
                                    onViewAll = onOpenShortsTab,
                                )
                            }
                        }
                    },
                    scrollToTop = scrollToTop,
                    indicatorTop = top,
                )
            }
            hasTaste -> {
                PersonalizedHomeContent(
                    state = personalized,
                    contentPadding = contentPadding.withTop(top),
                    indicatorTop = top,
                    scrollToTop = scrollToTop,
                    onOpen = onOpen,
                    onOpenShort = onOpenShort,
                    onOpenShortsTab = onOpenShortsTab,
                )
            }
            else -> {
                FirstTimeHomeWelcome(
                    contentPadding = contentPadding.withTop(top),
                    onSearch = onSearch,
                    onOpenShortsTab = onOpenShortsTab,
                    onSignIn = onSignIn,
                    onPickTopic = { topic -> LocalHistory.recordSearch(topic) },
                )
            }
        }
    }
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
    LaunchedEffect(state.generation) { list.scrollToItem(0) }
    val nearEnd by remember {
        derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, state.videos.size) {
        if (nearEnd && state.videos.isNotEmpty()) state.loadMore()
    }

    PullToRefresh(
        enabled = true,
        refreshing = state.refreshing,
        onRefresh = state::refresh,
        topPadding = indicatorTop,
    ) {
        LazyColumn(state = list, contentPadding = contentPadding) {
            // Taste filter chips bar
            item(key = "taste-chips") {
                HomeTasteFilterRow(
                    topics = state.topics,
                    selected = state.selectedTopic,
                    onSelectTopic = state::selectTopic,
                    onOpenShorts = onOpenShortsTab,
                    onRefresh = state::refresh,
                )
            }

            // Primary Shorts shelf at the top of the feed
            val topShorts = state.shorts.take(10)
            if (topShorts.isNotEmpty()) {
                item(key = "shorts-top-shelf") {
                    ShortsShelf(
                        shorts = topShorts,
                        onOpenShort = onOpenShort,
                        onViewAll = onOpenShortsTab,
                    )
                }
            }

            // First batch of recommended videos
            val firstBatch = state.videos.take(4)
            items(firstBatch, key = { "rec-1-" + it.id }) { video ->
                VideoCard(video, onClick = { onOpen(video) })
            }

            // Second Shorts shelf further down if we have more Shorts
            val moreShorts = state.shorts.drop(10).take(10)
            if (moreShorts.isNotEmpty() && state.videos.size >= 4) {
                item(key = "shorts-mid-shelf") {
                    ShortsShelf(
                        shorts = moreShorts,
                        title = "More Shorts for you",
                        onOpenShort = onOpenShort,
                        onViewAll = onOpenShortsTab,
                    )
                }
            }

            // Remaining recommended videos
            val restBatch = state.videos.drop(4)
            items(restBatch, key = { "rec-2-" + it.id }) { video ->
                VideoCard(video, onClick = { onOpen(video) })
            }

            when {
                state.error != null && state.videos.isEmpty() && state.shorts.isEmpty() -> item {
                    EmptyState(
                        icon = Icons.Rounded.CloudOff,
                        title = "Couldn't load suggestions",
                        body = state.error.orEmpty(),
                        action = { PillButton("Try again", state::refresh) },
                    )
                }
                state.loading || state.hasMore -> item {
                    Box(Modifier.fillMaxWidth().padding(28.dp), Alignment.Center) {
                        CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTasteFilterRow(
    topics: List<String>,
    selected: String?,
    onSelectTopic: (String?) -> Unit,
    onOpenShorts: () -> Unit,
    onRefresh: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterPill(
            label = "For you",
            selected = selected == null,
            icon = Icons.Rounded.AutoAwesome,
            onClick = { onSelectTopic(null) },
        )
        FilterPill(
            label = "Shorts",
            selected = false,
            icon = Icons.Rounded.PlayArrow,
            onClick = onOpenShorts,
        )
        topics.forEach { topic ->
            FilterPill(
                label = topic,
                selected = selected.equals(topic, ignoreCase = true),
                onClick = { onSelectTopic(topic) },
            )
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .pressable(onClick = onRefresh)
                .clip(CircleShape)
                .background(c.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Refresh, "Refresh recommendations", tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
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
            .height(36.dp)
            .pressable(onClick = onClick)
            .clip(CircleShape)
            .background(if (selected) c.primary else c.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = if (selected) c.primary else c.outlineVariant.copy(alpha = 0.28f),
                shape = CircleShape,
            )
            .padding(horizontal = 14.dp),
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
    val starterTopics = remember {
        listOf(
            "Science & Space", "Tech Reviews", "Lo-Fi Beats", "Game Design",
            "Nature 4K", "Cooking", "Coding & AI", "Filmmaking",
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
                            "Personalized on this device",
                            color = c.primary,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.5.sp,
                        )
                        Text(
                            "Watch or search to start",
                            fontFamily = RoundedHeavy,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 22.sp,
                            letterSpacing = (-0.6).sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Your Home feed starts clean. As soon as you search for or watch a video, Shinobi gathers videos and Shorts in that same style — privately on your phone, with no account needed.",
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
                        text = "Search videos",
                        onClick = onSearch,
                        icon = Icons.Rounded.Search,
                        modifier = Modifier.weight(1f),
                    )
                    TonalPillButton(
                        text = "Watch Shorts",
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
                    "Or pick a topic to seed your feed",
                    fontFamily = RoundedHeavy,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 17.sp,
                    letterSpacing = (-0.4).sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tap any interest below to populate Home with videos and Shorts right away.",
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
                        title = "Learns your taste",
                        body = "Every video you watch refines Home in that creator and topic style.",
                        modifier = Modifier.weight(1f),
                    )
                    FeatureBentoCard(
                        icon = Icons.Rounded.Shield,
                        title = "Zero ads, ever",
                        body = "Ad-free streams plus automatic SponsorBlock segment skipping.",
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FeatureBentoCard(
                        icon = Icons.Rounded.Lock,
                        title = "Private history",
                        body = "Watched videos, likes and Watch later stay locally on this phone.",
                        modifier = Modifier.weight(1f),
                    )
                    FeatureBentoCard(
                        icon = Icons.Rounded.AccountCircle,
                        title = "Optional Google sync",
                        body = "Tap to sign in if you want your cloud subscriptions and playlists.",
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
            ScreenHeader(title = "Subscriptions") {
                CircleAction(Icons.Rounded.Search, "Search", onSearch)
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
                                title = "Sync your YouTube subscriptions",
                                body = "Channels you watch or subscribe to appear above automatically. Sign in with Google anytime for cloud sync.",
                                actionLabel = "Sign in with Google",
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
private fun SignedOutSubscriptionsWelcome(
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
                        Text("Channels you follow", color = c.primary, fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp)
                        Text(
                            "Follow creators your way",
                            fontFamily = RoundedHeavy,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 22.sp,
                            letterSpacing = (-0.6).sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Subscribe to any channel while watching a video — even without signing in — or connect your Google account to bring in all the channels you already follow on YouTube.",
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
                        text = "Find channels",
                        onClick = onSearch,
                        icon = Icons.Rounded.Search,
                        modifier = Modifier.weight(1f),
                    )
                    TonalPillButton(
                        text = "Sign in",
                        onClick = onSignIn,
                        icon = Icons.Rounded.AccountCircle,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item(key = "sub-bento") {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FeatureBentoCard(
                        icon = Icons.Rounded.NotificationsActive,
                        title = "Local following",
                        body = "Tap Subscribe on any video or channel to follow it right on this device.",
                        modifier = Modifier.weight(1f),
                    )
                    FeatureBentoCard(
                        icon = Icons.Rounded.History,
                        title = "Automatic discovery",
                        body = "Channels from your recent watch history appear here with their latest uploads.",
                        modifier = Modifier.weight(1f),
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
            ScreenHeader(title = "Library") {
                CircleAction(Icons.Rounded.Tune, "Settings & Appearance", onAccount)
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
                        SectionTitle("Collections on this device")
                    }
                    item(key = "localCollectionsGrid") {
                        val localPlaylists = listOf(
                            PlaylistRef(
                                id = "LOCAL_WL",
                                title = "Watch later",
                                thumbnail = watchLater.firstOrNull()?.thumbnail,
                                subtitle = "${watchLater.size} video${if (watchLater.size == 1) "" else "s"}",
                            ),
                            PlaylistRef(
                                id = "LOCAL_LL",
                                title = "Liked videos",
                                thumbnail = liked.firstOrNull()?.thumbnail,
                                subtitle = "${liked.size} video${if (liked.size == 1) "" else "s"}",
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
                            SectionTitle("Recent on this device", modifier = Modifier.weight(1f))
                            if (recent.isNotEmpty()) TextLink("Clear", onClick = LocalHistory::clear)
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
                                        Text("No videos watched yet", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                                        Text(
                                            "Videos and Shorts you watch stay privately on this phone and power your Home feed.",
                                            color = c.onSurfaceVariant,
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                PillButton("Search videos", onSearch, icon = Icons.Rounded.Search)
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
                                    ) { Icon(Icons.Rounded.Close, "Remove from local history", tint = c.onSurfaceVariant) }
                                }
                                Text(
                                    "Watched ${YouTube.relativeTime(java.time.Instant.ofEpochMilli(entry.watchedAtMs))}",
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
                                SectionTitle("Recent searches", modifier = Modifier.weight(1f))
                                TextLink("Clear", onClick = LocalHistory::clearSearches)
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
                                            Icon(Icons.Rounded.Close, "Remove", tint = c.onSurfaceVariant, modifier = Modifier.size(13.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. Sync & account card
                    item(key = "localAccountCard") {
                        SyncBannerCard(
                            title = "Sync with your YouTube account",
                            body = "Sign in with Google to access your cloud playlists, Watch later and synced watch history.",
                            actionLabel = "Sign in with Google",
                            onAction = onSignIn,
                        )
                    }
                    return@LazyColumn
                }

                // Signed-in Library
                item(key = "historyTitle") { SectionTitle("History") }
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
                            lib.history.error ?: "Nothing watched yet.",
                            color = c.onSurfaceVariant, fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
                item(key = "playlistsTitle") { SectionTitle("Playlists") }
                val pinned = listOf(
                    PlaylistRef("WL", "Watch later", null, null),
                    PlaylistRef("LL", "Liked videos", null, null),
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
        Text(video.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                    CircleAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
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
                            title = "Nothing saved here yet",
                            body = if (isLocalWl) "Tap Save on any video or Short to keep it in Watch later on this device."
                            else "Tap Like on any video or Short to add it to your Liked videos on this device.",
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
                        EmptyState(Icons.Rounded.VideoLibrary, "Nothing here yet", "Videos you add to this playlist will show up here.")
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
        LocalHistory.recordSearch(q)
        loading = true
        error = null
        job?.cancel()
        job = scope.launch {
            runCatching {
                coroutineScope {
                    val innerDeferred = async(Dispatchers.IO) {
                        runCatching { InnerTube.searchPage(q) }.getOrNull()
                    }
                    val npDeferred = async(Dispatchers.IO) {
                        runCatching { YouTube.search(q) }.getOrNull()
                    }
                    val inner = innerDeferred.await()
                    val np = if (inner == null || inner.videos.isEmpty()) npDeferred.await() else null
                    val vids = inner?.videos?.ifEmpty { np.orEmpty() } ?: np ?: throw java.io.IOException("Search failed")
                    val sh = inner?.shorts.orEmpty() + vids.filter { it.isShort }
                    vids.filterNot { it.isShort } to sh.distinctBy { it.id }
                }
            }.onSuccess { (vids, sh) ->
                results = vids
                shorts = sh
                generation++
            }.onFailure {
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
                ScreenHeader(title = "Search")
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
                            Text("Search YouTube videos & Shorts", color = c.onSurfaceVariant, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        BasicTextField(
                            value = state.query,
                            onValueChange = { state.query = it },
                            singleLine = true,
                            textStyle = TextStyle(color = c.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
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
                            modifier = Modifier.size(44.dp).pressable { state.query = "" },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Close, "Clear", tint = c.onSurfaceVariant) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        },
    ) { top ->
        Box(Modifier.fillMaxSize()) {
            val hasResults = (state.results.isNotEmpty() || state.shorts.isNotEmpty()) && state.error == null
            Box(Modifier.padding(top = if (hasResults || state.submitted.isEmpty()) 0.dp else top)) {
                when {
                    state.loading && state.results.isEmpty() && state.shorts.isEmpty() -> Box(
                        Modifier.fillMaxWidth().padding(top = top + 48.dp),
                        Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(36.dp))
                    }
                    state.error != null -> EmptyState(
                        icon = Icons.Rounded.CloudOff,
                        title = "Search didn't load",
                        body = state.error.orEmpty(),
                        action = { PillButton("Try again", { state.submit() }) },
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
                                        SectionTitle("Recent searches", modifier = Modifier.weight(1f))
                                        TextLink("Clear", onClick = LocalHistory::clearSearches)
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
                                    title = "Search without the ads",
                                    body = "Videos and Shorts play straight away with no ads, and automatically teach your Home feed what you like.",
                                )
                            }
                        }
                    }
                    state.results.isEmpty() && state.shorts.isEmpty() -> EmptyState(
                        icon = Icons.Rounded.History,
                        title = "Nothing found",
                        body = "No videos matched “${state.submitted}”.",
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
                            items(state.results, key = { it.id }) { video ->
                                VideoCard(video, onClick = { onOpen(video) })
                            }
                        }
                    }
                }
            }
        }
    }
}
