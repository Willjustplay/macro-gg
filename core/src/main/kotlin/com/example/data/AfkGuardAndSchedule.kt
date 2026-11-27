package com.example.data

import kotlinx.serialization.Serializable
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Rendering strategy for Mode Layar Hitam AFK (🌙). */
@Serializable
enum class AfkBlackScreenMode(val label: String, val shortLabel: String, val description: String) {
    SMART_AUTO(
        "Pintar Otomatis (Rekomendasi)",
        "Pintar",
        "Otomatis pakai Layar Hitam Penuh AMOLED (#000000) untuk macro klik/swipe, dan Mode Redup Ultra-Gelap untuk macro Gambar/OCR agar deteksi tidak terganggu."
    ),
    ULTRA_DIM_VISION_SAFE(
        "Redup Ultra-Gelap (Aman Gambar/OCR)",
        "Redup Aman",
        "Turunkan kecerahan layar ke minimum (~0.5%) sambil menjaga tangkap layar Gambar/OCR/Warna 100% akurat."
    ),
    PURE_BLACK_AMOLED(
        "Layar Hitam Penuh AMOLED (#000000)",
        "Hitam Penuh",
        "Tutup layar dengan hitam pekat tembus sentuhan (hemat ekstra di layar AMOLED, cocok untuk klik/swipe/rekam)."
    )
}

/** Protective action taken when device battery temperature exceeds [AfkAndDeviceGuardConfig.maxTempCelsius]. */
@Serializable
enum class DeviceGuardAction(val label: String, val shortLabel: String) {
    PAUSE_AND_COOL("Jeda & Dinginkan (Lanjut Otomatis)", "Jeda Dingin"),
    STOP_MACRO("Hentikan Macro Sepenuhnya", "Stop Macro")
}

/**
 * Local device-only configuration for AFK Black Screen & Battery/Thermal Guard.
 * Stored strictly locally on-device (NEVER embedded inside [Macro], NEVER exported in .zip/.wjm).
 */
@Serializable
data class AfkAndDeviceGuardConfig(
    val blackScreenMode: AfkBlackScreenMode = AfkBlackScreenMode.SMART_AUTO,
    /** Auto-enable AFK Black Screen N seconds after macro starts (0 = manual toggle via 🌙 button only). */
    val autoBlackScreenAfterSec: Int = 0,
    /** Drop window brightness to minimum (~0.5%) while AFK Black Screen is active. */
    val dimBrightnessToMin: Boolean = true,
    /** Gently drift the AFK floating status pill every ~45s to prevent AMOLED burn-in. */
    val amoledBurnInShift: Boolean = true,
    /** Require double-tap on the AFK pill (or tap Wake button) to exit AFK Black Screen safely. */
    val doubleTapToUnlock: Boolean = true,
    /** Auto-stop macro when battery drops to or below [minBatteryPercent] while unplugged. */
    val batteryGuardEnabled: Boolean = false,
    val minBatteryPercent: Int = 15,
    /** Auto-pause or stop macro when battery temperature reaches [maxTempCelsius] °C. */
    val thermalGuardEnabled: Boolean = false,
    val maxTempCelsius: Float = 44.0f,
    /** Temperature drop hysteresis (°C) before automatically resuming from thermal pause. */
    val resumeTempDropCelsius: Float = 3.0f,
    val thermalAction: DeviceGuardAction = DeviceGuardAction.PAUSE_AND_COOL,
    /** Send remote Telegram/Discord alert & ring local Phone Alarm when a device guard triggers. */
    val notifyOnGuardTrigger: Boolean = true
)

/** Live hardware health snapshot read via Android sticky ACTION_BATTERY_CHANGED (zero extra permissions). */
data class DeviceHealthSnapshot(
    val batteryPercent: Int,
    val isCharging: Boolean,
    val tempCelsius: Float,
    val sampledAtMs: Long = System.currentTimeMillis()
)

enum class DeviceGuardDecision {
    OK,
    STOP_LOW_BATTERY,
    STOP_OVERHEAT,
    PAUSE_OVERHEAT,
    STAY_THERMAL_PAUSED,
    RESUME_COOLED
}

/** Pure-Kotlin deterministic evaluator for Battery Low & Thermal Overheat protection with hysteresis. */
object DeviceGuardEvaluator {

    fun evaluate(
        config: AfkAndDeviceGuardConfig,
        snapshot: DeviceHealthSnapshot,
        currentlyThermalPaused: Boolean
    ): DeviceGuardDecision {
        // 1. Battery Low Guard takes top priority (only when NOT plugged in)
        val minBat = config.minBatteryPercent.coerceIn(5, 80)
        if (config.batteryGuardEnabled && !snapshot.isCharging && snapshot.batteryPercent in 1..minBat) {
            return DeviceGuardDecision.STOP_LOW_BATTERY
        }

        // 2. Thermal Overheat Guard with hysteresis
        if (!config.thermalGuardEnabled) {
            return if (currentlyThermalPaused) DeviceGuardDecision.RESUME_COOLED else DeviceGuardDecision.OK
        }

        val maxTemp = config.maxTempCelsius.coerceIn(35f, 55f)
        val drop = config.resumeTempDropCelsius.coerceIn(1f, 10f)
        val resumeTemp = (maxTemp - drop).coerceAtLeast(30f)

        if (snapshot.tempCelsius <= 0f || !snapshot.tempCelsius.isFinite()) {
            return if (currentlyThermalPaused) DeviceGuardDecision.STAY_THERMAL_PAUSED else DeviceGuardDecision.OK
        }

        if (currentlyThermalPaused) {
            return if (snapshot.tempCelsius <= resumeTemp) {
                DeviceGuardDecision.RESUME_COOLED
            } else {
                DeviceGuardDecision.STAY_THERMAL_PAUSED
            }
        }

        if (snapshot.tempCelsius >= maxTemp) {
            return when (config.thermalAction) {
                DeviceGuardAction.STOP_MACRO -> DeviceGuardDecision.STOP_OVERHEAT
                DeviceGuardAction.PAUSE_AND_COOL -> DeviceGuardDecision.PAUSE_OVERHEAT
            }
        }

        return DeviceGuardDecision.OK
    }

    fun formatTemp(tempCelsius: Float): String =
        if (tempCelsius <= 0f || !tempCelsius.isFinite()) "--°C"
        else String.format(Locale.US, "%.1f°C", tempCelsius)
}

/** Repeat recurrence mode for [MacroSchedule]. */
@Serializable
enum class ScheduleRepeatMode(val label: String, val shortLabel: String) {
    ONCE("Sekali Jalan", "Sekali"),
    DAILY("Setiap Hari", "Harian"),
    WEEKDAYS("Hari Kerja (Sen–Jum)", "Sen–Jum"),
    CUSTOM_DAYS("Hari Pilihan", "Pilih Hari")
}

/**
 * Local scheduled automatic macro execution entry (device-only, NEVER exported in .zip/.wjm).
 */
@Serializable
data class MacroSchedule(
    val id: String = newId(),
    val label: String = "",
    val macroId: String = "",
    val macroName: String = "",
    val enabled: Boolean = true,
    /** 0..23 */
    val hour: Int = 8,
    /** 0..59 */
    val minute: Int = 0,
    val repeatMode: ScheduleRepeatMode = ScheduleRepeatMode.DAILY,
    /** ISO day-of-week values: 1 = Monday .. 7 = Sunday (used when [repeatMode] == CUSTOM_DAYS). */
    val daysOfWeek: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7),
    /** Optional game/app package name to launch automatically before running the macro. */
    val launchAppPackage: String = "",
    val launchAppLabel: String = "",
    val launchWaitSec: Int = 8,
    /** Optional max run duration override in minutes (0 = use macro's own loopCount / maxDurationMin). */
    val stopAfterMinutes: Int = 0,
    /** Automatically engage Mode Layar Hitam AFK (🌙) when started by this schedule. */
    val enableBlackScreenOnStart: Boolean = false,
    /** Epoch ms of the last time this schedule fired (prevents duplicate triggers in the same minute). */
    val lastTriggeredAtMs: Long = 0L
)

/** Pure-Kotlin scheduler calculator for next trigger time, due-window detection, and Indonesian labels. */
object MacroScheduleCalculator {

    private val dayNames = mapOf(
        1 to "Sen",
        2 to "Sel",
        3 to "Rab",
        4 to "Kam",
        5 to "Jum",
        6 to "Sab",
        7 to "Min"
    )

    fun dayShortLabel(isoDay: Int): String = dayNames[isoDay] ?: "?"

    fun formatTime(hour: Int, minute: Int): String =
        String.format(Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))

    fun calendarToIsoDay(calDayOfWeek: Int): Int = when (calDayOfWeek) {
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5
        Calendar.SATURDAY -> 6
        Calendar.SUNDAY -> 7
        else -> 1
    }

    fun matchesDay(schedule: MacroSchedule, isoDay: Int): Boolean = when (schedule.repeatMode) {
        ScheduleRepeatMode.ONCE, ScheduleRepeatMode.DAILY -> true
        ScheduleRepeatMode.WEEKDAYS -> isoDay in 1..5
        ScheduleRepeatMode.CUSTOM_DAYS -> isoDay in schedule.daysOfWeek
    }

    /**
     * Computes the next future epoch ms when [schedule] should fire strictly after [nowMs],
     * or `null` if disabled / invalid / already consumed (ONCE).
     */
    fun nextTriggerAtMs(
        schedule: MacroSchedule,
        nowMs: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long? {
        if (!schedule.enabled || schedule.macroId.isBlank()) return null
        if (schedule.repeatMode == ScheduleRepeatMode.ONCE && schedule.lastTriggeredAtMs > 0L) return null
        if (schedule.repeatMode == ScheduleRepeatMode.CUSTOM_DAYS && schedule.daysOfWeek.none { it in 1..7 }) {
            return null
        }

        val h = schedule.hour.coerceIn(0, 23)
        val m = schedule.minute.coerceIn(0, 59)
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        for (dayOffset in 0..7) {
            val candidate = (cal.clone() as Calendar).apply {
                if (dayOffset > 0) add(Calendar.DAY_OF_YEAR, dayOffset)
            }
            val isoDay = calendarToIsoDay(candidate.get(Calendar.DAY_OF_WEEK))
            if (matchesDay(schedule, isoDay) && candidate.timeInMillis > nowMs) {
                return candidate.timeInMillis
            }
        }
        return null
    }

    /**
     * Returns true if [schedule] is currently due within `[scheduledMinuteStart, scheduledMinuteStart + windowMs]`
     * and has NOT already been triggered during this minute.
     */
    fun isDueNow(
        schedule: MacroSchedule,
        nowMs: Long,
        windowMs: Long = 59_000L,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Boolean {
        if (!schedule.enabled || schedule.macroId.isBlank()) return false
        if (schedule.repeatMode == ScheduleRepeatMode.ONCE && schedule.lastTriggeredAtMs > 0L) return false

        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = nowMs }
        val isoDay = calendarToIsoDay(cal.get(Calendar.DAY_OF_WEEK))
        if (!matchesDay(schedule, isoDay)) return false

        val slotStart = (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, schedule.hour.coerceIn(0, 23))
            set(Calendar.MINUTE, schedule.minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val elapsed = nowMs - slotStart
        if (elapsed < 0L || elapsed > windowMs.coerceAtLeast(1_000L)) return false
        return schedule.lastTriggeredAtMs < slotStart
    }

    /** Returns the earliest next trigger across all enabled schedules, paired with its schedule. */
    fun findNextScheduled(
        schedules: List<MacroSchedule>,
        nowMs: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Pair<MacroSchedule, Long>? {
        var best: Pair<MacroSchedule, Long>? = null
        for (s in schedules) {
            val next = nextTriggerAtMs(s, nowMs, timeZone) ?: continue
            if (best == null || next < best.second) {
                best = s to next
            }
        }
        return best
    }

    fun formatNextTrigger(
        schedule: MacroSchedule,
        nowMs: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val nextMs = nextTriggerAtMs(schedule, nowMs, timeZone) ?: return "Nonaktif"
        val nowCal = Calendar.getInstance(timeZone).apply { timeInMillis = nowMs }
        val nextCal = Calendar.getInstance(timeZone).apply { timeInMillis = nextMs }
        val timeStr = formatTime(nextCal.get(Calendar.HOUR_OF_DAY), nextCal.get(Calendar.MINUTE))

        val sameYear = nowCal.get(Calendar.YEAR) == nextCal.get(Calendar.YEAR)
        val dayDiff = if (sameYear) {
            nextCal.get(Calendar.DAY_OF_YEAR) - nowCal.get(Calendar.DAY_OF_YEAR)
        } else {
            ((nextMs - nowMs) / 86_400_000L).toInt()
        }
        return when (dayDiff) {
            0 -> "Hari ini $timeStr"
            1 -> "Besok $timeStr"
            else -> "${dayShortLabel(calendarToIsoDay(nextCal.get(Calendar.DAY_OF_WEEK)))} $timeStr"
        }
    }

    fun repeatSummary(schedule: MacroSchedule): String = when (schedule.repeatMode) {
        ScheduleRepeatMode.ONCE -> "Sekali jalan"
        ScheduleRepeatMode.DAILY -> "Setiap hari"
        ScheduleRepeatMode.WEEKDAYS -> "Sen – Jum"
        ScheduleRepeatMode.CUSTOM_DAYS -> {
            val sorted = schedule.daysOfWeek.filter { it in 1..7 }.distinct().sorted()
            if (sorted.isEmpty()) "Belum pilih hari"
            else sorted.joinToString(", ") { dayShortLabel(it) }
        }
    }
}
