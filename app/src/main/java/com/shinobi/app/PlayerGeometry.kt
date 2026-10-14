package com.shinobi.app

import kotlin.math.roundToInt

/** Pixel geometry shared by portrait, landscape, Shorts and PiP. Never stretch the image. */
internal object PlayerGeometry {
    data class Size(val width: Int, val height: Int)
    fun size(width: Int, height: Int, aspect: Float, cover: Boolean = false): Size {
        val ratio = aspect.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val byHeight = (w.toFloat() / h > ratio) != cover
        return Size(
            (if (byHeight) h * ratio else w.toFloat()).roundToInt().coerceAtLeast(1),
            (if (byHeight) h.toFloat() else w / ratio).roundToInt().coerceAtLeast(1),
        )
    }
}
