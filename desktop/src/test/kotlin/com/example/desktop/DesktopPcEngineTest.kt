package com.example.desktop

import com.example.data.ClickAction
import com.example.data.ExpiryUnit
import com.example.data.ImageAction
import com.example.data.Macro
import com.example.data.MacroSecurity
import com.example.data.MacroSet
import com.example.data.RunMacroAction
import com.example.data.SingleUseImportException
import com.example.data.TextAction
import com.example.data.TextCompareMode
import com.example.vision.Frame
import com.example.vision.PreparedTemplate
import com.example.vision.ReusableFramePool
import com.example.vision.TemplateMatcher
import com.example.vision.TextConditionEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class DesktopPcEngineTest {

    @Test
    fun parallelTemplateMatchingMatchesSingleThreadedExactlyAndReusesFrameCache() {
        val w = 1280
        val h = 720
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = ((x * 7 + y * 13) % 40) + 30
                pixels[y * w + x] = (0xFF shl 24) or (v shl 16) or ((v + 15) shl 8) or (v + 30)
            }
        }
        // Embed a distinct 48x48 target button at (842, 418)
        val tx = 842
        val ty = 418
        val tw = 48
        val th = 48
        for (dy in 0 until th) {
            for (dx in 0 until tw) {
                val r = if ((dx - 24) * (dx - 24) + (dy - 24) * (dy - 24) <= 180) 245 else 40 + (dx * 4) % 180
                val g = if (dx in 16..32 && dy in 18..30) 220 else 70 + (dy * 3) % 150
                val b = 55 + ((dx + dy) * 3) % 160
                pixels[(ty + dy) * w + (tx + dx)] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val frame = Frame(w, h, pixels)
        val tpl = PreparedTemplate("pc_btn.png", frame.crop(tx, ty, tw, th))

        // Verify per-Frame downsampled grayscale cache hits the exact same object
        val c1 = frame.cachedDownsampledGray(0, 0, w, h, tpl.coarseFactor)
        val c2 = frame.cachedDownsampledGray(0, 0, w, h, tpl.coarseFactor)
        assertSame("Downsampled gray buffer must be cached on Frame", c1, c2)

        TemplateMatcher.parallelMatchingEnabled = false
        val seq = TemplateMatcher.findWithStats(frame, tpl, threshold = 0.85)

        TemplateMatcher.parallelMatchingEnabled = true
        TemplateMatcher.maxWorkerThreads = 4
        val par = TemplateMatcher.findWithStats(frame, tpl, threshold = 0.85)

        assertNotNull(seq.match)
        assertNotNull(par.match)
        assertEquals(tx, par.match!!.x)
        assertEquals(ty, par.match!!.y)
        assertEquals(seq.match!!.x, par.match!!.x)
        assertEquals(seq.match!!.y, par.match!!.y)
        assertEquals(seq.bestScore, par.bestScore, 1e-6)
    }

    @Test
    fun reusableFramePoolTripleBuffersWithoutAliasingPrevAndCurrFrames() {
        val pool = ReusableFramePool(slots = 3)
        val b0 = pool.acquire(100)
        val b1 = pool.acquire(100)
        val b2 = pool.acquire(100)
        val b3 = pool.acquire(100)
        assertTrue(b0 !== b1)
        assertTrue(b1 !== b2)
        assertSame("4th acquire wraps cleanly to slot 0 without reallocating", b0, b3)
    }

    @Test
    fun desktopStorePersistsLockedMacroSetAndRoundTrips1xZipBundle() {
        val dir1 = Files.createTempDirectory("wjust_pc_store1").toFile()
        val dir2 = Files.createTempDirectory("wjust_pc_store2").toFile()
        try {
            val store1 = DesktopStore(dir1)
            val img = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB).apply {
                val g = createGraphics()
                g.color = Color(230, 80, 40)
                g.fillRect(0, 0, 32, 32)
                g.dispose()
            }
            val savedImg = store1.saveImage("coc_attack", img)

            val rawSet = store1.saveMacroSet(MacroSet(name = "PC COC Set", description = "Set uji PC", colorHex = "#10B981"))
            val sub = store1.saveMacro(
                MacroSecurity.applySecurity(
                    macro = Macro(name = "Sub Attack", setId = rawSet.id, actions = listOf(ImageAction(imageName = savedImg.name))),
                    isLocked = false, isPassive = true, expiresAtEpochMs = 0L, expiryLabel = "", newPassword = "pcpass"
                )
            )
            val main = store1.saveMacro(
                Macro(
                    name = "Main PC Farm",
                    setId = rawSet.id,
                    actions = listOf(
                        ClickAction(x = 500, y = 300),
                        RunMacroAction(targetMacroId = sub.id, targetMacroName = sub.name, returnAfter = true)
                    )
                )
            )

            val expMs = MacroSecurity.computeExpiryEpochMs(store1.effectiveNowMs(), 15, ExpiryUnit.DAYS)
            val lockedSet = store1.saveMacroSetSecurity(
                MacroSecurity.applySetSecurity(
                    set = rawSet,
                    isLocked = true,
                    expiresAtEpochMs = expMs,
                    expiryLabel = "15 Hari",
                    newPassword = "pcpass",
                    expiryDurationAmount = 15,
                    expiryDurationUnit = ExpiryUnit.DAYS
                )
            )

            val zipOut = ByteArrayOutputStream()
            val expSummary = store1.exportMacroSetZip(lockedSet, zipOut, singleUseImport = true)
            assertEquals(1, expSummary.setsExported)
            assertEquals(2, expSummary.macrosExported)
            assertEquals(1, expSummary.imagesExported)
            assertTrue(expSummary.singleUseImport)

            val zipBytes = zipOut.toByteArray()
            val store2 = DesktopStore(dir2)
            val imp = store2.importFromStream(ByteArrayInputStream(zipBytes))
            assertEquals(1, imp.setsImported)
            assertEquals(2, imp.macrosImported)
            assertEquals(1, imp.imagesImported)
            assertTrue(imp.singleUseImport)

            val importedSet = store2.macroSets.value.first { it.name == "PC COC Set" }
            assertTrue(importedSet.isLocked)
            assertTrue(MacroSecurity.verifySetPassword(importedSet, "pcpass"))
            assertFalse(MacroSecurity.isSetTampered(importedSet))

            val importedMain = store2.macros.value.first { it.name == "Main PC Farm" }
            val importedSub = store2.macros.value.first { it.name == "Sub Attack" }
            assertEquals(importedSet.id, importedMain.setId)
            assertEquals(importedSet.id, importedSub.setId)
            assertEquals(importedSub.id, (importedMain.actions[1] as RunMacroAction).targetMacroId)
            assertTrue(importedMain.isLocked)
            assertNotNull(store2.loadTemplate(savedImg.name))

            // Second import of the same 1x Macro Set ZIP into store2 must fail!
            try {
                store2.importFromStream(ByteArrayInputStream(zipBytes))
                fail("Expected SingleUseImportException on second import")
            } catch (_: SingleUseImportException) {
                // Expected
            }
        } finally {
            dir1.deleteRecursively()
            dir2.deleteRecursively()
        }
    }

    @Test
    fun adbRawScreencapParserDecodes16ByteAnd12ByteHeadersAccurately() {
        val bridge = AdbBridge()
        fun makeRaw(headerSize: Int, w: Int, h: Int): ByteArray {
            val buf = ByteBuffer.allocate(headerSize + w * h * 4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(w)
            buf.putInt(h)
            buf.putInt(1) // RGBA_8888
            if (headerSize == 16) buf.putInt(0) // colorspace
            for (i in 0 until w * h) {
                buf.put(120.toByte()) // R
                buf.put(200.toByte()) // G
                buf.put(55.toByte())  // B
                buf.put(255.toByte()) // A
            }
            return buf.array()
        }

        val f16 = bridge.parseRawScreencap(makeRaw(16, 4, 3))
        assertNotNull(f16)
        assertEquals(4, f16!!.width)
        assertEquals(3, f16.height)
        assertEquals((0xFF shl 24) or (120 shl 16) or (200 shl 8) or 55, f16.argbAt(2, 1))

        val f12 = bridge.parseRawScreencap(makeRaw(12, 4, 3))
        assertNotNull(f12)
        assertEquals(4, f12!!.width)
        assertEquals((0xFF shl 24) or (120 shl 16) or (200 shl 8) or 55, f12.argbAt(0, 0))
    }

    @Test
    fun builtInPureKotlinHudDigitOcrReadsGameNumbersForNumericComparisons() {
        val img = BufferedImage(180, 52, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(24, 28, 36) // Dark COC resource bar background
        g.fillRect(0, 0, img.width, img.height)
        g.color = Color(255, 255, 255) // Bright HUD digits
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 28)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
        g.drawString("4500", 24, 36)
        g.dispose()

        val frame = bufferedImageToFrame(img)
        val blocks = DesktopOcrEngine.recognizeHudDigitsPureKotlin(frame, 0, 0)
        assertTrue("Expected HUD digits recognized, got $blocks", blocks.isNotEmpty())
        val text = blocks.joinToString(" ") { it.text }
        assertTrue("Expected recognized digits containing 4500, got '$text'", text.contains("4500"))

        val gtAction = TextAction(compareMode = TextCompareMode.GREATER_THAN, targetNumber = 3000.0)
        val eval = TextConditionEvaluator.evaluate(gtAction, blocks)
        assertTrue("4500 > 3000 must match via TextConditionEvaluator (got $eval)", eval.matched)
    }

    @Test
    fun hudPreprocessorBinarizesSmallDarkGameHudWithoutWhitePaddingBias() {
        val smallHud = BufferedImage(48, 18, BufferedImage.TYPE_INT_RGB)
        val g = smallHud.createGraphics()
        g.color = Color(20, 24, 30) // Dark game HUD
        g.fillRect(0, 0, 48, 18)
        g.color = Color(255, 245, 200) // Bright gold/white digit bar in center
        g.fillRect(14, 4, 20, 10)
        g.dispose()

        val processed = DesktopOcrEngine.preprocessGameHud(smallHud, scale = 2)
        // Outer white padding must stay pure white
        assertEquals(0xFFFFFFFF.toInt(), processed.getRGB(2, 2))
        // Bright text in the middle must become pure black ink for OCR
        assertEquals(0xFF000000.toInt(), processed.getRGB(processed.width / 2, processed.height / 2))
        // Dark background inside HUD (just inside padding) must become pure white background
        assertEquals(0xFFFFFFFF.toInt(), processed.getRGB(14, 14))
    }
}
