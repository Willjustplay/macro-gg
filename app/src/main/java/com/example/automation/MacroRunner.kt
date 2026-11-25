package com.example.automation

import com.example.data.Action
import com.example.data.ClickAction
import com.example.data.ColorAction
import com.example.data.ExitAppAction
import com.example.data.ExitAppMode
import com.example.data.FinishMacroAction
import com.example.data.FinishMacroMode
import com.example.data.ImageAction
import com.example.data.JumpAction
import com.example.data.JumpMode
import com.example.data.LaunchAppAction
import com.example.data.LongPressAction
import com.example.data.LoopAction
import com.example.data.Macro
import com.example.data.MacroSecurity
import com.example.data.NotFoundMode
import com.example.data.RecordAction
import com.example.data.RecordedGesture
import com.example.data.RecentsSwipeDirection
import com.example.data.RestartAction
import com.example.data.RunMacroAction
import com.example.data.StopAction
import com.example.data.StuckRecoveryMode
import com.example.data.SwipeAction
import com.example.data.TextAction
import com.example.data.TrackInteractionMode
import com.example.data.TrackObjectAction
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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
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
    suspend fun exitApp(
        mode: ExitAppMode,
        packageName: String,
        recentsSwipeDirection: RecentsSwipeDirection
    ): Boolean = exitApp(mode, packageName)
    suspend fun exitApp(
        mode: ExitAppMode,
        packageName: String,
        recentsSwipeDirection: RecentsSwipeDirection,
        forceStopBackground: Boolean
    ): Boolean = exitApp(mode, packageName, recentsSwipeDirection)
    suspend fun launchApp(packageName: String, freshStart: Boolean = false): Boolean = true
    fun setTrackingBoost(enabled: Boolean) {}
    fun captureFrame(): Frame?
    fun samplePixelArgb(x: Int, y: Int): Int?
    suspend fun recognizeText(region: Region?): List<OcrBlock> = emptyList()
    suspend fun recognizeText(region: Region?, enhanced: Boolean): List<OcrBlock> = recognizeText(region)
    fun loadTemplate(name: String): PreparedTemplate?
    fun loadMacro(id: String): Macro? = null
    fun onActiveMacroSwitched(target: Macro) {}
    fun onMatchFound(match: MatchResult, label: String) {}
    fun onLoopChanged(loop: Int) {}
    fun onActionExecuted() {}
    fun log(level: LogLevel, message: String)
    fun nowMs(): Long = System.currentTimeMillis()
}

private enum class FlowControl {
    NEXT,
    JUMP,
    RESTART_MACRO,
    RESTART_ROOT,
    FINISH_TO_ACTIVE_CONTINUE,
    FINISH_TO_ACTIVE_RESTART,
    SWITCH_TO_ACTIVE_MACRO,
    STOP_MACRO
}

private data class PendingJump(
    val mode: JumpMode,
    val targetActionId: String,
    val targetActionLabel: String,
    val stepCount: Int,
    val sourceActionId: String
)

/**
 * Recursive Macrorify-style macro executor supporting:
 * - Multi-point clicks with burst repeat & jitter
 * - Long press, swipe, 2-finger pinch Zoom In/Out, wait with random jitter
 * - Exit App & Launch App actions for app lifecycle automation & stuck recovery
 * - Per-action success probability ([Action.chancePercent]) for random events
 * - Automatic screen resolution scaling ([ResolutionScaler]) across all devices
 * - Image, OCR Text/Value, and Color detection with full Then/Else branches + Found/NotFound flow modes
 * - Nested loop blocks, [JumpAction] forward/backward jumps, & Chained/Linked Macros ([RunMacroAction]) with cycle protection, Passive Mode & [FinishMacroAction]
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
    private var pendingSwitchMacro: Macro? = null
    private var pendingJump: PendingJump? = null
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
        var currentMacro = macro
        pendingSwitchMacro = null
        pendingJump = null
        while (!runtime.isStopped()) {
            rootWatchdogMacro = currentMacro
            inStuckRecovery = false
            pendingJump = null
            lastProgressMs = runtime.nowMs()
            val flow = runInternal(currentMacro, depth = 0)
            if (flow == FlowControl.SWITCH_TO_ACTIVE_MACRO) {
                val nextTarget = pendingSwitchMacro
                pendingSwitchMacro = null
                if (nextTarget != null && !runtime.isStopped()) {
                    runtime.onActiveMacroSwitched(nextTarget)
                    currentMacro = nextTarget
                    continue
                }
            }
            break
        }
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

            pendingJump = null
            val flow = executeList(macro.actions, macro, startedAt, maxDurationMs, depth, isRootList = true)
            pendingJump = null
            when (flow) {
                FlowControl.STOP_MACRO -> return FlowControl.STOP_MACRO
                FlowControl.SWITCH_TO_ACTIVE_MACRO -> return FlowControl.SWITCH_TO_ACTIVE_MACRO
                FlowControl.FINISH_TO_ACTIVE_CONTINUE -> {
                    if (depth > 0) return FlowControl.FINISH_TO_ACTIVE_CONTINUE
                    runtime.log(LogLevel.INFO, "Siklus '${macro.name}' diselesaikan — lanjut ke siklus berikutnya.")
                    val d = if (macro.loopDelayMs > 0) macro.loopDelayMs else if (maxLoops == Int.MAX_VALUE && runtime.nowMs() == now) 50L else 0L
                    if (d > 0L && !runtime.isStopped()) runtime.sleep(d)
                    continue
                }
                FlowControl.FINISH_TO_ACTIVE_RESTART -> {
                    if (depth > 0) return FlowControl.FINISH_TO_ACTIVE_RESTART
                    runtime.log(LogLevel.INFO, "Macro aktif '${macro.name}' diulang dari aksi pertama tanpa berhenti.")
                    if (maxLoops != Int.MAX_VALUE && iteration >= maxLoops) {
                        iteration = (maxLoops - 1).coerceAtLeast(0)
                    }
                    val d = if (macro.loopDelayMs > 0) macro.loopDelayMs else if (runtime.nowMs() == now) 50L else 0L
                    if (d > 0L && !runtime.isStopped()) runtime.sleep(d)
                    continue
                }
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
                FlowControl.NEXT, FlowControl.JUMP -> {
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
                                when (waitFlow) {
                                    FlowControl.STOP_MACRO -> return FlowControl.STOP_MACRO
                                    FlowControl.SWITCH_TO_ACTIVE_MACRO -> return FlowControl.SWITCH_TO_ACTIVE_MACRO
                                    FlowControl.FINISH_TO_ACTIVE_CONTINUE,
                                    FlowControl.FINISH_TO_ACTIVE_RESTART,
                                    FlowControl.RESTART_ROOT -> if (depth > 0) return waitFlow
                                    else -> Unit
                                }
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
                val savedJump = pendingJump
                pendingJump = null
                val recFlow = try {
                    runtime.log(LogLevel.WARN, "Menjalankan ${watchdog.stuckActions.size} aksi Anti-Macet di luar aksi utama...")
                    executeList(watchdog.stuckActions, watchdog, startedAt, maxDurationMs, depth = 0, isRootList = true)
                } finally {
                    pendingJump = savedJump
                    inStuckRecovery = false
                    lastProgressMs = runtime.nowMs()
                }
                when {
                    runtime.isStopped() || recFlow == FlowControl.STOP_MACRO -> FlowControl.STOP_MACRO
                    recFlow == FlowControl.SWITCH_TO_ACTIVE_MACRO -> FlowControl.SWITCH_TO_ACTIVE_MACRO
                    recFlow == FlowControl.FINISH_TO_ACTIVE_RESTART -> restartSignal
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
        depth: Int,
        isBranch: Boolean = false,
        isRootList: Boolean = false
    ): FlowControl {
        var idx = 0
        var lastJumpClockMs = Long.MIN_VALUE
        var consecutiveZeroTimeJumps = 0

        while (idx < actions.size) {
            runtime.waitIfPaused()
            if (runtime.isStopped()) return FlowControl.STOP_MACRO
            if (runtime.nowMs() - startedAt >= maxDurationMs) return FlowControl.STOP_MACRO
            checkStuckRecovery(macro, startedAt, maxDurationMs, depth)?.let { return it }

            val action = actions[idx]
            if (!action.enabled) {
                idx++
                continue
            }

            // Per-action Random Event probability roll (0..100%)
            val chance = action.chancePercent.coerceIn(0, 100)
            if (chance < 100) {
                val roll = random.nextInt(100)
                if (roll >= chance) {
                    runtime.log(LogLevel.INFO, "Random Event dilewati (${action.kind.label}, peluang $chance%).")
                    idx++
                    continue
                }
                runtime.log(LogLevel.INFO, "Random Event aktif (${action.kind.label}, peluang $chance%).")
            }

            val flow = executeAction(action, macro, startedAt, maxDurationMs, depth)
            if (flow == FlowControl.JUMP) {
                val jump = pendingJump
                if (jump == null) {
                    idx++
                    continue
                }

                // Resolve target index within this action list, or propagate to an enclosing list
                val resolvedNextIdx: Int? = when (jump.mode) {
                    JumpMode.SKIP_AFTER_ACTION -> {
                        val targetIdx = actions.indexOfFirst { it.id == jump.targetActionId }
                        if (targetIdx >= 0) {
                            pendingJump = null
                            val nextIdx = targetIdx + 1
                            val targetName = "#${targetIdx + 1} (${actions[targetIdx].kind.label})"
                            if (nextIdx < actions.size) {
                                runtime.log(LogLevel.INFO, "Loncat melewati $targetName → lanjut di #${nextIdx + 1} (${actions[nextIdx].kind.label}).")
                            } else {
                                runtime.log(LogLevel.INFO, "Loncat melewati $targetName → selesai siklus ini.")
                            }
                            nextIdx
                        } else if (isRootList) {
                            pendingJump = null
                            runtime.log(LogLevel.WARN, "Loncat Aksi dilewati: aksi tujuan '${jump.targetActionLabel.ifBlank { jump.targetActionId }}' tidak ditemukan.")
                            idx + 1
                        } else {
                            null
                        }
                    }
                    JumpMode.JUMP_TO_ACTION -> {
                        val targetIdx = actions.indexOfFirst { it.id == jump.targetActionId }
                        if (targetIdx >= 0) {
                            pendingJump = null
                            if (targetIdx == idx && actions[idx].id == jump.sourceActionId) {
                                runtime.log(LogLevel.WARN, "Loncat Aksi ke diri sendiri (#${idx + 1}) dilewati agar tidak macet.")
                                idx + 1
                            } else {
                                val dir = if (targetIdx < idx) "kembali ke belakang" else "maju"
                                runtime.log(LogLevel.INFO, "Loncat $dir → #${targetIdx + 1} (${actions[targetIdx].kind.label}).")
                                targetIdx
                            }
                        } else if (isRootList) {
                            pendingJump = null
                            runtime.log(LogLevel.WARN, "Loncat Aksi dilewati: aksi tujuan '${jump.targetActionLabel.ifBlank { jump.targetActionId }}' tidak ditemukan.")
                            idx + 1
                        } else {
                            null
                        }
                    }
                    JumpMode.SKIP_FORWARD_STEPS -> {
                        if (isBranch && !isRootList) {
                            null
                        } else {
                            pendingJump = null
                            val skip = jump.stepCount.coerceAtLeast(1)
                            val nextIdx = idx + 1 + skip
                            if (nextIdx < actions.size) {
                                runtime.log(LogLevel.INFO, "Loncat lewati +$skip aksi → lanjut di #${nextIdx + 1} (${actions[nextIdx].kind.label}).")
                            } else {
                                runtime.log(LogLevel.INFO, "Loncat lewati +$skip aksi → selesai siklus ini.")
                            }
                            nextIdx
                        }
                    }
                    JumpMode.JUMP_BACKWARD_STEPS -> {
                        if (isBranch && !isRootList) {
                            null
                        } else {
                            pendingJump = null
                            val back = jump.stepCount.coerceAtLeast(1)
                            val nextIdx = (idx - back).coerceAtLeast(0)
                            if (nextIdx == idx && actions[idx].id == jump.sourceActionId) {
                                runtime.log(LogLevel.WARN, "Loncat mundur di aksi #1 tidak bisa mundur lagi — lanjut ke berikutnya.")
                                idx + 1
                            } else {
                                runtime.log(LogLevel.INFO, "Loncat kembali -$back aksi ke belakang → #${nextIdx + 1} (${actions[nextIdx].kind.label}).")
                                nextIdx
                            }
                        }
                    }
                }

                if (resolvedNextIdx == null) {
                    return FlowControl.JUMP
                }

                // Cooperative anti-spin guard for zero-delay backward jump loops
                if (resolvedNextIdx <= idx) {
                    val nowClock = runtime.nowMs()
                    if (nowClock == lastJumpClockMs) {
                        consecutiveZeroTimeJumps++
                        if (consecutiveZeroTimeJumps >= 20) {
                            consecutiveZeroTimeJumps = 0
                            runtime.sleep(25L)
                        }
                    } else {
                        lastJumpClockMs = nowClock
                        consecutiveZeroTimeJumps = 0
                    }
                }

                idx = resolvedNextIdx
                continue
            }

            if (flow != FlowControl.NEXT) return flow

            val extraRandom = if (macro.randomDelayMs > 0) random.nextLong(0, macro.randomDelayMs + 1) else 0L
            val totalDelay = action.delayAfterMs + extraRandom
            if (totalDelay > 0 && !runtime.isStopped()) {
                val delayFlow = sleepWatchingStuck(totalDelay, macro, startedAt, maxDurationMs, depth)
                if (delayFlow != FlowControl.NEXT) return delayFlow
            }
            idx++
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
        val liveW = runtime.screenWidth.coerceAtLeast(4)
        val liveH = runtime.screenHeight.coerceAtLeast(4)
        val transform = ResolutionScaler.forMacro(macro, liveW, liveH)
        val scaledJitter = transform.scaleDistance(macro.jitterPx)
        return when (action) {
            is ClickAction -> {
                val (sx, sy) = transform.scalePoint(action.x, action.y)
                val times = action.repeat.coerceAtLeast(1)
                for (i in 0 until times) {
                    if (runtime.isStopped()) return FlowControl.STOP_MACRO
                    runtime.waitIfPaused()
                    val (rawTx, rawTy) = applyJitter(sx, sy, scaledJitter)
                    val tx = rawTx.coerceIn(1, liveW - 2)
                    val ty = rawTy.coerceIn(1, liveH - 2)
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
                val (rawTx, rawTy) = applyJitter(sx, sy, scaledJitter)
                val tx = rawTx.coerceIn(1, liveW - 2)
                val ty = rawTy.coerceIn(1, liveH - 2)
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
                if (runtime.exitApp(action.mode, action.packageName, action.recentsSwipeDirection, action.forceStopBackground)) {
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

            is TrackObjectAction -> executeTrackObjectAction(action, macro, startedAt, maxDurationMs, depth)

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
                when (subFlow) {
                    FlowControl.RESTART_ROOT -> FlowControl.RESTART_ROOT
                    FlowControl.SWITCH_TO_ACTIVE_MACRO -> FlowControl.SWITCH_TO_ACTIVE_MACRO
                    FlowControl.FINISH_TO_ACTIVE_CONTINUE -> {
                        if (macro.isPassive && depth > 0) {
                            FlowControl.FINISH_TO_ACTIVE_CONTINUE
                        } else {
                            runtime.log(LogLevel.INFO, "Selesai dari '${target.name}' — kembali melanjutkan macro aktif '${macro.name}'.")
                            markProgress()
                            FlowControl.NEXT
                        }
                    }
                    FlowControl.FINISH_TO_ACTIVE_RESTART -> {
                        if (macro.isPassive && depth > 0) {
                            FlowControl.FINISH_TO_ACTIVE_RESTART
                        } else {
                            runtime.log(LogLevel.INFO, "Selesai dari '${target.name}' — kembali mengulang macro aktif '${macro.name}' dari awal.")
                            markProgress()
                            FlowControl.FINISH_TO_ACTIVE_RESTART
                        }
                    }
                    FlowControl.STOP_MACRO -> FlowControl.STOP_MACRO
                    else -> if (!action.returnAfter) FlowControl.STOP_MACRO else FlowControl.NEXT
                }
            }

            is FinishMacroAction -> {
                markProgress()
                when (action.mode) {
                    FinishMacroMode.RETURN_TO_ACTIVE_CONTINUE -> {
                        runtime.log(LogLevel.INFO, "Selesaikan Macro '${macro.name}' — langsung kembali & lanjutkan macro aktif.")
                        FlowControl.FINISH_TO_ACTIVE_CONTINUE
                    }
                    FinishMacroMode.RETURN_TO_ACTIVE_RESTART -> {
                        runtime.log(LogLevel.INFO, "Selesaikan Macro '${macro.name}' — langsung kembali & ulangi macro aktif dari awal.")
                        FlowControl.FINISH_TO_ACTIVE_RESTART
                    }
                    FinishMacroMode.SWITCH_TO_SPECIFIC_ACTIVE -> {
                        if (action.targetMacroId.isBlank()) {
                            runtime.log(LogLevel.WARN, "Selesaikan Macro: macro aktif tujuan belum dipilih — kembali ke macro aktif pemanggil.")
                            FlowControl.FINISH_TO_ACTIVE_CONTINUE
                        } else {
                            val target = runtime.loadMacro(action.targetMacroId)
                            if (target == null) {
                                runtime.log(LogLevel.WARN, "Selesaikan Macro: macro tujuan '${action.targetMacroName}' tidak ditemukan — kembali ke macro aktif pemanggil.")
                                FlowControl.FINISH_TO_ACTIVE_CONTINUE
                            } else {
                                val denial = MacroSecurity.checkDirectRun(target, runtime.nowMs())
                                if (denial != null) {
                                    runtime.log(LogLevel.WARN, "Selesaikan Macro: ${MacroSecurity.denialMessage(target, denial)} — kembali ke macro aktif pemanggil.")
                                    FlowControl.FINISH_TO_ACTIVE_CONTINUE
                                } else {
                                    runtime.log(LogLevel.INFO, "Selesaikan Macro '${macro.name}' — switch langsung ke macro aktif '${target.name}' tanpa berhenti.")
                                    pendingSwitchMacro = target
                                    FlowControl.SWITCH_TO_ACTIVE_MACRO
                                }
                            }
                        }
                    }
                }
            }

            is JumpAction -> {
                val needsTargetId = action.mode == JumpMode.SKIP_AFTER_ACTION || action.mode == JumpMode.JUMP_TO_ACTION
                if (needsTargetId && action.targetActionId.isBlank()) {
                    runtime.log(LogLevel.WARN, "Loncat Aksi dilewati: aksi tujuan belum dipilih.")
                    return FlowControl.NEXT
                }
                if (action.delayAfterMs > 0L && !runtime.isStopped()) {
                    val waitFlow = sleepWatchingStuck(action.delayAfterMs, macro, startedAt, maxDurationMs, depth)
                    if (waitFlow != FlowControl.NEXT) return waitFlow
                }
                pendingJump = PendingJump(
                    mode = action.mode,
                    targetActionId = action.targetActionId,
                    targetActionLabel = action.targetActionLabel,
                    stepCount = action.stepCount.coerceAtLeast(1),
                    sourceActionId = action.id
                )
                FlowControl.JUMP
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

    private data class TrackVariant(
        val template: PreparedTemplate,
        val label: String,
        val cacheKey: String
    )

    private data class TrackHit(
        val match: MatchResult,
        val usedScale: Double,
        val label: String,
        val variantIndex: Int
    )

    private suspend fun executeTrackObjectAction(
        action: TrackObjectAction,
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long,
        depth: Int
    ): FlowControl {
        val names = action.allTemplateNames()
        if (names.isEmpty()) {
            runtime.log(LogLevel.WARN, "Kejar Objek dilewati: belum ada tangkapan gambar objek yang dipilih.")
            return FlowControl.NEXT
        }

        val variants = ArrayList<TrackVariant>(names.size * 4)
        for (name in names) {
            val raw = runtime.loadTemplate(name) ?: continue
            val shortLabel = name.substringAfterLast('/')
            if (action.ignoreBackgroundEdges) {
                val core = raw.centerFocused()
                variants += TrackVariant(core, shortLabel, "$name#core")
                if (action.matchMirrorX) {
                    variants += TrackVariant(core.mirroredX(), "$shortLabel↔", "$name#core#mirror")
                }
                if (core !== raw) {
                    variants += TrackVariant(raw, shortLabel, name)
                    if (action.matchMirrorX) {
                        variants += TrackVariant(raw.mirroredX(), "$shortLabel↔", "$name#mirror")
                    }
                }
            } else {
                variants += TrackVariant(raw, shortLabel, name)
                if (action.matchMirrorX) {
                    variants += TrackVariant(raw.mirroredX(), "$shortLabel↔", "$name#mirror")
                }
            }
        }
        if (variants.isEmpty()) {
            runtime.log(LogLevel.WARN, "Gambar objek '${names.first()}' tidak ditemukan di pustaka.")
            return handleBranch(action.elseActions, action.notFound, macro, startedAt, maxDurationMs, depth)
        }

        val threshold = action.threshold.toDouble().coerceIn(0.45, 0.99)
        val pollMs = 45L
        var bestScore = 0.0
        var sawFrame = false
        var lastWinnerIdx = 0
        var lastMultiScaleMs = Long.MIN_VALUE / 2
        var multiScaleCount = 0

        fun searchVariantsInBounds(
            frame: Frame,
            macroRef: Macro,
            rLeft: Int,
            rTop: Int,
            rRight: Int,
            rBottom: Int,
            allowMultiScale: Boolean
        ): TrackHit? {
            if (rRight <= rLeft || rBottom <= rTop) return null
            val frameTransform = ResolutionScaler.forMacro(macroRef, frame.width, frame.height)
            val macroKey = "${macroRef.id}@${frame.width}x${frame.height}"
            val order = IntArray(variants.size) { i -> (lastWinnerIdx + i) % variants.size }

            for (idx in order) {
                val v = variants[idx]
                val tplKey = "${v.cacheKey}@${frame.width}x${frame.height}"
                val fastScales = ArrayList<Pair<Double, Double>>(6)
                fun addScale(sx: Double, sy: Double) {
                    if (fastScales.none { abs(it.first - sx) < 0.02 && abs(it.second - sy) < 0.02 }) {
                        fastScales += sx to sy
                    }
                }
                if (macroRef.autoScaleResolution) {
                    discoveredTemplateScale[tplKey]?.let { addScale(it, it) }
                }
                frameTransform.templateScaleCandidates().forEach { (sx, sy) -> addScale(sx, sy) }
                if (macroRef.autoScaleResolution) {
                    discoveredMacroScale[macroKey]?.let { addScale(it, it) }
                }

                for ((sx, sy) in fastScales) {
                    val cand = v.template.scaled(sx, sy)
                    if (cand.width > (rRight - rLeft) || cand.height > (rBottom - rTop)) continue
                    val outcome = TemplateMatcher.findWithStats(
                        frame, cand, threshold, rLeft, rTop, rRight, rBottom
                    )
                    if (outcome.bestScore > bestScore) bestScore = outcome.bestScore
                    if (outcome.match != null) {
                        val scale = (sx + sy) / 2.0
                        if (macroRef.autoScaleResolution && abs(sx - sy) < 0.02) {
                            discoveredTemplateScale[tplKey] = sx
                            if (abs(sx - 1.0) >= 0.02) discoveredMacroScale[macroKey] = sx
                        }
                        lastWinnerIdx = idx
                        return TrackHit(outcome.match, scale, v.label, idx)
                    }
                }
            }

            if (allowMultiScale && macroRef.autoScaleResolution) {
                val nowMs = runtime.nowMs()
                val msCooldown = if (discoveredMacroScale.containsKey(macroKey)) 800L else 450L
                if (multiScaleCount == 0 || nowMs - lastMultiScaleMs >= msCooldown) {
                    lastMultiScaleMs = nowMs
                    multiScaleCount++
                    for (idx in order) {
                        val v = variants[idx]
                        if (rRight - rLeft < (v.template.width * 0.45).toInt().coerceAtLeast(8) ||
                            rBottom - rTop < (v.template.height * 0.45).toInt().coerceAtLeast(8)
                        ) continue
                        val ms = TemplateMatcher.findMultiScale(
                            frame = frame,
                            template = v.template,
                            threshold = threshold,
                            regionLeft = rLeft,
                            regionTop = rTop,
                            regionRight = rRight,
                            regionBottom = rBottom
                        )
                        if (ms.bestScore > bestScore) bestScore = ms.bestScore
                        if (ms.match != null) {
                            val tplKey = "${v.cacheKey}@${frame.width}x${frame.height}"
                            discoveredTemplateScale[tplKey] = ms.bestScale
                            discoveredMacroScale[macroKey] = ms.bestScale
                            lastWinnerIdx = idx
                            return TrackHit(ms.match, ms.bestScale, v.label, idx)
                        }
                    }
                }
            }
            return null
        }

        fun locateInFrame(
            frame: Frame,
            lastPos: Pair<Int, Int>?,
            allowMultiScale: Boolean
        ): TrackHit? {
            val ft = ResolutionScaler.forMacro(macro, frame.width, frame.height)
            val region = action.region?.let { ft.scaleRegion(it) }
            val fullLeft = (region?.left ?: 0).coerceIn(0, frame.width)
            val fullTop = (region?.top ?: 0).coerceIn(0, frame.height)
            val fullRight = (region?.right ?: frame.width).coerceIn(fullLeft, frame.width)
            val fullBottom = (region?.bottom ?: frame.height).coerceIn(fullTop, frame.height)

            // Fast local ROI tracking window around last known object position
            if (lastPos != null) {
                val radius = ft.scaleDistance(action.trackRadiusPx.coerceIn(60, 1200)).coerceAtLeast(60)
                val l = (lastPos.first - radius).coerceIn(fullLeft, fullRight)
                val t = (lastPos.second - radius).coerceIn(fullTop, fullBottom)
                val r = (lastPos.first + radius).coerceIn(l, fullRight)
                val b = (lastPos.second + radius).coerceIn(t, fullBottom)
                searchVariantsInBounds(frame, macro, l, t, r, b, allowMultiScale = false)?.let { return it }
            }

            // Fallback: full region / full screen
            return searchVariantsInBounds(frame, macro, fullLeft, fullTop, fullRight, fullBottom, allowMultiScale)
        }

        fun predictAim(
            curX: Int,
            curY: Int,
            vx: Double,
            vy: Double,
            leadMs: Long,
            maxLeadPx: Int,
            sw: Int,
            sh: Int
        ): Pair<Int, Int> {
            if (leadMs <= 0L) return curX to curY
            var dx = vx * leadMs
            var dy = vy * leadMs
            val dist = hypot(dx, dy)
            val cap = maxLeadPx.coerceAtLeast(100).toDouble()
            if (dist > cap && dist > 1e-6) {
                val ratio = cap / dist
                dx *= ratio
                dy *= ratio
            }
            val px = (curX + dx.roundToInt()).coerceIn(2, (sw - 3).coerceAtLeast(2))
            val py = (curY + dy.roundToInt()).coerceIn(2, (sh - 3).coerceAtLeast(2))
            return px to py
        }

        suspend fun performInteraction(aimX: Int, aimY: Int, sw: Int, sh: Int, hit: TrackHit): Boolean {
            val clickTransform = ResolutionScaler.forMacro(macro, sw, sh)
            val (ox, oy) = clickTransform.scaleDelta(action.offsetX, action.offsetY)
            val (gx, gy) = applyJitter(
                (aimX + ox).coerceIn(2, (sw - 3).coerceAtLeast(2)),
                (aimY + oy).coerceIn(2, (sh - 3).coerceAtLeast(2)),
                clickTransform.scaleDistance(macro.jitterPx)
            )
            val pct = (hit.match.score * 100).toInt()
            val leadTag = if (aimX != hit.match.centerX || aimY != hit.match.centerY) " (prediksi)" else ""
            val ok = when (action.interactionMode) {
                TrackInteractionMode.TAP_ONCE,
                TrackInteractionMode.TAP_CONTINUOUS -> {
                    val tapDur = action.tapDurationMs.coerceIn(20L, 400L)
                    runtime.log(LogLevel.ACTION, "Kejar '${hit.label}' $pct% → Tap ($gx,$gy)$leadTag")
                    runtime.click(gx, gy, tapDur)
                }
                TrackInteractionMode.DRAG_TO_POINT -> {
                    val (destX, destY) = clickTransform.scalePoint(action.targetX, action.targetY)
                    val cx = destX.coerceIn(2, (sw - 3).coerceAtLeast(2))
                    val cy = destY.coerceIn(2, (sh - 3).coerceAtLeast(2))
                    val dur = action.dragDurationMs.coerceIn(60L, 5000L)
                    runtime.log(LogLevel.ACTION, "Kejar '${hit.label}' $pct% → Drag ($gx,$gy)→($cx,$cy) ${dur}ms$leadTag")
                    runtime.swipe(gx, gy, cx, cy, dur)
                }
                TrackInteractionMode.DRAG_BY_OFFSET -> {
                    val (ddx, ddy) = clickTransform.scaleDelta(action.dragOffsetX, action.dragOffsetY)
                    val cx = (gx + ddx).coerceIn(2, (sw - 3).coerceAtLeast(2))
                    val cy = (gy + ddy).coerceIn(2, (sh - 3).coerceAtLeast(2))
                    val dur = action.dragDurationMs.coerceIn(60L, 5000L)
                    runtime.log(LogLevel.ACTION, "Kejar '${hit.label}' $pct% → Drag ($gx,$gy)→($cx,$cy) ${dur}ms$leadTag")
                    runtime.swipe(gx, gy, cx, cy, dur)
                }
                TrackInteractionMode.DRAG_FROM_POINT_TO_OBJECT -> {
                    val (sx, sy) = clickTransform.scalePoint(action.targetX, action.targetY)
                    val cx = sx.coerceIn(2, (sw - 3).coerceAtLeast(2))
                    val cy = sy.coerceIn(2, (sh - 3).coerceAtLeast(2))
                    val dur = action.dragDurationMs.coerceIn(60L, 5000L)
                    runtime.log(LogLevel.ACTION, "Kejar '${hit.label}' $pct% → Drag ($cx,$cy)→($gx,$gy) ${dur}ms$leadTag")
                    runtime.swipe(cx, cy, gx, gy, dur)
                }
            }
            if (ok) {
                runtime.onActionExecuted()
                markProgress()
            }
            return ok
        }

        runtime.setTrackingBoost(true)
        try {
            // Phase 1: Initial object acquisition
            val deadline = runtime.nowMs() + action.timeoutMs.coerceAtLeast(0L)
            var firstHit: TrackHit? = null
            var firstHitTime = 0L
            var firstFrameW = runtime.screenWidth
            var firstFrameH = runtime.screenHeight

            while (!runtime.isStopped()) {
                runtime.waitIfPaused()
                checkStuckRecovery(macro, startedAt, maxDurationMs, depth)?.let { return it }
                val frame = runtime.captureFrame()
                if (frame != null) {
                    sawFrame = true
                    firstFrameW = frame.width
                    firstFrameH = frame.height
                    val hit = locateInFrame(frame, lastPos = null, allowMultiScale = true)
                    if (hit != null) {
                        firstHit = hit
                        firstHitTime = runtime.nowMs()
                        break
                    }
                }
                if (runtime.nowMs() >= deadline) break
                runtime.sleep(pollMs)
            }

            val acquired = firstHit
            if (acquired == null) {
                val reqPct = (threshold * 100).toInt()
                val maxPct = (bestScore * 100).toInt()
                if (!sawFrame) {
                    runtime.log(LogLevel.WARN, "Kejar Objek '${names.first()}' gagal: Tangkap Layar belum aktif.")
                } else {
                    runtime.log(LogLevel.IMAGE, "Objek '${names.first()}' tidak ditemukan (maks $maxPct% < $reqPct%).")
                }
                return handleBranch(action.elseActions, action.notFound, macro, startedAt, maxDurationMs, depth)
            }

            val leadMs = action.leadPredictionMs.coerceIn(0L, 400L)
            val initTransform = ResolutionScaler.forMacro(macro, firstFrameW, firstFrameH)
            val maxLeadPx = max(140, initTransform.scaleDistance(action.trackRadiusPx.coerceIn(60, 1200)))

            // Phase 2: Optional 2nd frame sample to measure live velocity (vx, vy) for lead prediction
            var curHit = acquired
            var curTime = firstHitTime
            var vx = 0.0
            var vy = 0.0
            var curW = firstFrameW
            var curH = firstFrameH

            if (leadMs > 0L && !runtime.isStopped()) {
                runtime.sleep(pollMs)
                if (!runtime.isStopped()) {
                    runtime.waitIfPaused()
                    val frame2 = runtime.captureFrame()
                    if (frame2 != null) {
                        val hit2 = locateInFrame(
                            frame2,
                            lastPos = acquired.match.centerX to acquired.match.centerY,
                            allowMultiScale = false
                        )
                        if (hit2 != null) {
                            val t2 = runtime.nowMs()
                            val dt = t2 - firstHitTime
                            if (dt in 15L..550L) {
                                vx = (hit2.match.centerX - acquired.match.centerX).toDouble() / dt.toDouble()
                                vy = (hit2.match.centerY - acquired.match.centerY).toDouble() / dt.toDouble()
                            }
                            curHit = hit2
                            curTime = t2
                            curW = frame2.width
                            curH = frame2.height
                        }
                    }
                }
            }

            if (action.interactionMode != TrackInteractionMode.TAP_CONTINUOUS) {
                val (aimX, aimY) = predictAim(curHit.match.centerX, curHit.match.centerY, vx, vy, leadMs, maxLeadPx, curW, curH)
                val pct = (curHit.match.score * 100).toInt()
                runtime.onMatchFound(curHit.match, "🎯 ${curHit.label} $pct%")
                markProgress()
                performInteraction(aimX, aimY, curW, curH, curHit)
            } else {
                // Continuous chase-and-tap loop until the moving object disappears or maxTrackDurationMs expires
                val trackDeadline = runtime.nowMs() + action.maxTrackDurationMs.coerceIn(300L, 300_000L)
                val repeatInterval = action.repeatIntervalMs.coerceIn(40L, 5000L)
                val lostGraceMs = max(450L, repeatInterval * 3L).coerceAtMost(2500L)
                var prevX = curHit.match.centerX
                var prevY = curHit.match.centerY
                var prevT = curTime
                var lastSeenMs = curTime
                var lastActionMs = Long.MIN_VALUE / 2
                var chaseCount = 0

                // Fire first tap immediately on the acquired (and velocity-predicted) object
                val (initAimX, initAimY) = predictAim(prevX, prevY, vx, vy, leadMs, maxLeadPx, curW, curH)
                runtime.onMatchFound(curHit.match, "🎯 ${curHit.label} ${(curHit.match.score * 100).toInt()}%")
                if (performInteraction(initAimX, initAimY, curW, curH, curHit)) {
                    chaseCount++
                    lastActionMs = runtime.nowMs()
                }

                while (!runtime.isStopped() && runtime.nowMs() < trackDeadline) {
                    runtime.waitIfPaused()
                    checkStuckRecovery(macro, startedAt, maxDurationMs, depth)?.let { return it }
                    runtime.sleep(pollMs)
                    if (runtime.isStopped()) break

                    val frame = runtime.captureFrame() ?: continue
                    val now = runtime.nowMs()
                    val hit = locateInFrame(frame, lastPos = prevX to prevY, allowMultiScale = false)
                    if (hit != null) {
                        val dt = now - prevT
                        if (dt in 15L..600L) {
                            val instVx = (hit.match.centerX - prevX).toDouble() / dt.toDouble()
                            val instVy = (hit.match.centerY - prevY).toDouble() / dt.toDouble()
                            vx = vx * 0.35 + instVx * 0.65
                            vy = vy * 0.35 + instVy * 0.65
                        } else if (dt > 600L) {
                            vx = 0.0
                            vy = 0.0
                        }
                        prevX = hit.match.centerX
                        prevY = hit.match.centerY
                        prevT = now
                        lastSeenMs = now
                        runtime.onMatchFound(hit.match, "🎯 ${hit.label} ${(hit.match.score * 100).toInt()}%")
                        if (now - lastActionMs >= repeatInterval) {
                            val (aimX, aimY) = predictAim(prevX, prevY, vx, vy, leadMs, maxLeadPx, frame.width, frame.height)
                            if (performInteraction(aimX, aimY, frame.width, frame.height, hit)) {
                                chaseCount++
                                lastActionMs = runtime.nowMs()
                            }
                        }
                    } else if (now - lastSeenMs >= lostGraceMs) {
                        runtime.log(LogLevel.IMAGE, "Kejar Objek '${names.first()}' selesai: objek hilang setelah $chaseCount× aksi.")
                        break
                    }
                }
            }

            return handleBranch(action.thenActions, action.foundMode, macro, startedAt, maxDurationMs, depth)
        } finally {
            runtime.setTrackingBoost(false)
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
        val liveW = runtime.screenWidth.coerceAtLeast(4)
        val liveH = runtime.screenHeight.coerceAtLeast(4)
        val transform = ResolutionScaler.forMacro(macro, liveW, liveH)
        val scaledRegion = action.region?.let { transform.scaleRegion(it) }
        val deadline = runtime.nowMs() + action.timeoutMs.coerceAtLeast(0L)
        var lastOutcome = TextConditionEvaluator.evaluate(action, emptyList())
        var sawBlocks = false

        fun updateBestOutcome(candidate: com.example.vision.TextMatchOutcome) {
            if (candidate.matched) {
                lastOutcome = candidate
            } else if (candidate.allNumbers.isNotEmpty() || (!lastOutcome.allNumbers.isNotEmpty() && candidate.allText.isNotBlank())) {
                lastOutcome = candidate
            } else if (lastOutcome.allText.isBlank()) {
                lastOutcome = candidate
            }
        }

        while (!runtime.isStopped()) {
            runtime.waitIfPaused()
            checkStuckRecovery(macro, startedAt, maxDurationMs, depth)?.let { return it }

            val fastBlocks = runtime.recognizeText(scaledRegion, enhanced = false)
            if (fastBlocks.isNotEmpty()) sawBlocks = true
            val fastOutcome = TextConditionEvaluator.evaluate(action, fastBlocks)
            updateBestOutcome(fastOutcome)
            if (fastOutcome.matched) break

            // If fast RGB pass missed (common on outlined/colored game HUD digits), run high-contrast HUD OCR pass
            val enhancedBlocks = runtime.recognizeText(scaledRegion, enhanced = true)
            if (enhancedBlocks.isNotEmpty()) {
                sawBlocks = true
                val combined = (fastBlocks + enhancedBlocks).distinctBy { "${it.text.lowercase()}|${it.left / 12}|${it.top / 12}" }
                val enhOutcome = TextConditionEvaluator.evaluate(action, combined)
                updateBestOutcome(enhOutcome)
                if (enhOutcome.matched) break
            }

            if (runtime.nowMs() >= deadline) break
            runtime.sleep(200L)
        }

        if (lastOutcome.matched) {
            val detail = if (action.compareMode.isNumeric && lastOutcome.parsedNumber != null) {
                val pStr = formatPlainNumber(lastOutcome.parsedNumber!!)
                val tStr = formatPlainNumber(action.targetNumber)
                "nilai=$pStr (${action.compareMode.shortLabel} $tStr)"
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
                    ?: (liveW / 2)
                val cy = lastOutcome.matchedBlock?.centerY
                    ?: scaledRegion?.let { it.top + it.height / 2 }
                    ?: (liveH / 2)
                val (rawTx, rawTy) = applyJitter(cx, cy, transform.scaleDistance(macro.jitterPx))
                val tx = rawTx.coerceIn(1, liveW - 2)
                val ty = rawTy.coerceIn(1, liveH - 2)
                if (runtime.click(tx, ty, 80L)) runtime.onActionExecuted()
            }
            return handleBranch(action.thenActions, action.foundMode, macro, startedAt, maxDurationMs, depth)
        } else {
            val readInfo = when {
                !sawBlocks -> "tidak ada teks terbaca (pastikan area baca menutupi teks/angka)"
                action.compareMode.isNumeric -> {
                    val numsStr = lastOutcome.allNumbers.take(5).map { formatPlainNumber(it) }
                    val tStr = formatPlainNumber(action.targetNumber)
                    "terbaca=\"${lastOutcome.allText.take(36)}\", angka=$numsStr, syarat ${action.compareMode.shortLabel} $tStr"
                }
                else -> "terbaca=\"${lastOutcome.allText.take(40)}\""
            }
            runtime.log(LogLevel.INFO, "Teks/Nilai belum cocok ($readInfo).")
            return handleBranch(action.elseActions, action.notFound, macro, startedAt, maxDurationMs, depth)
        }
    }

    private fun formatPlainNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()

    private suspend fun handleBranch(
        branchActions: List<Action>,
        endMode: NotFoundMode,
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long,
        depth: Int
    ): FlowControl {
        val childFlow = executeList(branchActions, macro, startedAt, maxDurationMs, depth, isBranch = true)
        if (childFlow != FlowControl.NEXT) return childFlow
        return when (endMode) {
            NotFoundMode.CONTINUE -> FlowControl.NEXT
            NotFoundMode.RESTART -> FlowControl.RESTART_MACRO
            NotFoundMode.FINISH -> {
                markProgress()
                runtime.log(LogLevel.INFO, "Cabang selesai — selesaikan '${macro.name}' & kembali ke macro aktif.")
                FlowControl.FINISH_TO_ACTIVE_CONTINUE
            }
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
