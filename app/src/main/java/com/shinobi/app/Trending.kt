package com.shinobi.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The charts YouTube publishes since it retired the combined Trending page (21 July 2025):
 * each is a real YouTube page, in the order YouTube's own Explore hub lists them. Music and
 * Movies come from YouTube Charts and only exist for [YouTube.CHART_COUNTRIES]; Gaming and
 * Podcasts exist everywhere. [kioskId] is NewPipeExtractor's name for the page.
 */
enum class TrendingCategory(val kioskId: String, val title: String, val chartsOnly: Boolean) {
    Music("trending_music", "Music", chartsOnly = true),
    Movies("trending_movies_and_shows", "Movies & trailers", chartsOnly = true),
    Gaming("trending_gaming", "Gaming", chartsOnly = false),
    Podcasts("trending_podcasts_episodes", "Podcasts", chartsOnly = false);

    fun availableIn(country: String): Boolean = !chartsOnly || country in YouTube.CHART_COUNTRIES

    val icon: ImageVector
        get() = when (this) {
            Music -> Icons.Rounded.MusicNote
            Movies -> Icons.Rounded.Movie
            Gaming -> Icons.Rounded.SportsEsports
            Podcasts -> Icons.Rounded.Podcasts
        }

    companion object {
        fun availableIn(country: String): List<TrendingCategory> = entries.filter { it.availableIn(country) }

        /** The first chart that exists for [country]: Music where Charts covers it, Gaming elsewhere. */
        fun defaultFor(country: String): TrendingCategory = availableIn(country).first()
    }
}

@Stable
class TrendingState(
    private val scope: CoroutineScope,
    private val loader: suspend (String, TrendingCategory) -> List<VideoItem> = YouTube::trending,
) {
    var videos by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** The chosen chart; null until the user picks one, meaning the country's default. */
    var category by mutableStateOf<TrendingCategory?>(null)
        private set
    private var job: Job? = null
    private var loadedKey: String? = null
    private val cache = object : LinkedHashMap<String, Pair<Long, List<VideoItem>>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<VideoItem>>>?) = size > 16
    }

    /** The chart shown for [country]: the picked one if that country has it, else the default. */
    fun categoryFor(country: String): TrendingCategory =
        category?.takeIf { it.availableIn(country) } ?: TrendingCategory.defaultFor(country)

    fun select(country: String, pick: TrendingCategory) {
        if (!pick.availableIn(country)) return
        category = pick
        load(country)
    }

    fun load(country: String, refresh: Boolean = false) {
        val chart = categoryFor(country)
        val key = "$country|${chart.name}"
        job?.cancel()
        error = null
        // Keep the current chart during an explicit refresh, but never under another one.
        if (loadedKey != key || !refresh) videos = emptyList()
        loadedKey = key
        val cached = cache[key]
        if (!refresh && cached != null && System.currentTimeMillis() - cached.first < 600_000) {
            videos = cached.second
            loading = false
            return
        }
        loading = true
        job = scope.launch {
            try {
                val items = loader(country, chart)
                if (!isActive) return@launch
                videos = items.distinctBy { it.id }.take(100)
                if (videos.isEmpty()) error = Loc.t("YouTube has no trending chart for this country right now.")
                else cache[key] = System.currentTimeMillis() to videos
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (isActive) error = YouTube.friendlyError(e) }
            finally { if (isActive) loading = false }
        }
    }
}

/** The country's name in the app's language ("Morocco", "المغرب"). */
fun countryName(code: String): String = Locale("", code).getDisplayCountry(Loc.localeOf()).ifBlank { code }

@Composable
fun TrendingContent(state: TrendingState, padding: PaddingValues, top: androidx.compose.ui.unit.Dp, onOpen: (VideoItem) -> Unit) {
    val c = MaterialTheme.colorScheme
    val country = AppSettings.trendingCountry
    val auto = AppSettings.trendingCountryIsAuto
    var choosing by remember { mutableStateOf(false) }
    val categories = remember(country) { TrendingCategory.availableIn(country) }
    val chart = state.categoryFor(country)
    LaunchedEffect(country) { state.load(country) }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(country, chart) { list.scrollToItem(0) }
    PullToRefresh(true, state.loading && state.videos.isNotEmpty(), { state.load(country, refresh = true) }, top) {
        LazyColumn(state = list, contentPadding = padding) {
            item(key = "country", contentType = "header") {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)) {
                    Text(Loc.t("Trending on YouTube"), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        Loc.t("What people are watching in") + " " + BidiText.isolate(countryName(country)),
                        color = c.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(10.dp))
                    // The region sits on top; automatic unless the user picked a country.
                    TonalPillButton(
                        text = (if (auto) Loc.t("Auto") + " · " else "") + countryName(country),
                        onClick = { choosing = true },
                        icon = if (auto) Icons.Rounded.MyLocation else Icons.Rounded.TravelExplore,
                    )
                }
            }
            item(key = "charts", contentType = "chips") {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        categories.forEach { category ->
                            FilterPill(
                                label = Loc.t(category.title),
                                selected = category == chart,
                                icon = category.icon,
                                onClick = { state.select(country, category) },
                            )
                        }
                    }
                    if (categories.size < TrendingCategory.entries.size) {
                        Text(
                            Loc.t("YouTube doesn't publish music and movie charts for") + " " + BidiText.isolate(countryName(country)) + ".",
                            color = c.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            items(state.videos, key = { it.id }, contentType = { "video" }) { video -> VideoCard(video, { onOpen(video) }) }
            if (state.loading && state.videos.isEmpty()) item(key = "skeleton") { ListSkeleton() }
            state.error?.let { message -> item(key = "error") {
                EmptyState(Icons.Rounded.TravelExplore, Loc.t("Trending unavailable"), message,
                    action = { PillButton(Loc.t("Try again"), { state.load(country, refresh = true) }) })
            } }
        }
    }
    if (choosing) CountryPicker(
        current = if (auto) AppSettings.AUTO_REGION else country,
        detected = AppSettings.detectedCountry,
        onPick = { choosing = false; AppSettings.chooseTrendingCountry(it) },
        onDismiss = { choosing = false },
    )
}

/**
 * The region dialog: "Automatic" (what the phone reports) on top, then every country, with a
 * filter box because the list is long. [current] is a country code or [AppSettings.AUTO_REGION].
 */
@Composable
private fun CountryPicker(current: String, detected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var filter by remember { mutableStateOf("") }
    val countries = remember(Loc.language) {
        Locale.getISOCountries().map { it to countryName(it) }.sortedBy { it.second }
    }
    val shown = remember(countries, filter) {
        val q = filter.trim()
        if (q.isEmpty()) countries else countries.filter { it.second.contains(q, ignoreCase = true) || it.first.equals(q, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Loc.t("Choose country")) },
        text = {
            Column {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    placeholder = { Text(Loc.t("Search countries")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    if (filter.isBlank()) item(key = "auto") {
                        CountryRow(
                            label = Loc.t("Automatic") + " · " + countryName(detected),
                            selected = current == AppSettings.AUTO_REGION,
                            icon = Icons.Rounded.MyLocation,
                        ) { onPick(AppSettings.AUTO_REGION) }
                    }
                    items(shown, key = { it.first }) { (code, name) ->
                        CountryRow(label = name, selected = code == current) { onPick(code) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Loc.t("Close")) } },
        containerColor = c.surfaceContainerHigh,
    )
}

@Composable
private fun CountryRow(label: String, selected: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = c.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(
                label,
                color = if (selected) c.primary else c.onSurface,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (selected) Icon(Icons.Rounded.Check, null, tint = c.primary, modifier = Modifier.size(18.dp))
        }
    }
}
