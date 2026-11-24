package com.example

import com.example.automation.LogLevel
import com.example.automation.MacroRunner
import com.example.automation.MacroRuntime
import com.example.data.ClickAction
import com.example.data.ExpiryUnit
import com.example.data.Macro
import com.example.data.MacroBundleCodec
import com.example.data.MacroSecurity
import com.example.data.RecordedGesture
import com.example.data.RunDenialReason
import com.example.data.RunMacroAction
import com.example.data.summary
import com.example.vision.Frame
import com.example.vision.PreparedTemplate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class MacroSecurityAndRandomTest {

    private fun fakeRuntime(
        clicks: MutableList<Pair<Int, Int>>,
        nowProvider: () -> Long = { 1_700_000_000_000L },
        macrosById: Map<String, Macro> = emptyMap(),
        logs: MutableList<String> = mutableListOf()
    ): MacroRuntime = object : MacroRuntime {
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
        override fun loadMacro(id: String): Macro? = macrosById[id]
        override fun log(level: LogLevel, message: String) { logs += message }
        override fun nowMs(): Long = nowProvider()
    }

    @Test
    fun randomEventChancePercentControlsExecutionProbability() = runTest {
        val clicks = mutableListOf<Pair<Int, Int>>()
        val macro = Macro(
            name = "Random Event Test",
            loopCount = 100,
            loopDelayMs = 0,
            actions = listOf(
                ClickAction(x = 1, y = 1, chancePercent = 100, delayAfterMs = 0),
                ClickAction(x = 2, y = 2, chancePercent = 0, delayAfterMs = 0),
                ClickAction(x = 3, y = 3, chancePercent = 50, delayAfterMs = 0)
            )
        )

        MacroRunner(fakeRuntime(clicks), Random(42)).run(macro)

        val alwaysCount = clicks.count { it == (1 to 1) }
        val neverCount = clicks.count { it == (2 to 2) }
        val halfCount = clicks.count { it == (3 to 3) }

        assertEquals(100, alwaysCount)
        assertEquals(0, neverCount)
        assertTrue("Expected ~50 random hits, got $halfCount", halfCount in 35..65)
        assertTrue(ClickAction(x = 1, y = 1, chancePercent = 40).summary().contains("40%"))
        assertEquals(0, ClickAction(x = 1, y = 1).withChancePercent(-10).chancePercent)
        assertEquals(100, ClickAction(x = 1, y = 1).withChancePercent(250).chancePercent)
    }

    @Test
    fun passwordLockAndExpiryDaysMonthsEnforcementWorks() = runTest {
        val baseNow = 1_700_000_000_000L // Nov 2023 UTC
        val deadline7Days = MacroSecurity.computeExpiryEpochMs(baseNow, 7, ExpiryUnit.DAYS)
        val deadline2Months = MacroSecurity.computeExpiryEpochMs(baseNow, 2, ExpiryUnit.MONTHS)
        assertEquals(baseNow + 7L * 86_400_000L, deadline7Days)
        assertTrue(deadline2Months > baseNow + 58L * 86_400_000L)

        val lockedTimed = MacroSecurity.applySecurity(
            macro = Macro(
                id = "m_timed",
                name = "VIP Macro",
                loopCount = 1,
                loopDelayMs = 0,
                actions = listOf(ClickAction(x = 50, y = 50, delayAfterMs = 0))
            ),
            isLocked = true,
            isPassive = false,
            expiresAtEpochMs = deadline7Days,
            expiryLabel = "7 Hari",
            newPassword = "s4nd1ku"
        )

        assertTrue(lockedTimed.isLocked)
        assertTrue(MacroSecurity.verifyPassword(lockedTimed, "s4nd1ku"))
        assertFalse(MacroSecurity.verifyPassword(lockedTimed, "salah"))
        assertFalse(MacroSecurity.isTampered(lockedTimed))
        assertNull(MacroSecurity.checkDirectRun(lockedTimed, baseNow + 1000L))
        assertEquals("Sisa 7 hari", MacroSecurity.remainingTimeLabel(lockedTimed, baseNow + 1000L))

        // Before deadline: runs normally
        val clicks = mutableListOf<Pair<Int, Int>>()
        MacroRunner(fakeRuntime(clicks, nowProvider = { baseNow + 3L * 86_400_000L })).run(lockedTimed)
        assertEquals(listOf(50 to 50), clicks)

        // After deadline: blocked!
        clicks.clear()
        val afterExpiry = deadline7Days + 1_000L
        assertEquals(RunDenialReason.EXPIRED, MacroSecurity.checkDirectRun(lockedTimed, afterExpiry))
        assertEquals("Kedaluwarsa", MacroSecurity.remainingTimeLabel(lockedTimed, afterExpiry))
        MacroRunner(fakeRuntime(clicks, nowProvider = { afterExpiry })).run(lockedTimed)
        assertTrue(clicks.isEmpty())

        // Manual JSON tampering (e.g. resetting expiresAtEpochMs to 0 without updating seal) is caught!
        val tampered = lockedTimed.copy(expiresAtEpochMs = 0L)
        assertTrue(MacroSecurity.isTampered(tampered))
        assertEquals(RunDenialReason.TAMPERED, MacroSecurity.checkDirectRun(tampered, baseNow))
        MacroRunner(fakeRuntime(clicks, nowProvider = { baseNow })).run(tampered)
        assertTrue(clicks.isEmpty())
    }

    @Test
    fun passiveModeBlocksDirectRunButAllowsLinkedExecutionFromActiveMacro() = runTest {
        val baseNow = 1_700_000_000_000L
        val passiveHelper = MacroSecurity.applySecurity(
            macro = Macro(
                id = "helper_passive",
                name = "Helper Pasif",
                loopCount = 1,
                loopDelayMs = 0,
                actions = listOf(ClickAction(x = 777, y = 888, delayAfterMs = 0))
            ),
            isLocked = false,
            isPassive = true,
            expiresAtEpochMs = 0L,
            expiryLabel = "",
            newPassword = "pass123"
        )

        // Direct run of passive macro is DENIED
        assertEquals(RunDenialReason.PASSIVE_DIRECT, MacroSecurity.checkDirectRun(passiveHelper, baseNow))
        val directClicks = mutableListOf<Pair<Int, Int>>()
        MacroRunner(fakeRuntime(directClicks, nowProvider = { baseNow })).run(passiveHelper)
        assertTrue("Passive macro must not run directly", directClicks.isEmpty())

        // Calling passive macro from an active macro via RunMacroAction is ALLOWED
        val activeCaller = Macro(
            id = "caller_active",
            name = "Macro Utama Aktif",
            loopCount = 1,
            loopDelayMs = 0,
            actions = listOf(
                ClickAction(x = 10, y = 20, delayAfterMs = 0),
                RunMacroAction(targetMacroId = "helper_passive", targetMacroName = "Helper Pasif", returnAfter = true, delayAfterMs = 0),
                ClickAction(x = 30, y = 40, delayAfterMs = 0)
            )
        )
        val linkedClicks = mutableListOf<Pair<Int, Int>>()
        MacroRunner(
            fakeRuntime(
                clicks = linkedClicks,
                nowProvider = { baseNow },
                macrosById = mapOf(passiveHelper.id to passiveHelper)
            )
        ).run(activeCaller)
        assertEquals(listOf(10 to 20, 777 to 888, 30 to 40), linkedClicks)

        // Expired passive macro is STILL blocked even when called by active macro!
        val expiredPassive = MacroSecurity.applySecurity(
            macro = passiveHelper,
            isLocked = false,
            isPassive = true,
            expiresAtEpochMs = baseNow - 5_000L,
            expiryLabel = "1 Hari"
        )
        val expiredClicks = mutableListOf<Pair<Int, Int>>()
        MacroRunner(
            fakeRuntime(
                clicks = expiredClicks,
                nowProvider = { baseNow },
                macrosById = mapOf(expiredPassive.id to expiredPassive)
            )
        ).run(activeCaller)
        assertEquals(listOf(10 to 20, 30 to 40), expiredClicks)

        // Turning passive OFF with password re-enables direct run
        val turnedOff = MacroSecurity.applySecurity(
            macro = passiveHelper,
            isLocked = false,
            isPassive = false,
            expiresAtEpochMs = 0L,
            expiryLabel = ""
        )
        assertNull(MacroSecurity.checkDirectRun(turnedOff, baseNow))
    }

    @Test
    fun zipBundlePreservesCustomMacroLogoAndValidSecuritySealAcrossImport() {
        val baseNow = 1_700_000_000_000L
        val fakeLogoPng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)
        val protectedMacro = MacroSecurity.applySecurity(
            macro = Macro(
                id = "orig_id_1",
                name = "Berlogo & Terkunci",
                logoImage = "logo_orig_id_1.png",
                actions = listOf(ClickAction(x = 100, y = 200, chancePercent = 75))
            ),
            isLocked = true,
            isPassive = true,
            expiresAtEpochMs = MacroSecurity.computeExpiryEpochMs(baseNow, 1, ExpiryUnit.MONTHS),
            expiryLabel = "1 Bulan",
            newPassword = "mySecret"
        )

        val out = ByteArrayOutputStream()
        MacroBundleCodec.writeMacroZip(
            root = protectedMacro,
            lookupMacro = { null },
            loadImage = { null },
            output = out,
            loadMacroIcon = { name -> if (name == "logo_orig_id_1.png") name to fakeLogoPng else null }
        )

        val decoded = MacroBundleCodec.readBundle(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, decoded.macros.size)
        val imported = decoded.macros.first()
        assertNotEquals("orig_id_1", imported.id)
        assertEquals("logo_orig_id_1.png", imported.logoImage)
        assertTrue("logo_orig_id_1.png" in decoded.macroIcons.keys)
        assertEquals(75, imported.actions.first().chancePercent)
        assertTrue(imported.isLocked)
        assertTrue(imported.isPassive)
        assertFalse("Security seal must remain valid after import ID remap", MacroSecurity.isTampered(imported))
        assertTrue(MacroSecurity.verifyPassword(imported, "mySecret"))
    }
}
