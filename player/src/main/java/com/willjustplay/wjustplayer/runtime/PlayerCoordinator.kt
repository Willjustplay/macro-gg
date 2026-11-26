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
import com.example.data.ColorAction
import com.example.data.ExitAppMode
import com.example.data.ImageAction
import com.example.data.Macro
import com.example.data.MacroSecurity
import com.example.data.MacroSet
import com.example.data.RecentsSwipeDirection
import com.example.data.RecordedGesture
import com.example.data.Region
import com.example.data.TextAction
import com.example.data.TrackObjectAction
import com.example.data.allActionsSequence
import com.example.vision.Frame
import com.example.vision.MatchResult
import com.example.vision.OcrBlock
import com.example.vision.PreparedTemplate
import com.willjustplay.wjustplayer.data.PlayerStore
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class PlayerEngineState { IDLE, RUNNING, PAUSED, ERROR }

/**
 * Play-Only execution engine coordinator for Wjust Player.
 * Reuses `:core`'s [MacroRunner] with full action fidelity while keeping user interaction strictly
 * Play / Pause / Stop and emitting privacy-filtered Safe Logs via [PlayerSafeLogBus].
 */
object PlayerCoordinator {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var store: PlayerStore? = null
    @Volatile private var appCtx: Context? = null
    @Volatile private var activeJob: Job? = null

    private val stopRequested = AtomicBoolean(false)
    private val pausedFlag = AtomicBoolean(false)

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

    fun attach(context: Context) {
        if (appCtx == null) appCtx = context.applicationContext
        if (store == null) {
            val s = PlayerStore.get(context)
            store = s
            if (_activeMacro.value == null) {
                _activeMacro.value = s.macros.value.firstOrNull { !it.isPassive }
            }
        }
    }

    fun setOverlayActive(active: Boolean) {
        _isOverlayActive.value = active
    }

    fun selectMacro(macro: Macro) {
        _activeMacro.value = macro
    }

    fun macroNeedsScreenCapture(macro: Macro): Boolean =
        macro.allActionsSequence().any {
            it is ImageAction || it is TrackObjectAction || it is TextAction || it is ColorAction || it is com.example.data.BranchRouterAction
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
            _engineState.value = PlayerEngineState.RUNNING
            PlayerSafeLogBus.info("Dilanjutkan.")
        }
    }

    fun stop() {
        stopRequested.set(true)
        pausedFlag.set(false)
        activeJob?.cancel()
        activeJob = null
        PlayerScreenCaptureService.trackingCaptureMode = false
        PlayerScreenCaptureService.highFrequencyCapture = false
        _engineState.value = PlayerEngineState.IDLE
    }

    fun runMacro(macro: Macro, fallbackW: Int = 1080, fallbackH: Int = 2400) {
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

        if (macroNeedsScreenCapture(macro) && !PlayerScreenCaptureService.isCapturing.value) {
            PlayerSafeLogBus.warn("Tangkap layar belum aktif — aktifkan izin Tangkap Layar agar deteksi berjalan.")
        }

        selectMacro(macro)
        stopRequested.set(false)
        pausedFlag.set(false)
        _currentLoop.value = 0
        _actionsExecuted.value = 0
        _engineState.value = PlayerEngineState.RUNNING
        PlayerScreenCaptureService.highFrequencyCapture = true

        val startedAt = System.currentTimeMillis()
        val counter = AtomicInteger(0)

        activeJob = engineScope.launch {
            val loopText = if (macro.loopCount <= 0) "∞" else "${macro.loopCount}×"
            PlayerSafeLogBus.info("Memulai '${macro.name}' (Loop: $loopText).")

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
                override fun loadMacro(id: String): Macro? = localStore?.getMacroById(id)
                override fun loadMacroSet(id: String): MacroSet? = localStore?.getMacroSetById(id)
                override fun onActiveMacroSwitched(target: Macro) {
                    selectMacro(target)
                    _currentLoop.value = 0
                    PlayerSafeLogBus.info("Beralih ke '${target.name}'.")
                }

                override fun onMatchFound(match: MatchResult, label: String) {
                    // Intentionally silent in Wjust Player to keep template locations hidden
                }

                override fun onLoopChanged(loop: Int) {
                    _currentLoop.value = loop
                    if (loop > 0) {
                        PlayerSafeLogBus.info("Loop #$loop berjalan...")
                    }
                }

                override fun onActionExecuted() {
                    _actionsExecuted.value = counter.incrementAndGet()
                }

                override fun log(level: LogLevel, message: String) {
                    PlayerSafeLogBus.logFromRunner(level, message)
                }

                override fun nowMs(): Long = localStore?.effectiveNowMs() ?: System.currentTimeMillis()
            }

            try {
                cooperativeDelay(140L)
                MacroRunner(runtime).run(macro)
            } catch (_: CancellationException) {
                PlayerSafeLogBus.info("Macro dihentikan.")
            } catch (t: Throwable) {
                _engineState.value = PlayerEngineState.ERROR
                PlayerSafeLogBus.error("Terjadi kesalahan saat menjalankan macro.")
            } finally {
                localStore?.flushClockWatermark()
                PlayerScreenCaptureService.trackingCaptureMode = false
                PlayerScreenCaptureService.highFrequencyCapture = false
                val elapsedSec = ((System.currentTimeMillis() - startedAt).coerceAtLeast(0L) / 1000.0)
                if (_engineState.value != PlayerEngineState.ERROR) {
                    _engineState.value = PlayerEngineState.IDLE
                }
                pausedFlag.set(false)
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
