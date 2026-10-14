package com.shinobi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The skip planner is plain arithmetic on returned segments, so it is tested on the JVM:
 * abutting sponsor reads must merge into one jump, and a segment must only ever be skipped
 * once per viewing (that is what makes Undo stick).
 */
class SponsorBlockSkipTest {
    private fun segment(uuid: String, category: SponsorBlock.Category, startMs: Long, endMs: Long) =
        SponsorBlock.Segment(uuid, category, startMs, endMs)

    private val sponsor = SponsorBlock.Category.Sponsor
    private val selfPromo = SponsorBlock.Category.SelfPromo

    /**
     * Sponsor reads are routinely submitted as several abutting segments. Every segment here
     * is a sponsor read, which is the one category that auto-skips by default.
     */
    @Test
    fun abuttingAutoSegmentsSkipAsOneRun() {
        val segments = listOf(
            segment("a", sponsor, 10_000, 20_000),
            segment("b", sponsor, 20_100, 25_000),
            segment("c", sponsor, 25_400, 30_000),
        )
        assertEquals(30_000, SponsorBlock.skipSpanEnd(segments, segments.first()))
        assertEquals(segments, SponsorBlock.segmentsWithin(segments, segments.first(), 30_000))
    }

    /** A real cut in the video is not swallowed: only a short gap reads as the same run. */
    @Test
    fun aRealGapEndsTheRun() {
        val segments = listOf(
            segment("a", sponsor, 0, 10_000),
            segment("b", sponsor, 11_000, 20_000),
        )
        assertEquals(10_000, SponsorBlock.skipSpanEnd(segments, segments.first()))
        assertEquals(listOf(segments.first()), SponsorBlock.segmentsWithin(segments, segments.first(), 10_000))
    }

    /** A category the user asked to just mark (or to be asked about) is never merged away. */
    @Test
    fun nonAutoNeighboursAreLeftAlone() {
        val intro = SponsorBlock.Category.Intro // default action: Show
        val segments = listOf(
            segment("a", sponsor, 0, 10_000),
            segment("b", intro, 10_200, 14_000),
            segment("c", sponsor, 14_200, 20_000),
        )
        assertEquals(10_000, SponsorBlock.skipSpanEnd(segments, segments.first()))
    }

    /** The end of a merge is the latest end it reaches, whatever order segments arrive in. */
    @Test
    fun overlappingSegmentsMerge() {
        val segments = listOf(
            segment("inner", selfPromo, 12_000, 13_000),
            segment("outer", sponsor, 10_000, 20_000),
        )
        assertEquals(20_000, SponsorBlock.skipSpanEnd(segments, segments.last()))
        assertEquals(20_000, SponsorBlock.skipSpanEnd(segments, segments.first()))
    }

    /** A segment is spent once it has been skipped, and Undo must not clear that. */
    @Test
    fun spentSegmentsOnlySkipOnce() {
        val one = segment("one", sponsor, 0, 10_000)
        val two = segment("two", sponsor, 20_000, 30_000)
        SponsorBlock.resetSpent()
        assertFalse(SponsorBlock.isSpent(one))
        SponsorBlock.markSpent(one)
        assertTrue(SponsorBlock.isSpent(one))
        assertFalse(SponsorBlock.isSpent(two))
        // A new video is a new viewing.
        SponsorBlock.resetSpent()
        assertFalse(SponsorBlock.isSpent(one))
    }

    /** The "already skipped past its tail" margin keeps a skip from firing at the very end. */
    @Test
    fun containsIgnoresTheLastHalfSecond() {
        val s = segment("a", sponsor, 10_000, 20_000)
        assertTrue(s.contains(10_000))
        assertTrue(s.contains(19_499))
        assertFalse(s.contains(19_500))
        assertFalse(s.contains(20_000))
        assertFalse(s.contains(9_999))
    }
}
