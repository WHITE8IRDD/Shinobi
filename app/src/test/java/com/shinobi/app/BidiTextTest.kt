package com.shinobi.app

import org.junit.Assert.*
import org.junit.Test

class BidiTextTest {
    @Test fun englishLayoutKeepsArabicChannelCountsAndDatesInSeparateIsolates() {
        assertEquals("\u200E\u2068قناة عربية\u2069 · \u206812K views\u2069 · \u20682 days ago\u2069",
            BidiText.join(listOf("قناة عربية", "12K views", "2 days ago"), rtl = false))
    }
    @Test fun arabicLayoutDoesNotReverseLatinChannelOrDuration() {
        assertEquals("\u200F\u2068English Channel\u2069 · \u206812:34\u2069", BidiText.join(listOf("English Channel", "12:34"), true))
    }
    @Test fun nestedDirectionControlsCannotEscapeMetadataRun() {
        assertEquals("\u2068abc\u2069", BidiText.isolate("\u202Eab\u2069c"))
    }
}
