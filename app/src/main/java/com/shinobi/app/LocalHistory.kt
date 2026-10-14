package com.shinobi.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** A recent item held only on this device; it is never sent to the account or a server. */
data class LocalHistoryEntry(val video: VideoItem, val watchedAtMs: Long)

/**
 * A small private history for signed-out use. Keeping a compact, bounded metadata list means
 * Search and playback work without an account while giving users a one-tap way to clear it.
 */
object LocalHistory {
    private const val PREFS = "local_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 100
    private var prefs: SharedPreferences? = null

    var entries by mutableStateOf<List<LocalHistoryEntry>>(emptyList())
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs?.getString(KEY_ENTRIES, null).orEmpty()
        entries = runCatching {
            val array = JSONArray(stored)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val item = obj.toVideoItem() ?: continue
                    val watchedAt = obj.optLong("watchedAt", 0L).takeIf { it > 0L } ?: continue
                    add(LocalHistoryEntry(item, watchedAt))
                }
            }.take(MAX_ENTRIES)
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun record(video: VideoItem, watchedAtMs: Long = System.currentTimeMillis()) {
        if (video.id.isBlank() || video.title.isBlank()) return
        entries = (listOf(LocalHistoryEntry(video, watchedAtMs)) + entries.filter { it.video.id != video.id })
            .take(MAX_ENTRIES)
        persist()
    }

    @Synchronized
    fun remove(videoId: String) {
        val updated = entries.filterNot { it.video.id == videoId }
        if (updated.size == entries.size) return
        entries = updated
        persist()
    }

    @Synchronized
    fun clear() {
        entries = emptyList()
        prefs?.edit()?.remove(KEY_ENTRIES)?.apply()
    }

    private fun persist() {
        val array = JSONArray()
        entries.forEach { entry ->
            val video = entry.video
            array.put(
                JSONObject()
                    .put("id", video.id)
                    .put("title", video.title)
                    .put("channel", video.channel)
                    .put("channelUrl", video.channelUrl)
                    .put("thumbnail", video.thumbnail)
                    .put("avatar", video.avatar)
                    .put("durationSec", video.durationSec)
                    .put("views", video.views)
                    .put("uploaded", video.uploaded)
                    .put("watchedAt", entry.watchedAtMs),
            )
        }
        prefs?.edit()?.putString(KEY_ENTRIES, array.toString())?.apply()
    }

    private fun JSONObject.toVideoItem(): VideoItem? {
        val id = optString("id", "").takeIf { it.isNotBlank() } ?: return null
        return VideoItem(
            id = id,
            title = optString("title", "").ifBlank { "Untitled video" },
            channel = optString("channel", ""),
            channelUrl = nullableString("channelUrl"),
            thumbnail = nullableString("thumbnail"),
            avatar = nullableString("avatar"),
            durationSec = optLong("durationSec", 0L).coerceAtLeast(0L),
            views = optLong("views", -1L),
            uploaded = nullableString("uploaded"),
        )
    }

    private fun JSONObject.nullableString(key: String): String? =
        optString(key, "").takeIf { it.isNotBlank() && it != "null" }
}
