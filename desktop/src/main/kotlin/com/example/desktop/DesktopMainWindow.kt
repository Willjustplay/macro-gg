package com.example.desktop

import com.example.automation.ExecutionLogBus
import com.example.automation.LogLevel
import com.example.automation.SandboxedLuaEngine
import com.example.data.Action
import com.example.data.ActionKind
import com.example.data.ContainerRef
import com.example.data.LuaScript
import com.example.data.Macro
import com.example.data.MacroSecurity
import com.example.data.MacroSet
import com.example.data.Slot
import com.example.data.childList
import com.example.data.countActions
import com.example.data.create
import com.example.data.insertInto
import com.example.data.kind
import com.example.data.moveAction
import com.example.data.removeAction
import com.example.data.slots
import com.example.data.summary
import com.example.data.updateAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.Image
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
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
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.EmptyBorder
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Flagship Wjust macro PC Studio main window.
 */
class DesktopMainWindow(
    private val store: DesktopStore,
    private val coordinator: DesktopCoordinator
) : JFrame("Wjust macro PC — High-Speed Desktop & Emulator Macro Studio") {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    private var selectedSetFilter: String = "__ALL__"
    private val expandedSetIds = HashSet<String>()
    private val unlockedMacroIds = HashSet<String>()
    private var selectedMacroId: String? = store.macros.value.firstOrNull()?.id
    private var selectedScriptId: String? = store.scripts.value.firstOrNull()?.id
    private var editingWatchdogTab: Boolean = false

    private val statusLabel = JLabel("Siap").apply {
        font = DesktopTheme.uiFont(12f, Font.BOLD)
        foreground = DesktopTheme.EMERALD
    }
    private val perfLabel = JLabel("").apply {
        font = DesktopTheme.monoFont(11.5f, Font.PLAIN)
        foreground = DesktopTheme.TEXT_SECONDARY
    }
    private val playTopBtn: JButton
    private val stopTopBtn: JButton

    private val macroListContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = DesktopTheme.BG_BASE
        border = EmptyBorder(8, 8, 8, 8)
    }
    private val setChipsRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
        background = DesktopTheme.BG_SURFACE
    }
    private val editorContainer = JPanel(BorderLayout()).apply {
        background = DesktopTheme.BG_SURFACE
        border = EmptyBorder(12, 14, 12, 14)
    }
    private val imageGridContainer = JPanel(FlowLayout(FlowLayout.LEFT, 10, 10)).apply {
        background = DesktopTheme.BG_BASE
    }
    private val luaListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = DesktopTheme.BG_BASE
    }
    private val luaNameField = JTextField(20)
    private val luaCodeArea = JTextArea().apply {
        font = DesktopTheme.monoFont(13f, Font.PLAIN)
        background = DesktopTheme.BG_BASE
        foreground = DesktopTheme.TEXT_PRIMARY
        caretColor = DesktopTheme.CYAN
        tabSize = 4
    }
    private val luaStatusLabel = DesktopTheme.mutedLabel("Siap", 12f)
    private val logArea = JTextArea().apply {
        isEditable = false
        font = DesktopTheme.monoFont(12f, Font.PLAIN)
        background = DesktopTheme.BG_BASE
        foreground = DesktopTheme.TEXT_PRIMARY
        lineWrap = true
        wrapStyleWord = true
    }

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
        onToggleMiniBar = { SwingUtilities.invokeLater { miniBar.isVisible = !miniBar.isVisible } }
    )

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        minimumSize = Dimension(1060, 700)
        setSize(1240, 780)
        setLocationRelativeTo(null)
        DesktopTheme.logoImage?.let { iconImage = it }

        coordinator.onVisualMatchFlash = { match, label, ox, oy ->
            DesktopOverlays.flashMatchBox(match, label, ox, oy)
        }

        // ---- Top Branded Toolbar ----
        val topBar = JPanel(BorderLayout(12, 0)).apply {
            background = DesktopTheme.BG_SURFACE
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, DesktopTheme.BORDER_SUBTLE),
                EmptyBorder(10, 16, 10, 16)
            )
        }

        val brandLeft = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply { isOpaque = false }
        DesktopTheme.logoIcon(36)?.let { brandLeft.add(JLabel(it)) }
        val titleStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JLabel("Wjust macro PC").apply {
                font = DesktopTheme.uiFont(17f, Font.BOLD)
                foreground = DesktopTheme.TEXT_PRIMARY
            })
            val subRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                add(statusLabel)
                add(perfLabel)
            }
            add(subRow)
        }
        brandLeft.add(titleStack)

        val topActions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply { isOpaque = false }
        playTopBtn = DesktopTheme.primaryButton("▶ Mulai (F6)", DesktopTheme.EMERALD, Color.BLACK) {
            handleGlobalPlayPause()
        }
        stopTopBtn = DesktopTheme.primaryButton("■ Stop (F7)", DesktopTheme.ROSE, Color.WHITE) {
            coordinator.stop()
        }
        topActions.add(DesktopTheme.subtleButton("✂ Potong Layar (F8)") { captureNewTemplateFromScreen() })
        topActions.add(DesktopTheme.subtleButton("Mini Bar (F9)") { miniBar.isVisible = !miniBar.isVisible })
        topActions.add(playTopBtn)
        topActions.add(stopTopBtn)

        topBar.add(brandLeft, BorderLayout.WEST)
        topBar.add(topActions, BorderLayout.EAST)

        // ---- Main Studio Tabs ----
        val tabs = JTabbedPane().apply {
            font = DesktopTheme.uiFont(13f, Font.BOLD)
            background = DesktopTheme.BG_BASE
            foreground = DesktopTheme.TEXT_PRIMARY
        }
        tabs.addTab("⚡ Macro & Macro Set", buildMacroStudioTab())
        tabs.addTab("🖼 Pustaka Gambar", buildImagesTab())
        tabs.addTab("💻 Lua Studio", buildLuaTab())
        tabs.addTab("📜 Live Log", buildLogTab())
        tabs.addTab("⚙ Target & Optimasi PC", buildSettingsTab())

        val root = JPanel(BorderLayout()).apply {
            background = DesktopTheme.BG_BASE
            add(topBar, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }
        contentPane = root

        registerWindowHotkeys()
        bindFlows()
        if (store.settings.value.globalHotkeysEnabled) hotkeyDaemon.start()

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                coordinator.shutdown()
                hotkeyDaemon.stop()
                miniBar.dispose()
                store.flushClockWatermark()
            }
        })

        // Refresh telemetry every 1.2s without burning CPU
        Timer(1200) {
            coordinator.refreshPerfStats()
            miniBar.refreshState()
        }.start()

        rebuildMacroPane()
        rebuildEditorPane()
        rebuildImagesPane()
        rebuildLuaPane()
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
        bind(KeyEvent.VK_F9, "wjust_minibar") { miniBar.isVisible = !miniBar.isVisible }
    }

    private fun bindFlows() {
        uiScope.launch {
            store.macros.collectLatest {
                rebuildMacroPane()
                rebuildEditorPane()
            }
        }
        uiScope.launch {
            store.macroSets.collectLatest {
                rebuildMacroPane()
                rebuildEditorPane()
            }
        }
        uiScope.launch {
            store.images.collectLatest { rebuildImagesPane() }
        }
        uiScope.launch {
            store.scripts.collectLatest { rebuildLuaPane() }
        }
        uiScope.launch {
            coordinator.engineState.collectLatest { st ->
                statusLabel.text = when (st) {
                    DesktopEngineState.RUNNING -> "● Berjalan (Loop ${coordinator.currentLoop.value})"
                    DesktopEngineState.PAUSED -> "● Dijeda"
                    DesktopEngineState.ERROR -> "● Error"
                    DesktopEngineState.IDLE -> "● Siap • Offline"
                }
                statusLabel.foreground = when (st) {
                    DesktopEngineState.RUNNING -> DesktopTheme.EMERALD
                    DesktopEngineState.PAUSED -> DesktopTheme.AMBER
                    DesktopEngineState.ERROR -> DesktopTheme.ROSE
                    DesktopEngineState.IDLE -> DesktopTheme.CYAN
                }
                playTopBtn.text = when (st) {
                    DesktopEngineState.RUNNING -> "⏸ Jeda (F6)"
                    DesktopEngineState.PAUSED -> "▶ Lanjut (F6)"
                    else -> "▶ Mulai (F6)"
                }
                if (st == DesktopEngineState.RUNNING && store.settings.value.showMiniBarOnRun && !miniBar.isVisible) {
                    miniBar.isVisible = true
                }
                miniBar.refreshState()
            }
        }
        uiScope.launch {
            coordinator.perfStats.collectLatest { p ->
                val capStr = if (p.captureMs > 0.05) "${"%.1f".format(p.captureMs)}ms" else "-"
                perfLabel.text = "  |  ${p.captureEngine} • ${p.targetLabel} • Cap $capStr • RAM ${p.ramUsedMb}MB"
            }
        }
        uiScope.launch {
            ExecutionLogBus.logs.collectLatest { logs ->
                val sb = StringBuilder()
                for (entry in logs.takeLast(250)) {
                    sb.append('[').append(entry.timestamp).append("] ")
                        .append(entry.level.name.padEnd(5)).append("  ")
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
        val m = selectedMacroId?.let { store.getMacroById(it) } ?: store.macros.value.firstOrNull()
        if (m == null) {
            JOptionPane.showMessageDialog(this, "Buat atau pilih macro terlebih dahulu.")
            return
        }
        val err = coordinator.runMacro(m)
        if (err != null) {
            JOptionPane.showMessageDialog(this, err, "Tidak Dapat Menjalankan Macro", JOptionPane.WARNING_MESSAGE)
        }
    }

    // ---- Tab 1: Macro & Macro Set Studio ----

    private fun buildMacroStudioTab(): Component {
        val leftPane = JPanel(BorderLayout(0, 6)).apply {
            background = DesktopTheme.BG_BASE
            minimumSize = Dimension(420, 400)
        }

        val quickBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 8)).apply {
            background = DesktopTheme.BG_SURFACE
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, DesktopTheme.BORDER_SUBTLE)
            add(DesktopTheme.primaryButton("+ Macro Baru", DesktopTheme.CYAN_DARK) {
                val targetSet = store.getMacroSetById(selectedSetFilter)
                if (targetSet != null && targetSet.isLocked) {
                    val ok = DesktopDialogs.askPassword(
                        this@DesktopMainWindow,
                        "Sandi Macro Set Diperlukan",
                        "Set '${targetSet.name}' terkunci. Masukkan sandi Set untuk menambah macro baru:"
                    ) { MacroSecurity.verifySetPassword(targetSet, it) }
                    if (!ok) return@primaryButton
                }
                val (sw, sh) = coordinator.captureAndInput.currentScreenSize()
                var created = Macro(
                    name = "Macro Baru",
                    sourceWidth = sw,
                    sourceHeight = sh,
                    setId = targetSet?.id.orEmpty()
                )
                if (targetSet != null && MacroSecurity.hasAnySetProtection(targetSet) && MacroSecurity.hasSetPassword(targetSet)) {
                    created = MacroSecurity.syncMacroWithSetSecurity(created, targetSet)
                }
                val saved = store.saveMacro(created)
                unlockedMacroIds += saved.id
                selectedMacroId = saved.id
                rebuildMacroPane()
                rebuildEditorPane()
            })
            add(DesktopTheme.subtleButton("📁 + Macro Set") { openCreateMacroSet() })
            add(DesktopTheme.subtleButton("↑ Impor .zip/.json") { importBundleFile() })
        }

        val leftTop = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = DesktopTheme.BG_SURFACE
            add(quickBar)
            add(setChipsRow)
        }

        val leftScroll = JScrollPane(macroListContainer).apply {
            border = null
            verticalScrollBar.unitIncrement = 16
        }

        leftPane.add(leftTop, BorderLayout.NORTH)
        leftPane.add(leftScroll, BorderLayout.CENTER)

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, editorContainer).apply {
            dividerLocation = 470
            resizeWeight = 0.42
            border = null
        }
        return split
    }

    private fun rebuildMacroPane() {
        val allMacros = store.macros.value
        val allSets = store.macroSets.value
        val setById = allSets.associateBy { it.id }
        val bySet = allMacros.groupBy { m -> if (m.setId in setById) m.setId else "" }
        val ungrouped = bySet[""].orEmpty()

        if (selectedSetFilter != "__ALL__" && selectedSetFilter != "__UNGROUPED__" && selectedSetFilter !in setById) {
            selectedSetFilter = "__ALL__"
        }

        // Filter chips bar
        setChipsRow.removeAll()
        fun addChip(label: String, id: String, accent: Color = DesktopTheme.CYAN) {
            val active = selectedSetFilter == id
            val btn = JButton(label).apply {
                font = DesktopTheme.uiFont(11.5f, Font.BOLD)
                background = if (active) Color(accent.red, accent.green, accent.blue, 60) else DesktopTheme.BG_ELEVATED
                foreground = if (active) accent else DesktopTheme.TEXT_SECONDARY
                isFocusPainted = false
                margin = java.awt.Insets(3, 9, 3, 9)
                addActionListener {
                    selectedSetFilter = id
                    rebuildMacroPane()
                }
            }
            setChipsRow.add(btn)
        }
        addChip("Semua (${allMacros.size})", "__ALL__")
        for (s in allSets) {
            val cnt = bySet[s.id]?.size ?: 0
            val pfx = if (s.isLocked) "🔒 " else "📁 "
            addChip("$pfx${s.name} ($cnt)", s.id, DesktopTheme.parseHexColor(s.colorHex))
        }
        if (allSets.isNotEmpty() && ungrouped.isNotEmpty()) {
            addChip("Tanpa Set (${ungrouped.size})", "__UNGROUPED__")
        }
        setChipsRow.revalidate()
        setChipsRow.repaint()

        // Main cards list
        macroListContainer.removeAll()
        val now = store.effectiveNowMs()

        val focusedSet = setById[selectedSetFilter]
        if (focusedSet != null) {
            val members = bySet[focusedSet.id].orEmpty()
            macroListContainer.add(buildMacroSetCard(focusedSet, members, now, forceExpanded = true))
        } else {
            if (selectedSetFilter == "__ALL__" && allSets.isNotEmpty()) {
                for (s in allSets) {
                    val members = bySet[s.id].orEmpty()
                    macroListContainer.add(buildMacroSetCard(s, members, now, forceExpanded = s.id in expandedSetIds))
                    macroListContainer.add(Box.createVerticalStrut(8))
                }
            }
            val loose = if (selectedSetFilter == "__UNGROUPED__" || allSets.isNotEmpty()) ungrouped else allMacros
            if (loose.isNotEmpty()) {
                if (allSets.isNotEmpty()) {
                    macroListContainer.add(DesktopTheme.sectionTitle("Macro Tanpa Set (${loose.size})").apply {
                        alignmentX = Component.LEFT_ALIGNMENT
                    })
                    macroListContainer.add(Box.createVerticalStrut(6))
                }
                for (m in loose) {
                    macroListContainer.add(buildMacroCard(m, null, now))
                    macroListContainer.add(Box.createVerticalStrut(6))
                }
            }
        }

        macroListContainer.add(Box.createVerticalGlue())
        macroListContainer.revalidate()
        macroListContainer.repaint()
    }

    private fun buildMacroSetCard(
        set: MacroSet,
        members: List<Macro>,
        now: Long,
        forceExpanded: Boolean
    ): JPanel {
        val accent = DesktopTheme.parseHexColor(set.colorHex)
        val isExp = MacroSecurity.isSetExpired(set, now)
        val isTampered = MacroSecurity.isSetTampered(set)
        val remLabel = MacroSecurity.remainingSetTimeLabel(set, now)
        val passiveCnt = members.count { it.isPassive }
        val activeCnt = (members.size - passiveCnt).coerceAtLeast(0)

        val card = DesktopTheme.roundedCard(
            borderColor = if (isExp || isTampered) DesktopTheme.ROSE else accent,
            bg = DesktopTheme.BG_CARD,
            padding = 10
        ).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }

        val headRow = JPanel(BorderLayout(8, 0)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
        val titleCol = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            val lockTag = if (set.isLocked) " 🔒" else ""
            add(JLabel("📁 ${set.name}$lockTag").apply {
                font = DesktopTheme.uiFont(14f, Font.BOLD)
                foreground = accent
            })
            val sub = "${members.size} macro ($activeCnt Aktif • $passiveCnt Pasif)" +
                if (set.description.isNotBlank()) "  •  ${set.description}" else ""
            add(DesktopTheme.mutedLabel(sub, 11.5f))
        }
        val badges = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        if (isTampered) badges.add(DesktopTheme.badgeLabel("⚠ Tidak Valid", DesktopTheme.ROSE))
        if (set.isLocked) badges.add(DesktopTheme.badgeLabel("🔒 Terkunci", DesktopTheme.VIOLET))
        if (remLabel != null) badges.add(DesktopTheme.badgeLabel("⏳ $remLabel", if (isExp) DesktopTheme.ROSE else DesktopTheme.AMBER))
        headRow.add(titleCol, BorderLayout.CENTER)
        headRow.add(badges, BorderLayout.EAST)
        card.add(headRow)
        card.add(Box.createVerticalStrut(6))

        val actionBar = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        if (selectedSetFilter == "__ALL__") {
            actionBar.add(DesktopTheme.subtleButton(if (forceExpanded) "▾ Sembunyikan" else "▸ Lihat (${members.size})") {
                if (set.id in expandedSetIds) expandedSetIds -= set.id else expandedSetIds += set.id
                rebuildMacroPane()
            })
        }
        actionBar.add(DesktopTheme.subtleButton("Isi Set") { requestManageSetMembers(set) })
        actionBar.add(DesktopTheme.subtleButton(if (set.isLocked) "🔒 Kunci" else "Kunci") { requestOpenSetSecurity(set) })
        actionBar.add(DesktopTheme.primaryButton("⬇ Ekspor .zip", DesktopTheme.CYAN_DARK) { exportMacroSetZip(set) })
        val moreBtn = DesktopTheme.subtleButton("⋮") {}
        moreBtn.addActionListener {
            val menu = JPopupMenu()
            menu.add(JMenuItem("Edit Nama & Warna Set").apply { addActionListener { requestEditMacroSet(set) } })
            menu.add(JMenuItem("Duplikat Set").apply { addActionListener { requestDuplicateMacroSet(set) } })
            menu.add(JMenuItem("Hapus Set...").apply { addActionListener { confirmDeleteMacroSet(set, members.size) } })
            menu.show(moreBtn, 0, moreBtn.height)
        }
        actionBar.add(moreBtn)
        card.add(actionBar)

        if (forceExpanded) {
            card.add(Box.createVerticalStrut(8))
            if (members.isEmpty()) {
                card.add(DesktopTheme.mutedLabel("Set ini masih kosong — klik 'Isi Set' untuk memasukkan macro.", 11.5f))
            } else {
                for (m in members) {
                    card.add(buildMacroCard(m, set, now))
                    card.add(Box.createVerticalStrut(6))
                }
            }
        }
        return card
    }

    private fun buildMacroCard(macro: Macro, parentSet: MacroSet?, now: Long): JPanel {
        val selected = macro.id == selectedMacroId
        val locked = MacroSecurity.isEffectivelyLocked(macro, parentSet)
        val isExp = MacroSecurity.isExpired(macro, now) || (parentSet != null && MacroSecurity.isSetExpired(parentSet, now))
        val isTampered = MacroSecurity.isTampered(macro) || (parentSet != null && MacroSecurity.isSetTampered(parentSet))
        val remLabel = MacroSecurity.remainingTimeLabel(macro, now)
            ?: parentSet?.let { MacroSecurity.remainingSetTimeLabel(it, now) }

        val card = DesktopTheme.roundedCard(
            borderColor = when {
                isExp || isTampered -> DesktopTheme.ROSE
                selected -> DesktopTheme.CYAN
                else -> DesktopTheme.BORDER_SUBTLE
            },
            bg = if (selected) DesktopTheme.BG_ELEVATED else DesktopTheme.BG_SURFACE,
            padding = 10
        ).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 125)
        }

        val topRow = JPanel(BorderLayout(6, 0)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
        val infoCol = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            val pfx = if (locked) "🔒 " else ""
            add(JLabel("$pfx${macro.name.ifBlank { "Macro Baru" }}").apply {
                font = DesktopTheme.uiFont(13f, Font.BOLD)
                foreground = DesktopTheme.TEXT_PRIMARY
            })
            val loopTxt = if (macro.loopCount <= 0) "Loop ∞" else "Loop ${macro.loopCount}×"
            add(DesktopTheme.mutedLabel("${macro.countActions()} aksi • $loopTxt", 11.5f))
        }
        val tags = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        if (locked) tags.add(DesktopTheme.badgeLabel("🔒", DesktopTheme.VIOLET))
        if (macro.isPassive) {
            tags.add(DesktopTheme.badgeLabel(
                if (macro.passiveEnabled) "🔗 Pasif ON" else "⛔ Pasif OFF",
                if (macro.passiveEnabled) DesktopTheme.EMERALD else DesktopTheme.AMBER
            ))
        }
        if (remLabel != null) tags.add(DesktopTheme.badgeLabel("⏳ $remLabel", if (isExp) DesktopTheme.ROSE else DesktopTheme.AMBER))
        topRow.add(infoCol, BorderLayout.CENTER)
        topRow.add(tags, BorderLayout.EAST)
        card.add(topRow)
        card.add(Box.createVerticalStrut(6))

        val btnRow = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        btnRow.add(DesktopTheme.subtleButton(if (locked) "🔒 Edit" else "✏ Edit") {
            selectedMacroId = macro.id
            requestSelectAndUnlockMacro(macro)
        })
        when {
            isExp || isTampered -> btnRow.add(DesktopTheme.subtleButton("Habis", DesktopTheme.ROSE) {
                val denial = MacroSecurity.checkDirectRun(macro, store.effectiveNowMs(), parentSet)
                if (denial != null) JOptionPane.showMessageDialog(this, MacroSecurity.denialMessage(macro, denial, parentSet))
            })
            macro.isPassive -> btnRow.add(DesktopTheme.subtleButton(
                if (macro.passiveEnabled) "Pasif ON" else "Pasif OFF",
                if (macro.passiveEnabled) DesktopTheme.EMERALD else DesktopTheme.AMBER
            ) {
                val updated = MacroSecurity.togglePassiveEnabled(macro, !macro.passiveEnabled)
                store.saveMacro(updated)
            })
            else -> btnRow.add(DesktopTheme.primaryButton("▶ Mulai", DesktopTheme.EMERALD, Color.BLACK) {
                selectedMacroId = macro.id
                val err = coordinator.runMacro(macro)
                if (err != null) JOptionPane.showMessageDialog(this, err, "Tidak Dapat Menjalankan", JOptionPane.WARNING_MESSAGE)
            })
        }
        val moreBtn = DesktopTheme.subtleButton("⋮") {}
        moreBtn.addActionListener {
            val menu = JPopupMenu()
            menu.add(JMenuItem("📐 Auto-Scale Layar (Tanpa Sandi)").apply { addActionListener { openAutoScaleDialog(macro) } })
            menu.add(JMenuItem("📁 Pindah ke Macro Set...").apply { addActionListener { requestMoveMacroToSet(macro) } })
            menu.add(JMenuItem("🔒 Kunci, Tenggat & Pasif").apply { addActionListener { requestOpenMacroSecurity(macro) } })
            menu.add(JMenuItem("📋 Duplikat").apply { addActionListener { requestDuplicateMacro(macro) } })
            menu.add(JMenuItem("⬇ Ekspor Macro (.zip)").apply { addActionListener { exportMacroZip(macro) } })
            menu.add(JMenuItem("🗑 Hapus").apply {
                foreground = DesktopTheme.ROSE
                addActionListener {
                    if (JOptionPane.showConfirmDialog(this@DesktopMainWindow, "Hapus macro '${macro.name}'?", "Hapus", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                        store.deleteMacro(macro.id)
                        if (selectedMacroId == macro.id) selectedMacroId = store.macros.value.firstOrNull()?.id
                    }
                }
            })
            menu.show(moreBtn, 0, moreBtn.height)
        }
        btnRow.add(moreBtn)
        card.add(btnRow)
        return card
    }

    // ---- Right Pane: Visual Macro Editor ----

    private fun rebuildEditorPane() {
        editorContainer.removeAll()
        val macro = selectedMacroId?.let { store.getMacroById(it) } ?: store.macros.value.firstOrNull()
        if (macro == null) {
            editorContainer.add(DesktopTheme.mutedLabel("Pilih atau buat macro di sebelah kiri untuk mulai mengedit.", 13f), BorderLayout.CENTER)
            editorContainer.revalidate()
            editorContainer.repaint()
            return
        }
        selectedMacroId = macro.id
        val parentSet = store.getMacroSetById(macro.setId)
        val locked = MacroSecurity.isEffectivelyLocked(macro, parentSet)

        if (locked && macro.id !in unlockedMacroIds) {
            val lockBox = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = EmptyBorder(40, 24, 24, 24)
                add(JLabel("🔒 Macro '${macro.name}' Dikunci").apply {
                    font = DesktopTheme.uiFont(18f, Font.BOLD)
                    foreground = DesktopTheme.VIOLET
                    alignmentX = Component.CENTER_ALIGNMENT
                })
                add(Box.createVerticalStrut(8))
                add(DesktopTheme.mutedLabel(
                    "Daftar aksi & setelan disembunyikan. Auto-Scale Layar & Pasif ON/OFF tetap bisa diatur tanpa sandi.",
                    13f
                ).apply { alignmentX = Component.CENTER_ALIGNMENT })
                add(Box.createVerticalStrut(18))
                val row = JPanel(FlowLayout(FlowLayout.CENTER, 10, 0)).apply { isOpaque = false }
                row.add(DesktopTheme.primaryButton("🔓 Buka Editor dengan Sandi", DesktopTheme.VIOLET, Color.BLACK) {
                    requestSelectAndUnlockMacro(macro)
                })
                row.add(DesktopTheme.subtleButton("📐 Auto-Scale Layar (Tanpa Sandi)") { openAutoScaleDialog(macro) })
                if (macro.isPassive) {
                    row.add(DesktopTheme.subtleButton(if (macro.passiveEnabled) "🔗 Pasif ON → OFF" else "⛔ Pasif OFF → ON") {
                        store.saveMacro(MacroSecurity.togglePassiveEnabled(macro, !macro.passiveEnabled))
                    })
                }
                add(row)
            }
            editorContainer.add(lockBox, BorderLayout.CENTER)
            editorContainer.revalidate()
            editorContainer.repaint()
            return
        }

        // Macro Header + Loop/Jitter/Anti-Macet Settings
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        val nameField = JTextField(macro.name, 20)
        val loopSpin = JSpinner(SpinnerNumberModel(macro.loopCount.coerceIn(0, 99999), 0, 99999, 1))
        val delaySpin = JSpinner(SpinnerNumberModel(macro.loopDelayMs.toInt().coerceIn(0, 600_000), 0, 600_000, 100))
        val jitterSpin = JSpinner(SpinnerNumberModel(macro.jitterPx.coerceIn(0, 100), 0, 100, 1))
        val stuckSpin = JSpinner(SpinnerNumberModel(macro.autoRecoverStuckSec.coerceIn(0, 3600), 0, 3600, 10))

        val r1 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 3)).apply {
            isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel("Nama:")); add(nameField)
            add(DesktopTheme.primaryButton("💾 Simpan Setelan", DesktopTheme.CYAN_DARK) {
                store.saveMacro(
                    macro.copy(
                        name = nameField.text.trim().ifBlank { macro.name },
                        loopCount = (loopSpin.value as Number).toInt(),
                        loopDelayMs = (delaySpin.value as Number).toLong(),
                        jitterPx = (jitterSpin.value as Number).toInt(),
                        autoRecoverStuckSec = (stuckSpin.value as Number).toInt()
                    )
                )
            })
            add(DesktopTheme.subtleButton("📐 Auto-Scale") { openAutoScaleDialog(macro) })
            add(DesktopTheme.subtleButton("🔒 Kunci") { requestOpenMacroSecurity(macro) })
        }
        val r2 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 3)).apply {
            isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel("Loop (0=∞):")); add(loopSpin)
            add(JLabel("Jeda Loop (ms):")); add(delaySpin)
            add(JLabel("Jitter (px):")); add(jitterSpin)
            add(JLabel("Anti-Macet (dtk):")); add(stuckSpin)
        }
        val r3 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            val mainTabBtn = DesktopTheme.primaryButton(
                "Aksi Utama (${macro.actions.size})",
                if (!editingWatchdogTab) DesktopTheme.CYAN_DARK else DesktopTheme.BG_ELEVATED
            ) { editingWatchdogTab = false; rebuildEditorPane() }
            val stuckTabBtn = DesktopTheme.primaryButton(
                "🛡 Anti-Macet (${macro.stuckActions.size})",
                if (editingWatchdogTab) DesktopTheme.ORANGE else DesktopTheme.BG_ELEVATED
            ) { editingWatchdogTab = true; rebuildEditorPane() }
            val addBtn = DesktopTheme.primaryButton("+ Tambah Aksi", DesktopTheme.EMERALD, Color.BLACK) {}
            addBtn.addActionListener {
                showAddActionPopup(addBtn, macro, ContainerRef(null, null), forStuck = editingWatchdogTab)
            }
            add(mainTabBtn); add(stuckTabBtn); add(Box.createHorizontalStrut(8)); add(addBtn)
        }
        header.add(r1); header.add(r2); header.add(r3)
        editorContainer.add(header, BorderLayout.NORTH)

        // Action Tree List
        val listPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = DesktopTheme.BG_BASE
            border = EmptyBorder(8, 8, 8, 8)
        }
        val rootActions = if (editingWatchdogTab) macro.stuckActions else macro.actions
        if (rootActions.isEmpty()) {
            listPanel.add(DesktopTheme.mutedLabel("Belum ada aksi. Klik '+ Tambah Aksi' untuk menambahkan Klik, Gambar, Kejar Objek, Teks/Angka, Loncat, dll.", 12.5f))
        } else {
            renderActionTree(listPanel, macro, rootActions, depth = 0, forStuck = editingWatchdogTab)
        }
        listPanel.add(Box.createVerticalGlue())

        val scroll = JScrollPane(listPanel).apply {
            border = BorderFactory.createLineBorder(DesktopTheme.BORDER_SUBTLE, 1, true)
            verticalScrollBar.unitIncrement = 16
        }
        editorContainer.add(scroll, BorderLayout.CENTER)
        editorContainer.revalidate()
        editorContainer.repaint()
    }

    private fun renderActionTree(
        container: JPanel,
        macro: Macro,
        actions: List<Action>,
        depth: Int,
        forStuck: Boolean
    ) {
        for ((idx, action) in actions.withIndex()) {
            val row = DesktopTheme.roundedCard(
                borderColor = if (action.enabled) DesktopTheme.BORDER_SUBTLE else Color(0x33, 0x41, 0x55),
                bg = DesktopTheme.BG_CARD,
                padding = 8
            ).apply {
                layout = BorderLayout(8, 0)
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 64)
            }

            val left = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                val prefix = if (action.enabled) "#${idx + 1} " else "#${idx + 1} [OFF] "
                add(JLabel("$prefix${action.kind.label}").apply {
                    font = DesktopTheme.uiFont(12.5f, Font.BOLD)
                    foreground = if (action.enabled) DesktopTheme.CYAN else DesktopTheme.TEXT_SECONDARY
                })
                add(DesktopTheme.mutedLabel(action.summary(), 11.5f))
            }

            val btns = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
            btns.add(DesktopTheme.subtleButton("✏") { editAction(macro, action, forStuck) })
            btns.add(DesktopTheme.subtleButton("↑") { mutateActionList(macro, forStuck) { it.moveAction(action.id, -1) } })
            btns.add(DesktopTheme.subtleButton("↓") { mutateActionList(macro, forStuck) { it.moveAction(action.id, +1) } })
            btns.add(DesktopTheme.subtleButton(if (action.enabled) "ON" else "OFF") {
                mutateActionList(macro, forStuck) { it.updateAction(action.id) { a -> a.withEnabled(!a.enabled) } }
            })
            btns.add(DesktopTheme.subtleButton("🗑", DesktopTheme.ROSE) {
                mutateActionList(macro, forStuck) { it.removeAction(action.id) }
            })

            row.add(left, BorderLayout.CENTER)
            row.add(btns, BorderLayout.EAST)

            val wrapped = JPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                border = EmptyBorder(0, depth * 22, 5, 0)
                add(row, BorderLayout.CENTER)
            }
            container.add(wrapped)

            // Render nested Then / Else / Loop slots
            for (slot in action.slots()) {
                val children = action.childList(slot)
                val slotBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = EmptyBorder(0, (depth + 1) * 22, 2, 0)
                    val slotColor = when (slot) {
                        Slot.THEN -> DesktopTheme.EMERALD
                        Slot.ELSE -> DesktopTheme.AMBER
                        Slot.BODY -> DesktopTheme.VIOLET
                    }
                    add(DesktopTheme.badgeLabel("${slot.label} (${children.size})", slotColor))
                    val addChildBtn = DesktopTheme.subtleButton("+ Aksi") {}
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
            onPickPointOnScreen = { dual, hint, cb ->
                val cap = captureForOverlay() ?: return@showActionEditorDialog
                DesktopOverlays.openPointPickerOverlay(this, cap.first, cap.second, dual, hint, cb)
            },
            onPickRegionOrCropOnScreen = { isCrop, hint, cb ->
                val cap = captureForOverlay() ?: return@showActionEditorDialog
                DesktopOverlays.openRegionOrCropOverlay(this, cap.first, cap.second, hint) { region, cropImg ->
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
            },
            onRecordMouseOnScreen = { cb ->
                val cap = captureForOverlay() ?: return@showActionEditorDialog
                DesktopOverlays.openMouseRecorderOverlay(this, cap.first, cap.second, cb)
            },
            onSave = { updatedAction ->
                mutateActionList(macro, forStuck) { list -> list.updateAction(updatedAction.id) { updatedAction } }
            }
        )
    }

    // ---- Tab 2: Pustaka Gambar ----

    private fun buildImagesTab(): Component {
        val root = JPanel(BorderLayout(8, 8)).apply {
            background = DesktopTheme.BG_BASE
            border = EmptyBorder(10, 12, 10, 12)
        }
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            isOpaque = false
            add(DesktopTheme.primaryButton("✂ Potong dari Layar (F8)", DesktopTheme.CYAN_DARK) { captureNewTemplateFromScreen() })
            add(DesktopTheme.subtleButton("+ Impor PNG/JPG") { importImageFromDisk() })
            add(DesktopTheme.subtleButton("📁 Buat Folder") {
                val n = JOptionPane.showInputDialog(this@DesktopMainWindow, "Nama folder gambar:")
                if (!n.isNullOrBlank()) store.createImageFolder(n)
            })
        }
        root.add(bar, BorderLayout.NORTH)
        root.add(JScrollPane(imageGridContainer).apply { border = null; verticalScrollBar.unitIncrement = 16 }, BorderLayout.CENTER)
        return root
    }

    private fun rebuildImagesPane() {
        imageGridContainer.removeAll()
        val list = store.images.value
        if (list.isEmpty()) {
            imageGridContainer.add(DesktopTheme.mutedLabel("Belum ada gambar target. Klik 'Potong dari Layar (F8)' untuk memotong ikon/tombol/pasukan.", 13f))
        } else {
            for (asset in list) {
                val card = DesktopTheme.roundedCard(padding = 8).apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    preferredSize = Dimension(156, 175)
                }
                val thumb = runCatching {
                    val img = ImageIO.read(File(asset.path))
                    if (img != null) {
                        val scale = minOf(128.0 / img.width, 86.0 / img.height, 1.0)
                        val w = (img.width * scale).toInt().coerceAtLeast(16)
                        val h = (img.height * scale).toInt().coerceAtLeast(16)
                        ImageIcon(img.getScaledInstance(w, h, Image.SCALE_SMOOTH))
                    } else null
                }.getOrNull()
                card.add(JLabel(thumb).apply {
                    alignmentX = Component.CENTER_ALIGNMENT
                    preferredSize = Dimension(132, 90)
                })
                card.add(JLabel(asset.displayName).apply {
                    font = DesktopTheme.uiFont(11.5f, Font.BOLD)
                    foreground = DesktopTheme.TEXT_PRIMARY
                    alignmentX = Component.CENTER_ALIGNMENT
                })
                card.add(DesktopTheme.mutedLabel("${asset.width}×${asset.height}", 10.5f).apply {
                    alignmentX = Component.CENTER_ALIGNMENT
                })
                val row = JPanel(FlowLayout(FlowLayout.CENTER, 4, 2)).apply { isOpaque = false }
                row.add(DesktopTheme.subtleButton("Ubah") {
                    val next = JOptionPane.showInputDialog(this, "Nama baru:", asset.displayName.removeSuffix(".png"))
                    if (!next.isNullOrBlank()) store.renameImage(asset.name, next)
                })
                row.add(DesktopTheme.subtleButton("🗑", DesktopTheme.ROSE) { store.deleteImage(asset.name) })
                card.add(row)
                imageGridContainer.add(card)
            }
        }
        imageGridContainer.revalidate()
        imageGridContainer.repaint()
    }

    // ---- Tab 3: Lua Studio ----

    private fun buildLuaTab(): Component {
        val left = JPanel(BorderLayout(6, 6)).apply {
            background = DesktopTheme.BG_BASE
            border = EmptyBorder(8, 8, 8, 8)
            preferredSize = Dimension(240, 400)
            add(DesktopTheme.primaryButton("+ Script Lua Baru") {
                val created = store.saveScript(LuaScript(name = "Script Baru", code = DesktopStore.DEFAULT_LUA_SAMPLE))
                selectedScriptId = created.id
                rebuildLuaPane()
            }, BorderLayout.NORTH)
            add(JScrollPane(luaListPanel).apply { border = null }, BorderLayout.CENTER)
        }
        val right = JPanel(BorderLayout(8, 8)).apply {
            background = DesktopTheme.BG_SURFACE
            border = EmptyBorder(10, 12, 10, 12)
        }
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(JLabel("Nama Script:")); add(luaNameField)
            add(DesktopTheme.subtleButton("✓ Cek Sintaks") {
                val res = SandboxedLuaEngine.validateSyntax(luaCodeArea.text)
                luaStatusLabel.text = if (res.isValid) "✓ Sintaks Lua valid!" else "✗ Error: ${res.message}"
                luaStatusLabel.foreground = if (res.isValid) DesktopTheme.EMERALD else DesktopTheme.ROSE
            })
            add(DesktopTheme.primaryButton("💾 Simpan") {
                val cur = store.scripts.value.firstOrNull { it.id == selectedScriptId } ?: LuaScript()
                val saved = store.saveScript(cur.copy(name = luaNameField.text.trim().ifBlank { "Script Lua" }, code = luaCodeArea.text))
                selectedScriptId = saved.id
                luaStatusLabel.text = "Tersimpan."
                luaStatusLabel.foreground = DesktopTheme.CYAN
            })
            add(DesktopTheme.primaryButton("▶ Jalankan", DesktopTheme.EMERALD, Color.BLACK) {
                val cur = store.scripts.value.firstOrNull { it.id == selectedScriptId } ?: LuaScript()
                val saved = store.saveScript(cur.copy(name = luaNameField.text.trim().ifBlank { "Script Lua" }, code = luaCodeArea.text))
                coordinator.runLuaScript(saved)
            })
        }
        right.add(bar, BorderLayout.NORTH)
        right.add(JScrollPane(luaCodeArea), BorderLayout.CENTER)
        right.add(luaStatusLabel, BorderLayout.SOUTH)
        return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply { dividerLocation = 250; border = null }
    }

    private fun rebuildLuaPane() {
        luaListPanel.removeAll()
        val scripts = store.scripts.value
        val active = scripts.firstOrNull { it.id == selectedScriptId } ?: scripts.firstOrNull()
        selectedScriptId = active?.id
        for (s in scripts) {
            val isSel = s.id == selectedScriptId
            val btn = DesktopTheme.primaryButton(
                s.name,
                if (isSel) DesktopTheme.CYAN_DARK else DesktopTheme.BG_CARD
            ) {
                selectedScriptId = s.id
                luaNameField.text = s.name
                luaCodeArea.text = s.code
                rebuildLuaPane()
            }.apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 36)
            }
            luaListPanel.add(btn)
            luaListPanel.add(Box.createVerticalStrut(4))
        }
        if (active != null && luaNameField.text.isBlank() && luaCodeArea.text.isBlank()) {
            luaNameField.text = active.name
            luaCodeArea.text = active.code
        }
        luaListPanel.revalidate()
        luaListPanel.repaint()
    }

    // ---- Tab 4: Live Log ----

    private fun buildLogTab(): Component {
        val root = JPanel(BorderLayout(8, 8)).apply {
            background = DesktopTheme.BG_BASE
            border = EmptyBorder(10, 12, 10, 12)
        }
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(DesktopTheme.sectionTitle("Live Log Eksekusi"))
            add(DesktopTheme.subtleButton("Bersihkan Log") { ExecutionLogBus.clear() })
        }
        root.add(bar, BorderLayout.NORTH)
        root.add(JScrollPane(logArea), BorderLayout.CENTER)
        return root
    }

    // ---- Tab 5: Target Capture & PC Performance Optimization ----

    private fun buildSettingsTab(): Component {
        val s = store.settings.value
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = DesktopTheme.BG_BASE
            border = EmptyBorder(16, 18, 16, 18)
        }

        // Card 1: Target Capture Engine
        val capCard = DesktopTheme.roundedCard(padding = 14).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        capCard.add(DesktopTheme.sectionTitle("🎯 Mode Tangkap Layar & Target Game/Emulator"))
        val modeBox = JComboBox(DesktopCaptureMode.entries.toTypedArray()).apply { selectedItem = s.captureMode }
        val winBox = JComboBox<String>().apply { isEditable = true; selectedItem = s.boundWindowTitle }
        val adbBox = JComboBox<String>().apply { isEditable = true; selectedItem = s.adbSerial }
        val adbPathField = JTextField(s.adbExecutable, 26)
        val adbConnectField = JTextField("127.0.0.1:5555", 16)

        fun refreshWindows() {
            winBox.removeAllItems()
            winBox.addItem(s.boundWindowTitle)
            Win32Native.listVisibleWindows().forEach { w -> winBox.addItem(w.title) }
        }
        fun refreshAdbDevices() {
            coordinator.captureAndInput.adb().adbPath = adbPathField.text.trim().ifBlank { "adb" }
            adbBox.removeAllItems()
            val devs = coordinator.captureAndInput.adb().listDevices()
            if (devs.isEmpty()) adbBox.addItem("") else devs.forEach { adbBox.addItem(it.serial) }
            if (s.adbSerial.isNotBlank()) adbBox.selectedItem = s.adbSerial
        }

        capCard.add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel("Mode Tangkap:")); add(modeBox)
        })
        capCard.add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel("Jendela Target (Windows):")); add(winBox)
            add(DesktopTheme.subtleButton("🔄 Pindai Jendela") { refreshWindows() })
        })
        capCard.add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel("Perangkat ADB:")); add(adbBox)
            add(DesktopTheme.subtleButton("🔄 Pindai ADB") { refreshAdbDevices() })
            add(adbConnectField)
            add(DesktopTheme.subtleButton("Hubungkan") {
                val res = coordinator.captureAndInput.adb().connectHost(adbConnectField.text)
                JOptionPane.showMessageDialog(this@DesktopMainWindow, res)
                refreshAdbDevices()
            })
        })
        capCard.add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel("Path adb.exe / adb:")); add(adbPathField)
        })
        panel.add(capCard)
        panel.add(Box.createVerticalStrut(12))

        // Card 2: Performance Optimization
        val perfCard = DesktopTheme.roundedCard(padding = 14).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        perfCard.add(DesktopTheme.sectionTitle("⚡ Optimasi Performa CPU & Vision Multi-Core"))
        val maxCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val fpsBox = JComboBox(arrayOf(15, 30, 60)).apply { selectedItem = s.targetFps }
        val parallelChk = JCheckBox("Aktifkan Multi-Thread Parallel Template Matching (3×–5× lebih cepat di layar HD/4K)", s.parallelMatching).apply { isOpaque = false }
        val threadSpin = JSpinner(SpinnerNumberModel(s.visionThreads.coerceIn(1, maxCores), 1, maxCores, 1))
        val boxChk = JCheckBox("Tampilkan Kotak Hijau (Match Highlight) di Layar", s.showMatchHighlight).apply { isOpaque = false }
        val miniChk = JCheckBox("Tampilkan Mini Bar Mengambang saat Macro Berjalan", s.showMiniBarOnRun).apply { isOpaque = false }
        val hotkeyChk = JCheckBox("Aktifkan Global Hotkey Windows (F6 Mulai/Jeda, F7 Stop, F8 Potong, F9 Mini Bar)", s.globalHotkeysEnabled).apply { isOpaque = false }

        perfCard.add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel("Target FPS Tangkap Layar:")); add(fpsBox)
            add(JLabel("Thread Vision (CPU: $maxCores core):")); add(threadSpin)
        })
        perfCard.add(parallelChk.apply { alignmentX = Component.LEFT_ALIGNMENT })
        perfCard.add(boxChk.apply { alignmentX = Component.LEFT_ALIGNMENT })
        perfCard.add(miniChk.apply { alignmentX = Component.LEFT_ALIGNMENT })
        perfCard.add(hotkeyChk.apply { alignmentX = Component.LEFT_ALIGNMENT })
        perfCard.add(Box.createVerticalStrut(6))
        perfCard.add(DesktopTheme.mutedLabel("Engine OCR PC aktif: ${DesktopOcrEngine.activeEngineName()}", 12f).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        })
        perfCard.add(Box.createVerticalStrut(8))
        perfCard.add(DesktopTheme.primaryButton("💾 Simpan & Terapkan Optimasi", DesktopTheme.EMERALD, Color.BLACK) {
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
            JOptionPane.showMessageDialog(this@DesktopMainWindow, "Pengaturan target & optimasi PC berhasil diterapkan!")
        }.apply { alignmentX = Component.LEFT_ALIGNMENT })
        panel.add(perfCard)
        panel.add(Box.createVerticalStrut(12))

        // Card 3: Backup & Data
        val dataCard = DesktopTheme.roundedCard(padding = 14).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        dataCard.add(DesktopTheme.sectionTitle("💾 Backup, Impor & Kompatibilitas Android"))
        dataCard.add(DesktopTheme.mutedLabel("Folder data offline: ${store.rootDir.absolutePath}", 12f).apply { alignmentX = Component.LEFT_ALIGNMENT })
        dataCard.add(Box.createVerticalStrut(8))
        dataCard.add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            add(DesktopTheme.primaryButton("⬇ Backup Semua (.zip)") { exportFullBackup() })
            add(DesktopTheme.subtleButton("↑ Impor Macro / Set (.zip / .json)") { importBundleFile() })
            add(DesktopTheme.subtleButton("📂 Buka Folder Data") {
                runCatching { Desktop.getDesktop().open(store.rootDir) }
            })
        })
        panel.add(dataCard)
        panel.add(Box.createVerticalGlue())

        return JScrollPane(panel).apply { border = null; verticalScrollBar.unitIncrement = 16 }
    }

    // ---- Password-Guarded Handlers & Export/Import ----

    private fun requestSelectAndUnlockMacro(macro: Macro) {
        val parentSet = store.getMacroSetById(macro.setId)
        val locked = MacroSecurity.isEffectivelyLocked(macro, parentSet)
        if (locked && macro.id !in unlockedMacroIds) {
            val ok = DesktopDialogs.askPassword(
                this,
                "Sandi Macro Diperlukan",
                "Macro '${macro.name}' dikunci. Masukkan sandi untuk membuka editor:"
            ) { MacroSecurity.verifyMacroOrSetPassword(macro, parentSet, it) }
            if (!ok) return
            unlockedMacroIds += macro.id
        }
        selectedMacroId = macro.id
        rebuildMacroPane()
        rebuildEditorPane()
    }

    private fun requestOpenMacroSecurity(macro: Macro) {
        val parentSet = store.getMacroSetById(macro.setId)
        if (MacroSecurity.hasPassword(macro) || (parentSet != null && MacroSecurity.hasSetPassword(parentSet))) {
            val ok = DesktopDialogs.askPassword(
                this,
                "Sandi Diperlukan",
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
                this,
                "Sandi Diperlukan",
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
            owner = this,
            existing = null,
            allMacros = store.macros.value,
            allSets = store.macroSets.value,
            preselectedIds = preselected
        ) { name, desc, colorHex, selectedIds ->
            val created = store.saveMacroSet(MacroSet(name = name, description = desc, colorHex = colorHex))
            if (selectedIds.isNotEmpty()) store.setMacrosInSet(created.id, selectedIds)
            expandedSetIds += created.id
            rebuildMacroPane()
        }
    }

    private fun requestEditMacroSet(set: MacroSet) {
        if (set.isLocked) {
            val ok = DesktopDialogs.askPassword(
                this, "Sandi Macro Set", "Masukkan sandi Set '${set.name}' untuk mengedit nama & warna:"
            ) { MacroSecurity.verifySetPassword(set, it) }
            if (!ok) return
        }
        DesktopDialogs.showCreateOrEditMacroSetDialog(
            owner = this, existing = set, allMacros = emptyList(), allSets = emptyList()
        ) { name, desc, colorHex, _ ->
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
        DesktopDialogs.showMacroSetSecurityDialog(
            this, set, store.getMacrosInSet(set.id).size, store.effectiveNowMs()
        ) { updated ->
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
            this,
            "Macro Set '${set.name}' berisi $memberCount macro.",
            "Hapus Macro Set",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null, opts, opts[0]
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
                this, "Sandi Macro Set", "Macro berada di Set terkunci '${curSet.name}'. Masukkan sandi Set untuk memindahkannya:"
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
            this,
            "Ekspor Macro (.zip)",
            "Ekspor macro '${macro.name}' (${macro.countActions()} aksi + sub-macro + gambar terkait)."
        ) ?: return
        val safe = DesktopStore.sanitizeFileName(macro.name).ifBlank { "macro" }
        val fileName = if (singleUse) "${safe}_1x.zip" else "$safe.zip"
        val chooser = JFileChooser().apply {
            selectedFile = File(fileName)
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
            this,
            "Ekspor Macro Set (.zip)",
            "Ekspor Set '${set.name}' berisi ${members.size} macro + semua gambar, tautan & proteksi kunci."
        ) ?: return
        val safe = DesktopStore.sanitizeFileName(set.name).ifBlank { "macro_set" }
        val fileName = if (singleUse) "${safe}_set_1x.zip" else "${safe}_set.zip"
        val chooser = JFileChooser().apply {
            selectedFile = File(fileName)
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
                if (s.singleUseImport) append(" • 🔒 1× impor")
                if (s.imagesImported > 0) append(", ${s.imagesImported} gambar")
                append(".")
            }
            JOptionPane.showMessageDialog(this, msg)
        }.onFailure { err ->
            JOptionPane.showMessageDialog(this, "Gagal impor: ${err.message}", "Error", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun captureForOverlay(): Pair<java.awt.image.BufferedImage, java.awt.Rectangle>? {
        val prevVisible = isVisible
        val prevMini = miniBar.isVisible
        if (store.settings.value.captureMode != DesktopCaptureMode.ADB_BACKGROUND) {
            isVisible = false
            miniBar.isVisible = false
            Thread.sleep(160L)
        }
        val cap = coordinator.captureAndInput.captureBufferedImage()
        if (cap == null) {
            isVisible = prevVisible
            miniBar.isVisible = prevMini
            JOptionPane.showMessageDialog(this, "Gagal menangkap layar. Pastikan perangkat/ADB atau layar aktif.")
        }
        return cap
    }

    private fun captureNewTemplateFromScreen() {
        val cap = captureForOverlay() ?: return
        DesktopOverlays.openRegionOrCropOverlay(this, cap.first, cap.second, "Potong Gambar Target") { _, cropImg ->
            isVisible = true
            toFront()
            val name = JOptionPane.showInputDialog(this, "Simpan gambar target dengan nama:", "target_${System.currentTimeMillis() % 1000}")
            if (!name.isNullOrBlank()) {
                val asset = store.saveImage(name, cropImg)
                ExecutionLogBus.info("Gambar '${asset.name}' (${asset.width}×${asset.height}) disimpan ke pustaka.")
            }
        }
        isVisible = true
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
                store.saveImage(f.nameWithoutExtension, img)
                added++
            }
        }
        if (added > 0) JOptionPane.showMessageDialog(this, "$added gambar ditambahkan ke Pustaka.")
    }
}
