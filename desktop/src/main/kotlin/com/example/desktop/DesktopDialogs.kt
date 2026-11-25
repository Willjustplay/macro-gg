package com.example.desktop

import com.example.data.Action
import com.example.data.ClickAction
import com.example.data.ColorAction
import com.example.data.ExitAppAction
import com.example.data.ExitAppMode
import com.example.data.ExpiryUnit
import com.example.data.FinishMacroAction
import com.example.data.FinishMacroMode
import com.example.data.ImageAction
import com.example.data.JumpAction
import com.example.data.JumpMode
import com.example.data.LaunchAppAction
import com.example.data.LongPressAction
import com.example.data.LoopAction
import com.example.data.Macro
import com.example.data.MacroSecurity
import com.example.data.MacroSet
import com.example.data.NotFoundMode
import com.example.data.RecordAction
import com.example.data.Region
import com.example.data.ResolutionScaleMode
import com.example.data.RestartAction
import com.example.data.RunMacroAction
import com.example.data.StopAction
import com.example.data.SwipeAction
import com.example.data.TextAction
import com.example.data.TextCompareMode
import com.example.data.TrackInteractionMode
import com.example.data.TrackObjectAction
import com.example.data.WaitAction
import com.example.data.ZoomAction
import com.example.data.kind
import com.example.data.summary
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.border.EmptyBorder

/**
 * Clean native desktop dialogs for Wjust macro PC covering:
 * - Password unlock, Macro Security (Lock/Expiry/Passive), Macro Set Create/Edit/Members/Lock/Move/Delete
 * - Portable .zip Export with optional 1x Import token
 * - Passwordless Auto-Scale configuration
 * - Full Action Editor for all 18 Action types (with live Screen Crop, Region Drag, and Magnifier Target Pick!)
 */
object DesktopDialogs {

    fun askPassword(
        owner: JFrame?,
        title: String,
        subtitle: String,
        verify: (String) -> Boolean
    ): Boolean {
        val field = JPasswordField(20)
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("<html><body style='width:320px'>$subtitle</body></html>"))
            add(Box.createVerticalStrut(10))
            add(field)
        }
        while (true) {
            val res = JOptionPane.showConfirmDialog(
                owner, panel, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
            )
            if (res != JOptionPane.OK_OPTION) return false
            val candidate = String(field.password)
            if (verify(candidate)) return true
            JOptionPane.showMessageDialog(owner, "Sandi salah. Silakan coba lagi.", "Sandi Salah", JOptionPane.ERROR_MESSAGE)
            field.text = ""
        }
    }

    /** Returns `true` for 1x single-use export, `false` for normal export, or `null` if canceled. */
    fun askExportOptions(owner: JFrame?, title: String, summaryText: String): Boolean? {
        val oneTimeChk = JCheckBox("🔒 Hanya Bisa Impor Sekali (1× Pakai / Token Unik)")
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("<html><body style='width:360px'>$summaryText</body></html>"))
            add(Box.createVerticalStrut(10))
            add(oneTimeChk)
            add(Box.createVerticalStrut(4))
            add(DesktopTheme.mutedLabel("File .zip kompatibel 100% dengan Wjust macro Android & PC.", 11.5f))
        }
        val res = JOptionPane.showConfirmDialog(owner, panel, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        return if (res == JOptionPane.OK_OPTION) oneTimeChk.isSelected else null
    }

    fun showCreateOrEditMacroSetDialog(
        owner: JFrame?,
        existing: MacroSet?,
        allMacros: List<Macro>,
        allSets: List<MacroSet>,
        preselectedIds: Set<String> = emptySet(),
        onSave: (name: String, desc: String, colorHex: String, memberIds: Set<String>) -> Unit
    ) {
        val dlg = JDialog(owner, if (existing == null) "Buat Macro Set Baru" else "Edit Macro Set", true)
        val root = JPanel(BorderLayout(12, 12)).apply {
            background = DesktopTheme.BG_SURFACE
            border = EmptyBorder(16, 16, 16, 16)
        }
        val form = JPanel(GridBagLayout()).apply { isOpaque = false }
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        val nameField = JTextField(existing?.name ?: "", 24)
        val descField = JTextField(existing?.description ?: "", 24)
        var chosenColor = existing?.colorHex?.ifBlank { "#22D3EE" } ?: "#22D3EE"

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0; form.add(JLabel("Nama Macro Set:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; form.add(nameField, gbc)
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0; form.add(JLabel("Catatan:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; form.add(descField, gbc)

        val colorRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        val colorButtons = ArrayList<Pair<String, JButton>>()
        fun refreshColorSelection() {
            for ((hex, btn) in colorButtons) {
                btn.text = if (hex.equals(chosenColor, ignoreCase = true)) "✓" else " "
            }
        }
        for (hex in DesktopTheme.SET_PALETTE) {
            val btn = JButton(" ").apply {
                background = DesktopTheme.parseHexColor(hex)
                foreground = Color.BLACK
                font = DesktopTheme.uiFont(12f, Font.BOLD)
                preferredSize = Dimension(30, 26)
                addActionListener { chosenColor = hex; refreshColorSelection() }
            }
            colorButtons += hex to btn
            colorRow.add(btn)
        }
        refreshColorSelection()
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0; form.add(JLabel("Warna Label:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; form.add(colorRow, gbc)

        val memberChecks = LinkedHashMap<String, JCheckBox>()
        if (existing == null && allMacros.isNotEmpty()) {
            val setById = allSets.associateBy { it.id }
            val eligible = allMacros.filter { m -> setById[m.setId]?.isLocked != true }
            if (eligible.isNotEmpty()) {
                val listPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    background = DesktopTheme.BG_CARD
                    border = EmptyBorder(6, 6, 6, 6)
                }
                for (m in eligible) {
                    val curSet = setById[m.setId]?.name
                    val suffix = if (!curSet.isNullOrBlank()) "  [di Set: $curSet]" else ""
                    val cb = JCheckBox((if (m.isPassive) "🔗 " else "") + m.name + suffix, m.id in preselectedIds).apply {
                        isOpaque = false
                    }
                    memberChecks[m.id] = cb
                    listPanel.add(cb)
                }
                val scroll = JScrollPane(listPanel).apply { preferredSize = Dimension(360, 140) }
                gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2; gbc.weightx = 1.0
                form.add(JLabel("Pilih Macro untuk dimasukkan (opsional):"), gbc)
                gbc.gridy = 4; form.add(scroll, gbc)
            }
        }

        val btnBar = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { isOpaque = false }
        btnBar.add(DesktopTheme.subtleButton("Batal") { dlg.dispose() })
        btnBar.add(DesktopTheme.primaryButton(if (existing == null) "Buat Set" else "Simpan") {
            val n = nameField.text.trim()
            if (n.isBlank()) {
                JOptionPane.showMessageDialog(dlg, "Nama Macro Set wajib diisi.")
                return@primaryButton
            }
            val selected = memberChecks.filterValues { it.isSelected }.keys
            dlg.dispose()
            onSave(n, descField.text.trim(), chosenColor, selected)
        })

        root.add(form, BorderLayout.CENTER)
        root.add(btnBar, BorderLayout.SOUTH)
        dlg.contentPane = root
        dlg.pack()
        dlg.setLocationRelativeTo(owner)
        dlg.isVisible = true
    }

    fun showManageMacroSetMembersDialog(
        owner: JFrame?,
        set: MacroSet,
        allMacros: List<Macro>,
        allSets: List<MacroSet>,
        onSave: (Set<String>) -> Unit
    ) {
        val dlg = JDialog(owner, "Kelola Isi Set: ${set.name}", true)
        val setById = allSets.associateBy { it.id }
        val root = JPanel(BorderLayout(10, 10)).apply {
            background = DesktopTheme.BG_SURFACE
            border = EmptyBorder(14, 16, 14, 16)
        }
        val topMsg = if (MacroSecurity.hasAnySetProtection(set)) {
            "🔒 Set ini terkunci/bertenggat — macro baru yang dimasukkan otomatis ikut dikunci & disegel."
        } else {
            "Centang macro yang ingin dikelompokkan ke dalam '${set.name}':"
        }
        root.add(DesktopTheme.mutedLabel(topMsg, 12f), BorderLayout.NORTH)

        val listPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = DesktopTheme.BG_CARD
            border = EmptyBorder(8, 8, 8, 8)
        }
        val checks = LinkedHashMap<String, JCheckBox>()
        for (m in allMacros) {
            val otherSet = if (m.setId.isNotBlank() && m.setId != set.id) setById[m.setId] else null
            val lockedElsewhere = otherSet != null && otherSet.isLocked
            val label = buildString {
                if (m.isLocked) append("🔒 ")
                if (m.isPassive) append(if (m.passiveEnabled) "🔗 " else "⛔ ")
                append(m.name)
                if (otherSet != null) append(if (lockedElsewhere) "  [🔒 terkunci di Set '${otherSet.name}']" else "  [di Set '${otherSet.name}']")
            }
            val cb = JCheckBox(label, m.setId == set.id).apply {
                isOpaque = false
                isEnabled = !lockedElsewhere
            }
            checks[m.id] = cb
            listPanel.add(cb)
        }
        root.add(JScrollPane(listPanel).apply { preferredSize = Dimension(420, 260) }, BorderLayout.CENTER)

        val btnBar = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { isOpaque = false }
        btnBar.add(DesktopTheme.subtleButton("Batal") { dlg.dispose() })
        btnBar.add(DesktopTheme.primaryButton("Simpan Isi Set") {
            val selected = checks.filterValues { it.isSelected }.keys
            dlg.dispose()
            onSave(selected)
        })
        root.add(btnBar, BorderLayout.SOUTH)

        dlg.contentPane = root
        dlg.pack()
        dlg.setLocationRelativeTo(owner)
        dlg.isVisible = true
    }

    fun showMacroSecurityDialog(
        owner: JFrame?,
        macro: Macro,
        nowMs: Long,
        onSave: (Macro) -> Unit
    ) {
        val dlg = JDialog(owner, "Kunci, Tenggat & Mode Pasif — ${macro.name}", true)
        val hasPass = MacroSecurity.hasPassword(macro)
        val hasActiveExp = macro.expiresAtEpochMs > 0L && !MacroSecurity.isExpired(macro, nowMs)

        val lockChk = JCheckBox("🔒 Kunci Macro dengan Sandi (cegah edit/lihat aksi/duplikat)", macro.isLocked)
        val passiveChk = JCheckBox("🔗 Mode Pasif (hanya bisa dipanggil macro aktif via Hubungkan Macro)", macro.isPassive)
        val passiveOnChk = JCheckBox("Status Pasif ON (dapat ditarik macro aktif)", macro.passiveEnabled)
        val expChk = JCheckBox("⏳ Aktifkan Tenggat Waktu Penggunaan", macro.expiresAtEpochMs > 0L)
        val expModeBox = JComboBox(
            if (hasActiveExp) arrayOf("Pertahankan", "+ Tambah Waktu", "Atur Ulang") else arrayOf("Atur Ulang")
        )
        val expAmountSpin = JSpinner(SpinnerNumberModel(macro.expiryDurationAmount.coerceAtLeast(7), 1, 3650, 1))
        val expUnitBox = JComboBox(ExpiryUnit.entries.toTypedArray()).apply { selectedItem = macro.expiryDurationUnit }
        val passField = JPasswordField(18)
        val confirmField = JPasswordField(18)

        val root = JPanel(BorderLayout(10, 10)).apply {
            background = DesktopTheme.BG_SURFACE
            border = EmptyBorder(16, 16, 16, 16)
        }
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(lockChk); add(Box.createVerticalStrut(6))
            add(passiveChk); add(passiveOnChk); add(Box.createVerticalStrut(6))
            add(expChk)
            val expRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
                isOpaque = false
                add(expModeBox); add(expAmountSpin); add(expUnitBox)
            }
            add(expRow)
            if (hasActiveExp) {
                add(DesktopTheme.mutedLabel("Tenggat saat ini: ${MacroSecurity.formatDeadlineDate(macro.expiresAtEpochMs)}", 11.5f))
            }
            add(Box.createVerticalStrut(8))
            add(JLabel(if (hasPass) "Sandi Baru (kosongkan jika tidak mengubah):" else "Sandi Keamanan (min. 4 karakter):"))
            add(passField)
            add(JLabel("Konfirmasi Sandi:"))
            add(confirmField)
        }

        val btnBar = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { isOpaque = false }
        if (MacroSecurity.hasAnyProtection(macro)) {
            btnBar.add(DesktopTheme.subtleButton("Matikan Semua Proteksi", DesktopTheme.ROSE) {
                lockChk.isSelected = false; passiveChk.isSelected = false; expChk.isSelected = false
            })
        }
        btnBar.add(DesktopTheme.subtleButton("Batal") { dlg.dispose() })
        btnBar.add(DesktopTheme.primaryButton("Simpan Proteksi") {
            val anyProt = lockChk.isSelected || passiveChk.isSelected || expChk.isSelected
            val p1 = String(passField.password)
            val p2 = String(confirmField.password)
            if (anyProt && (!hasPass || p1.isNotEmpty())) {
                if (p1.length < 4) { JOptionPane.showMessageDialog(dlg, "Sandi minimal 4 karakter."); return@primaryButton }
                if (p1 != p2) { JOptionPane.showMessageDialog(dlg, "Konfirmasi sandi tidak cocok."); return@primaryButton }
            }
            val amt = (expAmountSpin.value as Number).toInt()
            val unit = expUnitBox.selectedItem as ExpiryUnit
            val modeStr = expModeBox.selectedItem?.toString().orEmpty()
            val expMs = when {
                !expChk.isSelected -> 0L
                modeStr == "Pertahankan" && hasActiveExp -> macro.expiresAtEpochMs
                modeStr == "+ Tambah Waktu" && hasActiveExp -> MacroSecurity.computeExpiryEpochMs(macro.expiresAtEpochMs, amt, unit)
                else -> MacroSecurity.computeExpiryEpochMs(nowMs, amt, unit)
            }
            val expLabel = when {
                !expChk.isSelected -> ""
                modeStr == "Pertahankan" -> macro.expiryLabel.ifBlank { MacroSecurity.formatDeadlineDate(expMs) }
                modeStr == "+ Tambah Waktu" -> "+${MacroSecurity.formatDurationLabel(amt, unit)}"
                else -> MacroSecurity.formatDurationLabel(amt, unit)
            }
            val updated = MacroSecurity.applySecurity(
                macro = macro,
                isLocked = lockChk.isSelected,
                isPassive = passiveChk.isSelected,
                expiresAtEpochMs = expMs,
                expiryLabel = expLabel,
                newPassword = p1.takeIf { it.isNotBlank() },
                passiveEnabled = passiveOnChk.isSelected,
                expiryDurationAmount = if (!expChk.isSelected) 0 else if (modeStr == "Pertahankan") macro.expiryDurationAmount else amt,
                expiryDurationUnit = if (!expChk.isSelected) ExpiryUnit.DAYS else if (modeStr == "Pertahankan") macro.expiryDurationUnit else unit
            )
            dlg.dispose()
            onSave(updated)
        })

        root.add(body, BorderLayout.CENTER)
        root.add(btnBar, BorderLayout.SOUTH)
        dlg.contentPane = root
        dlg.pack()
        dlg.setLocationRelativeTo(owner)
        dlg.isVisible = true
    }

    fun showMacroSetSecurityDialog(
        owner: JFrame?,
        set: MacroSet,
        memberCount: Int,
        nowMs: Long,
        onSave: (MacroSet) -> Unit
    ) {
        val dlg = JDialog(owner, "Kunci & Tenggat Macro Set — ${set.name}", true)
        val hasPass = MacroSecurity.hasSetPassword(set)
        val hasActiveExp = set.expiresAtEpochMs > 0L && !MacroSecurity.isSetExpired(set, nowMs)

        val lockChk = JCheckBox("🔒 Kunci Seluruh Macro Set ($memberCount macro ikut dikunci & disegel)", set.isLocked)
        val expChk = JCheckBox("⏳ Aktifkan Tenggat Waktu Set", set.expiresAtEpochMs > 0L)
        val expModeBox = JComboBox(
            if (hasActiveExp) arrayOf("Pertahankan", "+ Tambah Waktu", "Atur Ulang") else arrayOf("Atur Ulang")
        )
        val expAmountSpin = JSpinner(SpinnerNumberModel(set.expiryDurationAmount.coerceAtLeast(7), 1, 3650, 1))
        val expUnitBox = JComboBox(ExpiryUnit.entries.toTypedArray()).apply { selectedItem = set.expiryDurationUnit }
        val passField = JPasswordField(18)
        val confirmField = JPasswordField(18)

        val root = JPanel(BorderLayout(10, 10)).apply {
            background = DesktopTheme.BG_SURFACE
            border = EmptyBorder(16, 16, 16, 16)
        }
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(lockChk)
            add(DesktopTheme.mutedLabel("Auto-Scale & Pasif ON/OFF tetap bebas diatur tanpa sandi.", 11.5f))
            add(Box.createVerticalStrut(8))
            add(expChk)
            val expRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
                isOpaque = false
                add(expModeBox); add(expAmountSpin); add(expUnitBox)
            }
            add(expRow)
            if (hasActiveExp) {
                add(DesktopTheme.mutedLabel("Tenggat saat ini: ${MacroSecurity.formatDeadlineDate(set.expiresAtEpochMs)}", 11.5f))
            }
            add(Box.createVerticalStrut(8))
            add(JLabel(if (hasPass) "Sandi Set Baru (kosongkan jika tetap):" else "Sandi Macro Set (min. 4 karakter):"))
            add(passField)
            add(JLabel("Konfirmasi Sandi:"))
            add(confirmField)
        }

        val btnBar = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { isOpaque = false }
        if (MacroSecurity.hasAnySetProtection(set)) {
            btnBar.add(DesktopTheme.subtleButton("Matikan Semua Proteksi Set", DesktopTheme.ROSE) {
                lockChk.isSelected = false; expChk.isSelected = false
            })
        }
        btnBar.add(DesktopTheme.subtleButton("Batal") { dlg.dispose() })
        btnBar.add(DesktopTheme.primaryButton("Simpan Proteksi Set") {
            val anyProt = lockChk.isSelected || expChk.isSelected
            val p1 = String(passField.password)
            val p2 = String(confirmField.password)
            if (anyProt && (!hasPass || p1.isNotEmpty())) {
                if (p1.length < 4) { JOptionPane.showMessageDialog(dlg, "Sandi minimal 4 karakter."); return@primaryButton }
                if (p1 != p2) { JOptionPane.showMessageDialog(dlg, "Konfirmasi sandi tidak cocok."); return@primaryButton }
            }
            val amt = (expAmountSpin.value as Number).toInt()
            val unit = expUnitBox.selectedItem as ExpiryUnit
            val modeStr = expModeBox.selectedItem?.toString().orEmpty()
            val expMs = when {
                !expChk.isSelected -> 0L
                modeStr == "Pertahankan" && hasActiveExp -> set.expiresAtEpochMs
                modeStr == "+ Tambah Waktu" && hasActiveExp -> MacroSecurity.computeExpiryEpochMs(set.expiresAtEpochMs, amt, unit)
                else -> MacroSecurity.computeExpiryEpochMs(nowMs, amt, unit)
            }
            val expLabel = when {
                !expChk.isSelected -> ""
                modeStr == "Pertahankan" -> set.expiryLabel.ifBlank { MacroSecurity.formatDeadlineDate(expMs) }
                modeStr == "+ Tambah Waktu" -> "+${MacroSecurity.formatDurationLabel(amt, unit)}"
                else -> MacroSecurity.formatDurationLabel(amt, unit)
            }
            val updated = MacroSecurity.applySetSecurity(
                set = set,
                isLocked = lockChk.isSelected,
                expiresAtEpochMs = expMs,
                expiryLabel = expLabel,
                newPassword = p1.takeIf { it.isNotBlank() },
                expiryDurationAmount = if (!expChk.isSelected) 0 else if (modeStr == "Pertahankan") set.expiryDurationAmount else amt,
                expiryDurationUnit = if (!expChk.isSelected) ExpiryUnit.DAYS else if (modeStr == "Pertahankan") set.expiryDurationUnit else unit
            )
            dlg.dispose()
            onSave(updated)
        })

        root.add(body, BorderLayout.CENTER)
        root.add(btnBar, BorderLayout.SOUTH)
        dlg.contentPane = root
        dlg.pack()
        dlg.setLocationRelativeTo(owner)
        dlg.isVisible = true
    }

    fun showAutoScaleDialog(
        owner: JFrame?,
        macro: Macro,
        curW: Int,
        curH: Int,
        onSave: (enabled: Boolean, srcW: Int, srcH: Int, mode: ResolutionScaleMode) -> Unit
    ) {
        val enabledChk = JCheckBox("📐 Aktifkan Auto-Scale Resolusi Layar (Tanpa Sandi)", macro.autoScaleResolution)
        val wSpin = JSpinner(SpinnerNumberModel(macro.sourceWidth.coerceIn(0, 10000), 0, 10000, 10))
        val hSpin = JSpinner(SpinnerNumberModel(macro.sourceHeight.coerceIn(0, 10000), 0, 10000, 10))
        val modeBox = JComboBox(ResolutionScaleMode.entries.toTypedArray()).apply { selectedItem = macro.scaleMode }
        val useNowBtn = DesktopTheme.subtleButton("Isi Layar Saat Ini (${curW}×${curH})") {
            wSpin.value = curW; hSpin.value = curH
        }
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(enabledChk)
            add(Box.createVerticalStrut(6))
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(JLabel("Resolusi Asal:")); add(wSpin); add(JLabel("×")); add(hSpin); add(useNowBtn)
            })
            add(Box.createVerticalStrut(6))
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { add(JLabel("Mode Proyeksi:")); add(modeBox) })
            add(DesktopTheme.mutedLabel("Macro Android (misal 1080×2400) otomatis menyesuaikan ke layar PC/Emulator.", 11.5f))
        }
        val res = JOptionPane.showConfirmDialog(
            owner, panel, "Auto-Scale — ${macro.name}", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )
        if (res == JOptionPane.OK_OPTION) {
            onSave(
                enabledChk.isSelected,
                (wSpin.value as Number).toInt(),
                (hSpin.value as Number).toInt(),
                modeBox.selectedItem as ResolutionScaleMode
            )
        }
    }

    fun showMoveMacroToSetDialog(
        owner: JFrame?,
        macro: Macro,
        sets: List<MacroSet>,
        onMove: (targetSetId: String) -> Unit
    ) {
        val group = ButtonGroup()
        val radios = ArrayList<Pair<String, JRadioButton>>()
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("Pilih Macro Set tujuan untuk '${macro.name}':"))
            add(Box.createVerticalStrut(8))
        }
        val noneRb = JRadioButton("Tanpa Set (Umum)", macro.setId.isBlank() || sets.none { it.id == macro.setId })
        group.add(noneRb); radios += "" to noneRb; panel.add(noneRb)
        for (s in sets) {
            val rb = JRadioButton((if (s.isLocked) "🔒 " else "📁 ") + s.name, macro.setId == s.id)
            group.add(rb); radios += s.id to rb; panel.add(rb)
        }
        val res = JOptionPane.showConfirmDialog(owner, panel, "Pindah ke Macro Set", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (res == JOptionPane.OK_OPTION) {
            radios.firstOrNull { it.second.isSelected }?.let { onMove(it.first) }
        }
    }

    /**
     * Full PC Action Editor supporting all 18 Action types with integrated live Screen Picker & Cropper!
     */
    fun showActionEditorDialog(
        owner: JFrame?,
        action: Action,
        currentMacro: Macro,
        allMacros: List<Macro>,
        images: List<DesktopImageAsset>,
        onPickPointOnScreen: (dual: Boolean, hint: String, callback: (x1: Int, y1: Int, x2: Int, y2: Int, hex: String) -> Unit) -> Unit,
        onPickRegionOrCropOnScreen: (isCrop: Boolean, hint: String, callback: (region: Region, savedImageName: String?) -> Unit) -> Unit,
        onRecordMouseOnScreen: (callback: (com.example.data.RecordedGesture) -> Unit) -> Unit,
        onSave: (Action) -> Unit
    ) {
        val dlg = JDialog(owner, "Edit Aksi: ${action.kind.label}", true)
        val root = JPanel(BorderLayout(10, 10)).apply {
            background = DesktopTheme.BG_SURFACE
            border = EmptyBorder(14, 16, 14, 16)
        }

        val form = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        val enabledChk = JCheckBox("Aksi Aktif", action.enabled).apply { isOpaque = false }
        val delaySpin = JSpinner(SpinnerNumberModel(action.delayAfterMs.toInt().coerceIn(0, 600_000), 0, 600_000, 50))
        val chanceSpin = JSpinner(SpinnerNumberModel(action.chancePercent.coerceIn(0, 100), 0, 100, 5))

        fun row(vararg comps: java.awt.Component): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 3)).apply {
            isOpaque = false
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            for (c in comps) add(c)
        }

        val imgNames = (listOf("") + images.map { it.name }).toTypedArray()
        var buildUpdated: () -> Action = { action }

        when (action) {
            is ClickAction -> {
                val xS = JSpinner(SpinnerNumberModel(action.x, 0, 10000, 1))
                val yS = JSpinner(SpinnerNumberModel(action.y, 0, 10000, 1))
                val durS = JSpinner(SpinnerNumberModel(action.durationMs.toInt().coerceIn(10, 10000), 10, 10000, 10))
                val repS = JSpinner(SpinnerNumberModel(action.repeat.coerceIn(1, 50), 1, 50, 1))
                form.add(row(JLabel("X:"), xS, JLabel("Y:"), yS, DesktopTheme.subtleButton("🎯 Pilih di Layar") {
                    dlg.isVisible = false
                    onPickPointOnScreen(false, "Pilih Titik Klik") { x1, y1, _, _, _ ->
                        xS.value = x1; yS.value = y1
                        dlg.isVisible = true
                    }
                }))
                form.add(row(JLabel("Durasi (ms):"), durS, JLabel("Ulangi klik:"), repS))
                buildUpdated = {
                    action.copy(
                        x = (xS.value as Number).toInt(),
                        y = (yS.value as Number).toInt(),
                        durationMs = (durS.value as Number).toLong(),
                        repeat = (repS.value as Number).toInt(),
                        enabled = enabledChk.isSelected,
                        delayAfterMs = (delaySpin.value as Number).toLong(),
                        chancePercent = (chanceSpin.value as Number).toInt()
                    )
                }
            }

            is LongPressAction -> {
                val xS = JSpinner(SpinnerNumberModel(action.x, 0, 10000, 1))
                val yS = JSpinner(SpinnerNumberModel(action.y, 0, 10000, 1))
                val durS = JSpinner(SpinnerNumberModel(action.durationMs.toInt().coerceIn(100, 30000), 100, 30000, 100))
                form.add(row(JLabel("X:"), xS, JLabel("Y:"), yS, DesktopTheme.subtleButton("🎯 Pilih di Layar") {
                    dlg.isVisible = false
                    onPickPointOnScreen(false, "Pilih Titik Tahan") { x1, y1, _, _, _ ->
                        xS.value = x1; yS.value = y1; dlg.isVisible = true
                    }
                }))
                form.add(row(JLabel("Durasi Tahan (ms):"), durS))
                buildUpdated = {
                    action.copy(
                        x = (xS.value as Number).toInt(), y = (yS.value as Number).toInt(),
                        durationMs = (durS.value as Number).toLong(),
                        enabled = enabledChk.isSelected, delayAfterMs = (delaySpin.value as Number).toLong(),
                        chancePercent = (chanceSpin.value as Number).toInt()
                    )
                }
            }

            is SwipeAction -> {
                val x1S = JSpinner(SpinnerNumberModel(action.x1, 0, 10000, 1))
                val y1S = JSpinner(SpinnerNumberModel(action.y1, 0, 10000, 1))
                val x2S = JSpinner(SpinnerNumberModel(action.x2, 0, 10000, 1))
                val y2S = JSpinner(SpinnerNumberModel(action.y2, 0, 10000, 1))
                val durS = JSpinner(SpinnerNumberModel(action.durationMs.toInt().coerceIn(40, 10000), 40, 10000, 20))
                form.add(row(JLabel("Dari A:"), x1S, y1S, JLabel("Ke B:"), x2S, y2S))
                form.add(row(JLabel("Durasi (ms):"), durS, DesktopTheme.subtleButton("🎯 Pilih A → B di Layar") {
                    dlg.isVisible = false
                    onPickPointOnScreen(true, "Pilih Arah Swipe (A → B)") { ax, ay, bx, by, _ ->
                        x1S.value = ax; y1S.value = ay; x2S.value = bx; y2S.value = by; dlg.isVisible = true
                    }
                }))
                buildUpdated = {
                    action.copy(
                        x1 = (x1S.value as Number).toInt(), y1 = (y1S.value as Number).toInt(),
                        x2 = (x2S.value as Number).toInt(), y2 = (y2S.value as Number).toInt(),
                        durationMs = (durS.value as Number).toLong(),
                        enabled = enabledChk.isSelected, delayAfterMs = (delaySpin.value as Number).toLong(),
                        chancePercent = (chanceSpin.value as Number).toInt()
                    )
                }
            }

            is ZoomAction -> {
                val cxS = JSpinner(SpinnerNumberModel(action.cx, 0, 10000, 1))
                val cyS = JSpinner(SpinnerNumberModel(action.cy, 0, 10000, 1))
                val inChk = JCheckBox("Perbesar (Zoom In; matikan = Perkecil/Zoom Out)", action.zoomIn).apply { isOpaque = false }
                form.add(row(JLabel("Pusat X:"), cxS, JLabel("Y:"), cyS, DesktopTheme.subtleButton("🎯 Pilih Pusat") {
                    dlg.isVisible = false
                    onPickPointOnScreen(false, "Pilih Pusat Zoom") { x1, y1, _, _, _ ->
                        cxS.value = x1; cyS.value = y1; dlg.isVisible = true
                    }
                }))
                form.add(row(inChk))
                buildUpdated = {
                    action.copy(
                        cx = (cxS.value as Number).toInt(), cy = (cyS.value as Number).toInt(),
                        zoomIn = inChk.isSelected,
                        enabled = enabledChk.isSelected, delayAfterMs = (delaySpin.value as Number).toLong(),
                        chancePercent = (chanceSpin.value as Number).toInt()
                    )
                }
            }

            is WaitAction -> {
                val dS = JSpinner(SpinnerNumberModel(action.durationMs.toInt().coerceIn(0, 600_000), 0, 600_000, 100))
                val rS = JSpinner(SpinnerNumberModel(action.randomMs.toInt().coerceIn(0, 60_000), 0, 60_000, 50))
                form.add(row(JLabel("Tunggu (ms):"), dS, JLabel("+ Acak (ms):"), rS))
                buildUpdated = {
                    action.copy(
                        durationMs = (dS.value as Number).toLong(), randomMs = (rS.value as Number).toLong(),
                        enabled = enabledChk.isSelected, delayAfterMs = (delaySpin.value as Number).toLong(),
                        chancePercent = (chanceSpin.value as Number).toInt()
                    )
                }
            }

            is ImageAction -> {
                val imgBox = JComboBox(imgNames).apply { isEditable = true; selectedItem = action.imageName }
                val thrS = JSpinner(SpinnerNumberModel((action.threshold * 100).toInt().coerceIn(45, 99), 45, 99, 2))
                val timeoutS = JSpinner(SpinnerNumberModel(action.timeoutMs.toInt().coerceIn(0, 120_000), 0, 120_000, 250))
                val clickChk = JCheckBox("Klik otomatis saat gambar ditemukan", action.clickOnFound).apply { isOpaque = false }
                val foundBox = JComboBox(NotFoundMode.entries.toTypedArray()).apply { selectedItem = action.foundMode }
                val notFoundBox = JComboBox(NotFoundMode.entries.toTypedArray()).apply { selectedItem = action.notFound }
                var curRegion = action.region
                val regionLbl = JLabel(curRegion?.let { "(${it.left},${it.top})-(${it.right},${it.bottom})" } ?: "Seluruh Layar")

                form.add(row(JLabel("Gambar Target:"), imgBox, DesktopTheme.subtleButton("✂ Potong Layar") {
                    dlg.isVisible = false
                    onPickRegionOrCropOnScreen(true, "Potong Gambar Target") { _, savedName ->
                        if (!savedName.isNullOrBlank()) imgBox.selectedItem = savedName
                        dlg.isVisible = true
                    }
                }))
                form.add(row(JLabel("Kemiripan (%):"), thrS, JLabel("Timeout (ms):"), timeoutS))
                form.add(row(clickChk))
                form.add(row(JLabel("Area:"), regionLbl, DesktopTheme.subtleButton("📐 Pilih Area") {
                    dlg.isVisible = false
                    onPickRegionOrCropOnScreen(false, "Pilih Area Pencarian") { reg, _ ->
                        curRegion = reg; regionLbl.text = "(${reg.left},${reg.top})-(${reg.right},${reg.bottom})"; dlg.isVisible = true
                    }
                }, DesktopTheme.subtleButton("Reset Area") { curRegion = null; regionLbl.text = "Seluruh Layar" }))
                form.add(row(JLabel("Jika Ketemu:"), foundBox, JLabel("Jika Tidak:"), notFoundBox))
                buildUpdated = {
                    action.copy(
                        imageName = imgBox.selectedItem?.toString().orEmpty().trim(),
                        threshold = ((thrS.value as Number).toInt() / 100f).coerceIn(0.45f, 0.99f),
                        timeoutMs = (timeoutS.value as Number).toLong(),
                        clickOnFound = clickChk.isSelected,
                        region = curRegion,
                        foundMode = foundBox.selectedItem as NotFoundMode,
                        notFound = notFoundBox.selectedItem as NotFoundMode,
                        enabled = enabledChk.isSelected, delayAfterMs = (delaySpin.value as Number).toLong(),
                        chancePercent = (chanceSpin.value as Number).toInt()
                    )
                }
            }

            is TrackObjectAction -> {
                val anyMotionChk = JCheckBox("🎯 Lacak Semua Pasukan Bergerak (Bebas Warna / Tanpa Gambar)", action.trackAnyMotion).apply {
                    isOpaque = false
                    font = DesktopTheme.uiFont(12.5f, Font.BOLD)
                    foreground = DesktopTheme.CYAN
                }
                val imgBox = JComboBox(imgNames).apply { isEditable = true; selectedItem = action.imageName }
                val modeBox = JComboBox(TrackInteractionMode.entries.toTypedArray()).apply { selectedItem = action.interactionMode }
                val thrS = JSpinner(SpinnerNumberModel((action.threshold * 100).toInt().coerceIn(45, 99), 45, 99, 2))
                val leadS = JSpinner(SpinnerNumberModel(action.leadPredictionMs.toInt().coerceIn(0, 600), 0, 600, 20))
                val maxDurS = JSpinner(SpinnerNumberModel(action.maxTrackDurationMs.toInt().coerceIn(500, 120_000), 500, 120_000, 500))
                val colorChk = JCheckBox("Cocokkan Warna Pasukan (HSV)", action.useColorMatch).apply { isOpaque = false }
                val motionChk = JCheckBox("Prioritaskan Objek Bergerak (Abaikan Bangunan Statis)", action.prioritizeMotion).apply { isOpaque = false }
                var curRegion = action.region
                val regionLbl = JLabel(curRegion?.let { "(${it.left},${it.top})-(${it.right},${it.bottom})" } ?: "Seluruh Layar")
                val tplRow = row(JLabel("Gambar Objek:"), imgBox, DesktopTheme.subtleButton("✂ Potong Objek") {
                    dlg.isVisible = false
                    onPickRegionOrCropOnScreen(true, "Potong Gambar Pasukan/Objek") { _, savedName ->
                        if (!savedName.isNullOrBlank()) imgBox.selectedItem = savedName
                        dlg.isVisible = true
                    }
                })
                val optRow = row(JLabel("Kemiripan (%):"), thrS, colorChk, motionChk)
                fun syncVis() {
                    val hideTpl = anyMotionChk.isSelected
                    tplRow.isVisible = !hideTpl
                    optRow.isVisible = !hideTpl
                    dlg.pack()
                }
                anyMotionChk.addActionListener { syncVis() }

                form.add(row(anyMotionChk))
                form.add(tplRow)
                form.add(row(JLabel("Mode Interaksi:"), modeBox))
                form.add(optRow)
                form.add(row(JLabel("Prediksi Gerak/Lead (ms):"), leadS, JLabel("Durasi Kejar Maks (ms):"), maxDurS))
                form.add(row(JLabel("Area Medan:"), regionLbl, DesktopTheme.subtleButton("📐 Pilih Area") {
                    dlg.isVisible = false
                    onPickRegionOrCropOnScreen(false, "Pilih Area Medan Perang") { reg, _ ->
                        curRegion = reg; regionLbl.text = "(${reg.left},${reg.top})-(${reg.right},${reg.bottom})"; dlg.isVisible = true
                    }
                }, DesktopTheme.subtleButton("Reset Area") { curRegion = null; regionLbl.text = "Seluruh Layar" }))
                syncVis()

                buildUpdated = {
                    action.copy(
                        trackAnyMotion = anyMotionChk.isSelected,
                        imageName = imgBox.selectedItem?.toString().orEmpty().trim(),
                        interactionMode = modeBox.selectedItem as TrackInteractionMode,
                        threshold = ((thrS.value as Number).toInt() / 100f).coerceIn(0.45f, 0.99f),
                        useColorMatch = colorChk.isSelected,
                        prioritizeMotion = motionChk.isSelected,
                        leadPredictionMs = (leadS.value as Number).toLong(),
                        maxTrackDurationMs = (maxDurS.value as Number).toLong(),
                        region = curRegion,
                        enabled = enabledChk.isSelected, delayAfterMs = (delaySpin.value as Number).toLong(),
                        chancePercent = (chanceSpin.value as Number).toInt()
                    )
                }
            }

            is TextAction -> {
                val modeBox = JComboBox(TextCompareMode.entries.toTypedArray()).apply { selectedItem = action.compareMode }
                val textField = JTextField(action.query, 20)
                val numField = JTextField(action.targetNumber.toString(), 12)
                val clickChk = JCheckBox("Klik posisi teks saat cocok", action.clickOnFound).apply { isOpaque = false }
                var curRegion = action.region
                val regionLbl = JLabel(curRegion?.let { "(${it.left},${it.top})-(${it.right},${it.bottom})" } ?: "Seluruh Layar")
                form.add(row(JLabel("Mode Cek:"), modeBox))
                form.add(row(JLabel("Teks dicari:"), textField, JLabel("Nilai Angka:"), numField))
                form.add(row(clickChk, DesktopTheme.mutedLabel("(Auto 2×/3× HUD Game OCR aktif)", 11.5f)))
                form.add(row(JLabel("Area OCR:"), regionLbl, DesktopTheme.subtleButton("📐 Pilih Area") {
                    dlg.isVisible = false
                    onPickRegionOrCropOnScreen(false, "Pilih Area Teks / Angka") { reg, _ ->
                        curRegion = reg; regionLbl.text = "(${reg.left},${reg.top})-(${reg.right},${reg.bottom})"; dlg.isVisible = true
                    }
                }, DesktopTheme.subtleButton("Reset Area") { curRegion = null; regionLbl.text = "Seluruh Layar" }))
                buildUpdated = {
                    action.copy(
                        compareMode = modeBox.selectedItem as TextCompareMode,
                        query = textField.text.trim(),
                        targetNumber = numField.text.trim().replace(",", ".").toDoubleOrNull() ?: action.targetNumber,
                        clickOnFound = clickChk.isSelected,
                        region = curRegion,
                        enabled = enabledChk.isSelected, delayAfterMs = (delaySpin.value as Number).toLong(),
                        chancePercent = (chanceSpin.value as Number).toInt()
                    )
                }
            }

            is ColorAction -> {
                val xS = JSpinner(SpinnerNumberModel(action.x, 0, 10000, 1))
                val yS = JSpinner(SpinnerNumberModel(action.y, 0, 10000, 1))
                val hexF = JTextField(action.color, 9)
                val tolS = JSpinner(SpinnerNumberModel(action.tolerance.coerceIn(0, 120), 0, 120, 2))
                val clickChk = JCheckBox("Klik saat warna cocok", action.clickOnFound).apply { isOpaque = false }
                form.add(row(JLabel("X:"), xS, JLabel("Y:"), yS, JLabel("Hex:"), hexF, DesktopTheme.subtleButton("🎯 Ambil Warna di Layar") {
                    dlg.isVisible = false
                    onPickPointOnScreen(false, "Ambil Titik & Warna Piksel") { x1, y1, _, _, hex ->
                        xS.value = x1; yS.value = y1; hexF.text = hex; dlg.isVisible = true
                    }
                }))
                form.add(row(JLabel("Toleransi (0-120):"), tolS, clickChk))
                buildUpdated = {
                    action.copy(
                        x = (xS.value as Number).toInt(), y = (yS.value as Number).toInt(),
                        color = hexF.text.trim().ifBlank { "#FFFFFF" },
                        tolerance = (tolS.value as Number).toInt(),
                        clickOnFound = clickChk.isSelected,
                        enabled = enabledChk.isSelected, delayAfterMs = (delaySpin.value as Number).toLong(),
                        chancePercent = (chanceSpin.value as Number).toInt()
                    )
                }
            }

            is LoopAction -> {
                val cntS = JSpinner(SpinnerNumberModel(action.count.coerceIn(0, 10000), 0, 10000, 1))
                form.add(row(JLabel("Jumlah Ulangi (0 = Tak Terbatas):"), cntS))
                buildUpdated = {
                    action.copy(
                        count = (cntS.value as Number).toInt(),
                        enabled = enabledChk.isSelected, delayAfterMs = (delaySpin.value as Number).toLong(),
                        chancePercent = (chanceSpin.value as Number).toInt()
                    )
                }
            }

            is RunMacroAction -> {
                val others = allMacros.filter { it.id != currentMacro.id }
                val items = others.map { "${it.name}${if (it.isPassive) " [Pasif]" else ""}" }.toTypedArray()
                val box = JComboBox(items)
                val selIdx = others.indexOfFirst { it.id == action.targetMacroId }
                if (selIdx >= 0) box.selectedIndex = selIdx
                val retChk = JCheckBox("Kembali lanjut ke macro ini setelah selesai", action.returnAfter).apply { isOpaque = false }
                form.add(row(JLabel("Macro Tujuan:"), box))
                form.add(row(retChk))
                buildUpdated = {
                    val chosen = others.getOrNull(box.selectedIndex)
                    action.copy(
                        targetMacroId = chosen?.id ?: action.targetMacroId,
                        targetMacroName = chosen?.name ?: action.targetMacroName,
                        returnAfter = retChk.isSelected,
                        enabled = enabledChk.isSelected, delayAfterMs = (delaySpin.value as Number).toLong(),
                        chancePercent = (chanceSpin.value as Number).toInt()
                    )
                }
            }

            is FinishMacroAction -> {
                val modeBox = JComboBox(FinishMacroMode.entries.toTypedArray()).apply { selectedItem = action.mode }
                val actives = allMacros.filter { it.id != currentMacro.id && !it.isPassive }
                val activeBox = JComboBox(actives.map { it.name }.toTypedArray())
                val selIdx = actives.indexOfFirst { it.id == action.targetMacroId }
                if (selIdx >= 0) activeBox.selectedIndex = selIdx
                form.add(row(JLabel("Mode Selesaikan:"), modeBox))
                form.add(row(JLabel("Macro Aktif Tujuan (jika Pindah):"), activeBox))
                buildUpdated = {
                    val chosen = actives.getOrNull(activeBox.selectedIndex)
                    action.copy(
                        mode = modeBox.selectedItem as FinishMacroMode,
                        targetMacroId = chosen?.id ?: action.targetMacroId,
                        targetMacroName = chosen?.name ?: action.targetMacroName,
                        enabled = enabledChk.isSelected, delayAfterMs = (delaySpin.value as Number).toLong(),
                        chancePercent = (chanceSpin.value as Number).toInt()
                    )
                }
            }

            is JumpAction -> {
                val modeBox = JComboBox(JumpMode.entries.toTypedArray()).apply { selectedItem = action.mode }
                val candidates = currentMacro.actions.filter { it.id != action.id }
                val candLabels = candidates.mapIndexed { idx, a -> "#${idx + 1} ${a.kind.label}: ${a.summary().take(28)}" }.toTypedArray()
                val candBox = JComboBox(candLabels)
                val selIdx = candidates.indexOfFirst { it.id == action.targetActionId }
                if (selIdx >= 0) candBox.selectedIndex = selIdx
                val stepS = JSpinner(SpinnerNumberModel(action.stepCount.coerceIn(1, 100), 1, 100, 1))
                form.add(row(JLabel("Mode Loncat:"), modeBox))
                form.add(row(JLabel("Aksi Tujuan:"), candBox))
                form.add(row(JLabel("Jumlah Langkah N (jika ±N):"), stepS))
                buildUpdated = {
                    val chosen = candidates.getOrNull(candBox.selectedIndex)
                    action.copy(
                        mode = modeBox.selectedItem as JumpMode,
                        targetActionId = chosen?.id ?: action.targetActionId,
                        targetActionLabel = chosen?.let { "${it.kind.label}: ${it.summary().take(24)}" } ?: action.targetActionLabel,
                        stepCount = (stepS.value as Number).toInt(),
                        enabled = enabledChk.isSelected, delayAfterMs = (delaySpin.value as Number).toLong(),
                        chancePercent = (chanceSpin.value as Number).toInt()
                    )
                }
            }

            is LaunchAppAction -> {
                val pkgF = JTextField(action.packageName.ifBlank { action.appLabel }, 22)
                form.add(row(JLabel("Nama Jendela PC / Paket Android (ADB):"), pkgF))
                buildUpdated = {
                    action.copy(
                        packageName = pkgF.text.trim(), appLabel = pkgF.text.trim(),
                        enabled = enabledChk.isSelected, delayAfterMs = (delaySpin.value as Number).toLong(),
                        chancePercent = (chanceSpin.value as Number).toInt()
                    )
                }
            }

            is ExitAppAction -> {
                val modeBox = JComboBox(ExitAppMode.entries.toTypedArray()).apply { selectedItem = action.mode }
                val pkgF = JTextField(action.packageName.ifBlank { action.appLabel }, 20)
                form.add(row(JLabel("Mode Keluar:"), modeBox))
                form.add(row(JLabel("Proses .exe / Paket Android (opsional):"), pkgF))
                buildUpdated = {
                    action.copy(
                        mode = modeBox.selectedItem as ExitAppMode,
                        packageName = pkgF.text.trim(), appLabel = pkgF.text.trim(),
                        enabled = enabledChk.isSelected, delayAfterMs = (delaySpin.value as Number).toLong(),
                        chancePercent = (chanceSpin.value as Number).toInt()
                    )
                }
            }

            is RecordAction -> {
                var gestures = action.gestures
                val spdS = JSpinner(SpinnerNumberModel(action.speed.toDouble().coerceIn(0.25, 4.0), 0.25, 4.0, 0.25))
                val infoLbl = JLabel("${gestures.sumOf { it.strokes.size }} goresan terekam")
                form.add(row(infoLbl, DesktopTheme.primaryButton("● Rekam Gerakan Mouse", DesktopTheme.ROSE) {
                    dlg.isVisible = false
                    onRecordMouseOnScreen { g ->
                        gestures = listOf(g)
                        infoLbl.text = "${g.strokes.size} goresan baru terekam!"
                        dlg.isVisible = true
                    }
                }))
                form.add(row(JLabel("Kecepatan Putar (×):"), spdS))
                buildUpdated = {
                    action.copy(
                        gestures = gestures, speed = (spdS.value as Number).toFloat(),
                        enabled = enabledChk.isSelected, delayAfterMs = (delaySpin.value as Number).toLong(),
                        chancePercent = (chanceSpin.value as Number).toInt()
                    )
                }
            }

            is RestartAction -> {
                buildUpdated = {
                    action.copy(
                        enabled = enabledChk.isSelected, delayAfterMs = (delaySpin.value as Number).toLong(),
                        chancePercent = (chanceSpin.value as Number).toInt()
                    )
                }
            }

            is StopAction -> {
                buildUpdated = {
                    action.copy(
                        enabled = enabledChk.isSelected, delayAfterMs = (delaySpin.value as Number).toLong(),
                        chancePercent = (chanceSpin.value as Number).toInt()
                    )
                }
            }
        }

        form.add(Box.createVerticalStrut(8))
        form.add(row(enabledChk, JLabel("Jeda Sesudah (ms):"), delaySpin, JLabel("Peluang (%):"), chanceSpin))

        val btnBar = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { isOpaque = false }
        btnBar.add(DesktopTheme.subtleButton("Batal") { dlg.dispose() })
        btnBar.add(DesktopTheme.primaryButton("Simpan Aksi") {
            val updated = buildUpdated()
            dlg.dispose()
            onSave(updated)
        })

        root.add(form, BorderLayout.CENTER)
        root.add(btnBar, BorderLayout.SOUTH)
        dlg.contentPane = root
        dlg.pack()
        dlg.setLocationRelativeTo(owner)
        dlg.isVisible = true
    }
}
