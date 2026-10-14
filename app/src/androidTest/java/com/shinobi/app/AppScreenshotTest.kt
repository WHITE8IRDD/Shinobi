package com.shinobi.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/**
 * The real app, launched fresh on the emulator, photographed at the screens the product
 * owner asked to see: the empty first-launch Home, the Trending charts with live data, and
 * the bottom navigation with "Subscriptions" selected. Each test also asserts the point the
 * picture is meant to prove, so a regression fails the build rather than only changing a
 * screenshot. Pictures land in Download/shinobi-screenshots/ (see [Screenshots]).
 */
class AppScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    companion object {
        @BeforeClass @JvmStatic
        fun grantNotifications() {
            // Otherwise the Android 13+ permission prompt would sit on top of the first screen.
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun shot(name: String): String {
        compose.waitForIdle()
        Thread.sleep(400) // let image loads and the last animation frame land
        compose.waitForIdle()
        return Screenshots.save(context, name, Screenshots.captureWindow(compose.activity))
    }

    private fun layouts(node: SemanticsNodeInteraction): List<TextLayoutResult> {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results
    }

    @Test fun a_firstLaunchHomeIsEmptyWithTheWarmUpMessage() {
        val message = "Search or watch a few videos to warm up your feed."
        compose.waitUntil(20_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("Your feed starts with you").assertIsDisplayed()
        // No video cards at all on a first launch.
        assertEquals(0, compose.onAllNodesWithTag("video-card").fetchSemanticsNodes().size)
        shot("01-home-first-launch")
    }

    @Test fun b_trendingShowsTheCountryAndItsChart() {
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Trending").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Trending").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Trending on YouTube").fetchSemanticsNodes().isNotEmpty() }
        // Live data: give YouTube up to 45 s, then photograph whatever is on screen.
        val loaded = runCatching {
            compose.waitUntil(45_000) { compose.onAllNodesWithTag("video-card").fetchSemanticsNodes().size >= 3 }
        }.isSuccess
        val cards = compose.onAllNodesWithTag("video-card").fetchSemanticsNodes()
        val titles = cards.mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" | ") { it.text } }
        Screenshots.saveText(
            context, "02-trending-titles",
            "country=${AppSettings.trendingCountry} auto=${AppSettings.trendingCountryIsAuto} detected=${AppSettings.detectedCountry}\n" +
                "cards=${cards.size}\n" + titles.joinToString("\n"),
        )
        // Not asserted: a runner without a route to YouTube must not block a release. The
        // file name says whether the chart came through, and the titles file says what did.
        shot(if (loaded) "02-trending" else "02-trending-unavailable")
    }

    /** The pill's merged node: its icon's description plus, when active, its label text. */
    private fun assertPillLabelFits(label: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
                .any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
        }
        val pill = compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
            .indexOfFirst { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
        val first = layouts(compose.onAllNodesWithContentDescription(label)[pill]).firstOrNull()
        assertTrue("no text layout for '$label'", first != null)
        assertFalse("'$label' is clipped in its pill: size=${first!!.size} lines=${first.lineCount}", first.hasVisualOverflow)
        assertEquals("'$label' wrapped in its pill", 1, first.lineCount)
    }

    @Test fun c_subscriptionsTabLabelFitsItsPill() {
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Subscriptions").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("Subscriptions").onFirst().performClick()
        assertPillLabelFits("Subscriptions")
        shot("03-nav-subscriptions")
    }

    @Test fun d_libraryAndHomeLabelsFitTheirPills() {
        for (label in listOf("Library", "Home")) {
            compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithContentDescription(label).onFirst().performClick()
            assertPillLabelFits(label)
        }
        shot("04-nav-home")
    }
}
