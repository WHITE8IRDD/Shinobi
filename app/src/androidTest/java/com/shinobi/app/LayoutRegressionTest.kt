package com.shinobi.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LayoutRegressionTest {
    @get:Rule val compose = createComposeRule()
    @Before fun english() { Loc.choose("en") }

    private fun narrow(rtl: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 1.3f),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) { MaterialTheme { Box(Modifier.width(320.dp)) { content() } } }
        }
    }

    private fun assertFullText(text: String, oneLine: Boolean = false) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertFalse("Clipped: $text", layouts.first().hasVisualOverflow)
        if (oneLine) assertEquals(1, layouts.first().lineCount)
    }

    @Test fun oneScrollableChipRowWithNoDuplicateForYouOrClippedMixLabel() {
        narrow {
            HomeTasteFilterRow(listOf("For you", "for YOU", "Trending", "Science"), null, {}, {}, {}, false, {})
        }
        compose.onAllNodesWithText("For you").assertCountEquals(1)
        compose.onAllNodesWithText("Trending").assertCountEquals(1)
        compose.onNodeWithText("Mix Shorts with videos").performScrollTo()
        assertFullText("Mix Shorts with videos", oneLine = true)
    }

    @Test fun subscriptionActionsAndCardTitlesFitNarrowLargeTextScreen() {
        narrow { SignedOutSubscriptionsWelcome(PaddingValues(0.dp), {}, {}) }
        compose.onNodeWithText("Find channels").performScrollTo()
        assertFullText("Find channels", oneLine = true)
        compose.onNodeWithText("Automatic discovery").performScrollTo()
        assertFullText("Automatic discovery")
    }

    @Test fun arabicSubscriptionsAlsoFit() {
        Loc.choose("ar")
        narrow(rtl = true) { SignedOutSubscriptionsWelcome(PaddingValues(0.dp), {}, {}) }
        compose.onNodeWithText(Loc.t("Find channels")).performScrollTo()
        assertFullText(Loc.t("Find channels"), oneLine = true)
        compose.onNodeWithText(Loc.t("Automatic discovery")).performScrollTo()
        assertFullText(Loc.t("Automatic discovery"))
    }

    @Test fun mixedArabicEnglishTitleAndMetadataRemainReadableInEnglishLayout() {
        val title = "مراجعة Pixel 10 — تجربة 2026"
        val meta = metaLine("قناة عربية", 12000, "2 days ago")
        narrow { VideoCard(VideoItem("test", title, "قناة عربية", null, null, null, 120, 12000, "2 days ago"), {}) }
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(meta).assertIsDisplayed()
        assertTrue(meta.startsWith("\u200E"))
        assertTrue(meta.contains("\u2068قناة عربية\u2069"))
    }
}
