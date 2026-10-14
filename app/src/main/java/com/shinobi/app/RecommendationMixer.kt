package com.shinobi.app

import java.util.Locale

/** Deterministic source budgets, not a shuffle or a replica of YouTube's private ranker. */
object RecommendationMixer {
    enum class Kind { Discovery, History, Like, Search }
    data class Source(val key: String, val kind: Kind, val videos: List<VideoItem>)

    private fun words(video: VideoItem) = TasteEngine.extractTopicKeywords(video.title, 8)
        .lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.length > 2 }.toSet()

    /** Recency is the tie breaker; novelty lets older interests survive a recent topic binge. */
    fun diverseSeeds(videos: List<VideoItem>, limit: Int): List<VideoItem> {
        val pool = videos.filter { it.id.isNotBlank() }.distinctBy { it.id }.take(100).toMutableList()
        val result = mutableListOf<VideoItem>()
        while (result.size < limit && pool.isNotEmpty()) {
            val best = pool.withIndex().maxByOrNull { (index, video) ->
                val tokens = words(video)
                val similarity = result.maxOfOrNull { other ->
                    val overlap = tokens.intersect(words(other)).size.toDouble() / maxOf(1, minOf(tokens.size, words(other).size))
                    maxOf(overlap, if (video.channel.isNotBlank() && video.channel == other.channel) 1.0 else 0.0)
                } ?: 0.0
                1.0 / (1 + index * 0.04) - similarity * 2.0
            }!!.value
            result += best
            pool.remove(best)
        }
        return result
    }

    /**
     * Each 30-card page reserves 12 slots for YouTube discovery, 12 for history and 6
     * for likes. Unused slots go ONLY to discovery, never to the sole liked/recent topic.
     * Each personal seed contributes at most six cards. Duplicate sources get no boost.
     * Rank within a source combines YouTube order with recent creator/topic diversity.
     */
    fun mix(sources: List<Source>, excluded: Set<String>, limit: Int = 30): List<VideoItem> {
        if (limit <= 0) return emptyList()
        val pools = sources.map { s -> s.videos.filter { it.id.isNotBlank() && it.id !in excluded }.distinctBy { it.id }.take(60).toMutableList() }
        val tokens = pools.flatten().associate { it.id to words(it) }
        val used = IntArray(sources.size)
        val result = mutableListOf<VideoItem>()
        val seen = excluded.toMutableSet()
        val pattern = listOf(Kind.Discovery, Kind.History, Kind.Discovery, Kind.Like, Kind.History)
        fun take(kind: Kind): VideoItem? {
            val choices = sources.indices.filter { sources[it].kind == kind &&
                (kind == Kind.Discovery || used[it] < maxOf(1, limit / 5)) }
            // Least-used seed first: adding more search expansions cannot amplify one seed.
            for (i in choices.sortedBy { used[it] }) {
                pools[i].removeAll { it.id in seen }
                val recent = result.takeLast(8)
                val candidate = pools[i].withIndex().maxByOrNull { (rank, v) ->
                    val sameCreator = if (v.channel.isBlank()) 0 else recent.count { it.channel == v.channel }
                    val overlap = recent.maxOfOrNull { other ->
                        val a = tokens[v.id].orEmpty(); val b = tokens[other.id].orEmpty()
                        a.intersect(b).size.toDouble() / maxOf(1, minOf(a.size, b.size))
                    } ?: 0.0
                    1.0 / (1 + rank * 0.12) - sameCreator * 0.8 - overlap * 0.7
                }?.value ?: continue
                pools[i].remove(candidate)
                used[i]++
                seen += candidate.id
                return candidate
            }
            return null
        }
        repeat(limit) { slot ->
            val kind = pattern[slot % pattern.size]
            // Search is a weak, bounded history substitute for a new user, not five title queries.
            val next = take(kind) ?: if (kind == Kind.History) take(Kind.Search) ?: take(Kind.Discovery) else take(Kind.Discovery)
            if (next != null) result += next
        }
        return result
    }
}
