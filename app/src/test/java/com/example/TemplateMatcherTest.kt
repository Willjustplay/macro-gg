package com.example

import com.example.vision.ColorUtil
import com.example.vision.Frame
import com.example.vision.PreparedTemplate
import com.example.vision.TemplateMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TemplateMatcherTest {

    private fun makePatternFrame(w: Int, h: Int, seed: Int = 1): Frame {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = ((x * (7 + seed) + y * (3 + seed * 2)) and 0xFF)
                val g = (((x + seed * 17) * 5 xor (y + seed * 9) * 11) and 0xFF)
                val b = ((x * 13 + y * (17 + seed * 3) + seed * 29) and 0xFF)
                px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Frame(w, h, px)
    }

    private fun stamp(bg: Frame, sprite: Frame, dstX: Int, dstY: Int, brightnessShift: Int = 0) {
        for (y in 0 until sprite.height) {
            for (x in 0 until sprite.width) {
                val p = sprite.pixels[y * sprite.width + x]
                val r = (((p shr 16) and 0xFF) + brightnessShift).coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) + brightnessShift).coerceIn(0, 255)
                val b = ((p and 0xFF) + brightnessShift).coerceIn(0, 255)
                bg.pixels[(dstY + y) * bg.width + (dstX + x)] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    @Test
    fun find_locatesExactAndSlightlyBrightnessShiftedTemplate() {
        val screen = Frame(320, 480, IntArray(320 * 480) { 0xFF1A2230.toInt() })
        val icon = makePatternFrame(48, 36)
        stamp(screen, icon, dstX = 137, dstY = 249, brightnessShift = 10)

        val tpl = PreparedTemplate("icon.png", icon)
        val match = TemplateMatcher.find(screen, tpl, threshold = 0.90)

        assertNotNull(match)
        assertTrue("x=${match!!.x}", abs(match.x - 137) <= 1)
        assertTrue("y=${match.y}", abs(match.y - 249) <= 1)
        assertTrue("score=${match.score}", match.score > 0.95)
        assertEquals(match.x + 24, match.centerX)
        assertEquals(match.y + 18, match.centerY)
    }

    @Test
    fun find_locatesRealWorldNonPowerOfTwoTemplatesAtArbitraryPhoneCoords() {
        // Regression test: previously non-power-of-2 coarseFactor (e.g. 5, 7, 9, 10) caused massive
        // integer-division coordinate drift and failed to detect real cropped game buttons.
        val screen = Frame(1080, 1920, IntArray(1080 * 1920) { 0xFF1E293B.toInt() })
        val cases = listOf(
            Triple(65 to 65, 513 to 877, "btn_65.png"),
            Triple(108 to 93, 731 to 1423, "btn_108.png"),
            Triple(155 to 110, 419 to 311, "btn_155.png"),
            Triple(22 to 18, 901 to 1655, "btn_22.png")
        )
        cases.forEachIndexed { idx, (size, pos, name) ->
            val icon = makePatternFrame(size.first, size.second, seed = idx + 1)
            stamp(screen, icon, dstX = pos.first, dstY = pos.second, brightnessShift = 6)
            val tpl = PreparedTemplate(name, icon)
            val outcome = TemplateMatcher.findWithStats(screen, tpl, threshold = 0.85)
            assertNotNull("Failed to find $name (bestScore=${outcome.bestScore})", outcome.match)
            assertTrue("x drift for $name: ${outcome.match!!.x} vs ${pos.first}", abs(outcome.match.x - pos.first) <= 1)
            assertTrue("y drift for $name: ${outcome.match.y} vs ${pos.second}", abs(outcome.match.y - pos.second) <= 1)
        }
    }

    @Test
    fun find_respectsSearchRegion() {
        val screen = Frame(300, 300, IntArray(300 * 300) { 0xFF101010.toInt() })
        val icon = makePatternFrame(32, 32)
        stamp(screen, icon, dstX = 200, dstY = 200)

        val tpl = PreparedTemplate("btn.png", icon)
        val missed = TemplateMatcher.find(screen, tpl, threshold = 0.85, regionLeft = 0, regionTop = 0, regionRight = 150, regionBottom = 150)
        assertNull(missed)

        val found = TemplateMatcher.find(screen, tpl, threshold = 0.85, regionLeft = 180, regionTop = 180, regionRight = 260, regionBottom = 260)
        assertNotNull(found)
        assertTrue(abs(found!!.x - 200) <= 1)
        assertTrue(abs(found.y - 200) <= 1)
    }

    @Test
    fun findAll_detectsMultipleDistinctOccurrences() {
        val screen = Frame(360, 360, IntArray(360 * 360) { 0xFF0E172A.toInt() })
        val icon = makePatternFrame(36, 36)
        stamp(screen, icon, 30, 40)
        stamp(screen, icon, 220, 250)

        val tpl = PreparedTemplate("coin.png", icon)
        val all = TemplateMatcher.findAll(screen, tpl, threshold = 0.90, maxResults = 5)
        assertEquals(2, all.size)
    }

    @Test
    fun colorUtil_parsesAndMatchesWithTolerance() {
        val argb = 0xFF2DD4BF.toInt()
        assertEquals("#2DD4BF", ColorUtil.toHex(argb))
        assertTrue(ColorUtil.matches(argb, "#2AD0BC", tolerance = 6))
        assertFalse(ColorUtil.matches(argb, "#FF0000", tolerance = 20))
    }

    @Test
    fun directDownsample_matchesTwoStepDownsampleAndFindsEdgeTemplates() {
        val screen = makePatternFrame(400, 300, seed = 7)
        for (factor in listOf(2, 4, 8)) {
            val expected = com.example.vision.GrayImage.fromFrame(screen, 13, 19, 321, 241).downsample(factor)
            val direct = com.example.vision.GrayImage.fromFrameDownsampled(screen, 13, 19, 321, 241, factor)
            assertEquals(expected.width, direct.width)
            assertEquals(expected.height, direct.height)
            for (i in expected.data.indices) {
                assertTrue(
                    "Mismatch at factor=$factor idx=$i: ${expected.data[i]} vs ${direct.data[i]}",
                    abs(expected.data[i] - direct.data[i]) < 0.01f
                )
            }
        }

        // Verify local-patch refinement works even when template touches right/bottom edge of frame
        val canvas = Frame(320, 240, IntArray(320 * 240) { 0xFF0F172A.toInt() })
        val edgeIcon = makePatternFrame(64, 60, seed = 11)
        stamp(canvas, edgeIcon, dstX = 320 - 64, dstY = 240 - 60)
        val tpl = PreparedTemplate("edge.png", edgeIcon)
        val outcome = TemplateMatcher.findWithStats(canvas, tpl, threshold = 0.90)
        assertNotNull("Expected match at bottom-right edge", outcome.match)
        assertTrue("x=${outcome.match!!.x}", abs(outcome.match.x - (320 - 64)) <= 1)
        assertTrue("y=${outcome.match.y}", abs(outcome.match.y - (240 - 60)) <= 1)
        assertTrue("score=${outcome.bestScore}", outcome.bestScore >= 0.96)

        // Verify absent template returns cleanly without false positives
        val blank = Frame(320, 240, IntArray(320 * 240) { 0xFF111827.toInt() })
        val absent = TemplateMatcher.findWithStats(blank, tpl, threshold = 0.85)
        assertNull(absent.match)
    }
}
