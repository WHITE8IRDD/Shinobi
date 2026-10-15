package com.shinobi.app

import org.junit.Assert.*
import org.junit.Test

class SeekStoryboardTest {
    private val board = SeekStoryboard(listOf("https://example.com/0.jpg", "https://example.com/1.jpg"),
        width = 160, height = 90, count = 8, intervalMs = 1000, columns = 3, rows = 2)

    @Test fun mapsNonSquareGridAndChangesSheets() {
        assertEquals(SeekStoryboard.Frame(board.urls[0], 320, 0), board.frameAt(2000))
        assertEquals(SeekStoryboard.Frame(board.urls[0], 0, 90), board.frameAt(3000))
        assertEquals(SeekStoryboard.Frame(board.urls[1], 0, 0), board.frameAt(6000))
    }

    @Test fun clampsNegativeAndEndOfVideoWithoutRequestingMissingSheet() {
        assertEquals(board.frameAt(0), board.frameAt(-1))
        assertEquals(SeekStoryboard.Frame(board.urls[1], 160, 0), board.frameAt(Long.MAX_VALUE))
        assertEquals(board.frameAt(5000), board.copy(urls = board.urls.take(1)).frameAt(9000))
    }

    @Test fun invalidOrUnavailableBoardsFallBackSafely() {
        assertNull(board.copy(width = Int.MAX_VALUE).frameAt(0))
        assertNull(board.copy(rows = Int.MAX_VALUE).frameAt(0))
        assertNull(board.copy(intervalMs = 0).frameAt(0))
        assertNull(board.copy(columns = 0).frameAt(0))
        assertNull(board.copy(urls = emptyList()).frameAt(0))
        assertNull(board.copy(count = 0).frameAt(0))
        assertNull(board.copy(urls = listOf("http://example.com/0.jpg")).frameAt(0))
    }
}
