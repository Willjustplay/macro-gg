package com.example

import com.example.automation.HumanizerEngine
import com.example.automation.LogLevel
import com.example.automation.MacroRunner
import com.example.automation.MacroRuntime
import com.example.automation.VisualDebugEvent
import com.example.data.AccountProfile
import com.example.data.AccountSwitchConfig
import com.example.data.AccountSwitchMethod
import com.example.data.BannedImageMode
import com.example.data.BranchConditionType
import com.example.data.BranchOutcomeAction
import com.example.data.BranchRouteRule
import com.example.data.BranchRouterAction
import com.example.data.CheckVariableAction
import com.example.data.ClickAction
import com.example.data.ColorAction
import com.example.data.HumanizerConfig
import com.example.data.HumanizerPreset
import com.example.data.ImageAction
import com.example.data.Macro
import com.example.data.MacroBranch
import com.example.data.NotFoundMode
import com.example.data.RecordedGesture
import com.example.data.Region
import com.example.data.StopAction
import com.example.data.StuckRecoveryMode
import com.example.data.SwipeAction
import com.example.data.SwitchAccountAction
import com.example.data.TextAction
import com.example.data.TextCompareMode
import com.example.data.VariableAction
import com.example.data.VariableCompareOp
import com.example.data.VariableOperation
import com.example.data.VariableScope
import com.example.data.WaitAction
import com.example.vision.Frame
import com.example.vision.OcrBlock
import com.example.vision.PreparedTemplate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class HumanizerAndVariablesTest {

    private val testJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    private class FakeRuntime(
        var visualDebug: Boolean = false,
        var frameProvider: () -> Frame? = { null },
        var pixelArgb: Int? = 0xFF00FF00.toInt(),
        var ocrBlocksProvider: (Region?, Boolean) -> List<OcrBlock> = { _, _ -> emptyList() },
        val templates: MutableMap<String, PreparedTemplate> = HashMap(),
        override val screenWidth: Int = 1080,
        override val screenHeight: Int = 2400
    ) : MacroRuntime {

        var virtualNowMs: Long = 1_000_000L
        var stopped: Boolean = false
        val clicks = mutableListOf<Triple<Int, Int, Long>>()
        val straightSwipes = mutableListOf<List<Int>>()
        val curvedSwipes = mutableListOf<List<Int>>()
        val debugEvents = mutableListOf<VisualDebugEvent>()
        val variableSnapshots = mutableListOf<Map<String, Double>>()
        val humanBreakUpdates = mutableListOf<Int>()
        val logs = mutableListOf<Pair<LogLevel, String>>()

        override fun isStopped(): Boolean = stopped
        override suspend fun waitIfPaused() {}
        override suspend fun sleep(ms: Long) {
            virtualNowMs += ms.coerceAtLeast(0L)
        }

        override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
            clicks += Triple(x, y, durationMs)
            return true
        }

        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
            straightSwipes += listOf(x1, y1, x2, y2, durationMs.toInt())
            return true
        }

        override suspend fun swipeCurved(
            x1: Int,
            y1: Int,
            cx: Int,
            cy: Int,
            x2: Int,
            y2: Int,
            durationMs: Long
        ): Boolean {
            curvedSwipes += listOf(x1, y1, cx, cy, x2, y2, durationMs.toInt())
            return true
        }

        override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float): Boolean = true
        override fun captureFrame(): Frame? = frameProvider()
        override fun samplePixelArgb(x: Int, y: Int): Int? = pixelArgb
        override suspend fun recognizeText(region: Region?, enhanced: Boolean): List<OcrBlock> =
            ocrBlocksProvider(region, enhanced)
        override fun loadTemplate(name: String): PreparedTemplate? = templates[name]
        override fun isVisualDebugEnabled(): Boolean = visualDebug
        override fun onVisualDebug(event: VisualDebugEvent) {
            debugEvents += event
        }
        override fun onVariablesChanged(variables: Map<String, Double>) {
            variableSnapshots += variables.toMap()
        }
        override fun onHumanBreakChanged(remainingSec: Int) {
            humanBreakUpdates += remainingSec
        }
        override fun log(level: LogLevel, message: String) {
            logs += level to message
        }
        override fun nowMs(): Long = virtualNowMs
    }

    private fun solidFrame(w: Int, h: Int, argb: Int): Frame =
        Frame(w, h, IntArray(w * h) { argb })

    private fun patternedTemplate(name: String, w: Int, h: Int): PreparedTemplate {
        val pixels = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            val r = (x * 17) and 0xFF
            val g = (y * 23) and 0xFF
            val b = ((x + y) * 13) and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return PreparedTemplate(name, Frame(w, h, pixels))
    }

    private fun stampTemplate(bg: Frame, tpl: PreparedTemplate, ox: Int, oy: Int): Frame {
        val out = bg.pixels.copyOf()
        val src = tpl.colorFrame!!.pixels
        for (y in 0 until tpl.height) {
            for (x in 0 until tpl.width) {
                val tx = ox + x
                val ty = oy + y
                if (tx in 0 until bg.width && ty in 0 until bg.height) {
                    out[ty * bg.width + tx] = src[y * tpl.width + x]
                }
            }
        }
        return Frame(bg.width, bg.height, out)
    }

    @Test
    fun humanizerGaussianClicksClusterNearCenterAndVaryHoldDuration() {
        val engine = HumanizerEngine(Random(42))
        val cfg = HumanizerConfig(preset = HumanizerPreset.NATURAL)
        var withinHalfRadius = 0
        val durations = HashSet<Long>()

        repeat(200) {
            val c = engine.humanizeClick(
                x = 540,
                y = 1200,
                baseDurationMs = 50L,
                screenW = 1080,
                screenH = 2400,
                legacyJitterPx = 0,
                rawConfig = cfg
            )
            assertTrue("Click must be flagged humanized", c.humanized)
            assertTrue("X offset within ±7px", abs(c.x - 540) <= 7)
            assertTrue("Y offset within ±7px", abs(c.y - 1200) <= 7)
            assertTrue("Tap hold within 45..95ms", c.durationMs in 45L..95L)
            if (abs(c.x - 540) <= 3 && abs(c.y - 1200) <= 3) withinHalfRadius++
            durations += c.durationMs
        }

        // Gaussian distribution clusters significantly more samples near the center than uniform (uniform would be ~(7/15)^2 ≈ 21%)
        assertTrue("Expected Gaussian clustering near center, got $withinHalfRadius/200", withinHalfRadius >= 85)
        assertTrue("Expected varied tap durations, got ${durations.size} distinct values", durations.size >= 15)

        // OFF preset keeps exact coordinates & duration when legacy jitter is 0
        val offClick = engine.humanizeClick(540, 1200, 55L, 1080, 2400, 0, HumanizerConfig(preset = HumanizerPreset.OFF))
        assertFalse(offClick.humanized)
        assertEquals(540, offClick.x)
        assertEquals(1200, offClick.y)
        assertEquals(55L, offClick.durationMs)
    }

    @Test
    fun humanizerBezierSwipesUseCurvedControlPointWhileOffUsesStraightSwipe() = runBlocking {
        val rtCurved = FakeRuntime()
        val curvedMacro = Macro(
            name = "Curved",
            loopCount = 1,
            loopDelayMs = 0L,
            humanizer = HumanizerConfig(preset = HumanizerPreset.STEALTH),
            actions = listOf(SwipeAction(x1 = 540, y1 = 1800, x2 = 540, y2 = 600, durationMs = 400L, delayAfterMs = 0L))
        )
        MacroRunner(rtCurved, Random(123)).run(curvedMacro)

        assertEquals(1, rtCurved.curvedSwipes.size)
        assertEquals(0, rtCurved.straightSwipes.size)
        val sw = rtCurved.curvedSwipes.first()
        val x1 = sw[0]
        val cx = sw[2]
        val x2 = sw[4]
        val straightMidX = (x1 + x2) / 2
        assertTrue("Bezier control point cx=$cx should deflect perpendicular from straightMidX=$straightMidX", abs(cx - straightMidX) >= 30)

        val rtOff = FakeRuntime()
        val offMacro = curvedMacro.copy(humanizer = HumanizerConfig(preset = HumanizerPreset.OFF))
        MacroRunner(rtOff, Random(123)).run(offMacro)
        assertEquals(0, rtOff.curvedSwipes.size)
        assertEquals(1, rtOff.straightSwipes.size)
    }

    @Test
    fun humanBreakTriggersCountdownAndDoesNotTriggerWatchdog() = runBlocking {
        val rt = FakeRuntime()
        var watchdogFired = false
        val macro = Macro(
            name = "BreakSafe",
            loopCount = 3,
            loopDelayMs = 0L,
            autoRecoverStuckSec = 5, // Shorter than the 10s Human Break to verify markProgress() protects it!
            stuckRecoveryMode = StuckRecoveryMode.RUN_WATCHDOG_ACTIONS,
            stuckActions = listOf(ClickAction(x = 999, y = 999, delayAfterMs = 0L)),
            humanizer = HumanizerConfig(
                preset = HumanizerPreset.CUSTOM,
                gaussianClickRadiusPx = 0,
                bezierSwipeEnabled = false,
                delayVariancePercent = 0,
                microHesitationChancePercent = 0,
                humanBreakEnabled = true,
                breakEveryMinMinutes = 1,
                breakEveryMaxMinutes = 1,
                breakDurationMinSec = 10,
                breakDurationMaxSec = 10
            ),
            actions = listOf(
                ClickAction(x = 200, y = 300, delayAfterMs = 0L),
                WaitAction(durationMs = 35_000L, delayAfterMs = 0L) // Loop 1 -> +35s, Loop 2 -> +70s >= 60s triggers break on Loop 3!
            )
        )

        // Note: WaitAction 35s itself would trigger 5s watchdog unless we use short clicks that advance virtual time;
        // instead use 25 short 3s clicks so progress updates every 3s (< 5s watchdog), crossing 60s cleanly!
        val actions75s = List(25) { ClickAction(x = 200, y = 300, delayAfterMs = 3_000L) }
        val safeMacro = macro.copy(loopCount = 1, actions = actions75s)

        MacroRunner(rt, Random(7)).run(safeMacro)

        watchdogFired = rt.clicks.any { it.first == 999 && it.second == 999 }
        assertFalse("Watchdog must NOT misfire during Human Break!", watchdogFired)
        assertTrue("Expected Human Break positive countdown updates, got ${rt.humanBreakUpdates}", rt.humanBreakUpdates.any { it in 1..10 })
        assertEquals("Human Break must clear to 0 after completion", 0, rt.humanBreakUpdates.last())
        assertTrue("All 25 clicks completed", rt.clicks.size == 25)
    }

    @Test
    fun visualVariablesMathOcrCheckAndBranchRouterWorkEndToEnd() = runBlocking {
        val rt = FakeRuntime(
            ocrBlocksProvider = { _, _ ->
                listOf(OcrBlock("Gold: 1.5M", 100, 100, 300, 140))
            }
        )

        val bonusBranch = MacroBranch(
            id = "branch_bonus",
            name = "Bonus",
            actions = listOf(
                ClickAction(x = 777, y = 888, delayAfterMs = 0L),
                StopAction()
            )
        )

        val macro = Macro(
            name = "VarsTest",
            loopCount = 5,
            loopDelayMs = 0L,
            branches = listOf(bonusBranch),
            actions = listOf(
                // Read OCR number "1.5M" into variable 'gold'
                VariableAction(
                    variableName = "gold",
                    operation = VariableOperation.READ_OCR_NUMBER,
                    ocrTimeoutMs = 200L,
                    delayAfterMs = 0L
                ),
                // Increment 'runs' by 1 each loop
                VariableAction(
                    variableName = "runs",
                    operation = VariableOperation.ADD,
                    value = 1.0,
                    delayAfterMs = 0L
                ),
                // Verify gold >= 1_000_000 -> increment 'rich_hits'
                CheckVariableAction(
                    variableName = "gold",
                    operator = VariableCompareOp.GREATER_OR_EQUAL,
                    targetValue = 1_000_000.0,
                    thenActions = listOf(
                        VariableAction(variableName = "rich_hits", operation = VariableOperation.ADD, value = 1.0, delayAfterMs = 0L)
                    ),
                    delayAfterMs = 0L
                ),
                // Route via BranchRouter when runs >= 3
                BranchRouterAction(
                    timeoutMs = 100L,
                    rules = listOf(
                        BranchRouteRule(
                            label = "3x selesai",
                            conditionType = BranchConditionType.VARIABLE,
                            variableName = "runs",
                            variableOp = VariableCompareOp.GREATER_OR_EQUAL,
                            variableTargetValue = 3.0,
                            outcome = BranchOutcomeAction.SWITCH_BRANCH,
                            targetBranchId = "branch_bonus"
                        )
                    ),
                    fallbackOutcome = BranchOutcomeAction.CONTINUE,
                    delayAfterMs = 0L
                )
            )
        )

        MacroRunner(rt, Random(1)).run(macro)

        // Should route to branch_bonus on loop #3 and click (777, 888)
        assertEquals(1, rt.clicks.size)
        assertEquals(777, rt.clicks.first().first)
        assertEquals(888, rt.clicks.first().second)

        val peakSnapshot = rt.variableSnapshots.last { it.isNotEmpty() }
        assertEquals(1_500_000.0, peakSnapshot["gold"] ?: 0.0, 0.001)
        assertEquals(3.0, peakSnapshot["runs"] ?: 0.0, 0.001)
        assertEquals(3.0, peakSnapshot["rich_hits"] ?: 0.0, 0.001)
    }

    @Test
    fun perAccountVariablesStayIsolatedAcrossMultiAccountSwitches() = runBlocking {
        val rt = FakeRuntime()
        val acc1 = AccountProfile(id = "a1", label = "Akun 1", packageName = "com.game.a1", enabled = true)
        val acc2 = AccountProfile(id = "a2", label = "Akun 2", packageName = "com.game.a2", enabled = true)

        val macro = Macro(
            name = "PerAccVars",
            loopCount = 2,
            loopDelayMs = 0L,
            accountSwitch = AccountSwitchConfig(
                enabled = true,
                method = AccountSwitchMethod.CLONE_APP,
                triggerMode = com.example.data.AccountTriggerMode.MANUAL_ACTION_ONLY,
                accounts = listOf(acc1, acc2),
                switchActions = listOf(ClickAction(x = 10, y = 10, delayAfterMs = 0L))
            ),
            actions = listOf(
                // Increment session var 'total' (+1 each loop) and per-account var 'hits' (+1 for active account)
                VariableAction(variableName = "total", operation = VariableOperation.ADD, value = 1.0, scope = VariableScope.SESSION, delayAfterMs = 0L),
                VariableAction(variableName = "hits", operation = VariableOperation.ADD, value = 1.0, scope = VariableScope.PER_ACCOUNT, delayAfterMs = 0L),
                // Check hits == 1 -> click (111, 222) — MUST succeed for BOTH Akun 1 AND Akun 2 because hits is per-account!
                CheckVariableAction(
                    variableName = "hits",
                    operator = VariableCompareOp.EQUALS,
                    targetValue = 1.0,
                    scope = VariableScope.PER_ACCOUNT,
                    thenActions = listOf(ClickAction(x = 111, y = 222, delayAfterMs = 0L)),
                    delayAfterMs = 0L
                ),
                SwitchAccountAction(restartAfterSwitch = false, delayAfterMs = 0L)
            )
        )

        MacroRunner(rt, Random(5)).run(macro)

        val matchedClicks = rt.clicks.count { it.first == 111 && it.second == 222 }
        assertEquals("Both accounts should see their own hits==1 independently", 2, matchedClicks)
        // Session variable 'total' reached 2 across both accounts
        assertTrue(rt.variableSnapshots.any { (it["total"] ?: 0.0) == 2.0 && (it["hits"] ?: 0.0) == 1.0 })
    }

    @Test
    fun liveVisualDebuggerEmitsRichEventsOnlyWhenEnabled() = runBlocking {
        val targetTpl = patternedTemplate("btn.png", 24, 24)
        val bannedPixels = IntArray(20 * 20) { idx ->
            val x = idx % 20
            val y = idx / 20
            if (((x / 3) + (y / 3)) % 2 == 0) 0xFFFF2244.toInt() else 0xFF101010.toInt()
        }
        val bannedTpl = PreparedTemplate("danger.png", Frame(20, 20, bannedPixels))
        val missingPixels = IntArray(20 * 20) { idx ->
            val y = idx / 20
            if ((y / 4) % 2 == 0) 0xFF00FFAA.toInt() else 0xFF220044.toInt()
        }
        val missingTpl = PreparedTemplate("missing.png", Frame(20, 20, missingPixels))

        val baseFrame = solidFrame(200, 200, 0xFF102030.toInt())
        val frameWithTargetAndBanned = stampTemplate(stampTemplate(baseFrame, targetTpl, 30, 30), bannedTpl, 120, 120)

        val macro = Macro(
            name = "DebugCheck",
            loopCount = 1,
            loopDelayMs = 0L,
            humanizer = HumanizerConfig(preset = HumanizerPreset.NATURAL),
            bannedGuardEnabled = true,
            bannedImages = listOf("danger.png"),
            bannedMode = BannedImageMode.AVOID_MATCH_AREA,
            bannedAvoidRadiusPx = 15,
            actions = listOf(
                ImageAction(imageName = "btn.png", threshold = 0.80f, timeoutMs = 100L, clickOnFound = true, delayAfterMs = 0L),
                ImageAction(imageName = "missing.png", threshold = 0.90f, timeoutMs = 0L, clickOnFound = false, delayAfterMs = 0L),
                TextAction(query = "READY", compareMode = TextCompareMode.CONTAINS, timeoutMs = 0L, clickOnFound = false, delayAfterMs = 0L),
                ColorAction(x = 10, y = 10, color = "#00FF00", tolerance = 10, timeoutMs = 0L, clickOnFound = false, delayAfterMs = 0L),
                SwipeAction(x1 = 40, y1 = 160, x2 = 160, y2 = 40, durationMs = 200L, delayAfterMs = 0L),
                // Click directly onto banned image center (130, 130)
                ClickAction(x = 130, y = 130, delayAfterMs = 0L)
            )
        )

        // 1. Visual Debugger OFF -> zero events emitted
        val rtOff = FakeRuntime(
            visualDebug = false,
            frameProvider = { frameWithTargetAndBanned },
            ocrBlocksProvider = { _, _ -> listOf(OcrBlock("READY", 20, 20, 90, 50)) },
            screenWidth = 200,
            screenHeight = 200
        ).apply {
            templates["btn.png"] = targetTpl
            templates["missing.png"] = missingTpl
            templates["danger.png"] = bannedTpl
        }
        MacroRunner(rtOff, Random(9)).run(macro)
        assertTrue("No debug events may be emitted when visualDebug == false", rtOff.debugEvents.isEmpty())

        // 2. Visual Debugger ON -> emits ImageCheck (hit & miss), TapPoint, OcrCheck, ColorCheck, SwipePath, and BannedBlocked!
        val rtOn = FakeRuntime(
            visualDebug = true,
            frameProvider = { frameWithTargetAndBanned },
            ocrBlocksProvider = { _, _ -> listOf(OcrBlock("READY", 20, 20, 90, 50)) },
            screenWidth = 200,
            screenHeight = 200
        ).apply {
            templates["btn.png"] = targetTpl
            templates["missing.png"] = missingTpl
            templates["danger.png"] = bannedTpl
        }
        MacroRunner(rtOn, Random(9)).run(macro)

        assertTrue("Expected ImageCheck found=true", rtOn.debugEvents.any { it is VisualDebugEvent.ImageCheck && it.found })
        assertTrue("Expected ImageCheck found=false", rtOn.debugEvents.any { it is VisualDebugEvent.ImageCheck && !it.found })
        assertTrue("Expected OcrCheck matched=true", rtOn.debugEvents.any { it is VisualDebugEvent.OcrCheck && it.matched })
        assertTrue("Expected ColorCheck matched=true", rtOn.debugEvents.any { it is VisualDebugEvent.ColorCheck && it.matched })
        assertTrue("Expected humanized TapPoint", rtOn.debugEvents.any { it is VisualDebugEvent.TapPoint && it.humanized })
        assertTrue("Expected curved SwipePath", rtOn.debugEvents.any { it is VisualDebugEvent.SwipePath && it.curved })
        assertTrue("Expected BannedBlocked", rtOn.debugEvents.any { it is VisualDebugEvent.BannedBlocked && it.imageName == "danger.png" })
    }

    @Test
    fun phase1ModelsSerializeAndDeserializeCleanly() {
        val original = Macro(
            name = "Phase1RoundTrip",
            humanizer = HumanizerConfig(
                preset = HumanizerPreset.CUSTOM,
                gaussianClickRadiusPx = 14,
                tapDurationMinMs = 55L,
                tapDurationMaxMs = 120L,
                bezierSwipeEnabled = true,
                bezierCurveFactor = 0.22f,
                delayVariancePercent = 20,
                microHesitationChancePercent = 9,
                humanBreakEnabled = true,
                breakEveryMinMinutes = 20,
                breakEveryMaxMinutes = 35,
                breakDurationMinSec = 60,
                breakDurationMaxSec = 180
            ),
            actions = listOf(
                VariableAction(
                    variableName = "gold",
                    operation = VariableOperation.READ_OCR_NUMBER,
                    scope = VariableScope.PER_ACCOUNT,
                    region = Region(10, 20, 300, 80),
                    ocrTimeoutMs = 1500L,
                    useFallbackOnOcrFail = true,
                    fallbackValue = 500.0
                ),
                CheckVariableAction(
                    variableName = "gold",
                    operator = VariableCompareOp.GREATER_OR_EQUAL,
                    targetValue = 1000.0,
                    compareToVariable = "min_gold",
                    scope = VariableScope.PER_ACCOUNT,
                    foundMode = NotFoundMode.SWITCH_BRANCH,
                    foundBranchId = "b1"
                ),
                BranchRouterAction(
                    rules = listOf(
                        BranchRouteRule(
                            label = "Cek Var",
                            conditionType = BranchConditionType.VARIABLE,
                            variableName = "serangan",
                            variableOp = VariableCompareOp.EQUALS,
                            variableTargetValue = 5.0
                        )
                    )
                )
            )
        )

        val json = testJson.encodeToString(Macro.serializer(), original)
        val decoded = testJson.decodeFromString(Macro.serializer(), json)
        assertEquals(original, decoded)
    }
}
