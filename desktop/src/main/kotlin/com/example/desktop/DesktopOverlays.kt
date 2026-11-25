package com.example.desktop

import com.example.data.MacroSecurity
import com.example.data.RecordedGesture
import com.example.data.RecordedStroke
import com.example.data.Region
import com.example.vision.ColorUtil
import com.example.vision.MatchResult
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.EmptyBorder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Crash-safe native desktop overlays (HiDPI-clamped, zero hidden-owner modal deadlocks,
 * and Android-matching FloatingMiniBar with crisp vector icons):
 * - Region / Template Snipping overlay
 * - 8x Magnifier Point & RGB/Hex Color Picker (single or dual A->B swipe)
 * - Live Mouse Gesture Recorder
 * - Click-through green Match Highlight Box (checks Windows translucency support before setting alpha)
 * - Always-on-Top FloatingMiniBar styled after the Android Floating Overlay Bar
 */
object DesktopOverlays {

    private fun safeScreenBounds(bounds: Rectangle): Rectangle {
        val w = bounds.width.coerceAtLeast(100)
        val h = bounds.height.coerceAtLeast(100)
        return Rectangle(bounds.x, bounds.y, w, h)
    }

    /** Opens a live snipping overlay over [screenshot]. Calls [onClosed] on cancel/ESC as well as completion. */
    fun openRegionOrCropOverlay(
        screenshot: BufferedImage,
        bounds: Rectangle,
        titleHint: String,
        onClosed: () -> Unit = {},
        onResult: (region: Region, croppedImage: BufferedImage) -> Unit
    ) {
        val safeBounds = safeScreenBounds(bounds)
        val frame = JFrame(titleHint)
        frame.isUndecorated = true
        frame.bounds = safeBounds
        frame.isAlwaysOnTop = true
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE

        var finished = false
        fun finish(block: (() -> Unit)? = null) {
            if (finished) return
            finished = true
            frame.isVisible = false
            frame.dispose()
            onClosed()
            block?.invoke()
        }

        var startPt: Point? = null
        var curPt: Point? = null

        val canvas = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g as Graphics2D
                g2.drawImage(screenshot, 0, 0, width, height, null)
                g2.color = Color(0, 0, 0, 120)
                g2.fillRect(0, 0, width, height)

                g2.color = Color(16, 25, 45, 235)
                g2.fillRoundRect(16, 14, min(width - 32, 540), 36, 12, 12)
                g2.color = DesktopTheme.CYAN
                g2.font = DesktopTheme.uiFont(13f, Font.BOLD)
                g2.drawString("$titleHint — Seret kotak di layar (ESC untuk batal)", 28, 37)

                val p1 = startPt
                val p2 = curPt
                if (p1 != null && p2 != null) {
                    val x = min(p1.x, p2.x).coerceIn(0, max(0, width - 1))
                    val y = min(p1.y, p2.y).coerceIn(0, max(0, height - 1))
                    val w = max(1, abs(p2.x - p1.x)).coerceAtMost(max(1, width - x))
                    val h = max(1, abs(p2.y - p1.y)).coerceAtMost(max(1, height - y))
                    val sx = screenshot.width.toDouble() / width.coerceAtLeast(1)
                    val sy = screenshot.height.toDouble() / height.coerceAtLeast(1)
                    val ix1 = (x * sx).toInt().coerceIn(0, screenshot.width - 1)
                    val iy1 = (y * sy).toInt().coerceIn(0, screenshot.height - 1)
                    val ix2 = ((x + w) * sx).toInt().coerceIn(ix1 + 1, screenshot.width)
                    val iy2 = ((y + h) * sy).toInt().coerceIn(iy1 + 1, screenshot.height)
                    g2.drawImage(screenshot, x, y, x + w, y + h, ix1, iy1, ix2, iy2, null)
                    g2.color = DesktopTheme.CYAN
                    g2.stroke = BasicStroke(2f)
                    g2.drawRect(x, y, w, h)
                    val label = "${ix2 - ix1}x${iy2 - iy1} px  ($ix1, $iy1)"
                    g2.color = Color(16, 25, 45, 235)
                    g2.fillRoundRect(x, max(4, y - 24), 190, 20, 8, 8)
                    g2.color = DesktopTheme.CYAN
                    g2.font = DesktopTheme.monoFont(11f, Font.BOLD)
                    g2.drawString(label, x + 6, max(18, y - 10))
                }
            }
        }
        canvas.cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
        canvas.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                startPt = e.point
                curPt = e.point
                canvas.repaint()
            }
            override fun mouseReleased(e: MouseEvent) {
                val p1 = startPt ?: return
                val p2 = e.point
                val sx = screenshot.width.toDouble() / canvas.width.coerceAtLeast(1)
                val sy = screenshot.height.toDouble() / canvas.height.coerceAtLeast(1)
                val x1 = (min(p1.x, p2.x) * sx).toInt().coerceIn(0, max(0, screenshot.width - 2))
                val y1 = (min(p1.y, p2.y) * sy).toInt().coerceIn(0, max(0, screenshot.height - 2))
                val x2 = (max(p1.x, p2.x) * sx).toInt().coerceIn(x1 + 1, screenshot.width)
                val y2 = (max(p1.y, p2.y) * sy).toInt().coerceIn(y1 + 1, screenshot.height)
                val cw = (x2 - x1).coerceIn(1, screenshot.width - x1)
                val ch = (y2 - y1).coerceIn(1, screenshot.height - y1)
                if (cw >= 6 && ch >= 6) {
                    val crop = BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB).also { dst ->
                        val g = dst.createGraphics()
                        g.drawImage(screenshot, 0, 0, cw, ch, x1, y1, x1 + cw, y1 + ch, null)
                        g.dispose()
                    }
                    finish { onResult(Region(x1, y1, x1 + cw, y1 + ch), crop) }
                } else {
                    startPt = null
                    curPt = null
                    canvas.repaint()
                }
            }
        })
        canvas.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                curPt = e.point
                canvas.repaint()
            }
        })
        frame.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) finish()
            }
        })
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) = finish()
        })
        frame.contentPane = canvas
        frame.isVisible = true
        frame.toFront()
        frame.requestFocus()
    }

    /** Opens a live target picker with 8x Magnifier & RGB/Hex Color Spy. */
    fun openPointPickerOverlay(
        screenshot: BufferedImage,
        bounds: Rectangle,
        dualPoint: Boolean,
        titleHint: String,
        onClosed: () -> Unit = {},
        onPicked: (x1: Int, y1: Int, x2: Int, y2: Int, hexColor: String) -> Unit
    ) {
        val safeBounds = safeScreenBounds(bounds)
        val frame = JFrame(titleHint)
        frame.isUndecorated = true
        frame.bounds = safeBounds
        frame.isAlwaysOnTop = true
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE

        var finished = false
        fun finish(block: (() -> Unit)? = null) {
            if (finished) return
            finished = true
            frame.isVisible = false
            frame.dispose()
            onClosed()
            block?.invoke()
        }

        var mousePt = Point(safeBounds.width / 2, safeBounds.height / 2)
        var firstPt: Point? = null

        val canvas = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.drawImage(screenshot, 0, 0, width, height, null)
                g2.color = Color(0, 0, 0, 75)
                g2.fillRect(0, 0, width, height)

                val sx = screenshot.width.toDouble() / width.coerceAtLeast(1)
                val sy = screenshot.height.toDouble() / height.coerceAtLeast(1)
                val imgX = (mousePt.x * sx).toInt().coerceIn(0, max(0, screenshot.width - 1))
                val imgY = (mousePt.y * sy).toInt().coerceIn(0, max(0, screenshot.height - 1))
                val argb = screenshot.getRGB(imgX, imgY)
                val hex = ColorUtil.toHex(argb)

                g2.color = Color(16, 25, 45, 235)
                g2.fillRoundRect(16, 14, min(width - 32, 560), 36, 12, 12)
                g2.color = DesktopTheme.CYAN
                g2.font = DesktopTheme.uiFont(13f, Font.BOLD)
                val stepMsg = if (dualPoint) {
                    if (firstPt == null) "$titleHint — Klik Titik AWAL (A)  |  ESC batal"
                    else "$titleHint — Klik Titik TUJUAN (B)  |  ESC batal"
                } else {
                    "$titleHint — Klik titik target di layar  |  ESC batal"
                }
                g2.drawString(stepMsg, 28, 37)

                firstPt?.let { p1 ->
                    g2.color = DesktopTheme.EMERALD
                    g2.stroke = BasicStroke(2.5f)
                    g2.drawLine(p1.x, p1.y, mousePt.x, mousePt.y)
                    g2.fillOval(p1.x - 6, p1.y - 6, 12, 12)
                }

                g2.color = DesktopTheme.CYAN
                g2.stroke = BasicStroke(1.5f)
                g2.drawLine(mousePt.x - 16, mousePt.y, mousePt.x + 16, mousePt.y)
                g2.drawLine(mousePt.x, mousePt.y - 16, mousePt.x, mousePt.y + 16)

                val loupeW = 144
                val loupeH = 144
                val lx = (if (mousePt.x + 24 + loupeW < width) mousePt.x + 24 else mousePt.x - 24 - loupeW)
                    .coerceIn(8, max(8, width - loupeW - 8))
                val ly = (if (mousePt.y + 24 + loupeH + 34 < height) mousePt.y + 24 else mousePt.y - 24 - loupeH - 34)
                    .coerceIn(56, max(56, height - loupeH - 42))
                g2.color = Color(16, 25, 45, 240)
                g2.fillRoundRect(lx - 4, ly - 4, loupeW + 8, loupeH + 36, 12, 12)
                val patchR = 9
                val sx0 = (imgX - patchR).coerceIn(0, max(0, screenshot.width - 1))
                val sy0 = (imgY - patchR).coerceIn(0, max(0, screenshot.height - 1))
                val sx1 = (imgX + patchR + 1).coerceIn(sx0 + 1, screenshot.width)
                val sy1 = (imgY + patchR + 1).coerceIn(sy0 + 1, screenshot.height)
                g2.drawImage(screenshot, lx, ly, lx + loupeW, ly + loupeH, sx0, sy0, sx1, sy1, null)
                g2.color = DesktopTheme.CYAN
                g2.drawRect(lx + loupeW / 2 - 4, ly + loupeH / 2 - 4, 8, 8)
                g2.drawRect(lx, ly, loupeW, loupeH)

                g2.color = Color(argb, false)
                g2.fillRoundRect(lx + 4, ly + loupeH + 6, 20, 20, 6, 6)
                g2.color = Color.WHITE
                g2.font = DesktopTheme.monoFont(11f, Font.BOLD)
                g2.drawString("($imgX,$imgY) $hex", lx + 30, ly + loupeH + 20)
            }
        }
        canvas.cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
        canvas.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) { mousePt = e.point; canvas.repaint() }
            override fun mouseDragged(e: MouseEvent) { mousePt = e.point; canvas.repaint() }
        })
        canvas.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val sx = screenshot.width.toDouble() / canvas.width.coerceAtLeast(1)
                val sy = screenshot.height.toDouble() / canvas.height.coerceAtLeast(1)
                val px = (e.x * sx).toInt().coerceIn(0, max(0, screenshot.width - 1))
                val py = (e.y * sy).toInt().coerceIn(0, max(0, screenshot.height - 1))
                val hex = ColorUtil.toHex(screenshot.getRGB(px, py))
                if (!dualPoint) {
                    finish { onPicked(px, py, px, py, hex) }
                } else {
                    val p1 = firstPt
                    if (p1 == null) {
                        firstPt = e.point
                        canvas.repaint()
                    } else {
                        val ax = (p1.x * sx).toInt().coerceIn(0, max(0, screenshot.width - 1))
                        val ay = (p1.y * sy).toInt().coerceIn(0, max(0, screenshot.height - 1))
                        finish { onPicked(ax, ay, px, py, hex) }
                    }
                }
            }
        })
        frame.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) finish()
            }
        })
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) = finish()
        })
        frame.contentPane = canvas
        frame.isVisible = true
        frame.toFront()
        frame.requestFocus()
    }

    /** Records a sequence of mouse clicks & drag paths directly over the live target screenshot. */
    fun openMouseRecorderOverlay(
        screenshot: BufferedImage,
        bounds: Rectangle,
        onClosed: () -> Unit = {},
        onFinished: (RecordedGesture) -> Unit
    ) {
        val safeBounds = safeScreenBounds(bounds)
        val frame = JFrame("Rekam Gerakan Mouse")
        frame.isUndecorated = true
        frame.bounds = safeBounds
        frame.isAlwaysOnTop = true
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE

        var finished = false
        fun finish(block: (() -> Unit)? = null) {
            if (finished) return
            finished = true
            frame.isVisible = false
            frame.dispose()
            onClosed()
            block?.invoke()
        }

        val strokes = ArrayList<RecordedStroke>()
        var recordStartMs = 0L
        var strokeStartMs = 0L
        var curPoints = ArrayList<Int>()

        val canvas = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.drawImage(screenshot, 0, 0, width, height, null)
                g2.color = Color(0, 0, 0, 95)
                g2.fillRect(0, 0, width, height)

                g2.color = Color(16, 25, 45, 235)
                g2.fillRoundRect(16, 14, min(width - 32, 640), 38, 12, 12)
                g2.color = DesktopTheme.ROSE
                g2.font = DesktopTheme.uiFont(13f, Font.BOLD)
                g2.drawString(
                    "REKAM GERAKAN (${strokes.size} goresan) — Klik/Seret di layar, ENTER simpan atau ESC batal",
                    28, 38
                )

                val invSx = width.toDouble() / screenshot.width.coerceAtLeast(1)
                val invSy = height.toDouble() / screenshot.height.coerceAtLeast(1)
                g2.stroke = BasicStroke(3f)
                g2.color = DesktopTheme.CYAN
                for (st in strokes) {
                    var i = 0
                    while (i + 3 < st.points.size) {
                        g2.drawLine(
                            (st.points[i] * invSx).toInt(), (st.points[i + 1] * invSy).toInt(),
                            (st.points[i + 2] * invSx).toInt(), (st.points[i + 3] * invSy).toInt()
                        )
                        i += 2
                    }
                    if (st.points.size >= 2) {
                        g2.fillOval((st.points[0] * invSx).toInt() - 4, (st.points[1] * invSy).toInt() - 4, 8, 8)
                    }
                }
                g2.color = DesktopTheme.AMBER
                var i = 0
                while (i + 3 < curPoints.size) {
                    g2.drawLine(
                        (curPoints[i] * invSx).toInt(), (curPoints[i + 1] * invSy).toInt(),
                        (curPoints[i + 2] * invSx).toInt(), (curPoints[i + 3] * invSy).toInt()
                    )
                    i += 2
                }
            }
        }
        canvas.cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
        canvas.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val now = System.currentTimeMillis()
                if (recordStartMs == 0L) recordStartMs = now
                strokeStartMs = now
                val sx = screenshot.width.toDouble() / canvas.width.coerceAtLeast(1)
                val sy = screenshot.height.toDouble() / canvas.height.coerceAtLeast(1)
                curPoints = arrayListOf((e.x * sx).toInt(), (e.y * sy).toInt())
                canvas.repaint()
            }
            override fun mouseReleased(e: MouseEvent) {
                if (curPoints.size < 2) return
                val now = System.currentTimeMillis()
                val sx = screenshot.width.toDouble() / canvas.width.coerceAtLeast(1)
                val sy = screenshot.height.toDouble() / canvas.height.coerceAtLeast(1)
                curPoints.add((e.x * sx).toInt())
                curPoints.add((e.y * sy).toInt())
                strokes += RecordedStroke(
                    startMs = (strokeStartMs - recordStartMs).coerceAtLeast(0L),
                    durationMs = (now - strokeStartMs).coerceAtLeast(40L),
                    points = curPoints.toList()
                )
                curPoints = ArrayList()
                canvas.repaint()
            }
        })
        canvas.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                if (curPoints.size < 2) return
                val sx = screenshot.width.toDouble() / canvas.width.coerceAtLeast(1)
                val sy = screenshot.height.toDouble() / canvas.height.coerceAtLeast(1)
                curPoints.add((e.x * sx).toInt())
                curPoints.add((e.y * sy).toInt())
                canvas.repaint()
            }
        })
        frame.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ESCAPE -> finish()
                    KeyEvent.VK_ENTER -> {
                        if (strokes.isNotEmpty()) {
                            finish { onFinished(RecordedGesture(delayBeforeMs = 0L, strokes = strokes.toList())) }
                        } else {
                            finish()
                        }
                    }
                }
            }
        })
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) = finish()
        })
        frame.contentPane = canvas
        frame.isVisible = true
        frame.toFront()
        frame.requestFocus()
    }

    /** Flashes a non-blocking transparent neon box where a target image or moving troop was detected. */
    fun flashMatchBox(match: MatchResult, label: String, offsetX: Int, offsetY: Int) {
        SwingUtilities.invokeLater {
            runCatching {
                val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
                val gd = ge.defaultScreenDevice
                if (!gd.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
                    return@runCatching
                }
                val win = JWindow()
                win.isAlwaysOnTop = true
                win.focusableWindowState = false
                win.background = Color(0, 0, 0, 0)
                val pad = 4
                val topLabelH = 22
                win.setBounds(
                    offsetX + match.x - pad,
                    (offsetY + match.y - topLabelH - pad).coerceAtLeast(0),
                    (match.width + pad * 2).coerceAtLeast(130),
                    match.height + topLabelH + pad * 2
                )
                win.contentPane = object : JPanel() {
                    init { isOpaque = false }
                    override fun paintComponent(g: Graphics) {
                        val g2 = g as Graphics2D
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.color = Color(16, 185, 129, 225)
                        g2.fillRoundRect(0, 0, min(width, 190), topLabelH - 2, 8, 8)
                        g2.color = DesktopTheme.ON_CYAN
                        g2.font = DesktopTheme.monoFont(11f, Font.BOLD)
                        g2.drawString(label.take(24), 6, 14)

                        g2.color = DesktopTheme.EMERALD
                        g2.stroke = BasicStroke(2.5f)
                        g2.drawRect(pad, topLabelH + pad, match.width, match.height)
                    }
                }
                win.isVisible = true
                Timer(520) {
                    win.isVisible = false
                    win.dispose()
                }.apply { isRepeats = false; start() }
            }
        }
    }

    /**
     * Compact Always-on-Top Floating Overlay Bar styled after the Android Wjust macro floating bar.
     * Uses solid Midnight Navy surface (never non-opaque color on opaque JPanel) for zero Windows driver crashes.
     */
    class FloatingMiniBar(
        private val coordinator: DesktopCoordinator,
        private val onQuickCrop: () -> Unit,
        private val onShowMainWindow: () -> Unit
    ) : JWindow() {

        private val titleLbl = JLabel("Wjust macro").apply {
            font = DesktopTheme.uiFont(12f, Font.BOLD)
            foreground = DesktopTheme.TEXT_PRIMARY
        }
        private val statsLbl = JLabel("Siap").apply {
            font = DesktopTheme.monoFont(11f, Font.PLAIN)
            foreground = DesktopTheme.CYAN
        }
        private val playBtn: DesktopTheme.PillButton
        private val stopBtn: DesktopTheme.PillButton
        private val passiveBtn: DesktopTheme.PillButton
        private var dragOffset: Point? = null

        init {
            isAlwaysOnTop = true
            focusableWindowState = false
            val root = JPanel(BorderLayout(8, 6)).apply {
                isOpaque = true
                background = DesktopTheme.BG_SURFACE
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(DesktopTheme.CYAN, 1, true),
                    EmptyBorder(8, 10, 8, 10)
                )
            }

            val topRow = JPanel(FlowLayout(FlowLayout.LEFT, 7, 0)).apply { isOpaque = false }
            DesktopTheme.logoIcon(22)?.let { topRow.add(JLabel(it)) }
            val txtCol = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(titleLbl)
                add(statsLbl)
            }
            topRow.add(txtCol)

            val btnRow = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply { isOpaque = false }
            playBtn = DesktopTheme.primaryButton("F6", DesktopTheme.EMERALD, DesktopTheme.ON_CYAN, PcIconKind.PLAY, radius = 10, padH = 10, padV = 5) {
                coordinator.togglePlayPause()
            }
            stopBtn = DesktopTheme.primaryButton("F7", DesktopTheme.ROSE, Color.WHITE, PcIconKind.STOP, radius = 10, padH = 9, padV = 5) {
                coordinator.stop()
            }
            val cropBtn = DesktopTheme.tonalButton("F8", DesktopTheme.BG_ELEVATED, DesktopTheme.CYAN, PcIconKind.CROP, radius = 10, padH = 9, padV = 5) {
                onQuickCrop()
            }
            passiveBtn = DesktopTheme.tonalButton("Pasif", DesktopTheme.BG_ELEVATED, DesktopTheme.EMERALD, PcIconKind.LINK, radius = 10, padH = 9, padV = 5) {
                toggleFirstPassive()
            }
            val studioBtn = DesktopTheme.outlinedButton("App", DesktopTheme.TEXT_PRIMARY, DesktopTheme.BORDER_SUBTLE, PcIconKind.LAYERS, radius = 10, padH = 9, padV = 5) {
                onShowMainWindow()
            }
            val hideBtn = DesktopTheme.iconButton(PcIconKind.CLOSE, DesktopTheme.TEXT_SECONDARY, "Tutup Bar", size = 28) {
                isVisible = false
            }

            btnRow.add(playBtn)
            btnRow.add(stopBtn)
            btnRow.add(cropBtn)
            btnRow.add(passiveBtn)
            btnRow.add(studioBtn)
            btnRow.add(Box.createHorizontalStrut(2))
            btnRow.add(hideBtn)

            root.add(topRow, BorderLayout.NORTH)
            root.add(btnRow, BorderLayout.SOUTH)

            root.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) { dragOffset = e.point }
                override fun mouseReleased(e: MouseEvent) { dragOffset = null }
            })
            root.addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    val off = dragOffset ?: return
                    setLocation(e.xOnScreen - off.x, e.yOnScreen - off.y)
                }
            })

            contentPane = root
            pack()
            setLocation(28, 28)
        }

        private fun toggleFirstPassive() {
            val now = coordinator.store.effectiveNowMs()
            val active = coordinator.activeMacro.value
            val passives = coordinator.store.macros.value.filter { m ->
                val pSet = coordinator.store.getMacroSetById(m.setId)
                m.isPassive &&
                    !MacroSecurity.isTampered(m) && !MacroSecurity.isExpired(m, now) &&
                    (pSet == null || (!MacroSecurity.isSetTampered(pSet) && !MacroSecurity.isSetExpired(pSet, now))) &&
                    (active.setId.isBlank() || m.setId == active.setId)
            }
            val target = passives.firstOrNull() ?: return
            val updated = MacroSecurity.togglePassiveEnabled(target, !target.passiveEnabled)
            coordinator.store.saveMacro(updated)
            refreshState()
        }

        fun refreshState() {
            SwingUtilities.invokeLater {
                runCatching {
                    val m = coordinator.activeMacro.value
                    val st = coordinator.engineState.value
                    val perf = coordinator.perfStats.value
                    titleLbl.text = m.name.ifBlank { "Wjust macro" }.take(22)
                    statsLbl.text = when (st) {
                        DesktopEngineState.RUNNING -> "Berjalan • Loop ${coordinator.currentLoop.value} • ${coordinator.actionsExecuted.value} aksi"
                        DesktopEngineState.PAUSED -> "Dijeda (tekan F6 lanjut)"
                        DesktopEngineState.ERROR -> "Error • cek Live Log"
                        DesktopEngineState.IDLE -> "Siap • ${perf.ramUsedMb}MB"
                    }
                    statsLbl.foreground = when (st) {
                        DesktopEngineState.RUNNING -> DesktopTheme.EMERALD
                        DesktopEngineState.PAUSED -> DesktopTheme.AMBER
                        DesktopEngineState.ERROR -> DesktopTheme.ROSE
                        DesktopEngineState.IDLE -> DesktopTheme.CYAN
                    }
                    when (st) {
                        DesktopEngineState.RUNNING -> {
                            playBtn.text = "Jeda"
                            playBtn.icon = DesktopTheme.icon(PcIconKind.PAUSE, 15, DesktopTheme.ON_CYAN)
                            playBtn.updateColors(DesktopTheme.AMBER, DesktopTheme.ON_CYAN)
                        }
                        DesktopEngineState.PAUSED -> {
                            playBtn.text = "Lanjut"
                            playBtn.icon = DesktopTheme.icon(PcIconKind.PLAY, 15, DesktopTheme.ON_CYAN)
                            playBtn.updateColors(DesktopTheme.EMERALD, DesktopTheme.ON_CYAN)
                        }
                        else -> {
                            playBtn.text = "F6"
                            playBtn.icon = DesktopTheme.icon(PcIconKind.PLAY, 15, DesktopTheme.ON_CYAN)
                            playBtn.updateColors(DesktopTheme.EMERALD, DesktopTheme.ON_CYAN)
                        }
                    }
                    val passiveCount = coordinator.store.macros.value.count { it.isPassive }
                    passiveBtn.isVisible = passiveCount > 0
                    pack()
                }
            }
        }
    }
}
