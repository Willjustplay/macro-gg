package com.example

import com.example.automation.LogLevel
import com.example.automation.LuaBridge
import com.example.automation.MacroRunner
import com.example.automation.MacroRuntime
import com.example.automation.PrismProtocol
import com.example.automation.PrismSwitchRequest
import com.example.automation.SandboxedLuaEngine
import com.example.data.AccountProfile
import com.example.data.AccountRotationMode
import com.example.data.AccountSwitchConfig
import com.example.data.AccountSwitchMethod
import com.example.data.AccountTriggerMode
import com.example.data.ClickAction
import com.example.data.ExitAppMode
import com.example.data.Macro
import com.example.data.MacroBundleCodec
import com.example.data.RecentsSwipeDirection
import com.example.data.RecordedGesture
import com.example.data.SelectAccountAction
import com.example.vision.Frame
import com.example.vision.PreparedTemplate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PrismClonerIntegrationTest {

    private class FakePrismRuntime(
        private val rootStubPackages: Set<String> = emptySet(),
        private val failPrismSwitch: Boolean = false
    ) : MacroRuntime {
        override val screenWidth: Int = 1080
        override val screenHeight: Int = 2400

        var virtualNowMs: Long = 1_700_000_000_000L
        var stopFlag: Boolean = false

        val exits = mutableListOf<String>()
        val launches = mutableListOf<String>()
        val prismSwitches = mutableListOf<PrismSwitchRequest>()
        val logs = mutableListOf<String>()

        override fun isStopped(): Boolean = stopFlag
        override suspend fun waitIfPaused() {}
        override suspend fun sleep(ms: Long) { virtualNowMs += ms }
        override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean = true
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = true
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

        override fun isPrismRootPackage(packageName: String): Boolean =
            if (rootStubPackages.isNotEmpty()) packageName in rootStubPackages
            else PrismProtocol.isPrismStubPackageName(packageName)

        override suspend fun switchPrismAccount(request: PrismSwitchRequest): Boolean {
            prismSwitches += request
            return !failPrismSwitch
        }

        override fun captureFrame(): Frame? = null
        override fun samplePixelArgb(x: Int, y: Int): Int? = null
        override fun loadTemplate(name: String): PreparedTemplate? = null
        override fun log(level: LogLevel, message: String) { logs += message }
        override fun nowMs(): Long = virtualNowMs
    }

    @Test
    fun prismProtocolGeneratesDeterministicStubPackageAndParsesSlot() {
        val pkg1 = PrismProtocol.generateStubPackageName("com.supercell.clashofclans", 1)
        val pkg12 = PrismProtocol.generateStubPackageName("com.supercell.clashofclans", 12)

        assertTrue("Expected app.prism.c.p...u01, got $pkg1", pkg1.matches(Regex("^app\\.prism\\.c\\.p[0-9a-f]{6}\\.u01$")))
        assertEquals(pkg1.removeSuffix(".u01") + ".u12", pkg12)
        assertTrue(PrismProtocol.isPrismStubPackageName(pkg1))
        assertEquals(1, PrismProtocol.parseSlotNumberFromPackage(pkg1))
        assertEquals(12, PrismProtocol.parseSlotNumberFromPackage(pkg12))
        assertFalse(PrismProtocol.isPrismStubPackageName("com.supercell.clashofclans"))
        assertEquals(0, PrismProtocol.parseSlotNumberFromPackage("com.supercell.clashofclans"))
    }

    @Test
    fun prismProtocolParsesSlotMetadataJsonForRootAndNonRoot() {
        val rootJson = """
            {
              "sourcePackage": "com.supercell.clashofclans",
              "stubPackage": "app.prism.c.p9a2b3c.u02",
              "slotIndex": 2,
              "activeProfileId": "root_coc_u02",
              "displayLabel": "02. TH16 Farm",
              "engineType": "NATIVE_GL",
              "rootDelegationMode": true,
              "hostPackage": "app.prism.cloner",
              "dynamicSlotOrder": 2,
              "futureExtraField": "ignored"
            }
        """.trimIndent()

        val parsedRoot = PrismProtocol.parseSlotMetadataJson(rootJson)
        assertNotNull(parsedRoot)
        assertEquals("app.prism.c.p9a2b3c.u02", parsedRoot!!.stubPackage)
        assertEquals("com.supercell.clashofclans", parsedRoot.sourcePackage)
        assertEquals("root_coc_u02", parsedRoot.profileId)
        assertEquals("02. TH16 Farm", parsedRoot.displayLabel)
        assertEquals(2, parsedRoot.slotOrder)
        assertEquals("app.prism.cloner", parsedRoot.hostPackage)
        assertTrue(parsedRoot.isRootDelegation)

        val nonRootJson = """
            {
              "sourcePackage": "com.example.game",
              "stubPackage": "app.prism.c.p112233.u01",
              "slotIndex": 1,
              "activeProfileId": "slot_1",
              "displayLabel": "NonRoot 1",
              "rootDelegationMode": false
            }
        """.trimIndent()
        val parsedNonRoot = PrismProtocol.parseSlotMetadataJson(nonRootJson)
        assertNotNull(parsedNonRoot)
        assertFalse(parsedNonRoot!!.isRootDelegation)
    }

    @Test
    fun prismProtocolValidatesRootShellCommandsAndRejectsInjection() {
        val switchCmd = PrismProtocol.buildRootBroadcastShellCommand(
            PrismSwitchRequest(
                targetPackage = "com.supercell.clashofclans",
                slotOrder = 2,
                profileId = "root_coc_u02",
                stubPackage = "app.prism.c.p9a2b3c.u02",
                switchNext = false
            )
        )
        assertNotNull(switchCmd)
        assertTrue(switchCmd!!.contains("-f 0x10000020"))
        assertTrue(switchCmd.contains("-n app.prism.cloner/.RootSwitchBroadcastReceiver"))
        assertTrue(switchCmd.contains("-a app.prism.action.SWITCH_ACCOUNT"))
        assertTrue(switchCmd.contains("--es app.prism.extra.ROOT_TARGET_PACKAGE com.supercell.clashofclans"))
        assertTrue(switchCmd.contains("--ei app.prism.extra.ROOT_SLOT_ORDER 2"))
        assertTrue(switchCmd.contains("--es app.prism.extra.ROOT_STUB_PACKAGE app.prism.c.p9a2b3c.u02"))

        val nextCmd = PrismProtocol.buildRootBroadcastShellCommand(
            PrismSwitchRequest(targetPackage = "com.supercell.clashofclans", switchNext = true)
        )
        assertNotNull(nextCmd)
        assertTrue(nextCmd!!.contains("-a app.prism.action.SWITCH_NEXT"))

        // Reject shell injection attempts
        assertNull(
            PrismProtocol.buildRootBroadcastShellCommand(
                PrismSwitchRequest(targetPackage = "com.game; rm -rf /", slotOrder = 1)
            )
        )
        assertNull(
            PrismProtocol.buildRootBroadcastShellCommand(
                PrismSwitchRequest(targetPackage = "com.game.ok", profileId = "bad$(id)", slotOrder = 1)
            )
        )
        assertNull(PrismProtocol.buildRootLaunchShellCommand("com.game && id"))

        val launchStubCmd = PrismProtocol.buildRootLaunchShellCommand("app.prism.c.p9a2b3c.u01")
        assertNotNull(launchStubCmd)
        assertTrue(launchStubCmd!!.contains("app.prism.stub.StubLaunchActivity"))
        assertTrue(launchStubCmd.contains(".MainActivity"))
    }

    @Test
    fun cloneAppModeWithPrismRootStubsSkipsBrokenExitAndUsesDirectPrismBroadcast() = runBlocking {
        val stub1 = PrismProtocol.generateStubPackageName("com.supercell.clashofclans", 1)
        val stub2 = PrismProtocol.generateStubPackageName("com.supercell.clashofclans", 2)
        val rt = FakePrismRuntime(rootStubPackages = setOf(stub1, stub2))

        val acc1 = AccountProfile(
            id = "p1",
            label = "01. TH16 Utama",
            packageName = stub1,
            appLabel = "01. TH16 Utama",
            prismTargetPackage = "com.supercell.clashofclans",
            prismSlotOrder = 1,
            prismProfileId = "root_u01"
        )
        val acc2 = AccountProfile(
            id = "p2",
            label = "02. TH16 Farm",
            packageName = stub2,
            appLabel = "02. TH16 Farm",
            prismTargetPackage = "com.supercell.clashofclans",
            prismSlotOrder = 2,
            prismProfileId = "root_u02"
        )

        val macro = Macro(
            name = "CoC PRISM Clone Mode",
            loopCount = 0,
            loopDelayMs = 0L,
            actions = listOf(ClickAction(x = 500, y = 500, delayAfterMs = 0L)),
            accountSwitch = AccountSwitchConfig(
                enabled = true,
                method = AccountSwitchMethod.CLONE_APP,
                rotationMode = AccountRotationMode.ONCE_ALL_ACCOUNTS,
                triggerMode = AccountTriggerMode.AFTER_LOOPS,
                loopsPerAccount = 1,
                accounts = listOf(acc1, acc2),
                switchActions = listOf(
                    SelectAccountAction(
                        exitPreviousApp = true,
                        exitMode = ExitAppMode.CLOSE_RECENT_AND_HOME,
                        exitWaitMs = 0L,
                        launchWaitMs = 0L,
                        delayAfterMs = 0L
                    )
                ),
                switchOnStart = true
            )
        )

        MacroRunner(rt).run(macro)

        // MUST NOT call broken Recents/Settings force-stop on headless PRISM stub packages!
        assertTrue("Expected zero exitApp calls for PRISM root stubs, got ${rt.exits}", rt.exits.isEmpty())
        // MUST dispatch directly via anti-BAL PRISM switch rather than fragile Activity launch
        assertTrue("Expected direct PRISM switch instead of raw launchApp, got ${rt.launches}", rt.launches.isEmpty())
        assertEquals(2, rt.prismSwitches.size)
        assertEquals(stub1, rt.prismSwitches[0].stubPackage)
        assertEquals(1, rt.prismSwitches[0].slotOrder)
        assertEquals("com.supercell.clashofclans", rt.prismSwitches[0].targetPackage)
        assertEquals(stub2, rt.prismSwitches[1].stubPackage)
        assertEquals(2, rt.prismSwitches[1].slotOrder)
        assertEquals("root_u02", rt.prismSwitches[1].profileId)
    }

    @Test
    fun prismRootModeWithZeroLocalAccountsCyclesAutomaticallyViaSwitchNext() = runBlocking {
        val rt = FakePrismRuntime()
        val macro = Macro(
            name = "PRISM Zero-Config Switch Next",
            loopCount = 3,
            loopDelayMs = 0L,
            actions = listOf(ClickAction(x = 300, y = 400, delayAfterMs = 0L)),
            accountSwitch = AccountSwitchConfig(
                enabled = true,
                method = AccountSwitchMethod.PRISM_ROOT,
                triggerMode = AccountTriggerMode.AFTER_LOOPS,
                loopsPerAccount = 1,
                accounts = emptyList(),
                switchActions = listOf(
                    SelectAccountAction(
                        methodOverride = AccountSwitchMethod.PRISM_ROOT,
                        prismUseSwitchNext = true,
                        prismTargetPackage = "com.supercell.clashofclans",
                        launchWaitMs = 0L,
                        delayAfterMs = 0L
                    )
                ),
                switchOnStart = false
            )
        )

        MacroRunner(rt).run(macro)

        // 3 loops with loopsPerAccount=1 -> switches after loop 1 and loop 2 (before loop 3)
        assertEquals(2, rt.prismSwitches.size)
        assertTrue(rt.prismSwitches.all { it.switchNext })
        assertTrue(rt.prismSwitches.all { it.targetPackage == "com.supercell.clashofclans" })
    }

    @Test
    fun prismRootModeSupportsDirectSlotNumbersWithoutStubApks() = runBlocking {
        val rt = FakePrismRuntime()
        val acc1 = AccountProfile(
            id = "s1",
            label = "Slot 1",
            prismTargetPackage = "com.supercell.clashofclans",
            prismSlotOrder = 1
        )
        val acc2 = AccountProfile(
            id = "s2",
            label = "Slot 2",
            prismTargetPackage = "com.supercell.clashofclans",
            prismSlotOrder = 2
        )

        val macro = Macro(
            name = "PRISM Direct Slots",
            loopCount = 0,
            loopDelayMs = 0L,
            actions = listOf(ClickAction(x = 200, y = 300, delayAfterMs = 0L)),
            accountSwitch = AccountSwitchConfig(
                enabled = true,
                method = AccountSwitchMethod.PRISM_ROOT,
                rotationMode = AccountRotationMode.ONCE_ALL_ACCOUNTS,
                triggerMode = AccountTriggerMode.AFTER_LOOPS,
                loopsPerAccount = 1,
                accounts = listOf(acc1, acc2),
                switchActions = listOf(
                    SelectAccountAction(
                        methodOverride = AccountSwitchMethod.PRISM_ROOT,
                        launchWaitMs = 0L,
                        delayAfterMs = 0L
                    )
                ),
                switchOnStart = true
            )
        )

        MacroRunner(rt).run(macro)

        assertEquals(2, rt.prismSwitches.size)
        assertFalse(rt.prismSwitches[0].switchNext)
        assertEquals(1, rt.prismSwitches[0].slotOrder)
        assertEquals("com.supercell.clashofclans", rt.prismSwitches[0].targetPackage)
        assertEquals(2, rt.prismSwitches[1].slotOrder)
    }

    @Test
    fun prismRootConfigRoundTripsInWjmAndZipWhileStrippingPersonalAccounts() {
        val macro = Macro(
            id = "m_prism",
            name = "Auto CoC PRISM Root",
            actions = listOf(ClickAction(x = 400, y = 500)),
            accountSwitch = AccountSwitchConfig(
                enabled = true,
                method = AccountSwitchMethod.PRISM_ROOT,
                loopsPerAccount = 2,
                accounts = listOf(
                    AccountProfile(
                        id = "secret_p1",
                        label = "Utama Rahasia",
                        packageName = "app.prism.c.p9a2b3c.u01",
                        prismTargetPackage = "com.supercell.clashofclans",
                        prismSlotOrder = 1,
                        prismProfileId = "secret_profile_id_1"
                    )
                ),
                switchActions = listOf(
                    SelectAccountAction(
                        methodOverride = AccountSwitchMethod.PRISM_ROOT,
                        prismUseSwitchNext = true,
                        prismTargetPackage = "com.supercell.clashofclans",
                        launchWaitMs = 6500L
                    )
                ),
                lastAccountId = "secret_p1"
            )
        )

        val wjmOut = ByteArrayOutputStream()
        MacroBundleCodec.writeEncryptedMacroWjm(
            root = macro,
            lookupMacro = { null },
            loadImage = { null },
            output = wjmOut
        )
        val importedWjm = MacroBundleCodec.readEncryptedWjm(wjmOut.toByteArray().inputStream()).macros.single()
        assertEquals(AccountSwitchMethod.PRISM_ROOT, importedWjm.accountSwitch.method)
        assertTrue(importedWjm.accountSwitch.accounts.isEmpty())
        val sel = importedWjm.accountSwitch.switchActions.single() as SelectAccountAction
        assertTrue(sel.prismUseSwitchNext)
        assertEquals("com.supercell.clashofclans", sel.prismTargetPackage)
        assertEquals(6500L, sel.launchWaitMs)

        val zipOut = ByteArrayOutputStream()
        MacroBundleCodec.writeMacroZip(
            root = macro,
            lookupMacro = { null },
            loadImage = { null },
            output = zipOut
        )
        val zipRaw = String(zipOut.toByteArray(), Charsets.ISO_8859_1)
        assertFalse("Personal PRISM profile ID must not leak in exported zip", zipRaw.contains("secret_profile_id_1"))
        val importedZip = MacroBundleCodec.readBundle(zipOut.toByteArray().inputStream()).macros.single()
        assertEquals(AccountSwitchMethod.PRISM_ROOT, importedZip.accountSwitch.method)
        assertTrue(importedZip.accountSwitch.accounts.isEmpty())
    }

    @Test
    fun luaEngineSupportsLaunchAppAndPrismSwitchApis() {
        val launched = mutableListOf<Pair<String, Boolean>>()
        val exited = mutableListOf<Pair<String, String>>()
        val prismRequests = mutableListOf<PrismSwitchRequest>()
        val logs = mutableListOf<String>()

        val bridge = object : LuaBridge {
            override val screenWidth: Int = 1080
            override val screenHeight: Int = 2400
            override fun isStopped(): Boolean = false
            override fun isPaused(): Boolean = false
            override fun click(x: Int, y: Int, durationMs: Int): Boolean = true
            override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean = true
            override fun sleepCooperative(ms: Long) {}
            override fun getColorArgb(x: Int, y: Int): Int? = null
            override fun launchApp(packageName: String, freshStart: Boolean): Boolean {
                launched += packageName to freshStart
                return true
            }
            override fun exitApp(packageName: String, mode: String): Boolean {
                exited += packageName to mode
                return true
            }
            override fun switchPrismAccount(request: PrismSwitchRequest): Boolean {
                prismRequests += request
                return true
            }
            override fun log(message: String) { logs += message }
        }

        val script = """
            assert(launchApp("app.prism.c.p9a2b3c.u01", false))
            assert(prismSwitchAccount("com.supercell.clashofclans", 2, "root_u02", "Farm 2"))
            assert(prismSwitchNext("com.supercell.clashofclans"))
            assert(prismSwitchStub("app.prism.c.p9a2b3c.u03"))
            assert(exitApp("com.supercell.clashofclans", "home"))
            log("PRISM_LUA_OK")
        """.trimIndent()

        val result = SandboxedLuaEngine.execute(script, bridge)
        assertTrue("Lua script failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(listOf("app.prism.c.p9a2b3c.u01" to false), launched)
        assertEquals(listOf("com.supercell.clashofclans" to "home"), exited)
        assertEquals(3, prismRequests.size)
        assertEquals(2, prismRequests[0].slotOrder)
        assertEquals("root_u02", prismRequests[0].profileId)
        assertTrue(prismRequests[1].switchNext)
        assertEquals("com.supercell.clashofclans", prismRequests[1].targetPackage)
        assertEquals("app.prism.c.p9a2b3c.u03", prismRequests[2].stubPackage)
        assertEquals(3, prismRequests[2].slotOrder)
        assertEquals(listOf("PRISM_LUA_OK"), logs)
    }
}
