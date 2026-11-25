package com.example.desktop

import com.formdev.flatlaf.FlatDarkLaf
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

/**
 * Wjust macro PC Dark Studio visual design system (matching Android app palette & typography).
 */
object DesktopTheme {
    val BG_BASE = Color(0x0A, 0x10, 0x20)
    val BG_SURFACE = Color(0x11, 0x18, 0x27)
    val BG_CARD = Color(0x16, 0x20, 0x33)
    val BG_ELEVATED = Color(0x1E, 0x29, 0x3B)
    val BORDER_SUBTLE = Color(0x28, 0x37, 0x52)

    val TEXT_PRIMARY = Color(0xF1, 0xF5, 0xF9)
    val TEXT_SECONDARY = Color(0x94, 0xA3, 0xB8)

    val CYAN = Color(0x22, 0xD3, 0xEE)
    val CYAN_DARK = Color(0x08, 0x91, 0xB2)
    val EMERALD = Color(0x10, 0xB9, 0x81)
    val VIOLET = Color(0xA7, 0x8B, 0xFA)
    val AMBER = Color(0xF5, 0x9E, 0x0B)
    val ROSE = Color(0xEF, 0x44, 0x44)
    val ORANGE = Color(0xF9, 0x73, 0x16)

    val SET_PALETTE = listOf("#22D3EE", "#10B981", "#A78BFA", "#F59E0B", "#F43F5E", "#3B82F6")

    var uiFontFamily: String = Font.SANS_SERIF
        private set
    var monoFontFamily: String = Font.MONOSPACED
        private set

    val logoImage: BufferedImage? by lazy {
        runCatching {
            DesktopTheme::class.java.getResourceAsStream("/wjust_logo_badge.png")?.use { ImageIO.read(it) }
        }.getOrNull()
    }

    fun logoIcon(size: Int): ImageIcon? = logoImage?.let {
        ImageIcon(it.getScaledInstance(size, size, Image.SCALE_SMOOTH))
    }

    fun parseHexColor(hex: String, fallback: Color = CYAN): Color =
        runCatching { Color.decode(hex.ifBlank { "#22D3EE" }) }.getOrDefault(fallback)

    fun install() {
        runCatching {
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            DesktopTheme::class.java.getResourceAsStream("/fonts/plus_jakarta_sans.ttf")?.use { s ->
                val f = Font.createFont(Font.TRUETYPE_FONT, s)
                ge.registerFont(f)
                uiFontFamily = f.family
            }
            DesktopTheme::class.java.getResourceAsStream("/fonts/jetbrains_mono.ttf")?.use { s ->
                val f = Font.createFont(Font.TRUETYPE_FONT, s)
                ge.registerFont(f)
                monoFontFamily = f.family
            }
        }

        UIManager.put("Component.arc", 10)
        UIManager.put("Button.arc", 10)
        UIManager.put("TextComponent.arc", 10)
        UIManager.put("ProgressBar.arc", 10)
        UIManager.put("TabbedPane.showTabSeparators", false)
        UIManager.put("Panel.background", BG_BASE)
        UIManager.put("OptionPane.background", BG_SURFACE)
        UIManager.put("RootPane.background", BG_BASE)
        UIManager.put("ScrollPane.background", BG_BASE)
        UIManager.put("Viewport.background", BG_BASE)
        UIManager.put("Component.focusColor", CYAN)
        UIManager.put("Component.accentColor", CYAN)
        UIManager.put("TabbedPane.selectedForeground", CYAN)
        UIManager.put("TabbedPane.underlineColor", CYAN)

        FlatDarkLaf.setup()
        UIManager.put("defaultFont", uiFont(13f, Font.PLAIN))
    }

    fun uiFont(size: Float = 13f, style: Int = Font.PLAIN): Font = Font(uiFontFamily, style, size.toInt())
    fun monoFont(size: Float = 13f, style: Int = Font.PLAIN): Font = Font(monoFontFamily, style, size.toInt())

    fun primaryButton(text: String, bg: Color = CYAN_DARK, fg: Color = Color.WHITE, onClick: () -> Unit): JButton =
        JButton(text).apply {
            font = uiFont(12.5f, Font.BOLD)
            background = bg
            foreground = fg
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = Insets(6, 12, 6, 12)
            addActionListener { onClick() }
        }

    fun subtleButton(text: String, fg: Color = TEXT_PRIMARY, onClick: () -> Unit): JButton =
        JButton(text).apply {
            font = uiFont(12f, Font.BOLD)
            background = BG_ELEVATED
            foreground = fg
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = Insets(5, 10, 5, 10)
            addActionListener { onClick() }
        }

    fun badgeLabel(text: String, tint: Color): JLabel = object : JLabel(text) {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(tint.red, tint.green, tint.blue, 38)
            g2.fillRoundRect(0, 0, width, height, 10, 10)
            g2.color = Color(tint.red, tint.green, tint.blue, 110)
            g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
            g2.dispose()
            super.paintComponent(g)
        }
    }.apply {
        isOpaque = false
        foreground = tint
        font = uiFont(11f, Font.BOLD)
        border = EmptyBorder(2, 7, 2, 7)
    }

    fun roundedCard(borderColor: Color = BORDER_SUBTLE, bg: Color = BG_CARD, padding: Int = 12): JPanel =
        object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = bg
                g2.fillRoundRect(0, 0, width, height, 16, 16)
                g2.color = borderColor
                g2.drawRoundRect(0, 0, width - 1, height - 1, 16, 16)
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            border = EmptyBorder(padding, padding, padding, padding)
        }

    fun sectionTitle(text: String): JLabel = JLabel(text).apply {
        font = uiFont(14f, Font.BOLD)
        foreground = TEXT_PRIMARY
        border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
    }

    fun mutedLabel(text: String, size: Float = 12f): JLabel = JLabel(text).apply {
        font = uiFont(size, Font.PLAIN)
        foreground = TEXT_SECONDARY
    }

    fun fixedHeight(h: Int): Dimension = Dimension(Int.MAX_VALUE, h)
}
