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

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _notificationConfig = MutableStateFlow(loadNotificationConfig())
    val notificationConfig: StateFlow<NotificationConfig> = _notificationConfig.asStateFlow()

    private val _farmingStats = MutableStateFlow(loadFarmingStats())
    val farmingStats: StateFlow<List<FarmingSessionRecord>> = _farmingStats.asStateFlow()

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
