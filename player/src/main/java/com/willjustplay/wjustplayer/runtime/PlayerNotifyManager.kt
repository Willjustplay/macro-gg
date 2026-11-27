package com.willjustplay.wjustplayer.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.example.automation.RemoteDispatchResult
import com.example.automation.RemoteNotifier
import com.example.data.NotificationConfig
import com.example.data.NotifyLevel
import com.willjustplay.wjustplayer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Non-blocking Telegram/Discord & local Phone Alarm dispatcher for Wjust Player (`:player`).
 * Uses only the player user's own local [NotificationConfig].
 */
object PlayerNotifyManager {

    private const val ALERT_CHANNEL_ID = "wjust_player_alerts"
    private val alertNotifSeq = AtomicInteger(6200)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun dispatchAsync(
        context: Context?,
        config: NotificationConfig,
        title: String,
        message: String,
        level: NotifyLevel = NotifyLevel.INFO,
        sendRemote: Boolean = true,
        includeScreenshot: Boolean = false,
        triggerPhoneAlarm: Boolean = false,
        captureBitmap: (() -> Bitmap?)? = null
    ) {
        val appCtx = context?.applicationContext
        if (triggerPhoneAlarm && appCtx != null) {
            triggerLocalAlarm(appCtx, config, title, message, level)
        }
        if (sendRemote && config.hasAnyRemoteConfigured()) {
            val bitmapCopy = if (includeScreenshot) runCatching { captureBitmap?.invoke() }.getOrNull() else null
            ioScope.launch {
                val jpeg = try {
                    encodeScreenshotJpeg(bitmapCopy)
                } finally {
                    runCatching { bitmapCopy?.recycle() }
                }
                val res = RemoteNotifier.send(
                    config = config,
                    title = title,
                    message = message,
                    level = level,
                    jpegBytes = jpeg
                )
                if (res.anyAttempted) {
                    if (res.anySucceeded) {
                        PlayerSafeLogBus.info("Notifikasi terkirim (${res.summaryMessage()}).")
                    } else {
                        PlayerSafeLogBus.warn("Gagal kirim notifikasi (${res.summaryMessage()}).")
                    }
                }
            }
        }
    }

    suspend fun sendTestRemote(config: NotificationConfig): RemoteDispatchResult =
        withContext(Dispatchers.IO) {
            RemoteNotifier.sendTest(config, appName = "Wjust Player")
        }

    fun triggerLocalAlarm(
        context: Context,
        config: NotificationConfig,
        title: String,
        message: String,
        level: NotifyLevel = NotifyLevel.WARNING
    ) {
        postAlertNotification(context, title, message, level)
        if (config.alarmVibrateEnabled) {
            vibratePattern(context, level == NotifyLevel.CRITICAL)
        }
        if (config.alarmSoundEnabled) {
            playBriefAlarmTone(context, urgent = level == NotifyLevel.CRITICAL)
        }
    }

    private fun postAlertNotification(context: Context, title: String, message: String, level: NotifyLevel) {
        runCatching {
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "Alert & Alarm Wjust Player",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifikasi alarm dan status dari Wjust Player."
                    enableVibration(false)
                }
                mgr.createNotificationChannel(ch)
            }
            val notif = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_player)
                .setContentTitle("${level.icon} ${title.ifBlank { "Wjust Player" }}")
                .setContentText(message.ifBlank { "Alert macro" })
                .setStyle(NotificationCompat.BigTextStyle().bigText(message.ifBlank { "Alert macro" }))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            mgr.notify(alertNotifSeq.incrementAndGet(), notif)
        }
    }

    private fun vibratePattern(context: Context, urgent: Boolean) {
        runCatching {
            val pattern = if (urgent) longArrayOf(0, 350, 140, 350, 140, 450) else longArrayOf(0, 260, 120, 260)
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            } ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }
    }

    private fun playBriefAlarmTone(context: Context, urgent: Boolean) {
        ioScope.launch {
            runCatching {
                val uri = if (urgent) {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                } else {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                } ?: return@runCatching
                val ringtone = RingtoneManager.getRingtone(context, uri) ?: return@runCatching
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ringtone.audioAttributes = AudioAttributes.Builder()
                        .setUsage(if (urgent) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                ringtone.play()
                delay(if (urgent) 2600L else 1800L)
                if (ringtone.isPlaying) {
                    ringtone.stop()
                }
            }
        }
    }

    private fun encodeScreenshotJpeg(bitmap: Bitmap?, maxDimPx: Int = 960, quality: Int = 72): ByteArray? {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        return runCatching {
            val longest = maxOf(bitmap.width, bitmap.height)
            val scaled = if (longest > maxDimPx) {
                val ratio = maxDimPx.toFloat() / longest.toFloat()
                val nw = (bitmap.width * ratio).toInt().coerceAtLeast(1)
                val nh = (bitmap.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, nw, nh, true)
            } else {
                bitmap
            }
            try {
                val out = ByteArrayOutputStream(48 * 1024)
                scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 92), out)
                out.toByteArray()
            } finally {
                if (scaled !== bitmap) {
                    scaled.recycle()
                }
            }
        }.getOrNull()
    }
}
