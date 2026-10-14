package com.shinobi.app

import org.junit.Assert.*
import org.junit.Test

class RecommendationMixerTest {
    private fun videos(prefix: String, topic: String, count: Int = 50) = (1..count).map {
        VideoItem("$prefix$it", "$topic $it", "$prefix creator $it", null, null, null, 600, 100, null)
    }
    private fun source(prefix: String, kind: RecommendationMixer.Kind, topic: String = prefix) =
        RecommendationMixer.Source(prefix, kind, videos(prefix, topic))

    @Test fun oneLikeCannotTakeOverHome() {
        val result = RecommendationMixer.mix(listOf(
            source("d", RecommendationMixer.Kind.Discovery),
            source("trump", RecommendationMixer.Kind.Like, "Trump election"),
        ), emptySet())
        assertEquals(30, result.size)
        assertEquals(6, result.count { it.id.startsWith("trump") })
        assertEquals(24, result.count { it.id.startsWith("d") })
    }

    @Test fun historyLikesAndDiscoveryHaveIndependentBudgets() {
        val result = RecommendationMixer.mix(listOf(
            source("d", RecommendationMixer.Kind.Discovery),
            source("h1", RecommendationMixer.Kind.History),
            source("h2", RecommendationMixer.Kind.History),
            source("h3", RecommendationMixer.Kind.History),
            source("l", RecommendationMixer.Kind.Like),
        ), emptySet())
        assertEquals(12, result.count { it.id.startsWith("d") })
        assertEquals(12, result.count { it.id.startsWith("h") })
        assertEquals(6, result.count { it.id.startsWith("l") })
        for (seed in listOf("h1", "h2", "h3")) assertTrue(result.any { it.id.startsWith(seed) })
    }

    @Test fun failedDiscoveryDoesNotBackfillEntirePageFromOneSeed() {
        assertEquals(6, RecommendationMixer.mix(listOf(source("l", RecommendationMixer.Kind.Like)), emptySet()).size)
    }

    @Test fun duplicatesWatchedAndDislikedNeverReenterEvenWhenAllCandidatesExcluded() {
        val data = videos("a", "topic", 10)
        val sources = listOf(RecommendationMixer.Source("1", RecommendationMixer.Kind.Discovery, data + data),
            RecommendationMixer.Source("2", RecommendationMixer.Kind.Like, data))
        val result = RecommendationMixer.mix(sources, setOf("a1", "a2"))
        assertEquals(result.size, result.distinctBy { it.id }.size)
        assertFalse(result.any { it.id in setOf("a1", "a2") })
        assertTrue(RecommendationMixer.mix(sources, data.map { it.id }.toSet()).isEmpty())
        assertEquals(result, RecommendationMixer.mix(sources, setOf("a1", "a2")))
    }

    @Test fun olderInterestsSurviveRecentTopicBinge() {
        val politics = videos("p", "Trump election campaign", 30).map { it.copy(channel = "News") }
        val tech = videos("t", "Kotlin programming tutorial", 1)
        val cooking = videos("c", "Cooking pasta recipe", 1)
        val seeds = RecommendationMixer.diverseSeeds(politics + tech + cooking, 3)
        assertEquals(listOf("p1", "t1", "c1"), seeds.map { it.id })
    }

    @Test fun zeroAndEmptyLimitsAreSafe() {
        assertTrue(RecommendationMixer.mix(emptyList(), emptySet()).isEmpty())
        assertTrue(RecommendationMixer.mix(listOf(source("d", RecommendationMixer.Kind.Discovery)), emptySet(), 0).isEmpty())
        assertTrue(RecommendationMixer.diverseSeeds(emptyList(), 3).isEmpty())
    }
}
