package com.shinobi.app

import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Diagnostic, NOT a low-end-device performance certification. Frame timings in an emulator
 * are noisy: report them rather than hiding regressions behind an arbitrary passing limit. */
class PerformanceSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<PlayerRegressionActivity>()

    @Test fun recordOfflineFeedScrollFrameTimesAndMemory() {
        val samples = java.util.Collections.synchronizedList(ArrayList<Long>())
        val thread = HandlerThread("frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            samples.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
        }
        compose.runOnIdle { compose.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        val cards = (0 until 120).map { index ->
            VideoItem("perf-$index", "Offline feed item $index · smooth scrolling test", "Creator $index",
                null, null, null, 600, 100_000, "1 day ago")
        }
        compose.setContent {
            MaterialTheme {
                CollapsingHeaderLayout(header = { ScreenHeader("Offline feed") }) { top ->
                    LazyColumn(Modifier.fillMaxSize().testTag("perf-feed"),
                        contentPadding = PaddingValues(top = top, bottom = 24.dp)) {
                        items(cards, key = { it.id }, contentType = { "video" }) { VideoCard(it, {}) }
                    }
                }
            }
        }
        try {
            repeat(12) { compose.onNodeWithTag("perf-feed").performTouchInput { swipeUp(durationMillis = 250) } }
            repeat(6) { compose.onNodeWithTag("perf-feed").performTouchInput { swipeDown(durationMillis = 250) } }
            compose.waitForIdle()
        } finally {
            compose.runOnIdle { compose.activity.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely(); thread.join(2000)
        }
        val times = synchronized(samples) { samples.toList().sorted() }
        assertTrue("No rendered frames were measured", times.isNotEmpty())
        fun percentile(p: Double) = times[((times.size - 1) * p).toInt()] / 1_000_000.0
        val report = """
            Offline debug-build feed scrolling only; emulator timings are not physical-device certification.
            frames=${times.size}
            medianMs=${percentile(0.5)}
            p95Ms=${percentile(0.95)}
            above16_7ms=${times.count { it > 16_700_000L }}
            above33_3ms=${times.count { it > 33_300_000L }}
            totalPssKb=${Debug.getPss()}
            javaHeapBytes=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()}
        """.trimIndent()
        Screenshots.saveText(compose.activity, "performance-scroll", report)
        println("SHINOBI_PERF " + report.replace('\n', ';'))
    }
}
