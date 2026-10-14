package com.shinobi.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchSuggestionsTest {
    @Test fun parsesEnglishAndArabicAndIgnoresMalformedItems() {
        assertEquals(listOf("طبخ عربي", "kotlin tutorial"), SuggestionApi.parse("""["k",["طبخ عربي","kotlin tutorial",42,"","kotlin tutorial"],{}]"""))
        assertTrue(SuggestionApi.parse("[]").isEmpty())
    }

    @Test fun typingIsDebouncedAndOnlyLatestQueryLoads() = runTest {
        val requests = mutableListOf<String>()
        val state = SearchSuggestions(this, { q, _ -> requests += q; listOf("$q tutorial") }, { emptyList() })
        state.update("k", "en"); advanceTimeBy(100)
        state.update("ko", "en"); advanceTimeBy(100)
        state.update("kot", "en"); advanceUntilIdle()
        assertEquals(listOf("kot"), requests)
        assertEquals(listOf("kot tutorial"), state.items)
    }

    @Test fun olderInFlightResponsesCannotReplaceNewerQuery() = runTest {
        val state = SearchSuggestions(this, { q, _ ->
            withContext(NonCancellable) { delay(if (q == "old") 1000 else 10); listOf(q) }
        }, { emptyList() })
        state.update("old", "en"); advanceTimeBy(300)
        state.update("new", "en"); advanceUntilIdle()
        assertEquals(listOf("new"), state.items)
        state.dismiss(); advanceUntilIdle()
        assertFalse(state.visible)
        assertTrue(state.items.isEmpty())
    }

    @Test fun offlineLocalHistoryBlankAndCacheAreHandled() = runTest {
        var calls = 0
        val state = SearchSuggestions(this, { q, _ -> calls++; if (q == "offline") error("offline"); listOf(q) }, { listOf("offline video") })
        state.update("offline", "en"); advanceUntilIdle()
        assertEquals(listOf("offline video"), state.items)
        state.update("cached", "en"); advanceUntilIdle()
        state.update("cached", "en"); advanceUntilIdle()
        assertEquals(2, calls)
        state.update("cached", "ar"); advanceUntilIdle()
        assertEquals(3, calls) // Language is part of the cache key.
        state.update("  ", "en"); advanceUntilIdle()
        assertTrue(state.items.isEmpty()); assertFalse(state.visible)
    }
}
