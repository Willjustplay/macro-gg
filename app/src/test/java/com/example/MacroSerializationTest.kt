package com.example

import com.example.data.ClickAction
import com.example.data.ColorAction
import com.example.data.ImageAction
import com.example.data.LongPressAction
import com.example.data.LoopAction
import com.example.data.Macro
import com.example.data.NotFoundMode
import com.example.data.RecordAction
import com.example.data.RecordedGesture
import com.example.data.RecordedStroke
import com.example.data.Region
import com.example.data.RestartAction
import com.example.data.StopAction
import com.example.data.SwipeAction
import com.example.data.WaitAction
import com.example.data.countActions
import com.example.data.imageNames
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class MacroSerializationTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    @Test
    fun allActionTypes_roundTripThroughJsonPolymorphically() {
        val original = Macro(
            name = "Full Macrorify Flow",
            loopCount = 0,
            loopDelayMs = 450,
            jitterPx = 5,
            randomDelayMs = 120,
            maxDurationMin = 30,
            actions = listOf(
                ClickAction(x = 100, y = 200, repeat = 3, repeatIntervalMs = 80),
                LongPressAction(x = 300, y = 400, durationMs = 900),
                SwipeAction(x1 = 10, y1 = 20, x2 = 30, y2 = 40, durationMs = 250),
                WaitAction(durationMs = 500, randomMs = 150),
                ImageAction(
                    imageName = "tombol_play.png",
                    threshold = 0.88f,
                    timeoutMs = 4000,
                    region = Region(10, 20, 500, 600),
                    clickOnFound = true,
                    offsetX = 4,
                    offsetY = -2,
                    thenActions = listOf(ClickAction(x = 50, y = 60)),
                    elseActions = listOf(RestartAction()),
                    notFound = NotFoundMode.RESTART
                ),
                ColorAction(
                    x = 222, y = 333, color = "#10B981", tolerance = 12,
                    thenActions = listOf(StopAction()),
                    notFound = NotFoundMode.CONTINUE
                ),
                LoopAction(
                    count = 4,
                    actions = listOf(
                        RecordAction(
                            gestures = listOf(
                                RecordedGesture(
                                    delayBeforeMs = 100,
                                    strokes = listOf(RecordedStroke(0, 120, listOf(10, 20, 30, 40)))
                                )
                            ),
                            speed = 1.5f
                        )
                    )
                )
            )
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<Macro>(encoded)

        assertEquals(original, decoded)
        assertEquals(setOf("tombol_play.png"), decoded.imageNames())
        assertEquals(11, decoded.countActions())
    }
}
