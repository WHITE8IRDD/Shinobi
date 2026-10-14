package com.shinobi.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Deliberately not a title/skin-tone/age-limit heuristic. Scores are not calibrated certainty. */
internal object SensitivePolicy {
    fun shouldCover(full: Float, center: Float): Boolean =
        full.isFinite() && center.isFinite() && full in 0.995f..1f && center in 0.995f..1f
}

/** One CPU worker, a pinned bundled model, bounded URL-keyed results, no third-party uploads. */
internal object SensitiveThumbnails {
    private val gate = Semaphore(1)
    private val cache = object : LinkedHashMap<String, Boolean>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 256
    }
    private var interpreter: Interpreter? = null

    suspend fun covered(context: Context, url: String): Boolean = gate.withPermit {
        withContext(Dispatchers.IO) {
            cache[url]?.let { return@withContext it }
            val result = context.imageLoader.execute(
                ImageRequest.Builder(context).data(url).size(256).allowHardware(false).build(),
            ) as? SuccessResult ?: return@withContext false
            val image = (result.drawable as? BitmapDrawable)?.bitmap ?: return@withContext false
            val model = interpreter ?: run {
                val bytes = context.assets.open("nsfw.tflite").use { it.readBytes() }
                val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
                buffer.rewind()
                Interpreter(buffer, Interpreter.Options().setNumThreads(1)).also {
                    check(it.getInputTensor(0).shape().contentEquals(intArrayOf(1, 224, 224, 3)))
                    check(it.getOutputTensor(0).shape().contentEquals(intArrayOf(1, 2)))
                    interpreter = it
                }
            }
            // Conservative agreement test: both the full image and center crop must score
            // very highly. Generic age restrictions/music categories are NOT sexual labels.
            val full = score(model, image)
            val side = minOf(image.width, image.height)
            val center = Bitmap.createBitmap(image, (image.width - side) / 2, (image.height - side) / 2, side, side)
            val cropped = score(model, center)
            if (center !== image) center.recycle()
            SensitivePolicy.shouldCover(full, cropped).also { cache[url] = it }
        }
    }

    private fun score(model: Interpreter, bitmap: Bitmap): Float {
        val scaled = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
        val pixels = IntArray(224 * 224)
        scaled.getPixels(pixels, 0, 224, 0, 0, 224, 224)
        val input = ByteBuffer.allocateDirect(pixels.size * 3 * 4).order(ByteOrder.nativeOrder())
        for (pixel in pixels) {
            // The pinned model's input is BGR, with channel means 104, 117, 123.
            input.putFloat((pixel and 255) - 104f)
            input.putFloat(((pixel shr 8) and 255) - 117f)
            input.putFloat(((pixel shr 16) and 255) - 123f)
        }
        input.rewind()
        val output = arrayOf(FloatArray(2))
        model.run(input, output)
        if (scaled !== bitmap) scaled.recycle()
        return output[0][1]
    }
}

/** Opaque cover works on API 26 as well as new phones; reveal is local and reversible. */
@Composable
fun SensitiveThumbnail(url: String?, modifier: Modifier = Modifier, radius: Int = 22) {
    val enabled = AppSettings.sensitiveFilter
    if (!enabled || url == null) { Thumbnail(url, modifier, radius); return }
    val context = LocalContext.current.applicationContext
    var reveal by remember(url) { mutableStateOf(false) }
    var covered by remember(url) { mutableStateOf<Boolean?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        try { covered = SensitiveThumbnails.covered(context, url) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { failed = true; covered = false }
    }
    Box(modifier.clip(RoundedCornerShape(radius.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (covered == false || reveal) {
            Thumbnail(url, Modifier.matchParentSize(), radius)
            if (failed) Text(Loc.t("Filter unavailable"), fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomStart).background(MaterialTheme.colorScheme.surface).padding(4.dp))
        } else {
            Column(Modifier.align(Alignment.Center).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Loc.t(if (covered == null) "Checking thumbnail…" else "Potentially sensitive thumbnail"), fontSize = 12.sp)
                TextButton(onClick = { reveal = true }) { Text(Loc.t("Show anyway"), fontSize = 12.sp) }
            }
        }
    }
}
