package com.shinobi.app

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.Locale

/**
 * Where the phone is, for the regional trending charts.
 *
 * The phone answers first, the same way LibreTube does it: the SIM's country, then the
 * country of the network the phone is on, then the locale. A Wi-Fi-only tablet or an emulator
 * knows none of the first two, so for them YouTube's own answer is asked — its pages carry the
 * `GL` (geolocation) country it resolved from the connection — before falling back to the
 * locale, which says what language the user reads more than where they are.
 */
object Region {
    private val ISO = Locale.getISOCountries().toSet()

    /** A two-letter ISO country code, uppercase, or null when it isn't one. */
    fun normalize(code: String?): String? = code?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 && it in ISO }

    /** The SIM's country, then the network's. Null on devices without telephony. */
    fun fromTelephony(context: Context): String? {
        val tm = runCatching { context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager }.getOrNull() ?: return null
        normalize(runCatching { tm.simCountryIso }.getOrNull())?.let { return it }
        // CDMA networks report their MCC as the home operator's, which is not where the phone is.
        if (runCatching { tm.phoneType }.getOrNull() == TelephonyManager.PHONE_TYPE_CDMA) return null
        return normalize(runCatching { tm.networkCountryIso }.getOrNull())
    }

    /** The country of the phone's locale (what the user set up, not necessarily where they are). */
    fun fromLocale(context: Context): String? {
        val locales = context.resources.configuration.locales
        for (i in 0 until locales.size()) normalize(locales[i].country)?.let { return it }
        return normalize(Locale.getDefault().country)
    }

    /**
     * YouTube's own idea of the connection's country, from the `"GL":"XX"` field its page
     * configuration carries. One small-ish GET; null on any failure (never throws).
     */
    suspend fun fromYouTube(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://www.youtube.com/?persist_gl=0")
                // Skip the EU consent interstitial, which has no configuration to read.
                .header("Cookie", "SOCS=CAISAiAD; CONSENT=PENDING+987")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            Net.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val source = response.body?.source() ?: return@use null
                // The configuration sits in the first part of the page; stop reading once found.
                val text = StringBuilder()
                val chunk = ByteArray(64 * 1024)
                while (text.length < 1_500_000) {
                    val read = source.read(chunk)
                    if (read <= 0) break
                    text.append(String(chunk, 0, read, Charsets.UTF_8))
                    GL.find(text)?.let { return@use normalize(it.groupValues[1]) }
                }
                null
            }
        }.onFailure { Log.i("Shinobi", "region from YouTube unavailable: ${it.javaClass.simpleName}") }.getOrNull()
    }

    private val GL = Regex("\"GL\"\\s*:\\s*\"([A-Za-z]{2})\"")
}
