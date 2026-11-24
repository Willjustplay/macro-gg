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
        assertEquals("orig_id_1", imported.originId)
    }

    @Test
    fun passiveOffIsSkippedCleanlyWithoutHangingOrTrippingStuckWatchdog() = runTest {
        var fakeClock = 1_700_000_000_000L
        val passiveOff = MacroSecurity.applySecurity(
            macro = Macro(
                id = "sub_passive_off",
                name = "Sub Pasif OFF",
                loopCount = 1,
                loopDelayMs = 0,
                actions = listOf(ClickAction(x = 999, y = 999, delayAfterMs = 0))
            ),
            isLocked = false,
            isPassive = true,
            passiveEnabled = false,
            expiresAtEpochMs = 0L,
            expiryLabel = "",
            newPassword = "pw123"
        )

        // Direct run blocked; linked run returns PASSIVE_OFF
        assertEquals(RunDenialReason.PASSIVE_DIRECT, MacroSecurity.checkDirectRun(passiveOff, fakeClock))
        assertEquals(RunDenialReason.PASSIVE_OFF, MacroSecurity.checkLinkedRun(passiveOff, fakeClock))
        assertFalse(MacroSecurity.isTampered(passiveOff))

        // Manual JSON tampering to flip passiveEnabled = true without valid seal MUST be caught
        val tampered = passiveOff.copy(passiveEnabled = true)
        assertTrue(MacroSecurity.isTampered(tampered))
        assertEquals(RunDenialReason.TAMPERED, MacroSecurity.checkLinkedRun(tampered, fakeClock))

        // Active macro calls passive OFF with BOTH returnAfter=true and returnAfter=false,
        // and has a strict stuck watchdog (5s STOP_MACRO) while time advances 4s per action.
        val activeCaller = Macro(
            id = "active_main",
            name = "Active Caller",
            loopCount = 3,
            loopDelayMs = 0,
            autoRecoverStuckSec = 5,
            stuckRecoveryMode = com.example.data.StuckRecoveryMode.STOP_MACRO,
            actions = listOf(
                ClickAction(x = 10, y = 10, delayAfterMs = 0),
                RunMacroAction(targetMacroId = passiveOff.id, targetMacroName = passiveOff.name, returnAfter = true, delayAfterMs = 0),
                RunMacroAction(targetMacroId = passiveOff.id, targetMacroName = passiveOff.name, returnAfter = false, delayAfterMs = 0),
                ClickAction(x = 20, y = 20, delayAfterMs = 0)
            )
        )

        val clicks = mutableListOf<Pair<Int, Int>>()
        val logs = mutableListOf<String>()
        val runtime = fakeRuntime(
            clicks = clicks,
            nowProvider = {
                fakeClock += 1_000L
                fakeClock
            },
            macrosById = mapOf(passiveOff.id to passiveOff),
            logs = logs
        )

        MacroRunner(runtime).run(activeCaller)

        // All 3 loops must complete both clicks; passive OFF click (999,999) must NEVER run; watchdog must NOT trip!
        assertEquals(
            listOf(10 to 10, 20 to 20, 10 to 10, 20 to 20, 10 to 10, 20 to 20),
            clicks
        )
        assertTrue(logs.none { it.contains("Auto-Perbaiki") })
        assertTrue(logs.any { it.contains("sedang OFF") })

        // Now flip Passive OFF -> ON legitimately via MacroSecurity.togglePassiveEnabled
        val passiveOn = MacroSecurity.togglePassiveEnabled(passiveOff, true)
        assertTrue(passiveOn.passiveEnabled)
        assertFalse(MacroSecurity.isTampered(passiveOn))
        assertTrue(MacroSecurity.verifyPassword(passiveOn, "pw123"))
        assertNull(MacroSecurity.checkLinkedRun(passiveOn, fakeClock))

        val clicksWhenOn = mutableListOf<Pair<Int, Int>>()
        val singleLoopCaller = activeCaller.copy(
            loopCount = 1,
            actions = listOf(
                ClickAction(x = 10, y = 10, delayAfterMs = 0),
                RunMacroAction(targetMacroId = passiveOn.id, targetMacroName = passiveOn.name, returnAfter = true, delayAfterMs = 0),
                ClickAction(x = 20, y = 20, delayAfterMs = 0)
            )
        )
        MacroRunner(
            fakeRuntime(
                clicks = clicksWhenOn,
                nowProvider = { fakeClock },
                macrosById = mapOf(passiveOn.id to passiveOn)
            )
        ).run(singleLoopCaller)
        assertEquals(listOf(10 to 10, 999 to 999, 20 to 20), clicksWhenOn)
    }

    @Test
    fun midRunPassiveSwitchOffExitsSubMacroCleanlyAndContinuesActiveCaller() = runTest {
        val baseNow = 1_700_000_000_000L
        val passiveLoop = MacroSecurity.applySecurity(
            macro = Macro(
                id = "sub_loop",
                name = "Sub Loop Pasif",
                loopCount = 5,
                loopDelayMs = 0,
                actions = listOf(ClickAction(x = 55, y = 55, delayAfterMs = 0))
            ),
            isLocked = false,
            isPassive = true,
            passiveEnabled = true,
            expiresAtEpochMs = 0L,
            expiryLabel = "",
            newPassword = "secret"
        )
        var currentSub = passiveLoop
        val clicks = mutableListOf<Pair<Int, Int>>()

        val runtime = object : MacroRuntime {
            override fun isStopped() = false
            override suspend fun waitIfPaused() {}
            override suspend fun sleep(ms: Long) {}
            override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
                clicks += x to y
                // After the 1st click of the passive sub-macro, user toggles it OFF
                if (x == 55 && y == 55) {
                    currentSub = MacroSecurity.togglePassiveEnabled(currentSub, false)
                }
                return true
            }
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
            override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float) = true
            override fun captureFrame(): Frame? = null
            override fun samplePixelArgb(x: Int, y: Int): Int? = null
            override fun loadTemplate(name: String): PreparedTemplate? = null
            override fun loadMacro(id: String): Macro? = if (id == currentSub.id) currentSub else null
            override fun log(level: LogLevel, message: String) {}
            override fun nowMs(): Long = baseNow
        }

        val caller = Macro(
            id = "caller",
            name = "Caller",
            loopCount = 1,
            loopDelayMs = 0,
            actions = listOf(
                ClickAction(x = 1, y = 1, delayAfterMs = 0),
                RunMacroAction(targetMacroId = passiveLoop.id, targetMacroName = passiveLoop.name, returnAfter = true, delayAfterMs = 0),
                ClickAction(x = 2, y = 2, delayAfterMs = 0)
            )
        )
        MacroRunner(runtime).run(caller)

        // Sub-macro runs 1st iteration, then sees OFF on 2nd iteration and exits cleanly back to caller -> click (2,2) runs!
        assertEquals(listOf(1 to 1, 55 to 55, 2 to 2), clicks)
    }

    @Test
    fun overwritingSameMacroStacksExpiryDeadlinesAndPreservesIdentityAcrossZip() {
        val dayMs = 86_400_000L
        val baseNow = 1_700_000_000_000L

        // Existing macro on device has 4 days remaining
        val existing = MacroSecurity.applySecurity(
            macro = Macro(
                id = "local_id_99",
                originId = "orig_vip_01",
                name = "VIP Farm",
                logoImage = "logo_old.png",
                actions = listOf(ClickAction(x = 10, y = 10))
            ),
            isLocked = true,
            isPassive = false,
            expiresAtEpochMs = baseNow + 4L * dayMs,
            expiryLabel = "4 Hari",
            expiryDurationAmount = 4,
            expiryDurationUnit = ExpiryUnit.DAYS,
            newPassword = "vip123"
        )

        // Scenario 1: Incoming overwrite adds 7 Days -> 4 + 7 = 11 days remaining
        val incoming7d = MacroSecurity.applySecurity(
            macro = Macro(
                id = "remapped_import_id",
                originId = "orig_vip_01",
                name = "VIP Farm",
                actions = listOf(ClickAction(x = 50, y = 60))
            ),
            isLocked = true,
            isPassive = false,
            expiresAtEpochMs = baseNow + 7L * dayMs,
            expiryLabel = "7 Hari",
            expiryDurationAmount = 7,
            expiryDurationUnit = ExpiryUnit.DAYS,
            newPassword = "vip123"
        )

        val resDays = MacroSecurity.mergeOverwrittenMacro(existing, incoming7d, baseNow)
        assertTrue(resDays.deadlineExtended)
        assertEquals("local_id_99", resDays.merged.id)
        assertEquals("orig_vip_01", resDays.merged.originId)
        assertEquals("logo_old.png", resDays.merged.logoImage)
        assertEquals(baseNow + 11L * dayMs, resDays.merged.expiresAtEpochMs)
        assertFalse("Stacked macro seal must be valid", MacroSecurity.isTampered(resDays.merged))
        assertEquals(50, (resDays.merged.actions.first() as ClickAction).x)

        // Scenario 2: Incoming overwrite adds 1 Calendar Month onto the 11-day remaining deadline
        val incoming1m = MacroSecurity.applySecurity(
            macro = Macro(
                id = "remapped_import_2",
                originId = "orig_vip_01",
                name = "VIP Farm",
                actions = listOf(ClickAction(x = 88, y = 99))
            ),
            isLocked = true,
            isPassive = true,
            passiveEnabled = false,
            expiresAtEpochMs = MacroSecurity.computeExpiryEpochMs(baseNow, 1, ExpiryUnit.MONTHS),
            expiryLabel = "1 Bulan",
            expiryDurationAmount = 1,
            expiryDurationUnit = ExpiryUnit.MONTHS,
            newPassword = "vip123"
        )
        val resMonth = MacroSecurity.mergeOverwrittenMacro(resDays.merged, incoming1m, baseNow)
        assertTrue(resMonth.deadlineExtended)
        assertEquals(
            MacroSecurity.computeExpiryEpochMs(resDays.merged.expiresAtEpochMs, 1, ExpiryUnit.MONTHS),
            resMonth.merged.expiresAtEpochMs
        )
        assertTrue(resMonth.merged.isPassive)
        assertFalse(resMonth.merged.passiveEnabled)
        assertFalse(MacroSecurity.isTampered(resMonth.merged))

        // Scenario 3: Legacy incoming (expiryDurationAmount = 0) still adds remaining delta onto existing
        val legacyIncoming = incoming7d.copy(expiryDurationAmount = 0)
        val resLegacy = MacroSecurity.mergeOverwrittenMacro(existing, legacyIncoming, baseNow)
        assertTrue(resLegacy.deadlineExtended)
        assertEquals(baseNow + 11L * dayMs, resLegacy.merged.expiresAtEpochMs)
        assertFalse(MacroSecurity.isTampered(resLegacy.merged))

        // Scenario 4: Existing macro already expired -> adopts incoming deadline fresh without stacking expired gap
        val expiredExisting = existing.copy(
            expiresAtEpochMs = baseNow - 2L * dayMs
        ).let { it.copy(securitySeal = MacroSecurity.computeSeal(it)) }
        val resRenew = MacroSecurity.mergeOverwrittenMacro(expiredExisting, incoming7d, baseNow)
        assertFalse(resRenew.deadlineExtended)
        assertEquals(incoming7d.expiresAtEpochMs, resRenew.merged.expiresAtEpochMs)
        assertFalse(MacroSecurity.isTampered(resRenew.merged))

        // Scenario 5: Tampered incoming macro CANNOT be whitewashed by overwriting
        val tamperedIncoming = incoming7d.copy(expiresAtEpochMs = baseNow + 999L * dayMs)
        assertTrue(MacroSecurity.isTampered(tamperedIncoming))
        val resTampered = MacroSecurity.mergeOverwrittenMacro(existing, tamperedIncoming, baseNow)
        assertFalse(resTampered.deadlineExtended)
        assertTrue(MacroSecurity.isTampered(resTampered.merged))

        // Scenario 6: Full ZIP export -> decode -> overwrite stacks correctly and preserves originId & passiveEnabled
        val out = ByteArrayOutputStream()
        MacroBundleCodec.writeMacroZip(
            root = existing,
            lookupMacro = { null },
            loadImage = { null },
            output = out
        )
        val decoded = MacroBundleCodec.readBundle(ByteArrayInputStream(out.toByteArray())).macros.first()
        assertEquals("orig_vip_01", decoded.originId)
        assertEquals(4, decoded.expiryDurationAmount)
        assertEquals(ExpiryUnit.DAYS, decoded.expiryDurationUnit)
        assertFalse(MacroSecurity.isTampered(decoded))

        val stackedAfterZip = MacroSecurity.mergeOverwrittenMacro(existing, decoded, baseNow)
        assertTrue(stackedAfterZip.deadlineExtended)
        assertEquals(baseNow + 8L * dayMs, stackedAfterZip.merged.expiresAtEpochMs)
        assertFalse(MacroSecurity.isTampered(stackedAfterZip.merged))
    }

    @Test
    fun legacyV27SealWithoutPassiveEnabledRemainsValid() {
        val baseNow = 1_700_000_000_000L
        val salt = MacroSecurity.generateSalt()
        val hash = MacroSecurity.hashPassword("oldpass", salt)
        val exp = baseNow + 5L * 86_400_000L
        val legacyPayload = "WjustMacro::v2.7::Seal::9f3a8c41d2e7|locked=true|passive=true|exp=$exp|hash=$hash|salt=$salt"
        val legacySeal = java.security.MessageDigest.getInstance("SHA-256")
            .digest(legacyPayload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        val legacyMacro = Macro(
            id = "legacy_v27",
            name = "Legacy v2.7",
            isLocked = true,
            isPassive = true,
            passiveEnabled = true,
            expiresAtEpochMs = exp,
            lockSalt = salt,
            lockPasswordHash = hash,
            securitySeal = legacySeal
        )
        assertFalse("v2.7.0 exported macro seal must stay valid on v2.8.0", MacroSecurity.isTampered(legacyMacro))
        assertNull(MacroSecurity.checkLinkedRun(legacyMacro, baseNow))
    }

    @Test
    fun passiveToggleWithoutPasswordPreservesProtectionAndRejectsTampering() {
        val baseNow = 1_700_000_000_000L
        val passiveLocked = MacroSecurity.applySecurity(
            macro = Macro(
                id = "sub_locked",
                name = "Sub Terkunci",
                actions = listOf(ClickAction(x = 12, y = 34))
            ),
            isLocked = true,
            isPassive = true,
            passiveEnabled = true,
            expiresAtEpochMs = baseNow + 7L * 86_400_000L,
            expiryLabel = "7 Hari",
            newPassword = "creatorPass"
        )

        // Toggling Passive ON -> OFF -> ON works without passing any password while preserving lock, passive mode, expiry & password
        val toggledOff = MacroSecurity.togglePassiveEnabled(passiveLocked, false)
        assertTrue(toggledOff.isPassive)
        assertTrue(toggledOff.isLocked)
        assertFalse(toggledOff.passiveEnabled)
        assertEquals(passiveLocked.expiresAtEpochMs, toggledOff.expiresAtEpochMs)
        assertEquals(passiveLocked.lockPasswordHash, toggledOff.lockPasswordHash)
        assertTrue(MacroSecurity.verifyPassword(toggledOff, "creatorPass"))
        assertFalse(MacroSecurity.isTampered(toggledOff))
        assertEquals(RunDenialReason.PASSIVE_OFF, MacroSecurity.checkLinkedRun(toggledOff, baseNow))
        assertEquals(RunDenialReason.PASSIVE_DIRECT, MacroSecurity.checkDirectRun(toggledOff, baseNow))

        val toggledBackOn = MacroSecurity.togglePassiveEnabled(toggledOff, true)
        assertTrue(toggledBackOn.passiveEnabled)
        assertFalse(MacroSecurity.isTampered(toggledBackOn))
        assertNull(MacroSecurity.checkLinkedRun(toggledBackOn, baseNow))

        // Active (non-passive) macro cannot be toggled via togglePassiveEnabled
        val activeMacro = Macro(id = "active_1", name = "Aktif", isPassive = false, passiveEnabled = true)
        val unchangedActive = MacroSecurity.togglePassiveEnabled(activeMacro, false)
        assertFalse(unchangedActive.isPassive)
        assertTrue(unchangedActive.passiveEnabled)

        // Tampered passive macro MUST NOT be whitewashed by passwordless togglePassiveEnabled
        val tamperedExpiry = passiveLocked.copy(expiresAtEpochMs = 0L)
        assertTrue(MacroSecurity.isTampered(tamperedExpiry))
        val afterToggle = MacroSecurity.togglePassiveEnabled(tamperedExpiry, false)
        assertTrue("Tampered macro must remain tampered after toggle attempt", MacroSecurity.isTampered(afterToggle))
        assertEquals(tamperedExpiry.passiveEnabled, afterToggle.passiveEnabled)
        assertEquals(RunDenialReason.TAMPERED, MacroSecurity.checkLinkedRun(afterToggle, baseNow))
    }
}

