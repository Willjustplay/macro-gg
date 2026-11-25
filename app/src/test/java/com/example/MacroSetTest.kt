package com.example

import com.example.automation.LogLevel
import com.example.automation.MacroRunner
import com.example.automation.MacroRuntime
import com.example.data.ClickAction
import com.example.data.ExpiryUnit
import com.example.data.FinishMacroAction
import com.example.data.FinishMacroMode
import com.example.data.ImageAction
import com.example.data.Macro
import com.example.data.MacroBundleCodec
import com.example.data.MacroSecurity
import com.example.data.MacroSet
import com.example.data.RecordedGesture
import com.example.data.RunDenialReason
import com.example.data.RunMacroAction
import com.example.data.SingleUseImportException
import com.example.vision.Frame
import com.example.vision.PreparedTemplate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MacroSetTest {

    @Test
    fun setLockPasswordExpiryAndTamperSealWorkCorrectly() {
        val now = 1_750_000_000_000L
        val rawSet = MacroSet(id = "set_coc", name = "COC TH16 Farm", colorHex = "#10B981")
        val expMs = MacroSecurity.computeExpiryEpochMs(now, 7, ExpiryUnit.DAYS)

        val lockedSet = MacroSecurity.applySetSecurity(
            set = rawSet,
            isLocked = true,
            expiresAtEpochMs = expMs,
            expiryLabel = "7 Hari",
            newPassword = "secret99",
            expiryDurationAmount = 7,
            expiryDurationUnit = ExpiryUnit.DAYS
        )

        assertTrue(lockedSet.isLocked)
        assertTrue(MacroSecurity.hasSetPassword(lockedSet))
        assertTrue(MacroSecurity.verifySetPassword(lockedSet, "secret99"))
        assertFalse(MacroSecurity.verifySetPassword(lockedSet, "wrong"))
        assertFalse(MacroSecurity.isSetTampered(lockedSet))
        assertFalse(MacroSecurity.isSetExpired(lockedSet, now + 1000L))
        assertTrue(MacroSecurity.isSetExpired(lockedSet, expMs + 1L))
        assertEquals("Sisa 7 hari", MacroSecurity.remainingSetTimeLabel(lockedSet, now + 1000L))

        // Manual JSON tamper attempt (flipping isLocked or extending deadline) must fail seal check
        assertTrue(MacroSecurity.isSetTampered(lockedSet.copy(isLocked = false)))
        assertTrue(MacroSecurity.isSetTampered(lockedSet.copy(expiresAtEpochMs = expMs + 86_400_000L)))
    }

    @Test
    fun syncMacroWithSetSecurityLocksMembersAndUnlocksCleanlyWhilePreservingPassiveMode() {
        val now = 1_750_000_000_000L
        val expMs = MacroSecurity.computeExpiryEpochMs(now, 30, ExpiryUnit.DAYS)
        val set = MacroSecurity.applySetSecurity(
            set = MacroSet(id = "set_1", name = "Farming Set"),
            isLocked = true,
            expiresAtEpochMs = expMs,
            expiryLabel = "30 Hari",
            newPassword = "pass1234",
            expiryDurationAmount = 30,
            expiryDurationUnit = ExpiryUnit.DAYS
        )

        val activeMember = Macro(id = "m_active", name = "Main Farm", setId = set.id)
        val passiveMember = MacroSecurity.applySecurity(
            macro = Macro(id = "m_passive", name = "Attack Sub", setId = set.id),
            isLocked = false,
            isPassive = true,
            expiresAtEpochMs = 0L,
            expiryLabel = "",
            newPassword = "oldpass",
            passiveEnabled = false
        )

        val lockedActive = MacroSecurity.syncMacroWithSetSecurity(activeMember, set)
        val lockedPassive = MacroSecurity.syncMacroWithSetSecurity(passiveMember, set)

        assertTrue(lockedActive.isLocked)
        assertFalse(lockedActive.isPassive)
        assertEquals(expMs, lockedActive.expiresAtEpochMs)
        assertTrue(MacroSecurity.verifyPassword(lockedActive, "pass1234"))
        assertFalse(MacroSecurity.isTampered(lockedActive))

        assertTrue(lockedPassive.isLocked)
        assertTrue(lockedPassive.isPassive)
        assertFalse("Passive OFF toggle must be preserved during set lock sync", lockedPassive.passiveEnabled)
        assertEquals(expMs, lockedPassive.expiresAtEpochMs)
        assertTrue(MacroSecurity.verifyPassword(lockedPassive, "pass1234"))
        assertFalse(MacroSecurity.isTampered(lockedPassive))

        // Turn OFF all protection on the Set -> both members unlock cleanly without breaking passive seal
        val unlockedSet = MacroSecurity.applySetSecurity(
            set = set,
            isLocked = false,
            expiresAtEpochMs = 0L,
            expiryLabel = ""
        )
        val unlockedActive = MacroSecurity.syncMacroWithSetSecurity(lockedActive, unlockedSet)
        val unlockedPassive = MacroSecurity.syncMacroWithSetSecurity(lockedPassive, unlockedSet)

        assertFalse(unlockedActive.isLocked)
        assertEquals(0L, unlockedActive.expiresAtEpochMs)
        assertFalse(MacroSecurity.hasPassword(unlockedActive))
        assertFalse(MacroSecurity.isTampered(unlockedActive))

        assertFalse(unlockedPassive.isLocked)
        assertEquals(0L, unlockedPassive.expiresAtEpochMs)
        assertTrue("Passive mode remains intact after unlocking set", unlockedPassive.isPassive)
        assertFalse(unlockedPassive.passiveEnabled)
        assertFalse("Passive member seal must remain valid after set unlock", MacroSecurity.isTampered(unlockedPassive))
    }

    @Test
    fun parentSetExpirationAndTamperBlockMacroExecutionInRunner() = runTest {
        val now = 1_750_000_000_000L
        val expMs = MacroSecurity.computeExpiryEpochMs(now, 1, ExpiryUnit.DAYS)
        val set = MacroSecurity.applySetSecurity(
            set = MacroSet(id = "set_exp", name = "Trial Set"),
            isLocked = true,
            expiresAtEpochMs = expMs,
            expiryLabel = "1 Hari",
            newPassword = "trial123",
            expiryDurationAmount = 1,
            expiryDurationUnit = ExpiryUnit.DAYS
        )
        val macro = Macro(
            id = "m_trial",
            name = "Trial Farm",
            setId = set.id,
            actions = listOf(ClickAction(x = 100, y = 200, delayAfterMs = 0L))
        )

        suspend fun runAt(clockMs: Long, storedSet: MacroSet): List<Pair<Int, Int>> {
            val clicks = mutableListOf<Pair<Int, Int>>()
            val runtime = object : MacroRuntime {
                override val screenWidth = 1080
                override val screenHeight = 1920
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
                override fun loadMacroSet(id: String): MacroSet? = if (id == storedSet.id) storedSet else null
                override fun log(level: LogLevel, message: String) {}
                override fun nowMs() = clockMs
            }
            MacroRunner(runtime).run(macro)
            return clicks
        }

        // Before deadline: runs normally!
        assertEquals(listOf(100 to 200), runAt(now + 10_000L, set))
        // After set deadline: blocked!
        assertTrue(runAt(expMs + 5000L, set).isEmpty())
        assertEquals(RunDenialReason.EXPIRED, MacroSecurity.checkDirectRun(macro, expMs + 5000L, set))
        // Tampered set: blocked!
        val tamperedSet = set.copy(isLocked = false)
        assertTrue(runAt(now + 10_000L, tamperedSet).isEmpty())
        assertEquals(RunDenialReason.TAMPERED, MacroSecurity.checkDirectRun(macro, now + 10_000L, tamperedSet))
    }

    @Test
    fun exportAndImportLockedMacroSetZipPreservesMembershipLinksImagesAndLockSeals() {
        val now = 1_750_000_000_000L
        val expMs = MacroSecurity.computeExpiryEpochMs(now, 14, ExpiryUnit.DAYS)
        val set = MacroSecurity.applySetSecurity(
            set = MacroSet(id = "orig_set", name = "COC Full Auto", description = "1 Aktif + 1 Pasif", colorHex = "#A78BFA"),
            isLocked = true,
            expiresAtEpochMs = expMs,
            expiryLabel = "14 Hari",
            newPassword = "cocpass",
            expiryDurationAmount = 14,
            expiryDurationUnit = ExpiryUnit.DAYS
        )

        val externalHelper = Macro(
            id = "orig_ext",
            name = "Helper Luar",
            actions = listOf(ImageAction(imageName = "helper.png"))
        )
        val passiveSub = MacroSecurity.syncMacroWithSetSecurity(
            MacroSecurity.applySecurity(
                macro = Macro(
                    id = "orig_passive",
                    name = "Attack Dragon",
                    setId = set.id,
                    actions = listOf(
                        ImageAction(imageName = "dragon.png"),
                        FinishMacroAction(mode = FinishMacroMode.SWITCH_TO_SPECIFIC_ACTIVE, targetMacroId = "orig_active", targetMacroName = "Main Loop")
                    )
                ),
                isLocked = false,
                isPassive = true,
                expiresAtEpochMs = 0L,
                expiryLabel = "",
                newPassword = "cocpass",
                passiveEnabled = true
            ),
            set
        )
        val activeMain = MacroSecurity.syncMacroWithSetSecurity(
            Macro(
                id = "orig_active",
                name = "Main Loop",
                setId = set.id,
                logoImage = "logo_main.png",
                actions = listOf(
                    ImageAction(imageName = "attack_btn.png"),
                    RunMacroAction(targetMacroId = passiveSub.id, targetMacroName = passiveSub.name, returnAfter = true),
                    RunMacroAction(targetMacroId = externalHelper.id, targetMacroName = externalHelper.name, returnAfter = true)
                )
            ),
            set
        )

        val allById = listOf(activeMain, passiveSub, externalHelper).associateBy { it.id }
        val fakePng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)
        val fakeLogo = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 9, 8, 7, 6)

        val zipBytes = ByteArrayOutputStream()
        val exportRes = MacroBundleCodec.writeMacroSetZip(
            set = set,
            memberMacros = listOf(activeMain, passiveSub),
            lookupMacro = { allById[it] },
            loadImage = { name -> name to fakePng },
            output = zipBytes,
            loadMacroIcon = { name -> if (name == "logo_main.png") name to fakeLogo else null },
            singleUseImport = false,
            exportedAtEpochMs = now
        )

        assertEquals(1, exportRes.setsExported)
        assertEquals(3, exportRes.macrosExported) // 2 set members + 1 external linked helper
        assertEquals(3, exportRes.imagesExported)

        var seq = 0
        val decoded = MacroBundleCodec.readBundle(
            input = ByteArrayInputStream(zipBytes.toByteArray()),
            idGenerator = { "new_id_${++seq}" }
        )

        assertEquals(1, decoded.macroSets.size)
        val importedSet = decoded.macroSets.first()
        assertNotEquals(set.id, importedSet.id)
        assertEquals("orig_set", importedSet.originId)
        assertEquals("COC Full Auto", importedSet.name)
        assertTrue(importedSet.isLocked)
        assertTrue(MacroSecurity.verifySetPassword(importedSet, "cocpass"))
        assertFalse("Set seal must remain valid after ID remapping", MacroSecurity.isSetTampered(importedSet))

        assertEquals(3, decoded.macros.size)
        val impMain = decoded.macros.first { it.name == "Main Loop" }
        val impPassive = decoded.macros.first { it.name == "Attack Dragon" }
        val impHelper = decoded.macros.first { it.name == "Helper Luar" }

        // Members remain grouped under the newly remapped set ID; external linked macro stays ungrouped
        assertEquals(importedSet.id, impMain.setId)
        assertEquals(importedSet.id, impPassive.setId)
        assertEquals("", impHelper.setId)

        // Internal & external RunMacroAction / FinishMacroAction links remapped cleanly
        val runPassive = impMain.actions[1] as RunMacroAction
        val runHelper = impMain.actions[2] as RunMacroAction
        assertEquals(impPassive.id, runPassive.targetMacroId)
        assertEquals(impHelper.id, runHelper.targetMacroId)
        val finishBack = impPassive.actions[1] as FinishMacroAction
        assertEquals(impMain.id, finishBack.targetMacroId)

        // Locks, seals, images, and logo preserved
        assertTrue(impMain.isLocked)
        assertFalse(MacroSecurity.isTampered(impMain))
        assertTrue(impPassive.isLocked && impPassive.isPassive)
        assertFalse(MacroSecurity.isTampered(impPassive))
        assertTrue("attack_btn.png" in decoded.images)
        assertTrue("dragon.png" in decoded.images)
        assertTrue("helper.png" in decoded.images)
        assertTrue("logo_main.png" in decoded.macroIcons)
    }

    @Test
    fun singleUseMacroSetZipBlocksSecondImportAndTampering() {
        val now = 1_750_000_000_000L
        val set = MacroSecurity.applySetSecurity(
            set = MacroSet(id = "set_1x", name = "VIP Set"),
            isLocked = true,
            expiresAtEpochMs = 0L,
            expiryLabel = "",
            newPassword = "vippass"
        )
        val m1 = MacroSecurity.syncMacroWithSetSecurity(Macro(id = "m1", name = "VIP 1", setId = set.id), set)

        val zipBytes = ByteArrayOutputStream()
        val exportRes = MacroBundleCodec.writeMacroSetZip(
            set = set,
            memberMacros = listOf(m1),
            lookupMacro = { null },
            loadImage = { null },
            output = zipBytes,
            singleUseImport = true,
            exportedAtEpochMs = now
        )
        assertTrue(exportRes.singleUseImport)
        assertTrue(exportRes.importToken.isNotBlank())

        val decoded = MacroBundleCodec.readBundle(ByteArrayInputStream(zipBytes.toByteArray()))
        assertEquals("MACRO_SET", decoded.bundleMeta?.bundleKind)

        // First import succeeds and extracts the one-time token
        val consumed = MacroBundleCodec.validateAndExtractOneTimeTokens(decoded, emptySet())
        assertEquals(setOf(exportRes.importToken), consumed)

        // Second import with already-consumed token must be rejected
        try {
            MacroBundleCodec.validateAndExtractOneTimeTokens(decoded, consumed)
            fail("Expected SingleUseImportException on second import of 1x Macro Set")
        } catch (_: SingleUseImportException) {
            // Expected
        }

        // Tampered set token must also be rejected
        val tamperedDecoded = decoded.copy(
            macroSets = decoded.macroSets.map { it.copy(oneTimeImportToken = "forged_token") }
        )
        try {
            MacroBundleCodec.validateAndExtractOneTimeTokens(tamperedDecoded, emptySet())
            fail("Expected SingleUseImportException on tampered Macro Set token")
        } catch (_: SingleUseImportException) {
            // Expected
        }
    }

    @Test
    fun overwriteSameMacroSetStacksActiveDeadlines() {
        val now = 1_750_000_000_000L
        val existingExp = MacroSecurity.computeExpiryEpochMs(now, 5, ExpiryUnit.DAYS)
        val existing = MacroSecurity.applySetSecurity(
            set = MacroSet(id = "local_set", originId = "origin_set_77", name = "COC Set"),
            isLocked = true,
            expiresAtEpochMs = existingExp,
            expiryLabel = "5 Hari",
            newPassword = "pw1234",
            expiryDurationAmount = 5,
            expiryDurationUnit = ExpiryUnit.DAYS
        )

        val incomingExp = MacroSecurity.computeExpiryEpochMs(now, 30, ExpiryUnit.DAYS)
        val incoming = MacroSecurity.applySetSecurity(
            set = MacroSet(id = "imported_set", originId = "origin_set_77", name = "COC Set v2"),
            isLocked = true,
            expiresAtEpochMs = incomingExp,
            expiryLabel = "30 Hari",
            newPassword = "pw1234",
            expiryDurationAmount = 30,
            expiryDurationUnit = ExpiryUnit.DAYS
        )

        val res = MacroSecurity.mergeOverwrittenMacroSet(existing, incoming, now)
        assertTrue(res.deadlineExtended)
        assertEquals("local_set", res.merged.id)
        assertEquals("origin_set_77", res.merged.originId)
        assertEquals("COC Set v2", res.merged.name)
        assertEquals(existingExp + 30L * 86_400_000L, res.merged.expiresAtEpochMs)
        assertFalse(MacroSecurity.isSetTampered(res.merged))
        assertNull(MacroSecurity.checkDirectRun(Macro(name = "A", setId = res.merged.id), existingExp + 10L * 86_400_000L, res.merged))
    }
}
