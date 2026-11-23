package com.example.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel(val tag: String) {
    INFO("INFO"),
    ACTION("ACT"),
    IMAGE("IMG"),
    COLOR("CLR"),
    LUA("LUA"),
    WARN("WARN"),
    ERROR("ERR")
}

data class LogEntry(
    val id: Long,
    val timestamp: String,
    val level: LogLevel,
    val message: String
)

object ExecutionLogBus {
    private const val MAX_ENTRIES = 250
    private val idGenerator = AtomicLong(1L)
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun log(level: LogLevel, message: String) {
        val formattedTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = LogEntry(
            id = idGenerator.getAndIncrement(),
            timestamp = formattedTime,
            level = level,
            message = message
        )
        _logs.update { current ->
            val next = current + entry
            if (next.size > MAX_ENTRIES) next.takeLast(MAX_ENTRIES) else next
        }
    }

    fun info(message: String) = log(LogLevel.INFO, message)
    fun action(message: String) = log(LogLevel.ACTION, message)
    fun image(message: String) = log(LogLevel.IMAGE, message)
    fun color(message: String) = log(LogLevel.COLOR, message)
    fun lua(message: String) = log(LogLevel.LUA, message)
    fun warn(message: String) = log(LogLevel.WARN, message)
    fun error(message: String) = log(LogLevel.ERROR, message)

    fun clear() {
        _logs.value = emptyList()
    }
}
