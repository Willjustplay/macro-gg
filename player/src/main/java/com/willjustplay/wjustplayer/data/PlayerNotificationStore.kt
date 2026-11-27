package com.willjustplay.wjustplayer.data

import android.content.Context
import com.example.data.FarmingSessionRecord
import com.example.data.NotificationConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local device-only store for Wjust Player's personal Telegram/Discord/Alarm preferences
 * and local farming session statistics. Never touches or leaks creator `.wjm` bundles.
 */
class PlayerNotificationStore private constructor(context: Context) {

    private val rootDir = File(context.applicationContext.filesDir, "wjust_player_local").apply { mkdirs() }
    private val notifyFile = File(rootDir, "notify_config.json")
    private val statsFile = File(rootDir, "farming_stats.json")
    private val afkGuardFile = File(rootDir, "afk_guard_config.json")
    private val schedulesFile = File(rootDir, "schedules.json")

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _notificationConfig = MutableStateFlow(loadNotificationConfig())
    val notificationConfig: StateFlow<NotificationConfig> = _notificationConfig.asStateFlow()

    private val _farmingStats = MutableStateFlow(loadFarmingStats())
    val farmingStats: StateFlow<List<FarmingSessionRecord>> = _farmingStats.asStateFlow()

    private val _afkGuardConfig = MutableStateFlow(loadAfkGuardConfig())
    val afkGuardConfig: StateFlow<com.example.data.AfkAndDeviceGuardConfig> = _afkGuardConfig.asStateFlow()

    private val _schedules = MutableStateFlow(loadSchedules())
    val schedules: StateFlow<List<com.example.data.MacroSchedule>> = _schedules.asStateFlow()

    private fun loadNotificationConfig(): NotificationConfig {
        if (!notifyFile.exists()) return NotificationConfig()
        return runCatching {
            json.decodeFromString<NotificationConfig>(notifyFile.readText())
        }.getOrDefault(NotificationConfig())
    }

    fun saveNotificationConfig(config: NotificationConfig) {
        runCatching { notifyFile.writeText(json.encodeToString(config)) }
        _notificationConfig.value = config
    }

    private fun loadFarmingStats(): List<FarmingSessionRecord> {
        if (!statsFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<FarmingSessionRecord>>(statsFile.readText())
        }.getOrDefault(emptyList()).sortedByDescending { it.endedAtMs }
    }

    fun recordFarmingSession(record: FarmingSessionRecord) {
        val next = (listOf(record) + _farmingStats.value).take(MAX_SESSIONS)
        runCatching { statsFile.writeText(json.encodeToString(next)) }
        _farmingStats.value = next
    }

    fun clearFarmingStats() {
        runCatching { if (statsFile.exists()) statsFile.delete() }
        _farmingStats.value = emptyList()
    }

    private fun loadAfkGuardConfig(): com.example.data.AfkAndDeviceGuardConfig {
        if (!afkGuardFile.exists()) return com.example.data.AfkAndDeviceGuardConfig()
        return runCatching {
            json.decodeFromString<com.example.data.AfkAndDeviceGuardConfig>(afkGuardFile.readText())
        }.getOrDefault(com.example.data.AfkAndDeviceGuardConfig())
    }

    fun saveAfkGuardConfig(config: com.example.data.AfkAndDeviceGuardConfig) {
        runCatching { afkGuardFile.writeText(json.encodeToString(config)) }
        _afkGuardConfig.value = config
    }

    private fun loadSchedules(): List<com.example.data.MacroSchedule> {
        if (!schedulesFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<com.example.data.MacroSchedule>>(schedulesFile.readText())
        }.getOrDefault(emptyList()).sortedWith(compareBy({ it.hour }, { it.minute }))
    }

    fun saveSchedule(schedule: com.example.data.MacroSchedule) {
        val next = (_schedules.value.filterNot { it.id == schedule.id } + schedule)
            .sortedWith(compareBy({ it.hour }, { it.minute }))
        runCatching { schedulesFile.writeText(json.encodeToString(next)) }
        _schedules.value = next
    }

    fun deleteSchedule(id: String) {
        val next = _schedules.value.filterNot { it.id == id }
        runCatching { schedulesFile.writeText(json.encodeToString(next)) }
        _schedules.value = next
    }

    fun markScheduleTriggered(id: String, triggeredAtMs: Long) {
        val next = _schedules.value.map { s ->
            if (s.id == id) {
                val keepEnabled = s.repeatMode != com.example.data.ScheduleRepeatMode.ONCE
                s.copy(lastTriggeredAtMs = triggeredAtMs, enabled = keepEnabled)
            } else s
        }
        runCatching { schedulesFile.writeText(json.encodeToString(next)) }
        _schedules.value = next
    }

    companion object {
        private const val MAX_SESSIONS = 120

        @Volatile
        private var INSTANCE: PlayerNotificationStore? = null

        fun get(context: Context): PlayerNotificationStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PlayerNotificationStore(context.applicationContext).also { INSTANCE = it }
            }
    }
}
