package com.example.desktop

import com.example.vision.Frame
import com.example.vision.ReusableFramePool
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Background ADB Emulator / Android-USB bridge for Wjust macro PC:
 * - Auto-discovers ADB binaries (PATH + common LDPlayer, BlueStacks, MuMu, Nox, Android SDK locations)
 * - Captures raw uncompressed framebuffer (`adb exec-out screencap`) directly into [ReusableFramePool]
 *   (3x-5x faster than PNG screencap and works even when the emulator window is covered or minimized!)
 * - Sends clicks, long-presses, swipes, Home/Back, and app launch/force-stop WITHOUT moving the PC mouse!
 */
class AdbBridge(
    private val pool: ReusableFramePool = ReusableFramePool(3)
) {
    @Volatile
    private var customAdbPath: String = ""

    private val detectedAdbPath: String by lazy { resolveDefaultAdbExecutable() }

    var adbPath: String
        get() = customAdbPath.ifBlank { detectedAdbPath }
        set(value) { customAdbPath = value.trim() }

    fun resolveDefaultAdbExecutable(): String {
        val candidates = ArrayList<String>()
        candidates += "adb"
        if (Win32Native.isWindows) {
            val userHome = System.getProperty("user.home").orEmpty()
            val localAppData = System.getenv("LOCALAPPDATA").orEmpty()
            candidates += listOf(
                "$localAppData\\Android\\Sdk\\platform-tools\\adb.exe",
                "$userHome\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe",
                "C:\\LDPlayer\\LDPlayer9\\adb.exe",
                "C:\\LDPlayer\\LDPlayer4.0\\adb.exe",
                "C:\\Program Files\\BlueStacks_nxt\\HD-Adb.exe",
                "C:\\Program Files\\Netease\\MuMuPlayerGlobal-12.0\\shell\\adb.exe",
                "C:\\Program Files\\Netease\\MuMuPlayer-12.0\\shell\\adb.exe",
                "C:\\Program Files (x86)\\Nox\\bin\\nox_adb.exe"
            )
        } else {
            val userHome = System.getProperty("user.home").orEmpty()
            candidates += listOf(
                "$userHome/Android/Sdk/platform-tools/adb",
                "/usr/bin/adb",
                "/usr/local/bin/adb"
            )
        }
        for (c in candidates) {
            if (c == "adb") {
                if (runQuick(listOf("adb", "version"), 1500L) != null) return "adb"
            } else if (File(c).isFile) {
                return c
            }
        }
        return "adb"
    }

    fun listDevices(): List<AdbDeviceInfo> {
        val out = runQuick(listOf(adbPath, "devices", "-l"), 3000L) ?: return emptyList()
        val list = ArrayList<AdbDeviceInfo>()
        for (line in out.lines().drop(1)) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("*")) continue
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size >= 2) {
                val serial = parts[0]
                val state = parts[1]
                val model = parts.firstOrNull { it.startsWith("model:") }?.removePrefix("model:")?.replace('_', ' ').orEmpty()
                list += AdbDeviceInfo(serial = serial, state = state, model = model)
            }
        }
        return list
    }

    fun connectHost(address: String): String {
        val clean = address.trim()
        if (clean.isBlank()) return "Alamat kosong."
        return runQuick(listOf(adbPath, "connect", clean), 4000L)?.trim().orEmpty()
            .ifBlank { "Gagal menghubungi $clean" }
    }

    /**
     * Captures a frame from [serial] using raw uncompressed RGBA_8888 `screencap` (zero PNG overhead!).
     * Automatically falls back to PNG (`screencap -p`) if the device returns an unexpected pixel format.
     */
    fun captureFrame(serial: String): Frame? {
        if (serial.isBlank()) return null
        val rawBytes = runBinary(listOf(adbPath, "-s", serial, "exec-out", "screencap"), 3000L)
        if (rawBytes != null && rawBytes.size >= 16) {
            val parsed = parseRawScreencap(rawBytes)
            if (parsed != null) return parsed
        }
        val pngBytes = runBinary(listOf(adbPath, "-s", serial, "exec-out", "screencap", "-p"), 4000L) ?: return null
        return runCatching {
            val img = ImageIO.read(ByteArrayInputStream(pngBytes)) ?: return null
            bufferedImageToFrame(img, pool)
        }.getOrNull()
    }

    internal fun parseRawScreencap(raw: ByteArray): Frame? {
        if (raw.size < 16) return null
        val header = ByteBuffer.wrap(raw, 0, 16).order(ByteOrder.LITTLE_ENDIAN)
        val w = header.int
        val h = header.int
        val format = header.int // 1 = PIXEL_FORMAT_RGBA_8888
        if (w <= 0 || h <= 0 || w > 8192 || h > 8192 || format != 1) return null
        val pixelBytes = w.toLong() * h.toLong() * 4L
        val headerSize = when {
            raw.size.toLong() == pixelBytes + 16L -> 16 // Android 8+ (includes colorspace uint32)
            raw.size.toLong() == pixelBytes + 12L -> 12 // Legacy Android <= 7
            raw.size.toLong() > pixelBytes + 16L -> 16
            else -> return null
        }
        val count = w * h
        val pixels = pool.acquire(count)
        var si = headerSize
        for (i in 0 until count) {
            val r = raw[si].toInt() and 0xFF
            val g = raw[si + 1].toInt() and 0xFF
            val b = raw[si + 2].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            si += 4
        }
        return Frame(w, h, pixels)
    }

    fun click(serial: String, x: Int, y: Int, durationMs: Long): Boolean {
        if (serial.isBlank()) return false
        val cmd = if (durationMs <= 80L) {
            listOf(adbPath, "-s", serial, "shell", "input", "tap", x.toString(), y.toString())
        } else {
            listOf(
                adbPath, "-s", serial, "shell", "input", "swipe",
                x.toString(), y.toString(), x.toString(), y.toString(), durationMs.coerceIn(80L, 10_000L).toString()
            )
        }
        return runQuick(cmd, durationMs + 2500L) != null
    }

    fun swipe(serial: String, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        if (serial.isBlank()) return false
        val cmd = listOf(
            adbPath, "-s", serial, "shell", "input", "swipe",
            x1.toString(), y1.toString(), x2.toString(), y2.toString(),
            durationMs.coerceIn(50L, 10_000L).toString()
        )
        return runQuick(cmd, durationMs + 2500L) != null
    }

    fun keyEvent(serial: String, keyCode: Int): Boolean {
        if (serial.isBlank()) return false
        return runQuick(listOf(adbPath, "-s", serial, "shell", "input", "keyevent", keyCode.toString()), 2500L) != null
    }

    fun launchPackage(serial: String, packageName: String): Boolean {
        if (serial.isBlank() || packageName.isBlank()) return false
        return runQuick(
            listOf(adbPath, "-s", serial, "shell", "monkey", "-p", packageName.trim(), "-c", "android.intent.category.LAUNCHER", "1"),
            5000L
        ) != null
    }

    fun forceStopPackage(serial: String, packageName: String): Boolean {
        if (serial.isBlank() || packageName.isBlank()) return false
        return runQuick(listOf(adbPath, "-s", serial, "shell", "am", "force-stop", packageName.trim()), 4000L) != null
    }

    private fun runQuick(command: List<String>, timeoutMs: Long): String? = runCatching {
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            return null
        }
        if (p.exitValue() == 0) text else null
    }.getOrNull()

    private fun runBinary(command: List<String>, timeoutMs: Long): ByteArray? = runCatching {
        val p = ProcessBuilder(command).start()
        val bytes = p.inputStream.readBytes()
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            return null
        }
        if (p.exitValue() == 0) bytes else null
    }.getOrNull()
}
