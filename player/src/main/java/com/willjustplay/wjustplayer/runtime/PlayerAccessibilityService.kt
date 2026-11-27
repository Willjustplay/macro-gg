package com.willjustplay.wjustplayer.runtime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.RecordedGesture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlin.math.roundToLong

class PlayerAccessibilityService : AccessibilityService() {

    private val gestureMutex = Mutex()
    @Volatile
    private var lastGestureEndUptimeMs: Long = 0L
    @Volatile
    private var cachedLauncherPackages: Set<String>? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        PlayerSafeLogBus.info("Accessibility Gesture Engine terhubung.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()?.trim().orEmpty()
        if (pkg.isBlank()) return
        if (isIgnoredSystemOrSelfPackage(pkg)) return
        lastForegroundPackage = pkg
    }

    private fun isIgnoredSystemOrSelfPackage(pkg: String): Boolean {
        if (pkg == packageName) return true
        if (pkg == "android" ||
            pkg == "com.android.systemui" ||
            pkg == "com.android.settings" ||
            pkg == "com.miui.securitycenter" ||
            pkg == "com.oplus.appdetail" ||
            pkg == "com.coloros.appdetail" ||
            pkg.contains("inputmethod", ignoreCase = true) ||
            pkg.contains("keyboard", ignoreCase = true) ||
            pkg.contains("gboard", ignoreCase = true) ||
            pkg.contains("launcher", ignoreCase = true) ||
            pkg.contains("trebuchet", ignoreCase = true) ||
            pkg.contains("nexuslauncher", ignoreCase = true) ||
            pkg.contains("quickstep", ignoreCase = true)
        ) {
            return true
        }
        val launchers = cachedLauncherPackages ?: resolveHomeLauncherPackages().also { cachedLauncherPackages = it }
        return pkg in launchers
    }

    private fun resolveHomeLauncherPackages(): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    override fun onInterrupt() {
        PlayerSafeLogBus.warn("Accessibility Gesture Engine diinterupsi sistem.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        _isConnected.value = false
        PlayerSafeLogBus.warn("Accessibility Gesture Engine terputus.")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        _isConnected.value = false
        super.onDestroy()
    }

    /**
     * Dispatches a single tap that reliably registers across Android views, Unity/Cocos2d/Supercell
     * OpenGL surfaces, and Redfinger cloud phones:
     * - Enforces a minimum 60ms press hold so DOWN and UP never collapse into a single 30 FPS game tick.
     * - Uses a 0.5px sub-slop micro-stroke so InputDispatcher emits a full DOWN -> MOVE -> UP sequence.
     * - Serializes via [gestureMutex] with a 22ms inter-gesture cooldown and retries up to 2x if the OS
     *   cancels the gesture (e.g. immediately after tapping Play on the floating bar).
     */
    suspend fun performClick(x: Float, y: Float, durationMs: Long): Boolean {
        val safeDuration = durationMs.coerceIn(MIN_RELIABLE_TAP_MS, GestureDescription.getMaxGestureDuration())
        val sx = if (x.isFinite()) x.coerceAtLeast(1f) else 1f
        val sy = if (y.isFinite()) y.coerceAtLeast(1f) else 1f
        val buildGesture = {
            val path = Path().apply {
                moveTo(sx, sy)
                lineTo(sx + 0.5f, sy + 0.5f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
            GestureDescription.Builder().addStroke(stroke).build()
        }
        return dispatchWithRetry(
            label = "Klik (${sx.toInt()},${sy.toInt()})",
            expectedDurationMs = safeDuration,
            maxRetries = 2,
            buildGesture = buildGesture
        )
    }

    suspend fun performSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ): Boolean {
        val safeDuration = durationMs.coerceIn(20L, GestureDescription.getMaxGestureDuration())
        val sx1 = if (x1.isFinite()) x1.coerceAtLeast(1f) else 1f
        val sy1 = if (y1.isFinite()) y1.coerceAtLeast(1f) else 1f
        val sx2 = if (x2.isFinite()) x2.coerceAtLeast(1f) else sx1
        val sy2 = if (y2.isFinite()) y2.coerceAtLeast(1f) else sy1
        val buildGesture = {
            val path = Path().apply {
                moveTo(sx1, sy1)
                if (hypot((sx2 - sx1).toDouble(), (sy2 - sy1).toDouble()) >= 2.0) {
                    lineTo(sx2, sy2)
                } else {
                    lineTo(sx1 + 0.5f, sy1 + 0.5f)
                }
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
            GestureDescription.Builder().addStroke(stroke).build()
        }
        return dispatchWithRetry(
            label = "Swipe (${sx1.toInt()},${sy1.toInt()})→(${sx2.toInt()},${sy2.toInt()})",
            expectedDurationMs = safeDuration,
            maxRetries = 1,
            buildGesture = buildGesture
        )
    }

    /** Dispatches a smooth quadratic Bezier curved swipe `(x1,y1) -> (cx,cy) -> (x2,y2)` for Humanizer mode. */
    suspend fun performCurvedSwipe(
        x1: Float,
        y1: Float,
        cx: Float,
        cy: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ): Boolean {
        val safeDuration = durationMs.coerceIn(25L, GestureDescription.getMaxGestureDuration())
        val sx1 = if (x1.isFinite()) x1.coerceAtLeast(1f) else 1f
        val sy1 = if (y1.isFinite()) y1.coerceAtLeast(1f) else 1f
        val scx = if (cx.isFinite()) cx.coerceAtLeast(1f) else sx1
        val scy = if (cy.isFinite()) cy.coerceAtLeast(1f) else sy1
        val sx2 = if (x2.isFinite()) x2.coerceAtLeast(1f) else sx1
        val sy2 = if (y2.isFinite()) y2.coerceAtLeast(1f) else sy1
        val buildGesture = {
            val path = Path().apply {
                moveTo(sx1, sy1)
                if (hypot((sx2 - sx1).toDouble(), (sy2 - sy1).toDouble()) >= 2.0) {
                    quadTo(scx, scy, sx2, sy2)
                } else {
                    lineTo(sx1 + 0.5f, sy1 + 0.5f)
                }
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
            GestureDescription.Builder().addStroke(stroke).build()
        }
        return dispatchWithRetry(
            label = "Swipe Bezier (${sx1.toInt()},${sy1.toInt()})→(${sx2.toInt()},${sy2.toInt()})",
            expectedDurationMs = safeDuration,
            maxRetries = 1,
            buildGesture = buildGesture
        )
    }

    /**
     * Dispatches a 2-finger diagonal pinch gesture centered at ([cx], [cy]).
     * [zoomIn] = true spreads fingers apart; false pinches fingers together.
     */
    suspend fun performZoom(
        cx: Float,
        cy: Float,
        zoomIn: Boolean,
        distancePx: Int,
        durationMs: Long
    ): Boolean {
        val safeDuration = durationMs.coerceIn(60L, GestureDescription.getMaxGestureDuration())
        val buildGesture = {
            val centerX = if (cx.isFinite()) cx.coerceAtLeast(60f) else 540f
            val centerY = if (cy.isFinite()) cy.coerceAtLeast(60f) else 960f
            val nearOffset = 45f
            val farOffset = (distancePx.coerceIn(80, 900) / 2f).coerceAtLeast(nearOffset + 40f)
            val startOff = if (zoomIn) nearOffset else farOffset
            val endOff = if (zoomIn) farOffset else nearOffset

            val f1StartX = (centerX - startOff).coerceAtLeast(2f)
            val f1StartY = (centerY - startOff).coerceAtLeast(2f)
            val f1EndX = (centerX - endOff).coerceAtLeast(2f)
            val f1EndY = (centerY - endOff).coerceAtLeast(2f)

            val f2StartX = (centerX + startOff).coerceAtLeast(2f)
            val f2StartY = (centerY + startOff).coerceAtLeast(2f)
            val f2EndX = (centerX + endOff).coerceAtLeast(2f)
            val f2EndY = (centerY + endOff).coerceAtLeast(2f)

            val p1 = Path().apply {
                moveTo(f1StartX, f1StartY)
                lineTo(f1EndX, f1EndY)
            }
            val p2 = Path().apply {
                moveTo(f2StartX, f2StartY)
                lineTo(f2EndX, f2EndY)
            }

            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p1, 0L, safeDuration))
                .addStroke(GestureDescription.StrokeDescription(p2, 0L, safeDuration))
                .build()
        }
        return dispatchWithRetry(
            label = "Zoom",
            expectedDurationMs = safeDuration,
            maxRetries = 1,
            buildGesture = buildGesture
        )
    }

    suspend fun performRecordedGesture(recorded: RecordedGesture, speed: Float): Boolean {
        if (recorded.strokes.isEmpty()) return true
        var maxEndMs = 50L
        val safeSpeed = speed.coerceIn(0.25f, 5f)
        val maxDur = GestureDescription.getMaxGestureDuration()
        val builtOnce = try {
            val builder = GestureDescription.Builder()
            var added = 0
            for (s in recorded.strokes.take(GestureDescription.getMaxStrokeCount())) {
                if (s.points.size < 2) continue
                val startX = s.points[0].coerceAtLeast(1).toFloat()
                val startY = s.points[1].coerceAtLeast(1).toFloat()
                val path = Path().apply { moveTo(startX, startY) }
                var prevX = startX
                var prevY = startY
                var moved = false
                var i = 2
                while (i + 1 < s.points.size) {
                    val nx = s.points[i].coerceAtLeast(1).toFloat()
                    val ny = s.points[i + 1].coerceAtLeast(1).toFloat()
                    if (hypot((nx - prevX).toDouble(), (ny - prevY).toDouble()) >= 2.0) {
                        path.lineTo(nx, ny)
                        prevX = nx
                        prevY = ny
                        moved = true
                    }
                    i += 2
                }
                if (!moved) {
                    path.lineTo(startX + 0.5f, startY + 0.5f)
                }
                val start = (s.startMs / safeSpeed).roundToLong().coerceIn(0L, (maxDur - 20L).coerceAtLeast(0L))
                val dur = (s.durationMs / safeSpeed).roundToLong().coerceIn(MIN_RELIABLE_TAP_MS, (maxDur - start).coerceAtLeast(MIN_RELIABLE_TAP_MS))
                if (start + dur > maxEndMs) maxEndMs = start + dur
                builder.addStroke(GestureDescription.StrokeDescription(path, start, dur))
                added++
            }
            if (added == 0) return true
            builder.build()
        } catch (t: Throwable) {
            PlayerSafeLogBus.error("Gestur rekaman tidak valid: ${t.message}")
            return false
        }
        return dispatchWithRetry(
            label = "Rekaman",
            expectedDurationMs = maxEndMs,
            maxRetries = 1,
            buildGesture = { builtOnce }
        )
    }

    private suspend fun dispatchWithRetry(
        label: String,
        expectedDurationMs: Long,
        maxRetries: Int,
        buildGesture: () -> GestureDescription
    ): Boolean = gestureMutex.withLock {
        val totalAttempts = maxRetries.coerceAtLeast(0) + 1
        for (attempt in 1..totalAttempts) {
            val sinceLast = SystemClock.uptimeMillis() - lastGestureEndUptimeMs
            if (sinceLast in 0L until INTER_GESTURE_GAP_MS) {
                delay(INTER_GESTURE_GAP_MS - sinceLast)
            }
            val gesture = try {
                buildGesture()
            } catch (t: Throwable) {
                PlayerSafeLogBus.error("Gestur $label tidak valid: ${t.message}")
                return@withLock false
            }
            val ok = dispatchGestureSuspended(gesture, expectedDurationMs)
            lastGestureEndUptimeMs = SystemClock.uptimeMillis()
            if (ok) return@withLock true
            if (attempt < totalAttempts) {
                delay(75L * attempt)
            }
        }
        PlayerSafeLogBus.warn("$label dibatalkan oleh sistem setelah $totalAttempts× percobaan.")
        false
    }

    /**
     * Dispatches gesture on Main thread with a strict timeout guard so a dropped OS callback
     * can never hang the touch recorder or freeze a running macro.
     */
    private suspend fun dispatchGestureSuspended(gesture: GestureDescription, expectedDurationMs: Long): Boolean {
        val timeoutMs = (expectedDurationMs + 1500L).coerceIn(1500L, 65_000L)
        val completed = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    val callback = object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                    val dispatched = try {
                        dispatchGesture(gesture, callback, null)
                    } catch (t: Throwable) {
                        PlayerSafeLogBus.error("Gagal mengirim gestur: ${t.message}")
                        false
                    }
                    if (!dispatched && continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }
        }
        if (completed == null) {
            PlayerSafeLogBus.warn("Timeout menunggu respons gestur sistem (${timeoutMs}ms) — dilanjutkan otomatis.")
            return false
        }
        return completed
    }

    // ---- Background App Termination via Android App Info (Force Stop -> OK) ----

    private fun collectCandidateRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        runCatching {
            windows?.forEach { w ->
                w.root?.let { roots += it }
            }
        }
        if (roots.isEmpty()) {
            runCatching { rootInActiveWindow }.getOrNull()?.let { roots += it }
        }
        return roots
    }

    private fun collectAllNodes(root: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, limit: Int = 220) {
        if (out.size >= limit) return
        out += root
        for (i in 0 until root.childCount) {
            if (out.size >= limit) return
            val child = root.getChild(i) ?: continue
            collectAllNodes(child, out, limit)
        }
    }

    private fun nodeCombinedLabel(node: AccessibilityNodeInfo): String {
        val t = node.text?.toString()?.trim().orEmpty()
        val d = node.contentDescription?.toString()?.trim().orEmpty()
        return "$t $d".trim().lowercase()
    }

    private fun isForbiddenButtonLabel(labelLower: String): Boolean {
        if (labelLower.isBlank()) return false
        return FORBIDDEN_SETTINGS_KEYWORDS.any { labelLower.contains(it) }
    }

    private fun clickableSelfOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 4) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    private fun isNodeEffectivelyEnabled(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled) return false
        val clickable = clickableSelfOrParent(node)
        return clickable == null || clickable.isEnabled
    }

    private suspend fun clickNodeOrFallbackTap(node: AccessibilityNodeInfo): Boolean {
        val target = clickableSelfOrParent(node) ?: node
        val clickedByAction = withContext(Dispatchers.Main.immediate) {
            runCatching {
                target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
        }
        if (clickedByAction) return true
        val rect = Rect()
        target.getBoundsInScreen(rect)
        if (!rect.isEmpty && rect.width() > 4 && rect.height() > 4) {
            return performClick(rect.exactCenterX(), rect.exactCenterY(), 80L)
        }
        return false
    }

    /**
     * Opens Android's App Info screen for [targetPackage], locates the "Force stop / Paksa berhenti"
     * button, clicks it, confirms the dialog ("OK / Paksa berhenti"), and returns true so the app's
     * background process is 100% terminated on Android 7–16 & Redfinger.
     */
    suspend fun performForceStopViaSettings(targetPackage: String): Boolean {
        val cleanPkg = targetPackage.trim()
        if (cleanPkg.isBlank() || cleanPkg == packageName) return false

        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$cleanPkg")
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        val opened = withContext(Dispatchers.Main.immediate) {
            runCatching {
                startActivity(intent)
                true
            }.getOrDefault(false)
        }
        if (!opened) return false

        // Wait up to ~2.1s for App Info screen to render and locate Force Stop button
        var forceStopNode: AccessibilityNodeInfo? = null
        var alreadyStopped = false
        for (poll in 0 until 14) {
            delay(150L)
            val found = withContext(Dispatchers.Main.immediate) {
                findForceStopButton()
            }
            if (found != null) {
                if (!isNodeEffectivelyEnabled(found)) {
                    alreadyStopped = true
                } else {
                    forceStopNode = found
                }
                break
            }
        }

        if (alreadyStopped) {
            PlayerSafeLogBus.info("Aplikasi '$cleanPkg' sudah berhenti total di latar belakang.")
            return true
        }
        val btn = forceStopNode ?: run {
            PlayerSafeLogBus.warn("Tombol 'Paksa Berhenti' tidak ditemukan di Info Aplikasi untuk '$cleanPkg'.")
            return false
        }

        if (!clickNodeOrFallbackTap(btn)) {
            PlayerSafeLogBus.warn("Gagal menekan tombol 'Paksa Berhenti' untuk '$cleanPkg'.")
            return false
        }

        // Wait up to ~1.5s for confirmation dialog and tap OK / Paksa berhenti
        for (poll in 0 until 11) {
            delay(135L)
            val confirmBtn = withContext(Dispatchers.Main.immediate) {
                findForceStopConfirmButton()
            }
            if (confirmBtn != null) {
                val confirmed = clickNodeOrFallbackTap(confirmBtn)
                if (confirmed) {
                    delay(320L)
                    PlayerSafeLogBus.info("Paksa Berhenti berhasil untuk '$cleanPkg' — latar belakang dibersihkan.")
                    return true
                }
            }
        }

        PlayerSafeLogBus.info("Permintaan Paksa Berhenti terkirim untuk '$cleanPkg'.")
        return true
    }

    private fun findForceStopButton(): AccessibilityNodeInfo? {
        val roots = collectCandidateRoots()
        val allNodes = ArrayList<AccessibilityNodeInfo>()
        roots.forEach { collectAllNodes(it, allNodes) }

        // 1. Explicit text / contentDescription match (most reliable across all OEMs & Android versions)
        for (n in allNodes) {
            val label = nodeCombinedLabel(n)
            if (label.isBlank() || isForbiddenButtonLabel(label)) continue
            if (FORCE_STOP_KEYWORDS.any { label == it || label.contains(it) }) {
                return clickableSelfOrParent(n) ?: n
            }
        }

        // 2. Fallback: known Force Stop resource IDs (strictly guarded by safety denylist)
        for (root in roots) {
            for (viewId in FORCE_STOP_VIEW_IDS) {
                val matches = runCatching { root.findAccessibilityNodeInfosByViewId(viewId) }.getOrNull().orEmpty()
                for (n in matches) {
                    val label = nodeCombinedLabel(n)
                    if (isForbiddenButtonLabel(label)) continue
                    val isExplicitForceStopId = viewId.contains("force_stop", ignoreCase = true)
                    if (isExplicitForceStopId || label.contains("stop") || label.contains("henti") || label.contains("tutup")) {
                        return clickableSelfOrParent(n) ?: n
                    }
                }
            }
        }
        return null
    }

    private fun findForceStopConfirmButton(): AccessibilityNodeInfo? {
        val roots = collectCandidateRoots()
        // 1. Standard Android AlertDialog positive button (android:id/button1)
        for (root in roots) {
            val btn1List = runCatching { root.findAccessibilityNodeInfosByViewId("android:id/button1") }.getOrNull().orEmpty()
            for (n in btn1List) {
                val label = nodeCombinedLabel(n)
                if (!n.isEnabled || isForbiddenButtonLabel(label)) continue
                if (CANCEL_KEYWORDS.any { label == it || label.contains(it) }) continue
                if (label.isBlank() || CONFIRM_KEYWORDS.any { label == it || label.contains(it) }) {
                    return clickableSelfOrParent(n) ?: n
                }
            }
        }

        // 2. OEM dialog positive buttons matched by exact localized text
        val allNodes = ArrayList<AccessibilityNodeInfo>()
        roots.forEach { collectAllNodes(it, allNodes) }
        for (n in allNodes) {
            if (!n.isEnabled) continue
            val cls = n.className?.toString().orEmpty()
            val isBtnLike = cls.contains("Button", ignoreCase = true) || n.isClickable
            if (!isBtnLike) continue
            val label = nodeCombinedLabel(n)
            if (label.isBlank() || isForbiddenButtonLabel(label)) continue
            if (CANCEL_KEYWORDS.any { label == it || label.contains(it) }) continue
            if (CONFIRM_KEYWORDS.any { label == it }) {
                return clickableSelfOrParent(n) ?: n
            }
        }
        return null
    }

    /**
     * Attempts direct Accessibility `ACTION_DISMISS` on a Recent Apps task card matching [targetAppLabel],
     * if the device's launcher exposes dismissible task cards. Returns false to fall back to fling swipe.
     */
    suspend fun tryDismissRecentCardByAccessibility(targetAppLabel: String): Boolean {
        val needle = targetAppLabel.trim().lowercase()
        if (needle.isBlank()) return false
        return withContext(Dispatchers.Main.immediate) {
            val roots = collectCandidateRoots()
            val allNodes = ArrayList<AccessibilityNodeInfo>()
            roots.forEach { collectAllNodes(it, allNodes, limit = 160) }
            for (n in allNodes) {
                val label = nodeCombinedLabel(n)
                if (!label.contains(needle)) continue
                val supportsDismiss = n.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS } == true
                if (supportsDismiss) {
                    val ok = runCatching { n.performAction(AccessibilityNodeInfo.ACTION_DISMISS) }.getOrDefault(false)
                    if (ok) return@withContext true
                }
            }
            false
        }
    }

    companion object {
        private const val MIN_RELIABLE_TAP_MS = 60L
        private const val INTER_GESTURE_GAP_MS = 22L

        private val FORCE_STOP_KEYWORDS = listOf(
            "paksa berhenti",
            "hentikan paksa",
            "berhenti paksa",
            "tutup paksa",
            "force stop",
            "henti paksa",
            "paksa henti"
        )

        private val FORCE_STOP_VIEW_IDS = listOf(
            "com.android.settings:id/force_stop_button",
            "com.miui.securitycenter:id/am_force_stop",
            "com.oplus.appdetail:id/force_stop",
            "com.coloros.appdetail:id/force_stop",
            "com.android.settings:id/right_button",
            "com.android.settings:id/button3",
            "com.android.settings:id/button2"
        )

        private val FORBIDDEN_SETTINGS_KEYWORDS = listOf(
            "uninstall",
            "copot",
            "hapus instalan",
            "disable",
            "nonaktif",
            "open",
            "buka",
            "hapus data",
            "clear data",
            "clear storage"
        )

        private val CONFIRM_KEYWORDS = listOf(
            "ok",
            "oke",
            "ya",
            "yes",
            "paksa berhenti",
            "hentikan paksa",
            "berhenti paksa",
            "tutup paksa",
            "force stop",
            "hentikan",
            "stop"
        )

        private val CANCEL_KEYWORDS = listOf("batal", "cancel", "tidak", "no", "kembali")

        @Volatile
        private var instance: PlayerAccessibilityService? = null

        @Volatile
        var lastForegroundPackage: String = ""
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) return true
            return try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val expected = ComponentName(context, PlayerAccessibilityService::class.java)
                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(enabledServices)
                while (splitter.hasNext()) {
                    val component = ComponentName.unflattenFromString(splitter.next())
                    if (component == expected) return true
                }
                false
            } catch (_: Throwable) {
                false
            }
        }

        suspend fun dispatchClick(x: Float, y: Float, durationMs: Long): Boolean {
            val svc = instance ?: return false
            return svc.performClick(x, y, durationMs)
        }

        suspend fun dispatchSwipe(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            durationMs: Long
        ): Boolean {
            val svc = instance ?: return false
            return svc.performSwipe(x1, y1, x2, y2, durationMs)
        }

        suspend fun dispatchCurvedSwipe(
            x1: Float,
            y1: Float,
            cx: Float,
            cy: Float,
            x2: Float,
            y2: Float,
            durationMs: Long
        ): Boolean {
            val svc = instance ?: return false
            return svc.performCurvedSwipe(x1, y1, cx, cy, x2, y2, durationMs)
        }

        suspend fun dispatchZoom(
            cx: Float,
            cy: Float,
            zoomIn: Boolean,
            distancePx: Int,
            durationMs: Long
        ): Boolean {
            val svc = instance ?: return false
            return svc.performZoom(cx, cy, zoomIn, distancePx, durationMs)
        }

        suspend fun dispatchRecordedGesture(gesture: RecordedGesture, speed: Float): Boolean {
            val svc = instance ?: return false
            return svc.performRecordedGesture(gesture, speed)
        }

        suspend fun dispatchGlobalHome(): Boolean {
            val svc = instance ?: return false
            return withContext(Dispatchers.Main.immediate) {
                runCatching { svc.performGlobalAction(GLOBAL_ACTION_HOME) }.getOrDefault(false)
            }
        }

        suspend fun dispatchGlobalBack(): Boolean {
            val svc = instance ?: return false
            return withContext(Dispatchers.Main.immediate) {
                runCatching { svc.performGlobalAction(GLOBAL_ACTION_BACK) }.getOrDefault(false)
            }
        }

        suspend fun dispatchGlobalRecents(): Boolean {
            val svc = instance ?: return false
            return withContext(Dispatchers.Main.immediate) {
                runCatching { svc.performGlobalAction(GLOBAL_ACTION_RECENTS) }.getOrDefault(false)
            }
        }

        suspend fun forceStopPackageViaSettings(packageName: String): Boolean {
            val svc = instance ?: return false
            return svc.performForceStopViaSettings(packageName)
        }

        suspend fun tryDismissRecentCard(targetAppLabel: String): Boolean {
            val svc = instance ?: return false
            return svc.tryDismissRecentCardByAccessibility(targetAppLabel)
        }

        /**
         * Launches an activity intent using the active AccessibilityService context, which is
         * exempt from Android 10–16 Background Activity Launch restrictions.
         */
        suspend fun launchActivityIntent(intent: Intent): Boolean {
            val svc = instance ?: return false
            return withContext(Dispatchers.Main.immediate) {
                runCatching {
                    svc.startActivity(intent)
                    true
                }.getOrDefault(false)
            }
        }
    }
}
