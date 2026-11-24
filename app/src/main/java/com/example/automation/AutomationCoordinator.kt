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
import com.example.data.MacroSecurity
import com.example.data.RecordAction
import com.example.data.RecordedGesture
import com.example.data.Region
import com.example.data.ResolutionScaler
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

    private fun rejectIfActiveMacroLocked(): Boolean {
        val cur = _activeMacro.value
        if (cur.isLocked) {
            ExecutionLogBus.warn("Macro '${cur.name}' dikunci dengan sandi — buka kunci di aplikasi untuk mengubah.")
            return true
        }
        return false
    }

    private fun resolveScreenSize(screenW: Int, screenH: Int): Pair<Int, Int> {
        if (screenW > 0 && screenH > 0) return screenW to screenH
        return store?.currentScreenSize() ?: (ScreenCaptureService.captureWidth to ScreenCaptureService.captureHeight)
    }

    private fun liveScreenSize(fallbackW: Int, fallbackH: Int): Pair<Int, Int> {
        if (ScreenCaptureService.isCapturing.value && ScreenCaptureService.captureWidth > 0 && ScreenCaptureService.captureHeight > 0) {
            return ScreenCaptureService.captureWidth to ScreenCaptureService.captureHeight
        }
        return store?.currentScreenSize() ?: (fallbackW.coerceAtLeast(1) to fallbackH.coerceAtLeast(1))
    }

    fun addQuickClickMarker(x: Int, y: Int, screenW: Int = 0, screenH: Int = 0): ClickAction? {
        if (rejectIfActiveMacroLocked()) return null
        val (sw, sh) = resolveScreenSize(screenW, screenH)
        val settings = store?.settings?.value
        val tapMs = settings?.defaultTapDurationMs ?: 50L
        var created: ClickAction? = null
        _activeMode.value = AutomationMode.MACRO
        updateActiveMacro(persist = true) { macro ->
            val base = ResolutionScaler.ensureAuthoringResolution(macro, sw, sh)
            val t = ResolutionScaler.forMacro(base, sw, sh)
            val (ox, oy) = t.unscalePoint(x, y)
            val action = ClickAction(x = ox, y = oy, durationMs = tapMs, delayAfterMs = 300L)
            created = action
            base.copy(actions = base.actions + action)
        }
        ExecutionLogBus.info("Titik klik ditambahkan di ($x, $y).")
        return created
    }

    fun addQuickSwipeMarker(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        screenW: Int = 0,
        screenH: Int = 0
    ): SwipeAction? {
        if (rejectIfActiveMacroLocked()) return null
        val (sw, sh) = resolveScreenSize(screenW, screenH)
        var created: SwipeAction? = null
        _activeMode.value = AutomationMode.MACRO
        updateActiveMacro(persist = true) { macro ->
            val base = ResolutionScaler.ensureAuthoringResolution(macro, sw, sh)
            val t = ResolutionScaler.forMacro(base, sw, sh)
            val (ox1, oy1) = t.unscalePoint(x1, y1)
            val (ox2, oy2) = t.unscalePoint(x2, y2)
            val action = SwipeAction(x1 = ox1, y1 = oy1, x2 = ox2, y2 = oy2, durationMs = 350L, delayAfterMs = 300L)
            created = action
            base.copy(actions = base.actions + action)
        }
        ExecutionLogBus.info("Swipe ditambahkan ($x1,$y1) → ($x2,$y2).")
        return created
    }

    fun addQuickImageAction(
        imageName: String,
        threshold: Float = 0.80f,
        region: Region? = null,
        screenW: Int = 0,
        screenH: Int = 0
    ) {
        if (rejectIfActiveMacroLocked()) return
        val (sw, sh) = resolveScreenSize(screenW, screenH)
        _activeMode.value = AutomationMode.MACRO
        updateActiveMacro(persist = true) { macro ->
            val base = ResolutionScaler.ensureAuthoringResolution(macro, sw, sh)
            val t = ResolutionScaler.forMacro(base, sw, sh)
            val storedRegion = region?.let { t.unscaleRegion(it) }
            base.copy(
                actions = base.actions + ImageAction(
                    imageName = imageName,
                    threshold = threshold,
                    timeoutMs = 3000L,
                    region = storedRegion,
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

    fun bindRegionToAction(actionId: String, region: Region, screenW: Int = 0, screenH: Int = 0) {
        val (sw, sh) = resolveScreenSize(screenW, screenH)
        _activeMode.value = AutomationMode.MACRO
        updateActiveMacro(persist = true) { macro ->
            val base = ResolutionScaler.ensureAuthoringResolution(macro, sw, sh)
            val t = ResolutionScaler.forMacro(base, sw, sh)
            val storedRegion = t.unscaleRegion(region)
            base.copy(
                actions = base.actions.updateAction(actionId) { a ->
                    when (a) {
                        is TextAction -> a.copy(region = storedRegion)
                        is ImageAction -> a.copy(region = storedRegion)
                        else -> a
                    }
                }
            )
        }
        ExecutionLogBus.info("Area baca (${region.left},${region.top})–(${region.right},${region.bottom}) disimpan.")
    }

    fun addRecordedGestures(gestures: List<RecordedGesture>, screenW: Int = 0, screenH: Int = 0) {
        if (gestures.isEmpty()) return
        if (rejectIfActiveMacroLocked()) return
        val (sw, sh) = resolveScreenSize(screenW, screenH)
        _activeMode.value = AutomationMode.MACRO
        updateActiveMacro(persist = true) { macro ->
            val base = ResolutionScaler.ensureAuthoringResolution(macro, sw, sh)
            val t = ResolutionScaler.forMacro(base, sw, sh)
            val storedGestures = gestures.map { t.unscaleRecordedGesture(it) }
            base.copy(actions = base.actions + RecordAction(gestures = storedGestures))
        }
        ExecutionLogBus.action("Rekaman (${gestures.size} gestur) ditambahkan ke '${_activeMacro.value.name}'.")
    }

    fun addQuickZoomMarker(cx: Int, cy: Int, zoomIn: Boolean = true, screenW: Int = 0, screenH: Int = 0) {
        if (rejectIfActiveMacroLocked()) return
        val (sw, sh) = resolveScreenSize(screenW, screenH)
        _activeMode.value = AutomationMode.MACRO
        updateActiveMacro(persist = true) { macro ->
            val base = ResolutionScaler.ensureAuthoringResolution(macro, sw, sh)
            val t = ResolutionScaler.forMacro(base, sw, sh)
            val (ox, oy) = t.unscalePoint(cx, cy)
            base.copy(actions = base.actions + ZoomAction(cx = ox, cy = oy, zoomIn = zoomIn))
        }
        ExecutionLogBus.info("${if (zoomIn) "Zoom In" else "Zoom Out"} ditambahkan di ($cx, $cy).")
    }

    fun addActionFromOverlay(ref: ContainerRef, action: Action, screenW: Int = 0, screenH: Int = 0) {
        if (rejectIfActiveMacroLocked()) return
        val (sw, sh) = resolveScreenSize(screenW, screenH)
        _activeMode.value = AutomationMode.MACRO
        updateActiveMacro(persist = true) { macro ->
            val base = ResolutionScaler.ensureAuthoringResolution(macro, sw, sh)
            val t = ResolutionScaler.forMacro(base, sw, sh)
            val storedAction = when (action) {
                is ClickAction -> {
                    val (ox, oy) = t.unscalePoint(action.x, action.y)
                    action.copy(x = ox, y = oy)
                }
                is LongPressAction -> {
                    val (ox, oy) = t.unscalePoint(action.x, action.y)
                    action.copy(x = ox, y = oy)
                }
                is SwipeAction -> {
                    val (ox1, oy1) = t.unscalePoint(action.x1, action.y1)
                    val (ox2, oy2) = t.unscalePoint(action.x2, action.y2)
                    action.copy(x1 = ox1, y1 = oy1, x2 = ox2, y2 = oy2)
                }
                is ZoomAction -> {
                    val (ox, oy) = t.unscalePoint(action.cx, action.cy)
                    action.copy(cx = ox, cy = oy)
                }
                is ColorAction -> {
                    val (ox, oy) = t.unscalePoint(action.x, action.y)
                    action.copy(x = ox, y = oy)
                }
                else -> action
            }
            base.copy(actions = base.actions.insertInto(ref, storedAction))
        }
    }

    fun moveActionFromOverlay(actionId: String, delta: Int) {
        if (rejectIfActiveMacroLocked()) return
        updateActiveMacro(persist = true) { macro ->
            macro.copy(actions = macro.actions.moveAction(actionId, delta))
        }
    }

    fun updateMarkerPosition(
        actionId: String,
        newX: Int,
        newY: Int,
        isEndNode: Boolean = false,
        screenW: Int = 0,
        screenH: Int = 0
    ) {
        val (sw, sh) = resolveScreenSize(screenW, screenH)
        updateActiveMacro(persist = true) { macro ->
            val base = ResolutionScaler.ensureAuthoringResolution(macro, sw, sh)
            val t = ResolutionScaler.forMacro(base, sw, sh)
            val (ox, oy) = t.unscalePoint(newX, newY)
            base.copy(
                actions = base.actions.updateAction(actionId) { a ->
                    when (a) {
                        is ClickAction -> a.copy(x = ox, y = oy)
                        is LongPressAction -> a.copy(x = ox, y = oy)
                        is SwipeAction -> if (isEndNode) a.copy(x2 = ox, y2 = oy) else a.copy(x1 = ox, y1 = oy)
                        is ZoomAction -> a.copy(cx = ox, cy = oy)
                        is ColorAction -> a.copy(x = ox, y = oy)
                        else -> a
                    }
                }
            )
        }
    }

    fun updateTargetFromScreen(
        actionId: String,
        screenX1: Int,
        screenY1: Int,
        screenX2: Int,
        screenY2: Int,
        hexColor: String?,
        screenW: Int,
        screenH: Int
    ) {
        if (rejectIfActiveMacroLocked()) return
        val (sw, sh) = resolveScreenSize(screenW, screenH)
        updateActiveMacro(persist = true) { macro ->
            val base = ResolutionScaler.ensureAuthoringResolution(macro, sw, sh)
            val t = ResolutionScaler.forMacro(base, sw, sh)
            val (ox1, oy1) = t.unscalePoint(screenX1, screenY1)
            val (ox2, oy2) = t.unscalePoint(screenX2, screenY2)
            base.copy(
                actions = base.actions.updateAction(actionId) { cur ->
                    when (cur) {
                        is ClickAction -> cur.copy(x = ox1, y = oy1)
                        is LongPressAction -> cur.copy(x = ox1, y = oy1)
                        is SwipeAction -> cur.copy(x1 = ox1, y1 = oy1, x2 = ox2, y2 = oy2)
                        is ZoomAction -> cur.copy(cx = ox1, cy = oy1)
                        is ColorAction -> cur.copy(x = ox1, y = oy1, color = hexColor ?: cur.color)
                        else -> cur
                    }
                }
            )
        }
    }

    fun updateActionFromOverlay(actionId: String, transform: (Action) -> Action) {
        if (rejectIfActiveMacroLocked()) return
        updateActiveMacro(persist = true) { macro ->
            macro.copy(actions = macro.actions.updateAction(actionId, transform))
        }
    }

    fun removeActionFromOverlay(actionId: String) {
        if (rejectIfActiveMacroLocked()) return
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
        val now = localStore?.effectiveNowMs() ?: System.currentTimeMillis()
        val visited = HashSet<String>()
        fun check(m: Macro): Boolean {
            if (!visited.add(m.id)) return false
            for (a in m.actions.walk()) {
                if (a is ImageAction || a is TextAction || a is ColorAction) return true
                if (a is RunMacroAction && a.targetMacroId.isNotBlank()) {
                    val linked = localStore?.getMacroById(a.targetMacroId)
                    if (linked != null && MacroSecurity.checkLinkedRun(linked, now) == null && check(linked)) {
                        return true
                    }
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
        val effectiveNow = store?.effectiveNowMs() ?: System.currentTimeMillis()
        MacroSecurity.checkDirectRun(macro, effectiveNow)?.let { denial ->
            ExecutionLogBus.error(MacroSecurity.denialMessage(macro, denial))
            return
        }
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
                override val screenWidth: Int get() = liveScreenSize(screenWidth, screenHeight).first
                override val screenHeight: Int get() = liveScreenSize(screenWidth, screenHeight).second
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
                override fun nowMs(): Long = localStore?.effectiveNowMs() ?: System.currentTimeMillis()
            }

            try {
                MacroRunner(runtime).run(macro)
            } catch (_: CancellationException) {
                ExecutionLogBus.info("Macro dihentikan.")
            } catch (t: Throwable) {
                _engineState.value = EngineState.ERROR
                ExecutionLogBus.error("Error: ${t.message ?: t::class.java.simpleName}")
            } finally {
                localStore?.flushClockWatermark()
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

                private val luaScaleCache = HashMap<String, Double>()

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
                    val rLeft = region?.left ?: 0
                    val rTop = region?.top ?: 0
                    val rRight = region?.right ?: frame.width
                    val rBottom = region?.bottom ?: frame.height
                    val cacheKey = "$name@${frame.width}x${frame.height}"
                    val cachedScale = luaScaleCache[cacheKey]
                    val triedScales = ArrayList<Double>(2)
                    var match: MatchResult? = null
                    if (cachedScale != null) {
                        triedScales += cachedScale
                        val scaledTpl = tpl.scaled(cachedScale, cachedScale)
                        match = TemplateMatcher.find(frame, scaledTpl, threshold, rLeft, rTop, rRight, rBottom)
                    }
                    if (match == null && (cachedScale == null || kotlin.math.abs(cachedScale - 1.0) >= 0.02)) {
                        triedScales += 1.0
                        match = TemplateMatcher.find(frame, tpl, threshold, rLeft, rTop, rRight, rBottom)
                        if (match != null) luaScaleCache[cacheKey] = 1.0
                    }
                    if (match == null) {
                        val ms = TemplateMatcher.findMultiScale(
                            frame = frame,
                            template = tpl,
                            threshold = threshold,
                            regionLeft = rLeft,
                            regionTop = rTop,
                            regionRight = rRight,
                            regionBottom = rBottom,
                            skipScales = triedScales
                        )
                        if (ms.match != null) {
                            match = ms.match
                            luaScaleCache[cacheKey] = ms.bestScale
                        }
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
