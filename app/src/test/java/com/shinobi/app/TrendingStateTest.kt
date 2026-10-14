package com.shinobi.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrendingStateTest {
    private fun page(id: String) = FeedPage(listOf(VideoItem(id, id, "Creator", null, null, null, 600, 0, null)), null)

    @Test fun switchingCountryNeverPublishesOldChartEvenIfLoaderIgnoresCancellation() = runTest {
        val state = TrendingState(this) { country ->
            withContext(NonCancellable) { delay(if (country == "US") 1000 else 10); page(country) }
        }
        state.load("US"); advanceTimeBy(100)
        state.load("MA"); advanceUntilIdle()
        assertEquals("MA", state.videos.single().id)
        assertFalse(state.loading)
    }

    @Test fun cacheIsCountrySpecificAndRefreshBypassesIt() = runTest {
        val calls = mutableListOf<String>()
        val state = TrendingState(this) { country -> calls += country; page(country) }
        state.load("MA"); advanceUntilIdle()
        state.load("US"); advanceUntilIdle()
        state.load("MA"); advanceUntilIdle()
        assertEquals(listOf("MA", "US"), calls)
        assertEquals("MA", state.videos.single().id)
        state.load("MA", refresh = true); advanceUntilIdle()
        assertEquals(listOf("MA", "US", "MA"), calls)
    }

    @Test fun unavailableCountryClearsPreviousChartAndShowsError() = runTest {
        val state = TrendingState(this) { country -> if (country == "US") page(country) else FeedPage(emptyList(), null) }
        state.load("US"); advanceUntilIdle()
        state.load("MA"); advanceUntilIdle()
        assertTrue(state.videos.isEmpty())
        assertNotNull(state.error)
        assertFalse(state.loading)
    }
}
