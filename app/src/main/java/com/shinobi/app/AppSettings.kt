package com.shinobi.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Playback and feed behaviour the user can change, persisted:
 *  - [mixShortsInFeed]: "For you" mixes Shorts in with long videos instead of boxing them
 *    into their own shelf (product owner, 2026-10-14);
 *  - [trendingRegionChoice]: the country of the trending charts, automatic (where the phone
 *    is, see [Region]) unless the user picks one;
 *  - [feedPreviews]: whether Home plays silent previews of the card in view.
 */
object AppSettings {
    private const val PREFS = "app_settings"
    private const val KEY_MIX_SHORTS = "mix_shorts_in_feed"
    private const val KEY_TRENDING_REGION = "trending_region"
    private const val KEY_FEED_PREVIEWS = "feed_previews"
    private const val KEY_BLUR_SENSITIVE = "blur_sensitive_thumbnails"
    /** The trending region follows where the phone is. */
    const val AUTO_REGION = "auto"
    private var prefs: SharedPreferences? = null

    private var mixState by mutableStateOf(true)
    private var previewsState by mutableStateOf(FeedPreview.ALWAYS)
    private var blurSensitiveState by mutableStateOf(true)

    /**
     * Blur the thumbnails of age-restricted/mature videos (see [SensitiveFilter]). On by
     * default; detection is YouTube's own metadata, so ordinary content is never blurred.
     */
    var blurSensitiveThumbnails: Boolean
        get() = blurSensitiveState
        set(value) {
            if (blurSensitiveState == value) return
            blurSensitiveState = value
            prefs?.edit()?.putBoolean(KEY_BLUR_SENSITIVE, value)?.apply()
        }

    /**
     * Mix Shorts and long videos together in the Home "For you" feed. On by default.
     * Assigning it persists the choice; an unchanged value writes nothing.
     */
    var mixShortsInFeed: Boolean
        get() = mixState
        set(value) {
            if (mixState == value) return
            mixState = value
            prefs?.edit()?.putBoolean(KEY_MIX_SHORTS, value)?.apply()
        }

    /**
     * Silent previews on Home when a card settles in view (see [FeedPreview]):
     * [FeedPreview.ALWAYS], [FeedPreview.WIFI] (unmetered connections only) or [FeedPreview.OFF].
     */
    var feedPreviews: String
        get() = previewsState
        set(value) {
            val v = value.takeIf { it == FeedPreview.ALWAYS || it == FeedPreview.WIFI || it == FeedPreview.OFF } ?: return
            if (previewsState == v) return
            previewsState = v
            prefs?.edit()?.putString(KEY_FEED_PREVIEWS, v)?.apply()
        }

    /** The trending region choice: [AUTO_REGION], or an ISO country code the user picked. */
    var trendingRegionChoice by mutableStateOf(AUTO_REGION)
        private set

    /** Where the phone seems to be (see [Region]); what [AUTO_REGION] resolves to. */
    var detectedCountry by mutableStateOf("US")
        private set

    /** The country the trending charts are for: the detected one unless the user chose another. */
    val trendingCountry: String
        get() = if (trendingRegionChoice == AUTO_REGION) detectedCountry else trendingRegionChoice

    val trendingCountryIsAuto: Boolean get() = trendingRegionChoice == AUTO_REGION

    /** True when neither SIM nor network said where the phone is, so only the locale did. */
    var detectionIsGuess: Boolean = true
        private set

    /** [code] is [AUTO_REGION] or an ISO country code; anything else is ignored. */
    fun chooseTrendingCountry(code: String) {
        val choice = if (code == AUTO_REGION) AUTO_REGION else Region.normalize(code) ?: return
        if (choice == trendingRegionChoice) return
        trendingRegionChoice = choice
        prefs?.edit()?.putString(KEY_TRENDING_REGION, choice)?.apply()
    }

    /** A better detection result arrived (e.g. YouTube's own answer on a Wi-Fi-only device). */
    fun updateDetectedCountry(code: String?) {
        detectedCountry = Region.normalize(code) ?: return
        detectionIsGuess = false
    }

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
        val fromPhone = Region.fromTelephony(context)
        detectionIsGuess = fromPhone == null
        detectedCountry = fromPhone ?: Region.fromLocale(context) ?: "US"
        trendingRegionChoice = p.getString(KEY_TRENDING_REGION, null)?.let { if (it == AUTO_REGION) it else Region.normalize(it) }
            // Before 1.6 the chart was always the United States' unless the user picked another
            // country; a pick other than that default is kept, the default becomes automatic.
            ?: p.getString("trending_country", null)?.takeIf { it != "US" }?.let { Region.normalize(it) }
            ?: AUTO_REGION
        mixState = p.getBoolean(KEY_MIX_SHORTS, true)
        // Low-RAM phones (2–3 GB) start with Home previews off — they keep a second decoder
        // warm — and can still turn them on explicitly.
        previewsState = p.getString(KEY_FEED_PREVIEWS, null)?.takeIf { it == FeedPreview.ALWAYS || it == FeedPreview.WIFI || it == FeedPreview.OFF }
            ?: if (DeviceRam.lowRam) FeedPreview.OFF else FeedPreview.ALWAYS
        blurSensitiveState = p.getBoolean(KEY_BLUR_SENSITIVE, true)
    }
}
