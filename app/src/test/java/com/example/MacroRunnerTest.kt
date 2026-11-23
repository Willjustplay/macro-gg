package com.example

import com.example.automation.LogLevel
import com.example.automation.MacroRunner
import com.example.automation.MacroRuntime
import com.example.data.ClickAction
import com.example.data.ColorAction
import com.example.data.ContainerRef
import com.example.data.ImageAction
import com.example.data.LoopAction
import com.example.data.Macro
import com.example.data.NotFoundMode
import com.example.data.RecordAction
import com.example.data.RecordedGesture
import com.example.data.RecordedStroke
import com.example.data.Slot
import com.example.data.StopAction
import com.example.data.insertInto
import com.example.data.removeAction
import com.example.data.updateAction
import com.example.vision.Frame
import com.example.vision.PreparedTemplate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroRunnerTest {

    private class FakeRuntime(
        private var frameProvider: () -> Frame? = { null },
        private val templates: Map<String, PreparedTemplate> = emptyMap(),
        private var pixelArgb: Int? = null
    ) : MacroRuntime {
        val events = mutableListOf<String>()
        var stopped = false
        var fakeClockMs = 1000L

        override fun isStopped(): Boolean = stopped
        override suspend fun waitIfPaused() {}
        override suspend fun sleep(ms: Long) { fakeClockMs += ms }
        override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
            events += "click($x,$y,$durationMs)"
            return true
        }
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
            events += "swipe($x1,$y1->$x2,$y2)"
            return true
        }
        override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float): Boolean {
            events += "rec(${gesture.strokes.size},${speed}x)"
            return true
        }
        override fun captureFrame(): Frame? = frameProvider()
        override fun samplePixelArgb(x: Int, y: Int): Int? = pixelArgb
        override fun loadTemplate(name: String): PreparedTemplate? = templates[name]
        override fun log(level: LogLevel, message: String) {}
        override fun nowMs(): Long = fakeClockMs
    }

    private fun pattern(w: Int, h: Int): Frame {
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (0xFF shl 24) or (((x * 19 + y * 7) and 0xFF) shl 16) or (((x * 3 xor y * 13) and 0xFF) shl 8) or ((x * 11 + y * 5) and 0xFF)
        }
        return Frame(w, h, px)
    }

    @Test
    fun imageAction_foundClicksCenterAndRunsThenBranch() = runTest {
        val screen = Frame(200, 200, IntArray(200 * 200) { 0xFF000000.toInt() })
        val icon = pattern(24, 24)
        for (y in 0 until 24) for (x in 0 until 24) {
            screen.pixels[(80 + y) * 200 + (60 + x)] = icon.pixels[y * 24 + x]
        }
        val rt = FakeRuntime(
            frameProvider = { screen },
            templates = mapOf("play.png" to PreparedTemplate("play.png", icon))
        )
        val macro = Macro(
            loopCount = 1,
            actions = listOf(
                ImageAction(
                    imageName = "play.png",
                    threshold = 0.90f,
                    timeoutMs = 0,
                    clickOnFound = true,
                    thenActions = listOf(ClickAction(x = 10, y = 20, delayAfterMs = 0)),
                    elseActions = listOf(ClickAction(x = 99, y = 99, delayAfterMs = 0)),
                    delayAfterMs = 0
                )
            )
        )

        MacroRunner(rt).run(macro)

        assertEquals(2, rt.events.size)
        // Center of (60..84, 80..104) is (72, 92)
        assertEquals("click(72,92,50)", rt.events[0])
        assertEquals("click(10,20,50)", rt.events[1])
    }

    @Test
    fun imageAction_notFoundRunsElseAndCanStopMacro() = runTest {
        val blankScreen = Frame(100, 100, IntArray(100 * 100) { 0xFF000000.toInt() })
        val icon = pattern(24, 24)
        val rt = FakeRuntime(
            frameProvider = { blankScreen },
            templates = mapOf("missing.png" to PreparedTemplate("missing.png", icon))
        )
        val macro = Macro(
            loopCount = 3,
            actions = listOf(
                ImageAction(
                    imageName = "missing.png",
                    threshold = 0.90f,
                    timeoutMs = 0,
                    elseActions = listOf(ClickAction(x = 5, y = 6, delayAfterMs = 0)),
                    notFound = NotFoundMode.STOP,
                    delayAfterMs = 0
                ),
                ClickAction(x = 99, y = 99, delayAfterMs = 0)
            )
        )

        MacroRunner(rt).run(macro)

        assertEquals(listOf("click(5,6,50)"), rt.events)
    }

    @Test
    fun loopColorAndRecordActions_executeInOrder() = runTest {
        val rt = FakeRuntime(pixelArgb = 0xFF10B981.toInt())
        val macro = Macro(
            loopCount = 1,
            actions = listOf(
                LoopAction(
                    count = 2,
                    actions = listOf(ClickAction(x = 1, y = 2, repeat = 2, repeatIntervalMs = 0, delayAfterMs = 0))
                ),
                ColorAction(
                    x = 50, y = 60, color = "#10B981", tolerance = 5, timeoutMs = 0,
                    clickOnFound = true, delayAfterMs = 0,
                    thenActions = listOf(
                        RecordAction(
                            gestures = listOf(RecordedGesture(0, listOf(RecordedStroke(0, 100, listOf(1, 2, 3, 4))))),
                            speed = 2f,
                            delayAfterMs = 0
                        ),
                        StopAction()
                    )
                ),
                ClickAction(x = 999, y = 999, delayAfterMs = 0)
            )
        )

        MacroRunner(rt).run(macro)

        assertEquals(
            listOf("click(1,2,50)", "click(1,2,50)", "click(1,2,50)", "click(1,2,50)", "click(50,60,50)", "rec(1,2.0x)"),
            rt.events
        )
    }

    @Test
    fun actionTree_insertsUpdatesAndRemovesNestedActions() {
        val rootImg = ImageAction(id = "img1", imageName = "a.png")
        val child = ClickAction(id = "c1", x = 10, y = 20)
        var list = listOf<com.example.data.Action>(rootImg)
        list = list.insertInto(ContainerRef("img1", Slot.THEN), child)
        assertEquals(1, (list[0] as ImageAction).thenActions.size)

        list = list.updateAction("c1") { (it as ClickAction).copy(x = 77) }
        assertEquals(77, ((list[0] as ImageAction).thenActions[0] as ClickAction).x)

        list = list.removeAction("c1")
        assertTrue((list[0] as ImageAction).thenActions.isEmpty())
    }
}
