package com.example

import com.example.automation.LogLevel
import com.example.automation.MacroRunner
import com.example.automation.MacroRuntime
import com.example.data.AccountOcrMatchMode
import com.example.data.AccountProfile
import com.example.data.AccountRotationMode
import com.example.data.AccountScrollDirection
import com.example.data.AccountSwitchConfig
import com.example.data.AccountSwitchMethod
import com.example.data.AccountTriggerMode
import com.example.data.ClickAction
import com.example.data.ExitAppMode
import com.example.data.Macro
import com.example.data.MacroBundleCodec
import com.example.data.RecentsSwipeDirection
import com.example.data.RecordedGesture
import com.example.data.Region
import com.example.data.SelectAccountAction
import com.example.data.SwitchAccountAction
import com.example.vision.Frame
import com.example.vision.OcrBlock
import com.example.vision.PreparedTemplate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MultiAccountSwitchTest {

    private class FakeMultiAccountRuntime(
        private val ocrProvider: (scrollOffset: Int, horizontal: Boolean) -> List<OcrBlock> = { _, _ -> emptyList() }
    ) : MacroRuntime {
        override val screenWidth: Int = 1080
        override val screenHeight: Int = 2400

        var virtualNowMs: Long = 1_700_000_000_000L
        var scrollOffset: Int = 0
        var minScrollOffset: Int = 0
        var maxScrollOffset: Int = 4
        var stopFlag: Boolean = false

        val clicks = mutableListOf<Pair<Int, Int>>()
        val swipes = mutableListOf<List<Int>>()
        val exits = mutableListOf<String>()
        val launches = mutableListOf<String>()
        val activeAccountHistory = mutableListOf<String>()
        val stateUpdates = mutableListOf<Triple<String, String, Long>>()
        val logs = mutableListOf<String>()

        override fun isStopped(): Boolean = stopFlag
        override suspend fun waitIfPaused() {}
        override suspend fun sleep(ms: Long) {
            virtualNowMs += ms
        }

        override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
            clicks += x to y
            return true
        }

        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
            swipes += listOf(x1, y1, x2, y2)
            // Vertical scroll down = finger moves up (y2 < y1); Horizontal scroll right = finger moves left (x2 < x1)
            val dy = y2 - y1
            val dx = x2 - x1
            if (kotlin.math.abs(dy) >= kotlin.math.abs(dx)) {
                scrollOffset = if (dy < 0) {
                    (scrollOffset + 1).coerceAtMost(maxScrollOffset)
                } else {
                    (scrollOffset - 1).coerceAtLeast(minScrollOffset)
                }
            } else {
                scrollOffset = if (dx < 0) {
                    (scrollOffset + 1).coerceAtMost(maxScrollOffset)
                } else {
                    (scrollOffset - 1).coerceAtLeast(minScrollOffset)
                }
            }
            return true
        }

        override suspend fun zoom(cx: Int, cy: Int, zoomIn: Boolean, distancePx: Int, durationMs: Long): Boolean = true
        override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float): Boolean = true

        override suspend fun exitApp(
            mode: ExitAppMode,
            packageName: String,
            recentsSwipeDirection: RecentsSwipeDirection,
            forceStopBackground: Boolean
        ): Boolean {
            exits += packageName
            return true
        }

        override suspend fun launchApp(packageName: String, freshStart: Boolean): Boolean {
            launches += packageName
            return true
        }

        override fun captureFrame(): Frame? = null
        override fun samplePixelArgb(x: Int, y: Int): Int? = null
        override suspend fun recognizeText(region: Region?): List<OcrBlock> =
            ocrProvider(scrollOffset, false)

        override suspend fun recognizeText(region: Region?, enhanced: Boolean): List<OcrBlock> =
            ocrProvider(scrollOffset, false)

        override fun loadTemplate(name: String): PreparedTemplate? = null

        override fun onActiveAccountChanged(accountIndex: Int, totalEnabled: Int, account: AccountProfile?) {
            if (account != null) {
                activeAccountHistory += "$accountIndex/$totalEnabled:${account.label}"
            }
        }

        override fun onAccountStateUpdated(
            macroId: String,
            lastAccountId: String,
            completedAccountId: String,
            lastUsedAtMs: Long
        ) {
            stateUpdates += Triple(lastAccountId, completedAccountId, lastUsedAtMs)
        }

        override fun log(level: LogLevel, message: String) {
            logs += message
        }

        override fun nowMs(): Long = virtualNowMs
    }

    @Test
    fun ocrListWithAutoScrollAndReverseAtEndFindsTargetAccount() = runBlocking {
        // Simulate a 4-page vertical account list where Farm 2 is at page 2 (downward)
        // and Farm 1 is back at page 0 (requires reversing after hitting bottom when starting from page 2!).
        val pages = mapOf(
            0 to listOf(
                OcrBlock("Farm 1", left = 120, top = 400, right = 420, bottom = 460),
                OcrBlock("server01@mail.com", left = 120, top = 470, right = 600, bottom = 520)
            ),
            1 to listOf(
                OcrBlock("Guest Account", left = 120, top = 400, right = 520, bottom = 460)
            ),
            2 to listOf(
                // Slight OCR lookalike 'O' instead of '0' to verify light fuzzy match + line merge!
                OcrBlock("Farm", left = 120, top = 600, right = 260, bottom = 660),
                OcrBlock("2", left = 270, top = 602, right = 310, bottom = 658)
            )
        )
        val rt = FakeMultiAccountRuntime(ocrProvider = { offset, _ -> pages[offset].orEmpty() }).apply {
            scrollOffset = 0
            maxScrollOffset = 2
        }

        val acc1 = AccountProfile(id = "a1", label = "Farm 1", ocrKeyword = "Farm 1")
        val acc2 = AccountProfile(id = "a2", label = "Farm 2", ocrKeyword = "Farm 2")

        val macro = Macro(
            name = "Universal OCR Test",
            loopCount = 0,
            loopDelayMs = 0L,
            actions = listOf(ClickAction(x = 500, y = 500, delayAfterMs = 0L)),
            accountSwitch = AccountSwitchConfig(
                enabled = true,
                method = AccountSwitchMethod.OCR_LIST,
                rotationMode = AccountRotationMode.ONCE_ALL_ACCOUNTS,
                triggerMode = AccountTriggerMode.AFTER_LOOPS,
                loopsPerAccount = 1,
                accounts = listOf(acc1, acc2),
                switchActions = listOf(
                    SelectAccountAction(
                        matchMode = AccountOcrMatchMode.CONTAINS,
                        scrollDirection = AccountScrollDirection.VERTICAL,
                        maxScrolls = 6,
                        scrollDurationMs = 10L,
                        scrollSettleMs = 0L,
                        reverseOnEnd = true,
                        delayAfterMs = 0L
                    )
                ),
                switchOnStart = false
            )
        )

        MacroRunner(rt).run(macro)

        // Expected flow:
        // 1. Starts on acc1 ("Farm 1"), executes main click (500, 500).
        // 2. After 1 loop, switches to acc2 ("Farm 2"): scrolls down from page 0 -> 1 -> 2, finds merged "Farm 2", clicks center (215, 630).
        // 3. Runs main click (500, 500) on acc2, then ONCE_ALL_ACCOUNTS stops cleanly!
        assertEquals(listOf("1/2:Farm 1", "2/2:Farm 2"), rt.activeAccountHistory)
        assertTrue("Expected at least 2 downward scrolls to reach page 2", rt.swipes.size >= 2)
        // Verify click landed inside merged "Farm 2" bounds (120..310, 600..660)
        assertTrue(
            "Expected click on Farm 2 OCR row, got ${rt.clicks}",
            rt.clicks.any { (x, y) -> x in 120..310 && y in 600..660 }
        )

        // Now start at bottom page 2 and switch back to Farm 1 (page 0) to verify end-of-list auto-reverse!
        val rtReverse = FakeMultiAccountRuntime(ocrProvider = { offset, _ -> pages[offset].orEmpty() }).apply {
            scrollOffset = 2
            maxScrollOffset = 2
        }
        val reverseMacro = macro.copy(
            accountSwitch = macro.accountSwitch.copy(
                switchOnStart = true,
                accounts = listOf(acc1) // switch directly to acc1 at page 0 while view starts at page 2
            )
        )
        MacroRunner(rtReverse).run(reverseMacro)
        assertTrue(
            "Expected reverse scroll to locate Farm 1 at top (page 0), clicks=${rtReverse.clicks}, swipes=${rtReverse.swipes}",
            rtReverse.clicks.any { (x, y) -> x in 120..420 && y in 400..460 }
        )
        assertTrue("Expected at least one upward reverse swipe (y2 > y1)", rtReverse.swipes.any { it[3] > it[1] })
    }

    @Test
    fun cloneAppAndHybridSwitchingLaunchesOnlyWhenPackageChanges() = runBlocking {
        val rtClone = FakeMultiAccountRuntime()
        val accA = AccountProfile(id = "c1", label = "Clone 1", packageName = "com.game.main")
        val accB = AccountProfile(id = "c2", label = "Clone 2", packageName = "com.game.clone2")

        val cloneMacro = Macro(
            name = "Clone App Farm",
            loopCount = 0,
            loopDelayMs = 0L,
            actions = listOf(ClickAction(x = 100, y = 200, delayAfterMs = 0L)),
            accountSwitch = AccountSwitchConfig(
                enabled = true,
                method = AccountSwitchMethod.CLONE_APP,
                rotationMode = AccountRotationMode.ONCE_ALL_ACCOUNTS,
                triggerMode = AccountTriggerMode.AFTER_LOOPS,
                loopsPerAccount = 1,
                accounts = listOf(accA, accB),
                switchActions = listOf(
                    SelectAccountAction(
                        exitPreviousApp = true,
                        exitWaitMs = 0L,
                        launchWaitMs = 0L,
                        delayAfterMs = 0L
                    )
                ),
                switchOnStart = true
            )
        )

        MacroRunner(rtClone).run(cloneMacro)

        assertEquals(listOf("com.game.main", "com.game.clone2"), rtClone.launches)
        assertTrue("Previous package should be exited when switching clone app", rtClone.exits.contains("com.game.main"))

        // Hybrid mode: acc1 & acc2 share com.game.main (different OCR keywords), acc3 is com.game.clone2
        val rtHybrid = FakeMultiAccountRuntime(ocrProvider = { _, _ ->
            listOf(
                OcrBlock("AlphaUser", 100, 300, 400, 350),
                OcrBlock("BetaUser", 100, 450, 400, 500),
                OcrBlock("GammaUser", 100, 600, 400, 650)
            )
        })
        val h1 = AccountProfile(id = "h1", label = "Alpha", ocrKeyword = "AlphaUser", packageName = "com.game.main")
        val h2 = AccountProfile(id = "h2", label = "Beta", ocrKeyword = "BetaUser", packageName = "com.game.main")
        val h3 = AccountProfile(id = "h3", label = "Gamma", ocrKeyword = "GammaUser", packageName = "com.game.clone2")

        val hybridMacro = Macro(
            name = "Hybrid Farm",
            loopCount = 0,
            loopDelayMs = 0L,
            actions = listOf(
                ClickAction(x = 50, y = 50, delayAfterMs = 0L),
                SwitchAccountAction(restartAfterSwitch = true, delayAfterMs = 0L)
            ),
            accountSwitch = AccountSwitchConfig(
                enabled = true,
                method = AccountSwitchMethod.HYBRID,
                rotationMode = AccountRotationMode.ONCE_ALL_ACCOUNTS,
                triggerMode = AccountTriggerMode.MANUAL_ACTION_ONLY,
                accounts = listOf(h1, h2, h3),
                switchActions = listOf(
                    SelectAccountAction(
                        exitPreviousApp = true,
                        exitWaitMs = 0L,
                        launchWaitMs = 0L,
                        scrollSettleMs = 0L,
                        delayAfterMs = 0L
                    )
                ),
                switchOnStart = true
            )
        )

        MacroRunner(rtHybrid).run(hybridMacro)

        // Package launches should be ONLY com.game.main (for h1) and com.game.clone2 (for h3); h2 shares com.game.main so NO relaunch!
        assertEquals(listOf("com.game.main", "com.game.clone2"), rtHybrid.launches)
        assertEquals(listOf("1/3:Alpha", "2/3:Beta", "3/3:Gamma"), rtHybrid.activeAccountHistory)
    }

    @Test
    fun cooldownSkipsRecentlyUsedAccount() = runBlocking {
        val rt = FakeMultiAccountRuntime()
        val now = rt.virtualNowMs
        // acc1 & acc2: acc1 was JUST used (within 30m cooldown), so runner must start on ready acc2!
        val acc1 = AccountProfile(id = "cd1", label = "OnCooldown", packageName = "com.game.1", cooldownMinutes = 30, lastUsedAt = now - 60_000L)
        val acc2 = AccountProfile(id = "cd2", label = "ReadyNow", packageName = "com.game.2", cooldownMinutes = 30, lastUsedAt = 0L)

        val macro = Macro(
            name = "Cooldown Test",
            loopCount = 1,
            loopDelayMs = 0L,
            actions = listOf(ClickAction(x = 10, y = 10, delayAfterMs = 0L)),
            accountSwitch = AccountSwitchConfig(
                enabled = true,
                method = AccountSwitchMethod.CLONE_APP,
                rotationMode = AccountRotationMode.SEQUENTIAL_LOOP,
                triggerMode = AccountTriggerMode.MANUAL_ACTION_ONLY,
                accounts = listOf(acc1, acc2),
                switchActions = listOf(SelectAccountAction(exitWaitMs = 0L, launchWaitMs = 0L, delayAfterMs = 0L)),
                switchOnStart = true
            )
        )

        MacroRunner(rt).run(macro)

        assertEquals(listOf("2/2:ReadyNow"), rt.activeAccountHistory)
        assertEquals(listOf("com.game.2"), rt.launches)
    }

    @Test
    fun portableWjmAndZipExportStripPersonalAccountsWhilePreservingSwitchFlow() {
        val secretMacro = Macro(
            id = "m_multi",
            name = "Auto Multi Account Universal",
            actions = listOf(ClickAction(x = 300, y = 400)),
            accountSwitch = AccountSwitchConfig(
                enabled = true,
                method = AccountSwitchMethod.HYBRID,
                rotationMode = AccountRotationMode.SEQUENTIAL_LOOP,
                triggerMode = AccountTriggerMode.AFTER_LOOPS,
                loopsPerAccount = 2,
                accounts = listOf(
                    AccountProfile(id = "secret_1", label = "Rahasia1", ocrKeyword = "rahasia1@gmail.com", packageName = "com.game.clone1"),
                    AccountProfile(id = "secret_2", label = "Rahasia2", ocrKeyword = "rahasia2@gmail.com", packageName = "com.game.clone2")
                ),
                switchActions = listOf(
                    ClickAction(x = 900, y = 100),
                    SelectAccountAction(maxScrolls = 8)
                ),
                lastAccountId = "secret_1",
                switchOnStart = true
            )
        )

        // 1. Encrypted .wjm export (for Wjust Player)
        val wjmOut = ByteArrayOutputStream()
        MacroBundleCodec.writeEncryptedMacroWjm(
            root = secretMacro,
            lookupMacro = { null },
            loadImage = { null },
            output = wjmOut
        )
        val decodedWjm = MacroBundleCodec.readEncryptedWjm(wjmOut.toByteArray().inputStream())
        val importedWjm = decodedWjm.macros.single()
        assertTrue("accountSwitch.enabled must remain true", importedWjm.accountSwitch.enabled)
        assertEquals(AccountSwitchMethod.HYBRID, importedWjm.accountSwitch.method)
        assertEquals(2, importedWjm.accountSwitch.loopsPerAccount)
        assertEquals(2, importedWjm.accountSwitch.switchActions.size)
        assertTrue("Personal accounts MUST be stripped on .wjm export", importedWjm.accountSwitch.accounts.isEmpty())
        assertEquals("lastAccountId MUST be cleared on .wjm export", "", importedWjm.accountSwitch.lastAccountId)

        // 2. Portable .zip export
        val zipOut = ByteArrayOutputStream()
        MacroBundleCodec.writeMacroZip(
            root = secretMacro,
            lookupMacro = { null },
            loadImage = { null },
            output = zipOut
        )
        val zipRaw = String(zipOut.toByteArray(), Charsets.ISO_8859_1)
        assertFalse("Personal email must never appear in exported zip", zipRaw.contains("rahasia1@gmail.com"))
        val decodedZip = MacroBundleCodec.readBundle(zipOut.toByteArray().inputStream())
        val importedZip = decodedZip.macros.single()
        assertTrue(importedZip.accountSwitch.enabled)
        assertEquals(2, importedZip.accountSwitch.switchActions.size)
        assertTrue(importedZip.accountSwitch.accounts.isEmpty())
        assertEquals("", importedZip.accountSwitch.lastAccountId)
    }
}
