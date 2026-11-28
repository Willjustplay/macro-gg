package com.example.automation

import com.example.data.AccountFailBehavior
import com.example.data.AccountOcrMatchMode
import com.example.data.AccountProfile
import com.example.data.AccountRotationMode
import com.example.data.AccountScrollDirection
import com.example.data.AccountSwitchMethod
import com.example.data.AccountTriggerMode
import com.example.data.Action
import com.example.data.ActionRemoteControl
import com.example.data.BannedImageMode
import com.example.data.RemoteActionMode
import com.example.data.BranchConditionType
import com.example.data.BranchNavigationMode
import com.example.data.BranchOutcomeAction
import com.example.data.BranchRouteRule
import com.example.data.BranchRouterAction
import com.example.data.CheckVariableAction
import com.example.data.ClickAction
import com.example.data.ColorAction
import com.example.data.ExitAppAction
import com.example.data.ExitAppMode
import com.example.data.FarmingStatsCalculator
import com.example.data.FinishMacroAction
import com.example.data.FinishMacroMode
import com.example.data.GoToBranchAction
import com.example.data.HumanizerPreset
import com.example.data.ImageAction
import com.example.data.JumpAction
import com.example.data.JumpMode
import com.example.data.LaunchAppAction
import com.example.data.LongPressAction
import com.example.data.LoopAction
import com.example.data.Macro
import com.example.data.MacroSecurity
import com.example.data.MacroSet
import com.example.data.NotFoundMode
import com.example.data.NotifyAction
import com.example.data.NotifyLevel
import com.example.data.RecordAction
import com.example.data.RecordedGesture
import com.example.data.RecentsSwipeDirection
import com.example.data.RestartAction
import com.example.data.RunMacroAction
import com.example.data.SelectAccountAction
import com.example.data.StopAction
import com.example.data.StuckRecoveryMode
import com.example.data.SwipeAction
import com.example.data.SwitchAccountAction
import com.example.data.TextAction
import com.example.data.TrackInteractionMode
import com.example.data.TrackObjectAction
import com.example.data.VariableAction
import com.example.data.VariableOperation
import com.example.data.VariableScope
import com.example.data.WaitAction
import com.example.data.ZoomAction
import com.example.data.Region
import com.example.data.ResolutionScaler
import com.example.data.actionsForBranch
import com.example.data.branchDisplayName
import com.example.data.kind
import com.example.vision.ColorUtil
import com.example.vision.Frame
import com.example.vision.MatchResult
import com.example.vision.MovingObjectTracker
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
    suspend fun swipeCurved(
        x1: Int,
        y1: Int,
        cx: Int,
        cy: Int,
        x2: Int,
        y2: Int,
        durationMs: Long
    ): Boolean = swipe(x1, y1, x2, y2, durationMs)
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
    /** Returns true when [packageName] is a PRISM Root delegation stub (`app.prism.c.*`) handled via PRISM Cloner. */
    fun isPrismRootPackage(packageName: String): Boolean = PrismProtocol.isPrismStubPackageName(packageName)
    /** Dispatches a direct anti-BAL PRISM Cloner Root account switch (`SWITCH_ACCOUNT` or `SWITCH_NEXT`). */
    suspend fun switchPrismAccount(request: PrismSwitchRequest): Boolean =
        if (request.stubPackage.isNotBlank()) launchApp(request.stubPackage, freshStart = false) else false
    fun setTrackingBoost(enabled: Boolean) {}
    fun captureFrame(): Frame?
    fun samplePixelArgb(x: Int, y: Int): Int?
    suspend fun recognizeText(region: Region?): List<OcrBlock> = emptyList()
    suspend fun recognizeText(region: Region?, enhanced: Boolean): List<OcrBlock> = recognizeText(region)
    fun loadTemplate(name: String): PreparedTemplate?
    fun loadMacro(id: String): Macro? = null
    fun loadMacroSet(id: String): MacroSet? = null
    fun onActiveMacroSwitched(target: Macro) {}
    fun onActiveAccountChanged(accountIndex: Int, totalEnabled: Int, account: AccountProfile?) {}
    fun onAccountStateUpdated(macroId: String, lastAccountId: String, completedAccountId: String, lastUsedAtMs: Long) {}
    fun consumeManualSkipAccountRequest(): Boolean = false
    fun isVisualDebugEnabled(): Boolean = false
    fun onVisualDebug(event: VisualDebugEvent) {}
    fun onVariablesChanged(variables: Map<String, Double>) {}
    fun onHumanBreakChanged(remainingSec: Int) {}
    fun onWatchdogTriggered(modeLabel: String) {}
    fun onBannedBlocked(imageName: String) {}
    fun onAccountSwitched(accountLabel: String) {}
    fun onHumanBreakStarted(durationSec: Int) {}
    suspend fun sendNotification(
        title: String,
        message: String,
        level: NotifyLevel,
        sendRemote: Boolean,
        includeScreenshot: Boolean,
        triggerPhoneAlarm: Boolean
    ) {}
    fun onMatchFound(match: MatchResult, label: String) {}
    fun onLoopChanged(loop: Int) {}
    fun onActionExecuted() {}
    /** Resolves live per-action Telegram/Discord remote control state (supports hot toggling while running). */
    fun resolveRemoteActionControl(macro: Macro, actionId: String): ActionRemoteControl? =
        loadMacro(macro.id)?.actionRemoteControls?.get(actionId) ?: macro.actionRemoteControls[actionId]
    /** Called when a [RemoteActionMode.ONE_SHOT] action is triggered so live state & storage immediately reset to OFF. */
    fun onOneShotRemoteActionConsumed(macroId: String, actionId: String) {}
    fun log(level: LogLevel, message: String)
    fun nowMs(): Long = System.currentTimeMillis()
}

private enum class FlowControl {
    NEXT,
    JUMP,
    SWITCH_BRANCH,
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
 * - Multi-point clicks with burst repeat, jitter & Humanizer Engine (Gaussian clicks, Bezier swipes, Human Break)
 * - Long press, swipe, 2-finger pinch Zoom In/Out, wait with random jitter
 * - Exit App & Launch App actions for app lifecycle automation & stuck recovery
 * - Per-action success probability ([Action.chancePercent]) for random events
 * - Automatic screen resolution scaling ([ResolutionScaler]) across all devices
 * - Image (with repeated clicks & banned-image avoidance), OCR Text/Value, Color detection & Live Visual Debugger
 * - Visual Variables & Counters ([VariableAction], [CheckVariableAction], and VARIABLE rules in [BranchRouterAction])
 * - Remote Notifications (Telegram/Discord + Screenshot) & Local Phone Alarm ([NotifyAction])
 * - Named sequence branches ([Macro.branches]), [BranchRouterAction], and [GoToBranchAction] (Switch & Call-Return)
 * - Universal Multi-Account rotation ([Macro.accountSwitch], [SwitchAccountAction], [SelectAccountAction]) via OCR + Auto-Scroll and Clone App switching
 * - Nested loop blocks, [JumpAction] forward/backward jumps, & Chained/Linked Macros ([RunMacroAction]) with cycle protection, Passive Mode & [FinishMacroAction]
 * - Expiration deadline & tamper seal enforcement via [MacroSecurity]
 * - Recorded gesture playback with speed multiplier
 * - Auto-Fix Stuck Macro Watchdog ([Macro.autoRecoverStuckSec], [Macro.stuckActions], [StuckRecoveryMode])
 */
class MacroRunner(
    private val runtime: MacroRuntime,
    private val random: Random = Random.Default
) {
    private val humanizer = HumanizerEngine(random)
    private var lastProgressMs: Long = 0L
    private var rootWatchdogMacro: Macro? = null
    private var inStuckRecovery: Boolean = false
    private var pendingSwitchMacro: Macro? = null
    private var pendingJump: PendingJump? = null
    private var pendingSwitchBranchId: String? = null
    private var branchCallDepth: Int = 0
    /** Live multi-account rotation state for the active root macro. */
    private var currentAccount: AccountProfile? = null
    private var previousAccountPackage: String = ""
    private var accountLoopCounter: Int = 0
    private var accountStartedAtMs: Long = 0L
    private var inAccountSwitchFlow: Boolean = false
    private var lastAccountSelectFailed: Boolean = false
    private val visitedAccountIdsInCycle = LinkedHashSet<String>()
    private val runtimeAccountLastUsed = HashMap<String, Long>()
    /** Visual variables stored per session and per active account. */
    private val sessionVariables = LinkedHashMap<String, Double>()
    private val accountVariables = HashMap<String, LinkedHashMap<String, Double>>()
    /** Scheduled epoch ms for the next Humanizer "Human Break" pause (0 = disabled). */
    private var nextHumanBreakAtMs: Long = 0L
    /** Live counters for notification template expansion (`{loop}`, `{actions}`). */
    private var rootLoopNumber: Int = 0
    private var sessionActionsCounter: Int = 0
    /** Caches discovered winning template scale per (imageName@frameWxH) for instant single-pass re-detection. */
    private val discoveredTemplateScale = HashMap<String, Double>()
    /** Caches discovered macro-wide scale per (macroId@frameWxH) so sibling templates match immediately. */
    private val discoveredMacroScale = HashMap<String, Double>()
    /** Fallback ONE_SHOT tracker when a test runtime does not back [MacroRuntime.loadMacro] with live storage. */
    private val fallbackConsumedOneShots = HashSet<String>()

    suspend fun run(macro: Macro) {
        val now = runtime.nowMs()
        val parentSet = if (macro.setId.isNotBlank()) runtime.loadMacroSet(macro.setId) else null
        MacroSecurity.checkDirectRun(macro, now, parentSet)?.let { denial ->
            runtime.log(LogLevel.ERROR, MacroSecurity.denialMessage(macro, denial, parentSet))
            return
        }
        var currentMacro = macro
        pendingSwitchMacro = null
        pendingJump = null
        pendingSwitchBranchId = null
        branchCallDepth = 0
        while (!runtime.isStopped()) {
            rootWatchdogMacro = currentMacro
            inStuckRecovery = false
            inAccountSwitchFlow = false
            lastAccountSelectFailed = false
            currentAccount = null
            previousAccountPackage = ""
            accountLoopCounter = 0
            accountStartedAtMs = runtime.nowMs()
            rootLoopNumber = 0
            sessionActionsCounter = 0
            visitedAccountIdsInCycle.clear()
            runtimeAccountLastUsed.clear()
            sessionVariables.clear()
            accountVariables.clear()
            fallbackConsumedOneShots.clear()
            runtime.onVariablesChanged(emptyMap())
            runtime.onHumanBreakChanged(0)
            val breakInterval = humanizer.nextBreakIntervalMs(currentMacro.humanizer)
            nextHumanBreakAtMs = if (breakInterval > 0L) runtime.nowMs() + breakInterval else 0L
            pendingJump = null
            pendingSwitchBranchId = null
            branchCallDepth = 0
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
        runtime.onHumanBreakChanged(0)
        runtime.onVariablesChanged(emptyMap())
        runtime.onActiveAccountChanged(0, 0, null)
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

        if (depth == 0 && macro.accountSwitch.enabled) {
            val enabledAccounts = macro.accountSwitch.accounts.filter { it.enabled }
            if (enabledAccounts.isEmpty()) {
                if (macro.accountSwitch.method == AccountSwitchMethod.PRISM_ROOT) {
                    accountLoopCounter = 0
                    accountStartedAtMs = startedAt
                    visitedAccountIdsInCycle.clear()
                    runtime.log(
                        LogLevel.INFO,
                        "🔀 PRISM Root Cloner (Switch Next) aktif — urutan rotasi akun mengikuti daftar di PRISM Cloner."
                    )
                    if (macro.accountSwitch.switchOnStart) {
                        val virtualTarget = AccountProfile(id = PRISM_VIRTUAL_NEXT_ID, label = "PRISM Next")
                        val startSwitchFlow = executeAccountSwitchSequence(macro, virtualTarget, startedAt, maxDurationMs, depth)
                        if (startSwitchFlow == FlowControl.STOP_MACRO) return FlowControl.STOP_MACRO
                    }
                } else {
                    runtime.log(LogLevel.WARN, "👥 Multi-Akun aktif tetapi belum ada profil akun yang aktif. Lanjut tanpa rotasi akun.")
                }
            } else {
                val initial = pickInitialAccount(macro, enabledAccounts, startedAt)
                currentAccount = initial
                accountLoopCounter = 0
                accountStartedAtMs = startedAt
                visitedAccountIdsInCycle.clear()
                if (initial != null) {
                    visitedAccountIdsInCycle += initial.id
                    if (!macro.accountSwitch.switchOnStart && initial.packageName.isNotBlank()) {
                        previousAccountPackage = initial.packageName
                    }
                    notifyActiveAccount(macro, initial)
                    runtime.log(
                        LogLevel.INFO,
                        "👤 Akun aktif awal: '${initial.label}' (${enabledAccounts.indexOfFirst { it.id == initial.id } + 1}/${enabledAccounts.size})."
                    )
                    if (macro.accountSwitch.switchOnStart) {
                        val startSwitchFlow = executeAccountSwitchSequence(macro, initial, startedAt, maxDurationMs, depth)
                        if (startSwitchFlow == FlowControl.STOP_MACRO) return FlowControl.STOP_MACRO
                        if (previousAccountPackage.isBlank() && initial.packageName.isNotBlank()) {
                            previousAccountPackage = initial.packageName
                        }
                    }
                }
            }
        }

        var iteration = 0
        while (!runtime.isStopped() && iteration < maxLoops) {
            val now = runtime.nowMs()
            if (depth == 0) {
                val pSet = if (macro.setId.isNotBlank()) runtime.loadMacroSet(macro.setId) else null
                if (MacroSecurity.isExpired(macro, now) || (pSet != null && MacroSecurity.isSetExpired(pSet, now))) {
                    runtime.log(LogLevel.ERROR, MacroSecurity.denialMessage(macro, com.example.data.RunDenialReason.EXPIRED, pSet))
                    return FlowControl.STOP_MACRO
                }
            } else {
                val liveSub = runtime.loadMacro(macro.id) ?: macro
                val pSet = if (liveSub.setId.isNotBlank()) runtime.loadMacroSet(liveSub.setId) else null
                MacroSecurity.checkLinkedRun(liveSub, now, pSet)?.let { denial ->
                    val level = if (denial == com.example.data.RunDenialReason.PASSIVE_OFF) LogLevel.INFO else LogLevel.WARN
                    runtime.log(level, MacroSecurity.denialMessage(liveSub, denial, pSet))
                    markProgress()
                    return FlowControl.NEXT
                }
            }
            if (now - startedAt >= maxDurationMs) {
                runtime.log(LogLevel.INFO, "Batas waktu ${macro.maxDurationMin} menit tercapai.")
                break
            }
            iteration++
            if (depth == 0) {
                rootLoopNumber = iteration
                runtime.onLoopChanged(iteration)
            }
            runtime.log(LogLevel.INFO, if (depth == 0) "Loop #$iteration dimulai." else "Sub-macro '${macro.name}' loop #$iteration.")

            pendingJump = null
            pendingSwitchBranchId = null
            var activeBranchId = ""
            var branchSwitchCount = 0
            var flow: FlowControl
            while (true) {
                val branchActions = macro.actionsForBranch(activeBranchId)
                flow = executeList(branchActions, macro, startedAt, maxDurationMs, depth, isRootList = true)
                pendingJump = null
                if (flow == FlowControl.SWITCH_BRANCH) {
                    val nextBranchId = pendingSwitchBranchId.orEmpty()
                    pendingSwitchBranchId = null
                    branchSwitchCount++
                    if (branchSwitchCount > MAX_BRANCH_SWITCHES_PER_LOOP) {
                        runtime.log(LogLevel.WARN, "Batas perpindahan jalur cabang ($MAX_BRANCH_SWITCHES_PER_LOOP×) tercapai dalam 1 siklus — dilanjutkan ke siklus berikutnya.")
                        flow = FlowControl.NEXT
                        break
                    }
                    if (nextBranchId.isNotBlank() && macro.branches.none { it.id == nextBranchId }) {
                        runtime.log(LogLevel.WARN, "Jalur cabang tujuan tidak ditemukan — kembali ke Jalur Utama.")
                        activeBranchId = ""
                    } else {
                        activeBranchId = nextBranchId
                    }
                    runtime.log(LogLevel.INFO, "🔀 Beralih arah ke '${macro.branchDisplayName(activeBranchId)}'.")
                    markProgress()
                    continue
                }
                break
            }
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
                    runtime.log(LogLevel.INFO, "Mengulang '${macro.name}' dari awal...")
                    if (macro.loopDelayMs > 0 && !runtime.isStopped()) runtime.sleep(macro.loopDelayMs)
                    continue
                }
                FlowControl.RESTART_MACRO -> {
                    runtime.log(LogLevel.INFO, "Mengulang '${macro.name}' dari awal...")
                    if (macro.loopDelayMs > 0 && !runtime.isStopped()) runtime.sleep(macro.loopDelayMs)
                    continue
                }
                FlowControl.NEXT, FlowControl.JUMP, FlowControl.SWITCH_BRANCH -> {
                    if (depth == 0 && !runtime.isStopped() && macro.accountSwitch.enabled && !inAccountSwitchFlow) {
                        val accCfg = macro.accountSwitch
                        val hasMultipleAccounts = accCfg.accounts.count { it.enabled } > 0 ||
                            accCfg.method == AccountSwitchMethod.PRISM_ROOT
                        val loopTrigger = accCfg.triggerMode == AccountTriggerMode.AFTER_LOOPS ||
                            accCfg.triggerMode == AccountTriggerMode.LOOPS_OR_MINUTES
                        val minuteTrigger = accCfg.triggerMode == AccountTriggerMode.AFTER_MINUTES ||
                            accCfg.triggerMode == AccountTriggerMode.LOOPS_OR_MINUTES
                        accountLoopCounter++
                        val loopsReached = loopTrigger && accountLoopCounter >= accCfg.loopsPerAccount.coerceAtLeast(1)
                        val minutesReached = minuteTrigger && accCfg.minutesPerAccount > 0 &&
                            (runtime.nowMs() - accountStartedAtMs) >= accCfg.minutesPerAccount * 60_000L
                        val canContinueMoreLoops = iteration < maxLoops
                        if (hasMultipleAccounts && (loopsReached || minutesReached) && (canContinueMoreLoops || accCfg.rotationMode == AccountRotationMode.ONCE_ALL_ACCOUNTS)) {
                            val reason = if (loopsReached) "$accountLoopCounter loop selesai" else "${accCfg.minutesPerAccount} menit tercapai"
                            runtime.log(LogLevel.INFO, "👥 Rotasi akun otomatis ($reason) untuk '${currentAccount?.label.orEmpty()}'...")
                            val swFlow = performAccountSwitch(
                                macro = macro,
                                specificAccountId = "",
                                restartAfterSwitch = false,
                                startedAt = startedAt,
                                maxDurationMs = maxDurationMs,
                                depth = depth
                            )
                            if (swFlow == FlowControl.STOP_MACRO) return FlowControl.STOP_MACRO
                        }
                    }
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
        runtime.onWatchdogTriggered(effectiveMode.label)
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
            checkAccountAutoOrManualSwitch(macro, startedAt, maxDurationMs, depth)?.let { return it }
            checkHumanBreak(macro, startedAt, maxDurationMs, depth)?.let { return it }

            val action = actions[idx]
            if (!action.enabled) {
                idx++
                continue
            }

            // Live per-action Telegram / Discord bot remote control (TOGGLE ON/OFF & ONE_SHOT trigger)
            val remoteCtrl = runtime.resolveRemoteActionControl(macro, action.id)
            if (remoteCtrl != null && remoteCtrl.enabled) {
                val oneShotKey = "${macro.id}:${action.id}"
                val isLiveBacked = runtime.loadMacro(macro.id) != null
                if (!remoteCtrl.active ||
                    (remoteCtrl.mode == RemoteActionMode.ONE_SHOT && !isLiveBacked && oneShotKey in fallbackConsumedOneShots)
                ) {
                    runtime.log(
                        LogLevel.INFO,
                        "🤖 Aksi '${remoteCtrl.displayName(action.kind.label)}' sedang OFF (Kontrol Bot) — dilewati."
                    )
                    idx++
                    continue
                }
                if (remoteCtrl.mode == RemoteActionMode.ONE_SHOT) {
                    if (!isLiveBacked) fallbackConsumedOneShots += oneShotKey
                    runtime.onOneShotRemoteActionConsumed(macro.id, action.id)
                    runtime.log(
                        LogLevel.INFO,
                        "🤖 Pemicu 1× '${remoteCtrl.displayName(action.kind.label)}' dijalankan (otomatis kembali OFF)."
                    )
                }
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

            val totalDelay = humanizer.humanizeDelay(action.delayAfterMs, macro.randomDelayMs, macro.humanizer)
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
        return when (action) {
            is ClickAction -> {
                val (sx, sy) = transform.scalePoint(action.x, action.y)
                val times = action.repeat.coerceAtLeast(1)
                for (i in 0 until times) {
                    if (runtime.isStopped()) return FlowControl.STOP_MACRO
                    runtime.waitIfPaused()
                    val label = if (times > 1) "Klik ${i + 1}/$times" else "Klik"
                    if (guardedClick(macro, sx, sy, action.durationMs, label = label)) {
                        runtime.onActionExecuted()
                        markProgress()
                    }
                    if (i + 1 < times && action.repeatIntervalMs > 0) {
                        val intv = humanizer.humanizeDelay(action.repeatIntervalMs, 0L, macro.humanizer)
                        if (intv > 0L) runtime.sleep(intv)
                    }
                }
                FlowControl.NEXT
            }

            is LongPressAction -> {
                val (sx, sy) = transform.scalePoint(action.x, action.y)
                val dur = action.durationMs.coerceAtLeast(250L)
                if (guardedClick(macro, sx, sy, dur, isLongPress = true, label = "Tahan")) {
                    runtime.onActionExecuted()
                    markProgress()
                }
                FlowControl.NEXT
            }

            is SwipeAction -> {
                val (sx1, sy1) = transform.scalePoint(action.x1, action.y1)
                val (sx2, sy2) = transform.scalePoint(action.x2, action.y2)
                if (humanizedSwipe(macro, sx1, sy1, sx2, sy2, action.durationMs, label = "Swipe")) {
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
                val total = humanizer.humanizeDelay(action.durationMs, action.randomMs, macro.humanizer)
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

            is BranchRouterAction -> executeBranchRouterAction(action, macro, startedAt, maxDurationMs, depth)

            is GoToBranchAction -> executeGoToBranchAction(action, macro, startedAt, maxDurationMs, depth)

            is VariableAction -> executeVariableAction(action, macro, startedAt, maxDurationMs, depth)

            is CheckVariableAction -> executeCheckVariableAction(action, macro, startedAt, maxDurationMs, depth)

            is NotifyAction -> {
                val vars = mergedVariablesSnapshot()
                val accName = currentAccount?.label.orEmpty()
                val expandedTitle = FarmingStatsCalculator.expandNotifyTemplate(
                    template = action.title.ifBlank { "Notifikasi Macro" },
                    macroName = macro.name,
                    loop = rootLoopNumber,
                    actionsExecuted = sessionActionsCounter,
                    accountLabel = accName,
                    variables = vars
                )
                val expandedMessage = FarmingStatsCalculator.expandNotifyTemplate(
                    template = action.message,
                    macroName = macro.name,
                    loop = rootLoopNumber,
                    actionsExecuted = sessionActionsCounter,
                    accountLabel = accName,
                    variables = vars
                )
                runtime.log(LogLevel.INFO, "🔔 Notifikasi [${action.level.label}]: $expandedTitle — ${expandedMessage.take(80)}")
                runtime.sendNotification(
                    title = expandedTitle,
                    message = expandedMessage,
                    level = action.level,
                    sendRemote = action.sendRemote,
                    includeScreenshot = action.includeScreenshot,
                    triggerPhoneAlarm = action.triggerPhoneAlarm
                )
                sessionActionsCounter++
                runtime.onActionExecuted()
                markProgress()
                FlowControl.NEXT
            }

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
                val targetSet = if (target.setId.isNotBlank()) runtime.loadMacroSet(target.setId) else null
                MacroSecurity.checkLinkedRun(target, runtime.nowMs(), targetSet)?.let { denial ->
                    val level = if (denial == com.example.data.RunDenialReason.PASSIVE_OFF) LogLevel.INFO else LogLevel.WARN
                    runtime.log(level, MacroSecurity.denialMessage(target, denial, targetSet))
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
                                val targetSet = if (target.setId.isNotBlank()) runtime.loadMacroSet(target.setId) else null
                                val denial = MacroSecurity.checkDirectRun(target, runtime.nowMs(), targetSet)
                                if (denial != null) {
                                    runtime.log(LogLevel.WARN, "Selesaikan Macro: ${MacroSecurity.denialMessage(target, denial, targetSet)} — kembali ke macro aktif pemanggil.")
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
                    val blockedHit = if (activeBannedGuardMacro(macro) != null) {
                        scaledGesture.strokes.firstNotNullOfOrNull { stroke ->
                            if (stroke.points.size >= 2) {
                                findBlockingBannedHit(macro, stroke.points[0], stroke.points[1])?.let { hit ->
                                    Triple(hit, stroke.points[0], stroke.points[1])
                                }
                            } else null
                        }
                    } else null
                    if (blockedHit != null) {
                        val (hit, bx, by) = blockedHit
                        if (runtime.isVisualDebugEnabled()) {
                            runtime.onVisualDebug(
                                VisualDebugEvent.BannedBlocked(
                                    x = bx,
                                    y = by,
                                    imageName = hit.imageName,
                                    left = hit.box.x,
                                    top = hit.box.y,
                                    right = hit.box.x + hit.box.width,
                                    bottom = hit.box.y + hit.box.height
                                )
                            )
                        }
                        runtime.log(LogLevel.WARN, "⛔ Gestur rekaman DIBLOKIR oleh Guard Banned Gambar ('${hit.imageName}')!")
                        runtime.onBannedBlocked(hit.imageName)
                        continue
                    }
                    if (runtime.playRecordedGesture(scaledGesture, speed)) {
                        runtime.onActionExecuted()
                        markProgress()
                    }
                }
                FlowControl.NEXT
            }

            is SwitchAccountAction -> {
                performAccountSwitch(
                    macro = macro,
                    specificAccountId = action.specificAccountId,
                    restartAfterSwitch = action.restartAfterSwitch,
                    startedAt = startedAt,
                    maxDurationMs = maxDurationMs,
                    depth = depth
                )
            }

            is SelectAccountAction -> {
                executeSelectAccountAction(
                    action = action,
                    macro = macro,
                    startedAt = startedAt,
                    maxDurationMs = maxDurationMs,
                    depth = depth
                )
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
            return handleBranch(
                action.elseActions, action.notFound, macro, startedAt, maxDurationMs, depth,
                targetBranchId = action.notFoundBranchId
            )
        }

        // Preload any configured banned/forbidden image templates once
        val bannedTemplates: List<Pair<String, PreparedTemplate>> = action.bannedImages
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .mapNotNull { bName -> runtime.loadTemplate(bName)?.let { bName to it } }

        val deadline = runtime.nowMs() + action.timeoutMs.coerceAtLeast(0L)
        var matched: MatchResult? = null
        var bestScore = 0.0
        var usedScale = 1.0
        var sawFrame = false
        var avoidedByBanCount = 0
        var lastBannedLabel = ""
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

                // Step 1: Detect any banned/forbidden images on this frame (Global Always-Active Guard + Per-Action)
                val globalGuard = activeBannedGuardMacro(macro)
                val globalBannedBoxes = if (globalGuard != null) {
                    detectGlobalBannedBoxes(globalGuard, frame, region = null)
                } else emptyList()

                if (globalGuard != null &&
                    globalGuard.bannedMode == BannedImageMode.BLOCK_IF_PRESENT &&
                    globalBannedBoxes.isNotEmpty()
                ) {
                    avoidedByBanCount++
                    lastBannedLabel = globalBannedBoxes.first().first
                    if (runtime.nowMs() >= deadline) break
                    runtime.sleep(120L)
                    continue
                }

                val bannedBoxes = ArrayList<Pair<String, MatchResult>>()
                if (bannedTemplates.isNotEmpty()) {
                    val banThreshold = action.bannedThreshold.coerceIn(0.45f, 0.99f).toDouble()
                    for ((bName, bTpl) in bannedTemplates) {
                        for ((sx, sy) in fastScales) {
                            val cand = bTpl.scaled(sx, sy)
                            if (cand.width > frame.width || cand.height > frame.height) continue
                            val hits = if (scaledRegion != null &&
                                scaledRegion.width >= cand.width &&
                                scaledRegion.height >= cand.height
                            ) {
                                TemplateMatcher.findAll(
                                    frame, cand, banThreshold, maxResults = 8,
                                    regionLeft = scaledRegion.left, regionTop = scaledRegion.top,
                                    regionRight = scaledRegion.right, regionBottom = scaledRegion.bottom
                                )
                            } else {
                                TemplateMatcher.findAll(frame, cand, banThreshold, maxResults = 8)
                            }
                            if (hits.isNotEmpty()) {
                                hits.forEach { bannedBoxes += bName to it }
                                break
                            }
                        }
                    }
                }

                if (action.bannedMode == BannedImageMode.BLOCK_IF_PRESENT && bannedBoxes.isNotEmpty()) {
                    avoidedByBanCount++
                    lastBannedLabel = bannedBoxes.first().first
                    if (runtime.nowMs() >= deadline) break
                    runtime.sleep(120L)
                    continue
                }

                val avoidRadiusPx = frameTransform.scaleDistance(action.bannedAvoidRadiusPx.coerceAtLeast(0))
                val globalAvoidRadiusPx = if (globalGuard != null) {
                    ResolutionScaler.forMacro(globalGuard, frame.width, frame.height)
                        .scaleDistance(globalGuard.bannedAvoidRadiusPx.coerceAtLeast(0))
                } else 0
                val hasAnyBannedZones = bannedBoxes.isNotEmpty() || globalBannedBoxes.isNotEmpty()

                fun isNearBanned(m: MatchResult): Boolean {
                    for ((bName, b) in globalBannedBoxes) {
                        val overlaps = m.x < b.x + b.width + globalAvoidRadiusPx &&
                            m.x + m.width + globalAvoidRadiusPx > b.x &&
                            m.y < b.y + b.height + globalAvoidRadiusPx &&
                            m.y + m.height + globalAvoidRadiusPx > b.y
                        if (overlaps) {
                            lastBannedLabel = bName
                            return true
                        }
                    }
                    for ((bName, b) in bannedBoxes) {
                        val overlaps = m.x < b.x + b.width + avoidRadiusPx &&
                            m.x + m.width + avoidRadiusPx > b.x &&
                            m.y < b.y + b.height + avoidRadiusPx &&
                            m.y + m.height + avoidRadiusPx > b.y
                        if (overlaps) {
                            lastBannedLabel = bName
                            return true
                        }
                    }
                    return false
                }

                val triedUniformScales = ArrayList<Double>(fastScales.size)
                for ((sx, sy) in fastScales) {
                    val cand = template.scaled(sx, sy)
                    if (cand.width > frame.width || cand.height > frame.height) continue
                    if (abs(sx - sy) < 0.02) triedUniformScales += sx

                    if (hasAnyBannedZones) {
                        val allHits = if (scaledRegion != null &&
                            scaledRegion.width >= cand.width &&
                            scaledRegion.height >= cand.height
                        ) {
                            TemplateMatcher.findAll(
                                frame, cand, action.threshold.toDouble(), maxResults = 16,
                                regionLeft = scaledRegion.left, regionTop = scaledRegion.top,
                                regionRight = scaledRegion.right, regionBottom = scaledRegion.bottom
                            )
                        } else {
                            TemplateMatcher.findAll(frame, cand, action.threshold.toDouble(), maxResults = 16)
                        }
                        allHits.firstOrNull()?.let { if (it.score > bestScore) bestScore = it.score }
                        val safeHit = allHits.firstOrNull { !isNearBanned(it) }
                        if (safeHit != null) {
                            matched = safeHit
                            usedScale = (sx + sy) / 2.0
                            if (macro.autoScaleResolution && abs(sx - sy) < 0.02) {
                                discoveredTemplateScale[tplKey] = sx
                                if (abs(sx - 1.0) >= 0.02) discoveredMacroScale[macroKey] = sx
                            }
                            break
                        } else if (allHits.isNotEmpty()) {
                            avoidedByBanCount++
                        }
                    } else {
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
                }

                // Adaptive multi-scale fallback
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
                            if (!isNearBanned(ms.match)) {
                                matched = ms.match
                                usedScale = ms.bestScale
                                discoveredTemplateScale[tplKey] = ms.bestScale
                                discoveredMacroScale[macroKey] = ms.bestScale
                            } else {
                                avoidedByBanCount++
                            }
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
            if (runtime.isVisualDebugEnabled()) {
                val clickTransform = ResolutionScaler.forMacro(macro, runtime.screenWidth, runtime.screenHeight)
                runtime.onVisualDebug(
                    VisualDebugEvent.ImageCheck(
                        label = action.imageName.substringAfterLast('/').removeSuffix(".png"),
                        found = true,
                        score = matched.score.toFloat(),
                        threshold = action.threshold,
                        left = matched.x,
                        top = matched.y,
                        right = matched.x + matched.width,
                        bottom = matched.y + matched.height,
                        scanRegion = action.region?.let { clickTransform.scaleRegion(it) }
                    )
                )
            }
            markProgress()
            if (action.clickOnFound) {
                val clickTransform = ResolutionScaler.forMacro(macro, runtime.screenWidth, runtime.screenHeight)
                val (ox, oy) = clickTransform.scaleDelta(action.offsetX, action.offsetY)
                val taps = action.clickCount.coerceIn(1, 50)
                val intervalMs = action.clickIntervalMs.coerceIn(0L, 5000L)
                val durationMs = action.clickDurationMs.coerceIn(10L, 2000L)
                if (taps > 1) {
                    runtime.log(LogLevel.ACTION, "Klik berulang gambar '${action.imageName}' ${taps}× (jeda ${intervalMs}ms).")
                }
                for (t in 0 until taps) {
                    if (runtime.isStopped()) return FlowControl.STOP_MACRO
                    runtime.waitIfPaused()
                    if (guardedClick(macro, matched.centerX + ox, matched.centerY + oy, durationMs, label = action.imageName.substringAfterLast('/').removeSuffix(".png"))) {
                        runtime.onActionExecuted()
                        markProgress()
                    }
                    if (t < taps - 1 && intervalMs > 0L) {
                        val intv = humanizer.humanizeDelay(intervalMs, 0L, macro.humanizer)
                        val waitFlow = sleepWatchingStuck(intv, macro, startedAt, maxDurationMs, depth)
                        if (waitFlow != FlowControl.NEXT) return waitFlow
                    }
                }
            }
            return handleBranch(
                action.thenActions, action.foundMode, macro, startedAt, maxDurationMs, depth,
                targetBranchId = action.foundBranchId
            )
        } else {
            val reqPct = (action.threshold * 100).toInt()
            val maxPct = (bestScore * 100).toInt()
            if (runtime.isVisualDebugEnabled() && sawFrame) {
                val dbgTransform = ResolutionScaler.forMacro(macro, runtime.screenWidth, runtime.screenHeight)
                val scanBox = action.region?.let { dbgTransform.scaleRegion(it) }
                    ?: Region(24, 120, (runtime.screenWidth - 24).coerceAtLeast(100), (runtime.screenHeight - 120).coerceAtLeast(100))
                runtime.onVisualDebug(
                    VisualDebugEvent.ImageCheck(
                        label = action.imageName.substringAfterLast('/').removeSuffix(".png"),
                        found = false,
                        score = bestScore.toFloat().coerceIn(0f, 1f),
                        threshold = action.threshold,
                        left = scanBox.left,
                        top = scanBox.top,
                        right = scanBox.right,
                        bottom = scanBox.bottom,
                        scanRegion = action.region?.let { dbgTransform.scaleRegion(it) }
                    )
                )
            }
            if (!sawFrame) {
                runtime.log(LogLevel.WARN, "Deteksi '${action.imageName}' gagal: Tangkap Layar belum aktif / belum ada frame.")
            } else if (avoidedByBanCount > 0) {
                runtime.log(LogLevel.IMAGE, "⛔ Klik '${action.imageName}' dihindari karena gambar terlarang '$lastBannedLabel' terdeteksi.")
            } else {
                runtime.log(LogLevel.IMAGE, "Gambar '${action.imageName}' belum cocok (maks $maxPct% < $reqPct%).")
            }
            return handleBranch(
                action.elseActions, action.notFound, macro, startedAt, maxDurationMs, depth,
                targetBranchId = action.notFoundBranchId
            )
        }
    }

    private suspend fun executeGoToBranchAction(
        action: GoToBranchAction,
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long,
        depth: Int
    ): FlowControl {
        val targetId = action.targetBranchId.trim()
        val targetName = macro.branchDisplayName(targetId)
        if (targetId.isNotBlank() && macro.branches.none { it.id == targetId }) {
            runtime.log(LogLevel.WARN, "Pindah Jalur dilewati: cabang '${action.targetBranchName.ifBlank { targetId }}' tidak ditemukan.")
            return FlowControl.NEXT
        }
        markProgress()
        return when (action.mode) {
            BranchNavigationMode.SWITCH -> {
                pendingSwitchBranchId = targetId
                runtime.log(LogLevel.INFO, "🔀 Pindah jalur ke '$targetName'.")
                FlowControl.SWITCH_BRANCH
            }
            BranchNavigationMode.CALL_AND_RETURN -> {
                executeNamedBranchCall(targetId, macro, startedAt, maxDurationMs, depth)
            }
        }
    }

    private suspend fun executeBranchRouterAction(
        action: BranchRouterAction,
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long,
        depth: Int
    ): FlowControl {
        val deadline = runtime.nowMs() + action.timeoutMs.coerceAtLeast(0L)
        val loadedTemplates = HashMap<String, PreparedTemplate>()
        for (rule in action.rules) {
            if (rule.conditionType == BranchConditionType.IMAGE && rule.imageName.isNotBlank()) {
                if (rule.imageName !in loadedTemplates) {
                    runtime.loadTemplate(rule.imageName)?.let { loadedTemplates[rule.imageName] = it }
                }
            }
        }

        var matchedRule: BranchRouteRule? = null
        var matchedPoint: Pair<Int, Int>? = null
        var matchedDesc = ""

        while (!runtime.isStopped()) {
            runtime.waitIfPaused()
            checkStuckRecovery(macro, startedAt, maxDurationMs, depth)?.let { return it }

            val frame = runtime.captureFrame()
            val liveW = runtime.screenWidth.coerceAtLeast(1)
            val liveH = runtime.screenHeight.coerceAtLeast(1)
            val frameTransform = if (frame != null) {
                ResolutionScaler.forMacro(macro, frame.width, frame.height)
            } else {
                ResolutionScaler.forMacro(macro, liveW, liveH)
            }

            for ((rIdx, rule) in action.rules.withIndex()) {
                val rLabel = rule.label.ifBlank { "Kondisi #${rIdx + 1}" }
                when (rule.conditionType) {
                    BranchConditionType.IMAGE -> {
                        val tpl = loadedTemplates[rule.imageName] ?: continue
                        val f = frame ?: continue
                        val scaledRegion = rule.region?.let { frameTransform.scaleRegion(it) }
                        var hit: MatchResult? = null
                        for ((sx, sy) in frameTransform.templateScaleCandidates()) {
                            val cand = tpl.scaled(sx, sy)
                            if (cand.width > f.width || cand.height > f.height) continue
                            val res = if (scaledRegion != null &&
                                scaledRegion.width >= cand.width &&
                                scaledRegion.height >= cand.height
                            ) {
                                TemplateMatcher.findWithStats(
                                    f, cand, rule.threshold.toDouble(),
                                    scaledRegion.left, scaledRegion.top, scaledRegion.right, scaledRegion.bottom
                                )
                            } else {
                                TemplateMatcher.findWithStats(f, cand, rule.threshold.toDouble())
                            }
                            if (res.match != null) {
                                hit = res.match
                                break
                            }
                        }
                        if (hit == null && macro.autoScaleResolution) {
                            val rL = scaledRegion?.left ?: 0
                            val rT = scaledRegion?.top ?: 0
                            val rR = scaledRegion?.right ?: f.width
                            val rB = scaledRegion?.bottom ?: f.height
                            hit = TemplateMatcher.findMultiScale(f, tpl, rule.threshold.toDouble(), rL, rT, rR, rB).match
                        }
                        if (hit != null) {
                            matchedRule = rule
                            matchedPoint = hit.centerX to hit.centerY
                            matchedDesc = "$rLabel (gambar '${rule.imageName}' ${(hit.score * 100).toInt()}%)"
                            runtime.onMatchFound(hit, rLabel)
                            break
                        }
                    }
                    BranchConditionType.COLOR -> {
                        if (ColorUtil.parseHex(rule.color) == null) continue
                        val (sx, sy) = frameTransform.scalePoint(rule.x, rule.y)
                        val sampled = if (frame != null && sx in 0 until frame.width && sy in 0 until frame.height) {
                            frame.pixels[sy * frame.width + sx]
                        } else {
                            runtime.samplePixelArgb(sx, sy)
                        }
                        if (sampled != null && ColorUtil.matches(sampled, rule.color, rule.tolerance)) {
                            matchedRule = rule
                            matchedPoint = sx to sy
                            matchedDesc = "$rLabel (warna ${rule.color})"
                            break
                        }
                    }
                    BranchConditionType.TEXT -> {
                        val scaledRegion = rule.region?.let { frameTransform.scaleRegion(it) }
                        val blocks = runtime.recognizeText(scaledRegion, enhanced = rule.compareMode.isNumeric)
                        val probeAction = TextAction(
                            query = rule.textQuery,
                            targetNumber = rule.targetNumber,
                            compareMode = rule.compareMode,
                            region = rule.region
                        )
                        val eval = TextConditionEvaluator.evaluate(
                            action = probeAction,
                            blocks = blocks
                        )
                        if (eval.matched) {
                            matchedRule = rule
                            matchedPoint = eval.matchedBlock?.let { it.centerX to it.centerY }
                                ?: scaledRegion?.let { (it.left + it.width / 2) to (it.top + it.height / 2) }
                            matchedDesc = "$rLabel (teks '${eval.matchedText}')"
                            break
                        }
                    }
                    BranchConditionType.VARIABLE -> {
                        val vName = normalizeVarName(rule.variableName)
                        if (vName.isBlank()) continue
                        val actual = readAnyVariable(vName)
                        val rhsName = normalizeVarName(rule.variableCompareToVar)
                        val expected = if (rhsName.isNotBlank()) readAnyVariable(rhsName) else rule.variableTargetValue
                        if (rule.variableOp.evaluate(actual, expected)) {
                            matchedRule = rule
                            matchedPoint = null
                            matchedDesc = "$rLabel (var $vName=${formatPlainNumber(actual)} ${rule.variableOp.symbol} ${formatPlainNumber(expected)})"
                            break
                        }
                    }
                }
            }

            if (matchedRule != null) break
            if (runtime.nowMs() >= deadline) break
            runtime.sleep(120L)
        }

        if (matchedRule != null) {
            runtime.log(LogLevel.INFO, "✅ Cek Arah cocok: $matchedDesc")
            markProgress()
            if (matchedRule.clickOnFound && matchedPoint != null) {
                if (guardedClick(macro, matchedPoint.first, matchedPoint.second, 60L, label = matchedRule.label.ifBlank { "Rute" })) {
                    runtime.onActionExecuted()
                }
            }
            return applyBranchOutcome(
                outcome = matchedRule.outcome,
                targetBranchId = matchedRule.targetBranchId,
                macro = macro,
                startedAt = startedAt,
                maxDurationMs = maxDurationMs,
                depth = depth
            )
        } else {
            val fbName = macro.branchDisplayName(action.fallbackBranchId)
            runtime.log(LogLevel.WARN, "⚠ Cek Arah: hasil tidak sesuai (${action.rules.size} kondisi) → ${action.fallbackOutcome.shortLabel} ($fbName).")
            return applyBranchOutcome(
                outcome = action.fallbackOutcome,
                targetBranchId = action.fallbackBranchId,
                macro = macro,
                startedAt = startedAt,
                maxDurationMs = maxDurationMs,
                depth = depth
            )
        }
    }

    private suspend fun applyBranchOutcome(
        outcome: BranchOutcomeAction,
        targetBranchId: String,
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long,
        depth: Int
    ): FlowControl = when (outcome) {
        BranchOutcomeAction.CONTINUE -> FlowControl.NEXT
        BranchOutcomeAction.SWITCH_BRANCH -> {
            pendingSwitchBranchId = targetBranchId.trim()
            FlowControl.SWITCH_BRANCH
        }
        BranchOutcomeAction.CALL_BRANCH_RETURN ->
            executeNamedBranchCall(targetBranchId.trim(), macro, startedAt, maxDurationMs, depth)
        BranchOutcomeAction.RESTART_MACRO -> FlowControl.RESTART_MACRO
        BranchOutcomeAction.STOP_MACRO -> FlowControl.STOP_MACRO
    }

    private suspend fun executeNamedBranchCall(
        targetBranchId: String,
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long,
        depth: Int
    ): FlowControl {
        if (branchCallDepth >= MAX_BRANCH_CALL_DEPTH) {
            runtime.log(LogLevel.WARN, "Batas kedalaman singgah jalur ($MAX_BRANCH_CALL_DEPTH) tercapai — dilewati.")
            return FlowControl.NEXT
        }
        if (targetBranchId.isNotBlank() && macro.branches.none { it.id == targetBranchId }) {
            runtime.log(LogLevel.WARN, "Singgah jalur dilewati: cabang tujuan tidak ditemukan.")
            return FlowControl.NEXT
        }
        val branchName = macro.branchDisplayName(targetBranchId)
        val branchActions = macro.actionsForBranch(targetBranchId)
        runtime.log(LogLevel.INFO, "↪ Singgah ke jalur '$branchName' (${branchActions.size} aksi)...")
        branchCallDepth++
        val savedJump = pendingJump
        pendingJump = null
        val subFlow = try {
            executeList(branchActions, macro, startedAt, maxDurationMs, depth, isBranch = false, isRootList = true)
        } finally {
            pendingJump = savedJump
            branchCallDepth--
        }
        if (subFlow == FlowControl.NEXT || subFlow == FlowControl.JUMP) {
            runtime.log(LogLevel.INFO, "↩ Selesai singgah '$branchName' — kembali melanjutkan urutan sebelumnya.")
            markProgress()
            return FlowControl.NEXT
        }
        return subFlow
    }

    private data class TrackVariant(
        val template: PreparedTemplate,
        val label: String,
        val cacheKey: String,
        val colorSig: MovingObjectTracker.ColorSignature? = null
    )

    private data class ColorTrackVariant(
        val sig: MovingObjectTracker.ColorSignature,
        val label: String
    )

    private data class TrackHit(
        val match: MatchResult,
        val usedScale: Double,
        val label: String,
        val variantIndex: Int,
        val colorSig: MovingObjectTracker.ColorSignature? = null,
        val colorScore: Double = 0.0,
        val viaHybrid: Boolean = false
    )

    private suspend fun executeTrackObjectAction(
        action: TrackObjectAction,
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long,
        depth: Int
    ): FlowControl {
        val names = action.allTemplateNames()
        val displayTargetName = if (action.trackAnyMotion) "Semua Gerakan" else (names.firstOrNull() ?: "Objek")
        if (!action.trackAnyMotion && names.isEmpty()) {
            runtime.log(LogLevel.WARN, "Kejar Objek dilewati: belum ada tangkapan gambar objek yang dipilih.")
            return FlowControl.NEXT
        }

        val variants = ArrayList<TrackVariant>(names.size * 4)
        val colorVariants = ArrayList<ColorTrackVariant>(names.size)
        if (!action.trackAnyMotion) {
            for (name in names) {
                val raw = runtime.loadTemplate(name) ?: continue
                val shortLabel = name.substringAfterLast('/')
                val colorSig = if (action.useColorMatch && raw.colorFrame != null) {
                    MovingObjectTracker.buildSignature(shortLabel, raw.colorFrame, action.ignoreBackgroundEdges).also {
                        colorVariants += ColorTrackVariant(it, shortLabel)
                    }
                } else null

                if (action.ignoreBackgroundEdges) {
                    val core = raw.centerFocused()
                    variants += TrackVariant(core, shortLabel, "$name#core", colorSig)
                    if (action.matchMirrorX) {
                        variants += TrackVariant(core.mirroredX(), "$shortLabel↔", "$name#core#mirror", colorSig)
                    }
                    if (core !== raw) {
                        variants += TrackVariant(raw, shortLabel, name, colorSig)
                        if (action.matchMirrorX) {
                            variants += TrackVariant(raw.mirroredX(), "$shortLabel↔", "$name#mirror", colorSig)
                        }
                    }
                } else {
                    variants += TrackVariant(raw, shortLabel, name, colorSig)
                    if (action.matchMirrorX) {
                        variants += TrackVariant(raw.mirroredX(), "$shortLabel↔", "$name#mirror", colorSig)
                    }
                }
            }
            if (variants.isEmpty()) {
                runtime.log(LogLevel.WARN, "Gambar objek '$displayTargetName' tidak ditemukan di pustaka.")
                return handleBranch(
                    action.elseActions, action.notFound, macro, startedAt, maxDurationMs, depth,
                    targetBranchId = action.notFoundBranchId
                )
            }
        }

        val threshold = action.threshold.toDouble().coerceIn(0.45, 0.99)
        val pollMs = 45L
        var bestScore = 0.0
        var sawFrame = false
        var lastWinnerIdx = 0
        var lastColorWinnerIdx = 0
        var lastScale = 1.0
        var lastMultiScaleMs = Long.MIN_VALUE / 2
        var multiScaleCount = 0

        fun recordBest(score: Double) {
            if (score > bestScore) bestScore = score
        }

        fun verifyCandidateColorAndMotion(
            frame: Frame,
            match: MatchResult,
            colorSig: MovingObjectTracker.ColorSignature?,
            motion: MovingObjectTracker.MotionField?
        ): Boolean {
            if (action.useColorMatch && colorSig != null && colorSig.hasChromaticSignature) {
                if (!MovingObjectTracker.verifyTemplateMatchColor(frame, match, colorSig)) return false
            }
            if (action.prioritizeMotion && motion != null && !motion.isGlobalCameraMotion && motion.blobs.isNotEmpty()) {
                val mr = motion.motionRatioIn(match.x, match.y, match.x + match.width, match.y + match.height)
                if (mr < 0.02 && match.score < max(threshold + 0.14, 0.85)) return false
            }
            return true
        }

        fun searchVariantsInBounds(
            frame: Frame,
            macroRef: Macro,
            rLeft: Int,
            rTop: Int,
            rRight: Int,
            rBottom: Int,
            allowMultiScale: Boolean,
            minScoreOverride: Double? = null,
            motion: MovingObjectTracker.MotionField? = null
        ): TrackHit? {
            if (rRight <= rLeft || rBottom <= rTop) return null
            val effThreshold = minScoreOverride ?: threshold
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
                        frame, cand, effThreshold, rLeft, rTop, rRight, rBottom
                    )
                    val matched = outcome.match
                    if (matched != null && verifyCandidateColorAndMotion(frame, matched, v.colorSig, motion)) {
                        recordBest(outcome.bestScore)
                        val scale = (sx + sy) / 2.0
                        if (macroRef.autoScaleResolution && abs(sx - sy) < 0.02) {
                            discoveredTemplateScale[tplKey] = sx
                            if (abs(sx - 1.0) >= 0.02) discoveredMacroScale[macroKey] = sx
                        }
                        lastWinnerIdx = idx
                        lastScale = scale
                        val cScore = if (v.colorSig != null) {
                            MovingObjectTracker.scoreColorWindow(frame, v.colorSig, matched.x, matched.y, matched.width, matched.height)
                        } else 0.0
                        return TrackHit(matched, scale, v.label, idx, v.colorSig, cScore, viaHybrid = false)
                    } else if (matched == null) {
                        recordBest(outcome.bestScore)
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
                            threshold = effThreshold,
                            regionLeft = rLeft,
                            regionTop = rTop,
                            regionRight = rRight,
                            regionBottom = rBottom
                        )
                        val matched = ms.match
                        if (matched != null && verifyCandidateColorAndMotion(frame, matched, v.colorSig, motion)) {
                            recordBest(ms.bestScore)
                            val tplKey = "${v.cacheKey}@${frame.width}x${frame.height}"
                            discoveredTemplateScale[tplKey] = ms.bestScale
                            discoveredMacroScale[macroKey] = ms.bestScale
                            lastWinnerIdx = idx
                            lastScale = ms.bestScale
                            val cScore = if (v.colorSig != null) {
                                MovingObjectTracker.scoreColorWindow(frame, v.colorSig, matched.x, matched.y, matched.width, matched.height)
                            } else 0.0
                            return TrackHit(matched, ms.bestScale, v.label, idx, v.colorSig, cScore, viaHybrid = false)
                        } else if (matched == null) {
                            recordBest(ms.bestScore)
                        }
                    }
                }
            }
            return null
        }

        fun searchColorHybridInBounds(
            frame: Frame,
            rLeft: Int,
            rTop: Int,
            rRight: Int,
            rBottom: Int,
            scales: DoubleArray,
            motion: MovingObjectTracker.MotionField?,
            livePatch: MovingObjectTracker.LiveTrackPatch?,
            requireMovingBlob: Boolean
        ): TrackHit? {
            if (colorVariants.isEmpty()) return null
            val order = IntArray(colorVariants.size) { i -> (lastColorWinnerIdx + i) % colorVariants.size }
            var bestTrackHit: TrackHit? = null
            for (idx in order) {
                val cv = colorVariants[idx]
                val hit = MovingObjectTracker.findHybridInBounds(
                    frame = frame,
                    sig = cv.sig,
                    threshold = threshold,
                    rLeft = rLeft,
                    rTop = rTop,
                    rRight = rRight,
                    rBottom = rBottom,
                    scales = scales,
                    motion = motion,
                    livePatch = livePatch,
                    matchMirrorX = action.matchMirrorX,
                    prioritizeMotion = action.prioritizeMotion,
                    requireMovingBlob = requireMovingBlob,
                    onBestScore = ::recordBest
                )
                if (hit != null && (bestTrackHit == null || hit.match.score > bestTrackHit.match.score)) {
                    lastColorWinnerIdx = idx
                    lastScale = hit.usedScale
                    bestTrackHit = TrackHit(
                        match = hit.match,
                        usedScale = hit.usedScale,
                        label = cv.label,
                        variantIndex = idx,
                        colorSig = cv.sig,
                        colorScore = hit.colorScore,
                        viaHybrid = true
                    )
                }
            }
            return bestTrackHit
        }

        fun searchColorInMovingBlobs(
            frame: Frame,
            motion: MovingObjectTracker.MotionField,
            livePatch: MovingObjectTracker.LiveTrackPatch?,
            fullLeft: Int,
            fullTop: Int,
            fullRight: Int,
            fullBottom: Int
        ): TrackHit? {
            if (colorVariants.isEmpty() || motion.isGlobalCameraMotion || motion.blobs.isEmpty()) return null
            val order = IntArray(colorVariants.size) { i -> (lastColorWinnerIdx + i) % colorVariants.size }
            var bestTrackHit: TrackHit? = null
            for (idx in order) {
                val cv = colorVariants[idx]
                val hit = MovingObjectTracker.findInMovingBlobs(
                    frame = frame,
                    sig = cv.sig,
                    threshold = threshold,
                    motion = motion,
                    livePatch = livePatch,
                    matchMirrorX = action.matchMirrorX,
                    prioritizeMotion = action.prioritizeMotion,
                    regionLeft = fullLeft,
                    regionTop = fullTop,
                    regionRight = fullRight,
                    regionBottom = fullBottom,
                    onBestScore = ::recordBest
                )
                if (hit != null && (bestTrackHit == null || hit.match.score > bestTrackHit.match.score)) {
                    lastColorWinnerIdx = idx
                    lastScale = hit.usedScale
                    bestTrackHit = TrackHit(
                        match = hit.match,
                        usedScale = hit.usedScale,
                        label = cv.label,
                        variantIndex = idx,
                        colorSig = cv.sig,
                        colorScore = hit.colorScore,
                        viaHybrid = true
                    )
                }
            }
            return bestTrackHit
        }

        fun locateInFrame(
            frame: Frame,
            prevFrame: Frame?,
            lastPos: Pair<Int, Int>?,
            predictedPos: Pair<Int, Int>?,
            radiusMultiplier: Double,
            livePatch: MovingObjectTracker.LiveTrackPatch?,
            allowMultiScale: Boolean,
            preferWaitForMotion: Boolean
        ): TrackHit? {
            val ft = ResolutionScaler.forMacro(macro, frame.width, frame.height)
            val region = action.region?.let { ft.scaleRegion(it) }
            val fullLeft = (region?.left ?: 0).coerceIn(0, frame.width)
            val fullTop = (region?.top ?: 0).coerceIn(0, frame.height)
            val fullRight = (region?.right ?: frame.width).coerceIn(fullLeft, frame.width)
            val fullBottom = (region?.bottom ?: frame.height).coerceIn(fullTop, frame.height)

            val motion = if (prevFrame != null && (action.trackAnyMotion || action.prioritizeMotion || action.useColorMatch)) {
                MovingObjectTracker.computeMotionField(prevFrame, frame, fullLeft, fullTop, fullRight, fullBottom)
            } else null

            // Color-free Any-Motion mode: lock directly onto the strongest/nearest moving troop or army clump
            if (action.trackAnyMotion) {
                if (motion == null || motion.isGlobalCameraMotion) return null
                val preferred = predictedPos ?: lastPos
                val baseRadius = ft.scaleDistance(action.trackRadiusPx.coerceIn(60, 1200)).coerceAtLeast(60)
                val maxRad = if (preferred != null) {
                    (baseRadius * radiusMultiplier).roundToInt().coerceAtMost(max(frame.width, frame.height))
                } else {
                    Int.MAX_VALUE
                }
                val match = MovingObjectTracker.pickBestMovingBlob(
                    motion = motion,
                    preferredCenter = preferred,
                    maxRadiusPx = maxRad,
                    minCells = 4
                ) ?: return null
                recordBest(match.score)
                return TrackHit(
                    match = match,
                    usedScale = 1.0,
                    label = "Semua Gerakan",
                    variantIndex = 0,
                    colorSig = null,
                    colorScore = 0.0,
                    viaHybrid = true
                )
            }

            // Tier 1: Fast local predictive ROI tracking around last known / predicted position
            if (lastPos != null) {
                val center = predictedPos ?: lastPos
                val baseRadius = ft.scaleDistance(action.trackRadiusPx.coerceIn(60, 1200)).coerceAtLeast(60)
                val radius = (baseRadius * radiusMultiplier).roundToInt().coerceAtMost(max(frame.width, frame.height))
                val l = (center.first - radius).coerceIn(fullLeft, fullRight)
                val t = (center.second - radius).coerceIn(fullTop, fullBottom)
                val r = (center.first + radius).coerceIn(l, fullRight)
                val b = (center.second + radius).coerceIn(t, fullBottom)

                // Exact crisp template match inside local ROI first
                searchVariantsInBounds(frame, macro, l, t, r, b, allowMultiScale = false, motion = motion)?.let { return it }

                // Hybrid HSV-Color + Elliptical RGB-NCC + Adaptive Live Patch + Motion inside local ROI
                if (colorVariants.isNotEmpty()) {
                    val s0 = lastScale.coerceIn(0.55, 1.55)
                    val localScales = doubleArrayOf(s0, (s0 * 0.86).coerceAtLeast(0.55), (s0 * 1.16).coerceAtMost(1.60))
                    searchColorHybridInBounds(
                        frame = frame,
                        rLeft = l, rTop = t, rRight = r, rBottom = b,
                        scales = localScales,
                        motion = motion,
                        livePatch = livePatch,
                        requireMovingBlob = false
                    )?.let { return it }
                }
            }

            // Tier 2: Motion-guided global troop acquisition (checks every moving blob on screen in ~3-6ms, ignores static buildings!)
            if (motion != null && !motion.isGlobalCameraMotion && motion.blobs.isNotEmpty() && colorVariants.isNotEmpty()) {
                searchColorInMovingBlobs(frame, motion, livePatch, fullLeft, fullTop, fullRight, fullBottom)?.let { return it }
            }

            // Tier 3: Standard TemplateMatcher (with color & motion verification)
            val minExactScore = if (preferWaitForMotion && prevFrame == null) max(threshold, 0.80) else null
            searchVariantsInBounds(
                frame, macro, fullLeft, fullTop, fullRight, fullBottom,
                allowMultiScale = allowMultiScale,
                minScoreOverride = minExactScore,
                motion = motion
            )?.let { return it }

            // Tier 4: Full-screen color backprojection fallback (catches momentarily stationary / rotated troops or after camera pan)
            if (colorVariants.isNotEmpty() && (!preferWaitForMotion || prevFrame != null)) {
                val hasChromatic = colorVariants.any { it.sig.hasChromaticSignature }
                if (hasChromatic) {
                    val globalScales = doubleArrayOf(1.0, 0.80, 1.22, 0.66, 1.40)
                    searchColorHybridInBounds(
                        frame = frame,
                        rLeft = fullLeft, rTop = fullTop, rRight = fullRight, rBottom = fullBottom,
                        scales = globalScales,
                        motion = motion,
                        livePatch = livePatch,
                        requireMovingBlob = false
                    )?.let { return it }
                }
            }

            return null
        }

        fun updateLivePatch(frame: Frame, hit: TrackHit, current: MovingObjectTracker.LiveTrackPatch?): MovingObjectTracker.LiveTrackPatch? {
            val sig = hit.colorSig ?: return current
            // Only adapt live patch when foreground color similarity is solid (prevents drifting onto background!)
            if (hit.colorScore >= 0.45 || (!sig.hasChromaticSignature && hit.match.score >= 0.78)) {
                return MovingObjectTracker.captureLivePatch(
                    frame = frame,
                    cx = hit.match.centerX,
                    cy = hit.match.centerY,
                    width = hit.match.width,
                    height = hit.match.height,
                    fgWeight = sig.fgWeight
                ) ?: current
            }
            return current
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
            val px = (curX + dx.roundToInt()).coerceIn(1, (sw - 2).coerceAtLeast(1))
            val py = (curY + dy.roundToInt()).coerceIn(1, (sh - 2).coerceAtLeast(1))
            return px to py
        }

        suspend fun performInteraction(aimX: Int, aimY: Int, sw: Int, sh: Int, hit: TrackHit): Boolean {
            val clickTransform = ResolutionScaler.forMacro(macro, sw, sh)
            val (ox, oy) = clickTransform.scaleDelta(action.offsetX, action.offsetY)
            val gx = (aimX + ox).coerceIn(1, (sw - 2).coerceAtLeast(1))
            val gy = (aimY + oy).coerceIn(1, (sh - 2).coerceAtLeast(1))
            val pct = (hit.match.score * 100).toInt()
            val modeTag = when {
                action.trackAnyMotion -> " [bebas warna]"
                hit.viaHybrid -> " [warna+gerak]"
                else -> ""
            }
            val leadTag = if (aimX != hit.match.centerX || aimY != hit.match.centerY) " (prediksi)" else ""
            val ok = when (action.interactionMode) {
                TrackInteractionMode.TAP_ONCE,
                TrackInteractionMode.TAP_CONTINUOUS -> {
                    val tapDur = action.tapDurationMs.coerceIn(20L, 400L)
                    runtime.log(LogLevel.ACTION, "Kejar '${hit.label}' $pct%$modeTag → Tap ($gx,$gy)$leadTag")
                    guardedClick(macro, gx, gy, tapDur, label = hit.label)
                }
                TrackInteractionMode.DRAG_TO_POINT -> {
                    val (destX, destY) = clickTransform.scalePoint(action.targetX, action.targetY)
                    val cx = destX.coerceIn(1, (sw - 2).coerceAtLeast(1))
                    val cy = destY.coerceIn(1, (sh - 2).coerceAtLeast(1))
                    val dur = action.dragDurationMs.coerceIn(60L, 5000L)
                    runtime.log(LogLevel.ACTION, "Kejar '${hit.label}' $pct%$modeTag → Drag ($gx,$gy)→($cx,$cy) ${dur}ms$leadTag")
                    humanizedSwipe(macro, gx, gy, cx, cy, dur, label = hit.label)
                }
                TrackInteractionMode.DRAG_BY_OFFSET -> {
                    val (ddx, ddy) = clickTransform.scaleDelta(action.dragOffsetX, action.dragOffsetY)
                    val cx = (gx + ddx).coerceIn(1, (sw - 2).coerceAtLeast(1))
                    val cy = (gy + ddy).coerceIn(1, (sh - 2).coerceAtLeast(1))
                    val dur = action.dragDurationMs.coerceIn(60L, 5000L)
                    runtime.log(LogLevel.ACTION, "Kejar '${hit.label}' $pct%$modeTag → Drag ($gx,$gy)→($cx,$cy) ${dur}ms$leadTag")
                    humanizedSwipe(macro, gx, gy, cx, cy, dur, label = hit.label)
                }
                TrackInteractionMode.DRAG_FROM_POINT_TO_OBJECT -> {
                    val (sx, sy) = clickTransform.scalePoint(action.targetX, action.targetY)
                    val cx = sx.coerceIn(1, (sw - 2).coerceAtLeast(1))
                    val cy = sy.coerceIn(1, (sh - 2).coerceAtLeast(1))
                    val dur = action.dragDurationMs.coerceIn(60L, 5000L)
                    runtime.log(LogLevel.ACTION, "Kejar '${hit.label}' $pct%$modeTag → Drag ($cx,$cy)→($gx,$gy) ${dur}ms$leadTag")
                    humanizedSwipe(macro, cx, cy, gx, gy, dur, label = hit.label)
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
            // Phase 1: Initial object acquisition (with frame-to-frame motion diff)
            val deadline = runtime.nowMs() + action.timeoutMs.coerceAtLeast(0L)
            var firstHit: TrackHit? = null
            var firstHitTime = 0L
            var firstFrameW = runtime.screenWidth
            var firstFrameH = runtime.screenHeight
            var prevFrame: Frame? = null
            var livePatch: MovingObjectTracker.LiveTrackPatch? = null
            val canWaitForMotion = action.prioritizeMotion && action.timeoutMs >= pollMs * 2L

            while (!runtime.isStopped()) {
                runtime.waitIfPaused()
                checkStuckRecovery(macro, startedAt, maxDurationMs, depth)?.let { return it }
                val frame = runtime.captureFrame()
                if (frame != null) {
                    sawFrame = true
                    firstFrameW = frame.width
                    firstFrameH = frame.height
                    val hit = locateInFrame(
                        frame = frame,
                        prevFrame = prevFrame,
                        lastPos = null,
                        predictedPos = null,
                        radiusMultiplier = 1.0,
                        livePatch = null,
                        allowMultiScale = true,
                        preferWaitForMotion = canWaitForMotion
                    )
                    prevFrame = frame
                    if (hit != null) {
                        firstHit = hit
                        firstHitTime = runtime.nowMs()
                        livePatch = updateLivePatch(frame, hit, livePatch)
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
                    runtime.log(LogLevel.WARN, "Kejar Objek '$displayTargetName' gagal: Tangkap Layar belum aktif.")
                } else if (action.trackAnyMotion) {
                    runtime.log(LogLevel.IMAGE, "Belum ada pasukan/objek bergerak terdeteksi di area lacak.")
                } else {
                    runtime.log(LogLevel.IMAGE, "Objek '$displayTargetName' tidak ditemukan (maks $maxPct% < $reqPct%).")
                }
                return handleBranch(
                    action.elseActions, action.notFound, macro, startedAt, maxDurationMs, depth,
                    targetBranchId = action.notFoundBranchId
                )
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
                            frame = frame2,
                            prevFrame = prevFrame,
                            lastPos = acquired.match.centerX to acquired.match.centerY,
                            predictedPos = null,
                            radiusMultiplier = 1.15,
                            livePatch = livePatch,
                            allowMultiScale = false,
                            preferWaitForMotion = false
                        )
                        prevFrame = frame2
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
                            livePatch = updateLivePatch(frame2, hit2, livePatch)
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
                // Continuous predictive chase-and-tap loop until the moving troop disappears or maxTrackDurationMs expires
                val trackDeadline = runtime.nowMs() + action.maxTrackDurationMs.coerceIn(300L, 300_000L)
                val repeatInterval = action.repeatIntervalMs.coerceIn(40L, 5000L)
                val lostGraceMs = max(900L, repeatInterval * 4L).coerceAtMost(3000L)
                var prevX = curHit.match.centerX
                var prevY = curHit.match.centerY
                var prevT = curTime
                var lastSeenMs = curTime
                var lastActionMs = Long.MIN_VALUE / 2
                var chaseCount = 0
                var missedFrames = 0

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
                    val dtPred = (now - prevT).coerceIn(0L, 450L)
                    val predPos = if (dtPred > 0L && (abs(vx) > 0.01 || abs(vy) > 0.01)) {
                        predictAim(prevX, prevY, vx, vy, dtPred, maxLeadPx, frame.width, frame.height)
                    } else {
                        prevX to prevY
                    }
                    val radiusMul = (1.0 + missedFrames * 0.45).coerceAtMost(2.8)
                    val hit = locateInFrame(
                        frame = frame,
                        prevFrame = prevFrame,
                        lastPos = prevX to prevY,
                        predictedPos = predPos,
                        radiusMultiplier = radiusMul,
                        livePatch = livePatch,
                        allowMultiScale = missedFrames >= 3,
                        preferWaitForMotion = false
                    )
                    prevFrame = frame
                    if (hit != null) {
                        val dt = now - prevT
                        if (dt in 15L..700L) {
                            val instVx = (hit.match.centerX - prevX).toDouble() / dt.toDouble()
                            val instVy = (hit.match.centerY - prevY).toDouble() / dt.toDouble()
                            vx = vx * 0.35 + instVx * 0.65
                            vy = vy * 0.35 + instVy * 0.65
                        } else if (dt > 700L) {
                            vx = 0.0
                            vy = 0.0
                        }
                        prevX = hit.match.centerX
                        prevY = hit.match.centerY
                        prevT = now
                        lastSeenMs = now
                        missedFrames = 0
                        livePatch = updateLivePatch(frame, hit, livePatch)
                        runtime.onMatchFound(hit.match, "🎯 ${hit.label} ${(hit.match.score * 100).toInt()}%")
                        if (now - lastActionMs >= repeatInterval) {
                            val (aimX, aimY) = predictAim(prevX, prevY, vx, vy, leadMs, maxLeadPx, frame.width, frame.height)
                            if (performInteraction(aimX, aimY, frame.width, frame.height, hit)) {
                                chaseCount++
                                lastActionMs = runtime.nowMs()
                            }
                        }
                    } else {
                        missedFrames++
                        if (now - lastSeenMs >= lostGraceMs) {
                            runtime.log(LogLevel.IMAGE, "Kejar Objek '$displayTargetName' selesai: objek hilang setelah $chaseCount× aksi.")
                            break
                        }
                    }
                }
            }

            return handleBranch(
                action.thenActions, action.foundMode, macro, startedAt, maxDurationMs, depth,
                targetBranchId = action.foundBranchId
            )
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
        var lastArgb: Int? = null
        while (!runtime.isStopped()) {
            runtime.waitIfPaused()
            checkStuckRecovery(macro, startedAt, maxDurationMs, depth)?.let { return it }
            val argb = runtime.samplePixelArgb(sx, sy)
            if (argb != null) lastArgb = argb
            if (argb != null && ColorUtil.matches(argb, action.color, action.tolerance)) {
                matched = true
                break
            }
            if (runtime.nowMs() >= deadline) break
            runtime.sleep(100L)
        }

        if (runtime.isVisualDebugEnabled()) {
            runtime.onVisualDebug(
                VisualDebugEvent.ColorCheck(
                    x = sx,
                    y = sy,
                    matched = matched,
                    actualHex = lastArgb?.let { ColorUtil.toHex(it) } ?: "------",
                    targetHex = action.color
                )
            )
        }

        if (matched) {
            runtime.log(LogLevel.COLOR, "Warna ${action.color} cocok di ($sx,$sy)")
            markProgress()
            if (action.clickOnFound) {
                if (guardedClick(macro, sx, sy, 50L, label = "Warna ${action.color}")) runtime.onActionExecuted()
            }
            return handleBranch(
                action.thenActions, action.foundMode, macro, startedAt, maxDurationMs, depth,
                targetBranchId = action.foundBranchId
            )
        } else {
            runtime.log(LogLevel.COLOR, "Warna ${action.color} tidak cocok.")
            return handleBranch(
                action.elseActions, action.notFound, macro, startedAt, maxDurationMs, depth,
                targetBranchId = action.notFoundBranchId
            )
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
        var lastBlocks: List<OcrBlock> = emptyList()
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
            if (fastBlocks.isNotEmpty()) {
                sawBlocks = true
                lastBlocks = fastBlocks
            }
            val fastOutcome = TextConditionEvaluator.evaluate(action, fastBlocks)
            updateBestOutcome(fastOutcome)
            if (fastOutcome.matched) break

            // If fast RGB pass missed (common on outlined/colored game HUD digits), run high-contrast HUD OCR pass
            val enhancedBlocks = runtime.recognizeText(scaledRegion, enhanced = true)
            if (enhancedBlocks.isNotEmpty()) {
                sawBlocks = true
                val combined = (fastBlocks + enhancedBlocks).distinctBy { "${it.text.lowercase()}|${it.left / 12}|${it.top / 12}" }
                lastBlocks = combined
                val enhOutcome = TextConditionEvaluator.evaluate(action, combined)
                updateBestOutcome(enhOutcome)
                if (enhOutcome.matched) break
            }

            if (runtime.nowMs() >= deadline) break
            runtime.sleep(200L)
        }

        if (runtime.isVisualDebugEnabled()) {
            val preview = when {
                lastOutcome.matched && action.compareMode.isNumeric && lastOutcome.parsedNumber != null ->
                    formatPlainNumber(lastOutcome.parsedNumber!!)
                lastOutcome.matched -> lastOutcome.matchedText.take(28)
                else -> lastOutcome.allText.take(28).ifBlank { "(kosong)" }
            }
            val label = if (action.compareMode.isNumeric) {
                "OCR ${action.compareMode.shortLabel} ${formatPlainNumber(action.targetNumber)}"
            } else {
                "OCR '${action.query.take(16)}'"
            }
            runtime.onVisualDebug(
                VisualDebugEvent.OcrCheck(
                    label = label,
                    matched = lastOutcome.matched,
                    readPreview = preview,
                    region = scaledRegion,
                    blocks = lastBlocks
                )
            )
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
                if (guardedClick(macro, cx, cy, 80L, label = "OCR")) runtime.onActionExecuted()
            }
            return handleBranch(
                action.thenActions, action.foundMode, macro, startedAt, maxDurationMs, depth,
                targetBranchId = action.foundBranchId
            )
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
            return handleBranch(
                action.elseActions, action.notFound, macro, startedAt, maxDurationMs, depth,
                targetBranchId = action.notFoundBranchId
            )
        }
    }

    /** Returns the active macro carrying an enabled always-on Banned Image Guard (local macro or root active macro). */
    private fun activeBannedGuardMacro(macro: Macro): Macro? = when {
        macro.bannedGuardEnabled && macro.bannedImages.any { it.isNotBlank() } -> macro
        rootWatchdogMacro?.let { it.bannedGuardEnabled && it.bannedImages.any { img -> img.isNotBlank() } } == true -> rootWatchdogMacro
        else -> null
    }

    /** Detects all global banned image bounding boxes on [frame] for [guardMacro]. */
    private fun detectGlobalBannedBoxes(
        guardMacro: Macro,
        frame: Frame,
        region: Region? = null
    ): List<Pair<String, MatchResult>> {
        val names = guardMacro.bannedImages.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return emptyList()
        val guardTransform = ResolutionScaler.forMacro(guardMacro, frame.width, frame.height)
        val fastScales = ArrayList<Pair<Double, Double>>(4)
        fun addScale(sx: Double, sy: Double) {
            if (fastScales.none { abs(it.first - sx) < 0.02 && abs(it.second - sy) < 0.02 }) {
                fastScales += sx to sy
            }
        }
        guardTransform.templateScaleCandidates().forEach { (sx, sy) -> addScale(sx, sy) }
        val macroKey = "${guardMacro.id}@${frame.width}x${frame.height}"
        if (guardMacro.autoScaleResolution) {
            discoveredMacroScale[macroKey]?.let { addScale(it, it) }
        }
        val threshold = guardMacro.bannedThreshold.coerceIn(0.45f, 0.99f).toDouble()
        val found = ArrayList<Pair<String, MatchResult>>()
        for (bName in names) {
            val tpl = runtime.loadTemplate(bName) ?: continue
            for ((sx, sy) in fastScales) {
                val cand = tpl.scaled(sx, sy)
                if (cand.width > frame.width || cand.height > frame.height) continue
                val hits = if (region != null && region.width >= cand.width && region.height >= cand.height) {
                    TemplateMatcher.findAll(
                        frame, cand, threshold, maxResults = 8,
                        regionLeft = region.left, regionTop = region.top,
                        regionRight = region.right, regionBottom = region.bottom
                    )
                } else {
                    TemplateMatcher.findAll(frame, cand, threshold, maxResults = 8)
                }
                if (hits.isNotEmpty()) {
                    hits.forEach { found += bName to it }
                    break
                }
            }
        }
        return found
    }

    private data class BannedHit(val imageName: String, val box: MatchResult)

    /**
     * Checks the always-active Banned Image Guard before dispatching any click at ([clickX], [clickY]).
     * Returns the matched banned image & bounding box if the click must be BLOCKED, or null when safe.
     */
    private fun findBlockingBannedHit(macro: Macro, clickX: Int, clickY: Int): BannedHit? {
        val guard = activeBannedGuardMacro(macro) ?: return null
        val frame = runtime.captureFrame() ?: return null
        val boxes = detectGlobalBannedBoxes(guard, frame)
        if (boxes.isEmpty()) return null
        if (guard.bannedMode == BannedImageMode.BLOCK_IF_PRESENT) {
            val first = boxes.first()
            return BannedHit(first.first, first.second)
        }
        val guardTransform = ResolutionScaler.forMacro(guard, frame.width, frame.height)
        val avoidPx = guardTransform.scaleDistance(guard.bannedAvoidRadiusPx.coerceAtLeast(0))
        val sw = runtime.screenWidth.coerceAtLeast(1)
        val sh = runtime.screenHeight.coerceAtLeast(1)
        val fx = if (frame.width != sw) ((clickX.toLong() * frame.width) / sw).toInt() else clickX
        val fy = if (frame.height != sh) ((clickY.toLong() * frame.height) / sh).toInt() else clickY
        for ((bName, b) in boxes) {
            if (fx in (b.x - avoidPx)..(b.x + b.width + avoidPx) &&
                fy in (b.y - avoidPx)..(b.y + b.height + avoidPx)
            ) {
                return BannedHit(bName, b)
            }
        }
        return null
    }

    /**
     * Always-active Humanizer + Banned Guard click wrapper used across all macro actions.
     * Applies Gaussian scatter & natural tap hold duration (when Humanizer is enabled), checks
     * Banned Image Guard, emits Live Visual Debug events, and dispatches the tap.
     */
    private suspend fun guardedClick(
        macro: Macro,
        x: Int,
        y: Int,
        durationMs: Long,
        isLongPress: Boolean = false,
        label: String = ""
    ): Boolean {
        val liveW = runtime.screenWidth.coerceAtLeast(4)
        val liveH = runtime.screenHeight.coerceAtLeast(4)
        val transform = ResolutionScaler.forMacro(macro, liveW, liveH)
        val legacyJitter = transform.scaleDistance(macro.jitterPx)
        val click = humanizer.humanizeClick(
            x = x,
            y = y,
            baseDurationMs = durationMs,
            screenW = liveW,
            screenH = liveH,
            legacyJitterPx = legacyJitter,
            rawConfig = macro.humanizer,
            isLongPress = isLongPress
        )
        val blocked = findBlockingBannedHit(macro, click.x, click.y)
        if (blocked != null) {
            if (runtime.isVisualDebugEnabled()) {
                runtime.onVisualDebug(
                    VisualDebugEvent.BannedBlocked(
                        x = click.x,
                        y = click.y,
                        imageName = blocked.imageName,
                        left = blocked.box.x,
                        top = blocked.box.y,
                        right = blocked.box.x + blocked.box.width,
                        bottom = blocked.box.y + blocked.box.height
                    )
                )
            }
            runtime.log(LogLevel.WARN, "⛔ Klik (${click.x}, ${click.y}) DIBLOKIR oleh Guard Banned Gambar ('${blocked.imageName}')!")
            runtime.onBannedBlocked(blocked.imageName)
            return false
        }
        if (isLongPress) {
            runtime.log(LogLevel.ACTION, "Tahan (${click.x},${click.y}) ${click.durationMs}ms")
        } else if (label.startsWith("Klik")) {
            val hTag = if (click.humanized) " [${click.durationMs}ms]" else ""
            runtime.log(LogLevel.ACTION, "$label di (${click.x},${click.y})$hTag")
        }
        if (runtime.isVisualDebugEnabled()) {
            runtime.onVisualDebug(
                VisualDebugEvent.TapPoint(
                    x = click.x,
                    y = click.y,
                    label = label,
                    humanized = click.humanized
                )
            )
        }
        val ok = runtime.click(click.x, click.y, click.durationMs)
        if (ok) sessionActionsCounter++
        return ok
    }

    /** Humanizer-aware swipe wrapper supporting curved quadratic Bezier arcs and Live Visual Debug telemetry. */
    private suspend fun humanizedSwipe(
        macro: Macro,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long,
        label: String = ""
    ): Boolean {
        val liveW = runtime.screenWidth.coerceAtLeast(4)
        val liveH = runtime.screenHeight.coerceAtLeast(4)
        val sw = humanizer.humanizeSwipe(
            x1 = x1,
            y1 = y1,
            x2 = x2,
            y2 = y2,
            baseDurationMs = durationMs,
            screenW = liveW,
            screenH = liveH,
            rawConfig = macro.humanizer
        )
        if (label == "Swipe") {
            val curveTag = if (sw.curved) " [Bezier]" else ""
            runtime.log(LogLevel.ACTION, "Swipe (${sw.x1},${sw.y1}) → (${sw.x2},${sw.y2}) ${sw.durationMs}ms$curveTag")
        }
        if (runtime.isVisualDebugEnabled()) {
            runtime.onVisualDebug(
                VisualDebugEvent.SwipePath(
                    x1 = sw.x1,
                    y1 = sw.y1,
                    cx = sw.cx,
                    cy = sw.cy,
                    x2 = sw.x2,
                    y2 = sw.y2,
                    curved = sw.curved,
                    label = label
                )
            )
        }
        val ok = if (sw.curved) {
            runtime.swipeCurved(sw.x1, sw.y1, sw.cx, sw.cy, sw.x2, sw.y2, sw.durationMs)
        } else {
            runtime.swipe(sw.x1, sw.y1, sw.x2, sw.y2, sw.durationMs)
        }
        if (ok) sessionActionsCounter++
        return ok
    }

    /** Triggers a natural Humanizer "Human Break" AFK pause when scheduled; updates Watchdog progress so Watchdog never misfires. */
    private suspend fun checkHumanBreak(
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long,
        depth: Int
    ): FlowControl? {
        if (depth != 0 || inStuckRecovery || inAccountSwitchFlow) return null
        if (nextHumanBreakAtMs <= 0L) return null
        val now = runtime.nowMs()
        if (now < nextHumanBreakAtMs) return null

        val breakDurMs = humanizer.nextBreakDurationMs(macro.humanizer)
        if (breakDurMs <= 0L) {
            nextHumanBreakAtMs = 0L
            return null
        }

        val totalSec = ((breakDurMs + 999L) / 1000L).toInt().coerceAtLeast(1)
        runtime.log(LogLevel.INFO, "☕ Human Break dimulai (${totalSec} dtk) — istirahat alami agar terlihat seperti manusia...")
        runtime.onHumanBreakStarted(totalSec)
        var remMs = breakDurMs
        try {
            while (remMs > 0L && !runtime.isStopped()) {
                runtime.waitIfPaused()
                if (runtime.nowMs() - startedAt >= maxDurationMs) {
                    return FlowControl.STOP_MACRO
                }
                val remSec = ((remMs + 999L) / 1000L).toInt().coerceAtLeast(1)
                runtime.onHumanBreakChanged(remSec)
                val slice = minOf(remMs, 1000L)
                runtime.sleep(slice)
                remMs -= slice
                markProgress()
            }
        } finally {
            runtime.onHumanBreakChanged(0)
        }

        if (runtime.isStopped()) return FlowControl.STOP_MACRO
        val nextInterval = humanizer.nextBreakIntervalMs(macro.humanizer)
        nextHumanBreakAtMs = if (nextInterval > 0L) runtime.nowMs() + nextInterval else 0L
        runtime.log(LogLevel.INFO, "☕ Human Break selesai — melanjutkan macro.")
        markProgress()
        return null
    }

    // ---- Visual Variables & Counters Engine (No-Code) ----

    private fun normalizeVarName(raw: String): String = raw.trim().lowercase()

    private fun activeAccountVarKey(): String = currentAccount?.id?.takeIf { it.isNotBlank() } ?: "_default"

    private fun readVariable(name: String, scope: VariableScope): Double {
        val key = normalizeVarName(name)
        if (key.isBlank()) return 0.0
        return when (scope) {
            VariableScope.SESSION -> sessionVariables[key] ?: 0.0
            VariableScope.PER_ACCOUNT -> accountVariables[activeAccountVarKey()]?.get(key) ?: 0.0
        }
    }

    private fun readAnyVariable(name: String): Double {
        val key = normalizeVarName(name)
        if (key.isBlank()) return 0.0
        return accountVariables[activeAccountVarKey()]?.get(key) ?: sessionVariables[key] ?: 0.0
    }

    private fun writeVariable(name: String, scope: VariableScope, value: Double) {
        val key = normalizeVarName(name)
        if (key.isBlank()) return
        val safe = if (value.isFinite()) value else 0.0
        when (scope) {
            VariableScope.SESSION -> sessionVariables[key] = safe
            VariableScope.PER_ACCOUNT -> accountVariables.getOrPut(activeAccountVarKey()) { LinkedHashMap() }[key] = safe
        }
        publishVariablesSnapshot()
    }

    private fun mergedVariablesSnapshot(): Map<String, Double> {
        val merged = LinkedHashMap<String, Double>(sessionVariables.size + 4)
        merged.putAll(sessionVariables)
        accountVariables[activeAccountVarKey()]?.forEach { (k, v) ->
            val displayKey = if (k in merged) "👤$k" else k
            merged[displayKey] = v
        }
        return merged
    }

    private fun publishVariablesSnapshot() {
        runtime.onVariablesChanged(mergedVariablesSnapshot())
    }

    private suspend fun executeVariableAction(
        action: VariableAction,
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long,
        depth: Int
    ): FlowControl {
        if (action.operation == VariableOperation.RESET_ALL) {
            when (action.scope) {
                VariableScope.SESSION -> sessionVariables.clear()
                VariableScope.PER_ACCOUNT -> accountVariables[activeAccountVarKey()]?.clear()
            }
            publishVariablesSnapshot()
            runtime.log(LogLevel.INFO, "🔄 Semua variabel (${action.scope.shortLabel}) direset ke 0.")
            runtime.onActionExecuted()
            markProgress()
            return FlowControl.NEXT
        }

        val key = normalizeVarName(action.variableName)
        if (key.isBlank()) {
            runtime.log(LogLevel.WARN, "Variabel dilewati: nama variabel masih kosong.")
            return FlowControl.NEXT
        }

        when (action.operation) {
            VariableOperation.SET -> {
                writeVariable(key, action.scope, action.value)
                runtime.log(LogLevel.INFO, "🔢 Variabel '$key' = ${formatPlainNumber(action.value)}")
            }
            VariableOperation.ADD -> {
                val next = readVariable(key, action.scope) + action.value
                writeVariable(key, action.scope, next)
                runtime.log(LogLevel.INFO, "🔢 Variabel '$key' + ${formatPlainNumber(action.value)} → ${formatPlainNumber(next)}")
            }
            VariableOperation.SUBTRACT -> {
                val next = readVariable(key, action.scope) - action.value
                writeVariable(key, action.scope, next)
                runtime.log(LogLevel.INFO, "🔢 Variabel '$key' - ${formatPlainNumber(action.value)} → ${formatPlainNumber(next)}")
            }
            VariableOperation.MULTIPLY -> {
                val next = readVariable(key, action.scope) * action.value
                writeVariable(key, action.scope, next)
                runtime.log(LogLevel.INFO, "🔢 Variabel '$key' × ${formatPlainNumber(action.value)} → ${formatPlainNumber(next)}")
            }
            VariableOperation.READ_OCR_NUMBER -> {
                val liveW = runtime.screenWidth.coerceAtLeast(4)
                val liveH = runtime.screenHeight.coerceAtLeast(4)
                val transform = ResolutionScaler.forMacro(macro, liveW, liveH)
                val scaledRegion = action.region?.let { transform.scaleRegion(it) }
                val deadline = runtime.nowMs() + action.ocrTimeoutMs.coerceAtLeast(0L)
                var parsedNumber: Double? = null
                var lastBlocks: List<OcrBlock> = emptyList()

                while (!runtime.isStopped()) {
                    runtime.waitIfPaused()
                    checkStuckRecovery(macro, startedAt, maxDurationMs, depth)?.let { return it }

                    val fastBlocks = runtime.recognizeText(scaledRegion, enhanced = false)
                    if (fastBlocks.isNotEmpty()) lastBlocks = fastBlocks
                    val fastNums = TextConditionEvaluator.mergeSameLineBlocks(fastBlocks)
                        .flatMap { TextConditionEvaluator.extractNumbers(it.text) }
                        .ifEmpty { fastBlocks.flatMap { TextConditionEvaluator.extractNumbers(it.text) } }
                    if (fastNums.isNotEmpty()) {
                        parsedNumber = fastNums.first()
                        break
                    }

                    val enhancedBlocks = runtime.recognizeText(scaledRegion, enhanced = true)
                    if (enhancedBlocks.isNotEmpty()) {
                        val combined = (fastBlocks + enhancedBlocks).distinctBy { "${it.text.lowercase()}|${it.left / 12}|${it.top / 12}" }
                        lastBlocks = combined
                        val enhNums = TextConditionEvaluator.mergeSameLineBlocks(combined)
                            .flatMap { TextConditionEvaluator.extractNumbers(it.text) }
                            .ifEmpty { combined.flatMap { TextConditionEvaluator.extractNumbers(it.text) } }
                        if (enhNums.isNotEmpty()) {
                            parsedNumber = enhNums.first()
                            break
                        }
                    }

                    if (runtime.nowMs() >= deadline) break
                    runtime.sleep(200L)
                }

                if (runtime.isVisualDebugEnabled()) {
                    runtime.onVisualDebug(
                        VisualDebugEvent.OcrCheck(
                            label = "Var:$key",
                            matched = parsedNumber != null,
                            readPreview = parsedNumber?.let { formatPlainNumber(it) } ?: "(kosong)",
                            region = scaledRegion,
                            blocks = lastBlocks
                        )
                    )
                }

                if (parsedNumber != null) {
                    writeVariable(key, action.scope, parsedNumber)
                    runtime.log(LogLevel.INFO, "🔢 Variabel '$key' ← OCR = ${formatPlainNumber(parsedNumber)}")
                } else if (action.useFallbackOnOcrFail) {
                    writeVariable(key, action.scope, action.fallbackValue)
                    runtime.log(LogLevel.WARN, "⚠️ OCR angka untuk '$key' kosong — memakai nilai cadangan ${formatPlainNumber(action.fallbackValue)}.")
                } else {
                    runtime.log(LogLevel.WARN, "⚠️ OCR angka untuk '$key' tidak terbaca — mempertahankan nilai ${formatPlainNumber(readVariable(key, action.scope))}.")
                }
            }
            VariableOperation.RESET_ALL -> Unit
        }

        runtime.onActionExecuted()
        markProgress()
        return FlowControl.NEXT
    }

    private suspend fun executeCheckVariableAction(
        action: CheckVariableAction,
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long,
        depth: Int
    ): FlowControl {
        val key = normalizeVarName(action.variableName)
        if (key.isBlank()) {
            runtime.log(LogLevel.WARN, "Cek Variabel dilewati: nama variabel masih kosong.")
            return FlowControl.NEXT
        }
        val actual = readVariable(key, action.scope)
        val rhsKey = normalizeVarName(action.compareToVariable)
        val expected = if (rhsKey.isNotBlank()) readVariable(rhsKey, action.scope) else action.targetValue
        val rhsText = if (rhsKey.isNotBlank()) "$rhsKey (${formatPlainNumber(expected)})" else formatPlainNumber(expected)
        val matched = action.operator.evaluate(actual, expected)

        if (matched) {
            runtime.log(LogLevel.INFO, "✅ Cek Variabel: $key (${formatPlainNumber(actual)}) ${action.operator.symbol} $rhsText → BENAR")
            markProgress()
            return handleBranch(
                action.thenActions, action.foundMode, macro, startedAt, maxDurationMs, depth,
                targetBranchId = action.foundBranchId
            )
        } else {
            runtime.log(LogLevel.INFO, "ℹ️ Cek Variabel: $key (${formatPlainNumber(actual)}) ${action.operator.symbol} $rhsText → SALAH")
            return handleBranch(
                action.elseActions, action.notFound, macro, startedAt, maxDurationMs, depth,
                targetBranchId = action.notFoundBranchId
            )
        }
    }

    // ---- Universal Multi-Account Switcher Engine (OCR + Auto-Scroll & Clone App / Hybrid) ----

    private fun accountEffectiveLastUsed(acc: AccountProfile): Long =
        max(acc.lastUsedAt, runtimeAccountLastUsed[acc.id] ?: 0L)

    private fun remainingCooldownMs(acc: AccountProfile, nowMs: Long): Long {
        if (acc.cooldownMinutes <= 0) return 0L
        val last = accountEffectiveLastUsed(acc)
        if (last <= 0L) return 0L
        val rem = (last + acc.cooldownMinutes * 60_000L) - nowMs
        return rem.coerceAtLeast(0L)
    }

    private fun notifyActiveAccount(macro: Macro, account: AccountProfile?) {
        val enabled = macro.accountSwitch.accounts.filter { it.enabled }
        if (account == null || enabled.isEmpty()) {
            runtime.onActiveAccountChanged(0, enabled.size, null)
        } else {
            val idx = enabled.indexOfFirst { it.id == account.id }.let { if (it >= 0) it + 1 else 1 }
            runtime.onActiveAccountChanged(idx, enabled.size, account)
        }
        publishVariablesSnapshot()
    }

    private fun pickInitialAccount(
        macro: Macro,
        enabledAccounts: List<AccountProfile>,
        nowMs: Long
    ): AccountProfile? {
        if (enabledAccounts.isEmpty()) return null
        val savedId = macro.accountSwitch.lastAccountId
        if (savedId.isNotBlank()) {
            val saved = enabledAccounts.firstOrNull { it.id == savedId }
            if (saved != null && remainingCooldownMs(saved, nowMs) == 0L) return saved
        }
        return enabledAccounts.firstOrNull { remainingCooldownMs(it, nowMs) == 0L }
            ?: enabledAccounts.minByOrNull { remainingCooldownMs(it, nowMs) }
    }

    private suspend fun checkAccountAutoOrManualSwitch(
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long,
        depth: Int
    ): FlowControl? {
        if (depth != 0 || inAccountSwitchFlow || inStuckRecovery || !macro.accountSwitch.enabled) return null
        val cfg = macro.accountSwitch
        val manualSkip = runtime.consumeManualSkipAccountRequest()
        val minuteTrigger = (cfg.triggerMode == AccountTriggerMode.AFTER_MINUTES ||
            cfg.triggerMode == AccountTriggerMode.LOOPS_OR_MINUTES) &&
            cfg.minutesPerAccount > 0 &&
            (runtime.nowMs() - accountStartedAtMs) >= cfg.minutesPerAccount * 60_000L
        if (!manualSkip && !minuteTrigger) return null
        val reason = if (manualSkip) "tombol Skip Akun di overlay" else "batas ${cfg.minutesPerAccount} menit per akun"
        runtime.log(LogLevel.INFO, "⏭ Pindah akun dipicu ($reason)...")
        return performAccountSwitch(
            macro = macro,
            specificAccountId = "",
            restartAfterSwitch = cfg.restartMainAfterSwitch,
            startedAt = startedAt,
            maxDurationMs = maxDurationMs,
            depth = depth
        )
    }

    private suspend fun performAccountSwitch(
        macro: Macro,
        specificAccountId: String,
        restartAfterSwitch: Boolean,
        startedAt: Long,
        maxDurationMs: Long,
        depth: Int
    ): FlowControl {
        if (inAccountSwitchFlow) return FlowControl.NEXT
        val cfg = macro.accountSwitch
        val enabledAccounts = cfg.accounts.filter { it.enabled }
        if (enabledAccounts.isEmpty()) {
            if (cfg.method == AccountSwitchMethod.PRISM_ROOT) {
                accountLoopCounter = 0
                accountStartedAtMs = runtime.nowMs()
                lastAccountSelectFailed = false
                val virtualTarget = AccountProfile(id = PRISM_VIRTUAL_NEXT_ID, label = "PRISM Next")
                runtime.log(LogLevel.INFO, "🔀 PRISM Root: beralih ke akun berikutnya (SWITCH_NEXT)...")
                val switchFlow = executeAccountSwitchSequence(macro, virtualTarget, startedAt, maxDurationMs, depth)
                if (switchFlow == FlowControl.STOP_MACRO || runtime.isStopped()) return FlowControl.STOP_MACRO
                if (!lastAccountSelectFailed) {
                    runtime.onAccountSwitched("PRISM Next")
                    markProgress()
                    return if (restartAfterSwitch) FlowControl.RESTART_ROOT else FlowControl.NEXT
                }
                return when (cfg.onFail) {
                    AccountFailBehavior.STOP_MACRO -> {
                        runtime.log(LogLevel.ERROR, "⛔ Gagal PRISM Switch Next — macro dihentikan sesuai aturan gagal.")
                        FlowControl.STOP_MACRO
                    }
                    AccountFailBehavior.CONTINUE_CURRENT, AccountFailBehavior.SKIP_TO_NEXT -> {
                        runtime.log(LogLevel.WARN, "⚠️ Gagal PRISM Switch Next — tetap melanjutkan macro.")
                        FlowControl.NEXT
                    }
                }
            }
            runtime.log(LogLevel.WARN, "👥 Ganti Akun dilewati: belum ada profil akun yang aktif di Tab Akun.")
            return FlowControl.NEXT
        }

        // Mark completed timestamp for previous account
        val prev = currentAccount
        val nowStart = runtime.nowMs()
        if (prev != null) {
            runtimeAccountLastUsed[prev.id] = nowStart
        }

        val triedIds = HashSet<String>()
        val maxAttempts = if (specificAccountId.isNotBlank()) 1 else enabledAccounts.size.coerceAtLeast(1)

        for (attempt in 0 until maxAttempts) {
            if (runtime.isStopped()) return FlowControl.STOP_MACRO
            val nowMs = runtime.nowMs()
            val chosen: AccountProfile? = if (specificAccountId.isNotBlank()) {
                enabledAccounts.firstOrNull { it.id == specificAccountId }
            } else {
                val candidates = enabledAccounts.filter { it.id !in triedIds }
                if (candidates.isEmpty()) null
                else when (cfg.rotationMode) {
                    AccountRotationMode.ONCE_ALL_ACCOUNTS -> {
                        val unvisited = candidates.filter { it.id !in visitedAccountIdsInCycle }
                        if (unvisited.isEmpty()) {
                            runtime.log(LogLevel.INFO, "✅ Semua ${enabledAccounts.size} akun telah selesai 1 putaran penuh — macro berhenti.")
                            return FlowControl.STOP_MACRO
                        }
                        val ready = unvisited.filter { remainingCooldownMs(it, nowMs) == 0L }
                        ready.firstOrNull() ?: waitUntilEarliestCooldown(unvisited, startedAt, maxDurationMs)
                    }
                    AccountRotationMode.RANDOM -> {
                        val ready = candidates.filter { remainingCooldownMs(it, nowMs) == 0L }
                        val pool = when {
                            ready.size > 1 -> ready.filter { it.id != currentAccount?.id }
                            ready.isNotEmpty() -> ready
                            else -> listOfNotNull(waitUntilEarliestCooldown(candidates, startedAt, maxDurationMs))
                        }
                        if (pool.isEmpty()) null else pool[random.nextInt(pool.size)]
                    }
                    AccountRotationMode.SEQUENTIAL_LOOP -> {
                        val curIdx = enabledAccounts.indexOfFirst { it.id == currentAccount?.id }
                        val ordered = ArrayList<AccountProfile>(enabledAccounts.size)
                        val startOffset = if (curIdx >= 0) curIdx + 1 else 0
                        for (i in enabledAccounts.indices) {
                            val cand = enabledAccounts[(startOffset + i) % enabledAccounts.size]
                            if (cand.id !in triedIds) ordered += cand
                        }
                        val ready = ordered.firstOrNull { remainingCooldownMs(it, nowMs) == 0L }
                        ready ?: waitUntilEarliestCooldown(ordered, startedAt, maxDurationMs)
                    }
                }
            }

            if (chosen == null) {
                if (runtime.isStopped()) return FlowControl.STOP_MACRO
                runtime.log(LogLevel.WARN, "👥 Tidak ada akun tujuan yang tersedia untuk pindah akun.")
                return FlowControl.NEXT
            }

            triedIds += chosen.id
            currentAccount = chosen
            visitedAccountIdsInCycle += chosen.id
            accountLoopCounter = 0
            accountStartedAtMs = runtime.nowMs()
            notifyActiveAccount(macro, chosen)
            runtime.onAccountStateUpdated(
                macroId = macro.id,
                lastAccountId = chosen.id,
                completedAccountId = prev?.id.orEmpty(),
                lastUsedAtMs = nowStart
            )

            val idxLabel = "${enabledAccounts.indexOfFirst { it.id == chosen.id } + 1}/${enabledAccounts.size}"
            runtime.log(LogLevel.INFO, "👥 Beralih ke Akun $idxLabel: '${chosen.label}' (${cfg.method.shortLabel})...")

            lastAccountSelectFailed = false
            val switchFlow = executeAccountSwitchSequence(macro, chosen, startedAt, maxDurationMs, depth)
            if (switchFlow == FlowControl.STOP_MACRO || runtime.isStopped()) return FlowControl.STOP_MACRO

            if (!lastAccountSelectFailed) {
                runtime.onAccountSwitched(chosen.label)
                markProgress()
                return if (restartAfterSwitch) FlowControl.RESTART_ROOT else FlowControl.NEXT
            }

            // Switch step failed to locate/open this account -> apply configured failure policy
            when (cfg.onFail) {
                AccountFailBehavior.STOP_MACRO -> {
                    runtime.log(LogLevel.ERROR, "⛔ Gagal pindah ke '${chosen.label}' — macro dihentikan sesuai aturan gagal.")
                    return FlowControl.STOP_MACRO
                }
                AccountFailBehavior.CONTINUE_CURRENT -> {
                    runtime.log(LogLevel.WARN, "⚠️ Gagal pindah ke '${chosen.label}' — tetap melanjutkan macro.")
                    return FlowControl.NEXT
                }
                AccountFailBehavior.SKIP_TO_NEXT -> {
                    runtime.log(LogLevel.WARN, "⏭ Gagal pindah ke '${chosen.label}' — melompat ke akun berikutnya...")
                    continue
                }
            }
        }
        return if (restartAfterSwitch) FlowControl.RESTART_ROOT else FlowControl.NEXT
    }

    private suspend fun waitUntilEarliestCooldown(
        candidates: List<AccountProfile>,
        startedAt: Long,
        maxDurationMs: Long
    ): AccountProfile? {
        if (candidates.isEmpty()) return null
        val nowMs = runtime.nowMs()
        val earliest = candidates.minByOrNull { remainingCooldownMs(it, nowMs) } ?: return null
        val waitMs = remainingCooldownMs(earliest, nowMs)
        if (waitMs <= 0L) return earliest
        val waitMin = ((waitMs + 59_999L) / 60_000L).coerceAtLeast(1L)
        runtime.log(LogLevel.INFO, "⏳ Semua akun sedang cooldown — menunggu ~${waitMin} menit untuk '${earliest.label}'...")
        var rem = waitMs
        while (rem > 0L && !runtime.isStopped()) {
            runtime.waitIfPaused()
            if (runtime.nowMs() - startedAt >= maxDurationMs) return null
            if (runtime.consumeManualSkipAccountRequest()) return earliest
            val slice = minOf(rem, 1000L)
            runtime.sleep(slice)
            rem -= slice
            markProgress()
        }
        return if (runtime.isStopped()) null else earliest
    }

    private suspend fun executeAccountSwitchSequence(
        macro: Macro,
        target: AccountProfile,
        startedAt: Long,
        maxDurationMs: Long,
        depth: Int
    ): FlowControl {
        val steps = macro.accountSwitch.switchActions.ifEmpty {
            listOf(SelectAccountAction(methodOverride = macro.accountSwitch.method))
        }
        inAccountSwitchFlow = true
        return try {
            executeList(steps, macro, startedAt, maxDurationMs, depth, isBranch = true)
        } finally {
            inAccountSwitchFlow = false
        }
    }

    private suspend fun executeSelectAccountAction(
        action: SelectAccountAction,
        macro: Macro,
        startedAt: Long,
        maxDurationMs: Long,
        depth: Int
    ): FlowControl {
        val effectiveMethod = action.methodOverride ?: macro.accountSwitch.method
        val target = currentAccount ?: macro.accountSwitch.accounts.firstOrNull { it.enabled }

        // Direct PRISM Root Cloner mode (supports both targeted slot/stub switching and zero-config SWITCH_NEXT)
        if (effectiveMethod == AccountSwitchMethod.PRISM_ROOT) {
            val stubPkg = target?.packageName?.trim().orEmpty()
            val targetGamePkg = target?.prismTargetPackage?.trim().orEmpty()
                .ifBlank { action.prismTargetPackage.trim() }
            val slotOrder = target?.prismSlotOrder?.takeIf { it in 1..99 }
                ?: PrismProtocol.parseSlotNumberFromPackage(stubPkg)
            val profileId = target?.prismProfileId?.trim().orEmpty()
            val useSwitchNext = action.prismUseSwitchNext ||
                target == null ||
                target.id == PRISM_VIRTUAL_NEXT_ID ||
                (stubPkg.isBlank() && slotOrder <= 0 && profileId.isBlank())

            val displayLabel = target?.appLabel?.ifBlank { target.label }.orEmpty().ifBlank { "PRISM Next" }
            val req = PrismSwitchRequest(
                targetPackage = targetGamePkg,
                slotOrder = if (useSwitchNext) 0 else slotOrder,
                profileId = if (useSwitchNext) "" else profileId,
                displayLabel = displayLabel,
                stubPackage = if (useSwitchNext) "" else stubPkg,
                switchNext = useSwitchNext
            )
            if (runtime.isStopped()) return FlowControl.STOP_MACRO
            runtime.log(
                LogLevel.ACTION,
                if (useSwitchNext) {
                    "🔀 PRISM Root: pindah akun berikutnya (SWITCH_NEXT)${if (targetGamePkg.isNotBlank()) " [$targetGamePkg]" else ""}..."
                } else {
                    "🔀 PRISM Root: pindah ke '$displayLabel'${if (slotOrder > 0) " (slot #$slotOrder)" else ""}${if (stubPkg.isNotBlank()) " [$stubPkg]" else ""}..."
                }
            )
            val switched = runtime.switchPrismAccount(req)
            if (!switched) {
                runtime.log(LogLevel.WARN, "⚠️ Gagal berpindah akun lewat PRISM Root untuk '$displayLabel'.")
                lastAccountSelectFailed = true
                return handleBranch(
                    action.elseActions, action.notFound, macro, startedAt, maxDurationMs, depth,
                    targetBranchId = action.notFoundBranchId
                )
            }
            if (stubPkg.isNotBlank()) {
                previousAccountPackage = stubPkg
            } else if (targetGamePkg.isNotBlank()) {
                previousAccountPackage = targetGamePkg
            }
            runtime.onActionExecuted()
            markProgress()
            if (action.launchWaitMs > 0L && !runtime.isStopped()) {
                val wFlow = sleepWatchingStuck(action.launchWaitMs, macro, startedAt, maxDurationMs, depth)
                if (wFlow != FlowControl.NEXT) return wFlow
            }
            return if (action.thenActions.isNotEmpty()) {
                executeList(action.thenActions, macro, startedAt, maxDurationMs, depth, isBranch = true)
            } else {
                FlowControl.NEXT
            }
        }

        if (target == null) {
            runtime.log(LogLevel.WARN, "Pilih Akun dilewati: belum ada profil akun aktif di Tab Akun.")
            lastAccountSelectFailed = true
            return handleBranch(
                action.elseActions, action.notFound, macro, startedAt, maxDurationMs, depth,
                targetBranchId = action.notFoundBranchId
            )
        }

        val liveW = runtime.screenWidth.coerceAtLeast(4)
        val liveH = runtime.screenHeight.coerceAtLeast(4)
        val transform = ResolutionScaler.forMacro(macro, liveW, liveH)

        // Step 1: Clone App / Dual App package switching (for CLONE_APP and HYBRID when package differs)
        val shouldSwitchPackage = when (effectiveMethod) {
            AccountSwitchMethod.CLONE_APP -> true
            AccountSwitchMethod.HYBRID -> target.packageName.isNotBlank() && target.packageName != previousAccountPackage
            AccountSwitchMethod.OCR_LIST -> false
            AccountSwitchMethod.PRISM_ROOT -> false
        }

        if (shouldSwitchPackage) {
            val pkg = target.packageName.trim()
            if (pkg.isBlank()) {
                runtime.log(LogLevel.WARN, "⚠️ Akun '${target.label}' belum memilih aplikasi Clone App.")
                lastAccountSelectFailed = true
                return handleBranch(
                    action.elseActions, action.notFound, macro, startedAt, maxDurationMs, depth,
                    targetBranchId = action.notFoundBranchId
                )
            }
            val isPrismRootTarget = runtime.isPrismRootPackage(pkg)
            if (action.exitPreviousApp) {
                val prevIsPrismOrSameGame = previousAccountPackage.isBlank() ||
                    runtime.isPrismRootPackage(previousAccountPackage)
                if (isPrismRootTarget && prevIsPrismOrSameGame) {
                    // PRISM's Root engine cleanly force-stops the real target game (am force-stop + pidof + sync)
                    // before swapping vaults; running Recents/Settings force-stop on a headless stub package
                    // opens the wrong App Info screen and causes account switches to fail.
                    runtime.log(
                        LogLevel.INFO,
                        "🔀 Paket PRISM Root terdeteksi ('$pkg') — penutupan & pemindahan sesi game ditangani langsung oleh PRISM Root."
                    )
                } else {
                    runtime.log(LogLevel.ACTION, "🚪 Menutup aplikasi sebelumnya sebelum membuka '${target.appLabel.ifBlank { pkg }}'...")
                    runtime.exitApp(
                        mode = action.exitMode,
                        packageName = previousAccountPackage,
                        recentsSwipeDirection = action.recentsSwipeDirection,
                        forceStopBackground = action.exitMode == ExitAppMode.CLOSE_RECENT_AND_HOME
                    )
                    if (action.exitWaitMs > 0L && !runtime.isStopped()) {
                        runtime.sleep(action.exitWaitMs)
                    }
                }
            }
            if (runtime.isStopped()) return FlowControl.STOP_MACRO
            val opened = if (isPrismRootTarget) {
                val slotOrder = target.prismSlotOrder.takeIf { it in 1..99 }
                    ?: PrismProtocol.parseSlotNumberFromPackage(pkg)
                val targetGamePkg = target.prismTargetPackage.trim().ifBlank { action.prismTargetPackage.trim() }
                runtime.log(
                    LogLevel.ACTION,
                    "🔀 Membuka akun PRISM Root '${target.label}': ${target.appLabel.ifBlank { pkg }} (Anti-BAL Broadcast)..."
                )
                runtime.switchPrismAccount(
                    PrismSwitchRequest(
                        targetPackage = targetGamePkg,
                        slotOrder = slotOrder,
                        profileId = target.prismProfileId.trim(),
                        displayLabel = target.appLabel.ifBlank { target.label },
                        stubPackage = pkg,
                        switchNext = false
                    )
                )
            } else {
                runtime.log(LogLevel.ACTION, "🚀 Membuka Clone App akun '${target.label}': ${target.appLabel.ifBlank { pkg }}")
                runtime.launchApp(pkg, freshStart = false)
            }
            if (!opened) {
                runtime.log(LogLevel.WARN, "⚠️ Gagal membuka aplikasi Clone '$pkg' untuk akun '${target.label}'.")
                lastAccountSelectFailed = true
                return handleBranch(
                    action.elseActions, action.notFound, macro, startedAt, maxDurationMs, depth,
                    targetBranchId = action.notFoundBranchId
                )
            }
            previousAccountPackage = pkg
            runtime.onActionExecuted()
            markProgress()
            if (action.launchWaitMs > 0L && !runtime.isStopped()) {
                val wFlow = sleepWatchingStuck(action.launchWaitMs, macro, startedAt, maxDurationMs, depth)
                if (wFlow != FlowControl.NEXT) return wFlow
            }
            if (effectiveMethod == AccountSwitchMethod.CLONE_APP) {
                return if (action.thenActions.isNotEmpty()) {
                    executeList(action.thenActions, macro, startedAt, maxDurationMs, depth, isBranch = true)
                } else {
                    FlowControl.NEXT
                }
            }
        }

        // Step 2: OCR Daftar Akun + Auto-Scroll (+ optional Fallback Image) for OCR_LIST and HYBRID
        val keyword = target.ocrKeyword.trim()
        val fallbackImgName = target.fallbackImageName.trim()
        if (keyword.isBlank() && fallbackImgName.isBlank()) {
            if (effectiveMethod == AccountSwitchMethod.HYBRID && target.packageName.isNotBlank()) {
                return if (action.thenActions.isNotEmpty()) {
                    executeList(action.thenActions, macro, startedAt, maxDurationMs, depth, isBranch = true)
                } else {
                    FlowControl.NEXT
                }
            }
            runtime.log(LogLevel.WARN, "⚠️ Akun '${target.label}' belum mengisi Kata Kunci OCR atau Gambar Fallback.")
            lastAccountSelectFailed = true
            return handleBranch(
                action.elseActions, action.notFound, macro, startedAt, maxDurationMs, depth,
                targetBranchId = action.notFoundBranchId
            )
        }

        val scaledRegion = action.region?.let { transform.scaleRegion(it) }
        val offsetX = transform.scaleDistance(action.clickOffsetX)
        val offsetY = transform.scaleDistance(action.clickOffsetY)
        val fallbackTemplate = if (fallbackImgName.isNotBlank()) runtime.loadTemplate(fallbackImgName) else null

        val boxL = scaledRegion?.left ?: (liveW * 0.12f).toInt()
        val boxT = scaledRegion?.top ?: (liveH * 0.18f).toInt()
        val boxR = scaledRegion?.right ?: (liveW * 0.88f).toInt()
        val boxB = scaledRegion?.bottom ?: (liveH * 0.82f).toInt()
        val boxCx = ((boxL + boxR) / 2).coerceIn(2, liveW - 2)
        val boxCy = ((boxT + boxB) / 2).coerceIn(2, liveH - 2)
        val boxW = (boxR - boxL).coerceAtLeast(40)
        val boxH = (boxB - boxT).coerceAtLeast(40)
        val ratio = action.scrollDistanceRatio.coerceIn(0.15f, 0.85f)
        val maxScrolls = action.maxScrolls.coerceIn(0, 30)

        var reversed = false
        var prevSignature = ""
        var totalPasses = 0
        val maxTotalPasses = 1 + maxScrolls * (if (action.reverseOnEnd) 2 else 1)
        var scrollsUsedInCurrentDir = 0

        runtime.log(
            LogLevel.INFO,
            "🔍 Mencari akun '${target.label}' di layar (OCR: '${keyword.ifBlank { "-" }}', ${action.scrollDirection.shortLabel}, maks ${maxScrolls}× scroll)..."
        )

        while (totalPasses < maxTotalPasses && !runtime.isStopped()) {
            runtime.waitIfPaused()
            if (runtime.nowMs() - startedAt >= maxDurationMs) return FlowControl.STOP_MACRO
            totalPasses++

            // 2a. OCR search across individual blocks & same-line merged blocks
            if (keyword.isNotBlank()) {
                val blocks = runtime.recognizeText(scaledRegion, enhanced = true)
                val matched = findMatchingAccountBlock(blocks, keyword, action.matchMode)
                if (runtime.isVisualDebugEnabled()) {
                    runtime.onVisualDebug(
                        VisualDebugEvent.OcrCheck(
                            label = "Akun: ${target.label.take(16)}",
                            matched = matched != null,
                            readPreview = matched?.text?.take(28) ?: blocks.joinToString(" ") { it.text }.take(28).ifBlank { "(kosong)" },
                            region = scaledRegion,
                            blocks = blocks
                        )
                    )
                }
                if (matched != null) {
                    val tx = (matched.centerX + offsetX).coerceIn(1, liveW - 2)
                    val ty = (matched.centerY + offsetY).coerceIn(1, liveH - 2)
                    runtime.log(LogLevel.INFO, "👤 Akun '${target.label}' ditemukan via OCR ('${matched.text}') → klik ($tx, $ty).")
                    if (guardedClick(macro, tx, ty, 60L, label = "Akun ${target.label}")) {
                        runtime.onActionExecuted()
                        markProgress()
                        return if (action.thenActions.isNotEmpty()) {
                            executeList(action.thenActions, macro, startedAt, maxDurationMs, depth, isBranch = true)
                        } else {
                            FlowControl.NEXT
                        }
                    }
                }
                val sig = blocks.joinToString("|") { it.text.trim().lowercase() }
                if (prevSignature.isNotEmpty() && sig == prevSignature && scrollsUsedInCurrentDir > 0) {
                    if (action.reverseOnEnd && !reversed) {
                        reversed = true
                        scrollsUsedInCurrentDir = 0
                        runtime.log(LogLevel.INFO, "🔄 Ujung daftar tercapai — membalik arah scroll untuk mencari '${target.label}'...")
                    } else {
                        break
                    }
                }
                prevSignature = sig
            }

            // 2b. Optional fallback cropped image search (for stylized game fonts)
            if (fallbackTemplate != null) {
                val frame = runtime.captureFrame()
                if (frame != null) {
                    val m = if (scaledRegion != null) {
                        TemplateMatcher.findAll(
                            frame, fallbackTemplate, action.fallbackThreshold.toDouble(), maxResults = 1,
                            regionLeft = scaledRegion.left, regionTop = scaledRegion.top,
                            regionRight = scaledRegion.right, regionBottom = scaledRegion.bottom
                        ).firstOrNull()
                    } else {
                        TemplateMatcher.findAll(
                            frame, fallbackTemplate, action.fallbackThreshold.toDouble(), maxResults = 1
                        ).firstOrNull()
                    }
                    if (m != null) {
                        val scaleX = liveW.toDouble() / frame.width.coerceAtLeast(1)
                        val scaleY = liveH.toDouble() / frame.height.coerceAtLeast(1)
                        val tx = ((m.centerX * scaleX).roundToInt() + offsetX).coerceIn(1, liveW - 2)
                        val ty = ((m.centerY * scaleY).roundToInt() + offsetY).coerceIn(1, liveH - 2)
                        runtime.log(LogLevel.INFO, "👤 Akun '${target.label}' ditemukan via gambar cadangan (${(m.score * 100).roundToInt()}%) → klik ($tx, $ty).")
                        if (guardedClick(macro, tx, ty, 60L, label = "Akun ${target.label}")) {
                            runtime.onActionExecuted()
                            markProgress()
                            return if (action.thenActions.isNotEmpty()) {
                                executeList(action.thenActions, macro, startedAt, maxDurationMs, depth, isBranch = true)
                            } else {
                                FlowControl.NEXT
                            }
                        }
                    }
                }
            }

            // 2c. Auto-scroll to next slice of the account list
            if (scrollsUsedInCurrentDir >= maxScrolls) {
                if (action.reverseOnEnd && !reversed && maxScrolls > 0) {
                    reversed = true
                    scrollsUsedInCurrentDir = 0
                    prevSignature = ""
                    runtime.log(LogLevel.INFO, "🔄 Batas scroll tercapai — membalik arah scroll untuk mencari '${target.label}'...")
                } else {
                    break
                }
            }
            if (maxScrolls == 0) break

            val halfSpan = when (action.scrollDirection) {
                AccountScrollDirection.VERTICAL -> ((boxH * ratio) / 2f).roundToInt().coerceAtLeast(30)
                AccountScrollDirection.HORIZONTAL -> ((boxW * ratio) / 2f).roundToInt().coerceAtLeast(30)
            }
            val (sx1, sy1, sx2, sy2) = when (action.scrollDirection) {
                AccountScrollDirection.VERTICAL -> {
                    val lowY = (boxCy + halfSpan).coerceIn(4, liveH - 4)
                    val highY = (boxCy - halfSpan).coerceIn(4, liveH - 4)
                    if (!reversed) intArrayOf(boxCx, lowY, boxCx, highY) else intArrayOf(boxCx, highY, boxCx, lowY)
                }
                AccountScrollDirection.HORIZONTAL -> {
                    val rightX = (boxCx + halfSpan).coerceIn(4, liveW - 4)
                    val leftX = (boxCx - halfSpan).coerceIn(4, liveW - 4)
                    if (!reversed) intArrayOf(rightX, boxCy, leftX, boxCy) else intArrayOf(leftX, boxCy, rightX, boxCy)
                }
            }
            scrollsUsedInCurrentDir++
            runtime.log(LogLevel.ACTION, "↕ Auto-Scroll akun #$scrollsUsedInCurrentDir (${if (reversed) "balik" else "maju"}): ($sx1,$sy1) → ($sx2,$sy2)")
            humanizedSwipe(macro, sx1, sy1, sx2, sy2, action.scrollDurationMs.coerceIn(120L, 2000L), label = "Scroll Akun")
            markProgress()
            val settle = action.scrollSettleMs.coerceIn(100L, 5000L)
            if (settle > 0L && !runtime.isStopped()) {
                runtime.sleep(settle)
            }
        }

        runtime.log(LogLevel.WARN, "⚠️ Akun '${target.label}' tidak ditemukan di layar setelah auto-scroll.")
        lastAccountSelectFailed = true
        return handleBranch(
            action.elseActions, action.notFound, macro, startedAt, maxDurationMs, depth,
            targetBranchId = action.notFoundBranchId
        )
    }

    private fun findMatchingAccountBlock(
        blocks: List<OcrBlock>,
        keyword: String,
        mode: AccountOcrMatchMode
    ): OcrBlock? {
        if (blocks.isEmpty() || keyword.isBlank()) return null
        // 1. Direct match on individual OCR blocks
        blocks.firstOrNull { matchesAccountKeyword(it.text, keyword, mode) }?.let { return it }
        // 2. Group blocks on the same horizontal line (in case OCR splits "TH15" and "Farm2")
        val sorted = blocks.sortedWith(compareBy<OcrBlock> { it.top }.thenBy { it.left })
        val lines = ArrayList<MutableList<OcrBlock>>()
        for (b in sorted) {
            val existing = lines.firstOrNull { line ->
                val ref = line.first()
                val refH = (ref.bottom - ref.top).coerceAtLeast(16)
                abs(b.centerY - ref.centerY) <= refH
            }
            if (existing != null) existing += b else lines += mutableListOf(b)
        }
        for (line in lines) {
            if (line.size < 2) continue
            val ordered = line.sortedBy { it.left }
            val mergedText = ordered.joinToString(" ") { it.text.trim() }
            if (matchesAccountKeyword(mergedText, keyword, mode)) {
                return OcrBlock(
                    text = mergedText,
                    left = ordered.minOf { it.left },
                    top = ordered.minOf { it.top },
                    right = ordered.maxOf { it.right },
                    bottom = ordered.maxOf { it.bottom }
                )
            }
        }
        return null
    }

    private fun matchesAccountKeyword(text: String, keyword: String, mode: AccountOcrMatchMode): Boolean {
        val normText = text.trim().lowercase().replace(Regex("\\s+"), " ")
        val normKey = keyword.trim().lowercase().replace(Regex("\\s+"), " ")
        if (normText.isEmpty() || normKey.isEmpty()) return false
        val compactText = normText.replace(" ", "")
        val compactKey = normKey.replace(" ", "")
        return when (mode) {
            AccountOcrMatchMode.EXACT -> normText == normKey || compactText == compactKey
            AccountOcrMatchMode.CONTAINS -> {
                if (normText.contains(normKey) || compactText.contains(compactKey)) return true
                // Light OCR lookalike tolerance (0<->o, 1<->l<->i, 5<->s) for game fonts when keyword >= 4 chars
                if (compactKey.length >= 4) {
                    ocrFuzzyFold(compactText).contains(ocrFuzzyFold(compactKey))
                } else false
            }
        }
    }

    private fun ocrFuzzyFold(s: String): String = buildString(s.length) {
        for (c in s) {
            append(
                when (c) {
                    '0' -> 'o'
                    '1', 'i' -> 'l'
                    '5' -> 's'
                    else -> c
                }
            )
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
        depth: Int,
        targetBranchId: String = ""
    ): FlowControl {
        val childFlow = executeList(branchActions, macro, startedAt, maxDurationMs, depth, isBranch = true)
        if (childFlow != FlowControl.NEXT) return childFlow
        return when (endMode) {
            NotFoundMode.CONTINUE -> FlowControl.NEXT
            NotFoundMode.SWITCH_BRANCH -> {
                pendingSwitchBranchId = targetBranchId.trim()
                FlowControl.SWITCH_BRANCH
            }
            NotFoundMode.CALL_BRANCH_RETURN ->
                executeNamedBranchCall(targetBranchId.trim(), macro, startedAt, maxDurationMs, depth)
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
        private const val MAX_BRANCH_CALL_DEPTH = 16
        private const val MAX_BRANCH_SWITCHES_PER_LOOP = 64
        private const val PRISM_VIRTUAL_NEXT_ID = "__prism_next__"
    }
}
