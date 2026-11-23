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
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.Action
import com.example.data.ClickAction
import com.example.data.ColorAction
import com.example.data.LocalStore
import com.example.data.LongPressAction
import com.example.data.Macro
import com.example.data.RecordedGesture
import com.example.data.SwipeAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Compact Macrorify-style Floating Control Bar & Overlay Tools:
 * - Slim collapsible control bar with quick +Click button
 * - Visible swipe lines & directional arrows (live-updated while dragging markers)
 * - Compact 2-button (Batal / Simpan) screenshot cropper
 * - Touch recorder, Color Spy, live match highlight box, and live log
 */
class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var store: LocalStore
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var controlBarView: LinearLayout? = null
    private var controlBarParams: WindowManager.LayoutParams? = null
    private var expandedToolsRow: LinearLayout? = null
    private var collapseBtn: TextView? = null
    private var playPauseBtn: TextView? = null
    private var statusDotView: View? = null
    private var isCollapsed = false

    private var logOverlayView: View? = null
    private var logTextView: TextView? = null
    private var logScrollView: ScrollView? = null
    private var isLogVisible = false

    private var colorSpyView: View? = null
    private var colorSpyParams: WindowManager.LayoutParams? = null
    private var colorSpySwatch: View? = null
    private var colorSpyLabel: TextView? = null
    private var isColorSpyVisible = false

    private var cropOverlayView: View? = null
    private var cropScreenshot: Bitmap? = null

    private var recorderOverlayView: View? = null
    private var recorderParams: WindowManager.LayoutParams? = null

    private var markerEditorView: View? = null

    private var matchBoxView: MatchHighlightView? = null
    private var swipePathView: SwipePathOverlayView? = null
    private val markerViews = mutableListOf<Pair<View, WindowManager.LayoutParams>>()

    private var screenWidth = 1080
    private var screenHeight = 2400

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        store = LocalStore.get(this)
        AutomationCoordinator.attachStore(this)
        refreshScreenSize()
    }

    private fun refreshScreenSize() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels.coerceAtLeast(1)
        screenHeight = metrics.heightPixels.coerceAtLeast(1)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_OPEN_CROP -> {
                val addToMacro = intent.getBooleanExtra(EXTRA_ADD_TO_MACRO, true)
                ensureOverlayRunning()
                mainHandler.postDelayed({ launchScreenshotCrop(addToMacro) }, 200L)
                return START_STICKY
            }
            ACTION_OPEN_RECORDER -> {
                ensureOverlayRunning()
                mainHandler.postDelayed({ openTouchRecorder() }, 200L)
                return START_STICKY
            }
        }
        ensureOverlayRunning()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep floating overlay running when user swipes MacroGG away from Recents.
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshScreenSize()
        // Keep floating bar inside the new screen bounds after portrait <-> landscape rotation
        val bar = controlBarView
        val params = controlBarParams
        if (bar != null && params != null) {
            params.x = params.x.coerceIn(0, (screenWidth - dp(40)).coerceAtLeast(0))
            params.y = params.y.coerceIn(0, (screenHeight - dp(40)).coerceAtLeast(0))
            try { windowManager.updateViewLayout(bar, params) } catch (_: Throwable) {}
        }
        val spy = colorSpyView
        val spyParams = colorSpyParams
        if (spy != null && spyParams != null) {
            spyParams.x = spyParams.x.coerceIn(0, (screenWidth - dp(40)).coerceAtLeast(0))
            spyParams.y = spyParams.y.coerceIn(0, (screenHeight - dp(40)).coerceAtLeast(0))
            try { windowManager.updateViewLayout(spy, spyParams) } catch (_: Throwable) {}
        }
    }

    private fun ensureOverlayRunning() {
        // Promote to foreground immediately to prevent ForegroundServiceDidNotStartInTimeException
        if (!startForegroundNotificationSafe()) {
            stopSelf()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            ExecutionLogBus.error("Izin Tampilkan di Atas Aplikasi belum diberikan.")
            stopSelf()
            return
        }
        if (controlBarView == null) {
            showControlBar()
            if (controlBarView != null) {
                observeEngineAndLogs()
                AutomationCoordinator.setOverlayActive(true)
                ExecutionLogBus.info("Floating Overlay MacroGG aktif.")
            } else {
                stopSelf()
            }
        }
    }

    private fun startForegroundNotificationSafe(): Boolean {
        return try {
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
            true
        } catch (t: Throwable) {
            ExecutionLogBus.error("Gagal memulai foreground service overlay: ${t.message}")
            false
        }
    }

    private fun safeAddView(view: View, params: WindowManager.LayoutParams): Boolean {
        return try {
            windowManager.addView(view, params)
            true
        } catch (t: Throwable) {
            ExecutionLogBus.error("Gagal menampilkan overlay: ${t.message}")
            false
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

    // ---- Compact Floating Control Bar ----

    @SuppressLint("ClickableViewAccessibility")
    private fun showControlBar() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E80F172A"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#2DD4BF"))
            }
            elevation = dp(6).toFloat()
        }

        // Row 1 (always visible, ultra-compact): [•] [▶] [■] [+] [▾] [✕]
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                marginStart = dp(2)
                marginEnd = dp(4)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2DD4BF"))
            }
        }
        statusDotView = dot
        topRow.addView(dot)

        val btnPlay = makeCompactButton("▶", "#0D9488") {
            handlePlayPauseClick()
        }
        playPauseBtn = btnPlay
        topRow.addView(btnPlay)

        topRow.addView(makeCompactButton("■", "#991B1B") {
            AutomationCoordinator.stop()
        })

        // Quick Add Click marker button right on the small top bar
        topRow.addView(makeCompactButton("+", "#1D4ED8") {
            refreshScreenSize()
            AutomationCoordinator.addQuickClickMarker(screenWidth / 2, screenHeight / 2)
        })

        val btnCollapse = makeCompactButton("▾", "#334155") {
            toggleCollapse()
        }
        collapseBtn = btnCollapse
        topRow.addView(btnCollapse)

        topRow.addView(makeCompactButton("✕", "#1E293B", marginEndDp = 0) {
            AutomationCoordinator.stop()
            stopSelf()
        })

        root.addView(topRow)

        // Row 2 (collapsible compact tool icons): [⇄] [✂] [●] [◎] [≡] [⌂]
        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }

        toolsRow.addView(makeCompactButton("⇄", "#0369A1") {
            refreshScreenSize()
            AutomationCoordinator.addQuickSwipeMarker(
                screenWidth / 2,
                (screenHeight * 0.62f).toInt(),
                screenWidth / 2,
                (screenHeight * 0.38f).toInt()
            )
        })

        toolsRow.addView(makeCompactButton("✂", "#0F766E") {
            launchScreenshotCrop(addToMacro = true)
        })

        toolsRow.addView(makeCompactButton("●", "#B91C1C") {
            openTouchRecorder()
        })

        toolsRow.addView(makeCompactButton("◎", "#6D28D9") {
            toggleColorSpy()
        })

        toolsRow.addView(makeCompactButton("≡", "#334155") {
            toggleLogConsole()
        })

        toolsRow.addView(makeCompactButton("⌂", "#475569", marginEndDp = 0) {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        })

        expandedToolsRow = toolsRow
        root.addView(toolsRow)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(10)
            y = dp(86)
        }

        attachDragBehavior(root, params)
        if (safeAddView(root, params)) {
            controlBarView = root
            controlBarParams = params
        }
    }

    private fun makeCompactButton(
        label: String,
        bgHex: String,
        marginEndDp: Int = 3,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = dp(26)
            minHeight = dp(26)
            setPadding(dp(5), dp(2), dp(5), dp(2))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = dp(13).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(26)
            ).apply {
                marginEnd = dp(marginEndDp)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun handlePlayPauseClick() {
        refreshScreenSize()
        val state = AutomationCoordinator.engineState.value
        if (state == EngineState.IDLE || state == EngineState.ERROR) {
            val needsCapture = AutomationCoordinator.activeMode.value == AutomationMode.MACRO &&
                AutomationCoordinator.macroNeedsScreenCapture(AutomationCoordinator.activeMacro.value)
            if (needsCapture && !ScreenCaptureService.isCapturing.value) {
                Toast.makeText(this, "Aktifkan izin tangkap layar untuk deteksi gambar/warna", Toast.LENGTH_SHORT).show()
                ScreenCapturePermissionActivity.request(this) {
                    mainHandler.post {
                        AutomationCoordinator.togglePlayPause(screenWidth, screenHeight)
                    }
                }
                return
            }
        }
        AutomationCoordinator.togglePlayPause(screenWidth, screenHeight)
    }

    private fun toggleCollapse() {
        isCollapsed = !isCollapsed
        expandedToolsRow?.visibility = if (isCollapsed) View.GONE else View.VISIBLE
        collapseBtn?.text = if (isCollapsed) "▸" else "▾"
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
                    if (abs(dx) > 5 || abs(dy) > 5) {
                        moved = true
                        params.x = (initialX + dx).coerceIn(0, (screenWidth - dp(32)).coerceAtLeast(0))
                        params.y = (initialY + dy).coerceIn(0, (screenHeight - dp(32)).coerceAtLeast(0))
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

    // ---- Screenshot & Crop Overlay (Compact Batal / Simpan Bar) ----

    private fun launchScreenshotCrop(addToMacro: Boolean) {
        if (cropOverlayView != null) return
        if (!ScreenCaptureService.isCapturing.value) {
            Toast.makeText(this, "Izinkan tangkap layar untuk crop gambar target", Toast.LENGTH_SHORT).show()
            ScreenCapturePermissionActivity.request(this) {
                mainHandler.post { launchScreenshotCrop(addToMacro) }
            }
            return
        }

        // Briefly hide overlay chrome so the captured screenshot is clean
        setOverlayChromeVisibility(View.INVISIBLE)
        mainHandler.postDelayed({
            val snapshot = ScreenCaptureService.captureBitmapCopy()
            setOverlayChromeVisibility(View.VISIBLE)
            if (snapshot == null) {
                Toast.makeText(this, "Gagal mengambil tangkapan layar. Coba lagi.", Toast.LENGTH_SHORT).show()
                return@postDelayed
            }
            showCropOverlay(snapshot, addToMacro)
        }, 160L)
    }

    private fun showCropOverlay(snapshot: Bitmap, addToMacro: Boolean) {
        dismissCropOverlay()
        cropScreenshot = snapshot
        val view = OverlayCropView(
            context = this,
            screenshot = snapshot,
            onSave = { cropped, _ ->
                val defaultName = store.uniqueImageName("target")
                val saved = store.saveImage(defaultName, cropped)
                cropped.recycle()
                if (addToMacro) {
                    AutomationCoordinator.addQuickImageAction(saved.name)
                    Toast.makeText(this, "Disimpan & ditambahkan: ${saved.name}", Toast.LENGTH_SHORT).show()
                } else {
                    ExecutionLogBus.image("Gambar '${saved.name}' (${saved.width}×${saved.height}) disimpan.")
                    Toast.makeText(this, "Disimpan: ${saved.name}", Toast.LENGTH_SHORT).show()
                }
                dismissCropOverlay()
            },
            onCancel = { dismissCropOverlay() }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        safeAddView(view, params)
        cropOverlayView = view
    }

    private fun dismissCropOverlay() {
        cropOverlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        cropOverlayView = null
        cropScreenshot?.recycle()
        cropScreenshot = null
    }

    // ---- Touch Recorder Overlay ----

    private fun openTouchRecorder() {
        if (recorderOverlayView != null) return
        AutomationCoordinator.stop()
        dismissMarkerEditor()
        setOverlayChromeVisibility(View.INVISIBLE)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val view = OverlayRecorderView(
            context = this,
            onPassThroughGesture = { gesture -> forwardRecordedGesture(gesture) },
            onFinish = { gestures ->
                dismissTouchRecorder()
                if (gestures.isNotEmpty()) {
                    AutomationCoordinator.addRecordedGestures(gestures)
                    Toast.makeText(this, "Rekaman disimpan (${gestures.size} gestur)", Toast.LENGTH_SHORT).show()
                }
            },
            onCancel = { dismissTouchRecorder() }
        )

        safeAddView(view, params)
        recorderOverlayView = view
        recorderParams = params
    }

    private fun forwardRecordedGesture(gesture: RecordedGesture) {
        val view = recorderOverlayView ?: return
        val params = recorderParams ?: return
        if (!MacroAccessibilityService.isConnected.value) return
        serviceScope.launch {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
            delay(30L)
            MacroAccessibilityService.dispatchRecordedGesture(gesture, 1f)
            delay(40L)
            if (recorderOverlayView === view) {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
            }
        }
    }

    private fun dismissTouchRecorder() {
        recorderOverlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        recorderOverlayView = null
        recorderParams = null
        setOverlayChromeVisibility(View.VISIBLE)
    }

    private fun setOverlayChromeVisibility(visibility: Int) {
        controlBarView?.visibility = visibility
        logOverlayView?.visibility = visibility
        colorSpyView?.visibility = visibility
        swipePathView?.visibility = visibility
        markerViews.forEach { (v, _) -> v.visibility = visibility }
    }

    // ---- Reactive State Observers ----

    private fun observeEngineAndLogs() {
        serviceScope.launch {
            combine(
                AutomationCoordinator.engineState,
                AutomationCoordinator.activeMode,
                AutomationCoordinator.activeMacro,
                AutomationCoordinator.activeLuaScript
            ) { state, mode, macro, _ ->
                OverlaySnapshot(state = state, mode = mode, macro = macro)
            }.collect { snap ->
                updateControlBarUi(snap.state)
                if (snap.mode == AutomationMode.MACRO) {
                    rebuildTargetMarkers(snap.macro.actions, isRunning = snap.state == EngineState.RUNNING)
                } else {
                    clearTargetMarkers()
                }
            }
        }

        serviceScope.launch {
            AutomationCoordinator.matchHighlight.collect { highlight ->
                updateMatchHighlight(highlight)
            }
        }

        serviceScope.launch {
            ExecutionLogBus.logs.collect { entries ->
                if (isLogVisible && logTextView != null) {
                    val text = entries.takeLast(45).joinToString("\n") {
                        "[${it.timestamp.substring(0, 8)} ${it.level.tag}] ${it.message}"
                    }.ifBlank { "Menunggu log eksekusi..." }
                    logTextView?.text = text
                    logScrollView?.post { logScrollView?.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }

    private fun updateControlBarUi(state: EngineState) {
        val (playSymbol, dotColor) = when (state) {
            EngineState.RUNNING -> "❚❚" to "#10B981"
            EngineState.PAUSED -> "▶" to "#F59E0B"
            EngineState.ERROR -> "▶" to "#EF4444"
            EngineState.IDLE -> "▶" to "#2DD4BF"
        }
        playPauseBtn?.text = playSymbol
        (statusDotView?.background as? GradientDrawable)?.setColor(Color.parseColor(dotColor))
    }

    // ---- Swipe Path Lines & Direction Arrows Overlay ----

    private data class SwipeSegment(
        val id: String,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float
    )

    private class SwipePathOverlayView(
        context: Context,
        private val markerRadiusPx: Float
    ) : View(context) {

        private val d = resources.displayMetrics.density
        private val segments = LinkedHashMap<String, SwipeSegment>()

        private val glowPaint = Paint().apply {
            color = Color.parseColor("#660284C7")
            style = Paint.Style.STROKE
            strokeWidth = 7f * d
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        private val linePaint = Paint().apply {
            color = Color.parseColor("#38BDF8")
            style = Paint.Style.STROKE
            strokeWidth = 3f * d
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        private val arrowFill = Paint().apply {
            color = Color.parseColor("#99F6E4")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private val arrowStroke = Paint().apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * d
            isAntiAlias = true
        }
        private val arrowPath = Path()

        fun setSegments(list: List<SwipeSegment>, isRunning: Boolean) {
            segments.clear()
            for (s in list) segments[s.id] = s
            alpha = if (isRunning) 0.35f else 0.95f
            invalidate()
        }

        fun updateEndpoint(id: String, x: Float, y: Float, isEndNode: Boolean) {
            val cur = segments[id] ?: return
            segments[id] = if (isEndNode) cur.copy(x2 = x, y2 = y) else cur.copy(x1 = x, y1 = y)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (seg in segments.values) {
                val dx = seg.x2 - seg.x1
                val dy = seg.y2 - seg.y1
                val dist = hypot(dx, dy)
                if (dist < 4f) continue

                val ux = dx / dist
                val uy = dy / dist
                val startOffset = (markerRadiusPx * 0.85f).coerceAtMost(dist * 0.25f)
                val endOffset = (markerRadiusPx * 0.95f).coerceAtMost(dist * 0.35f)

                val sx = seg.x1 + ux * startOffset
                val sy = seg.y1 + uy * startOffset
                val ex = seg.x2 - ux * endOffset
                val ey = seg.y2 - uy * endOffset

                // Line connecting A -> B
                canvas.drawLine(sx, sy, ex, ey, glowPaint)
                canvas.drawLine(sx, sy, ex, ey, linePaint)

                val angle = atan2(dy, dx)
                // Primary arrowhead right before marker B
                drawArrowHead(canvas, ex, ey, angle, 12f * d)

                // Midpoint directional arrow when swipe is long enough
                if (dist > markerRadiusPx * 3.5f) {
                    val mx = (sx + ex) * 0.5f
                    val my = (sy + ey) * 0.5f
                    drawArrowHead(canvas, mx, my, angle, 9f * d)
                }
            }
        }

        private fun drawArrowHead(canvas: Canvas, tipX: Float, tipY: Float, angleRad: Float, size: Float) {
            val spread = Math.toRadians(28.0).toFloat()
            val x1 = tipX - size * cos(angleRad - spread)
            val y1 = tipY - size * sin(angleRad - spread)
            val x2 = tipX - size * cos(angleRad + spread)
            val y2 = tipY - size * sin(angleRad + spread)
            arrowPath.reset()
            arrowPath.moveTo(tipX, tipY)
            arrowPath.lineTo(x1, y1)
            arrowPath.lineTo(x2, y2)
            arrowPath.close()
            canvas.drawPath(arrowPath, arrowFill)
            canvas.drawPath(arrowPath, arrowStroke)
        }
    }

    private fun updateSwipePathOverlay(segments: List<SwipeSegment>, isRunning: Boolean, markerHalfPx: Float) {
        if (segments.isEmpty()) {
            removeSwipePathOverlay()
            return
        }
        if (swipePathView == null) {
            val v = SwipePathOverlayView(this, markerHalfPx)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            try {
                safeAddView(v, params)
                swipePathView = v
            } catch (_: Exception) {
                return
            }
        }
        swipePathView?.setSegments(segments, isRunning)
    }

    private fun removeSwipePathOverlay() {
        swipePathView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        swipePathView = null
    }

    // ---- Live Match Bounding Box ----

    private fun updateMatchHighlight(highlight: MatchHighlight?) {
        if (highlight == null) {
            matchBoxView?.let {
                try { windowManager.removeView(it) } catch (_: Exception) {}
            }
            matchBoxView = null
            return
        }
        if (matchBoxView == null) {
            val v = MatchHighlightView(this)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            try {
                safeAddView(v, params)
                matchBoxView = v
            } catch (_: Exception) {
                return
            }
        }
        matchBoxView?.setHighlight(highlight)
    }

    private class MatchHighlightView(context: Context) : View(context) {
        private var current: MatchHighlight? = null
        private val d = resources.displayMetrics.density
        private val boxPaint = Paint().apply {
            color = Color.parseColor("#22C55E")
            style = Paint.Style.STROKE
            strokeWidth = 3f * d
            isAntiAlias = true
        }
        private val fillPaint = Paint().apply {
            color = Color.parseColor("#3322C55E")
            style = Paint.Style.FILL
        }
        private val textBg = Paint().apply {
            color = Color.parseColor("#CC14532D")
        }
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 11f * d
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        fun setHighlight(h: MatchHighlight) {
            current = h
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val h = current ?: return
            val m = h.match
            val r = RectF(m.x.toFloat(), m.y.toFloat(), (m.x + m.width).toFloat(), (m.y + m.height).toFloat())
            canvas.drawRect(r, fillPaint)
            canvas.drawRect(r, boxPaint)
            val tw = textPaint.measureText(h.label)
            val ty = (r.top - 22f * d).coerceAtLeast(4f * d)
            canvas.drawRoundRect(RectF(r.left, ty, r.left + tw + 12f * d, ty + 20f * d), 4f * d, 4f * d, textBg)
            canvas.drawText(h.label, r.left + 6f * d, ty + 14f * d, textPaint)
        }
    }

    // ---- Visual Target Markers (Click, LongPress, Swipe, Color) ----

    private fun rebuildTargetMarkers(actions: List<Action>, isRunning: Boolean) {
        clearTargetMarkers()
        val markerSize = dp(36)
        val half = markerSize / 2
        val baseFlags = if (isRunning) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        // Draw swipe connecting lines & direction arrows underneath the markers
        val swipeSegments = actions
            .filter { it.enabled }
            .filterIsInstance<SwipeAction>()
            .map { SwipeSegment(it.id, it.x1.toFloat(), it.y1.toFloat(), it.x2.toFloat(), it.y2.toFloat()) }
        updateSwipePathOverlay(swipeSegments, isRunning, half.toFloat())

        var markerNumber = 0
        actions.forEach { action ->
            if (!action.enabled) return@forEach
            when (action) {
                is ClickAction -> {
                    markerNumber++
                    addMarker(
                        label = "$markerNumber",
                        colorHex = "#CC0D9488",
                        cx = action.x,
                        cy = action.y,
                        size = markerSize,
                        half = half,
                        flags = baseFlags,
                        isRunning = isRunning,
                        onTap = { showMarkerQuickEditor(action) },
                        onDragCommit = { nx, ny -> AutomationCoordinator.updateMarkerPosition(action.id, nx, ny) }
                    )
                }
                is LongPressAction -> {
                    markerNumber++
                    addMarker(
                        label = "${markerNumber}L",
                        colorHex = "#CC4F46E5",
                        cx = action.x,
                        cy = action.y,
                        size = markerSize,
                        half = half,
                        flags = baseFlags,
                        isRunning = isRunning,
                        onTap = { showMarkerQuickEditor(action) },
                        onDragCommit = { nx, ny -> AutomationCoordinator.updateMarkerPosition(action.id, nx, ny) }
                    )
                }
                is SwipeAction -> {
                    markerNumber++
                    addMarker(
                        label = "${markerNumber}A",
                        colorHex = "#CC0284C7",
                        cx = action.x1,
                        cy = action.y1,
                        size = markerSize,
                        half = half,
                        flags = baseFlags,
                        isRunning = isRunning,
                        onTap = { showMarkerQuickEditor(action) },
                        onDragging = { nx, ny -> swipePathView?.updateEndpoint(action.id, nx.toFloat(), ny.toFloat(), isEndNode = false) },
                        onDragCommit = { nx, ny -> AutomationCoordinator.updateMarkerPosition(action.id, nx, ny, isEndNode = false) }
                    )
                    addMarker(
                        label = "${markerNumber}B",
                        colorHex = "#CC0369A1",
                        cx = action.x2,
                        cy = action.y2,
                        size = markerSize,
                        half = half,
                        flags = baseFlags,
                        isRunning = isRunning,
                        onTap = { showMarkerQuickEditor(action) },
                        onDragging = { nx, ny -> swipePathView?.updateEndpoint(action.id, nx.toFloat(), ny.toFloat(), isEndNode = true) },
                        onDragCommit = { nx, ny -> AutomationCoordinator.updateMarkerPosition(action.id, nx, ny, isEndNode = true) }
                    )
                }
                is ColorAction -> {
                    markerNumber++
                    addMarker(
                        label = "${markerNumber}C",
                        colorHex = "#CC7C3AED",
                        cx = action.x,
                        cy = action.y,
                        size = markerSize,
                        half = half,
                        flags = baseFlags,
                        isRunning = isRunning,
                        onTap = { showMarkerQuickEditor(action) },
                        onDragCommit = { nx, ny -> AutomationCoordinator.updateMarkerPosition(action.id, nx, ny) }
                    )
                }
                else -> Unit
            }
        }
    }

    private fun addMarker(
        label: String,
        colorHex: String,
        cx: Int,
        cy: Int,
        size: Int,
        half: Int,
        flags: Int,
        isRunning: Boolean,
        onTap: () -> Unit,
        onDragging: ((Int, Int) -> Unit)? = null,
        onDragCommit: (Int, Int) -> Unit
    ) {
        val view = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            alpha = if (isRunning) 0.4f else 0.92f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(colorHex))
                setStroke(dp(2), Color.parseColor("#99F6E4"))
            }
        }
        val params = WindowManager.LayoutParams(
            size, size, overlayType(), flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (cx - half).coerceIn(0, (screenWidth - size).coerceAtLeast(0))
            y = (cy - half).coerceIn(0, (screenHeight - size).coerceAtLeast(0))
        }
        if (!isRunning) {
            attachMarkerTouch(view, params, half, onTap, onDragging, onDragCommit)
        }
        safeAddView(view, params)
        markerViews.add(view to params)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachMarkerTouch(
        view: View,
        params: WindowManager.LayoutParams,
        half: Int,
        onTap: () -> Unit,
        onDragging: ((Int, Int) -> Unit)?,
        onCommit: (Int, Int) -> Unit
    ) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 6 || abs(dy) > 6) {
                        dragged = true
                        params.x = (initialX + dx).coerceIn(0, (screenWidth - half * 2).coerceAtLeast(0))
                        params.y = (initialY + dy).coerceIn(0, (screenHeight - half * 2).coerceAtLeast(0))
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                        onDragging?.invoke(params.x + half, params.y + half)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) onCommit(params.x + half, params.y + half) else onTap()
                    true
                }
                else -> false
            }
        }
    }

    private fun showMarkerQuickEditor(action: Action) {
        dismissMarkerEditor()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F20F172A"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#2DD4BF"))
            }
        }

        val title = TextView(this).apply {
            text = when (action) {
                is ClickAction -> "Edit Klik (${action.x}, ${action.y})"
                is LongPressAction -> "Edit Tahan (${action.x}, ${action.y})"
                is SwipeAction -> "Edit Swipe"
                is ColorAction -> "Edit Warna ${action.color}"
                else -> "Edit Aksi"
            }
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        card.addView(title)

        fun labeledNumberField(label: String, initial: Long): EditText {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            val tv = TextView(this).apply {
                text = label
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val et = EditText(this).apply {
                setText(initial.toString())
                setTextColor(Color.WHITE)
                textSize = 12f
                inputType = InputType.TYPE_CLASS_NUMBER
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = dp(8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            row.addView(tv)
            row.addView(et)
            card.addView(row)
            return et
        }

        val delayField = labeledNumberField("Jeda sesudah (ms)", action.delayAfterMs)
        val durationField = when (action) {
            is ClickAction -> labeledNumberField("Durasi tekan (ms)", action.durationMs)
            is LongPressAction -> labeledNumberField("Durasi tahan (ms)", action.durationMs)
            is SwipeAction -> labeledNumberField("Durasi swipe (ms)", action.durationMs)
            else -> null
        }
        val repeatField = (action as? ClickAction)?.let { labeledNumberField("Jumlah klik", it.repeat.toLong()) }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        btnRow.addView(makeCompactButton("Hapus", "#991B1B", marginEndDp = 6) {
            AutomationCoordinator.removeActionFromOverlay(action.id)
            dismissMarkerEditor()
        })
        btnRow.addView(makeCompactButton("Batal", "#475569", marginEndDp = 6) {
            dismissMarkerEditor()
        })
        btnRow.addView(makeCompactButton("Simpan", "#0D9488", marginEndDp = 0) {
            val newDelay = delayField.text.toString().toLongOrNull()?.coerceAtLeast(0L) ?: action.delayAfterMs
            val newDur = durationField?.text?.toString()?.toLongOrNull()?.coerceIn(15L, 60_000L)
            val newRep = repeatField?.text?.toString()?.toIntOrNull()?.coerceIn(1, 999)
            AutomationCoordinator.updateActionFromOverlay(action.id) { cur ->
                when (cur) {
                    is ClickAction -> cur.copy(
                        delayAfterMs = newDelay,
                        durationMs = newDur ?: cur.durationMs,
                        repeat = newRep ?: cur.repeat
                    )
                    is LongPressAction -> cur.copy(delayAfterMs = newDelay, durationMs = newDur ?: cur.durationMs)
                    is SwipeAction -> cur.copy(delayAfterMs = newDelay, durationMs = newDur ?: cur.durationMs)
                    is ColorAction -> cur.copy(delayAfterMs = newDelay)
                    else -> cur
                }
            }
            dismissMarkerEditor()
        })
        card.addView(btnRow)

        val params = WindowManager.LayoutParams(
            dp(260),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.4f
        }
        safeAddView(card, params)
        markerEditorView = card
    }

    private fun dismissMarkerEditor() {
        markerEditorView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        markerEditorView = null
    }

    private fun clearTargetMarkers() {
        markerViews.forEach { (view, _) ->
            try { windowManager.removeView(view) } catch (_: Exception) {}
        }
        markerViews.clear()
        removeSwipePathOverlay()
    }

    // ---- Live Log Console ----

    private fun toggleLogConsole() {
        if (isLogVisible) removeLogConsole() else showLogConsole()
    }

    private fun showLogConsole() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DC0E151B"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#332DD4BF"))
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "MacroGG Live Log"
            setTextColor(Color.parseColor("#2DD4BF"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "Bersihkan"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 10f
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener { ExecutionLogBus.clear() }
        })
        root.addView(header)

        val tv = TextView(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            text = ExecutionLogBus.logs.value.takeLast(45).joinToString("\n") {
                "[${it.timestamp.substring(0, 8)} ${it.level.tag}] ${it.message}"
            }.ifBlank { "Menunggu log eksekusi..." }
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(250), dp(125)).apply { topMargin = dp(4) }
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
            x = dp(12)
            y = dp(168)
        }

        attachDragBehavior(root, params)
        safeAddView(root, params)
        logOverlayView = root
        logTextView = tv
        logScrollView = scroll
        isLogVisible = true
    }

    private fun removeLogConsole() {
        logOverlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        logOverlayView = null
        logTextView = null
        logScrollView = null
        isLogVisible = false
    }

    // ---- Color Spy Eyedropper ----

    private fun toggleColorSpy() {
        if (isColorSpyVisible) removeColorSpy() else showColorSpy()
    }

    private fun showColorSpy() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EB111827"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#F59E0B"))
            }
        }

        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(5) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2DD4BF"))
                setStroke(dp(1), Color.WHITE)
            }
        }
        colorSpySwatch = swatch
        root.addView(swatch)

        val label = TextView(this).apply {
            text = "Bidik warna"
            setTextColor(Color.WHITE)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(5) }
        }
        colorSpyLabel = label
        root.addView(label)

        root.addView(makeCompactButton("Salin", "#0D9488", marginEndDp = 4) {
            val p = colorSpyParams ?: return@makeCompactButton
            val hex = ScreenCaptureService.samplePixelHex(p.x, p.y) ?: "#000000"
            AutomationCoordinator.publishPickedPixel(p.x, p.y, hex)
            val clip = getSystemService(ClipboardManager::class.java)
            clip?.setPrimaryClip(ClipData.newPlainText("MacroGG Color", "${p.x}, ${p.y}, \"$hex\""))
            Toast.makeText(this, "Disalin: (${p.x},${p.y}) $hex", Toast.LENGTH_SHORT).show()
        })

        root.addView(makeCompactButton("+Aksi", "#7C3AED", marginEndDp = 0) {
            val p = colorSpyParams ?: return@makeCompactButton
            val hex = ScreenCaptureService.samplePixelHex(p.x, p.y) ?: "#FFFFFF"
            AutomationCoordinator.publishPickedPixel(p.x, p.y, hex)
            AutomationCoordinator.updateActiveMacro(persist = true) { macro ->
                macro.copy(actions = macro.actions + ColorAction(x = p.x, y = p.y, color = hex))
            }
            Toast.makeText(this, "Deteksi Warna $hex ditambahkan", Toast.LENGTH_SHORT).show()
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth / 4
            y = screenHeight / 3
        }

        attachDragBehavior(root, params) { nx, ny ->
            val sampled = ScreenCaptureService.samplePixelHex(nx, ny)
            if (sampled != null) {
                colorSpyLabel?.text = "($nx,$ny) $sampled"
                try {
                    (colorSpySwatch?.background as? GradientDrawable)?.setColor(Color.parseColor(sampled))
                } catch (_: Exception) {}
            } else {
                colorSpyLabel?.text = "($nx,$ny) Aktifkan Layar"
            }
        }

        safeAddView(root, params)
        colorSpyView = root
        colorSpyParams = params
        isColorSpyVisible = true
    }

    private fun removeColorSpy() {
        colorSpyView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        colorSpyView = null
        colorSpyParams = null
        colorSpySwatch = null
        colorSpyLabel = null
        isColorSpyVisible = false
    }

    override fun onDestroy() {
        AutomationCoordinator.setOverlayActive(false)
        dismissCropOverlay()
        dismissTouchRecorder()
        dismissMarkerEditor()
        clearTargetMarkers()
        removeLogConsole()
        removeColorSpy()
        updateMatchHighlight(null)
        controlBarView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        controlBarView = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private data class OverlaySnapshot(
        val state: EngineState,
        val mode: AutomationMode,
        val macro: Macro
    )

    companion object {
        private const val CHANNEL_ID = "macrogg_floating_overlay"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_START = "com.example.action.START_OVERLAY"
        const val ACTION_STOP = "com.example.action.STOP_OVERLAY"
        const val ACTION_OPEN_CROP = "com.example.action.OPEN_CROP"
        const val ACTION_OPEN_RECORDER = "com.example.action.OPEN_RECORDER"
        const val EXTRA_ADD_TO_MACRO = "add_to_macro"

        fun start(context: Context, action: String = ACTION_START, addToMacro: Boolean = true) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                this.action = action
                putExtra(EXTRA_ADD_TO_MACRO, addToMacro)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                ExecutionLogBus.error("Gagal membuka Floating Overlay: ${t.message}")
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, FloatingOverlayService::class.java))
            }
        }
    }
}
