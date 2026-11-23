package com.example.automation

import android.content.Context
import com.example.data.Action
import com.example.data.ClickAction
import com.example.data.ColorAction
import com.example.data.ContainerRef
import com.example.data.ImageAction
import com.example.data.LocalStore
import com.example.data.LongPressAction
import com.example.data.LuaScript
import com.example.data.Macro
import com.example.data.RecordAction
import com.example.data.RecordedGesture
import com.example.data.Region
import com.example.data.RunMacroAction
import com.example.data.SwipeAction
import com.example.data.TextAction
import com.example.data.ZoomAction
import com.example.data.insertInto
import com.example.data.moveAction
import com.example.data.removeAction
import com.example.data.updateAction
import com.example.data.walk
import com.example.vision.Frame
import com.example.vision.MatchResult
import com.example.vision.OcrBlock
import com.example.vision.PreparedTemplate
import com.example.vision.ScreenTextRecognizer
import com.example.vision.TemplateMatcher
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

data class MatchHighlight(
    val match: MatchResult,
    val label: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Central state machine coordinating the active Macro / Lua script,
 * Android system services, Floating Overlay markers, and local persistence.
 */
object AutomationCoordinator {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null
    private val stopRequested = AtomicBoolean(false)
    private val pausedFlag = AtomicBoolean(false)

    @Volatile
    private var store: LocalStore? = null

    private val _engineState = MutableStateFlow(EngineState.IDLE)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _activeMode = MutableStateFlow(AutomationMode.MACRO)
    val activeMode: StateFlow<AutomationMode> = _activeMode.asStateFlow()

    private val _activeMacro = MutableStateFlow(Macro(name = "Auto Clicker Cepat"))
    val activeMacro: StateFlow<Macro> = _activeMacro.asStateFlow()

    private val _activeLuaScript = MutableStateFlow<LuaScript?>(null)
    val activeLuaScript: StateFlow<LuaScript?> = _activeLuaScript.asStateFlow()

    private val _isOverlayActive = MutableStateFlow(false)
    val isOverlayActive: StateFlow<Boolean> = _isOverlayActive.asStateFlow()

    private val _lastPickedPixel = MutableStateFlow<PickedPixel?>(null)
    val lastPickedPixel: StateFlow<PickedPixel?> = _lastPickedPixel.asStateFlow()

    private val _matchHighlight = MutableStateFlow<MatchHighlight?>(null)
    val matchHighlight: StateFlow<MatchHighlight?> = _matchHighlight.asStateFlow()

    private val _currentLoop = MutableStateFlow(0)
    val currentLoop: StateFlow<Int> = _currentLoop.asStateFlow()

    private val _actionsExecuted = MutableStateFlow(0)
    val actionsExecuted: StateFlow<Int> = _actionsExecuted.asStateFlow()

    fun attachStore(context: Context) {
        if (store == null) {
            store = LocalStore.get(context)
        }
    }

    fun getStore(): LocalStore? = store

    fun setOverlayActive(active: Boolean) {
        _isOverlayActive.value = active
    }

    fun activeTargetName(): String = when (_activeMode.value) {
        AutomationMode.MACRO -> _activeMacro.value.name.ifBlank { "Macro" }
        AutomationMode.LUA -> _activeLuaScript.value?.name?.ifBlank { "Lua" } ?: "Lua"
    }

    fun selectMacro(macro: Macro) {
        _activeMode.value = AutomationMode.MACRO
        _activeMacro.value = macro
    }

    fun selectLuaScript(script: LuaScript) {
        _activeMode.value = AutomationMode.LUA
        _activeLuaScript.value = script
    }

    fun updateActiveMacro(persist: Boolean = true, transform: (Macro) -> Macro) {
        val next = transform(_activeMacro.value)
        _activeMacro.value = next
        if (persist) {
            store?.saveMacro(next)
        }
    }

    fun addQuickClickMarker(x: Int, y: Int): ClickAction {
        val settings = store?.settings?.value
        val tapMs = settings?.defaultTapDurationMs ?: 50L
        val created = ClickAction(x = x, y = y, durationMs = tapMs, delayAfterMs = 300L)
        _activeMode.value = AutomationMode.MACRO
        updateActiveMacro(persist = true) { macro ->
            macro.copy(actions = macro.actions + created)
        }
        ExecutionLogBus.info("Titik klik ditambahkan di ($x, $y).")
        return created
    }

    fun addQuickSwipeMarker(x1: Int, y1: Int, x2: Int, y2: Int): SwipeAction {
        val created = SwipeAction(x1 = x1, y1 = y1, x2 = x2, y2 = y2, durationMs = 350L, delayAfterMs = 300L)
        _activeMode.value = AutomationMode.MACRO
        updateActiveMacro(persist = true) { macro ->
            macro.copy(actions = macro.actions + created)
        }
        ExecutionLogBus.info("Swipe ditambahkan ($x1,$y1) → ($x2,$y2).")
        return created
    }

    fun addQuickImageAction(imageName: String, threshold: Float = 0.80f, region: Region? = null) {
        _activeMode.value = AutomationMode.MACRO
        updateActiveMacro(persist = true) { macro ->
            macro.copy(
                actions = macro.actions + ImageAction(
                    imageName = imageName,
                    threshold = threshold,
                    timeoutMs = 3000L,
                    region = region,
                    clickOnFound = true
                )
            )
        }
        ExecutionLogBus.image("Aksi Deteksi Gambar '$imageName' ditambahkan ke '${_activeMacro.value.name}'.")
    }

    fun bindImageToAction(actionId: String, imageName: String) {
        _activeMode.value = AutomationMode.MACRO
        updateActiveMacro(persist = true) { macro ->
            macro.copy(
                actions = macro.actions.updateAction(actionId) { a ->
                    if (a is ImageAction) a.copy(imageName = imageName) else a
                }
            )
        }
        ExecutionLogBus.image("Gambar '$imageName' dipasang ke aksi Deteksi Gambar.")
    }

    fun bindRegionToAction(actionId: String, region: Region) {
        _activeMode.value = AutomationMode.MACRO
        updateActiveMacro(persist = true) { macro ->
            macro.copy(
                actions = macro.actions.updateAction(actionId) { a ->
                    when (a) {
                        is TextAction -> a.copy(region = region)
                        is ImageAction -> a.copy(region = region)
                        else -> a
                    }
                }
            )
        }
        ExecutionLogBus.info("Area baca (${region.left},${region.top})–(${region.right},${region.bottom}) disimpan.")
    }

    fun addRecordedGestures(gestures: List<RecordedGesture>) {
        if (gestures.isEmpty()) return
        _activeMode.value = AutomationMode.MACRO
        updateActiveMacro(persist = true) { macro ->
            macro.copy(actions = macro.actions + RecordAction(gestures = gestures))
        }
        ExecutionLogBus.action("Rekaman (${gestures.size} gestur) ditambahkan ke '${_activeMacro.value.name}'.")
    }

    fun addQuickZoomMarker(cx: Int, cy: Int, zoomIn: Boolean = true) {
        _activeMode.value = AutomationMode.MACRO
        updateActiveMacro(persist = true) { macro ->
            macro.copy(actions = macro.actions + ZoomAction(cx = cx, cy = cy, zoomIn = zoomIn))
        }
        ExecutionLogBus.info("${if (zoomIn) "Zoom In" else "Zoom Out"} ditambahkan di ($cx, $cy).")
    }

    fun addActionFromOverlay(ref: ContainerRef, action: Action) {
        _activeMode.value = AutomationMode.MACRO
        updateActiveMacro(persist = true) { macro ->
            macro.copy(actions = macro.actions.insertInto(ref, action))
        }
    }

    fun moveActionFromOverlay(actionId: String, delta: Int) {
        updateActiveMacro(persist = true) { macro ->
            macro.copy(actions = macro.actions.moveAction(actionId, delta))
        }
    }

    fun updateMarkerPosition(actionId: String, newX: Int, newY: Int, isEndNode: Boolean = false) {
        updateActiveMacro(persist = true) { macro ->
            macro.copy(
                actions = macro.actions.updateAction(actionId) { a ->
                    when (a) {
                        is ClickAction -> a.copy(x = newX, y = newY)
                        is LongPressAction -> a.copy(x = newX, y = newY)
                        is SwipeAction -> if (isEndNode) a.copy(x2 = newX, y2 = newY) else a.copy(x1 = newX, y1 = newY)
                        is ZoomAction -> a.copy(cx = newX, cy = newY)
                        is ColorAction -> a.copy(x = newX, y = newY)
                        else -> a
                    }
                }
            )
        }
    }

    fun updateActionFromOverlay(actionId: String, transform: (Action) -> Action) {
        updateActiveMacro(persist = true) { macro ->
            macro.copy(actions = macro.actions.updateAction(actionId, transform))
        }
    }

    fun removeActionFromOverlay(actionId: String) {
        updateActiveMacro(persist = true) { macro ->
            macro.copy(actions = macro.actions.removeAction(actionId))
        }
    }

    fun publishPickedPixel(x: Int, y: Int, hex: String) {
        _lastPickedPixel.value = PickedPixel(x, y, hex)
        ExecutionLogBus.color("Color Spy: ($x, $y) → $hex")
    }

    fun macroNeedsScreenCapture(macro: Macro): Boolean {
        val localStore = store
        val visited = HashSet<String>()
        fun check(m: Macro): Boolean {
            if (!visited.add(m.id)) return false
            for (a in m.actions.walk()) {
                if (a is ImageAction || a is TextAction || a is ColorAction) return true
                if (a is RunMacroAction && a.targetMacroId.isNotBlank()) {
                    val linked = localStore?.getMacroById(a.targetMacroId)
                    if (linked != null && check(linked)) return true
                }
            }
            return false
        }
        return check(macro)
    }

    fun togglePlayPause(screenWidth: Int, screenHeight: Int) {
        when (_engineState.value) {
            EngineState.RUNNING -> pause()
            EngineState.PAUSED -> resume()
            EngineState.IDLE, EngineState.ERROR -> {
                if (_activeMode.value == AutomationMode.LUA) {
                    val script = _activeLuaScript.value
                    if (script == null || script.code.isBlank()) {
                        ExecutionLogBus.warn("Pilih atau tulis script Lua terlebih dahulu.")
                        return
                    }
                    runLuaScript(script, screenWidth, screenHeight)
                } else {
                    val macro = _activeMacro.value
                    if (macro.actions.isEmpty()) {
                        ExecutionLogBus.warn("Tambahkan minimal 1 aksi (Klik / Gambar / Rekam) terlebih dahulu.")
                        return
                    }
                    runMacro(macro, screenWidth, screenHeight)
                }
            }
        }
    }

    fun runMacro(
        macro: Macro,
        screenWidth: Int = ScreenCaptureService.captureWidth,
        screenHeight: Int = ScreenCaptureService.captureHeight
    ) {
        stop()
        if (!MacroAccessibilityService.isConnected.value) {
            _engineState.value = EngineState.ERROR
            ExecutionLogBus.error("Accessibility Service belum aktif! Aktifkan di Pengaturan Aksesibilitas.")
            return
        }
        if (macroNeedsScreenCapture(macro) && !ScreenCaptureService.isCapturing.value) {
            ExecutionLogBus.warn("Tangkap layar belum aktif — deteksi gambar/teks/warna tidak dapat melihat layar.")
        }

        selectMacro(macro)
        stopRequested.set(false)
        pausedFlag.set(false)
        _currentLoop.value = 0
        _actionsExecuted.value = 0
        _engineState.value = EngineState.RUNNING
        ScreenCaptureService.highFrequencyCapture = true

        val startedAt = System.currentTimeMillis()
        val counter = AtomicInteger(0)
        val localStore = store

        activeJob = engineScope.launch {
            ExecutionLogBus.info(
                "Memulai '${macro.name}' (${macro.actions.size} aksi, loop=${if (macro.loopCount <= 0) "∞" else macro.loopCount})."
            )
            val runtime = object : MacroRuntime {
                override val screenWidth: Int = screenWidth
                override val screenHeight: Int = screenHeight
                override fun isStopped(): Boolean = stopRequested.get() || !isActive
                override suspend fun waitIfPaused() = this@AutomationCoordinator.waitIfPaused()
                override suspend fun sleep(ms: Long) = cooperativeDelay(ms)
                override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean =
                    MacroAccessibilityService.dispatchClick(x.toFloat(), y.toFloat(), durationMs)

                override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
                    MacroAccessibilityService.dispatchSwipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs)

                override suspend fun zoom(cx: Int, cy: Int, zoomIn: Boolean, distancePx: Int, durationMs: Long): Boolean =
                    MacroAccessibilityService.dispatchZoom(cx.toFloat(), cy.toFloat(), zoomIn, distancePx, durationMs)

                override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float): Boolean =
                    MacroAccessibilityService.dispatchRecordedGesture(gesture, speed)

                override fun captureFrame(): Frame? = ScreenCaptureService.captureFrame()
                override fun samplePixelArgb(x: Int, y: Int): Int? = ScreenCaptureService.samplePixelArgb(x, y)
                override suspend fun recognizeText(region: Region?): List<OcrBlock> {
                    val bmp = ScreenCaptureService.captureBitmapCopy() ?: return emptyList()
                    return try {
                        ScreenTextRecognizer.recognize(bmp, region)
                    } finally {
                        bmp.recycle()
                    }
                }
                override fun loadTemplate(name: String): PreparedTemplate? = localStore?.loadTemplate(name)
                override fun loadMacro(id: String): Macro? = localStore?.getMacroById(id)
                override fun onMatchFound(match: MatchResult, label: String) {
                    if (localStore?.settings?.value?.showDebugMatchBox != false) {
                        flashMatchHighlight(match, label)
                    }
                }
                override fun onLoopChanged(loop: Int) { _currentLoop.value = loop }
                override fun onActionExecuted() { _actionsExecuted.value = counter.incrementAndGet() }
                override fun log(level: LogLevel, message: String) = ExecutionLogBus.log(level, message)
            }

            try {
                MacroRunner(runtime).run(macro)
            } catch (_: CancellationException) {
                ExecutionLogBus.info("Macro dihentikan.")
            } catch (t: Throwable) {
                _engineState.value = EngineState.ERROR
                ExecutionLogBus.error("Error: ${t.message ?: t::class.java.simpleName}")
            } finally {
                ScreenCaptureService.highFrequencyCapture = false
                val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                if (_engineState.value != EngineState.ERROR) {
                    _engineState.value = EngineState.IDLE
                }
                pausedFlag.set(false)
                ExecutionLogBus.info("Selesai (${counter.get()} aksi dalam ${elapsed}ms).")
            }
        }
    }

    fun runLuaScript(script: LuaScript, screenWidth: Int, screenHeight: Int) {
        stop()
        if (!MacroAccessibilityService.isConnected.value) {
            _engineState.value = EngineState.ERROR
            ExecutionLogBus.error("Accessibility Service belum aktif! Aktifkan terlebih dahulu.")
            return
        }

        selectLuaScript(script)
        stopRequested.set(false)
        pausedFlag.set(false)
        _actionsExecuted.value = 0
        _engineState.value = EngineState.RUNNING
        ScreenCaptureService.highFrequencyCapture = true

        val startedAt = System.currentTimeMillis()
        val counter = AtomicInteger(0)
        val localStore = store
        val jitter = localStore?.settings?.value?.defaultJitterPx ?: 0

        activeJob = engineScope.launch {
            ExecutionLogBus.lua("Menjalankan script Lua '${script.name}'...")
            val bridge = object : LuaBridge {
                override val screenWidth: Int = screenWidth
                override val screenHeight: Int = screenHeight
                override fun isStopped(): Boolean = stopRequested.get() || !isActive
                override fun isPaused(): Boolean = pausedFlag.get()

                override fun click(x: Int, y: Int, durationMs: Int): Boolean {
                    val dx = if (jitter > 0) Random.nextInt(-jitter, jitter + 1) else 0
                    val dy = if (jitter > 0) Random.nextInt(-jitter, jitter + 1) else 0
                    val tx = (x + dx).coerceAtLeast(0).toFloat()
                    val ty = (y + dy).coerceAtLeast(0).toFloat()
                    ExecutionLogBus.action("Lua click(${tx.toInt()}, ${ty.toInt()}, ${durationMs}ms)")
                    val ok = runBlocking { MacroAccessibilityService.dispatchClick(tx, ty, durationMs.toLong()) }
                    if (ok) _actionsExecuted.value = counter.incrementAndGet()
                    return ok
                }

                override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
                    ExecutionLogBus.action("Lua swipe($x1,$y1 → $x2,$y2, ${durationMs}ms)")
                    val ok = runBlocking {
                        MacroAccessibilityService.dispatchSwipe(
                            x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs.toLong()
                        )
                    }
                    if (ok) _actionsExecuted.value = counter.incrementAndGet()
                    return ok
                }

                override fun zoom(cx: Int, cy: Int, zoomIn: Boolean, distancePx: Int, durationMs: Int): Boolean {
                    ExecutionLogBus.action("Lua ${if (zoomIn) "zoomIn" else "zoomOut"}($cx,$cy, ${distancePx}px)")
                    val ok = runBlocking {
                        MacroAccessibilityService.dispatchZoom(cx.toFloat(), cy.toFloat(), zoomIn, distancePx, durationMs.toLong())
                    }
                    if (ok) _actionsExecuted.value = counter.incrementAndGet()
                    return ok
                }

                override fun sleepCooperative(ms: Long) {
                    var remaining = ms
                    while (remaining > 0L) {
                        while (pausedFlag.get() && !stopRequested.get()) Thread.sleep(30L)
                        if (stopRequested.get() || !isActive) throw LuaStopSignal(graceful = false)
                        val chunk = minOf(remaining, 50L)
                        Thread.sleep(chunk)
                        remaining -= chunk
                    }
                }

                override fun getColorArgb(x: Int, y: Int): Int? {
                    val argb = ScreenCaptureService.samplePixelArgb(x, y)
                    if (argb == null) ExecutionLogBus.warn("getColor($x,$y): Tangkap layar belum aktif.")
                    return argb
                }

                override fun findImage(name: String, threshold: Double, region: Region?): MatchResult? {
                    val tpl = localStore?.loadTemplate(name)
                    if (tpl == null) {
                        ExecutionLogBus.warn("findImage('$name'): gambar tidak ada di pustaka.")
                        return null
                    }
                    val frame = ScreenCaptureService.captureFrame()
                    if (frame == null) {
                        ExecutionLogBus.warn("findImage('$name'): Tangkap layar belum aktif.")
                        return null
                    }
                    val match = if (region != null) {
                        TemplateMatcher.find(frame, tpl, threshold, region.left, region.top, region.right, region.bottom)
                    } else {
                        TemplateMatcher.find(frame, tpl, threshold)
                    }
                    if (match != null && localStore.settings.value.showDebugMatchBox) {
                        flashMatchHighlight(match, "$name ${(match.score * 100).toInt()}%")
                    }
                    return match
                }

                override fun findAllImages(name: String, threshold: Double, maxResults: Int, region: Region?): List<MatchResult> {
                    val tpl = localStore?.loadTemplate(name) ?: return emptyList()
                    val frame = ScreenCaptureService.captureFrame() ?: return emptyList()
                    return if (region != null) {
                        TemplateMatcher.findAll(frame, tpl, threshold, maxResults, region.left, region.top, region.right, region.bottom)
                    } else {
                        TemplateMatcher.findAll(frame, tpl, threshold, maxResults)
                    }
                }

                override fun log(message: String) = ExecutionLogBus.lua(message)
            }

            val result = SandboxedLuaEngine.execute(script.code, bridge)
            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            val isError = result.isFailure && result.exceptionOrNull() !is LuaStopSignal && !stopRequested.get()
            if (isError) {
                ExecutionLogBus.error("Lua Error: ${result.exceptionOrNull()?.message}")
            }
            ScreenCaptureService.highFrequencyCapture = false
            _engineState.value = if (isError) EngineState.ERROR else EngineState.IDLE
            pausedFlag.set(false)
            ExecutionLogBus.info("Lua selesai (${counter.get()} aksi dalam ${elapsed}ms).")
        }
    }

    private fun flashMatchHighlight(match: MatchResult, label: String) {
        val highlight = MatchHighlight(match, label)
        _matchHighlight.value = highlight
        engineScope.launch {
            delay(750L)
            _matchHighlight.update { cur -> if (cur?.timestampMs == highlight.timestampMs) null else cur }
        }
    }

    fun pause() {
        if (_engineState.value == EngineState.RUNNING) {
            pausedFlag.set(true)
            _engineState.value = EngineState.PAUSED
            ExecutionLogBus.info("Dijeda.")
        }
    }

    fun resume() {
        if (_engineState.value == EngineState.PAUSED) {
            pausedFlag.set(false)
            _engineState.value = EngineState.RUNNING
            ExecutionLogBus.info("Dilanjutkan.")
        }
    }

    fun stop() {
        stopRequested.set(true)
        pausedFlag.set(false)
        ScreenCaptureService.highFrequencyCapture = false
        activeJob?.cancel()
        activeJob = null
        if (_engineState.value != EngineState.IDLE) {
            _engineState.value = EngineState.IDLE
            ExecutionLogBus.info("Dihentikan.")
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
