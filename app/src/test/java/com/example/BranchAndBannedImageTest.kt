package com.example

import com.example.automation.LogLevel
import com.example.automation.MacroRunner
import com.example.automation.MacroRuntime
import com.example.data.BannedImageMode
import com.example.data.BranchConditionType
import com.example.data.BranchNavigationMode
import com.example.data.BranchOutcomeAction
import com.example.data.BranchRouteRule
import com.example.data.BranchRouterAction
import com.example.data.ClickAction
import com.example.data.GoToBranchAction
import com.example.data.ImageAction
import com.example.data.LongPressAction
import com.example.data.Macro
import com.example.data.MacroBranch
import com.example.data.MacroBundleCodec
import com.example.data.NotFoundMode
import com.example.data.RecordedGesture
import com.example.data.Region
import com.example.data.TextCompareMode
import com.example.data.branchIdOfAction
import com.example.data.imageNames
import com.example.data.moveActionToBranch
import com.example.vision.Frame
import com.example.vision.OcrBlock
import com.example.vision.PreparedTemplate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class BranchAndBannedImageTest {

    private class FakeRuntime(
        private val frameProvider: () -> Frame? = { null },
        private val templates: Map<String, PreparedTemplate> = emptyMap(),
        private val textProvider: () -> List<OcrBlock> = { emptyList() },
        private val pixelProvider: (Int, Int) -> Int? = { x, y ->
            val f = frameProvider()
            if (f != null && x in 0 until f.width && y in 0 until f.height) {
                f.pixels[y * f.width + x]
            } else null
        },
        override val screenWidth: Int = 200,
        override val screenHeight: Int = 200
    ) : MacroRuntime {
        val clicks = mutableListOf<Triple<Int, Int, Long>>()
        val logs = mutableListOf<String>()
        var stopped = false
        var fakeClockMs = 1_000L

        override fun isStopped(): Boolean = stopped
        override suspend fun waitIfPaused() {}
        override suspend fun sleep(ms: Long) { fakeClockMs += ms }
        override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
            clicks += Triple(x, y, durationMs)
            return true
        }
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = true
        override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float): Boolean = true
        override fun captureFrame(): Frame? = frameProvider()
        override fun samplePixelArgb(x: Int, y: Int): Int? = pixelProvider(x, y)
        override suspend fun recognizeText(region: Region?): List<OcrBlock> = textProvider()
        override fun loadTemplate(name: String): PreparedTemplate? = templates[name]
        override fun log(level: LogLevel, message: String) { logs += message }
        override fun nowMs(): Long = fakeClockMs
    }

    /** Generates a distinct, high-contrast pattern patch so NCC matching is deterministic and unambiguous. */
    private fun makePatternFrame(w: Int, h: Int, seed: Int): Frame {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = when (seed) {
                    1 -> {
                        // Diagonal + checkerboard bright cyan/navy target
                        val chk = ((x / 3) + (y / 3)) % 2 == 0
                        if (chk) 0xFF00E5FF.toInt() else 0xFF102A43.toInt()
                    }
                    2 -> {
                        // Distinct red/yellow cross warning icon (banned)
                        val cross = (x == y) || (x + y == w - 1) || (x in (w / 2 - 2)..(w / 2 + 2))
                        if (cross) 0xFFFF1744.toInt() else 0xFFFFEA00.toInt()
                    }
                    else -> {
                        val stripe = (y / 4) % 2 == 0
                        if (stripe) 0xFF00E676.toInt() else 0xFF311B92.toInt()
                    }
                }
                px[y * w + x] = v
            }
        }
        return Frame(w, h, px)
    }

    private fun blankScreen(w: Int = 200, h: Int = 200, bgColor: Int = 0xFF1E293B.toInt()): Frame {
        return Frame(w, h, IntArray(w * h) { bgColor })
    }

    private fun stamp(screen: Frame, patch: Frame, dstX: Int, dstY: Int) {
        for (y in 0 until patch.height) {
            val sy = dstY + y
            if (sy !in 0 until screen.height) continue
            for (x in 0 until patch.width) {
                val sx = dstX + x
                if (sx !in 0 until screen.width) continue
                screen.pixels[sy * screen.width + sx] = patch.pixels[y * patch.width + x]
            }
        }
    }

    @Test
    fun branchRouter_matchesConditionAndSwitchesBranchPermanently() = runTest {
        val screen = blankScreen(200, 200, bgColor = 0xFF111111.toInt())
        // Paint green pixel at (50, 60)
        screen.pixels[60 * 200 + 50] = 0xFF00FF00.toInt()

        val rt = FakeRuntime(frameProvider = { screen })
        val macro = Macro(
            loopCount = 1,
            sourceWidth = 200,
            sourceHeight = 200,
            actions = listOf(
                ClickAction(x = 1, y = 1, delayAfterMs = 0),
                BranchRouterAction(
                    timeoutMs = 0,
                    delayAfterMs = 0,
                    rules = listOf(
                        BranchRouteRule(
                            label = "Teks Kalah",
                            conditionType = BranchConditionType.TEXT,
                            textQuery = "DEFEAT",
                            outcome = BranchOutcomeAction.SWITCH_BRANCH,
                            targetBranchId = "b_lose",
                            targetBranchName = "Kalah"
                        ),
                        BranchRouteRule(
                            label = "Warna Hijau Menang",
                            conditionType = BranchConditionType.COLOR,
                            x = 50,
                            y = 60,
                            color = "#00FF00",
                            tolerance = 10,
                            clickOnFound = true,
                            outcome = BranchOutcomeAction.SWITCH_BRANCH,
                            targetBranchId = "b_win",
                            targetBranchName = "Menang"
                        )
                    ),
                    fallbackOutcome = BranchOutcomeAction.SWITCH_BRANCH,
                    fallbackBranchId = "b_fallback"
                ),
                // Must NOT run because SWITCH_BRANCH permanently left Jalur Utama
                ClickAction(x = 99, y = 99, delayAfterMs = 0)
            ),
            branches = listOf(
                MacroBranch(
                    id = "b_win",
                    name = "Menang",
                    actions = listOf(ClickAction(x = 77, y = 77, delayAfterMs = 0))
                ),
                MacroBranch(
                    id = "b_lose",
                    name = "Kalah",
                    actions = listOf(ClickAction(x = 88, y = 88, delayAfterMs = 0))
                ),
                MacroBranch(
                    id = "b_fallback",
                    name = "Fallback",
                    actions = listOf(ClickAction(x = 66, y = 66, delayAfterMs = 0))
                )
            )
        )

        MacroRunner(rt).run(macro)

        assertEquals(
            listOf(1 to 1, 50 to 60, 77 to 77),
            rt.clicks.map { it.first to it.second }
        )
    }

    @Test
    fun branchRouter_fallbackTriggersWhenScreenResultDoesNotMatch() = runTest {
        val screen = blankScreen(200, 200, bgColor = 0xFF111111.toInt())
        val rt = FakeRuntime(
            frameProvider = { screen },
            textProvider = { listOf(OcrBlock("LOADING...", 10, 10, 120, 40)) }
        )
        val macro = Macro(
            loopCount = 1,
            sourceWidth = 200,
            sourceHeight = 200,
            actions = listOf(
                BranchRouterAction(
                    timeoutMs = 0,
                    delayAfterMs = 0,
                    rules = listOf(
                        BranchRouteRule(
                            label = "Hasil Diharapkan",
                            conditionType = BranchConditionType.TEXT,
                            compareMode = TextCompareMode.CONTAINS,
                            textQuery = "VICTORY",
                            outcome = BranchOutcomeAction.CONTINUE
                        )
                    ),
                    fallbackOutcome = BranchOutcomeAction.CALL_BRANCH_RETURN,
                    fallbackBranchId = "b_fix_dir",
                    fallbackBranchName = "Perbaiki Arah"
                ),
                // Because fallback is CALL_BRANCH_RETURN, after b_fix_dir finishes, this MUST run!
                ClickAction(x = 120, y = 120, delayAfterMs = 0)
            ),
            branches = listOf(
                MacroBranch(
                    id = "b_fix_dir",
                    name = "Perbaiki Arah",
                    actions = listOf(
                        ClickAction(x = 40, y = 40, delayAfterMs = 0),
                        ClickAction(x = 45, y = 45, delayAfterMs = 0)
                    )
                )
            )
        )

        MacroRunner(rt).run(macro)

        assertEquals(
            listOf(40 to 40, 45 to 45, 120 to 120),
            rt.clicks.map { it.first to it.second }
        )
    }

    @Test
    fun goToBranch_callAndReturnRunsBranchThenResumesCaller() = runTest {
        val rt = FakeRuntime()
        val macro = Macro(
            loopCount = 1,
            sourceWidth = 200,
            sourceHeight = 200,
            actions = listOf(
                ClickAction(x = 10, y = 10, delayAfterMs = 0),
                GoToBranchAction(
                    targetBranchId = "b_sub",
                    targetBranchName = "SubRute",
                    mode = BranchNavigationMode.CALL_AND_RETURN,
                    delayAfterMs = 0
                ),
                ClickAction(x = 30, y = 30, delayAfterMs = 0)
            ),
            branches = listOf(
                MacroBranch(
                    id = "b_sub",
                    name = "SubRute",
                    actions = listOf(ClickAction(x = 20, y = 20, delayAfterMs = 0))
                )
            )
        )

        MacroRunner(rt).run(macro)

        assertEquals(
            listOf(10 to 10, 20 to 20, 30 to 30),
            rt.clicks.map { it.first to it.second }
        )
    }

    @Test
    fun imageAction_notFoundSwitchesToRecoveryBranch() = runTest {
        val screen = blankScreen(200, 200)
        val targetPatch = makePatternFrame(24, 24, seed = 1)
        val rt = FakeRuntime(
            frameProvider = { screen },
            templates = mapOf("missing.png" to PreparedTemplate("missing.png", targetPatch))
        )
        val macro = Macro(
            loopCount = 1,
            sourceWidth = 200,
            sourceHeight = 200,
            actions = listOf(
                ImageAction(
                    imageName = "missing.png",
                    threshold = 0.85f,
                    timeoutMs = 0,
                    delayAfterMs = 0,
                    notFound = NotFoundMode.SWITCH_BRANCH,
                    notFoundBranchId = "b_recover"
                ),
                // Skipped because SWITCH_BRANCH changed sequence permanently
                ClickAction(x = 99, y = 99, delayAfterMs = 0)
            ),
            branches = listOf(
                MacroBranch(
                    id = "b_recover",
                    name = "Recovery",
                    actions = listOf(ClickAction(x = 55, y = 55, delayAfterMs = 0))
                )
            )
        )

        MacroRunner(rt).run(macro)

        assertEquals(listOf(55 to 55), rt.clicks.map { it.first to it.second })
    }

    @Test
    fun imageAction_repeatedClicksFiresExactCountAndDuration() = runTest {
        val screen = blankScreen(200, 200)
        val targetPatch = makePatternFrame(24, 24, seed = 1)
        stamp(screen, targetPatch, 80, 60) // center = (92, 72)

        val rt = FakeRuntime(
            frameProvider = { screen },
            templates = mapOf("btn.png" to PreparedTemplate("btn.png", targetPatch))
        )
        val macro = Macro(
            loopCount = 1,
            sourceWidth = 200,
            sourceHeight = 200,
            actions = listOf(
                ImageAction(
                    imageName = "btn.png",
                    threshold = 0.80f,
                    timeoutMs = 0,
                    delayAfterMs = 0,
                    clickOnFound = true,
                    clickCount = 4,
                    clickIntervalMs = 60L,
                    clickDurationMs = 75L
                )
            )
        )

        MacroRunner(rt).run(macro)

        assertEquals(4, rt.clicks.size)
        rt.clicks.forEach { (x, y, dur) ->
            assertTrue("Expected click X near 92, got $x", kotlin.math.abs(x - 92) <= 2)
            assertTrue("Expected click Y near 72, got $y", kotlin.math.abs(y - 72) <= 2)
            assertEquals(75L, dur)
        }
    }

    @Test
    fun imageAction_bannedBlockIfPresentCancelsClickAndRunsElse() = runTest {
        val screen = blankScreen(200, 200)
        val targetPatch = makePatternFrame(24, 24, seed = 1)
        val bannedPatch = makePatternFrame(24, 24, seed = 2)
        stamp(screen, targetPatch, 30, 30)
        stamp(screen, bannedPatch, 140, 140)

        val rt = FakeRuntime(
            frameProvider = { screen },
            templates = mapOf(
                "target.png" to PreparedTemplate("target.png", targetPatch),
                "banned.png" to PreparedTemplate("banned.png", bannedPatch)
            )
        )
        val macro = Macro(
            loopCount = 1,
            sourceWidth = 200,
            sourceHeight = 200,
            actions = listOf(
                ImageAction(
                    imageName = "target.png",
                    threshold = 0.80f,
                    timeoutMs = 0,
                    delayAfterMs = 0,
                    clickOnFound = true,
                    bannedImages = listOf("banned.png"),
                    bannedThreshold = 0.80f,
                    bannedMode = BannedImageMode.BLOCK_IF_PRESENT,
                    elseActions = listOf(ClickAction(x = 180, y = 180, delayAfterMs = 0))
                )
            )
        )

        MacroRunner(rt).run(macro)

        // Target at (42,42) must NOT be clicked; only elseAction at (180,180) runs!
        assertEquals(listOf(180 to 180), rt.clicks.map { it.first to it.second })
    }

    @Test
    fun imageAction_bannedAvoidMatchAreaSkipsBannedTargetAndClicksSafeCopy() = runTest {
        val screen = blankScreen(240, 160)
        val targetPatch = makePatternFrame(24, 24, seed = 1)
        val bannedPatch = makePatternFrame(24, 24, seed = 2)

        // Copy #1 of target at (30, 40) -> center (42, 52), right next to banned icon at (56, 40) -> MUST BE AVOIDED!
        stamp(screen, targetPatch, 30, 40)
        stamp(screen, bannedPatch, 56, 40)

        // Copy #2 of target at (170, 90) -> center (182, 102), far from banned icon -> SAFE TO CLICK!
        stamp(screen, targetPatch, 170, 90)

        val rt = FakeRuntime(
            frameProvider = { screen },
            templates = mapOf(
                "target.png" to PreparedTemplate("target.png", targetPatch),
                "banned.png" to PreparedTemplate("banned.png", bannedPatch)
            ),
            screenWidth = 240,
            screenHeight = 160
        )
        val macro = Macro(
            loopCount = 1,
            sourceWidth = 240,
            sourceHeight = 160,
            actions = listOf(
                ImageAction(
                    imageName = "target.png",
                    threshold = 0.80f,
                    timeoutMs = 0,
                    delayAfterMs = 0,
                    clickOnFound = true,
                    clickCount = 2,
                    clickIntervalMs = 40L,
                    bannedImages = listOf("banned.png"),
                    bannedThreshold = 0.80f,
                    bannedMode = BannedImageMode.AVOID_MATCH_AREA,
                    bannedAvoidRadiusPx = 45
                )
            )
        )

        MacroRunner(rt).run(macro)

        assertEquals("Should click the safe copy twice", 2, rt.clicks.size)
        rt.clicks.forEach { (x, y, _) ->
            assertTrue("Expected safe click X near 182 (avoiding 42), got $x", kotlin.math.abs(x - 182) <= 3)
            assertTrue("Expected safe click Y near 102 (avoiding 52), got $y", kotlin.math.abs(y - 102) <= 3)
        }
    }

    @Test
    fun wjmBundlePreservesBranchesBannedImagesAndRouterImages() {
        val macro = Macro(
            id = "m_branch_test",
            name = "Macro Cabang & Banned",
            actions = listOf(
                ImageAction(
                    imageName = "btn_play.png",
                    clickCount = 3,
                    clickIntervalMs = 120L,
                    bannedImages = listOf("icon_bom.png"),
                    bannedMode = BannedImageMode.AVOID_MATCH_AREA,
                    notFound = NotFoundMode.SWITCH_BRANCH,
                    notFoundBranchId = "branch_alt"
                )
            ),
            branches = listOf(
                MacroBranch(
                    id = "branch_alt",
                    name = "Jalur Alternatif",
                    colorHex = "#06B6D4",
                    actions = listOf(
                        BranchRouterAction(
                            rules = listOf(
                                BranchRouteRule(
                                    label = "Cek Bonus",
                                    conditionType = BranchConditionType.IMAGE,
                                    imageName = "bonus.png",
                                    outcome = BranchOutcomeAction.SWITCH_BRANCH,
                                    targetBranchId = ""
                                )
                            ),
                            fallbackOutcome = BranchOutcomeAction.STOP_MACRO
                        )
                    )
                )
            )
        )

        val referenced = macro.imageNames()
        assertEquals(setOf("btn_play.png", "icon_bom.png", "bonus.png"), referenced)

        val fakePngs = mapOf(
            "btn_play.png" to byteArrayOf(1, 2, 3, 4),
            "icon_bom.png" to byteArrayOf(5, 6, 7, 8),
            "bonus.png" to byteArrayOf(9, 10, 11, 12)
        )

        val out = ByteArrayOutputStream()
        val exportRes = MacroBundleCodec.writeEncryptedMacroWjm(
            root = macro,
            lookupMacro = { null },
            loadImage = { name -> fakePngs[name]?.let { name to it } },
            output = out
        )
        assertEquals(3, exportRes.imagesExported)

        val decoded = MacroBundleCodec.readEncryptedWjm(out.toByteArray().inputStream())
        val restored = decoded.macros.single()
        assertEquals(1, restored.branches.size)
        assertEquals("Jalur Alternatif", restored.branches.first().name)
        val restoredImg = restored.actions.first() as ImageAction
        assertEquals(3, restoredImg.clickCount)
        assertEquals(120L, restoredImg.clickIntervalMs)
        assertEquals(listOf("icon_bom.png"), restoredImg.bannedImages)
        assertEquals("branch_alt", restoredImg.notFoundBranchId)
        assertArrayEquals(fakePngs["icon_bom.png"], decoded.images["icon_bom.png"])
        assertArrayEquals(fakePngs["bonus.png"], decoded.images["bonus.png"])
    }

    @Test
    fun globalBannedGuard_avoidMatchAreaBlocksClickOnBannedImageButAllowsSafeClick() = runTest {
        val screen = blankScreen(200, 200)
        val bannedPatch = makePatternFrame(24, 24, seed = 2)
        // Stamp banned icon at (40, 40) -> covers [40..64, 40..64], center (52, 52)
        stamp(screen, bannedPatch, 40, 40)

        val rt = FakeRuntime(
            frameProvider = { screen },
            templates = mapOf("banned.png" to PreparedTemplate("banned.png", bannedPatch))
        )
        val macro = Macro(
            loopCount = 1,
            sourceWidth = 200,
            sourceHeight = 200,
            bannedGuardEnabled = true,
            bannedImages = listOf("banned.png"),
            bannedThreshold = 0.80f,
            bannedMode = BannedImageMode.AVOID_MATCH_AREA,
            bannedAvoidRadiusPx = 25,
            actions = listOf(
                ClickAction(x = 52, y = 52, delayAfterMs = 0),   // directly on banned image -> BLOCKED!
                ClickAction(x = 170, y = 170, delayAfterMs = 0)  // far from banned image -> ALLOWED!
            )
        )

        MacroRunner(rt).run(macro)

        assertEquals(listOf(170 to 170), rt.clicks.map { it.first to it.second })
        assertTrue(rt.logs.any { it.contains("DIBLOKIR") })
    }

    @Test
    fun globalBannedGuard_blockIfPresentBlocksClickAndLongPressAnywhereOnScreen() = runTest {
        val screen = blankScreen(200, 200)
        val bannedPatch = makePatternFrame(24, 24, seed = 2)
        stamp(screen, bannedPatch, 140, 140)

        val rt = FakeRuntime(
            frameProvider = { screen },
            templates = mapOf("banned.png" to PreparedTemplate("banned.png", bannedPatch))
        )
        val macro = Macro(
            loopCount = 1,
            sourceWidth = 200,
            sourceHeight = 200,
            bannedGuardEnabled = true,
            bannedImages = listOf("banned.png"),
            bannedThreshold = 0.80f,
            bannedMode = BannedImageMode.BLOCK_IF_PRESENT,
            actions = listOf(
                ClickAction(x = 15, y = 15, delayAfterMs = 0),
                LongPressAction(x = 30, y = 30, durationMs = 400L, delayAfterMs = 0)
            )
        )

        MacroRunner(rt).run(macro)

        assertTrue("All clicks must be blocked when global BLOCK_IF_PRESENT image is visible", rt.clicks.isEmpty())
    }

    @Test
    fun globalBannedGuard_integratesWithImageActionToAvoidBannedCopy() = runTest {
        val screen = blankScreen(240, 160)
        val targetPatch = makePatternFrame(24, 24, seed = 1)
        val bannedPatch = makePatternFrame(24, 24, seed = 2)

        // Copy #1 near banned icon -> avoided by global guard even though ImageAction.bannedImages is empty!
        stamp(screen, targetPatch, 30, 40)
        stamp(screen, bannedPatch, 56, 40)
        // Copy #2 far away -> safe!
        stamp(screen, targetPatch, 170, 90)

        val rt = FakeRuntime(
            frameProvider = { screen },
            templates = mapOf(
                "target.png" to PreparedTemplate("target.png", targetPatch),
                "banned.png" to PreparedTemplate("banned.png", bannedPatch)
            ),
            screenWidth = 240,
            screenHeight = 160
        )
        val macro = Macro(
            loopCount = 1,
            sourceWidth = 240,
            sourceHeight = 160,
            bannedGuardEnabled = true,
            bannedImages = listOf("banned.png"),
            bannedThreshold = 0.80f,
            bannedMode = BannedImageMode.AVOID_MATCH_AREA,
            bannedAvoidRadiusPx = 45,
            actions = listOf(
                ImageAction(
                    imageName = "target.png",
                    threshold = 0.80f,
                    timeoutMs = 0,
                    delayAfterMs = 0,
                    clickOnFound = true
                )
            )
        )

        MacroRunner(rt).run(macro)

        assertEquals(1, rt.clicks.size)
        val (cx, cy, _) = rt.clicks.single()
        assertTrue("Expected safe click X near 182, got $cx", kotlin.math.abs(cx - 182) <= 3)
        assertTrue("Expected safe click Y near 102, got $cy", kotlin.math.abs(cy - 102) <= 3)
    }

    @Test
    fun moveActionToBranch_movesBetweenMainAndNamedBranchesAndWjmBundlesGlobalBannedImages() {
        val click1 = ClickAction(id = "a_move", x = 10, y = 20)
        val click2 = ClickAction(id = "a_stay", x = 30, y = 40)
        val initial = Macro(
            id = "m_move_test",
            name = "Move & Global Ban",
            bannedGuardEnabled = true,
            bannedImages = listOf("global_ban.png"),
            bannedThreshold = 0.85f,
            bannedMode = BannedImageMode.AVOID_MATCH_AREA,
            bannedAvoidRadiusPx = 75,
            actions = listOf(click1, click2),
            branches = listOf(MacroBranch(id = "b_alt", name = "Alternatif"))
        )

        assertEquals("", initial.branchIdOfAction("a_move"))
        val movedToAlt = initial.moveActionToBranch("a_move", "b_alt")
        assertEquals(listOf("a_stay"), movedToAlt.actions.map { it.id })
        assertEquals(listOf("a_move"), movedToAlt.branches.single().actions.map { it.id })
        assertEquals("b_alt", movedToAlt.branchIdOfAction("a_move"))

        val movedBack = movedToAlt.moveActionToBranch("a_move", "")
        assertEquals(listOf("a_stay", "a_move"), movedBack.actions.map { it.id })
        assertTrue(movedBack.branches.single().actions.isEmpty())
        assertEquals("", movedBack.branchIdOfAction("a_move"))

        // Global bannedImages must be bundled into .wjm and restored cleanly!
        assertEquals(setOf("global_ban.png"), movedBack.imageNames())
        val fakePngs = mapOf("global_ban.png" to byteArrayOf(7, 8, 9, 10))
        val out = ByteArrayOutputStream()
        val exportRes = MacroBundleCodec.writeEncryptedMacroWjm(
            root = movedBack,
            lookupMacro = { null },
            loadImage = { n -> fakePngs[n]?.let { n to it } },
            output = out
        )
        assertEquals(1, exportRes.imagesExported)

        val decoded = MacroBundleCodec.readEncryptedWjm(out.toByteArray().inputStream())
        val restored = decoded.macros.single()
        assertTrue(restored.bannedGuardEnabled)
        assertEquals(listOf("global_ban.png"), restored.bannedImages)
        assertEquals(0.85f, restored.bannedThreshold)
        assertEquals(75, restored.bannedAvoidRadiusPx)
        assertArrayEquals(fakePngs["global_ban.png"], decoded.images["global_ban.png"])
    }
}
