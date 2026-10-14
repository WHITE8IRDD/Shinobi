package com.shinobi.app

/** Isolate independent metadata runs; Arabic creator names cannot reorder view counts/dates. */
object BidiText {
    private val controls = Regex("[\\u202A-\\u202E\\u2066-\\u2069]")
    fun isolate(value: String): String = "\u2068" + value.replace(controls, "") + "\u2069"
    fun join(parts: List<String>, rtl: Boolean): String =
        (if (rtl) "\u200F" else "\u200E") + parts.filter { it.isNotBlank() }.joinToString(" · ") { isolate(it) }
}
