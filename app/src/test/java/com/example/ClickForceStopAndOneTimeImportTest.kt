package com.example

import com.example.automation.LogLevel
import com.example.automation.MacroRunner
import com.example.automation.MacroRuntime
import com.example.data.ClickAction
import com.example.data.ExitAppAction
import com.example.data.ExitAppMode
import com.example.data.ExpiryUnit
import com.example.data.Macro
import com.example.data.MacroBundleCodec
import com.example.data.MacroSecurity
import com.example.data.RecentsSwipeDirection
import com.example.data.RecordedGesture
import com.example.data.SingleUseImportException
import com.example.vision.Frame
import com.example.vision.PreparedTemplate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ClickForceStopAndOneTimeImportTest {

    @Test
    fun clickActionClampsJitterWithinLiveScreenBoundsAndUsesReliableDefaultDuration() = runTest {
        val clicks = mutableListOf<Triple<Int, Int, Long>>()
        val runtime = object : MacroRuntime {
            override val screenWidth = 1280
            override val screenHeight = 720
            override fun isStopped() = false
            override suspend fun waitIfPaused() {}
            override suspend fun sleep(ms: Long) {}
            override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
                clicks += Triple(x, y, durationMs)
                return true
            }
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
            override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float) = true
            override fun captureFrame(): Frame? = null
            override fun samplePixelArgb(x: Int, y: Int): Int? = null
            override fun loadTemplate(name: String): PreparedTemplate? = null
            override fun log(level: LogLevel, message: String) {}
        }

        val defaultClick = ClickAction(x = 0, y = 0, repeat = 10, repeatIntervalMs = 0L, delayAfterMs = 0L)
        assertTrue("Default click duration should be >= 60ms for reliable game touch", defaultClick.durationMs >= 60L)

        val macro = Macro(
            name = "Edge Click Clamp",
            sourceWidth = 1280,
            sourceHeight = 720,
            jitterPx = 40,
            actions = listOf(
                defaultClick,
                ClickAction(x = 1280, y = 720, repeat = 10, repeatIntervalMs = 0L, delayAfterMs = 0L)
            )
        )

        MacroRunner(runtime).run(macro)

        assertEquals(20, clicks.size)
        for ((tx, ty, dur) in clicks) {
            assertTrue("X=$tx must be clamped within [1, 1278]", tx in 1..1278)
            assertTrue("Y=$ty must be clamped within [1, 718]", ty in 1..718)
            assertTrue("Duration=$dur must be >= 60ms", dur >= 60L)
        }
    }

    @Test
    fun exitAppForwardsForceStopBackgroundAndForceStopAppMode() = runTest {
        val calls = mutableListOf<List<Any>>()
        val runtime = object : MacroRuntime {
            override fun isStopped() = false
            override suspend fun waitIfPaused() {}
            override suspend fun sleep(ms: Long) {}
            override suspend fun click(x: Int, y: Int, durationMs: Long) = true
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
            override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float) = true
            override suspend fun exitApp(
                mode: ExitAppMode,
                packageName: String,
                recentsSwipeDirection: RecentsSwipeDirection,
                forceStopBackground: Boolean
            ): Boolean {
                calls += listOf(mode, packageName, recentsSwipeDirection, forceStopBackground)
                return true
            }
            override fun captureFrame(): Frame? = null
            override fun samplePixelArgb(x: Int, y: Int): Int? = null
            override fun loadTemplate(name: String): PreparedTemplate? = null
            override fun log(level: LogLevel, message: String) {}
        }

        val macro = Macro(
            name = "Exit Modes",
            actions = listOf(
                ExitAppAction(
                    mode = ExitAppMode.CLOSE_RECENT_AND_HOME,
                    packageName = "com.supercell.clashofclans",
                    recentsSwipeDirection = RecentsSwipeDirection.AUTO,
                    forceStopBackground = true,
                    delayAfterMs = 0L
                ),
                ExitAppAction(
                    mode = ExitAppMode.FORCE_STOP_APP,
                    packageName = "com.supercell.clashofclans",
                    delayAfterMs = 0L
                )
            )
        )

        MacroRunner(runtime).run(macro)

        assertEquals(2, calls.size)
        assertEquals(listOf(ExitAppMode.CLOSE_RECENT_AND_HOME, "com.supercell.clashofclans", RecentsSwipeDirection.AUTO, true), calls[0])
        assertEquals(listOf(ExitAppMode.FORCE_STOP_APP, "com.supercell.clashofclans", RecentsSwipeDirection.AUTO, true), calls[1])
    }

    @Test
    fun singleUseExportRejectsSecondImportAndAllowsFreshExportFromSameCreatorMacro() {
        val now = 1_750_000_000_000L
        val exp = MacroSecurity.computeExpiryEpochMs(now, 7, ExpiryUnit.DAYS)
        val creatorMacro = MacroSecurity.applySecurity(
            macro = Macro(
                id = "creator-id-1",
                originId = "origin-vip-1",
                name = "VIP Farm",
                actions = listOf(ClickAction(x = 500, y = 400))
            ),
            isLocked = true,
            isPassive = false,
            expiresAtEpochMs = exp,
            expiryLabel = "7 Hari",
            newPassword = "secret",
            expiryDurationAmount = 7,
            expiryDurationUnit = ExpiryUnit.DAYS
        )

        // Export #1 with singleUseImport = true
        val zip1Out = ByteArrayOutputStream()
        val exp1 = MacroBundleCodec.writeMacroZip(
            root = creatorMacro,
            lookupMacro = { null },
            loadImage = { null },
            output = zip1Out,
            singleUseImport = true,
            exportedAtEpochMs = now
        )
        assertTrue(exp1.singleUseImport)
        assertTrue(exp1.importToken.isNotBlank())
        val zip1Bytes = zip1Out.toByteArray()

        // 1st import succeeds & produces an untampered locked macro
        val consumedTokens = LinkedHashSet<String>()
        val decoded1 = MacroBundleCodec.readBundle(ByteArrayInputStream(zip1Bytes))
        val tokens1 = MacroBundleCodec.validateAndExtractOneTimeTokens(decoded1, consumedTokens)
        assertEquals(setOf(exp1.importToken), tokens1)
        val imported1 = decoded1.macros.first()
        assertFalse("Imported 1x locked macro must have valid security seal", MacroSecurity.isTampered(imported1))
        consumedTokens.addAll(tokens1)

        // 2nd import of the EXACT SAME ZIP #1 must be rejected!
        val decoded1Replay = MacroBundleCodec.readBundle(ByteArrayInputStream(zip1Bytes))
        try {
            MacroBundleCodec.validateAndExtractOneTimeTokens(decoded1Replay, consumedTokens)
            fail("Re-importing the same 1x ZIP must throw SingleUseImportException")
        } catch (e: SingleUseImportException) {
            assertTrue(e.message!!.contains("1 kali"))
        }

        // Export #2 from the same untouched creator macro gets a fresh unique token and imports cleanly
        val zip2Out = ByteArrayOutputStream()
        val exp2 = MacroBundleCodec.writeMacroZip(
            root = creatorMacro,
            lookupMacro = { null },
            loadImage = { null },
            output = zip2Out,
            singleUseImport = true,
            exportedAtEpochMs = now + 60_000L
        )
        assertNotEquals("Each 1x export must mint a fresh unique token", exp1.importToken, exp2.importToken)

        val decoded2 = MacroBundleCodec.readBundle(ByteArrayInputStream(zip2Out.toByteArray()))
        val tokens2 = MacroBundleCodec.validateAndExtractOneTimeTokens(decoded2, consumedTokens)
        assertEquals(setOf(exp2.importToken), tokens2)
        val merged = MacroSecurity.mergeOverwrittenMacro(imported1, decoded2.macros.first(), now + 60_000L)
        assertTrue("Overwriting with fresh 1x ZIP should stack deadline", merged.deadlineExtended)
        assertFalse("Merged macro must remain untampered", MacroSecurity.isTampered(merged.merged))
    }

    @Test
    fun singleUseTamperAttemptsAreDetectedAndRejected() {
        val now = 1_750_000_000_000L
        val lockedMacro = MacroSecurity.applySecurity(
            macro = Macro(
                id = "creator-id-2",
                originId = "origin-tamper",
                name = "Locked 1x",
                actions = listOf(ClickAction(x = 300, y = 300))
            ),
            isLocked = true,
            isPassive = false,
            expiresAtEpochMs = 0L,
            expiryLabel = "",
            newPassword = "pass"
        )

        val zipOut = ByteArrayOutputStream()
        val exp = MacroBundleCodec.writeMacroZip(
            root = lockedMacro,
            lookupMacro = { null },
            loadImage = { null },
            output = zipOut,
            singleUseImport = true,
            exportedAtEpochMs = now
        )
        val originalEntries = readAllZipEntries(zipOut.toByteArray())
        assertTrue(originalEntries.containsKey("bundle_meta.json"))
        assertTrue(originalEntries.containsKey("macro.json"))

        // Attack A: Delete bundle_meta.json and try to replay after token is consumed
        val withoutMetaZip = writeZipEntries(originalEntries.filterKeys { it != "bundle_meta.json" })
        val decodedNoMeta = MacroBundleCodec.readBundle(ByteArrayInputStream(withoutMetaZip))
        try {
            MacroBundleCodec.validateAndExtractOneTimeTokens(decodedNoMeta, setOf(exp.importToken))
            fail("Deleting bundle_meta.json must NOT bypass consumed token stored in macro.json")
        } catch (_: SingleUseImportException) {
            // expected
        }

        // Attack B: Edit token inside macro.json to a forged random string
        val macroJsonText = originalEntries["macro.json"]!!.toString(Charsets.UTF_8)
        val forgedMacroJson = macroJsonText.replace(exp.importToken, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val forgedZip = writeZipEntries(
            mapOf("macro.json" to forgedMacroJson.toByteArray(Charsets.UTF_8))
        )
        val decodedForged = MacroBundleCodec.readBundle(ByteArrayInputStream(forgedZip))
        try {
            MacroBundleCodec.validateAndExtractOneTimeTokens(decodedForged, emptySet())
            fail("Forging oneTimeImportToken in macro.json must fail seal validation")
        } catch (_: SingleUseImportException) {
            // expected
        }
        assertTrue("Forged token must also fail MacroSecurity.isTampered", MacroSecurity.isTampered(decodedForged.macros.first()))

        // Attack C: Strip BOTH oneTimeImportToken and oneTimeImportSeal from locked macro.json
        val stripped = decodedForged.macros.first().copy(oneTimeImportToken = "", oneTimeImportSeal = "")
        assertTrue("Stripping 1x token from a locked macro must invalidate securitySeal", MacroSecurity.isTampered(stripped))
    }

    private fun readAllZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                map[entry.name] = zis.readBytes()
                zis.closeEntry()
            }
        }
        return map
    }

    private fun writeZipEntries(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, data) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
