package com.shinobi.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToLong

/** A single time-bounded line from a caption file. Times are milliseconds from video start. */
data class CaptionCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * Parses YouTube's TTML/transcript responses and ordinary WebVTT without Android APIs, so the
 * parser is deterministic and covered by JVM tests. Bad cues are ignored rather than breaking
 * playback; the returned timeline is bounded and sorted.
 */
object CaptionParser {
    private const val MAX_CHARS = 4 * 1024 * 1024
    private const val MAX_CUES = 100_000
    private val cueTag = Regex("(?is)<(p|text)\\b([^>]*)>(.*?)</\\1\\s*>")
    private val attribute = Regex("""([A-Za-z_][A-Za-z0-9_.:-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val clock = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{1,2})(?:[.,](\d{1,9}))?$""")
    private val entity = Regex("""&#(?:x([0-9a-fA-F]+)|(\d+));|&([A-Za-z]+);""")

    fun parse(payload: String): List<CaptionCue> {
        if (payload.isBlank() || payload.length > MAX_CHARS) return emptyList()
        val parsed = if (payload.contains("-->") || payload.trimStart().startsWith("WEBVTT", ignoreCase = true)) {
            parseWebVtt(payload)
        } else {
            parseTranscript(payload)
        }
        return parsed.asSequence()
            .filter { it.startMs >= 0L && it.endMs > it.startMs && it.text.isNotBlank() }
            .sortedBy { it.startMs }
            .take(MAX_CUES)
            .toList()
    }

    /** Returns the cue at [positionMs], using binary search over the sorted timeline. */
    fun cueAt(cues: List<CaptionCue>, positionMs: Long): CaptionCue? {
        var low = 0
        var high = cues.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cue = cues[mid]
            when {
                positionMs < cue.startMs -> high = mid - 1
                positionMs >= cue.endMs -> low = mid + 1
                else -> return cue
            }
        }
        return null
    }

    private fun parseTranscript(payload: String): List<CaptionCue> = buildList {
        for (match in cueTag.findAll(payload)) {
            if (size >= MAX_CUES) break
            val attrs = attributes(match.groupValues[2])
            val startEntry = listOf("begin", "start", "t").firstNotNullOfOrNull { key -> attrs[key]?.let { key to it } }
                ?: continue
            val start = parseTime(startEntry.second, startEntry.first) ?: continue
            val endEntry = listOf("end", "dur", "d", "duration").firstNotNullOfOrNull { key -> attrs[key]?.let { key to it } }
            val end = endEntry?.let { (key, value) ->
                val parsed = parseTime(value, key) ?: return@let null
                if (key == "dur" || key == "d" || key == "duration") start + parsed else parsed
            } ?: (start + 2_000L)
            val text = cleanText(match.groupValues[3])
            if (end > start && text.isNotEmpty()) add(CaptionCue(start, end, text))
        }
    }

    private fun parseWebVtt(payload: String): List<CaptionCue> = buildList {
        val normalized = payload.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        for (block in normalized.split(Regex("\\n[\\t ]*\\n"))) {
            if (size >= MAX_CUES || block.startsWith("NOTE")) continue
            val lines = block.lines()
            val timingIndex = lines.indexOfFirst { "-->" in it }
            if (timingIndex < 0) continue
            val times = lines[timingIndex].split("-->", limit = 2)
            if (times.size != 2) continue
            val start = parseTime(times[0].trim(), "clock") ?: continue
            val endToken = times[1].trim().substringBefore(' ').trim()
            val end = parseTime(endToken, "clock") ?: continue
            val text = cleanText(lines.drop(timingIndex + 1).joinToString("\n"))
            if (end > start && text.isNotEmpty()) add(CaptionCue(start, end, text))
        }
    }

    private fun attributes(source: String): Map<String, String> = buildMap {
        for (match in attribute.findAll(source)) {
            put(match.groupValues[1].lowercase(), match.groupValues[2].ifEmpty { match.groupValues[3] })
        }
    }

    /** Clock values are seconds; TTML's t/d attributes are milliseconds. */
    private fun parseTime(raw: String, attributeName: String): Long? {
        val value = raw.trim().removeSuffix("ms").removeSuffix("s").trim()
        clock.matchEntire(value)?.let { match ->
            val hours = match.groupValues[1].toLongOrNull() ?: 0L
            val minutes = match.groupValues[2].toLongOrNull() ?: return null
            val seconds = match.groupValues[3].toLongOrNull() ?: return null
            val fraction = match.groupValues[4].take(3).padEnd(3, '0').toLongOrNull() ?: 0L
            return (((hours * 60L + minutes) * 60L + seconds) * 1_000L + fraction)
        }
        val number = value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        val inMilliseconds = attributeName.equals("t", true) || attributeName.equals("d", true) || raw.trim().endsWith("ms", true)
        return (number * if (inMilliseconds) 1.0 else 1_000.0).roundToLong()
    }

    private fun cleanText(source: String): String {
        var value = source
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            // YouTube's auto-captions carry each word in its own <s> span, and the spans are not
            // always separated by whitespace in the source: keep the words apart.
            .replace(Regex("(?i)</s\\s*>"), " ")
            // Every other tag (i, b, c, v, ruby, …) is styling around words that are already
            // spaced in the text, so it is dropped without leaving a gap: a tag between "world"
            // and "!" must not become "world !".
            .replace(Regex("<[^>]*>"), "")
        repeat(2) {
            value = entity.replace(value) { match ->
                val hex = match.groupValues[1]
                val decimal = match.groupValues[2]
                when {
                    hex.isNotEmpty() -> hex.toIntOrNull(16)?.let(::codePoint) ?: match.value
                    decimal.isNotEmpty() -> decimal.toIntOrNull()?.let(::codePoint) ?: match.value
                    else -> when (match.groupValues[3].lowercase()) {
                        "amp" -> "&"
                        "lt" -> "<"
                        "gt" -> ">"
                        "quot" -> "\""
                        "apos" -> "'"
                        "nbsp" -> " "
                        "lrm" -> "\u200E"
                        "rlm" -> "\u200F"
                        "mdash" -> "—"
                        "ndash" -> "–"
                        else -> match.value
                    }
                }
            }
        }
        return value
            .replace('\u00A0', ' ')
            .replace(Regex("[\\t ]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .trim()
    }

    private fun codePoint(value: Int): String =
        if (Character.isValidCodePoint(value) && value !in 0xD800..0xDFFF) String(Character.toChars(value)) else ""
}

/** Fetches a caption URL only from YouTube over TLS; stream errors never stop the video. */
object CaptionLoader {
    private const val MAX_BYTES = 4L * 1024L * 1024L

    suspend fun load(track: CaptionTrack): List<CaptionCue> = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val url = track.url.toHttpUrlOrNull()
            ?: throw IOException("The caption link is invalid.")
        val host = url.host.lowercase()
        if (!url.isHttps || !(host == "youtube.com" || host.endsWith(".youtube.com"))) {
            throw IOException("The caption link wasn't served by YouTube.")
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Net.USER_AGENT)
            .header("Accept", "text/vtt, application/ttml+xml, application/xml, text/xml, */*")
            .build()
        Net.client.newCall(request).execute().use { response ->
            coroutineContext.ensureActive()
            if (!response.isSuccessful) throw IOException("Caption request failed (HTTP ${response.code}).")
            if (!(response.request.url.host == "youtube.com" || response.request.url.host.endsWith(".youtube.com"))) {
                throw IOException("The caption response was redirected away from YouTube.")
            }
            val body = response.body ?: throw IOException("The caption response was empty.")
            if (body.contentLength() > MAX_BYTES) throw IOException("This caption file is too large.")
            val source = body.source()
            val buffer = okio.Buffer()
            var total = 0L
            while (total <= MAX_BYTES) {
                val read = source.read(buffer, minOf(8_192L, MAX_BYTES + 1L - total))
                if (read == -1L) break
                total += read
            }
            if (total > MAX_BYTES) throw IOException("This caption file is too large.")
            CaptionParser.parse(buffer.readUtf8())
        }
    }
}

/**
 * The last language the user picked. A null language means captions are off; a chosen language
 * is matched to the best available track on the next video, preferring manually authored captions.
 */
object CaptionPreference {
    private const val PREFS = "captions"
    private const val KEY_LANGUAGE = "language_tag"
    private var prefs: SharedPreferences? = null

    @Volatile
    var languageTag: String? = null
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        languageTag = prefs?.getString(KEY_LANGUAGE, null)?.takeIf { it.isNotBlank() }
    }

    fun setLanguage(tag: String?) {
        languageTag = tag?.takeIf { it.isNotBlank() }
        prefs?.edit()?.let { edit ->
            if (languageTag == null) edit.remove(KEY_LANGUAGE) else edit.putString(KEY_LANGUAGE, languageTag)
            edit.apply()
        }
    }
}
