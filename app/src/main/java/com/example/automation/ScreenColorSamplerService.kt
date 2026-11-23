package com.example.automation

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

class ScreenColorSamplerService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            ExecutionLogBus.warn("Sesi MediaProjection dihentikan oleh sistem.")
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

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA_INTENT)
        }

        if (resultCode != Activity.RESULT_OK || dataIntent == null) {
            ExecutionLogBus.warn("Izin deteksi warna layar (MediaProjection) tidak lengkap.")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()
        startCapture(resultCode, dataIntent)
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
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
    }

    private fun startCapture(resultCode: Int, dataIntent: Intent) {
        stopCaptureResources()

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = try {
            projectionManager.getMediaProjection(resultCode, dataIntent)
        } catch (e: Exception) {
            ExecutionLogBus.error("Gagal memulai MediaProjection: ${e.message}")
            null
        }
        if (projection == null) {
            stopSelf()
            return
        }

        val wm = getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi

        captureWidth = width
        captureHeight = height

        val thread = HandlerThread("TapLuaColorSampler").apply { start() }
        val handler = Handler(thread.looper)
        captureThread = thread
        captureHandler = handler

        projection.registerCallback(projectionCallback, handler)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ ir ->
            val image = try {
                ir.acquireLatestImage()
            } catch (_: Exception) {
                null
            } ?: return@setOnImageAvailableListener

            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmapWidth = width + rowPadding / pixelStride
                val fullBitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
                fullBitmap.copyPixelsFromBuffer(buffer)
                val cropped = if (bitmapWidth != width) {
                    val crop = Bitmap.createBitmap(fullBitmap, 0, 0, width, height)
                    fullBitmap.recycle()
                    crop
                } else {
                    fullBitmap
                }
                synchronized(frameLock) {
                    val old = latestBitmap
                    latestBitmap = cropped
                    old?.recycle()
                }
            } catch (_: Exception) {
                // Skip transient frame acquisition errors safely
            } finally {
                image.close()
            }
        }, handler)

        val vd = projection.createVirtualDisplay(
            "TapLuaColorDisplay",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )

        mediaProjection = projection
        imageReader = reader
        virtualDisplay = vd
        _isCapturing.value = true
        ExecutionLogBus.color("Color Sampler aktif (${width}x${height}).")
    }

    private fun stopCaptureResources() {
        _isCapturing.value = false
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (_: Exception) {}
        imageReader = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null

        try {
            captureThread?.quitSafely()
        } catch (_: Exception) {}
        captureThread = null
        captureHandler = null

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
        private const val CHANNEL_ID = "taplua_color_capture"
        private const val NOTIFICATION_ID = 4102
        const val ACTION_START = "com.example.action.START_CAPTURE"
        const val ACTION_STOP = "com.example.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA_INTENT = "data_intent"

        private val frameLock = Any()
        private var latestBitmap: Bitmap? = null
        @Volatile var captureWidth: Int = 1080
        @Volatile var captureHeight: Int = 2400

        private val _isCapturing = MutableStateFlow(false)
        val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

        fun start(context: Context, resultCode: Int, dataIntent: Intent) {
            val intent = Intent(context, ScreenColorSamplerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA_INTENT, dataIntent)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenColorSamplerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun samplePixelArgb(x: Int, y: Int): Int? {
            synchronized(frameLock) {
                val bmp = latestBitmap ?: return null
                if (bmp.isRecycled) return null
                val clampedX = x.coerceIn(0, bmp.width - 1)
                val clampedY = y.coerceIn(0, bmp.height - 1)
                return bmp.getPixel(clampedX, clampedY)
            }
        }

        fun samplePixelHex(x: Int, y: Int): String? {
            val argb = samplePixelArgb(x, y) ?: return null
            return argbToHex(argb)
        }

        fun matchesColor(x: Int, y: Int, targetHex: String, tolerance: Int = 15): Boolean {
            val argb = samplePixelArgb(x, y) ?: return false
            return colorMatches(argb, targetHex, tolerance)
        }

        fun argbToHex(argb: Int): String {
            val r = Color.red(argb)
            val g = Color.green(argb)
            val b = Color.blue(argb)
            return String.format("#%02X%02X%02X", r, g, b)
        }

        fun colorMatches(actualArgb: Int, targetHex: String, tolerance: Int): Boolean {
            val clean = targetHex.trim().removePrefix("#")
            if (clean.length != 6) return false
            val targetRgb = clean.toIntOrNull(16) ?: return false
            val tr = (targetRgb shr 16) and 0xFF
            val tg = (targetRgb shr 8) and 0xFF
            val tb = targetRgb and 0xFF
            val ar = (actualArgb shr 16) and 0xFF
            val ag = (actualArgb shr 8) and 0xFF
            val ab = actualArgb and 0xFF
            val tol = tolerance.coerceIn(0, 255)
            return abs(ar - tr) <= tol && abs(ag - tg) <= tol && abs(ab - tb) <= tol
        }
    }
}
