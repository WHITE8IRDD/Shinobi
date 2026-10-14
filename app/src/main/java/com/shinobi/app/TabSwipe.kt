package com.shinobi.app

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign

/**
 * Swiping sideways across a page moves to the neighbouring tab, the way the YouTube app's
 * pages page. It never fights vertical scrolling: the gesture only engages once the finger
 * has travelled sideways past touch slop with the sideways travel dominating, and a list
 * that has already started scrolling vertically has consumed the pointer, which cancels it.
 * Rows that scroll horizontally (chips, shelves) sit below this modifier in the tree, get
 * the events first and keep their own drags.
 *
 * [onSwipe] gets `true` for a swipe towards the next tab and `false` for the previous one,
 * already corrected for right-to-left layouts, where the next tab lies to the left.
 */
fun Modifier.tabSwipe(enabled: Boolean, rtl: Boolean, onSwipe: (forward: Boolean) -> Unit): Modifier =
    if (!enabled) this else pointerInput(rtl, onSwipe) {
        val distanceToCommit = 72.dp.toPx()
        val speedToCommit = 1_100.dp.toPx() // per second: a quick flick commits from a short travel
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            var travel = 0f
            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                val fromDown = change.position - down.position
                // Mostly vertical so far: leave it to the list (slop detection restarts).
                if (abs(fromDown.y) > abs(fromDown.x)) return@awaitHorizontalTouchSlopOrCancellation
                change.consume()
                travel = over
                tracker.addPointerInputChange(change)
            } ?: return@awaitEachGesture
            val dragged = horizontalDrag(start.id) { change ->
                travel += change.positionChange().x
                tracker.addPointerInputChange(change)
                change.consume()
            }
            if (!dragged) return@awaitEachGesture
            val speed = tracker.calculateVelocity().x
            val committed = abs(travel) >= distanceToCommit ||
                (abs(speed) >= speedToCommit && sign(speed) == sign(travel) && abs(travel) >= distanceToCommit / 3f)
            if (!committed || travel == 0f) return@awaitEachGesture
            // Finger moving left reveals what lies to the right: the next tab in LTR, the
            // previous one in RTL.
            val towardsRight = travel < 0f
            onSwipe(if (rtl) !towardsRight else towardsRight)
        }
    }
