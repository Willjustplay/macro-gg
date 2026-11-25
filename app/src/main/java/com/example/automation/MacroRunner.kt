package com.example.automation

import com.example.data.Action
import com.example.data.ClickAction
import com.example.data.ColorAction
import com.example.data.ExitAppAction
import com.example.data.ExitAppMode
import com.example.data.ImageAction
import com.example.data.LaunchAppAction
import com.example.data.LongPressAction
import com.example.data.LoopAction
import com.example.data.Macro
import com.example.data.MacroSecurity
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
import com.example.data.ResolutionScaler
import com.example.data.kind
import com.example.vision.ColorUtil
import com.example.vision.Frame
import com.example.vision.MatchResult
import com.example.vision.OcrBlock
import com.example.vision.PreparedTemplate
import com.example.vision.TemplateMatcher
import com.example.vision.TextConditionEvaluator
import kotlin.math.abs
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
    suspend fun exitApp(mode: ExitAppMode, packageName: String = ""): Boolean = true
    suspend fun launchApp(packageName: String, freshStart: Boolean = false): Boolean = true
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
    RESTART_ROOT,
    STOP_MACRO
}

/**
 * Recursive Macrorify-style macro executor supporting:
 * - Multi-point clicks with burst repeat & jitter
 * - Long press, swipe, 2-finger pinch Zoom In/Out, wait with random jitter
 * - Exit App & Launch App actions for app lifecycle automation & stuck recovery
 * - Per-action success probability ([Action.chancePercent]) for random events
 * - Automatic screen resolution scaling ([ResolutionScaler]) across all devices
 * - Image, OCR Text/Value, and Color detection with full Then/Else branches + Found/NotFound flow modes
 * - Nested loop blocks & Chained/Linked Macros ([RunMacroAction]) with cycle protection & Passive Mode support
 * - Expiration deadline & tamper seal enforcement via [MacroSecurity]
 * - Recorded gesture playback with speed multiplier
 * - Auto-Fix Stuck Macro Watchdog ([Macro.autoRecoverStuckSec], [Macro.stuckActions], [StuckRecoveryMode])
 */
class MacroRunner(
    private val runtime: MacroRuntime,
    private val random: Random = Random.Default
) {
    private var lastProgressMs: Long = 0L
    private var rootWatchdogMacro: Macro? = null
    private var inStuckRecovery: Boolean = false
    /** Caches discovered winning template scale per (imageName@frameWxH) for instant single-pass re-detection. */
    private val discoveredTemplateScale = HashMap<String, Double>()
    /** Caches discovered macro-wide scale per (macroId@frameWxH) so sibling templates match immediately. */
    private val discoveredMacroScale = HashMap<String, Double>()

    suspend fun run(macro: Macro) {
        val now = runtime.nowMs()
        MacroSecurity.checkDirectRun(macro, now)?.let { denial ->
            runtime.log(LogLevel.ERROR, MacroSecurity.denialMessage(macro, denial))
            return
        }
        rootWatchdogMacro = macro
        inStuckRecovery = false
        lastProgressMs = now
        runInternal(macro, depth = 0)
    }

    private suspend fun runInternal(macro: Macro, depth: Int): FlowControl {
        if (depth >= MAX_CHAIN_DEPTH) {
            runtime.log(LogLevel.WARN, "Batas kedalaman macro terhubung ($MAX_CHAIN_DEPTH) tercapai — dihentikan untuk mencegah loop tak hingga.")
            return FlowControl.NEXT
        }
        val initialTransform = ResolutionScaler.forMacro(macro, runtime.screenWidth, runtime.screenHeight)
        if (initialTransform.isScaling) {
            runtime.log(LogLevel.INFO, "Auto-Scale '${macro.name}': ${initialTransform.describe()}")
        }
        val startedAt = runtime.nowMs()
        val maxDurationMs = if (macro.maxDurationMin > 0) macro.maxDurationMin * 60_000L else Long.MAX_VALUE
        val maxLoops = if (macro.loopCount <= 0) Int.MAX_VALUE else macro.loopCount

        var iteration = 0
        while (!runtime.isStopped() && iteration < maxLoops) {
            val now = runtime.nowMs()
            if (depth == 0) {
                if (MacroSecurity.isExpired(macro, now)) {
                    runtime.log(LogLevel.ERROR, MacroSecurity.denialMessage(macro, com.example.data.RunDenialReason.EXPIRED))
                    return FlowControl.STOP_MACRO
                }
            } else {
                val liveSub = runtime.loadMacro(macro.id) ?: macro
                MacroSecurity.checkLinkedRun(liveSub, now)?.let { denial ->
                    val level = if (denial == com.example.data.RunDenialReason.PASSIVE_OFF) LogLevel.INFO else LogLevel.WARN
                    runtime.log(level, MacroSecurity.denialMessage(liveSub, denial))
                    markProgress()
                    return FlowControl.NEXT
                }
            }
            if (now - startedAt >= maxDurationMs) {
                runtime.log(LogLevel.INFO, "Batas waktu ${macro.maxDurationMin} menit tercapai.")
                break
            }
            iteration++
            if (depth == 0) runtime.onLoopChanged(iteration)
            runtime.log(LogLevel.INFO, if (depth == 0) "Loop #$iteration dimulai." else "Sub-macro '${macro.name}' loop #$iteration.")

            val flow = executeList(macro.actions, macro, startedAt, maxDurationMs, depth)
            when (flow) {
                FlowControl.STOP_MACRO -> return FlowControl.STOP_MACRO
                FlowControl.RESTART_ROOT -> {
                    if (depth > 0) return FlowControl.RESTART_ROOT
                    runtime.log(LogLevel.INFO, "Mengulang '${macro.name}' dari awal setelah pemulihan Anti-Macet...")
                    if (macro.loopDelayMs > 0 && !runtime.isStopped()) runtime.sleep(macro.loopDelayMs)
                    continue
                }
                FlowControl.RESTART_MACRO -> {
                    runtime.log(LogLevel.INFO, "Mengulang '${macro.name}' dari awal...")
                    if (macro.loopDelayMs > 0 && !runtime.isStopped()) runtime.sleep(macro.loopDelayMs)
                    continue
                }
                FlowControl.NEXT -> {
                    if (!runtime.isStopped() && iteration < maxLoops) {
                        val effectiveDelay = if (macro.loopDelayMs > 0) {
                            macro.loopDelayMs
                        } else if (maxLoops == Int.MAX_VALUE && runtime.nowMs() == now) {
                            // Prevent tight 0ms spin / log flood if every action in an infinite loop was skipped (e.g. Passive OFF)
                            50L
                        } else {
                            0L
                        }
                        if (effectiveDelay > 0L) {
                            val waitFlow = sleepWatchingStuck(effectiveDelay, macro, startedAt, maxDurationMs, depth)
                            if (waitFlow != FlowControl.NEXT) {
                                if (waitFlow == FlowControl.STOP_MACRO) return FlowControl.STOP_MACRO
                                if (waitFlow == FlowControl.RESTART_ROOT && depth > 0) return FlowControl.RESTART_ROOT
                                continue
                            }
                        }
                    }
                }
            }
        }
        return FlowControl.NEXT
    }

    private fun markProgress() {
        lastProgressMs = runtime.nowMs()
    }

    /** Sleeps in cooperative <= 500ms slices so the stuck-macro watchdog triggers promptly even during long waits. */
    private suspend fun sleepWatchingStuck(
        totalMs: Long,
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long,
        depth: Int
    ): FlowControl {
        var remaining = totalMs
        while (remaining > 0L && !runtime.isStopped()) {
            runtime.waitIfPaused()
            if (runtime.nowMs() - startedAt >= maxDurationMs) return FlowControl.STOP_MACRO
            checkStuckRecovery(macro, startedAt, maxDurationMs, depth)?.let { return it }
            val step = minOf(remaining, 500L)
            runtime.sleep(step)
            remaining -= step
        }
        return if (runtime.isStopped()) FlowControl.STOP_MACRO else FlowControl.NEXT
    }

    /** Checks stuck watchdog (including root watchdog inside sub-macros); runs out-of-band [Macro.stuckActions] when triggered. */
    private suspend fun checkStuckRecovery(
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long,
        depth: Int
    ): FlowControl? {
        if (inStuckRecovery) return null
        val watchdog = when {
            macro.autoRecoverStuckSec > 0 -> macro
            else -> rootWatchdogMacro?.takeIf { it.autoRecoverStuckSec > 0 }
        } ?: return null

        val sec = watchdog.autoRecoverStuckSec
        val elapsed = runtime.nowMs() - lastProgressMs
        if (elapsed < sec * 1000L) return null

        val isRootTriggered = watchdog === rootWatchdogMacro && depth > 0
        val restartSignal = if (isRootTriggered) FlowControl.RESTART_ROOT else FlowControl.RESTART_MACRO

        val effectiveMode = if (watchdog.stuckRecoveryMode == StuckRecoveryMode.RUN_WATCHDOG_ACTIONS && watchdog.stuckActions.isEmpty()) {
            StuckRecoveryMode.RESTART_MACRO
        } else {
            watchdog.stuckRecoveryMode
        }

        runtime.log(LogLevel.WARN, "Anti-Macet (Watchdog): Aksi utama macet > ${sec}d — mengaktifkan '${effectiveMode.label}'.")
        lastProgressMs = runtime.nowMs()

        return when (effectiveMode) {
            StuckRecoveryMode.RUN_WATCHDOG_ACTIONS -> {
                inStuckRecovery = true
                val recFlow = try {
                    runtime.log(LogLevel.WARN, "Menjalankan ${watchdog.stuckActions.size} aksi Anti-Macet di luar aksi utama...")
                    executeList(watchdog.stuckActions, watchdog, startedAt, maxDurationMs, depth = 0)
                } finally {
                    inStuckRecovery = false
                    lastProgressMs = runtime.nowMs()
                }
                when {
                    runtime.isStopped() || recFlow == FlowControl.STOP_MACRO -> FlowControl.STOP_MACRO
                    recFlow == FlowControl.RESTART_MACRO || recFlow == FlowControl.RESTART_ROOT -> restartSignal
                    watchdog.stuckRestartAfter -> restartSignal
                    else -> null
                }
            }
            StuckRecoveryMode.RESTART_MACRO -> restartSignal
            StuckRecoveryMode.TAP_CENTER_AND_RESTART -> {
                runtime.click(runtime.screenWidth / 2, runtime.screenHeight / 2, 50L)
                runtime.sleep(250L)
                lastProgressMs = runtime.nowMs()
                restartSignal
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
            checkStuckRecovery(macro, startedAt, maxDurationMs, depth)?.let { return it }
            if (!action.enabled) continue

            // Per-action Random Event probability roll (0..100%)
            val chance = action.chancePercent.coerceIn(0, 100)
            if (chance < 100) {
                val roll = random.nextInt(100)
                if (roll >= chance) {
                    runtime.log(LogLevel.INFO, "Random Event dilewati (${action.kind.label}, peluang $chance%).")
                    continue
                }
                runtime.log(LogLevel.INFO, "Random Event aktif (${action.kind.label}, peluang $chance%).")
            }

            val flow = executeAction(action, macro, startedAt, maxDurationMs, depth)
            if (flow != FlowControl.NEXT) return flow

            val extraRandom = if (macro.randomDelayMs > 0) random.nextLong(0, macro.randomDelayMs + 1) else 0L
            val totalDelay = action.delayAfterMs + extraRandom
            if (totalDelay > 0 && !runtime.isStopped()) {
                val delayFlow = sleepWatchingStuck(totalDelay, macro, startedAt, maxDurationMs, depth)
                if (delayFlow != FlowControl.NEXT) return delayFlow
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
        val transform = ResolutionScaler.forMacro(macro, runtime.screenWidth, runtime.screenHeight)
        val scaledJitter = transform.scaleDistance(macro.jitterPx)
        return when (action) {
            is ClickAction -> {
                val (sx, sy) = transform.scalePoint(action.x, action.y)
                val times = action.repeat.coerceAtLeast(1)
                for (i in 0 until times) {
                    if (runtime.isStopped()) return FlowControl.STOP_MACRO
                    runtime.waitIfPaused()
                    val (tx, ty) = applyJitter(sx, sy, scaledJitter)
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
                val (sx, sy) = transform.scalePoint(action.x, action.y)
                val (tx, ty) = applyJitter(sx, sy, scaledJitter)
                val dur = action.durationMs.coerceAtLeast(250L)
                runtime.log(LogLevel.ACTION, "Tahan ($tx, $ty) ${dur}ms")
                if (runtime.click(tx, ty, dur)) {
                    runtime.onActionExecuted()
                    markProgress()
                }
                FlowControl.NEXT
            }

            is SwipeAction -> {
                val (sx1, sy1) = transform.scalePoint(action.x1, action.y1)
                val (sx2, sy2) = transform.scalePoint(action.x2, action.y2)
                runtime.log(LogLevel.ACTION, "Swipe ($sx1,$sy1) → ($sx2,$sy2) ${action.durationMs}ms")
                if (runtime.swipe(sx1, sy1, sx2, sy2, action.durationMs)) {
                    runtime.onActionExecuted()
                    markProgress()
                }
                FlowControl.NEXT
            }

            is ZoomAction -> {
                val (scx, scy) = transform.scalePoint(action.cx, action.cy)
                val scaledDist = transform.scaleDistance(action.distancePx)
                val label = if (action.zoomIn) "Zoom In" else "Zoom Out"
                runtime.log(LogLevel.ACTION, "$label di ($scx,$scy) jarak=${scaledDist}px")
                if (runtime.zoom(scx, scy, action.zoomIn, scaledDist, action.durationMs)) {
                    runtime.onActionExecuted()
                    markProgress()
                }
                FlowControl.NEXT
            }

            is WaitAction -> {
                val extra = if (action.randomMs > 0) random.nextLong(0, action.randomMs + 1) else 0L
                val total = (action.durationMs + extra).coerceAtLeast(0L)
                runtime.log(LogLevel.INFO, "Tunggu ${total}ms")
                if (total > 0) {
                    sleepWatchingStuck(total, macro, startedAt, maxDurationMs, depth)
                } else {
                    FlowControl.NEXT
                }
            }

            is ExitAppAction -> {
                val targetName = action.appLabel.ifBlank { action.packageName }.let { if (it.isNotBlank()) " ($it)" else "" }
                runtime.log(LogLevel.ACTION, "Keluar Aplikasi: ${action.mode.label}$targetName")
                if (runtime.exitApp(action.mode, action.packageName)) {
                    runtime.onActionExecuted()
                    markProgress()
                }
                FlowControl.NEXT
            }

            is LaunchAppAction -> {
                if (action.packageName.isBlank()) {
                    runtime.log(LogLevel.WARN, "Masuk Aplikasi dilewati: belum ada aplikasi dipilih.")
                    return FlowControl.NEXT
                }
                val label = action.appLabel.ifBlank { action.packageName }
                runtime.log(LogLevel.ACTION, "Masuk Aplikasi: '$label'" + if (action.freshStart) " (fresh)" else "")
                if (runtime.launchApp(action.packageName, action.freshStart)) {
                    runtime.onActionExecuted()
                    markProgress()
                }
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
                    markProgress()
                    return FlowControl.NEXT
                }
                val target = runtime.loadMacro(action.targetMacroId)
                if (target == null) {
                    runtime.log(LogLevel.WARN, "Macro tujuan '${action.targetMacroName}' tidak ditemukan — dilewati.")
                    markProgress()
                    return FlowControl.NEXT
                }
                // Linked execution allows Passive ON macros; cleanly skips Passive OFF, Expired, or Tampered macros
                // without hanging or triggering the stuck-macro watchdog.
                MacroSecurity.checkLinkedRun(target, runtime.nowMs())?.let { denial ->
                    val level = if (denial == com.example.data.RunDenialReason.PASSIVE_OFF) LogLevel.INFO else LogLevel.WARN
                    runtime.log(level, MacroSecurity.denialMessage(target, denial))
                    markProgress()
                    return FlowControl.NEXT
                }
                val tag = if (target.isPassive) "macro pasif" else "macro"
                runtime.log(
                    LogLevel.INFO,
                    if (action.returnAfter) "Memanggil $tag '${target.name}'..."
                    else "Berpindah ke $tag '${target.name}'..."
                )
                markProgress()
                val subFlow = runInternal(target, depth + 1)
                when {
                    subFlow == FlowControl.RESTART_ROOT -> FlowControl.RESTART_ROOT
                    !action.returnAfter || subFlow == FlowControl.STOP_MACRO -> FlowControl.STOP_MACRO
                    else -> FlowControl.NEXT
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
                    val scaledGesture = transform.scaleRecordedGesture(g)
                    if (runtime.playRecordedGesture(scaledGesture, speed)) {
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
        var matched: MatchResult? = null
        var bestScore = 0.0
        var usedScale = 1.0
        var sawFrame = false
        var lastMultiScaleMs = Long.MIN_VALUE / 2
        var multiScaleCount = 0

        while (!runtime.isStopped()) {
            runtime.waitIfPaused()
            checkStuckRecovery(macro, startedAt, maxDurationMs, depth)?.let { return it }
            val frame = runtime.captureFrame()
            if (frame != null) {
                sawFrame = true
                val frameTransform = ResolutionScaler.forMacro(macro, frame.width, frame.height)
                val scaledRegion = action.region?.let { frameTransform.scaleRegion(it) }
                val tplKey = "${action.imageName}@${frame.width}x${frame.height}"
                val macroKey = "${macro.id}@${frame.width}x${frame.height}"

                // Build ordered fast-path (sx, sy) candidates:
                // 1) previously discovered scale for this exact template
                // 2) expected resolution transform scales (or 1.0x)
                // 3) previously discovered macro-wide scale (helps sibling templates hit immediately)
                val fastScales = ArrayList<Pair<Double, Double>>(6)
                fun addFastScale(sx: Double, sy: Double) {
                    if (fastScales.none { abs(it.first - sx) < 0.02 && abs(it.second - sy) < 0.02 }) {
                        fastScales += sx to sy
                    }
                }
                if (macro.autoScaleResolution) {
                    discoveredTemplateScale[tplKey]?.let { addFastScale(it, it) }
                }
                frameTransform.templateScaleCandidates().forEach { (sx, sy) -> addFastScale(sx, sy) }
                if (macro.autoScaleResolution) {
                    discoveredMacroScale[macroKey]?.let { addFastScale(it, it) }
                }

                val triedUniformScales = ArrayList<Double>(fastScales.size)
                for ((sx, sy) in fastScales) {
                    val cand = template.scaled(sx, sy)
                    if (cand.width > frame.width || cand.height > frame.height) continue
                    if (abs(sx - sy) < 0.02) triedUniformScales += sx
                    val outcome = if (scaledRegion != null &&
                        scaledRegion.width >= cand.width &&
                        scaledRegion.height >= cand.height
                    ) {
                        TemplateMatcher.findWithStats(
                            frame, cand, action.threshold.toDouble(),
                            scaledRegion.left, scaledRegion.top, scaledRegion.right, scaledRegion.bottom
                        )
                    } else {
                        TemplateMatcher.findWithStats(frame, cand, action.threshold.toDouble())
                    }
                    if (outcome.bestScore > bestScore) bestScore = outcome.bestScore
                    if (outcome.match != null) {
                        matched = outcome.match
                        usedScale = (sx + sy) / 2.0
                        if (macro.autoScaleResolution && abs(sx - sy) < 0.02) {
                            discoveredTemplateScale[tplKey] = sx
                            if (abs(sx - 1.0) >= 0.02) discoveredMacroScale[macroKey] = sx
                        }
                        break
                    }
                }

                // Adaptive multi-scale fallback (0.50x..1.50x with local ±1.5%..±4% refinement):
                // catches DPI differences, legacy exports with sourceWidth==0, and gallery-cropped templates!
                if (matched == null && macro.autoScaleResolution) {
                    val nowMs = runtime.nowMs()
                    val msCooldown = if (discoveredMacroScale.containsKey(macroKey)) 750L else 400L
                    if (multiScaleCount == 0 || nowMs - lastMultiScaleMs >= msCooldown) {
                        lastMultiScaleMs = nowMs
                        multiScaleCount++
                        val validRegion = scaledRegion?.takeIf {
                            it.width >= (template.width * 0.5).toInt().coerceAtLeast(8) &&
                                it.height >= (template.height * 0.5).toInt().coerceAtLeast(8)
                        }
                        val rLeft = validRegion?.left ?: 0
                        val rTop = validRegion?.top ?: 0
                        val rRight = validRegion?.right ?: frame.width
                        val rBottom = validRegion?.bottom ?: frame.height
                        val ms = TemplateMatcher.findMultiScale(
                            frame = frame,
                            template = template,
                            threshold = action.threshold.toDouble(),
                            regionLeft = rLeft,
                            regionTop = rTop,
                            regionRight = rRight,
                            regionBottom = rBottom,
                            skipScales = triedUniformScales
                        )
                        if (ms.bestScore > bestScore) bestScore = ms.bestScore
                        if (ms.match != null) {
                            matched = ms.match
                            usedScale = ms.bestScale
                            discoveredTemplateScale[tplKey] = ms.bestScale
                            discoveredMacroScale[macroKey] = ms.bestScale
                        }
                    }
                }
                if (matched != null) break
            }
            if (runtime.nowMs() >= deadline) break
            runtime.sleep(120L)
        }

        if (matched != null) {
            val pct = (matched.score * 100).toInt()
            val scaleSuffix = if (abs(usedScale - 1.0) >= 0.02) " (skala ${(usedScale * 100).toInt()}%)" else ""
            runtime.log(LogLevel.IMAGE, "Ditemukan '${action.imageName}' di (${matched.centerX},${matched.centerY}) $pct%$scaleSuffix")
            runtime.onMatchFound(matched, "${action.imageName} $pct%")
            markProgress()
            if (action.clickOnFound) {
                val clickTransform = ResolutionScaler.forMacro(macro, runtime.screenWidth, runtime.screenHeight)
                val (ox, oy) = clickTransform.scaleDelta(action.offsetX, action.offsetY)
                val (tx, ty) = applyJitter(
                    matched.centerX + ox,
                    matched.centerY + oy,
                    clickTransform.scaleDistance(macro.jitterPx)
                )
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
        val transform = ResolutionScaler.forMacro(macro, runtime.screenWidth, runtime.screenHeight)
        val (sx, sy) = transform.scalePoint(action.x, action.y)
        val deadline = runtime.nowMs() + action.timeoutMs.coerceAtLeast(0L)
        var matched = false
        while (!runtime.isStopped()) {
            runtime.waitIfPaused()
            checkStuckRecovery(macro, startedAt, maxDurationMs, depth)?.let { return it }
            val argb = runtime.samplePixelArgb(sx, sy)
            if (argb != null && ColorUtil.matches(argb, action.color, action.tolerance)) {
                matched = true
                break
            }
            if (runtime.nowMs() >= deadline) break
            runtime.sleep(100L)
        }

        if (matched) {
            runtime.log(LogLevel.COLOR, "Warna ${action.color} cocok di ($sx,$sy)")
            markProgress()
            if (action.clickOnFound) {
                val (tx, ty) = applyJitter(sx, sy, transform.scaleDistance(macro.jitterPx))
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
        val transform = ResolutionScaler.forMacro(macro, runtime.screenWidth, runtime.screenHeight)
        val scaledRegion = action.region?.let { transform.scaleRegion(it) }
        val deadline = runtime.nowMs() + action.timeoutMs.coerceAtLeast(0L)
        var lastOutcome = TextConditionEvaluator.evaluate(action, emptyList())
        var sawBlocks = false

        while (!runtime.isStopped()) {
            runtime.waitIfPaused()
            checkStuckRecovery(macro, startedAt, maxDurationMs, depth)?.let { return it }
            val blocks = runtime.recognizeText(scaledRegion)
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
                    ?: scaledRegion?.let { it.left + it.width / 2 }
                    ?: (runtime.screenWidth / 2)
                val cy = lastOutcome.matchedBlock?.centerY
                    ?: scaledRegion?.let { it.top + it.height / 2 }
                    ?: (runtime.screenHeight / 2)
                val (tx, ty) = applyJitter(cx, cy, transform.scaleDistance(macro.jitterPx))
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
