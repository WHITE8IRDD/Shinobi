package com.shinobi.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Isolated renders of the pieces that were reported broken: the navigation pill at the
 * narrowest supported width with large text, in English and Arabic, and the launcher icon
 * exactly as the device's adaptive-icon pipeline draws it, next to the in-app header tile.
 */
class ComponentScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @Before fun english() { Loc.choose("en") }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun narrow(rtl: Boolean, fontScale: Float, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                ShinobiTheme {
                    Box(Modifier.width(320.dp).background(MaterialTheme.colorScheme.surface).padding(16.dp)) { content() }
                }
            }
        }
    }

    private fun assertFits(text: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onAllNodesWithText(text).onFirst().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue(results.isNotEmpty())
        val first = results.first()
        assertFalse("'$text' clipped: size=${first.size} lines=${first.lineCount}", first.hasVisualOverflow)
        assertEquals("'$text' wrapped", 1, first.lineCount)
    }

    @Test fun navPillSubscriptionsFitsAt320dpWithLargeText() {
        narrow(rtl = false, fontScale = 1.3f) { NavPill(Tab.Subscriptions, {}) }
        compose.waitForIdle()
        assertFits("Subscriptions")
        Screenshots.save(context, "05-nav-pill-320dp-font1.3-en", compose.onRoot().captureToImage().asAndroidBitmap())
    }

    @Test fun navPillArabicSubscriptionsFitsAt320dpWithLargeText() {
        Loc.choose("ar")
        narrow(rtl = true, fontScale = 1.3f) { NavPill(Tab.Subscriptions, {}) }
        compose.waitForIdle()
        assertFits(Loc.t("Subscriptions"))
        Screenshots.save(context, "06-nav-pill-320dp-font1.3-ar", compose.onRoot().captureToImage().asAndroidBitmap())
        Loc.choose("en")
    }

    @Test fun inAppBrandTileAndHeader() {
        narrow(rtl = false, fontScale = 1f) {
            Column {
                ScreenHeader(title = "Shinobi", brandMark = true) {}
                Spacer(Modifier.height(12.dp))
                Text("Header tile at 38 dp, mark centred on its bounding box", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                BrandTile(size = 96.dp)
            }
        }
        compose.waitForIdle()
        Screenshots.save(context, "07-in-app-brand-tile", compose.onRoot().captureToImage().asAndroidBitmap())
    }

    /**
     * The launcher icon as Android composes it from the adaptive layers: under the device's
     * own mask, then under a circle and a rounded square (the two common launcher shapes),
     * with the icon's safe zone (66 dp of 108) drawn as a thin ring so the margins are
     * visible in the picture.
     */
    @Test fun launcherIconUnderTheCommonMasks() {
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue("launcher icon is not adaptive: ${icon.javaClass}", icon is AdaptiveIconDrawable)
        val size = 384
        val gap = 48
        val out = Bitmap.createBitmap(size * 3 + gap * 4, size + gap * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.parseColor("#F3F4F8"))
        val masks = listOf<(RectF) -> Path?>(
            { null },
            { r -> Path().apply { addOval(r, Path.Direction.CW) } },
            { r -> Path().apply { addRoundRect(r, r.width() * 0.22f, r.height() * 0.22f, Path.Direction.CW) } },
        )
        masks.forEachIndexed { i, mask ->
            val left = gap + i * (size + gap)
            val rect = RectF(left.toFloat(), gap.toFloat(), (left + size).toFloat(), (gap + size).toFloat())
            canvas.save()
            mask(rect)?.let { canvas.clipPath(it) }
            icon.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
            icon.draw(canvas)
            canvas.restore()
            // Safe zone: a 66/72 circle of the visible 72 dp mask.
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(140, 230, 60, 60) }
            val radius = size / 2f * (66f / 72f)
            canvas.drawCircle(rect.centerX(), rect.centerY(), radius, ring)
        }
        Screenshots.save(context, "08-launcher-icon-masks", out)
    }
}
