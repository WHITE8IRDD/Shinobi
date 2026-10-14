package com.shinobi.app

import org.junit.Assert.*
import org.junit.Test

class PolishTest {
    @Test fun landscapeFitDoesNotStretchWideOrPortraitVideo() {
        assertEquals(PlayerGeometry.Size(1920, 1080), PlayerGeometry.size(2400, 1080, 16f / 9))
        assertEquals(PlayerGeometry.Size(608, 1080), PlayerGeometry.size(2400, 1080, 9f / 16))
        assertEquals(PlayerGeometry.Size(2400, 1350), PlayerGeometry.size(2400, 1080, 16f / 9, true))
    }
    @Test fun fitHandlesUnknownAspectWithoutCrashing() {
        assertEquals(PlayerGeometry.size(1920, 1080, 16f / 9), PlayerGeometry.size(1920, 1080, Float.NaN))
        assertTrue(PlayerGeometry.size(0, 0, 0f).width > 0)
    }
    private fun short(id: String, channel: String = id, isShort: Boolean = true) =
        VideoItem(id, "Video $id", channel, null, null, null, 30, 100, null, isShort)
    @Test fun shortsAreInterleavedDeduplicatedAndDislikesExcluded() {
        val mixed = ShortsMixer.mix(listOf(listOf(short("a"), short("c")), listOf(short("b"), short("a"), short("d"))), setOf("d"))
        assertEquals(listOf("a", "b", "c"), mixed.map { it.id })
    }
    @Test fun shortDurationDoesNotTurnHorizontalVideoIntoShort() {
        assertTrue(ShortsMixer.mix(listOf(listOf(short("a", isShort = false))), emptySet()).isEmpty())
    }
    @Test fun oneCreatorCannotFloodShortsPage() {
        val mixed = ShortsMixer.mix(listOf((0..12).map { short("$it", "Same creator") }), emptySet())
        assertEquals(2, mixed.size)
    }
    @Test fun filterRequiresAgreementAndRejectsInvalidScores() {
        assertFalse(SensitivePolicy.shouldCover(0.999f, 0.7f))
        assertFalse(SensitivePolicy.shouldCover(Float.NaN, 1f))
        assertFalse(SensitivePolicy.shouldCover(1.01f, 1f))
        assertTrue(SensitivePolicy.shouldCover(0.999f, 0.999f))
    }
    @Test fun storyboardPicksCorrectSheetTileAndClampsEnd() {
        val board = VideoStoryboard(listOf("https://example.com/0", "https://example.com/1"), 160, 90, 6, 1000, 2, 2)
        assertEquals(VideoStoryboard.Tile("https://example.com/0", 160, 90), board.tileAt(3_500))
        assertEquals(VideoStoryboard.Tile("https://example.com/1", 160, 0), board.tileAt(Long.MAX_VALUE))
        assertEquals(VideoStoryboard.Tile("https://example.com/0", 0, 0), board.tileAt(-1))
        assertNull(board.copy(intervalMs = 0).tileAt(1))
        assertNull(board.copy(columns = Int.MAX_VALUE).tileAt(1))
    }
    @Test fun malformedSponsorRecordsCannotSeek() {
        val body = """[{"videoID":"v","segments":[
          {"UUID":"valid","category":"sponsor","segment":[1,1.5],"actionType":"skip"},
          {"UUID":"negative","category":"sponsor","segment":[-2,10]},
          {"UUID":"reverse","category":"sponsor","segment":[20,10]},
          {"UUID":"missing","category":"sponsor","segment":[]},
          {"UUID":"mute","category":"sponsor","segment":[5,9],"actionType":"mute"},
          {"UUID":"","category":"sponsor","segment":[5,9]}
        ]}]"""
        assertEquals(listOf("valid"), SponsorBlock.parse("v", body).map { it.uuid })
        assertTrue(SponsorBlock.parse("different", body).isEmpty())
    }
}
