package com.example.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.example.data.DeviceHealthSnapshot
import com.example.data.LocalStore
import com.example.data.MacroScheduleCalculator

/** Zero-permission reader for device battery level, charging state, and battery temperature. */
object DeviceHealthReader {
    fun read(context: Context?): DeviceHealthSnapshot? {
        val appCtx = context?.applicationContext ?: return null
        return runCatching {
            val intent = appCtx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val pct = if (level >= 0) ((level * 100) / scale).coerceIn(0, 100) else 100
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val charging = plugged > 0 ||
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            val tempC = if (tempTenths > 0) tempTenths / 10.0f else 0f
            DeviceHealthSnapshot(
                batteryPercent = pct,
                isCharging = charging,
                tempCelsius = tempC,
                sampledAtMs = System.currentTimeMillis()
            )
        }.getOrNull()
    }
}

/**
 * Coordinates scheduled automatic macro launches via Android [AlarmManager] + in-service minute checks.
 * Never throws SecurityException on Android 12+ (gracefully falls back from exact to inexact allow-while-idle).
 */
object MacroSchedulerManager {

    private const val ACTION_TRIGGER_SCHEDULE = "com.example.wjustmacro.ACTION_TRIGGER_SCHEDULE"
    private const val ALARM_REQUEST_CODE = 7410

    fun syncAlarms(context: Context) {
        val appCtx = context.applicationContext
        val store = LocalStore.get(appCtx)
        val alarmMgr = appCtx.getSystemService(AlarmManager::class.java) ?: return
        val pi = buildPendingIntent(appCtx)

        val now = System.currentTimeMillis()
        val nextPair = MacroScheduleCalculator.findNextScheduled(store.schedules.value, now)
        if (nextPair == null) {
            runCatching { alarmMgr.cancel(pi) }
            return
        }

        val (_, triggerAtMs) = nextPair
        runCatching {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmMgr.canScheduleExactAlarms()
            if (canExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmMgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } else {
                alarmMgr.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            }
        }
    }

    /**
     * Checks all enabled schedules against current wall-clock time; executes the first due schedule
     * if engine is IDLE, marks it triggered to prevent duplicate fires, and schedules the next alarm.
     */
    fun checkAndRunDueSchedules(context: Context): Boolean {
        val appCtx = context.applicationContext
        val store = LocalStore.get(appCtx)
        AutomationCoordinator.attachStore(appCtx)
        val now = System.currentTimeMillis()
        val due = store.schedules.value.firstOrNull { MacroScheduleCalculator.isDueNow(it, now) }
            ?: run {
                syncAlarms(appCtx)
                return false
            }

        // Prevent duplicate fire inside the same minute right away
        store.markScheduleTriggered(due.id, now)
        syncAlarms(appCtx)

        if (AutomationCoordinator.engineState.value == EngineState.RUNNING ||
            AutomationCoordinator.engineState.value == EngineState.PAUSED
        ) {
            ExecutionLogBus.warn(
                "⏰ Jadwal '${due.label.ifBlank { due.macroName }}' dilewati karena ada macro lain yang sedang berjalan."
            )
            return false
        }

        if (Settings.canDrawOverlays(appCtx) && !AutomationCoordinator.isOverlayActive.value) {
            runCatching { FloatingOverlayService.start(appCtx) }
        }
        AutomationCoordinator.runScheduledMacro(due)
        return true
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MacroScheduleReceiver::class.java).apply {
            action = ACTION_TRIGGER_SCHEDULE
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags)
    }
}

class MacroScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        MacroSchedulerManager.checkAndRunDueSchedules(context)
    }
}
