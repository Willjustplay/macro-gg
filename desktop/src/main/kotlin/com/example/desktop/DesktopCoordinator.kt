package com.example.desktop

import com.example.automation.ExecutionLogBus
import com.example.automation.LogLevel
import com.example.automation.LuaBridge
import com.example.automation.MacroRunner
import com.example.automation.MacroRuntime
import com.example.automation.SandboxedLuaEngine
import com.example.data.ExitAppMode
import com.example.data.LuaScript
import com.example.data.Macro
import com.example.data.MacroSecurity
import com.example.data.MacroSet
import com.example.data.RecentsSwipeDirection
import com.example.data.RecordedGesture
import com.example.data.Region
import com.example.vision.Frame
import com.example.vision.MatchResult
import com.example.vision.OcrBlock
import com.example.vision.PreparedTemplate
import com.example.vision.TemplateMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class DesktopEngineState { IDLE, RUNNING, PAUSED, ERROR }

/**
 * Central PC automation engine connecting [MacroRunner], [SandboxedLuaEngine], [DesktopStore],
 * [DesktopCaptureAndInput], [DesktopOcrEngine], and live performance telemetry.
 */
class DesktopCoordinator(
    val store: DesktopStore,
    val captureAndInput: DesktopCaptureAndInput = DesktopCaptureAndInput()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _engineState = MutableStateFlow(DesktopEngineState.IDLE)
    val engineState: StateFlow<DesktopEngineState> = _engineState.asStateFlow()

    private val _activeMacro = MutableStateFlow(store.macros.value.firstOrNull() ?: Macro(name = "Macro Baru"))
    val activeMacro: StateFlow<Macro> = _activeMacro.asStateFlow()

    private val _currentLoop = MutableStateFlow(0)
    val currentLoop: StateFlow<Int> = _currentLoop.asStateFlow()

    private val _actionsExecuted = MutableStateFlow(0)
    val actionsExecuted: StateFlow<Int> = _actionsExecuted.asStateFlow()

    private val _perfStats = MutableStateFlow(DesktopPerfStats())
    val perfStats: StateFlow<DesktopPerfStats> = _perfStats.asStateFlow()

    private val stopRequested = AtomicBoolean(false)
    private val pausedFlag = AtomicBoolean(false)
    private var activeJob: Job? = null

    /** Optional UI hook to flash a click-through match box on screen when a target/troop is detected. */
    @Volatile
    var onVisualMatchFlash: ((match: MatchResult, label: String, offsetX: Int, offsetY: Int) -> Unit)? = null

    init {
        applySettings(store.settings.value)
    }

    fun applySettings(settings: DesktopSettings) {
        captureAndInput.updateSettings(settings)
        TemplateMatcher.parallelMatchingEnabled = settings.parallelMatching
        TemplateMatcher.maxWorkerThreads = settings.visionThreads.coerceIn(1, 8)
        refreshPerfStats()
    }

    fun selectMacro(macro: Macro) {
        _activeMacro.value = macro
    }

    fun syncActiveMacroFromStore() {
        val id = _activeMacro.value.id
        store.getMacroById(id)?.let { _activeMacro.value = it }
    }

    fun togglePlayPause() {
        when (_engineState.value) {
            DesktopEngineState.RUNNING -> pause()
            DesktopEngineState.PAUSED -> resume()
            DesktopEngineState.IDLE, DesktopEngineState.ERROR -> {
                val m = store.getMacroById(_activeMacro.value.id) ?: _activeMacro.value
                runMacro(m)
            }
        }
    }

    fun pause() {
        if (_engineState.value == DesktopEngineState.RUNNING) {
            pausedFlag.set(true)
            _engineState.value = DesktopEngineState.PAUSED
            ExecutionLogBus.info("Macro PC dijeda (tekan F6 untuk lanjut).")
        }
    }

    fun resume() {
        if (_engineState.value == DesktopEngineState.PAUSED) {
            pausedFlag.set(false)
            _engineState.value = DesktopEngineState.RUNNING
            ExecutionLogBus.info("Macro PC dilanjutkan.")
        }
    }

    fun stop() {
        stopRequested.set(true)
        pausedFlag.set(false)
        captureAndInput.setTrackingBoost(false)
        activeJob?.cancel()
        activeJob = null
        if (_engineState.value != DesktopEngineState.IDLE) {
            _engineState.value = DesktopEngineState.IDLE
            ExecutionLogBus.info("Macro PC dihentikan.")
        }
    }

    fun runMacro(macro: Macro): String? {
        stop()
        val latest = store.getMacroById(macro.id) ?: macro
        val parentSet = store.getMacroSetById(latest.setId)
        val denial = MacroSecurity.checkDirectRun(latest, store.effectiveNowMs(), parentSet)
        if (denial != null) {
            val msg = MacroSecurity.denialMessage(latest, denial, parentSet)
            ExecutionLogBus.error(msg)
            return msg
        }
        if (latest.actions.isEmpty()) {
            val msg = "Macro '${latest.name}' belum memiliki aksi."
            ExecutionLogBus.warn(msg)
            return msg
        }

        selectMacro(latest)
        applySettings(store.settings.value)
        stopRequested.set(false)
        pausedFlag.set(false)
        _currentLoop.value = 0
        _actionsExecuted.value = 0
        _engineState.value = DesktopEngineState.RUNNING

        val counter = AtomicInteger(0)
        activeJob = scope.launch {
            ExecutionLogBus.info(
                "Memulai '${latest.name}' di PC [${store.settings.value.captureMode.label}] " +
                    "(${latest.actions.size} aksi, parallel=${TemplateMatcher.parallelMatchingEnabled}, threads=${TemplateMatcher.maxWorkerThreads})."
            )
            val runtime = object : MacroRuntime {
                override val screenWidth: Int get() = captureAndInput.currentScreenSize().first
                override val screenHeight: Int get() = captureAndInput.currentScreenSize().second
                override fun isStopped(): Boolean = stopRequested.get()
                override suspend fun waitIfPaused() {
                    while (pausedFlag.get() && !stopRequested.get()) {
                        delay(60L)
                    }
                }
                override suspend fun sleep(ms: Long) {
                    var rem = ms
                    while (rem > 0 && !stopRequested.get()) {
                        waitIfPaused()
                        val step = minOf(rem, 40L)
                        delay(step)
                        rem -= step
                    }
                }
                override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean =
                    captureAndInput.click(x, y, durationMs)
                override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
                    captureAndInput.swipe(x1, y1, x2, y2, durationMs)
                override suspend fun zoom(cx: Int, cy: Int, zoomIn: Boolean, distancePx: Int, durationMs: Long): Boolean =
                    captureAndInput.zoom(cx, cy, zoomIn = zoomIn, notches = (distancePx / 65).coerceIn(2, 10))
                override suspend fun exitApp(
                    mode: ExitAppMode,
                    packageName: String,
                    recentsSwipeDirection: RecentsSwipeDirection,
                    forceStopBackground: Boolean
                ): Boolean = captureAndInput.exitApp(mode, packageName, forceStopBackground)
                override suspend fun launchApp(packageName: String, freshStart: Boolean): Boolean =
                    captureAndInput.launchApp(packageName)
                override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float): Boolean =
                    captureAndInput.playRecordedGesture(gesture, speed)
                override fun setTrackingBoost(enabled: Boolean) {
                    captureAndInput.setTrackingBoost(enabled)
                }
                override fun captureFrame(): Frame? {
                    val f = captureAndInput.captureFrame(forceFresh = false)
                    refreshPerfStats()
                    return f
                }
                override fun samplePixelArgb(x: Int, y: Int): Int? = captureAndInput.samplePixelArgb(x, y)
                override suspend fun recognizeText(region: Region?): List<OcrBlock> = recognizeText(region, enhanced = true)
                override suspend fun recognizeText(region: Region?, enhanced: Boolean): List<OcrBlock> {
                    val f = captureAndInput.captureFrame(forceFresh = true) ?: return emptyList()
                    return DesktopOcrEngine.recognizeText(f, region, enhanced)
                }
                override fun loadTemplate(name: String): PreparedTemplate? = store.loadTemplate(name)
                override fun loadMacro(id: String): Macro? = store.getMacroById(id)
                override fun loadMacroSet(id: String): MacroSet? = store.getMacroSetById(id)
                override fun onActiveMacroSwitched(target: Macro) {
                    selectMacro(target)
                    _currentLoop.value = 0
                }
                override fun onMatchFound(match: MatchResult, label: String) {
                    if (store.settings.value.showMatchHighlight &&
                        store.settings.value.captureMode != DesktopCaptureMode.ADB_BACKGROUND
                    ) {
                        val b = captureAndInput.resolveTargetBounds()
                        onVisualMatchFlash?.invoke(match, label, b.x, b.y)
                    }
                }
                override fun onLoopChanged(loop: Int) { _currentLoop.value = loop }
                override fun onActionExecuted() { _actionsExecuted.value = counter.incrementAndGet() }
                override fun log(level: LogLevel, message: String) = ExecutionLogBus.log(level, message)
                override fun nowMs(): Long = store.effectiveNowMs()
            }

            try {
                delay(120L)
                MacroRunner(runtime).run(latest)
            } catch (t: Throwable) {
                if (!stopRequested.get()) {
                    ExecutionLogBus.error("Error eksekusi PC: ${t.message}")
                    _engineState.value = DesktopEngineState.ERROR
                    return@launch
                }
            } finally {
                captureAndInput.setTrackingBoost(false)
                if (_engineState.value != DesktopEngineState.ERROR) {
                    _engineState.value = DesktopEngineState.IDLE
                }
                refreshPerfStats()
            }
        }
        return null
    }

    fun runLuaScript(script: LuaScript) {
        stop()
        applySettings(store.settings.value)
        stopRequested.set(false)
        pausedFlag.set(false)
        _actionsExecuted.value = 0
        _engineState.value = DesktopEngineState.RUNNING

        val counter = AtomicInteger(0)
        activeJob = scope.launch {
            ExecutionLogBus.info("Menjalankan Lua Script '${script.name}' di PC...")
            val bridge = object : LuaBridge {
                override val screenWidth: Int get() = captureAndInput.currentScreenSize().first
                override val screenHeight: Int get() = captureAndInput.currentScreenSize().second
                override fun isStopped(): Boolean = stopRequested.get()
                override fun isPaused(): Boolean = pausedFlag.get()
                override fun sleepCooperative(ms: Long) {
                    var rem = ms
                    while (rem > 0 && !stopRequested.get()) {
                        while (pausedFlag.get() && !stopRequested.get()) Thread.sleep(50L)
                        val step = minOf(rem, 40L)
                        Thread.sleep(step)
                        rem -= step
                    }
                }
                override fun click(x: Int, y: Int, durationMs: Int): Boolean {
                    val ok = captureAndInput.click(x, y, durationMs.toLong())
                    if (ok) _actionsExecuted.value = counter.incrementAndGet()
                    return ok
                }
                override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
                    val ok = captureAndInput.swipe(x1, y1, x2, y2, durationMs.toLong())
                    if (ok) _actionsExecuted.value = counter.incrementAndGet()
                    return ok
                }
                override fun zoom(cx: Int, cy: Int, zoomIn: Boolean, distancePx: Int, durationMs: Int): Boolean =
                    captureAndInput.zoom(cx, cy, zoomIn = zoomIn, notches = (distancePx / 65).coerceIn(2, 10))
                override fun getColorArgb(x: Int, y: Int): Int? = captureAndInput.samplePixelArgb(x, y)
                override fun findImage(name: String, threshold: Double, region: Region?): MatchResult? {
                    val tpl = store.loadTemplate(name) ?: return null
                    val frame = captureAndInput.captureFrame(forceFresh = false) ?: return null
                    return TemplateMatcher.find(
                        frame = frame,
                        template = tpl,
                        threshold = threshold,
                        regionLeft = region?.left ?: 0,
                        regionTop = region?.top ?: 0,
                        regionRight = region?.right ?: frame.width,
                        regionBottom = region?.bottom ?: frame.height
                    )
                }
                override fun findAllImages(name: String, threshold: Double, maxResults: Int, region: Region?): List<MatchResult> {
                    val tpl = store.loadTemplate(name) ?: return emptyList()
                    val frame = captureAndInput.captureFrame(forceFresh = false) ?: return emptyList()
                    return TemplateMatcher.findAll(
                        frame = frame,
                        template = tpl,
                        threshold = threshold,
                        maxResults = maxResults,
                        regionLeft = region?.left ?: 0,
                        regionTop = region?.top ?: 0,
                        regionRight = region?.right ?: frame.width,
                        regionBottom = region?.bottom ?: frame.height
                    )
                }
                override fun log(message: String) = ExecutionLogBus.lua(message)
            }

            try {
                val res = SandboxedLuaEngine.execute(script.code, bridge)
                res.exceptionOrNull()?.let { err ->
                    if (!stopRequested.get()) {
                        ExecutionLogBus.error("Lua Error: ${err.message}")
                        _engineState.value = DesktopEngineState.ERROR
                        return@launch
                    }
                }
            } catch (t: Throwable) {
                if (!stopRequested.get()) {
                    ExecutionLogBus.error("Lua Error: ${t.message}")
                    _engineState.value = DesktopEngineState.ERROR
                    return@launch
                }
            } finally {
                if (_engineState.value != DesktopEngineState.ERROR) {
                    _engineState.value = DesktopEngineState.IDLE
                }
                refreshPerfStats()
            }
        }
    }

    fun shutdown() {
        stop()
        captureAndInput.close()
        DesktopOcrEngine.shutdown()
    }

    fun refreshPerfStats() {
        val rt = Runtime.getRuntime()
        val ramMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
        val (sw, sh) = captureAndInput.currentScreenSize()
        val capMs = captureAndInput.lastCaptureMs
        val fps = if (capMs > 0.1) minOf(store.settings.value.targetFps.toDouble(), 1000.0 / capMs) else 0.0
        _perfStats.value = DesktopPerfStats(
            captureMs = capMs,
            effectiveFps = fps,
            ramUsedMb = ramMb,
            captureEngine = captureAndInput.lastEngineLabel,
            screenWidth = sw,
            screenHeight = sh,
            targetLabel = captureAndInput.lastTargetLabel
        )
    }
}
