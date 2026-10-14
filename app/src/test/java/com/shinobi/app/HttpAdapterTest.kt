package com.shinobi.app

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpAdapterTest {
    @Test fun statusAndRetryAfterSurviveHttpAdapter() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())
            try { call.awaitText(); fail("must throw on HTTP error") }
            catch (error: HttpFailure) {
                assertEquals(429, error.status)
                assertEquals(2000L, error.retryAfterMs)
            }
        }
    }

    @Test fun cancellingReadClosesUnderlyingCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("late response").setBodyDelay(2, TimeUnit.SECONDS))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())
            val job = launch(Dispatchers.Default) { call.awaitText() }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertTrue(call.isCanceled())
        }
    }
}
