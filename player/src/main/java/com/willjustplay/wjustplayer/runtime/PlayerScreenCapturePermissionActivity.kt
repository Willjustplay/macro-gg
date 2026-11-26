package com.willjustplay.wjustplayer.runtime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Transparent helper activity requesting MediaProjection when Play is tapped on a macro
 * that uses screen detection without Screen Capture active yet.
 */
class PlayerScreenCapturePermissionActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            PlayerScreenCaptureService.start(this, result.resultCode, result.data!!)
        } else {
            PlayerScreenCaptureService.pendingOnReady = null
            PlayerSafeLogBus.warn("Izin tangkap layar dibatalkan.")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            launcher.launch(mgr.createScreenCaptureIntent())
        } catch (t: Throwable) {
            PlayerScreenCaptureService.pendingOnReady = null
            PlayerSafeLogBus.error("Gagal membuka dialog tangkap layar: ${t.message}")
            finish()
        }
    }

    companion object {
        fun request(context: Context, onReady: (() -> Unit)? = null) {
            PlayerScreenCaptureService.pendingOnReady = onReady
            val intent = Intent(context, PlayerScreenCapturePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
