package com.shinobi.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeStateTest {
    private fun video(id: String) = VideoItem(id, id, "Creator", null, null, null, 600, 0, null)

    @Test fun cancelledRefreshCannotPublishOldTopic() = runTest {
        val state = PersonalizedHomeState(this) { request ->
            withContext(NonCancellable) {
                delay(if (request.key == "old") 1000 else 10)
                SearchPage(listOf(video(request.key)), emptyList(), null)
            }
        }
        state.selectTopic("old")
        // Home fetches and ranking run on real IO/Default dispatchers; allow them to start.
        withContext(Dispatchers.Default) { delay(30) }
        state.selectTopic("new")
        advanceUntilIdle()
        // Join children rather than relying on a real-time polling loop.
        coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertEquals(listOf("new"), state.videos.map { it.id })
        assertFalse(state.loading)
    }

    @Test fun repeatedTokenAndDuplicatePagesStopPagination() = runTest {
        var calls = 0
        val state = PersonalizedHomeState(this) { request ->
            calls++
            SearchPage(listOf(video("one")), emptyList(), if (request.next == null) "next" else request.next)
        }
        state.selectTopic("topic")
        coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertTrue(state.hasMore)
        state.loadMore()
        coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertFalse(state.hasMore)
        assertEquals(listOf("one"), state.videos.map { it.id })
        state.loadMore()
        assertEquals(2, calls)
    }

    @Test fun failureClearsLoadingAndRefreshCanRecover() = runTest {
        var fail = true
        val state = PersonalizedHomeState(this) {
            if (fail) throw java.io.IOException("offline")
            SearchPage(listOf(video("recovered")), emptyList(), null)
        }
        state.selectTopic("topic")
        coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertNotNull(state.error)
        assertFalse(state.loading)
        fail = false
        state.refresh()
        coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertNull(state.error)
        assertEquals("recovered", state.videos.single().id)
    }
    @Test fun emptyContinuationEndsNormallyInsteadOfShowingServiceError() = runTest {
        val state = PersonalizedHomeState(this) { request ->
            if (request.next == null) SearchPage(listOf(video("one")), emptyList(), "next")
            else SearchPage(emptyList(), emptyList(), null)
        }
        state.selectTopic("topic")
        coroutineContext[Job]!!.children.toList().forEach { it.join() }
        state.loadMore()
        coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertNull(state.error)
        assertFalse(state.hasMore)
        assertEquals(listOf("one"), state.videos.map { it.id })
    }

    @Test fun failedRefreshKeepsVisibleCardsAndPreservesServiceError() = runTest {
        var fail = false
        val state = PersonalizedHomeState(this) {
            if (fail) throw HttpFailure(429)
            SearchPage(listOf(video("one")), emptyList(), null)
        }
        state.selectTopic("topic")
        coroutineContext[Job]!!.children.toList().forEach { it.join() }
        fail = true
        state.refresh()
        coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertEquals(listOf("one"), state.videos.map { it.id })
        assertTrue(state.error.orEmpty().contains("rate-limiting"))
        assertFalse(state.loading)
        assertFalse(state.refreshing)
    }

    @Test fun freshLoadPublishesFastSourceBeforeSlowSourceFinishes() = runTest {
        // Empty history: refresh() loads exactly the discovery + shorts sources.
        val state = PersonalizedHomeState(this) { request ->
            if (request.key == "discovery") {
                withContext(Dispatchers.IO) { delay(5_000) }
                SearchPage(listOf(video("slow")), emptyList(), null)
            } else {
                SearchPage(emptyList(), listOf(video("fast")), null)
            }
        }
        state.refresh()
        // Real dispatchers back the IO fetches; poll briefly for the fast arrival.
        repeat(30) {
            if (state.shorts.isNotEmpty()) return@repeat
            withContext(Dispatchers.Default) { delay(100) }
        }
        assertEquals(listOf("fast"), state.shorts.map { it.id })
        assertTrue(state.loading)
        coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertEquals(listOf("slow"), state.videos.map { it.id })
        assertEquals(listOf("fast"), state.shorts.map { it.id })
        assertFalse(state.loading)
    }

}
