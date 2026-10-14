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
 *  - [shortsAutoHide]: the Shorts player hides every control while a Short plays, so the
 *    video has the screen to itself, and a tap brings the controls back.
 */
object AppSettings {
    private const val PREFS = "app_settings"
    private const val KEY_MIX_SHORTS = "mix_shorts_in_feed"
    private const val KEY_SHORTS_AUTOHIDE = "shorts_autohide"
    private var prefs: SharedPreferences? = null

    private var mixState by mutableStateOf(true)
    private var autoHideState by mutableStateOf(true)

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

    /** Hide the Shorts overlay while playing (tap to reveal). On by default. */
    var shortsAutoHide: Boolean
        get() = autoHideState
        set(value) {
            if (autoHideState == value) return
            autoHideState = value
            prefs?.edit()?.putBoolean(KEY_SHORTS_AUTOHIDE, value)?.apply()
        }

    var trendingCountry by mutableStateOf("US")
        private set

    fun chooseTrendingCountry(code: String) {
        if (code !in java.util.Locale.getISOCountries()) return
        trendingCountry = code
        prefs?.edit()?.putString("trending_country", code)?.apply()
    }

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
        trendingCountry = p.getString("trending_country", "US")?.takeIf { it in java.util.Locale.getISOCountries() } ?: "US"
        mixState = p.getBoolean(KEY_MIX_SHORTS, true)
        autoHideState = p.getBoolean(KEY_SHORTS_AUTOHIDE, true)
    }
}
