package com.example

import com.example.automation.LogLevel
import com.example.automation.MacroRunner
import com.example.automation.MacroRuntime
import com.example.billing.PremiumFeature
import com.example.billing.PremiumFeatureGate
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MultiAccountSwitchTest {

    private class FakeMultiAccountRuntime(
        private val ocrProvider: (scrollOffset: Int) -> List<OcrBlock> = { emptyList() }
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

        override fun isStopped(): Boolean = stopFlag
        override suspend fun waitIfPaused() {}
        override suspend fun sleep(ms: Long) { virtualNowMs += ms }
        override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean { clicks += x to y; return true }

        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
            swipes += listOf(x1, y1, x2, y2)
            val dy = y2 - y1
            val dx = x2 - x1
            scrollOffset = if (kotlin.math.abs(dy) >= kotlin.math.abs(dx)) {
                if (dy < 0) (scrollOffset + 1).coerceAtMost(maxScrollOffset) else (scrollOffset - 1).coerceAtLeast(minScrollOffset)
            } else {
                if (dx < 0) (scrollOffset + 1).coerceAtMost(maxScrollOffset) else (scrollOffset - 1).coerceAtLeast(minScrollOffset)
            }
            return true
        }

        override suspend fun zoom(cx: Int, cy: Int, zoomIn: Boolean, distancePx: Int, durationMs: Long): Boolean = true
        override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float): Boolean = true
        override suspend fun exitApp(mode: ExitAppMode, packageName: String, recentsSwipeDirection: RecentsSwipeDirection, forceStopBackground: Boolean): Boolean {
            exits += packageName
            return true
        }
        override suspend fun launchApp(packageName: String, freshStart: Boolean): Boolean {
            launches += packageName
            return true
        }

        override fun captureFrame(): Frame? = null
        override fun samplePixelArgb(x: Int, y: Int): Int? = null
        override suspend fun recognizeText(region: Region?): List<OcrBlock> = ocrProvider(scrollOffset)
        override suspend fun recognizeText(region: Region?, enhanced: Boolean): List<OcrBlock> = ocrProvider(scrollOffset)
        override fun loadTemplate(name: String): PreparedTemplate? = null

        override fun onActiveAccountChanged(accountIndex: Int, totalEnabled: Int, account: AccountProfile?) {
            if (account != null) activeAccountHistory += "$accountIndex/$totalEnabled:${account.label}"
        }

        override fun log(level: LogLevel, message: String) {}
        override fun nowMs(): Long = virtualNowMs
    }

    @Test
    fun ocrListWithAutoScrollAndReverseAtEndFindsTargetAccount() = runBlocking {
        val pages = mapOf(
            0 to listOf(OcrBlock("Farm 1", left = 120, top = 400, right = 420, bottom = 460)),
            1 to listOf(OcrBlock("Guest Account", left = 120, top = 400, right = 520, bottom = 460)),
            2 to listOf(
                OcrBlock("Farm", left = 120, top = 600, right = 260, bottom = 660),
                OcrBlock("2", left = 270, top = 602, right = 310, bottom = 658)
            )
        )
        val rt = FakeMultiAccountRuntime(ocrProvider = { pages[it].orEmpty() }).apply {
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
                )
            )
        )

        MacroRunner(rt).run(macro)
        assertEquals(listOf("1/2:Farm 1", "2/2:Farm 2"), rt.activeAccountHistory)
        assertTrue(rt.swipes.size >= 2)
        assertTrue(rt.clicks.any { (x, y) -> x in 120..310 && y in 600..660 })
    }

    @Test
    fun cloneAppAndHybridSwitchingLaunchesOnlyWhenPackageChanges() = runBlocking {
        val rtHybrid = FakeMultiAccountRuntime(ocrProvider = {
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
                switchActions = listOf(SelectAccountAction(exitWaitMs = 0L, launchWaitMs = 0L, scrollSettleMs = 0L, delayAfterMs = 0L)),
                switchOnStart = true
            )
        )

        MacroRunner(rtHybrid).run(hybridMacro)
        assertEquals(listOf("com.game.main", "com.game.clone2"), rtHybrid.launches)
        assertEquals(listOf("1/3:Alpha", "2/3:Beta", "3/3:Gamma"), rtHybrid.activeAccountHistory)
    }

    @Test
    fun playStoreFreeTierAllowsUpToTwoAccountsAndGatesThirdAccountBehindPro() {
        val acc1 = AccountProfile(id = "a1", label = "Akun 1", ocrKeyword = "user1")
        val acc2 = AccountProfile(id = "a2", label = "Akun 2", ocrKeyword = "user2")
        val acc3 = AccountProfile(id = "a3", label = "Akun 3", ocrKeyword = "user3")

        val twoAccountMacro = Macro(
            name = "Gratis 2 Akun",
            actions = listOf(
                ClickAction(x = 100, y = 200),
                SwitchAccountAction()
            ),
            accountSwitch = AccountSwitchConfig(
                enabled = true,
                method = AccountSwitchMethod.OCR_LIST,
                accounts = listOf(acc1, acc2),
                switchActions = listOf(SelectAccountAction())
            )
        )
        assertNull("2 enabled accounts must be allowed on Free tier", PremiumFeatureGate.checkMacroRun(twoAccountMacro, isPremium = false))

        // Disabled 3rd account still counts as only 2 enabled -> allowed!
        val twoActiveOneDisabled = twoAccountMacro.copy(
            accountSwitch = twoAccountMacro.accountSwitch.copy(
                accounts = listOf(acc1, acc2, acc3.copy(enabled = false))
            )
        )
        assertNull(PremiumFeatureGate.checkMacroRun(twoActiveOneDisabled, isPremium = false))

        // 3 enabled accounts -> blocked on Free with MULTI_ACCOUNT, allowed on PRO!
        val threeAccountMacro = twoAccountMacro.copy(
            accountSwitch = twoAccountMacro.accountSwitch.copy(
                accounts = listOf(acc1, acc2, acc3)
            )
        )
        val denial = PremiumFeatureGate.checkMacroRun(threeAccountMacro, isPremium = false)
        assertNotNull(denial)
        assertEquals(PremiumFeature.MULTI_ACCOUNT, denial!!.feature)
        assertNull("3+ accounts must be allowed on PRO", PremiumFeatureGate.checkMacroRun(threeAccountMacro, isPremium = true))
    }

    @Test
    fun zipExportStripsPersonalAccountsWhilePreservingSwitchFlow() {
        val secretMacro = Macro(
            id = "m_multi",
            name = "Auto Multi Account Universal",
            actions = listOf(ClickAction(x = 300, y = 400)),
            accountSwitch = AccountSwitchConfig(
                enabled = true,
                method = AccountSwitchMethod.HYBRID,
                accounts = listOf(
                    AccountProfile(id = "s1", label = "Rahasia1", ocrKeyword = "rahasia1@gmail.com", packageName = "com.game.clone1"),
                    AccountProfile(id = "s2", label = "Rahasia2", ocrKeyword = "rahasia2@gmail.com", packageName = "com.game.clone2")
                ),
                switchActions = listOf(ClickAction(x = 900, y = 100), SelectAccountAction(maxScrolls = 8)),
                lastAccountId = "s1"
            )
        )

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
        val imported = decodedZip.macros.single()
        assertTrue(imported.accountSwitch.enabled)
        assertEquals(2, imported.accountSwitch.switchActions.size)
        assertTrue(imported.accountSwitch.accounts.isEmpty())
        assertEquals("", imported.accountSwitch.lastAccountId)
    }
}
