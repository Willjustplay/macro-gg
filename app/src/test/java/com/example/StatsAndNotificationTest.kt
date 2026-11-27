package com.example

import com.example.automation.LogLevel
import com.example.automation.MacroRunner
import com.example.automation.MacroRuntime
import com.example.automation.RemoteHttpRequest
import com.example.automation.RemoteHttpResponse
import com.example.automation.RemoteHttpTransport
import com.example.automation.RemoteNotifier
import com.example.data.AccountProfile
import com.example.data.AccountSwitchConfig
import com.example.data.AccountSwitchMethod
import com.example.data.AccountTriggerMode
import com.example.data.ActionKind
import com.example.data.BannedImageMode
import com.example.data.ClickAction
import com.example.data.FarmingSessionRecord
import com.example.data.FarmingStatsCalculator
import com.example.data.HumanizerConfig
import com.example.data.HumanizerPreset
import com.example.data.Macro
import com.example.data.NotificationConfig
import com.example.data.NotifyAction
import com.example.data.NotifyLevel
import com.example.data.RecordedGesture
import com.example.data.StuckRecoveryMode
import com.example.data.SwitchAccountAction
import com.example.data.VariableAction
import com.example.data.VariableOperation
import com.example.data.VariableScope
import com.example.data.WaitAction
import com.example.data.create
import com.example.data.summary
import com.example.vision.Frame
import com.example.vision.PreparedTemplate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsAndNotificationTest {

    private val testJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    private data class RecordedNotification(
        val title: String,
        val message: String,
        val level: NotifyLevel,
        val sendRemote: Boolean,
        val includeScreenshot: Boolean,
        val triggerPhoneAlarm: Boolean
    )

    private open class TelemetryRuntime(
        var frameProvider: () -> Frame? = { null },
        val templates: MutableMap<String, PreparedTemplate> = HashMap(),
        var virtualTimeMs: Long = 1_000_000L
    ) : MacroRuntime {
        var stopped = false
        val notifications = ArrayList<RecordedNotification>()
        val watchdogModes = ArrayList<String>()
        val bannedBlocked = ArrayList<String>()
        val switchedAccounts = ArrayList<String>()
        val humanBreaks = ArrayList<Int>()
        val clicks = ArrayList<Pair<Int, Int>>()

        override fun isStopped(): Boolean = stopped
        override suspend fun waitIfPaused() {}
        override suspend fun sleep(ms: Long) {
            virtualTimeMs += ms.coerceAtLeast(0L)
        }
        override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
            clicks += x to y
            return true
        }
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = true
        override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float): Boolean = true
        override fun captureFrame(): Frame? = frameProvider()
        override fun samplePixelArgb(x: Int, y: Int): Int? = null
        override fun loadTemplate(name: String): PreparedTemplate? = templates[name]
        override fun log(level: LogLevel, message: String) {}
        override fun nowMs(): Long = virtualTimeMs

        override fun onWatchdogTriggered(modeLabel: String) {
            watchdogModes += modeLabel
        }
        override fun onBannedBlocked(imageName: String) {
            bannedBlocked += imageName
        }
        override fun onAccountSwitched(accountLabel: String) {
            switchedAccounts += accountLabel
        }
        override fun onHumanBreakStarted(durationSec: Int) {
            humanBreaks += durationSec
        }
        override suspend fun sendNotification(
            title: String,
            message: String,
            level: NotifyLevel,
            sendRemote: Boolean,
            includeScreenshot: Boolean,
            triggerPhoneAlarm: Boolean
        ) {
            notifications += RecordedNotification(
                title = title,
                message = message,
                level = level,
                sendRemote = sendRemote,
                includeScreenshot = includeScreenshot,
                triggerPhoneAlarm = triggerPhoneAlarm
            )
        }
    }

    private fun solidFrame(w: Int, h: Int, argb: Int): Frame {
        val px = IntArray(w * h) { argb }
        return Frame(w, h, px)
    }

    private fun patternedTemplate(name: String, w: Int, h: Int): PreparedTemplate {
        val px = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            val v = ((x * 17 + y * 31) and 0xFF)
            (0xFF shl 24) or (v shl 16) or ((255 - v) shl 8) or ((v / 2) and 0xFF)
        }
        return PreparedTemplate(name, Frame(w, h, px))
    }

    private fun frameWithEmbeddedPattern(fw: Int, fh: Int, pxX: Int, pxY: Int, tw: Int, th: Int): Frame {
        val pixels = IntArray(fw * fh) { 0xFF101010.toInt() }
        for (y in 0 until th) {
            for (x in 0 until tw) {
                val v = ((x * 17 + y * 31) and 0xFF)
                val c = (0xFF shl 24) or (v shl 16) or ((255 - v) shl 8) or ((v / 2) and 0xFF)
                pixels[(pxY + y) * fw + (pxX + x)] = c
            }
        }
        return Frame(fw, fh, pixels)
    }

    @Test
    fun farmingStatsCalculatorComputesRatesSummaryAndBreakdown() {
        assertEquals(0.0, FarmingStatsCalculator.ratePerHour(10, 500L), 0.001)
        assertEquals(60.0, FarmingStatsCalculator.ratePerHour(30, 30 * 60_000L), 0.001)

        val s1 = FarmingSessionRecord(
            macroId = "m1",
            macroName = "Farm Boss",
            startedAtMs = 1_000L,
            endedAtMs = 1_801_000L,
            durationMs = 1_800_000L, // 30m
            loopsCompleted = 45,
            actionsExecuted = 450,
            watchdogRecoveries = 1,
            bannedBlocks = 2,
            accountSwitches = 1,
            humanBreaks = 1
        )
        val s2 = FarmingSessionRecord(
            macroId = "m1",
            macroName = "Farm Boss",
            startedAtMs = 2_000_000L,
            endedAtMs = 3_800_000L,
            durationMs = 1_800_000L, // 30m
            loopsCompleted = 55,
            actionsExecuted = 550,
            watchdogRecoveries = 0,
            bannedBlocks = 1,
            accountSwitches = 2,
            humanBreaks = 0
        )
        val s3 = FarmingSessionRecord(
            macroId = "m2",
            macroName = "Daily Quest",
            startedAtMs = 4_000_000L,
            endedAtMs = 4_600_000L,
            durationMs = 600_000L, // 10m
            loopsCompleted = 10,
            actionsExecuted = 100
        )

        val summary = FarmingStatsCalculator.summarize(listOf(s1, s2, s3))
        assertEquals(3, summary.sessionCount)
        assertEquals(4_200_000L, summary.totalDurationMs)
        assertEquals(110, summary.totalLoops)
        assertEquals(1100, summary.totalActions)
        assertEquals(1, summary.totalWatchdogRecoveries)
        assertEquals(3, summary.totalBannedBlocks)
        assertEquals(3, summary.totalAccountSwitches)
        assertEquals(1, summary.totalHumanBreaks)
        assertTrue(summary.avgLoopsPerHour > 90.0 && summary.avgLoopsPerHour < 96.0)

        val breakdown = FarmingStatsCalculator.breakdownByMacro(listOf(s1, s2, s3))
        assertEquals(2, breakdown.size)
        assertEquals("m1", breakdown[0].macroId)
        assertEquals(100, breakdown[0].totalLoops)
        assertEquals(100.0, breakdown[0].loopsPerHour, 0.01)
        assertEquals("m2", breakdown[1].macroId)

        assertEquals("45d", FarmingStatsCalculator.formatDuration(45_000L))
        assertEquals("3m 12d", FarmingStatsCalculator.formatDuration(192_000L))
        assertEquals("2j 15m", FarmingStatsCalculator.formatDuration((2 * 3600 + 15 * 60) * 1000L))
    }

    @Test
    fun expandNotifyTemplateSubstitutesAllBuiltInAndVariablePlaceholders() {
        val out = FarmingStatsCalculator.expandNotifyTemplate(
            template = "[{macro}] Loop={loop} Aksi={actions} Akun={account} Gold={var:gold} Stamina={var:stamina} Kosong={var:missing}",
            macroName = "COC Farm",
            loop = 12,
            actionsExecuted = 340,
            accountLabel = "Akun Utama",
            variables = mapOf("gold" to 12500.0, "👤stamina" to 42.5)
        )
        assertEquals("[COC Farm] Loop=12 Aksi=340 Akun=Akun Utama Gold=12500 Stamina=42.5 Kosong=0", out)
    }

    @Test
    fun notifyActionRunsInsideMacroRunnerAndExpandsLiveVariablesAndAccount() = runBlocking {
        val rt = TelemetryRuntime()
        val macro = Macro(
            name = "Dungeon Bot",
            loopCount = 1,
            accountSwitch = AccountSwitchConfig(
                enabled = true,
                method = AccountSwitchMethod.CLONE_APP,
                triggerMode = AccountTriggerMode.MANUAL_ACTION_ONLY,
                accounts = listOf(
                    AccountProfile(id = "a1", label = "Hero1", packageName = "com.game.one", enabled = true)
                )
            ),
            actions = listOf(
                VariableAction(
                    variableName = "gold",
                    operation = VariableOperation.SET,
                    value = 1500.0,
                    scope = VariableScope.SESSION,
                    delayAfterMs = 0L
                ),
                VariableAction(
                    variableName = "gold",
                    operation = VariableOperation.ADD,
                    value = 250.0,
                    scope = VariableScope.SESSION,
                    delayAfterMs = 0L
                ),
                NotifyAction(
                    title = "Drop Langka ({macro})",
                    message = "Loop #{loop} • Akun: {account} • Gold: {var:gold} • Aksi: {actions}",
                    level = NotifyLevel.SUCCESS,
                    sendRemote = true,
                    includeScreenshot = true,
                    triggerPhoneAlarm = true,
                    delayAfterMs = 0L
                )
            )
        )

        MacroRunner(rt).run(macro)

        assertEquals(1, rt.notifications.size)
        val n = rt.notifications.first()
        assertEquals("Drop Langka (Dungeon Bot)", n.title)
        assertTrue(n.message.contains("Loop #1"))
        assertTrue(n.message.contains("Akun: Hero1"))
        assertTrue(n.message.contains("Gold: 1750"))
        assertEquals(NotifyLevel.SUCCESS, n.level)
        assertTrue(n.sendRemote)
        assertTrue(n.includeScreenshot)
        assertTrue(n.triggerPhoneAlarm)
    }

    @Test
    fun macroRunnerInvokesTelemetryHooksForBannedGuardAccountSwitchWatchdogAndHumanBreak() = runBlocking {
        // 1. Banned guard blocks a click near banned template
        val tpl = patternedTemplate("danger_btn", 24, 24)
        val frame = frameWithEmbeddedPattern(120, 120, 40, 40, 24, 24)
        val rtBanned = object : TelemetryRuntime(
            frameProvider = { frame },
            templates = mutableMapOf("danger_btn" to tpl)
        ) {
            override val screenWidth: Int = 120
            override val screenHeight: Int = 120
        }
        val macroBanned = Macro(
            name = "Banned Guard Test",
            loopCount = 1,
            sourceWidth = 120,
            sourceHeight = 120,
            bannedGuardEnabled = true,
            bannedImages = listOf("danger_btn"),
            bannedThreshold = 0.85f,
            bannedAvoidRadiusPx = 12,
            bannedMode = BannedImageMode.AVOID_MATCH_AREA,
            actions = listOf(
                ClickAction(x = 52, y = 52, delayAfterMs = 0L)
            )
        )
        MacroRunner(rtBanned).run(macroBanned)
        assertTrue("Expected click to be blocked", rtBanned.clicks.isEmpty())
        assertEquals(listOf("danger_btn"), rtBanned.bannedBlocked)

        // 2. Account switch + Human Break hooks
        val rtSwitch = TelemetryRuntime()
        val macroSwitch = Macro(
            name = "Multi + Break",
            loopCount = 1,
            loopDelayMs = 0L,
            humanizer = HumanizerConfig(
                preset = HumanizerPreset.CUSTOM,
                humanBreakEnabled = true,
                breakEveryMinMinutes = 1,
                breakEveryMaxMinutes = 1,
                breakDurationMinSec = 5,
                breakDurationMaxSec = 5
            ),
            accountSwitch = AccountSwitchConfig(
                enabled = true,
                method = AccountSwitchMethod.CLONE_APP,
                triggerMode = AccountTriggerMode.MANUAL_ACTION_ONLY,
                accounts = listOf(
                    AccountProfile(id = "a1", label = "Akun 1", packageName = "com.g.a1", enabled = true),
                    AccountProfile(id = "a2", label = "Akun 2", packageName = "com.g.a2", enabled = true)
                )
            ),
            actions = listOf(
                WaitAction(durationMs = 65_000L, delayAfterMs = 0L),
                SwitchAccountAction(restartAfterSwitch = false, delayAfterMs = 0L)
            )
        )
        MacroRunner(rtSwitch).run(macroSwitch)
        assertTrue("Expected onAccountSwitched callback", rtSwitch.switchedAccounts.contains("Akun 2"))
        assertTrue("Expected onHumanBreakStarted callback", rtSwitch.humanBreaks.isNotEmpty())

        // 3. Watchdog stuck recovery hook
        val staticFrame = solidFrame(64, 64, 0xFF222222.toInt())
        val rtWatchdog = TelemetryRuntime(frameProvider = { staticFrame })
        val macroWatchdog = Macro(
            name = "Watchdog Test",
            loopCount = 3,
            loopDelayMs = 0L,
            stuckRecoveryMode = StuckRecoveryMode.RESTART_MACRO,
            autoRecoverStuckSec = 5,
            actions = listOf(
                WaitAction(durationMs = 6_000L, delayAfterMs = 0L)
            )
        )
        MacroRunner(rtWatchdog).run(macroWatchdog)
        assertTrue("Expected onWatchdogTriggered callback", rtWatchdog.watchdogModes.isNotEmpty())
    }

    @Test
    fun remoteNotifierDispatchesTelegramAndDiscordJsonAndMultipartJpeg() {
        val captured = ArrayList<RemoteHttpRequest>()
        val okTransport = RemoteHttpTransport { req ->
            captured += req
            RemoteHttpResponse(200, """{"ok":true}""")
        }
        val cfg = NotificationConfig(
            telegramEnabled = true,
            telegramBotToken = "123456:TEST_TOKEN",
            telegramChatId = "987654321",
            discordEnabled = true,
            discordWebhookUrl = "https://discord.com/api/webhooks/111111/abcdef_token"
        )

        // 1. Text-only JSON delivery
        val resText = RemoteNotifier.send(
            config = cfg,
            title = "Farm Selesai",
            message = "Loop 50 selesai dengan sukses",
            level = NotifyLevel.SUCCESS,
            jpegBytes = null,
            transport = okTransport
        )
        assertTrue(resText.telegramAttempted && resText.telegramOk)
        assertTrue(resText.discordAttempted && resText.discordOk)
        assertEquals(2, captured.size)

        val tgJsonReq = captured[0]
        assertEquals("https://api.telegram.org/bot123456:TEST_TOKEN/sendMessage", tgJsonReq.url)
        assertTrue(tgJsonReq.contentType.startsWith("application/json"))
        val tgBody = tgJsonReq.body.toString(Charsets.UTF_8)
        assertTrue(tgBody.contains("\"chat_id\":\"987654321\""))
        assertTrue(tgBody.contains("Farm Selesai"))

        val dcJsonReq = captured[1]
        assertEquals("https://discord.com/api/webhooks/111111/abcdef_token", dcJsonReq.url)
        assertTrue(dcJsonReq.contentType.startsWith("application/json"))
        val dcBody = dcJsonReq.body.toString(Charsets.UTF_8)
        assertTrue(dcBody.contains("\"embeds\""))
        assertTrue(dcBody.contains("Farm Selesai"))

        // 2. Multipart delivery with JPEG screenshot bytes
        captured.clear()
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val resShot = RemoteNotifier.send(
            config = cfg,
            title = "Watchdog Aktif",
            message = "Layar macet 60 detik",
            level = NotifyLevel.WARNING,
            jpegBytes = fakeJpeg,
            transport = okTransport
        )
        assertTrue(resShot.telegramOk && resShot.discordOk)
        assertEquals(2, captured.size)

        val tgPhotoReq = captured[0]
        assertEquals("https://api.telegram.org/bot123456:TEST_TOKEN/sendPhoto", tgPhotoReq.url)
        assertTrue(tgPhotoReq.contentType.startsWith("multipart/form-data; boundary="))
        val tgPhotoStr = tgPhotoReq.body.toString(Charsets.ISO_8859_1)
        assertTrue(tgPhotoStr.contains("name=\"photo\"; filename=\"screen.jpg\""))
        assertTrue(tgPhotoStr.contains("name=\"caption\""))

        val dcPhotoReq = captured[1]
        assertTrue(dcPhotoReq.contentType.startsWith("multipart/form-data; boundary="))
        val dcPhotoStr = dcPhotoReq.body.toString(Charsets.ISO_8859_1)
        assertTrue(dcPhotoStr.contains("name=\"payload_json\""))
        assertTrue(dcPhotoStr.contains("attachment://screen.jpg"))
        assertTrue(dcPhotoStr.contains("name=\"files[0]\"; filename=\"screen.jpg\""))
    }

    @Test
    fun remoteNotifierValidatesDiscordUrlsAndNeverThrowsOnHttpOrNetworkFailure() {
        assertTrue(RemoteNotifier.isValidDiscordWebhook("https://discord.com/api/webhooks/123/abc"))
        assertTrue(RemoteNotifier.isValidDiscordWebhook("https://discordapp.com/api/webhooks/123/abc"))
        assertFalse(RemoteNotifier.isValidDiscordWebhook("https://evil.example.com/webhooks/123/abc"))
        assertFalse(RemoteNotifier.isValidDiscordWebhook("http://discord.com/api/webhooks/123/abc"))

        var calls = 0
        val failingTransport = RemoteHttpTransport {
            calls++
            if (calls == 1) RemoteHttpResponse(401, "Unauthorized")
            else throw RuntimeException("Simulated socket timeout")
        }

        val cfgBadDiscord = NotificationConfig(
            telegramEnabled = true,
            telegramBotToken = "bad:token",
            telegramChatId = "123",
            discordEnabled = true,
            discordWebhookUrl = "https://not-discord.example/hook"
        )
        val res1 = RemoteNotifier.send(cfgBadDiscord, "Hi", "Msg", transport = failingTransport)
        assertTrue(res1.telegramAttempted && !res1.telegramOk)
        assertEquals("HTTP 401", res1.telegramError)
        assertTrue(res1.discordAttempted && !res1.discordOk)
        assertTrue(res1.discordError.contains("tidak valid"))
        assertEquals(1, calls) // Discord rejected before network call

        val cfgNetworkErr = NotificationConfig(
            telegramEnabled = true,
            telegramBotToken = "bad:token",
            telegramChatId = "123"
        )
        val res2 = RemoteNotifier.send(cfgNetworkErr, "Hi", "Msg", transport = failingTransport)
        assertFalse(res2.anySucceeded)
        assertTrue(res2.telegramError.contains("Simulated socket timeout"))
    }

    @Test
    fun notifyActionJsonRoundTripAndZeroCredentialLeakInMacroSerialization() {
        val action = ActionKind.NOTIFY.create(1080, 2400) as NotifyAction
        assertTrue(action.summary().contains("Notif"))

        val macro = Macro(
            name = "Safe Export Macro",
            actions = listOf(
                action.copy(
                    title = "Alert Boss",
                    message = "Loop {loop} Gold {var:gold}",
                    level = NotifyLevel.CRITICAL,
                    sendRemote = true,
                    includeScreenshot = true,
                    triggerPhoneAlarm = true
                )
            )
        )
        val encodedMacro = testJson.encodeToString(macro)
        val decodedMacro = testJson.decodeFromString<Macro>(encodedMacro)
        val decodedAction = decodedMacro.actions.first() as NotifyAction
        assertEquals("Alert Boss", decodedAction.title)
        assertEquals("Loop {loop} Gold {var:gold}", decodedAction.message)
        assertEquals(NotifyLevel.CRITICAL, decodedAction.level)
        assertTrue(decodedAction.sendRemote)
        assertTrue(decodedAction.includeScreenshot)
        assertTrue(decodedAction.triggerPhoneAlarm)

        // Credentials MUST NEVER exist anywhere inside Macro serialization (zero-leak guarantee for .zip/.wjm)
        assertFalse(encodedMacro.contains("telegramBotToken"))
        assertFalse(encodedMacro.contains("telegramChatId"))
        assertFalse(encodedMacro.contains("discordWebhookUrl"))

        // Local NotificationConfig still round-trips cleanly in its own private store file
        val cfg = NotificationConfig(
            telegramEnabled = true,
            telegramBotToken = "99999:SECRET",
            telegramChatId = "55555",
            discordEnabled = true,
            discordWebhookUrl = "https://discord.com/api/webhooks/1/2",
            periodicReportMinutes = 30
        )
        val decodedCfg = testJson.decodeFromString<NotificationConfig>(testJson.encodeToString(cfg))
        assertEquals(cfg, decodedCfg)
    }
}
