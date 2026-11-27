package com.example.data

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import kotlin.math.roundToInt

/**
 * Local device-only notification & phone alarm preferences.
 * Stored strictly in local private preferences (NEVER inside [Macro], NEVER exported in .zip/.wjm).
 */
@Serializable
data class NotificationConfig(
    val telegramEnabled: Boolean = false,
    val telegramBotToken: String = "",
    val telegramChatId: String = "",
    val discordEnabled: Boolean = false,
    val discordWebhookUrl: String = "",
    val notifyOnComplete: Boolean = true,
    val notifyOnError: Boolean = true,
    val notifyOnWatchdog: Boolean = true,
    val notifyOnAccountSwitch: Boolean = false,
    /** 0 = off; otherwise sends periodic status report every N minutes while running. */
    val periodicReportMinutes: Int = 0,
    val attachScreenshotOnEvents: Boolean = true,
    val alarmSoundEnabled: Boolean = true,
    val alarmVibrateEnabled: Boolean = true,
    val alarmOnError: Boolean = true,
    val alarmOnComplete: Boolean = false
) {
    fun hasAnyRemoteConfigured(): Boolean =
        (telegramEnabled && telegramBotToken.isNotBlank() && telegramChatId.isNotBlank()) ||
            (discordEnabled && discordWebhookUrl.isNotBlank())
}

/** Persistent farming session log saved locally on device for the Statistics dashboard. */
@Serializable
data class FarmingSessionRecord(
    val id: String = newId(),
    val macroId: String,
    val macroName: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationMs: Long,
    val loopsCompleted: Int,
    val actionsExecuted: Int,
    val watchdogRecoveries: Int = 0,
    val bannedBlocks: Int = 0,
    val accountSwitches: Int = 0,
    val humanBreaks: Int = 0,
    val endReason: String = "Selesai",
    val finalVariables: Map<String, Double> = emptyMap()
)

data class LiveSessionStats(
    val macroName: String,
    val startedAtMs: Long,
    val loopsCompleted: Int,
    val actionsExecuted: Int,
    val watchdogRecoveries: Int,
    val bannedBlocks: Int,
    val accountSwitches: Int,
    val humanBreaks: Int
)

data class FarmingStatsSummary(
    val sessionCount: Int,
    val totalDurationMs: Long,
    val totalLoops: Int,
    val totalActions: Int,
    val totalWatchdogRecoveries: Int,
    val totalBannedBlocks: Int,
    val totalAccountSwitches: Int,
    val totalHumanBreaks: Int,
    val avgLoopsPerHour: Double,
    val avgActionsPerHour: Double
)

data class MacroStatsBreakdown(
    val macroId: String,
    val macroName: String,
    val sessionCount: Int,
    val totalDurationMs: Long,
    val totalLoops: Int,
    val totalActions: Int,
    val loopsPerHour: Double
)

/**
 * Pure-Kotlin farming metrics calculator & dynamic notification template expander.
 */
object FarmingStatsCalculator {

    private val varPlaceholderRegex = Regex("""\{var:([A-Za-z0-9_\-\s]+)\}""")

    fun ratePerHour(count: Int, durationMs: Long): Double {
        if (count <= 0 || durationMs < 1_000L) return 0.0
        val hours = durationMs.toDouble() / 3_600_000.0
        return count.toDouble() / hours
    }

    fun summarize(sessions: List<FarmingSessionRecord>): FarmingStatsSummary {
        if (sessions.isEmpty()) {
            return FarmingStatsSummary(0, 0L, 0, 0, 0, 0, 0, 0, 0.0, 0.0)
        }
        var dur = 0L
        var loops = 0
        var actions = 0
        var wd = 0
        var banned = 0
        var accSw = 0
        var breaks = 0
        for (s in sessions) {
            dur += s.durationMs.coerceAtLeast(0L)
            loops += s.loopsCompleted.coerceAtLeast(0)
            actions += s.actionsExecuted.coerceAtLeast(0)
            wd += s.watchdogRecoveries.coerceAtLeast(0)
            banned += s.bannedBlocks.coerceAtLeast(0)
            accSw += s.accountSwitches.coerceAtLeast(0)
            breaks += s.humanBreaks.coerceAtLeast(0)
        }
        return FarmingStatsSummary(
            sessionCount = sessions.size,
            totalDurationMs = dur,
            totalLoops = loops,
            totalActions = actions,
            totalWatchdogRecoveries = wd,
            totalBannedBlocks = banned,
            totalAccountSwitches = accSw,
            totalHumanBreaks = breaks,
            avgLoopsPerHour = ratePerHour(loops, dur),
            avgActionsPerHour = ratePerHour(actions, dur)
        )
    }

    fun breakdownByMacro(sessions: List<FarmingSessionRecord>): List<MacroStatsBreakdown> {
        if (sessions.isEmpty()) return emptyList()
        return sessions.groupBy { it.macroId.ifBlank { it.macroName } }
            .values
            .map { group ->
                val latest = group.maxByOrNull { it.endedAtMs } ?: group.first()
                val dur = group.sumOf { it.durationMs.coerceAtLeast(0L) }
                val loops = group.sumOf { it.loopsCompleted.coerceAtLeast(0) }
                val actions = group.sumOf { it.actionsExecuted.coerceAtLeast(0) }
                MacroStatsBreakdown(
                    macroId = latest.macroId,
                    macroName = latest.macroName.ifBlank { "Macro" },
                    sessionCount = group.size,
                    totalDurationMs = dur,
                    totalLoops = loops,
                    totalActions = actions,
                    loopsPerHour = ratePerHour(loops, dur)
                )
            }
            .sortedByDescending { it.totalDurationMs }
    }

    fun formatDuration(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L) / 1000L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return when {
            hours > 0L -> "${hours}j ${minutes}m"
            minutes > 0L -> "${minutes}m ${seconds}d"
            else -> "${seconds}d"
        }
    }

    fun formatRate(rate: Double): String {
        if (!rate.isFinite() || rate <= 0.0) return "0"
        return if (rate >= 100.0) rate.roundToInt().toString()
        else String.format(java.util.Locale.US, "%.1f", rate)
    }

    fun formatPlainDouble(v: Double): String =
        if (!v.isFinite()) "0"
        else if (v == v.toLong().toDouble()) v.toLong().toString()
        else BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()

    /**
     * Expands dynamic placeholders inside [NotifyAction.title] / [NotifyAction.message]:
     * `{macro}`, `{loop}`, `{actions}`, `{account}`, `{var:name}`.
     */
    fun expandNotifyTemplate(
        template: String,
        macroName: String,
        loop: Int,
        actionsExecuted: Int,
        accountLabel: String,
        variables: Map<String, Double>
    ): String {
        if (template.isBlank()) return ""
        var out = template
            .replace("{macro}", macroName)
            .replace("{loop}", loop.toString())
            .replace("{actions}", actionsExecuted.toString())
            .replace("{account}", accountLabel.ifBlank { "-" })

        out = varPlaceholderRegex.replace(out) { m ->
            val key = m.groupValues[1].trim().lowercase()
            val found = variables[key] ?: variables["👤$key"] ?: 0.0
            formatPlainDouble(found)
        }
        return out
    }
}
