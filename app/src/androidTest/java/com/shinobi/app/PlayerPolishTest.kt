package com.shinobi.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PlayerPolishTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullscreenSeekBarAndOptionsAreVisibleOnEntry() {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val state = remember { PlayerState(scope) }
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    VideoContent(state, true, {}, {}, Modifier.fillMaxSize())
                }
            }
        }
        compose.onNodeWithTag("player-seek-bar").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Player options").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Fill screen").assertIsDisplayed()
    }

    @Test fun scrubberDragsAndHasAccessibleSeekAction() {
        var sought = -1L
        var preview: Long? = null
        compose.setContent {
            MaterialTheme {
                Scrubber(emptyList(), 20_000, 50_000, 100_000, true, { preview = it }, { sought = it })
            }
        }
        compose.onNodeWithTag("player-seek-bar").performTouchInput {
            swipe(centerLeft, centerRight, 600)
        }
        compose.runOnIdle {
            assertNotNull(preview)
            assertTrue(sought >= 90_000)
        }
        compose.onNodeWithTag("player-seek-bar").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.runOnIdle { assertEquals(50_000L, sought) }
    }

    @Test fun bundledClassifierLoadsAndProducesFiniteScores() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = context.assets.open("nsfw.tflite").use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        buffer.rewind()
        Interpreter(buffer, Interpreter.Options().setNumThreads(1)).use { model ->
            assertArrayEquals(intArrayOf(1, 224, 224, 3), model.getInputTensor(0).shape())
            assertArrayEquals(intArrayOf(1, 2), model.getOutputTensor(0).shape())
            val output = arrayOf(FloatArray(2))
            model.run(ByteBuffer.allocateDirect(224 * 224 * 3 * 4).order(ByteOrder.nativeOrder()), output)
            assertTrue(output[0].all { it.isFinite() && it in 0f..1f })
        }
    }
}
