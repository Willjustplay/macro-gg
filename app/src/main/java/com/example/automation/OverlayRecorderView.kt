package com.example.automation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.data.RecordedGesture
import com.example.data.RecordedStroke

/**
 * Full-screen touch recorder overlay.
 * Captures taps & swipes (with accurate timing and curved paths) and optionally forwards
 * each completed gesture to the underlying app via [onPassThroughGesture] so the user
 * can navigate through screens while recording.
 */
@SuppressLint("ViewConstructor")
class OverlayRecorderView(
    context: Context,
    private val onPassThroughGesture: (RecordedGesture) -> Unit,
    private val onFinish: (List<RecordedGesture>) -> Unit,
    private val onCancel: () -> Unit
) : FrameLayout(context) {

    private val recorded = mutableListOf<RecordedGesture>()
    private var lastGestureEndMs = System.currentTimeMillis()
    private var passThroughEnabled = true

    private val statusLabel: TextView
    private val passBtn: TextView
    private val canvasView: RecorderCanvasView

    init {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        canvasView = RecorderCanvasView(context) { gesture ->
            recorded += gesture
            updateStatus()
            if (passThroughEnabled) {
                onPassThroughGesture(gesture)
            }
        }
        addView(canvasView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EB111827"))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(2), Color.parseColor("#EF4444"))
            }
        }

        statusLabel = TextView(context).apply {
            text = "● Rekam (0)"
            setTextColor(Color.parseColor("#FCA5A5"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }
        bar.addView(statusLabel)

        fun pillBtn(label: String, bgHex: String, onClick: () -> Unit): TextView = TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener { onClick() }
        }

        passBtn = pillBtn("Tembus: ON", "#0E7490") {
            passThroughEnabled = !passThroughEnabled
            passBtn.text = if (passThroughEnabled) "Tembus: ON" else "Tembus: OFF"
            (passBtn.background as? GradientDrawable)?.setColor(
                Color.parseColor(if (passThroughEnabled) "#0E7490" else "#475569")
            )
        }
        bar.addView(passBtn)

        bar.addView(pillBtn("Simpan", "#059669") {
            onFinish(recorded.toList())
        })

        bar.addView(pillBtn("Batal", "#7F1D1D") {
            onCancel()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        })

        addView(
            bar,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(38)
            }
        )
    }

    private fun updateStatus() {
        statusLabel.text = "● Rekam (${recorded.size})"
    }

    @Volatile
    var isForwardingGesture: Boolean = false

    private inner class RecorderCanvasView(
        context: Context,
        private val onGestureCaptured: (RecordedGesture) -> Unit
    ) : View(context) {

        private val d = resources.displayMetrics.density
        private val borderPaint = Paint().apply {
            color = Color.parseColor("#AAEF4444")
            style = Paint.Style.STROKE
            strokeWidth = 4f * d
        }
        private val trailPaint = Paint().apply {
            color = Color.parseColor("#38BDF8")
            style = Paint.Style.STROKE
            strokeWidth = 4f * d
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
        private val dotPaint = Paint().apply {
            color = Color.parseColor("#2DD4BF")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val activePoints = mutableListOf<Int>()
        private val activePath = Path()
        private var strokeStartMs = 0L
        private var lastSampleMs = 0L

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(2f * d, 2f * d, width - 2f * d, height - 2f * d, borderPaint)
            if (activePoints.size >= 4) {
                canvas.drawPath(activePath, trailPaint)
            }
            if (activePoints.size >= 2) {
                canvas.drawCircle(activePoints[0].toFloat(), activePoints[1].toFloat(), 10f * d, dotPaint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (isForwardingGesture) return true
            val rx = event.rawX.toInt().coerceAtLeast(0)
            val ry = event.rawY.toInt().coerceAtLeast(0)
            val now = System.currentTimeMillis()

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePoints.clear()
                    activePath.reset()
                    strokeStartMs = now
                    lastSampleMs = now
                    activePoints.add(rx)
                    activePoints.add(ry)
                    activePath.moveTo(event.x, event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (now - lastSampleMs >= 20L && activePoints.size < 300) {
                        val lastPx = activePoints[activePoints.size - 2]
                        val lastPy = activePoints[activePoints.size - 1]
                        if (kotlin.math.abs(rx - lastPx) >= 3 || kotlin.math.abs(ry - lastPy) >= 3) {
                            activePoints.add(rx)
                            activePoints.add(ry)
                            activePath.lineTo(event.x, event.y)
                            lastSampleMs = now
                            invalidate()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (activePoints.isNotEmpty()) {
                        val lastPx = activePoints[activePoints.size - 2]
                        val lastPy = activePoints[activePoints.size - 1]
                        if (kotlin.math.abs(rx - lastPx) >= 4 || kotlin.math.abs(ry - lastPy) >= 4) {
                            activePoints.add(rx)
                            activePoints.add(ry)
                        }
                        val duration = (now - strokeStartMs).coerceIn(35L, 10_000L)
                        val delayBefore = if (recorded.isEmpty()) 0L else (strokeStartMs - lastGestureEndMs).coerceIn(0L, 10_000L)
                        lastGestureEndMs = now
                        val gesture = RecordedGesture(
                            delayBeforeMs = delayBefore,
                            strokes = listOf(
                                RecordedStroke(
                                    startMs = 0L,
                                    durationMs = duration,
                                    points = activePoints.toList()
                                )
                            )
                        )
                        activePoints.clear()
                        activePath.reset()
                        invalidate()
                        onGestureCaptured(gesture)
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
