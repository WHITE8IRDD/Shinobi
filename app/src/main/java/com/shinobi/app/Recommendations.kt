package com.shinobi.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Seeds derived from the user's recent watch history and search activity. */
data class TasteSeeds(
    val seedVideoIds: List<String>,
    val tasteQueries: List<String>,
    val topicPills: List<String>,
    val preferredChannels: Set<String>,
)

/**
 * Pure, deterministic taste extraction and recommendation ranking. Used by the Home feed
 * and Shorts discovery so recommendations stay anchored to the style and taste of the
 * latest videos the user has been watching and searching.
 */
object TasteEngine {
    private val BRACKETED = Regex("\\[[^\\]]*]|\\([^)]*\\)")
    private val HASHTAG = Regex("#\\S+")
    private val NON_WORD = Regex("[^\\p{L}\\p{Nd}\\s'+-]+")
    private val WHITESPACE = Regex("\\s+")

    private val STOP_WORDS = setOf(
        "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with",
        "by", "from", "up", "about", "into", "over", "after", "is", "are", "was", "were",
        "be", "been", "being", "have", "has", "had", "do", "does", "did", "will", "would",
        "could", "should", "may", "might", "must", "can", "this", "that", "these", "those",
        "i", "you", "he", "she", "it", "we", "they", "my", "your", "his", "her", "its",
        "our", "their", "what", "which", "who", "whom", "how", "why", "when", "where",
        "official", "video", "full", "hd", "4k", "1080p", "episode", "ep", "part", "pt",
        "watch", "new", "best", "top", "vs", "ft", "feat", "shorts", "short",
    )

    /** Extracts 2–5 meaningful topic keywords from a video title. */
    fun extractTopicKeywords(title: String, maxWords: Int = 4): String {
        val cleaned = title
            .replace(BRACKETED, " ")
            .replace(HASHTAG, " ")
            .replace(NON_WORD, " ")
            .trim()
        val words = cleaned.split(WHITESPACE)
            .map { it.trim('-', '\'') }
            .filter { it.length >= 2 && it.lowercase() !in STOP_WORDS }
        if (words.isEmpty()) {
            return title.replace(HASHTAG, "").trim().take(36)
        }
        return words.distinctBy { it.lowercase() }.take(maxWords).joinToString(" ")
    }

    /**
     * Builds a search query that captures the style and topic of [video]: combines the
     * creator's name (when known) with the title's core topic keywords.
     */
    fun extractTasteQuery(video: VideoItem): String {
        val keywords = extractTopicKeywords(video.title, maxWords = 4)
        val channel = video.channel.trim()
        return when {
            channel.isNotBlank() && keywords.isNotBlank() &&
                !keywords.contains(channel, ignoreCase = true) -> "$channel $keywords"
            keywords.isNotBlank() -> keywords
            channel.isNotBlank() -> channel
            else -> video.title.trim()
        }.trim()
    }

    /**
     * Derives the active recommendation seeds from [entries] (newest first) and
     * [recentQueries] (newest first).
     */
    fun buildTasteSeeds(
        entries: List<LocalHistoryEntry>,
        recentQueries: List<String>,
        maxVideos: Int = 4,
        maxQueries: Int = 4,
    ): TasteSeeds {
        val seedVideoIds = entries
            .map { it.video.id }
            .filter { it.isNotBlank() }
            .distinct()
            .take(maxVideos)

        val queries = LinkedHashSet<String>()
        fun addQuery(q: String) {
            val clean = q.trim()
            if (clean.length >= 2 && queries.none { it.equals(clean, ignoreCase = true) }) {
                queries.add(clean)
            }
        }

        // Interleave the newest watched videos' taste queries and newest search queries
        // so whichever action the user just took leads the recommendation style.
        val maxLen = maxOf(entries.size.coerceAtMost(maxVideos), recentQueries.size.coerceAtMost(maxQueries))
        for (i in 0 until maxLen) {
            entries.getOrNull(i)?.video?.let { v ->
                addQuery(extractTasteQuery(v))
                val topicOnly = extractTopicKeywords(v.title, maxWords = 3)
                if (topicOnly.length >= 3) addQuery(topicOnly)
            }
            recentQueries.getOrNull(i)?.let(::addQuery)
        }

        val pills = LinkedHashSet<String>()
        fun addPill(label: String) {
            val clean = label.trim()
            if (clean.length in 2..24 && pills.none { it.equals(clean, ignoreCase = true) }) {
                pills.add(clean)
            }
        }
        entries.take(5).forEach { entry ->
            if (entry.video.channel.isNotBlank()) addPill(entry.video.channel)
            val topic = extractTopicKeywords(entry.video.title, maxWords = 2)
            if (topic.isNotBlank()) addPill(topic)
        }
        recentQueries.take(4).forEach(::addPill)

        val preferredChannels = entries
            .mapNotNull { it.video.channel.trim().takeIf { ch -> ch.isNotBlank() }?.lowercase() }
            .toSet()

        return TasteSeeds(
            seedVideoIds = seedVideoIds,
            tasteQueries = queries.take(maxQueries),
            topicPills = pills.take(6),
            preferredChannels = preferredChannels,
        )
    }

    /**
     * Combines candidate lists from multiple seeds (ordered from most recent seed to older
     * seeds) into a single personalized feed.
     *
     *  - The latest seed (`sources[0]`) is weighted highest (2 items per round) so the feed
     *    immediately reflects what the user just watched or searched.
     *  - Videos that appear in multiple seed sources (co-occurrence) are boosted to the top
     *    because they bridge the user's recent videos.
     *  - Already-watched ([watchedIds]) and disliked ([dislikedIds]) videos are excluded.
     */
    fun interleaveRecommendations(
        sources: List<List<VideoItem>>,
        watchedIds: Set<String>,
        shownIds: Set<String> = emptySet(),
        dislikedIds: Set<String> = emptySet(),
        preferredChannels: Set<String> = emptySet(),
        limit: Int = 40,
    ): List<VideoItem> {
        if (sources.isEmpty()) return emptyList()

        // Count how many distinct sources recommend each video ID.
        val occurrence = HashMap<String, Int>()
        sources.forEach { list ->
            list.map { it.id }.distinct().forEach { id ->
                occurrence[id] = (occurrence[id] ?: 0) + 1
            }
        }

        fun filterCandidates(excludeShown: Boolean): List<List<VideoItem>> =
            sources.map { list ->
                list.filter { v ->
                    v.id.isNotBlank() &&
                        v.id !in watchedIds &&
                        v.id !in dislikedIds &&
                        (!excludeShown || v.id !in shownIds)
                }
            }

        var filtered = filterCandidates(excludeShown = shownIds.isNotEmpty())
        if (filtered.sumOf { it.size } < 4 && shownIds.isNotEmpty()) {
            filtered = filterCandidates(excludeShown = false)
        }
        if (filtered.all { it.isEmpty() }) {
            // If every single candidate was already in watchedIds, allow unwatched-first then watched.
            filtered = sources.map { list -> list.filter { it.id.isNotBlank() && it.id !in dislikedIds } }
        }

        val result = LinkedHashMap<String, VideoItem>()

        // 1. Boosted items that appear across 2+ seed sources (strongest taste match).
        filtered.flatten()
            .filter { (occurrence[it.id] ?: 0) >= 2 }
            .forEach { if (result.size < limit) result.putIfAbsent(it.id, it) }

        // 2. Weighted round-robin across sources: newest seed contributes 2 per cycle,
        // subsequent seeds contribute 1 per cycle.
        val cursors = IntArray(filtered.size)
        var progress = true
        while (result.size < limit && progress) {
            progress = false
            for (sIndex in filtered.indices) {
                val list = filtered[sIndex]
                val quota = if (sIndex == 0) 2 else 1
                var taken = 0
                while (cursors[sIndex] < list.size && taken < quota && result.size < limit) {
                    val item = list[cursors[sIndex]++]
                    progress = true
                    if (result.putIfAbsent(item.id, item) == null) {
                        taken++
                    }
                }
            }
        }

        return result.values.toList()
    }

    /** Separates long-form videos and Shorts, keeping order and deduplicating by ID. */
    fun separateShortsAndVideos(
        candidates: List<VideoItem>,
        explicitShorts: List<VideoItem>,
        watchedIds: Set<String> = emptySet(),
        dislikedIds: Set<String> = emptySet(),
    ): Pair<List<VideoItem>, List<VideoItem>> {
        val shortsMap = LinkedHashMap<String, VideoItem>()
        val videosMap = LinkedHashMap<String, VideoItem>()

        explicitShorts.forEach { s ->
            if (s.id.isNotBlank() && s.id !in dislikedIds) {
                shortsMap.putIfAbsent(s.id, s.copy(isShort = true))
            }
        }
        candidates.forEach { item ->
            if (item.id.isBlank() || item.id in dislikedIds) return@forEach
            val isShortItem = item.isShort || (item.durationSec in 1..60 && (item.title.contains("#short", ignoreCase = true) || item.thumbnail?.contains("oar") == true))
            if (isShortItem) {
                shortsMap.putIfAbsent(item.id, item.copy(isShort = true))
            } else if (item.id !in shortsMap) {
                videosMap.putIfAbsent(item.id, item)
            }
        }

        // Put unwatched Shorts first so the shelf always feels fresh.
        val orderedShorts = shortsMap.values.sortedBy { if (it.id in watchedIds) 1 else 0 }
        return videosMap.values.toList() to orderedShorts
    }
}

/**
 * Reactive state for the personalized Home screen (works both signed out and as local
 * taste enrichment when signed in). Automatically refines recommendations whenever
 * [LocalHistory.tasteSignature] changes.
 */
@Stable
class PersonalizedHomeState(private val scope: CoroutineScope) {
    var videos by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var shorts by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var topics by mutableStateOf<List<String>>(emptyList())
        private set
    var selectedTopic by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var generation by mutableIntStateOf(0)
        private set

    /** Bumped only by an explicit user refresh, so the list returns to the top when asked. */
    var refreshToken by mutableIntStateOf(0)
        private set

    private var lastTasteKey: String = ""
    private val shownIds = HashSet<String>()
    private var nextRelatedToken: Pair<String, String>? = null
    private var nextSearchToken: Pair<String, String>? = null
    private var job: Job? = null
    private var moreJob: Job? = null
    /** Set when a page brought nothing new: stop paging until the next explicit refresh. */
    private var exhausted = false

    val hasMore: Boolean
        get() = !exhausted && (nextRelatedToken != null || nextSearchToken != null)

    /**
     * Called by HomeScreen when the user's taste key (recent watches + searches) changes.
     *
     * Only that key starts a new load. Related-video caching no longer re-triggers one, and a
     * key that is already loaded is ignored, so the feed cannot reload itself in a loop.
     */
    fun syncWithTaste(tasteKey: String) {
        if (!LocalHistory.hasTasteSignal) {
            job?.cancel()
            moreJob?.cancel()
            videos = emptyList()
            shorts = emptyList()
            topics = emptyList()
            selectedTopic = null
            lastTasteKey = ""
            exhausted = false
            error = null
            loading = false
            return
        }
        if (tasteKey == lastTasteKey && (videos.isNotEmpty() || loading)) return
        lastTasteKey = tasteKey
        loadRecommendations(isRefresh = false)
    }

    fun selectTopic(topic: String?) {
        val normalized = topic?.takeIf { it != selectedTopic }
        selectedTopic = normalized
        loadRecommendations(isRefresh = false)
    }

    fun refresh() {
        if (!LocalHistory.hasTasteSignal) return
        refreshToken++
        loadRecommendations(isRefresh = true)
    }

    private fun loadRecommendations(isRefresh: Boolean) {
        // One load at a time: a second request for the same key replaces the one in flight
        // instead of stacking a second (and a third) fetch on top of it.
        job?.cancel()
        moreJob?.cancel()
        exhausted = false
        val entries = LocalHistory.entries
        val queries = LocalHistory.recentQueries
        val seeds = TasteEngine.buildTasteSeeds(entries, queries)
        topics = seeds.topicPills

        val watchedIds = entries.mapTo(HashSet()) { it.video.id }
        val dislikedIds = LocalHistory.dislikedIds

        // Immediate pass from in-memory cached related items so returning from the player
        // shows updated recommendations right away while deeper sources load.
        if (!isRefresh && selectedTopic == null) {
            val cachedSources = seeds.seedVideoIds.map { LocalHistory.cachedRelated(it) }.filter { it.isNotEmpty() }
            if (cachedSources.isNotEmpty()) {
                val immediate = TasteEngine.interleaveRecommendations(
                    sources = cachedSources,
                    watchedIds = watchedIds,
                    dislikedIds = dislikedIds,
                    preferredChannels = seeds.preferredChannels,
                )
                val (immVideos, immShorts) = TasteEngine.separateShortsAndVideos(immediate, emptyList(), watchedIds, dislikedIds)
                if (immVideos.isNotEmpty()) videos = immVideos
                if (immShorts.isNotEmpty() && shorts.isEmpty()) shorts = immShorts
            }
        }

        loading = true
        refreshing = isRefresh && videos.isNotEmpty()
        error = null
        job = scope.launch {
            runCatching {
                fetchTasteFeed(seeds, selectedTopic, watchedIds, dislikedIds, isRefresh)
            }.onSuccess { result ->
                videos = result.videos
                if (result.shorts.isNotEmpty() || shorts.isEmpty()) {
                    shorts = result.shorts
                }
                nextRelatedToken = result.nextRelated
                nextSearchToken = result.nextSearch
                result.videos.forEach { shownIds += it.id }
                result.videos.take(2).forEach { StreamCache.prefetch(it.id) }
                result.shorts.take(2).forEach { StreamCache.prefetch(it.id) }
                generation++
            }.onFailure { t ->
                if (videos.isEmpty()) error = YouTube.friendlyError(t)
            }
            loading = false
            refreshing = false
        }
    }

    fun loadMore() {
        if (loading || moreJob?.isActive == true) return
        val relToken = nextRelatedToken
        val srchToken = nextSearchToken
        if (relToken == null && srchToken == null) return
        loading = true
        moreJob = scope.launch {
            runCatching {
                coroutineScope {
                    val relDeferred = relToken?.let { (vid, tok) ->
                        async(Dispatchers.IO) { runCatching { InnerTube.related(vid, tok) }.getOrNull() }
                    }
                    val srchDeferred = srchToken?.let { (q, tok) ->
                        async(Dispatchers.IO) { runCatching { InnerTube.searchPage(q, tok) }.getOrNull() }
                    }
                    val relPage = relDeferred?.await()
                    val srchPage = srchDeferred?.await()
                    nextRelatedToken = relPage?.next?.let { relToken.first to it }
                    nextSearchToken = srchPage?.next?.let { srchToken.first to it }

                    val watchedIds = LocalHistory.entries.mapTo(HashSet()) { it.video.id }
                    val seen = videos.mapTo(HashSet()) { it.id }
                    val moreCandidates = TasteEngine.interleaveRecommendations(
                        sources = listOfNotNull(relPage?.videos, srchPage?.videos),
                        watchedIds = watchedIds + seen,
                        dislikedIds = LocalHistory.dislikedIds,
                    )
                    val (moreVideos, moreShorts) = TasteEngine.separateShortsAndVideos(
                        moreCandidates,
                        srchPage?.shorts.orEmpty(),
                        watchedIds,
                        LocalHistory.dislikedIds,
                    )
                    if (moreVideos.isNotEmpty()) {
                        videos = (videos + moreVideos).distinctBy { it.id }
                        moreVideos.forEach { shownIds += it.id }
                    }
                    if (moreShorts.isNotEmpty()) {
                        shorts = (shorts + moreShorts).distinctBy { it.id }
                    }
                    // A page that adds nothing means the feed is spent: without this the
                    // footer could ask for the next page forever.
                    if (moreVideos.isEmpty() && moreShorts.isEmpty()) exhausted = true
                }
            }
            loading = false
        }
    }

    private class FetchResult(
        val videos: List<VideoItem>,
        val shorts: List<VideoItem>,
        val nextRelated: Pair<String, String>?,
        val nextSearch: Pair<String, String>?,
    )

    private suspend fun fetchTasteFeed(
        seeds: TasteSeeds,
        topic: String?,
        watchedIds: Set<String>,
        dislikedIds: Set<String>,
        isRefresh: Boolean,
    ): FetchResult = coroutineScope {
        if (topic != null) {
            val mainDeferred = async(Dispatchers.IO) {
                runCatching { InnerTube.searchPage(topic) }.getOrNull()
            }
            val shortsDeferred = async(Dispatchers.IO) {
                runCatching { InnerTube.searchPage("$topic #shorts") }.getOrNull()
            }
            val fallbackDeferred = async(Dispatchers.IO) {
                runCatching { YouTube.search(topic) }.getOrDefault(emptyList())
            }
            val mainPage = mainDeferred.await()
            val shortsPage = shortsDeferred.await()
            val fallback = if (mainPage == null || mainPage.videos.isEmpty()) fallbackDeferred.await() else emptyList()
            val combined = TasteEngine.interleaveRecommendations(
                sources = listOfNotNull(mainPage?.videos, fallback.takeIf { it.isNotEmpty() }),
                watchedIds = watchedIds,
                shownIds = if (isRefresh) shownIds else emptySet(),
                dislikedIds = dislikedIds,
                preferredChannels = seeds.preferredChannels,
            )
            val (vids, sh) = TasteEngine.separateShortsAndVideos(
                combined,
                mainPage?.shorts.orEmpty() + shortsPage?.shorts.orEmpty(),
                watchedIds,
                dislikedIds,
            )
            return@coroutineScope FetchResult(
                videos = vids,
                shorts = sh,
                nextRelated = null,
                nextSearch = mainPage?.next?.let { topic to it },
            )
        }

        // Gather Watch-Next related feeds for the top 3 recently watched videos in parallel.
        val relatedDeferreds = seeds.seedVideoIds.take(3).map { videoId ->
            async(Dispatchers.IO) {
                val cached = LocalHistory.cachedRelated(videoId)
                val remote = runCatching { InnerTube.related(videoId) }.getOrNull()
                val items = when {
                    remote != null && remote.videos.isNotEmpty() -> {
                        LocalHistory.cacheRelated(videoId, remote.videos)
                        remote.videos
                    }
                    cached.isNotEmpty() -> cached
                    else -> runCatching { YouTube.details(videoId).related }.getOrDefault(emptyList()).also {
                        if (it.isNotEmpty()) LocalHistory.cacheRelated(videoId, it)
                    }
                }
                Triple(videoId, items, remote?.next)
            }
        }

        // Gather search-based style expansion for the top 2 taste queries in parallel.
        val activeQueries = seeds.tasteQueries.take(2)
        val searchDeferreds = activeQueries.map { q ->
            async(Dispatchers.IO) {
                val page = runCatching { InnerTube.searchPage(q) }.getOrNull()
                val videos = if (page != null && page.videos.isNotEmpty()) {
                    page.videos
                } else {
                    runCatching { YouTube.search(q) }.getOrDefault(emptyList())
                }
                Triple(q, SearchPage(videos, page?.shorts.orEmpty(), page?.next), page?.next)
            }
        }

        // Dedicated Shorts query in the style of the user's latest taste so the Shorts shelf
        // is always populated even when Watch-Next only returns long-form videos.
        val primaryTasteQuery = activeQueries.firstOrNull() ?: LocalHistory.entries.firstOrNull()?.video?.let(TasteEngine::extractTasteQuery).orEmpty()
        val shortsQueryDeferred = if (primaryTasteQuery.isNotBlank()) {
            async(Dispatchers.IO) {
                runCatching { InnerTube.searchPage("$primaryTasteQuery #shorts") }.getOrNull()
            }
        } else null

        val relatedResults = relatedDeferreds.awaitAll()
        val searchResults = searchDeferreds.awaitAll()
        val extraShortsPage = shortsQueryDeferred?.await()

        // Order sources so the newest watched video / newest search leads.
        val sources = mutableListOf<List<VideoItem>>()
        val maxLen = maxOf(relatedResults.size, searchResults.size)
        for (i in 0 until maxLen) {
            relatedResults.getOrNull(i)?.second?.takeIf { it.isNotEmpty() }?.let(sources::add)
            searchResults.getOrNull(i)?.second?.videos?.takeIf { it.isNotEmpty() }?.let(sources::add)
        }

        val allExplicitShorts = buildList {
            searchResults.forEach { addAll(it.second.shorts) }
            if (extraShortsPage != null) {
                addAll(extraShortsPage.shorts)
                addAll(extraShortsPage.videos.filter { it.isShort || it.durationSec in 1..60 })
            }
        }

        val interleaved = TasteEngine.interleaveRecommendations(
            sources = sources,
            watchedIds = watchedIds,
            shownIds = if (isRefresh) shownIds else emptySet(),
            dislikedIds = dislikedIds,
            preferredChannels = seeds.preferredChannels,
            limit = 45,
        )

        val (finalVideos, finalShorts) = TasteEngine.separateShortsAndVideos(
            candidates = interleaved,
            explicitShorts = allExplicitShorts,
            watchedIds = watchedIds,
            dislikedIds = dislikedIds,
        )

        val nextRel = relatedResults.firstNotNullOfOrNull { (vid, _, next) -> next?.let { vid to it } }
        val nextSrch = searchResults.firstNotNullOfOrNull { (q, _, next) -> next?.let { q to it } }

        if (finalVideos.isEmpty() && finalShorts.isEmpty()) {
            throw java.io.IOException(Loc.t("Couldn't reach YouTube"))
        }

        FetchResult(
            videos = finalVideos,
            shorts = finalShorts,
            nextRelated = nextRel,
            nextSearch = nextSrch,
        )
    }
}

/**
 * Feeds the signed-out Subscriptions tab using channels the user follows locally or has
 * watched recently ([LocalHistory.activeChannels]).
 */
@Stable
class LocalSubscriptionsFeedState(private val scope: CoroutineScope) {
    var videos by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var lastKey: String = ""
    private var job: Job? = null

    fun sync(channels: List<ChannelRef>) {
        val key = channels.take(6).joinToString(",") { it.id }
        if (channels.isEmpty()) {
            job?.cancel()
            videos = emptyList()
            lastKey = ""
            loading = false
            return
        }
        if (key == lastKey && (videos.isNotEmpty() || loading)) return
        lastKey = key
        load(channels, isRefresh = false)
    }

    fun refresh(channels: List<ChannelRef>) {
        if (channels.isEmpty()) return
        load(channels, isRefresh = true)
    }

    private fun load(channels: List<ChannelRef>, isRefresh: Boolean) {
        loading = true
        refreshing = isRefresh && videos.isNotEmpty()
        error = null
        job?.cancel()
        job = scope.launch {
            runCatching {
                coroutineScope {
                    val lists = channels.take(5).map { ch ->
                        async(Dispatchers.IO) {
                            val ucId = ch.id.substringAfter("/channel/", ch.id).takeIf { it.startsWith("UC") }
                            val items = if (ucId != null) {
                                runCatching { InnerTube.channelVideos(ucId, null).videos }.getOrDefault(emptyList())
                            } else {
                                runCatching { InnerTube.searchPage(ch.name).videos }.getOrDefault(emptyList())
                            }
                            items.map {
                                it.copy(
                                    channel = it.channel.ifBlank { ch.name },
                                    avatar = it.avatar ?: ch.avatar,
                                    channelUrl = it.channelUrl ?: ch.resolvedUrl,
                                )
                            }
                        }
                    }.awaitAll()
                    TasteEngine.interleaveRecommendations(
                        sources = lists,
                        watchedIds = emptySet(),
                        dislikedIds = LocalHistory.dislikedIds,
                        limit = 40,
                    )
                }
            }.onSuccess {
                videos = it
            }.onFailure {
                if (videos.isEmpty()) error = YouTube.friendlyError(it)
            }
            loading = false
            refreshing = false
        }
    }
}
