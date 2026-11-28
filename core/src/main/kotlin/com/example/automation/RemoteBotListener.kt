package com.example.automation

import com.example.data.NotificationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Live status snapshot exposed to the UI & overlay for the 2-way Telegram/Discord Bot Listener. */
data class RemoteBotListenerStatus(
    val running: Boolean = false,
    val telegramListening: Boolean = false,
    val discordListening: Boolean = false,
    val commandsHandled: Int = 0,
    val lastCommandSummary: String = "",
    val lastError: String = ""
)

/**
 * Pure-Kotlin coroutine 2-way listener controller shared by Wjust macro (`:app`),
 * Wjust Player (`:player`), and the Play Store edition.
 *
 * - Telegram: HTTP long-polling (`getUpdates`, 20s timeout) with startup backlog skip (no replay).
 * - Discord: Lightweight REST polling (`GET /channels/{id}/messages?after=...`) with startup cursor bootstrap.
 * - Zero third-party libraries and zero backend server required.
 */
class RemoteBotListener(
    private val transport: RemoteBotHttpTransport = RemoteBotApiClient.defaultTransport,
    private val onLog: (String) -> Unit = {}
) {
    private val _status = MutableStateFlow(RemoteBotListenerStatus())
    val status: StateFlow<RemoteBotListenerStatus> = _status.asStateFlow()
    private val screenshotMutex = Mutex()

    @Volatile
    private var activeConfig: NotificationConfig = NotificationConfig()

    private var telegramKey: String = ""
    private var discordKey: String = ""
    private var telegramJob: Job? = null
    private var discordJob: Job? = null

    /**
     * Synchronizes running polling jobs with the latest [config] and [allowed] flag.
     * Safe to call repeatedly whenever settings change or a foreground service starts.
     */
    @Synchronized
    fun sync(
        scope: CoroutineScope,
        config: NotificationConfig,
        allowed: Boolean = true,
        hostProvider: () -> RemoteControlHost
    ) {
        activeConfig = config
        val rb = config.remoteBot
        val wantTg = allowed && rb.hasTelegramListenerReady(config)
        val wantDc = allowed && rb.hasDiscordListenerReady()

        val nextTgKey = if (wantTg) "${config.telegramBotToken.trim()}|${config.telegramChatId.trim()}|${rb.telegramAllowedIds.trim()}" else ""
        val nextDcKey = if (wantDc) "${rb.discordBotToken.trim()}|${rb.discordChannelId.trim()}|${rb.discordAllowedUserIds.trim()}|${rb.discordPollIntervalSec}" else ""

        if (!wantTg || nextTgKey != telegramKey) {
            telegramJob?.cancel()
            telegramJob = null
            telegramKey = ""
        }
        if (!wantDc || nextDcKey != discordKey) {
            discordJob?.cancel()
            discordJob = null
            discordKey = ""
        }

        if (wantTg && telegramJob == null) {
            telegramKey = nextTgKey
            telegramJob = scope.launch(Dispatchers.IO) {
                runTelegramLoop(scope, hostProvider)
            }
            onLog("🤖 Listener Bot Telegram aktif (2-arah).")
        }
        if (wantDc && discordJob == null) {
            discordKey = nextDcKey
            discordJob = scope.launch(Dispatchers.IO) {
                runDiscordLoop(scope, hostProvider)
            }
            onLog("🤖 Listener Bot Discord aktif (2-arah).")
        }

        _status.value = _status.value.copy(
            running = wantTg || wantDc,
            telegramListening = wantTg,
            discordListening = wantDc,
            lastError = if (!wantTg && !wantDc) "" else _status.value.lastError
        )
    }

    @Synchronized
    fun stop() {
        telegramJob?.cancel()
        telegramJob = null
        telegramKey = ""
        discordJob?.cancel()
        discordJob = null
        discordKey = ""
        _status.value = _status.value.copy(
            running = false,
            telegramListening = false,
            discordListening = false
        )
    }

    private suspend fun runTelegramLoop(scope: CoroutineScope, hostProvider: () -> RemoteControlHost) {
        var nextOffset = 0L
        var bootstrapped = false
        var backoffMs = 3_000L

        while (currentCoroutineContext().isActive) {
            val cfg = activeConfig
            val rb = cfg.remoteBot
            if (!rb.hasTelegramListenerReady(cfg)) break
            val token = cfg.telegramBotToken.trim()

            try {
                if (!bootstrapped) {
                    // Drain backlog cursor so commands sent before the app started are NEVER replayed!
                    val (ok, oldEvents) = RemoteBotApiClient.pollTelegramUpdates(
                        token = token,
                        offset = -1L,
                        timeoutSec = 0,
                        transport = transport
                    )
                    if (ok) {
                        val maxId = oldEvents.maxOfOrNull { it.updateId }
                        if (maxId != null) nextOffset = maxId + 1L
                        bootstrapped = true
                        backoffMs = 3_000L
                    } else {
                        _status.value = _status.value.copy(lastError = "Telegram polling gagal terhubung (periksa token)")
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(20_000L)
                        continue
                    }
                }

                val (ok, events) = RemoteBotApiClient.pollTelegramUpdates(
                    token = token,
                    offset = nextOffset,
                    timeoutSec = 20,
                    transport = transport
                )
                if (!ok) {
                    _status.value = _status.value.copy(lastError = "Koneksi Telegram terputus, mencoba lagi...")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(20_000L)
                    continue
                }
                backoffMs = 3_000L
                if (_status.value.lastError.startsWith("Telegram") || _status.value.lastError.startsWith("Koneksi Telegram")) {
                    _status.value = _status.value.copy(lastError = "")
                }

                for (ev in events) {
                    if (ev.updateId >= nextOffset) nextOffset = ev.updateId + 1L
                    if (!RemoteBotAuth.isTelegramAuthorized(
                            chatId = ev.chatId,
                            fromUserId = ev.fromUserId,
                            primaryChatId = cfg.telegramChatId,
                            allowedIdsRaw = rb.telegramAllowedIds
                        )
                    ) {
                        continue
                    }

                    val cmd = when {
                        !ev.callbackData.isNullOrBlank() -> RemoteBotCommandParser.parseCallbackData(ev.callbackData)
                        !ev.text.isNullOrBlank() -> RemoteBotCommandParser.parseText(ev.text, requirePrefix = true)
                        else -> null
                    } ?: continue

                    val summary = ev.text ?: ev.callbackData.orEmpty()
                    if (cmd is RemoteBotCommand.Screenshot) {
                        // Run screenshot capture + multipart upload in an isolated supervisor coroutine so /ss
                        // NEVER blocks polling, other bot commands, or the running macro engine, and serialize
                        // rapid /ss taps so RAM never piles up.
                        scope.launch(Dispatchers.IO) {
                            screenshotMutex.withLock {
                                runCatching {
                                    val host = hostProvider()
                                    val reply = RemoteBotCommandExecutor.execute(cmd, host) ?: return@runCatching
                                    RemoteBotApiClient.sendTelegramReply(
                                        token = token,
                                        chatId = ev.chatId,
                                        reply = reply,
                                        callbackQueryId = ev.callbackQueryId,
                                        editMessageId = ev.callbackMessageId,
                                        transport = transport
                                    )
                                    _status.update {
                                        it.copy(
                                            commandsHandled = it.commandsHandled + 1,
                                            lastCommandSummary = "Telegram: $summary"
                                        )
                                    }
                                    onLog("🤖 Perintah Telegram diproses: $summary")
                                }.onFailure { err ->
                                    _status.update {
                                        it.copy(lastError = "Telegram /ss: ${err.message?.take(60) ?: "Error"}")
                                    }
                                }
                            }
                        }
                        continue
                    }

                    val host = hostProvider()
                    val reply = runCatching { RemoteBotCommandExecutor.execute(cmd, host) }.getOrNull() ?: continue

                    RemoteBotApiClient.sendTelegramReply(
                        token = token,
                        chatId = ev.chatId,
                        reply = reply,
                        callbackQueryId = ev.callbackQueryId,
                        editMessageId = ev.callbackMessageId,
                        transport = transport
                    )

                    _status.update {
                        it.copy(
                            commandsHandled = it.commandsHandled + 1,
                            lastCommandSummary = "Telegram: $summary"
                        )
                    }
                    onLog("🤖 Perintah Telegram diproses: $summary")
                }
            } catch (e: Throwable) {
                if (!currentCoroutineContext().isActive) break
                _status.value = _status.value.copy(lastError = "Telegram: ${e.message?.take(60) ?: "Error"}")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(20_000L)
            }
        }
    }

    private suspend fun runDiscordLoop(scope: CoroutineScope, hostProvider: () -> RemoteControlHost) {
        var lastMessageId: String? = null
        var bootstrapped = false
        var backoffMs = 4_000L

        while (currentCoroutineContext().isActive) {
            val cfg = activeConfig
            val rb = cfg.remoteBot
            if (!rb.hasDiscordListenerReady()) break
            val token = rb.discordBotToken.trim()
            val channelId = rb.discordChannelId.trim()
            val intervalMs = rb.discordPollIntervalSec.coerceIn(3, 30) * 1000L

            try {
                if (!bootstrapped) {
                    val (ok, latest) = RemoteBotApiClient.pollDiscordMessages(
                        botToken = token,
                        channelId = channelId,
                        afterMessageId = null,
                        limit = 1,
                        transport = transport
                    )
                    if (ok) {
                        lastMessageId = latest.lastOrNull()?.messageId
                        bootstrapped = true
                        backoffMs = 4_000L
                    } else {
                        _status.value = _status.value.copy(lastError = "Discord gagal terhubung (periksa Bot Token & Channel ID)")
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(25_000L)
                        continue
                    }
                    delay(intervalMs)
                    continue
                }

                val (ok, messages) = RemoteBotApiClient.pollDiscordMessages(
                    botToken = token,
                    channelId = channelId,
                    afterMessageId = lastMessageId,
                    limit = 10,
                    transport = transport
                )
                if (!ok) {
                    _status.value = _status.value.copy(lastError = "Koneksi Discord terputus, mencoba lagi...")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(25_000L)
                    continue
                }
                backoffMs = 4_000L
                if (_status.value.lastError.startsWith("Discord") || _status.value.lastError.startsWith("Koneksi Discord")) {
                    _status.value = _status.value.copy(lastError = "")
                }

                for (msg in messages) {
                    lastMessageId = msg.messageId
                    if (!RemoteBotAuth.isDiscordAuthorized(
                            authorId = msg.authorId,
                            isBot = msg.authorIsBot,
                            allowedUserIdsRaw = rb.discordAllowedUserIds
                        )
                    ) {
                        continue
                    }

                    val cmd = RemoteBotCommandParser.parseText(msg.content, requirePrefix = true) ?: continue
                    if (cmd is RemoteBotCommand.Screenshot) {
                        scope.launch(Dispatchers.IO) {
                            screenshotMutex.withLock {
                                runCatching {
                                    val host = hostProvider()
                                    val reply = RemoteBotCommandExecutor.execute(cmd, host) ?: return@runCatching
                                    RemoteBotApiClient.sendDiscordReply(
                                        botToken = token,
                                        channelId = channelId,
                                        reply = reply,
                                        transport = transport
                                    )
                                    _status.update {
                                        it.copy(
                                            commandsHandled = it.commandsHandled + 1,
                                            lastCommandSummary = "Discord: ${msg.content.take(40)}"
                                        )
                                    }
                                    onLog("🤖 Perintah Discord diproses: ${msg.content.take(40)}")
                                }.onFailure { err ->
                                    _status.update {
                                        it.copy(lastError = "Discord !ss: ${err.message?.take(60) ?: "Error"}")
                                    }
                                }
                            }
                        }
                        continue
                    }

                    val host = hostProvider()
                    val reply = runCatching { RemoteBotCommandExecutor.execute(cmd, host) }.getOrNull() ?: continue

                    RemoteBotApiClient.sendDiscordReply(
                        botToken = token,
                        channelId = channelId,
                        reply = reply,
                        transport = transport
                    )

                    _status.update {
                        it.copy(
                            commandsHandled = it.commandsHandled + 1,
                            lastCommandSummary = "Discord: ${msg.content.take(40)}"
                        )
                    }
                    onLog("🤖 Perintah Discord diproses: ${msg.content.take(40)}")
                }

                delay(intervalMs)
            } catch (e: Throwable) {
                if (!currentCoroutineContext().isActive) break
                _status.value = _status.value.copy(lastError = "Discord: ${e.message?.take(60) ?: "Error"}")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(25_000L)
            }
        }
    }
}
