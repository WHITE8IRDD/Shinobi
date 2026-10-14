package com.shinobi.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

@Stable
class TrendingState(
    private val scope: CoroutineScope,
    private val loader: suspend (String) -> FeedPage = InnerTube::trending,
) {
    var videos by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null
    private var loadedCountry: String? = null
    private val cache = object : LinkedHashMap<String, Pair<Long, List<VideoItem>>>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<VideoItem>>>?) = size > 8
    }

    fun load(country: String, refresh: Boolean = false) {
        job?.cancel()
        error = null
        // Keep the current chart during an explicit refresh, but never under another country.
        if (loadedCountry != country || !refresh) videos = emptyList()
        loadedCountry = country
        val cached = cache[country]
        if (!refresh && cached != null && System.currentTimeMillis() - cached.first < 600_000) {
            videos = cached.second
            loading = false
            return
        }
        loading = true
        job = scope.launch {
            try {
                val page = loader(country)
                if (!isActive) return@launch
                videos = page.videos.distinctBy { it.id }.take(100)
                if (videos.isEmpty()) error = Loc.t("YouTube has no trending chart for this country right now.")
                else cache[country] = System.currentTimeMillis() to videos
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (isActive) error = YouTube.friendlyError(e) }
            finally { if (isActive) loading = false }
        }
    }
}

@Composable
fun TrendingContent(state: TrendingState, padding: PaddingValues, top: androidx.compose.ui.unit.Dp, onOpen: (VideoItem) -> Unit) {
    val country = AppSettings.trendingCountry
    var choosing by remember { mutableStateOf(false) }
    val countries = remember(Loc.language) {
        Locale.getISOCountries().map { it to Locale("", it).getDisplayCountry(Loc.localeOf()) }.sortedBy { it.second }
    }
    LaunchedEffect(country) { state.load(country) }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(country) { list.scrollToItem(0) }
    PullToRefresh(true, state.loading && state.videos.isNotEmpty(), { state.load(country, refresh = true) }, top) {
        LazyColumn(state = list, contentPadding = padding) {
            item(key = "country") {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(Loc.t("Trending on YouTube"), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    TonalPillButton(Loc.t("Country") + ": " + Locale("", country).getDisplayCountry(Loc.localeOf()), { choosing = true }, icon = Icons.Rounded.TravelExplore)
                }
            }
            items(state.videos, key = { it.id }, contentType = { "video" }) { video -> VideoCard(video, { onOpen(video) }) }
            if (state.loading) item { ListSkeleton() }
            state.error?.let { message -> item {
                EmptyState(Icons.Rounded.TravelExplore, Loc.t("Trending unavailable"), message,
                    action = { PillButton(Loc.t("Try again"), { state.load(country, refresh = true) }) })
            } }
        }
    }
    if (choosing) AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text(Loc.t("Choose country")) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(countries, key = { it.first }) { (code, name) ->
                    TextButton(onClick = { choosing = false; AppSettings.chooseTrendingCountry(code) }, modifier = Modifier.fillMaxWidth()) {
                        Text((if (code == country) "✓ " else "") + name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosing = false }) { Text(Loc.t("Close")) } },
    )
}
