package com.shinobi.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShortsFeedLifecycleTest {
    private fun item(id: String) = VideoItem(id, id, "Creator", null, null, null, 30, 0, null, isShort = true)
    private fun page(id: String, next: String? = null) = SearchPage(emptyList(), listOf(item(id)), next)

    @Test fun lateInitialPageCannotReplaceAnExplicitlyOpenedShort() = runTest {
        val state = ShortsFeedState(this, search = { _, _ ->
            withContext(NonCancellable) { delay(100) }; page("stale")
        }, fallbackSearch = { emptyList() }, prefetch = {}, dispatcher = StandardTestDispatcher(testScheduler))
        state.ensureLoaded(); runCurrent()
        state.openWith(item("chosen"), (0..10).map { item("shelf-$it") })
        advanceUntilIdle()
        assertEquals("chosen", state.shorts.first().id)
        assertFalse(state.shorts.any { it.id == "stale" })
        assertFalse(state.loading)
        assertNull(state.error)
    }

    @Test fun refreshCancelsOldPaginationIncludingNonCooperativeLoader() = runTest {
        var refresh = false
        val state = ShortsFeedState(this, search = { _, token ->
            if (token != null) { withContext(NonCancellable) { delay(1000) }; page("stale-more") }
            else page(if (refresh) "fresh" else "first", "next")
        }, fallbackSearch = { emptyList() }, prefetch = {}, dispatcher = StandardTestDispatcher(testScheduler))
        state.ensureLoaded(); advanceUntilIdle()
        state.loadMore(); runCurrent()
        refresh = true
        state.refresh(); advanceUntilIdle()
        assertEquals(listOf("fresh"), state.shorts.map { it.id })
        assertFalse(state.loading)
        assertNull(state.error)
    }

    @Test fun shelfIsDeduplicatedAndRetentionIsBoundedWithoutMovingFirstItem() = runTest {
        val state = ShortsFeedState(this, search = { _, _ -> page("unused") }, fallbackSearch = { emptyList() }, prefetch = {}, dispatcher = StandardTestDispatcher(testScheduler))
        val selected = item("chosen")
        state.openWith(selected, listOf(selected, selected) + (0..499).map { item("shelf-$it") } + item("shelf-1"))
        assertEquals("chosen", state.shorts.first().id)
        assertEquals(300, state.shorts.size)
        assertEquals(state.shorts.size, state.shorts.distinctBy { it.id }.size)
        assertFalse(state.hasMore)
    }
}
