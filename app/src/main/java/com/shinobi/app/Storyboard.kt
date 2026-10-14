package com.shinobi.app

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** YouTube's published storyboard tiles, not frames extracted by seeking the live decoder. */
@Immutable
data class VideoStoryboard(
    val urls: List<String>, val width: Int, val height: Int, val count: Int,
    val intervalMs: Int, val columns: Int, val rows: Int,
) {
    data class Tile(val url: String, val x: Int, val y: Int)
    fun tileAt(positionMs: Long): Tile? {
        // Limit decoded sheet size to 3200×1800; prefer much smaller sheets in the extractor.
        if (width !in 1..320 || height !in 1..180 || columns !in 1..10 || rows !in 1..10 || count <= 0 || intervalMs <= 0) return null
        val frame = (positionMs.coerceAtLeast(0) / intervalMs).coerceAtMost(count.toLong() - 1).toInt()
        val perPage = columns * rows
        val url = urls.getOrNull(frame / perPage)?.takeIf { it.startsWith("https://") } ?: return null
        val relative = frame % perPage
        return Tile(url, relative % columns * width, relative / columns * height)
    }
}

@Composable
fun StoryboardPreview(storyboard: VideoStoryboard, positionMs: Long) {
    val tile = storyboard.tileAt(positionMs) ?: return
    val context = LocalContext.current
    // A whole sheet is shared between adjacent positions; changing tiles doesn't re-decode.
    val bitmap by produceState<Bitmap?>(null, tile.url) {
        value = null
        value = withContext(Dispatchers.IO) {
            val result = context.imageLoader.execute(ImageRequest.Builder(context)
                .data(tile.url).size(coil.size.Size.ORIGINAL).allowHardware(false).build())
            ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
        }
    }
    val image = bitmap ?: return
    if (tile.x + storyboard.width > image.width || tile.y + storyboard.height > image.height) return
    Canvas(Modifier.width(112.dp).aspectRatio(storyboard.width.toFloat() / storyboard.height)) {
        drawImage(image.asImageBitmap(), srcOffset = IntOffset(tile.x, tile.y),
            srcSize = IntSize(storyboard.width, storyboard.height),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
    }
}
