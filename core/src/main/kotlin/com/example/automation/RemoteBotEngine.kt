package com.example.automation

import com.example.data.Action
import com.example.data.ActionRemoteControl
import com.example.data.DeviceHealthSnapshot
import com.example.data.LiveSessionStats
import com.example.data.Macro
import com.example.data.NotificationConfig
import com.example.data.RemoteActionMode
import com.example.data.RemoteBotConfig
import com.example.data.RemoteIdSanitizer
import com.example.data.enabledRemoteActions
import com.example.data.kind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal HTTP request supporting both GET and POST with custom headers (Telegram & Discord Bot APIs). */
data class BotHttpRequest(
    val method: String = "GET",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val contentType: String? = null,
    val body: ByteArray? = null,
    val readTimeoutMs: Int = 15_000
)

data class BotHttpResponse(
    val statusCode: Int,
    val body: String
)

fun interface RemoteBotHttpTransport {
    fun execute(request: BotHttpRequest): BotHttpResponse
}

/** Single inline button for Telegram interactive panel menus. */
data class BotInlineButton(
    val text: String,
    val callbackData: String
)

/** Result returned by [RemoteBotCommandExecutor] ready to send to Telegram or Discord. */
data class RemoteBotReply(
    val text: String,
    val inlineRows: List<List<BotInlineButton>> = emptyList(),
    val jpegBytes: ByteArray? = null,
    val callbackToast: String? = null,
    val editOriginalMessage: Boolean = false
)

enum class ToggleOp { ON, OFF, TOGGLE }

/** Parsed incoming command from Telegram text, Telegram inline button callback, or Discord channel message. */
sealed class RemoteBotCommand {
    abstract val targetDevice: String?

    data class Menu(override val targetDevice: String? = null, val fromCallback: Boolean = false) : RemoteBotCommand()
    data class ListMacros(override val targetDevice: String? = null) : RemoteBotCommand()
    data class MacroDetail(val macroId: String, override val targetDevice: String? = null, val fromCallback: Boolean = false) : RemoteBotCommand()
    data class MacroSwitch(val macroId: String, val op: ToggleOp, override val targetDevice: String? = null, val fromCallback: Boolean = false) : RemoteBotCommand()
    data class ActionSwitch(
        val macroId: String,
        val actionId: String,
        val op: ToggleOp,
        override val targetDevice: String? = null,
        val fromCallback: Boolean = false
    ) : RemoteBotCommand()
    data class Status(override val targetDevice: String? = null, val fromCallback: Boolean = false) : RemoteBotCommand()
    data class Screenshot(override val targetDevice: String? = null) : RemoteBotCommand()
    data class AfkSwitch(val op: ToggleOp, override val targetDevice: String? = null, val fromCallback: Boolean = false) : RemoteBotCommand()
    data class Unknown(val rawCommand: String, override val targetDevice: String? = null) : RemoteBotCommand()
}

data class RemoteHostOpResult(
    val ok: Boolean,
    val message: String
)

/**
 * Host abstraction implemented by Android `:app` (`AutomationCoordinator`) and `:player` (`PlayerCoordinator`)
 * so all command routing, Active/Passive semantics, and inline menu generation live in pure-Kotlin `:core`.
 */
interface RemoteControlHost {
    val appDisplayName: String
    val deviceTag: String
    fun listMacros(): List<Macro>
    fun runningMacroId(): String?
    fun liveStats(): LiveSessionStats?
    fun deviceHealth(): DeviceHealthSnapshot?
    fun isAfkBlackScreenActive(): Boolean
    fun startActiveMacro(macro: Macro): RemoteHostOpResult
    fun stopActiveMacro(macro: Macro): RemoteHostOpResult
    fun setPassiveMacroEnabled(macro: Macro, enabled: Boolean): RemoteHostOpResult
    fun setActionRemoteActive(macro: Macro, actionId: String, active: Boolean): RemoteHostOpResult
    fun setAfkBlackScreen(enabled: Boolean): RemoteHostOpResult
    fun captureScreenshotJpeg(): ByteArray?
}

/** Strict fail-closed sender authorization checks for Telegram & Discord remote commands. */
object RemoteBotAuth {
    fun isTelegramAuthorized(
        chatId: String,
        fromUserId: String,
        primaryChatId: String,
        allowedIdsRaw: String
    ): Boolean {
        val allowed = buildSet {
            val primary = primaryChatId.trim()
            if (primary.isNotEmpty()) add(primary)
            addAll(RemoteBotConfig.parseIdSet(allowedIdsRaw))
        }
        if (allowed.isEmpty()) return false
        val c = chatId.trim()
        val u = fromUserId.trim()
        return (c.isNotEmpty() && c in allowed) || (u.isNotEmpty() && u in allowed)
    }

    fun isDiscordAuthorized(
        authorId: String,
        isBot: Boolean,
        allowedUserIdsRaw: String
    ): Boolean {
        if (isBot) return false
        val allowed = RemoteBotConfig.parseIdSet(allowedUserIdsRaw)
        if (allowed.isEmpty()) return true
        return authorId.trim() in allowed
    }
}

/**
 * Parses both text commands (`/on farm1`, `!act farm1 sell off`, `@hp2 /status`, `/on hp2:farm1`)
 * and Telegram Inline Keyboard callback payloads (`m:home`, `m:d:farm1`, `m:tg:farm1`, `m:atg:farm1:sell`).
 */
object RemoteBotCommandParser {

    fun parseText(rawText: String, requirePrefix: Boolean = true): RemoteBotCommand? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return null

        val rawTokens = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (rawTokens.isEmpty()) return null

        var idx = 0
        var targetDevice: String? = null

        // Optional leading device selector: `@hp1 /on farm1` or `@hp1 !status`
        if (rawTokens[0].startsWith("@") && rawTokens.size >= 2) {
            targetDevice = RemoteIdSanitizer.sanitize(rawTokens[0].substring(1)).ifBlank { null }
            idx = 1
        }

        val cmdToken = rawTokens[idx]
        val hasPrefix = cmdToken.startsWith("/") || cmdToken.startsWith("!")
        if (requirePrefix && !hasPrefix) return null

        val cleanCmd = cmdToken
            .removePrefix("/")
            .removePrefix("!")
            .substringBefore('@')
            .lowercase()

        val args = rawTokens.drop(idx + 1).toMutableList()

        // Optional trailing `@device` token for commands like `/ss @hp2`
        if (targetDevice == null && args.isNotEmpty() && args.last().startsWith("@")) {
            targetDevice = RemoteIdSanitizer.sanitize(args.removeAt(args.lastIndex).substring(1)).ifBlank { null }
        }

        fun splitDeviceAndId(token: String): Pair<String?, String> {
            val colon = token.indexOf(':')
            return if (colon > 0 && colon < token.lastIndex) {
                val dev = RemoteIdSanitizer.sanitize(token.substring(0, colon)).ifBlank { null }
                val id = RemoteIdSanitizer.sanitize(token.substring(colon + 1))
                dev to id
            } else {
                null to RemoteIdSanitizer.sanitize(token)
            }
        }

        return when (cleanCmd) {
            "menu", "panel", "start", "help", "bantuan" ->
                RemoteBotCommand.Menu(targetDevice = targetDevice)

            "list", "macros", "makro", "id" ->
                RemoteBotCommand.ListMacros(targetDevice = targetDevice)

            "on", "aktif", "hidup" -> {
                val arg = args.firstOrNull() ?: return RemoteBotCommand.ListMacros(targetDevice)
                val (dev, id) = splitDeviceAndId(arg)
                if (id.isEmpty()) return RemoteBotCommand.ListMacros(targetDevice ?: dev)
                RemoteBotCommand.MacroSwitch(id, ToggleOp.ON, targetDevice ?: dev)
            }

            "off", "pasif", "mati", "stop" -> {
                val arg = args.firstOrNull() ?: return RemoteBotCommand.ListMacros(targetDevice)
                val (dev, id) = splitDeviceAndId(arg)
                if (id.isEmpty()) return RemoteBotCommand.ListMacros(targetDevice ?: dev)
                RemoteBotCommand.MacroSwitch(id, ToggleOp.OFF, targetDevice ?: dev)
            }

            "toggle", "switch" -> {
                val arg = args.firstOrNull() ?: return RemoteBotCommand.ListMacros(targetDevice)
                val (dev, id) = splitDeviceAndId(arg)
                if (id.isEmpty()) return RemoteBotCommand.ListMacros(targetDevice ?: dev)
                RemoteBotCommand.MacroSwitch(id, ToggleOp.TOGGLE, targetDevice ?: dev)
            }

            "detail", "info_macro" -> {
                val arg = args.firstOrNull() ?: return RemoteBotCommand.ListMacros(targetDevice)
                val (dev, id) = splitDeviceAndId(arg)
                if (id.isEmpty()) return RemoteBotCommand.ListMacros(targetDevice ?: dev)
                RemoteBotCommand.MacroDetail(id, targetDevice ?: dev)
            }

            "act", "aksi", "action" -> {
                if (args.isEmpty()) return RemoteBotCommand.ListMacros(targetDevice)
                val (dev, mId) = splitDeviceAndId(args[0])
                val resolvedDev = targetDevice ?: dev
                if (mId.isEmpty()) return RemoteBotCommand.ListMacros(resolvedDev)
                if (args.size == 1) {
                    return RemoteBotCommand.MacroDetail(mId, resolvedDev)
                }
                val aId = RemoteIdSanitizer.sanitize(args[1])
                val op = parseToggleOp(args.getOrNull(2))
                RemoteBotCommand.ActionSwitch(mId, aId, op, resolvedDev)
            }

            "trigger", "picu" -> {
                if (args.size < 2) return RemoteBotCommand.ListMacros(targetDevice)
                val (dev, mId) = splitDeviceAndId(args[0])
                val aId = RemoteIdSanitizer.sanitize(args[1])
                RemoteBotCommand.ActionSwitch(mId, aId, ToggleOp.ON, targetDevice ?: dev)
            }

            "status", "info", "stats" ->
                RemoteBotCommand.Status(targetDevice = targetDevice)

            "ss", "screenshot", "screen", "foto" ->
                RemoteBotCommand.Screenshot(targetDevice = targetDevice)

            "afk", "blackscreen", "layarhitam" ->
                RemoteBotCommand.AfkSwitch(parseToggleOp(args.firstOrNull()), targetDevice)

            else -> if (hasPrefix) RemoteBotCommand.Unknown(cleanCmd, targetDevice) else null
        }
    }

    fun parseCallbackData(rawData: String): RemoteBotCommand? {
        val parts = rawData.trim().split(':')
        if (parts.size < 2 || parts[0] != "m") return null
        return when (parts[1]) {
            "home" -> RemoteBotCommand.Menu(fromCallback = true)
            "st" -> RemoteBotCommand.Status(fromCallback = true)
            "ss" -> RemoteBotCommand.Screenshot()
            "afk" -> RemoteBotCommand.AfkSwitch(ToggleOp.TOGGLE, fromCallback = true)
            "d" -> parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let {
                RemoteBotCommand.MacroDetail(RemoteIdSanitizer.sanitize(it), fromCallback = true)
            }
            "on" -> parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let {
                RemoteBotCommand.MacroSwitch(RemoteIdSanitizer.sanitize(it), ToggleOp.ON, fromCallback = true)
            }
            "off" -> parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let {
                RemoteBotCommand.MacroSwitch(RemoteIdSanitizer.sanitize(it), ToggleOp.OFF, fromCallback = true)
            }
            "tg" -> parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let {
                RemoteBotCommand.MacroSwitch(RemoteIdSanitizer.sanitize(it), ToggleOp.TOGGLE, fromCallback = true)
            }
            "atg" -> {
                val mId = parts.getOrNull(2)?.let { RemoteIdSanitizer.sanitize(it) }.orEmpty()
                val aId = parts.getOrNull(3)?.let { RemoteIdSanitizer.sanitize(it) }.orEmpty()
                if (mId.isNotEmpty() && aId.isNotEmpty()) {
                    RemoteBotCommand.ActionSwitch(mId, aId, ToggleOp.TOGGLE, fromCallback = true)
                } else null
            }
            else -> null
        }
    }

    private fun parseToggleOp(raw: String?): ToggleOp = when (raw?.trim()?.lowercase()) {
        "on", "1", "true", "aktif", "hidup", "start", "ya" -> ToggleOp.ON
        "off", "0", "false", "pasif", "mati", "stop", "tidak" -> ToggleOp.OFF
        else -> ToggleOp.TOGGLE
    }
}

/**
 * Pure-Kotlin command router that executes parsed commands against [RemoteControlHost]
 * and generates formatted Indonesian status replies + Telegram inline panel keyboards.
 */
object RemoteBotCommandExecutor {

    fun execute(command: RemoteBotCommand, host: RemoteControlHost): RemoteBotReply? {
        val localTag = RemoteIdSanitizer.sanitize(host.deviceTag)
        val requestedTag = command.targetDevice?.let { RemoteIdSanitizer.sanitize(it) }.orEmpty()
        if (requestedTag.isNotEmpty() && requestedTag != localTag) {
            // Command targets another phone in a multi-device setup — ignore silently.
            return null
        }

        val tagPrefix = if (localTag.isNotEmpty()) "[$localTag] " else ""

        return when (command) {
            is RemoteBotCommand.Menu -> buildMainPanel(host, tagPrefix, editInPlace = command.fromCallback)
            is RemoteBotCommand.ListMacros -> buildListReply(host, tagPrefix)
            is RemoteBotCommand.MacroDetail -> buildMacroDetailPanel(host, tagPrefix, command.macroId, editInPlace = command.fromCallback)
            is RemoteBotCommand.MacroSwitch -> handleMacroSwitch(host, tagPrefix, command)
            is RemoteBotCommand.ActionSwitch -> handleActionSwitch(host, tagPrefix, command)
            is RemoteBotCommand.Status -> buildStatusReply(host, tagPrefix, editInPlace = command.fromCallback)
            is RemoteBotCommand.Screenshot -> handleScreenshot(host, tagPrefix)
            is RemoteBotCommand.AfkSwitch -> handleAfkSwitch(host, tagPrefix, command)
            is RemoteBotCommand.Unknown -> RemoteBotReply(
                text = "${tagPrefix}❓ Perintah tidak dikenal: `${command.rawCommand}`.\n" +
                    "Gunakan `/menu`, `/list`, `/on <id>`, `/off <id>`, `/act <id_makro> <id_aksi> on|off`, `/status`, `/ss`, atau `/afk on|off`."
            )
        }
    }

    private fun controllableMacros(host: RemoteControlHost): List<Macro> =
        host.listMacros().filter { it.remoteControlEnabled && RemoteIdSanitizer.sanitize(it.remoteId).isNotEmpty() }

    private fun findControllableMacro(host: RemoteControlHost, remoteId: String): Macro? {
        val target = RemoteIdSanitizer.sanitize(remoteId)
        if (target.isEmpty()) return null
        return controllableMacros(host).firstOrNull { RemoteIdSanitizer.sanitize(it.remoteId) == target }
    }

    private fun isMacroOn(macro: Macro, runningId: String?): Boolean =
        if (macro.isPassive) macro.passiveEnabled else (runningId != null && runningId == macro.id)

    private fun buildMainPanel(host: RemoteControlHost, tagPrefix: String, editInPlace: Boolean): RemoteBotReply {
        val macros = controllableMacros(host)
        val runningId = host.runningMacroId()
        val afkOn = host.isAfkBlackScreenActive()
        val health = host.deviceHealth()

        val sb = StringBuilder()
        sb.append("🤖 ").append(tagPrefix).append(host.appDisplayName).append(" — Panel Kontrol Bot\n")
        if (health != null) {
            sb.append("🔋 ${health.batteryPercent}% • 🌡️ ${"%.1f".format(health.tempCelsius)}°C")
            sb.append(" • 🌙 AFK: ${if (afkOn) "ON" else "OFF"}\n")
        }
        sb.append("\n")

        if (macros.isEmpty()) {
            sb.append("Belum ada makro dengan ID Kontrol Bot aktif.\n")
            sb.append("Buka Setelan Makro di HP → aktifkan 'Kontrol Bot' & isi ID Makro.")
        } else {
            sb.append("📋 Makro Terkontrol (${macros.size}):\n")
            macros.forEach { m ->
                val rid = RemoteIdSanitizer.sanitize(m.remoteId)
                val on = isMacroOn(m, runningId)
                val typeTag = if (m.isPassive) "Pasif" else "Aktif"
                val statusIcon = if (on) "🟢 ON" else "⚪ OFF"
                val actCount = m.enabledRemoteActions().size
                val actInfo = if (actCount > 0) " • $actCount aksi" else ""
                sb.append("• $statusIcon  `$rid` — ${m.name} [$typeTag]$actInfo\n")
            }
            sb.append("\nPerintah cepat:\n")
            sb.append("• `/on <id>` / `/off <id>` — ON/OFF makro Aktif & Pasif\n")
            sb.append("• `/act <id_makro> <id_aksi> on|off` — ON/OFF aksi live\n")
            sb.append("• `/status` • `/ss` • `/afk on|off`")
        }

        val rows = ArrayList<List<BotInlineButton>>()
        macros.take(16).forEach { m ->
            val rid = RemoteIdSanitizer.sanitize(m.remoteId)
            val on = isMacroOn(m, runningId)
            val icon = if (on) "🟢" else "⚪"
            val typeShort = if (m.isPassive) "Pasif" else "Aktif"
            val actCount = m.enabledRemoteActions().size
            val toggleBtn = BotInlineButton(
                text = "$icon ${m.name.take(18)} ($typeShort: ${if (on) "ON" else "OFF"})",
                callbackData = "m:tg:$rid"
            )
            if (actCount > 0) {
                rows += listOf(
                    toggleBtn,
                    BotInlineButton(text = "⚙️ Aksi ($actCount)", callbackData = "m:d:$rid")
                )
            } else {
                rows += listOf(
                    toggleBtn,
                    BotInlineButton(text = "🔍 Detail", callbackData = "m:d:$rid")
                )
            }
        }
        rows += listOf(
            BotInlineButton(text = "📊 Status", callbackData = "m:st"),
            BotInlineButton(text = "📸 Screenshot", callbackData = "m:ss"),
            BotInlineButton(text = if (afkOn) "🌙 AFK: ON" else "🌙 AFK: OFF", callbackData = "m:afk")
        )
        rows += listOf(BotInlineButton(text = "🔄 Segarkan Panel", callbackData = "m:home"))

        return RemoteBotReply(
            text = sb.toString().trim(),
            inlineRows = rows,
            editOriginalMessage = editInPlace
        )
    }

    private fun buildListReply(host: RemoteControlHost, tagPrefix: String): RemoteBotReply {
        val macros = controllableMacros(host)
        val runningId = host.runningMacroId()
        if (macros.isEmpty()) {
            return RemoteBotReply(
                text = "${tagPrefix}📋 Belum ada makro yang mengaktifkan ID Kontrol Bot.\nAktifkan di Pengaturan Makro → Kontrol Bot Telegram/Discord."
            )
        }
        val sb = StringBuilder()
        sb.append("${tagPrefix}📋 Daftar ID Makro (${macros.size}):\n\n")
        macros.forEach { m ->
            val rid = RemoteIdSanitizer.sanitize(m.remoteId)
            val on = isMacroOn(m, runningId)
            val kindLabel = if (m.isPassive) "PASIF" else "AKTIF"
            val stateLabel = when {
                m.isPassive && on -> "🟢 ON (Dapat Ditarik)"
                m.isPassive -> "⚪ OFF (Dilewati)"
                on -> "🟢 ON (Berjalan)"
                else -> "⚪ OFF (Berhenti)"
            }
            sb.append("• ID: `$rid` | ${m.name} [$kindLabel]\n  Status: $stateLabel\n")
            val acts = m.enabledRemoteActions()
            if (acts.isNotEmpty()) {
                val actSummary = acts.joinToString(", ") { (_, c) ->
                    val modeBadge = if (c.mode == RemoteActionMode.ONE_SHOT) "1×" else if (c.active) "ON" else "OFF"
                    "`${c.normalizedId()}`($modeBadge)"
                }
                sb.append("  Aksi: $actSummary\n")
            }
        }
        sb.append("\nContoh: `/on ${RemoteIdSanitizer.sanitize(macros.first().remoteId)}` atau `/act ${RemoteIdSanitizer.sanitize(macros.first().remoteId)}`")
        return RemoteBotReply(text = sb.toString().trim())
    }

    private fun buildMacroDetailPanel(
        host: RemoteControlHost,
        tagPrefix: String,
        macroRemoteId: String,
        editInPlace: Boolean,
        bannerLine: String? = null,
        callbackToast: String? = null
    ): RemoteBotReply {
        val macro = findControllableMacro(host, macroRemoteId)
            ?: return RemoteBotReply(
                text = "${tagPrefix}⚠️ Makro dengan ID `$macroRemoteId` tidak ditemukan atau kontrol bot-nya belum aktif.\nKirim `/list` untuk melihat ID yang tersedia.",
                callbackToast = "ID makro tidak ditemukan"
            )
        val rid = RemoteIdSanitizer.sanitize(macro.remoteId)
        val runningId = host.runningMacroId()
        val on = isMacroOn(macro, runningId)
        val acts = macro.enabledRemoteActions()

        val sb = StringBuilder()
        if (!bannerLine.isNullOrBlank()) {
            sb.append(bannerLine).append("\n\n")
        }
        val kindLabel = if (macro.isPassive) "Makro Pasif" else "Makro Aktif"
        val stateLabel = when {
            macro.isPassive && on -> "🟢 ON (Dapat Ditarik)"
            macro.isPassive -> "⚪ OFF (Dilewati)"
            on -> "🟢 ON (Sedang Berjalan)"
            else -> "⚪ OFF (Berhenti)"
        }
        sb.append("🤖 ${tagPrefix}${macro.name}\n")
        sb.append("ID: `$rid` • Jenis: $kindLabel\n")
        sb.append("Status: $stateLabel\n")

        if (acts.isEmpty()) {
            sb.append("\nBelum ada aksi di dalam makro ini yang di-setting untuk kontrol bot.")
        } else {
            sb.append("\n⚙️ Aksi Terkontrol (${acts.size}):\n")
            acts.forEach { (act, ctrl) ->
                val aId = ctrl.normalizedId()
                val label = ctrl.displayName(act.kind.label)
                val badge = when {
                    ctrl.mode == RemoteActionMode.ONE_SHOT && ctrl.active -> "⚡ SIAP 1×"
                    ctrl.mode == RemoteActionMode.ONE_SHOT -> "⚪ PICU 1× (OFF)"
                    ctrl.active -> "🟢 ON"
                    else -> "⚪ OFF"
                }
                sb.append("• $badge  `$aId` — $label\n")
            }
            sb.append("\nPerintah: `/act $rid <id_aksi> on|off`")
        }

        val rows = ArrayList<List<BotInlineButton>>()
        rows += listOf(
            BotInlineButton(
                text = if (on) "🔴 Matikan (OFF)" else "🟢 Nyalakan (ON)",
                callbackData = if (on) "m:off:$rid" else "m:on:$rid"
            ),
            BotInlineButton(text = "📸 SS", callbackData = "m:ss")
        )
        acts.take(20).forEach { (act, ctrl) ->
            val aId = ctrl.normalizedId()
            val label = ctrl.displayName(act.kind.label).take(22)
            val btnText = when {
                ctrl.mode == RemoteActionMode.ONE_SHOT && ctrl.active -> "⚡ $label (Siap 1×)"
                ctrl.mode == RemoteActionMode.ONE_SHOT -> "⚡ Picu 1×: $label"
                ctrl.active -> "🟢 $label: ON"
                else -> "⚪ $label: OFF"
            }
            rows += listOf(BotInlineButton(text = btnText, callbackData = "m:atg:$rid:$aId"))
        }
        rows += listOf(
            BotInlineButton(text = "⬅️ Kembali ke Menu", callbackData = "m:home"),
            BotInlineButton(text = "🔄 Segarkan", callbackData = "m:d:$rid")
        )

        return RemoteBotReply(
            text = sb.toString().trim(),
            inlineRows = rows,
            callbackToast = callbackToast,
            editOriginalMessage = editInPlace
        )
    }

    private fun handleMacroSwitch(
        host: RemoteControlHost,
        tagPrefix: String,
        cmd: RemoteBotCommand.MacroSwitch
    ): RemoteBotReply {
        val macro = findControllableMacro(host, cmd.macroId)
            ?: return RemoteBotReply(
                text = "${tagPrefix}⚠️ ID Makro `${cmd.macroId}` tidak ditemukan atau belum diaktifkan untuk kontrol bot.\nKirim `/list` untuk melihat daftar ID.",
                callbackToast = "ID makro tidak ditemukan"
            )

        val currentOn = isMacroOn(macro, host.runningMacroId())
        val targetOn = when (cmd.op) {
            ToggleOp.ON -> true
            ToggleOp.OFF -> false
            ToggleOp.TOGGLE -> !currentOn
        }

        val result = if (macro.isPassive) {
            host.setPassiveMacroEnabled(macro, targetOn)
        } else {
            if (targetOn) host.startActiveMacro(macro) else host.stopActiveMacro(macro)
        }

        val icon = if (result.ok) "✅" else "❌"
        val banner = "$icon ${result.message}"
        return when {
            cmd.fromCallback && cmd.op == ToggleOp.TOGGLE ->
                buildMainPanel(host, tagPrefix, editInPlace = true).copy(
                    callbackToast = result.message.take(180)
                )
            cmd.fromCallback ->
                buildMacroDetailPanel(
                    host = host,
                    tagPrefix = tagPrefix,
                    macroRemoteId = macro.remoteId,
                    editInPlace = true,
                    callbackToast = result.message.take(180)
                )
            else ->
                buildMacroDetailPanel(
                    host = host,
                    tagPrefix = tagPrefix,
                    macroRemoteId = macro.remoteId,
                    editInPlace = false,
                    bannerLine = banner
                )
        }
    }

    private fun handleActionSwitch(
        host: RemoteControlHost,
        tagPrefix: String,
        cmd: RemoteBotCommand.ActionSwitch
    ): RemoteBotReply {
        val macro = findControllableMacro(host, cmd.macroId)
            ?: return RemoteBotReply(
                text = "${tagPrefix}⚠️ Makro `${cmd.macroId}` tidak ditemukan atau kontrol bot belum aktif.",
                callbackToast = "Makro tidak ditemukan"
            )

        val targetActionSlug = RemoteIdSanitizer.sanitize(cmd.actionId)
        val matched = macro.enabledRemoteActions().firstOrNull { (_, ctrl) -> ctrl.normalizedId() == targetActionSlug }
            ?: return RemoteBotReply(
                text = "${tagPrefix}⚠️ Aksi `${cmd.actionId}` tidak ditemukan di makro `${macro.remoteId}`.\nKirim `/act ${macro.remoteId}` untuk melihat daftar aksi.",
                callbackToast = "Aksi tidak ditemukan"
            )

        val (act, ctrl) = matched
        val targetActive = when (cmd.op) {
            ToggleOp.ON -> true
            ToggleOp.OFF -> false
            ToggleOp.TOGGLE -> if (ctrl.mode == RemoteActionMode.ONE_SHOT) !ctrl.active else !ctrl.active
        }

        val res = host.setActionRemoteActive(macro, act.id, targetActive)
        val icon = if (res.ok) "✅" else "❌"
        val banner = "$icon ${res.message}"

        return buildMacroDetailPanel(
            host = host,
            tagPrefix = tagPrefix,
            macroRemoteId = macro.remoteId,
            editInPlace = cmd.fromCallback,
            bannerLine = if (cmd.fromCallback) null else banner,
            callbackToast = res.message.take(180)
        )
    }

    private fun buildStatusReply(host: RemoteControlHost, tagPrefix: String, editInPlace: Boolean): RemoteBotReply {
        val runningId = host.runningMacroId()
        val macros = host.listMacros()
        val runningMacro = runningId?.let { id -> macros.firstOrNull { it.id == id } }
        val stats = host.liveStats()
        val health = host.deviceHealth()
        val afkOn = host.isAfkBlackScreenActive()

        val sb = StringBuilder()
        sb.append("📊 ${tagPrefix}Status ${host.appDisplayName}\n\n")
        if (runningMacro != null) {
            val rid = RemoteIdSanitizer.sanitize(runningMacro.remoteId)
            val idInfo = if (runningMacro.remoteControlEnabled && rid.isNotEmpty()) " (`$rid`)" else ""
            sb.append("▶️ Berjalan: ${runningMacro.name}$idInfo\n")
            if (stats != null) {
                val elapsedSec = ((System.currentTimeMillis() - stats.startedAtMs).coerceAtLeast(0L)) / 1000L
                val min = elapsedSec / 60L
                val sec = elapsedSec % 60L
                sb.append("🔁 Siklus: ${stats.loopsCompleted} • 👆 Aksi: ${stats.actionsExecuted} • ⏱️ ${min}m ${sec}d\n")
                if (stats.accountSwitches > 0) sb.append("👥 Ganti Akun: ${stats.accountSwitches}×\n")
            }
        } else {
            sb.append("⏹️ Status: Tidak ada makro aktif yang berjalan.\n")
        }

        if (health != null) {
            val chg = if (health.isCharging) " (Mengisi)" else ""
            sb.append("🔋 Baterai: ${health.batteryPercent}%$chg • 🌡️ Suhu: ${"%.1f".format(health.tempCelsius)}°C\n")
        }
        sb.append("🌙 Mode Layar Hitam AFK: ${if (afkOn) "ON" else "OFF"}\n")

        val passiveCount = controllableMacros(host).count { it.isPassive }
        val passiveOn = controllableMacros(host).count { it.isPassive && it.passiveEnabled }
        if (passiveCount > 0) {
            sb.append("🧩 Makro Pasif Terkontrol: $passiveOn/$passiveCount ON\n")
        }

        val rows = listOf(
            listOf(
                BotInlineButton(text = "📸 Screenshot", callbackData = "m:ss"),
                BotInlineButton(text = if (afkOn) "🌙 Matikan AFK" else "🌙 Nyalakan AFK", callbackData = "m:afk")
            ),
            listOf(
                BotInlineButton(text = "⬅️ Panel Utama", callbackData = "m:home"),
                BotInlineButton(text = "🔄 Segarkan", callbackData = "m:st")
            )
        )
        return RemoteBotReply(
            text = sb.toString().trim(),
            inlineRows = rows,
            editOriginalMessage = editInPlace
        )
    }

    private fun handleScreenshot(host: RemoteControlHost, tagPrefix: String): RemoteBotReply {
        val jpeg = host.captureScreenshotJpeg()
        return if (jpeg != null && jpeg.isNotEmpty()) {
            val runningId = host.runningMacroId()
            val runningName = runningId?.let { id -> host.listMacros().firstOrNull { it.id == id }?.name } ?: "Standby"
            RemoteBotReply(
                text = "📸 ${tagPrefix}Screenshot Layar ($runningName)",
                jpegBytes = jpeg,
                callbackToast = "Mengirim screenshot..."
            )
        } else {
            RemoteBotReply(
                text = "${tagPrefix}⚠️ Gagal mengambil screenshot. Pastikan izin Tangkap Layar (MediaProjection) sudah aktif di HP.",
                callbackToast = "Izin Tangkap Layar belum aktif"
            )
        }
    }

    private fun handleAfkSwitch(
        host: RemoteControlHost,
        tagPrefix: String,
        cmd: RemoteBotCommand.AfkSwitch
    ): RemoteBotReply {
        val targetOn = when (cmd.op) {
            ToggleOp.ON -> true
            ToggleOp.OFF -> false
            ToggleOp.TOGGLE -> !host.isAfkBlackScreenActive()
        }
        val res = host.setAfkBlackScreen(targetOn)
        val icon = if (res.ok) "🌙" else "⚠️"
        return if (cmd.fromCallback) {
            buildMainPanel(host, tagPrefix, editInPlace = true).copy(
                callbackToast = res.message.take(180)
            )
        } else {
            RemoteBotReply(text = "$icon ${tagPrefix}${res.message}")
        }
    }
}

/** Incoming normalized event polled from Telegram Bot API (`getUpdates`). */
data class TelegramIncomingEvent(
    val updateId: Long,
    val chatId: String,
    val fromUserId: String,
    val text: String? = null,
    val callbackQueryId: String? = null,
    val callbackData: String? = null,
    val callbackMessageId: Long? = null
)

/** Incoming normalized message polled from Discord REST API (`GET /channels/{id}/messages`). */
data class DiscordIncomingMessage(
    val messageId: String,
    val channelId: String,
    val authorId: String,
    val authorIsBot: Boolean,
    val content: String
)

/**
 * Pure-Kotlin HTTP client for Telegram Bot API (long-polling + inline buttons + photo upload)
 * and Discord Bot REST API (channel message polling + text/photo replies).
 */
object RemoteBotApiClient {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private val jsonParser = Json { ignoreUnknownKeys = true }

    val defaultTransport = RemoteBotHttpTransport { req ->
        val conn = (URL(req.url).openConnection() as HttpURLConnection).apply {
            requestMethod = req.method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = req.readTimeoutMs
            useCaches = false
            setRequestProperty("User-Agent", "WjustMacroBot/2.21")
            req.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (req.contentType != null) {
                setRequestProperty("Content-Type", req.contentType)
            }
            if (req.body != null) {
                doOutput = true
            }
        }
        try {
            if (req.body != null) {
                conn.outputStream.use { os ->
                    os.write(req.body)
                    os.flush()
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            BotHttpResponse(code, body)
        } finally {
            conn.disconnect()
        }
    }

    // ---- TELEGRAM BOT API ----

    fun pollTelegramUpdates(
        token: String,
        offset: Long,
        timeoutSec: Int = 20,
        transport: RemoteBotHttpTransport = defaultTransport
    ): Pair<Boolean, List<TelegramIncomingEvent>> {
        val safeTimeout = timeoutSec.coerceIn(0, 45)
        val allowed = URLEncoder.encode("""["message","callback_query"]""", "UTF-8")
        val url = "https://api.telegram.org/bot${token.trim()}/getUpdates?offset=$offset&timeout=$safeTimeout&limit=25&allowed_updates=$allowed"
        val resp = transport.execute(
            BotHttpRequest(
                method = "GET",
                url = url,
                readTimeoutMs = (safeTimeout + 12) * 1000
            )
        )
        if (resp.statusCode !in 200..299) return false to emptyList()
        return parseTelegramUpdatesJson(resp.body)
    }

    internal fun parseTelegramUpdatesJson(body: String): Pair<Boolean, List<TelegramIncomingEvent>> {
        val root = runCatching { jsonParser.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return false to emptyList()
        val ok = root["ok"]?.jsonPrimitive?.booleanOrNull == true
        if (!ok) return false to emptyList()
        val arr = root["result"] as? JsonArray ?: return true to emptyList()
        val events = ArrayList<TelegramIncomingEvent>(arr.size)
        for (el in arr) {
            val obj = el as? JsonObject ?: continue
            val updateId = obj["update_id"]?.jsonPrimitive?.longOrNull ?: continue
            val msg = obj["message"] as? JsonObject
            if (msg != null) {
                val chatId = (msg["chat"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
                val fromId = (msg["from"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
                val text = msg["text"]?.jsonPrimitive?.contentOrNull
                if (chatId.isNotEmpty() && !text.isNullOrBlank()) {
                    events += TelegramIncomingEvent(
                        updateId = updateId,
                        chatId = chatId,
                        fromUserId = fromId,
                        text = text
                    )
                }
                continue
            }
            val cb = obj["callback_query"] as? JsonObject
            if (cb != null) {
                val cbId = cb["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val cbData = cb["data"]?.jsonPrimitive?.contentOrNull
                val fromId = (cb["from"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
                val cbMsg = cb["message"] as? JsonObject
                val chatId = (cbMsg?.get("chat") as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
                val msgId = cbMsg?.get("message_id")?.jsonPrimitive?.longOrNull
                if (cbId.isNotEmpty() && chatId.isNotEmpty() && !cbData.isNullOrBlank()) {
                    events += TelegramIncomingEvent(
                        updateId = updateId,
                        chatId = chatId,
                        fromUserId = fromId,
                        callbackQueryId = cbId,
                        callbackData = cbData,
                        callbackMessageId = msgId
                    )
                }
            }
        }
        return true to events
    }

    fun sendTelegramReply(
        token: String,
        chatId: String,
        reply: RemoteBotReply,
        callbackQueryId: String? = null,
        editMessageId: Long? = null,
        transport: RemoteBotHttpTransport = defaultTransport
    ): Boolean {
        val cleanToken = token.trim()
        if (callbackQueryId != null) {
            val toastPart = reply.callbackToast?.take(190)?.let { ""","text":${jsonQuote(it)}""" }.orEmpty()
            val payload = """{"callback_query_id":${jsonQuote(callbackQueryId)}$toastPart}"""
            runCatching {
                transport.execute(
                    BotHttpRequest(
                        method = "POST",
                        url = "https://api.telegram.org/bot$cleanToken/answerCallbackQuery",
                        contentType = "application/json; charset=UTF-8",
                        body = payload.toByteArray(Charsets.UTF_8)
                    )
                )
            }
        }

        if (reply.jpegBytes != null && reply.jpegBytes.isNotEmpty()) {
            val req = buildTelegramPhotoRequest(cleanToken, chatId, reply.text, reply.jpegBytes)
            val resp = transport.execute(req)
            return resp.statusCode in 200..299
        }

        val markupJson = buildTelegramInlineKeyboardJson(reply.inlineRows)
        val markupField = if (markupJson != null) ""","reply_markup":$markupJson""" else ""

        if (reply.editOriginalMessage && editMessageId != null) {
            val editPayload = """{"chat_id":${jsonQuote(chatId)},"message_id":$editMessageId,"text":${jsonQuote(reply.text.take(3950))}$markupField}"""
            val editResp = transport.execute(
                BotHttpRequest(
                    method = "POST",
                    url = "https://api.telegram.org/bot$cleanToken/editMessageText",
                    contentType = "application/json; charset=UTF-8",
                    body = editPayload.toByteArray(Charsets.UTF_8)
                )
            )
            if (editResp.statusCode in 200..299) return true
            // "message is not modified" is a harmless no-op when user clicks Refresh with identical state.
            if (editResp.body.contains("message is not modified", ignoreCase = true)) return true
        }

        val sendPayload = """{"chat_id":${jsonQuote(chatId)},"text":${jsonQuote(reply.text.take(3950))}$markupField}"""
        val resp = transport.execute(
            BotHttpRequest(
                method = "POST",
                url = "https://api.telegram.org/bot$cleanToken/sendMessage",
                contentType = "application/json; charset=UTF-8",
                body = sendPayload.toByteArray(Charsets.UTF_8)
            )
        )
        return resp.statusCode in 200..299
    }

    private fun buildTelegramInlineKeyboardJson(rows: List<List<BotInlineButton>>): String? {
        if (rows.isEmpty()) return null
        val rowsJson = rows.joinToString(",") { row ->
            "[" + row.joinToString(",") { btn ->
                val safeData = truncateUtf8(btn.callbackData, 64)
                """{"text":${jsonQuote(btn.text.take(48))},"callback_data":${jsonQuote(safeData)}}"""
            } + "]"
        }
        return """{"inline_keyboard":[$rowsJson]}"""
    }

    private fun buildTelegramPhotoRequest(
        token: String,
        chatId: String,
        caption: String,
        jpegBytes: ByteArray
    ): BotHttpRequest {
        val boundary = "----WjustBotTg${System.nanoTime()}"
        val baos = ByteArrayOutputStream(jpegBytes.size + 1024)
        val out = DataOutputStream(baos)
        writeFormField(out, boundary, "chat_id", chatId)
        writeFormField(out, boundary, "caption", caption.take(980))
        writeFilePart(out, boundary, "photo", "screen.jpg", "image/jpeg", jpegBytes)
        out.writeBytes("--$boundary--\r\n")
        out.flush()
        return BotHttpRequest(
            method = "POST",
            url = "https://api.telegram.org/bot$token/sendPhoto",
            contentType = "multipart/form-data; boundary=$boundary",
            body = baos.toByteArray()
        )
    }

    // ---- DISCORD BOT REST API (0-Server Polling) ----

    fun pollDiscordMessages(
        botToken: String,
        channelId: String,
        afterMessageId: String? = null,
        limit: Int = 10,
        transport: RemoteBotHttpTransport = defaultTransport
    ): Pair<Boolean, List<DiscordIncomingMessage>> {
        val cleanToken = botToken.trim().removePrefix("Bot ").trim()
        val cleanChannel = channelId.trim()
        if (cleanToken.isEmpty() || cleanChannel.isEmpty()) return false to emptyList()

        val afterPart = if (!afterMessageId.isNullOrBlank()) "&after=${URLEncoder.encode(afterMessageId, "UTF-8")}" else ""
        val url = "https://discord.com/api/v10/channels/$cleanChannel/messages?limit=${limit.coerceIn(1, 25)}$afterPart"
        val resp = transport.execute(
            BotHttpRequest(
                method = "GET",
                url = url,
                headers = mapOf("Authorization" to "Bot $cleanToken"),
                readTimeoutMs = 12_000
            )
        )
        if (resp.statusCode !in 200..299) return false to emptyList()
        return true to parseDiscordMessagesJson(resp.body, cleanChannel)
    }

    internal fun parseDiscordMessagesJson(body: String, fallbackChannelId: String): List<DiscordIncomingMessage> {
        val arr = runCatching { jsonParser.parseToJsonElement(body).jsonArray }.getOrNull() ?: return emptyList()
        val list = ArrayList<DiscordIncomingMessage>(arr.size)
        for (el in arr) {
            val obj = el as? JsonObject ?: continue
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val ch = obj["channel_id"]?.jsonPrimitive?.contentOrNull ?: fallbackChannelId
            val author = obj["author"] as? JsonObject
            val authorId = author?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
            val isBot = author?.get("bot")?.jsonPrimitive?.booleanOrNull == true
            list += DiscordIncomingMessage(
                messageId = id,
                channelId = ch,
                authorId = authorId,
                authorIsBot = isBot,
                content = content
            )
        }
        // Discord returns newest-first; sort chronologically (oldest -> newest) by numeric snowflake ID!
        return list.sortedBy { it.messageId.toLongOrNull() ?: 0L }
    }

    fun sendDiscordReply(
        botToken: String,
        channelId: String,
        reply: RemoteBotReply,
        transport: RemoteBotHttpTransport = defaultTransport
    ): Boolean {
        val cleanToken = botToken.trim().removePrefix("Bot ").trim()
        val cleanChannel = channelId.trim()
        if (cleanToken.isEmpty() || cleanChannel.isEmpty()) return false
        val url = "https://discord.com/api/v10/channels/$cleanChannel/messages"
        val headers = mapOf("Authorization" to "Bot $cleanToken")

        if (reply.jpegBytes != null && reply.jpegBytes.isNotEmpty()) {
            val boundary = "----WjustBotDc${System.nanoTime()}"
            val baos = ByteArrayOutputStream(reply.jpegBytes.size + 1024)
            val out = DataOutputStream(baos)
            val payloadJson = """{"content":${jsonQuote(reply.text.take(1900))}}"""
            writeFormField(out, boundary, "payload_json", payloadJson)
            writeFilePart(out, boundary, "files[0]", "screen.jpg", "image/jpeg", reply.jpegBytes)
            out.writeBytes("--$boundary--\r\n")
            out.flush()
            val resp = transport.execute(
                BotHttpRequest(
                    method = "POST",
                    url = url,
                    headers = headers,
                    contentType = "multipart/form-data; boundary=$boundary",
                    body = baos.toByteArray()
                )
            )
            return resp.statusCode in 200..299
        }

        val payload = """{"content":${jsonQuote(reply.text.take(1950))}}"""
        val resp = transport.execute(
            BotHttpRequest(
                method = "POST",
                url = url,
                headers = headers,
                contentType = "application/json; charset=UTF-8",
                body = payload.toByteArray(Charsets.UTF_8)
            )
        )
        return resp.statusCode in 200..299
    }

    /** Sends an instant interactive control panel to configured Telegram and/or Discord control channels. */
    fun sendPanelNow(
        config: NotificationConfig,
        host: RemoteControlHost,
        transport: RemoteBotHttpTransport = defaultTransport
    ): RemoteHostOpResult {
        val rb = config.remoteBot
        val panel = RemoteBotCommandExecutor.execute(RemoteBotCommand.Menu(), host)
            ?: return RemoteHostOpResult(false, "Gagal membangun panel kontrol.")
        val results = ArrayList<String>(2)
        var anyOk = false

        if (rb.telegramRemoteEnabled && config.telegramBotToken.isNotBlank() && config.telegramChatId.isNotBlank()) {
            val ok = runCatching {
                sendTelegramReply(config.telegramBotToken, config.telegramChatId, panel, transport = transport)
            }.getOrDefault(false)
            if (ok) anyOk = true
            results += if (ok) "Telegram: ✅ Panel terkirim" else "Telegram: ❌ Gagal"
        }
        if (rb.discordRemoteEnabled && rb.discordBotToken.isNotBlank() && rb.discordChannelId.isNotBlank()) {
            val ok = runCatching {
                sendDiscordReply(rb.discordBotToken, rb.discordChannelId, panel, transport = transport)
            }.getOrDefault(false)
            if (ok) anyOk = true
            results += if (ok) "Discord: ✅ Panel terkirim" else "Discord: ❌ Gagal"
        }
        if (results.isEmpty()) {
            return RemoteHostOpResult(false, "Isi Token Bot & Chat/Channel ID terlebih dahulu.")
        }
        return RemoteHostOpResult(anyOk, results.joinToString(" • "))
    }

    private fun writeFormField(out: DataOutputStream, boundary: String, name: String, value: String) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n")
        out.writeBytes("Content-Type: text/plain; charset=UTF-8\r\n\r\n")
        out.write(value.toByteArray(Charsets.UTF_8))
        out.writeBytes("\r\n")
    }

    private fun writeFilePart(
        out: DataOutputStream,
        boundary: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n")
        out.writeBytes("Content-Type: $mimeType\r\n\r\n")
        out.write(bytes)
        out.writeBytes("\r\n")
    }

    private fun truncateUtf8(s: String, maxBytes: Int): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return s
        var len = s.length
        while (len > 0 && s.substring(0, len).toByteArray(Charsets.UTF_8).size > maxBytes) {
            len--
        }
        return s.substring(0, len)
    }

    private fun jsonQuote(value: String): String = JsonPrimitive(value).toString()
}
