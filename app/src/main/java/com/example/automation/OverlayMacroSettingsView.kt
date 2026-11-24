package com.example.automation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.data.Action
import com.example.data.ActionKind
import com.example.data.ClickAction
import com.example.data.ColorAction
import com.example.data.ContainerRef
import com.example.data.ImageAction
import com.example.data.LocalStore
import com.example.data.LongPressAction
import com.example.data.LoopAction
import com.example.data.Macro
import com.example.data.MacroSecurity
import com.example.data.NotFoundMode
import com.example.data.RecordAction
import com.example.data.Region
import com.example.data.ResolutionScaleMode
import com.example.data.ResolutionScaler
import com.example.data.RestartAction
import com.example.data.RunMacroAction
import com.example.data.Slot
import com.example.data.StopAction
import com.example.data.StuckRecoveryMode
import com.example.data.SwipeAction
import com.example.data.TextAction
import com.example.data.TextCompareMode
import com.example.data.WaitAction
import com.example.data.ZoomAction
import com.example.data.childList
import com.example.data.create
import com.example.data.findAction
import com.example.data.kind
import com.example.data.slots
import com.example.data.summary
import kotlin.math.roundToInt

/**
 * Full-featured in-overlay Macro Studio matching the in-app Macro Editor:
 * - Tab "Setelan": switch active macro, name, loop/delay/jitter/randomDelay/maxDuration,
 *   Auto-Recover Stuck Macro watchdog, and optional marker preview toggle.
 * - Tab "Aksi": full action tree (including Then/Else/Loop branches), add any action kind,
 *   random event chance %, 1-tap visual image swapping, OCR Text & Numeric action editor,
 *   and visual target placement. Locked macros hide actions & settings until unlocked in-app.
 */
@SuppressLint("ViewConstructor")
class OverlayMacroSettingsView(
    context: Context,
    private var macro: Macro,
    private val store: LocalStore,
    private val screenW: Int,
    private val screenH: Int,
    private var showAllMarkersPreview: Boolean,
    initialTab: Int = 0,
    initialEditActionId: String? = null,
    private val onSwitchMacro: (Macro) -> Unit,
    private val onToggleShowAllMarkers: (Boolean) -> Unit,
    private val onUpdateMacro: (Macro) -> Unit,
    private val onAddAction: (ContainerRef, Action) -> Unit,
    private val onUpdateAction: (String, (Action) -> Action) -> Unit,
    private val onMoveAction: (String, Int) -> Unit,
    private val onDeleteAction: (String) -> Unit,
    private val onCropForImageAction: (actionId: String) -> Unit,
    private val onRecropExistingImage: (imageName: String) -> Unit,
    private val onAddNewCropImageAction: () -> Unit,
    private val onPickTargetOnScreen: (actionId: String) -> Unit,
    private val onPickRegionOnScreen: (actionId: String) -> Unit,
    private val onAddBranchClickTargetOnScreen: (parentId: String, slot: Slot) -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * d).toInt()

    private var activeTab = initialTab // 0 = Setelan, 1 = Aksi
    private var editingActionId: String? = initialEditActionId
    private var quickImageSwapActionId: String? = null
    private var addPickerRef: ContainerRef? = null

    private val bodyContainer = LinearLayout(context).apply { orientation = VERTICAL }
    private val tabSettingsBtn: TextView
    private val tabActionsBtn: TextView
    private val titleView: TextView
    private val subtitleView: TextView

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#F50A1020"))
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Color.parseColor("#22D3EE"))
        }
        elevation = dp(10).toFloat()

        // Header
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        val titleCol = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        titleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitleView = TextView(context).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10.5f
            maxLines = 1
        }
        titleCol.addView(titleView)
        titleCol.addView(subtitleView)
        header.addView(titleCol)
        header.addView(makeBtn("✕", "#334155") { onClose() })
        addView(header)

        // Tab switcher row
        val tabsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        tabSettingsBtn = makeTabBtn("Setelan Macro") {
            activeTab = 0
            editingActionId = null
            quickImageSwapActionId = null
            addPickerRef = null
            rebuildBody()
        }
        tabActionsBtn = makeTabBtn("Daftar Aksi") {
            activeTab = 1
            editingActionId = null
            quickImageSwapActionId = null
            addPickerRef = null
            rebuildBody()
        }
        tabsRow.addView(tabSettingsBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        tabsRow.addView(tabActionsBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(tabsRow)

        bodyContainer.setPadding(0, 0, 0, dp(14))
        val scroll = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            clipToPadding = false
            isScrollbarFadingEnabled = false
            addView(bodyContainer)
        }
        addView(scroll)

        rebuildBody()
    }

    fun refreshWithMacro(updated: Macro) {
        macro = updated
        rebuildBody()
    }

    private fun updateHeader() {
        val lockIcon = if (macro.isLocked) "🔒 " else ""
        titleView.text = lockIcon + macro.name.ifBlank { "Macro Aktif" }
        val loopStr = if (macro.loopCount <= 0) "Loop ∞" else "Loop ${macro.loopCount}×"
        val recStr = if (macro.autoRecoverStuckSec > 0) " • AutoFix ${macro.autoRecoverStuckSec}d" else ""
        val extra = buildList {
            if (macro.isPassive) add(if (macro.passiveEnabled) "Pasif ON" else "Pasif OFF")
            MacroSecurity.remainingTimeLabel(macro, store.effectiveNowMs())?.let { add(it) }
        }.joinToString(" • ").let { if (it.isNotBlank()) " • $it" else "" }
        subtitleView.text = "$loopStr • ${macro.actions.size} aksi$recStr$extra"
        tabActionsBtn.text = if (macro.isLocked) "🔒 Terkunci" else "Daftar Aksi (${macro.actions.size})"

        styleTab(tabSettingsBtn, activeTab == 0)
        styleTab(tabActionsBtn, activeTab == 1)
    }

    private fun styleTab(tv: TextView, selected: Boolean) {
        (tv.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (selected) "#0891B2" else "#1E293B")
        )
        tv.setTextColor(if (selected) Color.WHITE else Color.parseColor("#94A3B8"))
    }

    private fun rebuildBody() {
        updateHeader()
        bodyContainer.removeAllViews()

        if (macro.isLocked) {
            if (activeTab == 0) {
                buildMacroSwitcherRow()
                buildPassiveQuickControlsRow()
            }
            buildLockedOverlayNotice()
            return
        }

        val pickerRef = addPickerRef
        val swapId = quickImageSwapActionId
        val editId = editingActionId
        when {
            pickerRef != null -> buildAddActionPicker(pickerRef)
            swapId != null -> {
                val target = macro.actions.findAction(swapId) as? ImageAction
                if (target != null) buildQuickImageSwapView(target)
                else {
                    quickImageSwapActionId = null
                    buildActionList()
                }
            }
            editId != null -> {
                val target = macro.actions.findAction(editId)
                if (target != null) buildActionEditor(target)
                else {
                    editingActionId = null
                    buildActionList()
                }
            }
            activeTab == 0 -> buildMacroSettingsForm()
            else -> buildActionList()
        }
    }

    private fun buildMacroSwitcherRow() {
        val allMacros = store.macros.value
        if (allMacros.size > 1) {
            bodyContainer.addView(sectionLabel("Ganti Macro Aktif"))
            val hScroll = HorizontalScrollView(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (m in allMacros) {
                val sel = m.id == macro.id
                val prefix = when {
                    m.isLocked -> "🔒 "
                    m.isPassive && !m.passiveEnabled -> "⛔ "
                    m.isPassive -> "🔗 "
                    else -> ""
                }
                row.addView(makeBtn((prefix + m.name).take(18), if (sel) "#0891B2" else "#1E293B", marginEndDp = 5) {
                    if (!sel) {
                        macro = m
                        onSwitchMacro(m)
                        rebuildBody()
                    }
                })
            }
            hScroll.addView(row)
            bodyContainer.addView(hScroll)
        }
    }

    private fun buildPassiveQuickControlsRow() {
        val now = store.effectiveNowMs()
        val passiveMacros = store.macros.value.filter {
            it.isPassive && !MacroSecurity.isTampered(it) && !MacroSecurity.isExpired(it, now)
        }
        if (passiveMacros.isEmpty()) return

        bodyContainer.addView(sectionLabel("Pasif ON/OFF (Tanpa Sandi)"))
        val hScroll = HorizontalScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        for (m in passiveMacros) {
            val label = (if (m.passiveEnabled) "🔗 " else "⛔ ") + m.name.take(14) + if (m.passiveEnabled) " • ON" else " • OFF"
            val bgHex = if (m.passiveEnabled) "#059669" else "#B45309"
            row.addView(makeBtn(label, bgHex, marginEndDp = 5) {
                val updated = MacroSecurity.togglePassiveEnabled(m, !m.passiveEnabled)
                store.saveMacro(updated)
                if (updated.id == macro.id) {
                    macro = updated
                    AutomationCoordinator.selectMacro(updated)
                }
                val st = if (updated.passiveEnabled) "ON" else "OFF"
                Toast.makeText(context, "Pasif '${updated.name}': $st", Toast.LENGTH_SHORT).show()
                rebuildBody()
            })
        }
        hScroll.addView(row)
        bodyContainer.addView(hScroll)
    }

    private fun buildLockedOverlayNotice() {
        val now = store.effectiveNowMs()
        val expLabel = MacroSecurity.remainingTimeLabel(macro, now)
        val lines = buildList {
            add("🔒 Macro ini dikunci dengan sandi.")
            add("Daftar aksi & setelan disembunyikan agar tidak dapat diubah atau disalin.")
            if (macro.isPassive) {
                add(
                    if (macro.passiveEnabled) "🔗 Mode Pasif: ON — dapat ditarik oleh macro aktif lain (ON/OFF bebas tanpa sandi)."
                    else "⛔ Mode Pasif: OFF — tidak dapat ditarik (otomatis dilewati/skip oleh macro aktif; ON/OFF bebas tanpa sandi)."
                )
            }
            if (expLabel != null) {
                add("⏳ Masa berlaku: $expLabel (${MacroSecurity.formatDeadlineDate(macro.expiresAtEpochMs)})")
            }
            val scaleTransform = ResolutionScaler.forMacro(macro, screenW, screenH)
            if (scaleTransform.isScaling) {
                add("📐 Auto-Scale aktif: ${scaleTransform.describe()}")
            }
            add("Buka kunci melalui aplikasi Wjust macro dengan sandi untuk mengedit atau mengubah status Aktif/Pasif.")
        }.joinToString("\n\n")

        val box = TextView(context).apply {
            text = lines
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11.5f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18243E"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#A78BFA"))
            }
        }
        bodyContainer.addView(box)
    }

    // ---- TAB 1: Complete Macro Settings ----

    private fun buildMacroSettingsForm() {
        buildMacroSwitcherRow()
        buildPassiveQuickControlsRow()

        val nameField = addTextRow(bodyContainer, "Nama Macro", macro.name)

        val infiniteCheck = CheckBox(context).apply {
            text = "Ulangi tanpa batas (Loop ∞)"
            isChecked = macro.loopCount <= 0
            setTextColor(Color.WHITE)
            textSize = 11.5f
        }
        bodyContainer.addView(infiniteCheck)

        val loopCountField = addNumberRow(bodyContainer, "Jumlah Loop", macro.loopCount.coerceAtLeast(1).toString())
        loopCountField.isEnabled = !infiniteCheck.isChecked
        loopCountField.alpha = if (infiniteCheck.isChecked) 0.45f else 1f
        infiniteCheck.setOnCheckedChangeListener { _, checked ->
            loopCountField.isEnabled = !checked
            loopCountField.alpha = if (checked) 0.45f else 1f
        }

        val loopDelayField = addNumberRow(bodyContainer, "Jeda antar loop (ms)", macro.loopDelayMs.toString())
        val jitterField = addNumberRow(bodyContainer, "Acak posisi (±px)", macro.jitterPx.toString())
        val randomDelayField = addNumberRow(bodyContainer, "Acak jeda (+ms)", macro.randomDelayMs.toString())
        val maxMinField = addNumberRow(bodyContainer, "Stop otomatis (menit, 0=off)", macro.maxDurationMin.toString())

        // Auto-Scale Resolusi Layar section
        bodyContainer.addView(dividerLine())
        bodyContainer.addView(sectionLabel("Auto-Scale Resolusi Layar"))
        val autoScaleCheck = CheckBox(context).apply {
            text = "Aktifkan Auto-Scale (antar HP / Redfinger)"
            isChecked = macro.autoScaleResolution
            setTextColor(Color.WHITE)
            textSize = 11.5f
        }
        bodyContainer.addView(autoScaleCheck)

        val srcWField = addNumberRow(
            bodyContainer,
            "Lebar asal (px, 0=otomatis)",
            macro.sourceWidth.takeIf { it > 0 }?.toString() ?: ""
        )
        val srcHField = addNumberRow(
            bodyContainer,
            "Tinggi asal (px, 0=otomatis)",
            macro.sourceHeight.takeIf { it > 0 }?.toString() ?: ""
        )
        val scalePresetsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        scalePresetsRow.addView(makeBtn("Layar Ini (${screenW}×${screenH})", "#1E293B", marginEndDp = 5) {
            srcWField.setText(screenW.toString())
            srcHField.setText(screenH.toString())
        })
        scalePresetsRow.addView(makeBtn("⇄ Putar", "#1E293B", marginEndDp = 0) {
            val curW = srcWField.text.toString().ifBlank { screenW.toString() }
            val curH = srcHField.text.toString().ifBlank { screenH.toString() }
            srcWField.setText(curH)
            srcHField.setText(curW)
        })
        bodyContainer.addView(scalePresetsRow)

        var chosenScaleMode = macro.scaleMode
        val scaleModeRow = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            }
        }
        val scaleModeBtns = mutableListOf<Pair<ResolutionScaleMode, TextView>>()
        ResolutionScaleMode.entries.forEach { mode ->
            val btn = makeBtn(mode.label, if (mode == chosenScaleMode) "#0284C7" else "#1E293B", marginEndDp = 0) {
                chosenScaleMode = mode
                scaleModeBtns.forEach { (m, tv) ->
                    (tv.background as? GradientDrawable)?.setColor(Color.parseColor(if (m == chosenScaleMode) "#0284C7" else "#1E293B"))
                }
            }.apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(4)
                }
            }
            scaleModeBtns += mode to btn
            scaleModeRow.addView(btn)
        }
        bodyContainer.addView(scaleModeRow)

        // Auto-Recover Stuck Macro section
        bodyContainer.addView(dividerLine())
        bodyContainer.addView(sectionLabel("Auto Perbaiki Macro Macet (Watchdog)"))
        val stuckSecField = addNumberRow(bodyContainer, "Batas macet (detik, 0=off)", macro.autoRecoverStuckSec.toString())
        var chosenRecovery = macro.stuckRecoveryMode
        val recRow = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        val recBtns = mutableListOf<Pair<StuckRecoveryMode, TextView>>()
        StuckRecoveryMode.entries.forEach { mode ->
            val btn = makeBtn(mode.label, if (mode == chosenRecovery) "#0284C7" else "#1E293B", marginEndDp = 0) {
                chosenRecovery = mode
                recBtns.forEach { (m, tv) ->
                    (tv.background as? GradientDrawable)?.setColor(Color.parseColor(if (m == chosenRecovery) "#0284C7" else "#1E293B"))
                }
            }.apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(4)
                }
            }
            recBtns += mode to btn
            recRow.addView(btn)
        }
        bodyContainer.addView(recRow)

        bodyContainer.addView(dividerLine())
        val showMarkersCheck = CheckBox(context).apply {
            text = "Tampilkan semua penanda titik di layar (Preview)"
            isChecked = showAllMarkersPreview
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11.5f
            setOnCheckedChangeListener { _, checked ->
                showAllMarkersPreview = checked
                onToggleShowAllMarkers(checked)
            }
        }
        bodyContainer.addView(showMarkersCheck)

        // Save Settings button
        val saveRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        saveRow.addView(makeBtn("Simpan Setelan", "#0D9488") {
            val updated = macro.copy(
                name = nameField.text.toString().trim().ifBlank { "Macro Baru" },
                loopCount = if (infiniteCheck.isChecked) 0 else (loopCountField.text.toString().toIntOrNull() ?: 1).coerceAtLeast(1),
                loopDelayMs = (loopDelayField.text.toString().toLongOrNull() ?: macro.loopDelayMs).coerceAtLeast(0L),
                jitterPx = (jitterField.text.toString().toIntOrNull() ?: macro.jitterPx).coerceIn(0, 60),
                randomDelayMs = (randomDelayField.text.toString().toLongOrNull() ?: macro.randomDelayMs).coerceAtLeast(0L),
                maxDurationMin = (maxMinField.text.toString().toIntOrNull() ?: macro.maxDurationMin).coerceIn(0, 1440),
                autoRecoverStuckSec = (stuckSecField.text.toString().toIntOrNull() ?: macro.autoRecoverStuckSec).coerceIn(0, 3600),
                stuckRecoveryMode = chosenRecovery,
                sourceWidth = (srcWField.text.toString().toIntOrNull() ?: 0).coerceIn(0, 10000),
                sourceHeight = (srcHField.text.toString().toIntOrNull() ?: 0).coerceIn(0, 10000),
                autoScaleResolution = autoScaleCheck.isChecked,
                scaleMode = chosenScaleMode
            )
            macro = updated
            onUpdateMacro(updated)
            updateHeader()
        })
        bodyContainer.addView(saveRow)
    }

    // ---- TAB 2: Complete Action Tree ----

    private fun buildActionList() {
        val topBtns = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        topBtns.addView(makeBtn("+ Tambah Aksi", "#1D4ED8", marginEndDp = 6) {
            addPickerRef = ContainerRef.ROOT
            rebuildBody()
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        topBtns.addView(makeBtn("✂ Crop Img", "#0F766E") {
            onAddNewCropImageAction()
        })
        bodyContainer.addView(topBtns)

        if (macro.actions.isEmpty()) {
            bodyContainer.addView(TextView(context).apply {
                text = "Belum ada aksi. Ketuk '+ Tambah Aksi' atau '✂ Crop Img'."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 11.5f
                setPadding(dp(4), dp(12), dp(4), dp(12))
            })
        } else {
            macro.actions.forEachIndexed { idx, action ->
                bodyContainer.addView(buildActionCard(idx, macro.actions.size, action, depth = 0))
            }
        }
    }

    private fun buildActionCard(idx: Int, siblingCount: Int, action: Action, depth: Int): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (depth == 0) "#1E293B" else "#0F172A"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            alpha = if (action.enabled) 1f else 0.5f
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        if (action is ImageAction && action.imageName.isNotBlank()) {
            val file = store.imageFile(action.imageName)
            if (file.exists()) {
                val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                if (bmp != null) {
                    row.addView(ImageView(context).apply {
                        setImageBitmap(bmp)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(Color.parseColor("#0B1220"))
                        setPadding(dp(2), dp(2), dp(2), dp(2))
                        layoutParams = LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(6) }
                        setOnClickListener {
                            quickImageSwapActionId = action.id
                            rebuildBody()
                        }
                    })
                }
            }
        }

        val info = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                editingActionId = action.id
                rebuildBody()
            }
        }
        info.addView(TextView(context).apply {
            text = "${idx + 1}. ${action.kind.label}"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        info.addView(TextView(context).apply {
            text = action.summary()
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(info)

        if (idx > 0) {
            row.addView(makeSmallIconBtn("↑", "#334155") { onMoveAction(action.id, -1) })
        }
        if (idx < siblingCount - 1) {
            row.addView(makeSmallIconBtn("↓", "#334155") { onMoveAction(action.id, 1) })
        }
        if (action is ImageAction) {
            row.addView(makeSmallIconBtn("🖼", "#0F766E") {
                quickImageSwapActionId = action.id
                rebuildBody()
            })
        } else if (action is TextAction) {
            row.addView(makeSmallIconBtn("📐", "#0369A1") {
                onPickRegionOnScreen(action.id)
            })
        } else if (isPositionable(action)) {
            row.addView(makeSmallIconBtn("🎯", "#0F766E") {
                onPickTargetOnScreen(action.id)
            })
        }
        row.addView(makeSmallIconBtn(if (action.enabled) "ON" else "OFF", if (action.enabled) "#059669" else "#475569") {
            onUpdateAction(action.id) { it.withEnabled(!action.enabled) }
        })
        row.addView(makeSmallIconBtn("✎", "#0284C7") {
            editingActionId = action.id
            rebuildBody()
        })
        row.addView(makeSmallIconBtn("✕", "#991B1B", marginEndDp = 0) {
            onDeleteAction(action.id)
        })
        card.addView(row)

        // Nested branches (Jika Ditemukan / Jika Tidak / Isi Perulangan)
        val slots = action.slots()
        if (slots.isNotEmpty() && depth < 2) {
            for (slot in slots) {
                val children = action.childList(slot)
                val accentHex = when (slot) {
                    Slot.THEN -> "#10B981"
                    Slot.ELSE -> "#F59E0B"
                    Slot.BODY -> "#818CF8"
                }
                val box = LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(6), dp(5), dp(6), dp(5))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#18000000"))
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), Color.parseColor(accentHex))
                    }
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(5)
                    }
                }
                val bHeader = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                bHeader.addView(TextView(context).apply {
                    text = "${slot.label} (${children.size})"
                    setTextColor(Color.parseColor(accentHex))
                    textSize = 10.5f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                })
                bHeader.addView(makeSmallIconBtn("+🎯 Klik", "#0F766E", marginEndDp = 4) {
                    onAddBranchClickTargetOnScreen(action.id, slot)
                })
                bHeader.addView(makeSmallIconBtn("+ Aksi", "#1E293B", marginEndDp = 0) {
                    addPickerRef = ContainerRef(action.id, slot)
                    rebuildBody()
                })
                box.addView(bHeader)
                children.forEachIndexed { cIdx, child ->
                    box.addView(buildActionCard(cIdx, children.size, child, depth + 1))
                }
                card.addView(box)
            }
        }

        return card
    }

    private fun isPositionable(action: Action): Boolean = when (action) {
        is ClickAction, is LongPressAction, is SwipeAction, is ZoomAction, is ColorAction -> true
        else -> false
    }

    // ---- Add Action Picker inside Overlay ----

    private fun buildAddActionPicker(ref: ContainerRef) {
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        bar.addView(makeBtn("← Kembali", "#334155", marginEndDp = 8) {
            addPickerRef = null
            rebuildBody()
        })
        bar.addView(TextView(context).apply {
            text = if (ref.slot != null) "Tambah ke: ${ref.slot.label}" else "Pilih Jenis Aksi"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        })
        bodyContainer.addView(bar)

        val kinds = listOf(
            ActionKind.IMAGE, ActionKind.TEXT, ActionKind.COLOR, ActionKind.CLICK, ActionKind.LONG_PRESS,
            ActionKind.SWIPE, ActionKind.ZOOM, ActionKind.WAIT, ActionKind.LOOP,
            ActionKind.RUN_MACRO, ActionKind.RESTART, ActionKind.STOP
        )
        for (k in kinds) {
            val item = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = dp(10).toFloat()
                }
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(5)
                }
                setOnClickListener {
                    val defaultImg = store.images.value.firstOrNull()?.name.orEmpty()
                    val created = k.create(screenW, screenH, imageName = defaultImg)
                    onAddAction(ref, created)
                    addPickerRef = null
                    editingActionId = if (k == ActionKind.STOP || k == ActionKind.RESTART) null else created.id
                }
            }
            item.addView(TextView(context).apply {
                text = k.label
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            })
            item.addView(TextView(context).apply {
                text = k.description
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 10f
            })
            bodyContainer.addView(item)
        }
    }

    // ---- Complete Action Editor inside Overlay ----

    private fun buildActionEditor(action: Action) {
        val nav = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        nav.addView(makeBtn("← Kembali", "#334155", marginEndDp = 8) {
            editingActionId = null
            rebuildBody()
        })
        nav.addView(TextView(context).apply {
            text = "Edit: ${action.kind.label}"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
        })
        bodyContainer.addView(nav)

        val chanceF = addNumberRow(bodyContainer, "Peluang Random Event (0-100%)", action.chancePercent.toString())
        fun saveEdited(transform: (Action) -> Action) {
            val pct = (chanceF.text.toString().toIntOrNull() ?: action.chancePercent).coerceIn(0, 100)
            onUpdateAction(action.id) { cur -> transform(cur).withChancePercent(pct) }
            editingActionId = null
        }

        when (action) {
            is ClickAction -> {
                addPickTargetBtn("🎯 Atur Posisi Klik di Layar") { onPickTargetOnScreen(action.id) }
                val xF = addNumberRow(bodyContainer, "Koordinat X", action.x.toString())
                val yF = addNumberRow(bodyContainer, "Koordinat Y", action.y.toString())
                val durF = addNumberRow(bodyContainer, "Durasi tekan (ms)", action.durationMs.toString())
                val repF = addNumberRow(bodyContainer, "Jumlah klik", action.repeat.toString())
                val repIntF = addNumberRow(bodyContainer, "Interval klik (ms)", action.repeatIntervalMs.toString())
                val delF = addNumberRow(bodyContainer, "Jeda sesudah (ms)", action.delayAfterMs.toString())
                addSaveActionBtn {
                    saveEdited {
                        action.copy(
                            x = xF.text.toString().toIntOrNull() ?: action.x,
                            y = yF.text.toString().toIntOrNull() ?: action.y,
                            durationMs = (durF.text.toString().toLongOrNull() ?: action.durationMs).coerceIn(10L, 60_000L),
                            repeat = (repF.text.toString().toIntOrNull() ?: action.repeat).coerceIn(1, 999),
                            repeatIntervalMs = (repIntF.text.toString().toLongOrNull() ?: action.repeatIntervalMs).coerceAtLeast(10L),
                            delayAfterMs = (delF.text.toString().toLongOrNull() ?: action.delayAfterMs).coerceAtLeast(0L)
                        )
                    }
                }
            }

            is LongPressAction -> {
                addPickTargetBtn("🎯 Atur Posisi Tahan di Layar") { onPickTargetOnScreen(action.id) }
                val xF = addNumberRow(bodyContainer, "Koordinat X", action.x.toString())
                val yF = addNumberRow(bodyContainer, "Koordinat Y", action.y.toString())
                val durF = addNumberRow(bodyContainer, "Durasi tahan (ms)", action.durationMs.toString())
                val delF = addNumberRow(bodyContainer, "Jeda sesudah (ms)", action.delayAfterMs.toString())
                addSaveActionBtn {
                    saveEdited {
                        action.copy(
                            x = xF.text.toString().toIntOrNull() ?: action.x,
                            y = yF.text.toString().toIntOrNull() ?: action.y,
                            durationMs = (durF.text.toString().toLongOrNull() ?: action.durationMs).coerceIn(250L, 60_000L),
                            delayAfterMs = (delF.text.toString().toLongOrNull() ?: action.delayAfterMs).coerceAtLeast(0L)
                        )
                    }
                }
            }

            is SwipeAction -> {
                addPickTargetBtn("🎯 Atur Garis Swipe A→B di Layar") { onPickTargetOnScreen(action.id) }
                val x1F = addNumberRow(bodyContainer, "Awal X (A)", action.x1.toString())
                val y1F = addNumberRow(bodyContainer, "Awal Y (A)", action.y1.toString())
                val x2F = addNumberRow(bodyContainer, "Akhir X (B)", action.x2.toString())
                val y2F = addNumberRow(bodyContainer, "Akhir Y (B)", action.y2.toString())
                val durF = addNumberRow(bodyContainer, "Durasi swipe (ms)", action.durationMs.toString())
                val delF = addNumberRow(bodyContainer, "Jeda sesudah (ms)", action.delayAfterMs.toString())
                addSaveActionBtn {
                    saveEdited {
                        action.copy(
                            x1 = x1F.text.toString().toIntOrNull() ?: action.x1,
                            y1 = y1F.text.toString().toIntOrNull() ?: action.y1,
                            x2 = x2F.text.toString().toIntOrNull() ?: action.x2,
                            y2 = y2F.text.toString().toIntOrNull() ?: action.y2,
                            durationMs = (durF.text.toString().toLongOrNull() ?: action.durationMs).coerceIn(30L, 10_000L),
                            delayAfterMs = (delF.text.toString().toLongOrNull() ?: action.delayAfterMs).coerceAtLeast(0L)
                        )
                    }
                }
            }

            is ZoomAction -> {
                addPickTargetBtn("🎯 Atur Titik Pusat Zoom di Layar") { onPickTargetOnScreen(action.id) }
                var zoomInFlag = action.zoomIn
                val modeRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                }
                lateinit var btnIn: TextView
                lateinit var btnOut: TextView
                btnIn = makeBtn("Zoom In (+)", if (zoomInFlag) "#0D9488" else "#1E293B", marginEndDp = 6) {
                    zoomInFlag = true
                    (btnIn.background as? GradientDrawable)?.setColor(Color.parseColor("#0D9488"))
                    (btnOut.background as? GradientDrawable)?.setColor(Color.parseColor("#1E293B"))
                }
                btnOut = makeBtn("Zoom Out (−)", if (!zoomInFlag) "#0D9488" else "#1E293B") {
                    zoomInFlag = false
                    (btnIn.background as? GradientDrawable)?.setColor(Color.parseColor("#1E293B"))
                    (btnOut.background as? GradientDrawable)?.setColor(Color.parseColor("#0D9488"))
                }
                modeRow.addView(btnIn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
                modeRow.addView(btnOut, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                bodyContainer.addView(modeRow)

                val cxF = addNumberRow(bodyContainer, "Pusat X", action.cx.toString())
                val cyF = addNumberRow(bodyContainer, "Pusat Y", action.cy.toString())
                val distF = addNumberRow(bodyContainer, "Jarak cubit (px)", action.distancePx.toString())
                val durF = addNumberRow(bodyContainer, "Durasi (ms)", action.durationMs.toString())
                val delF = addNumberRow(bodyContainer, "Jeda sesudah (ms)", action.delayAfterMs.toString())
                addSaveActionBtn {
                    saveEdited {
                        action.copy(
                            zoomIn = zoomInFlag,
                            cx = cxF.text.toString().toIntOrNull() ?: action.cx,
                            cy = cyF.text.toString().toIntOrNull() ?: action.cy,
                            distancePx = (distF.text.toString().toIntOrNull() ?: action.distancePx).coerceIn(80, 900),
                            durationMs = (durF.text.toString().toLongOrNull() ?: action.durationMs).coerceIn(60L, 5000L),
                            delayAfterMs = (delF.text.toString().toLongOrNull() ?: action.delayAfterMs).coerceAtLeast(0L)
                        )
                    }
                }
            }

            is WaitAction -> {
                val durF = addNumberRow(bodyContainer, "Tunggu (ms)", action.durationMs.toString())
                val rndF = addNumberRow(bodyContainer, "Acak tambahan (ms)", action.randomMs.toString())
                addSaveActionBtn {
                    saveEdited {
                        action.copy(
                            durationMs = (durF.text.toString().toLongOrNull() ?: action.durationMs).coerceAtLeast(0L),
                            randomMs = (rndF.text.toString().toLongOrNull() ?: action.randomMs).coerceAtLeast(0L)
                        )
                    }
                }
            }

            is ImageAction -> {
                var selectedImg = action.imageName
                var threshold = action.threshold
                var foundMode = action.foundMode
                var notFoundMode = action.notFound

                // Crop new & Re-crop existing buttons
                val cropRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                }
                cropRow.addView(makeBtn("✂ Crop Baru", "#0F766E", marginEndDp = 6) {
                    onCropForImageAction(action.id)
                }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
                if (selectedImg.isNotBlank() && store.imageFile(selectedImg).exists()) {
                    cropRow.addView(makeBtn("✂ Potong Ulang", "#0284C7") {
                        onRecropExistingImage(selectedImg)
                    }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                }
                bodyContainer.addView(cropRow)

                // Visual folder-aware thumbnail picker
                addVisualImageSelector(
                    initialSelected = selectedImg,
                    onSelect = { selectedImg = it }
                )

                // Threshold stepper
                val thrRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                }
                val thrLabel = TextView(context).apply {
                    text = "Akurasi kemiripan: ${(threshold * 100).roundToInt()}%"
                    setTextColor(Color.WHITE)
                    textSize = 11.5f
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                }
                thrRow.addView(thrLabel)
                thrRow.addView(makeSmallIconBtn("-5%", "#334155") {
                    threshold = (threshold - 0.05f).coerceIn(0.50f, 0.99f)
                    thrLabel.text = "Akurasi kemiripan: ${(threshold * 100).roundToInt()}%"
                })
                thrRow.addView(makeSmallIconBtn("+5%", "#334155", marginEndDp = 0) {
                    threshold = (threshold + 0.05f).coerceIn(0.50f, 0.99f)
                    thrLabel.text = "Akurasi kemiripan: ${(threshold * 100).roundToInt()}%"
                })
                bodyContainer.addView(thrRow)

                val timeoutF = addNumberRow(bodyContainer, "Batas waktu cari (ms)", action.timeoutMs.toString())
                val delayF = addNumberRow(bodyContainer, "Jeda sesudah (ms)", action.delayAfterMs.toString())

                bodyContainer.addView(dividerLine())
                bodyContainer.addView(sectionLabel("Jika Ditemukan (Then)"))
                val clickCheck = CheckBox(context).apply {
                    text = "Klik gambar saat ditemukan"
                    isChecked = action.clickOnFound
                    setTextColor(Color.WHITE)
                    textSize = 11.5f
                }
                bodyContainer.addView(clickCheck)
                val oxF = addSignedNumberRow(bodyContainer, "Offset X (px)", action.offsetX.toString())
                val oyF = addSignedNumberRow(bodyContainer, "Offset Y (px)", action.offsetY.toString())
                addBranchModePicker("Setelah cabang Ditemukan:", foundMode) { foundMode = it }
                addBranchActionsControls(action.id, Slot.THEN, action.thenActions, "#065F46")

                bodyContainer.addView(dividerLine())
                bodyContainer.addView(sectionLabel("Jika Tidak Ditemukan (Else)"))
                addBranchModePicker("Setelah cabang Tidak Ditemukan:", notFoundMode) { notFoundMode = it }
                addBranchActionsControls(action.id, Slot.ELSE, action.elseActions, "#92400E")

                // Optional search region
                bodyContainer.addView(dividerLine())
                val regCheck = CheckBox(context).apply {
                    text = "Batasi area pencarian (Region)"
                    isChecked = action.region != null
                    setTextColor(Color.parseColor("#CBD5E1"))
                    textSize = 11.5f
                }
                bodyContainer.addView(regCheck)
                bodyContainer.addView(makeBtn("📐 Pilih Area di Layar", "#0369A1") {
                    onPickRegionOnScreen(action.id)
                }.apply { layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) } })
                val reg = action.region ?: Region(0, 0, screenW, screenH)
                val rL = addNumberRow(bodyContainer, "Area Kiri", reg.left.toString())
                val rT = addNumberRow(bodyContainer, "Area Atas", reg.top.toString())
                val rR = addNumberRow(bodyContainer, "Area Kanan", reg.right.toString())
                val rB = addNumberRow(bodyContainer, "Area Bawah", reg.bottom.toString())

                addSaveActionBtn {
                    val newRegion = if (regCheck.isChecked) {
                        Region(
                            rL.text.toString().toIntOrNull() ?: reg.left,
                            rT.text.toString().toIntOrNull() ?: reg.top,
                            rR.text.toString().toIntOrNull() ?: reg.right,
                            rB.text.toString().toIntOrNull() ?: reg.bottom
                        )
                    } else null
                    saveEdited { cur ->
                        val imgCur = (cur as? ImageAction) ?: action
                        imgCur.copy(
                            imageName = selectedImg,
                            threshold = threshold,
                            timeoutMs = (timeoutF.text.toString().toLongOrNull() ?: imgCur.timeoutMs).coerceAtLeast(0L),
                            delayAfterMs = (delayF.text.toString().toLongOrNull() ?: imgCur.delayAfterMs).coerceAtLeast(0L),
                            clickOnFound = clickCheck.isChecked,
                            offsetX = oxF.text.toString().toIntOrNull() ?: imgCur.offsetX,
                            offsetY = oyF.text.toString().toIntOrNull() ?: imgCur.offsetY,
                            foundMode = foundMode,
                            notFound = notFoundMode,
                            region = newRegion
                        )
                    }
                }
            }

            is TextAction -> {
                var compareMode = action.compareMode
                var foundMode = action.foundMode
                var notFoundMode = action.notFound
                var useRegion = action.region != null

                addPickTargetBtn("📐 Pilih Area Baca Teks/Nilai di Layar") {
                    onPickRegionOnScreen(action.id)
                }

                // Compare mode selector (Text vs Numeric < / <= / > / >= / =)
                bodyContainer.addView(sectionLabel("Kondisi Deteksi"))
                val modeBtns = mutableListOf<Pair<TextCompareMode, TextView>>()
                val queryBox = LinearLayout(context).apply { orientation = VERTICAL }
                val queryField = addTextRow(queryBox, "Teks yang dicari", action.query)
                val numBox = LinearLayout(context).apply { orientation = VERTICAL }
                val numStr = if (action.targetNumber == action.targetNumber.toLong().toDouble()) {
                    action.targetNumber.toLong().toString()
                } else {
                    action.targetNumber.toString()
                }
                val numField = addDecimalRow(numBox, "Nilai pembanding", numStr)

                fun refreshModeInputs() {
                    queryBox.visibility = if (compareMode.isNumeric) View.GONE else View.VISIBLE
                    numBox.visibility = if (compareMode.isNumeric) View.VISIBLE else View.GONE
                    modeBtns.forEach { (m, tv) ->
                        (tv.background as? GradientDrawable)?.setColor(
                            Color.parseColor(if (m == compareMode) "#0284C7" else "#1E293B")
                        )
                    }
                }

                // Row 1: Text modes
                val textModeRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                }
                listOf(TextCompareMode.CONTAINS, TextCompareMode.EQUALS).forEachIndexed { idx, m ->
                    val b = makeBtn(m.label, if (m == compareMode) "#0284C7" else "#1E293B") {
                        compareMode = m
                        refreshModeInputs()
                    }
                    modeBtns += m to b
                    textModeRow.addView(b, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = if (idx == 0) dp(4) else 0
                    })
                }
                bodyContainer.addView(textModeRow)

                // Row 2: Numeric modes (<, <=, >, >=, =)
                val numModeRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                }
                val numericModes = listOf(
                    TextCompareMode.LESS_THAN,
                    TextCompareMode.LESS_OR_EQUAL,
                    TextCompareMode.GREATER_THAN,
                    TextCompareMode.GREATER_OR_EQUAL,
                    TextCompareMode.NUMBER_EQUALS
                )
                numericModes.forEachIndexed { idx, m ->
                    val b = makeBtn(m.shortLabel, if (m == compareMode) "#0284C7" else "#1E293B") {
                        compareMode = m
                        refreshModeInputs()
                    }
                    modeBtns += m to b
                    numModeRow.addView(b, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = if (idx < numericModes.lastIndex) dp(3) else 0
                    })
                }
                bodyContainer.addView(numModeRow)

                bodyContainer.addView(queryBox)
                bodyContainer.addView(numBox)
                refreshModeInputs()

                val timeoutF = addNumberRow(bodyContainer, "Batas waktu cari (ms)", action.timeoutMs.toString())
                val delayF = addNumberRow(bodyContainer, "Jeda sesudah (ms)", action.delayAfterMs.toString())

                bodyContainer.addView(dividerLine())
                bodyContainer.addView(sectionLabel("Jika Cocok (Then)"))
                val clickCheck = CheckBox(context).apply {
                    text = "Klik posisi teks saat cocok"
                    isChecked = action.clickOnFound
                    setTextColor(Color.WHITE)
                    textSize = 11.5f
                }
                bodyContainer.addView(clickCheck)
                addBranchModePicker("Setelah cabang Jika Cocok:", foundMode) { foundMode = it }
                addBranchActionsControls(action.id, Slot.THEN, action.thenActions, "#065F46")

                bodyContainer.addView(dividerLine())
                bodyContainer.addView(sectionLabel("Jika Tidak Cocok (Else)"))
                addBranchModePicker("Setelah cabang Jika Tidak:", notFoundMode) { notFoundMode = it }
                addBranchActionsControls(action.id, Slot.ELSE, action.elseActions, "#92400E")

                bodyContainer.addView(dividerLine())
                val regCheck = CheckBox(context).apply {
                    text = "Batasi area baca (Region)"
                    isChecked = useRegion
                    setTextColor(Color.parseColor("#CBD5E1"))
                    textSize = 11.5f
                    setOnCheckedChangeListener { _, checked -> useRegion = checked }
                }
                bodyContainer.addView(regCheck)
                val reg = action.region ?: Region(0, 0, screenW, screenH)
                val rL = addNumberRow(bodyContainer, "Area Kiri", reg.left.toString())
                val rT = addNumberRow(bodyContainer, "Area Atas", reg.top.toString())
                val rR = addNumberRow(bodyContainer, "Area Kanan", reg.right.toString())
                val rB = addNumberRow(bodyContainer, "Area Bawah", reg.bottom.toString())

                addSaveActionBtn {
                    val newRegion = if (useRegion) {
                        Region(
                            rL.text.toString().toIntOrNull() ?: reg.left,
                            rT.text.toString().toIntOrNull() ?: reg.top,
                            rR.text.toString().toIntOrNull() ?: reg.right,
                            rB.text.toString().toIntOrNull() ?: reg.bottom
                        )
                    } else null
                    val parsedNum = numField.text.toString().replace(',', '.').toDoubleOrNull() ?: action.targetNumber
                    saveEdited { cur ->
                        val txtCur = (cur as? TextAction) ?: action
                        txtCur.copy(
                            compareMode = compareMode,
                            query = queryField.text.toString(),
                            targetNumber = parsedNum,
                            timeoutMs = (timeoutF.text.toString().toLongOrNull() ?: txtCur.timeoutMs).coerceAtLeast(0L),
                            delayAfterMs = (delayF.text.toString().toLongOrNull() ?: txtCur.delayAfterMs).coerceAtLeast(0L),
                            clickOnFound = clickCheck.isChecked,
                            foundMode = foundMode,
                            notFound = notFoundMode,
                            region = newRegion
                        )
                    }
                }
            }

            is ColorAction -> {
                addPickTargetBtn("🎯 Pilih Posisi & Warna di Layar") { onPickTargetOnScreen(action.id) }
                var foundMode = action.foundMode
                var notFoundMode = action.notFound
                val xF = addNumberRow(bodyContainer, "Koordinat X", action.x.toString())
                val yF = addNumberRow(bodyContainer, "Koordinat Y", action.y.toString())
                val hexF = addTextRow(bodyContainer, "Warna (#RRGGBB)", action.color)
                val tolF = addNumberRow(bodyContainer, "Toleransi", action.tolerance.toString())
                val toF = addNumberRow(bodyContainer, "Batas waktu (ms)", action.timeoutMs.toString())
                val delF = addNumberRow(bodyContainer, "Jeda sesudah (ms)", action.delayAfterMs.toString())
                val clickCheck = CheckBox(context).apply {
                    text = "Klik titik saat warna cocok"
                    isChecked = action.clickOnFound
                    setTextColor(Color.WHITE)
                    textSize = 11.5f
                }
                bodyContainer.addView(clickCheck)
                addBranchModePicker("Setelah cabang Ditemukan:", foundMode) { foundMode = it }
                addBranchActionsControls(action.id, Slot.THEN, action.thenActions, "#065F46")
                addBranchModePicker("Setelah cabang Tidak Cocok:", notFoundMode) { notFoundMode = it }
                addBranchActionsControls(action.id, Slot.ELSE, action.elseActions, "#92400E")

                addSaveActionBtn {
                    saveEdited { cur ->
                        val colCur = (cur as? ColorAction) ?: action
                        colCur.copy(
                            x = xF.text.toString().toIntOrNull() ?: colCur.x,
                            y = yF.text.toString().toIntOrNull() ?: colCur.y,
                            color = hexF.text.toString().trim().uppercase().take(7).ifBlank { colCur.color },
                            tolerance = (tolF.text.toString().toIntOrNull() ?: colCur.tolerance).coerceIn(0, 128),
                            timeoutMs = (toF.text.toString().toLongOrNull() ?: colCur.timeoutMs).coerceAtLeast(0L),
                            delayAfterMs = (delF.text.toString().toLongOrNull() ?: colCur.delayAfterMs).coerceAtLeast(0L),
                            clickOnFound = clickCheck.isChecked,
                            foundMode = foundMode,
                            notFound = notFoundMode
                        )
                    }
                }
            }

            is LoopAction -> {
                val cntF = addNumberRow(bodyContainer, "Jumlah pengulangan", action.count.toString())
                bodyContainer.addView(makeBtn("+ Tambah Aksi di Blok (${action.actions.size})", "#3730A3") {
                    addPickerRef = ContainerRef(action.id, Slot.BODY)
                    rebuildBody()
                }.apply { layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) } })
                addSaveActionBtn {
                    saveEdited { cur ->
                        ((cur as? LoopAction) ?: action).copy(
                            count = (cntF.text.toString().toIntOrNull() ?: action.count).coerceIn(1, 9999)
                        )
                    }
                }
            }

            is RunMacroAction -> {
                var targetId = action.targetMacroId
                var targetName = action.targetMacroName
                val otherMacros = store.macros.value.filter { it.id != macro.id }
                bodyContainer.addView(sectionLabel("Pilih Macro Tujuan"))
                if (otherMacros.isEmpty()) {
                    bodyContainer.addView(TextView(context).apply {
                        text = "Belum ada macro lain. Buat macro kedua dulu di aplikasi."
                        setTextColor(Color.parseColor("#94A3B8"))
                        textSize = 11f
                    })
                } else {
                    val now = store.effectiveNowMs()
                    val mBtns = mutableListOf<Pair<String, TextView>>()
                    otherMacros.forEach { m ->
                        val suffix = when {
                            MacroSecurity.isExpired(m, now) -> " [Habis]"
                            m.isPassive && m.passiveEnabled -> " [Pasif ON]"
                            m.isPassive && !m.passiveEnabled -> " [Pasif OFF • skip]"
                            else -> ""
                        }
                        val b = makeBtn(m.name + suffix, if (m.id == targetId) "#0891B2" else "#1E293B") {
                            targetId = m.id
                            targetName = m.name
                            mBtns.forEach { (id, tv) ->
                                (tv.background as? GradientDrawable)?.setColor(Color.parseColor(if (id == targetId) "#0891B2" else "#1E293B"))
                            }
                        }.apply {
                            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                        }
                        mBtns += m.id to b
                        bodyContainer.addView(b)
                    }
                }
                val retCheck = CheckBox(context).apply {
                    text = "Kembali lanjutkan macro ini setelah selesai"
                    isChecked = action.returnAfter
                    setTextColor(Color.WHITE)
                    textSize = 11.5f
                }
                bodyContainer.addView(retCheck)
                val delF = addNumberRow(bodyContainer, "Jeda sesudah (ms)", action.delayAfterMs.toString())
                addSaveActionBtn {
                    saveEdited {
                        action.copy(
                            targetMacroId = targetId,
                            targetMacroName = targetName,
                            returnAfter = retCheck.isChecked,
                            delayAfterMs = (delF.text.toString().toLongOrNull() ?: action.delayAfterMs).coerceAtLeast(0L)
                        )
                    }
                }
            }

            is RecordAction -> {
                var speed = action.speed
                val spdRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                }
                val spdLbl = TextView(context).apply {
                    text = "Kecepatan: ${String.format("%.2f", speed)}x (${action.gestures.size} gestur)"
                    setTextColor(Color.WHITE)
                    textSize = 11.5f
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                }
                spdRow.addView(spdLbl)
                spdRow.addView(makeSmallIconBtn("−", "#334155") {
                    speed = (speed - 0.25f).coerceIn(0.5f, 3f)
                    spdLbl.text = "Kecepatan: ${String.format("%.2f", speed)}x (${action.gestures.size} gestur)"
                })
                spdRow.addView(makeSmallIconBtn("+", "#334155", marginEndDp = 0) {
                    speed = (speed + 0.25f).coerceIn(0.5f, 3f)
                    spdLbl.text = "Kecepatan: ${String.format("%.2f", speed)}x (${action.gestures.size} gestur)"
                })
                bodyContainer.addView(spdRow)
                val delF = addNumberRow(bodyContainer, "Jeda sesudah (ms)", action.delayAfterMs.toString())
                addSaveActionBtn {
                    saveEdited {
                        action.copy(
                            speed = speed,
                            delayAfterMs = (delF.text.toString().toLongOrNull() ?: action.delayAfterMs).coerceAtLeast(0L)
                        )
                    }
                }
            }

            is RestartAction, is StopAction -> {
                bodyContainer.addView(TextView(context).apply {
                    text = "Aksi kontrol alur (dapat diatur peluang Random Event di atas)."
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 11.5f
                })
                addSaveActionBtn {
                    saveEdited { cur -> cur }
                }
            }
        }
    }

    private fun addPickTargetBtn(label: String, onClick: () -> Unit) {
        bodyContainer.addView(makeBtn(label, "#0F766E", onClick = onClick).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        })
    }

    private fun addBranchActionsControls(
        parentId: String,
        slot: Slot,
        children: List<Action>,
        accentBgHex: String
    ) {
        // Show existing branch actions with 1-tap 🎯 target positioning & ✎ edit
        if (children.isNotEmpty()) {
            children.forEachIndexed { idx, child ->
                val row = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(7), dp(5), dp(7), dp(5))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1E293B"))
                        cornerRadius = dp(8).toFloat()
                    }
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(4)
                    }
                }
                row.addView(TextView(context).apply {
                    text = "${idx + 1}. ${child.kind.label} • ${child.summary()}"
                    setTextColor(Color.parseColor("#E2E8F0"))
                    textSize = 10.5f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                })
                if (isPositionable(child)) {
                    row.addView(makeSmallIconBtn("🎯", "#0F766E") {
                        onPickTargetOnScreen(child.id)
                    })
                }
                row.addView(makeSmallIconBtn("✎", "#0284C7") {
                    editingActionId = child.id
                    rebuildBody()
                })
                row.addView(makeSmallIconBtn("✕", "#991B1B", marginEndDp = 0) {
                    onDeleteAction(child.id)
                })
                bodyContainer.addView(row)
            }
        }

        val btnRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        btnRow.addView(makeBtn("+ 🎯 Target Klik", "#0F766E", marginEndDp = 5) {
            onAddBranchClickTargetOnScreen(parentId, slot)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.15f).apply { marginEnd = dp(5) })
        btnRow.addView(makeBtn("+ Aksi Lain (${children.size})", accentBgHex) {
            addPickerRef = ContainerRef(parentId, slot)
            rebuildBody()
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bodyContainer.addView(btnRow)
    }

    private fun addBranchModePicker(title: String, initial: NotFoundMode, onSelect: (NotFoundMode) -> Unit) {
        bodyContainer.addView(TextView(context).apply {
            text = title
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10.5f
            setPadding(0, dp(2), 0, dp(4))
        })
        var current = initial
        val btns = mutableListOf<Pair<NotFoundMode, TextView>>()
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        }
        val shortLabels = mapOf(
            NotFoundMode.CONTINUE to "Lanjut",
            NotFoundMode.RESTART to "Ulangi",
            NotFoundMode.STOP to "Stop"
        )
        NotFoundMode.entries.forEachIndexed { idx, mode ->
            val b = makeBtn(shortLabels[mode] ?: mode.label, if (mode == current) "#0284C7" else "#1E293B", marginEndDp = 0) {
                current = mode
                onSelect(mode)
                btns.forEach { (m, tv) ->
                    (tv.background as? GradientDrawable)?.setColor(Color.parseColor(if (m == current) "#0284C7" else "#1E293B"))
                }
            }
            btns += mode to b
            row.addView(b, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (idx < NotFoundMode.entries.lastIndex) dp(4) else 0
            })
        }
        bodyContainer.addView(row)
    }

    private fun addSaveActionBtn(onClick: () -> Unit) {
        bodyContainer.addView(makeBtn("Simpan Perubahan Aksi", "#0D9488", onClick = onClick).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
                bottomMargin = dp(4)
            }
        })
    }

    private fun buildQuickImageSwapView(action: ImageAction) {
        val nav = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        nav.addView(makeBtn("← Kembali", "#334155", marginEndDp = 8) {
            quickImageSwapActionId = null
            rebuildBody()
        })
        nav.addView(TextView(context).apply {
            text = "Ganti Gambar Target"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
        })
        bodyContainer.addView(nav)

        val quickBtns = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        quickBtns.addView(makeBtn("✂ Crop Baru", "#0F766E", marginEndDp = 6) {
            quickImageSwapActionId = null
            onCropForImageAction(action.id)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        if (action.imageName.isNotBlank() && store.imageFile(action.imageName).exists()) {
            quickBtns.addView(makeBtn("✂ Potong Ulang", "#0284C7") {
                quickImageSwapActionId = null
                onRecropExistingImage(action.imageName)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        bodyContainer.addView(quickBtns)

        addVisualImageSelector(
            initialSelected = action.imageName,
            onSelect = { chosen ->
                onUpdateAction(action.id) { cur ->
                    if (cur is ImageAction) cur.copy(imageName = chosen) else cur
                }
                quickImageSwapActionId = null
            }
        )
    }

    private fun addVisualImageSelector(
        initialSelected: String,
        onSelect: (String) -> Unit
    ) {
        val allImages = store.images.value
        val folders = store.imageFolders.value
        if (allImages.isEmpty()) {
            bodyContainer.addView(TextView(context).apply {
                text = "Pustaka gambar masih kosong. Ketuk '✂ Crop Baru' untuk mengambil gambar target."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 11f
                setPadding(0, dp(4), 0, dp(6))
            })
            return
        }

        var selectedImg = initialSelected
        var activeFolderFilter: String? = null // null = Semua, "" = Utama, else folder name

        val folderContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        val thumbStripContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        }

        fun renderThumbStrip() {
            thumbStripContainer.removeAllViews()
            val filtered = when (activeFolderFilter) {
                null -> allImages
                "" -> allImages.filter { it.folder.isBlank() }
                else -> allImages.filter { it.folder == activeFolderFilter }
            }
            if (filtered.isEmpty()) {
                thumbStripContainer.addView(TextView(context).apply {
                    text = "Tidak ada gambar di folder ini."
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 10.5f
                    setPadding(0, dp(4), 0, dp(4))
                })
                return
            }
            val hScroll = HorizontalScrollView(context)
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            val cardViews = mutableListOf<Pair<String, LinearLayout>>()
            filtered.forEach { asset ->
                val isSel = asset.name == selectedImg
                val tile = LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(if (isSel) "#134E4A" else "#1E293B"))
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(if (isSel) 2 else 1), Color.parseColor(if (isSel) "#2DD4BF" else "#334155"))
                    }
                    layoutParams = LayoutParams(dp(72), LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
                    setOnClickListener {
                        selectedImg = asset.name
                        cardViews.forEach { (n, v) ->
                            val sel = n == selectedImg
                            (v.background as? GradientDrawable)?.apply {
                                setColor(Color.parseColor(if (sel) "#134E4A" else "#1E293B"))
                                setStroke(dp(if (sel) 2 else 1), Color.parseColor(if (sel) "#2DD4BF" else "#334155"))
                            }
                        }
                        onSelect(asset.name)
                    }
                }
                val bmp = runCatching { BitmapFactory.decodeFile(asset.path) }.getOrNull()
                tile.addView(ImageView(context).apply {
                    if (bmp != null) setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.parseColor("#0B1220"))
                    layoutParams = LayoutParams(dp(58), dp(46)).apply { bottomMargin = dp(3) }
                })
                tile.addView(TextView(context).apply {
                    text = asset.displayName.removeSuffix(".png")
                    setTextColor(Color.WHITE)
                    textSize = 9.5f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                cardViews += asset.name to tile
                row.addView(tile)
            }
            hScroll.addView(row)
            thumbStripContainer.addView(hScroll)
        }

        bodyContainer.addView(sectionLabel("Pilih Gambar (${allImages.size})"))
        if (folders.isNotEmpty()) {
            val fScroll = HorizontalScrollView(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(5) }
            }
            val fRow = LinearLayout(context).apply { orientation = HORIZONTAL }
            val fBtns = mutableListOf<Pair<String?, TextView>>()
            val filterOptions: List<Pair<String?, String>> =
                listOf(null to "Semua", "" to "Utama") + folders.map { it to "📁 $it" }
            filterOptions.forEach { (key, label) ->
                val b = makeBtn(label, if (key == activeFolderFilter) "#0284C7" else "#1E293B", marginEndDp = 4) {
                    activeFolderFilter = key
                    fBtns.forEach { (k, tv) ->
                        (tv.background as? GradientDrawable)?.setColor(
                            Color.parseColor(if (k == activeFolderFilter) "#0284C7" else "#1E293B")
                        )
                    }
                    renderThumbStrip()
                }
                fBtns += key to b
                fRow.addView(b)
            }
            fScroll.addView(fRow)
            folderContainer.addView(fScroll)
            bodyContainer.addView(folderContainer)
        }
        bodyContainer.addView(thumbStripContainer)
        renderThumbStrip()
    }

    private fun sectionLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.parseColor("#2DD4BF"))
        textSize = 11.5f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(2), 0, dp(4))
    }

    private fun dividerLine(): View = View(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        }
        setBackgroundColor(Color.parseColor("#334155"))
    }

    private fun addTextRow(parent: LinearLayout, label: String, initial: String): EditText {
        val row = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        }
        row.addView(TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11f
        })
        val et = EditText(context).apply {
            setText(initial)
            setTextColor(Color.WHITE)
            textSize = 12f
            isSingleLine = true
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(8).toFloat()
            }
        }
        row.addView(et)
        parent.addView(row)
        return et
    }

    private fun addNumberRow(parent: LinearLayout, label: String, initial: String): EditText =
        addNumericRow(parent, label, initial, InputType.TYPE_CLASS_NUMBER)

    private fun addSignedNumberRow(parent: LinearLayout, label: String, initial: String): EditText =
        addNumericRow(parent, label, initial, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)

    private fun addDecimalRow(parent: LinearLayout, label: String, initial: String): EditText =
        addNumericRow(
            parent,
            label,
            initial,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        )

    private fun addNumericRow(parent: LinearLayout, label: String, initial: String, type: Int): EditText {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(5) }
        }
        row.addView(TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        val et = EditText(context).apply {
            setText(initial)
            setTextColor(Color.WHITE)
            textSize = 11.5f
            inputType = type
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LayoutParams(dp(84), LayoutParams.WRAP_CONTENT)
        }
        row.addView(et)
        parent.addView(row)
        return et
    }

    private fun makeTabBtn(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 11.5f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#1E293B"))
            cornerRadius = dp(10).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun makeBtn(label: String, bgHex: String, marginEndDp: Int = 0, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            cornerRadius = dp(10).toFloat()
        }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(marginEndDp)
        }
        setOnClickListener { onClick() }
    }

    private fun makeSmallIconBtn(label: String, bgHex: String, marginEndDp: Int = 3, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = dp(24)
        minHeight = dp(24)
        setPadding(dp(5), dp(3), dp(5), dp(3))
        background = GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            cornerRadius = dp(8).toFloat()
        }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(marginEndDp)
        }
        setOnClickListener { onClick() }
    }
}
