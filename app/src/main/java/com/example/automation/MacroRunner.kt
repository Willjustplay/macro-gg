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
import com.example.data.RunMacroAction
import com.example.data.StopAction
import com.example.data.StuckRecoveryMode
import com.example.data.SwipeAction
import com.example.data.TextAction
import com.example.data.WaitAction
import com.example.data.ZoomAction
import com.example.data.Region
import com.example.vision.ColorUtil
import com.example.vision.Frame
import com.example.vision.MatchResult
import com.example.vision.OcrBlock
import com.example.vision.PreparedTemplate
import com.example.vision.TemplateMatcher
import com.example.vision.TextConditionEvaluator
import kotlin.math.roundToLong
import kotlin.random.Random

/** Abstraction over OS gesture/capture/storage so [MacroRunner] is pure and unit-testable. */
interface MacroRuntime {
    val screenWidth: Int get() = 1080
    val screenHeight: Int get() = 2400
    fun isStopped(): Boolean
    suspend fun waitIfPaused()
    suspend fun sleep(ms: Long)
    suspend fun click(x: Int, y: Int, durationMs: Long): Boolean
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean
    suspend fun zoom(cx: Int, cy: Int, zoomIn: Boolean, distancePx: Int, durationMs: Long): Boolean = true
    suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float): Boolean
    fun captureFrame(): Frame?
    fun samplePixelArgb(x: Int, y: Int): Int?
    suspend fun recognizeText(region: Region?): List<OcrBlock> = emptyList()
    fun loadTemplate(name: String): PreparedTemplate?
    fun loadMacro(id: String): Macro? = null
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
 * - Long press, swipe, 2-finger pinch Zoom In/Out, wait with random jitter
 * - Image detection (template matching) & Color detection with full Then/Else branches + Found/NotFound flow modes
 * - Nested loop blocks & Chained/Linked Macros ([RunMacroAction]) with cycle protection
 * - Recorded gesture playback with speed multiplier
 * - Auto-Fix Stuck Macro Watchdog ([Macro.autoRecoverStuckSec] & [StuckRecoveryMode])
 */
class MacroRunner(
    private val runtime: MacroRuntime,
    private val random: Random = Random.Default
) {
    private var lastProgressMs: Long = 0L

    suspend fun run(macro: Macro) {
        lastProgressMs = runtime.nowMs()
        runInternal(macro, depth = 0)
    }

    private suspend fun runInternal(macro: Macro, depth: Int): FlowControl {
        if (depth >= MAX_CHAIN_DEPTH) {
            runtime.log(LogLevel.WARN, "Batas kedalaman macro terhubung ($MAX_CHAIN_DEPTH) tercapai — dihentikan untuk mencegah loop tak hingga.")
            return FlowControl.NEXT
        }
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
            if (depth == 0) runtime.onLoopChanged(iteration)
            runtime.log(LogLevel.INFO, if (depth == 0) "Loop #$iteration dimulai." else "Sub-macro '${macro.name}' loop #$iteration.")

            val flow = executeList(macro.actions, macro, startedAt, maxDurationMs, depth)
            when (flow) {
                FlowControl.STOP_MACRO -> return FlowControl.STOP_MACRO
                FlowControl.RESTART_MACRO -> {
                    runtime.log(LogLevel.INFO, "Mengulang '${macro.name}' dari awal...")
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
        return FlowControl.NEXT
    }

    private fun markProgress() {
        lastProgressMs = runtime.nowMs()
    }

    /** Checks stuck watchdog; if triggered, performs recovery action and returns control signal. */
    private suspend fun checkStuckRecovery(macro: Macro): FlowControl? {
        val sec = macro.autoRecoverStuckSec
        if (sec <= 0) return null
        val elapsed = runtime.nowMs() - lastProgressMs
        if (elapsed < sec * 1000L) return null

        runtime.log(LogLevel.WARN, "Auto-Perbaiki: Macro macet > ${sec}d — menjalankan '${macro.stuckRecoveryMode.label}'.")
        lastProgressMs = runtime.nowMs()
        return when (macro.stuckRecoveryMode) {
            StuckRecoveryMode.RESTART_MACRO -> FlowControl.RESTART_MACRO
            StuckRecoveryMode.TAP_CENTER_AND_RESTART -> {
                runtime.click(runtime.screenWidth / 2, runtime.screenHeight / 2, 50L)
                runtime.sleep(250L)
                lastProgressMs = runtime.nowMs()
                FlowControl.RESTART_MACRO
            }
            StuckRecoveryMode.STOP_MACRO -> FlowControl.STOP_MACRO
        }
    }

    private suspend fun executeList(
        actions: List<Action>,
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long,
        depth: Int
    ): FlowControl {
        for (action in actions) {
            runtime.waitIfPaused()
            if (runtime.isStopped()) return FlowControl.STOP_MACRO
            if (runtime.nowMs() - startedAt >= maxDurationMs) return FlowControl.STOP_MACRO
            checkStuckRecovery(macro)?.let { return it }
            if (!action.enabled) continue

            val flow = executeAction(action, macro, startedAt, maxDurationMs, depth)
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
        maxDurationMs: Long,
        depth: Int
    ): FlowControl {
        return when (action) {
            is ClickAction -> {
                val times = action.repeat.coerceAtLeast(1)
                for (i in 0 until times) {
                    if (runtime.isStopped()) return FlowControl.STOP_MACRO
                    runtime.waitIfPaused()
                    val (tx, ty) = applyJitter(action.x, action.y, macro.jitterPx)
                    runtime.log(LogLevel.ACTION, "Klik ($tx, $ty) ${action.durationMs}ms" + if (times > 1) " [${i + 1}/$times]" else "")
                    if (runtime.click(tx, ty, action.durationMs)) {
                        runtime.onActionExecuted()
                        markProgress()
                    }
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
                if (runtime.click(tx, ty, dur)) {
                    runtime.onActionExecuted()
                    markProgress()
                }
                FlowControl.NEXT
            }

            is SwipeAction -> {
                runtime.log(LogLevel.ACTION, "Swipe (${action.x1},${action.y1}) → (${action.x2},${action.y2}) ${action.durationMs}ms")
                if (runtime.swipe(action.x1, action.y1, action.x2, action.y2, action.durationMs)) {
                    runtime.onActionExecuted()
                    markProgress()
                }
                FlowControl.NEXT
            }

            is ZoomAction -> {
                val label = if (action.zoomIn) "Zoom In" else "Zoom Out"
                runtime.log(LogLevel.ACTION, "$label di (${action.cx},${action.cy}) jarak=${action.distancePx}px")
                if (runtime.zoom(action.cx, action.cy, action.zoomIn, action.distancePx, action.durationMs)) {
                    runtime.onActionExecuted()
                    markProgress()
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

            is ImageAction -> executeImageAction(action, macro, startedAt, maxDurationMs, depth)

            is TextAction -> executeTextAction(action, macro, startedAt, maxDurationMs, depth)

            is ColorAction -> executeColorAction(action, macro, startedAt, maxDurationMs, depth)

            is LoopAction -> {
                val times = action.count.coerceAtLeast(1)
                for (i in 1..times) {
                    if (runtime.isStopped()) return FlowControl.STOP_MACRO
                    runtime.waitIfPaused()
                    runtime.log(LogLevel.INFO, "Blok ulang #$i/$times")
                    val flow = executeList(action.actions, macro, startedAt, maxDurationMs, depth)
                    if (flow != FlowControl.NEXT) return flow
                }
                FlowControl.NEXT
            }

            is RunMacroAction -> {
                if (action.targetMacroId.isBlank()) {
                    runtime.log(LogLevel.WARN, "Hubungkan Macro dilewati: belum ada macro tujuan dipilih.")
                    return FlowControl.NEXT
                }
                val target = runtime.loadMacro(action.targetMacroId)
                if (target == null) {
                    runtime.log(LogLevel.WARN, "Macro tujuan '${action.targetMacroName}' tidak ditemukan.")
                    return FlowControl.NEXT
                }
                runtime.log(
                    LogLevel.INFO,
                    if (action.returnAfter) "Memanggil macro '${target.name}'..."
                    else "Berpindah ke macro '${target.name}'..."
                )
                markProgress()
                val subFlow = runInternal(target, depth + 1)
                if (!action.returnAfter || subFlow == FlowControl.STOP_MACRO) {
                    FlowControl.STOP_MACRO
                } else {
                    FlowControl.NEXT
                }
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
                    if (runtime.playRecordedGesture(g, speed)) {
                        runtime.onActionExecuted()
                        markProgress()
                    }
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
        maxDurationMs: Long,
        depth: Int
    ): FlowControl {
        if (action.imageName.isBlank()) {
            runtime.log(LogLevel.WARN, "Deteksi Gambar dilewati: belum ada gambar dipilih.")
            return FlowControl.NEXT
        }
        val template = runtime.loadTemplate(action.imageName)
        if (template == null) {
            runtime.log(LogLevel.WARN, "Gambar '${action.imageName}' tidak ditemukan di pustaka.")
            return handleBranch(action.elseActions, action.notFound, macro, startedAt, maxDurationMs, depth)
        }

        val deadline = runtime.nowMs() + action.timeoutMs.coerceAtLeast(0L)
        val region = action.region
        var matched: MatchResult? = null
        var bestScore = 0.0
        var sawFrame = false

        while (!runtime.isStopped()) {
            runtime.waitIfPaused()
            checkStuckRecovery(macro)?.let { return it }
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
            markProgress()
            if (action.clickOnFound) {
                val (tx, ty) = applyJitter(matched.centerX + action.offsetX, matched.centerY + action.offsetY, macro.jitterPx)
                if (runtime.click(tx, ty, 50L)) runtime.onActionExecuted()
            }
            return handleBranch(action.thenActions, action.foundMode, macro, startedAt, maxDurationMs, depth)
        } else {
            val reqPct = (action.threshold * 100).toInt()
            val maxPct = (bestScore * 100).toInt()
            if (!sawFrame) {
                runtime.log(LogLevel.WARN, "Deteksi '${action.imageName}' gagal: Tangkap Layar belum aktif / belum ada frame.")
            } else {
                runtime.log(LogLevel.IMAGE, "Gambar '${action.imageName}' belum cocok (maks $maxPct% < $reqPct%).")
            }
            return handleBranch(action.elseActions, action.notFound, macro, startedAt, maxDurationMs, depth)
        }
    }

    private suspend fun executeColorAction(
        action: ColorAction,
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long,
        depth: Int
    ): FlowControl {
        val deadline = runtime.nowMs() + action.timeoutMs.coerceAtLeast(0L)
        var matched = false
        while (!runtime.isStopped()) {
            runtime.waitIfPaused()
            checkStuckRecovery(macro)?.let { return it }
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
            markProgress()
            if (action.clickOnFound) {
                val (tx, ty) = applyJitter(action.x, action.y, macro.jitterPx)
                if (runtime.click(tx, ty, 50L)) runtime.onActionExecuted()
            }
            return handleBranch(action.thenActions, action.foundMode, macro, startedAt, maxDurationMs, depth)
        } else {
            runtime.log(LogLevel.COLOR, "Warna ${action.color} tidak cocok.")
            return handleBranch(action.elseActions, action.notFound, macro, startedAt, maxDurationMs, depth)
        }
    }

    private suspend fun executeTextAction(
        action: TextAction,
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long,
        depth: Int
    ): FlowControl {
        val deadline = runtime.nowMs() + action.timeoutMs.coerceAtLeast(0L)
        var lastOutcome = TextConditionEvaluator.evaluate(action, emptyList())
        var sawBlocks = false

        while (!runtime.isStopped()) {
            runtime.waitIfPaused()
            checkStuckRecovery(macro)?.let { return it }
            val blocks = runtime.recognizeText(action.region)
            if (blocks.isNotEmpty()) sawBlocks = true
            val outcome = TextConditionEvaluator.evaluate(action, blocks)
            lastOutcome = outcome
            if (outcome.matched) break
            if (runtime.nowMs() >= deadline) break
            runtime.sleep(220L)
        }

        if (lastOutcome.matched) {
            val detail = if (action.compareMode.isNumeric && lastOutcome.parsedNumber != null) {
                "nilai=${lastOutcome.parsedNumber} (${action.compareMode.shortLabel} ${action.targetNumber})"
            } else {
                "\"${lastOutcome.matchedText}\""
            }
            runtime.log(LogLevel.INFO, "Teks/Nilai cocok: $detail")
            lastOutcome.matchedBlock?.let { blk ->
                runtime.onMatchFound(
                    MatchResult(blk.left, blk.top, blk.width, blk.height, 1.0),
                    detail.take(28)
                )
            }
            markProgress()
            if (action.clickOnFound) {
                val cx = lastOutcome.matchedBlock?.centerX
                    ?: action.region?.let { it.left + it.width / 2 }
                    ?: (runtime.screenWidth / 2)
                val cy = lastOutcome.matchedBlock?.centerY
                    ?: action.region?.let { it.top + it.height / 2 }
                    ?: (runtime.screenHeight / 2)
                val (tx, ty) = applyJitter(cx, cy, macro.jitterPx)
                if (runtime.click(tx, ty, 50L)) runtime.onActionExecuted()
            }
            return handleBranch(action.thenActions, action.foundMode, macro, startedAt, maxDurationMs, depth)
        } else {
            val readInfo = when {
                !sawBlocks -> "tidak ada teks terbaca"
                action.compareMode.isNumeric -> "angka terbaca=${lastOutcome.allNumbers.take(4)}"
                else -> "terbaca=\"${lastOutcome.allText.take(40)}\""
            }
            runtime.log(LogLevel.INFO, "Teks/Nilai belum cocok ($readInfo).")
            return handleBranch(action.elseActions, action.notFound, macro, startedAt, maxDurationMs, depth)
        }
    }

    private suspend fun handleBranch(
        branchActions: List<Action>,
        endMode: NotFoundMode,
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long,
        depth: Int
    ): FlowControl {
        val childFlow = executeList(branchActions, macro, startedAt, maxDurationMs, depth)
        if (childFlow != FlowControl.NEXT) return childFlow
        return when (endMode) {
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

    companion object {
        private const val MAX_CHAIN_DEPTH = 5
    }
}
