package com.shinobi.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadTransportTest {
    private fun body(request: Request): JSONObject {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }
    private val page = """{"contents":{"items":[{"videoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Test"}}},{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next-page"}}}}]}}"""

    @Test fun serviceFailureSwitchesClientAndPinsContinuation() = runTest {
        val requests = mutableListOf<Request>()
        val result = InnerTube.readPost("browse", JSONObject().put("browseId", "FEwhat_to_watch"), anonymous = true, country = "EG", transport = {
            requests += it
            if (requests.size == 1) throw HttpFailure(503)
            page
        })
        assertEquals(listOf("1", "2"), requests.map { it.header("X-Youtube-Client-Name") })
        assertEquals("MWEB", body(requests.last()).getJSONObject("context").getJSONObject("client").getString("clientName"))
        assertEquals("EG", body(requests.last()).getJSONObject("context").getJSONObject("client").getString("gl"))
        assertNull(requests.last().header("Cookie"))
        assertNull(requests.last().header("Authorization"))
        val token = InnerTube.parseFeed(result).next!!
        assertEquals("shinobi:mweb:next-page", token)
        InnerTube.readPost("browse", JSONObject().put("continuation", token), anonymous = true, transport = {
            assertEquals("2", it.header("X-Youtube-Client-Name"))
            assertEquals("next-page", body(it).getString("continuation"))
            page
        })
    }

    @Test fun malformedResponseAndApiErrorEnvelopeAreRetried() = runTest {
        var calls = 0
        val result = InnerTube.readPost("search", JSONObject().put("query", "test"), anonymous = true, transport = {
            calls++
            when (calls) { 1 -> "<html>consent</html>"; 2 -> """{"error":{"code":503}}"""; else -> page }
        })
        assertEquals(3, calls)
        assertEquals(1, InnerTube.parseFeed(result).videos.size)
    }

    @Test fun privateReadsDoNotBecomeAnonymousPublicFeeds() = runTest {
        var calls = 0
        try {
            InnerTube.readPost("browse", JSONObject().put("browseId", "FEsubscriptions"), anonymous = false, publicRead = false, transport = { calls++; page })
            fail("signed-out private read must fail")
        } catch (_: NotSignedInException) {}
        assertEquals(0, calls)
    }

    @Test fun webContinuationDoesNotSwitchToIncompatibleClient() = runTest {
        var calls = 0
        InnerTube.readPost("search", JSONObject().put("continuation", "web-token"), anonymous = true, transport = {
            calls++
            assertEquals("1", it.header("X-Youtube-Client-Name"))
            if (calls < 3) throw HttpFailure(500)
            page
        })
        assertEquals(3, calls)
    }
}
