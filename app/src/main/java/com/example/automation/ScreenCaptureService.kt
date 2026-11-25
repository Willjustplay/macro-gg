package com.example.automation

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.vision.ColorUtil
import com.example.vision.Frame
import com.example.vision.toFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

/**
 * Foreground MediaProjection service supplying live screen frames for:
 * - Image detection (template matching)
 * - Overlay screenshot & crop tool
 * - Real-time pixel color detection & Color Spy
 *
 * Uses trailing-edge throttling (~8 FPS max) so static screens NEVER stay stuck on an old frame,
 * reuses Bitmap buffers to prevent OOM over heavy games, and listens to DisplayManager so
 * Portrait <-> Landscape switches resize VirtualDisplay reliably.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var displayManager: DisplayManager? = null
    private var lastFrameCopyMs: Long = 0L
    private var lastMetricsCheckMs: Long = 0L
    private var pendingImage: Image? = null
    private var pendingFlushScheduled = false
    private var packedBuffer: ByteBuffer? = null

    private val flushPendingRunnable = Runnable {
        pendingFlushScheduled = false
        val img = pendingImage ?: return@Runnable
        pendingImage = null
        copyImageToLatestBitmap(img, captureWidth, captureHeight)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            checkAndHandleRotation()
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            ExecutionLogBus.warn("Sesi tangkap layar (MediaProjection) dihentikan oleh sistem.")
            stopCaptureResources()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCaptureResources()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!startForegroundNotificationSafe()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA_INTENT)
        }

        if (resultCode != Activity.RESULT_OK || dataIntent == null) {
            if (_isCapturing.value && virtualDisplay != null) {
                return START_STICKY
            }
            ExecutionLogBus.warn("Izin tangkap layar tidak lengkap.")
            stopSelf()
            return START_NOT_STICKY
        }

        startCapture(resultCode, dataIntent)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep foreground MediaProjection service alive when user swipes app from Recents.
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        checkAndHandleRotation()
    }

    private fun checkAndHandleRotationThrottled() {
        val now = SystemClock.uptimeMillis()
        if (now - lastMetricsCheckMs < 450L) return
        lastMetricsCheckMs = now
        checkAndHandleRotation()
    }

    private fun checkAndHandleRotation() {
        val vd = virtualDisplay ?: return
        val handler = captureHandler ?: return
        val (w, h, density) = readDisplayMetrics()
        if (w != captureWidth || h != captureHeight) {
            handler.post {
                val curVd = virtualDisplay ?: return@post
                if (w != captureWidth || h != captureHeight) {
                    resizeVirtualDisplaySafely(curVd, handler, w, h, density)
                }
            }
        }
    }

    private fun readDisplayMetrics(): Triple<Int, Int, Int> {
        val dm = displayManager ?: getSystemService(DisplayManager::class.java)
        val defaultDisplay = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        if (defaultDisplay != null) {
            @Suppress("DEPRECATION")
            defaultDisplay.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return Triple(
                    metrics.widthPixels.coerceAtLeast(1),
                    metrics.heightPixels.coerceAtLeast(1),
                    metrics.densityDpi.coerceAtLeast(160)
                )
            }
        }
        val wm = getSystemService(WindowManager::class.java)
        val density = resources.displayMetrics.densityDpi.coerceAtLeast(160)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = runCatching { wm.maximumWindowMetrics.bounds }.getOrNull()
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                return Triple(bounds.width(), bounds.height(), density)
            }
        }
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return Triple(
            metrics.widthPixels.coerceAtLeast(1),
            metrics.heightPixels.coerceAtLeast(1),
            metrics.densityDpi.coerceAtLeast(160)
        )
    }

    private fun startForegroundNotificationSafe(): Boolean {
        return try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_capture),
                    NotificationManager.IMPORTANCE_LOW
                )
                manager.createNotificationChannel(channel)
            }
            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notif_capture_title))
                .setContentText(getString(R.string.notif_capture_text))
                .setOngoing(true)
                .setSilent(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Throwable) {
            ExecutionLogBus.error("Gagal memulai foreground service tangkap layar: ${t.message}")
            false
        }
    }

    private fun startCapture(resultCode: Int, dataIntent: Intent) {
        stopCaptureResources()

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = try {
            projectionManager.getMediaProjection(resultCode, dataIntent)
        } catch (t: Throwable) {
            ExecutionLogBus.error("Gagal memulai MediaProjection: ${t.message}")
            null
        }
        if (projection == null) {
            stopSelf()
            return
        }

        try {
            val (width, height, density) = readDisplayMetrics()
            val thread = HandlerThread("MacroGGScreenCapture").apply { start() }
            val handler = Handler(thread.looper)
            captureThread = thread
            captureHandler = handler
            mediaProjection = projection
            projection.registerCallback(projectionCallback, handler)

            val dm = getSystemService(DisplayManager::class.java)
            displayManager = dm
            dm?.registerDisplayListener(displayListener, handler)

            val reader = createImageReader(width, height, handler)
            val vd = projection.createVirtualDisplay(
                "MacroGGScreenDisplay",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
            captureWidth = width
            captureHeight = height
            imageReader = reader
            virtualDisplay = vd
            activeInstance = this
            _isCapturing.value = true
            ExecutionLogBus.image("Tangkap layar aktif (${width}x${height}).")

            val pending = pendingOnReady
            pendingOnReady = null
            if (pending != null) {
                handler.postDelayed({ pending() }, 280L)
            }
        } catch (t: Throwable) {
            ExecutionLogBus.error("Gagal membuat VirtualDisplay: ${t.message}")
            stopCaptureResources()
            stopSelf()
        }
    }

    private fun resizeVirtualDisplaySafely(
        vd: VirtualDisplay,
        handler: Handler,
        width: Int,
        height: Int,
        density: Int
    ) {
        try {
            handler.removeCallbacks(flushPendingRunnable)
            pendingFlushScheduled = false
            try { pendingImage?.close() } catch (_: Throwable) {}
            pendingImage = null

            val oldReader = imageReader
            val newReader = createImageReader(width, height, handler)
            vd.resize(width, height, density)
            vd.setSurface(newReader.surface)
            captureWidth = width
            captureHeight = height
            imageReader = newReader
            try {
                oldReader?.setOnImageAvailableListener(null, null)
                oldReader?.close()
            } catch (_: Throwable) {}
            synchronized(frameLock) {
                latestBitmap?.recycle()
                latestBitmap = null
            }
            ExecutionLogBus.image("Orientasi layar diperbarui (${width}x${height}).")
        } catch (t: Throwable) {
            ExecutionLogBus.warn("Gagal ubah ukuran tangkap layar: ${t.message}")
        }
    }

    private fun createImageReader(width: Int, height: Int, handler: Handler): ImageReader {
        // maxImages = 3 so holding 1 trailing-edge pending image never stalls the producer
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ ir ->
            checkAndHandleRotationThrottled()
            val image = try {
                ir.acquireLatestImage()
            } catch (_: Throwable) {
                null
            } ?: return@setOnImageAvailableListener

            val now = SystemClock.uptimeMillis()
            val hasExisting = synchronized(frameLock) { latestBitmap != null && !latestBitmap!!.isRecycled }
            val intervalMs = when {
                trackingCaptureMode -> TRACKING_FRAME_INTERVAL_MS
                highFrequencyCapture -> ACTIVE_FRAME_INTERVAL_MS
                else -> IDLE_FRAME_INTERVAL_MS
            }
            val elapsed = now - lastFrameCopyMs

            if (!hasExisting || elapsed >= intervalMs) {
                handler.removeCallbacks(flushPendingRunnable)
                pendingFlushScheduled = false
                try { pendingImage?.close() } catch (_: Throwable) {}
                pendingImage = null
                copyImageToLatestBitmap(image, width, height)
            } else {
                // Keep only the latest pending frame and flush once cooldown expires so static screens are never stale
                try { pendingImage?.close() } catch (_: Throwable) {}
                pendingImage = image
                if (!pendingFlushScheduled) {
                    pendingFlushScheduled = true
                    handler.postDelayed(flushPendingRunnable, (intervalMs - elapsed).coerceAtLeast(16L))
                }
            }
        }, handler)
        return reader
    }

    private fun copyImageToLatestBitmap(image: Image, width: Int, height: Int) {
        try {
            lastFrameCopyMs = SystemClock.uptimeMillis()
            val imgW = image.width.takeIf { it > 0 } ?: width
            val imgH = image.height.takeIf { it > 0 } ?: height
            val plane = image.planes[0]
            val buffer = plane.buffer
            buffer.rewind()
            val pixelStride = plane.pixelStride
            if (pixelStride <= 0) return
            val rowStride = plane.rowStride
            val rowBytes = imgW * pixelStride
            val rowPadding = rowStride - rowBytes
            val totalBytes = rowBytes * imgH

            synchronized(frameLock) {
                var dst = latestBitmap
                if (dst == null || dst.isRecycled || dst.width != imgW || dst.height != imgH) {
                    dst?.recycle()
                    dst = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
                    latestBitmap = dst
                }
                if (rowPadding == 0 && buffer.remaining() >= totalBytes) {
                    dst.copyPixelsFromBuffer(buffer)
                } else {
                    // Row-by-row copy avoids "Buffer not large enough for pixels" on Mali/Redfinger/Gralloc4
                    // when the last row omits trailing stride padding, and avoids per-frame Bitmap allocations.
                    var packed = packedBuffer
                    if (packed == null || packed.capacity() < totalBytes) {
                        packed = ByteBuffer.allocate(totalBytes)
                        packedBuffer = packed
                    }
                    packed.clear()
                    val dstArray = packed.array()
                    val limit = buffer.limit()
                    var dstOffset = 0
                    for (row in 0 until imgH) {
                        val srcPos = row * rowStride
                        if (srcPos >= limit) break
                        val copyLen = minOf(rowBytes, limit - srcPos)
                        buffer.position(srcPos)
                        buffer.get(dstArray, dstOffset, copyLen)
                        dstOffset += rowBytes
                    }
                    packed.position(0)
                    packed.limit(totalBytes)
                    dst.copyPixelsFromBuffer(packed)
                }
            }
        } catch (_: Throwable) {
            // Swallow transient frame / OOM errors safely
        } finally {
            try { image.close() } catch (_: Throwable) {}
        }
    }

    private fun stopCaptureResources() {
        if (activeInstance === this) activeInstance = null
        _isCapturing.value = false
        try { displayManager?.unregisterDisplayListener(displayListener) } catch (_: Throwable) {}
        displayManager = null

        try { captureHandler?.removeCallbacks(flushPendingRunnable) } catch (_: Throwable) {}
        pendingFlushScheduled = false
        try { pendingImage?.close() } catch (_: Throwable) {}
        pendingImage = null

        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (_: Throwable) {}
        imageReader = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Throwable) {}
        mediaProjection = null

        try { captureThread?.quitSafely() } catch (_: Throwable) {}
        captureThread = null
        captureHandler = null
        packedBuffer = null

        synchronized(frameLock) {
            latestBitmap?.recycle()
            latestBitmap = null
        }
    }

    override fun onDestroy() {
        stopCaptureResources()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "macrogg_screen_capture"
        private const val NOTIFICATION_ID = 4102
        private const val TRACKING_FRAME_INTERVAL_MS = 33L
        private const val ACTIVE_FRAME_INTERVAL_MS = 120L
        private const val IDLE_FRAME_INTERVAL_MS = 450L
        @Volatile var highFrequencyCapture: Boolean = false
        @Volatile var trackingCaptureMode: Boolean = false
        const val ACTION_START = "com.example.action.START_CAPTURE"
        const val ACTION_STOP = "com.example.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA_INTENT = "data_intent"

        private val frameLock = Any()
        private var latestBitmap: Bitmap? = null
        @Volatile private var activeInstance: ScreenCaptureService? = null
        @Volatile var captureWidth: Int = 1080
        @Volatile var captureHeight: Int = 2400
        @Volatile var pendingOnReady: (() -> Unit)? = null

        private val _isCapturing = MutableStateFlow(false)
        val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

        fun start(context: Context, resultCode: Int, dataIntent: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA_INTENT, dataIntent)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                ExecutionLogBus.error("Gagal menjalankan ScreenCaptureService: ${t.message}")
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ScreenCaptureService::class.java))
            }
        }

        /** Returns an immutable snapshot [Frame] of the latest screen image for template matching. */
        fun captureFrame(): Frame? {
            activeInstance?.checkAndHandleRotationThrottled()
            synchronized(frameLock) {
                val bmp = latestBitmap ?: return null
                if (bmp.isRecycled) return null
                return try {
                    bmp.toFrame()
                } catch (_: Throwable) {
                    null
                }
            }
        }

        /** Returns a standalone Bitmap copy for the overlay cropper UI. */
        fun captureBitmapCopy(): Bitmap? {
            activeInstance?.checkAndHandleRotationThrottled()
            synchronized(frameLock) {
                val bmp = latestBitmap ?: return null
                if (bmp.isRecycled) return null
                return try {
                    bmp.copy(Bitmap.Config.ARGB_8888, false)
                } catch (_: Throwable) {
                    null
                }
            }
        }

        fun samplePixelArgb(x: Int, y: Int): Int? {
            synchronized(frameLock) {
                val bmp = latestBitmap ?: return null
                if (bmp.isRecycled) return null
                return try {
                    val cx = x.coerceIn(0, bmp.width - 1)
                    val cy = y.coerceIn(0, bmp.height - 1)
                    bmp.getPixel(cx, cy)
                } catch (_: Throwable) {
                    null
                }
            }
        }

        fun samplePixelHex(x: Int, y: Int): String? {
            val argb = samplePixelArgb(x, y) ?: return null
            return ColorUtil.toHex(argb)
        }

        fun matchesColor(x: Int, y: Int, targetHex: String, tolerance: Int = 15): Boolean {
            val argb = samplePixelArgb(x, y) ?: return false
            return ColorUtil.matches(argb, targetHex, tolerance)
        }
    }
}
