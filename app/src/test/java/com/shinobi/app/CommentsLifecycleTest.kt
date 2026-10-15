package com.shinobi.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommentsLifecycleTest {
    private fun page(total: Int) = CommentsPage(emptyList(), total, false, null)

    @Test fun openingShortDoesNotFetchUntilPanelIsOpenedAndReopeningIsCached() = runTest {
        val calls = mutableListOf<String>()
        val state = CommentsState(this) { id -> calls += id; page(42) }
        state.clearFor("short-a")
        advanceUntilIdle()
        assertTrue(calls.isEmpty())
        state.ensureLoaded("short-a"); state.ensureLoaded("short-a")
        advanceUntilIdle()
        assertEquals(listOf("short-a"), calls)
        assertEquals(42, state.total)
        state.ensureLoaded("short-a"); advanceUntilIdle()
        assertEquals(1, calls.size)
        state.clearFor("short-b"); advanceUntilIdle()
        assertEquals(1, calls.size)
        state.ensureLoaded("short-b"); advanceUntilIdle()
        assertEquals(listOf("short-a", "short-b"), calls)
    }

    @Test fun cancelledOldRequestCannotReplaceNewShortOrStopItsSpinner() = runTest {
        val state = CommentsState(this) { id ->
            withContext(NonCancellable) { delay(if (id == "old") 100 else 1000) }
            page(if (id == "old") 1 else 2)
        }
        state.load("old"); runCurrent()
        state.clearFor("new"); state.ensureLoaded("new"); runCurrent()
        advanceTimeBy(200); runCurrent()
        assertTrue(state.loading)
        assertEquals(-1, state.total)
        advanceUntilIdle()
        assertEquals(2, state.total)
        assertFalse(state.loading)
        assertNull(state.error)
    }
}
