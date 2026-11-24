package com.example

import com.example.automation.LogLevel
import com.example.automation.MacroRunner
import com.example.automation.MacroRuntime
import com.example.data.ClickAction
import com.example.data.ColorAction
import com.example.data.ImageAction
import com.example.data.LongPressAction
import com.example.data.Macro
import com.example.data.MacroBundleCodec
import com.example.data.MacroSecurity
import com.example.data.RecordAction
import com.example.data.RecordedGesture
import com.example.data.RecordedStroke
import com.example.data.Region
import com.example.data.ResolutionScaleMode
import com.example.data.ResolutionScaler
import com.example.data.RunMacroAction
import com.example.data.SwipeAction
import com.example.data.TextAction
import com.example.data.ZoomAction
import com.example.vision.Frame
import com.example.vision.OcrBlock
import com.example.vision.PreparedTemplate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class ResolutionAutoScaleTest {

    @Test
    fun proportionalAutoScaleMapsPhoneToRedfingerAcrossAllActions() = runTest {
        val clicks = mutableListOf<Triple<Int, Int, Long>>()
        val swipes = mutableListOf<List<Int>>()
        val zooms = mutableListOf<Triple<Int, Int, Int>>()
        val recorded = mutableListOf<RecordedGesture>()
        val sampledColors = mutableListOf<Pair<Int, Int>>()
        val ocrRegions = mutableListOf<Region?>()
        val logs = mutableListOf<String>()

        // Authored on a 1080x2400 phone
        val phoneMacro = Macro(
            id = "phone_farm",
            name = "Farm Ponsel",
            sourceWidth = 1080,
            sourceHeight = 2400,
            autoScaleResolution = true,
            scaleMode = ResolutionScaleMode.PROPORTIONAL,
            loopCount = 1,
            loopDelayMs = 0,
            actions = listOf(
                ClickAction(x = 540, y = 1200, durationMs = 50, delayAfterMs = 0),
                LongPressAction(x = 810, y = 1800, durationMs = 500, delayAfterMs = 0),
                SwipeAction(x1 = 270, y1 = 1800, x2 = 810, y2 = 600, durationMs = 300, delayAfterMs = 0),
                ZoomAction(cx = 540, cy = 1200, zoomIn = true, distancePx = 300, durationMs = 400, delayAfterMs = 0),
                RecordAction(
                    gestures = listOf(
                        RecordedGesture(
                            delayBeforeMs = 0,
                            strokes = listOf(RecordedStroke(0, 100, listOf(540, 600, 540, 1800)))
                        )
                    ),
                    delayAfterMs = 0
                ),
                ColorAction(x = 108, y = 240, color = "#FF0000", timeoutMs = 0, clickOnFound = true, delayAfterMs = 0),
                TextAction(query = "READY", region = Region(108, 240, 972, 1200), timeoutMs = 0, clickOnFound = false, delayAfterMs = 0)
            )
        )

        // Executed on Redfinger 720x1280 (sx = 2/3, sy = 8/15)
        val runtime = object : MacroRuntime {
            override val screenWidth: Int = 720
            override val screenHeight: Int = 1280
            override fun isStopped() = false
            override suspend fun waitIfPaused() {}
            override suspend fun sleep(ms: Long) {}
            override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
                clicks += Triple(x, y, durationMs)
                return true
            }
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
                swipes += listOf(x1, y1, x2, y2)
                return true
            }
            override suspend fun zoom(cx: Int, cy: Int, zoomIn: Boolean, distancePx: Int, durationMs: Long): Boolean {
                zooms += Triple(cx, cy, distancePx)
                return true
            }
            override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float): Boolean {
                recorded += gesture
                return true
            }
            override fun captureFrame(): Frame? = null
            override fun samplePixelArgb(x: Int, y: Int): Int {
                sampledColors += x to y
                return 0xFFFF0000.toInt()
            }
            override suspend fun recognizeText(region: Region?): List<OcrBlock> {
                ocrRegions += region
                return listOf(OcrBlock("READY", 100, 200, 200, 240))
            }
            override fun loadTemplate(name: String): PreparedTemplate? = null
            override fun log(level: LogLevel, message: String) { logs += message }
        }

        MacroRunner(runtime).run(phoneMacro)

        // Click: (540, 1200) -> (360, 640); LongPress: (810, 1800) -> (540, 960); Color click: (108, 240) -> (72, 128)
        assertEquals(listOf(Triple(360, 640, 50L), Triple(540, 960, 500L), Triple(72, 128, 50L)), clicks)
        // Swipe: (270, 1800) -> (810, 600) becomes (180, 960) -> (540, 320)
        assertEquals(listOf(listOf(180, 960, 540, 320)), swipes)
        // Zoom: center (360, 640), distance 300 * min(2/3, 8/15) = 160
        assertEquals(listOf(Triple(360, 640, 160)), zooms)
        // Recorded stroke: (540,600)->(540,1800) becomes (360,320)->(360,960)
        assertEquals(listOf(360, 320, 360, 960), recorded.single().strokes.single().points)
        // Color sample point: (72, 128)
        assertEquals(listOf(72 to 128), sampledColors)
        // OCR Region: (108, 240, 972, 1200) -> (72, 128, 648, 640)
        assertEquals(Region(72, 128, 648, 640), ocrRegions.single())
        assertTrue(logs.any { it.contains("Auto-Scale") && it.contains("1080×2400 → 720×1280") })
    }

    @Test
    fun centerFitMaps20By9LetterboxedGameAccuratelyTo16By9RedfingerAndRoundTrips() {
        val macro = Macro(
            sourceWidth = 2400,
            sourceHeight = 1080,
            autoScaleResolution = true,
            scaleMode = ResolutionScaleMode.CENTER_FIT
        )
        val t = ResolutionScaler.forMacro(macro, 1280, 720)
        assertTrue(t.isScaling)

        // Left edge of centered 16:9 viewport on 2400x1080 is x=240; center=1200; right=2160
        assertEquals(0 to 360, t.scalePoint(240, 540))
        assertEquals(640 to 360, t.scalePoint(1200, 540))
        assertEquals(1279 to 360, t.scalePoint(2160, 540)) // clamped inside [0, 1279]

        // Mid-canvas point round-trips cleanly via unscalePoint
        val scaled = t.scalePoint(1500, 810)
        assertEquals(840 to 540, scaled)
        val back = t.unscalePoint(scaled.first, scaled.second)
        assertTrue("Expected ~1500, got ${back.first}", abs(back.first - 1500) <= 1)
        assertTrue("Expected ~810, got ${back.second}", abs(back.second - 810) <= 1)

        // Region round-trip
        val reg = Region(600, 270, 1800, 810)
        val scaledReg = t.scaleRegion(reg)
        assertEquals(Region(240, 180, 1040, 540), scaledReg)
        val backReg = t.unscaleRegion(scaledReg)
        assertEquals(reg, backReg)
    }

    @Test
    fun orientationAlignmentProtectsLandscapeGameAuthoredFromPortraitApp() {
        // Saved with portrait dims 1080x2400, executed in landscape game
        val macro = Macro(sourceWidth = 1080, sourceHeight = 2400, autoScaleResolution = true)

        // On the same phone in Landscape (2400x1080): aligned source becomes 2400x1080 -> 1:1 identity!
        val samePhoneLandscape = ResolutionScaler.forMacro(macro, 2400, 1080)
        assertFalse(samePhoneLandscape.isScaling)
        assertEquals(2100 to 900, samePhoneLandscape.scalePoint(2100, 900))

        // On Redfinger Landscape (1280x720): aligned source 2400x1080 -> 1280x720
        val redfingerLandscape = ResolutionScaler.forMacro(macro, 1280, 720)
        assertTrue(redfingerLandscape.isScaling)
        assertEquals(2400, redfingerLandscape.srcW)
        assertEquals(1080, redfingerLandscape.srcH)
        assertEquals(640 to 360, redfingerLandscape.scalePoint(1200, 540))
    }

    @Test
    fun inferSourceSizeDetectsPortraitVsLandscapeForLegacyMacros() {
        val legacyLandscape = Macro(
            sourceWidth = 0,
            sourceHeight = 0,
            actions = listOf(ClickAction(x = 2050, y = 800))
        )
        assertEquals(2400 to 1080, ResolutionScaler.inferSourceSizeForDevice(legacyLandscape, 1080, 2400))

        val legacyPortrait = Macro(
            sourceWidth = 0,
            sourceHeight = 0,
            actions = listOf(SwipeAction(x1 = 500, y1 = 1900, x2 = 500, y2 = 600))
        )
        assertEquals(1080 to 2400, ResolutionScaler.inferSourceSizeForDevice(legacyPortrait, 1080, 2400))
    }

    @Test
    fun imageActionAutoScalesTemplateAndOffsetsAcrossResolutions() = runTest {
        // Build a distinctive textured 48x48 button pattern (as cropped on a 1080x1920 phone)
        fun makeButtonFrame(size: Int): Frame {
            val px = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val nx = x.toFloat() / size
                    val ny = y.toFloat() / size
                    val luma = when {
                        nx < 0.15f || nx > 0.85f || ny < 0.15f || ny > 0.85f -> 30
                        (nx - 0.5f) * (nx - 0.5f) + (ny - 0.5f) * (ny - 0.5f) < 0.06f -> 240
                        nx > ny -> 190
                        else -> 95
                    }
                    px[y * size + x] = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
                }
            }
            return Frame(size, size, px)
        }

        val phoneTemplate = PreparedTemplate("btn_ok.png", makeButtonFrame(48))

        // Render the same button scaled down to 32x32 (factor 2/3) inside a 720x1280 Redfinger screen at (400, 700)
        val rfW = 720
        val rfH = 1280
        val rfPixels = IntArray(rfW * rfH) { 0xFF181C24.toInt() }
        val scaledBtn = makeButtonFrame(32)
        val placeX = 400
        val placeY = 700
        for (y in 0 until scaledBtn.height) {
            for (x in 0 until scaledBtn.width) {
                rfPixels[(placeY + y) * rfW + (placeX + x)] = scaledBtn.pixels[y * scaledBtn.width + x]
            }
        }
        val rfFrame = Frame(rfW, rfH, rfPixels)

        val clicks = mutableListOf<Pair<Int, Int>>()
        val macro = Macro(
            name = "Image AutoScale",
            sourceWidth = 1080,
            sourceHeight = 1920,
            autoScaleResolution = true,
            loopCount = 1,
            loopDelayMs = 0,
            actions = listOf(
                ImageAction(
                    imageName = "btn_ok.png",
                    threshold = 0.85f,
                    timeoutMs = 0,
                    region = Region(540, 960, 780, 1200), // scales to (360, 640, 520, 800) which contains (400..432, 700..732)
                    clickOnFound = true,
                    offsetX = 30, // scales to +20 on 720x1280
                    offsetY = -15, // scales to -10 on 720x1280
                    delayAfterMs = 0
                )
            )
        )

        val runtime = object : MacroRuntime {
            override val screenWidth: Int = rfW
            override val screenHeight: Int = rfH
            override fun isStopped() = false
            override suspend fun waitIfPaused() {}
            override suspend fun sleep(ms: Long) {}
            override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
                clicks += x to y
                return true
            }
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
            override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float) = true
            override fun captureFrame(): Frame = rfFrame
            override fun samplePixelArgb(x: Int, y: Int): Int? = null
            override fun loadTemplate(name: String): PreparedTemplate? = if (name == "btn_ok.png") phoneTemplate else null
            override fun log(level: LogLevel, message: String) {}
        }

        MacroRunner(runtime).run(macro)

        assertEquals("Scaled template must match on Redfinger screen", 1, clicks.size)
        val expectedX = placeX + scaledBtn.width / 2 + 20 // 416 + 20 = 436
        val expectedY = placeY + scaledBtn.height / 2 - 10 // 716 - 10 = 706
        assertTrue("Expected click X near $expectedX, got ${clicks[0].first}", abs(clicks[0].first - expectedX) <= 2)
        assertTrue("Expected click Y near $expectedY, got ${clicks[0].second}", abs(clicks[0].second - expectedY) <= 2)
    }

    @Test
    fun linkedSubMacroUsesItsOwnSourceResolutionAndAutoScaleOffDisablesScaling() = runTest {
        val clicks = mutableListOf<Pair<Int, Int>>()

        // Sub-macro authored on 1080x1920
        val subMacro = Macro(
            id = "sub_1080_1920",
            name = "Sub 16:9",
            sourceWidth = 1080,
            sourceHeight = 1920,
            autoScaleResolution = true,
            loopCount = 1,
            loopDelayMs = 0,
            actions = listOf(ClickAction(x = 540, y = 960, delayAfterMs = 0))
        )

        // Master macro authored on 1080x2400
        val masterMacro = Macro(
            id = "master_20_9",
            name = "Master 20:9",
            sourceWidth = 1080,
            sourceHeight = 2400,
            autoScaleResolution = true,
            loopCount = 1,
            loopDelayMs = 0,
            actions = listOf(
                ClickAction(x = 540, y = 1200, delayAfterMs = 0),
                RunMacroAction(targetMacroId = subMacro.id, targetMacroName = subMacro.name, returnAfter = true, delayAfterMs = 0)
            )
        )

        val runtime = object : MacroRuntime {
            override val screenWidth: Int = 720
            override val screenHeight: Int = 1280
            override fun isStopped() = false
            override suspend fun waitIfPaused() {}
            override suspend fun sleep(ms: Long) {}
            override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
                clicks += x to y
                return true
            }
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
            override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float) = true
            override fun captureFrame(): Frame? = null
            override fun samplePixelArgb(x: Int, y: Int): Int? = null
            override fun loadTemplate(name: String): PreparedTemplate? = null
            override fun loadMacro(id: String): Macro? = if (id == subMacro.id) subMacro else null
            override fun log(level: LogLevel, message: String) {}
        }

        MacroRunner(runtime).run(masterMacro)
        // Both centers map accurately to (360, 640) on 720x1280 even though source heights differed (2400 vs 1920)
        assertEquals(listOf(360 to 640, 360 to 640), clicks)

        // When autoScaleResolution = false, raw coordinates pass through untouched
        clicks.clear()
        val disabledMacro = masterMacro.copy(autoScaleResolution = false, actions = listOf(ClickAction(x = 540, y = 1200, delayAfterMs = 0)))
        MacroRunner(runtime).run(disabledMacro)
        assertEquals(listOf(540 to 1200), clicks)
    }

    @Test
    fun zipExportImportPreservesSourceResolutionWithoutBreakingSecuritySeal() {
        val sealed = MacroSecurity.applySecurity(
            macro = Macro(
                id = "locked_scaled",
                name = "Locked AutoScale",
                sourceWidth = 1080,
                sourceHeight = 2400,
                autoScaleResolution = true,
                scaleMode = ResolutionScaleMode.CENTER_FIT,
                actions = listOf(ClickAction(x = 540, y = 1200))
            ),
            isLocked = true,
            isPassive = true,
            expiresAtEpochMs = 1_800_000_000_000L,
            expiryLabel = "7 Hari",
            newPassword = "secret"
        )

        val out = ByteArrayOutputStream()
        MacroBundleCodec.writeMacroZip(
            root = sealed,
            lookupMacro = { null },
            loadImage = { null },
            output = out
        )
        val decoded = MacroBundleCodec.readBundle(ByteArrayInputStream(out.toByteArray())).macros.first()
        assertEquals(1080, decoded.sourceWidth)
        assertEquals(2400, decoded.sourceHeight)
        assertTrue(decoded.autoScaleResolution)
        assertEquals(ResolutionScaleMode.CENTER_FIT, decoded.scaleMode)
        assertFalse(MacroSecurity.isTampered(decoded))
        assertTrue(MacroSecurity.verifyPassword(decoded, "secret"))
    }

    @Test
    fun legacyMacroWithUnknownSourceResolutionDiscoversTemplateScaleAutomatically() = runTest {
        fun makeIcon(w: Int, h: Int): Frame {
            val px = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val nx = x.toFloat() / w
                    val ny = y.toFloat() / h
                    val luma = when {
                        nx < 0.1f || nx > 0.9f || ny < 0.1f || ny > 0.9f -> 25
                        (nx - 0.5f) * (nx - 0.5f) + (ny - 0.5f) * (ny - 0.5f) < 0.08f -> 240
                        nx > ny -> 185
                        else -> 90
                    }
                    px[y * w + x] = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
                }
            }
            return Frame(w, h, px)
        }

        // Template cropped at 96x72, screen displays it at 0.75x (72x54) on a legacy macro with sourceWidth=0!
        val tpl = PreparedTemplate("legacy_btn.png", makeIcon(96, 72))
        val screenW = 640
        val screenH = 480
        val pixels = IntArray(screenW * screenH) { 0xFF1A202C.toInt() }
        val scaledBtn = makeIcon(72, 54)
        for (y in 0 until scaledBtn.height) {
            for (x in 0 until scaledBtn.width) {
                pixels[(180 + y) * screenW + (260 + x)] = scaledBtn.pixels[y * scaledBtn.width + x]
            }
        }
        val frame = Frame(screenW, screenH, pixels)

        val clicks = mutableListOf<Pair<Int, Int>>()
        val logs = mutableListOf<String>()
        val legacyMacro = Macro(
            name = "Legacy Imported Macro",
            sourceWidth = 0,
            sourceHeight = 0,
            autoScaleResolution = true,
            loopCount = 1,
            loopDelayMs = 0,
            actions = listOf(
                ImageAction(
                    imageName = "legacy_btn.png",
                    threshold = 0.80f,
                    timeoutMs = 0,
                    clickOnFound = true,
                    delayAfterMs = 0
                )
            )
        )

        val runtime = object : MacroRuntime {
            override val screenWidth: Int = screenW
            override val screenHeight: Int = screenH
            override fun isStopped() = false
            override suspend fun waitIfPaused() {}
            override suspend fun sleep(ms: Long) {}
            override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
                clicks += x to y
                return true
            }
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
            override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float) = true
            override fun captureFrame(): Frame = frame
            override fun samplePixelArgb(x: Int, y: Int): Int? = null
            override fun loadTemplate(name: String): PreparedTemplate? = if (name == "legacy_btn.png") tpl else null
            override fun log(level: LogLevel, message: String) { logs += message }
        }

        MacroRunner(runtime).run(legacyMacro)
        assertEquals("Legacy macro must detect scaled template automatically", 1, clicks.size)
        assertTrue("Expected X near 296, got ${clicks[0].first}", abs(clicks[0].first - 296) <= 3)
        assertTrue("Expected Y near 207, got ${clicks[0].second}", abs(clicks[0].second - 207) <= 3)
        assertTrue("Expected scale info in log: $logs", logs.any { it.contains("skala ") })
    }
}
