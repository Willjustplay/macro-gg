package com.example.data

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import java.util.UUID

enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun fromRaw(raw: String?): ThemeMode =
            entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

enum class MacroStepType(val label: String) {
    CLICK("Klik"),
    LONG_PRESS("Tahan"),
    SWIPE("Swipe"),
    WAIT("Jeda"),
    WAIT_COLOR("Tunggu Warna");

    companion object {
        fun fromRaw(raw: String?): MacroStepType =
            entries.firstOrNull { it.name == raw } ?: CLICK
    }
}

enum class ScriptRunStatus(val label: String) {
    NEVER("Belum Dijalankan"),
    SUCCESS("Berhasil"),
    STOPPED("Dihentikan"),
    ERROR("Error");

    companion object {
        fun fromRaw(raw: String?): ScriptRunStatus =
            entries.firstOrNull { it.name == raw } ?: NEVER
    }
}

enum class RunTargetType {
    MACRO, LUA;

    companion object {
        fun fromRaw(raw: String?): RunTargetType =
            entries.firstOrNull { it.name == raw } ?: MACRO
    }
}

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

data class MacroStep(
    val stepId: String = "st_${UUID.randomUUID().toString().take(8)}",
    val type: MacroStepType = MacroStepType.CLICK,
    val label: String = "Titik Klik",
    val x: Int = 540,
    val y: Int = 960,
    val endX: Int = 540,
    val endY: Int = 500,
    val durationMs: Int = 60,
    val delayAfterMs: Int = 400,
    val targetColorHex: String = "#2DD4BF",
    val colorTolerance: Int = 15,
    val timeoutMs: Int = 5000
) {
    fun toFirestoreMap(): Map<String, Any> = mapOf(
        "stepId" to stepId,
        "type" to type.name,
        "label" to label.take(60),
        "x" to x.coerceIn(0, 10000),
        "y" to y.coerceIn(0, 10000),
        "endX" to endX.coerceIn(0, 10000),
        "endY" to endY.coerceIn(0, 10000),
        "durationMs" to durationMs.coerceIn(10, 60000),
        "delayAfterMs" to delayAfterMs.coerceIn(0, 600000),
        "targetColorHex" to targetColorHex.uppercase().take(9),
        "colorTolerance" to colorTolerance.coerceIn(0, 255),
        "timeoutMs" to timeoutMs.coerceIn(100, 600000)
    )

    companion object {
        fun fromFirestoreMap(map: Map<String, Any?>): MacroStep = MacroStep(
            stepId = (map["stepId"] as? String)?.takeIf { it.isNotBlank() }
                ?: "st_${UUID.randomUUID().toString().take(8)}",
            type = MacroStepType.fromRaw(map["type"] as? String),
            label = (map["label"] as? String).orEmpty().ifBlank { "Aksi" },
            x = (map["x"] as? Number)?.toInt() ?: 540,
            y = (map["y"] as? Number)?.toInt() ?: 960,
            endX = (map["endX"] as? Number)?.toInt() ?: 540,
            endY = (map["endY"] as? Number)?.toInt() ?: 500,
            durationMs = (map["durationMs"] as? Number)?.toInt() ?: 60,
            delayAfterMs = (map["delayAfterMs"] as? Number)?.toInt() ?: 400,
            targetColorHex = (map["targetColorHex"] as? String)?.takeIf { it.startsWith("#") } ?: "#2DD4BF",
            colorTolerance = (map["colorTolerance"] as? Number)?.toInt() ?: 15,
            timeoutMs = (map["timeoutMs"] as? Number)?.toInt() ?: 5000
        )
    }
}

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "Pengguna TapLua",
    val email: String? = null,
    val themeMode: String = ThemeMode.SYSTEM.name,
    val defaultTapDurationMs: Int = 60,
    val jitterRadiusPx: Int = 4,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun resolvedThemeMode(): ThemeMode = ThemeMode.fromRaw(themeMode)
}

@Keep
@IgnoreExtraProperties
data class VisualMacro(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val description: String = "",
    val loopCount: Int = 1,
    val loopDelayMs: Int = 800,
    val stopOnError: Boolean = true,
    val steps: List<Map<String, Any>> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun parsedSteps(): List<MacroStep> = steps.map { MacroStep.fromFirestoreMap(it) }
}

@Keep
@IgnoreExtraProperties
data class LuaScript(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val description: String = "",
    val code: String = "",
    val tags: List<String> = emptyList(),
    val lastRunStatus: String = ScriptRunStatus.NEVER.name,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun resolvedStatus(): ScriptRunStatus = ScriptRunStatus.fromRaw(lastRunStatus)
}

@Keep
@IgnoreExtraProperties
data class RunHistoryEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val targetType: String = RunTargetType.MACRO.name,
    val targetId: String = "",
    val targetName: String = "",
    val status: String = ScriptRunStatus.SUCCESS.name,
    val durationMs: Long = 0L,
    val actionsExecuted: Int = 0,
    val summaryMessage: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    fun resolvedTargetType(): RunTargetType = RunTargetType.fromRaw(targetType)
    fun resolvedStatus(): ScriptRunStatus = ScriptRunStatus.fromRaw(status)
}
