package com.example

import com.example.data.AfkAndDeviceGuardConfig
import com.example.data.AfkBlackScreenMode
import com.example.data.DeviceGuardAction
import com.example.data.DeviceGuardDecision
import com.example.data.DeviceGuardEvaluator
import com.example.data.DeviceHealthSnapshot
import com.example.data.Macro
import com.example.data.MacroSchedule
import com.example.data.MacroScheduleCalculator
import com.example.data.ScheduleRepeatMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AfkGuardAndSchedulerTest {

    private val testJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    private val utc = TimeZone.getTimeZone("UTC")

    private fun utcMs(year: Int, month1Based: Int, day: Int, hour: Int, minute: Int, sec: Int = 0): Long {
        return Calendar.getInstance(utc).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month1Based - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, sec)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Test
    fun batteryGuardStopsOnlyWhenUnpluggedAndAtOrBelowThreshold() {
        val disabledCfg = AfkAndDeviceGuardConfig(batteryGuardEnabled = false, minBatteryPercent = 20)
        assertEquals(
            DeviceGuardDecision.OK,
            DeviceGuardEvaluator.evaluate(disabledCfg, DeviceHealthSnapshot(5, false, 35f), false)
        )

        val enabledCfg = AfkAndDeviceGuardConfig(batteryGuardEnabled = true, minBatteryPercent = 15)
        // Charging at 10% should NOT stop
        assertEquals(
            DeviceGuardDecision.OK,
            DeviceGuardEvaluator.evaluate(enabledCfg, DeviceHealthSnapshot(10, true, 36f), false)
        )
        // Unplugged at 16% -> safe
        assertEquals(
            DeviceGuardDecision.OK,
            DeviceGuardEvaluator.evaluate(enabledCfg, DeviceHealthSnapshot(16, false, 36f), false)
        )
        // Unplugged at 15% and 8% -> STOP_LOW_BATTERY
        assertEquals(
            DeviceGuardDecision.STOP_LOW_BATTERY,
            DeviceGuardEvaluator.evaluate(enabledCfg, DeviceHealthSnapshot(15, false, 36f), false)
        )
        assertEquals(
            DeviceGuardDecision.STOP_LOW_BATTERY,
            DeviceGuardEvaluator.evaluate(enabledCfg, DeviceHealthSnapshot(8, false, 36f), false)
        )
    }

    @Test
    fun thermalGuardPausesWithHysteresisResumesWhenCooledAndRespectsStopMode() {
        val pauseCfg = AfkAndDeviceGuardConfig(
            thermalGuardEnabled = true,
            maxTempCelsius = 44.0f,
            resumeTempDropCelsius = 3.0f,
            thermalAction = DeviceGuardAction.PAUSE_AND_COOL
        )

        // Below threshold -> OK
        assertEquals(
            DeviceGuardDecision.OK,
            DeviceGuardEvaluator.evaluate(pauseCfg, DeviceHealthSnapshot(80, false, 43.5f), false)
        )
        // Reaches 44.0°C -> PAUSE_OVERHEAT
        assertEquals(
            DeviceGuardDecision.PAUSE_OVERHEAT,
            DeviceGuardEvaluator.evaluate(pauseCfg, DeviceHealthSnapshot(80, false, 44.0f), false)
        )
        // While paused, still 42.0°C (> 41.0°C resume threshold) -> STAY_THERMAL_PAUSED
        assertEquals(
            DeviceGuardDecision.STAY_THERMAL_PAUSED,
            DeviceGuardEvaluator.evaluate(pauseCfg, DeviceHealthSnapshot(80, false, 42.0f), true)
        )
        // Cooled to 41.0°C -> RESUME_COOLED
        assertEquals(
            DeviceGuardDecision.RESUME_COOLED,
            DeviceGuardEvaluator.evaluate(pauseCfg, DeviceHealthSnapshot(80, false, 41.0f), true)
        )

        // STOP_MACRO mode stops immediately at 44.5°C
        val stopCfg = pauseCfg.copy(thermalAction = DeviceGuardAction.STOP_MACRO)
        assertEquals(
            DeviceGuardDecision.STOP_OVERHEAT,
            DeviceGuardEvaluator.evaluate(stopCfg, DeviceHealthSnapshot(80, true, 44.5f), false)
        )

        // Turning thermal guard OFF while paused triggers immediate resume
        assertEquals(
            DeviceGuardDecision.RESUME_COOLED,
            DeviceGuardEvaluator.evaluate(pauseCfg.copy(thermalGuardEnabled = false), DeviceHealthSnapshot(80, false, 45.0f), true)
        )

        // Battery low takes priority over thermal pause
        val bothCfg = pauseCfg.copy(batteryGuardEnabled = true, minBatteryPercent = 15)
        assertEquals(
            DeviceGuardDecision.STOP_LOW_BATTERY,
            DeviceGuardEvaluator.evaluate(bothCfg, DeviceHealthSnapshot(12, false, 45.0f), true)
        )
    }

    @Test
    fun macroScheduleCalculatorComputesNextTriggerForOnceDailyWeekdaysAndCustomDays() {
        // Friday, 2026-11-27 10:00:00 UTC (ISO day 5)
        val friday10am = utcMs(2026, 11, 27, 10, 0, 0)

        // 1. DAILY later today (14:30) -> same day
        val dailyLater = MacroSchedule(macroId = "m1", macroName = "Farm", hour = 14, minute = 30, repeatMode = ScheduleRepeatMode.DAILY)
        assertEquals(
            utcMs(2026, 11, 27, 14, 30, 0),
            MacroScheduleCalculator.nextTriggerAtMs(dailyLater, friday10am, utc)
        )
        assertEquals("Hari ini 14:30", MacroScheduleCalculator.formatNextTrigger(dailyLater, friday10am, utc))

        // 2. DAILY earlier today (08:00) -> tomorrow Saturday 08:00
        val dailyEarlier = dailyLater.copy(hour = 8, minute = 0)
        assertEquals(
            utcMs(2026, 11, 28, 8, 0, 0),
            MacroScheduleCalculator.nextTriggerAtMs(dailyEarlier, friday10am, utc)
        )
        assertEquals("Besok 08:00", MacroScheduleCalculator.formatNextTrigger(dailyEarlier, friday10am, utc))

        // 3. WEEKDAYS earlier on Friday (08:00) -> skips Sat/Sun, picks Monday 2026-11-30 08:00!
        val weekdays = dailyEarlier.copy(repeatMode = ScheduleRepeatMode.WEEKDAYS)
        assertEquals(
            utcMs(2026, 11, 30, 8, 0, 0),
            MacroScheduleCalculator.nextTriggerAtMs(weekdays, friday10am, utc)
        )
        assertEquals("Sen 08:00", MacroScheduleCalculator.formatNextTrigger(weekdays, friday10am, utc))

        // 4. CUSTOM_DAYS (Wednesday = 3 only) -> picks Wednesday 2026-12-02 21:15
        val customWed = MacroSchedule(
            macroId = "m1",
            macroName = "Boss",
            hour = 21,
            minute = 15,
            repeatMode = ScheduleRepeatMode.CUSTOM_DAYS,
            daysOfWeek = listOf(3)
        )
        assertEquals(
            utcMs(2026, 12, 2, 21, 15, 0),
            MacroScheduleCalculator.nextTriggerAtMs(customWed, friday10am, utc)
        )

        // 5. ONCE before & after firing
        val once = MacroSchedule(macroId = "m1", hour = 11, minute = 0, repeatMode = ScheduleRepeatMode.ONCE)
        assertEquals(utcMs(2026, 11, 27, 11, 0, 0), MacroScheduleCalculator.nextTriggerAtMs(once, friday10am, utc))
        assertNull(MacroScheduleCalculator.nextTriggerAtMs(once.copy(lastTriggeredAtMs = friday10am), friday10am, utc))

        // 6. Disabled or blank macroId -> null
        assertNull(MacroScheduleCalculator.nextTriggerAtMs(dailyLater.copy(enabled = false), friday10am, utc))
        assertNull(MacroScheduleCalculator.nextTriggerAtMs(dailyLater.copy(macroId = ""), friday10am, utc))
    }

    @Test
    fun macroScheduleCalculatorDueWindowPreventsDuplicateFiresWithinSameMinute() {
        val sched = MacroSchedule(
            id = "s1",
            macroId = "m1",
            macroName = "Daily",
            hour = 8,
            minute = 0,
            repeatMode = ScheduleRepeatMode.DAILY
        )
        val before = utcMs(2026, 11, 27, 7, 59, 59)
        val at15s = utcMs(2026, 11, 27, 8, 0, 15)
        val at35s = utcMs(2026, 11, 27, 8, 0, 35)
        val pastWindow = utcMs(2026, 11, 27, 8, 1, 5)

        assertFalse(MacroScheduleCalculator.isDueNow(sched, before, timeZone = utc))
        assertTrue(MacroScheduleCalculator.isDueNow(sched, at15s, timeZone = utc))

        // Once marked triggered at 08:00:15, checking again at 08:00:35 MUST return false!
        val triggered = sched.copy(lastTriggeredAtMs = at15s)
        assertFalse(MacroScheduleCalculator.isDueNow(triggered, at35s, timeZone = utc))
        assertFalse(MacroScheduleCalculator.isDueNow(sched, pastWindow, timeZone = utc))

        // Next day at 08:00:10 -> due again!
        val nextDay = utcMs(2026, 11, 28, 8, 0, 10)
        assertTrue(MacroScheduleCalculator.isDueNow(triggered, nextDay, timeZone = utc))

        // findNextScheduled picks earliest
        val sLate = MacroSchedule(id = "late", macroId = "m1", hour = 20, minute = 0)
        val sEarly = MacroSchedule(id = "early", macroId = "m2", hour = 12, minute = 30)
        val best = MacroScheduleCalculator.findNextScheduled(listOf(sLate, sEarly), at15s, utc)
        assertNotNull(best)
        assertEquals("early", best!!.first.id)
    }

    @Test
    fun afkGuardAndScheduleModelsRoundTripJsonAndStayOutsideMacroExport() {
        val guardCfg = AfkAndDeviceGuardConfig(
            blackScreenMode = AfkBlackScreenMode.PURE_BLACK_AMOLED,
            autoBlackScreenAfterSec = 30,
            dimBrightnessToMin = true,
            amoledBurnInShift = true,
            doubleTapToUnlock = true,
            batteryGuardEnabled = true,
            minBatteryPercent = 20,
            thermalGuardEnabled = true,
            maxTempCelsius = 43.5f,
            resumeTempDropCelsius = 4.0f,
            thermalAction = DeviceGuardAction.PAUSE_AND_COOL,
            notifyOnGuardTrigger = true
        )
        assertEquals(guardCfg, testJson.decodeFromString<AfkAndDeviceGuardConfig>(testJson.encodeToString(guardCfg)))

        val sched = MacroSchedule(
            label = "Boss Malam",
            macroId = "m_boss",
            macroName = "Boss Macro",
            hour = 22,
            minute = 15,
            repeatMode = ScheduleRepeatMode.CUSTOM_DAYS,
            daysOfWeek = listOf(1, 3, 5),
            launchAppPackage = "com.supercell.clashofclans",
            launchAppLabel = "Clash of Clans",
            launchWaitSec = 10,
            stopAfterMinutes = 90,
            enableBlackScreenOnStart = true
        )
        assertEquals(sched, testJson.decodeFromString<MacroSchedule>(testJson.encodeToString(sched)))

        // Device-only guarantee: Macro JSON must never leak local schedules or device guard configs
        val encodedMacro = testJson.encodeToString(Macro(name = "Clean Macro"))
        assertFalse(encodedMacro.contains("minBatteryPercent"))
        assertFalse(encodedMacro.contains("maxTempCelsius"))
        assertFalse(encodedMacro.contains("launchAppPackage"))
    }
}
