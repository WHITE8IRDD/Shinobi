package com.shinobi.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class PlaybackBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val target = "com.shinobi.app"

    @Test fun feedScroll() = measure("feed") {
        val feed = device.findObject(By.res("benchmark-feed")) ?: error("Feed not found")
        feed.setGestureMargin(device.displayWidth / 8)
        repeat(6) { feed.fling(Direction.DOWN); device.waitForIdle() }
        repeat(3) { feed.fling(Direction.UP); device.waitForIdle() }
    }

    @Test fun shortsPagingWithLocalDecode() = measure("shorts") {
        val pager = device.findObject(By.res("benchmark-shorts")) ?: error("Shorts not found")
        pager.setGestureMargin(device.displayWidth / 8)
        repeat(6) {
            pager.swipe(Direction.UP, 0.7f)
            check(device.wait(Until.hasObject(By.res("benchmark-ready")), 10_000)) { "New Short did not render" }
        }
    }

    @Test fun fullscreenSeekingWithLocalDecode() = measure("player") {
        repeat(3) {
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            val scrubber = device.wait(Until.findObject(By.res("player-scrubber")), 3000)
                ?: error("Fullscreen scrubber not found")
            val bounds = scrubber.visibleBounds
            for (fraction in listOf(0.05f, 0.5f, 0.95f)) {
                device.click(bounds.left + (bounds.width() * fraction).toInt(), bounds.centerY())
                device.swipe(bounds.left + bounds.width() / 5, bounds.centerY(),
                    bounds.left + bounds.width() * 4 / 5, bounds.centerY(), 25)
            }
        }
    }

    private fun measure(scenario: String, block: androidx.benchmark.macro.MacrobenchmarkScope.() -> Unit) =
        benchmark.measureRepeated(
            packageName = target,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Full(),
            iterations = 3,
            startupMode = StartupMode.WARM,
            setupBlock = {
                startActivityAndWait(Intent().setClassName(target, "$target.PerformanceActivity")
                    .putExtra("scenario", scenario).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                check(device.wait(Until.hasObject(By.res("benchmark-ready")), 15_000)) { "$scenario not ready" }
            },
            measureBlock = block,
        )
}
