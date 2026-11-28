package com.example.automation

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import com.example.data.Action
import com.example.data.ClickAction
import com.example.data.ColorAction
import com.example.data.ContainerRef
import com.example.data.ExitAppMode
import com.example.data.FinishMacroAction
import com.example.data.FinishMacroMode
import com.example.data.ImageAction
import com.example.data.LocalStore
import com.example.data.LongPressAction
import com.example.data.LuaScript
import com.example.data.Macro
import com.example.data.MacroSecurity
import com.example.data.RecentsSwipeDirection
import com.example.data.RecordAction
import com.example.data.RecordedGesture
import com.example.data.Region
import com.example.data.ResolutionScaleMode
import com.example.data.ResolutionScaler
import com.example.data.RunMacroAction
import com.example.data.StuckRecoveryMode
import com.example.data.SwipeAction
import com.example.data.TextAction
import com.example.data.TrackInteractionMode
import com.example.data.TrackObjectAction
import com.example.data.ZoomAction
import com.example.data.allActionsSequence
import com.example.data.insertInto
import com.example.data.moveActionToBranch
import com.example.data.moveAnyAction
import com.example.data.removeAnyAction
import com.example.data.updateAnyAction
import com.example.data.updateBranchActions
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
import kotlinx.coroutines.withContext
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
    private val botScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> }
    )
    private var activeJob: Job? = null
    private val runGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val stopRequested = AtomicBoolean(false)
    private val pausedFlag = AtomicBoolean(false)
    private val skipAccountRequested = AtomicBoolean(false)

    @Volatile
    private var store: LocalStore? = null
    @Volatile
    private var appCtx: Context? = null

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

    private val _activeAccountLabel = MutableStateFlow("")
    val activeAccountLabel: StateFlow<String> = _activeAccountLabel.asStateFlow()

    private val _visualDebugEnabled = MutableStateFlow(false)
    val visualDebugEnabled: StateFlow<Boolean> = _visualDebugEnabled.asStateFlow()

    private val _debugEvents = kotlinx.coroutines.flow.MutableSharedFlow<VisualDebugEvent>(extraBufferCapacity = 48)
    val debugEvents: kotlinx.coroutines.flow.SharedFlow<VisualDebugEvent> = _debugEvents

    private val _liveVariables = MutableStateFlow<Map<String, Double>>(emptyMap())
    val liveVariables: StateFlow<Map<String, Double>> = _liveVariables.asStateFlow()

    private val _humanBreakRemainingSec = MutableStateFlow(0)
    val humanBreakRemainingSec: StateFlow<Int> = _humanBreakRemainingSec.asStateFlow()

    private val _liveSessionStats = MutableStateFlow<com.example.data.LiveSessionStats?>(null)
    val liveSessionStats: StateFlow<com.example.data.LiveSessionStats?> = _liveSessionStats.asStateFlow()

    private val _afkBlackScreenActive = MutableStateFlow(false)
    val afkBlackScreenActive: StateFlow<Boolean> = _afkBlackScreenActive.asStateFlow()

    private val _deviceHealth = MutableStateFlow<com.example.data.DeviceHealthSnapshot?>(null)
    val deviceHealth: StateFlow<com.example.data.DeviceHealthSnapshot?> = _deviceHealth.asStateFlow()

    private val _thermalPaused = MutableStateFlow(false)
    val thermalPaused: StateFlow<Boolean> = _thermalPaused.asStateFlow()

    fun toggleAfkBlackScreen(): Boolean {
        val next = !_afkBlackScreenActive.value
        _afkBlackScreenActive.value = next
        if (next) {
            _deviceHealth.value = DeviceHealthReader.read(appCtx)
            ExecutionLogBus.info("🌙 Mode Layar Hitam AFK AKTIF — layar diredupkan hemat baterai.")
        } else {
            ExecutionLogBus.info("☀ Mode Layar Hitam AFK dimatikan.")
        }
        return next
    }

    fun setAfkBlackScreenActive(active: Boolean) {
        if (_afkBlackScreenActive.value == active) return
        _afkBlackScreenActive.value = active
        if (active) {
            _deviceHealth.value = DeviceHealthReader.read(appCtx)
        }
    }

    fun refreshDeviceHealth(): com.example.data.DeviceHealthSnapshot? {
        val snap = DeviceHealthReader.read(appCtx)
        _deviceHealth.value = snap
        return snap
    }

    fun runScheduledMacro(schedule: com.example.data.MacroSchedule) {
        val localStore = store ?: return
        val target = localStore.getMacroById(schedule.macroId)
        val schedTitle = schedule.label.ifBlank { schedule.macroName.ifBlank { "Jadwal" } }
        if (target == null) {
            ExecutionLogBus.error("⏰ Jadwal '$schedTitle' gagal: macro tidak ditemukan.")
            return
        }
        val effectiveNow = localStore.effectiveNowMs()
        val parentSet = localStore.getMacroSetById(target.setId)
        MacroSecurity.checkDirectRun(target, effectiveNow, parentSet)?.let { denial ->
            ExecutionLogBus.error("⏰ Jadwal '$schedTitle' ditolak: ${MacroSecurity.denialMessage(target, denial, parentSet)}")
            return
        }
        if (!MacroAccessibilityService.isConnected.value) {
            val msg = "Jadwal '$schedTitle' tidak bisa jalan karena Aksesibilitas belum aktif."
            ExecutionLogBus.error("⏰ $msg")
            val cfg = localStore.notificationConfig.value
            if (cfg.notifyOnError || cfg.alarmOnError) {
                PhoneAlarmAndNotifyManager.dispatchAsync(
                    context = appCtx,
                    config = cfg,
                    title = "Jadwal Gagal — $schedTitle",
                    message = msg,
                    level = com.example.data.NotifyLevel.WARNING,
                    sendRemote = cfg.notifyOnError,
                    includeScreenshot = false,
                    triggerPhoneAlarm = cfg.alarmOnError
                )
            }
            return
        }

        ExecutionLogBus.info("⏰ Memulai jadwal '$schedTitle' → macro '${target.name}'...")
        engineScope.launch {
            val pkg = schedule.launchAppPackage.trim()
            if (pkg.isNotBlank()) {
                val ctx = appCtx
                val launchIntent = runCatching { ctx?.packageManager?.getLaunchIntentForPackage(pkg) }.getOrNull()
                if (launchIntent != null && ctx != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    val launched = MacroAccessibilityService.launchActivityIntent(launchIntent) ||
                        runCatching { ctx.startActivity(launchIntent); true }.getOrDefault(false)
                    if (launched) {
                        ExecutionLogBus.info("⏰ Membuka '${schedule.launchAppLabel.ifBlank { pkg }}', menunggu ${schedule.launchWaitSec}d...")
                        delay(schedule.launchWaitSec.coerceIn(1, 60) * 1000L)
                    }
                }
            }
            val effectiveMacro = if (schedule.stopAfterMinutes > 0) {
                target.copy(maxDurationMin = schedule.stopAfterMinutes)
            } else target
            val (sw, sh) = liveScreenSize(ScreenCaptureService.captureWidth, ScreenCaptureService.captureHeight)
            runMacro(
                macro = effectiveMacro,
                screenWidth = sw,
                screenHeight = sh,
                startInBlackScreen = schedule.enableBlackScreenOnStart
            )
        }
    }

    fun toggleVisualDebug(): Boolean {
        val next = !_visualDebugEnabled.value
        _visualDebugEnabled.value = next
        ExecutionLogBus.info(
            if (next) "🐞 Debug Visual AKTIF — kotak deteksi, skor %, OCR & kurva swipe ditampilkan di layar."
            else "🐞 Debug Visual dimatikan."
        )
        return next
    }

    fun setVisualDebugEnabled(enabled: Boolean) {
        _visualDebugEnabled.value = enabled
    }

    fun requestSkipAccount() {
        if (_engineState.value == EngineState.RUNNING || _engineState.value == EngineState.PAUSED) {
            skipAccountRequested.set(true)
            ExecutionLogBus.info("⏭ Permintaan lompat ke akun berikutnya diterima...")
        }
    }

    private val remoteBotListener = RemoteBotListener { msg -> ExecutionLogBus.info(msg) }
    val remoteBotStatus: StateFlow<RemoteBotListenerStatus> = remoteBotListener.status

    fun syncRemoteBotListener(allowed: Boolean = true) {
        val cfg = store?.notificationConfig?.value ?: com.example.data.NotificationConfig()
        remoteBotListener.sync(
            scope = botScope,
            config = cfg,
            allowed = allowed,
            hostProvider = { buildRemoteControlHost() }
        )
    }

    suspend fun sendRemoteBotPanelNow(config: com.example.data.NotificationConfig): RemoteHostOpResult =
        withContext(Dispatchers.IO) {
            RemoteBotApiClient.sendPanelNow(config, buildRemoteControlHost())
        }

    private fun buildRemoteControlHost(): RemoteControlHost = object : RemoteControlHost {
        override val appDisplayName: String = "Wjust macro"
        override val deviceTag: String
            get() = store?.notificationConfig?.value?.remoteBot?.deviceTag.orEmpty()

        override fun listMacros(): List<Macro> = store?.macros?.value.orEmpty()

        override fun runningMacroId(): String? =
            if (_engineState.value == EngineState.RUNNING || _engineState.value == EngineState.PAUSED) {
                _activeMacro.value.id
            } else null

        override fun liveStats(): com.example.data.LiveSessionStats? = _liveSessionStats.value

        override fun deviceHealth(): com.example.data.DeviceHealthSnapshot? = refreshDeviceHealth()

        override fun isAfkBlackScreenActive(): Boolean = _afkBlackScreenActive.value

        override fun startActiveMacro(macro: Macro): RemoteHostOpResult {
            val s = store ?: return RemoteHostOpResult(false, "Penyimpanan belum siap.")
            val latest = s.getMacroById(macro.id) ?: macro
            if (latest.isPassive) {
                return RemoteHostOpResult(false, "Macro '${latest.name}' adalah Macro Pasif — gunakan toggle Pasif.")
            }
            if ((_engineState.value == EngineState.RUNNING || _engineState.value == EngineState.PAUSED) &&
                _activeMacro.value.id == latest.id
            ) {
                return RemoteHostOpResult(true, "Macro Aktif '${latest.name}' sudah berjalan (ON).")
            }
            val parentSet = s.getMacroSetById(latest.setId)
            MacroSecurity.checkDirectRun(latest, s.effectiveNowMs(), parentSet)?.let { denial ->
                return RemoteHostOpResult(false, MacroSecurity.denialMessage(latest, denial, parentSet))
            }
            if (!MacroAccessibilityService.isConnected.value) {
                return RemoteHostOpResult(false, "Aksesibilitas belum aktif di HP.")
            }
            val (sw, sh) = liveScreenSize(ScreenCaptureService.captureWidth, ScreenCaptureService.captureHeight)
            runMacro(latest, sw, sh)
            return RemoteHostOpResult(true, "Macro Aktif '${latest.name}' (`${latest.remoteId}`) dinyalakan (ON).")
        }

        override fun stopActiveMacro(macro: Macro): RemoteHostOpResult {
            val runningId = runningMacroId()
            return if (runningId == macro.id) {
                stop()
                RemoteHostOpResult(true, "Macro Aktif '${macro.name}' (`${macro.remoteId}`) dihentikan (OFF).")
            } else {
                RemoteHostOpResult(true, "Macro Aktif '${macro.name}' (`${macro.remoteId}`) sudah berhenti (OFF).")
            }
        }

        override fun setPassiveMacroEnabled(macro: Macro, enabled: Boolean): RemoteHostOpResult {
            val s = store ?: return RemoteHostOpResult(false, "Penyimpanan belum siap.")
            val latest = s.getMacroById(macro.id) ?: macro
            if (!latest.isPassive) {
                return RemoteHostOpResult(false, "Macro '${latest.name}' bukan Macro Pasif.")
            }
            if (MacroSecurity.isTampered(latest)) {
                return RemoteHostOpResult(false, "Macro '${latest.name}' gagal verifikasi integritas.")
            }
            val updated = MacroSecurity.togglePassiveEnabled(latest, enabled)
            s.saveMacro(updated)
            if (_activeMacro.value.id == updated.id) _activeMacro.value = updated
            ExecutionLogBus.info("🤖 Bot mengubah Macro Pasif '${updated.name}' → ${if (enabled) "ON" else "OFF"}.")
            return RemoteHostOpResult(
                true,
                "Macro Pasif '${updated.name}' (`${updated.remoteId}`) → ${if (enabled) "ON (Dapat Ditarik)" else "OFF (Dilewati)"}."
            )
        }

        override fun setActionRemoteActive(macro: Macro, actionId: String, active: Boolean): RemoteHostOpResult {
            val s = store ?: return RemoteHostOpResult(false, "Penyimpanan belum siap.")
            val latest = s.getMacroById(macro.id) ?: macro
            val ctrl = latest.actionRemoteControls[actionId]
                ?: return RemoteHostOpResult(false, "Pengaturan bot aksi tidak ditemukan.")
            if (!ctrl.enabled) {
                return RemoteHostOpResult(false, "Aksi ini tidak diaktifkan untuk kontrol bot.")
            }
            val updated = MacroSecurity.setRemoteActionActive(latest, actionId, active)
            s.saveMacro(updated)
            if (_activeMacro.value.id == updated.id) _activeMacro.value = updated
            val label = ctrl.displayName(ctrl.remoteId)
            val statusTxt = when {
                ctrl.mode == com.example.data.RemoteActionMode.ONE_SHOT && active -> "SIAP DIPICU 1× (otomatis kembali OFF)"
                ctrl.mode == com.example.data.RemoteActionMode.ONE_SHOT -> "OFF (Dibatalkan)"
                active -> "ON (Aktif)"
                else -> "OFF (Pasif / Dilewati)"
            }
            ExecutionLogBus.info("🤖 Bot mengubah aksi '$label' di '${updated.name}' → $statusTxt.")
            return RemoteHostOpResult(true, "Aksi '$label' (`${ctrl.remoteId}`) → $statusTxt.")
        }

        override fun setAfkBlackScreen(enabled: Boolean): RemoteHostOpResult {
            setAfkBlackScreenActive(enabled)
            ExecutionLogBus.info(
                if (enabled) "🤖 Bot menyalakan Mode Layar Hitam AFK (ON)."
                else "🤖 Bot mematikan Mode Layar Hitam AFK (OFF)."
            )
            return RemoteHostOpResult(
                true,
                if (enabled) "Mode Layar Hitam AFK dinyalakan (ON)." else "Mode Layar Hitam AFK dimatikan (OFF)."
            )
        }

        override fun captureScreenshotJpeg(): ByteArray? {
            if (!ScreenCaptureService.isCapturing.value) return null
            val first = ScreenCaptureService.captureScaledJpeg(maxDimPx = 960, quality = 70)
            if (first != null && first.isNotEmpty()) return first
            // Brief single retry if a frame was momentarily being swapped during display rotation
            runCatching { Thread.sleep(120L) }
            return if (ScreenCaptureService.isCapturing.value) {
                ScreenCaptureService.captureScaledJpeg(maxDimPx = 960, quality = 70)
            } else null
        }
    }

    fun attachStore(context: Context) {
        appCtx = context.applicationContext
        if (store == null) {
            store = LocalStore.get(context)
        }
        _deviceHealth.value = DeviceHealthReader.read(appCtx)
        MacroSchedulerManager.syncAlarms(context)
        syncRemoteBotListener()
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
        val parentSet = store?.getMacroSetById(cur.setId)
        if (MacroSecurity.isEffectivelyLocked(cur, parentSet)) {
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
        val ctx = appCtx
        if (ctx != null) {
            val dm = runCatching { ctx.getSystemService(DisplayManager::class.java) }.getOrNull()
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return metrics.widthPixels to metrics.heightPixels
                }
            }
        }
        if (ScreenCaptureService.isCapturing.value && ScreenCaptureService.captureWidth > 0 && ScreenCaptureService.captureHeight > 0) {
            return ScreenCaptureService.captureWidth to ScreenCaptureService.captureHeight
        }
        return store?.currentScreenSize() ?: (fallbackW.coerceAtLeast(1) to fallbackH.coerceAtLeast(1))
    }

    /** Reads exact live display rotation (Surface.ROTATION_*) and real dimensions directly from DisplayManager. */
    private fun readLiveDisplayState(fallbackW: Int, fallbackH: Int): Triple<Int, Int, Int> {
        val ctx = appCtx
        if (ctx != null) {
            val dm = runCatching { ctx.getSystemService(DisplayManager::class.java) }.getOrNull()
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                val rot = display.rotation
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return Triple(rot, metrics.widthPixels, metrics.heightPixels)
                }
            }
        }
        val (w, h) = liveScreenSize(fallbackW, fallbackH)
        val rot = if (w > h) RecentsDismissGeometry.ROTATION_90 else RecentsDismissGeometry.ROTATION_0
        return Triple(rot, w, h)
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

    fun bindImageToAction(actionId: String, imageName: String, appendExtraFrame: Boolean = false) {
        _activeMode.value = AutomationMode.MACRO
        updateActiveMacro(persist = true) { macro ->
            macro.updateAnyAction(actionId) { a ->
                when (a) {
                    is ImageAction -> a.copy(imageName = imageName)
                    is TrackObjectAction -> {
                        if (appendExtraFrame && a.imageName.isNotBlank()) {
                            val updatedExtras = (a.extraImageNames + imageName)
                                .map { it.trim() }
                                .filter { it.isNotBlank() && it != a.imageName }
                                .distinct()
                                .take(3)
                            a.copy(extraImageNames = updatedExtras)
                        } else {
                            a.copy(
                                imageName = imageName,
                                extraImageNames = a.extraImageNames.filter { it != imageName }
                            )
                        }
                    }
                    else -> a
                }
            }
        }
        ExecutionLogBus.image(
            if (appendExtraFrame) "Frame pose tambahan '$imageName' ditambahkan ke aksi Kejar Objek."
            else "Gambar '$imageName' dipasang ke aksi deteksi."
        )
    }

    fun bindRegionToAction(actionId: String, region: Region, screenW: Int = 0, screenH: Int = 0) {
        val (sw, sh) = resolveScreenSize(screenW, screenH)
        _activeMode.value = AutomationMode.MACRO
        updateActiveMacro(persist = true) { macro ->
            val base = ResolutionScaler.ensureAuthoringResolution(macro, sw, sh)
            val t = ResolutionScaler.forMacro(base, sw, sh)
            val storedRegion = t.unscaleRegion(region)
            base.updateAnyAction(actionId) { a ->
                when (a) {
                    is TextAction -> a.copy(region = storedRegion)
                    is ImageAction -> a.copy(region = storedRegion)
                    is TrackObjectAction -> a.copy(region = storedRegion)
                    is com.example.data.SelectAccountAction -> a.copy(region = storedRegion)
                    is com.example.data.VariableAction -> a.copy(region = storedRegion)
                    else -> a
                }
            }
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

    private fun unscaleActionForStorage(t: ResolutionScaler.ScreenTransform, action: Action): Action = when (action) {
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

    fun addActionFromOverlay(
        ref: ContainerRef,
        action: Action,
        screenW: Int = 0,
        screenH: Int = 0,
        branchId: String = ""
    ) {
        if (rejectIfActiveMacroLocked()) return
        val (sw, sh) = resolveScreenSize(screenW, screenH)
        _activeMode.value = AutomationMode.MACRO
        updateActiveMacro(persist = true) { macro ->
            val base = ResolutionScaler.ensureAuthoringResolution(macro, sw, sh)
            val t = ResolutionScaler.forMacro(base, sw, sh)
            val storedAction = unscaleActionForStorage(t, action)
            base.updateBranchActions(branchId) { list -> list.insertInto(ref, storedAction) }
        }
    }

    fun moveActionToBranchFromOverlay(actionId: String, targetBranchId: String) {
        if (rejectIfActiveMacroLocked()) return
        updateActiveMacro(persist = true) { macro ->
            macro.moveActionToBranch(actionId, targetBranchId)
        }
    }

    fun addBannedImageToActiveMacro(imageName: String) {
        if (rejectIfActiveMacroLocked()) return
        val clean = imageName.trim()
        if (clean.isBlank()) return
        updateActiveMacro(persist = true) { macro ->
            macro.copy(
                bannedGuardEnabled = true,
                bannedImages = (macro.bannedImages + clean).distinct()
            )
        }
        ExecutionLogBus.image("⛔ Gambar terlarang '$clean' ditambahkan ke Guard Banned Gambar (selalu aktif).")
    }

    fun addStuckActionFromOverlay(ref: ContainerRef, action: Action, screenW: Int = 0, screenH: Int = 0) {
        if (rejectIfActiveMacroLocked()) return
        val (sw, sh) = resolveScreenSize(screenW, screenH)
        _activeMode.value = AutomationMode.MACRO
        updateActiveMacro(persist = true) { macro ->
            val base = ResolutionScaler.ensureAuthoringResolution(macro, sw, sh)
            val t = ResolutionScaler.forMacro(base, sw, sh)
            val storedAction = unscaleActionForStorage(t, action)
            val wasEmpty = base.stuckActions.isEmpty()
            base.copy(
                stuckActions = base.stuckActions.insertInto(ref, storedAction),
                stuckRecoveryMode = if (wasEmpty) StuckRecoveryMode.RUN_WATCHDOG_ACTIONS else base.stuckRecoveryMode,
                autoRecoverStuckSec = if (wasEmpty && base.autoRecoverStuckSec <= 0) 60 else base.autoRecoverStuckSec
            )
        }
    }

    fun addSwitchAccountActionFromOverlay(ref: ContainerRef, action: Action, screenW: Int = 0, screenH: Int = 0) {
        if (rejectIfActiveMacroLocked()) return
        val (sw, sh) = resolveScreenSize(screenW, screenH)
        _activeMode.value = AutomationMode.MACRO
        updateActiveMacro(persist = true) { macro ->
            val base = ResolutionScaler.ensureAuthoringResolution(macro, sw, sh)
            val t = ResolutionScaler.forMacro(base, sw, sh)
            val storedAction = unscaleActionForStorage(t, action)
            base.copy(
                accountSwitch = base.accountSwitch.copy(
                    enabled = true,
                    switchActions = base.accountSwitch.switchActions.insertInto(ref, storedAction)
                )
            )
        }
    }

    /**
     * Updates Auto-Scale settings on the active macro WITHOUT password even when [Macro.isLocked] is true,
     * preserving locked actions and security integrity seal.
     */
    fun updateAutoScaleWithoutUnlock(
        autoScaleResolution: Boolean,
        sourceWidth: Int,
        sourceHeight: Int,
        scaleMode: ResolutionScaleMode
    ): Macro {
        val current = _activeMacro.value
        val updated = MacroSecurity.updateAutoScale(
            macro = current,
            autoScaleResolution = autoScaleResolution,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            scaleMode = scaleMode
        )
        val saved = store?.saveMacro(updated) ?: updated
        _activeMacro.value = saved
        return saved
    }

    fun moveActionFromOverlay(actionId: String, delta: Int) {
        if (rejectIfActiveMacroLocked()) return
        updateActiveMacro(persist = true) { macro ->
            macro.moveAnyAction(actionId, delta)
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
            base.updateAnyAction(actionId) { a ->
                when (a) {
                    is ClickAction -> a.copy(x = ox, y = oy)
                    is LongPressAction -> a.copy(x = ox, y = oy)
                    is SwipeAction -> if (isEndNode) a.copy(x2 = ox, y2 = oy) else a.copy(x1 = ox, y1 = oy)
                    is ZoomAction -> a.copy(cx = ox, cy = oy)
                    is ColorAction -> a.copy(x = ox, y = oy)
                    is TrackObjectAction -> a.copy(targetX = ox, targetY = oy)
                    else -> a
                }
            }
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
            base.updateAnyAction(actionId) { cur ->
                when (cur) {
                    is ClickAction -> cur.copy(x = ox1, y = oy1)
                    is LongPressAction -> cur.copy(x = ox1, y = oy1)
                    is SwipeAction -> cur.copy(x1 = ox1, y1 = oy1, x2 = ox2, y2 = oy2)
                    is ZoomAction -> cur.copy(cx = ox1, cy = oy1)
                    is ColorAction -> cur.copy(x = ox1, y = oy1, color = hexColor ?: cur.color)
                    is TrackObjectAction -> {
                        if (cur.interactionMode == TrackInteractionMode.DRAG_BY_OFFSET) {
                            cur.copy(dragOffsetX = ox2 - ox1, dragOffsetY = oy2 - oy1)
                        } else {
                            cur.copy(targetX = ox1, targetY = oy1)
                        }
                    }
                    else -> cur
                }
            }
        }
    }

    fun updateActionFromOverlay(actionId: String, transform: (Action) -> Action) {
        if (rejectIfActiveMacroLocked()) return
        updateActiveMacro(persist = true) { macro ->
            macro.updateAnyAction(actionId, transform)
        }
    }

    fun removeActionFromOverlay(actionId: String) {
        if (rejectIfActiveMacroLocked()) return
        updateActiveMacro(persist = true) { macro ->
            macro.removeAnyAction(actionId)
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
            if (m.bannedGuardEnabled && m.bannedImages.any { it.isNotBlank() }) return true
            if (m.accountSwitch.enabled && m.accountSwitch.method != com.example.data.AccountSwitchMethod.CLONE_APP && m.accountSwitch.accounts.any { it.enabled }) return true
            for (a in m.allActionsSequence()) {
                if (a is ImageAction || a is TrackObjectAction || a is TextAction || a is ColorAction || a is com.example.data.BranchRouterAction ||
                    (a is com.example.data.VariableAction && a.operation == com.example.data.VariableOperation.READ_OCR_NUMBER) ||
                    (a is com.example.data.SelectAccountAction && a.methodOverride != com.example.data.AccountSwitchMethod.CLONE_APP) ||
                    (a is com.example.data.NotifyAction && a.includeScreenshot)
                ) return true
                if (a is RunMacroAction && a.targetMacroId.isNotBlank()) {
                    val linked = localStore?.getMacroById(a.targetMacroId)
                    val linkedSet = if (linked != null) localStore.getMacroSetById(linked.setId) else null
                    if (linked != null && MacroSecurity.checkLinkedRun(linked, now, linkedSet) == null && check(linked)) {
                        return true
                    }
                } else if (a is FinishMacroAction &&
                    a.mode == FinishMacroMode.SWITCH_TO_SPECIFIC_ACTIVE &&
                    a.targetMacroId.isNotBlank()
                ) {
                    val linked = localStore?.getMacroById(a.targetMacroId)
                    val linkedSet = if (linked != null) localStore.getMacroSetById(linked.setId) else null
                    if (linked != null && MacroSecurity.checkDirectRun(linked, now, linkedSet) == null && check(linked)) {
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
        screenHeight: Int = ScreenCaptureService.captureHeight,
        startInBlackScreen: Boolean = false
    ) {
        stop()
        val effectiveNow = store?.effectiveNowMs() ?: System.currentTimeMillis()
        val parentSet = store?.getMacroSetById(macro.setId)
        MacroSecurity.checkDirectRun(macro, effectiveNow, parentSet)?.let { denial ->
            ExecutionLogBus.error(MacroSecurity.denialMessage(macro, denial, parentSet))
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
        skipAccountRequested.set(false)
        _currentLoop.value = 0
        _actionsExecuted.value = 0
        _activeAccountLabel.value = ""
        _liveVariables.value = emptyMap()
        _humanBreakRemainingSec.value = 0
        _thermalPaused.value = false
        _deviceHealth.value = DeviceHealthReader.read(appCtx)
        if (startInBlackScreen) {
            _afkBlackScreenActive.value = true
        }
        _engineState.value = EngineState.RUNNING
        ScreenCaptureService.highFrequencyCapture = true

        val startedAt = System.currentTimeMillis()
        val counter = AtomicInteger(0)
        val watchdogCount = AtomicInteger(0)
        val bannedCount = AtomicInteger(0)
        val accountSwitchCount = AtomicInteger(0)
        val humanBreakCount = AtomicInteger(0)
        val lastVarsRef = java.util.concurrent.atomic.AtomicReference<Map<String, Double>>(emptyMap())
        val localStore = store

        fun publishLiveStats() {
            _liveSessionStats.value = com.example.data.LiveSessionStats(
                macroName = _activeMacro.value.name.ifBlank { macro.name },
                startedAtMs = startedAt,
                loopsCompleted = _currentLoop.value,
                actionsExecuted = counter.get(),
                watchdogRecoveries = watchdogCount.get(),
                bannedBlocks = bannedCount.get(),
                accountSwitches = accountSwitchCount.get(),
                humanBreaks = humanBreakCount.get()
            )
        }
        publishLiveStats()

        val myRunId = runGeneration.incrementAndGet()
        activeJob = engineScope.launch {
            ExecutionLogBus.info(
                "Memulai '${macro.name}' (${macro.actions.size} aksi, loop=${if (macro.loopCount <= 0) "∞" else macro.loopCount})."
            )
            var lastPeriodicReportAt = startedAt
            val periodicReportJob = launch {
                while (isActive && !stopRequested.get()) {
                    delay(15_000L)
                    val cfg = localStore?.notificationConfig?.value ?: continue
                    val intervalMin = cfg.periodicReportMinutes
                    if (intervalMin <= 0 || !cfg.hasAnyRemoteConfigured()) continue
                    val now = System.currentTimeMillis()
                    if (now - lastPeriodicReportAt >= intervalMin * 60_000L) {
                        lastPeriodicReportAt = now
                        val dur = (now - startedAt).coerceAtLeast(0L)
                        val loops = _currentLoop.value
                        val acts = counter.get()
                        val rateL = com.example.data.FarmingStatsCalculator.formatRate(
                            com.example.data.FarmingStatsCalculator.ratePerHour(loops, dur)
                        )
                        val accLine = _activeAccountLabel.value.takeIf { it.isNotBlank() }?.let { "\nAkun: $it" } ?: ""
                        val msg = "Durasi: ${com.example.data.FarmingStatsCalculator.formatDuration(dur)} • Loop: $loops ($rateL/jam) • Aksi: $acts" +
                            "\nWatchdog: ${watchdogCount.get()} • Banned dicegah: ${bannedCount.get()}$accLine"
                        PhoneAlarmAndNotifyManager.dispatchAsync(
                            context = appCtx,
                            config = cfg,
                            title = "Laporan Berkala — ${_activeMacro.value.name.ifBlank { macro.name }}",
                            message = msg,
                            level = com.example.data.NotifyLevel.INFO,
                            sendRemote = true,
                            includeScreenshot = cfg.attachScreenshotOnEvents,
                            triggerPhoneAlarm = false,
                            captureJpeg = { ScreenCaptureService.captureScaledJpeg() }
                        )
                    }
                }
            }
            val deviceGuardJob = launch {
                while (isActive && !stopRequested.get()) {
                    val snap = DeviceHealthReader.read(appCtx)
                    if (snap != null) {
                        _deviceHealth.value = snap
                        val gCfg = localStore?.afkGuardConfig?.value ?: com.example.data.AfkAndDeviceGuardConfig()
                        val decision = com.example.data.DeviceGuardEvaluator.evaluate(gCfg, snap, _thermalPaused.value)
                        val nCfg = localStore?.notificationConfig?.value ?: com.example.data.NotificationConfig()
                        val macroTitle = _activeMacro.value.name.ifBlank { macro.name }
                        when (decision) {
                            com.example.data.DeviceGuardDecision.STOP_LOW_BATTERY -> {
                                val msg = "Baterai ${snap.batteryPercent}% (batas ${gCfg.minBatteryPercent}%, tidak dicas) — macro dihentikan demi keamanan baterai."
                                ExecutionLogBus.warn("🔋 $msg")
                                if (gCfg.notifyOnGuardTrigger) {
                                    PhoneAlarmAndNotifyManager.dispatchAsync(
                                        context = appCtx,
                                        config = nCfg,
                                        title = "Guard Baterai Lemah — $macroTitle",
                                        message = msg,
                                        level = com.example.data.NotifyLevel.WARNING,
                                        sendRemote = nCfg.hasAnyRemoteConfigured(),
                                        includeScreenshot = false,
                                        triggerPhoneAlarm = true
                                    )
                                }
                                stopRequested.set(true)
                                activeJob?.cancel()
                                break
                            }
                            com.example.data.DeviceGuardDecision.STOP_OVERHEAT -> {
                                val tempTxt = com.example.data.DeviceGuardEvaluator.formatTemp(snap.tempCelsius)
                                val msg = "Suhu HP mencapai $tempTxt (batas ${gCfg.maxTempCelsius}°C) — macro dihentikan otomatis."
                                ExecutionLogBus.warn("🌡 $msg")
                                if (gCfg.notifyOnGuardTrigger) {
                                    PhoneAlarmAndNotifyManager.dispatchAsync(
                                        context = appCtx,
                                        config = nCfg,
                                        title = "Guard Suhu Panas — $macroTitle",
                                        message = msg,
                                        level = com.example.data.NotifyLevel.CRITICAL,
                                        sendRemote = nCfg.hasAnyRemoteConfigured(),
                                        includeScreenshot = false,
                                        triggerPhoneAlarm = true
                                    )
                                }
                                stopRequested.set(true)
                                activeJob?.cancel()
                                break
                            }
                            com.example.data.DeviceGuardDecision.PAUSE_OVERHEAT -> {
                                val tempTxt = com.example.data.DeviceGuardEvaluator.formatTemp(snap.tempCelsius)
                                _thermalPaused.value = true
                                pausedFlag.set(true)
                                _engineState.value = EngineState.PAUSED
                                val msg = "Suhu HP $tempTxt (≥ ${gCfg.maxTempCelsius}°C) — macro dijeda sementara untuk mendinginkan HP."
                                ExecutionLogBus.warn("🌡 $msg")
                                if (gCfg.notifyOnGuardTrigger) {
                                    PhoneAlarmAndNotifyManager.dispatchAsync(
                                        context = appCtx,
                                        config = nCfg,
                                        title = "Jeda Pendinginan HP — $macroTitle",
                                        message = msg,
                                        level = com.example.data.NotifyLevel.WARNING,
                                        sendRemote = nCfg.hasAnyRemoteConfigured(),
                                        includeScreenshot = false,
                                        triggerPhoneAlarm = false
                                    )
                                }
                            }
                            com.example.data.DeviceGuardDecision.RESUME_COOLED -> {
                                val tempTxt = com.example.data.DeviceGuardEvaluator.formatTemp(snap.tempCelsius)
                                _thermalPaused.value = false
                                pausedFlag.set(false)
                                _engineState.value = EngineState.RUNNING
                                ExecutionLogBus.info("❄ Suhu HP turun ke $tempTxt — macro dilanjutkan otomatis.")
                            }
                            com.example.data.DeviceGuardDecision.STAY_THERMAL_PAUSED,
                            com.example.data.DeviceGuardDecision.OK -> Unit
                        }
                    }
                    delay(10_000L)
                }
            }
            val autoBlackScreenJob = launch {
                val sec = localStore?.afkGuardConfig?.value?.autoBlackScreenAfterSec ?: 0
                if (sec > 0 && !_afkBlackScreenActive.value) {
                    cooperativeDelay(sec.coerceIn(5, 3600) * 1000L)
                    if (isActive && !stopRequested.get() && _engineState.value == EngineState.RUNNING) {
                        _afkBlackScreenActive.value = true
                        ExecutionLogBus.info("🌙 Layar Hitam AFK otomatis aktif setelah ${sec}d.")
                    }
                }
            }
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

                override suspend fun swipeCurved(
                    x1: Int,
                    y1: Int,
                    cx: Int,
                    cy: Int,
                    x2: Int,
                    y2: Int,
                    durationMs: Long
                ): Boolean = MacroAccessibilityService.dispatchCurvedSwipe(
                    x1.toFloat(), y1.toFloat(), cx.toFloat(), cy.toFloat(), x2.toFloat(), y2.toFloat(), durationMs
                )

                override suspend fun zoom(cx: Int, cy: Int, zoomIn: Boolean, distancePx: Int, durationMs: Long): Boolean =
                    MacroAccessibilityService.dispatchZoom(cx.toFloat(), cy.toFloat(), zoomIn, distancePx, durationMs)

                override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float): Boolean =
                    MacroAccessibilityService.dispatchRecordedGesture(gesture, speed)

                override suspend fun exitApp(
                    mode: ExitAppMode,
                    packageName: String,
                    recentsSwipeDirection: RecentsSwipeDirection,
                    forceStopBackground: Boolean
                ): Boolean {
                    val cleanPkg = packageName.trim()
                    val effectivePkg = cleanPkg.ifBlank { MacroAccessibilityService.lastForegroundPackage }
                    val resolvedAppLabel = if (effectivePkg.isNotBlank()) {
                        runCatching {
                            val pm = appCtx?.packageManager
                            val ai = pm?.getApplicationInfo(effectivePkg, 0)
                            if (ai != null) pm.getApplicationLabel(ai).toString() else ""
                        }.getOrDefault("")
                    } else ""

                    val ok = when (mode) {
                        ExitAppMode.HOME_ONLY -> {
                            val h = MacroAccessibilityService.dispatchGlobalHome()
                            cooperativeDelay(350L)
                            h
                        }
                        ExitAppMode.BACK_THEN_HOME -> {
                            MacroAccessibilityService.dispatchGlobalBack()
                            cooperativeDelay(250L)
                            MacroAccessibilityService.dispatchGlobalBack()
                            cooperativeDelay(250L)
                            val h = MacroAccessibilityService.dispatchGlobalHome()
                            cooperativeDelay(350L)
                            h
                        }
                        ExitAppMode.FORCE_STOP_APP -> {
                            val stopped = if (effectivePkg.isNotBlank()) {
                                MacroAccessibilityService.forceStopPackageViaSettings(effectivePkg)
                            } else {
                                ExecutionLogBus.warn("Paksa Berhenti: belum ada aplikasi target terdeteksi — kembali ke Home.")
                                false
                            }
                            cooperativeDelay(250L)
                            val h = MacroAccessibilityService.dispatchGlobalHome()
                            cooperativeDelay(350L)
                            stopped || h
                        }
                        ExitAppMode.CLOSE_RECENT_AND_HOME -> {
                            val (beforeRot, beforeW, beforeH) = readLiveDisplayState(screenWidth, screenHeight)
                            val openedRecents = MacroAccessibilityService.dispatchGlobalRecents()
                            if (openedRecents) {
                                cooperativeDelay(900L)
                                val dismissedByNode = if (resolvedAppLabel.isNotBlank()) {
                                    MacroAccessibilityService.tryDismissRecentCard(resolvedAppLabel)
                                } else false
                                if (dismissedByNode) {
                                    ExecutionLogBus.action("Tutup Recent langsung via kartu '$resolvedAppLabel'.")
                                } else {
                                    val (afterRot, afterW, afterH) = readLiveDisplayState(screenWidth, screenHeight)
                                    val swipe = RecentsDismissGeometry.computeSwipe(
                                        beforeRotation = beforeRot,
                                        beforeWidth = beforeW,
                                        beforeHeight = beforeH,
                                        afterRotation = afterRot,
                                        afterWidth = afterW,
                                        afterHeight = afterH,
                                        sdkInt = Build.VERSION.SDK_INT,
                                        direction = recentsSwipeDirection
                                    )
                                    ExecutionLogBus.action(
                                        "Tutup Recent (${swipe.description}, rot ${RecentsDismissGeometry.rotationDegrees(beforeRot)}°→${RecentsDismissGeometry.rotationDegrees(afterRot)}°, ${afterW}×${afterH}) fling (${swipe.x1.toInt()},${swipe.y1.toInt()})→(${swipe.x2.toInt()},${swipe.y2.toInt()})"
                                    )
                                    // Fast 145ms fling across 66% of screen crosses Android's fling-to-dismiss velocity threshold
                                    MacroAccessibilityService.dispatchSwipe(swipe.x1, swipe.y1, swipe.x2, swipe.y2, 145L)
                                }
                                cooperativeDelay(500L)
                            }
                            if (forceStopBackground && effectivePkg.isNotBlank()) {
                                MacroAccessibilityService.forceStopPackageViaSettings(effectivePkg)
                                cooperativeDelay(220L)
                            }
                            val h = MacroAccessibilityService.dispatchGlobalHome()
                            cooperativeDelay(350L)
                            openedRecents || h
                        }
                    }
                    if (effectivePkg.isNotBlank()) {
                        runCatching {
                            val am = appCtx?.getSystemService(ActivityManager::class.java)
                            @Suppress("DEPRECATION")
                            am?.killBackgroundProcesses(effectivePkg)
                        }
                    }
                    return ok
                }

                override suspend fun launchApp(packageName: String, freshStart: Boolean): Boolean {
                    val cleanPkg = packageName.trim()
                    if (cleanPkg.isBlank()) return false
                    val ctx = appCtx ?: return false
                    val launchIntent = runCatching {
                        ctx.packageManager.getLaunchIntentForPackage(cleanPkg)
                    }.getOrNull()
                    if (launchIntent == null) {
                        ExecutionLogBus.error("Masuk Aplikasi gagal: paket '$cleanPkg' tidak ditemukan di perangkat.")
                        return false
                    }
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    if (freshStart) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    val startedByAcc = MacroAccessibilityService.launchActivityIntent(launchIntent)
                    if (startedByAcc) return true
                    return runCatching {
                        ctx.startActivity(launchIntent)
                        true
                    }.onFailure { err ->
                        ExecutionLogBus.error("Gagal membuka '$cleanPkg': ${err.message}")
                    }.getOrDefault(false)
                }

                override fun setTrackingBoost(enabled: Boolean) {
                    ScreenCaptureService.trackingCaptureMode = enabled
                }

                override fun captureFrame(): Frame? = ScreenCaptureService.captureFrame()
                override fun samplePixelArgb(x: Int, y: Int): Int? = ScreenCaptureService.samplePixelArgb(x, y)
                override suspend fun recognizeText(region: Region?): List<OcrBlock> =
                    recognizeText(region, enhanced = false)

                override suspend fun recognizeText(region: Region?, enhanced: Boolean): List<OcrBlock> {
                    val bmp = ScreenCaptureService.captureBitmapCopy() ?: return emptyList()
                    return try {
                        val liveW = screenWidth.coerceAtLeast(1)
                        val liveH = screenHeight.coerceAtLeast(1)
                        val needsAlign = region != null && (bmp.width != liveW || bmp.height != liveH)
                        val bmpRegion = if (region != null && needsAlign) {
                            val sx = bmp.width.toDouble() / liveW.toDouble()
                            val sy = bmp.height.toDouble() / liveH.toDouble()
                            Region(
                                left = (region.left * sx).toInt().coerceIn(0, (bmp.width - 1).coerceAtLeast(0)),
                                top = (region.top * sy).toInt().coerceIn(0, (bmp.height - 1).coerceAtLeast(0)),
                                right = (region.right * sx).toInt().coerceIn(1, bmp.width),
                                bottom = (region.bottom * sy).toInt().coerceIn(1, bmp.height)
                            )
                        } else {
                            region
                        }
                        val rawBlocks = ScreenTextRecognizer.recognize(bmp, bmpRegion, enhanced = enhanced)
                        if (needsAlign && rawBlocks.isNotEmpty()) {
                            val invX = liveW.toDouble() / bmp.width.coerceAtLeast(1).toDouble()
                            val invY = liveH.toDouble() / bmp.height.coerceAtLeast(1).toDouble()
                            rawBlocks.map { b ->
                                OcrBlock(
                                    text = b.text,
                                    left = (b.left * invX).toInt(),
                                    top = (b.top * invY).toInt(),
                                    right = (b.right * invX).toInt(),
                                    bottom = (b.bottom * invY).toInt()
                                )
                            }
                        } else {
                            rawBlocks
                        }
                    } finally {
                        bmp.recycle()
                    }
                }
                override fun loadTemplate(name: String): PreparedTemplate? = localStore?.loadTemplate(name)
                override fun loadMacro(id: String): Macro? = localStore?.getMacroById(id)
                override fun loadMacroSet(id: String): com.example.data.MacroSet? = localStore?.getMacroSetById(id)
                override fun onActiveMacroSwitched(target: Macro) {
                    selectMacro(target)
                    _currentLoop.value = 0
                }
                override fun onActiveAccountChanged(accountIndex: Int, totalEnabled: Int, account: com.example.data.AccountProfile?) {
                    _activeAccountLabel.value = if (account != null && totalEnabled > 0) {
                        "👤 $accountIndex/$totalEnabled • ${account.label.ifBlank { "Akun $accountIndex" }}"
                    } else ""
                }
                override fun onAccountStateUpdated(
                    macroId: String,
                    lastAccountId: String,
                    completedAccountId: String,
                    lastUsedAtMs: Long
                ) {
                    val s = localStore ?: return
                    val cur = s.getMacroById(macroId) ?: return
                    val nextAccounts = if (completedAccountId.isNotBlank()) {
                        cur.accountSwitch.accounts.map { acc ->
                            if (acc.id == completedAccountId) acc.copy(lastUsedAt = lastUsedAtMs) else acc
                        }
                    } else cur.accountSwitch.accounts
                    val updated = cur.copy(
                        accountSwitch = cur.accountSwitch.copy(
                            lastAccountId = lastAccountId,
                            accounts = nextAccounts
                        )
                    )
                    s.saveMacro(updated)
                    if (_activeMacro.value.id == updated.id) {
                        _activeMacro.value = updated
                    }
                }
                override fun consumeManualSkipAccountRequest(): Boolean = skipAccountRequested.getAndSet(false)
                override fun isVisualDebugEnabled(): Boolean = _visualDebugEnabled.value
                override fun onVisualDebug(event: VisualDebugEvent) {
                    _debugEvents.tryEmit(event)
                }
                override fun onVariablesChanged(variables: Map<String, Double>) {
                    lastVarsRef.set(variables)
                    _liveVariables.value = variables
                }
                override fun onHumanBreakChanged(remainingSec: Int) {
                    _humanBreakRemainingSec.value = remainingSec.coerceAtLeast(0)
                }
                override fun onWatchdogTriggered(modeLabel: String) {
                    val count = watchdogCount.incrementAndGet()
                    publishLiveStats()
                    val cfg = localStore?.notificationConfig?.value ?: return
                    if (cfg.notifyOnWatchdog && cfg.hasAnyRemoteConfigured()) {
                        PhoneAlarmAndNotifyManager.dispatchAsync(
                            context = appCtx,
                            config = cfg,
                            title = "Watchdog Anti-Macet — ${_activeMacro.value.name.ifBlank { macro.name }}",
                            message = "Recovery #$count ($modeLabel) pada loop #${_currentLoop.value}.",
                            level = com.example.data.NotifyLevel.WARNING,
                            sendRemote = true,
                            includeScreenshot = cfg.attachScreenshotOnEvents,
                            triggerPhoneAlarm = false,
                            captureJpeg = { ScreenCaptureService.captureScaledJpeg() }
                        )
                    }
                }
                override fun onBannedBlocked(imageName: String) {
                    bannedCount.incrementAndGet()
                    publishLiveStats()
                }
                override fun onAccountSwitched(accountLabel: String) {
                    val count = accountSwitchCount.incrementAndGet()
                    publishLiveStats()
                    val cfg = localStore?.notificationConfig?.value ?: return
                    if (cfg.notifyOnAccountSwitch && cfg.hasAnyRemoteConfigured()) {
                        PhoneAlarmAndNotifyManager.dispatchAsync(
                            context = appCtx,
                            config = cfg,
                            title = "Ganti Akun — ${_activeMacro.value.name.ifBlank { macro.name }}",
                            message = "Beralih ke akun '$accountLabel' (perpindahan #$count, loop #${_currentLoop.value}).",
                            level = com.example.data.NotifyLevel.INFO,
                            sendRemote = true,
                            includeScreenshot = false,
                            triggerPhoneAlarm = false
                        )
                    }
                }
                override fun onHumanBreakStarted(durationSec: Int) {
                    humanBreakCount.incrementAndGet()
                    publishLiveStats()
                }
                override suspend fun sendNotification(
                    title: String,
                    message: String,
                    level: com.example.data.NotifyLevel,
                    sendRemote: Boolean,
                    includeScreenshot: Boolean,
                    triggerPhoneAlarm: Boolean
                ) {
                    val cfg = localStore?.notificationConfig?.value ?: com.example.data.NotificationConfig()
                    PhoneAlarmAndNotifyManager.dispatchAsync(
                        context = appCtx,
                        config = cfg,
                        title = title,
                        message = message,
                        level = level,
                        sendRemote = sendRemote,
                        includeScreenshot = includeScreenshot,
                        triggerPhoneAlarm = triggerPhoneAlarm,
                        captureJpeg = { ScreenCaptureService.captureScaledJpeg() }
                    )
                }
                override fun onMatchFound(match: MatchResult, label: String) {
                    if (!_visualDebugEnabled.value && localStore?.settings?.value?.showDebugMatchBox != false) {
                        flashMatchHighlight(match, label)
                    }
                }
                override fun onLoopChanged(loop: Int) {
                    _currentLoop.value = loop
                    publishLiveStats()
                }
                override fun onActionExecuted() {
                    _actionsExecuted.value = counter.incrementAndGet()
                    publishLiveStats()
                }
                override fun resolveRemoteActionControl(
                    macro: Macro,
                    actionId: String
                ): com.example.data.ActionRemoteControl? =
                    localStore?.getMacroById(macro.id)?.actionRemoteControls?.get(actionId)
                        ?: macro.actionRemoteControls[actionId]
                override fun onOneShotRemoteActionConsumed(macroId: String, actionId: String) {
                    val s = localStore ?: return
                    val cur = s.getMacroById(macroId) ?: return
                    val updated = MacroSecurity.setRemoteActionActive(cur, actionId, false)
                    if (updated !== cur) {
                        s.saveMacro(updated)
                        if (_activeMacro.value.id == updated.id) _activeMacro.value = updated
                    }
                }
                override fun log(level: LogLevel, message: String) = ExecutionLogBus.log(level, message)
                override fun nowMs(): Long = localStore?.effectiveNowMs() ?: System.currentTimeMillis()
            }

            var endReason = "Selesai"
            var errorDetail = ""
            try {
                // Brief settle delay so releasing the floating Play button never cancels action #1
                cooperativeDelay(140L)
                MacroRunner(runtime).run(macro)
                if (stopRequested.get()) {
                    endReason = "Dihentikan"
                }
            } catch (_: CancellationException) {
                endReason = "Dihentikan"
                ExecutionLogBus.info("Macro dihentikan.")
            } catch (t: Throwable) {
                endReason = "Error"
                errorDetail = t.message ?: t::class.java.simpleName
                if (runGeneration.get() == myRunId) {
                    _engineState.value = EngineState.ERROR
                }
                ExecutionLogBus.error("Error: $errorDetail")
            } finally {
                periodicReportJob.cancel()
                deviceGuardJob.cancel()
                autoBlackScreenJob.cancel()
                localStore?.flushClockWatermark()
                val endedAt = System.currentTimeMillis()
                val elapsed = (endedAt - startedAt).coerceAtLeast(0L)
                val finalLoops = _currentLoop.value
                val finalActions = counter.get()
                val finalMacroName = _activeMacro.value.name.ifBlank { macro.name }
                if (elapsed >= 500L || finalActions > 0 || finalLoops > 0 || endReason == "Error") {
                    localStore?.recordFarmingSession(
                        com.example.data.FarmingSessionRecord(
                            macroId = macro.id,
                            macroName = finalMacroName,
                            startedAtMs = startedAt,
                            endedAtMs = endedAt,
                            durationMs = elapsed,
                            loopsCompleted = finalLoops,
                            actionsExecuted = finalActions,
                            watchdogRecoveries = watchdogCount.get(),
                            bannedBlocks = bannedCount.get(),
                            accountSwitches = accountSwitchCount.get(),
                            humanBreaks = humanBreakCount.get(),
                            endReason = endReason,
                            finalVariables = lastVarsRef.get()
                        )
                    )
                }
                val cfg = localStore?.notificationConfig?.value
                if (cfg != null) {
                    val durTxt = com.example.data.FarmingStatsCalculator.formatDuration(elapsed)
                    if (endReason == "Error" && (cfg.notifyOnError || cfg.alarmOnError)) {
                        PhoneAlarmAndNotifyManager.dispatchAsync(
                            context = appCtx,
                            config = cfg,
                            title = "Macro Error — $finalMacroName",
                            message = "$errorDetail\nDurasi: $durTxt • Loop: $finalLoops • Aksi: $finalActions",
                            level = com.example.data.NotifyLevel.CRITICAL,
                            sendRemote = cfg.notifyOnError,
                            includeScreenshot = cfg.attachScreenshotOnEvents,
                            triggerPhoneAlarm = cfg.alarmOnError,
                            captureJpeg = { ScreenCaptureService.captureScaledJpeg() }
                        )
                    } else if (endReason == "Selesai" && (cfg.notifyOnComplete || cfg.alarmOnComplete)) {
                        PhoneAlarmAndNotifyManager.dispatchAsync(
                            context = appCtx,
                            config = cfg,
                            title = "Macro Selesai — $finalMacroName",
                            message = "Durasi: $durTxt • Loop: $finalLoops • Aksi: $finalActions" +
                                "\nWatchdog: ${watchdogCount.get()} • Banned dicegah: ${bannedCount.get()}",
                            level = com.example.data.NotifyLevel.SUCCESS,
                            sendRemote = cfg.notifyOnComplete,
                            includeScreenshot = cfg.attachScreenshotOnEvents,
                            triggerPhoneAlarm = cfg.alarmOnComplete,
                            captureJpeg = { ScreenCaptureService.captureScaledJpeg() }
                        )
                    }
                }
                if (runGeneration.get() == myRunId) {
                    ScreenCaptureService.trackingCaptureMode = false
                    ScreenCaptureService.highFrequencyCapture = false
                    _liveVariables.value = emptyMap()
                    _humanBreakRemainingSec.value = 0
                    _liveSessionStats.value = null
                    _thermalPaused.value = false
                    _afkBlackScreenActive.value = false
                    if (_engineState.value != EngineState.ERROR) {
                        _engineState.value = EngineState.IDLE
                    }
                    pausedFlag.set(false)
                }
                ExecutionLogBus.info("Selesai ($finalActions aksi dalam ${elapsed}ms).")
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
            _thermalPaused.value = false
            _engineState.value = EngineState.RUNNING
            ExecutionLogBus.info("Dilanjutkan.")
        }
    }

    fun stop() {
        stopRequested.set(true)
        pausedFlag.set(false)
        skipAccountRequested.set(false)
        _activeAccountLabel.value = ""
        _liveVariables.value = emptyMap()
        _humanBreakRemainingSec.value = 0
        _liveSessionStats.value = null
        _thermalPaused.value = false
        _afkBlackScreenActive.value = false
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
