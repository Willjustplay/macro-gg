package com.example

import com.example.automation.BotHttpRequest
import com.example.automation.BotHttpResponse
import com.example.automation.LogLevel
import com.example.automation.MacroRunner
import com.example.automation.MacroRuntime
import com.example.automation.RemoteBotApiClient
import com.example.automation.RemoteBotAuth
import com.example.automation.RemoteBotCommand
import com.example.automation.RemoteBotCommandExecutor
import com.example.automation.RemoteBotCommandParser
import com.example.automation.RemoteBotHttpTransport
import com.example.automation.RemoteControlHost
import com.example.automation.RemoteHostOpResult
import com.example.automation.ToggleOp
import com.example.data.ActionRemoteControl
import com.example.data.ClickAction
import com.example.data.DeviceHealthSnapshot
import com.example.data.LiveSessionStats
import com.example.data.Macro
import com.example.data.MacroSecurity
import com.example.data.RecordedGesture
import com.example.data.RemoteActionMode
import com.example.data.RemoteIdSanitizer
import com.example.vision.Frame
import com.example.vision.PreparedTemplate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBotControlTest {

    @Test
    fun sanitizerProducesCleanUniqueSlugsWithinTelegramCallbackLimit() {
        assertEquals("farm_coc_1", RemoteIdSanitizer.sanitize("  Farm COC #1!!  "))
        assertEquals(24, RemoteIdSanitizer.sanitize("a".repeat(50)).length)

        val existing = setOf("farm", "farm_2")
        assertEquals("farm_3", RemoteIdSanitizer.suggestMacroId("Farm", existing))
        assertEquals("auto_sell_2", RemoteIdSanitizer.suggestActionId("Auto Sell", setOf("auto_sell")))
    }

    @Test
    fun commandParserSupportsTelegramDiscordDeviceTagsAndInlineCallbacks() {
        assertEquals(
            RemoteBotCommand.MacroSwitch("farm1", ToggleOp.ON),
            RemoteBotCommandParser.parseText("/on@WjustBot farm1")
        )
        assertEquals(
            RemoteBotCommand.MacroSwitch("farm1", ToggleOp.OFF, targetDevice = "hp2"),
            RemoteBotCommandParser.parseText("!off hp2:farm1")
        )
        assertEquals(
            RemoteBotCommand.ActionSwitch("farm1", "auto_sell", ToggleOp.ON, targetDevice = "hp1"),
            RemoteBotCommandParser.parseText("@hp1 /act farm1 auto_sell on")
        )
        assertEquals(
            RemoteBotCommand.ActionSwitch("farm1", "claim", ToggleOp.ON),
            RemoteBotCommandParser.parseText("!trigger farm1 claim")
        )
        assertEquals(
            RemoteBotCommand.Screenshot(targetDevice = "hp2"),
            RemoteBotCommandParser.parseText("/ss @hp2")
        )
        assertEquals(
            RemoteBotCommand.Screenshot(targetDevice = "hp1"),
            RemoteBotCommandParser.parseText("/ss hp1")
        )
        assertEquals(
            RemoteBotCommand.AfkSwitch(ToggleOp.ON),
            RemoteBotCommandParser.parseText("!afk on")
        )
        // Normal chat message without / or ! prefix must be ignored
        assertNull(RemoteBotCommandParser.parseText("halo semua lagi farming"))

        // Telegram inline keyboard callback parsing
        assertEquals(
            RemoteBotCommand.Menu(fromCallback = true),
            RemoteBotCommandParser.parseCallbackData("m:home")
        )
        assertEquals(
            RemoteBotCommand.ActionSwitch("farm1", "auto_sell", ToggleOp.TOGGLE, fromCallback = true),
            RemoteBotCommandParser.parseCallbackData("m:atg:farm1:auto_sell")
        )
    }

    @Test
    fun authEnforcesStrictWhitelistsAndBlocksBots() {
        assertFalse(RemoteBotAuth.isTelegramAuthorized("111", "222", "", ""))
        assertTrue(RemoteBotAuth.isTelegramAuthorized("111", "999", "111", ""))
        assertTrue(RemoteBotAuth.isTelegramAuthorized("888", "222", "111", "222, 333"))
        assertFalse(RemoteBotAuth.isTelegramAuthorized("888", "999", "111", "222, 333"))

        assertFalse(RemoteBotAuth.isDiscordAuthorized("555", isBot = true, allowedUserIdsRaw = ""))
        assertTrue(RemoteBotAuth.isDiscordAuthorized("555", isBot = false, allowedUserIdsRaw = ""))
        assertTrue(RemoteBotAuth.isDiscordAuthorized("555", isBot = false, allowedUserIdsRaw = "444, 555"))
        assertFalse(RemoteBotAuth.isDiscordAuthorized("777", isBot = false, allowedUserIdsRaw = "444, 555"))
    }

    @Test
    fun executorHandlesActiveAndPassiveMacrosActionsDeviceTagAndScreenshot() {
        val sellAct = ClickAction(id = "act_sell", x = 100, y = 200)
        val claimAct = ClickAction(id = "act_claim", x = 300, y = 400)
        val activeMacro = Macro(
            id = "m_active",
            name = "Farm Utama",
            isPassive = false,
            remoteControlEnabled = true,
            remoteId = "farm1",
            actions = listOf(sellAct, claimAct),
            actionRemoteControls = mapOf(
                sellAct.id to ActionRemoteControl(enabled = true, remoteId = "sell", label = "Auto Jual", mode = RemoteActionMode.TOGGLE, active = true),
                claimAct.id to ActionRemoteControl(enabled = true, remoteId = "claim", label = "Klaim Mail", mode = RemoteActionMode.ONE_SHOT, active = false)
            )
        )
        val passiveMacro = Macro(
            id = "m_passive",
            name = "Sub Boss",
            isPassive = true,
            passiveEnabled = false,
            remoteControlEnabled = true,
            remoteId = "boss"
        )
        val privateMacro = Macro(
            id = "m_private",
            name = "Rahasia",
            remoteControlEnabled = false,
            remoteId = "secret"
        )

        val macros = linkedMapOf(
            activeMacro.id to activeMacro,
            passiveMacro.id to passiveMacro,
            privateMacro.id to privateMacro
        )
        var runningId: String? = null
        var afkOn = false

        val host = object : RemoteControlHost {
            override val appDisplayName = "Wjust macro"
            override val deviceTag = "hp1"
            override fun listMacros(): List<Macro> = macros.values.toList()
            override fun runningMacroId(): String? = runningId
            override fun liveStats(): LiveSessionStats? =
                runningId?.let { LiveSessionStats("Farm Utama", System.currentTimeMillis() - 5000L, 3, 25, 0, 0, 0, 0) }
            override fun deviceHealth(): DeviceHealthSnapshot = DeviceHealthSnapshot(batteryPercent = 84, isCharging = false, tempCelsius = 36.2f)
            override fun isAfkBlackScreenActive(): Boolean = afkOn
            override fun startActiveMacro(macro: Macro): RemoteHostOpResult {
                runningId = macro.id
                return RemoteHostOpResult(true, "Start OK")
            }
            override fun stopActiveMacro(macro: Macro): RemoteHostOpResult {
                if (runningId == macro.id) runningId = null
                return RemoteHostOpResult(true, "Stop OK")
            }
            override fun setPassiveMacroEnabled(macro: Macro, enabled: Boolean): RemoteHostOpResult {
                macros[macro.id] = macros[macro.id]!!.copy(passiveEnabled = enabled)
                return RemoteHostOpResult(true, "Passive=$enabled")
            }
            override fun setActionRemoteActive(macro: Macro, actionId: String, active: Boolean): RemoteHostOpResult {
                macros[macro.id] = MacroSecurity.setRemoteActionActive(macros[macro.id]!!, actionId, active)
                return RemoteHostOpResult(true, "Action=$active")
            }
            override fun setAfkBlackScreen(enabled: Boolean): RemoteHostOpResult {
                afkOn = enabled
                return RemoteHostOpResult(true, "AFK=$enabled")
            }
            override fun captureScreenshotJpeg(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02)
        }

        // Command aimed at another phone is silently ignored
        assertNull(RemoteBotCommandExecutor.execute(RemoteBotCommand.MacroSwitch("farm1", ToggleOp.ON, targetDevice = "hp2"), host))

        // Active macro ON -> Starts, OFF -> Stops
        val onReply = RemoteBotCommandExecutor.execute(RemoteBotCommand.MacroSwitch("farm1", ToggleOp.ON), host)!!
        assertEquals("m_active", runningId)
        assertTrue(onReply.text.contains("Start OK"))

        val offReply = RemoteBotCommandExecutor.execute(RemoteBotCommand.MacroSwitch("farm1", ToggleOp.OFF), host)!!
        assertNull(runningId)
        assertTrue(offReply.text.contains("Stop OK"))

        // Passive macro ON/OFF flips passiveEnabled without starting it directly
        RemoteBotCommandExecutor.execute(RemoteBotCommand.MacroSwitch("boss", ToggleOp.ON), host)
        assertTrue(macros["m_passive"]!!.passiveEnabled)
        assertNull(runningId)
        RemoteBotCommandExecutor.execute(RemoteBotCommand.MacroSwitch("boss", ToggleOp.OFF), host)
        assertFalse(macros["m_passive"]!!.passiveEnabled)

        // Unenabled macro is refused
        val secretReply = RemoteBotCommandExecutor.execute(RemoteBotCommand.MacroSwitch("secret", ToggleOp.ON), host)!!
        assertTrue(secretReply.text.contains("tidak ditemukan"))

        // Action TOGGLE & ONE_SHOT
        RemoteBotCommandExecutor.execute(RemoteBotCommand.ActionSwitch("farm1", "sell", ToggleOp.OFF), host)
        assertFalse(macros["m_active"]!!.actionRemoteControls[sellAct.id]!!.active)
        RemoteBotCommandExecutor.execute(RemoteBotCommand.ActionSwitch("farm1", "claim", ToggleOp.ON), host)
        assertTrue(macros["m_active"]!!.actionRemoteControls[claimAct.id]!!.active)

        // Inline menu buttons stay strictly under Telegram's 64-byte callback_data limit
        val menu = RemoteBotCommandExecutor.execute(RemoteBotCommand.Menu(fromCallback = true), host)!!
        assertTrue(menu.editOriginalMessage)
        assertTrue(menu.inlineRows.flatten().all { it.callbackData.toByteArray(Charsets.UTF_8).size <= 64 })

        // Screenshot & AFK commands
        val ss = RemoteBotCommandExecutor.execute(RemoteBotCommand.Screenshot(), host)!!
        assertNotNull(ss.jpegBytes)
        RemoteBotCommandExecutor.execute(RemoteBotCommand.AfkSwitch(ToggleOp.ON), host)
        assertTrue(afkOn)
    }

    @Test
    fun macroRunnerRespectsLiveToggleAndAutoResetsOneShotAction() = runBlocking {
        val alwaysAct = ClickAction(id = "a_always", x = 10, y = 10, delayAfterMs = 0L)
        val toggleAct = ClickAction(id = "a_toggle", x = 20, y = 20, delayAfterMs = 0L)
        val oneShotAct = ClickAction(id = "a_oneshot", x = 30, y = 30, delayAfterMs = 0L)

        var liveMacro = Macro(
            id = "m_live",
            name = "Live Bot Test",
            loopCount = 3,
            loopDelayMs = 0L,
            remoteControlEnabled = true,
            remoteId = "live",
            actions = listOf(alwaysAct, toggleAct, oneShotAct),
            actionRemoteControls = mapOf(
                toggleAct.id to ActionRemoteControl(enabled = true, remoteId = "tog", mode = RemoteActionMode.TOGGLE, active = false),
                oneShotAct.id to ActionRemoteControl(enabled = true, remoteId = "once", mode = RemoteActionMode.ONE_SHOT, active = true)
            )
        )

        val clickedXs = mutableListOf<Int>()
        var currentLoop = 0
        val runtime = object : MacroRuntime {
            override fun isStopped() = false
            override suspend fun waitIfPaused() {}
            override suspend fun sleep(ms: Long) {}
            override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
                clickedXs += x
                return true
            }
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
            override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float) = true
            override fun captureFrame(): Frame? = null
            override fun samplePixelArgb(x: Int, y: Int): Int? = null
            override fun loadTemplate(name: String): PreparedTemplate? = null
            override fun loadMacro(id: String): Macro? = if (id == liveMacro.id) liveMacro else null
            override fun onLoopChanged(loop: Int) {
                currentLoop = loop
                // Simulate user flipping toggleAct ON from Telegram right before loop 2!
                if (loop == 2) {
                    liveMacro = MacroSecurity.setRemoteActionActive(liveMacro, toggleAct.id, true)
                }
            }
            override fun onOneShotRemoteActionConsumed(macroId: String, actionId: String) {
                liveMacro = MacroSecurity.setRemoteActionActive(liveMacro, actionId, false)
            }
            override fun log(level: LogLevel, message: String) {}
        }

        MacroRunner(runtime).run(liveMacro)

        // Loop 1: always(10) + oneShot(30) [toggle OFF]
        // Loop 2: always(10) + toggle(20)  [oneShot auto-reset OFF!]
        // Loop 3: always(10) + toggle(20)  [oneShot still OFF]
        assertEquals(listOf(10, 30, 10, 20, 10, 20), clickedXs)
        assertFalse(liveMacro.actionRemoteControls[oneShotAct.id]!!.active)
        assertTrue(liveMacro.actionRemoteControls[toggleAct.id]!!.active)
        assertEquals(3, currentLoop)
    }

    @Test
    fun macroSecurityPreservesSealWhenUpdatingRemoteIdOrTogglingRemoteAction() {
        val act = ClickAction(id = "a1", x = 50, y = 50)
        val base = Macro(
            id = "m_sec",
            name = "Locked Macro",
            actions = listOf(act),
            actionRemoteControls = mapOf(
                act.id to ActionRemoteControl(enabled = true, remoteId = "sell", mode = RemoteActionMode.TOGGLE, active = true)
            )
        )
        val locked = MacroSecurity.applySecurity(
            macro = base,
            isLocked = true,
            isPassive = true,
            expiresAtEpochMs = System.currentTimeMillis() + 86_400_000L,
            expiryLabel = "1 Hari",
            newPassword = "secret",
            passiveEnabled = true
        )
        assertFalse(MacroSecurity.isTampered(locked))

        val withId = MacroSecurity.updateMacroRemoteControl(locked, true, "Sub_Boss")
        assertEquals("sub_boss", withId.remoteId)
        assertTrue(withId.remoteControlEnabled)
        assertFalse(MacroSecurity.isTampered(withId))

        val flippedAct = MacroSecurity.setRemoteActionActive(withId, act.id, false)
        assertFalse(flippedAct.actionRemoteControls[act.id]!!.active)
        assertFalse(MacroSecurity.isTampered(flippedAct))
    }

    @Test
    fun apiClientParsesTelegramUpdatesAndDiscordMessagesChronologically() {
        val tgJson = """
            {
              "ok": true,
              "result": [
                {
                  "update_id": 1001,
                  "message": {
                    "message_id": 10,
                    "from": {"id": 555},
                    "chat": {"id": 555},
                    "text": "/on farm1"
                  }
                },
                {
                  "update_id": 1002,
                  "callback_query": {
                    "id": "cb_9",
                    "from": {"id": 555},
                    "data": "m:atg:farm1:sell",
                    "message": {"message_id": 42, "chat": {"id": 555}}
                  }
                }
              ]
            }
        """.trimIndent()
        val (tgOk, tgEvents) = RemoteBotApiClient.pollTelegramUpdates(
            token = "tg_tok",
            offset = 1001L,
            timeoutSec = 0,
            transport = RemoteBotHttpTransport { BotHttpResponse(200, tgJson) }
        )
        assertTrue(tgOk)
        assertEquals(2, tgEvents.size)
        assertEquals("/on farm1", tgEvents[0].text)
        assertEquals("m:atg:farm1:sell", tgEvents[1].callbackData)
        assertEquals(42L, tgEvents[1].callbackMessageId)

        val dcJson = """
            [
              {"id": "200", "channel_id": "99", "content": "!off farm1", "author": {"id": "77", "bot": false}},
              {"id": "150", "channel_id": "99", "content": "!on farm1", "author": {"id": "77", "bot": false}},
              {"id": "175", "channel_id": "99", "content": "Bot reply", "author": {"id": "1", "bot": true}}
            ]
        """.trimIndent()
        val (dcOk, dcMsgs) = RemoteBotApiClient.pollDiscordMessages(
            botToken = "dc_tok",
            channelId = "99",
            afterMessageId = "100",
            transport = RemoteBotHttpTransport { req ->
                assertEquals("Bot dc_tok", req.headers["Authorization"])
                BotHttpResponse(200, dcJson)
            }
        )
        assertTrue(dcOk)
        assertEquals(listOf("150", "175", "200"), dcMsgs.map { it.messageId })
        assertTrue(dcMsgs[1].authorIsBot)

        val recorded = mutableListOf<BotHttpRequest>()
        val mockTransport = RemoteBotHttpTransport { req ->
            recorded += req
            BotHttpResponse(200, """{"ok":true}""")
        }
        val sent = RemoteBotApiClient.sendDiscordReply(
            botToken = "tok123",
            channelId = "99",
            reply = com.example.automation.RemoteBotReply(text = "SS", jpegBytes = byteArrayOf(1, 2, 3)),
            transport = mockTransport
        )
        assertTrue(sent)
        assertEquals("Bot tok123", recorded.first().headers["Authorization"])
        assertTrue(recorded.first().contentType!!.startsWith("multipart/form-data"))
    }

    @Test
    fun listenerRunsScreenshotAsynchronouslyWithoutStoppingRunningMacroOrFailingLoop() = runBlocking {
        val ssStarted = java.util.concurrent.CountDownLatch(1)
        val allowSsFinish = java.util.concurrent.CountDownLatch(1)
        val statusSent = java.util.concurrent.CountDownLatch(1)
        val photoOrFallbackSent = java.util.concurrent.CountDownLatch(2)
        val pollCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val stopCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val captureCalls = java.util.concurrent.atomic.AtomicInteger(0)
        var runningId: String? = "m_running"

        val mockTransport = RemoteBotHttpTransport { req ->
            when {
                req.url.contains("/getUpdates") -> {
                    when (pollCalls.getAndIncrement()) {
                        // Bootstrap drain
                        0 -> BotHttpResponse(200, """{"ok":true,"result":[]}""")
                        // Batch 1: /ss (blocks briefly) followed by /status in same poll batch
                        1 -> BotHttpResponse(
                            200,
                            """{"ok":true,"result":[
                                {"update_id":10,"message":{"message_id":1,"from":{"id":555},"chat":{"id":555},"text":"/ss"}},
                                {"update_id":11,"message":{"message_id":2,"from":{"id":555},"chat":{"id":555},"text":"/status"}}
                            ]}"""
                        )
                        // Batch 2: /ss when capture throws an Error — must NEVER stop macro or kill listener
                        2 -> BotHttpResponse(
                            200,
                            """{"ok":true,"result":[
                                {"update_id":12,"message":{"message_id":3,"from":{"id":555},"chat":{"id":555},"text":"/ss"}}
                            ]}"""
                        )
                        // Batch 3: /ss when capture returns null — sends polite fallback text, macro stays running
                        3 -> BotHttpResponse(
                            200,
                            """{"ok":true,"result":[
                                {"update_id":13,"message":{"message_id":4,"from":{"id":555},"chat":{"id":555},"text":"/ss"}}
                            ]}"""
                        )
                        else -> {
                            Thread.sleep(60L)
                            BotHttpResponse(200, """{"ok":true,"result":[]}""")
                        }
                    }
                }
                req.url.contains("/sendPhoto") -> {
                    photoOrFallbackSent.countDown()
                    BotHttpResponse(200, """{"ok":true}""")
                }
                req.url.contains("/sendMessage") -> {
                    val bodyStr = req.body?.toString(Charsets.UTF_8).orEmpty()
                    if (bodyStr.contains("Status Wjust macro")) {
                        statusSent.countDown()
                    } else if (bodyStr.contains("Gagal mengambil screenshot")) {
                        photoOrFallbackSent.countDown()
                    }
                    BotHttpResponse(200, """{"ok":true}""")
                }
                else -> BotHttpResponse(200, """{"ok":true}""")
            }
        }

        val runningMacro = Macro(
            id = "m_running",
            name = "Farm Aktif",
            remoteControlEnabled = true,
            remoteId = "farm1"
        )
        val host = object : RemoteControlHost {
            override val appDisplayName = "Wjust macro"
            override val deviceTag = ""
            override fun listMacros(): List<Macro> = listOf(runningMacro)
            override fun runningMacroId(): String? = runningId
            override fun liveStats(): LiveSessionStats =
                LiveSessionStats("Farm Aktif", System.currentTimeMillis() - 10_000L, 4, 40, 0, 0, 0, 0)
            override fun deviceHealth(): DeviceHealthSnapshot = DeviceHealthSnapshot(90, false, 35.0f)
            override fun isAfkBlackScreenActive(): Boolean = false
            override fun startActiveMacro(macro: Macro) = RemoteHostOpResult(true, "OK")
            override fun stopActiveMacro(macro: Macro): RemoteHostOpResult {
                stopCalls.incrementAndGet()
                runningId = null
                return RemoteHostOpResult(true, "Stopped")
            }
            override fun setPassiveMacroEnabled(macro: Macro, enabled: Boolean) = RemoteHostOpResult(true, "OK")
            override fun setActionRemoteActive(macro: Macro, actionId: String, active: Boolean) = RemoteHostOpResult(true, "OK")
            override fun setAfkBlackScreen(enabled: Boolean) = RemoteHostOpResult(true, "OK")
            override fun captureScreenshotJpeg(): ByteArray? {
                return when (captureCalls.getAndIncrement()) {
                    0 -> {
                        ssStarted.countDown()
                        allowSsFinish.await(3, java.util.concurrent.TimeUnit.SECONDS)
                        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22)
                    }
                    1 -> throw OutOfMemoryError("Simulated heavy game heap pressure")
                    else -> null
                }
            }
        }

        val botScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() +
                kotlinx.coroutines.Dispatchers.IO +
                kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> }
        )
        val listener = com.example.automation.RemoteBotListener(transport = mockTransport)
        try {
            val cfg = com.example.data.NotificationConfig(
                telegramBotToken = "tg_tok",
                telegramChatId = "555",
                remoteBot = com.example.data.RemoteBotConfig(
                    listenerEnabled = true,
                    telegramRemoteEnabled = true
                )
            )
            listener.sync(scope = botScope, config = cfg, allowed = true, hostProvider = { host })

            // /status MUST complete even while /ss #1 is still blocked inside captureScreenshotJpeg()!
            assertTrue("Expected /ss #1 to start", ssStarted.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("Expected /status not to be blocked by /ss", statusSent.await(3, java.util.concurrent.TimeUnit.SECONDS))

            // Now let /ss #1 finish, then let /ss #2 (OOM) and /ss #3 (null fallback) complete
            allowSsFinish.countDown()
            assertTrue(
                "Expected /ss #1 photo and /ss #3 fallback reply",
                photoOrFallbackSent.await(5, java.util.concurrent.TimeUnit.SECONDS)
            )

            // Running macro must NEVER be stopped or touched by any /ss outcome
            assertEquals("m_running", runningId)
            assertEquals(0, stopCalls.get())
            assertTrue(listener.status.value.telegramListening)
        } finally {
            listener.stop()
            botScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
