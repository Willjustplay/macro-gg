package com.willjustplay.wjustplayer.runtime

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.example.data.AfkAndDeviceGuardConfig
import com.example.data.AfkBlackScreenMode
import com.example.data.DeviceGuardEvaluator
import com.example.data.DeviceHealthSnapshot
import com.example.data.MacroScheduleCalculator
import com.willjustplay.wjustplayer.data.PlayerNotificationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

object PlayerDeviceHealthReader {
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

object PlayerSchedulerManager {
    private const val ACTION_TRIGGER_PLAYER_SCHEDULE = "com.willjustplay.wjustplayer.ACTION_TRIGGER_SCHEDULE"
    private const val ALARM_REQUEST_CODE = 8410

    fun syncAlarms(context: Context) {
        val appCtx = context.applicationContext
        val store = PlayerNotificationStore.get(appCtx)
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

    fun checkAndRunDueSchedules(context: Context): Boolean {
        val appCtx = context.applicationContext
        val store = PlayerNotificationStore.get(appCtx)
        PlayerCoordinator.attach(appCtx)
        val now = System.currentTimeMillis()
        val due = store.schedules.value.firstOrNull { MacroScheduleCalculator.isDueNow(it, now) }
            ?: run {
                syncAlarms(appCtx)
                return false
            }

        store.markScheduleTriggered(due.id, now)
        syncAlarms(appCtx)

        if (PlayerCoordinator.engineState.value == PlayerEngineState.RUNNING ||
            PlayerCoordinator.engineState.value == PlayerEngineState.PAUSED
        ) {
            PlayerSafeLogBus.warn("Jadwal otomatis dilewati karena macro lain sedang berjalan.")
            return false
        }

        if (Settings.canDrawOverlays(appCtx) && !PlayerCoordinator.isOverlayActive.value) {
            runCatching { PlayerOverlayService.start(appCtx) }
        }
        PlayerCoordinator.runScheduledMacro(due)
        return true
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PlayerScheduleReceiver::class.java).apply {
            action = ACTION_TRIGGER_PLAYER_SCHEDULE
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags)
    }
}

class PlayerScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        PlayerSchedulerManager.checkAndRunDueSchedules(context)
    }
}

/** Dual-layer touch-passthrough AFK Black Screen controller for Wjust Player (`:player`). */
class PlayerAfkBlackScreenController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val scope: CoroutineScope,
    private val onWake: () -> Unit,
    private val onStopMacro: () -> Unit
) {
    private var shieldView: View? = null
    private var pillView: LinearLayout? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var detailTextView: TextView? = null
    private var burnInJob: Job? = null

    private var activeConfig: AfkAndDeviceGuardConfig = AfkAndDeviceGuardConfig()
    private var baseX = 0
    private var baseY = 0
    private var lastTapMs = 0L

    val isShowing: Boolean get() = shieldView != null || pillView != null

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun resolveEffectiveMode(mode: AfkBlackScreenMode, macroNeedsVision: Boolean): AfkBlackScreenMode =
        when (mode) {
            AfkBlackScreenMode.SMART_AUTO ->
                if (macroNeedsVision) AfkBlackScreenMode.ULTRA_DIM_VISION_SAFE
                else AfkBlackScreenMode.PURE_BLACK_AMOLED
            else -> mode
        }

    @SuppressLint("ClickableViewAccessibility")
    fun show(
        config: AfkAndDeviceGuardConfig,
        macroName: String,
        macroNeedsVision: Boolean,
        screenWidth: Int,
        screenHeight: Int
    ) {
        dismiss()
        activeConfig = config
        val effMode = resolveEffectiveMode(config.blackScreenMode, macroNeedsVision)
        val brightness = if (config.dimBrightnessToMin) 0.005f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

        val shield = View(context).apply {
            setBackgroundColor(if (effMode == AfkBlackScreenMode.PURE_BLACK_AMOLED) Color.BLACK else Color.TRANSPARENT)
        }
        val shieldFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val shieldParams = WindowManager.LayoutParams(
            if (effMode == AfkBlackScreenMode.PURE_BLACK_AMOLED) WindowManager.LayoutParams.MATCH_PARENT else 1,
            if (effMode == AfkBlackScreenMode.PURE_BLACK_AMOLED) WindowManager.LayoutParams.MATCH_PARENT else 1,
            overlayType(),
            shieldFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            screenBrightness = brightness
        }
        runCatching {
            windowManager.addView(shield, shieldParams)
            shieldView = shield
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6060A14"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val modeLabel = if (effMode == AfkBlackScreenMode.PURE_BLACK_AMOLED) "🌙 Layar Hitam AFK" else "🌙 Redup AFK"
        header.addView(TextView(context).apply {
            text = "$modeLabel • ${macroName.ifBlank { "Wjust Player" }}"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        })
        header.addView(TextView(context).apply {
            text = "☀"
            setTextColor(Color.parseColor("#FDE68A"))
            textSize = 13f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(5) }
            setOnClickListener { onWake() }
        })
        header.addView(TextView(context).apply {
            text = "■"
            setTextColor(Color.parseColor("#FCA5A5"))
            textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3F1219"))
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener {
                onStopMacro()
                onWake()
            }
        })
        root.addView(header)

        val detailTv = TextView(context).apply {
            text = "Loop #0 • Memantau perangkat..."
            setTextColor(Color.parseColor("#64748B"))
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        }
        detailTextView = detailTv
        root.addView(detailTv)

        root.addView(TextView(context).apply {
            text = if (config.doubleTapToUnlock) "Ketuk 2× di sini atau ☀ untuk bangun" else "Ketuk di sini atau ☀ untuk bangun"
            setTextColor(Color.parseColor("#475569"))
            textSize = 9.5f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        })

        baseX = dp(14)
        baseY = if (effMode == AfkBlackScreenMode.PURE_BLACK_AMOLED) (screenHeight * 0.42f).toInt().coerceAtLeast(dp(60)) else dp(38)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = baseX
            y = baseY
            screenBrightness = brightness
        }

        var initX = 0; var initY = 0; var tx = 0f; var ty = 0f; var dragged = false
        root.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { initX = lp.x; initY = lp.y; tx = ev.rawX; ty = ev.rawY; dragged = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - tx).toInt(); val dy = (ev.rawY - ty).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        dragged = true
                        lp.x = (initX + dx).coerceIn(0, (screenWidth - dp(120)).coerceAtLeast(0))
                        lp.y = (initY + dy).coerceIn(dp(18), (screenHeight - dp(80)).coerceAtLeast(dp(20)))
                        baseX = lp.x; baseY = lp.y
                        runCatching { windowManager.updateViewLayout(root, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        val now = System.currentTimeMillis()
                        if (!activeConfig.doubleTapToUnlock || (now - lastTapMs) <= 450L) {
                            lastTapMs = 0L; onWake()
                        } else lastTapMs = now
                    }
                    true
                }
                else -> false
            }
        }
        runCatching {
            windowManager.addView(root, lp)
            pillView = root
            pillParams = lp
        }

        if (config.amoledBurnInShift) {
            burnInJob?.cancel()
            burnInJob = scope.launch {
                val offsets = listOf(0 to 0, 14 to 10, -10 to 18, 12 to -12, -14 to -8)
                var step = 0
                while (isActive && pillView != null) {
                    delay(45_000L)
                    step = (step + 1) % offsets.size
                    val (ox, oy) = offsets[step]
                    val v = pillView ?: break
                    val p = pillParams ?: break
                    p.x = (baseX + dp(ox)).coerceIn(dp(6), (screenWidth - dp(140)).coerceAtLeast(dp(6)))
                    p.y = (baseY + dp(oy)).coerceIn(dp(24), (screenHeight - dp(90)).coerceAtLeast(dp(24)))
                    runCatching { windowManager.updateViewLayout(v, p) }
                }
            }
        }
    }

    fun updateHud(loop: Int, health: DeviceHealthSnapshot?, thermalPaused: Boolean, humanBreakSec: Int) {
        val tv = detailTextView ?: return
        val batText = if (health != null) {
            val chg = if (health.isCharging) "⚡" else "🔋"
            "$chg${health.batteryPercent}% • 🌡${DeviceGuardEvaluator.formatTemp(health.tempCelsius)}"
        } else "Memantau perangkat..."
        val extra = when {
            thermalPaused -> " • ❄ Mendinginkan HP..."
            humanBreakSec > 0 -> " • ☕ Break ${humanBreakSec}d"
            else -> ""
        }
        tv.text = "Loop #$loop • $batText$extra"
        tv.setTextColor(if (thermalPaused) Color.parseColor("#38BDF8") else Color.parseColor("#64748B"))
    }

    fun dismiss() {
        burnInJob?.cancel(); burnInJob = null
        pillView?.let { runCatching { windowManager.removeView(it) } }
        shieldView?.let { runCatching { windowManager.removeView(it) } }
        pillView = null; pillParams = null; shieldView = null; detailTextView = null; lastTapMs = 0L
    }
}
