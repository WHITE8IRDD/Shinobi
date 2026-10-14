package com.shinobi.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** A recent item held only on this device; it is never sent to the account or a server. */
data class LocalHistoryEntry(val video: VideoItem, val watchedAtMs: Long, val watchedMs: Long = 0L)

/**
 * Private on-device state for signed-out use (and local taste refinement):
 *  - recent watched videos and Shorts ([entries]);
 *  - recent search queries ([recentQueries]);
 *  - on-device Watch later ([watchLaterVideos]), Liked videos ([likedVideos]), and followed
 *    channels ([followedChannels]);
 *  - an in-memory cache of related videos ([relatedCache]) so the Home recommendation engine
 *    can refine suggestions immediately as each video plays.
 */
object LocalHistory {
    private const val PREFS = "local_history"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_QUERIES = "queries"
    private const val KEY_LIKED = "liked"
    private const val KEY_DISLIKED = "disliked"
    private const val KEY_WATCH_LATER = "watch_later"
    private const val KEY_CHANNELS = "followed_channels"
    private const val MAX_ENTRIES = 100
    private const val MAX_QUERIES = 25
    private const val MAX_COLLECTION = 200
    private var prefs: SharedPreferences? = null

    var entries by mutableStateOf<List<LocalHistoryEntry>>(emptyList())
        private set

    var recentQueries by mutableStateOf<List<String>>(emptyList())
        private set

    var likedVideos by mutableStateOf<List<VideoItem>>(emptyList())
        private set

    var dislikedIds by mutableStateOf<Set<String>>(emptySet())
        private set

    var watchLaterVideos by mutableStateOf<List<VideoItem>>(emptyList())
        private set

    var followedChannels by mutableStateOf<List<ChannelRef>>(emptyList())
        private set

    /**
     * Bumped only when a *new* related list is cached (see [cacheRelated]). It is part of the
     * taste signature for cheap in-memory re-ranking, but never triggers a network reload by
     * itself — that is what used to make Home reload itself forever.
     */
    var relatedVersion by mutableIntStateOf(0)
        private set

    /**
     * The taste-relevant part of [tasteSignature]: what the user watched and searched for.
     * Kept as state and recomputed on writes rather than on reads, so screens can observe it
     * without building a string on every recomposition.
     */
    var tasteKey by mutableStateOf("")
        private set

    private val relatedCache = ConcurrentHashMap<String, List<VideoItem>>()

    /** True once the user has searched for or watched at least one video on this device. */
    val hasTasteSignal: Boolean
        get() = entries.isNotEmpty() || recentQueries.isNotEmpty() || likedVideos.isNotEmpty()

    /**
     * Reactive signature of the user's latest taste signals (recent watched videos + recent
     * search queries). Home observes this to refine its feed.
     */
    val tasteSignature: String
        get() = "$tasteKey|$relatedVersion"

    private fun refreshTasteKey() {
        tasteKey = buildString {
            entries.take(100).forEach { e ->
                append(e.video.id).append(':').append(e.watchedMs >= 5_000).append(':').append(e.video.channel).append(',')
            }
            append('|')
            recentQueries.take(5).forEach { q -> append(q).append(',') }
            append('|'); likedVideos.take(20).forEach { append(it.id).append(',') }
            append('|'); dislikedIds.sorted().forEach { append(it).append(',') }
        }
    }

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
        entries = runCatching {
            val array = JSONArray(p.getString(KEY_ENTRIES, null).orEmpty())
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val item = obj.toVideoItem() ?: continue
                    val watchedAt = obj.optLong("watchedAt", 0L).takeIf { it > 0L } ?: continue
                    add(LocalHistoryEntry(item, watchedAt, obj.optLong("watchedMs", 30_000L)))
                }
            }.take(MAX_ENTRIES)
        }.getOrDefault(emptyList())

        recentQueries = runCatching {
            val array = JSONArray(p.getString(KEY_QUERIES, null).orEmpty())
            buildList {
                for (index in 0 until array.length()) {
                    val q = array.optString(index, "").trim()
                    if (q.isNotBlank()) add(q)
                }
            }.distinct().take(MAX_QUERIES)
        }.getOrDefault(emptyList())
        refreshTasteKey()

        likedVideos = parseVideoList(p.getString(KEY_LIKED, null))
        watchLaterVideos = parseVideoList(p.getString(KEY_WATCH_LATER, null))
        dislikedIds = runCatching {
            val array = JSONArray(p.getString(KEY_DISLIKED, null).orEmpty())
            buildSet {
                for (index in 0 until array.length()) {
                    val id = array.optString(index, "").trim()
                    if (id.isNotBlank()) add(id)
                }
            }
        }.getOrDefault(emptySet())

        followedChannels = runCatching {
            val array = JSONArray(p.getString(KEY_CHANNELS, null).orEmpty())
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val id = obj.optString("id", "").trim().ifBlank { continue }
                    val name = obj.optString("name", "").trim().ifBlank { continue }
                    add(
                        ChannelRef(
                            id = id,
                            name = name,
                            avatar = obj.nullableString("avatar"),
                            url = obj.nullableString("url"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
        refreshTasteKey()
    }

    @Synchronized
    fun record(video: VideoItem, watchedAtMs: Long = System.currentTimeMillis()) {
        if (video.id.isBlank() || video.title.isBlank()) return
        val existing = entries.firstOrNull { it.video.id == video.id }?.video
        val merged = if (existing != null) {
            video.copy(
                channel = video.channel.ifBlank { existing.channel },
                channelUrl = video.channelUrl ?: existing.channelUrl,
                thumbnail = video.thumbnail ?: existing.thumbnail,
                avatar = video.avatar ?: existing.avatar,
                durationSec = if (video.durationSec > 0) video.durationSec else existing.durationSec,
                views = if (video.views >= 0) video.views else existing.views,
                uploaded = video.uploaded ?: existing.uploaded,
                isShort = video.isShort || existing.isShort,
            )
        } else video
        entries = (listOf(LocalHistoryEntry(merged, watchedAtMs, entries.firstOrNull { it.video.id == video.id }?.watchedMs ?: 0L)) + entries.filter { it.video.id != video.id })
            .take(MAX_ENTRIES)
        refreshTasteKey()
        persistEntries()
    }

    /** Playback time, not seek position or a thumbnail tap, qualifies a recommendation seed. */
    @Synchronized
    fun recordViewing(videoId: String, elapsedMs: Long) {
        val entry = entries.firstOrNull { it.video.id == videoId } ?: return
        if (entry.watchedMs >= 30_000L) return
        entries = entries.map { if (it.video.id == videoId) it.copy(watchedMs = (it.watchedMs + elapsedMs.coerceIn(0, 5_000)).coerceAtMost(30_000)) else it }
        refreshTasteKey()
        persistEntries()
    }

    @Synchronized
    fun recordSearch(query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        recentQueries = (listOf(clean) + recentQueries.filterNot { it.equals(clean, ignoreCase = true) })
            .take(MAX_QUERIES)
        refreshTasteKey()
        persistQueries()
    }

    @Synchronized
    fun removeSearch(query: String) {
        val updated = recentQueries.filterNot { it.equals(query, ignoreCase = true) }
        if (updated.size == recentQueries.size) return
        recentQueries = updated
        refreshTasteKey()
        persistQueries()
    }

    @Synchronized
    fun clearSearches() {
        recentQueries = emptyList()
        refreshTasteKey()
        prefs?.edit()?.remove(KEY_QUERIES)?.apply()
    }

    fun cacheRelated(videoId: String, related: List<VideoItem>) {
        if (videoId.isBlank() || related.isEmpty()) return
        // Only a *change* may bump the version. Bumping it for an identical list (which the
        // recommendation engine does on every pass) used to change the taste signature,
        // which made Home reload itself in a loop.
        val previous = relatedCache[videoId]
        if (previous != null && previous.size == related.size &&
            previous.asSequence().map { it.id }.toList() == related.asSequence().map { it.id }.toList()
        ) {
            return
        }
        if (relatedCache.size >= 50) relatedCache.keys.firstOrNull()?.let { relatedCache.remove(it) }
        relatedCache[videoId] = related.take(60)
        relatedVersion++
    }

    fun cachedRelated(videoId: String): List<VideoItem> = relatedCache[videoId].orEmpty()

    @Synchronized
    fun remove(videoId: String) {
        val updated = entries.filterNot { it.video.id == videoId }
        if (updated.size == entries.size) return
        entries = updated
        refreshTasteKey()
        persistEntries()
    }

    @Synchronized
    fun clear() {
        entries = emptyList()
        relatedCache.clear()
        refreshTasteKey()
        prefs?.edit()?.remove(KEY_ENTRIES)?.apply()
    }

    // ── Local Likes & Watch Later ──

    fun likeStatus(videoId: String): LikeStatus = when {
        likedVideos.any { it.id == videoId } -> LikeStatus.Like
        videoId in dislikedIds -> LikeStatus.Dislike
        else -> LikeStatus.None
    }

    @Synchronized
    fun setLike(video: VideoItem, status: LikeStatus) {
        if (video.id.isBlank()) return
        when (status) {
            LikeStatus.Like -> {
                likedVideos = (listOf(video) + likedVideos.filterNot { it.id == video.id }).take(MAX_COLLECTION)
                dislikedIds = dislikedIds - video.id
            }
            LikeStatus.Dislike -> {
                likedVideos = likedVideos.filterNot { it.id == video.id }
                dislikedIds = dislikedIds + video.id
            }
            LikeStatus.None -> {
                likedVideos = likedVideos.filterNot { it.id == video.id }
                dislikedIds = dislikedIds - video.id
            }
        }
        refreshTasteKey()
        persistVideoList(KEY_LIKED, likedVideos)
        val arr = JSONArray().apply { dislikedIds.forEach { put(it) } }
        prefs?.edit()?.putString(KEY_DISLIKED, arr.toString())?.apply()
    }

    fun isInWatchLater(videoId: String): Boolean = watchLaterVideos.any { it.id == videoId }

    @Synchronized
    fun setWatchLater(video: VideoItem, add: Boolean) {
        if (video.id.isBlank()) return
        watchLaterVideos = if (add) {
            (listOf(video) + watchLaterVideos.filterNot { it.id == video.id }).take(MAX_COLLECTION)
        } else {
            watchLaterVideos.filterNot { it.id == video.id }
        }
        persistVideoList(KEY_WATCH_LATER, watchLaterVideos)
    }

    // ── Local Followed & Watched Channels ──

    fun isSubscribed(channelIdOrUrl: String?): Boolean {
        val key = normalizeChannelKey(channelIdOrUrl) ?: return false
        return followedChannels.any { normalizeChannelKey(it.id) == key || normalizeChannelKey(it.url) == key }
    }

    @Synchronized
    fun setSubscribed(channel: ChannelRef, subscribe: Boolean) {
        val key = normalizeChannelKey(channel.id) ?: normalizeChannelKey(channel.url) ?: return
        followedChannels = if (subscribe) {
            (listOf(channel) + followedChannels.filterNot {
                normalizeChannelKey(it.id) == key || normalizeChannelKey(it.url) == key
            }).take(MAX_ENTRIES)
        } else {
            followedChannels.filterNot {
                normalizeChannelKey(it.id) == key || normalizeChannelKey(it.url) == key
            }
        }
        persistChannels()
    }

    /**
     * Channels relevant to the user on this device: explicitly followed channels first,
     * followed by channels from their watch history.
     */
    fun activeChannels(): List<ChannelRef> {
        val out = LinkedHashMap<String, ChannelRef>()
        followedChannels.forEach { ch ->
            val key = normalizeChannelKey(ch.id) ?: normalizeChannelKey(ch.url) ?: ch.name.lowercase()
            out.putIfAbsent(key, ch)
        }
        entries.forEach { entry ->
            val v = entry.video
            if (v.channel.isBlank()) return@forEach
            val channelId = v.channelUrl?.substringAfter("/channel/", "")?.takeIf { it.startsWith("UC") }
                ?: v.channelUrl
                ?: return@forEach
            val key = normalizeChannelKey(channelId) ?: v.channel.lowercase()
            out.putIfAbsent(
                key,
                ChannelRef(
                    id = channelId,
                    name = v.channel,
                    avatar = v.avatar,
                    url = v.channelUrl,
                ),
            )
        }
        return out.values.toList()
    }

    private fun normalizeChannelKey(idOrUrl: String?): String? {
        val raw = idOrUrl?.trim()?.ifBlank { null } ?: return null
        return raw.substringAfter("/channel/", raw).trimEnd('/')
    }

    // ── Persistence helpers ──

    /**
     * Persistence runs on one background thread: recording a video happens on the tap that
     * opens it (and again when its details land), which is exactly when the app must not be
     * serializing a hundred history entries on the main thread. State is still updated
     * synchronously, so every screen sees the change immediately.
     */
    private val disk = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "shinobi-history").apply { isDaemon = true }
    }

    private fun persistEntries() {
        val snapshot = entries
        val p = prefs ?: return
        disk.execute {
            val array = JSONArray()
            snapshot.forEach { entry ->
                array.put(entry.video.toJson().put("watchedAt", entry.watchedAtMs).put("watchedMs", entry.watchedMs))
            }
            p.edit().putString(KEY_ENTRIES, array.toString()).apply()
        }
    }

    private fun persistQueries() {
        val snapshot = recentQueries
        val p = prefs ?: return
        disk.execute {
            val array = JSONArray()
            snapshot.forEach { array.put(it) }
            p.edit().putString(KEY_QUERIES, array.toString()).apply()
        }
    }

    private fun persistVideoList(key: String, list: List<VideoItem>) {
        val snapshot = list
        val p = prefs ?: return
        disk.execute {
            val array = JSONArray()
            snapshot.forEach { array.put(it.toJson()) }
            p.edit().putString(key, array.toString()).apply()
        }
    }

    private fun persistChannels() {
        val snapshot = followedChannels
        val p = prefs ?: return
        disk.execute {
            val array = JSONArray()
            snapshot.forEach { ch ->
                array.put(
                    JSONObject()
                        .put("id", ch.id)
                        .put("name", ch.name)
                        .put("avatar", ch.avatar)
                        .put("url", ch.url),
                )
            }
            p.edit().putString(KEY_CHANNELS, array.toString()).apply()
        }
    }

    private fun parseVideoList(raw: String?): List<VideoItem> = runCatching {
        val array = JSONArray(raw.orEmpty())
        buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                obj.toVideoItem()?.let(::add)
            }
        }.take(MAX_COLLECTION)
    }.getOrDefault(emptyList())

    private fun VideoItem.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("channel", channel)
        .put("channelUrl", channelUrl)
        .put("thumbnail", thumbnail)
        .put("avatar", avatar)
        .put("durationSec", durationSec)
        .put("views", views)
        .put("uploaded", uploaded)
        .put("isShort", isShort)

    private fun JSONObject.toVideoItem(): VideoItem? {
        val id = optString("id", "").takeIf { it.isNotBlank() } ?: return null
        return VideoItem(
            id = id,
            title = optString("title", "").ifBlank { Loc.t("Untitled video") },
            channel = optString("channel", ""),
            channelUrl = nullableString("channelUrl"),
            thumbnail = nullableString("thumbnail"),
            avatar = nullableString("avatar"),
            durationSec = optLong("durationSec", 0L).coerceAtLeast(0L),
            views = optLong("views", -1L),
            uploaded = nullableString("uploaded"),
            isShort = optBoolean("isShort", false),
        )
    }

    private fun JSONObject.nullableString(key: String): String? =
        optString(key, "").takeIf { it.isNotBlank() && it != "null" }
}
