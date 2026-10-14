package com.shinobi.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrendingStateTest {
    private fun chart(id: String) = listOf(VideoItem(id, id, "Creator", null, null, null, 600, 0, null))

    @Test fun switchingCountryNeverPublishesOldChartEvenIfLoaderIgnoresCancellation() = runTest {
        val state = TrendingState(this) { country, _ ->
            withContext(NonCancellable) { delay(if (country == "US") 1000 else 10); chart(country) }
        }
        state.load("US"); advanceTimeBy(100)
        state.load("MA"); advanceUntilIdle()
        assertEquals("MA", state.videos.single().id)
        assertFalse(state.loading)
    }

    @Test fun cacheIsCountryAndChartSpecificAndRefreshBypassesIt() = runTest {
        val calls = mutableListOf<String>()
        val state = TrendingState(this) { country, category -> calls += "$country/${category.name}"; chart(country) }
        state.load("MA"); advanceUntilIdle()
        state.load("US"); advanceUntilIdle()
        state.load("MA"); advanceUntilIdle()
        assertEquals(listOf("MA/Gaming", "US/Music"), calls)
        assertEquals("MA", state.videos.single().id)
        state.load("MA", refresh = true); advanceUntilIdle()
        assertEquals(listOf("MA/Gaming", "US/Music", "MA/Gaming"), calls)
        state.select("MA", TrendingCategory.Podcasts); advanceUntilIdle()
        assertEquals("MA/Podcasts", calls.last())
    }

    @Test fun unavailableCountryClearsPreviousChartAndShowsError() = runTest {
        val state = TrendingState(this) { country, _ -> if (country == "US") chart(country) else emptyList() }
        state.load("US"); advanceUntilIdle()
        state.load("MA"); advanceUntilIdle()
        assertTrue(state.videos.isEmpty())
        assertNotNull(state.error)
        assertFalse(state.loading)
    }

    @Test fun chartsOnlyCategoriesFallBackWhereYouTubeChartsHasNoCountry() = runTest {
        val state = TrendingState(this) { country, category -> chart("$country/${category.name}") }
        // Music is the default where YouTube Charts covers the country, Gaming elsewhere.
        assertEquals(TrendingCategory.Music, state.categoryFor("US"))
        assertEquals(TrendingCategory.Gaming, state.categoryFor("MA"))
        assertEquals(listOf(TrendingCategory.Gaming, TrendingCategory.Podcasts), TrendingCategory.availableIn("MA"))
        assertEquals(TrendingCategory.entries, TrendingCategory.availableIn("FR"))
        // A pick the country can't have is ignored; one it can is kept across countries that have it.
        state.select("MA", TrendingCategory.Movies); advanceUntilIdle()
        assertTrue(state.videos.isEmpty())
        state.select("US", TrendingCategory.Movies); advanceUntilIdle()
        assertEquals("US/Movies", state.videos.single().id)
        assertEquals(TrendingCategory.Gaming, state.categoryFor("MA"))
        assertEquals(TrendingCategory.Movies, state.categoryFor("GB"))
    }
}
