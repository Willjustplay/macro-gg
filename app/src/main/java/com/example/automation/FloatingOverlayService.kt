package com.example.automation

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.data.MacroStep
import com.example.data.MacroStepType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controlBarView: View? = null
    private var controlBarParams: WindowManager.LayoutParams? = null
    private var playPauseBtn: TextView? = null
    private var statusDotView: View? = null
    private var targetBadgeText: TextView? = null

    private var logOverlayView: View? = null
    private var logOverlayParams: WindowManager.LayoutParams? = null
    private var logTextView: TextView? = null
    private var logScrollView: ScrollView? = null
    private var isLogVisible = false

    private var colorSpyView: View? = null
    private var colorSpyParams: WindowManager.LayoutParams? = null
    private var colorSpySwatch: View? = null
    private var colorSpyLabel: TextView? = null
    private var isColorSpyVisible = false

    private val markerViews = mutableListOf<Pair<View, WindowManager.LayoutParams>>()
    private var screenWidth = 1080
    private var screenHeight = 2400

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels.coerceAtLeast(1)
        screenHeight = metrics.heightPixels.coerceAtLeast(1)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            ExecutionLogBus.error("Izin Display Over Other Apps belum diberikan.")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()
        if (controlBarView == null) {
            showControlBar()
            observeEngineAndLogs()
            AutomationCoordinator.setOverlayActive(true)
            ExecutionLogBus.info("Floating Overlay TapLua aktif.")
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_overlay),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notif_overlay_title))
            .setContentText(getString(R.string.notif_overlay_text))
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun showControlBar() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6101B22"))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.parseColor("#4CDADA"))
            }
            elevation = dp(8).toFloat()
        }

        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginEnd = dp(6)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981"))
            }
        }
        statusDotView = dot
        container.addView(dot)

        val badge = TextView(this).apply {
            text = "TapLua"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }
        targetBadgeText = badge
        container.addView(badge)

        val btnPlay = makeControlButton("▶", "#006A6A") {
            AutomationCoordinator.togglePlayPause(screenWidth, screenHeight)
        }
        playPauseBtn = btnPlay
        container.addView(btnPlay)

        container.addView(makeControlButton("■", "#7F1D1D") {
            AutomationCoordinator.stop()
        })

        container.addView(makeControlButton("+", "#1E3A5F") {
            AutomationCoordinator.addQuickClickMarker(screenWidth / 2, screenHeight / 2)
        })

        container.addView(makeControlButton("◎", "#3B2F63") {
            toggleColorSpy()
        })

        container.addView(makeControlButton("LOG", "#1F2E36") {
            toggleLogConsole()
        })

        container.addView(makeControlButton("✕", "#2B343B") {
            AutomationCoordinator.stop()
            stopSelf()
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }

        attachDragBehavior(container, params)
        windowManager.addView(container, params)
        controlBarView = container
        controlBarParams = params
    }

    private fun makeControlButton(label: String, bgHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = if (label.length > 1) 10f else 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = dp(34)
            minHeight = dp(34)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = dp(17).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(34)
            ).apply {
                marginEnd = dp(5)
            }
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragBehavior(
        view: View,
        params: WindowManager.LayoutParams,
        onMoved: ((Int, Int) -> Unit)? = null
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 4 || abs(dy) > 4) {
                        moved = true
                        params.x = (initialX + dx).coerceIn(0, screenWidth - dp(40))
                        params.y = (initialY + dy).coerceIn(0, screenHeight - dp(40))
                        try {
                            windowManager.updateViewLayout(view, params)
                            onMoved?.invoke(params.x, params.y)
                        } catch (_: Exception) {}
                    }
                    moved
                }
                else -> false
            }
        }
    }

    private fun observeEngineAndLogs() {
        serviceScope.launch {
            combine(
                AutomationCoordinator.engineState,
                AutomationCoordinator.activeTargetName,
                AutomationCoordinator.activeMode,
                AutomationCoordinator.activeMacroSteps
            ) { state, targetName, mode, steps ->
                OverlaySnapshot(state, targetName, mode, steps)
            }.collect { snap ->
                updateControlBarUi(snap.state, snap.targetName)
                if (snap.mode == AutomationMode.MACRO) {
                    rebuildTargetMarkers(snap.steps, isRunning = snap.state == EngineState.RUNNING)
                } else {
                    clearTargetMarkers()
                }
            }
        }

        serviceScope.launch {
            ExecutionLogBus.logs.collect { entries ->
                if (isLogVisible && logTextView != null) {
                    val text = entries.takeLast(40).joinToString("\n") {
                        "[${it.timestamp.substring(0, 8)} ${it.level.tag}] ${it.message}"
                    }.ifBlank { "Menunggu log eksekusi..." }
                    logTextView?.text = text
                    logScrollView?.post { logScrollView?.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }

    private fun updateControlBarUi(state: EngineState, targetName: String) {
        val shortName = targetName.take(12)
        targetBadgeText?.text = shortName
        val (playSymbol, dotColor) = when (state) {
            EngineState.RUNNING -> "❚❚" to "#10B981"
            EngineState.PAUSED -> "▶" to "#F59E0B"
            EngineState.ERROR -> "▶" to "#EF4444"
            EngineState.IDLE -> "▶" to "#4CDADA"
        }
        playPauseBtn?.text = playSymbol
        (statusDotView?.background as? GradientDrawable)?.setColor(Color.parseColor(dotColor))
    }

    private fun rebuildTargetMarkers(steps: List<MacroStep>, isRunning: Boolean) {
        clearTargetMarkers()
        val markerSize = dp(40)
        val half = markerSize / 2
        val baseFlags = if (isRunning) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        steps.forEachIndexed { idx, step ->
            if (step.type == MacroStepType.WAIT) return@forEachIndexed
            val badgeColor = when (step.type) {
                MacroStepType.CLICK -> "#CC006A6A"
                MacroStepType.LONG_PRESS -> "#CC4B607C"
                MacroStepType.SWIPE -> "#CC0E7490"
                MacroStepType.WAIT_COLOR -> "#CC7C3AED"
                MacroStepType.WAIT -> "#CC475569"
            }
            val labelText = if (step.type == MacroStepType.SWIPE) "${idx + 1}A" else "${idx + 1}"
            val startView = buildMarkerView(labelText, badgeColor, isRunning)
            val startParams = WindowManager.LayoutParams(
                markerSize,
                markerSize,
                overlayType(),
                baseFlags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (step.x - half).coerceIn(0, screenWidth - markerSize)
                y = (step.y - half).coerceIn(0, screenHeight - markerSize)
            }
            if (!isRunning) {
                attachMarkerDrag(startView, startParams, half) { cx, cy ->
                    AutomationCoordinator.updateMarkerPosition(step.stepId, cx, cy, isEndNode = false)
                }
            }
            windowManager.addView(startView, startParams)
            markerViews.add(startView to startParams)

            if (step.type == MacroStepType.SWIPE) {
                val endView = buildMarkerView("${idx + 1}B", "#CC0284C7", isRunning)
                val endParams = WindowManager.LayoutParams(
                    markerSize,
                    markerSize,
                    overlayType(),
                    baseFlags,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = (step.endX - half).coerceIn(0, screenWidth - markerSize)
                    y = (step.endY - half).coerceIn(0, screenHeight - markerSize)
                }
                if (!isRunning) {
                    attachMarkerDrag(endView, endParams, half) { cx, cy ->
                        AutomationCoordinator.updateMarkerPosition(step.stepId, cx, cy, isEndNode = true)
                    }
                }
                windowManager.addView(endView, endParams)
                markerViews.add(endView to endParams)
            }
        }
    }

    private fun buildMarkerView(label: String, fillHex: String, isRunning: Boolean): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            alpha = if (isRunning) 0.45f else 0.92f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(fillHex))
                setStroke(dp(2), Color.parseColor("#9CF1F0"))
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachMarkerDrag(
        view: View,
        params: WindowManager.LayoutParams,
        half: Int,
        onCommit: (Int, Int) -> Unit
    ) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (initialX + (event.rawX - touchX).toInt()).coerceIn(0, screenWidth - half * 2)
                    params.y = (initialY + (event.rawY - touchY).toInt()).coerceIn(0, screenHeight - half * 2)
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    onCommit(params.x + half, params.y + half)
                    true
                }
                else -> false
            }
        }
    }

    private fun clearTargetMarkers() {
        markerViews.forEach { (view, _) ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {}
        }
        markerViews.clear()
    }

    private fun toggleLogConsole() {
        if (isLogVisible) {
            removeLogConsole()
        } else {
            showLogConsole()
        }
    }

    private fun showLogConsole() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DC0E151B"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#334CDADA"))
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "TapLua Live Console"
            setTextColor(Color.parseColor("#4CDADA"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val clearBtn = TextView(this).apply {
            text = "Bersihkan"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 10f
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener { ExecutionLogBus.clear() }
        }
        header.addView(title)
        header.addView(clearBtn)
        root.addView(header)

        val tv = TextView(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            text = ExecutionLogBus.logs.value.takeLast(40).joinToString("\n") {
                "[${it.timestamp.substring(0, 8)} ${it.level.tag}] ${it.message}"
            }.ifBlank { "Menunggu log eksekusi..." }
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(260), dp(130)).apply {
                topMargin = dp(4)
            }
            addView(tv)
        }
        root.addView(scroll)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(200)
        }

        attachDragBehavior(root, params)
        windowManager.addView(root, params)
        logOverlayView = root
        logOverlayParams = params
        logTextView = tv
        logScrollView = scroll
        isLogVisible = true
    }

    private fun removeLogConsole() {
        logOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        logOverlayView = null
        logOverlayParams = null
        logTextView = null
        logScrollView = null
        isLogVisible = false
    }

    private fun toggleColorSpy() {
        if (isColorSpyVisible) {
            removeColorSpy()
        } else {
            showColorSpy()
        }
    }

    private fun showColorSpy() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EB111827"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(2), Color.parseColor("#F59E0B"))
            }
        }

        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(6) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2DD4BF"))
                setStroke(dp(1), Color.WHITE)
            }
        }
        colorSpySwatch = swatch
        root.addView(swatch)

        val label = TextView(this).apply {
            text = "Geser untuk bidik warna"
            setTextColor(Color.WHITE)
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }
        colorSpyLabel = label
        root.addView(label)

        val pickBtn = makeControlButton("Ambil", "#006A6A") {
            val params = colorSpyParams ?: return@makeControlButton
            val px = params.x
            val py = params.y
            val hex = ScreenColorSamplerService.samplePixelHex(px, py) ?: "#000000"
            AutomationCoordinator.publishPickedPixel(px, py, hex)
            val clip = getSystemService(ClipboardManager::class.java)
            clip?.setPrimaryClip(ClipData.newPlainText("TapLua Color", "$px, $py, \"$hex\""))
            Toast.makeText(this, "Disalin: ($px,$py) $hex", Toast.LENGTH_SHORT).show()
        }
        root.addView(pickBtn)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth / 3
            y = screenHeight / 3
        }

        attachDragBehavior(root, params) { nx, ny ->
            val sampled = ScreenColorSamplerService.samplePixelHex(nx, ny)
            if (sampled != null) {
                colorSpyLabel?.text = "($nx,$ny) $sampled"
                try {
                    (colorSpySwatch?.background as? GradientDrawable)?.setColor(Color.parseColor(sampled))
                } catch (_: Exception) {}
            } else {
                colorSpyLabel?.text = "($nx,$ny) Aktifkan Color Sampler"
            }
        }

        windowManager.addView(root, params)
        colorSpyView = root
        colorSpyParams = params
        isColorSpyVisible = true
    }

    private fun removeColorSpy() {
        colorSpyView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        colorSpyView = null
        colorSpyParams = null
        colorSpySwatch = null
        colorSpyLabel = null
        isColorSpyVisible = false
    }

    override fun onDestroy() {
        AutomationCoordinator.setOverlayActive(false)
        clearTargetMarkers()
        removeLogConsole()
        removeColorSpy()
        controlBarView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        controlBarView = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private data class OverlaySnapshot(
        val state: EngineState,
        val targetName: String,
        val mode: AutomationMode,
        val steps: List<MacroStep>
    )

    companion object {
        private const val CHANNEL_ID = "taplua_floating_overlay"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_START = "com.example.action.START_OVERLAY"
        const val ACTION_STOP = "com.example.action.STOP_OVERLAY"

        fun start(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
