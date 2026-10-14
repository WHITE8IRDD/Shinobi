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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

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
    var refreshToken by mutableIntStateOf(0)
        private set
    private var lastTasteKey: String? = null
    private var job: Job? = null
    private var sources = emptyList<HomeSource>()
    private var exhausted = false
    val hasMore get() = !exhausted && videos.size < 240 && sources.any { it.next != null }

    private data class HomeSource(
        val key: String,
        val kind: RecommendationMixer.Kind,
        val next: String? = null,
    )
    private data class Loaded(val source: HomeSource, val page: SearchPage)

    fun syncWithTaste(tasteKey: String) {
        if (lastTasteKey == tasteKey && (videos.isNotEmpty() || loading)) return
        lastTasteKey = tasteKey
        refresh(explicit = false)
    }

    fun selectTopic(topic: String?) {
        selectedTopic = topic?.takeIf { it != selectedTopic }
        refresh()
    }

    fun refresh() = refresh(explicit = true)

    private fun refresh(explicit: Boolean) {
        job?.cancel()
        if (explicit) refreshToken++
        val history = RecommendationMixer.diverseSeeds(LocalHistory.entries.filter { it.watchedMs >= 5_000 }.map { it.video }, 3)
        val likes = RecommendationMixer.diverseSeeds(LocalHistory.likedVideos.filter { liked -> history.none { it.id == liked.id } }, 2)
        topics = TasteEngine.buildTasteSeeds(LocalHistory.entries, LocalHistory.recentQueries).topicPills
        val topic = selectedTopic
        val requested = if (topic != null) listOf(HomeSource(topic, RecommendationMixer.Kind.Search)) else buildList {
            add(HomeSource("discovery", RecommendationMixer.Kind.Discovery))
            history.forEach { add(HomeSource(it.id, RecommendationMixer.Kind.History)) }
            likes.forEach { add(HomeSource(it.id, RecommendationMixer.Kind.Like)) }
            // A like already present in history is moved into the bounded like bucket.
            if (likes.isEmpty()) LocalHistory.likedVideos.firstOrNull()?.let { liked ->
                removeAll { it.key == liked.id }
                add(HomeSource(liked.id, RecommendationMixer.Kind.Like))
            }
            if (history.isEmpty()) LocalHistory.recentQueries.firstOrNull()?.let {
                add(HomeSource(it, RecommendationMixer.Kind.Search))
            }
        }
        exhausted = false
        load(requested, append = false, topicOnly = topic != null)
    }

    fun loadMore() {
        if (loading || !hasMore || error != null) return
        load(sources.filter { it.next != null }, append = true, topicOnly = selectedTopic != null)
    }

    private suspend fun fetch(source: HomeSource): Loaded {
        val page = when (source.kind) {
            RecommendationMixer.Kind.Discovery -> {
                val discovery = InnerTube.discovery(source.next)
                if (source.next == null && discovery.shorts.isEmpty()) {
                    val extra = try { InnerTube.searchPage("#shorts") }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { null }
                    discovery.copy(shorts = extra?.shorts.orEmpty() + extra?.videos.orEmpty().filter { it.isShort })
                } else discovery
            }
            RecommendationMixer.Kind.Search -> InnerTube.searchPage(source.key, source.next)
            else -> {
                val page = InnerTube.related(source.key, source.next)
                SearchPage(page.videos, page.videos.filter { it.isShort }, page.next)
            }
        }
        // A repeated token is terminal, not a request loop.
        return Loaded(source.copy(next = page.next?.takeUnless { it == source.next }), page)
    }

    private fun load(requested: List<HomeSource>, append: Boolean, topicOnly: Boolean) {
        loading = true
        refreshing = !append && videos.isNotEmpty()
        error = null
        // Snapshot exclusions once, not while ranking on the UI thread.
        val excluded = LocalHistory.dislikedIds + LocalHistory.entries.map { it.video.id } +
            if (append) videos.map { it.id } + shorts.map { it.id } else emptyList()
        job = scope.launch {
            try {
                val loaded = coroutineScope {
                    requested.map { source -> async(Dispatchers.IO) {
                        try { fetch(source) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { null }
                    } }.awaitAll().filterNotNull()
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (loaded.isEmpty()) throw java.io.IOException(Loc.t("Couldn't reach YouTube"))
                val ranked = kotlinx.coroutines.withContext(Dispatchers.Default) {
                    fun rank(short: Boolean): List<VideoItem> {
                        val inputs = loaded.map { (src, page) ->
                            val items = if (short) page.shorts + page.videos.filter { it.isShort } else page.videos.filterNot { it.isShort }
                            RecommendationMixer.Source(src.key, src.kind, items)
                        }
                        return if (topicOnly) inputs.flatMap { it.videos }.filter { it.id !in excluded }.distinctBy { it.id }.take(30)
                        else RecommendationMixer.mix(inputs, excluded)
                    }
                    rank(false) to rank(true)
                }
                sources = loaded.map { it.source }
                if (append) {
                    videos = (videos + ranked.first).distinctBy { it.id }.take(240)
                    shorts = (shorts + ranked.second).distinctBy { it.id }.take(60)
                } else {
                    videos = ranked.first
                    shorts = ranked.second
                }
                exhausted = ranked.first.isEmpty() && ranked.second.isEmpty()
                if (videos.isEmpty() && shorts.isEmpty()) error = Loc.t("No suggestions available. Try again later.")
                generation++
                // No speculative stream extraction for every feed refresh: it competes with playback.
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = YouTube.friendlyError(e)
            } finally {
                // A cancelled older request must never clear a newer request's loading flag.
                if (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    loading = false
                    refreshing = false
                }
            }
        }
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
