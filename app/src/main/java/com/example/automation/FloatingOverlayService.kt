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
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.view.WindowInsets
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
import com.example.data.ContainerRef
import com.example.data.ImageAction
import com.example.data.LocalStore
import com.example.data.LongPressAction
import com.example.data.LoopAction
import com.example.data.Macro
import com.example.data.MacroSecurity
import com.example.data.RecordedGesture
import com.example.data.Region
import com.example.data.ResolutionScaler
import com.example.data.Slot
import com.example.data.SwipeAction
import com.example.data.TextAction
import com.example.data.ZoomAction
import com.example.data.isStuckAction
import com.example.data.walkWithSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Compact Macrorify-style Floating Control Bar & Overlay Tools:
 * - Slim collapsible control bar with quick +Click and Hide Buttons mode
 * - Full in-overlay Macro Studio (macro settings, watchdog, macro switcher, full action tree & editor)
 * - Zoomable 2-button (Batal / Simpan) screenshot cropper & re-cropper bound to Image Detection
 * - Visible swipe lines & directional arrows with live drag updates + Zoom markers
 * - Freeze-proof touch recorder, Color Spy, live match highlight box, and live log
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

    // Hide Buttons Mode (minimizes entire overlay UI to a tiny translucent bubble)
    private var isOverlayHidden = false
    private var hiddenBubbleView: TextView? = null
    private var hiddenBubbleParams: WindowManager.LayoutParams? = null

    // Target click/swipe markers stay hidden by default unless selected for editing or previewed
    private var showAllMarkersPreview = false
    private var focusedMarkerActionId: String? = null

    private var macroSettingsView: OverlayMacroSettingsView? = null
    private var macroSettingsParams: WindowManager.LayoutParams? = null

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

    private var recorderOverlayView: OverlayRecorderView? = null
    private var recorderParams: WindowManager.LayoutParams? = null

    private var targetPickerView: OverlayTargetPickerView? = null

    private var markerEditorView: View? = null
    private var markerEditorParams: WindowManager.LayoutParams? = null

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width().coerceAtLeast(1)
            screenHeight = bounds.height().coerceAtLeast(1)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels.coerceAtLeast(1)
            screenHeight = metrics.heightPixels.coerceAtLeast(1)
        }
    }

    /**
     * Reads current system bar (status bar + navigation bar) & display cutout insets
     * so overlay settings panels never get clipped behind the navigation bar in portrait or landscape.
     */
    private fun readSystemBarInsets(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ins = windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return Rect(ins.left, ins.top, ins.right, ins.bottom)
        }
        val real = DisplayMetrics()
        val usable = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(real)
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(usable)

        val statusResId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusH = if (statusResId > 0) resources.getDimensionPixelSize(statusResId) else dp(28)
        val navResId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val navFallback = if (navResId > 0) resources.getDimensionPixelSize(navResId) else dp(48)

        val bottomGap = (real.heightPixels - usable.heightPixels).coerceAtLeast(0)
        val sideGap = (real.widthPixels - usable.widthPixels).coerceAtLeast(0)
        val bottomInset = if (bottomGap > 0) bottomGap else if (real.heightPixels >= real.widthPixels) navFallback else 0
        val rightInset = if (sideGap > 0) sideGap else if (real.widthPixels > real.heightPixels) navFallback else 0
        return Rect(0, statusH, rightInset, bottomInset)
    }

    /**
     * Configures width, bounded height, IME resize behavior, and system-bar inset avoidance on an
     * overlay dialog/settings window so it stays completely visible above the navigation bar.
     */
    private fun applySafeOverlayBounds(
        params: WindowManager.LayoutParams,
        maxWidthDp: Int,
        maxHeightDp: Int,
        fixedHeight: Boolean
    ) {
        val insets = readSystemBarInsets()
        val usableW = (screenWidth - insets.left - insets.right).coerceAtLeast(dp(240))
        val usableH = (screenHeight - insets.top - insets.bottom).coerceAtLeast(dp(200))

        params.width = minOf(dp(maxWidthDp), (usableW - dp(16)).coerceAtLeast(dp(220)))
        if (fixedHeight) {
            params.height = minOf(dp(maxHeightDp), (usableH - dp(20)).coerceAtLeast(dp(200)))
        } else {
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
        }
        params.gravity = Gravity.CENTER
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.fitInsetsTypes = WindowInsets.Type.systemBars() or
                WindowInsets.Type.ime() or
                WindowInsets.Type.displayCutout()
            params.setFitInsetsIgnoringVisibility(false)
            params.x = 0
            params.y = 0
        } else {
            // Center explicitly within the safe area outside status/navigation bars on API 24-29
            params.x = (insets.left - insets.right) / 2
            params.y = (insets.top - insets.bottom) / 2
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_OPEN_CROP -> {
                val addToMacro = intent.getBooleanExtra(EXTRA_ADD_TO_MACRO, true)
                val targetActionId = intent.getStringExtra(EXTRA_TARGET_ACTION_ID)
                val targetFolder = intent.getStringExtra(EXTRA_TARGET_FOLDER).orEmpty()
                ensureOverlayRunning()
                mainHandler.postDelayed(
                    { launchScreenshotCrop(addToMacro, targetActionId, targetFolder = targetFolder) },
                    200L
                )
                return START_STICKY
            }
            ACTION_PICK_REGION -> {
                val targetActionId = intent.getStringExtra(EXTRA_TARGET_ACTION_ID)
                ensureOverlayRunning()
                if (!targetActionId.isNullOrBlank()) {
                    mainHandler.postDelayed({ launchRegionPicker(targetActionId, reopenSettingsActionId = null) }, 200L)
                }
                return START_STICKY
            }
            ACTION_OPEN_RECORDER -> {
                ensureOverlayRunning()
                mainHandler.postDelayed({ openTouchRecorder() }, 200L)
                return START_STICKY
            }
            ACTION_PICK_TARGET -> {
                val targetActionId = intent.getStringExtra(EXTRA_TARGET_ACTION_ID)
                ensureOverlayRunning()
                if (!targetActionId.isNullOrBlank()) {
                    mainHandler.postDelayed({ launchTargetPicker(targetActionId, reopenSettingsActionId = null) }, 180L)
                }
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
        clampOverlayWindow(controlBarView, controlBarParams)
        clampOverlayWindow(colorSpyView, colorSpyParams)
        clampOverlayWindow(hiddenBubbleView, hiddenBubbleParams)
        macroSettingsView?.let { panel ->
            macroSettingsParams?.let { p ->
                applySafeOverlayBounds(p, maxWidthDp = 340, maxHeightDp = 490, fixedHeight = true)
                try { windowManager.updateViewLayout(panel, p) } catch (_: Throwable) {}
            }
        }
        markerEditorView?.let { card ->
            markerEditorParams?.let { p ->
                applySafeOverlayBounds(p, maxWidthDp = 280, maxHeightDp = 380, fixedHeight = false)
                try { windowManager.updateViewLayout(card, p) } catch (_: Throwable) {}
            }
        }
    }

    private fun clampOverlayWindow(view: View?, params: WindowManager.LayoutParams?) {
        if (view == null || params == null) return
        params.x = params.x.coerceIn(0, (screenWidth - dp(36)).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenHeight - dp(36)).coerceAtLeast(0))
        try { windowManager.updateViewLayout(view, params) } catch (_: Throwable) {}
    }

    private fun ensureOverlayRunning() {
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
                ExecutionLogBus.info("Floating Overlay Wjust macro aktif.")
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

    private fun applyFullScreenCutout(params: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
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
                setColor(Color.parseColor("#EC0A1020"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#22D3EE"))
            }
            elevation = dp(6).toFloat()
        }

        // Row 1 (ultra-compact): [•] [▶] [■] [+] [👁] [▾]
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
                setColor(Color.parseColor("#22D3EE"))
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

        topRow.addView(makeCompactButton("+", "#1D4ED8") {
            refreshScreenSize()
            val created = AutomationCoordinator.addQuickClickMarker(
                screenWidth / 2,
                screenHeight / 2,
                screenWidth,
                screenHeight
            )
            if (created != null) {
                launchTargetPicker(created.id, reopenSettingsActionId = null)
            } else {
                Toast.makeText(this, "Macro dikunci dengan sandi — tidak dapat diubah", Toast.LENGTH_SHORT).show()
            }
        })

        // Hide Buttons Mode toggle
        topRow.addView(makeCompactButton("👁", "#475569") {
            enterHideButtonsMode()
        })

        val btnCollapse = makeCompactButton("▾", "#334155", marginEndDp = 0) {
            toggleCollapse()
        }
        collapseBtn = btnCollapse
        topRow.addView(btnCollapse)

        root.addView(topRow)

        // Row 2 (collapsible compact tools): [⇄] [✂] [●] [⚙] [◎] [≡] [⌂] [✕]
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
            val created = AutomationCoordinator.addQuickSwipeMarker(
                screenWidth / 2,
                (screenHeight * 0.62f).toInt(),
                screenWidth / 2,
                (screenHeight * 0.38f).toInt(),
                screenWidth,
                screenHeight
            )
            if (created != null) {
                launchTargetPicker(created.id, reopenSettingsActionId = null)
            } else {
                Toast.makeText(this, "Macro dikunci dengan sandi — tidak dapat diubah", Toast.LENGTH_SHORT).show()
            }
        })

        toolsRow.addView(makeCompactButton("✂", "#0F766E") {
            if (AutomationCoordinator.activeMacro.value.isLocked) {
                Toast.makeText(this, "Macro dikunci dengan sandi — tidak dapat ditambah aksi", Toast.LENGTH_SHORT).show()
            } else {
                launchScreenshotCrop(addToMacro = true, targetActionId = null)
            }
        })

        toolsRow.addView(makeCompactButton("●", "#B91C1C") {
            if (AutomationCoordinator.activeMacro.value.isLocked) {
                Toast.makeText(this, "Macro dikunci dengan sandi — tidak dapat ditambah rekaman", Toast.LENGTH_SHORT).show()
            } else {
                openTouchRecorder()
            }
        })

        toolsRow.addView(makeCompactButton("⚙", "#0284C7") {
            toggleMacroSettingsPanel()
        })

        toolsRow.addView(makeCompactButton("◎", "#6D28D9") {
            toggleColorSpy()
        })

        toolsRow.addView(makeCompactButton("≡", "#334155") {
            toggleLogConsole()
        })

        toolsRow.addView(makeCompactButton("⌂", "#475569") {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        })

        toolsRow.addView(makeCompactButton("✕", "#1E293B", marginEndDp = 0) {
            AutomationCoordinator.stop()
            stopSelf()
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

    // ---- Hide Buttons Mode ----

    @SuppressLint("ClickableViewAccessibility")
    private fun enterHideButtonsMode() {
        if (isOverlayHidden) return
        isOverlayHidden = true
        dismissMacroSettingsPanel()
        dismissMarkerEditor()
        setOverlayChromeVisibility(View.GONE)

        val bubbleSize = dp(30)
        val bubble = TextView(this).apply {
            text = "👁"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            alpha = 0.45f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC0F172A"))
                setStroke(dp(1), Color.parseColor("#2DD4BF"))
            }
        }
        val params = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (controlBarParams?.x ?: dp(8)).coerceIn(0, (screenWidth - bubbleSize).coerceAtLeast(0))
            y = (controlBarParams?.y ?: dp(90)).coerceIn(0, (screenHeight - bubbleSize).coerceAtLeast(0))
        }

        var initX = 0
        var initY = 0
        var tx = 0f
        var ty = 0f
        var dragged = false
        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    tx = event.rawX
                    ty = event.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - tx).toInt()
                    val dy = (event.rawY - ty).toInt()
                    if (abs(dx) > 6 || abs(dy) > 6) {
                        dragged = true
                        params.x = (initX + dx).coerceIn(0, (screenWidth - bubbleSize).coerceAtLeast(0))
                        params.y = (initY + dy).coerceIn(0, (screenHeight - bubbleSize).coerceAtLeast(0))
                        try { windowManager.updateViewLayout(bubble, params) } catch (_: Throwable) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) exitHideButtonsMode()
                    true
                }
                else -> false
            }
        }

        if (safeAddView(bubble, params)) {
            hiddenBubbleView = bubble
            hiddenBubbleParams = params
            Toast.makeText(this, "Tombol disembunyikan — ketuk ikon 👁 untuk tampilkan", Toast.LENGTH_SHORT).show()
        } else {
            exitHideButtonsMode()
        }
    }

    private fun exitHideButtonsMode() {
        isOverlayHidden = false
        hiddenBubbleView?.let {
            try { windowManager.removeView(it) } catch (_: Throwable) {}
        }
        hiddenBubbleView = null
        hiddenBubbleParams = null
        setOverlayChromeVisibility(View.VISIBLE)
        applyMarkerVisibility()
    }

    // ---- Macro Settings Panel Inside Overlay ----

    private fun toggleMacroSettingsPanel() {
        if (macroSettingsView != null) dismissMacroSettingsPanel() else showMacroSettingsPanel()
    }

    private fun showMacroSettingsPanel(initialTab: Int = 0, initialEditActionId: String? = null) {
        dismissMacroSettingsPanel()
        dismissMarkerEditor()
        refreshScreenSize()
        val macro = AutomationCoordinator.activeMacro.value
        val panel = OverlayMacroSettingsView(
            context = this,
            macro = macro,
            store = store,
            screenW = screenWidth,
            screenH = screenHeight,
            showAllMarkersPreview = showAllMarkersPreview,
            initialTab = initialTab,
            initialEditActionId = initialEditActionId,
            onSwitchMacro = { selected ->
                AutomationCoordinator.selectMacro(selected)
                Toast.makeText(this, "Macro aktif: ${selected.name}", Toast.LENGTH_SHORT).show()
            },
            onToggleShowAllMarkers = { show ->
                showAllMarkersPreview = show
                val snap = AutomationCoordinator.activeMacro.value
                rebuildTargetMarkers(
                    snap.actions,
                    isRunning = AutomationCoordinator.engineState.value == EngineState.RUNNING
                )
            },
            onUpdateMacro = { updated ->
                AutomationCoordinator.updateActiveMacro(persist = true) { updated }
                Toast.makeText(this, "Setelan macro disimpan", Toast.LENGTH_SHORT).show()
            },
            onUpdateAutoScale = { enabled, srcW, srcH, mode ->
                val saved = AutomationCoordinator.updateAutoScaleWithoutUnlock(enabled, srcW, srcH, mode)
                macroSettingsView?.refreshWithMacro(saved)
                val st = if (saved.autoScaleResolution) "ON (${saved.scaleMode.label})" else "OFF"
                Toast.makeText(this, "Auto-Scale disimpan: $st", Toast.LENGTH_SHORT).show()
            },
            onAddAction = { ref, newAction, forStuck ->
                refreshScreenSize()
                if (forStuck) {
                    AutomationCoordinator.addStuckActionFromOverlay(ref, newAction, screenWidth, screenHeight)
                } else {
                    AutomationCoordinator.addActionFromOverlay(ref, newAction, screenWidth, screenHeight)
                }
                macroSettingsView?.refreshWithMacro(AutomationCoordinator.activeMacro.value)
            },
            onUpdateAction = { actionId, transform ->
                AutomationCoordinator.updateActionFromOverlay(actionId, transform)
                macroSettingsView?.refreshWithMacro(AutomationCoordinator.activeMacro.value)
            },
            onMoveAction = { actionId, delta ->
                AutomationCoordinator.moveActionFromOverlay(actionId, delta)
                macroSettingsView?.refreshWithMacro(AutomationCoordinator.activeMacro.value)
            },
            onDeleteAction = { actionId ->
                AutomationCoordinator.removeActionFromOverlay(actionId)
                macroSettingsView?.refreshWithMacro(AutomationCoordinator.activeMacro.value)
            },
            onCropForImageAction = { actionId ->
                dismissMacroSettingsPanel()
                launchScreenshotCrop(addToMacro = false, targetActionId = actionId, reopenSettingsActionId = actionId)
            },
            onRecropExistingImage = { imageName ->
                dismissMacroSettingsPanel()
                launchRecropExistingImage(imageName)
            },
            onAddNewCropImageAction = {
                dismissMacroSettingsPanel()
                launchScreenshotCrop(addToMacro = true, targetActionId = null)
            },
            onPickTargetOnScreen = { actionId ->
                dismissMacroSettingsPanel()
                launchTargetPicker(actionId, reopenSettingsActionId = actionId)
            },
            onPickRegionOnScreen = { actionId ->
                dismissMacroSettingsPanel()
                launchRegionPicker(actionId, reopenSettingsActionId = actionId)
            },
            onAddBranchClickTargetOnScreen = { parentId, slot, isStuckBranch ->
                refreshScreenSize()
                val tapMs = store.settings.value.defaultTapDurationMs
                val created = ClickAction(
                    x = screenWidth / 2,
                    y = screenHeight / 2,
                    durationMs = tapMs,
                    delayAfterMs = 250L
                )
                if (isStuckBranch) {
                    AutomationCoordinator.addStuckActionFromOverlay(ContainerRef(parentId, slot), created, screenWidth, screenHeight)
                } else {
                    AutomationCoordinator.addActionFromOverlay(ContainerRef(parentId, slot), created, screenWidth, screenHeight)
                }
                dismissMacroSettingsPanel()
                launchTargetPicker(created.id, reopenSettingsActionId = parentId)
            },
            onClose = { dismissMacroSettingsPanel() }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0.48f
            applySafeOverlayBounds(this, maxWidthDp = 340, maxHeightDp = 490, fixedHeight = true)
        }

        if (safeAddView(panel, params)) {
            macroSettingsView = panel
            macroSettingsParams = params
        }
    }

    private fun dismissMacroSettingsPanel() {
        macroSettingsView?.let {
            try { windowManager.removeView(it) } catch (_: Throwable) {}
        }
        macroSettingsView = null
        macroSettingsParams = null
    }

    // ---- Interactive On-Screen Target Placement (Click / Swipe / Zoom / Color, incl. Then/Else) ----

    private fun launchTargetPicker(actionId: String, reopenSettingsActionId: String? = null) {
        if (targetPickerView != null) dismissTargetPicker()
        if (isOverlayHidden) exitHideButtonsMode()
        dismissMarkerEditor()
        refreshScreenSize()

        val activeMacro = AutomationCoordinator.activeMacro.value
        val found = activeMacro.actions
            .walkWithSlot()
            .firstOrNull { it.first.id == actionId }
            ?: activeMacro.stuckActions
                .walkWithSlot()
                .firstOrNull { it.first.id == actionId }
        if (found == null) {
            Toast.makeText(this, "Aksi target tidak ditemukan.", Toast.LENGTH_SHORT).show()
            return
        }
        val (action, slot) = found
        val scaleTransform = ResolutionScaler.forMacro(activeMacro, screenWidth, screenHeight)
        val slotSuffix = when (slot) {
            Slot.THEN -> " — Jika Ditemukan"
            Slot.ELSE -> " — Jika Tidak Ditemukan"
            Slot.BODY -> " — Perulangan"
            null -> ""
        }
        val accentHex = when (slot) {
            Slot.THEN -> "#10B981"
            Slot.ELSE -> "#F59E0B"
            Slot.BODY -> "#818CF8"
            null -> "#2DD4BF"
        }

        val isDual: Boolean
        val x1: Int
        val y1: Int
        val x2: Int
        val y2: Int
        val sampleColor: Boolean
        val initHex: String?
        val title: String
        when (action) {
            is ClickAction -> {
                val (sx, sy) = scaleTransform.scalePoint(action.x, action.y)
                isDual = false; x1 = sx; y1 = sy; x2 = sx; y2 = sy
                sampleColor = false; initHex = null
                title = "🎯 Posisi Klik$slotSuffix"
            }
            is LongPressAction -> {
                val (sx, sy) = scaleTransform.scalePoint(action.x, action.y)
                isDual = false; x1 = sx; y1 = sy; x2 = sx; y2 = sy
                sampleColor = false; initHex = null
                title = "🎯 Posisi Tahan$slotSuffix"
            }
            is SwipeAction -> {
                val (sx1, sy1) = scaleTransform.scalePoint(action.x1, action.y1)
                val (sx2, sy2) = scaleTransform.scalePoint(action.x2, action.y2)
                isDual = true; x1 = sx1; y1 = sy1; x2 = sx2; y2 = sy2
                sampleColor = false; initHex = null
                title = "🎯 Garis Swipe A→B$slotSuffix"
            }
            is ZoomAction -> {
                val (sx, sy) = scaleTransform.scalePoint(action.cx, action.cy)
                isDual = false; x1 = sx; y1 = sy; x2 = sx; y2 = sy
                sampleColor = false; initHex = null
                title = "🎯 Pusat Zoom$slotSuffix"
            }
            is ColorAction -> {
                val (sx, sy) = scaleTransform.scalePoint(action.x, action.y)
                isDual = false; x1 = sx; y1 = sy; x2 = sx; y2 = sy
                sampleColor = true; initHex = action.color
                title = "🎯 Titik Warna$slotSuffix"
            }
            else -> {
                Toast.makeText(this, "Aksi ini tidak memerlukan posisi layar.", Toast.LENGTH_SHORT).show()
                return
            }
        }

        if (sampleColor) {
            ScreenCaptureService.highFrequencyCapture = true
        }
        setOverlayChromeVisibility(View.INVISIBLE)

        val picker = OverlayTargetPickerView(
            context = this,
            title = title,
            accentHex = accentHex,
            isDualPoint = isDual,
            initialX1 = x1,
            initialY1 = y1,
            initialX2 = x2,
            initialY2 = y2,
            sampleColor = sampleColor,
            initialHexColor = initHex,
            pixelSampler = if (sampleColor) { px, py -> ScreenCaptureService.samplePixelHex(px, py) } else null,
            onSave = { nx1, ny1, nx2, ny2, hex ->
                refreshScreenSize()
                AutomationCoordinator.updateTargetFromScreen(
                    actionId = actionId,
                    screenX1 = nx1,
                    screenY1 = ny1,
                    screenX2 = nx2,
                    screenY2 = ny2,
                    hexColor = hex,
                    screenW = screenWidth,
                    screenH = screenHeight
                )
                if (sampleColor && hex != null) {
                    AutomationCoordinator.publishPickedPixel(nx1, ny1, hex)
                }
                dismissTargetPicker()
                Toast.makeText(this, "Posisi target disimpan ($nx1, $ny1)", Toast.LENGTH_SHORT).show()
                if (reopenSettingsActionId != null) {
                    val tabIdx = if (AutomationCoordinator.activeMacro.value.isStuckAction(reopenSettingsActionId)) 2 else 1
                    showMacroSettingsPanel(initialTab = tabIdx, initialEditActionId = reopenSettingsActionId)
                }
            },
            onCancel = {
                dismissTargetPicker()
                if (reopenSettingsActionId != null) {
                    val tabIdx = if (AutomationCoordinator.activeMacro.value.isStuckAction(reopenSettingsActionId)) 2 else 1
                    showMacroSettingsPanel(initialTab = tabIdx, initialEditActionId = reopenSettingsActionId)
                }
            }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).also { applyFullScreenCutout(it) }

        if (safeAddView(picker, params)) {
            targetPickerView = picker
        } else {
            setOverlayChromeVisibility(View.VISIBLE)
            applyMarkerVisibility()
        }
    }

    private fun dismissTargetPicker() {
        targetPickerView?.let {
            try { windowManager.removeView(it) } catch (_: Throwable) {}
        }
        targetPickerView = null
        if (AutomationCoordinator.engineState.value != EngineState.RUNNING && !isColorSpyVisible) {
            ScreenCaptureService.highFrequencyCapture = false
        }
        setOverlayChromeVisibility(View.VISIBLE)
        applyMarkerVisibility()
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
            if (AutomationCoordinator.activeMode.value == AutomationMode.MACRO) {
                val active = AutomationCoordinator.activeMacro.value
                MacroSecurity.checkDirectRun(active, store.effectiveNowMs())?.let { denial ->
                    val msg = MacroSecurity.denialMessage(active, denial)
                    ExecutionLogBus.error(msg)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    return
                }
            }
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
                        } catch (_: Throwable) {}
                    }
                    moved
                }
                else -> false
            }
        }
    }

    // ---- Screenshot & Crop / Re-Crop Overlay (Zoomable + Compact Batal/Simpan Bar) ----

    private fun launchScreenshotCrop(
        addToMacro: Boolean,
        targetActionId: String?,
        targetFolder: String = "",
        reopenSettingsActionId: String? = null
    ) {
        if (cropOverlayView != null) return
        if (isOverlayHidden) exitHideButtonsMode()
        if (!ScreenCaptureService.isCapturing.value) {
            Toast.makeText(this, "Izinkan tangkap layar untuk crop gambar target", Toast.LENGTH_SHORT).show()
            ScreenCapturePermissionActivity.request(this) {
                mainHandler.post { launchScreenshotCrop(addToMacro, targetActionId, targetFolder, reopenSettingsActionId) }
            }
            return
        }

        // Briefly hide overlay chrome, capture snapshot off the UI thread so it never stalls
        setOverlayChromeVisibility(View.INVISIBLE)
        serviceScope.launch {
            delay(160L)
            val snapshot = withContext(Dispatchers.Default) {
                ScreenCaptureService.captureBitmapCopy()
            }
            setOverlayChromeVisibility(View.VISIBLE)
            applyMarkerVisibility()
            if (snapshot == null) {
                Toast.makeText(this@FloatingOverlayService, "Gagal mengambil tangkapan layar. Coba lagi.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            showCropOverlay(
                snapshot = snapshot,
                addToMacro = addToMacro,
                targetActionId = targetActionId,
                overwriteImageName = null,
                targetFolder = targetFolder,
                regionTargetActionId = null,
                reopenSettingsActionId = reopenSettingsActionId
            )
        }
    }

    private fun launchRegionPicker(actionId: String, reopenSettingsActionId: String? = null) {
        if (cropOverlayView != null) return
        if (isOverlayHidden) exitHideButtonsMode()
        if (!ScreenCaptureService.isCapturing.value) {
            Toast.makeText(this, "Izinkan tangkap layar untuk memilih area baca", Toast.LENGTH_SHORT).show()
            ScreenCapturePermissionActivity.request(this) {
                mainHandler.post { launchRegionPicker(actionId, reopenSettingsActionId) }
            }
            return
        }

        setOverlayChromeVisibility(View.INVISIBLE)
        serviceScope.launch {
            delay(160L)
            val snapshot = withContext(Dispatchers.Default) {
                ScreenCaptureService.captureBitmapCopy()
            }
            setOverlayChromeVisibility(View.VISIBLE)
            applyMarkerVisibility()
            if (snapshot == null) {
                Toast.makeText(this@FloatingOverlayService, "Gagal mengambil tangkapan layar. Coba lagi.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            showCropOverlay(
                snapshot = snapshot,
                addToMacro = false,
                targetActionId = null,
                overwriteImageName = null,
                targetFolder = "",
                regionTargetActionId = actionId,
                reopenSettingsActionId = reopenSettingsActionId
            )
        }
    }

    private fun launchRecropExistingImage(imageName: String) {
        if (cropOverlayView != null) return
        if (isOverlayHidden) exitHideButtonsMode()
        serviceScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                val f = store.imageFile(imageName)
                if (!f.exists()) null else runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
            }
            if (bmp == null) {
                Toast.makeText(this@FloatingOverlayService, "Gambar '$imageName' tidak ditemukan.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            showCropOverlay(
                snapshot = bmp,
                addToMacro = false,
                targetActionId = null,
                overwriteImageName = imageName,
                targetFolder = "",
                regionTargetActionId = null,
                reopenSettingsActionId = null
            )
        }
    }

    private fun showCropOverlay(
        snapshot: Bitmap,
        addToMacro: Boolean,
        targetActionId: String?,
        overwriteImageName: String?,
        targetFolder: String,
        regionTargetActionId: String?,
        reopenSettingsActionId: String?
    ) {
        dismissCropOverlay()
        cropScreenshot = snapshot
        val view = OverlayCropView(
            context = this,
            screenshot = snapshot,
            onSave = { cropped, rect ->
                when {
                    !regionTargetActionId.isNullOrBlank() -> {
                        cropped.recycle()
                        val region = Region(rect.left, rect.top, rect.right, rect.bottom)
                        AutomationCoordinator.bindRegionToAction(
                            regionTargetActionId,
                            region,
                            screenW = snapshot.width,
                            screenH = snapshot.height
                        )
                        Toast.makeText(this, "Area baca disimpan (${region.width}×${region.height})", Toast.LENGTH_SHORT).show()
                    }
                    !overwriteImageName.isNullOrBlank() -> {
                        val saved = store.overwriteImage(overwriteImageName, cropped)
                        cropped.recycle()
                        ExecutionLogBus.image("Gambar '${saved.displayName}' dipotong ulang (${saved.width}×${saved.height}).")
                        Toast.makeText(this, "Dipangkas ulang: ${saved.displayName}", Toast.LENGTH_SHORT).show()
                    }
                    !targetActionId.isNullOrBlank() -> {
                        val saved = store.saveImage("target", cropped, targetFolder)
                        cropped.recycle()
                        AutomationCoordinator.bindImageToAction(targetActionId, saved.name)
                        Toast.makeText(this, "Gambar dipasang: ${saved.displayName}", Toast.LENGTH_SHORT).show()
                    }
                    addToMacro -> {
                        val saved = store.saveImage("target", cropped, targetFolder)
                        cropped.recycle()
                        AutomationCoordinator.addQuickImageAction(
                            saved.name,
                            screenW = snapshot.width,
                            screenH = snapshot.height
                        )
                        Toast.makeText(this, "Deteksi Gambar ditambahkan: ${saved.displayName}", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        val saved = store.saveImage("target", cropped, targetFolder)
                        cropped.recycle()
                        ExecutionLogBus.image("Gambar '${saved.name}' (${saved.width}×${saved.height}) disimpan.")
                        Toast.makeText(this, "Disimpan: ${saved.displayName}", Toast.LENGTH_SHORT).show()
                    }
                }
                dismissCropOverlay()
                if (!reopenSettingsActionId.isNullOrBlank()) {
                    val tabIdx = if (AutomationCoordinator.activeMacro.value.isStuckAction(reopenSettingsActionId)) 2 else 1
                    showMacroSettingsPanel(initialTab = tabIdx, initialEditActionId = reopenSettingsActionId)
                }
            },
            onCancel = {
                dismissCropOverlay()
                if (!reopenSettingsActionId.isNullOrBlank()) {
                    val tabIdx = if (AutomationCoordinator.activeMacro.value.isStuckAction(reopenSettingsActionId)) 2 else 1
                    showMacroSettingsPanel(initialTab = tabIdx, initialEditActionId = reopenSettingsActionId)
                }
            }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).also { applyFullScreenCutout(it) }

        if (safeAddView(view, params)) {
            cropOverlayView = view
        } else {
            cropScreenshot?.recycle()
            cropScreenshot = null
        }
    }

    private fun dismissCropOverlay() {
        cropOverlayView?.let {
            try { windowManager.removeView(it) } catch (_: Throwable) {}
        }
        cropOverlayView = null
        cropScreenshot?.recycle()
        cropScreenshot = null
    }

    // ---- Freeze-Proof Touch Recorder Overlay ----

    private fun openTouchRecorder() {
        if (recorderOverlayView != null) return
        if (isOverlayHidden) exitHideButtonsMode()
        AutomationCoordinator.stop()
        dismissMarkerEditor()
        dismissMacroSettingsPanel()
        setOverlayChromeVisibility(View.INVISIBLE)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).also { applyFullScreenCutout(it) }

        val view = OverlayRecorderView(
            context = this,
            onPassThroughGesture = { gesture -> forwardRecordedGesture(gesture) },
            onFinish = { gestures ->
                dismissTouchRecorder()
                if (gestures.isNotEmpty()) {
                    refreshScreenSize()
                    AutomationCoordinator.addRecordedGestures(gestures, screenWidth, screenHeight)
                    Toast.makeText(this, "Rekaman disimpan (${gestures.size} gestur)", Toast.LENGTH_SHORT).show()
                }
            },
            onCancel = { dismissTouchRecorder() }
        )

        if (safeAddView(view, params)) {
            recorderOverlayView = view
            recorderParams = params
        } else {
            setOverlayChromeVisibility(View.VISIBLE)
            applyMarkerVisibility()
        }
    }

    private fun forwardRecordedGesture(gesture: RecordedGesture) {
        val view = recorderOverlayView ?: return
        val params = recorderParams ?: return
        if (view.isForwardingGesture || !MacroAccessibilityService.isConnected.value) return
        view.isForwardingGesture = true
        serviceScope.launch {
            try {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                try { windowManager.updateViewLayout(view, params) } catch (_: Throwable) {}
                delay(25L)
                MacroAccessibilityService.dispatchRecordedGesture(gesture, 1f)
                delay(35L)
            } finally {
                view.isForwardingGesture = false
                if (recorderOverlayView === view) {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    try { windowManager.updateViewLayout(view, params) } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun dismissTouchRecorder() {
        recorderOverlayView?.let {
            try { windowManager.removeView(it) } catch (_: Throwable) {}
        }
        recorderOverlayView = null
        recorderParams = null
        setOverlayChromeVisibility(View.VISIBLE)
        applyMarkerVisibility()
    }

    private fun setOverlayChromeVisibility(visibility: Int) {
        controlBarView?.visibility = visibility
        logOverlayView?.visibility = visibility
        colorSpyView?.visibility = visibility
        swipePathView?.visibility = visibility
        markerViews.forEach { (v, _) -> v.visibility = visibility }
    }

    private fun applyMarkerVisibility() {
        val shouldShow = !isOverlayHidden && (showAllMarkersPreview || focusedMarkerActionId != null)
        val vis = if (shouldShow) View.VISIBLE else View.GONE
        swipePathView?.visibility = vis
        markerViews.forEach { (v, _) -> v.visibility = vis }
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

                canvas.drawLine(sx, sy, ex, ey, glowPaint)
                canvas.drawLine(sx, sy, ex, ey, linePaint)

                val angle = atan2(dy, dx)
                drawArrowHead(canvas, ex, ey, angle, 12f * d)

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
            ).also { applyFullScreenCutout(it) }
            if (safeAddView(v, params)) {
                swipePathView = v
            } else {
                return
            }
        }
        swipePathView?.setSegments(segments, isRunning)
        val shouldShow = !isOverlayHidden && (showAllMarkersPreview || focusedMarkerActionId != null)
        swipePathView?.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun removeSwipePathOverlay() {
        swipePathView?.let {
            try { windowManager.removeView(it) } catch (_: Throwable) {}
        }
        swipePathView = null
    }

    // ---- Live Match Bounding Box ----

    private fun updateMatchHighlight(highlight: MatchHighlight?) {
        if (highlight == null) {
            matchBoxView?.let {
                try { windowManager.removeView(it) } catch (_: Throwable) {}
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
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).also { applyFullScreenCutout(it) }
            if (safeAddView(v, params)) {
                matchBoxView = v
            } else {
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

        private val matchRect = RectF()
        private val labelRect = RectF()

        fun setHighlight(h: MatchHighlight) {
            current = h
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val h = current ?: return
            val m = h.match
            matchRect.set(m.x.toFloat(), m.y.toFloat(), (m.x + m.width).toFloat(), (m.y + m.height).toFloat())
            canvas.drawRect(matchRect, fillPaint)
            canvas.drawRect(matchRect, boxPaint)
            val tw = textPaint.measureText(h.label)
            val ty = (matchRect.top - 22f * d).coerceAtLeast(4f * d)
            labelRect.set(matchRect.left, ty, matchRect.left + tw + 12f * d, ty + 20f * d)
            canvas.drawRoundRect(labelRect, 4f * d, 4f * d, textBg)
            canvas.drawText(h.label, matchRect.left + 6f * d, ty + 14f * d, textPaint)
        }
    }

    // ---- Visual Target Markers ----

    private fun collectEnabledWithSlot(
        actions: List<Action>,
        enclosing: Slot? = null,
        out: MutableList<Pair<Action, Slot?>> = ArrayList()
    ): List<Pair<Action, Slot?>> {
        for (a in actions) {
            if (!a.enabled) continue
            out += a to enclosing
            when (a) {
                is ImageAction -> {
                    collectEnabledWithSlot(a.thenActions, Slot.THEN, out)
                    collectEnabledWithSlot(a.elseActions, Slot.ELSE, out)
                }
                is TextAction -> {
                    collectEnabledWithSlot(a.thenActions, Slot.THEN, out)
                    collectEnabledWithSlot(a.elseActions, Slot.ELSE, out)
                }
                is ColorAction -> {
                    collectEnabledWithSlot(a.thenActions, Slot.THEN, out)
                    collectEnabledWithSlot(a.elseActions, Slot.ELSE, out)
                }
                is LoopAction -> {
                    collectEnabledWithSlot(a.actions, Slot.BODY, out)
                }
                else -> Unit
            }
        }
        return out
    }

    private fun rebuildTargetMarkers(actions: List<Action>, isRunning: Boolean) {
        clearTargetMarkers()
        // Keep screen completely clean unless user is actively editing a target or turned on preview
        if (isRunning || (!showAllMarkersPreview && focusedMarkerActionId == null)) {
            return
        }
        refreshScreenSize()
        val scaleTransform = ResolutionScaler.forMacro(AutomationCoordinator.activeMacro.value, screenWidth, screenHeight)
        val markerSize = dp(36)
        val half = markerSize / 2
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        val allEntries = collectEnabledWithSlot(actions)
        val enabledEntries = if (!showAllMarkersPreview && focusedMarkerActionId != null) {
            allEntries.filter { it.first.id == focusedMarkerActionId }
        } else {
            allEntries
        }

        val swipeSegments = enabledEntries
            .map { it.first }
            .filterIsInstance<SwipeAction>()
            .map {
                val (sx1, sy1) = scaleTransform.scalePoint(it.x1, it.y1)
                val (sx2, sy2) = scaleTransform.scalePoint(it.x2, it.y2)
                SwipeSegment(it.id, sx1.toFloat(), sy1.toFloat(), sx2.toFloat(), sy2.toFloat())
            }
        updateSwipePathOverlay(swipeSegments, isRunning, half.toFloat())

        var markerNumber = 0
        enabledEntries.forEach { (action, slot) ->
            val prefix = when (slot) {
                Slot.THEN -> "✓"
                Slot.ELSE -> "✗"
                Slot.BODY -> "↻"
                null -> ""
            }
            val strokeHex = when (slot) {
                Slot.THEN -> "#10B981"
                Slot.ELSE -> "#F59E0B"
                Slot.BODY -> "#818CF8"
                null -> "#99F6E4"
            }
            when (action) {
                is ClickAction -> {
                    markerNumber++
                    val (sx, sy) = scaleTransform.scalePoint(action.x, action.y)
                    val fillHex = when (slot) {
                        Slot.THEN -> "#CC059669"
                        Slot.ELSE -> "#CCB45309"
                        Slot.BODY -> "#CC4338CA"
                        null -> "#CC0D9488"
                    }
                    addMarker(
                        label = "$prefix$markerNumber",
                        colorHex = fillHex,
                        strokeHex = strokeHex,
                        cx = sx,
                        cy = sy,
                        size = markerSize,
                        half = half,
                        flags = baseFlags,
                        isRunning = isRunning,
                        onTap = { showMarkerQuickEditor(action) },
                        onDragCommit = { nx, ny ->
                            AutomationCoordinator.updateMarkerPosition(action.id, nx, ny, false, screenWidth, screenHeight)
                        }
                    )
                }
                is LongPressAction -> {
                    markerNumber++
                    val (sx, sy) = scaleTransform.scalePoint(action.x, action.y)
                    addMarker(
                        label = "${prefix}${markerNumber}L",
                        colorHex = "#CC4F46E5",
                        strokeHex = strokeHex,
                        cx = sx,
                        cy = sy,
                        size = markerSize,
                        half = half,
                        flags = baseFlags,
                        isRunning = isRunning,
                        onTap = { showMarkerQuickEditor(action) },
                        onDragCommit = { nx, ny ->
                            AutomationCoordinator.updateMarkerPosition(action.id, nx, ny, false, screenWidth, screenHeight)
                        }
                    )
                }
                is SwipeAction -> {
                    markerNumber++
                    val (sx1, sy1) = scaleTransform.scalePoint(action.x1, action.y1)
                    val (sx2, sy2) = scaleTransform.scalePoint(action.x2, action.y2)
                    addMarker(
                        label = "${prefix}${markerNumber}A",
                        colorHex = "#CC0284C7",
                        strokeHex = strokeHex,
                        cx = sx1,
                        cy = sy1,
                        size = markerSize,
                        half = half,
                        flags = baseFlags,
                        isRunning = isRunning,
                        onTap = { showMarkerQuickEditor(action) },
                        onDragging = { nx, ny -> swipePathView?.updateEndpoint(action.id, nx.toFloat(), ny.toFloat(), isEndNode = false) },
                        onDragCommit = { nx, ny ->
                            AutomationCoordinator.updateMarkerPosition(action.id, nx, ny, isEndNode = false, screenWidth, screenHeight)
                        }
                    )
                    addMarker(
                        label = "${prefix}${markerNumber}B",
                        colorHex = "#CC0369A1",
                        strokeHex = strokeHex,
                        cx = sx2,
                        cy = sy2,
                        size = markerSize,
                        half = half,
                        flags = baseFlags,
                        isRunning = isRunning,
                        onTap = { showMarkerQuickEditor(action) },
                        onDragging = { nx, ny -> swipePathView?.updateEndpoint(action.id, nx.toFloat(), ny.toFloat(), isEndNode = true) },
                        onDragCommit = { nx, ny ->
                            AutomationCoordinator.updateMarkerPosition(action.id, nx, ny, isEndNode = true, screenWidth, screenHeight)
                        }
                    )
                }
                is ZoomAction -> {
                    markerNumber++
                    val (sx, sy) = scaleTransform.scalePoint(action.cx, action.cy)
                    addMarker(
                        label = if (action.zoomIn) "${prefix}Z+" else "${prefix}Z−",
                        colorHex = "#CC0891B2",
                        strokeHex = strokeHex,
                        cx = sx,
                        cy = sy,
                        size = markerSize,
                        half = half,
                        flags = baseFlags,
                        isRunning = isRunning,
                        onTap = { showMacroSettingsPanel(initialTab = 1, initialEditActionId = action.id) },
                        onDragCommit = { nx, ny ->
                            AutomationCoordinator.updateMarkerPosition(action.id, nx, ny, false, screenWidth, screenHeight)
                        }
                    )
                }
                is ColorAction -> {
                    markerNumber++
                    val (sx, sy) = scaleTransform.scalePoint(action.x, action.y)
                    addMarker(
                        label = "${prefix}${markerNumber}C",
                        colorHex = "#CC7C3AED",
                        strokeHex = strokeHex,
                        cx = sx,
                        cy = sy,
                        size = markerSize,
                        half = half,
                        flags = baseFlags,
                        isRunning = isRunning,
                        onTap = { showMarkerQuickEditor(action) },
                        onDragCommit = { nx, ny ->
                            AutomationCoordinator.updateMarkerPosition(action.id, nx, ny, false, screenWidth, screenHeight)
                        }
                    )
                }
                else -> Unit
            }
        }
        applyMarkerVisibility()
    }

    private fun addMarker(
        label: String,
        colorHex: String,
        strokeHex: String = "#99F6E4",
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
            textSize = if (label.length >= 3) 9.5f else 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            alpha = if (isRunning) 0.4f else 0.92f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(colorHex))
                setStroke(dp(2), Color.parseColor(strokeHex))
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
        if (safeAddView(view, params)) {
            markerViews.add(view to params)
        }
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
                        try { windowManager.updateViewLayout(view, params) } catch (_: Throwable) {}
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
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0.4f
            applySafeOverlayBounds(this, maxWidthDp = 280, maxHeightDp = 380, fixedHeight = false)
        }
        if (safeAddView(card, params)) {
            markerEditorView = card
            markerEditorParams = params
        }
    }

    private fun dismissMarkerEditor() {
        markerEditorView?.let {
            try { windowManager.removeView(it) } catch (_: Throwable) {}
        }
        markerEditorView = null
        markerEditorParams = null
    }

    private fun clearTargetMarkers() {
        markerViews.forEach { (view, _) ->
            try { windowManager.removeView(view) } catch (_: Throwable) {}
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
                setColor(Color.parseColor("#E00A1020"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#4422D3EE"))
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Wjust macro Live Log"
            setTextColor(Color.parseColor("#22D3EE"))
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
        if (safeAddView(root, params)) {
            logOverlayView = root
            logTextView = tv
            logScrollView = scroll
            isLogVisible = true
        }
    }

    private fun removeLogConsole() {
        logOverlayView?.let {
            try { windowManager.removeView(it) } catch (_: Throwable) {}
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
                setColor(Color.parseColor("#EB0A1020"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#22D3EE"))
            }
        }

        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(5) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#22D3EE"))
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

        root.addView(makeCompactButton("Salin", "#0891B2", marginEndDp = 4) {
            val p = colorSpyParams ?: return@makeCompactButton
            val hex = ScreenCaptureService.samplePixelHex(p.x, p.y) ?: "#000000"
            AutomationCoordinator.publishPickedPixel(p.x, p.y, hex)
            val clip = getSystemService(ClipboardManager::class.java)
            clip?.setPrimaryClip(ClipData.newPlainText("Wjust macro Color", "${p.x}, ${p.y}, \"$hex\""))
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
                } catch (_: Throwable) {}
            } else {
                colorSpyLabel?.text = "($nx,$ny) Aktifkan Layar"
            }
        }

        if (safeAddView(root, params)) {
            colorSpyView = root
            colorSpyParams = params
            isColorSpyVisible = true
            ScreenCaptureService.highFrequencyCapture = true
        }
    }

    private fun removeColorSpy() {
        colorSpyView?.let {
            try { windowManager.removeView(it) } catch (_: Throwable) {}
        }
        colorSpyView = null
        colorSpyParams = null
        colorSpySwatch = null
        colorSpyLabel = null
        isColorSpyVisible = false
        if (AutomationCoordinator.engineState.value != EngineState.RUNNING) {
            ScreenCaptureService.highFrequencyCapture = false
        }
    }

    override fun onDestroy() {
        AutomationCoordinator.setOverlayActive(false)
        hiddenBubbleView?.let {
            try { windowManager.removeView(it) } catch (_: Throwable) {}
        }
        hiddenBubbleView = null
        dismissMacroSettingsPanel()
        dismissCropOverlay()
        dismissTargetPicker()
        dismissTouchRecorder()
        dismissMarkerEditor()
        clearTargetMarkers()
        removeLogConsole()
        removeColorSpy()
        updateMatchHighlight(null)
        controlBarView?.let {
            try { windowManager.removeView(it) } catch (_: Throwable) {}
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
        const val ACTION_PICK_REGION = "com.example.action.PICK_REGION"
        const val ACTION_OPEN_RECORDER = "com.example.action.OPEN_RECORDER"
        const val ACTION_PICK_TARGET = "com.example.action.PICK_TARGET"
        const val EXTRA_ADD_TO_MACRO = "add_to_macro"
        const val EXTRA_TARGET_ACTION_ID = "target_action_id"
        const val EXTRA_TARGET_FOLDER = "target_folder"

        fun start(
            context: Context,
            action: String = ACTION_START,
            addToMacro: Boolean = true,
            targetActionId: String? = null,
            targetFolder: String = ""
        ) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                this.action = action
                putExtra(EXTRA_ADD_TO_MACRO, addToMacro)
                if (targetActionId != null) putExtra(EXTRA_TARGET_ACTION_ID, targetActionId)
                if (targetFolder.isNotBlank()) putExtra(EXTRA_TARGET_FOLDER, targetFolder)
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
