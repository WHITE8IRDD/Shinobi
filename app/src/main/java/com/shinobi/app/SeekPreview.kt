package com.shinobi.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Size

/** Metadata only: decoded sprite sheets are owned/bounded by Coil's normal image cache. */
data class SeekStoryboard(
    val urls: List<String>,
    val width: Int,
    val height: Int,
    val count: Int,
    val intervalMs: Int,
    val columns: Int,
    val rows: Int,
) {
    data class Frame(val url: String, val left: Int, val top: Int)

    fun frameAt(positionMs: Long): Frame? {
        if (width <= 0 || height <= 0 || count <= 0 || intervalMs <= 0 || columns <= 0 || rows <= 0) return null
        val perSheet = columns.toLong() * rows
        val available = minOf(count.toLong(), perSheet * urls.size)
        if (available <= 0) return null
        val frame = (positionMs.coerceAtLeast(0) / intervalMs).coerceAtMost(available - 1)
        val local = frame % perSheet
        val url = urls.getOrNull((frame / perSheet).toInt())?.takeIf { it.startsWith("https://") } ?: return null
        return Frame(url, (local % columns).toInt() * width, (local / columns).toInt() * height)
    }
}

/** One sprite request per sheet, not per finger movement; drawing crops without bitmap copies. */
@Composable
internal fun SeekThumbnail(storyboard: SeekStoryboard?, thumbnail: String?, position: Long, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.9f)).padding(4.dp)
            .testTag("seek-thumbnail"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val frame = storyboard?.frameAt(position)
        val imageModifier = Modifier.width(144.dp).aspectRatio(
            if (frame != null && storyboard != null) storyboard.width.toFloat() / storyboard.height else 16f / 9f,
        ).clip(RoundedCornerShape(6.dp))
        if (frame == null || storyboard == null) {
            // Live streams and some uploads do not provide storyboards. Keep seeking usable.
            Thumbnail(thumbnail, imageModifier, radius = 6)
        } else {
            val context = LocalContext.current
            val request = remember(frame.url) {
                ImageRequest.Builder(context).data(frame.url).size(Size.ORIGINAL).build()
            }
            val painter = rememberAsyncImagePainter(request)
            Canvas(imageModifier) {
                val sheet = painter.intrinsicSize
                if (sheet.width.isFinite() && sheet.height.isFinite() && sheet.width > 0 && sheet.height > 0) {
                    clipRect {
                        withTransform({
                            scale(size.width / storyboard.width, size.height / storyboard.height, pivot = androidx.compose.ui.geometry.Offset.Zero)
                            translate(-frame.left.toFloat(), -frame.top.toFloat())
                        }) { with(painter) { draw(sheet) } }
                    }
                }
            }
        }
        Text(formatDuration(position), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
    }
}
