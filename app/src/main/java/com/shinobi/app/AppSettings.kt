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

    /** Mix Shorts and long videos together in the Home "For you" feed. On by default. */
    var mixShortsInFeed by mutableStateOf(true)
        private set

    /** Hide the Shorts overlay while playing (tap to reveal). On by default. */
    var shortsAutoHide by mutableStateOf(true)
        private set

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
        mixShortsInFeed = p.getBoolean(KEY_MIX_SHORTS, true)
        shortsAutoHide = p.getBoolean(KEY_SHORTS_AUTOHIDE, true)
    }

    fun setMixShortsInFeed(value: Boolean) {
        if (mixShortsInFeed == value) return
        mixShortsInFeed = value
        prefs?.edit()?.putBoolean(KEY_MIX_SHORTS, value)?.apply()
    }

    fun setShortsAutoHide(value: Boolean) {
        if (shortsAutoHide == value) return
        shortsAutoHide = value
        prefs?.edit()?.putBoolean(KEY_SHORTS_AUTOHIDE, value)?.apply()
    }
}
