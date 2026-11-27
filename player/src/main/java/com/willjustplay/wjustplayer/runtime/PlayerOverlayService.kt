package com.willjustplay.wjustplayer.runtime

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import android.text.TextUtils
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
import com.willjustplay.wjustplayer.R
import com.willjustplay.wjustplayer.ui.PlayerMainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Play-Only floating control bar for Wjust Player.
 * Contains ONLY playback & safe status controls: Play/Pause, Stop, Hide Bar, Safe Log, Home, Close.
 * Intentionally omits all macro creation, editing, target markers, crop, record, and settings controls.
 */
class PlayerOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var controlBarView: View? = null
    private var controlBarParams: WindowManager.LayoutParams? = null
    private var playPauseBtn: TextView? = null
    private var titleLabel: TextView? = null
    private var statusDot: View? = null
    private var activeAccountRow: LinearLayout? = null
    private var activeAccountBadgeView: TextView? = null

    private var hiddenBubbleView: View? = null
    private var logOverlayView: View? = null
    private var logTextView: TextView? = null
    private var logScrollView: ScrollView? = null

    private var isOverlayHidden = false
    private var screenWidth = 1080
    private var screenHeight = 2400

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        PlayerCoordinator.attach(this)
        refreshScreenSize()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            PlayerCoordinator.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Aktifkan izin Overlay (Tampilkan di atas aplikasi lain) terlebih dahulu.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!startForegroundSafe()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (controlBarView == null) {
            buildControlBar()
            observeCoordinator()
            PlayerCoordinator.setOverlayActive(true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        PlayerCoordinator.setOverlayActive(false)
        serviceScope.cancel()
        removeViewSafe(controlBarView)
        removeViewSafe(hiddenBubbleView)
        removeViewSafe(logOverlayView)
        controlBarView = null
        hiddenBubbleView = null
        logOverlayView = null
        super.onDestroy()
    }

    private fun startForegroundSafe(): Boolean {
        return runCatching {
            val mgr = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_overlay),
                    NotificationManager.IMPORTANCE_LOW
                )
                mgr.createNotificationChannel(ch)
            }
            val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_player)
                .setContentTitle(getString(R.string.notif_overlay_title))
                .setContentText(getString(R.string.notif_overlay_text))
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            true
        }.getOrDefault(false)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun refreshScreenSize() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = windowManager.maximumWindowMetrics.bounds
                screenWidth = b.width().coerceAtLeast(1)
                screenHeight = b.height().coerceAtLeast(1)
            } else {
                val m = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(m)
                screenWidth = m.widthPixels.coerceAtLeast(1)
                screenHeight = m.heightPixels.coerceAtLeast(1)
            }
        }
    }

    private fun safeAddView(view: View, params: WindowManager.LayoutParams): Boolean {
        return runCatching {
            windowManager.addView(view, params)
            true
        }.getOrDefault(false)
    }

    private fun removeViewSafe(view: View?) {
        if (view != null) {
            runCatching { windowManager.removeView(view) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildControlBar() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(6), dp(7), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E80B1224"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#22D3EE"))
            }
            elevation = dp(8).toFloat()
        }

        // Compact header showing active macro title + status dot (drag handle)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(5) }
        }
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(5) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#94A3B8"))
            }
        }
        statusDot = dot
        val title = TextView(this).apply {
            text = PlayerCoordinator.activeMacro.value?.name ?: "Wjust Player"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(180)
        }
        titleLabel = title
        headerRow.addView(dot)
        headerRow.addView(title)
        root.addView(headerRow)

        val accountRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(6), dp(3), dp(4), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#11263F"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#0284C7"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(5) }
        }
        val accountBadge = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#7DD3FC"))
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            }
        }
        val skipAccBtn = TextView(this).apply {
            text = "⏭"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0284C7"))
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener {
                PlayerCoordinator.requestSkipAccount()
                Toast.makeText(this@PlayerOverlayService, "Pindah ke akun berikutnya...", Toast.LENGTH_SHORT).show()
            }
        }
        accountRow.addView(accountBadge)
        accountRow.addView(skipAccBtn)
        activeAccountRow = accountRow
        activeAccountBadgeView = accountBadge
        root.addView(accountRow)

        // Single clean playback row: [▶/⏸] [■] [👁] [≡] [⌂] [✕]
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnPlay = makeBtn("▶", "#059669") {
            refreshScreenSize()
            val state = PlayerCoordinator.engineState.value
            val active = PlayerCoordinator.activeMacro.value
            if ((state == PlayerEngineState.IDLE || state == PlayerEngineState.ERROR) &&
                active != null &&
                PlayerCoordinator.macroNeedsScreenCapture(active) &&
                !PlayerScreenCaptureService.isCapturing.value
            ) {
                PlayerScreenCapturePermissionActivity.request(this) {
                    serviceScope.launch {
                        PlayerCoordinator.togglePlayPause(screenWidth, screenHeight)
                    }
                }
            } else {
                PlayerCoordinator.togglePlayPause(screenWidth, screenHeight)
            }
        }
        playPauseBtn = btnPlay
        row.addView(btnPlay)

        row.addView(makeBtn("■", "#DC2626") {
            PlayerCoordinator.stop()
        })

        row.addView(makeBtn("👁", "#475569") {
            enterHideButtonsMode()
        })

        row.addView(makeBtn("≡", "#334155") {
            toggleLogConsole()
        })

        row.addView(makeBtn("⌂", "#1E293B") {
            val intent = Intent(this, PlayerMainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        })

        row.addView(makeBtn("✕", "#1E293B", marginEndDp = 0) {
            PlayerCoordinator.stop()
            stopSelf()
        })

        root.addView(row)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(90)
        }

        attachDrag(root, params)
        if (safeAddView(root, params)) {
            controlBarView = root
            controlBarParams = params
        }
    }

    private fun makeBtn(symbol: String, hexColor: String, marginEndDp: Int = 4, onClick: () -> Unit): TextView {
        val size = dp(32)
        return TextView(this).apply {
            text = symbol
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(hexColor))
                cornerRadius = dp(9).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                if (marginEndDp > 0) marginEnd = dp(marginEndDp)
            }
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(view: View, params: WindowManager.LayoutParams) {
        var initX = 0
        var initY = 0
        var tx = 0f
        var ty = 0f
        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    tx = ev.rawX
                    ty = ev.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - tx).toInt()
                    val dy = (ev.rawY - ty).toInt()
                    if (abs(dx) > 6 || abs(dy) > 6) {
                        params.x = (initX + dx).coerceIn(0, (screenWidth - dp(40)).coerceAtLeast(0))
                        params.y = (initY + dy).coerceIn(0, (screenHeight - dp(40)).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(view, params) }
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enterHideButtonsMode() {
        if (isOverlayHidden) return
        isOverlayHidden = true
        controlBarView?.visibility = View.GONE
        logOverlayView?.visibility = View.GONE

        val bubbleSize = dp(32)
        val bubble = TextView(this).apply {
            text = "👁"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            alpha = 0.5f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC0B1224"))
                setStroke(dp(1), Color.parseColor("#22D3EE"))
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
            x = (controlBarParams?.x ?: dp(10)).coerceIn(0, (screenWidth - bubbleSize).coerceAtLeast(0))
            y = (controlBarParams?.y ?: dp(90)).coerceIn(0, (screenHeight - bubbleSize).coerceAtLeast(0))
        }
        var initX = 0
        var initY = 0
        var tx = 0f
        var ty = 0f
        var dragged = false
        bubble.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y; tx = ev.rawX; ty = ev.rawY; dragged = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - tx).toInt()
                    val dy = (ev.rawY - ty).toInt()
                    if (abs(dx) > 6 || abs(dy) > 6) {
                        dragged = true
                        params.x = (initX + dx).coerceIn(0, (screenWidth - bubbleSize).coerceAtLeast(0))
                        params.y = (initY + dy).coerceIn(0, (screenHeight - bubbleSize).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(bubble, params) }
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
        } else {
            exitHideButtonsMode()
        }
    }

    private fun exitHideButtonsMode() {
        isOverlayHidden = false
        removeViewSafe(hiddenBubbleView)
        hiddenBubbleView = null
        controlBarView?.visibility = View.VISIBLE
        logOverlayView?.visibility = View.VISIBLE
    }

    private fun toggleLogConsole() {
        if (logOverlayView != null) {
            removeViewSafe(logOverlayView)
            logOverlayView = null
            logTextView = null
            logScrollView = null
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E00B1224"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Status Wjust Player"
            setTextColor(Color.parseColor("#22D3EE"))
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(dp(6), dp(2), dp(4), dp(2))
            setOnClickListener { toggleLogConsole() }
        })
        box.addView(header)

        val tv = TextView(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
        }
        logTextView = tv
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(230), dp(110))
            addView(tv)
        }
        logScrollView = scroll
        box.addView(scroll)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = controlBarParams?.x ?: dp(12)
            y = ((controlBarParams?.y ?: dp(90)) + dp(74)).coerceAtMost((screenHeight - dp(150)).coerceAtLeast(0))
        }
        attachDrag(box, params)
        if (safeAddView(box, params)) {
            logOverlayView = box
            renderLogs(PlayerSafeLogBus.logs.value)
        }
    }

    private fun renderLogs(entries: List<SafeLogEntry>) {
        val tv = logTextView ?: return
        val text = if (entries.isEmpty()) {
            "Siap menjalankan macro."
        } else {
            entries.takeLast(14).joinToString("\n") { "[${it.timestamp}] ${it.message}" }
        }
        tv.text = text
        logScrollView?.post { logScrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun observeCoordinator() {
        serviceScope.launch {
            PlayerCoordinator.engineState.collectLatest { st ->
                val playText = if (st == PlayerEngineState.RUNNING) "⏸" else "▶"
                val playColor = when (st) {
                    PlayerEngineState.RUNNING -> "#D97706"
                    else -> "#059669"
                }
                playPauseBtn?.apply {
                    text = playText
                    (background as? GradientDrawable)?.setColor(Color.parseColor(playColor))
                }
                val dotColor = when (st) {
                    PlayerEngineState.RUNNING -> "#10B981"
                    PlayerEngineState.PAUSED -> "#F59E0B"
                    PlayerEngineState.ERROR -> "#EF4444"
                    PlayerEngineState.IDLE -> "#22D3EE"
                }
                (statusDot?.background as? GradientDrawable)?.setColor(Color.parseColor(dotColor))
            }
        }
        serviceScope.launch {
            PlayerCoordinator.activeMacro.collectLatest { m ->
                titleLabel?.text = m?.name?.ifBlank { "Wjust Player" } ?: "Wjust Player"
            }
        }
        serviceScope.launch {
            PlayerCoordinator.activeAccountLabel.collectLatest { label ->
                if (label.isBlank()) {
                    activeAccountRow?.visibility = View.GONE
                } else {
                    activeAccountBadgeView?.text = "👤 $label"
                    activeAccountRow?.visibility = View.VISIBLE
                }
            }
        }
        serviceScope.launch {
            PlayerSafeLogBus.logs.collectLatest { list ->
                renderLogs(list)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "wjust_player_overlay_ch"
        private const val NOTIF_ID = 4102
        const val ACTION_START = "com.willjustplay.wjustplayer.action.START_OVERLAY"
        const val ACTION_STOP = "com.willjustplay.wjustplayer.action.STOP_OVERLAY"

        fun start(context: Context) {
            val intent = Intent(context, PlayerOverlayService::class.java).apply { action = ACTION_START }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, PlayerOverlayService::class.java))
            }
        }
    }
}
