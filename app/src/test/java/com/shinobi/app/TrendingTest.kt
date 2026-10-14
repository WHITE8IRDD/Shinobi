package com.shinobi.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrendingTest {
    @Test fun emptyCountryChartDoesNotInventSearchResults() {
        assertTrue(InnerTube.parseTrending(JSONObject("""{"contents":{}}""")).videos.isEmpty())
    }
    @Test fun selectedChartOnlyAndNoContinuationLeakage() {
        val video = """{"videoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Chart video"},"ownerText":{"runs":[{"text":"Creator"}]}}}"""
        val other = video.replace("abcdefghijk", "otherabcdef")
        val root = JSONObject("""{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[
          {"tabRenderer":{"selected":false,"content":$other}},
          {"tabRenderer":{"selected":true,"content":{"items":[$video],"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"stale"}}}}}}
        ]}}}""")
        val page = InnerTube.parseTrending(root)
        assertEquals(listOf("abcdefghijk"), page.videos.map { it.id })
        assertNull(page.next)
    }
    @Test fun mobileClientSelectedChartIsParsed() {
        val root = JSONObject("""{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[
          {"tabRenderer":{"selected":true,"content":{"videoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Regional chart"}}}}}
        ]}}}""")
        assertEquals("abcdefghijk", InnerTube.parseTrending(root).videos.single().id)
    }

}
