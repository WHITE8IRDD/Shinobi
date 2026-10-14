package com.shinobi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionParserTest {
    @Test
    fun parsesTranscriptSecondsAndCleansMarkup() {
        val cues = CaptionParser.parse(
            """<transcript><text start="0.25" dur="1.50">Hello &amp; <i>world</i>&#33;</text></transcript>""",
        )

        assertEquals(listOf(CaptionCue(250, 1_750, "Hello & world!")), cues)
    }

    @Test
    fun parsesTtmlMillisecondAttributesAndClockTimes() {
        val cues = CaptionParser.parse(
            """<tt><body><p t="4000" d="2500">First</p><p begin="00:00:07.000" end="00:00:08.250"><span>Second</span></p></body></tt>""",
        )

        assertEquals(
            listOf(CaptionCue(4_000, 6_500, "First"), CaptionCue(7_000, 8_250, "Second")),
            cues,
        )
    }

    @Test
    fun parsesWebVttIdentifiersSettingsAndMultilineText() {
        val cues = CaptionParser.parse(
            """WEBVTT

intro
00:00:00.500 --> 00:00:02.000 align:start position:0%
<i>Welcome</i> to Shinobi
captions

00:00:03.000 --> 00:00:04.250
See you.
""",
        )

        assertEquals(
            listOf(
                CaptionCue(500, 2_000, "Welcome to Shinobi\ncaptions"),
                CaptionCue(3_000, 4_250, "See you."),
            ),
            cues,
        )
    }

    @Test
    fun ignoresMalformedCuesAndUsesHalfOpenBoundaries() {
        val cues = CaptionParser.parse(
            """<transcript><text start="2" dur="1">later</text><text start="bad" dur="2">skip</text><text start="0" dur="1">first</text></transcript>""",
        )

        assertEquals(listOf(CaptionCue(0, 1_000, "first"), CaptionCue(2_000, 3_000, "later")), cues)
        assertEquals("first", CaptionParser.cueAt(cues, 0)?.text)
        assertNull(CaptionParser.cueAt(cues, 1_000))
        assertEquals("later", CaptionParser.cueAt(cues, 2_500)?.text)
        assertNull(CaptionParser.cueAt(cues, 3_000))
    }
}
