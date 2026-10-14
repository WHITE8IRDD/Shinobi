package com.shinobi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationsTest {
    private fun video(
        id: String,
        title: String,
        channel: String = "Test Channel",
        durationSec: Long = 600,
        isShort: Boolean = false,
    ) = VideoItem(
        id = id,
        title = title,
        channel = channel,
        channelUrl = "https://www.youtube.com/channel/UC_$channel",
        thumbnail = "https://i.ytimg.com/vi/$id/hq720.jpg",
        avatar = null,
        durationSec = durationSec,
        views = 50_000,
        uploaded = "2 days ago",
        isShort = isShort,
    )

    @Test fun emptyHistoryProducesNoSeeds() {
        val seeds = TasteEngine.buildTasteSeeds(
            watched = emptyList(),
            searches = emptyList(),
            followed = emptyList(),
        )
        assertTrue(seeds.latestVideoIds.isEmpty())
        assertTrue(seeds.searchQueries.isEmpty())
        assertTrue(seeds.topicPills.isEmpty())
    }

    @Test fun latestWatchedVideosDriveTasteQueriesAndTopics() {
        val watched = listOf(
            video("vid00000001", "Rust Async Programming Deep Dive", channel = "No Boilerplate"),
            video("vid00000002", "Building a Compiler in Rust", channel = "Jon Gjengset"),
            video("vid00000003", "Linux Kernel Memory Management", channel = "Kernel Dev"),
        )
        val seeds = TasteEngine.buildTasteSeeds(
            watched = watched,
            searches = listOf("systems programming"),
            followed = emptyList(),
        )
        assertEquals(listOf("vid00000001", "vid00000002", "vid00000003"), seeds.latestVideoIds)
        assertTrue("expected systems programming in queries: ${seeds.searchQueries}",
            seeds.searchQueries.any { it.contains("systems programming", ignoreCase = true) })
        assertTrue("expected Rust in topic pills: ${seeds.topicPills}",
            seeds.topicPills.any { it.equals("Rust", ignoreCase = true) } ||
                seeds.topicPills.any { it.contains("Boilerplate", ignoreCase = true) })
    }

    @Test fun interleaveFiltersWatchedAndDislikedAndBalancesSources() {
        val r1 = video("rel00000001", "Related 1")
        val r2 = video("rel00000002", "Related 2")
        val rWatched = video("wat00000001", "Already Watched")
        val t1 = video("top00000001", "Topic 1")
        val tDisliked = video("dis00000001", "Disliked Video")
        val c1 = video("chn00000001", "Channel Upload 1")

        val merged = TasteEngine.interleaveRecommendations(
            relatedBuckets = listOf(listOf(rWatched, r1, r2)),
            topicBuckets = listOf(listOf(tDisliked, t1)),
            channelBuckets = listOf(listOf(c1)),
            excludeIds = setOf("wat00000001"),
            dislikedIds = setOf("dis00000001"),
        )

        val ids = merged.map { it.id }
        assertFalse("watched video excluded", "wat00000001" in ids)
        assertFalse("disliked video excluded", "dis00000001" in ids)
        assertEquals(listOf("rel00000001", "rel00000002", "top00000001", "chn00000001"), ids)
    }

    @Test fun separateShortsAndVideosSplitsByFlagAndDuration() {
        val normal = video("norm0000001", "Full Documentary", durationSec = 1800, isShort = false)
        val flaggedShort = video("shrt0000001", "Quick Tip", durationSec = 45, isShort = true)
        val verticalThumbShort = video("shrt0000002", "Short Clip #shorts", durationSec = 30, isShort = false)

        val (videos, shorts) = TasteEngine.separateShortsAndVideos(listOf(normal, flaggedShort, verticalThumbShort))
        assertEquals(listOf("norm0000001"), videos.map { it.id })
        assertEquals(listOf("shrt0000001", "shrt0000002"), shorts.map { it.id })
        assertTrue(shorts.all { it.isShort })
    }
}
