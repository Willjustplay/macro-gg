package com.willjustplay.wjustplayer.runtime

import com.example.automation.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class SafeLogEntry(
    val id: Long,
    val timestamp: String,
    val level: LogLevel,
    val message: String
)

/**
 * Privacy-preserving live log bus for Wjust Player.
 *
 * Shows clear status/lifecycle messages (start, loop progress, pause, stop, errors) while
 * stripping low-level execution internals (pixel coordinates, image template names, OCR text,
 * or branching logic) so creator `.wjm` configs remain opaque.
 */
object PlayerSafeLogBus {
    private const val MAX_ENTRIES = 160
    private val seq = AtomicLong(0)
    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    private val _logs = MutableStateFlow<List<SafeLogEntry>>(emptyList())
    val logs: StateFlow<List<SafeLogEntry>> = _logs.asStateFlow()

    fun info(message: String) = append(LogLevel.INFO, message)
    fun warn(message: String) = append(LogLevel.WARN, message)
    fun error(message: String) = append(LogLevel.ERROR, message)

    fun clear() {
        _logs.value = emptyList()
    }

    /**
     * Filters raw logs emitted by [com.example.automation.MacroRunner] so only safe, high-level
     * status/error lines reach Wjust Player users. Returns `null` when a message should be hidden.
     */
    fun filterRunnerMessage(level: LogLevel, rawMessage: String): Pair<LogLevel, String>? {
        val msg = rawMessage.trim()
        if (msg.isBlank()) return null
        return when (level) {
            LogLevel.ACTION, LogLevel.IMAGE, LogLevel.COLOR, LogLevel.LUA -> null
            LogLevel.ERROR -> LogLevel.ERROR to sanitizeSensitiveDetails(msg)
            LogLevel.WARN -> {
                if (containsSensitiveCoordsOrAssets(msg)) null
                else LogLevel.WARN to msg
            }
            LogLevel.INFO -> {
                if (isSafeStatusMessage(msg)) LogLevel.INFO to msg else null
            }
        }
    }

    fun logFromRunner(level: LogLevel, rawMessage: String) {
        val filtered = filterRunnerMessage(level, rawMessage) ?: return
        append(filtered.first, filtered.second)
    }

    private fun isSafeStatusMessage(msg: String): Boolean {
        if (containsSensitiveCoordsOrAssets(msg)) return false
        return msg.startsWith("Loop ", ignoreCase = true) ||
            msg.startsWith("Memulai", ignoreCase = true) ||
            msg.startsWith("Selesai", ignoreCase = true) ||
            msg.startsWith("Macro ", ignoreCase = true) ||
            msg.startsWith("Dijeda", ignoreCase = true) ||
            msg.startsWith("Dilanjutkan", ignoreCase = true) ||
            msg.startsWith("Watchdog", ignoreCase = true) ||
            msg.startsWith("Anti-Macet", ignoreCase = true) ||
            msg.startsWith("Beralih", ignoreCase = true) ||
            msg.startsWith("Batas durasi", ignoreCase = true)
    }

    private fun containsSensitiveCoordsOrAssets(msg: String): Boolean {
        return msg.contains(".png", ignoreCase = true) ||
            msg.contains(".jpg", ignoreCase = true) ||
            msg.contains("#") && Regex("#[0-9A-Fa-f]{6}").containsMatchIn(msg) ||
            Regex("\\(\\s*\\d+\\s*,\\s*\\d+\\s*\\)").containsMatchIn(msg)
    }

    private fun sanitizeSensitiveDetails(msg: String): String {
        val cleaned = msg
            .replace(Regex("[A-Za-z0-9_\\-/]+\\.(png|jpg|jpeg|webp)", RegexOption.IGNORE_CASE), "[aset]")
            .replace(Regex("\\(\\s*\\d+\\s*,\\s*\\d+\\s*\\)"), "(...)")
        return cleaned
    }

    private fun append(level: LogLevel, message: String) {
        val entry = SafeLogEntry(
            id = seq.incrementAndGet(),
            timestamp = timeFormat.get()?.format(Date()) ?: "00:00:00",
            level = level,
            message = message
        )
        _logs.update { current ->
            val next = current + entry
            if (next.size > MAX_ENTRIES) next.takeLast(MAX_ENTRIES) else next
        }
    }
}
