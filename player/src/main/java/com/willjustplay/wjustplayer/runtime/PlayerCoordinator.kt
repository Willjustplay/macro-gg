package com.willjustplay.wjustplayer.runtime

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import com.example.automation.LogLevel
import com.example.automation.MacroRunner
import com.example.automation.MacroRuntime
import com.example.automation.RecentsDismissGeometry
import com.example.automation.RemoteBotApiClient
import com.example.automation.RemoteBotListener
import com.example.automation.RemoteBotListenerStatus
import com.example.automation.RemoteControlHost
import com.example.automation.RemoteHostOpResult
import com.example.data.AccountProfile
import com.example.data.AccountSwitchMethod
import com.example.data.ActionRemoteControl
import com.example.data.ColorAction
import com.example.data.ExitAppMode
import com.example.data.ImageAction
import com.example.data.Macro
import com.example.data.MacroSecurity
import com.example.data.MacroSet
import com.example.data.RecentsSwipeDirection
import com.example.data.RecordedGesture
import com.example.data.Region
import com.example.data.RemoteActionMode
import com.example.data.SelectAccountAction
import com.example.data.TextAction
import com.example.data.TrackObjectAction
import com.example.data.allActionsSequence
import com.example.vision.Frame
import com.example.vision.MatchResult
import com.example.vision.OcrBlock
import com.example.vision.PreparedTemplate
import com.willjustplay.wjustplayer.data.PlayerAccountStore
import com.willjustplay.wjustplayer.data.PlayerStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class PlayerEngineState { IDLE, RUNNING, PAUSED, ERROR }

/**
 * Play-Only execution engine coordinator for Wjust Player.
 * Reuses `:core`'s [MacroRunner] with full action fidelity while keeping user interaction strictly
 * Play / Pause / Stop and emitting privacy-filtered Safe Logs via [PlayerSafeLogBus].
 */
object PlayerCoordinator {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val botScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }
    )

    @Volatile private var store: PlayerStore? = null
    @Volatile private var accountStore: PlayerAccountStore? = null
    @Volatile private var notifyStore: com.willjustplay.wjustplayer.data.PlayerNotificationStore? = null
    @Volatile private var appCtx: Context? = null
    @Volatile private var activeJob: Job? = null

    private val runGeneration = AtomicLong(0L)
    private val stopRequested = AtomicBoolean(false)
    private val pausedFlag = AtomicBoolean(false)
    private val skipAccountRequested = AtomicBoolean(false)

    private val _engineState = MutableStateFlow(PlayerEngineState.IDLE)
    val engineState: StateFlow<PlayerEngineState> = _engineState.asStateFlow()

    private val _isOverlayActive = MutableStateFlow(false)
    val isOverlayActive: StateFlow<Boolean> = _isOverlayActive.asStateFlow()

    private val _activeMacro = MutableStateFlow<Macro?>(null)
    val activeMacro: StateFlow<Macro?> = _activeMacro.asStateFlow()

    private val _currentLoop = MutableStateFlow(0)
    val currentLoop: StateFlow<Int> = _currentLoop.asStateFlow()

    private val _actionsExecuted = MutableStateFlow(0)
    val actionsExecuted: StateFlow<Int> = _actionsExecuted.asStateFlow()

    private val _activeAccountLabel = MutableStateFlow("")
    val activeAccountLabel: StateFlow<String> = _activeAccountLabel.asStateFlow()

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
            _deviceHealth.value = PlayerDeviceHealthReader.read(appCtx)
            PlayerSafeLogBus.info("Mode Layar Hitam AFK aktif.")
        } else {
            PlayerSafeLogBus.info("Mode Layar Hitam AFK dimatikan.")
        }
        return next
    }

    fun setAfkBlackScreenActive(active: Boolean) {
        if (_afkBlackScreenActive.value == active) return
        _afkBlackScreenActive.value = active
        if (active) _deviceHealth.value = PlayerDeviceHealthReader.read(appCtx)
    }

    fun refreshDeviceHealth(): com.example.data.DeviceHealthSnapshot? {
        val snap = PlayerDeviceHealthReader.read(appCtx)
        _deviceHealth.value = snap
        return snap
    }

    fun runScheduledMacro(schedule: com.example.data.MacroSchedule) {
        val localStore = store ?: return
        val target = localStore.getMacroById(schedule.macroId)
        val schedTitle = schedule.label.ifBlank { schedule.macroName.ifBlank { "Jadwal" } }
        if (target == null) {
            PlayerSafeLogBus.error("Jadwal '$schedTitle' gagal: macro .wjm tidak ditemukan.")
            return
        }
        val parentSet = localStore.getMacroSetById(target.setId)
        MacroSecurity.checkDirectRun(target, localStore.effectiveNowMs(), parentSet)?.let { denial ->
            PlayerSafeLogBus.error(MacroSecurity.denialMessage(target, denial, parentSet))
            return
        }
        if (!PlayerAccessibilityService.isConnected.value) {
            PlayerSafeLogBus.error("Jadwal '$schedTitle' gagal: Aksesibilitas Wjust Player belum aktif.")
            val cfg = notifyStore?.notificationConfig?.value
            if (cfg != null && (cfg.notifyOnError || cfg.alarmOnError)) {
                PlayerNotifyManager.dispatchAsync(
                    context = appCtx,
                    config = cfg,
                    title = "Jadwal Gagal — $schedTitle",
                    message = "Aksesibilitas Wjust Player belum aktif saat jadwal dimulai.",
                    level = com.example.data.NotifyLevel.WARNING,
                    sendRemote = cfg.notifyOnError,
                    includeScreenshot = false,
                    triggerPhoneAlarm = cfg.alarmOnError
                )
            }
            return
        }

        PlayerSafeLogBus.info("Memulai jadwal otomatis '$schedTitle'...")
        engineScope.launch {
            val pkg = schedule.launchAppPackage.trim()
            if (pkg.isNotBlank()) {
                val ctx = appCtx
                val launchIntent = runCatching { ctx?.packageManager?.getLaunchIntentForPackage(pkg) }.getOrNull()
                if (launchIntent != null && ctx != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    val launched = PlayerAccessibilityService.launchActivityIntent(launchIntent) ||
                        runCatching { ctx.startActivity(launchIntent); true }.getOrDefault(false)
                    if (launched) {
                        delay(schedule.launchWaitSec.coerceIn(1, 60) * 1000L)
                    }
                }
            }
            val effectiveMacro = if (schedule.stopAfterMinutes > 0) {
                target.copy(maxDurationMin = schedule.stopAfterMinutes)
            } else target
            val (sw, sh) = liveScreenSize(1080, 2400)
            runMacro(effectiveMacro, sw, sh, startInBlackScreen = schedule.enableBlackScreenOnStart)
        }
    }

    private val remoteBotListener = RemoteBotListener { msg -> PlayerSafeLogBus.info(msg) }
    val remoteBotStatus: StateFlow<RemoteBotListenerStatus> = remoteBotListener.status

    fun syncRemoteBotListener() {
        val cfg = notifyStore?.notificationConfig?.value ?: com.example.data.NotificationConfig()
        remoteBotListener.sync(
            scope = botScope,
            config = cfg,
            allowed = true,
            hostProvider = { buildRemoteControlHost() }
        )
    }

    suspend fun sendRemoteBotPanelNow(config: com.example.data.NotificationConfig): RemoteHostOpResult =
        withContext(Dispatchers.IO) {
            RemoteBotApiClient.sendPanelNow(config, buildRemoteControlHost())
        }

    private fun buildRemoteControlHost(): RemoteControlHost = object : RemoteControlHost {
        override val appDisplayName: String = "Wjust Player"
        override val deviceTag: String
            get() = notifyStore?.notificationConfig?.value?.remoteBot?.deviceTag.orEmpty()

        override fun listMacros(): List<Macro> = store?.macros?.value.orEmpty()

        override fun runningMacroId(): String? =
            if (_engineState.value == PlayerEngineState.RUNNING || _engineState.value == PlayerEngineState.PAUSED) {
                _activeMacro.value?.id
            } else null

        override fun liveStats(): com.example.data.LiveSessionStats? = _liveSessionStats.value

        override fun deviceHealth(): com.example.data.DeviceHealthSnapshot? = refreshDeviceHealth()

        override fun isAfkBlackScreenActive(): Boolean = _afkBlackScreenActive.value

        override fun startActiveMacro(macro: Macro): RemoteHostOpResult {
            val s = store ?: return RemoteHostOpResult(false, "Penyimpanan Player belum siap.")
            val latest = s.getMacroById(macro.id) ?: macro
            if (latest.isPassive) {
                return RemoteHostOpResult(false, "Macro '${latest.name}' adalah Sub-Macro Pasif.")
            }
            if ((_engineState.value == PlayerEngineState.RUNNING || _engineState.value == PlayerEngineState.PAUSED) &&
                _activeMacro.value?.id == latest.id
            ) {
                return RemoteHostOpResult(true, "Macro Aktif '${latest.name}' sudah berjalan (ON).")
            }
            val parentSet = if (latest.setId.isNotBlank()) s.getMacroSetById(latest.setId) else null
            MacroSecurity.checkDirectRun(latest, s.effectiveNowMs(), parentSet)?.let { denial ->
                return RemoteHostOpResult(false, MacroSecurity.denialMessage(latest, denial, parentSet))
            }
            if (!PlayerAccessibilityService.isConnected.value) {
                return RemoteHostOpResult(false, "Aksesibilitas Wjust Player belum aktif di HP.")
            }
            val (sw, sh) = liveScreenSize(1080, 2400)
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
            val s = store ?: return RemoteHostOpResult(false, "Penyimpanan Player belum siap.")
            val updated = s.setPassiveEnabled(macro.id, enabled)
                ?: return RemoteHostOpResult(false, "Gagal mengubah Sub-Macro Pasif '${macro.name}'.")
            if (_activeMacro.value?.id == updated.id) _activeMacro.value = updated
            PlayerSafeLogBus.info("🤖 Bot mengubah Sub-Macro '${updated.name}' → ${if (enabled) "ON" else "OFF"}.")
            return RemoteHostOpResult(
                true,
                "Sub-Macro Pasif '${updated.name}' (`${updated.remoteId}`) → ${if (enabled) "ON (Dapat Ditarik)" else "OFF (Dilewati)"}."
            )
        }

        override fun setActionRemoteActive(macro: Macro, actionId: String, active: Boolean): RemoteHostOpResult {
            val s = store ?: return RemoteHostOpResult(false, "Penyimpanan Player belum siap.")
            val latest = s.getMacroById(macro.id) ?: macro
            val ctrl = latest.actionRemoteControls[actionId]
                ?: return RemoteHostOpResult(false, "Pengaturan bot aksi tidak ditemukan.")
            val updated = s.setRemoteActionActive(latest.id, actionId, active)
                ?: return RemoteHostOpResult(false, "Aksi ini tidak dapat diubah via bot.")
            if (_activeMacro.value?.id == updated.id) _activeMacro.value = updated
            val label = ctrl.displayName(ctrl.remoteId)
            val statusTxt = when {
                ctrl.mode == RemoteActionMode.ONE_SHOT && active -> "SIAP DIPICU 1× (otomatis kembali OFF)"
                ctrl.mode == RemoteActionMode.ONE_SHOT -> "OFF (Dibatalkan)"
                active -> "ON (Aktif)"
                else -> "OFF (Pasif / Dilewati)"
            }
            PlayerSafeLogBus.info("🤖 Bot mengubah aksi '$label' → $statusTxt.")
            return RemoteHostOpResult(true, "Aksi '$label' (`${ctrl.remoteId}`) → $statusTxt.")
        }

        override fun setAfkBlackScreen(enabled: Boolean): RemoteHostOpResult {
            setAfkBlackScreenActive(enabled)
            return RemoteHostOpResult(
                true,
                if (enabled) "Mode Layar Hitam AFK dinyalakan (ON)." else "Mode Layar Hitam AFK dimatikan (OFF)."
            )
        }

        override fun captureScreenshotJpeg(): ByteArray? {
            if (!PlayerScreenCaptureService.isCapturing.value) return null
            val first = PlayerScreenCaptureService.captureScaledJpeg(maxDimPx = 960, quality = 70)
            if (first != null && first.isNotEmpty()) return first
            runCatching { Thread.sleep(120L) }
            return if (PlayerScreenCaptureService.isCapturing.value) {
                PlayerScreenCaptureService.captureScaledJpeg(maxDimPx = 960, quality = 70)
            } else null
        }
    }

    fun attach(context: Context) {
        if (appCtx == null) appCtx = context.applicationContext
        if (accountStore == null) {
            accountStore = PlayerAccountStore.get(context)
        }
        if (notifyStore == null) {
            notifyStore = com.willjustplay.wjustplayer.data.PlayerNotificationStore.get(context)
        }
        if (store == null) {
            val s = PlayerStore.get(context)
            store = s
            if (_activeMacro.value == null) {
                _activeMacro.value = s.macros.value.firstOrNull { !it.isPassive }
            }
        }
        _deviceHealth.value = PlayerDeviceHealthReader.read(appCtx)
        PlayerSchedulerManager.syncAlarms(context)
        syncRemoteBotListener()
    }

    fun setOverlayActive(active: Boolean) {
        _isOverlayActive.value = active
    }

    fun selectMacro(macro: Macro) {
        _activeMacro.value = macro
    }

    fun requestSkipAccount() {
        if (_engineState.value == PlayerEngineState.RUNNING || _engineState.value == PlayerEngineState.PAUSED) {
            skipAccountRequested.set(true)
            PlayerSafeLogBus.info("Permintaan pindah akun berikutnya diterima...")
        }
    }

    private fun mergeLocalAccounts(macro: Macro): Macro {
        if (!macro.accountSwitch.enabled) return macro
        val local = accountStore?.getFor(macro) ?: return macro
        if (local.accounts.isEmpty()) return macro
        return macro.copy(
            accountSwitch = macro.accountSwitch.copy(
                accounts = local.accounts,
                lastAccountId = local.lastAccountId
            )
        )
    }

    fun macroNeedsScreenCapture(macro: Macro): Boolean =
        (macro.bannedGuardEnabled && macro.bannedImages.any { it.isNotBlank() }) ||
            (macro.accountSwitch.enabled && macro.accountSwitch.method != AccountSwitchMethod.CLONE_APP) ||
            macro.allActionsSequence().any {
                it is ImageAction ||
                    it is TrackObjectAction ||
                    it is TextAction ||
                    it is ColorAction ||
                    it is com.example.data.BranchRouterAction ||
                    (it is com.example.data.VariableAction && it.operation == com.example.data.VariableOperation.READ_OCR_NUMBER) ||
                    (it is SelectAccountAction && (it.methodOverride ?: macro.accountSwitch.method) != AccountSwitchMethod.CLONE_APP) ||
                    (it is com.example.data.NotifyAction && it.includeScreenshot)
            }

    fun togglePlayPause(screenW: Int = 1080, screenH: Int = 2400) {
        when (_engineState.value) {
            PlayerEngineState.RUNNING -> pause()
            PlayerEngineState.PAUSED -> resume()
            PlayerEngineState.IDLE, PlayerEngineState.ERROR -> {
                val target = _activeMacro.value?.let { cur ->
                    store?.getMacroById(cur.id) ?: cur
                } ?: store?.macros?.value?.firstOrNull { !it.isPassive }
                if (target == null) {
                    PlayerSafeLogBus.warn("Belum ada macro .wjm yang dipilih.")
                    return
                }
                runMacro(target, screenW, screenH)
            }
        }
    }

    fun pause() {
        if (_engineState.value == PlayerEngineState.RUNNING) {
            pausedFlag.set(true)
            _engineState.value = PlayerEngineState.PAUSED
            PlayerSafeLogBus.info("Dijeda.")
        }
    }

    fun resume() {
        if (_engineState.value == PlayerEngineState.PAUSED) {
            pausedFlag.set(false)
            _thermalPaused.value = false
            _engineState.value = PlayerEngineState.RUNNING
            PlayerSafeLogBus.info("Dilanjutkan.")
        }
    }

    fun stop() {
        stopRequested.set(true)
        pausedFlag.set(false)
        skipAccountRequested.set(false)
        activeJob?.cancel()
        activeJob = null
        PlayerScreenCaptureService.trackingCaptureMode = false
        PlayerScreenCaptureService.highFrequencyCapture = false
        _activeAccountLabel.value = ""
        _humanBreakRemainingSec.value = 0
        _liveSessionStats.value = null
        _thermalPaused.value = false
        _afkBlackScreenActive.value = false
        _engineState.value = PlayerEngineState.IDLE
    }

    fun runMacro(
        macro: Macro,
        fallbackW: Int = 1080,
        fallbackH: Int = 2400,
        startInBlackScreen: Boolean = false
    ) {
        stop()
        val localStore = store
        val effectiveNow = localStore?.effectiveNowMs() ?: System.currentTimeMillis()
        val parentSet = localStore?.getMacroSetById(macro.setId)

        MacroSecurity.checkDirectRun(macro, effectiveNow, parentSet)?.let { denial ->
            _engineState.value = PlayerEngineState.ERROR
            PlayerSafeLogBus.error(MacroSecurity.denialMessage(macro, denial, parentSet))
            return
        }

        if (!PlayerAccessibilityService.isConnected.value) {
            _engineState.value = PlayerEngineState.ERROR
            PlayerSafeLogBus.error("Layanan Aksesibilitas Wjust Player belum aktif.")
            return
        }

        val effectiveMacro = mergeLocalAccounts(macro)
        if (effectiveMacro.accountSwitch.enabled && effectiveMacro.accountSwitch.accounts.none { it.enabled }) {
            PlayerSafeLogBus.warn("Macro Multi-Akun belum memiliki daftar akun lokal. Ketuk 'Atur Akun Saya' di aplikasi Wjust Player untuk menambahkan akun Anda.")
        }

        if (macroNeedsScreenCapture(effectiveMacro) && !PlayerScreenCaptureService.isCapturing.value) {
            PlayerSafeLogBus.warn("Tangkap layar belum aktif — aktifkan izin Tangkap Layar agar deteksi berjalan.")
        }

        selectMacro(macro)
        stopRequested.set(false)
        pausedFlag.set(false)
        skipAccountRequested.set(false)
        _currentLoop.value = 0
        _actionsExecuted.value = 0
        _activeAccountLabel.value = ""
        _humanBreakRemainingSec.value = 0
        _thermalPaused.value = false
        _deviceHealth.value = PlayerDeviceHealthReader.read(appCtx)
        if (startInBlackScreen) {
            _afkBlackScreenActive.value = true
        }
        _engineState.value = PlayerEngineState.RUNNING
        PlayerScreenCaptureService.highFrequencyCapture = true

        val startedAt = System.currentTimeMillis()
        val counter = AtomicInteger(0)
        val watchdogCount = AtomicInteger(0)
        val bannedCount = AtomicInteger(0)
        val accountSwitchCount = AtomicInteger(0)
        val humanBreakCount = AtomicInteger(0)
        val localNotifyStore = notifyStore

        fun publishLiveStats() {
            _liveSessionStats.value = com.example.data.LiveSessionStats(
                macroName = _activeMacro.value?.name?.ifBlank { effectiveMacro.name } ?: effectiveMacro.name,
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
            val loopText = if (effectiveMacro.loopCount <= 0) "∞" else "${effectiveMacro.loopCount}×"
            PlayerSafeLogBus.info("Memulai '${effectiveMacro.name}' (Loop: $loopText).")

            var lastPeriodicReportAt = startedAt
            val periodicReportJob = launch {
                while (isActive && !stopRequested.get()) {
                    delay(15_000L)
                    val cfg = localNotifyStore?.notificationConfig?.value ?: continue
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
                        PlayerNotifyManager.dispatchAsync(
                            context = appCtx,
                            config = cfg,
                            title = "Laporan Berkala — ${_activeMacro.value?.name ?: effectiveMacro.name}",
                            message = msg,
                            level = com.example.data.NotifyLevel.INFO,
                            sendRemote = true,
                            includeScreenshot = cfg.attachScreenshotOnEvents,
                            triggerPhoneAlarm = false,
                            captureJpeg = { PlayerScreenCaptureService.captureScaledJpeg() }
                        )
                    }
                }
            }
            val deviceGuardJob = launch {
                while (isActive && !stopRequested.get()) {
                    val snap = PlayerDeviceHealthReader.read(appCtx)
                    if (snap != null) {
                        _deviceHealth.value = snap
                        val gCfg = localNotifyStore?.afkGuardConfig?.value ?: com.example.data.AfkAndDeviceGuardConfig()
                        val decision = com.example.data.DeviceGuardEvaluator.evaluate(gCfg, snap, _thermalPaused.value)
                        val nCfg = localNotifyStore?.notificationConfig?.value ?: com.example.data.NotificationConfig()
                        val macroTitle = _activeMacro.value?.name ?: effectiveMacro.name
                        when (decision) {
                            com.example.data.DeviceGuardDecision.STOP_LOW_BATTERY -> {
                                val msg = "Baterai ${snap.batteryPercent}% (batas ${gCfg.minBatteryPercent}%) — macro dihentikan."
                                PlayerSafeLogBus.warn(msg)
                                if (gCfg.notifyOnGuardTrigger) {
                                    PlayerNotifyManager.dispatchAsync(
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
                                val msg = "Suhu HP $tempTxt (batas ${gCfg.maxTempCelsius}°C) — macro dihentikan."
                                PlayerSafeLogBus.warn(msg)
                                if (gCfg.notifyOnGuardTrigger) {
                                    PlayerNotifyManager.dispatchAsync(
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
                                _engineState.value = PlayerEngineState.PAUSED
                                val msg = "Suhu HP $tempTxt — macro dijeda sementara untuk mendinginkan HP."
                                PlayerSafeLogBus.warn(msg)
                                if (gCfg.notifyOnGuardTrigger) {
                                    PlayerNotifyManager.dispatchAsync(
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
                                _thermalPaused.value = false
                                pausedFlag.set(false)
                                _engineState.value = PlayerEngineState.RUNNING
                                PlayerSafeLogBus.info("Suhu HP kembali aman — macro dilanjutkan otomatis.")
                            }
                            com.example.data.DeviceGuardDecision.STAY_THERMAL_PAUSED,
                            com.example.data.DeviceGuardDecision.OK -> Unit
                        }
                    }
                    delay(10_000L)
                }
            }
            val autoBlackScreenJob = launch {
                val sec = localNotifyStore?.afkGuardConfig?.value?.autoBlackScreenAfterSec ?: 0
                if (sec > 0 && !_afkBlackScreenActive.value) {
                    cooperativeDelay(sec.coerceIn(5, 3600) * 1000L)
                    if (isActive && !stopRequested.get() && _engineState.value == PlayerEngineState.RUNNING) {
                        _afkBlackScreenActive.value = true
                        PlayerSafeLogBus.info("Mode Layar Hitam AFK otomatis aktif.")
                    }
                }
            }

            val runtime = object : MacroRuntime {
                override val screenWidth: Int get() = liveScreenSize(fallbackW, fallbackH).first
                override val screenHeight: Int get() = liveScreenSize(fallbackW, fallbackH).second
                override fun isStopped(): Boolean = stopRequested.get() || !isActive
                override suspend fun waitIfPaused() = this@PlayerCoordinator.waitIfPaused()
                override suspend fun sleep(ms: Long) = cooperativeDelay(ms)

                override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean =
                    PlayerAccessibilityService.dispatchClick(x.toFloat(), y.toFloat(), durationMs)

                override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
                    PlayerAccessibilityService.dispatchSwipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs)

                override suspend fun swipeCurved(
                    x1: Int,
                    y1: Int,
                    cx: Int,
                    cy: Int,
                    x2: Int,
                    y2: Int,
                    durationMs: Long
                ): Boolean = PlayerAccessibilityService.dispatchCurvedSwipe(
                    x1.toFloat(), y1.toFloat(), cx.toFloat(), cy.toFloat(), x2.toFloat(), y2.toFloat(), durationMs
                )

                override suspend fun zoom(cx: Int, cy: Int, zoomIn: Boolean, distancePx: Int, durationMs: Long): Boolean =
                    PlayerAccessibilityService.dispatchZoom(cx.toFloat(), cy.toFloat(), zoomIn, distancePx, durationMs)

                override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float): Boolean =
                    PlayerAccessibilityService.dispatchRecordedGesture(gesture, speed)

                override suspend fun exitApp(
                    mode: ExitAppMode,
                    packageName: String,
                    recentsSwipeDirection: RecentsSwipeDirection,
                    forceStopBackground: Boolean
                ): Boolean {
                    val cleanPkg = packageName.trim()
                    val effectivePkg = cleanPkg.ifBlank { PlayerAccessibilityService.lastForegroundPackage }
                    val resolvedAppLabel = if (effectivePkg.isNotBlank()) {
                        runCatching {
                            val pm = appCtx?.packageManager
                            val ai = pm?.getApplicationInfo(effectivePkg, 0)
                            if (ai != null) pm.getApplicationLabel(ai).toString() else ""
                        }.getOrDefault("")
                    } else ""

                    val ok = when (mode) {
                        ExitAppMode.HOME_ONLY -> {
                            val h = PlayerAccessibilityService.dispatchGlobalHome()
                            cooperativeDelay(350L)
                            h
                        }
                        ExitAppMode.BACK_THEN_HOME -> {
                            PlayerAccessibilityService.dispatchGlobalBack()
                            cooperativeDelay(250L)
                            PlayerAccessibilityService.dispatchGlobalBack()
                            cooperativeDelay(250L)
                            val h = PlayerAccessibilityService.dispatchGlobalHome()
                            cooperativeDelay(350L)
                            h
                        }
                        ExitAppMode.FORCE_STOP_APP -> {
                            val stopped = if (effectivePkg.isNotBlank()) {
                                PlayerAccessibilityService.forceStopPackageViaSettings(effectivePkg)
                            } else false
                            cooperativeDelay(250L)
                            val h = PlayerAccessibilityService.dispatchGlobalHome()
                            cooperativeDelay(350L)
                            stopped || h
                        }
                        ExitAppMode.CLOSE_RECENT_AND_HOME -> {
                            val (beforeRot, beforeW, beforeH) = readLiveDisplayState(fallbackW, fallbackH)
                            val openedRecents = PlayerAccessibilityService.dispatchGlobalRecents()
                            if (openedRecents) {
                                cooperativeDelay(900L)
                                val dismissedByNode = if (resolvedAppLabel.isNotBlank()) {
                                    PlayerAccessibilityService.tryDismissRecentCard(resolvedAppLabel)
                                } else false
                                if (!dismissedByNode) {
                                    val (afterRot, afterW, afterH) = readLiveDisplayState(fallbackW, fallbackH)
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
                                    PlayerAccessibilityService.dispatchSwipe(swipe.x1, swipe.y1, swipe.x2, swipe.y2, 145L)
                                }
                                cooperativeDelay(500L)
                            }
                            if (forceStopBackground && effectivePkg.isNotBlank()) {
                                PlayerAccessibilityService.forceStopPackageViaSettings(effectivePkg)
                                cooperativeDelay(220L)
                            }
                            val h = PlayerAccessibilityService.dispatchGlobalHome()
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
                    }.getOrNull() ?: return false
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    if (freshStart) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    if (PlayerAccessibilityService.launchActivityIntent(launchIntent)) return true
                    return runCatching {
                        ctx.startActivity(launchIntent)
                        true
                    }.getOrDefault(false)
                }

                override fun setTrackingBoost(enabled: Boolean) {
                    PlayerScreenCaptureService.trackingCaptureMode = enabled
                }

                override fun captureFrame(): Frame? = PlayerScreenCaptureService.captureFrame()
                override fun samplePixelArgb(x: Int, y: Int): Int? = PlayerScreenCaptureService.samplePixelArgb(x, y)
                override suspend fun recognizeText(region: Region?): List<OcrBlock> =
                    recognizeText(region, enhanced = false)

                override suspend fun recognizeText(region: Region?, enhanced: Boolean): List<OcrBlock> {
                    val bmp = PlayerScreenCaptureService.captureBitmapCopy() ?: return emptyList()
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
                        } else region
                        val rawBlocks = PlayerTextRecognizer.recognize(bmp, bmpRegion, enhanced = enhanced)
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
                        } else rawBlocks
                    } finally {
                        bmp.recycle()
                    }
                }

                override fun loadTemplate(name: String): PreparedTemplate? = localStore?.loadTemplate(name)
                override fun loadMacro(id: String): Macro? = localStore?.getMacroById(id)?.let { mergeLocalAccounts(it) }
                override fun loadMacroSet(id: String): MacroSet? = localStore?.getMacroSetById(id)
                override fun onActiveMacroSwitched(target: Macro) {
                    selectMacro(target)
                    _currentLoop.value = 0
                    PlayerSafeLogBus.info("Beralih ke '${target.name}'.")
                }

                override fun onActiveAccountChanged(accountIndex: Int, totalEnabled: Int, account: AccountProfile?) {
                    if (account == null || totalEnabled <= 0) {
                        _activeAccountLabel.value = ""
                    } else {
                        val badge = "$accountIndex/$totalEnabled • ${account.label}"
                        if (_activeAccountLabel.value != badge) {
                            PlayerSafeLogBus.info("Akun aktif ($accountIndex/$totalEnabled): ${account.label}.")
                        }
                        _activeAccountLabel.value = badge
                    }
                }

                override fun onAccountStateUpdated(
                    macroId: String,
                    lastAccountId: String,
                    completedAccountId: String,
                    lastUsedAtMs: Long
                ) {
                    val targetMacro = localStore?.getMacroById(macroId) ?: _activeMacro.value ?: macro
                    accountStore?.recordAccountTransition(
                        macro = targetMacro,
                        lastAccountId = lastAccountId,
                        completedAccountId = completedAccountId,
                        lastUsedAtMs = lastUsedAtMs
                    )
                }

                override fun consumeManualSkipAccountRequest(): Boolean =
                    skipAccountRequested.getAndSet(false)

                override fun isVisualDebugEnabled(): Boolean = false

                override fun onHumanBreakChanged(remainingSec: Int) {
                    _humanBreakRemainingSec.value = remainingSec.coerceAtLeast(0)
                }

                override fun onWatchdogTriggered(modeLabel: String) {
                    val count = watchdogCount.incrementAndGet()
                    publishLiveStats()
                    val cfg = localNotifyStore?.notificationConfig?.value ?: return
                    if (cfg.notifyOnWatchdog && cfg.hasAnyRemoteConfigured()) {
                        PlayerNotifyManager.dispatchAsync(
                            context = appCtx,
                            config = cfg,
                            title = "Watchdog Anti-Macet — ${_activeMacro.value?.name ?: effectiveMacro.name}",
                            message = "Recovery #$count ($modeLabel) pada loop #${_currentLoop.value}.",
                            level = com.example.data.NotifyLevel.WARNING,
                            sendRemote = true,
                            includeScreenshot = cfg.attachScreenshotOnEvents,
                            triggerPhoneAlarm = false,
                            captureJpeg = { PlayerScreenCaptureService.captureScaledJpeg() }
                        )
                    }
                }

                override fun onBannedBlocked(imageName: String) {
                    // Only count locally; do not expose internal asset filenames in Wjust Player
                    bannedCount.incrementAndGet()
                    publishLiveStats()
                }

                override fun onAccountSwitched(accountLabel: String) {
                    val count = accountSwitchCount.incrementAndGet()
                    publishLiveStats()
                    val cfg = localNotifyStore?.notificationConfig?.value ?: return
                    if (cfg.notifyOnAccountSwitch && cfg.hasAnyRemoteConfigured()) {
                        PlayerNotifyManager.dispatchAsync(
                            context = appCtx,
                            config = cfg,
                            title = "Ganti Akun — ${_activeMacro.value?.name ?: effectiveMacro.name}",
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
                    val cfg = localNotifyStore?.notificationConfig?.value ?: com.example.data.NotificationConfig()
                    PlayerNotifyManager.dispatchAsync(
                        context = appCtx,
                        config = cfg,
                        title = title,
                        message = message,
                        level = level,
                        sendRemote = sendRemote,
                        includeScreenshot = includeScreenshot,
                        triggerPhoneAlarm = triggerPhoneAlarm,
                        captureJpeg = { PlayerScreenCaptureService.captureScaledJpeg() }
                    )
                }

                override fun onMatchFound(match: MatchResult, label: String) {
                    // Intentionally silent in Wjust Player to keep template locations hidden
                }

                override fun onLoopChanged(loop: Int) {
                    _currentLoop.value = loop
                    publishLiveStats()
                    if (loop > 0) {
                        PlayerSafeLogBus.info("Loop #$loop berjalan...")
                    }
                }

                override fun onActionExecuted() {
                    _actionsExecuted.value = counter.incrementAndGet()
                    publishLiveStats()
                }

                override fun resolveRemoteActionControl(
                    macro: Macro,
                    actionId: String
                ): ActionRemoteControl? =
                    localStore?.getMacroById(macro.id)?.actionRemoteControls?.get(actionId)
                        ?: macro.actionRemoteControls[actionId]

                override fun onOneShotRemoteActionConsumed(macroId: String, actionId: String) {
                    val updated = localStore?.setRemoteActionActive(macroId, actionId, false)
                    if (updated != null && _activeMacro.value?.id == updated.id) {
                        _activeMacro.value = updated
                    }
                }

                override fun log(level: LogLevel, message: String) {
                    PlayerSafeLogBus.logFromRunner(level, message)
                }

                override fun nowMs(): Long = localStore?.effectiveNowMs() ?: System.currentTimeMillis()
            }

            var endReason = "Selesai"
            try {
                cooperativeDelay(140L)
                MacroRunner(runtime).run(effectiveMacro)
                if (stopRequested.get()) {
                    endReason = "Dihentikan"
                }
            } catch (_: CancellationException) {
                endReason = "Dihentikan"
                PlayerSafeLogBus.info("Macro dihentikan.")
            } catch (t: Throwable) {
                endReason = "Error"
                if (runGeneration.get() == myRunId) {
                    _engineState.value = PlayerEngineState.ERROR
                }
                PlayerSafeLogBus.error("Terjadi kesalahan saat menjalankan macro.")
            } finally {
                periodicReportJob.cancel()
                deviceGuardJob.cancel()
                autoBlackScreenJob.cancel()
                localStore?.flushClockWatermark()
                val endedAt = System.currentTimeMillis()
                val elapsed = (endedAt - startedAt).coerceAtLeast(0L)
                val finalLoops = _currentLoop.value
                val finalActions = counter.get()
                val finalMacroName = _activeMacro.value?.name?.ifBlank { effectiveMacro.name } ?: effectiveMacro.name
                if (elapsed >= 500L || finalActions > 0 || finalLoops > 0 || endReason == "Error") {
                    localNotifyStore?.recordFarmingSession(
                        com.example.data.FarmingSessionRecord(
                            macroId = effectiveMacro.id,
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
                            finalVariables = emptyMap() // Keep creator variable names hidden in Wjust Player
                        )
                    )
                }
                val cfg = localNotifyStore?.notificationConfig?.value
                if (cfg != null) {
                    val durTxt = com.example.data.FarmingStatsCalculator.formatDuration(elapsed)
                    if (endReason == "Error" && (cfg.notifyOnError || cfg.alarmOnError)) {
                        PlayerNotifyManager.dispatchAsync(
                            context = appCtx,
                            config = cfg,
                            title = "Macro Error — $finalMacroName",
                            message = "Terjadi kesalahan saat menjalankan macro.\nDurasi: $durTxt • Loop: $finalLoops • Aksi: $finalActions",
                            level = com.example.data.NotifyLevel.CRITICAL,
                            sendRemote = cfg.notifyOnError,
                            includeScreenshot = cfg.attachScreenshotOnEvents,
                            triggerPhoneAlarm = cfg.alarmOnError,
                            captureJpeg = { PlayerScreenCaptureService.captureScaledJpeg() }
                        )
                    } else if (endReason == "Selesai" && (cfg.notifyOnComplete || cfg.alarmOnComplete)) {
                        PlayerNotifyManager.dispatchAsync(
                            context = appCtx,
                            config = cfg,
                            title = "Macro Selesai — $finalMacroName",
                            message = "Durasi: $durTxt • Loop: $finalLoops • Aksi: $finalActions" +
                                "\nWatchdog: ${watchdogCount.get()} • Banned dicegah: ${bannedCount.get()}",
                            level = com.example.data.NotifyLevel.SUCCESS,
                            sendRemote = cfg.notifyOnComplete,
                            includeScreenshot = cfg.attachScreenshotOnEvents,
                            triggerPhoneAlarm = cfg.alarmOnComplete,
                            captureJpeg = { PlayerScreenCaptureService.captureScaledJpeg() }
                        )
                    }
                }
                if (runGeneration.get() == myRunId) {
                    PlayerScreenCaptureService.trackingCaptureMode = false
                    PlayerScreenCaptureService.highFrequencyCapture = false
                    _activeAccountLabel.value = ""
                    _humanBreakRemainingSec.value = 0
                    _liveSessionStats.value = null
                    _thermalPaused.value = false
                    _afkBlackScreenActive.value = false
                    skipAccountRequested.set(false)
                    if (_engineState.value != PlayerEngineState.ERROR) {
                        _engineState.value = PlayerEngineState.IDLE
                    }
                    pausedFlag.set(false)
                }
                val elapsedSec = elapsed / 1000.0
                PlayerSafeLogBus.info(String.format("Selesai (%.1f detik).", elapsedSec))
            }
        }
    }

    private suspend fun waitIfPaused() {
        while (pausedFlag.get() && !stopRequested.get()) {
            delay(80L)
        }
    }

    private suspend fun cooperativeDelay(ms: Long) {
        if (ms <= 0L) return
        var remaining = ms
        while (remaining > 0L && !stopRequested.get()) {
            waitIfPaused()
            val step = minOf(remaining, 60L)
            delay(step)
            remaining -= step
        }
    }

    private fun liveScreenSize(fallbackW: Int, fallbackH: Int): Pair<Int, Int> {
        val capW = PlayerScreenCaptureService.captureWidth
        val capH = PlayerScreenCaptureService.captureHeight
        if (PlayerScreenCaptureService.isCapturing.value && capW > 100 && capH > 100) {
            return capW to capH
        }
        val (_, w, h) = readLiveDisplayState(fallbackW, fallbackH)
        return w to h
    }

    private fun readLiveDisplayState(fallbackW: Int, fallbackH: Int): Triple<Int, Int, Int> {
        val ctx = appCtx
        if (ctx != null) {
            runCatching {
                val wm = ctx.getSystemService(WindowManager::class.java)
                val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching { ctx.display?.rotation }.getOrNull() ?: Surface.ROTATION_0
                } else {
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.rotation
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val b = wm.maximumWindowMetrics.bounds
                    return Triple(rotation, b.width().coerceAtLeast(1), b.height().coerceAtLeast(1))
                } else {
                    val m = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getRealMetrics(m)
                    return Triple(rotation, m.widthPixels.coerceAtLeast(1), m.heightPixels.coerceAtLeast(1))
                }
            }
        }
        return Triple(Surface.ROTATION_0, fallbackW.coerceAtLeast(1), fallbackH.coerceAtLeast(1))
    }
}
