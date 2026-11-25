package com.example.desktop

import com.example.automation.ExecutionLogBus
import com.example.automation.SandboxedLuaEngine
import com.example.data.Action
import com.example.data.ActionKind
import com.example.data.ClickAction
import com.example.data.ColorAction
import com.example.data.ContainerRef
import com.example.data.ImageAction
import com.example.data.LuaScript
import com.example.data.Macro
import com.example.data.MacroSecurity
import com.example.data.MacroSet
import com.example.data.ResolutionScaler
import com.example.data.Slot
import com.example.data.TextAction
import com.example.data.TrackObjectAction
import com.example.data.WaitAction
import com.example.data.childList
import com.example.data.countActions
import com.example.data.countStuckActions
import com.example.data.create
import com.example.data.insertInto
import com.example.data.kind
import com.example.data.moveAction
import com.example.data.removeAction
import com.example.data.slots
import com.example.data.summary
import com.example.data.updateAction
import com.example.data.walk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Image
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.EmptyBorder
import javax.swing.filechooser.FileNameExtensionFilter

private enum class PcMainTab(val label: String, val icon: PcIconKind) {
    MACROS("Macro", PcIconKind.TOUCH_APP),
    IMAGES("Gambar", PcIconKind.IMAGE_SEARCH),
    TOOLS("Alat", PcIconKind.CODE)
}

/**
 * Wjust macro PC main window designed with 1:1 visual & navigation parity with the Android app:
 * - Midnight Navy (#0A1020) + Surface (#10192D) + Logo Cyan (#22D3EE) + Lavender (#A78BFA)
 * - Exact 3-tab Material 3 Bottom Navigation Bar (Macro | Gambar | Alat)
 * - Exact Android MacroHomeScreen (Brand header, collapsible Engine status pills, 3 quick actions,
 *   Macro Set filter chips/cards, Macro cards with icon/logo badges & feature pills)
 * - Exact full-screen Android MacroEditorScreen (Back bar, Aksi Utama / Anti-Macet segmented pill,
 *   nested condition/loop tree, floating "+ Tambah Aksi" CTA)
 * - Tools tab with Android Segmented Control (Lua Studio | Live Log | Pengaturan)
 * - Non-blocking screen capture & background ADB/OCR probing for zero EDT freezes or force closes
 */
class DesktopMainWindow(
    private val store: DesktopStore,
    private val coordinator: DesktopCoordinator
) : JFrame("Wjust macro") {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    // Navigation state (mirrors Android MainActivity.kt)
    private var selectedTab: PcMainTab = PcMainTab.MACROS
    private var toolsSubTab: Int = 0 // 0=Lua Studio, 1=Live Log, 2=Pengaturan
    private var editingMacroId: String? = null
    private var editingWatchdogTab: Boolean = false
    private var selectedSetFilter: String = "__ALL__"
    private var selectedImageFolder: String = ""
    private var engineStatusExpanded: Boolean = false
    private val expandedSetIds = HashSet<String>()
    private val unlockedMacroIds = HashSet<String>()
    private var selectedScriptId: String? = store.scripts.value.firstOrNull()?.id

    // Root shell slots
    private val centerStage = JPanel(BorderLayout()).apply { background = DesktopTheme.BG_BASE }
    private val bottomNavBar = JPanel(GridLayout(1, 3, 6, 0)).apply {
        background = DesktopTheme.BG_SURFACE
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, DesktopTheme.BORDER_SUBTLE),
            EmptyBorder(6, 12, 8, 12)
        )
    }

    // Persistent Lua & Log editors (keep user typing intact across tab refreshes)
    private val luaNameField = JTextField(18)
    private val luaCodeArea = JTextArea().apply {
        font = DesktopTheme.monoFont(12.5f, Font.PLAIN)
        background = DesktopTheme.CODE_SURFACE
        foreground = DesktopTheme.TEXT_PRIMARY
        caretColor = DesktopTheme.CYAN
        border = EmptyBorder(10, 12, 10, 12)
        tabSize = 4
    }
    private val luaStatusLabel = DesktopTheme.mutedLabel("Siap menjalankan Lua sandbox.", 11.5f)
    private val logArea = JTextArea().apply {
        isEditable = false
        font = DesktopTheme.monoFont(11.5f, Font.PLAIN)
        background = DesktopTheme.CODE_SURFACE
        foreground = DesktopTheme.TEXT_PRIMARY
        border = EmptyBorder(10, 12, 10, 12)
        lineWrap = true
        wrapStyleWord = true
    }

    private var ocrEngineLabelText: String = DesktopOcrEngine.activeEngineName(allowProbe = false)

    private val miniBar = DesktopOverlays.FloatingMiniBar(
        coordinator = coordinator,
        onQuickCrop = { captureNewTemplateFromScreen() },
        onShowMainWindow = {
            SwingUtilities.invokeLater {
                isVisible = true
                state = NORMAL
                toFront()
                requestFocus()
            }
        }
    )

    private val hotkeyDaemon = Win32Native.GlobalHotkeyListener(
        onPlayPause = { SwingUtilities.invokeLater { handleGlobalPlayPause() } },
        onStop = { SwingUtilities.invokeLater { coordinator.stop() } },
        onCrop = { SwingUtilities.invokeLater { captureNewTemplateFromScreen() } },
        onToggleMiniBar = { SwingUtilities.invokeLater { toggleMiniBar() } }
    )

    private val logoThumbCache = HashMap<String, BufferedImage>()

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        minimumSize = Dimension(420, 620)
        // Android-like portrait studio proportions that sit cleanly beside a game/emulator window
        setSize(520, 820)
        setLocationRelativeTo(null)
        DesktopTheme.logoImage?.let { iconImage = it }

        coordinator.onVisualMatchFlash = { match, label, ox, oy ->
            DesktopOverlays.flashMatchBox(match, label, ox, oy)
        }

        val root = JPanel(BorderLayout()).apply {
            background = DesktopTheme.BG_BASE
            add(centerStage, BorderLayout.CENTER)
            add(bottomNavBar, BorderLayout.SOUTH)
        }
        contentPane = root

        registerWindowHotkeys()
        bindFlows()

        // Start Win32 global hotkeys + probe OCR engine off EDT so startup is instant & never freezes
        if (store.settings.value.globalHotkeysEnabled) {
            hotkeyDaemon.start()
        }
        uiScope.launch(Dispatchers.IO) {
            val probed = DesktopOcrEngine.activeEngineName(allowProbe = true)
            withContext(Dispatchers.Swing) {
                ocrEngineLabelText = probed
                if (editingMacroId == null && selectedTab == PcMainTab.TOOLS && toolsSubTab == 2) {
                    renderCurrentScreen()
                }
            }
        }

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                coordinator.shutdown()
                hotkeyDaemon.stop()
                miniBar.dispose()
                store.flushClockWatermark()
            }
        })

        // Refresh telemetry & MiniBar smoothly without heavy UI rebuilds
        Timer(1200) {
            coordinator.refreshPerfStats()
            if (miniBar.isVisible) miniBar.refreshState()
        }.start()

        renderCurrentScreen()
    }

    private fun toggleMiniBar() {
        miniBar.isVisible = !miniBar.isVisible
        if (miniBar.isVisible) miniBar.refreshState()
        if (editingMacroId == null && selectedTab == PcMainTab.MACROS) {
            renderCurrentScreen()
        }
    }

    private fun registerWindowHotkeys() {
        val imap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val amap = rootPane.actionMap
        fun bind(keyCode: Int, name: String, fn: () -> Unit) {
            imap.put(KeyStroke.getKeyStroke(keyCode, 0), name)
            amap.put(name, object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = fn()
            })
        }
        bind(KeyEvent.VK_F6, "wjust_play_pause") { handleGlobalPlayPause() }
        bind(KeyEvent.VK_F7, "wjust_stop") { coordinator.stop() }
        bind(KeyEvent.VK_F8, "wjust_crop") { captureNewTemplateFromScreen() }
        bind(KeyEvent.VK_F9, "wjust_minibar") { toggleMiniBar() }
    }

    private fun bindFlows() {
        uiScope.launch {
            store.macros.collectLatest { renderCurrentScreen() }
        }
        uiScope.launch {
            store.macroSets.collectLatest { renderCurrentScreen() }
        }
        uiScope.launch {
            store.images.collectLatest {
                if (editingMacroId == null && selectedTab == PcMainTab.IMAGES) renderCurrentScreen()
            }
        }
        uiScope.launch {
            store.scripts.collectLatest {
                if (editingMacroId == null && selectedTab == PcMainTab.TOOLS && toolsSubTab == 0) renderCurrentScreen()
            }
        }
        uiScope.launch {
            coordinator.engineState.collectLatest { st ->
                if (st == DesktopEngineState.RUNNING && store.settings.value.showMiniBarOnRun && !miniBar.isVisible) {
                    miniBar.isVisible = true
                }
                miniBar.refreshState()
                if (editingMacroId == null && selectedTab == PcMainTab.MACROS) {
                    renderCurrentScreen()
                }
            }
        }
        uiScope.launch {
            ExecutionLogBus.logs.collectLatest { logs ->
                val sb = StringBuilder()
                for (entry in logs.takeLast(250)) {
                    sb.append('[').append(entry.timestamp).append("] ")
                        .append(entry.level.tag.padEnd(4)).append("  ")
                        .append(entry.message).append('\n')
                }
                logArea.text = sb.toString()
                logArea.caretPosition = logArea.document.length
            }
        }
    }

    private fun handleGlobalPlayPause() {
        val state = coordinator.engineState.value
        if (state == DesktopEngineState.RUNNING || state == DesktopEngineState.PAUSED) {
            coordinator.togglePlayPause()
            return
        }
        val m = editingMacroId?.let { store.getMacroById(it) }
            ?: store.getMacroById(coordinator.activeMacro.value.id)
            ?: store.macros.value.firstOrNull()
        if (m == null) {
            JOptionPane.showMessageDialog(this, "Buat atau pilih macro terlebih dahulu.")
            return
        }
        launchMacro(m)
    }

    private fun launchMacro(macro: Macro) {
        val err = coordinator.runMacro(macro)
        if (err != null) {
            JOptionPane.showMessageDialog(this, err, "Tidak Dapat Menjalankan Macro", JOptionPane.WARNING_MESSAGE)
        }
    }

    // =========================================================================
    // Android-Style Root Router + Bottom Navigation Bar
    // =========================================================================

    private fun renderCurrentScreen() {
        val editingMacro = editingMacroId?.let { store.getMacroById(it) }
        if (editingMacroId != null && editingMacro == null) {
            editingMacroId = null
        }

        centerStage.removeAll()
        if (editingMacro != null) {
            bottomNavBar.isVisible = false
            centerStage.add(buildMacroEditorScreen(editingMacro), BorderLayout.CENTER)
        } else {
            bottomNavBar.isVisible = true
            rebuildBottomNavBar()
            val screen = when (selectedTab) {
                PcMainTab.MACROS -> buildMacroHomeScreen()
                PcMainTab.IMAGES -> buildImageLibraryScreen()
                PcMainTab.TOOLS -> buildToolsScreen()
            }
            centerStage.add(screen, BorderLayout.CENTER)
        }

        centerStage.revalidate()
        centerStage.repaint()
    }

    private fun rebuildBottomNavBar() {
        bottomNavBar.removeAll()
        for (tab in PcMainTab.entries) {
            val active = selectedTab == tab
            val item = object : JPanel() {
                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    if (active) {
                        val pillW = 64
                        val pillH = 30
                        val px = (width - pillW) / 2
                        g2.color = DesktopTheme.CYAN_CONTAINER
                        g2.fill(RoundRectangle2D.Double(px.toDouble(), 2.0, pillW.toDouble(), pillH.toDouble(), 28.0, 28.0))
                    }
                    g2.dispose()
                }
            }.apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = EmptyBorder(6, 4, 4, 4)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                val tint = if (active) DesktopTheme.CYAN else DesktopTheme.TEXT_SECONDARY
                add(JLabel(DesktopTheme.icon(tab.icon, 20, tint)).apply {
                    alignmentX = Component.CENTER_ALIGNMENT
                })
                add(Box.createVerticalStrut(4))
                add(JLabel(tab.label).apply {
                    font = DesktopTheme.uiFont(11.5f, if (active) Font.BOLD else Font.PLAIN)
                    foreground = tint
                    alignmentX = Component.CENTER_ALIGNMENT
                })
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        if (selectedTab != tab) {
                            selectedTab = tab
                            renderCurrentScreen()
                        }
                    }
                })
            }
            bottomNavBar.add(item)
        }
        bottomNavBar.revalidate()
        bottomNavBar.repaint()
    }

    /** Wraps vertical content in an Android-style centered scrollable column (clean on both 500px & maximized screens). */
    private fun wrapAndroidColumnScroll(inner: JPanel): JScrollPane {
        val wrapper = JPanel(BorderLayout()).apply {
            background = DesktopTheme.BG_BASE
            border = EmptyBorder(14, 16, 18, 16)
            add(inner, BorderLayout.NORTH)
        }
        return JScrollPane(wrapper).apply {
            border = null
            background = DesktopTheme.BG_BASE
            viewport.background = DesktopTheme.BG_BASE
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 18
        }
    }

    // =========================================================================
    // TAB 1: MacroHomeScreen (1:1 Android Layout)
    // =========================================================================

    private fun buildMacroHomeScreen(): Component {
        val col = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        val allMacros = store.macros.value
        val allSets = store.macroSets.value
        val setById = allSets.associateBy { it.id }
        val bySet = allMacros.groupBy { m -> if (m.setId in setById) m.setId else "" }
        val ungrouped = bySet[""].orEmpty()
        val now = store.effectiveNowMs()
        val (sw, sh) = coordinator.captureAndInput.currentScreenSize()

        if (selectedSetFilter != "__ALL__" && selectedSetFilter != "__UNGROUPED__" && selectedSetFilter !in setById) {
            selectedSetFilter = "__ALL__"
        }

        // 1. Brand Header (Logo + "Wjust macro" + animated status dot + "Buka Bar" / "Stop")
        col.add(buildAndroidBrandHeader())
        col.add(Box.createVerticalStrut(12))

        // 2. Collapsible PC Engine Status Card (mirrors Android's 4-pill Permission Card)
        col.add(buildPcEngineStatusCard())
        col.add(Box.createVerticalStrut(12))

        // 3. Quick Start 3-Button Row (Auto Clicker | + Baru | Impor)
        col.add(buildQuickActionsRow())
        col.add(Box.createVerticalStrut(12))

        // 4. Macro Set Banner / Filter Chips Strip
        col.add(buildMacroSetStrip(allMacros, allSets, bySet, ungrouped))
        col.add(Box.createVerticalStrut(12))

        // 5. Focused Set View OR Full Sets + Loose Macros List
        val focusedSet = if (selectedSetFilter != "__ALL__" && selectedSetFilter != "__UNGROUPED__") setById[selectedSetFilter] else null
        if (focusedSet != null) {
            val members = bySet[focusedSet.id].orEmpty()
            col.add(buildFocusedMacroSetBanner(focusedSet, members, now))
            col.add(Box.createVerticalStrut(10))
            if (members.isEmpty()) {
                col.add(buildEmptySetPlaceholder(focusedSet))
            } else {
                for (m in members) {
                    col.add(buildAndroidMacroCard(m, focusedSet, now, sw, sh))
                    col.add(Box.createVerticalStrut(10))
                }
            }
        } else {
            if (selectedSetFilter == "__ALL__" && allSets.isNotEmpty()) {
                for (s in allSets) {
                    val members = bySet[s.id].orEmpty()
                    col.add(buildAndroidMacroSetCard(s, members, now, sw, sh, isExpanded = s.id in expandedSetIds))
                    col.add(Box.createVerticalStrut(10))
                }
            }
            val loose = if (selectedSetFilter == "__UNGROUPED__" || allSets.isNotEmpty()) ungrouped else allMacros
            if (loose.isNotEmpty()) {
                if (allSets.isNotEmpty() && selectedSetFilter == "__ALL__") {
                    col.add(DesktopTheme.sectionTitle("Macro Tanpa Set (${loose.size})").apply {
                        alignmentX = Component.LEFT_ALIGNMENT
                        border = EmptyBorder(4, 2, 6, 2)
                    })
                }
                for (m in loose) {
                    col.add(buildAndroidMacroCard(m, null, now, sw, sh))
                    col.add(Box.createVerticalStrut(10))
                }
            } else if (allSets.isEmpty()) {
                col.add(buildEmptyMacroPlaceholder())
            }
        }

        return wrapAndroidColumnScroll(col)
    }

    private fun buildAndroidBrandHeader(): JPanel {
        val st = coordinator.engineState.value
        val statusColor = when {
            st == DesktopEngineState.RUNNING -> DesktopTheme.EMERALD
            st == DesktopEngineState.PAUSED -> DesktopTheme.AMBER
            st == DesktopEngineState.ERROR -> DesktopTheme.ROSE
            miniBar.isVisible -> DesktopTheme.CYAN
            else -> DesktopTheme.TEXT_SECONDARY
        }
        val statusText = when {
            st == DesktopEngineState.RUNNING -> "Berjalan (Loop ${coordinator.currentLoop.value})"
            st == DesktopEngineState.PAUSED -> "Dijeda"
            st == DesktopEngineState.ERROR -> "Error"
            miniBar.isVisible -> "Bar Aktif • F6–F9"
            else -> "Siap • Offline"
        }

        return JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 54)

            val left = JPanel(BorderLayout(12, 0)).apply { isOpaque = false }
            DesktopTheme.logoIcon(42)?.let { left.add(JLabel(it), BorderLayout.WEST) }
            val titleCol = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(JLabel("Wjust macro").apply {
                    font = DesktopTheme.uiFont(19f, Font.BOLD)
                    foreground = DesktopTheme.TEXT_PRIMARY
                    alignmentX = Component.LEFT_ALIGNMENT
                })
                add(Box.createVerticalStrut(2))
                val statusRow = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                    add(object : JPanel() {
                        init {
                            isOpaque = false
                            preferredSize = Dimension(8, 8)
                            maximumSize = Dimension(8, 8)
                        }
                        override fun paintComponent(g: Graphics) {
                            val g2 = g as Graphics2D
                            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                            g2.color = statusColor
                            g2.fill(Ellipse2D.Double(0.0, 0.5, 7.0, 7.0))
                        }
                    })
                    add(Box.createHorizontalStrut(6))
                    add(JLabel(statusText).apply {
                        font = DesktopTheme.uiFont(11.5f, Font.BOLD)
                        foreground = statusColor
                    })
                }
                add(statusRow)
            }
            left.add(titleCol, BorderLayout.CENTER)

            val rightBtn = if (st == DesktopEngineState.RUNNING || st == DesktopEngineState.PAUSED) {
                DesktopTheme.primaryButton("Stop", DesktopTheme.ROSE, Color.WHITE, PcIconKind.STOP, radius = 12, padH = 14, padV = 9) {
                    coordinator.stop()
                }
            } else {
                DesktopTheme.tonalButton(
                    text = if (miniBar.isVisible) "Tutup Bar" else "Buka Bar",
                    bg = DesktopTheme.CYAN_CONTAINER,
                    fg = DesktopTheme.ON_CYAN_CONTAINER,
                    iconKind = PcIconKind.LAYERS,
                    radius = 12,
                    padH = 13,
                    padV = 9
                ) { toggleMiniBar() }
            }

            add(left, BorderLayout.CENTER)
            add(rightBtn, BorderLayout.EAST)
        }
    }

    private fun buildPcEngineStatusCard(): JPanel {
        val s = store.settings.value
        val perf = coordinator.perfStats.value
        val allReady = true
        val tint = if (allReady) DesktopTheme.EMERALD else DesktopTheme.AMBER

        val card = DesktopTheme.roundedCard(
            borderColor = DesktopTheme.withAlpha(tint, 90),
            bg = DesktopTheme.withAlpha(tint, 24),
            padding = 10,
            radius = 16
        ).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, if (engineStatusExpanded) 170 else 48)
        }

        val headerRow = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 7, 0)).apply {
                isOpaque = false
                add(JLabel(DesktopTheme.icon(PcIconKind.CHECK_CIRCLE, 17, tint)))
                val modeShort = when (s.captureMode) {
                    DesktopCaptureMode.DESKTOP_FAST -> "Win32 GDI Fast"
                    DesktopCaptureMode.WINDOW_BOUND -> "Jendela Target"
                    DesktopCaptureMode.ADB_BACKGROUND -> "ADB Background"
                }
                add(JLabel("Engine PC Aktif • $modeShort (${perf.ramUsedMb}MB)").apply {
                    font = DesktopTheme.uiFont(12.5f, Font.BOLD)
                    foreground = tint
                })
            }
            val chev = JLabel(DesktopTheme.icon(if (engineStatusExpanded) PcIconKind.EXPAND_LESS else PcIconKind.EXPAND_MORE, 18, DesktopTheme.TEXT_SECONDARY))
            add(left, BorderLayout.CENTER)
            add(chev, BorderLayout.EAST)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    engineStatusExpanded = !engineStatusExpanded
                    renderCurrentScreen()
                }
            })
        }
        card.add(headerRow)

        if (engineStatusExpanded) {
            card.add(Box.createVerticalStrut(10))
            val grid = JPanel(GridLayout(2, 2, 8, 8)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }
            fun pill(title: String, active: Boolean) = DesktopTheme.tonalButton(
                text = title,
                bg = if (active) DesktopTheme.withAlpha(DesktopTheme.EMERALD, 36) else DesktopTheme.BG_ELEVATED,
                fg = if (active) DesktopTheme.EMERALD else DesktopTheme.TEXT_SECONDARY,
                iconKind = if (active) PcIconKind.CHECK_CIRCLE else PcIconKind.SETTINGS,
                radius = 10,
                padH = 10,
                padV = 7
            ) {
                selectedTab = PcMainTab.TOOLS
                toolsSubTab = 2
                renderCurrentScreen()
            }
            val capName = when (s.captureMode) {
                DesktopCaptureMode.DESKTOP_FAST -> "Tangkap: Layar PC"
                DesktopCaptureMode.WINDOW_BOUND -> "Tangkap: Jendela"
                DesktopCaptureMode.ADB_BACKGROUND -> "Tangkap: ADB"
            }
            grid.add(pill(capName, true))
            grid.add(pill(if (s.globalHotkeysEnabled) "Hotkey F6-F9 Aktif" else "Hotkey Nonaktif", s.globalHotkeysEnabled))
            grid.add(pill("Vision ${s.visionThreads} Thread • ${s.targetFps}fps", s.parallelMatching))
            grid.add(pill("OCR PC Offline", true))
            card.add(grid)
        }
        return card
    }

    private fun buildQuickActionsRow(): JPanel {
        return JPanel(GridLayout(1, 3, 8, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 44)

            add(DesktopTheme.primaryButton(
                text = "Auto Clicker",
                bg = DesktopTheme.CYAN,
                fg = DesktopTheme.ON_CYAN,
                iconKind = PcIconKind.TOUCH_APP,
                radius = 14,
                padH = 10,
                padV = 10
            ) { createQuickAutoClicker() })

            add(DesktopTheme.tonalButton(
                text = "Baru",
                bg = DesktopTheme.CYAN_CONTAINER,
                fg = DesktopTheme.ON_CYAN_CONTAINER,
                iconKind = PcIconKind.ADD,
                radius = 14,
                padH = 10,
                padV = 10
            ) { createNewMacroInSelectedFilter() })

            add(DesktopTheme.outlinedButton(
                text = "Impor",
                fg = DesktopTheme.TEXT_PRIMARY,
                outline = DesktopTheme.BORDER_SUBTLE,
                iconKind = PcIconKind.UPLOAD,
                radius = 14,
                padH = 10,
                padV = 10
            ) { importBundleFile() })
        }
    }

    private fun buildMacroSetStrip(
        allMacros: List<Macro>,
        allSets: List<MacroSet>,
        bySet: Map<String, List<Macro>>,
        ungrouped: List<Macro>
    ): JComponent {
        if (allSets.isEmpty()) {
            return DesktopTheme.roundedCard(
                borderColor = DesktopTheme.BORDER_SUBTLE,
                bg = DesktopTheme.BG_SURFACE,
                padding = 12,
                radius = 14
            ).apply {
                layout = BorderLayout(10, 0)
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 64)

                val left = JPanel(BorderLayout(10, 0)).apply {
                    isOpaque = false
                    add(JLabel(DesktopTheme.icon(PcIconKind.FOLDER_NEW, 22, DesktopTheme.CYAN)), BorderLayout.WEST)
                    val txt = JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        isOpaque = false
                        add(JLabel("Macro Set").apply {
                            font = DesktopTheme.uiFont(13f, Font.BOLD)
                            foreground = DesktopTheme.TEXT_PRIMARY
                            alignmentX = Component.LEFT_ALIGNMENT
                        })
                        add(Box.createVerticalStrut(2))
                        add(DesktopTheme.mutedLabel("Kelompokkan, kunci & ekspor macro sekaligus", 11f).apply {
                            alignmentX = Component.LEFT_ALIGNMENT
                        })
                    }
                    add(txt, BorderLayout.CENTER)
                }
                val btn = DesktopTheme.tonalButton("Buat Set", iconKind = PcIconKind.ADD, radius = 10, padH = 10, padV = 6) {
                    openCreateMacroSet()
                }
                add(left, BorderLayout.CENTER)
                add(btn, BorderLayout.EAST)
            }
        }

        val chips = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        fun addChip(label: String, id: String, accent: Color = DesktopTheme.CYAN, iconKind: PcIconKind? = null) {
            val active = selectedSetFilter == id
            val fg = if (active) accent else DesktopTheme.TEXT_SECONDARY
            val bg = if (active) DesktopTheme.withAlpha(accent, 42) else DesktopTheme.BG_SURFACE
            val btn = DesktopTheme.PillButton(
                text = label,
                fillBg = bg,
                fgColor = fg,
                borderCol = if (active) accent else DesktopTheme.BORDER_SUBTLE,
                radius = 10,
                btnIcon = iconKind?.let { DesktopTheme.icon(it, 14, fg) },
                padH = 10,
                padV = 5
            ).apply {
                font = DesktopTheme.uiFont(11.5f, Font.BOLD)
                addActionListener {
                    selectedSetFilter = id
                    renderCurrentScreen()
                }
            }
            chips.add(btn)
        }
        addChip("Semua (${allMacros.size})", "__ALL__")
        for (s in allSets) {
            val cnt = bySet[s.id]?.size ?: 0
            addChip("${s.name} ($cnt)", s.id, DesktopTheme.parseHexColor(s.colorHex), if (s.isLocked) PcIconKind.LOCK else PcIconKind.FOLDER)
        }
        if (ungrouped.isNotEmpty()) {
            addChip("Tanpa Set (${ungrouped.size})", "__UNGROUPED__")
        }
        chips.add(DesktopTheme.tonalButton("+ Set", iconKind = PcIconKind.FOLDER_NEW, radius = 10, padH = 10, padV = 5) {
            openCreateMacroSet()
        })
        return chips
    }

    private fun buildFocusedMacroSetBanner(set: MacroSet, members: List<Macro>, now: Long): JPanel {
        val accent = DesktopTheme.parseHexColor(set.colorHex)
        val isExp = MacroSecurity.isSetExpired(set, now)
        val isTampered = MacroSecurity.isSetTampered(set)
        val remLabel = MacroSecurity.remainingSetTimeLabel(set, now)
        val passiveCnt = members.count { it.isPassive }
        val activeCnt = (members.size - passiveCnt).coerceAtLeast(0)

        val card = DesktopTheme.roundedCard(
            borderColor = if (isExp || isTampered) DesktopTheme.ROSE else accent,
            bg = DesktopTheme.withAlpha(accent, 26),
            padding = 14,
            radius = 18,
            borderWidth = 1.5f
        ).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 190)
        }

        val topRow = JPanel(BorderLayout(10, 0)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
        topRow.add(DesktopTheme.iconBoxBadge(if (set.isLocked) PcIconKind.LOCK else PcIconKind.FOLDER_OPEN, accent, 42, 22), BorderLayout.WEST)
        val infoCol = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JLabel(set.name).apply {
                font = DesktopTheme.uiFont(15f, Font.BOLD)
                foreground = DesktopTheme.TEXT_PRIMARY
            })
            add(DesktopTheme.mutedLabel("${members.size} macro ($activeCnt Aktif • $passiveCnt Pasif)", 11.5f))
            if (set.description.isNotBlank()) add(DesktopTheme.mutedLabel(set.description, 11f))
        }
        topRow.add(infoCol, BorderLayout.CENTER)
        val moreBtn = DesktopTheme.iconButton(PcIconKind.MORE_VERT, DesktopTheme.TEXT_SECONDARY, "Menu Set") {}
        moreBtn.addActionListener { showMacroSetPopupMenu(moreBtn, set, members.size, focused = true) }
        topRow.add(moreBtn, BorderLayout.EAST)
        card.add(topRow)

        if (set.isLocked || remLabel != null || isTampered) {
            card.add(Box.createVerticalStrut(8))
            val tags = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
            if (isTampered) tags.add(DesktopTheme.badgeLabel("Set Tidak Valid", DesktopTheme.ROSE, PcIconKind.WARNING))
            if (set.isLocked) tags.add(DesktopTheme.badgeLabel("Set Terkunci", DesktopTheme.VIOLET, PcIconKind.LOCK))
            if (remLabel != null) tags.add(DesktopTheme.badgeLabel(remLabel, if (isExp) DesktopTheme.ROSE else DesktopTheme.AMBER))
            card.add(tags)
        }

        card.add(Box.createVerticalStrut(10))
        val btnGrid = JPanel(GridLayout(1, 4, 6, 0)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
        btnGrid.add(DesktopTheme.tonalButton("Macro", iconKind = PcIconKind.ADD, radius = 10, padH = 6, padV = 6) {
            createNewMacroInSet(set)
        })
        btnGrid.add(DesktopTheme.outlinedButton("Isi Set", radius = 10, padH = 6, padV = 6) {
            requestManageSetMembers(set)
        })
        btnGrid.add(DesktopTheme.outlinedButton(if (set.isLocked) "Terkunci" else "Kunci", iconKind = PcIconKind.LOCK, radius = 10, padH = 6, padV = 6) {
            requestOpenSetSecurity(set)
        })
        btnGrid.add(DesktopTheme.primaryButton("Ekspor", iconKind = PcIconKind.DOWNLOAD, radius = 10, padH = 6, padV = 6) {
            exportMacroSetZip(set)
        })
        card.add(btnGrid)
        return card
    }

    private fun buildAndroidMacroSetCard(
        set: MacroSet,
        members: List<Macro>,
        now: Long,
        sw: Int,
        sh: Int,
        isExpanded: Boolean
    ): JPanel {
        val accent = DesktopTheme.parseHexColor(set.colorHex)
        val isExp = MacroSecurity.isSetExpired(set, now)
        val isTampered = MacroSecurity.isSetTampered(set)
        val remLabel = MacroSecurity.remainingSetTimeLabel(set, now)
        val passiveCnt = members.count { it.isPassive }
        val activeCnt = (members.size - passiveCnt).coerceAtLeast(0)

        val card = DesktopTheme.roundedCard(
            borderColor = if (isExp || isTampered) DesktopTheme.ROSE else DesktopTheme.withAlpha(accent, if (isExpanded) 210 else 120),
            bg = DesktopTheme.BG_CARD,
            padding = 14,
            radius = 18,
            borderWidth = if (isExpanded || isExp) 1.5f else 1.1f
        ).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }

        val head = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        head.add(DesktopTheme.iconBoxBadge(if (isExpanded) PcIconKind.FOLDER_OPEN else PcIconKind.FOLDER, accent, 44, 22), BorderLayout.WEST)

        val info = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            val titleRow = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(JLabel(set.name.ifBlank { "Set Baru" }).apply {
                    font = DesktopTheme.uiFont(14f, Font.BOLD)
                    foreground = DesktopTheme.TEXT_PRIMARY
                })
                if (set.isLocked) {
                    add(Box.createHorizontalStrut(6))
                    add(JLabel(DesktopTheme.icon(PcIconKind.LOCK, 13, DesktopTheme.VIOLET)))
                }
            }
            add(titleRow)
            add(Box.createVerticalStrut(2))
            val sub = buildString {
                append("${members.size} macro")
                if (members.isNotEmpty()) {
                    append(" ($activeCnt Aktif")
                    if (passiveCnt > 0) append(" • $passiveCnt Pasif")
                    append(")")
                }
            }
            add(DesktopTheme.mutedLabel(sub, 11.5f).apply { alignmentX = Component.LEFT_ALIGNMENT })
            if (set.description.isNotBlank()) {
                add(DesktopTheme.mutedLabel(set.description, 11f).apply { alignmentX = Component.LEFT_ALIGNMENT })
            }
        }
        head.add(info, BorderLayout.CENTER)

        val rightBtns = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply { isOpaque = false }
        rightBtns.add(DesktopTheme.iconButton(if (isExpanded) PcIconKind.EXPAND_LESS else PcIconKind.EXPAND_MORE, DesktopTheme.TEXT_SECONDARY, "Buka/Tutup Set") {
            if (set.id in expandedSetIds) expandedSetIds -= set.id else expandedSetIds += set.id
            renderCurrentScreen()
        })
        val moreBtn = DesktopTheme.iconButton(PcIconKind.MORE_VERT, DesktopTheme.TEXT_SECONDARY, "Menu Set") {}
        moreBtn.addActionListener { showMacroSetPopupMenu(moreBtn, set, members.size, focused = false) }
        rightBtns.add(moreBtn)
        head.add(rightBtns, BorderLayout.EAST)
        head.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                if (set.id in expandedSetIds) expandedSetIds -= set.id else expandedSetIds += set.id
                renderCurrentScreen()
            }
        })
        card.add(head)

        if (set.isLocked || remLabel != null || isTampered) {
            card.add(Box.createVerticalStrut(8))
            val tags = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
            if (isTampered) tags.add(DesktopTheme.badgeLabel("Set Tidak Valid", DesktopTheme.ROSE, PcIconKind.WARNING))
            if (set.isLocked) tags.add(DesktopTheme.badgeLabel("Set Terkunci", DesktopTheme.VIOLET, PcIconKind.LOCK))
            if (remLabel != null) tags.add(DesktopTheme.badgeLabel(remLabel, if (isExp) DesktopTheme.ROSE else DesktopTheme.AMBER))
            card.add(tags)
        }

        card.add(Box.createVerticalStrut(10))
        val bar = JPanel(BorderLayout(6, 0)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
        val leftBtns = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        leftBtns.add(DesktopTheme.outlinedButton(
            text = if (set.isLocked) "Terkunci" else "Kunci",
            fg = if (set.isLocked) DesktopTheme.VIOLET else DesktopTheme.TEXT_PRIMARY,
            iconKind = PcIconKind.LOCK,
            radius = 10,
            padH = 10,
            padV = 5
        ) { requestOpenSetSecurity(set) })
        leftBtns.add(DesktopTheme.outlinedButton("Ekspor", iconKind = PcIconKind.DOWNLOAD, radius = 10, padH = 10, padV = 5) {
            exportMacroSetZip(set)
        })
        val viewBtn = DesktopTheme.tonalButton(
            if (isExpanded) "Sembunyikan" else "Lihat (${members.size})",
            radius = 10,
            padH = 11,
            padV = 5
        ) {
            if (set.id in expandedSetIds) expandedSetIds -= set.id else expandedSetIds += set.id
            renderCurrentScreen()
        }
        bar.add(leftBtns, BorderLayout.WEST)
        bar.add(viewBtn, BorderLayout.EAST)
        card.add(bar)

        if (isExpanded) {
            card.add(Box.createVerticalStrut(10))
            val divider = JPanel().apply {
                background = DesktopTheme.withAlpha(accent, 70)
                maximumSize = Dimension(Int.MAX_VALUE, 1)
                preferredSize = Dimension(10, 1)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            card.add(divider)
            card.add(Box.createVerticalStrut(10))
            if (members.isEmpty()) {
                val emptyRow = JPanel(BorderLayout()).apply {
                    isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
                    add(DesktopTheme.mutedLabel("Belum ada macro di set ini.", 12f), BorderLayout.WEST)
                    add(DesktopTheme.tonalButton("+ Pilih Macro", radius = 10, padH = 10, padV = 5) {
                        requestManageSetMembers(set)
                    }, BorderLayout.EAST)
                }
                card.add(emptyRow)
            } else {
                for (m in members) {
                    card.add(buildAndroidMacroCard(m, set, now, sw, sh))
                    card.add(Box.createVerticalStrut(8))
                }
            }
        }

        return card
    }

    private fun showMacroSetPopupMenu(anchor: Component, set: MacroSet, memberCount: Int, focused: Boolean) {
        val menu = JPopupMenu()
        if (focused) {
            menu.add(JMenuItem("Kembali ke Semua").apply { addActionListener { selectedSetFilter = "__ALL__"; renderCurrentScreen() } })
        } else {
            menu.add(JMenuItem("Buka Tampilan Fokus Set").apply { addActionListener { selectedSetFilter = set.id; renderCurrentScreen() } })
        }
        menu.add(JMenuItem("Tambah Macro Baru ke Set").apply { addActionListener { createNewMacroInSet(set) } })
        menu.add(JMenuItem("Kelola Isi Set ($memberCount)").apply { addActionListener { requestManageSetMembers(set) } })
        menu.add(JMenuItem("Edit Nama & Warna Set").apply { addActionListener { requestEditMacroSet(set) } })
        menu.add(JMenuItem("Kunci & Tenggat Set").apply { addActionListener { requestOpenSetSecurity(set) } })
        menu.add(JMenuItem("Duplikat Set").apply { addActionListener { requestDuplicateMacroSet(set) } })
        menu.add(JMenuItem("Ekspor Macro Set (.zip)").apply { addActionListener { exportMacroSetZip(set) } })
        menu.add(JMenuItem("Hapus Set...").apply {
            foreground = DesktopTheme.ROSE
            addActionListener { confirmDeleteMacroSet(set, memberCount) }
        })
        menu.show(anchor, 0, anchor.height)
    }

    private fun buildAndroidMacroCard(
        macro: Macro,
        parentSet: MacroSet?,
        now: Long,
        sw: Int,
        sh: Int
    ): JPanel {
        val isActive = coordinator.activeMacro.value.id == macro.id &&
            (coordinator.engineState.value == DesktopEngineState.RUNNING || miniBar.isVisible)
        val locked = MacroSecurity.isEffectivelyLocked(macro, parentSet)
        val isExp = MacroSecurity.isExpired(macro, now) || (parentSet != null && MacroSecurity.isSetExpired(parentSet, now))
        val isTampered = MacroSecurity.isTampered(macro) || (parentSet != null && MacroSecurity.isSetTampered(parentSet))
        val remLabel = MacroSecurity.remainingTimeLabel(macro, now)
            ?: parentSet?.let { MacroSecurity.remainingSetTimeLabel(it, now) }

        val allActions = macro.actions.walk().toList()
        val hasImage = allActions.any { it is ImageAction || it is TrackObjectAction }
        val hasText = allActions.any { it is TextAction }
        val hasColor = allActions.any { it is ColorAction }
        val stuckCount = macro.countStuckActions()
        val scaleTransform = ResolutionScaler.forMacro(macro, sw, sh)

        val badgeTint = when {
            isExp || isTampered -> DesktopTheme.ROSE
            hasImage -> DesktopTheme.EMERALD
            hasText -> DesktopTheme.CYAN
            hasColor -> DesktopTheme.VIOLET
            else -> DesktopTheme.CYAN
        }
        val badgeIcon = when {
            hasImage -> PcIconKind.IMAGE_SEARCH
            hasText -> PcIconKind.LOG
            else -> PcIconKind.TOUCH_APP
        }
        val customLogo = loadMacroLogo(macro.logoImage)

        val cardBg = when {
            isExp || isTampered -> DesktopTheme.withAlpha(DesktopTheme.ROSE, 24)
            isActive -> DesktopTheme.withAlpha(DesktopTheme.CYAN, 22)
            else -> DesktopTheme.BG_CARD
        }
        val cardBorder = when {
            isExp || isTampered -> DesktopTheme.ROSE
            isActive -> DesktopTheme.CYAN
            else -> DesktopTheme.BORDER_SUBTLE
        }

        val card = DesktopTheme.roundedCard(
            borderColor = cardBorder,
            bg = cardBg,
            padding = 14,
            radius = 16,
            borderWidth = if (isActive || isExp) 1.5f else 1.1f
        ).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 180)
        }

        // Row 1: 44x44 Icon/Logo + Title + Subtitle + MoreVert
        val topRow = JPanel(BorderLayout(12, 0)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
        val iconBox = DesktopTheme.iconBoxBadge(badgeIcon, badgeTint, 44, 21, customLogo).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Klik untuk ubah foto logo macro"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) { requestChangeMacroLogo(macro) }
            })
        }
        topRow.add(iconBox, BorderLayout.WEST)

        val infoCol = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            val nameRow = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(JLabel(macro.name.ifBlank { "Macro Baru" }).apply {
                    font = DesktopTheme.uiFont(14f, Font.BOLD)
                    foreground = DesktopTheme.TEXT_PRIMARY
                })
                if (locked) {
                    add(Box.createHorizontalStrut(6))
                    add(JLabel(DesktopTheme.icon(PcIconKind.LOCK, 13, DesktopTheme.VIOLET)))
                }
                if (isActive) {
                    add(Box.createHorizontalStrut(6))
                    add(object : JPanel() {
                        init {
                            isOpaque = false
                            preferredSize = Dimension(8, 8)
                            maximumSize = Dimension(8, 8)
                        }
                        override fun paintComponent(g: Graphics) {
                            val g2 = g as Graphics2D
                            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                            g2.color = DesktopTheme.EMERALD
                            g2.fill(Ellipse2D.Double(0.0, 0.0, 8.0, 8.0))
                        }
                    })
                }
            }
            add(nameRow)
            add(Box.createVerticalStrut(3))
            val loopTxt = if (macro.loopCount <= 0) "Loop ∞" else "Loop ${macro.loopCount}×"
            val wdTxt = if (stuckCount > 0) " • Anti-Macet $stuckCount" else ""
            add(DesktopTheme.mutedLabel("${macro.countActions()} aksi • $loopTxt$wdTxt", 11.5f).apply {
                alignmentX = Component.LEFT_ALIGNMENT
            })
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) { requestOpenMacroEditor(macro) }
            })
        }
        topRow.add(infoCol, BorderLayout.CENTER)

        val moreBtn = DesktopTheme.iconButton(PcIconKind.MORE_VERT, DesktopTheme.TEXT_SECONDARY, "Menu Macro") {}
        moreBtn.addActionListener { showMacroPopupMenu(moreBtn, macro, isExp, isTampered) }
        topRow.add(moreBtn, BorderLayout.EAST)
        card.add(topRow)

        // Row 2: Security / Passive / Auto-Scale / Expiry Badges
        if (locked || macro.isPassive || remLabel != null || isTampered || scaleTransform.isScaling) {
            card.add(Box.createVerticalStrut(8))
            val tagsRow = JPanel(FlowLayout(FlowLayout.LEFT, 5, 2)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
            if (isTampered) tagsRow.add(DesktopTheme.badgeLabel("Tidak Valid", DesktopTheme.ROSE, PcIconKind.WARNING))
            if (locked) tagsRow.add(DesktopTheme.badgeLabel("Terkunci", DesktopTheme.VIOLET, PcIconKind.LOCK))
            if (macro.isPassive) {
                tagsRow.add(DesktopTheme.badgeLabel(
                    if (macro.passiveEnabled) "Pasif ON" else "Pasif OFF",
                    if (macro.passiveEnabled) DesktopTheme.EMERALD else DesktopTheme.AMBER,
                    if (macro.passiveEnabled) PcIconKind.LINK else PcIconKind.LINK_OFF
                ))
            }
            if (scaleTransform.isScaling || locked) {
                val scaleLbl = DesktopTheme.badgeLabel(
                    if (macro.autoScaleResolution) "Auto-Scale" else "Scale OFF",
                    if (macro.autoScaleResolution) DesktopTheme.CYAN else DesktopTheme.TEXT_SECONDARY,
                    PcIconKind.SCALE
                ).apply {
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) { openAutoScaleDialog(macro) }
                    })
                }
                tagsRow.add(scaleLbl)
            }
            if (remLabel != null) {
                tagsRow.add(DesktopTheme.badgeLabel(remLabel, if (isExp) DesktopTheme.ROSE else DesktopTheme.AMBER))
            }
            card.add(tagsRow)
        }

        // Row 3: Action Buttons (Android-style Edit + Mulai / Pasif toggle)
        card.add(Box.createVerticalStrut(10))
        val btnRow = JPanel(GridLayout(1, 2, 8, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 36)
        }
        btnRow.add(DesktopTheme.outlinedButton(
            text = if (locked) "Buka Editor" else "Edit Aksi",
            fg = if (locked) DesktopTheme.VIOLET else DesktopTheme.TEXT_PRIMARY,
            iconKind = if (locked) PcIconKind.LOCK else PcIconKind.EDIT,
            radius = 10,
            padH = 10,
            padV = 6
        ) { requestOpenMacroEditor(macro) })

        when {
            isExp || isTampered -> btnRow.add(DesktopTheme.outlinedButton(
                text = "Masa Berlaku Habis",
                fg = DesktopTheme.ROSE,
                outline = DesktopTheme.ROSE,
                iconKind = PcIconKind.WARNING,
                radius = 10,
                padH = 10,
                padV = 6
            ) {
                val denial = MacroSecurity.checkDirectRun(macro, store.effectiveNowMs(), parentSet)
                if (denial != null) JOptionPane.showMessageDialog(this, MacroSecurity.denialMessage(macro, denial, parentSet))
            })
            macro.isPassive -> btnRow.add(DesktopTheme.tonalButton(
                text = if (macro.passiveEnabled) "Pasif ON" else "Pasif OFF",
                bg = DesktopTheme.withAlpha(if (macro.passiveEnabled) DesktopTheme.EMERALD else DesktopTheme.AMBER, 40),
                fg = if (macro.passiveEnabled) DesktopTheme.EMERALD else DesktopTheme.AMBER,
                iconKind = if (macro.passiveEnabled) PcIconKind.LINK else PcIconKind.LINK_OFF,
                radius = 10,
                padH = 10,
                padV = 6
            ) {
                store.saveMacro(MacroSecurity.togglePassiveEnabled(macro, !macro.passiveEnabled))
            })
            else -> btnRow.add(DesktopTheme.primaryButton(
                text = "Mulai",
                bg = DesktopTheme.CYAN,
                fg = DesktopTheme.ON_CYAN,
                iconKind = PcIconKind.PLAY,
                radius = 10,
                padH = 10,
                padV = 6
            ) { launchMacro(macro) })
        }
        card.add(btnRow)
        return card
    }

    private fun showMacroPopupMenu(anchor: Component, macro: Macro, isExp: Boolean, isTampered: Boolean) {
        val menu = JPopupMenu()
        menu.add(JMenuItem("Auto-Scale Layar (Tanpa Sandi)").apply { addActionListener { openAutoScaleDialog(macro) } })
        menu.add(JMenuItem("Pindah ke Macro Set...").apply { addActionListener { requestMoveMacroToSet(macro) } })
        menu.add(JMenuItem("Ubah Foto Logo").apply { addActionListener { requestChangeMacroLogo(macro) } })
        menu.add(JMenuItem("Kunci, Tenggat & Pasif").apply { addActionListener { requestOpenMacroSecurity(macro) } })
        if (macro.isPassive && !isExp && !isTampered) {
            menu.add(JMenuItem(if (macro.passiveEnabled) "Mode Pasif: Matikan (OFF)" else "Mode Pasif: Nyalakan (ON)").apply {
                addActionListener { store.saveMacro(MacroSecurity.togglePassiveEnabled(macro, !macro.passiveEnabled)) }
            })
        }
        menu.add(JMenuItem("Duplikat").apply { addActionListener { requestDuplicateMacro(macro) } })
        menu.add(JMenuItem("Ekspor Macro + Gambar (.zip)").apply { addActionListener { exportMacroZip(macro) } })
        menu.add(JMenuItem("Hapus").apply {
            foreground = DesktopTheme.ROSE
            addActionListener {
                if (JOptionPane.showConfirmDialog(this@DesktopMainWindow, "Hapus macro '${macro.name}'?", "Hapus Macro", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    store.deleteMacro(macro.id)
                }
            }
        })
        menu.show(anchor, 0, anchor.height)
    }

    private fun buildEmptySetPlaceholder(set: MacroSet): JPanel {
        val accent = DesktopTheme.parseHexColor(set.colorHex)
        return DesktopTheme.roundedCard(bg = DesktopTheme.BG_SURFACE, padding = 20, radius = 16).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel(DesktopTheme.icon(PcIconKind.FOLDER_OPEN, 36, accent)).apply { alignmentX = Component.CENTER_ALIGNMENT })
            add(Box.createVerticalStrut(8))
            add(JLabel("Macro Set ini masih kosong").apply {
                font = DesktopTheme.uiFont(14f, Font.BOLD); foreground = DesktopTheme.TEXT_PRIMARY
                alignmentX = Component.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(4))
            add(DesktopTheme.mutedLabel("Tambahkan macro baru atau pilih macro yang sudah ada.", 12f).apply {
                alignmentX = Component.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(12))
            val row = JPanel(FlowLayout(FlowLayout.CENTER, 8, 0)).apply { isOpaque = false }
            row.add(DesktopTheme.primaryButton("Macro Baru", iconKind = PcIconKind.ADD) { createNewMacroInSet(set) })
            row.add(DesktopTheme.tonalButton("Pilih Macro") { requestManageSetMembers(set) })
            add(row)
        }
    }

    private fun buildEmptyMacroPlaceholder(): JPanel {
        return DesktopTheme.roundedCard(bg = DesktopTheme.BG_SURFACE, padding = 24, radius = 16).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel(DesktopTheme.icon(PcIconKind.TOUCH_APP, 36, DesktopTheme.CYAN)).apply { alignmentX = Component.CENTER_ALIGNMENT })
            add(Box.createVerticalStrut(8))
            add(JLabel("Belum ada macro").apply {
                font = DesktopTheme.uiFont(14.5f, Font.BOLD); foreground = DesktopTheme.TEXT_PRIMARY
                alignmentX = Component.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(4))
            add(DesktopTheme.mutedLabel("Klik '+ Baru', 'Auto Clicker', atau 'Impor' untuk mulai.", 12f).apply {
                alignmentX = Component.CENTER_ALIGNMENT
            })
        }
    }

    // =========================================================================
    // FULL-SCREEN ANDROID-STYLE MACRO EDITOR SCREEN
    // =========================================================================

    private fun buildMacroEditorScreen(macro: Macro): Component {
        val root = JPanel(BorderLayout()).apply { background = DesktopTheme.BG_BASE }

        // TopAppBar (Back arrow + Logo + Title + Settings + Lock + Play)
        val topBar = JPanel(BorderLayout(8, 0)).apply {
            background = DesktopTheme.BG_SURFACE
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, DesktopTheme.BORDER_SUBTLE),
                EmptyBorder(10, 12, 10, 12)
            )
        }
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        left.add(DesktopTheme.iconButton(PcIconKind.BACK, DesktopTheme.TEXT_PRIMARY, "Kembali") {
            unlockedMacroIds -= macro.id
            editingMacroId = null
            editingWatchdogTab = false
            renderCurrentScreen()
        })
        left.add(DesktopTheme.iconBoxBadge(PcIconKind.TOUCH_APP, DesktopTheme.CYAN, 32, 16, loadMacroLogo(macro.logoImage)))
        val titleCol = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JLabel(macro.name.ifBlank { "Macro Baru" }).apply {
                font = DesktopTheme.uiFont(15f, Font.BOLD)
                foreground = DesktopTheme.TEXT_PRIMARY
            })
            val loopTxt = if (macro.loopCount <= 0) "Loop ∞" else "Loop ${macro.loopCount}×"
            add(DesktopTheme.mutedLabel("${macro.countActions()} aksi • $loopTxt • Jeda ${macro.loopDelayMs}ms", 11f))
        }
        left.add(titleCol)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply { isOpaque = false }
        right.add(DesktopTheme.iconButton(PcIconKind.SETTINGS, DesktopTheme.TEXT_SECONDARY, "Setelan Loop & Anti-Macet") {
            openMacroSettingsDialog(macro)
        })
        right.add(DesktopTheme.iconButton(PcIconKind.LOCK, if (macro.isLocked) DesktopTheme.VIOLET else DesktopTheme.TEXT_SECONDARY, "Kunci, Tenggat & Pasif") {
            requestOpenMacroSecurity(macro)
        })
        right.add(DesktopTheme.primaryButton("Mulai", DesktopTheme.CYAN, DesktopTheme.ON_CYAN, PcIconKind.PLAY, radius = 10, padH = 12, padV = 6) {
            launchMacro(macro)
        })

        topBar.add(left, BorderLayout.CENTER)
        topBar.add(right, BorderLayout.EAST)
        root.add(topBar, BorderLayout.NORTH)

        // Body: Segmented Toggle (Aksi Utama | Anti-Macet) + Action List
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        val segRow = JPanel(GridLayout(1, 2, 6, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 38)
            add(DesktopTheme.PillButton(
                text = "Aksi Utama (${macro.actions.size})",
                fillBg = if (!editingWatchdogTab) DesktopTheme.CYAN_CONTAINER else DesktopTheme.BG_SURFACE,
                fgColor = if (!editingWatchdogTab) DesktopTheme.ON_CYAN_CONTAINER else DesktopTheme.TEXT_SECONDARY,
                borderCol = if (!editingWatchdogTab) DesktopTheme.CYAN else DesktopTheme.BORDER_SUBTLE,
                radius = 12,
                padH = 10,
                padV = 7
            ).apply { addActionListener { editingWatchdogTab = false; renderCurrentScreen() } })
            add(DesktopTheme.PillButton(
                text = "Anti-Macet (${macro.stuckActions.size})",
                fillBg = if (editingWatchdogTab) DesktopTheme.withAlpha(DesktopTheme.ORANGE, 45) else DesktopTheme.BG_SURFACE,
                fgColor = if (editingWatchdogTab) DesktopTheme.ORANGE else DesktopTheme.TEXT_SECONDARY,
                borderCol = if (editingWatchdogTab) DesktopTheme.ORANGE else DesktopTheme.BORDER_SUBTLE,
                radius = 12,
                btnIcon = DesktopTheme.icon(PcIconKind.SHIELD, 14, if (editingWatchdogTab) DesktopTheme.ORANGE else DesktopTheme.TEXT_SECONDARY),
                padH = 10,
                padV = 7
            ).apply { addActionListener { editingWatchdogTab = true; renderCurrentScreen() } })
        }
        body.add(segRow)
        body.add(Box.createVerticalStrut(12))

        val rootActions = if (editingWatchdogTab) macro.stuckActions else macro.actions
        if (rootActions.isEmpty()) {
            body.add(DesktopTheme.roundedCard(bg = DesktopTheme.BG_SURFACE, padding = 20, radius = 16).apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                add(DesktopTheme.mutedLabel(
                    if (editingWatchdogTab) "Belum ada aksi Anti-Macet. Tambahkan aksi pemulihan jika layar macet."
                    else "Belum ada aksi. Klik '+ Tambah Aksi' di bawah untuk menyusun langkah macro.",
                    12.5f
                ).apply { alignmentX = Component.CENTER_ALIGNMENT })
            })
        } else {
            renderActionTree(body, macro, rootActions, depth = 0, forStuck = editingWatchdogTab)
        }
        root.add(wrapAndroidColumnScroll(body), BorderLayout.CENTER)

        // Bottom Floating CTA Bar ("+ Tambah Aksi" + "Auto-Scale")
        val bottomBar = JPanel(BorderLayout(8, 0)).apply {
            background = DesktopTheme.BG_SURFACE
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, DesktopTheme.BORDER_SUBTLE),
                EmptyBorder(10, 14, 10, 14)
            )
            add(DesktopTheme.outlinedButton("Auto-Scale", iconKind = PcIconKind.SCALE, radius = 12, padH = 12, padV = 8) {
                openAutoScaleDialog(macro)
            }, BorderLayout.WEST)
            val addBtn = DesktopTheme.primaryButton("Tambah Aksi", DesktopTheme.CYAN, DesktopTheme.ON_CYAN, PcIconKind.ADD, radius = 14, padH = 16, padV = 9) {}
            addBtn.addActionListener {
                showAddActionPopup(addBtn, macro, ContainerRef(null, null), forStuck = editingWatchdogTab)
            }
            add(addBtn, BorderLayout.EAST)
        }
        root.add(bottomBar, BorderLayout.SOUTH)
        return root
    }

    private fun renderActionTree(
        container: JPanel,
        macro: Macro,
        actions: List<Action>,
        depth: Int,
        forStuck: Boolean
    ) {
        actions.forEachIndexed { idx, action ->
            val accent = when (action) {
                is ImageAction, is TrackObjectAction -> DesktopTheme.EMERALD
                is TextAction -> DesktopTheme.CYAN
                is ColorAction -> DesktopTheme.VIOLET
                is WaitAction -> DesktopTheme.AMBER
                else -> DesktopTheme.CYAN
            }
            val row = DesktopTheme.roundedCard(
                borderColor = if (action.enabled) DesktopTheme.BORDER_SUBTLE else DesktopTheme.withAlpha(DesktopTheme.OUTLINE, 90),
                bg = DesktopTheme.BG_CARD,
                padding = 10,
                radius = 14
            ).apply {
                layout = BorderLayout(8, 0)
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 70)
            }

            val left = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                val prefix = if (action.enabled) "#${idx + 1}  " else "#${idx + 1} [OFF]  "
                add(JLabel("$prefix${action.kind.label}").apply {
                    font = DesktopTheme.uiFont(13f, Font.BOLD)
                    foreground = if (action.enabled) accent else DesktopTheme.TEXT_SECONDARY
                })
                add(DesktopTheme.mutedLabel(action.summary(), 11.5f))
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) { editAction(macro, action, forStuck) }
                })
            }

            val btns = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply { isOpaque = false }
            btns.add(DesktopTheme.iconButton(PcIconKind.ARROW_UP, DesktopTheme.TEXT_SECONDARY, "Naik", 28) {
                mutateActionList(macro, forStuck) { it.moveAction(action.id, -1) }
            })
            btns.add(DesktopTheme.iconButton(PcIconKind.ARROW_DOWN, DesktopTheme.TEXT_SECONDARY, "Turun", 28) {
                mutateActionList(macro, forStuck) { it.moveAction(action.id, +1) }
            })
            btns.add(DesktopTheme.iconButton(PcIconKind.EDIT, DesktopTheme.CYAN, "Edit", 28) {
                editAction(macro, action, forStuck)
            })
            btns.add(DesktopTheme.iconButton(PcIconKind.TRASH, DesktopTheme.ROSE, "Hapus", 28) {
                mutateActionList(macro, forStuck) { it.removeAction(action.id) }
            })

            row.add(left, BorderLayout.CENTER)
            row.add(btns, BorderLayout.EAST)

            val wrapped = JPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                border = EmptyBorder(0, depth * 18, 6, 0)
                add(row, BorderLayout.CENTER)
            }
            container.add(wrapped)

            for (slot in action.slots()) {
                val children = action.childList(slot)
                val slotColor = when (slot) {
                    Slot.THEN -> DesktopTheme.EMERALD
                    Slot.ELSE -> DesktopTheme.AMBER
                    Slot.BODY -> DesktopTheme.VIOLET
                }
                val slotBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = EmptyBorder(0, (depth + 1) * 18, 4, 0)
                    add(DesktopTheme.badgeLabel("${slot.label} (${children.size})", slotColor))
                    val addChildBtn = DesktopTheme.tonalButton("+ Aksi", DesktopTheme.BG_ELEVATED, slotColor, radius = 8, padH = 8, padV = 3) {}
                    addChildBtn.addActionListener {
                        showAddActionPopup(addChildBtn, macro, ContainerRef(action.id, slot), forStuck)
                    }
                    add(addChildBtn)
                }
                container.add(slotBar)
                if (children.isNotEmpty()) {
                    renderActionTree(container, macro, children, depth + 1, forStuck)
                }
            }
        }
    }

    private fun openMacroSettingsDialog(macro: Macro) {
        val nameField = JTextField(macro.name, 20)
        val loopSpin = JSpinner(SpinnerNumberModel(macro.loopCount.coerceIn(0, 99999), 0, 99999, 1))
        val delaySpin = JSpinner(SpinnerNumberModel(macro.loopDelayMs.toInt().coerceIn(0, 600_000), 0, 600_000, 100))
        val jitterSpin = JSpinner(SpinnerNumberModel(macro.jitterPx.coerceIn(0, 100), 0, 100, 1))
        val stuckSpin = JSpinner(SpinnerNumberModel(macro.autoRecoverStuckSec.coerceIn(0, 3600), 0, 3600, 10))
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("Nama Macro:")); add(nameField); add(Box.createVerticalStrut(6))
            add(JLabel("Jumlah Loop (0 = Tak Terbatas):")); add(loopSpin); add(Box.createVerticalStrut(6))
            add(JLabel("Jeda Antar Loop (ms):")); add(delaySpin); add(Box.createVerticalStrut(6))
            add(JLabel("Acak Titik / Jitter (px):")); add(jitterSpin); add(Box.createVerticalStrut(6))
            add(JLabel("Batas Anti-Macet (detik, 0 = Mati):")); add(stuckSpin)
        }
        val res = JOptionPane.showConfirmDialog(this, panel, "Setelan Macro", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (res == JOptionPane.OK_OPTION) {
            store.saveMacro(
                macro.copy(
                    name = nameField.text.trim().ifBlank { macro.name },
                    loopCount = (loopSpin.value as Number).toInt(),
                    loopDelayMs = (delaySpin.value as Number).toLong(),
                    jitterPx = (jitterSpin.value as Number).toInt(),
                    autoRecoverStuckSec = (stuckSpin.value as Number).toInt()
                )
            )
        }
    }

    // =========================================================================
    // TAB 2: ImageLibraryScreen (Android Style)
    // =========================================================================

    private fun buildImageLibraryScreen(): Component {
        val col = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // Header
        val head = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 46)
            add(JLabel("Pustaka Gambar").apply {
                font = DesktopTheme.uiFont(19f, Font.BOLD)
                foreground = DesktopTheme.TEXT_PRIMARY
            }, BorderLayout.WEST)
            val acts = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(DesktopTheme.primaryButton("Potong (F8)", iconKind = PcIconKind.CROP, radius = 10, padH = 10, padV = 6) {
                    captureNewTemplateFromScreen()
                })
                add(DesktopTheme.tonalButton("Impor", iconKind = PcIconKind.UPLOAD, radius = 10, padH = 10, padV = 6) {
                    importImageFromDisk()
                })
                add(DesktopTheme.iconButton(PcIconKind.FOLDER_NEW, DesktopTheme.CYAN, "Folder Baru") {
                    val f = JOptionPane.showInputDialog(this@DesktopMainWindow, "Nama folder baru:")
                    if (!f.isNullOrBlank()) {
                        store.createImageFolder(f)
                        renderCurrentScreen()
                    }
                })
            }
            add(acts, BorderLayout.EAST)
        }
        col.add(head)
        col.add(Box.createVerticalStrut(10))

        // Folder filter chips
        val folders = store.imageFolders.value
        val allImages = store.images.value
        if (folders.isNotEmpty()) {
            val chipRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 3)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }
            fun addFolderChip(label: String, key: String) {
                val active = selectedImageFolder == key
                chipRow.add(DesktopTheme.PillButton(
                    text = label,
                    fillBg = if (active) DesktopTheme.CYAN_CONTAINER else DesktopTheme.BG_SURFACE,
                    fgColor = if (active) DesktopTheme.ON_CYAN_CONTAINER else DesktopTheme.TEXT_SECONDARY,
                    borderCol = if (active) DesktopTheme.CYAN else DesktopTheme.BORDER_SUBTLE,
                    radius = 10,
                    padH = 10,
                    padV = 4
                ).apply {
                    font = DesktopTheme.uiFont(11.5f, Font.BOLD)
                    addActionListener { selectedImageFolder = key; renderCurrentScreen() }
                })
            }
            addFolderChip("Semua (${allImages.size})", "")
            for (f in folders) addFolderChip(f, f)
            col.add(chipRow)
            col.add(Box.createVerticalStrut(10))
        }

        val filtered = if (selectedImageFolder.isBlank()) allImages else allImages.filter { it.folder == selectedImageFolder }
        if (filtered.isEmpty()) {
            col.add(DesktopTheme.roundedCard(bg = DesktopTheme.BG_SURFACE, padding = 24, radius = 16).apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                add(JLabel(DesktopTheme.icon(PcIconKind.IMAGE_SEARCH, 36, DesktopTheme.CYAN)).apply { alignmentX = Component.CENTER_ALIGNMENT })
                add(Box.createVerticalStrut(8))
                add(DesktopTheme.mutedLabel("Belum ada gambar target. Tekan 'Potong (F8)' atau 'Impor'.", 12.5f).apply {
                    alignmentX = Component.CENTER_ALIGNMENT
                })
            })
        } else {
            val grid = JPanel(GridLayout(0, 2, 10, 10)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }
            for (img in filtered) {
                val card = DesktopTheme.roundedCard(bg = DesktopTheme.BG_CARD, padding = 10, radius = 14).apply {
                    layout = BorderLayout(0, 6)
                    preferredSize = Dimension(200, 175)
                }
                val thumbLbl = JLabel("", SwingConstants.CENTER).apply {
                    preferredSize = Dimension(180, 96)
                    runCatching {
                        val bi = ImageIO.read(File(img.path))
                        if (bi != null) {
                            val scale = minOf(170.0 / bi.width.coerceAtLeast(1), 90.0 / bi.height.coerceAtLeast(1), 1.0)
                            val nw = (bi.width * scale).toInt().coerceAtLeast(1)
                            val nh = (bi.height * scale).toInt().coerceAtLeast(1)
                            icon = ImageIcon(bi.getScaledInstance(nw, nh, Image.SCALE_SMOOTH))
                        }
                    }
                }
                val meta = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(JLabel(img.displayName).apply {
                        font = DesktopTheme.uiFont(12f, Font.BOLD)
                        foreground = DesktopTheme.TEXT_PRIMARY
                    })
                    add(DesktopTheme.mutedLabel("${img.width}×${img.height} px", 10.5f))
                }
                val foot = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(meta, BorderLayout.CENTER)
                    val acts = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                        isOpaque = false
                        add(DesktopTheme.iconButton(PcIconKind.EDIT, DesktopTheme.CYAN, "Ubah Nama", 26) {
                            val n = JOptionPane.showInputDialog(this@DesktopMainWindow, "Nama baru:", img.displayName.removeSuffix(".png"))
                            if (!n.isNullOrBlank()) store.renameImage(img.name, n)
                        })
                        add(DesktopTheme.iconButton(PcIconKind.TRASH, DesktopTheme.ROSE, "Hapus", 26) {
                            if (JOptionPane.showConfirmDialog(this@DesktopMainWindow, "Hapus '${img.name}'?", "Hapus", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                                store.deleteImage(img.name)
                            }
                        })
                    }
                    add(acts, BorderLayout.EAST)
                }
                card.add(thumbLbl, BorderLayout.CENTER)
                card.add(foot, BorderLayout.SOUTH)
                grid.add(card)
            }
            col.add(grid)
        }

        return wrapAndroidColumnScroll(col)
    }

    // =========================================================================
    // TAB 3: ToolsScreen (Android Segmented Bar: Lua Studio | Live Log | Pengaturan)
    // =========================================================================

    private fun buildToolsScreen(): Component {
        val root = JPanel(BorderLayout()).apply { background = DesktopTheme.BG_BASE }

        // Exact Android SingleChoiceSegmentedButtonRow
        val segWrap = JPanel(GridLayout(1, 3, 6, 0)).apply {
            background = DesktopTheme.BG_BASE
            border = EmptyBorder(12, 16, 10, 16)
        }
        val labels = listOf("Lua Studio" to PcIconKind.CODE, "Live Log" to PcIconKind.LOG, "Pengaturan" to PcIconKind.SETTINGS)
        labels.forEachIndexed { idx, (label, ic) ->
            val active = toolsSubTab == idx
            val fg = if (active) DesktopTheme.ON_CYAN_CONTAINER else DesktopTheme.TEXT_SECONDARY
            segWrap.add(DesktopTheme.PillButton(
                text = label,
                fillBg = if (active) DesktopTheme.CYAN_CONTAINER else DesktopTheme.BG_SURFACE,
                fgColor = fg,
                borderCol = if (active) DesktopTheme.CYAN else DesktopTheme.BORDER_SUBTLE,
                radius = 12,
                btnIcon = DesktopTheme.icon(ic, 14, fg),
                padH = 8,
                padV = 8
            ).apply {
                addActionListener {
                    toolsSubTab = idx
                    renderCurrentScreen()
                }
            })
        }
        root.add(segWrap, BorderLayout.NORTH)

        val content = when (toolsSubTab) {
            0 -> buildLuaStudioSubTab()
            1 -> buildLiveLogSubTab()
            else -> buildSettingsSubTab()
        }
        root.add(content, BorderLayout.CENTER)
        return root
    }

    private fun buildLuaStudioSubTab(): Component {
        val root = JPanel(BorderLayout(0, 8)).apply {
            background = DesktopTheme.BG_BASE
            border = EmptyBorder(0, 16, 14, 16)
        }

        val scripts = store.scripts.value
        val active = scripts.firstOrNull { it.id == selectedScriptId } ?: scripts.firstOrNull()
        selectedScriptId = active?.id
        if (active != null && luaNameField.text.isBlank() && luaCodeArea.text.isBlank()) {
            luaNameField.text = active.name
            luaCodeArea.text = active.code
        }

        val topBox = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        val scriptStrip = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            for (s in scripts) {
                val sel = s.id == selectedScriptId
                add(DesktopTheme.PillButton(
                    text = s.name,
                    fillBg = if (sel) DesktopTheme.CYAN_CONTAINER else DesktopTheme.BG_SURFACE,
                    fgColor = if (sel) DesktopTheme.ON_CYAN_CONTAINER else DesktopTheme.TEXT_SECONDARY,
                    borderCol = if (sel) DesktopTheme.CYAN else DesktopTheme.BORDER_SUBTLE,
                    radius = 10,
                    padH = 10,
                    padV = 4
                ).apply {
                    addActionListener {
                        selectedScriptId = s.id
                        luaNameField.text = s.name
                        luaCodeArea.text = s.code
                        renderCurrentScreen()
                    }
                })
            }
            add(DesktopTheme.tonalButton("+ Baru", radius = 10, padH = 9, padV = 4) {
                val created = store.saveScript(LuaScript(name = "Script ${store.scripts.value.size + 1}", code = DesktopStore.DEFAULT_LUA_SAMPLE))
                selectedScriptId = created.id
                luaNameField.text = created.name
                luaCodeArea.text = created.code
                renderCurrentScreen()
            })
        }
        val nameRow = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            border = EmptyBorder(6, 0, 2, 0)
            add(JLabel("Nama:").apply { foreground = DesktopTheme.TEXT_SECONDARY }, BorderLayout.WEST)
            add(luaNameField, BorderLayout.CENTER)
        }
        topBox.add(scriptStrip)
        topBox.add(nameRow)
        root.add(topBox, BorderLayout.NORTH)

        val codeScroll = JScrollPane(luaCodeArea).apply {
            border = BorderFactory.createLineBorder(DesktopTheme.BORDER_SUBTLE, 1, true)
        }
        root.add(codeScroll, BorderLayout.CENTER)

        val footer = JPanel(BorderLayout(0, 6)).apply {
            isOpaque = false
            border = EmptyBorder(6, 0, 0, 0)
            add(luaStatusLabel, BorderLayout.NORTH)
            val btns = JPanel(GridLayout(1, 3, 8, 0)).apply {
                isOpaque = false
                add(DesktopTheme.outlinedButton("Cek Sintaks", iconKind = PcIconKind.CHECK_CIRCLE, radius = 10) {
                    val res = SandboxedLuaEngine.validateSyntax(luaCodeArea.text)
                    luaStatusLabel.text = if (res.isValid) "Sintaks Lua valid!" else "Error: ${res.message}"
                    luaStatusLabel.foreground = if (res.isValid) DesktopTheme.EMERALD else DesktopTheme.ROSE
                })
                add(DesktopTheme.tonalButton("Simpan", radius = 10) {
                    val cur = store.scripts.value.firstOrNull { it.id == selectedScriptId } ?: LuaScript()
                    val saved = store.saveScript(cur.copy(name = luaNameField.text.trim().ifBlank { "Script Lua" }, code = luaCodeArea.text))
                    selectedScriptId = saved.id
                    luaStatusLabel.text = "Script '${saved.name}' tersimpan."
                    luaStatusLabel.foreground = DesktopTheme.CYAN
                })
                add(DesktopTheme.primaryButton("Jalankan", DesktopTheme.CYAN, DesktopTheme.ON_CYAN, PcIconKind.PLAY, radius = 10) {
                    val cur = store.scripts.value.firstOrNull { it.id == selectedScriptId } ?: LuaScript()
                    val saved = store.saveScript(cur.copy(name = luaNameField.text.trim().ifBlank { "Script Lua" }, code = luaCodeArea.text))
                    selectedScriptId = saved.id
                    coordinator.runLuaScript(saved)
                })
            }
            add(btns, BorderLayout.SOUTH)
        }
        root.add(footer, BorderLayout.SOUTH)
        return root
    }

    private fun buildLiveLogSubTab(): Component {
        val root = JPanel(BorderLayout(0, 8)).apply {
            background = DesktopTheme.BG_BASE
            border = EmptyBorder(0, 16, 14, 16)
        }
        val top = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(DesktopTheme.sectionTitle("Log Eksekusi Real-Time"), BorderLayout.WEST)
            val acts = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(DesktopTheme.outlinedButton("Salin", iconKind = PcIconKind.COPY, radius = 10, padH = 10, padV = 5) {
                    runCatching {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(logArea.text), null)
                    }
                })
                add(DesktopTheme.tonalButton("Bersihkan", DesktopTheme.BG_ELEVATED, DesktopTheme.ROSE, PcIconKind.TRASH, radius = 10, padH = 10, padV = 5) {
                    ExecutionLogBus.clear()
                })
            }
            add(acts, BorderLayout.EAST)
        }
        root.add(top, BorderLayout.NORTH)
        root.add(JScrollPane(logArea).apply {
            border = BorderFactory.createLineBorder(DesktopTheme.BORDER_SUBTLE, 1, true)
        }, BorderLayout.CENTER)
        return root
    }

    private fun buildSettingsSubTab(): Component {
        val s = store.settings.value
        val col = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // Card 1: Target Capture & Emulator ADB
        val capCard = DesktopTheme.roundedCard(bg = DesktopTheme.BG_CARD, padding = 14, radius = 16).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        capCard.add(DesktopTheme.sectionTitle("Mode Tangkap Layar & Target PC/Emulator"))
        capCard.add(Box.createVerticalStrut(8))

        val modeBox = JComboBox(DesktopCaptureMode.entries.toTypedArray()).apply { selectedItem = s.captureMode }
        val winBox = JComboBox<String>().apply { isEditable = true; addItem(s.boundWindowTitle); selectedItem = s.boundWindowTitle }
        val adbBox = JComboBox<String>().apply { isEditable = true; addItem(s.adbSerial); selectedItem = s.adbSerial }
        val adbPathField = JTextField(s.adbExecutable, 18)
        val adbConnectField = JTextField("127.0.0.1:5555", 13)

        fun scanWindowsAsync() {
            uiScope.launch(Dispatchers.IO) {
                val list = Win32Native.listVisibleWindows()
                withContext(Dispatchers.Swing) {
                    winBox.removeAllItems()
                    if (s.boundWindowTitle.isNotBlank()) winBox.addItem(s.boundWindowTitle)
                    list.forEach { winBox.addItem(it.title) }
                }
            }
        }
        fun scanAdbAsync() {
            val path = adbPathField.text.trim().ifBlank { "adb" }
            uiScope.launch(Dispatchers.IO) {
                coordinator.captureAndInput.adb().adbPath = path
                val devs = coordinator.captureAndInput.adb().listDevices()
                withContext(Dispatchers.Swing) {
                    adbBox.removeAllItems()
                    if (devs.isEmpty()) adbBox.addItem("") else devs.forEach { adbBox.addItem(it.serial) }
                    if (s.adbSerial.isNotBlank()) adbBox.selectedItem = s.adbSerial
                }
            }
        }

        fun labeledRow(lbl: String, vararg comps: Component): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 3)).apply {
            isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel(lbl).apply { foreground = DesktopTheme.TEXT_SECONDARY; font = DesktopTheme.uiFont(12f, Font.BOLD) })
            comps.forEach { add(it) }
        }
        capCard.add(labeledRow("Mode:", modeBox))
        capCard.add(labeledRow("Jendela:", winBox, DesktopTheme.tonalButton("Pindai", iconKind = PcIconKind.REFRESH, radius = 8, padH = 8, padV = 4) { scanWindowsAsync() }))
        capCard.add(labeledRow("ADB:", adbBox, DesktopTheme.tonalButton("Pindai", iconKind = PcIconKind.REFRESH, radius = 8, padH = 8, padV = 4) { scanAdbAsync() }))
        capCard.add(labeledRow("Hubungkan:", adbConnectField, DesktopTheme.outlinedButton("Connect", radius = 8, padH = 8, padV = 4) {
            val addr = adbConnectField.text
            uiScope.launch(Dispatchers.IO) {
                val out = coordinator.captureAndInput.adb().connectHost(addr)
                withContext(Dispatchers.Swing) {
                    JOptionPane.showMessageDialog(this@DesktopMainWindow, out)
                    scanAdbAsync()
                }
            }
        }))
        capCard.add(labeledRow("Path adb:", adbPathField))
        col.add(capCard)
        col.add(Box.createVerticalStrut(12))

        // Card 2: PC Multi-Core Optimization
        val perfCard = DesktopTheme.roundedCard(bg = DesktopTheme.BG_CARD, padding = 14, radius = 16).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        perfCard.add(DesktopTheme.sectionTitle("Optimasi Performa PC & Vision"))
        perfCard.add(Box.createVerticalStrut(8))

        val maxCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val fpsBox = JComboBox(arrayOf(15, 30, 60)).apply { selectedItem = s.targetFps }
        val threadSpin = JSpinner(SpinnerNumberModel(s.visionThreads.coerceIn(1, maxCores), 1, maxCores, 1))
        val parallelChk = JCheckBox("Parallel Multi-Thread Template Matching", s.parallelMatching).apply { isOpaque = false }
        val boxChk = JCheckBox("Tampilkan Kotak Hijau Match Highlight", s.showMatchHighlight).apply { isOpaque = false }
        val miniChk = JCheckBox("Buka Floating Bar saat Macro Berjalan", s.showMiniBarOnRun).apply { isOpaque = false }
        val hotkeyChk = JCheckBox("Global Hotkey Windows (F6/F7/F8/F9)", s.globalHotkeysEnabled).apply { isOpaque = false }

        perfCard.add(labeledRow("Target FPS:", fpsBox, JLabel("Thread ($maxCores core):"), threadSpin))
        perfCard.add(parallelChk.apply { alignmentX = Component.LEFT_ALIGNMENT })
        perfCard.add(boxChk.apply { alignmentX = Component.LEFT_ALIGNMENT })
        perfCard.add(miniChk.apply { alignmentX = Component.LEFT_ALIGNMENT })
        perfCard.add(hotkeyChk.apply { alignmentX = Component.LEFT_ALIGNMENT })
        perfCard.add(Box.createVerticalStrut(4))
        perfCard.add(DesktopTheme.mutedLabel("Engine OCR: $ocrEngineLabelText", 11.5f).apply { alignmentX = Component.LEFT_ALIGNMENT })
        perfCard.add(Box.createVerticalStrut(10))
        perfCard.add(DesktopTheme.primaryButton("Simpan & Terapkan Pengaturan", DesktopTheme.CYAN, DesktopTheme.ON_CYAN, PcIconKind.CHECK_CIRCLE, radius = 12) {
            val updated = s.copy(
                captureMode = modeBox.selectedItem as DesktopCaptureMode,
                boundWindowTitle = winBox.selectedItem?.toString().orEmpty().trim(),
                adbSerial = adbBox.selectedItem?.toString().orEmpty().trim(),
                adbExecutable = adbPathField.text.trim().ifBlank { "adb" },
                targetFps = (fpsBox.selectedItem as? Int) ?: 30,
                parallelMatching = parallelChk.isSelected,
                visionThreads = (threadSpin.value as Number).toInt(),
                showMatchHighlight = boxChk.isSelected,
                showMiniBarOnRun = miniChk.isSelected,
                globalHotkeysEnabled = hotkeyChk.isSelected
            )
            store.saveSettings(updated)
            coordinator.applySettings(updated)
            if (updated.globalHotkeysEnabled) hotkeyDaemon.start() else hotkeyDaemon.stop()
            JOptionPane.showMessageDialog(this@DesktopMainWindow, "Pengaturan PC berhasil disimpan & diterapkan!")
        }.apply { alignmentX = Component.LEFT_ALIGNMENT })
        col.add(perfCard)
        col.add(Box.createVerticalStrut(12))

        // Card 3: Backup, Android Compatibility & Crash Diagnostics
        val dataCard = DesktopTheme.roundedCard(bg = DesktopTheme.BG_CARD, padding = 14, radius = 16).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        dataCard.add(DesktopTheme.sectionTitle("Backup & Kompatibilitas Android"))
        dataCard.add(Box.createVerticalStrut(4))
        dataCard.add(DesktopTheme.mutedLabel("Folder data: ${store.rootDir.absolutePath}", 11f).apply { alignmentX = Component.LEFT_ALIGNMENT })
        dataCard.add(Box.createVerticalStrut(10))
        val dataBtns = JPanel(GridLayout(2, 2, 8, 8)).apply {
            isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            add(DesktopTheme.primaryButton("Backup Semua (.zip)", iconKind = PcIconKind.DOWNLOAD, radius = 10) { exportFullBackup() })
            add(DesktopTheme.tonalButton("Impor .zip / .json", iconKind = PcIconKind.UPLOAD, radius = 10) { importBundleFile() })
            add(DesktopTheme.outlinedButton("Buka Folder Data", iconKind = PcIconKind.FOLDER_OPEN, radius = 10) {
                runCatching { Desktop.getDesktop().open(store.rootDir) }
            })
            add(DesktopTheme.outlinedButton("Lihat Crash Log", iconKind = PcIconKind.LOG, radius = 10) {
                val f = DesktopCrashGuard.crashLogFile
                if (!f.exists()) {
                    JOptionPane.showMessageDialog(this@DesktopMainWindow, "Belum ada catatan crash — aplikasi berjalan normal!")
                } else {
                    runCatching { Desktop.getDesktop().open(f) }
                }
            })
        }
        dataCard.add(dataBtns)
        col.add(dataCard)

        return wrapAndroidColumnScroll(col)
    }

    // =========================================================================
    // Macro & Macro Set Mutations, Password Guards & Async Screen Capture
    // =========================================================================

    private fun createQuickAutoClicker() {
        val (sw, sh) = coordinator.captureAndInput.currentScreenSize()
        val quick = store.saveMacro(
            Macro(
                name = "Auto Clicker Cepat",
                loopCount = 0,
                loopDelayMs = 150,
                jitterPx = 2,
                sourceWidth = sw,
                sourceHeight = sh,
                actions = listOf(ClickAction(x = sw / 2, y = sh / 2, durationMs = 50, delayAfterMs = 150))
            )
        )
        coordinator.selectMacro(quick)
        if (!miniBar.isVisible) miniBar.isVisible = true
        miniBar.refreshState()
        unlockedMacroIds += quick.id
        editingMacroId = quick.id
        renderCurrentScreen()
    }

    private fun createNewMacroInSelectedFilter() {
        val targetSet = store.getMacroSetById(selectedSetFilter)
        if (targetSet != null) {
            createNewMacroInSet(targetSet)
            return
        }
        val (sw, sh) = coordinator.captureAndInput.currentScreenSize()
        val created = store.saveMacro(Macro(name = "Macro Baru", sourceWidth = sw, sourceHeight = sh))
        unlockedMacroIds += created.id
        editingMacroId = created.id
        renderCurrentScreen()
    }

    private fun createNewMacroInSet(set: MacroSet) {
        if (set.isLocked) {
            val ok = DesktopDialogs.askPassword(
                this, "Sandi Macro Set Diperlukan",
                "Set '${set.name}' terkunci. Masukkan sandi Set untuk menambah macro baru:"
            ) { MacroSecurity.verifySetPassword(set, it) }
            if (!ok) return
        }
        val (sw, sh) = coordinator.captureAndInput.currentScreenSize()
        var created = Macro(name = "Macro Baru", sourceWidth = sw, sourceHeight = sh, setId = set.id)
        if (MacroSecurity.hasAnySetProtection(set) && MacroSecurity.hasSetPassword(set)) {
            created = MacroSecurity.syncMacroWithSetSecurity(created, set)
        }
        val saved = store.saveMacro(created)
        unlockedMacroIds += saved.id
        editingMacroId = saved.id
        renderCurrentScreen()
    }

    private fun requestOpenMacroEditor(macro: Macro) {
        val parentSet = store.getMacroSetById(macro.setId)
        val locked = MacroSecurity.isEffectivelyLocked(macro, parentSet)
        if (locked && macro.id !in unlockedMacroIds) {
            val ok = DesktopDialogs.askPassword(
                this, "Sandi Macro Diperlukan",
                "Macro '${macro.name}' dikunci. Masukkan sandi untuk membuka editor:"
            ) { MacroSecurity.verifyMacroOrSetPassword(macro, parentSet, it) }
            if (!ok) return
            unlockedMacroIds += macro.id
        }
        editingMacroId = macro.id
        editingWatchdogTab = false
        renderCurrentScreen()
    }

    private fun requestChangeMacroLogo(macro: Macro) {
        val parentSet = store.getMacroSetById(macro.setId)
        if (MacroSecurity.isEffectivelyLocked(macro, parentSet) && macro.id !in unlockedMacroIds) {
            val ok = DesktopDialogs.askPassword(
                this, "Sandi Diperlukan",
                "Masukkan sandi '${macro.name}' untuk mengubah foto logo:"
            ) { MacroSecurity.verifyMacroOrSetPassword(macro, parentSet, it) }
            if (!ok) return
        }
        val chooser = JFileChooser().apply {
            fileFilter = FileNameExtensionFilter("Gambar Logo (*.png, *.jpg, *.jpeg)", "png", "jpg", "jpeg")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile ?: return
        runCatching {
            val savedName = store.saveMacroLogoBytes("logo_${DesktopStore.sanitizeFileName(macro.id)}.png", file.readBytes())
            if (savedName != null) {
                logoThumbCache.remove(savedName)
                store.saveMacro(macro.copy(logoImage = savedName))
            }
        }
    }

    private fun loadMacroLogo(logoImage: String): BufferedImage? {
        if (logoImage.isBlank()) return null
        logoThumbCache[logoImage]?.let { return it }
        val file = store.macroIconFile(logoImage) ?: return null
        if (!file.exists()) return null
        val img = runCatching { ImageIO.read(file) }.getOrNull() ?: return null
        logoThumbCache[logoImage] = img
        return img
    }

    private fun mutateActionList(macro: Macro, forStuck: Boolean, transform: (List<Action>) -> List<Action>) {
        val latest = store.getMacroById(macro.id) ?: macro
        val updated = if (forStuck) latest.copy(stuckActions = transform(latest.stuckActions))
        else latest.copy(actions = transform(latest.actions))
        store.saveMacro(updated)
    }

    private fun showAddActionPopup(anchor: Component, macro: Macro, targetRef: ContainerRef, forStuck: Boolean) {
        val popup = JPopupMenu()
        val (sw, sh) = coordinator.captureAndInput.currentScreenSize()
        for (kind in ActionKind.entries) {
            popup.add(JMenuItem("${kind.label}  —  ${kind.description}").apply {
                addActionListener {
                    val created = kind.create(sw, sh, store.images.value.firstOrNull()?.name.orEmpty())
                    mutateActionList(macro, forStuck) { list -> list.insertInto(targetRef, created) }
                    val updatedMacro = store.getMacroById(macro.id) ?: macro
                    editAction(updatedMacro, created, forStuck)
                }
            })
        }
        popup.show(anchor, 0, anchor.height)
    }

    private fun editAction(macro: Macro, action: Action, forStuck: Boolean) {
        DesktopDialogs.showActionEditorDialog(
            owner = this,
            action = action,
            currentMacro = macro,
            allMacros = store.macros.value,
            images = store.images.value,
            onPickPointOnScreen = { dual, hint, onClosed, cb ->
                captureForOverlayAsync(onFailed = onClosed) { img, bounds, restoreMain ->
                    DesktopOverlays.openPointPickerOverlay(
                        screenshot = img,
                        bounds = bounds,
                        dualPoint = dual,
                        titleHint = hint,
                        onClosed = { restoreMain(); onClosed() },
                        onPicked = cb
                    )
                }
            },
            onPickRegionOrCropOnScreen = { isCrop, hint, onClosed, cb ->
                captureForOverlayAsync(onFailed = onClosed) { img, bounds, restoreMain ->
                    DesktopOverlays.openRegionOrCropOverlay(
                        screenshot = img,
                        bounds = bounds,
                        titleHint = hint,
                        onClosed = { restoreMain(); onClosed() }
                    ) { region, cropImg ->
                        if (isCrop) {
                            val name = JOptionPane.showInputDialog(this, "Nama file gambar target:", "target_${System.currentTimeMillis() % 1000}")
                            if (!name.isNullOrBlank()) {
                                val asset = store.saveImage(name, cropImg)
                                cb(region, asset.name)
                            } else {
                                cb(region, null)
                            }
                        } else {
                            cb(region, null)
                        }
                    }
                }
            },
            onRecordMouseOnScreen = { onClosed, cb ->
                captureForOverlayAsync(onFailed = onClosed) { img, bounds, restoreMain ->
                    DesktopOverlays.openMouseRecorderOverlay(
                        screenshot = img,
                        bounds = bounds,
                        onClosed = { restoreMain(); onClosed() },
                        onFinished = cb
                    )
                }
            },
            onSave = { updatedAction ->
                mutateActionList(macro, forStuck) { list -> list.updateAction(updatedAction.id) { updatedAction } }
            }
        )
    }

    private fun requestOpenMacroSecurity(macro: Macro) {
        val parentSet = store.getMacroSetById(macro.setId)
        if (MacroSecurity.hasPassword(macro) || (parentSet != null && MacroSecurity.hasSetPassword(parentSet))) {
            val ok = DesktopDialogs.askPassword(
                this, "Sandi Diperlukan",
                "Masukkan sandi '${macro.name}' untuk mengubah Kunci, Tenggat, atau Mode Pasif:"
            ) { MacroSecurity.verifyMacroOrSetPassword(macro, parentSet, it) }
            if (!ok) return
        }
        DesktopDialogs.showMacroSecurityDialog(this, macro, store.effectiveNowMs()) { updated ->
            store.saveMacro(updated)
            coordinator.syncActiveMacroFromStore()
        }
    }

    private fun requestDuplicateMacro(macro: Macro) {
        val parentSet = store.getMacroSetById(macro.setId)
        if (MacroSecurity.isEffectivelyLocked(macro, parentSet) && macro.id !in unlockedMacroIds) {
            val ok = DesktopDialogs.askPassword(
                this, "Sandi Diperlukan",
                "Masukkan sandi '${macro.name}' untuk menduplikat macro terkunci:"
            ) { MacroSecurity.verifyMacroOrSetPassword(macro, parentSet, it) }
            if (!ok) return
        }
        store.duplicateMacro(macro)
    }

    private fun openAutoScaleDialog(macro: Macro) {
        val (sw, sh) = coordinator.captureAndInput.currentScreenSize()
        DesktopDialogs.showAutoScaleDialog(this, macro, sw, sh) { enabled, srcW, srcH, mode ->
            val updated = MacroSecurity.updateAutoScale(macro, enabled, srcW, srcH, mode)
            store.saveMacro(updated)
            coordinator.syncActiveMacroFromStore()
        }
    }

    private fun openCreateMacroSet(preselected: Set<String> = emptySet()) {
        DesktopDialogs.showCreateOrEditMacroSetDialog(
            owner = this, existing = null,
            allMacros = store.macros.value, allSets = store.macroSets.value, preselectedIds = preselected
        ) { name, desc, colorHex, selectedIds ->
            val created = store.saveMacroSet(MacroSet(name = name, description = desc, colorHex = colorHex))
            if (selectedIds.isNotEmpty()) store.setMacrosInSet(created.id, selectedIds)
            expandedSetIds += created.id
            renderCurrentScreen()
        }
    }

    private fun requestEditMacroSet(set: MacroSet) {
        if (set.isLocked) {
            val ok = DesktopDialogs.askPassword(
                this, "Sandi Macro Set", "Masukkan sandi Set '${set.name}' untuk mengedit nama & warna:"
            ) { MacroSecurity.verifySetPassword(set, it) }
            if (!ok) return
        }
        DesktopDialogs.showCreateOrEditMacroSetDialog(this, set, emptyList(), emptyList()) { name, desc, colorHex, _ ->
            store.saveMacroSet(set.copy(name = name, description = desc, colorHex = colorHex))
        }
    }

    private fun requestManageSetMembers(set: MacroSet) {
        if (set.isLocked) {
            val ok = DesktopDialogs.askPassword(
                this, "Sandi Macro Set", "Masukkan sandi Set '${set.name}' untuk mengelola anggota macro:"
            ) { MacroSecurity.verifySetPassword(set, it) }
            if (!ok) return
        }
        DesktopDialogs.showManageMacroSetMembersDialog(this, set, store.macros.value, store.macroSets.value) { ids ->
            store.setMacrosInSet(set.id, ids)
        }
    }

    private fun requestOpenSetSecurity(set: MacroSet) {
        if (MacroSecurity.hasSetPassword(set)) {
            val ok = DesktopDialogs.askPassword(
                this, "Sandi Macro Set", "Masukkan sandi Set '${set.name}' untuk mengubah Kunci & Tenggat:"
            ) { MacroSecurity.verifySetPassword(set, it) }
            if (!ok) return
        }
        DesktopDialogs.showMacroSetSecurityDialog(this, set, store.getMacrosInSet(set.id).size, store.effectiveNowMs()) { updated ->
            store.saveMacroSetSecurity(updated)
            coordinator.syncActiveMacroFromStore()
        }
    }

    private fun requestDuplicateMacroSet(set: MacroSet) {
        if (set.isLocked) {
            val ok = DesktopDialogs.askPassword(
                this, "Sandi Macro Set", "Masukkan sandi Set '${set.name}' untuk menduplikat Set terkunci:"
            ) { MacroSecurity.verifySetPassword(set, it) }
            if (!ok) return
        }
        store.duplicateMacroSet(set)
    }

    private fun confirmDeleteMacroSet(set: MacroSet, memberCount: Int) {
        if (memberCount <= 0) {
            if (JOptionPane.showConfirmDialog(this, "Hapus Macro Set '${set.name}'?", "Hapus Set", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                store.deleteMacroSet(set.id, deleteMembers = false)
            }
            return
        }
        val opts = arrayOf("Hapus Set Saja (Macro Tetap Aman)", "Hapus Set + $memberCount Macro", "Batal")
        val choice = JOptionPane.showOptionDialog(
            this, "Macro Set '${set.name}' berisi $memberCount macro.", "Hapus Macro Set",
            JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, opts, opts[0]
        )
        when (choice) {
            0 -> store.deleteMacroSet(set.id, deleteMembers = false)
            1 -> store.deleteMacroSet(set.id, deleteMembers = true)
        }
    }

    private fun requestMoveMacroToSet(macro: Macro) {
        val curSet = store.getMacroSetById(macro.setId)
        if (curSet != null && curSet.isLocked) {
            val ok = DesktopDialogs.askPassword(
                this, "Sandi Macro Set", "Macro berada di Set terkunci '${curSet.name}'. Masukkan sandi Set:"
            ) { MacroSecurity.verifySetPassword(curSet, it) }
            if (!ok) return
        }
        DesktopDialogs.showMoveMacroToSetDialog(this, macro, store.macroSets.value) { targetId ->
            val destSet = if (targetId.isBlank()) null else store.getMacroSetById(targetId)
            if (destSet != null && destSet.isLocked && macro.setId != destSet.id) {
                val ok = DesktopDialogs.askPassword(
                    this, "Sandi Macro Set", "Set tujuan '${destSet.name}' terkunci. Masukkan sandi Set:"
                ) { MacroSecurity.verifySetPassword(destSet, it) }
                if (!ok) return@showMoveMacroToSetDialog
            }
            store.moveMacroToSet(macro.id, targetId)
        }
    }

    private fun exportMacroZip(macro: Macro) {
        val singleUse = DesktopDialogs.askExportOptions(
            this, "Ekspor Macro (.zip)",
            "Ekspor macro '${macro.name}' (${macro.countActions()} aksi + sub-macro + gambar terkait)."
        ) ?: return
        val safe = DesktopStore.sanitizeFileName(macro.name).ifBlank { "macro" }
        val chooser = JFileChooser().apply {
            selectedFile = File(if (singleUse) "${safe}_1x.zip" else "$safe.zip")
            fileFilter = FileNameExtensionFilter("Wjust macro ZIP (*.zip)", "zip")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val outFile = chooser.selectedFile.let { if (it.name.endsWith(".zip", true)) it else File("${it.absolutePath}.zip") }
        runCatching {
            outFile.outputStream().use { store.exportMacroZip(macro, it, singleUseImport = singleUse) }
        }.onSuccess { s ->
            JOptionPane.showMessageDialog(this, "Diekspor ke ${outFile.name}: ${s.macrosExported} macro & ${s.imagesExported} gambar.")
        }.onFailure { err ->
            JOptionPane.showMessageDialog(this, "Gagal ekspor: ${err.message}", "Error", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun exportMacroSetZip(set: MacroSet) {
        val members = store.getMacrosInSet(set.id)
        val singleUse = DesktopDialogs.askExportOptions(
            this, "Ekspor Macro Set (.zip)",
            "Ekspor Set '${set.name}' berisi ${members.size} macro + semua gambar, tautan & proteksi kunci."
        ) ?: return
        val safe = DesktopStore.sanitizeFileName(set.name).ifBlank { "macro_set" }
        val chooser = JFileChooser().apply {
            selectedFile = File(if (singleUse) "${safe}_set_1x.zip" else "${safe}_set.zip")
            fileFilter = FileNameExtensionFilter("Wjust macro Set ZIP (*.zip)", "zip")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val outFile = chooser.selectedFile.let { if (it.name.endsWith(".zip", true)) it else File("${it.absolutePath}.zip") }
        runCatching {
            outFile.outputStream().use { store.exportMacroSetZip(set, it, singleUseImport = singleUse) }
        }.onSuccess { s ->
            JOptionPane.showMessageDialog(this, "Set '${set.name}' diekspor ke ${outFile.name}: ${s.macrosExported} macro & ${s.imagesExported} gambar.")
        }.onFailure { err ->
            JOptionPane.showMessageDialog(this, "Gagal ekspor Set: ${err.message}", "Error", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun exportFullBackup() {
        val chooser = JFileChooser().apply {
            selectedFile = File("wjust_macro_pc_backup.zip")
            fileFilter = FileNameExtensionFilter("ZIP Backup (*.zip)", "zip")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val outFile = chooser.selectedFile.let { if (it.name.endsWith(".zip", true)) it else File("${it.absolutePath}.zip") }
        runCatching {
            outFile.outputStream().use { store.exportAllBackupZip(it) }
        }.onSuccess { s ->
            JOptionPane.showMessageDialog(this, "Backup selesai: ${s.setsExported} set, ${s.macrosExported} macro & ${s.imagesExported} gambar.")
        }.onFailure { err ->
            JOptionPane.showMessageDialog(this, "Gagal backup: ${err.message}", "Error", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun importBundleFile() {
        val chooser = JFileChooser().apply {
            fileFilter = FileNameExtensionFilter("Wjust macro Bundle (*.zip, *.json)", "zip", "json")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile ?: return
        runCatching {
            file.inputStream().use { store.importFromStream(it) }
        }.onSuccess { s ->
            coordinator.syncActiveMacroFromStore()
            val msg = buildString {
                append("Impor selesai: ")
                if (s.setsImported > 0) append("${s.setsImported} set, ")
                append("${s.macrosImported} macro")
                if (s.macrosOverwritten > 0) append(" (${s.macrosOverwritten} ditimpa)")
                if (s.deadlinesExtended > 0) append(" • tenggat +waktu")
                if (s.singleUseImport) append(" • 1x impor")
                if (s.imagesImported > 0) append(", ${s.imagesImported} gambar")
                append(".")
            }
            JOptionPane.showMessageDialog(this, msg)
        }.onFailure { err ->
            JOptionPane.showMessageDialog(this, "Gagal impor: ${err.message}", "Error", JOptionPane.ERROR_MESSAGE)
        }
    }

    /** Non-blocking screen capture before launching snipping/picker overlays (never blocks EDT with Thread.sleep). */
    private fun captureForOverlayAsync(
        onFailed: () -> Unit = {},
        onReady: (img: BufferedImage, bounds: Rectangle, restoreMain: () -> Unit) -> Unit
    ) {
        val prevVisible = isVisible
        val prevMini = miniBar.isVisible
        fun restoreMain() {
            SwingUtilities.invokeLater {
                isVisible = prevVisible
                miniBar.isVisible = prevMini
                if (prevVisible) toFront()
            }
        }
        val hideDelayMs = if (store.settings.value.captureMode != DesktopCaptureMode.ADB_BACKGROUND) {
            isVisible = false
            miniBar.isVisible = false
            180
        } else 10

        Timer(hideDelayMs) {
            uiScope.launch(Dispatchers.IO) {
                val cap = runCatching { coordinator.captureAndInput.captureBufferedImage() }.getOrNull()
                withContext(Dispatchers.Swing) {
                    if (cap == null) {
                        restoreMain()
                        onFailed()
                        JOptionPane.showMessageDialog(
                            this@DesktopMainWindow,
                            "Gagal menangkap layar. Pastikan layar/jendela atau perangkat ADB aktif."
                        )
                    } else {
                        onReady(cap.first, cap.second, ::restoreMain)
                    }
                }
            }
        }.apply { isRepeats = false; start() }
    }

    private fun captureNewTemplateFromScreen() {
        captureForOverlayAsync { img, bounds, restoreMain ->
            DesktopOverlays.openRegionOrCropOverlay(
                screenshot = img,
                bounds = bounds,
                titleHint = "Potong Gambar Target",
                onClosed = restoreMain
            ) { _, cropImg ->
                SwingUtilities.invokeLater {
                    val name = JOptionPane.showInputDialog(this, "Simpan gambar target dengan nama:", "target_${System.currentTimeMillis() % 1000}")
                    if (!name.isNullOrBlank()) {
                        val asset = store.saveImage(name, cropImg, selectedImageFolder)
                        ExecutionLogBus.info("Gambar '${asset.name}' (${asset.width}x${asset.height}) disimpan ke pustaka.")
                    }
                }
            }
        }
    }

    private fun importImageFromDisk() {
        val chooser = JFileChooser().apply {
            isMultiSelectionEnabled = true
            fileFilter = FileNameExtensionFilter("Gambar (*.png, *.jpg, *.jpeg)", "png", "jpg", "jpeg")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        var added = 0
        for (f in chooser.selectedFiles) {
            runCatching {
                val img = ImageIO.read(f) ?: return@runCatching
                store.saveImage(f.nameWithoutExtension, img, selectedImageFolder)
                added++
            }
        }
        if (added > 0) JOptionPane.showMessageDialog(this, "$added gambar ditambahkan ke Pustaka.")
    }
}
