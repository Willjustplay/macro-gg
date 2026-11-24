package com.example.automation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Full-screen interactive target placement overlay that lets users visually place
 * autoclick, long-press, swipe (A→B), zoom, or color detection targets directly on
 * screen — including actions nested inside "Jika Ditemukan" (Then) and
 * "Jika Tidak Ditemukan" (Else) branches.
 */
@SuppressLint("ViewConstructor")
class OverlayTargetPickerView(
    context: Context,
    title: String,
    accentHex: String = "#2DD4BF",
    private val isDualPoint: Boolean = false,
    initialX1: Int,
    initialY1: Int,
    initialX2: Int = initialX1,
    initialY2: Int = initialY1,
    private val sampleColor: Boolean = false,
    initialHexColor: String? = null,
    private val pixelSampler: ((Int, Int) -> String?)? = null,
    private val onSave: (x1: Int, y1: Int, x2: Int, y2: Int, sampledHex: String?) -> Unit,
    private val onCancel: () -> Unit
) : FrameLayout(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * d).toInt()

    private var ptX1 = initialX1.toFloat()
    private var ptY1 = initialY1.toFloat()
    private var ptX2 = initialX2.toFloat()
    private var ptY2 = initialY2.toFloat()
    private var sampledHex: String? = initialHexColor

    private val accentColor = runCatching { Color.parseColor(accentHex) }.getOrDefault(Color.parseColor("#2DD4BF"))
    private val coordLabel: TextView
    private val colorSwatchView: View?
    private val canvasView: TargetCanvasView

    init {
        canvasView = TargetCanvasView(context)
        addView(canvasView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Compact top label chip showing which action/branch is being positioned
        val topChip = TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60F172A"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), accentColor)
            }
            elevation = dp(4).toFloat()
        }
        val topParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(40)
        }
        addView(topChip, topParams)

        // Compact minimal bottom bar: [coords] [Batal] [Simpan]
        val bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE0F172A"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), accentColor)
            }
            elevation = dp(8).toFloat()
        }

        colorSwatchView = if (sampleColor) {
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                    marginStart = dp(2)
                    marginEnd = dp(6)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(parseColorSafe(sampledHex))
                    setStroke(dp(1), Color.WHITE)
                }
            }.also { bottomBar.addView(it) }
        } else null

        coordLabel = TextView(context).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(4), 0, dp(8), 0)
        }
        bottomBar.addView(coordLabel)

        bottomBar.addView(makeBarBtn("Batal", "#475569", marginEndDp = 6) {
            onCancel()
        })

        bottomBar.addView(makeBarBtn("Simpan", "#0D9488", marginEndDp = 0) {
            onSave(
                ptX1.roundToInt(),
                ptY1.roundToInt(),
                ptX2.roundToInt(),
                ptY2.roundToInt(),
                sampledHex
            )
        })

        val bottomParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(48)
        }
        addView(bottomBar, bottomParams)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            topParams.topMargin = maxOf(dp(24), bars.top + dp(12))
            bottomParams.bottomMargin = maxOf(dp(28), bars.bottom + dp(16))
            bottomParams.leftMargin = maxOf(dp(12), bars.left + dp(8))
            bottomParams.rightMargin = maxOf(dp(12), bars.right + dp(8))
            topChip.layoutParams = topParams
            bottomBar.layoutParams = bottomParams
            insets
        }

        refreshReadout()
    }

    private fun parseColorSafe(hex: String?): Int =
        runCatching { Color.parseColor(hex ?: "#FFFFFF") }.getOrDefault(Color.WHITE)

    private fun makeBarBtn(label: String, bgHex: String, marginEndDp: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(marginEndDp)
            }
            setOnClickListener { onClick() }
        }

    private fun refreshReadout() {
        val x1 = ptX1.roundToInt()
        val y1 = ptY1.roundToInt()
        if (isDualPoint) {
            val x2 = ptX2.roundToInt()
            val y2 = ptY2.roundToInt()
            coordLabel.text = "A($x1,$y1)→B($x2,$y2)"
        } else {
            if (sampleColor) {
                pixelSampler?.invoke(x1, y1)?.let { sampledHex = it }
                (colorSwatchView?.background as? GradientDrawable)?.setColor(parseColorSafe(sampledHex))
                coordLabel.text = "($x1,$y1) ${sampledHex ?: ""}".trim()
            } else {
                coordLabel.text = "X:$x1  Y:$y1"
            }
        }
    }

    private inner class TargetCanvasView(context: Context) : View(context) {
        private val screenLoc = IntArray(2)
        private var activeDragNode = 1 // 1 = primary/A, 2 = secondary/B

        private val veilPaint = Paint().apply {
            color = Color.parseColor("#2A0F172A")
            style = Paint.Style.FILL
        }
        private val guidePaint = Paint().apply {
            color = Color.argb(120, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * d
            pathEffect = DashPathEffect(floatArrayOf(6f * d, 6f * d), 0f)
            isAntiAlias = true
        }
        private val haloPaint = Paint().apply {
            color = Color.argb(72, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private val ringPaint = Paint().apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = 3f * d
            isAntiAlias = true
        }
        private val innerDotPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private val innerBorderPaint = Paint().apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * d
            isAntiAlias = true
        }
        private val swipeGlowPaint = Paint().apply {
            color = Color.parseColor("#660284C7")
            style = Paint.Style.STROKE
            strokeWidth = 7f * d
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        private val swipeLinePaint = Paint().apply {
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
        private val nodeFillA = Paint().apply {
            color = Color.parseColor("#E60284C7")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private val nodeFillB = Paint().apply {
            color = Color.parseColor("#E60D9488")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private val nodeTextPaint = Paint().apply {
            color = Color.WHITE
            textSize = 13f * d
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        private val arrowPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            getLocationOnScreen(screenLoc)
            val offX = screenLoc[0].toFloat()
            val offY = screenLoc[1].toFloat()

            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), veilPaint)

            val cx1 = ptX1 - offX
            val cy1 = ptY1 - offY

            if (isDualPoint) {
                val cx2 = ptX2 - offX
                val cy2 = ptY2 - offY
                val dx = cx2 - cx1
                val dy = cy2 - cy1
                val dist = hypot(dx, dy)
                val nodeRadius = 20f * d

                if (dist > 8f) {
                    val ux = dx / dist
                    val uy = dy / dist
                    val sx = cx1 + ux * nodeRadius.coerceAtMost(dist * 0.3f)
                    val sy = cy1 + uy * nodeRadius.coerceAtMost(dist * 0.3f)
                    val ex = cx2 - ux * nodeRadius.coerceAtMost(dist * 0.3f)
                    val ey = cy2 - uy * nodeRadius.coerceAtMost(dist * 0.3f)

                    canvas.drawLine(sx, sy, ex, ey, swipeGlowPaint)
                    canvas.drawLine(sx, sy, ex, ey, swipeLinePaint)

                    val angle = atan2(dy, dx)
                    drawArrow(canvas, ex, ey, angle, 12f * d)
                    if (dist > nodeRadius * 4f) {
                        drawArrow(canvas, (sx + ex) * 0.5f, (sy + ey) * 0.5f, angle, 9f * d)
                    }
                }

                drawSwipeNode(canvas, cx1, cy1, nodeRadius, "A", nodeFillA)
                drawSwipeNode(canvas, cx2, cy2, nodeRadius, "B", nodeFillB)
            } else {
                // Dashed crosshair guidelines across full screen
                canvas.drawLine(0f, cy1, width.toFloat(), cy1, guidePaint)
                canvas.drawLine(cx1, 0f, cx1, height.toFloat(), guidePaint)

                // Outer halo + crisp ring + exact center pixel dot
                val outerR = 26f * d
                val ringR = 18f * d
                canvas.drawCircle(cx1, cy1, outerR, haloPaint)
                canvas.drawCircle(cx1, cy1, ringR, ringPaint)
                canvas.drawCircle(cx1, cy1, 4f * d, innerDotPaint)
                canvas.drawCircle(cx1, cy1, 4f * d, innerBorderPaint)
            }
        }

        private fun drawSwipeNode(canvas: Canvas, cx: Float, cy: Float, r: Float, label: String, fill: Paint) {
            canvas.drawCircle(cx, cy, r + 6f * d, haloPaint)
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, ringPaint)
            val textY = cy - (nodeTextPaint.descent() + nodeTextPaint.ascent()) * 0.5f
            canvas.drawText(label, cx, textY, nodeTextPaint)
        }

        private fun drawArrow(canvas: Canvas, tipX: Float, tipY: Float, angleRad: Float, size: Float) {
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
            canvas.drawPath(arrowPath, innerBorderPaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val rx = event.rawX.coerceAtLeast(0f)
            val ry = event.rawY.coerceAtLeast(0f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activeDragNode = if (!isDualPoint) {
                        1
                    } else {
                        val d1 = hypot(rx - ptX1, ry - ptY1)
                        val d2 = hypot(rx - ptX2, ry - ptY2)
                        if (d2 < d1) 2 else 1
                    }
                    updatePoint(rx, ry)
                    return true
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    updatePoint(rx, ry)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun updatePoint(rx: Float, ry: Float) {
            if (activeDragNode == 2 && isDualPoint) {
                ptX2 = rx
                ptY2 = ry
            } else {
                ptX1 = rx
                ptY1 = ry
            }
            refreshReadout()
            invalidate()
        }
    }
}
