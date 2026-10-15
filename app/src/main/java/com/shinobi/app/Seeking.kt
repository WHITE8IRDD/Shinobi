package com.shinobi.app

/** Shared pointer-to-timeline mapping. Off-track taps, zero-size layouts and unknown/live
 * timelines must never dispatch a negative, overflowing or non-finite seek. */
internal fun seekPosition(x: Float, width: Int, durationMs: Long): Long {
    if (!x.isFinite() || width <= 0 || durationMs <= 0) return 0L
    return ((x.toDouble() / width).coerceIn(0.0, 1.0) * durationMs).toLong()
        .coerceIn(0L, durationMs)
}
