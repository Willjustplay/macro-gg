package com.example.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import com.example.data.RecordedGesture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlin.math.roundToLong

class MacroAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        ExecutionLogBus.info("Accessibility Gesture Engine terhubung.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Gesture-only service; no UI tree scraping performed.
    }

    override fun onInterrupt() {
        ExecutionLogBus.warn("Accessibility Gesture Engine diinterupsi sistem.")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        _isConnected.value = false
        ExecutionLogBus.warn("Accessibility Gesture Engine terputus.")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        _isConnected.value = false
        super.onDestroy()
    }

    suspend fun performClick(x: Float, y: Float, durationMs: Long): Boolean {
        val safeDuration = durationMs.coerceIn(10L, GestureDescription.getMaxGestureDuration())
        val gesture = try {
            val sx = if (x.isFinite()) x.coerceAtLeast(0f) else 0f
            val sy = if (y.isFinite()) y.coerceAtLeast(0f) else 0f
            val path = Path().apply { moveTo(sx, sy) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
            GestureDescription.Builder().addStroke(stroke).build()
        } catch (t: Throwable) {
            ExecutionLogBus.error("Gestur klik tidak valid: ${t.message}")
            return false
        }
        return dispatchGestureSuspended(gesture, safeDuration)
    }

    suspend fun performSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ): Boolean {
        val safeDuration = durationMs.coerceIn(20L, GestureDescription.getMaxGestureDuration())
        val gesture = try {
            val sx1 = if (x1.isFinite()) x1.coerceAtLeast(0f) else 0f
            val sy1 = if (y1.isFinite()) y1.coerceAtLeast(0f) else 0f
            val sx2 = if (x2.isFinite()) x2.coerceAtLeast(0f) else sx1
            val sy2 = if (y2.isFinite()) y2.coerceAtLeast(0f) else sy1
            val path = Path().apply {
                moveTo(sx1, sy1)
                if (hypot((sx2 - sx1).toDouble(), (sy2 - sy1).toDouble()) >= 2.0) {
                    lineTo(sx2, sy2)
                }
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
            GestureDescription.Builder().addStroke(stroke).build()
        } catch (t: Throwable) {
            ExecutionLogBus.error("Gestur swipe tidak valid: ${t.message}")
            return false
        }
        return dispatchGestureSuspended(gesture, safeDuration)
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
        val gesture = try {
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
        } catch (t: Throwable) {
            ExecutionLogBus.error("Gestur zoom tidak valid: ${t.message}")
            return false
        }
        return dispatchGestureSuspended(gesture, safeDuration)
    }

    suspend fun performRecordedGesture(recorded: RecordedGesture, speed: Float): Boolean {
        if (recorded.strokes.isEmpty()) return true
        var maxEndMs = 50L
        val gesture = try {
            val safeSpeed = speed.coerceIn(0.25f, 5f)
            val maxDur = GestureDescription.getMaxGestureDuration()
            val builder = GestureDescription.Builder()
            var added = 0
            for (s in recorded.strokes.take(GestureDescription.getMaxStrokeCount())) {
                if (s.points.size < 2) continue
                val startX = s.points[0].coerceAtLeast(0).toFloat()
                val startY = s.points[1].coerceAtLeast(0).toFloat()
                val path = Path().apply { moveTo(startX, startY) }
                var prevX = startX
                var prevY = startY
                var i = 2
                while (i + 1 < s.points.size) {
                    val nx = s.points[i].coerceAtLeast(0).toFloat()
                    val ny = s.points[i + 1].coerceAtLeast(0).toFloat()
                    // Skip zero-length segments to avoid OEM gesture rejection/stalls
                    if (hypot((nx - prevX).toDouble(), (ny - prevY).toDouble()) >= 2.0) {
                        path.lineTo(nx, ny)
                        prevX = nx
                        prevY = ny
                    }
                    i += 2
                }
                val start = (s.startMs / safeSpeed).roundToLong().coerceIn(0L, (maxDur - 20L).coerceAtLeast(0L))
                val dur = (s.durationMs / safeSpeed).roundToLong().coerceIn(15L, (maxDur - start).coerceAtLeast(15L))
                if (start + dur > maxEndMs) maxEndMs = start + dur
                builder.addStroke(GestureDescription.StrokeDescription(path, start, dur))
                added++
            }
            if (added == 0) return true
            builder.build()
        } catch (t: Throwable) {
            ExecutionLogBus.error("Gestur rekaman tidak valid: ${t.message}")
            return false
        }
        return dispatchGestureSuspended(gesture, maxEndMs)
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
                        ExecutionLogBus.error("Gagal mengirim gestur: ${t.message}")
                        false
                    }
                    if (!dispatched && continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }
        }
        if (completed == null) {
            ExecutionLogBus.warn("Timeout menunggu respons gestur sistem (${timeoutMs}ms) — dilanjutkan otomatis.")
            return false
        }
        return completed
    }

    companion object {
        @Volatile
        private var instance: MacroAccessibilityService? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) return true
            return try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val expected = ComponentName(context, MacroAccessibilityService::class.java)
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

        /**
         * Launches an activity intent using the active AccessibilityService context, which is
         * exempt from Android 10–16 Background Activity Launch restrictions.
         */
        suspend fun launchActivityIntent(intent: android.content.Intent): Boolean {
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
