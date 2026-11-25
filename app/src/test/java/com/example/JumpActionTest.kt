package com.example

import com.example.automation.LogLevel
import com.example.automation.MacroRunner
import com.example.automation.MacroRuntime
import com.example.data.ClickAction
import com.example.data.JumpAction
import com.example.data.JumpMode
import com.example.data.LoopAction
import com.example.data.Macro
import com.example.data.MacroBundleCodec
import com.example.data.RecordedGesture
import com.example.data.Region
import com.example.data.TextAction
import com.example.data.TextCompareMode
import com.example.data.cloneWithRemappedIds
import com.example.vision.Frame
import com.example.vision.OcrBlock
import com.example.vision.PreparedTemplate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class JumpActionTest {

    private class FakeRuntime(
        private var textProvider: () -> List<OcrBlock> = { emptyList() }
    ) : MacroRuntime {
        val events = mutableListOf<String>()
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
        override fun captureFrame(): Frame? = null
        override fun samplePixelArgb(x: Int, y: Int): Int? = null
        override suspend fun recognizeText(region: Region?): List<OcrBlock> = textProvider()
        override fun loadTemplate(name: String): PreparedTemplate? = null
        override fun log(level: LogLevel, message: String) {}
        override fun nowMs(): Long = fakeClockMs
    }

    @Test
    fun skipAfterAction_jumpsForwardPastSpecifiedActionFromBranchAndTopLevel() = runTest {
        val rt = FakeRuntime(textProvider = { listOf(OcrBlock("150 000", 10, 10, 100, 40)) })
        val macro = Macro(
            loopCount = 1,
            actions = listOf(
                ClickAction(id = "a1", x = 1, y = 1, delayAfterMs = 0),
                TextAction(
                    id = "a2",
                    compareMode = TextCompareMode.LESS_THAN,
                    targetNumber = 300_000.0,
                    timeoutMs = 0,
                    clickOnFound = false,
                    delayAfterMs = 0,
                    thenActions = listOf(
                        // Skip past a4 -> must skip a3 AND a4, landing on a5!
                        JumpAction(mode = JumpMode.SKIP_AFTER_ACTION, targetActionId = "a4", targetActionLabel = "#4 Klik")
                    )
                ),
                ClickAction(id = "a3", x = 3, y = 3, delayAfterMs = 0),
                ClickAction(id = "a4", x = 4, y = 4, delayAfterMs = 0),
                ClickAction(id = "a5", x = 5, y = 5, delayAfterMs = 0),
                // Top-level skip past last action a7 -> skips a6 and a7 cleanly
                JumpAction(id = "a_skip_end", mode = JumpMode.SKIP_AFTER_ACTION, targetActionId = "a7"),
                ClickAction(id = "a6", x = 6, y = 6, delayAfterMs = 0),
                ClickAction(id = "a7", x = 7, y = 7, delayAfterMs = 0)
            )
        )

        MacroRunner(rt).run(macro)

        assertEquals(listOf("click(1,1)", "click(5,5)"), rt.events)
    }

    @Test
    fun jumpToAction_jumpsBackwardFromElseBranchUntilGoalReached() = runTest {
        var scanCount = 0
        val rt = FakeRuntime(textProvider = {
            scanCount++
            // First 2 scans have low gold (100k, 250k); 3rd scan has 520k (> 400k)
            val txt = when (scanCount) {
                1 -> "100 000"
                2 -> "250 000"
                else -> "520 000"
            }
            listOf(OcrBlock(txt, 10, 10, 120, 40))
        })

        val macro = Macro(
            loopCount = 1,
            actions = listOf(
                ClickAction(id = "start_search", x = 10, y = 10, delayAfterMs = 0),
                ClickAction(id = "check_prep", x = 20, y = 20, delayAfterMs = 0),
                TextAction(
                    id = "check_gold",
                    compareMode = TextCompareMode.GREATER_THAN,
                    targetNumber = 400_000.0,
                    timeoutMs = 0,
                    clickOnFound = false,
                    delayAfterMs = 0,
                    elseActions = listOf(
                        ClickAction(x = 99, y = 99, delayAfterMs = 0), // Click "Next Base"
                        JumpAction(mode = JumpMode.JUMP_TO_ACTION, targetActionId = "check_prep")
                    )
                ),
                ClickAction(id = "attack", x = 50, y = 50, delayAfterMs = 0)
            )
        )

        MacroRunner(rt).run(macro)

        // Note: enhanced OCR runs on miss (so each failed scan calls recognizeText twice),
        // let's assert start_search runs ONCE, attack runs ONCE at the end, and check_prep loops back!
        assertEquals("click(10,10)", rt.events.first())
        assertEquals("click(50,50)", rt.events.last())
        assertTrue(rt.events.count { it == "click(10,10)" } == 1)
        assertTrue(rt.events.count { it == "click(20,20)" } >= 2)
        assertTrue(rt.events.contains("click(99,99)"))
    }

    @Test
    fun relativeForwardAndBackwardStepJumpsWorkAtRootAndInsideBranches() = runTest {
        var checkRuns = 0
        val rt = FakeRuntime(textProvider = {
            checkRuns++
            val txt = if (checkRuns <= 2) "WAIT" else "READY"
            listOf(OcrBlock(txt, 10, 10, 100, 40))
        })

        val macro = Macro(
            loopCount = 1,
            actions = listOf(
                ClickAction(id = "s1", x = 1, y = 1, delayAfterMs = 0),
                ClickAction(id = "s2", x = 2, y = 2, delayAfterMs = 0),
                TextAction(
                    id = "s3",
                    compareMode = TextCompareMode.CONTAINS,
                    query = "READY",
                    timeoutMs = 0,
                    clickOnFound = false,
                    delayAfterMs = 0,
                    thenActions = listOf(
                        // Skip +2 actions after s3 (skips s4 & s5, lands on s6)
                        JumpAction(mode = JumpMode.SKIP_FORWARD_STEPS, stepCount = 2)
                    ),
                    elseActions = listOf(
                        // Jump back -1 action from s3 (lands on s2)
                        JumpAction(mode = JumpMode.JUMP_BACKWARD_STEPS, stepCount = 1)
                    )
                ),
                ClickAction(id = "s4", x = 4, y = 4, delayAfterMs = 0),
                ClickAction(id = "s5", x = 5, y = 5, delayAfterMs = 0),
                ClickAction(id = "s6", x = 6, y = 6, delayAfterMs = 0)
            )
        )

        MacroRunner(rt).run(macro)

        // s1 runs once -> s2 -> s3 fails -> jumps back -1 to s2 -> s3 succeeds -> skips +2 (s4, s5) -> s6!
        assertEquals(listOf("click(1,1)", "click(2,2)", "click(2,2)", "click(6,6)"), rt.events)
    }

    @Test
    fun jumpFromInsideLoopToRootTargetBreaksLoopAndLandsAccurately() = runTest {
        val rt = FakeRuntime()
        val macro = Macro(
            loopCount = 1,
            actions = listOf(
                ClickAction(id = "r1", x = 1, y = 1, delayAfterMs = 0),
                LoopAction(
                    id = "r2_loop",
                    count = 5,
                    actions = listOf(
                        ClickAction(id = "l1", x = 10, y = 10, delayAfterMs = 0),
                        JumpAction(mode = JumpMode.JUMP_TO_ACTION, targetActionId = "r4"),
                        ClickAction(id = "l2", x = 11, y = 11, delayAfterMs = 0)
                    )
                ),
                ClickAction(id = "r3", x = 3, y = 3, delayAfterMs = 0),
                ClickAction(id = "r4", x = 4, y = 4, delayAfterMs = 0)
            )
        )

        MacroRunner(rt).run(macro)

        assertEquals(listOf("click(1,1)", "click(10,10)", "click(4,4)"), rt.events)
    }

    @Test
    fun cloneAndZipBundleImportRemapJumpTargetIdsAndExecuteIdentically() = runTest {
        val original = Macro(
            id = "m_jump",
            name = "Jump Bundle",
            loopCount = 1,
            actions = listOf(
                ClickAction(id = "orig_1", x = 1, y = 1, delayAfterMs = 0),
                JumpAction(
                    id = "orig_jump",
                    mode = JumpMode.SKIP_AFTER_ACTION,
                    targetActionId = "orig_2",
                    targetActionLabel = "#2 Klik"
                ),
                ClickAction(id = "orig_2", x = 2, y = 2, delayAfterMs = 0),
                ClickAction(id = "orig_3", x = 3, y = 3, delayAfterMs = 0)
            )
        )

        // 1) Direct cloneWithRemappedIds (used by duplicateMacro)
        val clonedList = original.actions.cloneWithRemappedIds()
        val clonedJump = clonedList[1] as JumpAction
        val clonedTarget = clonedList[2]
        assertNotEquals("orig_2", clonedTarget.id)
        assertEquals(clonedTarget.id, clonedJump.targetActionId)

        // 2) Full ZIP export + import round-trip
        val baos = ByteArrayOutputStream()
        MacroBundleCodec.writeMacroZip(
            root = original,
            lookupMacro = { null },
            loadImage = { null },
            output = baos
        )
        val decoded = MacroBundleCodec.readBundle(ByteArrayInputStream(baos.toByteArray()))
        val importedMacro = decoded.macros.single()
        val importedJump = importedMacro.actions[1] as JumpAction
        assertEquals(importedMacro.actions[2].id, importedJump.targetActionId)
        assertNotEquals("orig_2", importedJump.targetActionId)

        // Execute imported macro: must skip orig_2 (click 2,2) and run only click(1,1) and click(3,3)
        val rt = FakeRuntime()
        MacroRunner(rt).run(importedMacro)
        assertEquals(listOf("click(1,1)", "click(3,3)"), rt.events)
    }

    @Test
    fun missingTargetAndSelfJumpRecoverSafelyWithoutFreezing() = runTest {
        val rt = FakeRuntime()
        val macro = Macro(
            loopCount = 1,
            actions = listOf(
                ClickAction(id = "c1", x = 1, y = 1, delayAfterMs = 0),
                JumpAction(id = "j_missing", mode = JumpMode.JUMP_TO_ACTION, targetActionId = "deleted_id"),
                JumpAction(id = "j_self", mode = JumpMode.JUMP_TO_ACTION, targetActionId = "j_self"),
                ClickAction(id = "c2", x = 2, y = 2, delayAfterMs = 0)
            )
        )

        MacroRunner(rt).run(macro)

        assertEquals(listOf("click(1,1)", "click(2,2)"), rt.events)
    }
}
