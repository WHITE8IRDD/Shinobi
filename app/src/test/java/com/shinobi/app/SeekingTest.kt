package com.shinobi.app

import org.junit.Assert.*
import org.junit.Test

class SeekingTest {
    @Test fun seekTargetsAreBoundedAndFinite() {
        for (duration in listOf(1L, 60_000L, 3_600_000L, Long.MAX_VALUE)) {
            assertEquals(0L, seekPosition(-100f, 300, duration))
            assertEquals(duration, seekPosition(900f, 300, duration))
            assertEquals(0L, seekPosition(Float.NaN, 300, duration))
            assertEquals(0L, seekPosition(Float.POSITIVE_INFINITY, 300, duration))
            assertEquals(0L, seekPosition(100f, 0, duration))
            for (x in 0..300) assertTrue(seekPosition(x.toFloat(), 300, duration) in 0..duration)
        }
        assertEquals(30_000L, seekPosition(150f, 300, 60_000))
        assertEquals(0L, seekPosition(150f, 300, -1))
    }
}
