package com.example

import com.example.automation.LogLevel
import com.example.automation.MacroRunner
import com.example.automation.MacroRuntime
import com.example.data.ClickAction
import com.example.data.FinishMacroAction
import com.example.data.FinishMacroMode
import com.example.data.ImageAction
import com.example.data.LoopAction
import com.example.data.Macro
import com.example.data.MacroBundleCodec
import com.example.data.NotFoundMode
import com.example.data.RecordedGesture
import com.example.data.RunMacroAction
import com.example.vision.Frame
import com.example.vision.PreparedTemplate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FinishMacroActionTest {

    private class FakeRuntime(
        private var frameProvider: () -> Frame? = { null },
        private val templates: Map<String, PreparedTemplate> = emptyMap(),
        private val macrosById: Map<String, Macro> = emptyMap()
    ) : MacroRuntime {
        val events = mutableListOf<String>()
        val switchedTo = mutableListOf<String>()
        var stopped = false
        var fakeClockMs = 1_000L

        override val screenWidth: Int = 1080
        override val screenHeight: Int = 2400
        override fun isStopped(): Boolean = stopped
        override suspend fun waitIfPaused() {}
        override suspend fun sleep(ms: Long) { fakeClockMs += ms }
        override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
            events += "click($x,$y)"
            return true
        }
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = true
        override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float): Boolean = true
        override fun captureFrame(): Frame? = frameProvider()
        override fun samplePixelArgb(x: Int, y: Int): Int? = null
        override fun loadTemplate(name: String): PreparedTemplate? = templates[name]
        override fun loadMacro(id: String): Macro? = macrosById[id]
        override fun onActiveMacroSwitched(target: Macro) {
            switchedTo += target.id
            events += "switch(${target.id})"
        }
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
    fun passiveMacroInfiniteLoop_finishActionReturnsToActiveMacroWithoutStopping() = runTest {
        val screen = Frame(200, 200, IntArray(200 * 200) { 0xFF000000.toInt() })
        val icon = pattern(24, 24)
        for (y in 0 until 24) for (x in 0 until 24) {
            screen.pixels[(40 + y) * 200 + (40 + x)] = icon.pixels[y * 24 + x]
        }
        var passiveHits = 0
        val rt = FakeRuntime(
            frameProvider = {
                passiveHits++
                if (passiveHits >= 2) screen else Frame(200, 200, IntArray(200 * 200) { 0xFF000000.toInt() })
            },
            templates = mapOf("goal.png" to PreparedTemplate("goal.png", icon)),
            macrosById = mapOf(
                "passive_farm" to Macro(
                    id = "passive_farm",
                    name = "Passive Farm",
                    isPassive = true,
                    passiveEnabled = true,
                    loopCount = 0, // infinite loop inside passive macro!
                    loopDelayMs = 0,
                    actions = listOf(
                        ClickAction(x = 10, y = 10, delayAfterMs = 0),
                        ImageAction(
                            imageName = "goal.png",
                            threshold = 0.85f,
                            timeoutMs = 0,
                            clickOnFound = false,
                            delayAfterMs = 0,
                            thenActions = listOf(
                                ClickAction(x = 20, y = 20, delayAfterMs = 0),
                                FinishMacroAction(mode = FinishMacroMode.RETURN_TO_ACTIVE_CONTINUE)
                            )
                        ),
                        ClickAction(x = 99, y = 99, delayAfterMs = 0)
                    )
                )
            )
        )

        val activeMacro = Macro(
            id = "active_main",
            name = "Active Main",
            loopCount = 1,
            actions = listOf(
                ClickAction(x = 1, y = 1, delayAfterMs = 0),
                RunMacroAction(targetMacroId = "passive_farm", targetMacroName = "Passive Farm", returnAfter = true, delayAfterMs = 0),
                ClickAction(x = 2, y = 2, delayAfterMs = 0)
            )
        )

        MacroRunner(rt).run(activeMacro)

        // Passive loop #1: click(10,10), image miss, click(99,99)
        // Passive loop #2: click(10,10), image hit -> click(20,20) -> FinishMacroAction -> skips (99,99) & breaks infinite loop
        // Active macro resumes immediately and executes click(2,2)!
        assertEquals(
            listOf("click(1,1)", "click(10,10)", "click(99,99)", "click(10,10)", "click(20,20)", "click(2,2)"),
            rt.events
        )
    }

    @Test
    fun passiveMacroCalledWithReturnAfterFalse_stillSwitchesBackToActiveOnFinishAction() = runTest {
        val passive = Macro(
            id = "p1",
            name = "Pasif",
            isPassive = true,
            passiveEnabled = true,
            loopCount = 0,
            actions = listOf(
                ClickAction(x = 50, y = 50, delayAfterMs = 0),
                FinishMacroAction(mode = FinishMacroMode.RETURN_TO_ACTIVE_CONTINUE),
                ClickAction(x = 777, y = 777, delayAfterMs = 0)
            )
        )
        val active = Macro(
            id = "a1",
            name = "Aktif",
            loopCount = 1,
            actions = listOf(
                ClickAction(x = 1, y = 1, delayAfterMs = 0),
                RunMacroAction(targetMacroId = "p1", targetMacroName = "Pasif", returnAfter = false, delayAfterMs = 0),
                ClickAction(x = 2, y = 2, delayAfterMs = 0)
            )
        )
        val rt = FakeRuntime(macrosById = mapOf("p1" to passive, "a1" to active))

        MacroRunner(rt).run(active)

        assertEquals(listOf("click(1,1)", "click(50,50)", "click(2,2)"), rt.events)
    }

    @Test
    fun nestedPassiveMacros_finishUnwindsDirectlyToActiveCaller() = runTest {
        val p2 = Macro(
            id = "p2",
            name = "Pasif 2",
            isPassive = true,
            passiveEnabled = true,
            loopCount = 5,
            actions = listOf(
                LoopAction(
                    count = 10,
                    actions = listOf(
                        ClickAction(x = 30, y = 30, delayAfterMs = 0),
                        FinishMacroAction(mode = FinishMacroMode.RETURN_TO_ACTIVE_CONTINUE),
                        ClickAction(x = 888, y = 888, delayAfterMs = 0)
                    )
                )
            )
        )
        val p1 = Macro(
            id = "p1",
            name = "Pasif 1",
            isPassive = true,
            passiveEnabled = true,
            loopCount = 5,
            actions = listOf(
                ClickAction(x = 20, y = 20, delayAfterMs = 0),
                RunMacroAction(targetMacroId = "p2", targetMacroName = "Pasif 2", returnAfter = true, delayAfterMs = 0),
                ClickAction(x = 999, y = 999, delayAfterMs = 0)
            )
        )
        val active = Macro(
            id = "active",
            name = "Active Root",
            loopCount = 1,
            actions = listOf(
                ClickAction(x = 10, y = 10, delayAfterMs = 0),
                RunMacroAction(targetMacroId = "p1", targetMacroName = "Pasif 1", returnAfter = true, delayAfterMs = 0),
                ClickAction(x = 40, y = 40, delayAfterMs = 0)
            )
        )
        val rt = FakeRuntime(macrosById = mapOf("p1" to p1, "p2" to p2, "active" to active))

        MacroRunner(rt).run(active)

        assertEquals(listOf("click(10,10)", "click(20,20)", "click(30,30)", "click(40,40)"), rt.events)
    }

    @Test
    fun finishActionRestartActiveMode_restartsActiveFromStepOneEvenWithLoopCountOne() = runTest {
        var calls = 0
        val screen = Frame(100, 100, IntArray(100 * 100) { 0xFF000000.toInt() })
        val icon = pattern(20, 20)
        for (y in 0 until 20) for (x in 0 until 20) {
            screen.pixels[(10 + y) * 100 + (10 + x)] = icon.pixels[y * 20 + x]
        }
        val passive = Macro(
            id = "p_restart",
            name = "Pasif",
            isPassive = true,
            passiveEnabled = true,
            loopCount = 1,
            actions = listOf(
                ImageAction(
                    imageName = "once.png",
                    threshold = 0.85f,
                    timeoutMs = 0,
                    clickOnFound = false,
                    delayAfterMs = 0,
                    thenActions = listOf(FinishMacroAction(mode = FinishMacroMode.RETURN_TO_ACTIVE_RESTART))
                )
            )
        )
        val active = Macro(
            id = "a_once",
            name = "Aktif",
            loopCount = 1,
            loopDelayMs = 0,
            actions = listOf(
                ClickAction(x = 1, y = 1, delayAfterMs = 0),
                RunMacroAction(targetMacroId = "p_restart", targetMacroName = "Pasif", returnAfter = true, delayAfterMs = 0),
                ClickAction(x = 2, y = 2, delayAfterMs = 0)
            )
        )
        val rt = FakeRuntime(
            frameProvider = {
                calls++
                if (calls == 1) screen else Frame(100, 100, IntArray(100 * 100) { 0xFF000000.toInt() })
            },
            templates = mapOf("once.png" to PreparedTemplate("once.png", icon)),
            macrosById = mapOf("p_restart" to passive, "a_once" to active)
        )

        MacroRunner(rt).run(active)

        // First pass triggers RETURN_TO_ACTIVE_RESTART -> skips click(2,2) and restarts from click(1,1)
        // Second pass does not trigger -> completes with click(2,2)
        assertEquals(listOf("click(1,1)", "click(1,1)", "click(2,2)"), rt.events)
    }

    @Test
    fun finishActionSwitchToSpecificActiveMacro_handsOffCleanlyWithoutStopping() = runTest {
        val activeB = Macro(
            id = "active_b",
            name = "Aktif B",
            isPassive = false,
            loopCount = 1,
            actions = listOf(
                ClickAction(x = 200, y = 200, delayAfterMs = 0),
                ClickAction(x = 201, y = 201, delayAfterMs = 0)
            )
        )
        val passive = Macro(
            id = "passive_a",
            name = "Pasif A",
            isPassive = true,
            passiveEnabled = true,
            loopCount = 0,
            actions = listOf(
                ClickAction(x = 100, y = 100, delayAfterMs = 0),
                FinishMacroAction(
                    mode = FinishMacroMode.SWITCH_TO_SPECIFIC_ACTIVE,
                    targetMacroId = "active_b",
                    targetMacroName = "Aktif B"
                ),
                ClickAction(x = 999, y = 999, delayAfterMs = 0)
            )
        )
        val activeA = Macro(
            id = "active_a",
            name = "Aktif A",
            isPassive = false,
            loopCount = 1,
            actions = listOf(
                ClickAction(x = 10, y = 10, delayAfterMs = 0),
                RunMacroAction(targetMacroId = "passive_a", targetMacroName = "Pasif A", returnAfter = true, delayAfterMs = 0),
                ClickAction(x = 555, y = 555, delayAfterMs = 0)
            )
        )
        val rt = FakeRuntime(macrosById = mapOf("active_a" to activeA, "passive_a" to passive, "active_b" to activeB))

        MacroRunner(rt).run(activeA)

        assertEquals(listOf("active_b"), rt.switchedTo)
        assertEquals(
            listOf("click(10,10)", "click(100,100)", "switch(active_b)", "click(200,200)", "click(201,201)"),
            rt.events
        )
    }

    @Test
    fun branchNotFoundModeFinish_exitsPassiveMacroAndContinuesActiveMacro() = runTest {
        val screen = Frame(100, 100, IntArray(100 * 100) { 0xFF000000.toInt() })
        val icon = pattern(20, 20)
        for (y in 0 until 20) for (x in 0 until 20) {
            screen.pixels[(15 + y) * 100 + (15 + x)] = icon.pixels[y * 20 + x]
        }
        val passive = Macro(
            id = "p_branch",
            name = "Pasif Branch",
            isPassive = true,
            passiveEnabled = true,
            loopCount = 0,
            actions = listOf(
                ImageAction(
                    imageName = "done.png",
                    threshold = 0.85f,
                    timeoutMs = 0,
                    clickOnFound = false,
                    foundMode = NotFoundMode.FINISH,
                    delayAfterMs = 0
                ),
                ClickAction(x = 888, y = 888, delayAfterMs = 0)
            )
        )
        val active = Macro(
            id = "a_branch",
            name = "Aktif Branch",
            loopCount = 1,
            actions = listOf(
                ClickAction(x = 5, y = 5, delayAfterMs = 0),
                RunMacroAction(targetMacroId = "p_branch", targetMacroName = "Pasif Branch", returnAfter = true, delayAfterMs = 0),
                ClickAction(x = 6, y = 6, delayAfterMs = 0)
            )
        )
        val rt = FakeRuntime(
            frameProvider = { screen },
            templates = mapOf("done.png" to PreparedTemplate("done.png", icon)),
            macrosById = mapOf("p_branch" to passive, "a_branch" to active)
        )

        MacroRunner(rt).run(active)

        assertEquals(listOf("click(5,5)", "click(6,6)"), rt.events)
    }

    @Test
    fun bundleExportImport_roundTripsFinishMacroActionAndRemapsTargetId() {
        val activeB = Macro(
            id = "old_b",
            name = "Aktif B",
            actions = listOf(ClickAction(x = 12, y = 34))
        )
        val passive = Macro(
            id = "old_p",
            name = "Pasif",
            isPassive = true,
            actions = listOf(
                FinishMacroAction(
                    mode = FinishMacroMode.SWITCH_TO_SPECIFIC_ACTIVE,
                    targetMacroId = "old_b",
                    targetMacroName = "Aktif B"
                )
            )
        )
        val activeA = Macro(
            id = "old_a",
            name = "Aktif A",
            actions = listOf(RunMacroAction(targetMacroId = "old_p", targetMacroName = "Pasif"))
        )
        val byId = mapOf(activeA.id to activeA, passive.id to passive, activeB.id to activeB)

        val baos = ByteArrayOutputStream()
        val exp = MacroBundleCodec.writeMacroZip(
            root = activeA,
            lookupMacro = { byId[it] },
            loadImage = { null },
            output = baos
        )
        assertEquals(3, exp.macrosExported)

        var seq = 0
        val decoded = MacroBundleCodec.readBundle(
            input = ByteArrayInputStream(baos.toByteArray()),
            idGenerator = { "new_${++seq}" }
        )
        assertEquals(3, decoded.macros.size)
        val newPassive = decoded.macros.first { it.name == "Pasif" }
        val newActiveB = decoded.macros.first { it.name == "Aktif B" }
        val finishAct = newPassive.actions.first() as FinishMacroAction
        assertEquals(FinishMacroMode.SWITCH_TO_SPECIFIC_ACTIVE, finishAct.mode)
        assertEquals(newActiveB.id, finishAct.targetMacroId)
        assertTrue(finishAct.targetMacroId.startsWith("new_"))
    }
}
