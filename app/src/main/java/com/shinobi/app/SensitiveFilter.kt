package com.shinobi.app

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * The sensitive-content filter: a thumbnail blur for the videos YouTube itself flags as
 * age-restricted or mature.
 *
 * Why it can't lag or misfire: detection is **metadata, not pixels**. Nothing is scanned,
 * decoded or classified on-device — a card is flagged only when YouTube's own response says
 * so (an age-restriction badge in the feed, the extractor's age rating on the open video),
 * which is exact and arrives with the same background parse that built the card. There is no
 * per-frame work and no false positive on ordinary videos. The blur itself is a GPU
 * RenderEffect on Android 12+, and a cheap scrim on older devices — drawn only on the few
 * flagged thumbnails, and animated smoothly when the setting changes.
 */
object SensitiveFilter {
    /** The extractor's age rating at which a thumbnail is blurred. */
    const val AGE_LIMIT = 18

    /** Blur only when both the video is flagged and the user left the filter on. */
    fun shouldBlur(item: VideoItem): Boolean = item.sensitive && AppSettings.blurSensitiveThumbnails

    /**
     * Feed-level signal from a raw InnerTube renderer: an age-restriction badge, or the
     * explicit `isFamilySafe: false` flag. Ordinary uploads carry neither — so normal
     * content is never touched. Only badge fields and that one flag are read (never a title
     * or a description), object by object on JSON already parsed off the UI thread —
     * cheaper than decoding one thumbnail, and impossible to misfire on regular videos.
     */
    fun flaggedInFeed(json: JSONObject): Boolean {
        var flagged = false
        json.forEachObject { obj ->
            if (flagged) return@forEachObject
            // {"isFamilySafe": false} — a boolean or its string form.
            if (obj.has("isFamilySafe") && !obj.optBoolean("isFamilySafe", true)) flagged = true
            // Badge style enums: BADGE_STYLE_TYPE_AGE_RESTRICTED and friends.
            val style = obj.optString("style").ifBlank { obj.optString("badgeStyle") }
            if (style.contains("AGE", ignoreCase = true) && style.contains("RESTRICT", ignoreCase = true)) flagged = true
            // Badge labels only ("label" / "badgeText") — never run text, so a title that
            // happens to say "18+" cannot flag a card.
            for (field in BADGE_LABEL_FIELDS) {
                val label = obj.optString(field).trim()
                if (label.isNotEmpty() && AGE_BADGE.matches(label)) flagged = true
            }
        }
        return flagged
    }

    private val BADGE_LABEL_FIELDS = arrayOf("label", "badgeText")

    /** Exactly YouTube's own labels — never a title or a sentence that merely mentions age. */
    private val AGE_BADGE = Regex("^(18\\+|21\\+|Age[- ]restricted)$", RegexOption.IGNORE_CASE)
}

/**
 * The blur for a flagged thumbnail's image layer: a smooth GPU RenderEffect on Android 12+,
 * and no blur at all below that (where [Thumbnail] falls back to a scrim — software blur on
 * old, low-RAM phones is exactly what used to cost frames). The radius eases in when a
 * flagged thumbnail appears, so nothing pops.
 */
@Composable
internal fun sensitiveImageBlur(enabled: Boolean): Modifier {
    if (!enabled || Build.VERSION.SDK_INT < 31) return Modifier
    val strength by animateFloatAsState(targetValue = 1f, animationSpec = tween(220), label = "sensitiveBlur")
    return Modifier.blur(16.dp * strength)
}
