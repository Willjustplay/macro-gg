package com.example.desktop

import kotlinx.serialization.Serializable

enum class DesktopCaptureMode(val label: String, val description: String) {
    DESKTOP_FAST(
        "Desktop Cepat (Win32 GDI / Robot)",
        "Tangkap layar PC super cepat (Win32 BitBlt zero-GC di Windows, Robot di Linux/macOS) untuk game PC & emulator."
    ),
    WINDOW_BOUND(
        "Ikat Jendela Game / Emulator",
        "Kunci tangkapan & koordinat khusus ke 1 jendela (COC Google Play Games, LDPlayer, BlueStacks, MuMu, Roblox) — posisi otomatis ikut jika jendela digeser."
    ),
    ADB_BACKGROUND(
        "Emulator ADB (Background Tanpa Ganggu Mouse)",
        "Tangkap framebuffer langsung & kirim sentuhan lewat ADB — bisa farming saat emulator tertutup/background tanpa menggerakkan kursor mouse PC!"
    )
}

@Serializable
data class DesktopSettings(
    val captureMode: DesktopCaptureMode = DesktopCaptureMode.DESKTOP_FAST,
    val boundWindowTitle: String = "",
    val adbSerial: String = "",
    val adbExecutable: String = "adb",
    val targetFps: Int = 30,
    val parallelMatching: Boolean = true,
    val visionThreads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8),
    val lowCpuIdleMode: Boolean = true,
    val showMatchHighlight: Boolean = true,
    val showMiniBarOnRun: Boolean = true,
    val globalHotkeysEnabled: Boolean = true,
    val defaultTapDurationMs: Long = 50L,
    val defaultJitterPx: Int = 0
)

data class DesktopImageAsset(
    val name: String,
    val path: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val updatedAt: Long,
    val folder: String = ""
) {
    val displayName: String get() = name.substringAfterLast('/')
}

data class DesktopWindowInfo(
    val handleHex: String,
    val title: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {
    override fun toString(): String = "$title (${width}×${height})"
}

data class AdbDeviceInfo(
    val serial: String,
    val state: String,
    val model: String = ""
) {
    val isOnline: Boolean get() = state == "device"
    override fun toString(): String = if (model.isNotBlank()) "$model ($serial) [$state]" else "$serial [$state]"
}

data class DesktopPerfStats(
    val captureMs: Double = 0.0,
    val matchMs: Double = 0.0,
    val effectiveFps: Double = 0.0,
    val ramUsedMb: Long = 0L,
    val captureEngine: String = "Siap",
    val screenWidth: Int = 1920,
    val screenHeight: Int = 1080,
    val targetLabel: String = "Seluruh Layar"
)
