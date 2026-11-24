package com.example.automation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen interactive screenshot cropper shown directly inside the Floating Overlay:
 * - Pinch-to-zoom, 2-finger pan, and compact top [- | 1.0x | +] zoom controls (1x..6x)
 * - Selection rectangle tracked in exact screenshot pixel coordinates for 1:1 accuracy
 * - Compact bottom navigation bar with only Batal and Simpan buttons
 */
@SuppressLint("ViewConstructor")
class OverlayCropView(
    context: Context,
    private val screenshot: Bitmap,
    private val onSave: (cropped: Bitmap, rect: Rect) -> Unit,
    private val onCancel: () -> Unit
) : FrameLayout(context) {

    private val canvasView: CropCanvasView
    private val zoomLabel: TextView

    init {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        zoomLabel = TextView(context).apply {
            text = "1.0x"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        canvasView = CropCanvasView(context, screenshot) { z ->
            zoomLabel.text = String.format("%.1fx", z)
        }
        addView(
            canvasView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        // Compact top zoom bar: [-] [1.0x] [+]
        val zoomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(3), dp(4), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DD0F172A"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            elevation = dp(6).toFloat()
        }

        fun makeZoomBtn(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = dp(28)
            minHeight = dp(26)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(13).toFloat()
            }
            setOnClickListener { onClick() }
        }

        zoomBar.addView(makeZoomBtn("−") { canvasView.zoomBy(0.75f) })
        zoomLabel.setOnClickListener { canvasView.resetZoom() }
        zoomBar.addView(zoomLabel)
        zoomBar.addView(makeZoomBtn("+") { canvasView.zoomBy(1.35f) })

        val zoomParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(38)
        }
        addView(zoomBar, zoomParams)

        // Compact bottom navigation bar with ONLY Batal & Simpan
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

        val navParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(48)
        }
        addView(navBar, navParams)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            zoomParams.topMargin = maxOf(dp(24), bars.top + dp(12))
            navParams.bottomMargin = maxOf(dp(28), bars.bottom + dp(16))
            navParams.leftMargin = maxOf(dp(12), bars.left + dp(8))
            navParams.rightMargin = maxOf(dp(12), bars.right + dp(8))
            zoomBar.layoutParams = zoomParams
            navBar.layoutParams = navParams
            insets
        }
    }

    private class CropCanvasView(
        context: Context,
        private val bitmap: Bitmap,
        private val onZoomChanged: (Float) -> Unit
    ) : View(context) {

        private enum class Mode { NONE, PAN, MOVE, TL, TR, BL, BR, LEFT, RIGHT, TOP, BOTTOM }

        private val d = resources.displayMetrics.density
        private val handleHitPx = 26f * d
        private val minCropImgPx = 12f

        /** Crop selection stored in exact screenshot bitmap coordinates. */
        private val selImg = RectF()
        private var initialized = false

        private var zoom = 1f
        private var panX = 0f
        private var panY = 0f
        private var baseScale = 1f

        private var activeMode = Mode.NONE
        private var lastX = 0f
        private var lastY = 0f
        private var isScaling = false

        private val drawMatrix = Matrix()
        private val selScreen = RectF()

        private val bgPaint = Paint().apply { color = Color.BLACK }
        private val scrimPaint = Paint().apply { color = Color.parseColor("#88000000") }
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

        private val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    isScaling = true
                    activeMode = Mode.PAN
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    applyZoom(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    isScaling = false
                }
            }
        )

        private fun totalScale(): Float = baseScale * zoom

        private fun imgToScreenX(ix: Float): Float = width / 2f + (ix - bitmap.width / 2f) * totalScale() + panX
        private fun imgToScreenY(iy: Float): Float = height / 2f + (iy - bitmap.height / 2f) * totalScale() + panY

        fun zoomBy(factor: Float) {
            val fx = if (initialized) imgToScreenX(selImg.centerX()) else width / 2f
            val fy = if (initialized) imgToScreenY(selImg.centerY()) else height / 2f
            applyZoom(zoom * factor, fx, fy)
        }

        fun resetZoom() {
            zoom = 1f
            panX = 0f
            panY = 0f
            onZoomChanged(zoom)
            invalidate()
        }

        private fun applyZoom(targetZoom: Float, focusX: Float, focusY: Float) {
            val newZoom = targetZoom.coerceIn(1f, 6f)
            val oldScale = totalScale()
            val newScale = baseScale * newZoom
            if (oldScale > 0f) {
                val ratio = newScale / oldScale
                val cx = width / 2f
                val cy = height / 2f
                panX = (focusX - cx) * (1f - ratio) + panX * ratio
                panY = (focusY - cy) * (1f - ratio) + panY * ratio
            }
            zoom = newZoom
            clampPan()
            onZoomChanged(zoom)
            invalidate()
        }

        private fun clampPan() {
            if (width <= 0 || height <= 0) return
            val dispW = bitmap.width * totalScale()
            val dispH = bitmap.height * totalScale()
            val maxPanX = max(0f, (dispW - width) / 2f)
            val maxPanY = max(0f, (dispH - height) / 2f)
            panX = panX.coerceIn(-maxPanX, maxPanX)
            panY = panY.coerceIn(-maxPanY, maxPanY)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0 && h > 0 && !bitmap.isRecycled) {
                baseScale = min(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
                if (!initialized) {
                    val bw = bitmap.width * 0.28f
                    val bh = bitmap.height * 0.12f
                    selImg.set(
                        (bitmap.width - bw) / 2f,
                        (bitmap.height - bh) / 2f,
                        (bitmap.width + bw) / 2f,
                        (bitmap.height + bh) / 2f
                    )
                    initialized = true
                }
                clampPan()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (bitmap.isRecycled) return
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

            val scale = totalScale()
            drawMatrix.reset()
            drawMatrix.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
            drawMatrix.postScale(scale, scale)
            drawMatrix.postTranslate(width / 2f + panX, height / 2f + panY)
            canvas.drawBitmap(bitmap, drawMatrix, null)

            // Map selection rect from image pixels to screen coordinates
            selScreen.set(
                imgToScreenX(selImg.left),
                imgToScreenY(selImg.top),
                imgToScreenX(selImg.right),
                imgToScreenY(selImg.bottom)
            )

            val vw = width.toFloat()
            val vh = height.toFloat()
            canvas.drawRect(0f, 0f, vw, selScreen.top, scrimPaint)
            canvas.drawRect(0f, selScreen.bottom, vw, vh, scrimPaint)
            canvas.drawRect(0f, selScreen.top, selScreen.left, selScreen.bottom, scrimPaint)
            canvas.drawRect(selScreen.right, selScreen.top, vw, selScreen.bottom, scrimPaint)

            canvas.drawRect(selScreen, borderPaint)
            val r = 6.5f * d
            drawHandle(canvas, selScreen.left, selScreen.top, r)
            drawHandle(canvas, selScreen.right, selScreen.top, r)
            drawHandle(canvas, selScreen.left, selScreen.bottom, r)
            drawHandle(canvas, selScreen.right, selScreen.bottom, r)

            val cropRect = imageCropRect()
            if (cropRect != null) {
                val info = "${cropRect.width()}×${cropRect.height()} px"
                val tw = labelText.measureText(info)
                val pad = 5f * d
                val bx = selScreen.left.coerceIn(8f * d, max(8f * d, vw - tw - pad * 2 - 8f * d))
                val by = (selScreen.top - 26f * d).coerceAtLeast(8f * d)
                labelRect.set(bx, by, bx + tw + pad * 2, by + 20f * d)
                canvas.drawRoundRect(labelRect, 6f * d, 6f * d, labelBg)
                canvas.drawText(info, bx + pad, by + 14f * d, labelText)
            }
        }

        private val labelRect = RectF()

        private fun drawHandle(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            canvas.drawCircle(cx, cy, r, handlePaint)
            canvas.drawCircle(cx, cy, r, handleStroke)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            if (event.pointerCount >= 2) {
                activeMode = Mode.PAN
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    activeMode = hitTest(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isScaling) return true
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    applyDrag(dx, dy)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activeMode = Mode.NONE
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun hitTest(x: Float, y: Float): Mode {
            fun near(px: Float, py: Float) = abs(x - px) <= handleHitPx && abs(y - py) <= handleHitPx
            if (near(selScreen.left, selScreen.top)) return Mode.TL
            if (near(selScreen.right, selScreen.top)) return Mode.TR
            if (near(selScreen.left, selScreen.bottom)) return Mode.BL
            if (near(selScreen.right, selScreen.bottom)) return Mode.BR
            if (abs(x - selScreen.left) <= handleHitPx && y in selScreen.top..selScreen.bottom) return Mode.LEFT
            if (abs(x - selScreen.right) <= handleHitPx && y in selScreen.top..selScreen.bottom) return Mode.RIGHT
            if (abs(y - selScreen.top) <= handleHitPx && x in selScreen.left..selScreen.right) return Mode.TOP
            if (abs(y - selScreen.bottom) <= handleHitPx && x in selScreen.left..selScreen.right) return Mode.BOTTOM
            if (selScreen.contains(x, y)) return Mode.MOVE
            return Mode.PAN
        }

        private fun applyDrag(dxScreen: Float, dyScreen: Float) {
            if (activeMode == Mode.PAN) {
                if (zoom > 1.01f) {
                    panX += dxScreen
                    panY += dyScreen
                    clampPan()
                }
                return
            }
            val s = totalScale().coerceAtLeast(0.01f)
            val dx = dxScreen / s
            val dy = dyScreen / s
            val bw = bitmap.width.toFloat()
            val bh = bitmap.height.toFloat()

            when (activeMode) {
                Mode.MOVE -> {
                    val w = selImg.width()
                    val h = selImg.height()
                    val nl = (selImg.left + dx).coerceIn(0f, max(0f, bw - w))
                    val nt = (selImg.top + dy).coerceIn(0f, max(0f, bh - h))
                    selImg.set(nl, nt, nl + w, nt + h)
                }
                Mode.TL -> {
                    selImg.left = (selImg.left + dx).coerceIn(0f, selImg.right - minCropImgPx)
                    selImg.top = (selImg.top + dy).coerceIn(0f, selImg.bottom - minCropImgPx)
                }
                Mode.TR -> {
                    selImg.right = (selImg.right + dx).coerceIn(selImg.left + minCropImgPx, bw)
                    selImg.top = (selImg.top + dy).coerceIn(0f, selImg.bottom - minCropImgPx)
                }
                Mode.BL -> {
                    selImg.left = (selImg.left + dx).coerceIn(0f, selImg.right - minCropImgPx)
                    selImg.bottom = (selImg.bottom + dy).coerceIn(selImg.top + minCropImgPx, bh)
                }
                Mode.BR -> {
                    selImg.right = (selImg.right + dx).coerceIn(selImg.left + minCropImgPx, bw)
                    selImg.bottom = (selImg.bottom + dy).coerceIn(selImg.top + minCropImgPx, bh)
                }
                Mode.LEFT -> selImg.left = (selImg.left + dx).coerceIn(0f, selImg.right - minCropImgPx)
                Mode.RIGHT -> selImg.right = (selImg.right + dx).coerceIn(selImg.left + minCropImgPx, bw)
                Mode.TOP -> selImg.top = (selImg.top + dy).coerceIn(0f, selImg.bottom - minCropImgPx)
                Mode.BOTTOM -> selImg.bottom = (selImg.bottom + dy).coerceIn(selImg.top + minCropImgPx, bh)
                else -> {}
            }
        }

        fun imageCropRect(): Rect? {
            if (bitmap.isRecycled) return null
            val left = selImg.left.toInt().coerceIn(0, bitmap.width - 2)
            val top = selImg.top.toInt().coerceIn(0, bitmap.height - 2)
            val right = selImg.right.toInt().coerceIn(left + 2, bitmap.width)
            val bottom = selImg.bottom.toInt().coerceIn(top + 2, bitmap.height)
            return Rect(left, top, right, bottom)
        }

        fun extractCrop(): Bitmap? {
            val r = imageCropRect() ?: return null
            return try {
                Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height())
            } catch (_: Throwable) {
                null
            }
        }
    }
}
