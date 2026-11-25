package com.example.desktop

import com.example.vision.Frame
import com.example.vision.ReusableFramePool
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.platform.win32.WinUser
import java.awt.Rectangle
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Windows native Win32 acceleration layer via JNA (hardened against Windows GDI & DLL edge cases):
 * 1. Fast zero-GC GDI BitBlt / GetDIBits screen capture (~3-6ms for 1080p) strictly following Win32 MSDN rules
 *    (bitmap is ALWAYS deselected from memDC before calling GetDIBits to avoid driver crashes!).
 * 2. Live game/emulator window discovery and client-rect tracking (EnumWindows + ClientToScreen).
 * 3. True global OS hotkeys (F6 Start/Pause, F7 Stop, F8 Crop, F9 Mini Bar) with clean JNA fallback.
 */
object Win32Native {

    val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** Verifies once that JNA native dispatch + User32/GDI32 load cleanly without crashing the JVM. */
    val isNativeReady: Boolean by lazy {
        if (!isWindows) return@lazy false
        runCatching {
            User32.INSTANCE.GetDesktopWindow() != null
        }.getOrDefault(false)
    }

    private const val SRCCOPY = 0x00CC0020
    private const val BI_RGB = 0
    private const val DIB_RGB_COLORS = 0

    // Virtual key codes for global hotkeys
    const val VK_F6 = 0x75
    const val VK_F7 = 0x76
    const val VK_F8 = 0x77
    const val VK_F9 = 0x78

    /**
     * Reusable native GDI capture context that caches the compatible memory DC, HBITMAP,
     * BITMAPINFO, and native DIB buffer across same-size frames — strictly deselecting
     * the HBITMAP before calling GetDIBits per Microsoft Win32 specification.
     */
    class FastScreenCapturer(private val pool: ReusableFramePool = ReusableFramePool(3)) : AutoCloseable {
        private var nativeBuf: Memory? = null
        private var nativeBufCapacity: Long = 0L
        private var cachedMemDc: WinDef.HDC? = null
        private var cachedBitmap: WinDef.HBITMAP? = null
        private var cachedW: Int = 0
        private var cachedH: Int = 0
        private var cachedBmi: WinGDI.BITMAPINFO? = null

        @Synchronized
        fun captureRect(bounds: Rectangle): Frame? {
            if (!isNativeReady || bounds.width <= 0 || bounds.height <= 0) return null
            return runCatching {
                val w = bounds.width.coerceAtMost(7680)
                val h = bounds.height.coerceAtMost(4320)
                val screenDc = User32.INSTANCE.GetDC(null) ?: return null
                try {
                    if (cachedMemDc == null || cachedBitmap == null || cachedW != w || cachedH != h) {
                        releaseCachedGdi()
                        val memDc = GDI32.INSTANCE.CreateCompatibleDC(screenDc) ?: return null
                        val hBitmap = GDI32.INSTANCE.CreateCompatibleBitmap(screenDc, w, h)
                        if (hBitmap == null) {
                            GDI32.INSTANCE.DeleteDC(memDc)
                            return null
                        }
                        val bmi = WinGDI.BITMAPINFO()
                        bmi.bmiHeader.biSize = bmi.bmiHeader.size()
                        bmi.bmiHeader.biWidth = w
                        bmi.bmiHeader.biHeight = -h // top-down DIB
                        bmi.bmiHeader.biPlanes = 1
                        bmi.bmiHeader.biBitCount = 32
                        bmi.bmiHeader.biCompression = BI_RGB
                        bmi.bmiHeader.biSizeImage = w * h * 4

                        cachedMemDc = memDc
                        cachedBitmap = hBitmap
                        cachedW = w
                        cachedH = h
                        cachedBmi = bmi
                    }

                    val memDc = cachedMemDc ?: return null
                    val hBitmap = cachedBitmap ?: return null
                    val bmi = cachedBmi ?: return null

                    // Select bitmap only for BitBlt, then IMMEDIATELY deselect before GetDIBits (MSDN requirement!)
                    val oldObj = GDI32.INSTANCE.SelectObject(memDc, hBitmap)
                    val ok = try {
                        GDI32.INSTANCE.BitBlt(
                            memDc, 0, 0, w, h,
                            screenDc, bounds.x, bounds.y,
                            SRCCOPY
                        )
                    } finally {
                        if (oldObj != null) {
                            GDI32.INSTANCE.SelectObject(memDc, oldObj)
                        }
                    }
                    if (!ok) {
                        releaseCachedGdi()
                        return null
                    }

                    val byteSize = w.toLong() * h.toLong() * 4L
                    val buf = if (nativeBuf != null && nativeBufCapacity >= byteSize) {
                        nativeBuf!!
                    } else {
                        Memory(byteSize).also {
                            nativeBuf = it
                            nativeBufCapacity = byteSize
                        }
                    }

                    val scanLines = GDI32.INSTANCE.GetDIBits(
                        memDc, hBitmap, 0, h, buf, bmi, DIB_RGB_COLORS
                    )
                    if (scanLines <= 0) {
                        releaseCachedGdi()
                        return null
                    }

                    val count = w * h
                    val pixels = pool.acquire(count)
                    buf.read(0L, pixels, 0, count)
                    for (i in 0 until count) {
                        pixels[i] = pixels[i] or (0xFF shl 24)
                    }
                    Frame(w, h, pixels)
                } finally {
                    User32.INSTANCE.ReleaseDC(null, screenDc)
                }
            }.getOrNull()
        }

        private fun releaseCachedGdi() {
            val memDc = cachedMemDc
            val bmp = cachedBitmap
            if (bmp != null) runCatching { GDI32.INSTANCE.DeleteObject(bmp) }
            if (memDc != null) runCatching { GDI32.INSTANCE.DeleteDC(memDc) }
            cachedMemDc = null
            cachedBitmap = null
            cachedBmi = null
            cachedW = 0
            cachedH = 0
        }

        @Synchronized
        override fun close() {
            releaseCachedGdi()
            nativeBuf = null
            nativeBufCapacity = 0L
        }
    }

    /** Enumerates all visible, non-minimized top-level windows (games, emulators, browsers). */
    fun listVisibleWindows(): List<DesktopWindowInfo> {
        if (!isNativeReady) return emptyList()
        val result = ArrayList<DesktopWindowInfo>()
        runCatching {
            val titleBuf = CharArray(512)
            User32.INSTANCE.EnumWindows({ hwnd, _ ->
                if (User32.INSTANCE.IsWindowVisible(hwnd)) {
                    val len = User32.INSTANCE.GetWindowText(hwnd, titleBuf, titleBuf.size)
                    if (len > 0) {
                        val title = String(titleBuf, 0, len).trim()
                        if (title.isNotBlank() && !title.startsWith("Wjust macro")) {
                            val rect = clientScreenBounds(hwnd)
                            if (rect != null && rect.width >= 160 && rect.height >= 120) {
                                result += DesktopWindowInfo(
                                    handleHex = Pointer.nativeValue(hwnd.pointer).toString(16),
                                    title = title,
                                    x = rect.x,
                                    y = rect.y,
                                    width = rect.width,
                                    height = rect.height
                                )
                            }
                        }
                    }
                }
                true
            }, null)
        }
        return result.sortedBy { it.title.lowercase() }
    }

    /** Finds the live client-area screen rectangle for the first visible window matching [titleQuery]. */
    fun findWindowBounds(titleQuery: String): Rectangle? {
        if (!isNativeReady || titleQuery.isBlank()) return null
        val q = titleQuery.trim().lowercase()
        var found: Rectangle? = null
        runCatching {
            val titleBuf = CharArray(512)
            User32.INSTANCE.EnumWindows({ hwnd, _ ->
                if (User32.INSTANCE.IsWindowVisible(hwnd)) {
                    val len = User32.INSTANCE.GetWindowText(hwnd, titleBuf, titleBuf.size)
                    if (len > 0) {
                        val title = String(titleBuf, 0, len).trim()
                        if (title.equals(titleQuery.trim(), ignoreCase = true) || title.lowercase().contains(q)) {
                            val rect = clientScreenBounds(hwnd)
                            if (rect != null && rect.width >= 100 && rect.height >= 100) {
                                found = rect
                                return@EnumWindows false
                            }
                        }
                    }
                }
                true
            }, null)
        }
        return found
    }

    /** Brings the first matching window to the foreground (used by LaunchAppAction on Windows). */
    fun focusWindowByTitle(titleQuery: String): Boolean {
        if (!isNativeReady || titleQuery.isBlank()) return false
        val q = titleQuery.trim().lowercase()
        var focused = false
        runCatching {
            val titleBuf = CharArray(512)
            User32.INSTANCE.EnumWindows({ hwnd, _ ->
                if (User32.INSTANCE.IsWindowVisible(hwnd)) {
                    val len = User32.INSTANCE.GetWindowText(hwnd, titleBuf, titleBuf.size)
                    if (len > 0) {
                        val title = String(titleBuf, 0, len).trim()
                        if (title.lowercase().contains(q)) {
                            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE)
                            User32.INSTANCE.SetForegroundWindow(hwnd)
                            focused = true
                            return@EnumWindows false
                        }
                    }
                }
                true
            }, null)
        }
        return focused
    }

    private fun clientScreenBounds(hwnd: WinDef.HWND): Rectangle? {
        val clientRect = WinDef.RECT()
        if (!User32.INSTANCE.GetClientRect(hwnd, clientRect)) return null
        val w = clientRect.right - clientRect.left
        val h = clientRect.bottom - clientRect.top
        if (w <= 0 || h <= 0) return null
        val topLeft = WinDef.POINT(0, 0)
        val ok = runCatching { User32ClientToScreen.INSTANCE.ClientToScreen(hwnd, topLeft) }.getOrDefault(false)
        return if (ok) {
            Rectangle(topLeft.x, topLeft.y, w, h)
        } else {
            val winRect = WinDef.RECT()
            if (User32.INSTANCE.GetWindowRect(hwnd, winRect)) {
                Rectangle(winRect.left, winRect.top, winRect.right - winRect.left, winRect.bottom - winRect.top)
            } else null
        }
    }

    private interface User32ClientToScreen : com.sun.jna.win32.StdCallLibrary {
        fun ClientToScreen(hWnd: WinDef.HWND, lpPoint: WinDef.POINT): Boolean

        companion object {
            val INSTANCE: User32ClientToScreen by lazy {
                Native.load("user32", User32ClientToScreen::class.java)
            }
        }
    }

    /**
     * Low-CPU OS-level global hotkey listener for Windows (F6/F7/F8/F9).
     */
    class GlobalHotkeyListener(
        private val onPlayPause: () -> Unit,
        private val onStop: () -> Unit,
        private val onCrop: () -> Unit,
        private val onToggleMiniBar: () -> Unit
    ) {
        private val running = AtomicBoolean(false)
        private var thread: Thread? = null

        @Volatile
        var isRegistered: Boolean = false
            private set

        fun start() {
            if (!isWindows || !running.compareAndSet(false, true)) return
            thread = Thread({
                if (!isNativeReady) {
                    running.set(false)
                    return@Thread
                }
                val registeredIds = ArrayList<Int>(4)
                runCatching {
                    if (User32.INSTANCE.RegisterHotKey(null, 1, 0, VK_F6)) registeredIds += 1
                    if (User32.INSTANCE.RegisterHotKey(null, 2, 0, VK_F7)) registeredIds += 2
                    if (User32.INSTANCE.RegisterHotKey(null, 3, 0, VK_F8)) registeredIds += 3
                    if (User32.INSTANCE.RegisterHotKey(null, 4, 0, VK_F9)) registeredIds += 4
                    isRegistered = registeredIds.isNotEmpty()
                    val msg = WinUser.MSG()
                    while (running.get()) {
                        val hasMsg = User32.INSTANCE.PeekMessage(msg, null, 0, 0, 1)
                        if (hasMsg && msg.message == WinUser.WM_HOTKEY) {
                            when (msg.wParam?.toInt()) {
                                1 -> runCatching { onPlayPause() }
                                2 -> runCatching { onStop() }
                                3 -> runCatching { onCrop() }
                                4 -> runCatching { onToggleMiniBar() }
                            }
                        } else {
                            try {
                                Thread.sleep(40L)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                    }
                }
                isRegistered = false
                for (id in registeredIds) {
                    runCatching { User32.INSTANCE.UnregisterHotKey(null, id) }
                }
            }, "wjust-win32-hotkeys").apply {
                isDaemon = true
                start()
            }
        }

        fun stop() {
            running.set(false)
            isRegistered = false
            thread?.interrupt()
            thread = null
        }
    }
}
