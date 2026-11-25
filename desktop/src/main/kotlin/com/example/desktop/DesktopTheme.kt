package com.example.desktop

import com.formdev.flatlaf.FlatDarkLaf
import java.awt.BasicStroke
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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

/**
 * Exact Android Material 3 Dark design tokens & custom rounded components for Wjust macro PC.
 * Matches `app/src/main/java/com/example/ui/theme/Color.kt` pixel-for-pixel.
 */
object DesktopTheme {
    // Exact Material 3 Dark Palette from Android Color.kt
    val BG_BASE = Color(0x0A, 0x10, 0x20)           // BackgroundDark #0A1020
    val BG_SURFACE = Color(0x10, 0x19, 0x2D)        // SurfaceDark #10192D
    val BG_CARD = Color(0x10, 0x19, 0x2D)           // Card Surface #10192D
    val BG_ELEVATED = Color(0x18, 0x24, 0x3E)       // SurfaceVariantDark #18243E
    val CODE_SURFACE = Color(0x0B, 0x12, 0x22)      // CodeSurfaceDark #0B1222
    val BORDER_SUBTLE = Color(0x23, 0x32, 0x52)     // OutlineVariantDark #233252
    val OUTLINE = Color(0x47, 0x55, 0x69)           // OutlineDark #475569

    val TEXT_PRIMARY = Color(0xF1, 0xF5, 0xF9)      // OnBackgroundDark #F1F5F9
    val TEXT_SECONDARY = Color(0x94, 0xA3, 0xB8)    // OnSurfaceVariantDark #94A3B8

    val CYAN = Color(0x22, 0xD3, 0xEE)              // TealPrimaryDark #22D3EE
    val ON_CYAN = Color(0x04, 0x22, 0x2E)           // OnTealPrimaryDark #04222E
    val CYAN_CONTAINER = Color(0x12, 0x36, 0x4A)    // TealPrimaryContainerDark #12364A
    val ON_CYAN_CONTAINER = Color(0xA5, 0xF3, 0xFC) // OnTealPrimaryContainerDark #A5F3FC
    val CYAN_DARK = Color(0x08, 0x91, 0xB2)

    val VIOLET = Color(0xA7, 0x8B, 0xFA)            // SlateSecondaryDark #A78BFA
    val VIOLET_CONTAINER = Color(0x2A, 0x21, 0x52)  // SlateSecondaryContainerDark #2A2152

    val EMERALD = Color(0x10, 0xB9, 0x81)           // StatusEmerald #10B981
    val AMBER = Color(0xF5, 0x9E, 0x0B)             // StatusAmber #F59E0B
    val ROSE = Color(0xEF, 0x44, 0x44)              // StatusCrimson #EF4444
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

    fun icon(kind: PcIconKind, size: Int = 16, color: Color = TEXT_PRIMARY): Icon =
        PcVectorIcon(kind, size, color)

    fun parseHexColor(hex: String, fallback: Color = CYAN): Color =
        runCatching { Color.decode(hex.ifBlank { "#22D3EE" }) }.getOrDefault(fallback)

    fun withAlpha(c: Color, alpha: Int): Color =
        Color(c.red, c.green, c.blue, alpha.coerceIn(0, 255))

    fun install() {
        // Avoid Win32 WndProc subclassing crash in FlatLaf native decorations on Windows 10/11
        System.setProperty("flatlaf.useWindowDecorations", "false")
        JFrame.setDefaultLookAndFeelDecorated(false)

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

        val lafOk = runCatching {
            UIManager.put("Component.arc", 12)
            UIManager.put("Button.arc", 12)
            UIManager.put("TextComponent.arc", 12)
            UIManager.put("ProgressBar.arc", 12)
            UIManager.put("ScrollBar.width", 8)
            UIManager.put("ScrollBar.thumbArc", 999)
            UIManager.put("ScrollBar.track", BG_BASE)
            UIManager.put("ScrollBar.thumb", BORDER_SUBTLE)
            UIManager.put("Panel.background", BG_BASE)
            UIManager.put("OptionPane.background", BG_SURFACE)
            UIManager.put("OptionPane.messageForeground", TEXT_PRIMARY)
            UIManager.put("RootPane.background", BG_BASE)
            UIManager.put("ScrollPane.background", BG_BASE)
            UIManager.put("Viewport.background", BG_BASE)
            UIManager.put("TextField.background", BG_ELEVATED)
            UIManager.put("TextField.foreground", TEXT_PRIMARY)
            UIManager.put("PasswordField.background", BG_ELEVATED)
            UIManager.put("PasswordField.foreground", TEXT_PRIMARY)
            UIManager.put("ComboBox.background", BG_ELEVATED)
            UIManager.put("ComboBox.foreground", TEXT_PRIMARY)
            UIManager.put("Spinner.background", BG_ELEVATED)
            UIManager.put("Spinner.foreground", TEXT_PRIMARY)
            UIManager.put("PopupMenu.background", BG_SURFACE)
            UIManager.put("PopupMenu.borderColor", BORDER_SUBTLE)
            UIManager.put("MenuItem.background", BG_SURFACE)
            UIManager.put("MenuItem.foreground", TEXT_PRIMARY)
            UIManager.put("MenuItem.selectionBackground", CYAN_CONTAINER)
            UIManager.put("MenuItem.selectionForeground", ON_CYAN_CONTAINER)
            UIManager.put("Component.focusColor", CYAN)
            UIManager.put("Component.accentColor", CYAN)
            UIManager.put("CheckBox.icon.selectedBackground", CYAN)
            UIManager.put("CheckBox.icon.checkmarkColor", ON_CYAN)
            FlatDarkLaf.setup()
        }.getOrDefault(false)

        if (!lafOk) {
            runCatching { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()) }
        }
        UIManager.put("defaultFont", uiFont(13f, Font.PLAIN))
    }

    fun uiFont(size: Float = 13f, style: Int = Font.PLAIN): Font = Font(uiFontFamily, style, size.toInt())
    fun monoFont(size: Float = 13f, style: Int = Font.PLAIN): Font = Font(monoFontFamily, style, size.toInt())

    /** Material 3 Filled / Tonal / Outlined rounded pill button painted with crisp anti-aliasing. */
    class PillButton(
        text: String,
        private var fillBg: Color,
        private var fgColor: Color,
        private var borderCol: Color? = null,
        private val radius: Int = 12,
        btnIcon: Icon? = null,
        padH: Int = 12,
        padV: Int = 7
    ) : JButton(text, btnIcon) {
        private var hovered = false

        init {
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            font = uiFont(12.5f, Font.BOLD)
            foreground = fgColor
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = EmptyBorder(padV, padH, padV, padH)
            iconTextGap = 6
            horizontalAlignment = SwingConstants.CENTER
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent?) { hovered = true; repaint() }
                override fun mouseExited(e: MouseEvent?) { hovered = false; repaint() }
            })
        }

        fun updateColors(bg: Color, fg: Color, outline: Color? = borderCol) {
            fillBg = bg
            fgColor = fg
            borderCol = outline
            foreground = fg
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val bgPaint = if (hovered && isEnabled) brighten(fillBg) else fillBg
            if (bgPaint.alpha > 0) {
                g2.color = bgPaint
                g2.fill(RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, radius.toDouble(), radius.toDouble()))
            }
            borderCol?.let { bc ->
                g2.color = if (hovered) CYAN else bc
                g2.stroke = BasicStroke(1.1f)
                g2.draw(RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, radius.toDouble(), radius.toDouble()))
            }
            g2.dispose()
            super.paintComponent(g)
        }

        private fun brighten(c: Color): Color {
            if (c.alpha == 0) return withAlpha(CYAN, 26)
            return Color(
                (c.red + 16).coerceAtMost(255),
                (c.green + 16).coerceAtMost(255),
                (c.blue + 16).coerceAtMost(255),
                c.alpha
            )
        }
    }

    /** Primary Material 3 Filled Button (default Logo Cyan #22D3EE with dark ink #04222E). */
    fun primaryButton(
        text: String,
        bg: Color = CYAN,
        fg: Color = ON_CYAN,
        iconKind: PcIconKind? = null,
        radius: Int = 12,
        padH: Int = 13,
        padV: Int = 8,
        onClick: () -> Unit
    ): PillButton = PillButton(
        text = text,
        fillBg = bg,
        fgColor = fg,
        borderCol = null,
        radius = radius,
        btnIcon = iconKind?.let { icon(it, 15, fg) },
        padH = padH,
        padV = padV
    ).apply { addActionListener { onClick() } }

    /** Material 3 FilledTonalButton (CyanContainer #12364A / Elevated #18243E). */
    fun tonalButton(
        text: String,
        bg: Color = CYAN_CONTAINER,
        fg: Color = ON_CYAN_CONTAINER,
        iconKind: PcIconKind? = null,
        radius: Int = 12,
        padH: Int = 11,
        padV: Int = 7,
        onClick: () -> Unit
    ): PillButton = PillButton(
        text = text,
        fillBg = bg,
        fgColor = fg,
        borderCol = withAlpha(fg, 40),
        radius = radius,
        btnIcon = iconKind?.let { icon(it, 15, fg) },
        padH = padH,
        padV = padV
    ).apply { addActionListener { onClick() } }

    /** Material 3 OutlinedButton. */
    fun outlinedButton(
        text: String,
        fg: Color = TEXT_PRIMARY,
        outline: Color = BORDER_SUBTLE,
        iconKind: PcIconKind? = null,
        radius: Int = 12,
        padH: Int = 11,
        padV: Int = 7,
        onClick: () -> Unit
    ): PillButton = PillButton(
        text = text,
        fillBg = Color(0, 0, 0, 0),
        fgColor = fg,
        borderCol = outline,
        radius = radius,
        btnIcon = iconKind?.let { icon(it, 15, fg) },
        padH = padH,
        padV = padV
    ).apply { addActionListener { onClick() } }

    /** Compact icon-only circular/rounded button (e.g. MoreVert, Back, Trash, Up/Down). */
    fun iconButton(
        kind: PcIconKind,
        tint: Color = TEXT_SECONDARY,
        tooltip: String = "",
        size: Int = 32,
        onClick: () -> Unit
    ): PillButton = PillButton(
        text = "",
        fillBg = Color(0, 0, 0, 0),
        fgColor = tint,
        borderCol = null,
        radius = 10,
        btnIcon = icon(kind, 17, tint),
        padH = 6,
        padV = 6
    ).apply {
        preferredSize = Dimension(size, size)
        minimumSize = Dimension(size, size)
        maximumSize = Dimension(size, size)
        if (tooltip.isNotBlank()) toolTipText = tooltip
        addActionListener { onClick() }
    }

    /** Backwards-compatible subtle button used across dialogs. */
    fun subtleButton(text: String, fg: Color = TEXT_PRIMARY, onClick: () -> Unit): JButton =
        tonalButton(text = text, bg = BG_ELEVATED, fg = fg, radius = 10, padH = 10, padV = 6, onClick = onClick)

    /** Android-style colored 44×44 rounded icon container badge (for Macro & Macro Set cards). */
    fun iconBoxBadge(
        kind: PcIconKind,
        tint: Color,
        boxSize: Int = 44,
        iconSize: Int = 21,
        customImage: BufferedImage? = null
    ): JPanel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val clip = RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), 12.0, 12.0)
            g2.clip = clip
            if (customImage != null) {
                g2.color = BG_BASE
                g2.fill(clip)
                g2.drawImage(customImage, 0, 0, width, height, null)
            } else {
                g2.color = withAlpha(tint, 40)
                g2.fill(clip)
                val ic = icon(kind, iconSize, tint)
                ic.paintIcon(this, g2, (width - iconSize) / 2, (height - iconSize) / 2)
            }
            g2.dispose()
        }
    }.apply {
        isOpaque = false
        preferredSize = Dimension(boxSize, boxSize)
        minimumSize = Dimension(boxSize, boxSize)
        maximumSize = Dimension(boxSize, boxSize)
    }

    fun badgeLabel(text: String, tint: Color, iconKind: PcIconKind? = null): JLabel = object : JLabel(
        text,
        iconKind?.let { icon(it, 12, tint) },
        SwingConstants.LEFT
    ) {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = withAlpha(tint, 34)
            g2.fillRoundRect(0, 0, width, height, 10, 10)
            g2.color = withAlpha(tint, 95)
            g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
            g2.dispose()
            super.paintComponent(g)
        }
    }.apply {
        isOpaque = false
        foreground = tint
        font = uiFont(11f, Font.BOLD)
        iconTextGap = 4
        border = EmptyBorder(3, 8, 3, 8)
    }

    fun roundedCard(
        borderColor: Color = BORDER_SUBTLE,
        bg: Color = BG_CARD,
        padding: Int = 14,
        radius: Int = 16,
        borderWidth: Float = 1.1f
    ): JPanel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = bg
            g2.fill(RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, radius.toDouble(), radius.toDouble()))
            g2.color = borderColor
            g2.stroke = BasicStroke(borderWidth)
            g2.draw(RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, radius.toDouble(), radius.toDouble()))
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
