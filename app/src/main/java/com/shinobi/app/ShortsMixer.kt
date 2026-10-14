package com.shinobi.app

/** Interleave sources; retain only actual Shorts and cap any creator at two per batch. */
internal object ShortsMixer {
    fun mix(sources: List<List<VideoItem>>, excluded: Set<String>, recent: List<VideoItem> = emptyList(), limit: Int = 30): List<VideoItem> {
        val seen = excluded.toMutableSet()
        val creators = mutableMapOf<String, Int>()
        val pools = sources.map { it.filter { v -> v.isShort && v.id.isNotBlank() }.toMutableList() }
        val result = mutableListOf<VideoItem>()
        while (result.size < limit) {
            var added = false
            for (pool in pools) {
                pool.removeAll { it.id in seen || (it.channel.isNotBlank() && (creators[it.channel] ?: 0) >= 2) }
                val lastChannel = result.lastOrNull()?.channel ?: recent.lastOrNull()?.channel
                val candidate = pool.firstOrNull { it.channel != lastChannel } ?: pool.firstOrNull() ?: continue
                pool.remove(candidate)
                result += candidate
                seen += candidate.id
                if (candidate.channel.isNotBlank()) creators[candidate.channel] = (creators[candidate.channel] ?: 0) + 1
                added = true
                if (result.size == limit) break
            }
            if (!added) break
        }
        return result
    }
}
