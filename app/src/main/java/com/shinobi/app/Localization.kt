package com.shinobi.app

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * The app's language, persisted, with **English as the default** (product owner, 2026-10-14):
 * a fresh install is English even on an Arabic phone, and Arabic is one tap away in
 * Settings → Language. Choosing Arabic flips the whole app to right-to-left through the
 * Activity's configuration ([wrap]), so mirroring is real layout mirroring — not a pile of
 * per-screen exceptions — exactly as the system does it for the official YouTube app.
 */
object Loc {
    const val ENGLISH = "en"
    const val ARABIC = "ar"
    private const val PREFS = "language"
    private const val KEY_LANGUAGE = "language"
    private var prefs: SharedPreferences? = null

    var language by mutableStateOf(ENGLISH)
        private set

    /** Bumped on every language change so screens can rebuild derived text. */
    var version by mutableIntStateOf(0)
        private set

    val isArabic: Boolean get() = language == ARABIC

    /** True when the app draws right-to-left. */
    val isRtl: Boolean get() = isArabic

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
        language = p.getString(KEY_LANGUAGE, null)?.takeIf { it == ARABIC || it == ENGLISH } ?: ENGLISH
    }

    fun choose(tag: String) {
        val next = if (tag == ARABIC) ARABIC else ENGLISH
        if (next == language) return
        language = next
        version++
        prefs?.edit()?.putString(KEY_LANGUAGE, next)?.apply()
    }

    fun localeOf(tag: String = language): Locale =
        if (tag == ARABIC) Locale.forLanguageTag("ar") else Locale.ENGLISH

    /**
     * The activity context with the chosen language and its layout direction applied.
     * Applied from `attachBaseContext`, so resources, string lookups, the WebView sign-in
     * page and Compose's own layout direction all agree on the language from the first frame.
     */
    fun wrap(base: Context): Context {
        val locale = localeOf()
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * The one translation entry point: `t("Home")` is "الرئيسية" in Arabic and the same
     * string in English. Unknown keys fall through unchanged, so a missed string can only
     * ever be untranslated — never blank.
     */
    fun t(key: String): String = if (language == ARABIC) ARABIC_STRINGS[key] ?: key else key

    /** "1.2M views" / "1.2 مليون مشاهدة". */
    fun views(n: Long): String = if (isArabic) "${formatCount(n)} مشاهدة" else "${formatCount(n)} views"

    /** "12 subscribers" / "12 مشتركًا". */
    fun subscribers(n: Long): String = if (isArabic) "${formatCount(n)} مشترك" else "${formatCount(n)} subscribers"

    /** "12 likes" / "12 إعجابًا". */
    fun likes(n: Long): String = if (isArabic) "${formatCount(n)} إعجاب" else "${formatCount(n)} likes"

    /** "12 videos" / "12 مقطع فيديو". */
    fun videos(n: Int): String =
        if (isArabic) "$n ${if (n == 1) "مقطع فيديو" else "مقطع فيديو"}"
        else "$n video${if (n == 1) "" else "s"}"

    /** "12 comments" / "12 تعليقًا". */
    fun comments(n: Long): String = if (isArabic) "${formatCount(n)} تعليق" else "${formatCount(n)} comments"

    /** "Watched 3 days ago" / "شوهد قبل 3 أيام". */
    fun watched(relative: String): String = if (isArabic) "شوهد $relative" else "Watched $relative"

    /** "3 days ago" / "قبل 3 أيام". */
    fun ago(n: Long, unit: String): String = if (isArabic) {
        val word = when (unit) {
            "second" -> if (n == 1L) "ثانية" else "ثوانٍ"
            "minute" -> if (n == 1L) "دقيقة" else "دقائق"
            "hour" -> if (n == 1L) "ساعة" else "ساعات"
            "day" -> if (n == 1L) "يوم" else "أيام"
            "week" -> if (n == 1L) "أسبوع" else "أسابيع"
            "month" -> if (n == 1L) "شهر" else "أشهر"
            else -> if (n == 1L) "سنة" else "سنوات"
        }
        "قبل $n $word"
    } else {
        "$n $unit${if (n == 1L) "" else "s"} ago"
    }

    fun justNow(): String = t("just now")

    /** "3 replies" / "3 ردود". */
    fun replies(n: Int): String = when {
        !isArabic -> "$n ${if (n == 1) "reply" else "replies"}"
        n == 1 -> "رد واحد"
        n == 2 -> "ردان"
        n in 3..10 -> "$n ردود"
        else -> "$n ردًّا"
    }
}
