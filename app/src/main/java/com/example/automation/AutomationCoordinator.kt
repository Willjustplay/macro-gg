package com.example.automation

import com.example.data.LuaScript
import com.example.data.MacroStep
import com.example.data.MacroStepType
import com.example.data.RunTargetType
import com.example.data.ScriptRunStatus
import com.example.data.VisualMacro
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

enum class EngineState(val label: String) {
    IDLE("Siap"),
    RUNNING("Berjalan"),
    PAUSED("Dijeda"),
    ERROR("Error")
}

enum class AutomationMode {
    MACRO, LUA
}

data class PickedPixel(
    val x: Int,
    val y: Int,
    val hexColor: String
)

data class RunSummary(
    val targetType: RunTargetType,
    val targetId: String,
    val targetName: String,
    val status: ScriptRunStatus,
    val durationMs: Long,
    val actionsExecuted: Int,
    val summaryMessage: String
)

object AutomationCoordinator {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null
    private val stopRequested = AtomicBoolean(false)
    private val pausedFlag = AtomicBoolean(false)

    private val _engineState = MutableStateFlow(EngineState.IDLE)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _activeMode = MutableStateFlow(AutomationMode.MACRO)
    val activeMode: StateFlow<AutomationMode> = _activeMode.asStateFlow()

    private val _activeTargetName = MutableStateFlow("Belum ada target")
    val activeTargetName: StateFlow<String> = _activeTargetName.asStateFlow()

    private val _isOverlayActive = MutableStateFlow(false)
    val isOverlayActive: StateFlow<Boolean> = _isOverlayActive.asStateFlow()

    private val _activeMacro = MutableStateFlow(
        VisualMacro(
            id = "",
            name = "Macro Baru",
            description = "",
            loopCount = 1,
            loopDelayMs = 800,
            stopOnError = true
        )
    )
    val activeMacro: StateFlow<VisualMacro> = _activeMacro.asStateFlow()

    private val _activeMacroSteps = MutableStateFlow<List<MacroStep>>(emptyList())
    val activeMacroSteps: StateFlow<List<MacroStep>> = _activeMacroSteps.asStateFlow()

    private val _activeLuaScript = MutableStateFlow<LuaScript?>(null)
    val activeLuaScript: StateFlow<LuaScript?> = _activeLuaScript.asStateFlow()

    private val _lastPickedPixel = MutableStateFlow<PickedPixel?>(null)
    val lastPickedPixel: StateFlow<PickedPixel?> = _lastPickedPixel.asStateFlow()

    private var defaultTapDurationMs: Int = 60
    private var jitterRadiusPx: Int = 4
    private var onRunFinishedCallback: ((RunSummary) -> Unit)? = null

    fun setRunFinishedListener(listener: ((RunSummary) -> Unit)?) {
        onRunFinishedCallback = listener
    }

    fun updateUserTouchPreferences(tapDurationMs: Int, jitterPx: Int) {
        defaultTapDurationMs = tapDurationMs.coerceIn(10, 5000)
        jitterRadiusPx = jitterPx.coerceIn(0, 100)
    }

    fun setOverlayActive(active: Boolean) {
        _isOverlayActive.value = active
    }

    fun publishPickedPixel(x: Int, y: Int, hex: String) {
        _lastPickedPixel.value = PickedPixel(x, y, hex)
        ExecutionLogBus.color("Color Spy: ($x, $y) -> $hex")
    }

    fun selectMacroForRun(macro: VisualMacro, steps: List<MacroStep> = macro.parsedSteps()) {
        _activeMode.value = AutomationMode.MACRO
        _activeMacro.value = macro
        _activeMacroSteps.value = steps
        _activeTargetName.value = macro.name.ifBlank { "Macro Visual" }
    }

    fun syncDraftSteps(steps: List<MacroStep>) {
        _activeMacroSteps.value = steps
    }

    fun selectLuaScriptForRun(script: LuaScript) {
        _activeMode.value = AutomationMode.LUA
        _activeLuaScript.value = script
        _activeTargetName.value = script.title.ifBlank { "Script Lua" }
    }

    fun addQuickClickMarker(x: Int, y: Int) {
        _activeMode.value = AutomationMode.MACRO
        _activeMacroSteps.update { current ->
            if (current.size >= 50) current
            else current + MacroStep(
                type = MacroStepType.CLICK,
                label = "Klik #${current.size + 1}",
                x = x,
                y = y,
                durationMs = defaultTapDurationMs,
                delayAfterMs = 500
            )
        }
        ExecutionLogBus.info("Titik klik #${_activeMacroSteps.value.size} ditambahkan di ($x, $y).")
    }

    fun updateMarkerPosition(stepId: String, newX: Int, newY: Int, isEndNode: Boolean = false) {
        _activeMacroSteps.update { list ->
            list.map { step ->
                if (step.stepId == stepId) {
                    if (isEndNode) step.copy(endX = newX, endY = newY)
                    else step.copy(x = newX, y = newY)
                } else step
            }
        }
    }

    fun togglePlayPause(screenWidth: Int, screenHeight: Int) {
        when (_engineState.value) {
            EngineState.RUNNING -> pause()
            EngineState.PAUSED -> resume()
            EngineState.IDLE, EngineState.ERROR -> {
                if (_activeMode.value == AutomationMode.LUA) {
                    val script = _activeLuaScript.value
                    if (script == null || script.code.isBlank()) {
                        ExecutionLogBus.warn("Pilih atau tulis script Lua terlebih dahulu di Lua Studio.")
                        return
                    }
                    runLuaScript(script, screenWidth, screenHeight)
                } else {
                    val macro = _activeMacro.value
                    val steps = _activeMacroSteps.value
                    if (steps.isEmpty()) {
                        ExecutionLogBus.warn("Tambahkan minimal satu titik klik/aksi sebelum menjalankan macro.")
                        return
                    }
                    runVisualMacro(macro, steps)
                }
            }
        }
    }

    fun runVisualMacro(macro: VisualMacro, steps: List<MacroStep>) {
        stop()
        if (!TapLuaAccessibilityService.isConnected.value) {
            _engineState.value = EngineState.ERROR
            ExecutionLogBus.error("Accessibility Service belum aktif! Aktifkan di Pengaturan Aksesibilitas.")
            return
        }
        val usesColor = steps.any { it.type == MacroStepType.WAIT_COLOR }
        if (usesColor && !ScreenColorSamplerService.isCapturing.value) {
            ExecutionLogBus.warn("Deteksi warna layar belum aktif — langkah WAIT_COLOR mungkin timeout.")
        }

        selectMacroForRun(macro, steps)
        stopRequested.set(false)
        pausedFlag.set(false)
        _engineState.value = EngineState.RUNNING

        val startedAt = System.currentTimeMillis()
        val actionsCount = AtomicInteger(0)

        activeJob = engineScope.launch {
            ExecutionLogBus.info("Memulai Visual Macro '${macro.name}' (${steps.size} langkah, loop=${if (macro.loopCount == 0) "∞" else macro.loopCount}).")
            var finalStatus = ScriptRunStatus.SUCCESS
            var finalMsg = "Macro selesai dengan lancar."
            try {
                var iteration = 0
                val maxLoops = if (macro.loopCount <= 0) Int.MAX_VALUE else macro.loopCount
                while (isActive && !stopRequested.get() && iteration < maxLoops) {
                    iteration++
                    ExecutionLogBus.info("Loop #$iteration dimulai.")
                    for ((index, step) in steps.withIndex()) {
                        waitIfPaused()
                        if (stopRequested.get()) break

                        val success = executeMacroStep(index + 1, step, actionsCount)
                        if (!success && macro.stopOnError) {
                            finalStatus = ScriptRunStatus.ERROR
                            finalMsg = "Langkah #${index + 1} (${step.label}) gagal atau timeout."
                            stopRequested.set(true)
                            break
                        }
                        if (step.delayAfterMs > 0 && !stopRequested.get()) {
                            cooperativeDelay(step.delayAfterMs.toLong())
                        }
                    }
                    if (!stopRequested.get() && iteration < maxLoops && macro.loopDelayMs > 0) {
                        cooperativeDelay(macro.loopDelayMs.toLong())
                    }
                }
                if (stopRequested.get() && finalStatus == ScriptRunStatus.SUCCESS) {
                    finalStatus = ScriptRunStatus.STOPPED
                    finalMsg = "Dihentikan oleh pengguna."
                }
            } catch (_: CancellationException) {
                finalStatus = ScriptRunStatus.STOPPED
                finalMsg = "Dihentikan oleh pengguna."
            } catch (e: Exception) {
                finalStatus = ScriptRunStatus.ERROR
                finalMsg = e.message ?: "Error tidak terduga saat menjalankan macro."
                ExecutionLogBus.error(finalMsg)
            } finally {
                val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                _engineState.value = if (finalStatus == ScriptRunStatus.ERROR) EngineState.ERROR else EngineState.IDLE
                pausedFlag.set(false)
                ExecutionLogBus.info("Selesai: $finalMsg (${actionsCount.get()} aksi, ${elapsed}ms).")
                onRunFinishedCallback?.invoke(
                    RunSummary(
                        targetType = RunTargetType.MACRO,
                        targetId = macro.id,
                        targetName = macro.name.ifBlank { "Macro Visual" },
                        status = finalStatus,
                        durationMs = elapsed,
                        actionsExecuted = actionsCount.get(),
                        summaryMessage = finalMsg
                    )
                )
            }
        }
    }

    private suspend fun executeMacroStep(
        number: Int,
        step: MacroStep,
        actionsCount: AtomicInteger
    ): Boolean {
        val jx = if (jitterRadiusPx > 0) Random.nextInt(-jitterRadiusPx, jitterRadiusPx + 1) else 0
        val jy = if (jitterRadiusPx > 0) Random.nextInt(-jitterRadiusPx, jitterRadiusPx + 1) else 0
        return when (step.type) {
            MacroStepType.CLICK, MacroStepType.LONG_PRESS -> {
                val tx = (step.x + jx).coerceAtLeast(0).toFloat()
                val ty = (step.y + jy).coerceAtLeast(0).toFloat()
                val dur = if (step.type == MacroStepType.LONG_PRESS) step.durationMs.coerceAtLeast(300) else step.durationMs
                ExecutionLogBus.action("#$number ${step.type.label} (${tx.toInt()}, ${ty.toInt()}) ${dur}ms")
                val ok = TapLuaAccessibilityService.dispatchClick(tx, ty, dur.toLong())
                if (ok) actionsCount.incrementAndGet()
                ok
            }
            MacroStepType.SWIPE -> {
                ExecutionLogBus.action("#$number Swipe (${step.x},${step.y}) -> (${step.endX},${step.endY}) ${step.durationMs}ms")
                val ok = TapLuaAccessibilityService.dispatchSwipe(
                    step.x.toFloat(),
                    step.y.toFloat(),
                    step.endX.toFloat(),
                    step.endY.toFloat(),
                    step.durationMs.toLong()
                )
                if (ok) actionsCount.incrementAndGet()
                ok
            }
            MacroStepType.WAIT -> {
                ExecutionLogBus.info("#$number Jeda ${step.durationMs}ms")
                cooperativeDelay(step.durationMs.toLong())
                true
            }
            MacroStepType.WAIT_COLOR -> {
                ExecutionLogBus.color("#$number Tunggu warna ${step.targetColorHex} di (${step.x},${step.y}) tol=${step.colorTolerance}")
                val deadline = System.currentTimeMillis() + step.timeoutMs.toLong()
                while (System.currentTimeMillis() <= deadline && !stopRequested.get()) {
                    waitIfPaused()
                    if (ScreenColorSamplerService.matchesColor(step.x, step.y, step.targetColorHex, step.colorTolerance)) {
                        actionsCount.incrementAndGet()
                        ExecutionLogBus.color("#$number Warna ${step.targetColorHex} cocok!")
                        return true
                    }
                    cooperativeDelay(100L)
                }
                ExecutionLogBus.warn("#$number Timeout menunggu warna ${step.targetColorHex}.")
                false
            }
        }
    }

    fun runLuaScript(script: LuaScript, screenWidth: Int, screenHeight: Int) {
        stop()
        if (!TapLuaAccessibilityService.isConnected.value) {
            _engineState.value = EngineState.ERROR
            ExecutionLogBus.error("Accessibility Service belum aktif! Aktifkan terlebih dahulu.")
            return
        }

        selectLuaScriptForRun(script)
        stopRequested.set(false)
        pausedFlag.set(false)
        _engineState.value = EngineState.RUNNING

        val startedAt = System.currentTimeMillis()
        val actionsCount = AtomicInteger(0)

        activeJob = engineScope.launch {
            ExecutionLogBus.lua("Menjalankan script Lua '${script.title}'...")
            val bridge = object : LuaBridge {
                override val screenWidth: Int = screenWidth
                override val screenHeight: Int = screenHeight
                override fun isStopped(): Boolean = stopRequested.get() || !isActive
                override fun isPaused(): Boolean = pausedFlag.get()

                override fun click(x: Int, y: Int, durationMs: Int): Boolean {
                    val jx = if (jitterRadiusPx > 0) Random.nextInt(-jitterRadiusPx, jitterRadiusPx + 1) else 0
                    val jy = if (jitterRadiusPx > 0) Random.nextInt(-jitterRadiusPx, jitterRadiusPx + 1) else 0
                    val tx = (x + jx).coerceAtLeast(0).toFloat()
                    val ty = (y + jy).coerceAtLeast(0).toFloat()
                    ExecutionLogBus.action("Lua click(${tx.toInt()}, ${ty.toInt()}, ${durationMs}ms)")
                    val ok = runBlocking { TapLuaAccessibilityService.dispatchClick(tx, ty, durationMs.toLong()) }
                    if (ok) actionsCount.incrementAndGet()
                    return ok
                }

                override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
                    ExecutionLogBus.action("Lua swipe($x1,$y1 -> $x2,$y2, ${durationMs}ms)")
                    val ok = runBlocking {
                        TapLuaAccessibilityService.dispatchSwipe(
                            x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs.toLong()
                        )
                    }
                    if (ok) actionsCount.incrementAndGet()
                    return ok
                }

                override fun sleepCooperative(ms: Long) {
                    var remaining = ms
                    while (remaining > 0L) {
                        while (pausedFlag.get() && !stopRequested.get()) {
                            Thread.sleep(30L)
                        }
                        if (stopRequested.get() || !isActive) {
                            throw LuaStopSignal(graceful = false)
                        }
                        val chunk = minOf(remaining, 50L)
                        Thread.sleep(chunk)
                        remaining -= chunk
                    }
                }

                override fun getColorArgb(x: Int, y: Int): Int? {
                    val argb = ScreenColorSamplerService.samplePixelArgb(x, y)
                    if (argb == null) {
                        ExecutionLogBus.warn("getColor($x,$y): Color Sampler belum aktif.")
                    } else {
                        actionsCount.incrementAndGet()
                    }
                    return argb
                }

                override fun log(message: String) {
                    ExecutionLogBus.lua(message)
                }
            }

            val result = SandboxedLuaEngine.execute(script.code, bridge)
            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)

            val (status, msg) = when {
                result.isSuccess -> ScriptRunStatus.SUCCESS to "Script Lua selesai."
                result.exceptionOrNull() is LuaStopSignal || stopRequested.get() ->
                    ScriptRunStatus.STOPPED to "Script Lua dihentikan pengguna."
                else -> {
                    val errText = result.exceptionOrNull()?.message ?: "Runtime error Lua."
                    ExecutionLogBus.error("Lua Error: $errText")
                    ScriptRunStatus.ERROR to errText
                }
            }

            _engineState.value = if (status == ScriptRunStatus.ERROR) EngineState.ERROR else EngineState.IDLE
            pausedFlag.set(false)
            ExecutionLogBus.info("Lua selesai ($status): ${actionsCount.get()} aksi dalam ${elapsed}ms.")

            onRunFinishedCallback?.invoke(
                RunSummary(
                    targetType = RunTargetType.LUA,
                    targetId = script.id,
                    targetName = script.title.ifBlank { "Script Lua" },
                    status = status,
                    durationMs = elapsed,
                    actionsExecuted = actionsCount.get(),
                    summaryMessage = msg
                )
            )
        }
    }

    fun pause() {
        if (_engineState.value == EngineState.RUNNING) {
            pausedFlag.set(true)
            _engineState.value = EngineState.PAUSED
            ExecutionLogBus.info("Otomasi dijeda.")
        }
    }

    fun resume() {
        if (_engineState.value == EngineState.PAUSED) {
            pausedFlag.set(false)
            _engineState.value = EngineState.RUNNING
            ExecutionLogBus.info("Otomasi dilanjutkan.")
        }
    }

    fun stop() {
        stopRequested.set(true)
        pausedFlag.set(false)
        activeJob?.cancel()
        activeJob = null
        if (_engineState.value != EngineState.IDLE) {
            _engineState.value = EngineState.IDLE
            ExecutionLogBus.info("Otomasi dihentikan.")
        }
    }

    private suspend fun waitIfPaused() {
        while (pausedFlag.get() && !stopRequested.get()) {
            delay(40L)
        }
    }

    private suspend fun cooperativeDelay(totalMs: Long) {
        var remaining = totalMs
        while (remaining > 0L && !stopRequested.get()) {
            waitIfPaused()
            val chunk = minOf(remaining, 50L)
            delay(chunk)
            remaining -= chunk
        }
    }
}
