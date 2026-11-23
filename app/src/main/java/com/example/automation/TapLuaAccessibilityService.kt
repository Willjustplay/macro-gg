package com.example.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class TapLuaAccessibilityService : AccessibilityService() {

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
        if (instance === this) {
            instance = null
        }
        _isConnected.value = false
        ExecutionLogBus.warn("Accessibility Gesture Engine terputus.")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        _isConnected.value = false
        super.onDestroy()
    }

    suspend fun performClick(x: Float, y: Float, durationMs: Long): Boolean {
        val safeDuration = durationMs.coerceIn(10L, 60000L)
        val path = Path().apply {
            moveTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f))
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSuspended(gesture)
    }

    suspend fun performSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ): Boolean {
        val safeDuration = durationMs.coerceIn(20L, 60000L)
        val path = Path().apply {
            moveTo(x1.coerceAtLeast(0f), y1.coerceAtLeast(0f))
            lineTo(x2.coerceAtLeast(0f), y2.coerceAtLeast(0f))
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSuspended(gesture)
    }

    private suspend fun dispatchGestureSuspended(gesture: GestureDescription): Boolean =
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
            } catch (e: Exception) {
                ExecutionLogBus.error("Gagal mengirim gestur: ${e.message}")
                false
            }
            if (!dispatched && continuation.isActive) {
                continuation.resume(false)
            }
        }

    companion object {
        @Volatile
        private var instance: TapLuaAccessibilityService? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) return true
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expected = ComponentName(context, TapLuaAccessibilityService::class.java)
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            while (splitter.hasNext()) {
                val component = ComponentName.unflattenFromString(splitter.next())
                if (component == expected) return true
            }
            return false
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
    }
}
