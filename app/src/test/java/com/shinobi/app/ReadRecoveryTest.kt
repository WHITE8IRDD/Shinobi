package com.shinobi.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

@OptIn(ExperimentalCoroutinesApi::class)
class ReadRecoveryTest {
    @Test fun failuresDoNotInventAnOfflineDiagnosis() {
        for (state in listOf(ConnectionState.Online, ConnectionState.Unknown, ConnectionState.Unvalidated)) {
            for (error in listOf(IOException(), UnknownHostException(), HttpFailure(503))) {
                assertEquals("YouTube is temporarily unavailable. Please try again.", ReadRecovery.message(error, state))
            }
        }
        assertTrue(ReadRecovery.message(IOException(), ConnectionState.Offline).startsWith("No internet connection."))
        assertTrue(ReadRecovery.message(IOException(), ConnectionState.CaptivePortal).contains("requires sign-in"))
        assertTrue(ReadRecovery.message(HttpFailure(429), ConnectionState.Online).contains("rate-limiting"))
        assertTrue(ReadRecovery.message(java.net.SocketTimeoutException(), ConnectionState.Online).contains("too long"))
    }

    @Test fun exponentialBackoffThenSuccess() = runTest {
        val times = mutableListOf<Long>()
        val result = ReadRecovery.run(jitter = { 0 }) { attempt ->
            times += testScheduler.currentTime
            if (attempt < 2) throw HttpFailure(503)
            "loaded"
        }
        assertEquals("loaded", result)
        assertEquals(listOf(0L, 500L, 1500L), times)
    }

    @Test fun boundedRetryAfterAndPermanentFailures() = runTest {
        var calls = 0
        try { ReadRecovery.run(jitter = { 0 }) { calls++; throw HttpFailure(429, 90_000) } }
        catch (_: HttpFailure) {}
        assertEquals(3, calls)
        assertEquals(10_000L, testScheduler.currentTime)
        calls = 0
        try { ReadRecovery.run { calls++; throw HttpFailure(404) } } catch (_: HttpFailure) {}
        assertEquals(1, calls)
        assertFalse(ReadRecovery.retryable(NotSignedInException()))
    }

    @Test fun cancellationDuringBackoffStopsAllRequests() = runTest {
        var calls = 0
        val job = launch { ReadRecovery.run { calls++; throw IOException() } }
        runCurrent()
        job.cancelAndJoin()
        advanceUntilIdle()
        assertEquals(1, calls)
    }
}
