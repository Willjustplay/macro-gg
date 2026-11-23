package com.example.automation

import com.example.data.Action
import com.example.data.ClickAction
import com.example.data.ColorAction
import com.example.data.ImageAction
import com.example.data.LongPressAction
import com.example.data.LoopAction
import com.example.data.Macro
import com.example.data.NotFoundMode
import com.example.data.RecordAction
import com.example.data.RecordedGesture
import com.example.data.RestartAction
import com.example.data.StopAction
import com.example.data.SwipeAction
import com.example.data.WaitAction
import com.example.vision.ColorUtil
import com.example.vision.Frame
import com.example.vision.MatchResult
import com.example.vision.PreparedTemplate
import com.example.vision.TemplateMatcher
import kotlin.math.roundToLong
import kotlin.random.Random

/** Abstraction over OS gesture/capture/storage so [MacroRunner] is pure and unit-testable. */
interface MacroRuntime {
    fun isStopped(): Boolean
    suspend fun waitIfPaused()
    suspend fun sleep(ms: Long)
    suspend fun click(x: Int, y: Int, durationMs: Long): Boolean
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean
    suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float): Boolean
    fun captureFrame(): Frame?
    fun samplePixelArgb(x: Int, y: Int): Int?
    fun loadTemplate(name: String): PreparedTemplate?
    fun onMatchFound(match: MatchResult, label: String) {}
    fun onLoopChanged(loop: Int) {}
    fun onActionExecuted() {}
    fun log(level: LogLevel, message: String)
    fun nowMs(): Long = System.currentTimeMillis()
}

private enum class FlowControl {
    NEXT,
    RESTART_MACRO,
    STOP_MACRO
}

/**
 * Recursive Macrorify-style macro executor supporting:
 * - Multi-point clicks with burst repeat & jitter
 * - Long press, swipe, wait with random jitter
 * - Image detection (template matching) with click-on-found + then/else branches + notFound modes
 * - Color detection with click-on-found + then/else branches + notFound modes
 * - Nested loop blocks
 * - Recorded gesture playback with speed multiplier
 * - Restart & Stop control flow + max duration guard
 */
class MacroRunner(
    private val runtime: MacroRuntime,
    private val random: Random = Random.Default
) {
    suspend fun run(macro: Macro) {
        val startedAt = runtime.nowMs()
        val maxDurationMs = if (macro.maxDurationMin > 0) macro.maxDurationMin * 60_000L else Long.MAX_VALUE
        val maxLoops = if (macro.loopCount <= 0) Int.MAX_VALUE else macro.loopCount

        var iteration = 0
        while (!runtime.isStopped() && iteration < maxLoops) {
            if (runtime.nowMs() - startedAt >= maxDurationMs) {
                runtime.log(LogLevel.INFO, "Batas waktu ${macro.maxDurationMin} menit tercapai.")
                break
            }
            iteration++
            runtime.onLoopChanged(iteration)
            runtime.log(LogLevel.INFO, "Loop #$iteration dimulai.")

            val flow = executeList(macro.actions, macro, startedAt, maxDurationMs)
            when (flow) {
                FlowControl.STOP_MACRO -> break
                FlowControl.RESTART_MACRO -> {
                    runtime.log(LogLevel.INFO, "Mengulang macro dari awal...")
                    if (macro.loopDelayMs > 0 && !runtime.isStopped()) runtime.sleep(macro.loopDelayMs)
                    continue
                }
                FlowControl.NEXT -> {
                    if (!runtime.isStopped() && iteration < maxLoops && macro.loopDelayMs > 0) {
                        runtime.sleep(macro.loopDelayMs)
                    }
                }
            }
        }
    }

    private suspend fun executeList(
        actions: List<Action>,
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long
    ): FlowControl {
        for (action in actions) {
            runtime.waitIfPaused()
            if (runtime.isStopped()) return FlowControl.STOP_MACRO
            if (runtime.nowMs() - startedAt >= maxDurationMs) return FlowControl.STOP_MACRO
            if (!action.enabled) continue

            val flow = executeAction(action, macro, startedAt, maxDurationMs)
            if (flow != FlowControl.NEXT) return flow

            val extraRandom = if (macro.randomDelayMs > 0) random.nextLong(0, macro.randomDelayMs + 1) else 0L
            val totalDelay = action.delayAfterMs + extraRandom
            if (totalDelay > 0 && !runtime.isStopped()) {
                runtime.sleep(totalDelay)
            }
        }
        return FlowControl.NEXT
    }

    private suspend fun executeAction(
        action: Action,
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long
    ): FlowControl {
        return when (action) {
            is ClickAction -> {
                val times = action.repeat.coerceAtLeast(1)
                for (i in 0 until times) {
                    if (runtime.isStopped()) return FlowControl.STOP_MACRO
                    runtime.waitIfPaused()
                    val (tx, ty) = applyJitter(action.x, action.y, macro.jitterPx)
                    runtime.log(LogLevel.ACTION, "Klik ($tx, $ty) ${action.durationMs}ms" + if (times > 1) " [${i + 1}/$times]" else "")
                    if (runtime.click(tx, ty, action.durationMs)) runtime.onActionExecuted()
                    if (i + 1 < times && action.repeatIntervalMs > 0) {
                        runtime.sleep(action.repeatIntervalMs)
                    }
                }
                FlowControl.NEXT
            }

            is LongPressAction -> {
                val (tx, ty) = applyJitter(action.x, action.y, macro.jitterPx)
                val dur = action.durationMs.coerceAtLeast(250L)
                runtime.log(LogLevel.ACTION, "Tahan ($tx, $ty) ${dur}ms")
                if (runtime.click(tx, ty, dur)) runtime.onActionExecuted()
                FlowControl.NEXT
            }

            is SwipeAction -> {
                runtime.log(LogLevel.ACTION, "Swipe (${action.x1},${action.y1}) → (${action.x2},${action.y2}) ${action.durationMs}ms")
                if (runtime.swipe(action.x1, action.y1, action.x2, action.y2, action.durationMs)) {
                    runtime.onActionExecuted()
                }
                FlowControl.NEXT
            }

            is WaitAction -> {
                val extra = if (action.randomMs > 0) random.nextLong(0, action.randomMs + 1) else 0L
                val total = (action.durationMs + extra).coerceAtLeast(0L)
                runtime.log(LogLevel.INFO, "Tunggu ${total}ms")
                if (total > 0) runtime.sleep(total)
                FlowControl.NEXT
            }

            is ImageAction -> executeImageAction(action, macro, startedAt, maxDurationMs)

            is ColorAction -> executeColorAction(action, macro, startedAt, maxDurationMs)

            is LoopAction -> {
                val times = action.count.coerceAtLeast(1)
                for (i in 1..times) {
                    if (runtime.isStopped()) return FlowControl.STOP_MACRO
                    runtime.waitIfPaused()
                    runtime.log(LogLevel.INFO, "Blok ulang #$i/$times")
                    val flow = executeList(action.actions, macro, startedAt, maxDurationMs)
                    if (flow != FlowControl.NEXT) return flow
                }
                FlowControl.NEXT
            }

            is RecordAction -> {
                val speed = action.speed.coerceIn(0.25f, 5f)
                runtime.log(LogLevel.ACTION, "Putar rekaman (${action.gestures.size} gestur, ${speed}x)")
                for (g in action.gestures) {
                    if (runtime.isStopped()) return FlowControl.STOP_MACRO
                    runtime.waitIfPaused()
                    val waitMs = (g.delayBeforeMs / speed).roundToLong().coerceAtLeast(0L)
                    if (waitMs > 0) runtime.sleep(waitMs)
                    if (runtime.isStopped()) return FlowControl.STOP_MACRO
                    if (runtime.playRecordedGesture(g, speed)) runtime.onActionExecuted()
                }
                FlowControl.NEXT
            }

            is RestartAction -> FlowControl.RESTART_MACRO

            is StopAction -> {
                runtime.log(LogLevel.INFO, "Aksi Hentikan dijalankan.")
                FlowControl.STOP_MACRO
            }
        }
    }

    private suspend fun executeImageAction(
        action: ImageAction,
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long
    ): FlowControl {
        if (action.imageName.isBlank()) {
            runtime.log(LogLevel.WARN, "Deteksi Gambar dilewati: belum ada gambar dipilih.")
            return FlowControl.NEXT
        }
        val template = runtime.loadTemplate(action.imageName)
        if (template == null) {
            runtime.log(LogLevel.WARN, "Gambar '${action.imageName}' tidak ditemukan di pustaka.")
            return handleNotFound(action.elseActions, action.notFound, macro, startedAt, maxDurationMs)
        }

        val deadline = runtime.nowMs() + action.timeoutMs.coerceAtLeast(0L)
        val region = action.region
        var matched: MatchResult? = null
        var bestScore = 0.0
        var sawFrame = false

        while (!runtime.isStopped()) {
            runtime.waitIfPaused()
            val frame = runtime.captureFrame()
            if (frame != null) {
                sawFrame = true
                val outcome = if (region != null && region.width >= template.width && region.height >= template.height) {
                    TemplateMatcher.findWithStats(
                        frame, template, action.threshold.toDouble(),
                        region.left, region.top, region.right, region.bottom
                    )
                } else {
                    TemplateMatcher.findWithStats(frame, template, action.threshold.toDouble())
                }
                if (outcome.bestScore > bestScore) bestScore = outcome.bestScore
                if (outcome.match != null) {
                    matched = outcome.match
                    break
                }
            }
            if (runtime.nowMs() >= deadline) break
            runtime.sleep(120L)
        }

        if (matched != null) {
            val pct = (matched.score * 100).toInt()
            runtime.log(LogLevel.IMAGE, "Ditemukan '${action.imageName}' di (${matched.centerX},${matched.centerY}) $pct%")
            runtime.onMatchFound(matched, "${action.imageName} $pct%")
            if (action.clickOnFound) {
                val (tx, ty) = applyJitter(matched.centerX + action.offsetX, matched.centerY + action.offsetY, macro.jitterPx)
                if (runtime.click(tx, ty, 50L)) runtime.onActionExecuted()
            }
            return executeList(action.thenActions, macro, startedAt, maxDurationMs)
        } else {
            val reqPct = (action.threshold * 100).toInt()
            val maxPct = (bestScore * 100).toInt()
            if (!sawFrame) {
                runtime.log(LogLevel.WARN, "Deteksi '${action.imageName}' gagal: Tangkap Layar belum aktif / belum ada frame.")
            } else {
                runtime.log(LogLevel.IMAGE, "Gambar '${action.imageName}' belum cocok (maks $maxPct% < $reqPct%).")
            }
            return handleNotFound(action.elseActions, action.notFound, macro, startedAt, maxDurationMs)
        }
    }

    private suspend fun executeColorAction(
        action: ColorAction,
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long
    ): FlowControl {
        val deadline = runtime.nowMs() + action.timeoutMs.coerceAtLeast(0L)
        var matched = false
        while (!runtime.isStopped()) {
            runtime.waitIfPaused()
            val argb = runtime.samplePixelArgb(action.x, action.y)
            if (argb != null && ColorUtil.matches(argb, action.color, action.tolerance)) {
                matched = true
                break
            }
            if (runtime.nowMs() >= deadline) break
            runtime.sleep(100L)
        }

        if (matched) {
            runtime.log(LogLevel.COLOR, "Warna ${action.color} cocok di (${action.x},${action.y})")
            if (action.clickOnFound) {
                val (tx, ty) = applyJitter(action.x, action.y, macro.jitterPx)
                if (runtime.click(tx, ty, 50L)) runtime.onActionExecuted()
            }
            return executeList(action.thenActions, macro, startedAt, maxDurationMs)
        } else {
            runtime.log(LogLevel.COLOR, "Warna ${action.color} tidak cocok.")
            return handleNotFound(action.elseActions, action.notFound, macro, startedAt, maxDurationMs)
        }
    }

    private suspend fun handleNotFound(
        elseActions: List<Action>,
        mode: NotFoundMode,
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long
    ): FlowControl {
        val elseFlow = executeList(elseActions, macro, startedAt, maxDurationMs)
        if (elseFlow != FlowControl.NEXT) return elseFlow
        return when (mode) {
            NotFoundMode.CONTINUE -> FlowControl.NEXT
            NotFoundMode.RESTART -> FlowControl.RESTART_MACRO
            NotFoundMode.STOP -> FlowControl.STOP_MACRO
        }
    }

    private fun applyJitter(x: Int, y: Int, jitterPx: Int): Pair<Int, Int> {
        if (jitterPx <= 0) return x.coerceAtLeast(0) to y.coerceAtLeast(0)
        val dx = random.nextInt(-jitterPx, jitterPx + 1)
        val dy = random.nextInt(-jitterPx, jitterPx + 1)
        return (x + dx).coerceAtLeast(0) to (y + dy).coerceAtLeast(0)
    }
}
