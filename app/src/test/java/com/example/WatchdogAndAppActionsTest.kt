package com.example

import com.example.automation.LogLevel
import com.example.automation.MacroRunner
import com.example.automation.MacroRuntime
import com.example.data.ClickAction
import com.example.data.ExitAppAction
import com.example.data.ExitAppMode
import com.example.data.ImageAction
import com.example.data.LaunchAppAction
import com.example.data.Macro
import com.example.data.MacroBundleCodec
import com.example.data.NotFoundMode
import com.example.data.RecordedGesture
import com.example.data.RunMacroAction
import com.example.data.StopAction
import com.example.data.StuckRecoveryMode
import com.example.data.WaitAction
import com.example.data.imageNames
import com.example.vision.Frame
import com.example.vision.PreparedTemplate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WatchdogAndAppActionsTest {

    @Test
    fun exitAppAndLaunchAppExecuteCleanly() = runTest {
        val events = mutableListOf<String>()
        val macro = Macro(
            name = "Restart App Test",
            loopCount = 1,
            loopDelayMs = 0,
            actions = listOf(
                ExitAppAction(mode = ExitAppMode.CLOSE_RECENT_AND_HOME, packageName = "com.supercell.clashofclans", delayAfterMs = 0),
                LaunchAppAction(packageName = "com.supercell.clashofclans", appLabel = "Clash of Clans", freshStart = true, delayAfterMs = 0),
                LaunchAppAction(packageName = "", delayAfterMs = 0) // skipped safely with warning
            )
        )

        val runtime = object : MacroRuntime {
            override fun isStopped() = false
            override suspend fun waitIfPaused() {}
            override suspend fun sleep(ms: Long) {}
            override suspend fun click(x: Int, y: Int, durationMs: Long) = true
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
            override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float) = true
            override suspend fun exitApp(mode: ExitAppMode, packageName: String): Boolean {
                events += "EXIT:${mode.name}:$packageName"
                return true
            }
            override suspend fun launchApp(packageName: String, freshStart: Boolean): Boolean {
                events += "LAUNCH:$packageName:$freshStart"
                return true
            }
            override fun captureFrame(): Frame? = null
            override fun samplePixelArgb(x: Int, y: Int): Int? = null
            override fun loadTemplate(name: String): PreparedTemplate? = null
            override fun log(level: LogLevel, message: String) { events += "LOG:$message" }
        }

        MacroRunner(runtime).run(macro)

        assertTrue(events.contains("EXIT:CLOSE_RECENT_AND_HOME:com.supercell.clashofclans"))
        assertTrue(events.contains("LAUNCH:com.supercell.clashofclans:true"))
        assertTrue(events.any { it.startsWith("LOG:") && it.contains("dilewati") })
    }

    @Test
    fun outOfBandWatchdogTriggersWhenMainMacroIsStuckAndRecoversWithoutLoopingRecursively() = runTest {
        var virtualClockMs = 1_000L
        var recovered = false
        val events = mutableListOf<String>()

        // Distinctive 2D 16x16 icon (bright outer border + diagonal + dark inner core) so location is unique
        val iconPx = IntArray(16 * 16) { idx ->
            val x = idx % 16
            val y = idx / 16
            val luma = when {
                x == 0 || x == 15 || y == 0 || y == 15 -> 240
                x in 5..10 && y in 5..10 -> 25
                x > y -> 195
                else -> 110
            }
            (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
        }
        val iconFrame = Frame(16, 16, iconPx)
        val tpl = PreparedTemplate("ok.png", iconFrame)
        val blankScreen = Frame(64, 64, IntArray(64 * 64) { 0xFF101010.toInt() })
        val recoveredScreenPx = IntArray(64 * 64) { 0xFF101010.toInt() }
        for (y in 0 until 16) for (x in 0 until 16) {
            recoveredScreenPx[(20 + y) * 64 + (20 + x)] = iconPx[y * 16 + x]
        }
        val recoveredScreen = Frame(64, 64, recoveredScreenPx)

        val macro = Macro(
            name = "Farm + Watchdog",
            loopCount = 5,
            loopDelayMs = 0,
            autoScaleResolution = false,
            autoRecoverStuckSec = 10,
            stuckRecoveryMode = StuckRecoveryMode.RUN_WATCHDOG_ACTIONS,
            stuckRestartAfter = true,
            actions = listOf(
                ImageAction(
                    imageName = "ok.png",
                    threshold = 0.85f,
                    timeoutMs = 20_000L, // stays polling until watchdog fires at 10s
                    clickOnFound = true,
                    thenActions = listOf(StopAction()),
                    notFound = NotFoundMode.CONTINUE,
                    delayAfterMs = 0
                )
            ),
            stuckActions = listOf(
                ExitAppAction(mode = ExitAppMode.CLOSE_RECENT_AND_HOME, packageName = "com.game.app", delayAfterMs = 0),
                WaitAction(durationMs = 15_000L, delayAfterMs = 0), // longer than 10s! Must NOT re-trigger watchdog recursively!
                LaunchAppAction(packageName = "com.game.app", freshStart = true, delayAfterMs = 0)
            )
        )

        val runtime = object : MacroRuntime {
            override val screenWidth: Int = 64
            override val screenHeight: Int = 64
            override fun isStopped() = false
            override suspend fun waitIfPaused() {}
            override suspend fun sleep(ms: Long) {
                virtualClockMs += ms
            }
            override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
                events += "CLICK:$x,$y"
                return true
            }
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
            override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float) = true
            override suspend fun exitApp(mode: ExitAppMode, packageName: String): Boolean {
                events += "WATCHDOG_EXIT:$packageName"
                return true
            }
            override suspend fun launchApp(packageName: String, freshStart: Boolean): Boolean {
                events += "WATCHDOG_LAUNCH:$packageName"
                recovered = true // Game is now back on screen after restart!
                return true
            }
            override fun captureFrame(): Frame = if (recovered) recoveredScreen else blankScreen
            override fun samplePixelArgb(x: Int, y: Int): Int? = null
            override fun loadTemplate(name: String): PreparedTemplate? = if (name == "ok.png") tpl else null
            override fun log(level: LogLevel, message: String) {}
            override fun nowMs(): Long = virtualClockMs
        }

        MacroRunner(runtime).run(macro)

        assertEquals(
            listOf("WATCHDOG_EXIT:com.game.app", "WATCHDOG_LAUNCH:com.game.app", "CLICK:28,28"),
            events
        )
    }

    @Test
    fun rootWatchdogTriggersDuringLongWaitInsideLinkedSubMacro() = runTest {
        var virtualClockMs = 0L
        var watchdogRuns = 0
        val logs = mutableListOf<String>()

        val subMacro = Macro(
            id = "sub_wait",
            name = "Sub Wait",
            loopCount = 1,
            loopDelayMs = 0,
            actions = listOf(WaitAction(durationMs = 60_000L, delayAfterMs = 0))
        )

        val rootMacro = Macro(
            id = "root_m",
            name = "Root",
            loopCount = 3,
            loopDelayMs = 0,
            autoRecoverStuckSec = 5,
            stuckRecoveryMode = StuckRecoveryMode.RUN_WATCHDOG_ACTIONS,
            stuckRestartAfter = true,
            actions = listOf(
                RunMacroAction(targetMacroId = subMacro.id, targetMacroName = subMacro.name, returnAfter = true, delayAfterMs = 0)
            ),
            stuckActions = listOf(
                ExitAppAction(mode = ExitAppMode.HOME_ONLY, delayAfterMs = 0),
                StopAction()
            )
        )

        val runtime = object : MacroRuntime {
            override fun isStopped() = false
            override suspend fun waitIfPaused() {}
            override suspend fun sleep(ms: Long) {
                virtualClockMs += ms
            }
            override suspend fun click(x: Int, y: Int, durationMs: Long) = true
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
            override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float) = true
            override suspend fun exitApp(mode: ExitAppMode, packageName: String): Boolean {
                watchdogRuns++
                return true
            }
            override fun captureFrame(): Frame? = null
            override fun samplePixelArgb(x: Int, y: Int): Int? = null
            override fun loadTemplate(name: String): PreparedTemplate? = null
            override fun loadMacro(id: String): Macro? = if (id == subMacro.id) subMacro else null
            override fun log(level: LogLevel, message: String) { logs += message }
            override fun nowMs(): Long = virtualClockMs
        }

        MacroRunner(runtime).run(rootMacro)

        assertEquals(1, logs.count { it.contains("Sub-macro 'Sub Wait'") })
        assertEquals(1, watchdogRuns)
        assertTrue("Watchdog must trigger promptly near 5s, not after 60s (clock=${virtualClockMs}ms)", virtualClockMs < 10_000L)
    }

    @Test
    fun zipBundlePreservesStuckActionsAppActionsAndLinkedMacros() {
        val helperSub = Macro(
            id = "helper_1",
            name = "Helper Sub",
            actions = listOf(ClickAction(x = 100, y = 200))
        )
        val root = Macro(
            id = "root_1",
            name = "Root Watchdog",
            autoRecoverStuckSec = 45,
            stuckRecoveryMode = StuckRecoveryMode.RUN_WATCHDOG_ACTIONS,
            stuckRestartAfter = false,
            actions = listOf(ClickAction(x = 500, y = 500)),
            stuckActions = listOf(
                ExitAppAction(mode = ExitAppMode.BACK_THEN_HOME, packageName = "com.test.game", appLabel = "Test Game"),
                LaunchAppAction(packageName = "com.test.game", appLabel = "Test Game", freshStart = true),
                ImageAction(imageName = "watchdog_icon.png"),
                RunMacroAction(targetMacroId = helperSub.id, targetMacroName = helperSub.name)
            )
        )

        assertTrue(root.imageNames().contains("watchdog_icon.png"))

        val out = ByteArrayOutputStream()
        val exportRes = MacroBundleCodec.writeMacroZip(
            root = root,
            lookupMacro = { if (it == helperSub.id) helperSub else null },
            loadImage = { name -> if (name == "watchdog_icon.png") name to byteArrayOf(1, 2, 3) else null },
            output = out
        )
        assertEquals(2, exportRes.macrosExported)
        assertEquals(1, exportRes.imagesExported)

        val decoded = MacroBundleCodec.readBundle(ByteArrayInputStream(out.toByteArray()))
        assertEquals(2, decoded.macros.size)
        val importedRoot = decoded.macros.first { it.name == "Root Watchdog" }
        val importedSub = decoded.macros.first { it.name == "Helper Sub" }

        assertEquals(45, importedRoot.autoRecoverStuckSec)
        assertEquals(StuckRecoveryMode.RUN_WATCHDOG_ACTIONS, importedRoot.stuckRecoveryMode)
        assertEquals(false, importedRoot.stuckRestartAfter)
        assertEquals(4, importedRoot.stuckActions.size)
        assertTrue(importedRoot.stuckActions[0] is ExitAppAction)
        assertTrue(importedRoot.stuckActions[1] is LaunchAppAction)
        val remappedRun = importedRoot.stuckActions[3] as RunMacroAction
        assertEquals("Linked sub-macro inside stuckActions must be remapped to imported ID", importedSub.id, remappedRun.targetMacroId)
    }
}
