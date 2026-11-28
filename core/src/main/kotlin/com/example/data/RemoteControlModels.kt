package com.example.data

import kotlinx.serialization.Serializable

/**
 * How an individual action inside a [Macro] responds to Telegram / Discord bot commands.
 */
@Serializable
enum class RemoteActionMode(
    val label: String,
    val shortLabel: String,
    val description: String
) {
    TOGGLE(
        label = "Saklar ON / OFF (Aktif / Pasif)",
        shortLabel = "Saklar",
        description = "ON = aksi dijalankan normal; OFF = aksi dilewati secara live tanpa perlu hentikan macro."
    ),
    ONE_SHOT(
        label = "Pemicu 1× (Otomatis Balik OFF)",
        shortLabel = "Pemicu 1×",
        description = "Default OFF; saat dipicu dari bot, aksi dijalankan tepat 1 kali lalu otomatis kembali OFF."
    )
}

/**
 * Per-action remote control binding stored in [Macro.actionRemoteControls] keyed by [Action.id].
 * Keeping this map on [Macro] avoids modifying all 25 [Action] subclasses and stays 100% backward-compatible.
 */
@Serializable
data class ActionRemoteControl(
    val enabled: Boolean = false,
    /** Short unique command ID inside the macro, e.g. "auto_sell", "heal", "claim". */
    val remoteId: String = "",
    /** Optional friendly label displayed on Telegram inline buttons and status lists. */
    val label: String = "",
    val mode: RemoteActionMode = RemoteActionMode.TOGGLE,
    /**
     * Live ON/OFF state.
     * - For [RemoteActionMode.TOGGLE]: true = runs normally, false = skipped live.
     * - For [RemoteActionMode.ONE_SHOT]: true = armed for 1 execution (auto-resets to false once executed).
     */
    val active: Boolean = true
) {
    fun displayName(fallback: String = "Aksi"): String =
        label.trim().ifBlank { remoteId.trim().ifBlank { fallback } }

    fun normalizedId(): String = RemoteIdSanitizer.sanitize(remoteId)
}

/**
 * Local device-only 2-way Telegram / Discord remote bot listener settings.
 * Stored strictly inside local device preferences ([NotificationConfig.remoteBot]) — NEVER exported in .zip/.wjm!
 */
@Serializable
data class RemoteBotConfig(
    /** Master switch enabling 2-way remote command listening on this phone. */
    val listenerEnabled: Boolean = false,
    /** Optional short device name for multi-phone setups (e.g. "hp1", "hp2"). Blank = matches untargeted commands. */
    val deviceTag: String = "",
    /** Reuse the configured Telegram Bot Token + Chat ID for 2-way long-polling (`getUpdates`). */
    val telegramRemoteEnabled: Boolean = true,
    /** Optional comma-separated extra Telegram Chat/User IDs allowed to send commands (primary Chat ID is always allowed). */
    val telegramAllowedIds: String = "",
    /** Enable 2-way Discord Bot REST polling (`GET /channels/{id}/messages`) using a Discord Bot Token. */
    val discordRemoteEnabled: Boolean = false,
    val discordBotToken: String = "",
    val discordChannelId: String = "",
    /** Optional comma-separated Discord User IDs allowed to issue commands in [discordChannelId]. */
    val discordAllowedUserIds: String = "",
    /** Discord REST polling interval in seconds (clamped 3..30s to stay battery- and rate-limit-friendly). */
    val discordPollIntervalSec: Int = 4
) {
    fun normalizedDeviceTag(): String = RemoteIdSanitizer.sanitize(deviceTag)

    fun hasTelegramListenerReady(notif: NotificationConfig): Boolean =
        listenerEnabled && telegramRemoteEnabled &&
            notif.telegramBotToken.isNotBlank() &&
            (notif.telegramChatId.isNotBlank() || parseIdSet(telegramAllowedIds).isNotEmpty())

    fun hasDiscordListenerReady(): Boolean =
        listenerEnabled && discordRemoteEnabled &&
            discordBotToken.isNotBlank() && discordChannelId.isNotBlank()

    fun hasAnyListenerReady(notif: NotificationConfig): Boolean =
        hasTelegramListenerReady(notif) || hasDiscordListenerReady()

    companion object {
        fun parseIdSet(raw: String): Set<String> =
            raw.split(',', ';', '\n', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
    }
}

/**
 * Pure-Kotlin sanitizer & unique slug generator for Macro Remote IDs, Action Remote IDs, and Device Tags.
 */
object RemoteIdSanitizer {

    private val invalidChars = Regex("[^a-z0-9_-]+")
    private val repeatedUnderscore = Regex("_+")

    /** Normalizes any user input into a safe lowercase command slug (`a-z`, `0-9`, `_`, `-`, max 24 chars). */
    fun sanitize(raw: String): String {
        val cleaned = raw.trim()
            .lowercase()
            .replace(' ', '_')
            .replace(invalidChars, "")
            .replace(repeatedUnderscore, "_")
            .trim('_', '-')
            .take(24)
        return cleaned
    }

    /** Generates a clean, unique short macro remote ID from [macroName] that does not collide with [existingIds]. */
    fun suggestMacroId(macroName: String, existingIds: Set<String>): String {
        val normalizedExisting = existingIds.map { sanitize(it) }.filter { it.isNotEmpty() }.toSet()
        val base = sanitize(macroName).take(16).ifBlank { "macro" }
        if (base !in normalizedExisting) return base
        for (i in 2..999) {
            val candidate = "${base.take(12)}_$i"
            if (candidate !in normalizedExisting) return candidate
        }
        return "m_${newId().take(6).lowercase()}"
    }

    /** Generates a clean, unique short action remote ID inside a macro. */
    fun suggestActionId(hint: String, existingIds: Set<String>): String {
        val normalizedExisting = existingIds.map { sanitize(it) }.filter { it.isNotEmpty() }.toSet()
        val base = sanitize(hint).take(16).ifBlank { "aksi" }
        if (base !in normalizedExisting) return base
        for (i in 2..999) {
            val candidate = "${base.take(12)}_$i"
            if (candidate !in normalizedExisting) return candidate
        }
        return "a_${newId().take(6).lowercase()}"
    }
}
