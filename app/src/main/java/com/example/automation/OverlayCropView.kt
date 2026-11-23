package com.example.automation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen interactive screenshot cropper shown directly inside the Floating Overlay.
 * Features a compact bottom pill bar with only Cancel ("Batal") and Save ("Simpan") buttons.
 */
@SuppressLint("ViewConstructor")
class OverlayCropView(
    context: Context,
    private val screenshot: Bitmap,
    private val onSave: (cropped: Bitmap, rect: Rect) -> Unit,
    private val onCancel: () -> Unit
) : FrameLayout(context) {

    private val canvasView: CropCanvasView

    init {
        canvasView = CropCanvasView(context, screenshot)
        addView(
            canvasView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        // Compact floating navigation bar with only Batal & Simpan
        val navBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EB0F172A"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#2DD4BF"))
            }
            elevation = dp(8).toFloat()
        }

        fun makeSmallBtn(label: String, bgHex: String, marginEndDp: Int, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(bgHex))
                    cornerRadius = dp(15).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(marginEndDp)
                }
                setOnClickListener { onClick() }
            }
        }

        navBar.addView(makeSmallBtn("Batal", "#475569", 6) { onCancel() })
        navBar.addView(makeSmallBtn("Simpan", "#0D9488", 0) {
            val crop = canvasView.extractCrop()
            val rect = canvasView.imageCropRect()
            if (crop != null && rect != null) {
                onSave(crop, rect)
            }
        })

        addView(
            navBar,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(28)
            }
        )
    }

    private class CropCanvasView(
        context: Context,
        private val bitmap: Bitmap
    ) : View(context) {

        private enum class Handle { NONE, MOVE, TL, TR, BL, BR, LEFT, RIGHT, TOP, BOTTOM }

        private val d = resources.displayMetrics.density
        private val handleRadius = 26f * d
        private val minBoxPx = 24f * d
        private val sel = RectF()
        private var initialized = false
        private var activeHandle = Handle.NONE
        private var lastX = 0f
        private var lastY = 0f

        private val scrimPaint = Paint().apply { color = Color.parseColor("#99000000") }
        private val borderPaint = Paint().apply {
            color = Color.parseColor("#2DD4BF")
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * d
            isAntiAlias = true
        }
        private val handlePaint = Paint().apply {
            color = Color.parseColor("#2DD4BF")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private val handleStroke = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f * d
            isAntiAlias = true
        }
        private val labelBg = Paint().apply {
            color = Color.parseColor("#CC0F172A")
            isAntiAlias = true
        }
        private val labelText = Paint().apply {
            color = Color.WHITE
            textSize = 11f * d
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
        private val dstRect = RectF()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            dstRect.set(0f, 0f, w.toFloat(), h.toFloat())
            if (!initialized && w > 0 && h > 0) {
                val bw = min(w * 0.40f, 180f * d)
                val bh = min(h * 0.16f, 110f * d)
                sel.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f)
                initialized = true
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (bitmap.isRecycled) return
            canvas.drawBitmap(bitmap, null, dstRect, null)

            val vw = width.toFloat()
            val vh = height.toFloat()
            canvas.drawRect(0f, 0f, vw, sel.top, scrimPaint)
            canvas.drawRect(0f, sel.bottom, vw, vh, scrimPaint)
            canvas.drawRect(0f, sel.top, sel.left, sel.bottom, scrimPaint)
            canvas.drawRect(sel.right, sel.top, vw, sel.bottom, scrimPaint)

            canvas.drawRect(sel, borderPaint)
            val r = 6.5f * d
            val corners = arrayOf(
                sel.left to sel.top,
                sel.right to sel.top,
                sel.left to sel.bottom,
                sel.right to sel.bottom
            )
            for ((cx, cy) in corners) {
                canvas.drawCircle(cx, cy, r, handlePaint)
                canvas.drawCircle(cx, cy, r, handleStroke)
            }

            val cropRect = imageCropRect()
            if (cropRect != null) {
                val info = "${cropRect.width()}×${cropRect.height()} px"
                val tw = labelText.measureText(info)
                val pad = 5f * d
                val bx = sel.left.coerceIn(8f * d, max(8f * d, vw - tw - pad * 2 - 8f * d))
                val by = (sel.top - 26f * d).coerceAtLeast(8f * d)
                canvas.drawRoundRect(
                    RectF(bx, by, bx + tw + pad * 2, by + 20f * d),
                    6f * d, 6f * d, labelBg
                )
                canvas.drawText(info, bx + pad, by + 14f * d, labelText)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    activeHandle = hitTest(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    applyDrag(dx, dy)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activeHandle = Handle.NONE
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun hitTest(x: Float, y: Float): Handle {
            fun near(px: Float, py: Float) = abs(x - px) <= handleRadius && abs(y - py) <= handleRadius
            if (near(sel.left, sel.top)) return Handle.TL
            if (near(sel.right, sel.top)) return Handle.TR
            if (near(sel.left, sel.bottom)) return Handle.BL
            if (near(sel.right, sel.bottom)) return Handle.BR
            if (abs(x - sel.left) <= handleRadius && y in sel.top..sel.bottom) return Handle.LEFT
            if (abs(x - sel.right) <= handleRadius && y in sel.top..sel.bottom) return Handle.RIGHT
            if (abs(y - sel.top) <= handleRadius && x in sel.left..sel.right) return Handle.TOP
            if (abs(y - sel.bottom) <= handleRadius && x in sel.left..sel.right) return Handle.BOTTOM
            if (sel.contains(x, y)) return Handle.MOVE
            sel.set(x - minBoxPx / 2, y - minBoxPx / 2, x + minBoxPx / 2, y + minBoxPx / 2)
            clampBox()
            return Handle.BR
        }

        private fun applyDrag(dx: Float, dy: Float) {
            when (activeHandle) {
                Handle.MOVE -> {
                    val w = sel.width()
                    val h = sel.height()
                    val nl = (sel.left + dx).coerceIn(0f, max(0f, width - w))
                    val nt = (sel.top + dy).coerceIn(0f, max(0f, height - h))
                    sel.set(nl, nt, nl + w, nt + h)
                }
                Handle.TL -> {
                    sel.left = (sel.left + dx).coerceIn(0f, sel.right - minBoxPx)
                    sel.top = (sel.top + dy).coerceIn(0f, sel.bottom - minBoxPx)
                }
                Handle.TR -> {
                    sel.right = (sel.right + dx).coerceIn(sel.left + minBoxPx, width.toFloat())
                    sel.top = (sel.top + dy).coerceIn(0f, sel.bottom - minBoxPx)
                }
                Handle.BL -> {
                    sel.left = (sel.left + dx).coerceIn(0f, sel.right - minBoxPx)
                    sel.bottom = (sel.bottom + dy).coerceIn(sel.top + minBoxPx, height.toFloat())
                }
                Handle.BR -> {
                    sel.right = (sel.right + dx).coerceIn(sel.left + minBoxPx, width.toFloat())
                    sel.bottom = (sel.bottom + dy).coerceIn(sel.top + minBoxPx, height.toFloat())
                }
                Handle.LEFT -> sel.left = (sel.left + dx).coerceIn(0f, sel.right - minBoxPx)
                Handle.RIGHT -> sel.right = (sel.right + dx).coerceIn(sel.left + minBoxPx, width.toFloat())
                Handle.TOP -> sel.top = (sel.top + dy).coerceIn(0f, sel.bottom - minBoxPx)
                Handle.BOTTOM -> sel.bottom = (sel.bottom + dy).coerceIn(sel.top + minBoxPx, height.toFloat())
                Handle.NONE -> {}
            }
        }

        private fun clampBox() {
            val l = sel.left.coerceIn(0f, max(0f, width - minBoxPx))
            val t = sel.top.coerceIn(0f, max(0f, height - minBoxPx))
            val r = sel.right.coerceIn(l + minBoxPx, width.toFloat())
            val b = sel.bottom.coerceIn(t + minBoxPx, height.toFloat())
            sel.set(l, t, r, b)
        }

        fun imageCropRect(): Rect? {
            if (width <= 0 || height <= 0 || bitmap.isRecycled) return null
            val sx = bitmap.width.toFloat() / width
            val sy = bitmap.height.toFloat() / height
            val left = (sel.left * sx).toInt().coerceIn(0, bitmap.width - 2)
            val top = (sel.top * sy).toInt().coerceIn(0, bitmap.height - 2)
            val right = (sel.right * sx).toInt().coerceIn(left + 2, bitmap.width)
            val bottom = (sel.bottom * sy).toInt().coerceIn(top + 2, bitmap.height)
            return Rect(left, top, right, bottom)
        }

        fun extractCrop(): Bitmap? {
            val r = imageCropRect() ?: return null
            return try {
                Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height())
            } catch (_: Exception) {
                null
            }
        }
    }
}
