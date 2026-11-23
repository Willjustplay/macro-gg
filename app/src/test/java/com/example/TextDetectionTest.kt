package com.example

import com.example.automation.LogLevel
import com.example.automation.MacroRunner
import com.example.automation.MacroRuntime
import com.example.data.ClickAction
import com.example.data.ContainerRef
import com.example.data.ImageAction
import com.example.data.LocalStore
import com.example.data.LoopAction
import com.example.data.Macro
import com.example.data.MacroBundleCodec
import com.example.data.RecordedGesture
import com.example.data.Region
import com.example.data.Slot
import com.example.data.TextAction
import com.example.data.TextCompareMode
import com.example.data.WaitAction
import com.example.data.reorderInContainer
import com.example.vision.Frame
import com.example.vision.OcrBlock
import com.example.vision.PreparedTemplate
import com.example.vision.TextConditionEvaluator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TextDetectionTest {

    @Test
    fun smartNumberExtractionHandlesFormatsAndSuffixes() {
        assertEquals(listOf(450.0, 1000.0), TextConditionEvaluator.extractNumbers("HP: 450 / 1000"))
        assertEquals(listOf(12_500.0), TextConditionEvaluator.extractNumbers("Gold 12.5K"))
        assertEquals(listOf(2_000_000.0), TextConditionEvaluator.extractNumbers("2M"))
        assertEquals(listOf(1_250_000.0), TextConditionEvaluator.extractNumbers("Rp 1.250.000"))
        assertEquals(listOf(1250.0), TextConditionEvaluator.extractNumbers("Score: 1,250"))
        assertEquals(listOf(-5.5), TextConditionEvaluator.extractNumbers("Delta -5.5"))
    }

    @Test
    fun evaluatesTextAndNumericLessGreaterConditions() {
        val blocks = listOf(
            OcrBlock("HP: 320", 100, 50, 220, 90),
            OcrBlock("Victory!", 300, 400, 500, 460)
        )

        val textMatch = TextConditionEvaluator.evaluate(
            TextAction(compareMode = TextCompareMode.CONTAINS, query = "victory"),
            blocks
        )
        assertTrue(textMatch.matched)
        assertEquals(400, textMatch.matchedBlock?.centerX)

        val lessThanMatch = TextConditionEvaluator.evaluate(
            TextAction(compareMode = TextCompareMode.LESS_THAN, targetNumber = 500.0),
            blocks
        )
        assertTrue(lessThanMatch.matched)
        assertEquals(320.0, lessThanMatch.parsedNumber!!, 0.001)

        val greaterThanFail = TextConditionEvaluator.evaluate(
            TextAction(compareMode = TextCompareMode.GREATER_THAN, targetNumber = 500.0),
            blocks
        )
        assertFalse(greaterThanFail.matched)
    }

    @Test
    fun macroRunnerExecutesTextActionThenAndElseBranches() = runTest {
        val clicks = mutableListOf<Pair<Int, Int>>()
        var fakeBlocks = listOf(OcrBlock("Stamina 15", 200, 100, 300, 140))

        val runtime = object : MacroRuntime {
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
            override suspend fun recognizeText(region: Region?): List<OcrBlock> = fakeBlocks
            override fun loadTemplate(name: String): PreparedTemplate? = null
            override fun log(level: LogLevel, message: String) {}
        }

        val macro = Macro(
            name = "Low Stamina Refill",
            loopCount = 1,
            loopDelayMs = 0,
            actions = listOf(
                TextAction(
                    compareMode = TextCompareMode.LESS_THAN,
                    targetNumber = 20.0,
                    timeoutMs = 0,
                    clickOnFound = true,
                    thenActions = listOf(ClickAction(x = 800, y = 900, delayAfterMs = 0)),
                    elseActions = listOf(ClickAction(x = 111, y = 222, delayAfterMs = 0)),
                    delayAfterMs = 0
                )
            )
        )

        MacroRunner(runtime).run(macro)
        // First click = matched block center (250, 120), second click = thenActions (800, 900)
        assertEquals(listOf(250 to 120, 800 to 900), clicks)

        // Now stamina is 80 (not < 20) -> should execute elseActions (111, 222)
        clicks.clear()
        fakeBlocks = listOf(OcrBlock("Stamina 80", 200, 100, 300, 140))
        MacroRunner(runtime).run(macro)
        assertEquals(listOf(111 to 222), clicks)
    }

    @Test
    fun folderAwareImageNormalizationAndZipBundlePreservesFolders() {
        assertEquals("boss/tombol_play.png", LocalStore.normalizeImageName("boss/tombol_play.png"))
        assertEquals("Boss_1/skill.png", LocalStore.normalizeImageName("Boss 1/skill.png"))
        assertEquals("root_btn.png", LocalStore.normalizeImageName("root_btn.png"))

        val fakePng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 10, 20)
        val macro = Macro(
            id = "m1",
            name = "Folder Macro",
            actions = listOf(ImageAction(imageName = "boss/attack.png"))
        )
        val out = ByteArrayOutputStream()
        val exp = MacroBundleCodec.writeMacroZip(
            root = macro,
            lookupMacro = { null },
            loadImage = { name -> if (name == "boss/attack.png") name to fakePng else null },
            output = out
        )
        assertEquals(1, exp.imagesExported)

        val decoded = MacroBundleCodec.readBundle(ByteArrayInputStream(out.toByteArray()))
        assertTrue("boss/attack.png" in decoded.images.keys)
    }

    @Test
    fun dragAndDropReorderWorksForRootAndNestedBranches() {
        val c1 = ClickAction(id = "c1", x = 10, y = 10)
        val c2 = ClickAction(id = "c2", x = 20, y = 20)
        val c3 = ClickAction(id = "c3", x = 30, y = 30)
        val e1 = WaitAction(id = "e1", durationMs = 100)
        val e2 = WaitAction(id = "e2", durationMs = 200)
        val img = ImageAction(
            id = "img1",
            imageName = "btn.png",
            thenActions = listOf(c1, c2, c3),
            elseActions = listOf(e1, e2)
        )
        val b1 = ClickAction(id = "b1", x = 1, y = 1)
        val b2 = ClickAction(id = "b2", x = 2, y = 2)
        val loop = LoopAction(id = "loop1", count = 3, actions = listOf(b1, b2))
        val tail = WaitAction(id = "w1", durationMs = 500)

        val root = listOf(img, loop, tail)

        // Root drag: move index 0 -> index 2
        val rootReordered = root.reorderInContainer(ContainerRef.ROOT, 0, 2)
        assertEquals(listOf("loop1", "w1", "img1"), rootReordered.map { it.id })

        // Nested THEN drag: move index 2 -> index 0
        val thenReordered = rootReordered.reorderInContainer(ContainerRef("img1", Slot.THEN), 2, 0)
        val updatedImg = thenReordered.last() as ImageAction
        assertEquals(listOf("c3", "c1", "c2"), updatedImg.thenActions.map { it.id })

        // Nested ELSE drag: swap 0 <-> 1
        val elseReordered = thenReordered.reorderInContainer(ContainerRef("img1", Slot.ELSE), 0, 1)
        val updatedImg2 = elseReordered.last() as ImageAction
        assertEquals(listOf("e2", "e1"), updatedImg2.elseActions.map { it.id })

        // Nested LOOP BODY drag: swap 1 -> 0
        val bodyReordered = elseReordered.reorderInContainer(ContainerRef("loop1", Slot.BODY), 1, 0)
        val updatedLoop = bodyReordered.first() as LoopAction
        assertEquals(listOf("b2", "b1"), updatedLoop.actions.map { it.id })

        // Out-of-bounds / no-op safety
        assertEquals(bodyReordered, bodyReordered.reorderInContainer(ContainerRef.ROOT, -1, 5))
        assertEquals(bodyReordered, bodyReordered.reorderInContainer(ContainerRef.ROOT, 1, 1))
    }
}
