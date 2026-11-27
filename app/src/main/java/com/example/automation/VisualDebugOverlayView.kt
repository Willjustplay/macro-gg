package com.example.automation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lightweight, non-touchable full-screen Live Visual Debugger canvas for Wjust macro (`:app`).
 * Renders fading bounding boxes, image match confidence scores, OCR scan regions & blocks,
 * Bezier swipe paths, humanized tap ripples, banned-image blocks, color crosshairs, and a compact
 * live variables HUD pill. Uses 0% CPU when no active events are fading.
 */
class VisualDebugOverlayView(context: Context) : View(context) {

    private data class TimedEvent(
        val event: VisualDebugEvent,
        val createdAtMs: Long,
        val ttlMs: Long
    )

    private val events = ArrayList<TimedEvent>(MAX_EVENTS)
    private var liveVariables: Map<String, Double> = emptyMap()
    private val density = resources.displayMetrics.density

    private fun dp(v: Float): Float = v * density

    private val solidStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
    }

    private val dashedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(5f)), 0f)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(10.5f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val hudTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        textSize = dp(10f)
        typeface = Typeface.DEFAULT_BOLD
    }

    private val hudVarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        textSize = dp(10.5f)
        typeface = Typeface.MONOSPACE
    }

    private val swipePath = Path()
    private val tmpRect = RectF()
    private var tickScheduled = false

    private val repaintRunnable = Runnable {
        tickScheduled = false
        invalidate()
    }

    fun pushEvent(event: VisualDebugEvent) {
        val now = SystemClock.uptimeMillis()
        val ttl = when (event) {
            is VisualDebugEvent.TapPoint -> 1100L
            is VisualDebugEvent.SwipePath -> 1500L
            is VisualDebugEvent.BannedBlocked -> 2200L
            is VisualDebugEvent.ImageCheck -> if (event.found) 1700L else 1400L
            is VisualDebugEvent.OcrCheck -> 1800L
            is VisualDebugEvent.ColorCheck -> 1500L
        }
        events += TimedEvent(event, now, ttl)
        if (events.size > MAX_EVENTS) {
            events.removeAt(0)
        }
        invalidate()
    }

    fun updateVariables(vars: Map<String, Double>) {
        liveVariables = vars
        invalidate()
    }

    fun clearAll() {
        events.clear()
        removeCallbacks(repaintRunnable)
        tickScheduled = false
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(repaintRunnable)
        tickScheduled = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()

        // Prune expired events
        var i = 0
        while (i < events.size) {
            if (now - events[i].createdAtMs >= events[i].ttlMs) {
                events.removeAt(i)
            } else {
                i++
            }
        }

        for (item in events) {
            val progress = ((now - item.createdAtMs).toFloat() / item.ttlMs.toFloat()).coerceIn(0f, 1f)
            val alphaFactor = if (progress < 0.65f) 1f else (1f - (progress - 0.65f) / 0.35f).coerceIn(0f, 1f)
            val alpha = (alphaFactor * 240f).toInt().coerceIn(0, 255)
            when (val ev = item.event) {
                is VisualDebugEvent.ImageCheck -> drawImageCheck(canvas, ev, alpha)
                is VisualDebugEvent.OcrCheck -> drawOcrCheck(canvas, ev, alpha)
                is VisualDebugEvent.TapPoint -> drawTapPoint(canvas, ev, progress, alpha)
                is VisualDebugEvent.SwipePath -> drawSwipePath(canvas, ev, alpha)
                is VisualDebugEvent.BannedBlocked -> drawBannedBlocked(canvas, ev, alpha)
                is VisualDebugEvent.ColorCheck -> drawColorCheck(canvas, ev, alpha)
            }
        }

        drawHudBadge(canvas)

        if (events.isNotEmpty() && !tickScheduled) {
            tickScheduled = true
            postDelayed(repaintRunnable, 33L)
        }
    }

    private fun drawImageCheck(canvas: Canvas, ev: VisualDebugEvent.ImageCheck, alpha: Int) {
        ev.scanRegion?.let { r ->
            dashedStrokePaint.color = withAlpha(Color.parseColor("#38BDF8"), (alpha * 0.55f).toInt())
            canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), dashedStrokePaint)
        }
        val color = if (ev.found) Color.parseColor("#10B981") else Color.parseColor("#F43F5E")
        val scorePct = (ev.score * 100f).toInt().coerceIn(0, 100)
        val reqPct = (ev.threshold * 100f).toInt().coerceIn(0, 100)
        val tag = if (ev.found) "✓ ${ev.label} $scorePct%" else "✗ ${ev.label} $scorePct%/$reqPct%"

        if (ev.found) {
            fillPaint.color = withAlpha(color, (alpha * 0.14f).toInt())
            canvas.drawRect(ev.left.toFloat(), ev.top.toFloat(), ev.right.toFloat(), ev.bottom.toFloat(), fillPaint)
            solidStrokePaint.color = withAlpha(color, alpha)
            solidStrokePaint.strokeWidth = dp(2.4f)
            canvas.drawRect(ev.left.toFloat(), ev.top.toFloat(), ev.right.toFloat(), ev.bottom.toFloat(), solidStrokePaint)
        } else {
            dashedStrokePaint.color = withAlpha(color, alpha)
            canvas.drawRect(ev.left.toFloat(), ev.top.toFloat(), ev.right.toFloat(), ev.bottom.toFloat(), dashedStrokePaint)
        }
        drawLabelPill(canvas, ev.left.toFloat(), (ev.top - dp(4f)), tag, color, alpha)
    }

    private fun drawOcrCheck(canvas: Canvas, ev: VisualDebugEvent.OcrCheck, alpha: Int) {
        val accent = if (ev.matched) Color.parseColor("#22D3EE") else Color.parseColor("#F59E0B")
        ev.region?.let { r ->
            fillPaint.color = withAlpha(accent, (alpha * 0.10f).toInt())
            canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), fillPaint)
            dashedStrokePaint.color = withAlpha(accent, alpha)
            canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), dashedStrokePaint)
        }
        solidStrokePaint.color = withAlpha(accent, (alpha * 0.85f).toInt())
        solidStrokePaint.strokeWidth = dp(1.4f)
        for (b in ev.blocks.take(12)) {
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), solidStrokePaint)
        }
        val anchorX = ev.region?.left?.toFloat() ?: ev.blocks.firstOrNull()?.left?.toFloat() ?: dp(16f)
        val anchorY = ev.region?.top?.toFloat() ?: ev.blocks.firstOrNull()?.top?.toFloat() ?: dp(96f)
        val icon = if (ev.matched) "✓" else "○"
        drawLabelPill(canvas, anchorX, anchorY - dp(4f), "$icon ${ev.label}: ${ev.readPreview}", accent, alpha)
    }

    private fun drawTapPoint(canvas: Canvas, ev: VisualDebugEvent.TapPoint, progress: Float, alpha: Int) {
        val color = if (ev.humanized) Color.parseColor("#22D3EE") else Color.parseColor("#F59E0B")
        val cx = ev.x.toFloat()
        val cy = ev.y.toFloat()
        val ringRadius = dp(8f + progress * 22f)
        solidStrokePaint.color = withAlpha(color, alpha)
        solidStrokePaint.strokeWidth = dp(2.2f)
        canvas.drawCircle(cx, cy, ringRadius, solidStrokePaint)
        fillPaint.color = withAlpha(color, alpha)
        canvas.drawCircle(cx, cy, dp(4.5f), fillPaint)
        if (ev.label.isNotBlank()) {
            drawLabelPill(canvas, cx + dp(10f), cy - dp(8f), ev.label, color, alpha)
        }
    }

    private fun drawSwipePath(canvas: Canvas, ev: VisualDebugEvent.SwipePath, alpha: Int) {
        val color = if (ev.curved) Color.parseColor("#A855F7") else Color.parseColor("#38BDF8")
        swipePath.reset()
        swipePath.moveTo(ev.x1.toFloat(), ev.y1.toFloat())
        if (ev.curved) {
            swipePath.quadTo(ev.cx.toFloat(), ev.cy.toFloat(), ev.x2.toFloat(), ev.y2.toFloat())
        } else {
            swipePath.lineTo(ev.x2.toFloat(), ev.y2.toFloat())
        }
        solidStrokePaint.color = withAlpha(color, alpha)
        solidStrokePaint.strokeWidth = dp(2.8f)
        canvas.drawPath(swipePath, solidStrokePaint)

        // Start dot
        fillPaint.color = withAlpha(color, alpha)
        canvas.drawCircle(ev.x1.toFloat(), ev.y1.toFloat(), dp(4.5f), fillPaint)

        // Arrowhead at end tangent
        val refX = if (ev.curved) ev.cx.toFloat() else ev.x1.toFloat()
        val refY = if (ev.curved) ev.cy.toFloat() else ev.y1.toFloat()
        val angle = atan2((ev.y2 - refY).toDouble(), (ev.x2 - refX).toDouble())
        val headLen = dp(11f)
        val spread = 0.48
        val ax1 = ev.x2 - headLen * cos(angle - spread).toFloat()
        val ay1 = ev.y2 - headLen * sin(angle - spread).toFloat()
        val ax2 = ev.x2 - headLen * cos(angle + spread).toFloat()
        val ay2 = ev.y2 - headLen * sin(angle + spread).toFloat()
        canvas.drawLine(ev.x2.toFloat(), ev.y2.toFloat(), ax1, ay1, solidStrokePaint)
        canvas.drawLine(ev.x2.toFloat(), ev.y2.toFloat(), ax2, ay2, solidStrokePaint)

        val tag = if (ev.curved) "Bezier ${ev.label}".trim() else ev.label
        if (tag.isNotBlank()) {
            drawLabelPill(canvas, ev.cx.toFloat(), ev.cy.toFloat() - dp(6f), tag, color, alpha)
        }
    }

    private fun drawBannedBlocked(canvas: Canvas, ev: VisualDebugEvent.BannedBlocked, alpha: Int) {
        val color = Color.parseColor("#EF4444")
        fillPaint.color = withAlpha(color, (alpha * 0.22f).toInt())
        canvas.drawRect(ev.left.toFloat(), ev.top.toFloat(), ev.right.toFloat(), ev.bottom.toFloat(), fillPaint)
        solidStrokePaint.color = withAlpha(color, alpha)
        solidStrokePaint.strokeWidth = dp(3f)
        canvas.drawRect(ev.left.toFloat(), ev.top.toFloat(), ev.right.toFloat(), ev.bottom.toFloat(), solidStrokePaint)
        canvas.drawLine(ev.left.toFloat(), ev.top.toFloat(), ev.right.toFloat(), ev.bottom.toFloat(), solidStrokePaint)
        canvas.drawLine(ev.right.toFloat(), ev.top.toFloat(), ev.left.toFloat(), ev.bottom.toFloat(), solidStrokePaint)
        drawLabelPill(canvas, ev.left.toFloat(), ev.top.toFloat() - dp(4f), "⛔ DIBLOKIR: ${ev.imageName}", color, alpha)
    }

    private fun drawColorCheck(canvas: Canvas, ev: VisualDebugEvent.ColorCheck, alpha: Int) {
        val ringColor = if (ev.matched) Color.parseColor("#10B981") else Color.parseColor("#F43F5E")
        val cx = ev.x.toFloat()
        val cy = ev.y.toFloat()
        val parsedActual = runCatching { Color.parseColor(ev.actualHex) }.getOrDefault(Color.DKGRAY)
        fillPaint.color = withAlpha(parsedActual, alpha)
        canvas.drawCircle(cx, cy, dp(8f), fillPaint)
        solidStrokePaint.color = withAlpha(ringColor, alpha)
        solidStrokePaint.strokeWidth = dp(2.4f)
        canvas.drawCircle(cx, cy, dp(10f), solidStrokePaint)
        val icon = if (ev.matched) "✓" else "✗"
        drawLabelPill(canvas, cx + dp(12f), cy - dp(6f), "$icon ${ev.actualHex} / ${ev.targetHex}", ringColor, alpha)
    }

    private fun drawLabelPill(canvas: Canvas, rawX: Float, rawBottomY: Float, text: String, accentColor: Int, alpha: Int) {
        if (text.isBlank()) return
        val padH = dp(6f)
        val padV = dp(3f)
        val tw = textPaint.measureText(text)
        val th = textPaint.fontSpacing
        val boxW = tw + padH * 2f
        val boxH = th + padV * 2f
        val left = rawX.coerceIn(dp(6f), (width - boxW - dp(6f)).coerceAtLeast(dp(6f)))
        val top = (rawBottomY - boxH).coerceIn(dp(24f), (height - boxH - dp(8f)).coerceAtLeast(dp(24f)))
        tmpRect.set(left, top, left + boxW, top + boxH)

        fillPaint.color = withAlpha(Color.parseColor("#0F172A"), (alpha * 0.90f).toInt())
        canvas.drawRoundRect(tmpRect, dp(6f), dp(6f), fillPaint)
        solidStrokePaint.color = withAlpha(accentColor, alpha)
        solidStrokePaint.strokeWidth = dp(1.2f)
        canvas.drawRoundRect(tmpRect, dp(6f), dp(6f), solidStrokePaint)
        textPaint.color = withAlpha(Color.WHITE, alpha)
        canvas.drawText(text, left + padH, top + padV - textPaint.ascent(), textPaint)
    }

    private fun drawHudBadge(canvas: Canvas) {
        val entries = liveVariables.entries.take(6)
        val header = "🐞 DEBUG VISUAL"
        val lines = entries.map { (k, v) -> "$k = ${formatVar(v)}" }
        var maxW = hudTitlePaint.measureText(header)
        for (ln in lines) {
            val w = hudVarPaint.measureText(ln)
            if (w > maxW) maxW = w
        }
        val padH = dp(9f)
        val padV = dp(6f)
        val lineGap = dp(2f)
        val boxW = maxW + padH * 2f
        val boxH = padV * 2f + hudTitlePaint.fontSpacing + lines.size * (hudVarPaint.fontSpacing + lineGap)
        val left = (width - boxW - dp(10f)).coerceAtLeast(dp(8f))
        val top = dp(34f)
        tmpRect.set(left, top, left + boxW, top + boxH)

        fillPaint.color = Color.parseColor("#CC0B1224")
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), fillPaint)
        solidStrokePaint.color = Color.parseColor("#AA38BDF8")
        solidStrokePaint.strokeWidth = dp(1.3f)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), solidStrokePaint)

        var y = top + padV - hudTitlePaint.ascent()
        canvas.drawText(header, left + padH, y, hudTitlePaint)
        y += hudTitlePaint.descent() + lineGap
        for (ln in lines) {
            y -= hudVarPaint.ascent()
            canvas.drawText(ln, left + padH, y, hudVarPaint)
            y += hudVarPaint.descent() + lineGap
        }
    }

    private fun formatVar(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        private const val MAX_EVENTS = 18
    }
}
