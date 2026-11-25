package com.example

import com.example.automation.LogLevel
import com.example.automation.MacroRunner
import com.example.automation.MacroRuntime
import com.example.data.ClickAction
import com.example.data.Macro
import com.example.data.RecordedGesture
import com.example.data.TrackInteractionMode
import com.example.data.TrackObjectAction
import com.example.vision.Frame
import com.example.vision.MovingObjectTracker
import com.example.vision.PreparedTemplate
import com.example.vision.TemplateMatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class CocMovingTroopTrackerTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r.coerceIn(0, 255)) shl 16) or ((g.coerceIn(0, 255)) shl 8) or (b.coerceIn(0, 255))

    /**
     * Renders a Clash of Clans-style battlefield with:
     * - Textured green grass (luma ~136, intentionally matching red dragon luma!)
     * - Dark obsidian wall strip across the middle
     * - Optional static red/brown building roof decoy at (390, 65)
     * - Optional animated/rotated/scaled troop sprite
     */
    private fun renderCocBattlefield(
        width: Int = 480,
        height: Int = 300,
        troopCx: Int? = null,
        troopCy: Int? = null,
        rotated90: Boolean = false,
        wingPhase: Int = 0,
        scale: Double = 1.0,
        isCyanElectroDragon: Boolean = false,
        withStaticRedRoofDecoy: Boolean = true,
        panOffsetX: Int = 0,
        healSpellTintOnTroop: Boolean = false
    ): Frame {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val tx = x + panOffsetX
                // Realistic COC checkerboard grass tiles + subtle texture
                val tileDark = (((tx / 24) + (y / 24)) and 1) == 0
                val jitter = ((tx * 11 + y * 17) % 16) - 8
                var r = (if (tileDark) 86 else 124) + jitter
                var g = (if (tileDark) 142 else 178) + jitter
                var b = (if (tileDark) 42 else 66) + (jitter / 2)

                // Dark obsidian wall band running diagonally across battlefield
                if (abs((tx - 80) - y) <= 14) {
                    r = 42 + (jitter / 2)
                    g = 38 + (jitter / 2)
                    b = 48 + (jitter / 2)
                }

                // Scattered stone/wood base buildings (static relative to map!)
                if ((tx % 96) in 12..44 && (y % 88) in 14..42 && !(x in 360..420 && y in 40..92)) {
                    r = 128 + (jitter / 2)
                    g = 118 + (jitter / 2)
                    b = 104 + (jitter / 2)
                }

                // Static red/brown building roof decoy (top-right) — never moves!
                if (withStaticRedRoofDecoy && x in 368..412 && y in 45..85) {
                    val roofPat = ((x + y) % 5) * 6
                    r = 185 - roofPat
                    g = 78 + roofPat
                    b = 52
                }

                pixels[y * width + x] = argb(r, g, b)
            }
        }

        if (troopCx != null && troopCy != null) {
            val half = (18 * scale).roundToInt().coerceAtLeast(8)
            val wingSpread = when (wingPhase % 3) {
                0 -> half
                1 -> (half * 0.65).roundToInt()
                else -> (half * 1.15).roundToInt()
            }
            for (dy in -half..half) {
                for (dx in -half..half) {
                    val sx = troopCx + dx
                    val sy = troopCy + dy
                    if (sx !in 0 until width || sy !in 0 until height) continue

                    // Rotate local coordinates 90° when troop turns perpendicular to crop orientation
                    val lx = if (rotated90) dy else dx
                    val ly = if (rotated90) -dx else dy
                    val nx = lx.toDouble() / half.toDouble()
                    val ny = ly.toDouble() / half.toDouble()

                    // Body ellipse + flapping wings + glowing gold eyes/belly
                    val inBody = (nx * nx) / 0.28 + (ny * ny) / 0.55 <= 1.0
                    val inWings = abs(lx) <= wingSpread && abs(ny) <= 0.34 && abs(lx) >= (half * 0.25)
                    val inCoreAccent = (nx * nx + (ny + 0.15) * (ny + 0.15)) <= 0.09
                    if (!inBody && !inWings) continue

                    var pr: Int
                    var pg: Int
                    var pb: Int
                    if (!isCyanElectroDragon) {
                        // Classic COC Red/Orange/Gold Dragon (luma ~125 — camouflaged in grayscale against grass!)
                        when {
                            inCoreAccent -> { pr = 248; pg = 205; pb = 48 }   // Golden belly/crest
                            inBody -> { pr = 216; pg = 64; pb = 38 }          // Crimson body
                            else -> { pr = 232; pg = 112; pb = 42 }           // Flapping orange-red wings
                        }
                    } else {
                        // Wrong troop: Cyan/Blue Electro Dragon
                        when {
                            inCoreAccent -> { pr = 215; pg = 248; pb = 255 }
                            inBody -> { pr = 42; pg = 148; pb = 235 }
                            else -> { pr = 64; pg = 205; pb = 245 }
                        }
                    }

                    if (healSpellTintOnTroop) {
                        // Semi-transparent golden Heal Spell ring over the troop
                        pr = (pr * 0.72 + 255 * 0.28).roundToInt()
                        pg = (pg * 0.72 + 235 * 0.28).roundToInt()
                        pb = (pb * 0.72 + 95 * 0.28).roundToInt()
                    }
                    pixels[sy * width + sx] = argb(pr, pg, pb)
                }
            }
        }
        return Frame(width, height, pixels)
    }

    private fun captureDragonTemplateOnGrass(): PreparedTemplate {
        val f = renderCocBattlefield(
            width = 120,
            height = 120,
            troopCx = 60,
            troopCy = 60,
            rotated90 = false,
            wingPhase = 0,
            scale = 1.0,
            withStaticRedRoofDecoy = false
        )
        // 48x48 crop around the dragon (includes grass margin around outer edges, just like a user crop!)
        return PreparedTemplate("dragon.png", f.crop(60 - 24, 60 - 24, 48, 48))
    }

    @Test
    fun cocDragonRotatedFlappingAndZoomedOverGrassAndWalls_isTrackedContinuouslyWhileIgnoringStaticDecoy() = runTest {
        val dragonTpl = captureDragonTemplateOnGrass()

        // Sanity proof: pure legacy grayscale NCC fails on a 90°-rotated, wing-flapping, 0.85x zoomed dragon over walls!
        val rotatedLive = renderCocBattlefield(
            troopCx = 180, troopCy = 150, rotated90 = true, wingPhase = 1, scale = 0.85
        )
        val oldGrayHit = TemplateMatcher.find(rotatedLive, dragonTpl.centerFocused(), threshold = 0.72)
        assertNull("Legacy grayscale NCC fails on rotated/flapping/zoomed COC troop over walls", oldGrayHit)

        // Now run the upgraded MacroRunner on a live moving, turning, wing-flapping, zoomed Dragon!
        val path = listOf(
            Triple(95, 120, 0),
            Triple(130, 132, 1),
            Triple(168, 146, 2),
            Triple(206, 160, 0),
            Triple(245, 174, 1),
            Triple(284, 186, 2)
        )
        var clockMs = 1000L
        var frameIdx = 0
        val taps = mutableListOf<Pair<Int, Int>>()

        val runtime = object : MacroRuntime {
            override val screenWidth = 480
            override val screenHeight = 300
            override fun isStopped() = false
            override suspend fun waitIfPaused() {}
            override suspend fun sleep(ms: Long) { clockMs += ms }
            override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
                taps += x to y
                return true
            }
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
            override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float) = true
            override fun captureFrame(): Frame {
                val p = path.getOrNull(frameIdx++)
                return if (p != null) {
                    renderCocBattlefield(
                        troopCx = p.first,
                        troopCy = p.second,
                        rotated90 = true,
                        wingPhase = p.third,
                        scale = 0.85,
                        withStaticRedRoofDecoy = true
                    )
                } else {
                    renderCocBattlefield(troopCx = null, troopCy = null, withStaticRedRoofDecoy = true)
                }
            }
            override fun samplePixelArgb(x: Int, y: Int): Int? = null
            override fun loadTemplate(name: String) = if (name == "dragon.png") dragonTpl else null
            override fun log(level: LogLevel, message: String) {}
            override fun nowMs() = clockMs
        }

        val macro = Macro(
            name = "COC Chase Dragon",
            sourceWidth = 480,
            sourceHeight = 300,
            autoScaleResolution = false,
            actions = listOf(
                TrackObjectAction(
                    imageName = "dragon.png",
                    threshold = 0.65f,
                    timeoutMs = 1500L,
                    interactionMode = TrackInteractionMode.TAP_CONTINUOUS,
                    repeatIntervalMs = 45L,
                    maxTrackDurationMs = 4000L,
                    leadPredictionMs = 0L,
                    useColorMatch = true,
                    prioritizeMotion = true,
                    delayAfterMs = 0L
                )
            )
        )

        MacroRunner(runtime).run(macro)

        assertTrue("Expected continuous chase taps (>= 3), got $taps", taps.size >= 3)
        // Must NEVER tap the static red roof decoy at (390, 65)
        for ((tx, ty) in taps) {
            assertTrue(
                "Tap ($tx,$ty) falsely hit static red roof decoy (390,65)!",
                hypot((tx - 390).toDouble(), (ty - 65).toDouble()) > 45.0
            )
        }
        // Taps must track diagonally along the Dragon's rightward-downward path!
        assertTrue("Expected rightward tracking across battlefield: $taps", taps.last().first > taps.first().first + 60)
        assertTrue("Expected downward tracking across battlefield: $taps", taps.last().second > taps.first().second + 20)
    }

    @Test
    fun rejectsWrongColoredMovingElectroDragonAndRunsElseBranch() = runTest {
        val redDragonTpl = captureDragonTemplateOnGrass()
        var clockMs = 1000L
        var frameIdx = 0
        val clicks = mutableListOf<Pair<Int, Int>>()

        val runtime = object : MacroRuntime {
            override val screenWidth = 480
            override val screenHeight = 300
            override fun isStopped() = false
            override suspend fun waitIfPaused() {}
            override suspend fun sleep(ms: Long) { clockMs += ms }
            override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
                clicks += x to y
                return true
            }
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
            override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float) = true
            override fun captureFrame(): Frame {
                val cx = 100 + (frameIdx++ * 28) % 240
                // Battlefield ONLY has a moving CYAN Electro Dragon (wrong troop!) + static decoy
                return renderCocBattlefield(
                    troopCx = cx,
                    troopCy = 150,
                    rotated90 = false,
                    wingPhase = frameIdx,
                    isCyanElectroDragon = true,
                    withStaticRedRoofDecoy = false
                )
            }
            override fun samplePixelArgb(x: Int, y: Int): Int? = null
            override fun loadTemplate(name: String) = if (name == "dragon.png") redDragonTpl else null
            override fun log(level: LogLevel, message: String) {}
            override fun nowMs() = clockMs
        }

        val macro = Macro(
            sourceWidth = 480,
            sourceHeight = 300,
            autoScaleResolution = false,
            actions = listOf(
                TrackObjectAction(
                    imageName = "dragon.png",
                    threshold = 0.65f,
                    timeoutMs = 200L,
                    interactionMode = TrackInteractionMode.TAP_ONCE,
                    useColorMatch = true,
                    prioritizeMotion = true,
                    elseActions = listOf(ClickAction(x = 77, y = 77, delayAfterMs = 0L)),
                    delayAfterMs = 0L
                )
            )
        )

        MacroRunner(runtime).run(macro)

        // Must reject the cyan Electro Dragon and run elseActions -> (77, 77)
        assertEquals(listOf(77 to 77), clicks)
    }

    @Test
    fun cameraPanGuardAndSpellOverlayKeepTroopLockedWithoutFalseDrops() = runTest {
        val dragonTpl = captureDragonTemplateOnGrass()
        val f0 = renderCocBattlefield(troopCx = 160, troopCy = 150, panOffsetX = 0)
        val f1 = renderCocBattlefield(troopCx = 195, troopCy = 155, panOffsetX = 40) // Whole map panned!
        val motion = MovingObjectTracker.computeMotionField(f0, f1, 0, 0, 480, 300)
        assertTrue("Whole-map pan must trigger global camera motion guard", motion != null && motion.isGlobalCameraMotion)

        // Even during camera pan + yellow Heal Spell overlay on the Dragon, tracker still locks on!
        val sig = MovingObjectTracker.buildSignature("dragon.png", dragonTpl.colorFrame!!, ignoreBackgroundEdges = true)
        val spellFrame = renderCocBattlefield(
            troopCx = 210,
            troopCy = 155,
            rotated90 = true,
            wingPhase = 2,
            healSpellTintOnTroop = true,
            withStaticRedRoofDecoy = false
        )
        val hit = MovingObjectTracker.findHybridInBounds(
            frame = spellFrame,
            sig = sig,
            threshold = 0.58,
            rLeft = 0,
            rTop = 0,
            rRight = 480,
            rBottom = 300,
            scales = doubleArrayOf(1.0, 0.85, 1.15),
            motion = motion,
            livePatch = null,
            matchMirrorX = true,
            prioritizeMotion = true
        )
        assertTrue("Tracker must still lock onto Dragon under Heal Spell & camera pan", hit != null)
        assertTrue(
            "Hit center (${hit!!.match.centerX},${hit.match.centerY}) should be near (210,155)",
            hypot((hit.match.centerX - 210).toDouble(), (hit.match.centerY - 155).toDouble()) <= 18.0
        )
        assertFalse("Signature must detect chromatic foreground colors", !sig.hasChromaticSignature)
    }

    @Test
    fun anyMotionModeTracksMixedColorTroopsWithoutTemplateAndIgnoresStaticDecoy() = runTest {
        // Mixed army: starts as Cyan Electro Dragon, then Red Dragon continues moving along the battlefield!
        val path = listOf(
            Triple(95, 118, true),
            Triple(132, 130, true),
            Triple(170, 144, true),
            Triple(208, 158, false),
            Triple(246, 172, false),
            Triple(284, 186, false)
        )
        var clockMs = 1000L
        var frameIdx = 0
        val taps = mutableListOf<Pair<Int, Int>>()

        val runtime = object : MacroRuntime {
            override val screenWidth = 480
            override val screenHeight = 300
            override fun isStopped() = false
            override suspend fun waitIfPaused() {}
            override suspend fun sleep(ms: Long) { clockMs += ms }
            override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
                taps += x to y
                return true
            }
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
            override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float) = true
            override fun captureFrame(): Frame {
                val p = path.getOrNull(frameIdx++)
                return if (p != null) {
                    renderCocBattlefield(
                        troopCx = p.first,
                        troopCy = p.second,
                        rotated90 = (frameIdx % 2 == 0),
                        wingPhase = frameIdx,
                        scale = 0.9,
                        isCyanElectroDragon = p.third,
                        withStaticRedRoofDecoy = true
                    )
                } else {
                    renderCocBattlefield(troopCx = null, troopCy = null, withStaticRedRoofDecoy = true)
                }
            }
            override fun samplePixelArgb(x: Int, y: Int): Int? = null
            override fun loadTemplate(name: String): PreparedTemplate? = null // Zero templates!
            override fun log(level: LogLevel, message: String) {}
            override fun nowMs() = clockMs
        }

        val macro = Macro(
            name = "COC Chase Any Moving Troop",
            sourceWidth = 480,
            sourceHeight = 300,
            autoScaleResolution = false,
            actions = listOf(
                TrackObjectAction(
                    imageName = "",
                    trackAnyMotion = true,
                    timeoutMs = 1200L,
                    interactionMode = TrackInteractionMode.TAP_CONTINUOUS,
                    repeatIntervalMs = 45L,
                    maxTrackDurationMs = 4000L,
                    leadPredictionMs = 0L,
                    delayAfterMs = 0L
                )
            )
        )

        MacroRunner(runtime).run(macro)

        assertTrue("Expected continuous color-free chase taps (>= 3), got $taps", taps.size >= 3)
        for ((tx, ty) in taps) {
            assertTrue(
                "Color-free tap ($tx,$ty) falsely hit static red roof decoy (390,65)!",
                hypot((tx - 390).toDouble(), (ty - 65).toDouble()) > 45.0
            )
        }
        assertTrue("Expected rightward tracking across mixed-color troops: $taps", taps.last().first > taps.first().first + 60)
        assertTrue("Expected downward tracking across mixed-color troops: $taps", taps.last().second > taps.first().second + 20)
    }

    @Test
    fun anyMotionModeWithLeadPredictionLeadsMovingTroopInTapOnce() = runTest {
        var clockMs = 1000L
        var frameIdx = 0
        val taps = mutableListOf<Pair<Int, Int>>()

        val runtime = object : MacroRuntime {
            override val screenWidth = 480
            override val screenHeight = 300
            override fun isStopped() = false
            override suspend fun waitIfPaused() {}
            override suspend fun sleep(ms: Long) { clockMs += ms }
            override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
                taps += x to y
                return true
            }
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
            override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float) = true
            override fun captureFrame(): Frame {
                val cx = (100 + frameIdx * 30).coerceAtMost(380)
                frameIdx++
                return renderCocBattlefield(
                    troopCx = cx,
                    troopCy = 150,
                    isCyanElectroDragon = true,
                    withStaticRedRoofDecoy = true
                )
            }
            override fun samplePixelArgb(x: Int, y: Int): Int? = null
            override fun loadTemplate(name: String): PreparedTemplate? = null
            override fun log(level: LogLevel, message: String) {}
            override fun nowMs() = clockMs
        }

        val macro = Macro(
            sourceWidth = 480,
            sourceHeight = 300,
            autoScaleResolution = false,
            actions = listOf(
                TrackObjectAction(
                    imageName = "",
                    trackAnyMotion = true,
                    timeoutMs = 1000L,
                    interactionMode = TrackInteractionMode.TAP_ONCE,
                    leadPredictionMs = 100L,
                    delayAfterMs = 0L
                )
            )
        )

        MacroRunner(runtime).run(macro)

        assertEquals("TAP_ONCE must fire exactly once", 1, taps.size)
        val (tapX, tapY) = taps.first()
        // Frame 0: cx=100 (baseline), Frame 1: cx=130 (first hit), Frame 2: cx=160 (second hit for velocity) -> predicted > 160!
        assertTrue("Lead click tapX=$tapX should aim ahead of troop (expected > 165)", tapX > 165)
        assertTrue("Lead click tapY=$tapY should stay aligned with troop y=150", abs(tapY - 150) <= 22)
    }

    @Test
    fun anyMotionModeIgnoresStaticBattlefieldAndWholeScreenCameraPan() = runTest {
        suspend fun runScenario(panPerFrame: Int, elseCoord: Pair<Int, Int>): List<Pair<Int, Int>> {
            var clockMs = 1000L
            var frameIdx = 0
            val clicks = mutableListOf<Pair<Int, Int>>()

            val runtime = object : MacroRuntime {
                override val screenWidth = 480
                override val screenHeight = 300
                override fun isStopped() = false
                override suspend fun waitIfPaused() {}
                override suspend fun sleep(ms: Long) { clockMs += ms }
                override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
                    clicks += x to y
                    return true
                }
                override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
                override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float) = true
                override fun captureFrame(): Frame {
                    val pan = frameIdx++ * panPerFrame
                    return renderCocBattlefield(
                        troopCx = null,
                        troopCy = null,
                        withStaticRedRoofDecoy = true,
                        panOffsetX = pan
                    )
                }
                override fun samplePixelArgb(x: Int, y: Int): Int? = null
                override fun loadTemplate(name: String): PreparedTemplate? = null
                override fun log(level: LogLevel, message: String) {}
                override fun nowMs() = clockMs
            }

            val macro = Macro(
                sourceWidth = 480,
                sourceHeight = 300,
                autoScaleResolution = false,
                actions = listOf(
                    TrackObjectAction(
                        imageName = "",
                        trackAnyMotion = true,
                        timeoutMs = 200L,
                        interactionMode = TrackInteractionMode.TAP_ONCE,
                        elseActions = listOf(ClickAction(x = elseCoord.first, y = elseCoord.second, delayAfterMs = 0L)),
                        delayAfterMs = 0L
                    )
                )
            )

            MacroRunner(runtime).run(macro)
            return clicks
        }

        // Case 1: completely static battlefield (only static buildings + red roof decoy)
        assertEquals(listOf(88 to 88), runScenario(panPerFrame = 0, elseCoord = 88 to 88))
        // Case 2: whole-screen camera pan (isGlobalCameraMotion must suppress false triggers)
        assertEquals(listOf(99 to 99), runScenario(panPerFrame = 40, elseCoord = 99 to 99))
    }
}

