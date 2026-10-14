package com.shinobi.app

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Where the device tests leave their screenshots: `Download/shinobi-screenshots/` on the
 * shared storage, which `adb pull` can read without root. The CI job prints them into its
 * log and attaches them as an artifact, so a run's screens can be inspected after the fact.
 */
internal object Screenshots {
    private const val DIR = "shinobi-screenshots"

    fun save(context: Context, name: String, bitmap: Bitmap, quality: Int = 92): String {
        val file = "$name.png"
        val path = if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused $file")
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, quality, it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            "Download/$DIR/$file"
        } else {
            val dir = File(context.getExternalFilesDir(null), DIR).apply { mkdirs() }
            File(dir, file).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, quality, it) }
            File(dir, file).absolutePath
        }
        Log.i("ShinobiShots", "saved $path (${bitmap.width}x${bitmap.height})")
        return path
    }

    fun saveText(context: Context, name: String, text: String) {
        val file = "$name.txt"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        } else {
            val dir = File(context.getExternalFilesDir(null), DIR).apply { mkdirs() }
            File(dir, file).writeText(text)
        }
        Log.i("ShinobiShots", "$name:\n$text")
    }

    /** The activity's window as the user sees it (status bar area included). */
    fun captureWindow(activity: Activity): Bitmap {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var result = -1
        PixelCopy.request(activity.window, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
        check(latch.await(10, TimeUnit.SECONDS)) { "PixelCopy timed out" }
        check(result == PixelCopy.SUCCESS) { "PixelCopy failed: $result" }
        return bitmap
    }
}
