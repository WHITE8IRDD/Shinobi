package com.shinobi.app

import android.app.ActivityManager
import android.content.Context

/**
 * How much headroom this phone has. The app must stay super fluid on old 2–3 GB devices, so
 * the heavier optional pieces (a second preview player, big image caches) check this and
 * step aside there — the same way the big open-source clients gate extras on device class.
 */
object DeviceRam {
    /** True on phones where memory is tight: 2–3 GB class hardware or an OS low-RAM flag. */
    @Volatile
    var lowRam: Boolean = false
        private set

    fun init(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val flagged = am?.isLowRamDevice == true
        val total = am?.totalMem ?: 0L
        // Below ~3.5 GB total, treat as low-RAM (a "3 GB" phone has less usable to the app).
        lowRam = flagged || total in 1 until 3_500_000_000L
    }
}
