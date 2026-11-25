package com.example.desktop

import com.example.automation.ExecutionLogBus
import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * Bulletproof entry point for Wjust macro PC (Windows / Linux / macOS):
 * - Disables crash-prone Windows D3D/DirectDraw/FlatLaf WndProc native hooks
 * - Configures a writable user-level JNA native cache (`~/.wjust-macro/native_cache`) to avoid %TEMP% locks
 * - Installs global UncaughtExceptionHandler + Swing EventQueue guard that logs to `crash.log`
 *   instead of silently force-closing the desktop app
 */
object DesktopCrashGuard {
    private val dataDir: File by lazy { DesktopStore.resolveDefaultDataDir().apply { mkdirs() } }
    val crashLogFile: File get() = File(dataDir, "crash.log")

    @Volatile
    private var lastDialogAtMs: Long = 0L

    fun recordAndNotify(context: String, error: Throwable, showDialog: Boolean = true) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val entry = buildString {
            appendLine("=== [$ts] $context ===")
            appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.arch")} | Java: ${System.getProperty("java.version")}")
            appendLine(sw.toString())
        }
        runCatching { crashLogFile.appendText(entry, Charsets.UTF_8) }
        runCatching { ExecutionLogBus.error("[$context] ${error.javaClass.simpleName}: ${error.message}") }

        if (showDialog) {
            val now = System.currentTimeMillis()
            if (now - lastDialogAtMs > 4000L) {
                lastDialogAtMs = now
                val summary = "${error.javaClass.simpleName}: ${error.message ?: "(tanpa pesan)"}"
                val msg = "Terjadi kesalahan non-fatal ($context):\n$summary\n\nDetail lengkap tersimpan di:\n${crashLogFile.absolutePath}"
                runCatching {
                    if (SwingUtilities.isEventDispatchThread()) {
                        JOptionPane.showMessageDialog(null, msg, "Wjust macro PC — Pemulihan Error", JOptionPane.WARNING_MESSAGE)
                    } else {
                        SwingUtilities.invokeLater {
                            runCatching {
                                JOptionPane.showMessageDialog(null, msg, "Wjust macro PC — Pemulihan Error", JOptionPane.WARNING_MESSAGE)
                            }
                        }
                    }
                }
            }
        }
    }

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            recordAndNotify("Thread:${thread.name}", throwable, showDialog = false)
        }
        runCatching {
            val sysQueue = Toolkit.getDefaultToolkit().systemEventQueue
            sysQueue.push(object : EventQueue() {
                override fun dispatchEvent(event: AWTEvent) {
                    try {
                        super.dispatchEvent(event)
                    } catch (t: Throwable) {
                        recordAndNotify("UI/EDT", t, showDialog = true)
                    }
                }
            })
        }
    }
}

fun main() {
    // 1. Safe Windows Java2D & HiDPI defaults (eliminates D3D/DirectDraw/FlatLaf native WndProc force closes)
    System.setProperty("sun.java2d.d3d", "false")
    System.setProperty("sun.java2d.noddraw", "true")
    System.setProperty("sun.java2d.opengl", "false")
    System.setProperty("flatlaf.useWindowDecorations", "false")
    System.setProperty("sun.java2d.uiScale.enabled", "true")
    System.setProperty("awt.useSystemAAFontSettings", "on")
    System.setProperty("swing.aatext", "true")

    // 2. Writable JNA extraction cache in user data dir (prevents Windows Defender / %TEMP% DLL extraction failures)
    runCatching {
        val nativeDir = File(DesktopStore.resolveDefaultDataDir(), "native_cache").apply { mkdirs() }
        if (nativeDir.canWrite()) {
            System.setProperty("jna.tmpdir", nativeDir.absolutePath)
            System.setProperty("flatlaf.nativeLibraryPath", nativeDir.absolutePath)
        }
    }

    // 3. Install crash handler before starting Swing
    DesktopCrashGuard.install()

    SwingUtilities.invokeLater {
        try {
            DesktopTheme.install()
            val store = DesktopStore()
            val coordinator = DesktopCoordinator(store)
            val window = DesktopMainWindow(store, coordinator)
            window.isVisible = true
        } catch (t: Throwable) {
            DesktopCrashGuard.recordAndNotify("Startup", t, showDialog = true)
        }
    }
}
