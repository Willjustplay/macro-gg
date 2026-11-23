package com.example.automation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Transparent helper activity that requests MediaProjection permission when triggered
 * from the Floating Overlay (e.g. when tapping the Screenshot/Crop tool without capture active).
 */
class ScreenCapturePermissionActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.start(this, result.resultCode, result.data!!)
        } else {
            ScreenCaptureService.pendingOnReady = null
            ExecutionLogBus.warn("Izin tangkap layar dibatalkan.")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            launcher.launch(mgr.createScreenCaptureIntent())
        } catch (t: Throwable) {
            ScreenCaptureService.pendingOnReady = null
            ExecutionLogBus.error("Gagal membuka dialog tangkap layar: ${t.message}")
            finish()
        }
    }

    companion object {
        fun request(context: Context, onReady: (() -> Unit)? = null) {
            ScreenCaptureService.pendingOnReady = onReady
            val intent = Intent(context, ScreenCapturePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
