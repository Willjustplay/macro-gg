package com.example.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon

/**
 * Crisp Java2D Material-style vector icons matching the Android app's Material Icons.
 * Eliminates missing-glyph tofu boxes (`□`) on Windows when using custom UI fonts.
 */
enum class PcIconKind {
    TOUCH_APP,
    IMAGE_SEARCH,
    CODE,
    LAYERS,
    PLAY,
    PAUSE,
    STOP,
    ADD,
    UPLOAD,
    DOWNLOAD,
    FOLDER,
    FOLDER_OPEN,
    FOLDER_NEW,
    LOCK,
    UNLOCK,
    EDIT,
    MORE_VERT,
    BACK,
    SETTINGS,
    CROP,
    TARGET,
    TRASH,
    COPY,
    CHECK_CIRCLE,
    WARNING,
    EXPAND_MORE,
    EXPAND_LESS,
    ARROW_UP,
    ARROW_DOWN,
    LINK,
    LINK_OFF,
    SHIELD,
    LOG,
    RECORD,
    SCALE,
    REFRESH,
    CLOSE
}

class PcVectorIcon(
    private val kind: PcIconKind,
    private val size: Int = 18,
    private val color: Color = DesktopTheme.TEXT_PRIMARY
) : Icon {

    override fun getIconWidth(): Int = size
    override fun getIconHeight(): Int = size

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g2.translate(x, y)
        val s = size / 24.0
        g2.scale(s, s)
        g2.color = color
        val sw = BasicStroke(2.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.stroke = sw

        when (kind) {
            PcIconKind.PLAY -> {
                val p = Polygon(intArrayOf(7, 7, 19), intArrayOf(5, 19, 12), 3)
                g2.fill(p)
            }
            PcIconKind.PAUSE -> {
                g2.fill(RoundRectangle2D.Double(6.0, 5.0, 4.2, 14.0, 2.0, 2.0))
                g2.fill(RoundRectangle2D.Double(13.8, 5.0, 4.2, 14.0, 2.0, 2.0))
            }
            PcIconKind.STOP -> {
                g2.fill(RoundRectangle2D.Double(6.0, 6.0, 12.0, 12.0, 2.5, 2.5))
            }
            PcIconKind.ADD -> {
                g2.draw(Line2D.Double(12.0, 5.0, 12.0, 19.0))
                g2.draw(Line2D.Double(5.0, 12.0, 19.0, 12.0))
            }
            PcIconKind.CLOSE -> {
                g2.draw(Line2D.Double(6.5, 6.5, 17.5, 17.5))
                g2.draw(Line2D.Double(17.5, 6.5, 6.5, 17.5))
            }
            PcIconKind.TOUCH_APP -> {
                g2.draw(Arc2D.Double(5.5, 2.5, 13.0, 13.0, 25.0, 130.0, Arc2D.OPEN))
                g2.fill(Ellipse2D.Double(9.5, 7.0, 5.0, 5.0))
                g2.draw(RoundRectangle2D.Double(9.8, 9.5, 4.4, 11.0, 4.0, 4.0))
            }
            PcIconKind.IMAGE_SEARCH -> {
                g2.draw(RoundRectangle2D.Double(3.0, 4.0, 15.0, 14.0, 3.0, 3.0))
                g2.draw(Line2D.Double(4.5, 15.5, 8.5, 11.0))
                g2.draw(Line2D.Double(8.5, 11.0, 12.0, 14.0))
                g2.fill(Ellipse2D.Double(14.5, 14.5, 6.0, 6.0))
                g2.color = DesktopTheme.BG_BASE
                g2.fill(Ellipse2D.Double(16.0, 16.0, 3.0, 3.0))
                g2.color = color
                g2.draw(Line2D.Double(19.5, 19.5, 22.0, 22.0))
            }
            PcIconKind.CODE -> {
                val left = Path2D.Double().apply { moveTo(9.0, 7.0); lineTo(4.0, 12.0); lineTo(9.0, 17.0) }
                val right = Path2D.Double().apply { moveTo(15.0, 7.0); lineTo(20.0, 12.0); lineTo(15.0, 17.0) }
                g2.draw(left); g2.draw(right)
                g2.draw(Line2D.Double(13.2, 5.5, 10.8, 18.5))
            }
            PcIconKind.LAYERS -> {
                val top = Path2D.Double().apply {
                    moveTo(12.0, 3.5); lineTo(20.5, 8.5); lineTo(12.0, 13.5); lineTo(3.5, 8.5); closePath()
                }
                g2.draw(top)
                val mid = Path2D.Double().apply { moveTo(4.0, 12.5); lineTo(12.0, 17.0); lineTo(20.0, 12.5) }
                val bot = Path2D.Double().apply { moveTo(4.0, 16.2); lineTo(12.0, 20.7); lineTo(20.0, 16.2) }
                g2.draw(mid); g2.draw(bot)
            }
            PcIconKind.UPLOAD -> {
                g2.draw(Line2D.Double(12.0, 15.5, 12.0, 4.5))
                val head = Path2D.Double().apply { moveTo(7.5, 9.0); lineTo(12.0, 4.5); lineTo(16.5, 9.0) }
                g2.draw(head)
                val tray = Path2D.Double().apply { moveTo(4.5, 16.0); lineTo(4.5, 19.5); lineTo(19.5, 19.5); lineTo(19.5, 16.0) }
                g2.draw(tray)
            }
            PcIconKind.DOWNLOAD -> {
                g2.draw(Line2D.Double(12.0, 4.5, 12.0, 15.5))
                val head = Path2D.Double().apply { moveTo(7.5, 11.0); lineTo(12.0, 15.5); lineTo(16.5, 11.0) }
                g2.draw(head)
                val tray = Path2D.Double().apply { moveTo(4.5, 16.0); lineTo(4.5, 19.5); lineTo(19.5, 19.5); lineTo(19.5, 16.0) }
                g2.draw(tray)
            }
            PcIconKind.FOLDER, PcIconKind.FOLDER_OPEN, PcIconKind.FOLDER_NEW -> {
                val p = Path2D.Double().apply {
                    moveTo(3.0, 7.0); lineTo(3.0, 18.5); lineTo(21.0, 18.5); lineTo(21.0, 8.5)
                    lineTo(11.5, 8.5); lineTo(9.5, 5.5); lineTo(4.5, 5.5); closePath()
                }
                g2.draw(p)
                if (kind == PcIconKind.FOLDER_NEW) {
                    g2.draw(Line2D.Double(12.0, 11.0, 12.0, 16.0))
                    g2.draw(Line2D.Double(9.5, 13.5, 14.5, 13.5))
                } else if (kind == PcIconKind.FOLDER_OPEN) {
                    g2.draw(Line2D.Double(5.0, 12.0, 19.0, 12.0))
                }
            }
            PcIconKind.LOCK -> {
                g2.draw(RoundRectangle2D.Double(5.5, 10.5, 13.0, 9.5, 3.0, 3.0))
                g2.draw(Arc2D.Double(8.0, 4.5, 8.0, 10.0, 0.0, 180.0, Arc2D.OPEN))
                g2.fill(Ellipse2D.Double(10.8, 14.0, 2.4, 2.4))
            }
            PcIconKind.UNLOCK -> {
                g2.draw(RoundRectangle2D.Double(5.5, 10.5, 13.0, 9.5, 3.0, 3.0))
                g2.draw(Arc2D.Double(8.0, 4.5, 8.0, 10.0, 20.0, 160.0, Arc2D.OPEN))
            }
            PcIconKind.EDIT -> {
                val p = Path2D.Double().apply {
                    moveTo(4.5, 19.5); lineTo(8.5, 18.5); lineTo(19.0, 8.0); lineTo(16.0, 5.0); lineTo(5.5, 15.5); closePath()
                }
                g2.draw(p)
            }
            PcIconKind.MORE_VERT -> {
                g2.fill(Ellipse2D.Double(10.3, 4.8, 3.4, 3.4))
                g2.fill(Ellipse2D.Double(10.3, 10.3, 3.4, 3.4))
                g2.fill(Ellipse2D.Double(10.3, 15.8, 3.4, 3.4))
            }
            PcIconKind.BACK -> {
                g2.draw(Line2D.Double(19.0, 12.0, 5.5, 12.0))
                val head = Path2D.Double().apply { moveTo(11.0, 6.5); lineTo(5.5, 12.0); lineTo(11.0, 17.5) }
                g2.draw(head)
            }
            PcIconKind.SETTINGS -> {
                g2.draw(Ellipse2D.Double(8.5, 8.5, 7.0, 7.0))
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4.0
                    g2.draw(Line2D.Double(12 + Math.cos(a) * 6.8, 12 + Math.sin(a) * 6.8, 12 + Math.cos(a) * 9.6, 12 + Math.sin(a) * 9.6))
                }
            }
            PcIconKind.CROP -> {
                g2.draw(Line2D.Double(7.0, 3.5, 7.0, 17.0))
                g2.draw(Line2D.Double(7.0, 17.0, 20.5, 17.0))
                g2.draw(Line2D.Double(3.5, 7.0, 17.0, 7.0))
                g2.draw(Line2D.Double(17.0, 7.0, 17.0, 20.5))
            }
            PcIconKind.TARGET -> {
                g2.draw(Ellipse2D.Double(5.5, 5.5, 13.0, 13.0))
                g2.fill(Ellipse2D.Double(10.2, 10.2, 3.6, 3.6))
                g2.draw(Line2D.Double(12.0, 2.5, 12.0, 5.5))
                g2.draw(Line2D.Double(12.0, 18.5, 12.0, 21.5))
                g2.draw(Line2D.Double(2.5, 12.0, 5.5, 12.0))
                g2.draw(Line2D.Double(18.5, 12.0, 21.5, 12.0))
            }
            PcIconKind.TRASH -> {
                g2.draw(Line2D.Double(4.5, 6.5, 19.5, 6.5))
                g2.draw(Line2D.Double(9.5, 4.0, 14.5, 4.0))
                g2.draw(RoundRectangle2D.Double(6.5, 6.5, 11.0, 13.5, 2.0, 2.0))
                g2.draw(Line2D.Double(10.0, 10.5, 10.0, 16.5))
                g2.draw(Line2D.Double(14.0, 10.5, 14.0, 16.5))
            }
            PcIconKind.COPY -> {
                g2.draw(RoundRectangle2D.Double(8.0, 8.0, 11.5, 12.5, 2.5, 2.5))
                val back = Path2D.Double().apply { moveTo(15.5, 8.0); lineTo(15.5, 4.5); lineTo(5.0, 4.5); lineTo(5.0, 16.0); lineTo(8.0, 16.0) }
                g2.draw(back)
            }
            PcIconKind.CHECK_CIRCLE -> {
                g2.draw(Ellipse2D.Double(3.5, 3.5, 17.0, 17.0))
                val ck = Path2D.Double().apply { moveTo(8.0, 12.2); lineTo(11.0, 15.2); lineTo(16.5, 9.2) }
                g2.draw(ck)
            }
            PcIconKind.WARNING -> {
                val tri = Path2D.Double().apply { moveTo(12.0, 3.5); lineTo(21.0, 19.5); lineTo(3.0, 19.5); closePath() }
                g2.draw(tri)
                g2.draw(Line2D.Double(12.0, 9.5, 12.0, 14.0))
                g2.fill(Ellipse2D.Double(11.0, 16.2, 2.0, 2.0))
            }
            PcIconKind.EXPAND_MORE -> {
                val p = Path2D.Double().apply { moveTo(6.5, 9.5); lineTo(12.0, 15.0); lineTo(17.5, 9.5) }
                g2.draw(p)
            }
            PcIconKind.EXPAND_LESS -> {
                val p = Path2D.Double().apply { moveTo(6.5, 15.0); lineTo(12.0, 9.5); lineTo(17.5, 15.0) }
                g2.draw(p)
            }
            PcIconKind.ARROW_UP -> {
                g2.draw(Line2D.Double(12.0, 18.5, 12.0, 5.5))
                val p = Path2D.Double().apply { moveTo(7.0, 10.5); lineTo(12.0, 5.5); lineTo(17.0, 10.5) }
                g2.draw(p)
            }
            PcIconKind.ARROW_DOWN -> {
                g2.draw(Line2D.Double(12.0, 5.5, 12.0, 18.5))
                val p = Path2D.Double().apply { moveTo(7.0, 13.5); lineTo(12.0, 18.5); lineTo(17.0, 13.5) }
                g2.draw(p)
            }
            PcIconKind.LINK -> {
                g2.draw(RoundRectangle2D.Double(3.5, 8.5, 8.5, 7.0, 6.0, 6.0))
                g2.draw(RoundRectangle2D.Double(12.0, 8.5, 8.5, 7.0, 6.0, 6.0))
                g2.draw(Line2D.Double(9.0, 12.0, 15.0, 12.0))
            }
            PcIconKind.LINK_OFF -> {
                g2.draw(RoundRectangle2D.Double(3.5, 8.5, 8.0, 7.0, 6.0, 6.0))
                g2.draw(RoundRectangle2D.Double(12.5, 8.5, 8.0, 7.0, 6.0, 6.0))
                g2.draw(Line2D.Double(5.0, 5.0, 19.0, 19.0))
            }
            PcIconKind.SHIELD -> {
                val p = Path2D.Double().apply {
                    moveTo(12.0, 3.0); lineTo(19.5, 6.5); lineTo(19.5, 12.5)
                    quadTo(19.5, 18.0, 12.0, 21.0); quadTo(4.5, 18.0, 4.5, 12.5); lineTo(4.5, 6.5); closePath()
                }
                g2.draw(p)
            }
            PcIconKind.LOG -> {
                g2.draw(RoundRectangle2D.Double(4.5, 4.0, 15.0, 16.0, 3.0, 3.0))
                g2.draw(Line2D.Double(8.0, 9.0, 16.0, 9.0))
                g2.draw(Line2D.Double(8.0, 12.5, 16.0, 12.5))
                g2.draw(Line2D.Double(8.0, 16.0, 13.0, 16.0))
            }
            PcIconKind.RECORD -> {
                g2.draw(Ellipse2D.Double(4.0, 4.0, 16.0, 16.0))
                g2.fill(Ellipse2D.Double(8.0, 8.0, 8.0, 8.0))
            }
            PcIconKind.SCALE -> {
                g2.draw(RoundRectangle2D.Double(3.5, 5.0, 17.0, 14.0, 3.0, 3.0))
                g2.draw(Line2D.Double(7.5, 9.0, 11.0, 9.0))
                g2.draw(Line2D.Double(7.5, 9.0, 7.5, 12.5))
                g2.draw(Line2D.Double(16.5, 15.0, 13.0, 15.0))
                g2.draw(Line2D.Double(16.5, 15.0, 16.5, 11.5))
            }
            PcIconKind.REFRESH -> {
                g2.draw(Arc2D.Double(4.5, 4.5, 15.0, 15.0, 35.0, 290.0, Arc2D.OPEN))
                val arr = Path2D.Double().apply { moveTo(16.5, 4.0); lineTo(19.5, 7.5); lineTo(15.5, 8.5) }
                g2.draw(arr)
            }
        }
        g2.dispose()
    }
}
