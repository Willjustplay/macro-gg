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
import com.example.data.AccountFailBehavior
import com.example.data.AccountOcrMatchMode
import com.example.data.AccountProfile
import com.example.data.AccountRotationMode
import com.example.data.AccountScrollDirection
import com.example.data.AccountSwitchMethod
import com.example.data.AccountTriggerMode
import com.example.data.Action
import com.example.data.ActionKind
import com.example.data.BannedImageMode
import com.example.data.BranchNavigationMode
import com.example.data.BranchOutcomeAction
import com.example.data.BranchRouterAction
import com.example.data.ClickAction
import com.example.data.ColorAction
import com.example.data.ContainerRef
import com.example.data.ExitAppAction
import com.example.data.ExitAppMode
import com.example.data.FinishMacroAction
import com.example.data.FinishMacroMode
import com.example.data.GoToBranchAction
import com.example.data.ImageAction
import com.example.data.JumpAction
import com.example.data.JumpMode
import com.example.data.LaunchAppAction
import com.example.data.LocalStore
import com.example.data.LongPressAction
import com.example.data.LoopAction
import com.example.data.Macro
import com.example.data.MacroBranch
import com.example.data.MacroSecurity
import com.example.data.NotFoundMode
import com.example.data.RecentsSwipeDirection
import com.example.data.RecordAction
import com.example.data.Region
import com.example.data.ResolutionScaleMode
import com.example.data.ResolutionScaler
import com.example.data.RestartAction
import com.example.data.RunMacroAction
import com.example.data.SelectAccountAction
import com.example.data.Slot
import com.example.data.StopAction
import com.example.data.StuckRecoveryMode
import com.example.data.SwipeAction
import com.example.data.SwitchAccountAction
import com.example.data.TextAction
import com.example.data.TextCompareMode
import com.example.data.TrackInteractionMode
import com.example.data.TrackObjectAction
import com.example.data.WaitAction
import com.example.data.ZoomAction
import com.example.data.actionsForBranch
import com.example.data.branchDisplayName
import com.example.data.branchIdOfAction
import com.example.data.childList
import com.example.data.create
import com.example.data.findAction
import com.example.data.findAnyAction
import com.example.data.isStuckAction
import com.example.data.isSwitchAccountAction
import com.example.data.kind
import com.example.data.moveActionToBranch
import com.example.data.newId
import com.example.data.slots
import com.example.data.summary
import com.example.data.updateAnyAction
import com.example.data.withActionRemoteControl
import kotlin.math.roundToInt

/**
 * Full-featured in-overlay Macro Studio matching the in-app Macro Editor:
 * - Tab "Setelan": switch active macro, name, loop/delay/jitter/randomDelay/maxDuration,
 *   all-device Auto-Scale (ALWAYS unlocked even when macro is locked!), and marker preview toggle.
 * - Tab "Aksi": full main & branch action tree (with branch switcher & move-action-to-branch), add any action kind.
 * - Tab "Anti-Macet": out-of-band Watchdog actions triggered automatically when main macro gets stuck.
 * - Tab "Banned": always-active Banned Image Guard (like Watchdog) blocking clicks onto forbidden images.
 * - Tab "Akun": universal multi-account rotation (OCR Daftar Akun + Auto-Scroll, Clone App, Hybrid).
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
    private val maxFreeAccounts: Int? = null,
    private val isPremium: Boolean = true,
    private val onSwitchMacro: (Macro) -> Unit,
    private val onToggleShowAllMarkers: (Boolean) -> Unit,
    private val onUpdateMacro: (Macro) -> Unit,
    private val onUpdateAutoScale: (enabled: Boolean, srcW: Int, srcH: Int, mode: ResolutionScaleMode) -> Unit,
    private val onAddAction: (ContainerRef, Action, Boolean, String) -> Unit,
    private val onMoveActionToBranch: (actionId: String, targetBranchId: String) -> Unit = { _, _ -> },
    private val onAddSwitchAccountAction: (ContainerRef, Action) -> Unit = { _, _ -> },
    private val onUpdateAction: (String, (Action) -> Action) -> Unit,
    private val onMoveAction: (String, Int) -> Unit,
    private val onDeleteAction: (String) -> Unit,
    private val onCropForImageAction: (actionId: String) -> Unit,
    private val onCropExtraFrameForTrackAction: (actionId: String) -> Unit,
    private val onRecropExistingImage: (imageName: String) -> Unit,
    private val onAddNewCropImageAction: () -> Unit,
    private val onCropAddBannedImage: () -> Unit = {},
    private val onPickTargetOnScreen: (actionId: String) -> Unit,
    private val onPickRegionOnScreen: (actionId: String) -> Unit,
    private val onAddBranchClickTargetOnScreen: (parentId: String, slot: Slot, isStuckBranch: Boolean) -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * d).toInt()

    private var activeTab = initialTab // 0 = Setelan, 1 = Aksi, 2 = Anti-Macet, 3 = Banned, 4 = Akun
    private var selectedBranchId: String = "" // "" = Jalur Utama
    private var showCreateBranchInline: Boolean = false
    private var moveBranchActionId: String? = null
    private var editingActionId: String? = initialEditActionId
    private var quickImageSwapActionId: String? = null
    private var appPickerActionId: String? = null
    private var cloneAppPickerAccountId: String? = null
    private var addPickerRef: ContainerRef? = null
    private var addPickerForStuck: Boolean = false
    private var addPickerForSwitchAccount: Boolean = false
    private var selectedOverlaySetFilter: String =
        if (macro.setId.isNotBlank() && store.getMacroSetById(macro.setId) != null) macro.setId else "__ALL__"

    private val bodyContainer = LinearLayout(context).apply { orientation = VERTICAL }
    private val tabSettingsBtn: TextView
    private val tabActionsBtn: TextView
    private val tabWatchdogBtn: TextView
    private val tabBannedBtn: TextView
    private val tabAccountBtn: TextView
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

        // 5-Tab switcher row: Setel | Aksi | Macet | Ban | Akun
        val tabsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        tabSettingsBtn = makeTabBtn("Setel") {
            activeTab = 0
            clearSubScreens()
            rebuildBody()
        }
        tabActionsBtn = makeTabBtn("Aksi") {
            activeTab = 1
            clearSubScreens()
            rebuildBody()
        }
        tabWatchdogBtn = makeTabBtn("Macet") {
            activeTab = 2
            clearSubScreens()
            rebuildBody()
        }
        tabBannedBtn = makeTabBtn("⛔") {
            activeTab = 3
            clearSubScreens()
            rebuildBody()
        }
        tabAccountBtn = makeTabBtn("👥") {
            activeTab = 4
            clearSubScreens()
            rebuildBody()
        }
        tabsRow.addView(tabSettingsBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(3) })
        tabsRow.addView(tabActionsBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.05f).apply { marginEnd = dp(3) })
        tabsRow.addView(tabWatchdogBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.05f).apply { marginEnd = dp(3) })
        tabsRow.addView(tabBannedBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.9f).apply { marginEnd = dp(3) })
        tabsRow.addView(tabAccountBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.95f))
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

    private fun clearSubScreens() {
        editingActionId = null
        quickImageSwapActionId = null
        appPickerActionId = null
        cloneAppPickerAccountId = null
        addPickerRef = null
        addPickerForStuck = false
        addPickerForSwitchAccount = false
        moveBranchActionId = null
        showCreateBranchInline = false
    }

    fun refreshWithMacro(updated: Macro) {
        macro = updated
        if (selectedBranchId.isNotBlank() && macro.branches.none { it.id == selectedBranchId }) {
            selectedBranchId = ""
        }
        rebuildBody()
    }

    private fun isMacroEffectivelyLocked(m: Macro = macro): Boolean {
        val parentSet = store.getMacroSetById(m.setId)
        return MacroSecurity.isEffectivelyLocked(m, parentSet)
    }

    private fun updateHeader() {
        val parentSet = store.getMacroSetById(macro.setId)
        val effectivelyLocked = MacroSecurity.isEffectivelyLocked(macro, parentSet)
        val lockIcon = if (effectivelyLocked) "🔒 " else ""
        titleView.text = lockIcon + macro.name.ifBlank { "Macro Aktif" }
        val loopStr = if (macro.loopCount <= 0) "Loop ∞" else "Loop ${macro.loopCount}×"
        val recStr = if (macro.autoRecoverStuckSec > 0) " • Macet ${macro.autoRecoverStuckSec}d" else ""
        val banStr = if (macro.bannedGuardEnabled && macro.bannedImages.isNotEmpty()) " • ⛔ ${macro.bannedImages.size}" else ""
        val accStr = if (macro.accountSwitch.enabled) " • 👥 ${macro.accountSwitch.accounts.count { it.enabled }}" else ""
        val extra = buildList {
            if (parentSet != null) add("📦 ${parentSet.name}")
            if (macro.isPassive) add(if (macro.passiveEnabled) "Pasif ON" else "Pasif OFF")
            val now = store.effectiveNowMs()
            (MacroSecurity.remainingTimeLabel(macro, now) ?: parentSet?.let { MacroSecurity.remainingSetTimeLabel(it, now) })
                ?.let { add(it) }
        }.joinToString(" • ").let { if (it.isNotBlank()) " • $it" else "" }
        subtitleView.text = "$loopStr • ${macro.actions.size} aksi$recStr$banStr$accStr$extra"
        tabActionsBtn.text = if (effectivelyLocked) "🔒Aksi" else "Aksi(${macro.actions.size})"
        tabWatchdogBtn.text = if (effectivelyLocked) "🔒Mct" else "Mct(${macro.stuckActions.size})"
        tabBannedBtn.text = if (effectivelyLocked) "🔒Ban" else "⛔${macro.bannedImages.size}"
        tabAccountBtn.text = if (effectivelyLocked) "🔒Akn" else "👥${macro.accountSwitch.accounts.size}"

        styleTab(tabSettingsBtn, activeTab == 0)
        styleTab(tabActionsBtn, activeTab == 1)
        styleTab(tabWatchdogBtn, activeTab == 2, selectedHex = "#C2410C")
        styleTab(tabBannedBtn, activeTab == 3, selectedHex = "#DC2626")
        styleTab(tabAccountBtn, activeTab == 4, selectedHex = "#0284C7")
    }

    private fun styleTab(tv: TextView, selected: Boolean, selectedHex: String = "#0891B2") {
        (tv.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (selected) selectedHex else "#1E293B")
        )
        tv.setTextColor(if (selected) Color.WHITE else Color.parseColor("#94A3B8"))
    }

    private fun rebuildBody() {
        updateHeader()
        bodyContainer.removeAllViews()

        if (isMacroEffectivelyLocked(macro)) {
            if (activeTab == 0) {
                buildMacroSwitcherRow()
                buildPassiveQuickControlsRow()
                buildAutoScaleControls(standaloneSave = true)
            }
            buildLockedOverlayNotice()
            return
        }

        val pickerRef = addPickerRef
        val swapId = quickImageSwapActionId
        val appPickId = appPickerActionId
        val cloneAccId = cloneAppPickerAccountId
        val editId = editingActionId
        val moveId = moveBranchActionId
        when {
            pickerRef != null -> buildAddActionPicker(
                pickerRef,
                forStuck = addPickerForStuck,
                forSwitchAccount = addPickerForSwitchAccount
            )
            moveId != null -> {
                val target = macro.findAnyAction(moveId)
                if (target != null) buildMoveActionToBranchView(target)
                else {
                    moveBranchActionId = null
                    rebuildBody()
                }
            }
            cloneAccId != null -> {
                val acc = macro.accountSwitch.accounts.firstOrNull { it.id == cloneAccId }
                if (acc != null) buildAccountCloneAppPickerView(acc)
                else {
                    cloneAppPickerAccountId = null
                    rebuildBody()
                }
            }
            appPickId != null -> {
                val target = macro.findAnyAction(appPickId)
                if (target is LaunchAppAction || target is ExitAppAction) {
                    buildInstalledAppPickerView(target)
                } else {
                    appPickerActionId = null
                    rebuildBody()
                }
            }
            swapId != null -> {
                val target = macro.findAnyAction(swapId)
                if (target is ImageAction || target is TrackObjectAction) buildQuickImageSwapView(target)
                else {
                    quickImageSwapActionId = null
                    rebuildBody()
                }
            }
            editId != null -> {
                val target = macro.findAnyAction(editId)
                if (target != null) buildActionEditor(target)
                else {
                    editingActionId = null
                    rebuildBody()
                }
            }
            activeTab == 0 -> buildMacroSettingsForm()
            activeTab == 1 -> buildActionList()
            activeTab == 2 -> buildWatchdogTab()
            activeTab == 3 -> buildBannedGuardTab()
            else -> buildAccountSwitchTab()
        }
    }

    private fun filteredOverlayMacros(): List<Macro> {
        val all = store.macros.value
        val sets = store.macroSets.value
        if (sets.isEmpty()) return all
        val setIds = sets.map { it.id }.toHashSet()
        if (selectedOverlaySetFilter != "__ALL__" && selectedOverlaySetFilter != "__UNGROUPED__" && selectedOverlaySetFilter !in setIds) {
            selectedOverlaySetFilter = "__ALL__"
        }
        return when (selectedOverlaySetFilter) {
            "__ALL__" -> all
            "__UNGROUPED__" -> all.filter { it.setId.isBlank() || it.setId !in setIds }
            else -> all.filter { it.setId == selectedOverlaySetFilter }
        }
    }

    private fun buildMacroSwitcherRow() {
        val sets = store.macroSets.value
        if (sets.isNotEmpty()) {
            bodyContainer.addView(sectionLabel("Filter Macro Set"))
            val setScroll = HorizontalScrollView(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(6)
                }
            }
            val setRow = LinearLayout(context).apply { orientation = HORIZONTAL }
            setRow.addView(makeBtn("Semua", if (selectedOverlaySetFilter == "__ALL__") "#0284C7" else "#1E293B", marginEndDp = 5) {
                selectedOverlaySetFilter = "__ALL__"
                rebuildBody()
            })
            for (s in sets) {
                val sel = selectedOverlaySetFilter == s.id
                val prefix = if (s.isLocked) "🔒 " else "📦 "
                setRow.addView(makeBtn((prefix + s.name).take(16), if (sel) "#7C3AED" else "#1E293B", marginEndDp = 5) {
                    selectedOverlaySetFilter = s.id
                    rebuildBody()
                })
            }
            setRow.addView(makeBtn("Umum", if (selectedOverlaySetFilter == "__UNGROUPED__") "#0284C7" else "#1E293B", marginEndDp = 5) {
                selectedOverlaySetFilter = "__UNGROUPED__"
                rebuildBody()
            })
            setScroll.addView(setRow)
            bodyContainer.addView(setScroll)
        }

        val visibleMacros = filteredOverlayMacros()
        if (visibleMacros.size > 1 || (visibleMacros.size == 1 && visibleMacros.first().id != macro.id)) {
            bodyContainer.addView(sectionLabel("Ganti Macro Aktif"))
            val hScroll = HorizontalScrollView(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (m in visibleMacros) {
                val sel = m.id == macro.id
                val locked = isMacroEffectivelyLocked(m)
                val prefix = when {
                    locked -> "🔒 "
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
        val passiveMacros = filteredOverlayMacros().filter { m ->
            val pSet = store.getMacroSetById(m.setId)
            m.isPassive &&
                !MacroSecurity.isTampered(m) &&
                !MacroSecurity.isExpired(m, now) &&
                (pSet == null || (!MacroSecurity.isSetTampered(pSet) && !MacroSecurity.isSetExpired(pSet, now)))
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
        val parentSet = store.getMacroSetById(macro.setId)
        val expLabel = MacroSecurity.remainingTimeLabel(macro, now)
            ?: parentSet?.let { MacroSecurity.remainingSetTimeLabel(it, now) }
        val lines = buildList {
            if (parentSet != null && parentSet.isLocked) {
                add("🔒 Macro ini dikunci oleh Macro Set '${parentSet.name}'.")
            } else {
                add("🔒 Macro ini dikunci dengan sandi.")
            }
            add("Daftar aksi & setelan lain disembunyikan, namun Auto-Scale Layar & Pasif ON/OFF tetap bebas diatur tanpa sandi.")
            if (macro.isPassive) {
                add(
                    if (macro.passiveEnabled) "🔗 Mode Pasif: ON — dapat ditarik oleh macro aktif lain."
                    else "⛔ Mode Pasif: OFF — tidak dapat ditarik (otomatis dilewati/skip)."
                )
            }
            if (expLabel != null) {
                add("⏳ Masa berlaku: $expLabel (${MacroSecurity.formatDeadlineDate(macro.expiresAtEpochMs)})")
            }
            add("Buka kunci melalui aplikasi Wjust macro dengan sandi untuk mengedit daftar aksi atau mengubah status Aktif/Pasif.")
        }.joinToString("\n\n")

        val box = TextView(context).apply {
            text = lines
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11f
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18243E"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#A78BFA"))
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        bodyContainer.addView(box)
    }

    private data class AutoScaleHolders(
        val autoScaleCheck: CheckBox,
        val srcWField: EditText,
        val srcHField: EditText,
        val getMode: () -> ResolutionScaleMode
    )

    /**
     * Reusable all-device Auto-Scale controls panel.
     * Rendered in both unlocked settings AND locked macros (with [standaloneSave] = true, no password required!).
     */
    private fun buildAutoScaleControls(standaloneSave: Boolean): AutoScaleHolders {
        bodyContainer.addView(dividerLine())
        bodyContainer.addView(
            sectionLabel(
                if (standaloneSave) "📐 Auto-Scale Semua Perangkat (Tanpa Sandi)"
                else "📐 Auto-Scale Resolusi Layar (Semua Perangkat)"
            )
        )
        val autoScaleCheck = CheckBox(context).apply {
            text = "Aktifkan Auto-Scale (HP, Tablet, Foldable, Emulator)"
            isChecked = macro.autoScaleResolution
            setTextColor(Color.WHITE)
            textSize = 11.2f
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

        val quickRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(5)
            }
        }
        quickRow.addView(makeBtn("Layar Ini (${screenW}×${screenH})", "#1E293B", marginEndDp = 5) {
            srcWField.setText(screenW.toString())
            srcHField.setText(screenH.toString())
        })
        quickRow.addView(makeBtn("⇄ Putar", "#1E293B", marginEndDp = 0) {
            val curW = srcWField.text.toString().ifBlank { screenW.toString() }
            val curH = srcHField.text.toString().ifBlank { screenH.toString() }
            srcWField.setText(curH)
            srcHField.setText(curW)
        })
        bodyContainer.addView(quickRow)

        // Horizontal preset bar covering Phones, Tablets, Foldables & Emulators
        val presetScroll = HorizontalScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        val presetRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        for (p in ResolutionScaler.DEVICE_PRESETS) {
            presetRow.addView(makeBtn(p.label, "#1E293B", marginEndDp = 4) {
                srcWField.setText(p.width.toString())
                srcHField.setText(p.height.toString())
            })
        }
        presetScroll.addView(presetRow)
        bodyContainer.addView(presetScroll)

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

        val curTransform = ResolutionScaler.forMacro(macro, screenW, screenH)
        bodyContainer.addView(TextView(context).apply {
            text = if (curTransform.isScaling) "Aktif: ${curTransform.describe()}" else "Layar ini: ${screenW}×${screenH}"
            setTextColor(Color.parseColor(if (curTransform.isScaling) "#22D3EE" else "#94A3B8"))
            textSize = 10f
            setPadding(dp(2), 0, dp(2), dp(4))
        })

        if (standaloneSave) {
            val saveScaleRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.END
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(4)
                }
            }
            saveScaleRow.addView(makeBtn("Simpan Auto-Scale", "#0D9488") {
                val sw = (srcWField.text.toString().toIntOrNull() ?: 0).coerceIn(0, 10000)
                val sh = (srcHField.text.toString().toIntOrNull() ?: 0).coerceIn(0, 10000)
                onUpdateAutoScale(autoScaleCheck.isChecked, sw, sh, chosenScaleMode)
            })
            bodyContainer.addView(saveScaleRow)
        }

        return AutoScaleHolders(autoScaleCheck, srcWField, srcHField) { chosenScaleMode }
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

        // Humanizer Engine quick preset switcher
        bodyContainer.addView(dividerLine())
        bodyContainer.addView(sectionLabel("🤖 Humanizer Engine (Anti-Deteksi Alami)"))
        var chosenHumanizer = macro.humanizer
        val hInfoTv = TextView(context).apply {
            text = chosenHumanizer.preset.description
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
            setPadding(dp(2), 0, dp(2), dp(5))
        }
        val hRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
        }
        val hBtns = mutableListOf<Pair<com.example.data.HumanizerPreset, TextView>>()
        com.example.data.HumanizerPreset.entries.forEachIndexed { idx, preset ->
            val activeHex = when (preset) {
                com.example.data.HumanizerPreset.OFF -> "#475569"
                com.example.data.HumanizerPreset.NATURAL -> "#059669"
                com.example.data.HumanizerPreset.STEALTH -> "#0891B2"
                com.example.data.HumanizerPreset.CUSTOM -> "#7C3AED"
            }
            val btn = makeBtn(preset.label, if (preset == chosenHumanizer.preset) activeHex else "#1E293B", marginEndDp = 0) {
                chosenHumanizer = when (preset) {
                    com.example.data.HumanizerPreset.OFF -> chosenHumanizer.copy(preset = preset)
                    com.example.data.HumanizerPreset.NATURAL -> chosenHumanizer.copy(preset = preset)
                    com.example.data.HumanizerPreset.STEALTH -> chosenHumanizer.copy(preset = preset, humanBreakEnabled = true)
                    com.example.data.HumanizerPreset.CUSTOM -> chosenHumanizer.effective().copy(preset = preset)
                }
                hInfoTv.text = preset.description
                hBtns.forEach { (p, tv) ->
                    val selHex = when (p) {
                        com.example.data.HumanizerPreset.OFF -> "#475569"
                        com.example.data.HumanizerPreset.NATURAL -> "#059669"
                        com.example.data.HumanizerPreset.STEALTH -> "#0891B2"
                        com.example.data.HumanizerPreset.CUSTOM -> "#7C3AED"
                    }
                    (tv.background as? GradientDrawable)?.setColor(Color.parseColor(if (p == chosenHumanizer.preset) selHex else "#1E293B"))
                }
            }
            hBtns += preset to btn
            hRow.addView(btn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (idx < com.example.data.HumanizerPreset.entries.lastIndex) dp(4) else 0
            })
        }
        bodyContainer.addView(hRow)
        bodyContainer.addView(hInfoTv)

        // Kontrol Bot Telegram / Discord (ID Makro ON/OFF)
        bodyContainer.addView(dividerLine())
        bodyContainer.addView(sectionLabel("🤖 Kontrol Bot Telegram / Discord"))
        val remoteIdField = addTextRow(bodyContainer, "ID Makro (mis. farm1)", macro.remoteId)
        val remoteEnableCheck = CheckBox(context).apply {
            text = if (macro.isPassive) "Izinkan Bot ON/OFF Makro Pasif ini" else "Izinkan Bot ON/OFF Makro Aktif ini"
            isChecked = macro.remoteControlEnabled
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 11.5f
            setOnCheckedChangeListener { _, checked ->
                if (checked && com.example.data.RemoteIdSanitizer.sanitize(remoteIdField.text.toString()).isEmpty()) {
                    val existing = store.macros.value.filter { it.id != macro.id }.map { it.remoteId }.toSet()
                    remoteIdField.setText(com.example.data.RemoteIdSanitizer.suggestMacroId(macro.name, existing))
                }
            }
        }
        bodyContainer.addView(remoteEnableCheck)

        // Auto-Scale Resolusi Layar (All Devices)
        val scaleHolders = buildAutoScaleControls(standaloneSave = false)

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
            val cleanRemoteId = com.example.data.RemoteIdSanitizer.sanitize(remoteIdField.text.toString())
            val updated = macro.copy(
                name = nameField.text.toString().trim().ifBlank { "Macro Baru" },
                loopCount = if (infiniteCheck.isChecked) 0 else (loopCountField.text.toString().toIntOrNull() ?: 1).coerceAtLeast(1),
                loopDelayMs = (loopDelayField.text.toString().toLongOrNull() ?: macro.loopDelayMs).coerceAtLeast(0L),
                jitterPx = (jitterField.text.toString().toIntOrNull() ?: macro.jitterPx).coerceIn(0, 60),
                randomDelayMs = (randomDelayField.text.toString().toLongOrNull() ?: macro.randomDelayMs).coerceAtLeast(0L),
                maxDurationMin = (maxMinField.text.toString().toIntOrNull() ?: macro.maxDurationMin).coerceIn(0, 1440),
                autoRecoverStuckSec = (stuckSecField.text.toString().toIntOrNull() ?: macro.autoRecoverStuckSec).coerceIn(0, 3600),
                stuckRecoveryMode = chosenRecovery,
                sourceWidth = (scaleHolders.srcWField.text.toString().toIntOrNull() ?: 0).coerceIn(0, 10000),
                sourceHeight = (scaleHolders.srcHField.text.toString().toIntOrNull() ?: 0).coerceIn(0, 10000),
                autoScaleResolution = scaleHolders.autoScaleCheck.isChecked,
                scaleMode = scaleHolders.getMode(),
                humanizer = chosenHumanizer,
                remoteControlEnabled = remoteEnableCheck.isChecked && cleanRemoteId.isNotEmpty(),
                remoteId = cleanRemoteId
            )
            macro = updated
            onUpdateMacro(updated)
            updateHeader()
        })
        bodyContainer.addView(saveRow)
    }

    // ---- TAB 2: Complete Main Action Tree ----

    private fun buildActionList() {
        val branchScroll = HorizontalScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        val branchRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val isMainSel = selectedBranchId.isBlank()
        branchRow.addView(makeBtn(
            "🟢 Utama (${macro.actions.size})",
            if (isMainSel) "#0D9488" else "#1E293B",
            marginEndDp = 4
        ) {
            selectedBranchId = ""
            showCreateBranchInline = false
            rebuildBody()
        })
        macro.branches.forEach { b ->
            val isSel = selectedBranchId == b.id
            branchRow.addView(makeBtn(
                "🔀 ${b.name} (${b.actions.size})",
                if (isSel) "#D97706" else "#1E293B",
                marginEndDp = 4
            ) {
                selectedBranchId = b.id
                showCreateBranchInline = false
                rebuildBody()
            })
        }
        branchRow.addView(makeBtn(
            if (showCreateBranchInline) "Batal" else "+ Cabang",
            if (showCreateBranchInline) "#475569" else "#0369A1",
            marginEndDp = 0
        ) {
            showCreateBranchInline = !showCreateBranchInline
            rebuildBody()
        })
        branchScroll.addView(branchRow)
        bodyContainer.addView(branchScroll)

        if (showCreateBranchInline) {
            val createBox = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#111C30"))
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), Color.parseColor("#0284C7"))
                }
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(6)
                }
            }
            val nameEt = EditText(context).apply {
                hint = "Nama cabang baru..."
                setText("Cabang ${macro.branches.size + 1}")
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#64748B"))
                textSize = 11.5f
                isSingleLine = true
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = dp(6).toFloat()
                }
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
            }
            createBox.addView(nameEt)
            createBox.addView(makeBtn("Buat", "#059669") {
                val bName = nameEt.text.toString().trim().ifBlank { "Cabang ${macro.branches.size + 1}" }.take(36)
                val colors = listOf("#F59E0B", "#06B6D4", "#10B981", "#EC4899", "#8B5CF6", "#EF4444")
                val created = MacroBranch(
                    id = newId(),
                    name = bName,
                    colorHex = colors[macro.branches.size % colors.size]
                )
                val updated = macro.copy(branches = macro.branches + created)
                macro = updated
                selectedBranchId = created.id
                showCreateBranchInline = false
                onUpdateMacro(updated)
                rebuildBody()
            })
            bodyContainer.addView(createBox)
        }

        val currentBranch = if (selectedBranchId.isBlank()) null else macro.branches.firstOrNull { it.id == selectedBranchId }
        if (currentBranch != null) {
            val bInfo = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2A1D08"))
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), Color.parseColor("#D97706"))
                }
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(6)
                }
            }
            bInfo.addView(TextView(context).apply {
                text = "🔀 Cabang: ${currentBranch.name} (${currentBranch.actions.size} aksi)"
                setTextColor(Color.parseColor("#FDE68A"))
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            })
            bInfo.addView(makeSmallIconBtn("Hapus", "#991B1B", marginEndDp = 0) {
                val updated = macro.copy(branches = macro.branches.filterNot { it.id == currentBranch.id })
                macro = updated
                selectedBranchId = ""
                onUpdateMacro(updated)
                rebuildBody()
            })
            bodyContainer.addView(bInfo)
        }

        val topBtns = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        topBtns.addView(makeBtn(
            if (currentBranch != null) "+ Aksi ke ${currentBranch.name}" else "+ Tambah Aksi",
            "#1D4ED8",
            marginEndDp = 6
        ) {
            addPickerForStuck = false
            addPickerForSwitchAccount = false
            addPickerRef = ContainerRef.ROOT
            rebuildBody()
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        topBtns.addView(makeBtn("✂ Crop Img", "#0F766E") {
            onAddNewCropImageAction()
        })
        bodyContainer.addView(topBtns)

        val branchActions = macro.actionsForBranch(selectedBranchId)
        if (branchActions.isEmpty()) {
            bodyContainer.addView(TextView(context).apply {
                text = if (currentBranch != null) {
                    "Belum ada aksi di cabang '${currentBranch.name}'. Ketuk '+ Aksi' atau pindahkan aksi dari cabang lain dengan tombol ⇄."
                } else {
                    "Belum ada aksi. Ketuk '+ Tambah Aksi' atau '✂ Crop Img'."
                }
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 11.5f
                setPadding(dp(4), dp(12), dp(4), dp(12))
            })
        } else {
            branchActions.forEachIndexed { idx, action ->
                bodyContainer.addView(buildActionCard(idx, branchActions.size, action, depth = 0, isStuckTree = false))
            }
        }
    }

    // ---- TAB 3: Out-of-Band Anti-Macet (Watchdog) Actions ----

    private fun buildWatchdogTab() {
        val infoBox = TextView(context).apply {
            text = "🛡 Aksi di luar aksi utama. Otomatis aktif jika aksi utama macet melebihi batas detik."
            setTextColor(Color.parseColor("#FDBA74"))
            textSize = 10.5f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A1508"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#EA580C"))
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        bodyContainer.addView(infoBox)

        val watchdogEnabled = macro.autoRecoverStuckSec > 0 && macro.stuckRecoveryMode == StuckRecoveryMode.RUN_WATCHDOG_ACTIONS
        val enableCheck = CheckBox(context).apply {
            text = "Aktifkan Watchdog Anti-Macet"
            isChecked = watchdogEnabled
            setTextColor(Color.WHITE)
            textSize = 11.5f
        }
        bodyContainer.addView(enableCheck)

        val secField = addNumberRow(
            bodyContainer,
            "Deteksi macet setelah (detik)",
            if (macro.autoRecoverStuckSec > 0) macro.autoRecoverStuckSec.toString() else "60"
        )
        val restartAfterCheck = CheckBox(context).apply {
            text = "Setelah selesai, ulangi aksi utama dari awal"
            isChecked = macro.stuckRestartAfter
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11f
        }
        bodyContainer.addView(restartAfterCheck)

        val cfgBtns = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
                topMargin = dp(2)
            }
        }
        cfgBtns.addView(makeBtn("Simpan Watchdog", "#C2410C", marginEndDp = 5) {
            val sec = (secField.text.toString().toIntOrNull() ?: 60).coerceIn(5, 3600)
            val updated = macro.copy(
                autoRecoverStuckSec = if (enableCheck.isChecked) sec else 0,
                stuckRecoveryMode = if (enableCheck.isChecked) StuckRecoveryMode.RUN_WATCHDOG_ACTIONS else macro.stuckRecoveryMode,
                stuckRestartAfter = restartAfterCheck.isChecked
            )
            macro = updated
            onUpdateMacro(updated)
            updateHeader()
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
        cfgBtns.addView(makeBtn("+ Aksi Anti-Macet", "#1D4ED8", marginEndDp = 0) {
            addPickerForStuck = true
            addPickerForSwitchAccount = false
            addPickerRef = ContainerRef.ROOT
            rebuildBody()
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bodyContainer.addView(cfgBtns)

        bodyContainer.addView(makeBtn("⚡ Template: Keluar & Masuk Ulang Aplikasi", "#0F766E", marginEndDp = 0) {
            val launchAct = LaunchAppAction(delayAfterMs = 8000L)
            val tpl = listOf(
                ExitAppAction(delayAfterMs = 1500L),
                WaitAction(durationMs = 1500L, delayAfterMs = 0L),
                launchAct
            )
            val sec = (secField.text.toString().toIntOrNull() ?: 60).coerceIn(5, 3600)
            val updated = macro.copy(
                stuckActions = macro.stuckActions + tpl,
                stuckRecoveryMode = StuckRecoveryMode.RUN_WATCHDOG_ACTIONS,
                autoRecoverStuckSec = if (macro.autoRecoverStuckSec <= 0) sec else macro.autoRecoverStuckSec
            )
            macro = updated
            onUpdateMacro(updated)
            editingActionId = launchAct.id
            rebuildBody()
        }.apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        })

        if (macro.stuckActions.isEmpty()) {
            bodyContainer.addView(TextView(context).apply {
                text = "Belum ada aksi Anti-Macet. Tambahkan aksi (mis. Keluar Aplikasi → Masuk Aplikasi) agar otomatis berjalan saat aksi utama macet."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 11f
                setPadding(dp(4), dp(8), dp(4), dp(8))
            })
        } else {
            macro.stuckActions.forEachIndexed { idx, action ->
                bodyContainer.addView(buildActionCard(idx, macro.stuckActions.size, action, depth = 0, isStuckTree = true))
            }
        }
    }

    // ---- TAB 4: Always-Active Banned Image Guard (like Watchdog) ----

    private fun buildBannedGuardTab() {
        val infoBox = TextView(context).apply {
            text = "⛔ Guard Banned Gambar (Selalu Aktif seperti Watchdog). Semua aksi klik (Klik, Tahan, Gambar, Teks, Warna, Rekaman) otomatis DIBLOKIR jika mengenai gambar terlarang di bawah."
            setTextColor(Color.parseColor("#FCA5A5"))
            textSize = 10.5f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A0B0B"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#DC2626"))
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        bodyContainer.addView(infoBox)

        val enableCheck = CheckBox(context).apply {
            text = "Aktifkan Guard Banned Gambar (Selalu Aktif)"
            isChecked = macro.bannedGuardEnabled
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setOnCheckedChangeListener { _, checked ->
                val updated = macro.copy(bannedGuardEnabled = checked)
                macro = updated
                onUpdateMacro(updated)
                updateHeader()
            }
        }
        bodyContainer.addView(enableCheck)

        var chosenMode = macro.bannedMode
        val modeRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
                bottomMargin = dp(4)
            }
        }
        val modeDesc = TextView(context).apply {
            text = chosenMode.description
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 10f
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        lateinit var btnAvoid: TextView
        lateinit var btnBlockAll: TextView
        fun refreshModeBtns() {
            (btnAvoid.background as? GradientDrawable)?.setColor(
                Color.parseColor(if (chosenMode == BannedImageMode.AVOID_MATCH_AREA) "#DC2626" else "#1E293B")
            )
            (btnBlockAll.background as? GradientDrawable)?.setColor(
                Color.parseColor(if (chosenMode == BannedImageMode.BLOCK_IF_PRESENT) "#DC2626" else "#1E293B")
            )
            modeDesc.text = chosenMode.description
        }
        btnAvoid = makeBtn(BannedImageMode.AVOID_MATCH_AREA.shortLabel, if (chosenMode == BannedImageMode.AVOID_MATCH_AREA) "#DC2626" else "#1E293B", marginEndDp = 5) {
            chosenMode = BannedImageMode.AVOID_MATCH_AREA
            refreshModeBtns()
        }
        btnBlockAll = makeBtn(BannedImageMode.BLOCK_IF_PRESENT.shortLabel, if (chosenMode == BannedImageMode.BLOCK_IF_PRESENT) "#DC2626" else "#1E293B") {
            chosenMode = BannedImageMode.BLOCK_IF_PRESENT
            refreshModeBtns()
        }
        modeRow.addView(btnAvoid, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
        modeRow.addView(btnBlockAll, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bodyContainer.addView(modeRow)
        bodyContainer.addView(modeDesc)

        var thresholdPct = (macro.bannedThreshold * 100).roundToInt().coerceIn(50, 99)
        val thrRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        val thrLabel = TextView(context).apply {
            text = "Kemiripan Banned: $thresholdPct%"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        thrRow.addView(thrLabel)
        thrRow.addView(makeSmallIconBtn("-5%", "#334155", marginEndDp = 4) {
            thresholdPct = (thresholdPct - 5).coerceIn(50, 99)
            thrLabel.text = "Kemiripan Banned: $thresholdPct%"
        })
        thrRow.addView(makeSmallIconBtn("+5%", "#334155", marginEndDp = 0) {
            thresholdPct = (thresholdPct + 5).coerceIn(50, 99)
            thrLabel.text = "Kemiripan Banned: $thresholdPct%"
        })
        bodyContainer.addView(thrRow)

        val radiusField = addNumberRow(
            bodyContainer,
            "Radius blokir aman (px)",
            macro.bannedAvoidRadiusPx.toString()
        )

        val actionBtns = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
                bottomMargin = dp(8)
            }
        }
        actionBtns.addView(makeBtn("Simpan Guard", "#DC2626", marginEndDp = 5) {
            val radius = (radiusField.text.toString().toIntOrNull() ?: macro.bannedAvoidRadiusPx).coerceIn(0, 500)
            val updated = macro.copy(
                bannedGuardEnabled = enableCheck.isChecked,
                bannedMode = chosenMode,
                bannedThreshold = thresholdPct / 100f,
                bannedAvoidRadiusPx = radius
            )
            macro = updated
            onUpdateMacro(updated)
            updateHeader()
            Toast.makeText(context, "Guard Banned Gambar disimpan.", Toast.LENGTH_SHORT).show()
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
        actionBtns.addView(makeBtn("✂ Crop Banned", "#0F766E") {
            onCropAddBannedImage()
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bodyContainer.addView(actionBtns)

        // Active Banned Images quick chips
        if (macro.bannedImages.isNotEmpty()) {
            bodyContainer.addView(sectionLabel("Gambar Diblokir (${macro.bannedImages.size}) — ketuk untuk hapus"))
            val activeScroll = HorizontalScrollView(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(6)
                }
            }
            val activeRow = LinearLayout(context).apply { orientation = HORIZONTAL }
            macro.bannedImages.forEach { bName ->
                activeRow.addView(makeBtn(
                    "⛔ ${bName.substringAfterLast('/').removeSuffix(".png")} ✕",
                    "#991B1B",
                    marginEndDp = 4
                ) {
                    val nextList = macro.bannedImages.filterNot { it == bName }
                    val updated = macro.copy(bannedImages = nextList)
                    macro = updated
                    onUpdateMacro(updated)
                    rebuildBody()
                })
            }
            activeScroll.addView(activeRow)
            bodyContainer.addView(activeScroll)
        }

        // Visual library toggle strip to add/remove banned images
        val allImages = store.images.value
        bodyContainer.addView(sectionLabel("Pilih / Lepas Gambar Terlarang (${allImages.size})"))
        if (allImages.isEmpty()) {
            bodyContainer.addView(TextView(context).apply {
                text = "Pustaka gambar masih kosong. Ketuk '✂ Crop Banned' untuk langsung memotong gambar yang ingin diblokir dari layar."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 11f
                setPadding(dp(4), dp(6), dp(4), dp(6))
            })
        } else {
            val hScroll = HorizontalScrollView(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(4)
                }
            }
            val strip = LinearLayout(context).apply { orientation = HORIZONTAL }
            allImages.forEach { asset ->
                val isBanned = asset.name in macro.bannedImages
                val tile = LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(if (isBanned) "#450A0A" else "#1E293B"))
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(if (isBanned) 2 else 1), Color.parseColor(if (isBanned) "#EF4444" else "#334155"))
                    }
                    layoutParams = LayoutParams(dp(74), LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
                    setOnClickListener {
                        val nextList = if (isBanned) {
                            macro.bannedImages.filterNot { it == asset.name }
                        } else {
                            (macro.bannedImages + asset.name).distinct()
                        }
                        val updated = macro.copy(
                            bannedGuardEnabled = if (!isBanned) true else macro.bannedGuardEnabled,
                            bannedImages = nextList
                        )
                        macro = updated
                        onUpdateMacro(updated)
                        rebuildBody()
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
                    text = (if (isBanned) "⛔ " else "") + asset.displayName.removeSuffix(".png")
                    setTextColor(if (isBanned) Color.parseColor("#FCA5A5") else Color.WHITE)
                    textSize = 9.5f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                strip.addView(tile)
            }
            hScroll.addView(strip)
            bodyContainer.addView(hScroll)
        }
    }

    // ---- TAB 5: Universal Multi-Account Switcher (OCR + Auto-Scroll / Clone App / Hybrid) ----

    private fun buildAccountSwitchTab() {
        val cfg = macro.accountSwitch
        val infoBox = TextView(context).apply {
            text = "👥 Ganti Akun Universal (Semua Game, Tanpa Root). Mendukung OCR Daftar Akun + Auto-Scroll, Ganti Clone App / Dual App, atau Hybrid."
            setTextColor(Color.parseColor("#7DD3FC"))
            textSize = 10.5f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0B2038"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#0284C7"))
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        bodyContainer.addView(infoBox)

        val enableCheck = CheckBox(context).apply {
            text = "Aktifkan Rotasi Multi-Akun"
            isChecked = cfg.enabled
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setOnCheckedChangeListener { _, checked ->
                val updated = macro.copy(accountSwitch = macro.accountSwitch.copy(enabled = checked))
                macro = updated
                onUpdateMacro(updated)
                updateHeader()
            }
        }
        bodyContainer.addView(enableCheck)

        // Method selector
        var chosenMethod = cfg.method
        bodyContainer.addView(sectionLabel("Metode Ganti Akun"))
        val methodBtns = mutableListOf<Pair<AccountSwitchMethod, TextView>>()
        AccountSwitchMethod.entries.chunked(2).forEach { pair ->
            val r = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
            }
            pair.forEachIndexed { idx, m ->
                val tv = makeBtn(m.shortLabel, if (m == chosenMethod) "#0284C7" else "#1E293B", marginEndDp = 0) {
                    chosenMethod = m
                    methodBtns.forEach { (item, v) ->
                        (v.background as? GradientDrawable)?.setColor(Color.parseColor(if (item == chosenMethod) "#0284C7" else "#1E293B"))
                    }
                }
                methodBtns += m to tv
                r.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = if (idx < pair.lastIndex) dp(4) else 0
                })
            }
            bodyContainer.addView(r)
        }

        // Trigger selector
        var chosenTrigger = cfg.triggerMode
        bodyContainer.addView(sectionLabel("Kapan Pindah Akun"))
        val trigBtns = mutableListOf<Pair<AccountTriggerMode, TextView>>()
        AccountTriggerMode.entries.chunked(2).forEach { pair ->
            val r = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
            }
            pair.forEachIndexed { idx, t ->
                val tv = makeBtn(t.shortLabel, if (t == chosenTrigger) "#0891B2" else "#1E293B", marginEndDp = 0) {
                    chosenTrigger = t
                    trigBtns.forEach { (item, v) ->
                        (v.background as? GradientDrawable)?.setColor(Color.parseColor(if (item == chosenTrigger) "#0891B2" else "#1E293B"))
                    }
                }
                trigBtns += t to tv
                r.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = if (idx < pair.lastIndex) dp(4) else 0
                })
            }
            bodyContainer.addView(r)
        }

        val loopsF = addNumberRow(bodyContainer, "Loop per akun", cfg.loopsPerAccount.toString())
        val minsF = addNumberRow(bodyContainer, "Menit per akun", cfg.minutesPerAccount.toString())
        val switchStartCheck = CheckBox(context).apply {
            text = "Pilih akun pertama saat macro mulai"
            isChecked = cfg.switchOnStart
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11f
        }
        bodyContainer.addView(switchStartCheck)

        bodyContainer.addView(makeBtn("Simpan Setelan Akun", "#0284C7", marginEndDp = 0) {
            val updated = macro.copy(
                accountSwitch = macro.accountSwitch.copy(
                    enabled = enableCheck.isChecked,
                    method = chosenMethod,
                    triggerMode = chosenTrigger,
                    loopsPerAccount = (loopsF.text.toString().toIntOrNull() ?: cfg.loopsPerAccount).coerceIn(1, 9999),
                    minutesPerAccount = (minsF.text.toString().toIntOrNull() ?: cfg.minutesPerAccount).coerceIn(1, 1440),
                    switchOnStart = switchStartCheck.isChecked
                )
            )
            macro = updated
            onUpdateMacro(updated)
            updateHeader()
            Toast.makeText(context, "Setelan Multi-Akun disimpan.", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
                bottomMargin = dp(8)
            }
        })

        // Account Profiles List + Quick Add + PRISM Auto-Import
        bodyContainer.addView(dividerLine())
        val freeLimitNote = if (maxFreeAccounts != null && !isPremium) " (Gratis maks $maxFreeAccounts)" else ""
        bodyContainer.addView(sectionLabel("Daftar Akun (${cfg.accounts.size})$freeLimitNote"))

        val detectedPrism = PrismClonerBridge.discoverInstalledPrismRootSlots(context)
        if (detectedPrism.isNotEmpty()) {
            bodyContainer.addView(makeBtn("🔗 Impor ${detectedPrism.size} Slot PRISM Root", "#0E7490", marginEndDp = 0) {
                val existingPkgs = macro.accountSwitch.accounts.map { it.packageName }.toHashSet()
                val candidates = detectedPrism.filter { it.stubPackage !in existingPkgs }
                val allowed = if (maxFreeAccounts != null && !isPremium) {
                    (maxFreeAccounts - macro.accountSwitch.accounts.size).coerceAtLeast(0)
                } else candidates.size
                if (allowed == 0 && candidates.isNotEmpty()) {
                    Toast.makeText(
                        context,
                        "Versi gratis mendukung hingga $maxFreeAccounts akun. Upgrade ke PRO di aplikasi utama.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@makeBtn
                }
                val toAdd = candidates.take(allowed).mapIndexed { idx, slot ->
                    val num = slot.slotOrder.takeIf { it > 0 } ?: (macro.accountSwitch.accounts.size + idx + 1)
                    val lbl = slot.displayLabel.ifBlank { "PRISM #$num" }
                    AccountProfile(
                        label = lbl,
                        packageName = slot.stubPackage,
                        appLabel = lbl,
                        prismTargetPackage = slot.sourcePackage,
                        prismSlotOrder = slot.slotOrder,
                        prismProfileId = slot.profileId,
                        enabled = true
                    )
                }
                val updated = macro.copy(
                    accountSwitch = macro.accountSwitch.copy(
                        enabled = true,
                        method = if (macro.accountSwitch.method == AccountSwitchMethod.OCR_LIST) AccountSwitchMethod.PRISM_ROOT else macro.accountSwitch.method,
                        accounts = macro.accountSwitch.accounts + toAdd
                    )
                )
                macro = updated
                onUpdateMacro(updated)
                Toast.makeText(context, "${toAdd.size} slot PRISM Root ditambahkan.", Toast.LENGTH_SHORT).show()
                rebuildBody()
            }.apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(6)
                }
            })
        }

        val quickLabelEt = addTextRow(bodyContainer, "Nama akun baru (mis. Farm 1)", "")
        val quickKwEt = addTextRow(bodyContainer, "Kata kunci OCR di layar (nama/email/server)", "")
        bodyContainer.addView(makeBtn("+ Tambah Akun", "#059669", marginEndDp = 0) {
            if (maxFreeAccounts != null && !isPremium && macro.accountSwitch.accounts.size >= maxFreeAccounts) {
                Toast.makeText(
                    context,
                    "Versi gratis mendukung hingga $maxFreeAccounts akun. Upgrade ke PRO di aplikasi utama untuk akun tanpa batas.",
                    Toast.LENGTH_LONG
                ).show()
                return@makeBtn
            }
            val nextNum = macro.accountSwitch.accounts.size + 1
            val lbl = quickLabelEt.text.toString().trim().ifBlank { "Akun $nextNum" }.take(32)
            val kw = quickKwEt.text.toString().trim()
            val added = AccountProfile(label = lbl, ocrKeyword = kw)
            val updated = macro.copy(
                accountSwitch = macro.accountSwitch.copy(
                    enabled = true,
                    accounts = macro.accountSwitch.accounts + added
                )
            )
            macro = updated
            onUpdateMacro(updated)
            rebuildBody()
        }.apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        })

        if (cfg.accounts.isEmpty()) {
            bodyContainer.addView(TextView(context).apply {
                text = if (cfg.method == AccountSwitchMethod.PRISM_ROOT) {
                    "Mode PRISM Root otomatis mengirim SWITCH_NEXT ke PRISM Cloner walau daftar akun kosong, atau ketuk 'Impor Slot PRISM Root' di atas."
                } else {
                    "Belum ada akun. Tambahkan minimal 2 akun di atas (atau kelola lengkap dengan gambar fallback & cooldown di Editor Aplikasi)."
                }
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 10.5f
                setPadding(dp(2), dp(2), dp(2), dp(6))
            })
        } else {
            cfg.accounts.forEachIndexed { idx, acc ->
                val accRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1E293B"))
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), Color.parseColor(if (acc.enabled) "#0284C7" else "#334155"))
                    }
                    alpha = if (acc.enabled) 1f else 0.55f
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(4)
                    }
                }
                val infoCol = LinearLayout(context).apply {
                    orientation = VERTICAL
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                }
                infoCol.addView(TextView(context).apply {
                    text = "${idx + 1}. ${acc.label}"
                    setTextColor(Color.WHITE)
                    textSize = 11.5f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                val sub = buildList {
                    if (acc.ocrKeyword.isNotBlank()) add("OCR: \"${acc.ocrKeyword}\"")
                    if (PrismProtocol.isPrismStubPackageName(acc.packageName) || acc.prismSlotOrder > 0) {
                        val sNum = acc.prismSlotOrder.takeIf { it > 0 } ?: PrismProtocol.parseSlotNumberFromPackage(acc.packageName)
                        add(if (sNum > 0) "🔀 PRISM #%02d".format(sNum) else "🔀 PRISM")
                    }
                    if (acc.packageName.isNotBlank()) add("App: ${acc.appLabel.ifBlank { acc.packageName }}")
                    if (acc.cooldownMinutes > 0) add("CD ${acc.cooldownMinutes}m")
                }.joinToString(" • ").ifBlank { "Ketuk 📱 untuk pilih Clone / PRISM App" }
                infoCol.addView(TextView(context).apply {
                    text = sub
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 9.5f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                accRow.addView(infoCol)
                accRow.addView(makeSmallIconBtn("📱", "#0369A1") {
                    cloneAppPickerAccountId = acc.id
                    rebuildBody()
                })
                accRow.addView(makeSmallIconBtn(if (acc.enabled) "ON" else "OFF", if (acc.enabled) "#059669" else "#475569") {
                    val updatedList = macro.accountSwitch.accounts.map {
                        if (it.id == acc.id) it.copy(enabled = !it.enabled) else it
                    }
                    val updated = macro.copy(accountSwitch = macro.accountSwitch.copy(accounts = updatedList))
                    macro = updated
                    onUpdateMacro(updated)
                    rebuildBody()
                })
                accRow.addView(makeSmallIconBtn("✕", "#991B1B", marginEndDp = 0) {
                    val updatedList = macro.accountSwitch.accounts.filterNot { it.id == acc.id }
                    val updated = macro.copy(accountSwitch = macro.accountSwitch.copy(accounts = updatedList))
                    macro = updated
                    onUpdateMacro(updated)
                    rebuildBody()
                })
                bodyContainer.addView(accRow)
            }
        }

        // Switch Actions Steps + Templates
        bodyContainer.addView(dividerLine())
        bodyContainer.addView(sectionLabel("Urutan Aksi Ganti Akun (${cfg.switchActions.size})"))

        bodyContainer.addView(makeBtn("⚡ Template PRISM Root (Anti-BAL)", "#0284C7", marginEndDp = 0) {
            val selectAct = SelectAccountAction(
                methodOverride = AccountSwitchMethod.PRISM_ROOT,
                exitPreviousApp = false,
                launchWaitMs = 6000L,
                prismUseSwitchNext = macro.accountSwitch.accounts.isEmpty(),
                prismTargetPackage = detectedPrism.firstOrNull()?.sourcePackage.orEmpty(),
                delayAfterMs = 1000L
            )
            val updated = macro.copy(
                accountSwitch = macro.accountSwitch.copy(
                    enabled = true,
                    method = AccountSwitchMethod.PRISM_ROOT,
                    switchActions = macro.accountSwitch.switchActions + listOf(selectAct)
                )
            )
            macro = updated
            onUpdateMacro(updated)
            editingActionId = selectAct.id
            rebuildBody()
        }.apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
        })

        val tplRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        }
        tplRow.addView(makeBtn("⚡ Template OCR", "#0F766E", marginEndDp = 4) {
            val selectAct = SelectAccountAction(
                methodOverride = AccountSwitchMethod.OCR_LIST,
                maxScrolls = 6,
                reverseOnEnd = true,
                delayAfterMs = 2000L
            )
            val updated = macro.copy(
                accountSwitch = macro.accountSwitch.copy(
                    enabled = true,
                    method = AccountSwitchMethod.OCR_LIST,
                    switchActions = macro.accountSwitch.switchActions + listOf(
                        selectAct,
                        WaitAction(durationMs = 4000L, delayAfterMs = 0L)
                    )
                )
            )
            macro = updated
            onUpdateMacro(updated)
            editingActionId = selectAct.id
            rebuildBody()
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        tplRow.addView(makeBtn("⚡ Template Clone", "#7C3AED", marginEndDp = 0) {
            val selectAct = SelectAccountAction(
                methodOverride = AccountSwitchMethod.CLONE_APP,
                exitPreviousApp = true,
                exitWaitMs = 1500L,
                launchWaitMs = 7000L,
                delayAfterMs = 1000L
            )
            val updated = macro.copy(
                accountSwitch = macro.accountSwitch.copy(
                    enabled = true,
                    method = AccountSwitchMethod.CLONE_APP,
                    switchActions = macro.accountSwitch.switchActions + listOf(selectAct)
                )
            )
            macro = updated
            onUpdateMacro(updated)
            editingActionId = selectAct.id
            rebuildBody()
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bodyContainer.addView(tplRow)

        bodyContainer.addView(makeBtn("+ Tambah Aksi Ganti Akun", "#1D4ED8", marginEndDp = 0) {
            addPickerForStuck = false
            addPickerForSwitchAccount = true
            addPickerRef = ContainerRef.ROOT
            rebuildBody()
        }.apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        })

        if (cfg.switchActions.isEmpty()) {
            bodyContainer.addView(TextView(context).apply {
                text = "Belum ada aksi ganti akun. Gunakan tombol Template di atas atau tambahkan langkah navigasi ke daftar akun + aksi 'Pilih Akun Giliran'."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 10.5f
                setPadding(dp(2), dp(4), dp(2), dp(8))
            })
        } else {
            cfg.switchActions.forEachIndexed { idx, action ->
                bodyContainer.addView(
                    buildActionCard(
                        idx,
                        cfg.switchActions.size,
                        action,
                        depth = 0,
                        isStuckTree = false,
                        isSwitchAccountTree = true
                    )
                )
            }
        }
    }

    private fun buildAccountCloneAppPickerView(account: AccountProfile) {
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        bar.addView(makeBtn("← Kembali", "#334155", marginEndDp = 8) {
            cloneAppPickerAccountId = null
            rebuildBody()
        })
        bar.addView(TextView(context).apply {
            text = "Clone App: ${account.label}"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        bodyContainer.addView(bar)

        val searchF = addTextRow(bodyContainer, "Cari nama game / clone app", "")
        val listHolder = LinearLayout(context).apply { orientation = VERTICAL }
        bodyContainer.addView(listHolder)

        val allApps = InstalledAppsHelper.loadLaunchableApps(context, forceRefresh = true)

        fun renderFiltered(queryRaw: String) {
            listHolder.removeAllViews()
            val q = queryRaw.trim().lowercase()
            if (account.packageName.isNotBlank()) {
                listHolder.addView(makeBtn("Kosongkan Clone App (hanya pakai OCR)", "#475569", marginEndDp = 0) {
                    val updatedList = macro.accountSwitch.accounts.map {
                        if (it.id == account.id) {
                            it.copy(
                                packageName = "",
                                appLabel = "",
                                prismTargetPackage = "",
                                prismSlotOrder = 0,
                                prismProfileId = ""
                            )
                        } else it
                    }
                    val updated = macro.copy(accountSwitch = macro.accountSwitch.copy(accounts = updatedList))
                    macro = updated
                    onUpdateMacro(updated)
                    cloneAppPickerAccountId = null
                    rebuildBody()
                }.apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                })
            }
            val matched = if (q.isEmpty()) allApps else allApps.filter {
                it.label.lowercase().contains(q) ||
                    it.packageName.lowercase().contains(q) ||
                    it.prismTargetPackage.lowercase().contains(q)
            }
            if (matched.isEmpty()) {
                listHolder.addView(TextView(context).apply {
                    text = "Tidak ada aplikasi cocok."
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 11f
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                })
                return
            }
            for (info in matched.take(60)) {
                val item = LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(if (info.isPrismRoot) "#0F2942" else "#1E293B"))
                        cornerRadius = dp(9).toFloat()
                    }
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                    setOnClickListener {
                        val updatedList = macro.accountSwitch.accounts.map {
                            if (it.id == account.id) {
                                it.copy(
                                    packageName = info.packageName,
                                    appLabel = info.label,
                                    prismTargetPackage = if (info.isPrismRoot) info.prismTargetPackage else "",
                                    prismSlotOrder = if (info.isPrismRoot) info.prismSlotOrder else 0,
                                    prismProfileId = if (info.isPrismRoot) info.prismProfileId else ""
                                )
                            } else it
                        }
                        val updated = macro.copy(accountSwitch = macro.accountSwitch.copy(accounts = updatedList))
                        macro = updated
                        onUpdateMacro(updated)
                        cloneAppPickerAccountId = null
                        rebuildBody()
                    }
                }
                val titleText = if (info.isPrismRoot) {
                    val tag = if (info.prismSlotOrder > 0) " [PRISM #%02d]".format(info.prismSlotOrder) else " [PRISM Root]"
                    "${info.label}$tag"
                } else info.label
                item.addView(TextView(context).apply {
                    text = titleText
                    setTextColor(if (info.isPrismRoot) Color.parseColor("#38BDF8") else Color.WHITE)
                    textSize = 11.5f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                item.addView(TextView(context).apply {
                    text = if (info.isPrismRoot && info.prismTargetPackage.isNotBlank()) {
                        "${info.packageName} → ${info.prismTargetPackage}"
                    } else info.packageName
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 9.5f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                listHolder.addView(item)
            }
        }

        searchF.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                renderFiltered(s?.toString().orEmpty())
            }
        })
        renderFiltered("")
    }

    private fun buildActionCard(
        idx: Int,
        siblingCount: Int,
        action: Action,
        depth: Int,
        isStuckTree: Boolean = false,
        isSwitchAccountTree: Boolean = false
    ): LinearLayout {
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

        val rowThumbName = when (action) {
            is ImageAction -> action.imageName
            is TrackObjectAction -> if (action.trackAnyMotion) "" else action.imageName
            else -> ""
        }
        if (rowThumbName.isNotBlank()) {
            val file = store.imageFile(rowThumbName)
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
        if (action is ImageAction || (action is TrackObjectAction && !action.trackAnyMotion)) {
            row.addView(makeSmallIconBtn("🖼", "#0F766E") {
                quickImageSwapActionId = action.id
                rebuildBody()
            })
        } else if (action is TextAction || action is SelectAccountAction || (action is com.example.data.VariableAction && action.operation == com.example.data.VariableOperation.READ_OCR_NUMBER) || (action is TrackObjectAction && action.trackAnyMotion)) {
            row.addView(makeSmallIconBtn("📐", "#0369A1") {
                onPickRegionOnScreen(action.id)
            })
        } else if (isPositionable(action)) {
            row.addView(makeSmallIconBtn("🎯", "#0F766E") {
                onPickTargetOnScreen(action.id)
            })
        }
        if (!isStuckTree && !isSwitchAccountTree && depth == 0) {
            row.addView(makeSmallIconBtn("⇄", "#D97706") {
                moveBranchActionId = action.id
                rebuildBody()
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
                    onAddBranchClickTargetOnScreen(action.id, slot, isStuckTree)
                })
                bHeader.addView(makeSmallIconBtn("+ Aksi", "#1E293B", marginEndDp = 0) {
                    addPickerForStuck = isStuckTree
                    addPickerForSwitchAccount = isSwitchAccountTree
                    addPickerRef = ContainerRef(action.id, slot)
                    rebuildBody()
                })
                box.addView(bHeader)
                children.forEachIndexed { cIdx, child ->
                    box.addView(
                        buildActionCard(
                            cIdx,
                            children.size,
                            child,
                            depth + 1,
                            isStuckTree = isStuckTree,
                            isSwitchAccountTree = isSwitchAccountTree
                        )
                    )
                }
                card.addView(box)
            }
        }

        return card
    }

    private fun buildMoveActionToBranchView(action: Action) {
        val nav = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        nav.addView(makeBtn("← Kembali", "#334155", marginEndDp = 8) {
            moveBranchActionId = null
            rebuildBody()
        })
        nav.addView(TextView(context).apply {
            text = "Pindah ke Cabang: ${action.kind.label}"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        bodyContainer.addView(nav)

        val curBranchId = macro.branchIdOfAction(action.id).orEmpty()
        bodyContainer.addView(TextView(context).apply {
            text = "Saat ini berada di: ${macro.branchDisplayName(curBranchId)}. Pilih jalur cabang tujuan:"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11f
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        })

        fun addBranchTargetRow(targetId: String, label: String, count: Int) {
            val isCurrent = curBranchId == targetId
            val rowBtn = makeBtn(
                "$label ($count aksi)" + if (isCurrent) " • Saat ini" else "",
                if (isCurrent) "#334155" else if (targetId.isBlank()) "#0D9488" else "#B45309",
                marginEndDp = 0
            ) {
                if (!isCurrent) {
                    onMoveActionToBranch(action.id, targetId)
                    selectedBranchId = targetId
                    moveBranchActionId = null
                    Toast.makeText(context, "Dipindahkan ke $label.", Toast.LENGTH_SHORT).show()
                }
            }.apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(6)
                }
            }
            bodyContainer.addView(rowBtn)
        }

        addBranchTargetRow("", "🟢 Jalur Utama", macro.actions.size)
        macro.branches.forEach { b ->
            addBranchTargetRow(b.id, "🔀 ${b.name}", b.actions.size)
        }

        bodyContainer.addView(dividerLine())
        bodyContainer.addView(sectionLabel("+ Buat Cabang Baru & Pindahkan"))
        val newRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val newBranchEt = EditText(context).apply {
            setText("Cabang ${macro.branches.size + 1}")
            setTextColor(Color.WHITE)
            textSize = 11.5f
            isSingleLine = true
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        }
        newRow.addView(newBranchEt)
        newRow.addView(makeBtn("Buat & Pindah", "#059669") {
            val bName = newBranchEt.text.toString().trim().ifBlank { "Cabang ${macro.branches.size + 1}" }.take(36)
            val colors = listOf("#F59E0B", "#06B6D4", "#10B981", "#EC4899", "#8B5CF6", "#EF4444")
            val created = MacroBranch(
                id = newId(),
                name = bName,
                colorHex = colors[macro.branches.size % colors.size]
            )
            val withBranch = macro.copy(branches = macro.branches + created)
            val moved = withBranch.moveActionToBranch(action.id, created.id)
            macro = moved
            selectedBranchId = created.id
            moveBranchActionId = null
            onUpdateMacro(moved)
            rebuildBody()
            Toast.makeText(context, "Dipindahkan ke cabang baru '$bName'.", Toast.LENGTH_SHORT).show()
        })
        bodyContainer.addView(newRow)
    }

    private fun isPositionable(action: Action): Boolean = when (action) {
        is ClickAction, is LongPressAction, is SwipeAction, is ZoomAction, is ColorAction -> true
        else -> false
    }

    // ---- Add Action Picker inside Overlay ----

    private fun buildAddActionPicker(ref: ContainerRef, forStuck: Boolean, forSwitchAccount: Boolean = false) {
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        bar.addView(makeBtn("← Kembali", "#334155", marginEndDp = 8) {
            addPickerRef = null
            addPickerForSwitchAccount = false
            rebuildBody()
        })
        bar.addView(TextView(context).apply {
            val slot = ref.slot
            text = when {
                slot != null -> "Tambah ke: ${slot.label}"
                forSwitchAccount -> "Tambah Aksi Ganti Akun"
                forStuck -> "Tambah Aksi Anti-Macet"
                selectedBranchId.isNotBlank() -> "Tambah ke: ${macro.branchDisplayName(selectedBranchId)}"
                else -> "Pilih Jenis Aksi"
            }
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        })
        bodyContainer.addView(bar)

        val kinds = if (forSwitchAccount) {
            listOf(
                ActionKind.SELECT_ACCOUNT, ActionKind.IMAGE, ActionKind.TEXT, ActionKind.COLOR,
                ActionKind.CLICK, ActionKind.LONG_PRESS, ActionKind.SWIPE, ActionKind.WAIT,
                ActionKind.EXIT_APP, ActionKind.LAUNCH_APP, ActionKind.LOOP, ActionKind.TRACK_OBJECT,
                ActionKind.ZOOM, ActionKind.SWITCH_ACCOUNT, ActionKind.STOP
            )
        } else {
            listOf(
                ActionKind.IMAGE, ActionKind.TRACK_OBJECT, ActionKind.TEXT, ActionKind.COLOR,
                ActionKind.VARIABLE, ActionKind.CHECK_VARIABLE, ActionKind.NOTIFY,
                ActionKind.SWITCH_ACCOUNT, ActionKind.SELECT_ACCOUNT,
                ActionKind.BRANCH_ROUTER, ActionKind.GOTO_BRANCH,
                ActionKind.EXIT_APP, ActionKind.LAUNCH_APP,
                ActionKind.CLICK, ActionKind.LONG_PRESS,
                ActionKind.SWIPE, ActionKind.ZOOM, ActionKind.WAIT, ActionKind.LOOP,
                ActionKind.JUMP, ActionKind.RUN_MACRO, ActionKind.FINISH_MACRO, ActionKind.RESTART, ActionKind.STOP
            )
        }
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
                    val rawCreated = k.create(screenW, screenH, imageName = defaultImg)
                    val created = if (rawCreated is GoToBranchAction) {
                        val suggested = macro.branches.firstOrNull { it.id != selectedBranchId } ?: macro.branches.firstOrNull()
                        if (suggested != null) rawCreated.copy(targetBranchId = suggested.id, targetBranchName = suggested.name)
                        else rawCreated
                    } else rawCreated
                    if (forSwitchAccount) {
                        onAddSwitchAccountAction(ref, created)
                    } else {
                        onAddAction(ref, created, forStuck, selectedBranchId)
                    }
                    addPickerRef = null
                    addPickerForSwitchAccount = false
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

        val existingCtrl = macro.actionRemoteControls[action.id] ?: com.example.data.ActionRemoteControl()
        var botMode = existingCtrl.mode
        var botActive = existingCtrl.active
        val botBox = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1B4B"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#6D28D9"))
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(6)
            }
        }
        val botIdField = EditText(context).apply {
            hint = "ID Aksi Bot (mis. auto_sell)"
            setText(existingCtrl.remoteId)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            isSingleLine = true
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = dp(6).toFloat()
            }
        }
        val botDetailCol = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = if (existingCtrl.enabled) View.VISIBLE else View.GONE
        }
        val botEnableCheck = CheckBox(context).apply {
            text = "🤖 Kontrol Aksi via Bot Telegram / Discord"
            isChecked = existingCtrl.enabled
            setTextColor(Color.parseColor("#C4B5FD"))
            textSize = 11.5f
            setOnCheckedChangeListener { _, checked ->
                botDetailCol.visibility = if (checked) View.VISIBLE else View.GONE
                if (checked && com.example.data.RemoteIdSanitizer.sanitize(botIdField.text.toString()).isEmpty()) {
                    val otherIds = macro.actionRemoteControls
                        .filterKeys { it != action.id }
                        .values.map { it.normalizedId() }.toSet()
                    botIdField.setText(com.example.data.RemoteIdSanitizer.suggestActionId(action.kind.label, otherIds))
                }
            }
        }
        botBox.addView(botEnableCheck)
        botDetailCol.addView(botIdField, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        val botModeRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        var modeBtnRef: TextView? = null
        var activeBtnRef: TextView? = null
        fun refreshBotBtns() {
            modeBtnRef?.text = if (botMode == com.example.data.RemoteActionMode.TOGGLE) "Mode: Saklar ON/OFF" else "Mode: Pemicu 1×"
            (modeBtnRef?.background as? GradientDrawable)?.setColor(
                Color.parseColor(if (botMode == com.example.data.RemoteActionMode.TOGGLE) "#4F46E5" else "#7C3AED")
            )
            activeBtnRef?.text = when {
                botMode == com.example.data.RemoteActionMode.ONE_SHOT && botActive -> "⚡ Siap 1×"
                botMode == com.example.data.RemoteActionMode.ONE_SHOT -> "⚪ OFF"
                botActive -> "🟢 ON"
                else -> "⚪ OFF"
            }
            (activeBtnRef?.background as? GradientDrawable)?.setColor(
                Color.parseColor(if (botActive) "#059669" else "#475569")
            )
        }
        val modeBtn = makeBtn("Mode: Saklar ON/OFF", "#4F46E5", marginEndDp = 4) {
            botMode = if (botMode == com.example.data.RemoteActionMode.TOGGLE) {
                botActive = false
                com.example.data.RemoteActionMode.ONE_SHOT
            } else {
                com.example.data.RemoteActionMode.TOGGLE
            }
            refreshBotBtns()
        }
        val activeBtn = makeBtn("🟢 ON", "#059669", marginEndDp = 0) {
            botActive = !botActive
            refreshBotBtns()
        }
        modeBtnRef = modeBtn
        activeBtnRef = activeBtn
        refreshBotBtns()
        botModeRow.addView(modeBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.4f).apply { marginEnd = dp(4) })
        botModeRow.addView(activeBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        botDetailCol.addView(botModeRow)
        botBox.addView(botDetailCol)
        bodyContainer.addView(botBox)

        fun saveEdited(transform: (Action) -> Action) {
            val pct = (chanceF.text.toString().toIntOrNull() ?: action.chancePercent).coerceIn(0, 100)
            val cleanBotId = com.example.data.RemoteIdSanitizer.sanitize(botIdField.text.toString())
            val nextRemoteCtrl = if (botEnableCheck.isChecked && cleanBotId.isNotEmpty()) {
                existingCtrl.copy(
                    enabled = true,
                    remoteId = cleanBotId,
                    mode = botMode,
                    active = botActive
                )
            } else null
            val updatedMacro = macro
                .updateAnyAction(action.id) { cur -> transform(cur).withChancePercent(pct) }
                .withActionRemoteControl(action.id, nextRemoteCtrl)
            macro = updatedMacro
            onUpdateMacro(updatedMacro)
            editingActionId = null
            rebuildBody()
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
                var bannedMode = action.bannedMode

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
                val clickCountF = addNumberRow(bodyContainer, "Jumlah klik berulang (1-50)", action.clickCount.toString())
                val clickIntF = addNumberRow(bodyContainer, "Jeda antar klik berulang (ms)", action.clickIntervalMs.toString())
                val clickDurF = addNumberRow(bodyContainer, "Durasi tekan klik (ms)", action.clickDurationMs.toString())
                val oxF = addSignedNumberRow(bodyContainer, "Offset X (px)", action.offsetX.toString())
                val oyF = addSignedNumberRow(bodyContainer, "Offset Y (px)", action.offsetY.toString())
                if (action.bannedImages.isNotEmpty()) {
                    bodyContainer.addView(TextView(context).apply {
                        text = "⛔ ${action.bannedImages.size} gambar terlarang aktif (${bannedMode.shortLabel}) — atur daftar lengkap di editor aplikasi."
                        setTextColor(Color.parseColor("#FCA5A5"))
                        textSize = 10.5f
                        setPadding(0, dp(2), 0, dp(4))
                    })
                    val bmBtns = mutableListOf<Pair<BannedImageMode, TextView>>()
                    val bmRow = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                    }
                    BannedImageMode.entries.forEachIndexed { idx, bm ->
                        val tv = makeBtn(bm.shortLabel, if (bm == bannedMode) "#DC2626" else "#1E293B", marginEndDp = 0) {
                            bannedMode = bm
                            bmBtns.forEach { (m, v) ->
                                (v.background as? GradientDrawable)?.setColor(Color.parseColor(if (m == bannedMode) "#DC2626" else "#1E293B"))
                            }
                        }
                        bmBtns += bm to tv
                        bmRow.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = if (idx < BannedImageMode.entries.lastIndex) dp(4) else 0
                        })
                    }
                    bodyContainer.addView(bmRow)
                }
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
                            clickCount = (clickCountF.text.toString().toIntOrNull() ?: imgCur.clickCount).coerceIn(1, 50),
                            clickIntervalMs = (clickIntF.text.toString().toLongOrNull() ?: imgCur.clickIntervalMs).coerceIn(10L, 10_000L),
                            clickDurationMs = (clickDurF.text.toString().toLongOrNull() ?: imgCur.clickDurationMs).coerceIn(10L, 5000L),
                            offsetX = oxF.text.toString().toIntOrNull() ?: imgCur.offsetX,
                            offsetY = oyF.text.toString().toIntOrNull() ?: imgCur.offsetY,
                            bannedMode = bannedMode,
                            foundMode = foundMode,
                            notFound = notFoundMode,
                            region = newRegion
                        )
                    }
                }
            }

            is TrackObjectAction -> {
                var selectedImg = action.imageName
                val extraImgs = action.extraImageNames.toMutableList()
                var interactionMode = action.interactionMode
                var threshold = action.threshold
                var leadMs = action.leadPredictionMs
                var foundMode = action.foundMode
                var notFoundMode = action.notFound

                val templateBox = LinearLayout(context).apply { orientation = VERTICAL }
                val templateOptionsBox = LinearLayout(context).apply { orientation = VERTICAL }
                val anyMotionTip = TextView(context).apply {
                    text = "Mode Bebas Warna aktif: mengejar pasukan/kerumunan bergerak apa saja tanpa terikat gambar atau warna. Disarankan batasi Area Lacak ke arena base."
                    setTextColor(Color.parseColor("#FDA4AF"))
                    textSize = 10.5f
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#4C0519"))
                        cornerRadius = dp(8).toFloat()
                    }
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                }
                val anyMotionChk = CheckBox(context).apply {
                    text = "Lacak Semua Pasukan Bergerak (Bebas Warna / Tanpa Gambar)"
                    isChecked = action.trackAnyMotion
                    setTextColor(Color.parseColor("#FECDD3"))
                    textSize = 11.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setOnCheckedChangeListener { _, checked ->
                        templateBox.visibility = if (checked) GONE else VISIBLE
                        templateOptionsBox.visibility = if (checked) GONE else VISIBLE
                        anyMotionTip.visibility = if (checked) VISIBLE else GONE
                    }
                }
                bodyContainer.addView(anyMotionChk)
                bodyContainer.addView(anyMotionTip)
                anyMotionTip.visibility = if (action.trackAnyMotion) VISIBLE else GONE

                // Crop primary object & crop extra animation pose directly from live game screen
                val cropRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                }
                cropRow.addView(makeBtn("✂ Tangkap Objek", "#E11D48", marginEndDp = 6) {
                    onCropForImageAction(action.id)
                }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
                cropRow.addView(makeBtn("+ ✂ Pose Lain", "#0F766E") {
                    onCropExtraFrameForTrackAction(action.id)
                }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                templateBox.addView(cropRow)

                if (selectedImg.isNotBlank() && store.imageFile(selectedImg).exists()) {
                    templateBox.addView(makeBtn("✂ Potong Ulang Gambar Utama", "#0284C7", marginEndDp = 0) {
                        onRecropExistingImage(selectedImg)
                    }.apply {
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                    })
                }

                // Visual folder-aware thumbnail picker for primary image
                val savedBody = bodyContainer
                addVisualImageSelector(
                    initialSelected = selectedImg,
                    onSelect = {
                        selectedImg = it
                        extraImgs.remove(it)
                    },
                    targetParent = templateBox
                )

                // Extra pose frames summary & removable pills
                if (extraImgs.isNotEmpty()) {
                    templateBox.addView(sectionLabel("Pose Tambahan (${extraImgs.size}/3) — ketuk untuk hapus"))
                    val extraWrap = LinearLayout(context).apply {
                        orientation = VERTICAL
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                    }
                    extraImgs.toList().forEach { extraName ->
                        extraWrap.addView(makeBtn("🖼 ${extraName.substringAfterLast('/').removeSuffix(".png")}  ✕", "#334155", marginEndDp = 0) {
                            onUpdateAction(action.id) { cur ->
                                if (cur is TrackObjectAction) cur.copy(extraImageNames = cur.extraImageNames.filter { it != extraName }) else cur
                            }
                        }.apply {
                            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                        })
                    }
                    templateBox.addView(extraWrap)
                }
                templateBox.visibility = if (action.trackAnyMotion) GONE else VISIBLE
                savedBody.addView(templateBox)

                // Tap / Drag interaction mode selector
                bodyContainer.addView(dividerLine())
                bodyContainer.addView(sectionLabel("Aksi ke Objek (Tap / Drag)"))
                val modeBtns = mutableListOf<Pair<TrackInteractionMode, TextView>>()
                val tapBox = LinearLayout(context).apply { orientation = VERTICAL }
                val tapContBox = LinearLayout(context).apply { orientation = VERTICAL }
                val dragPointBox = LinearLayout(context).apply { orientation = VERTICAL }
                val dragOffsetBox = LinearLayout(context).apply { orientation = VERTICAL }
                val dragDurBox = LinearLayout(context).apply { orientation = VERTICAL }

                val modeDescTv = TextView(context).apply {
                    text = interactionMode.description
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 10.2f
                    setPadding(0, dp(2), 0, dp(6))
                }

                val pickPointBtn = makeBtn("🎯 Pilih Titik Drag di Layar", "#0F766E", marginEndDp = 0) {
                    onUpdateAction(action.id) { cur ->
                        if (cur is TrackObjectAction) cur.copy(interactionMode = interactionMode) else cur
                    }
                    onPickTargetOnScreen(action.id)
                }.apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                }

                fun syncModeVisibility() {
                    modeDescTv.text = interactionMode.description
                    tapBox.visibility = if (interactionMode == TrackInteractionMode.TAP_ONCE || interactionMode == TrackInteractionMode.TAP_CONTINUOUS) View.VISIBLE else View.GONE
                    tapContBox.visibility = if (interactionMode == TrackInteractionMode.TAP_CONTINUOUS) View.VISIBLE else View.GONE
                    val isPointDrag = interactionMode == TrackInteractionMode.DRAG_TO_POINT || interactionMode == TrackInteractionMode.DRAG_FROM_POINT_TO_OBJECT
                    dragPointBox.visibility = if (isPointDrag) View.VISIBLE else View.GONE
                    dragOffsetBox.visibility = if (interactionMode == TrackInteractionMode.DRAG_BY_OFFSET) View.VISIBLE else View.GONE
                    dragDurBox.visibility = if (isPointDrag || interactionMode == TrackInteractionMode.DRAG_BY_OFFSET) View.VISIBLE else View.GONE
                    pickPointBtn.text = if (interactionMode == TrackInteractionMode.DRAG_FROM_POINT_TO_OBJECT) {
                        "🎯 Pilih Titik Awal Drag di Layar"
                    } else {
                        "🎯 Pilih Titik Tujuan Drag di Layar"
                    }
                }

                TrackInteractionMode.entries.forEach { m ->
                    val b = makeBtn(m.label, if (m == interactionMode) "#E11D48" else "#1E293B", marginEndDp = 0) {
                        interactionMode = m
                        modeBtns.forEach { (item, tv) ->
                            (tv.background as? GradientDrawable)?.setColor(Color.parseColor(if (item == interactionMode) "#E11D48" else "#1E293B"))
                        }
                        syncModeVisibility()
                    }.apply {
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                    }
                    modeBtns += m to b
                    bodyContainer.addView(b)
                }
                bodyContainer.addView(modeDescTv)

                val tapDurF = addNumberRow(tapBox, "Durasi Tap (ms)", action.tapDurationMs.toString())
                val repIntF = addNumberRow(tapContBox, "Interval Tap (ms)", action.repeatIntervalMs.toString())
                val maxTrackF = addNumberRow(tapContBox, "Maks Kejar (ms)", action.maxTrackDurationMs.toString())

                dragPointBox.addView(pickPointBtn)
                val targetXF = addNumberRow(dragPointBox, "Titik X", action.targetX.toString())
                val targetYF = addNumberRow(dragPointBox, "Titik Y", action.targetY.toString())

                dragOffsetBox.addView(makeBtn("🎯 Tarik Arah & Jarak Drag (A→B) di Layar", "#0F766E", marginEndDp = 0) {
                    onUpdateAction(action.id) { cur ->
                        if (cur is TrackObjectAction) cur.copy(interactionMode = TrackInteractionMode.DRAG_BY_OFFSET) else cur
                    }
                    onPickTargetOnScreen(action.id)
                }.apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                })
                val ddxF = addSignedNumberRow(dragOffsetBox, "Geser dX (px)", action.dragOffsetX.toString())
                val ddyF = addSignedNumberRow(dragOffsetBox, "Geser dY (px)", action.dragOffsetY.toString())
                val dragDurF = addNumberRow(dragDurBox, "Durasi Drag (ms)", action.dragDurationMs.toString())

                bodyContainer.addView(tapBox)
                bodyContainer.addView(tapContBox)
                bodyContainer.addView(dragPointBox)
                bodyContainer.addView(dragOffsetBox)
                bodyContainer.addView(dragDurBox)
                syncModeVisibility()

                bodyContainer.addView(dividerLine())

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
                    threshold = (threshold - 0.05f).coerceIn(0.45f, 0.99f)
                    thrLabel.text = "Akurasi kemiripan: ${(threshold * 100).roundToInt()}%"
                })
                thrRow.addView(makeSmallIconBtn("+5%", "#334155", marginEndDp = 0) {
                    threshold = (threshold + 0.05f).coerceIn(0.45f, 0.99f)
                    thrLabel.text = "Akurasi kemiripan: ${(threshold * 100).roundToInt()}%"
                })
                templateOptionsBox.addView(thrRow)

                // Lead prediction stepper
                val leadRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                }
                val leadLabel = TextView(context).apply {
                    text = if (leadMs <= 0L) "Prediksi Gerak: Mati" else "Prediksi Gerak: +${leadMs} ms"
                    setTextColor(Color.WHITE)
                    textSize = 11.5f
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                }
                leadRow.addView(leadLabel)
                leadRow.addView(makeSmallIconBtn("-20", "#334155") {
                    leadMs = (leadMs - 20L).coerceIn(0L, 300L)
                    leadLabel.text = if (leadMs <= 0L) "Prediksi Gerak: Mati" else "Prediksi Gerak: +${leadMs} ms"
                })
                leadRow.addView(makeSmallIconBtn("+20", "#334155", marginEndDp = 0) {
                    leadMs = (leadMs + 20L).coerceIn(0L, 300L)
                    leadLabel.text = if (leadMs <= 0L) "Prediksi Gerak: Mati" else "Prediksi Gerak: +${leadMs} ms"
                })
                bodyContainer.addView(leadRow)

                val colorChk = CheckBox(context).apply {
                    text = "Cocokkan Warna Pasukan (RGB/HSV, tahan putaran & zoom)"
                    isChecked = action.useColorMatch
                    setTextColor(Color.WHITE)
                    textSize = 11.2f
                }
                templateOptionsBox.addView(colorChk)

                val motionChk = CheckBox(context).apply {
                    text = "Prioritaskan Objek Bergerak (abaikan bangunan diam)"
                    isChecked = action.prioritizeMotion
                    setTextColor(Color.WHITE)
                    textSize = 11.2f
                }
                templateOptionsBox.addView(motionChk)

                val mirrorChk = CheckBox(context).apply {
                    text = "Cek Mirror Kiri ↔ Kanan (objek berbalik arah)"
                    isChecked = action.matchMirrorX
                    setTextColor(Color.WHITE)
                    textSize = 11.2f
                }
                templateOptionsBox.addView(mirrorChk)

                val coreChk = CheckBox(context).apply {
                    text = "Fokus Tengah (abaikan latar belakang pinggir)"
                    isChecked = action.ignoreBackgroundEdges
                    setTextColor(Color.WHITE)
                    textSize = 11.2f
                }
                templateOptionsBox.addView(coreChk)
                templateOptionsBox.visibility = if (action.trackAnyMotion) GONE else VISIBLE
                bodyContainer.addView(templateOptionsBox)

                val timeoutF = addNumberRow(bodyContainer, "Batas cari awal (ms)", action.timeoutMs.toString())
                val radiusF = addNumberRow(bodyContainer, "Radius lacak lokal (px)", action.trackRadiusPx.toString())
                val oxF = addSignedNumberRow(bodyContainer, "Offset X objek (px)", action.offsetX.toString())
                val oyF = addSignedNumberRow(bodyContainer, "Offset Y objek (px)", action.offsetY.toString())
                val delayF = addNumberRow(bodyContainer, "Jeda sesudah (ms)", action.delayAfterMs.toString())

                bodyContainer.addView(dividerLine())
                val regCheck = CheckBox(context).apply {
                    text = "Batasi area pelacakan (Region)"
                    isChecked = action.region != null
                    setTextColor(Color.parseColor("#CBD5E1"))
                    textSize = 11.5f
                }
                bodyContainer.addView(regCheck)
                bodyContainer.addView(makeBtn("📐 Pilih Area Lacak di Layar", "#0369A1") {
                    onPickRegionOnScreen(action.id)
                }.apply { layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) } })
                val reg = action.region ?: Region(0, 0, screenW, screenH)
                val rL = addNumberRow(bodyContainer, "Area Kiri", reg.left.toString())
                val rT = addNumberRow(bodyContainer, "Area Atas", reg.top.toString())
                val rR = addNumberRow(bodyContainer, "Area Kanan", reg.right.toString())
                val rB = addNumberRow(bodyContainer, "Area Bawah", reg.bottom.toString())

                bodyContainer.addView(dividerLine())
                bodyContainer.addView(sectionLabel("Setelah Objek Ditangani (Then)"))
                addBranchModePicker("Setelah cabang Selesai:", foundMode) { foundMode = it }
                addBranchActionsControls(action.id, Slot.THEN, action.thenActions, "#065F46")

                bodyContainer.addView(dividerLine())
                bodyContainer.addView(sectionLabel("Jika Objek Tidak Ditemukan (Else)"))
                addBranchModePicker("Setelah cabang Tidak Ditemukan:", notFoundMode) { notFoundMode = it }
                addBranchActionsControls(action.id, Slot.ELSE, action.elseActions, "#92400E")

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
                        val tCur = (cur as? TrackObjectAction) ?: action
                        tCur.copy(
                            imageName = selectedImg,
                            extraImageNames = extraImgs.filter { it != selectedImg }.distinct().take(3),
                            interactionMode = interactionMode,
                            threshold = threshold,
                            timeoutMs = (timeoutF.text.toString().toLongOrNull() ?: tCur.timeoutMs).coerceAtLeast(0L),
                            delayAfterMs = (delayF.text.toString().toLongOrNull() ?: tCur.delayAfterMs).coerceAtLeast(0L),
                            tapDurationMs = (tapDurF.text.toString().toLongOrNull() ?: tCur.tapDurationMs).coerceIn(20L, 400L),
                            dragDurationMs = (dragDurF.text.toString().toLongOrNull() ?: tCur.dragDurationMs).coerceIn(60L, 5000L),
                            repeatIntervalMs = (repIntF.text.toString().toLongOrNull() ?: tCur.repeatIntervalMs).coerceIn(40L, 5000L),
                            maxTrackDurationMs = (maxTrackF.text.toString().toLongOrNull() ?: tCur.maxTrackDurationMs).coerceIn(300L, 300_000L),
                            targetX = targetXF.text.toString().toIntOrNull() ?: tCur.targetX,
                            targetY = targetYF.text.toString().toIntOrNull() ?: tCur.targetY,
                            dragOffsetX = ddxF.text.toString().toIntOrNull() ?: tCur.dragOffsetX,
                            dragOffsetY = ddyF.text.toString().toIntOrNull() ?: tCur.dragOffsetY,
                            offsetX = oxF.text.toString().toIntOrNull() ?: tCur.offsetX,
                            offsetY = oyF.text.toString().toIntOrNull() ?: tCur.offsetY,
                            leadPredictionMs = leadMs,
                            trackRadiusPx = (radiusF.text.toString().toIntOrNull() ?: tCur.trackRadiusPx).coerceIn(60, 1200),
                            trackAnyMotion = anyMotionChk.isChecked,
                            useColorMatch = colorChk.isChecked,
                            prioritizeMotion = motionChk.isChecked,
                            matchMirrorX = mirrorChk.isChecked,
                            ignoreBackgroundEdges = coreChk.isChecked,
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
                    java.math.BigDecimal.valueOf(action.targetNumber).stripTrailingZeros().toPlainString()
                }
                val numField = addTextRow(numBox, "Nilai pembanding (cth 500000 / 500K)", numStr)

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
                    val parsedNum = com.example.vision.TextConditionEvaluator.parseSmartNumber(numField.text.toString())
                        ?: action.targetNumber
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

            is FinishMacroAction -> {
                var selectedMode = action.mode
                var targetId = action.targetMacroId
                var targetName = action.targetMacroName
                bodyContainer.addView(TextView(context).apply {
                    text = "Selesaikan macro/pasif ini lalu langsung kembali/switch ke macro aktif tanpa menghentikan eksekusi."
                    setTextColor(Color.parseColor("#CBD5E1"))
                    textSize = 11f
                    setPadding(0, 0, 0, dp(6))
                })
                bodyContainer.addView(sectionLabel("Arah Switch Setelah Selesai"))
                val activePickerContainer = LinearLayout(context).apply {
                    orientation = VERTICAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(4)
                    }
                }
                val modeBtns = mutableListOf<Pair<FinishMacroMode, TextView>>()
                FinishMacroMode.entries.forEach { m ->
                    val b = makeBtn("${m.shortLabel} — ${m.label}", if (m == selectedMode) "#059669" else "#1E293B") {
                        selectedMode = m
                        modeBtns.forEach { (itemMode, tv) ->
                            (tv.background as? GradientDrawable)?.setColor(
                                Color.parseColor(if (itemMode == selectedMode) "#059669" else "#1E293B")
                            )
                        }
                        activePickerContainer.visibility = if (selectedMode == FinishMacroMode.SWITCH_TO_SPECIFIC_ACTIVE) VISIBLE else GONE
                    }.apply {
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                    }
                    modeBtns += m to b
                    bodyContainer.addView(b)
                }

                activePickerContainer.addView(sectionLabel("Pilih Macro Aktif Tujuan"))
                val activeMacros = store.macros.value.filter { it.id != macro.id && !it.isPassive }
                if (activeMacros.isEmpty()) {
                    activePickerContainer.addView(TextView(context).apply {
                        text = "Belum ada macro aktif lain. Buat macro aktif di aplikasi terlebih dahulu."
                        setTextColor(Color.parseColor("#94A3B8"))
                        textSize = 11f
                    })
                } else {
                    val now = store.effectiveNowMs()
                    val mBtns = mutableListOf<Pair<String, TextView>>()
                    activeMacros.forEach { m ->
                        val suffix = if (MacroSecurity.isExpired(m, now)) " [Habis]" else ""
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
                        activePickerContainer.addView(b)
                    }
                }
                activePickerContainer.visibility = if (selectedMode == FinishMacroMode.SWITCH_TO_SPECIFIC_ACTIVE) VISIBLE else GONE
                bodyContainer.addView(activePickerContainer)

                addSaveActionBtn {
                    saveEdited {
                        action.copy(
                            mode = selectedMode,
                            targetMacroId = if (selectedMode == FinishMacroMode.SWITCH_TO_SPECIFIC_ACTIVE) targetId else "",
                            targetMacroName = if (selectedMode == FinishMacroMode.SWITCH_TO_SPECIFIC_ACTIVE) targetName else ""
                        )
                    }
                }
            }

            is JumpAction -> {
                val ownerActions = if (macro.isStuckAction(action.id)) macro.stuckActions else macro.actions
                val enclosingIdx = ownerActions.indexOfFirst { top ->
                    top.id == action.id || listOf(top).findAction(action.id) != null
                }
                var selectedMode = action.mode
                var targetId = action.targetActionId
                var targetLabel = action.targetActionLabel

                bodyContainer.addView(TextView(context).apply {
                    text = "Loncat melewati aksi tertentu ke depan atau kembali ke belakang ke aksi sebelumnya tanpa menghentikan macro."
                    setTextColor(Color.parseColor("#CBD5E1"))
                    textSize = 11f
                    setPadding(0, 0, 0, dp(6))
                })
                bodyContainer.addView(sectionLabel("Mode Loncat Aksi"))

                val targetBox = LinearLayout(context).apply {
                    orientation = VERTICAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
                }
                val stepsBox = LinearLayout(context).apply {
                    orientation = VERTICAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
                }
                val previewTv = TextView(context).apply {
                    setTextColor(Color.parseColor("#C4B5FD"))
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#2E1065"))
                        cornerRadius = dp(8).toFloat()
                    }
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                }
                val stepF = addNumberRow(stepsBox, "Jumlah langkah (N)", action.stepCount.coerceAtLeast(1).toString())

                fun refreshJumpUi() {
                    val isTargetMode = selectedMode == JumpMode.SKIP_AFTER_ACTION || selectedMode == JumpMode.JUMP_TO_ACTION
                    targetBox.visibility = if (isTargetMode) VISIBLE else GONE
                    stepsBox.visibility = if (isTargetMode) GONE else VISIBLE
                    val selIdx = ownerActions.indexOfFirst { it.id == targetId }
                    val msg = when (selectedMode) {
                        JumpMode.SKIP_AFTER_ACTION -> if (selIdx >= 0) {
                            val nextIdx = selIdx + 1
                            if (nextIdx < ownerActions.size) {
                                "Lewati #${selIdx + 1} (${ownerActions[selIdx].kind.label}) → lanjut di #${nextIdx + 1} (${ownerActions[nextIdx].kind.label})"
                            } else {
                                "Lewati #${selIdx + 1} (${ownerActions[selIdx].kind.label}) → selesai siklus ini"
                            }
                        } else "Pilih aksi yang ingin dilewati di bawah"
                        JumpMode.JUMP_TO_ACTION -> if (selIdx >= 0) {
                            val dir = when {
                                enclosingIdx >= 0 && selIdx < enclosingIdx -> "mundur ke belakang"
                                enclosingIdx >= 0 && selIdx > enclosingIdx -> "maju ke depan"
                                else -> "ke"
                            }
                            "Loncat $dir → langsung jalan dari #${selIdx + 1} (${ownerActions[selIdx].kind.label})"
                        } else "Pilih aksi tujuan (mundur/maju) di bawah"
                        JumpMode.SKIP_FORWARD_STEPS -> {
                            val n = (stepF.text.toString().toIntOrNull() ?: action.stepCount).coerceAtLeast(1)
                            "Melewati +$n aksi di depan"
                        }
                        JumpMode.JUMP_BACKWARD_STEPS -> {
                            val n = (stepF.text.toString().toIntOrNull() ?: action.stepCount).coerceAtLeast(1)
                            "Kembali -$n aksi ke belakang"
                        }
                    }
                    previewTv.text = msg
                }

                val modeBtns = mutableListOf<Pair<JumpMode, TextView>>()
                JumpMode.entries.forEach { m ->
                    val b = makeBtn("${m.shortLabel} — ${m.label}", if (m == selectedMode) "#7C3AED" else "#1E293B") {
                        selectedMode = m
                        modeBtns.forEach { (itemMode, tv) ->
                            (tv.background as? GradientDrawable)?.setColor(
                                Color.parseColor(if (itemMode == selectedMode) "#7C3AED" else "#1E293B")
                            )
                        }
                        refreshJumpUi()
                    }.apply {
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                    }
                    modeBtns += m to b
                    bodyContainer.addView(b)
                }

                bodyContainer.addView(previewTv)

                targetBox.addView(sectionLabel("Pilih Aksi di Daftar Macro"))
                if (ownerActions.isEmpty()) {
                    targetBox.addView(TextView(context).apply {
                        text = "Belum ada aksi di daftar macro."
                        setTextColor(Color.parseColor("#94A3B8"))
                        textSize = 11f
                    })
                } else {
                    val aBtns = mutableListOf<Pair<String, TextView>>()
                    ownerActions.forEachIndexed { idx, candidate ->
                        val dirTag = when {
                            candidate.id == action.id -> " [Aksi ini]"
                            enclosingIdx >= 0 && idx < enclosingIdx -> " [← Mundur]"
                            enclosingIdx >= 0 && idx > enclosingIdx -> " [→ Maju]"
                            else -> ""
                        }
                        val labelStr = "#${idx + 1} ${candidate.kind.label}"
                        val b = makeBtn(
                            "$labelStr • ${candidate.summary().take(28)}$dirTag",
                            if (candidate.id == targetId) "#0891B2" else "#1E293B"
                        ) {
                            if (candidate.id != action.id || selectedMode == JumpMode.SKIP_AFTER_ACTION) {
                                targetId = candidate.id
                                targetLabel = labelStr
                                aBtns.forEach { (id, tv) ->
                                    (tv.background as? GradientDrawable)?.setColor(
                                        Color.parseColor(if (id == targetId) "#0891B2" else "#1E293B")
                                    )
                                }
                                refreshJumpUi()
                            }
                        }.apply {
                            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                        }
                        aBtns += candidate.id to b
                        targetBox.addView(b)
                    }
                }
                bodyContainer.addView(targetBox)
                bodyContainer.addView(stepsBox)
                refreshJumpUi()

                val delF = addNumberRow(bodyContainer, "Jeda sebelum loncat (ms)", action.delayAfterMs.toString())
                addSaveActionBtn {
                    val isTargetMode = selectedMode == JumpMode.SKIP_AFTER_ACTION || selectedMode == JumpMode.JUMP_TO_ACTION
                    val parsedSteps = (stepF.text.toString().toIntOrNull() ?: action.stepCount).coerceIn(1, 999)
                    saveEdited {
                        action.copy(
                            mode = selectedMode,
                            targetActionId = if (isTargetMode) targetId else "",
                            targetActionLabel = if (isTargetMode) targetLabel else "",
                            stepCount = parsedSteps,
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

            is ExitAppAction -> {
                var chosenMode = action.mode
                var chosenSwipeDir = action.recentsSwipeDirection
                bodyContainer.addView(sectionLabel("Cara Keluar Aplikasi"))
                val dirBox = LinearLayout(context).apply { orientation = VERTICAL }
                val modeBtns = mutableListOf<Pair<ExitAppMode, TextView>>()
                ExitAppMode.entries.forEach { mode ->
                    val b = makeBtn(mode.label, if (mode == chosenMode) "#EA580C" else "#1E293B", marginEndDp = 0) {
                        chosenMode = mode
                        modeBtns.forEach { (m, tv) ->
                            (tv.background as? GradientDrawable)?.setColor(Color.parseColor(if (m == chosenMode) "#EA580C" else "#1E293B"))
                        }
                        dirBox.visibility = if (chosenMode == ExitAppMode.CLOSE_RECENT_AND_HOME) View.VISIBLE else View.GONE
                    }.apply {
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = dp(4)
                        }
                    }
                    modeBtns += mode to b
                    bodyContainer.addView(b)
                }

                dirBox.addView(sectionLabel("Arah Swipe Recent (Portrait & Lanskap)"))
                val dirBtns = mutableListOf<Pair<RecentsSwipeDirection, TextView>>()
                RecentsSwipeDirection.entries.forEach { dir ->
                    val db = makeBtn(dir.label, if (dir == chosenSwipeDir) "#0284C7" else "#1E293B", marginEndDp = 0) {
                        chosenSwipeDir = dir
                        dirBtns.forEach { (dItem, tv) ->
                            (tv.background as? GradientDrawable)?.setColor(Color.parseColor(if (dItem == chosenSwipeDir) "#0284C7" else "#1E293B"))
                        }
                    }.apply {
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = dp(4)
                        }
                    }
                    dirBtns += dir to db
                    dirBox.addView(db)
                }
                val forceStopChk = CheckBox(context).apply {
                    text = "Paksa Berhenti di Latar Belakang (Info Aplikasi → OK)"
                    isChecked = action.forceStopBackground
                    setTextColor(Color.WHITE)
                    textSize = 11.2f
                }
                dirBox.addView(forceStopChk)
                dirBox.visibility = if (chosenMode == ExitAppMode.CLOSE_RECENT_AND_HOME) View.VISIBLE else View.GONE
                bodyContainer.addView(dirBox)

                addPickTargetBtn(
                    if (action.packageName.isBlank()) "📱 Pilih Aplikasi Target (Opsional — otomatis deteksi app aktif)"
                    else "📱 Target: ${action.appLabel.ifBlank { action.packageName }}"
                ) {
                    appPickerActionId = action.id
                    rebuildBody()
                }
                val pkgF = addTextRow(bodyContainer, "Package Name (opsional)", action.packageName)
                val delF = addNumberRow(bodyContainer, "Jeda sesudah keluar (ms)", action.delayAfterMs.toString())
                addSaveActionBtn {
                    val pkg = pkgF.text.toString().trim()
                    saveEdited {
                        action.copy(
                            mode = chosenMode,
                            recentsSwipeDirection = chosenSwipeDir,
                            forceStopBackground = forceStopChk.isChecked,
                            packageName = pkg,
                            appLabel = if (pkg == action.packageName) action.appLabel else "",
                            delayAfterMs = (delF.text.toString().toLongOrNull() ?: action.delayAfterMs).coerceAtLeast(0L)
                        )
                    }
                }
            }

            is LaunchAppAction -> {
                addPickTargetBtn(
                    if (action.packageName.isBlank()) "📱 Pilih Aplikasi / Game Terpasang"
                    else "📱 Ganti: ${action.appLabel.ifBlank { action.packageName }}"
                ) {
                    appPickerActionId = action.id
                    rebuildBody()
                }
                val pkgF = addTextRow(bodyContainer, "Package Name Aplikasi", action.packageName)
                val freshChk = CheckBox(context).apply {
                    text = "Buka ulang bersih dari awal (Fresh Start)"
                    isChecked = action.freshStart
                    setTextColor(Color.WHITE)
                    textSize = 11.2f
                }
                bodyContainer.addView(freshChk)
                val delF = addNumberRow(bodyContainer, "Jeda loading sesudah masuk (ms)", action.delayAfterMs.toString())
                addSaveActionBtn {
                    val pkg = pkgF.text.toString().trim()
                    saveEdited {
                        action.copy(
                            packageName = pkg,
                            appLabel = if (pkg == action.packageName) action.appLabel else "",
                            freshStart = freshChk.isChecked,
                            delayAfterMs = (delF.text.toString().toLongOrNull() ?: action.delayAfterMs).coerceAtLeast(0L)
                        )
                    }
                }
            }

            is GoToBranchAction -> {
                var targetId = action.targetBranchId
                var targetName = action.targetBranchName
                var navMode = action.mode

                bodyContainer.addView(sectionLabel("Cabang Tujuan"))
                val branchChoices = listOf("" to "Jalur Utama") + macro.branches.map { it.id to it.name }
                val branchBtns = mutableListOf<Pair<String, TextView>>()
                branchChoices.chunked(2).forEach { pair ->
                    val r = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                    }
                    pair.forEachIndexed { idx, (bid, bname) ->
                        val tv = makeBtn(bname, if (bid == targetId) "#0284C7" else "#1E293B", marginEndDp = 0) {
                            targetId = bid
                            targetName = bname
                            branchBtns.forEach { (id, v) ->
                                (v.background as? GradientDrawable)?.setColor(Color.parseColor(if (id == targetId) "#0284C7" else "#1E293B"))
                            }
                        }
                        branchBtns += bid to tv
                        r.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = if (idx < pair.lastIndex) dp(4) else 0
                        })
                    }
                    bodyContainer.addView(r)
                }

                bodyContainer.addView(sectionLabel("Mode Perpindahan"))
                val navBtns = mutableListOf<Pair<BranchNavigationMode, TextView>>()
                val navRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                }
                BranchNavigationMode.entries.forEachIndexed { idx, m ->
                    val tv = makeBtn(m.shortLabel, if (m == navMode) "#0284C7" else "#1E293B", marginEndDp = 0) {
                        navMode = m
                        navBtns.forEach { (modeItem, v) ->
                            (v.background as? GradientDrawable)?.setColor(Color.parseColor(if (modeItem == navMode) "#0284C7" else "#1E293B"))
                        }
                    }
                    navBtns += m to tv
                    navRow.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = if (idx < BranchNavigationMode.entries.lastIndex) dp(4) else 0
                    })
                }
                bodyContainer.addView(navRow)

                val delF = addNumberRow(bodyContainer, "Jeda sesudah (ms)", action.delayAfterMs.toString())
                addSaveActionBtn {
                    saveEdited {
                        action.copy(
                            targetBranchId = targetId,
                            targetBranchName = targetName,
                            mode = navMode,
                            delayAfterMs = (delF.text.toString().toLongOrNull() ?: action.delayAfterMs).coerceAtLeast(0L)
                        )
                    }
                }
            }

            is BranchRouterAction -> {
                val timeoutF = addNumberRow(bodyContainer, "Batas waktu cek (ms)", action.timeoutMs.toString())
                val delF = addNumberRow(bodyContainer, "Jeda sesudah (ms)", action.delayAfterMs.toString())
                bodyContainer.addView(TextView(context).apply {
                    text = "Terpasang ${action.rules.size} kondisi rute • Fallback: ${action.fallbackOutcome.shortLabel}" +
                        if (action.fallbackOutcome == BranchOutcomeAction.SWITCH_BRANCH || action.fallbackOutcome == BranchOutcomeAction.CALL_BRANCH_RETURN) {
                            " → ${macro.branchDisplayName(action.fallbackBranchId)}"
                        } else ""
                    setTextColor(Color.parseColor("#CBD5E1"))
                    textSize = 11f
                    setPadding(0, dp(4), 0, dp(4))
                })
                bodyContainer.addView(TextView(context).apply {
                    text = "Untuk menambah/mengubah daftar kondisi gambar/teks/warna secara lengkap, buka editor macro di aplikasi utama."
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 10.5f
                    setPadding(0, 0, 0, dp(6))
                })
                addSaveActionBtn {
                    saveEdited { cur ->
                        val br = (cur as? BranchRouterAction) ?: action
                        br.copy(
                            timeoutMs = (timeoutF.text.toString().toLongOrNull() ?: br.timeoutMs).coerceAtLeast(0L),
                            delayAfterMs = (delF.text.toString().toLongOrNull() ?: br.delayAfterMs).coerceAtLeast(0L)
                        )
                    }
                }
            }

            is SwitchAccountAction -> {
                var specId = action.specificAccountId
                var specLabel = action.specificAccountLabel
                bodyContainer.addView(TextView(context).apply {
                    text = "Memicu perpindahan ke akun giliran berikutnya (menjalankan urutan aksi di tab 👥 Akun)."
                    setTextColor(Color.parseColor("#CBD5E1"))
                    textSize = 11f
                    setPadding(0, 0, 0, dp(6))
                })

                bodyContainer.addView(sectionLabel("Target Akun"))
                val accChoices = listOf("" to "Akun Giliran Berikutnya (Otomatis)") +
                    macro.accountSwitch.accounts.map { it.id to it.label }
                val accBtns = mutableListOf<Pair<String, TextView>>()
                accChoices.forEach { (aid, albl) ->
                    val tv = makeBtn(albl, if (aid == specId) "#0284C7" else "#1E293B", marginEndDp = 0) {
                        specId = aid
                        specLabel = if (aid.isBlank()) "" else albl
                        accBtns.forEach { (idItem, v) ->
                            (v.background as? GradientDrawable)?.setColor(Color.parseColor(if (idItem == specId) "#0284C7" else "#1E293B"))
                        }
                    }.apply {
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                    }
                    accBtns += aid to tv
                    bodyContainer.addView(tv)
                }

                val restartCheck = CheckBox(context).apply {
                    text = "Ulangi Jalur Utama dari langkah 1 setelah pindah"
                    isChecked = action.restartAfterSwitch
                    setTextColor(Color.WHITE)
                    textSize = 11.2f
                }
                bodyContainer.addView(restartCheck)
                val delF = addNumberRow(bodyContainer, "Jeda sesudah (ms)", action.delayAfterMs.toString())
                addSaveActionBtn {
                    saveEdited {
                        action.copy(
                            specificAccountId = specId,
                            specificAccountLabel = specLabel,
                            restartAfterSwitch = restartCheck.isChecked,
                            delayAfterMs = (delF.text.toString().toLongOrNull() ?: action.delayAfterMs).coerceAtLeast(0L)
                        )
                    }
                }
            }

            is SelectAccountAction -> {
                var methodOverride = action.methodOverride
                var matchMode = action.matchMode
                var scrollDir = action.scrollDirection
                var notFoundMode = action.notFound
                var useRegion = action.region != null

                addPickTargetBtn("📐 Pilih Area Daftar Akun di Layar (Opsional)") {
                    onPickRegionOnScreen(action.id)
                }

                bodyContainer.addView(sectionLabel("Metode Pilih Akun"))
                val methodOptions = listOf<Pair<AccountSwitchMethod?, String>>(
                    null to "Ikuti Macro (${macro.accountSwitch.method.shortLabel})",
                    AccountSwitchMethod.PRISM_ROOT to AccountSwitchMethod.PRISM_ROOT.shortLabel,
                    AccountSwitchMethod.OCR_LIST to AccountSwitchMethod.OCR_LIST.shortLabel,
                    AccountSwitchMethod.CLONE_APP to AccountSwitchMethod.CLONE_APP.shortLabel,
                    AccountSwitchMethod.HYBRID to AccountSwitchMethod.HYBRID.shortLabel
                )
                val mBtns = mutableListOf<Pair<AccountSwitchMethod?, TextView>>()
                methodOptions.chunked(2).forEach { pair ->
                    val r = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                    }
                    pair.forEachIndexed { idx, (mOpt, lbl) ->
                        val tv = makeBtn(lbl, if (mOpt == methodOverride) "#0284C7" else "#1E293B", marginEndDp = 0) {
                            methodOverride = mOpt
                            mBtns.forEach { (item, v) ->
                                (v.background as? GradientDrawable)?.setColor(Color.parseColor(if (item == methodOverride) "#0284C7" else "#1E293B"))
                            }
                        }
                        mBtns += mOpt to tv
                        r.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = if (idx < pair.lastIndex) dp(4) else 0
                        })
                    }
                    bodyContainer.addView(r)
                }

                bodyContainer.addView(dividerLine())
                bodyContainer.addView(sectionLabel("PRISM Root Cloner (Anti-BAL)"))
                val prismNextCheck = CheckBox(context).apply {
                    text = "Gunakan PRISM Switch Next otomatis (SWITCH_NEXT)"
                    isChecked = action.prismUseSwitchNext
                    setTextColor(Color.parseColor("#38BDF8"))
                    textSize = 11f
                }
                bodyContainer.addView(prismNextCheck)
                val prismTargetF = addTextRow(bodyContainer, "Package Game Target PRISM (opsional)", action.prismTargetPackage)

                bodyContainer.addView(dividerLine())
                bodyContainer.addView(sectionLabel("OCR Daftar Akun + Auto-Scroll"))
                val matchBtns = mutableListOf<Pair<AccountOcrMatchMode, TextView>>()
                val matchRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                }
                AccountOcrMatchMode.entries.forEachIndexed { idx, mm ->
                    val tv = makeBtn(mm.shortLabel, if (mm == matchMode) "#0891B2" else "#1E293B", marginEndDp = 0) {
                        matchMode = mm
                        matchBtns.forEach { (item, v) ->
                            (v.background as? GradientDrawable)?.setColor(Color.parseColor(if (item == matchMode) "#0891B2" else "#1E293B"))
                        }
                    }
                    matchBtns += mm to tv
                    matchRow.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = if (idx < AccountOcrMatchMode.entries.lastIndex) dp(4) else 0
                    })
                }
                bodyContainer.addView(matchRow)

                val dirBtns = mutableListOf<Pair<AccountScrollDirection, TextView>>()
                val dirRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                }
                AccountScrollDirection.entries.forEachIndexed { idx, sd ->
                    val tv = makeBtn(sd.shortLabel, if (sd == scrollDir) "#0891B2" else "#1E293B", marginEndDp = 0) {
                        scrollDir = sd
                        dirBtns.forEach { (item, v) ->
                            (v.background as? GradientDrawable)?.setColor(Color.parseColor(if (item == scrollDir) "#0891B2" else "#1E293B"))
                        }
                    }
                    dirBtns += sd to tv
                    dirRow.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = if (idx < AccountScrollDirection.entries.lastIndex) dp(4) else 0
                    })
                }
                bodyContainer.addView(dirRow)

                val maxScrF = addNumberRow(bodyContainer, "Maks auto-scroll (0-30)", action.maxScrolls.toString())
                val settleF = addNumberRow(bodyContainer, "Jeda setelah scroll (ms)", action.scrollSettleMs.toString())
                val reverseCheck = CheckBox(context).apply {
                    text = "Balik arah scroll otomatis di ujung daftar"
                    isChecked = action.reverseOnEnd
                    setTextColor(Color.WHITE)
                    textSize = 11f
                }
                bodyContainer.addView(reverseCheck)
                val oxF = addSignedNumberRow(bodyContainer, "Offset klik X (px)", action.clickOffsetX.toString())
                val oyF = addSignedNumberRow(bodyContainer, "Offset klik Y (px)", action.clickOffsetY.toString())

                bodyContainer.addView(dividerLine())
                bodyContainer.addView(sectionLabel("Clone App / Dual App"))
                val exitPrevCheck = CheckBox(context).apply {
                    text = "Tutup aplikasi sebelumnya sebelum buka clone"
                    isChecked = action.exitPreviousApp
                    setTextColor(Color.WHITE)
                    textSize = 11f
                }
                bodyContainer.addView(exitPrevCheck)
                val exitWaitF = addNumberRow(bodyContainer, "Jeda tutup app lama (ms)", action.exitWaitMs.toString())
                val launchWaitF = addNumberRow(bodyContainer, "Jeda loading clone app (ms)", action.launchWaitMs.toString())
                val delF = addNumberRow(bodyContainer, "Jeda sesudah aksi (ms)", action.delayAfterMs.toString())

                bodyContainer.addView(dividerLine())
                val regCheck = CheckBox(context).apply {
                    text = "Batasi area daftar akun (Region)"
                    isChecked = useRegion
                    setTextColor(Color.parseColor("#CBD5E1"))
                    textSize = 11.2f
                    setOnCheckedChangeListener { _, checked -> useRegion = checked }
                }
                bodyContainer.addView(regCheck)
                val reg = action.region ?: Region(0, 0, screenW, screenH)
                val rL = addNumberRow(bodyContainer, "Area Kiri", reg.left.toString())
                val rT = addNumberRow(bodyContainer, "Area Atas", reg.top.toString())
                val rR = addNumberRow(bodyContainer, "Area Kanan", reg.right.toString())
                val rB = addNumberRow(bodyContainer, "Area Bawah", reg.bottom.toString())

                bodyContainer.addView(dividerLine())
                bodyContainer.addView(sectionLabel("Jika Akun Dipilih (Then)"))
                addBranchActionsControls(action.id, Slot.THEN, action.thenActions, "#065F46")
                bodyContainer.addView(sectionLabel("Jika Akun Gagal Ditemukan (Else)"))
                addBranchModePicker("Setelah cabang Gagal:", notFoundMode) { notFoundMode = it }
                addBranchActionsControls(action.id, Slot.ELSE, action.elseActions, "#92400E")

                addSaveActionBtn {
                    val newRegion = if (useRegion) {
                        Region(
                            rL.text.toString().toIntOrNull() ?: reg.left,
                            rT.text.toString().toIntOrNull() ?: reg.top,
                            rR.text.toString().toIntOrNull() ?: reg.right,
                            rB.text.toString().toIntOrNull() ?: reg.bottom
                        )
                    } else null
                    saveEdited { cur ->
                        val sa = (cur as? SelectAccountAction) ?: action
                        sa.copy(
                            methodOverride = methodOverride,
                            region = newRegion,
                            matchMode = matchMode,
                            scrollDirection = scrollDir,
                            maxScrolls = (maxScrF.text.toString().toIntOrNull() ?: sa.maxScrolls).coerceIn(0, 30),
                            scrollSettleMs = (settleF.text.toString().toLongOrNull() ?: sa.scrollSettleMs).coerceIn(100L, 5000L),
                            reverseOnEnd = reverseCheck.isChecked,
                            clickOffsetX = oxF.text.toString().toIntOrNull() ?: sa.clickOffsetX,
                            clickOffsetY = oyF.text.toString().toIntOrNull() ?: sa.clickOffsetY,
                            exitPreviousApp = exitPrevCheck.isChecked,
                            exitWaitMs = (exitWaitF.text.toString().toLongOrNull() ?: sa.exitWaitMs).coerceIn(0L, 15_000L),
                            launchWaitMs = (launchWaitF.text.toString().toLongOrNull() ?: sa.launchWaitMs).coerceIn(0L, 60_000L),
                            prismUseSwitchNext = prismNextCheck.isChecked,
                            prismTargetPackage = prismTargetF.text.toString().trim(),
                            notFound = notFoundMode,
                            delayAfterMs = (delF.text.toString().toLongOrNull() ?: sa.delayAfterMs).coerceAtLeast(0L)
                        )
                    }
                }
            }

            is com.example.data.VariableAction -> {
                var op = action.operation
                var scope = action.scope
                var useRegion = action.region != null

                val nameF = addTextRow(bodyContainer, "Nama Variabel", action.variableName)

                bodyContainer.addView(sectionLabel("Operasi"))
                val opBtns = mutableListOf<Pair<com.example.data.VariableOperation, TextView>>()
                com.example.data.VariableOperation.entries.chunked(2).forEach { pair ->
                    val r = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                    }
                    pair.forEachIndexed { idx, item ->
                        val tv = makeBtn(item.label, if (item == op) "#7C3AED" else "#1E293B", marginEndDp = 0) {
                            op = item
                            opBtns.forEach { (k, v) ->
                                (v.background as? GradientDrawable)?.setColor(Color.parseColor(if (k == op) "#7C3AED" else "#1E293B"))
                            }
                        }
                        opBtns += item to tv
                        r.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = if (idx < pair.lastIndex) dp(4) else 0
                        })
                    }
                    bodyContainer.addView(r)
                }

                bodyContainer.addView(sectionLabel("Cakupan"))
                val scBtns = mutableListOf<Pair<com.example.data.VariableScope, TextView>>()
                val scRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                }
                com.example.data.VariableScope.entries.forEachIndexed { idx, sc ->
                    val tv = makeBtn(sc.label, if (sc == scope) "#0891B2" else "#1E293B", marginEndDp = 0) {
                        scope = sc
                        scBtns.forEach { (k, v) ->
                            (v.background as? GradientDrawable)?.setColor(Color.parseColor(if (k == scope) "#0891B2" else "#1E293B"))
                        }
                    }
                    scBtns += sc to tv
                    scRow.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = if (idx < com.example.data.VariableScope.entries.lastIndex) dp(4) else 0
                    })
                }
                bodyContainer.addView(scRow)

                val valStr = if (action.value == action.value.toLong().toDouble()) action.value.toLong().toString() else action.value.toString()
                val valF = addTextRow(bodyContainer, "Nilai / Tambahan", valStr)
                addPickTargetBtn("📐 Pilih Area Angka OCR di Layar (untuk Baca OCR)") {
                    onPickRegionOnScreen(action.id)
                }
                val regionCheck = CheckBox(context).apply {
                    text = if (action.region != null) {
                        "Pakai area OCR (${action.region!!.left},${action.region!!.top}–${action.region!!.right},${action.region!!.bottom})"
                    } else "Area OCR: Layar Penuh"
                    isChecked = useRegion
                    isEnabled = action.region != null
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    setOnCheckedChangeListener { _, checked -> useRegion = checked }
                }
                bodyContainer.addView(regionCheck)
                val ocrTimeoutF = addNumberRow(bodyContainer, "Timeout OCR (ms)", action.ocrTimeoutMs.toString())
                val delF = addNumberRow(bodyContainer, "Jeda setelah (ms)", action.delayAfterMs.toString())

                addSaveActionBtn {
                    saveEdited { cur ->
                        val va = (cur as? com.example.data.VariableAction) ?: action
                        va.copy(
                            variableName = nameF.text.toString().trim().take(32),
                            operation = op,
                            scope = scope,
                            value = valF.text.toString().replace(',', '.').toDoubleOrNull() ?: va.value,
                            region = if (useRegion) va.region else null,
                            ocrTimeoutMs = (ocrTimeoutF.text.toString().toLongOrNull() ?: va.ocrTimeoutMs).coerceIn(0L, 30_000L),
                            delayAfterMs = (delF.text.toString().toLongOrNull() ?: va.delayAfterMs).coerceAtLeast(0L)
                        )
                    }
                }
            }

            is com.example.data.CheckVariableAction -> {
                var op = action.operator
                var scope = action.scope
                var foundMode = action.foundMode
                var notFoundMode = action.notFound

                val nameF = addTextRow(bodyContainer, "Nama Variabel", action.variableName)
                bodyContainer.addView(sectionLabel("Operator Perbandingan"))
                val opBtns = mutableListOf<Pair<com.example.data.VariableCompareOp, TextView>>()
                val opRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                }
                com.example.data.VariableCompareOp.entries.forEachIndexed { idx, item ->
                    val tv = makeBtn(item.symbol, if (item == op) "#DB2777" else "#1E293B", marginEndDp = 0) {
                        op = item
                        opBtns.forEach { (k, v) ->
                            (v.background as? GradientDrawable)?.setColor(Color.parseColor(if (k == op) "#DB2777" else "#1E293B"))
                        }
                    }
                    opBtns += item to tv
                    opRow.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = if (idx < com.example.data.VariableCompareOp.entries.lastIndex) dp(3) else 0
                    })
                }
                bodyContainer.addView(opRow)

                val targetStr = if (action.targetValue == action.targetValue.toLong().toDouble()) action.targetValue.toLong().toString() else action.targetValue.toString()
                val targetF = addTextRow(bodyContainer, "Angka Target", targetStr)
                val cmpVarF = addTextRow(bodyContainer, "Atau Variabel Lain (opsional)", action.compareToVariable)

                bodyContainer.addView(sectionLabel("Cakupan Variabel"))
                val scBtns = mutableListOf<Pair<com.example.data.VariableScope, TextView>>()
                val scRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                }
                com.example.data.VariableScope.entries.forEachIndexed { idx, sc ->
                    val tv = makeBtn(sc.label, if (sc == scope) "#0891B2" else "#1E293B", marginEndDp = 0) {
                        scope = sc
                        scBtns.forEach { (k, v) ->
                            (v.background as? GradientDrawable)?.setColor(Color.parseColor(if (k == scope) "#0891B2" else "#1E293B"))
                        }
                    }
                    scBtns += sc to tv
                    scRow.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = if (idx < com.example.data.VariableScope.entries.lastIndex) dp(4) else 0
                    })
                }
                bodyContainer.addView(scRow)

                bodyContainer.addView(sectionLabel("Jika BENAR"))
                val fmBtns = mutableListOf<Pair<NotFoundMode, TextView>>()
                NotFoundMode.entries.chunked(2).forEach { pair ->
                    val r = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                    }
                    pair.forEachIndexed { idx, m ->
                        val tv = makeBtn(m.label, if (m == foundMode) "#059669" else "#1E293B", marginEndDp = 0) {
                            foundMode = m
                            fmBtns.forEach { (k, v) ->
                                (v.background as? GradientDrawable)?.setColor(Color.parseColor(if (k == foundMode) "#059669" else "#1E293B"))
                            }
                        }
                        fmBtns += m to tv
                        r.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = if (idx < pair.lastIndex) dp(4) else 0
                        })
                    }
                    bodyContainer.addView(r)
                }

                bodyContainer.addView(sectionLabel("Jika SALAH"))
                val nfBtns = mutableListOf<Pair<NotFoundMode, TextView>>()
                NotFoundMode.entries.chunked(2).forEach { pair ->
                    val r = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                    }
                    pair.forEachIndexed { idx, m ->
                        val tv = makeBtn(m.label, if (m == notFoundMode) "#D97706" else "#1E293B", marginEndDp = 0) {
                            notFoundMode = m
                            nfBtns.forEach { (k, v) ->
                                (v.background as? GradientDrawable)?.setColor(Color.parseColor(if (k == notFoundMode) "#D97706" else "#1E293B"))
                            }
                        }
                        nfBtns += m to tv
                        r.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = if (idx < pair.lastIndex) dp(4) else 0
                        })
                    }
                    bodyContainer.addView(r)
                }

                val delF = addNumberRow(bodyContainer, "Jeda setelah (ms)", action.delayAfterMs.toString())
                addSaveActionBtn {
                    saveEdited { cur ->
                        val cv = (cur as? com.example.data.CheckVariableAction) ?: action
                        cv.copy(
                            variableName = nameF.text.toString().trim().take(32),
                            operator = op,
                            targetValue = targetF.text.toString().replace(',', '.').toDoubleOrNull() ?: cv.targetValue,
                            compareToVariable = cmpVarF.text.toString().trim().take(32),
                            scope = scope,
                            foundMode = foundMode,
                            notFound = notFoundMode,
                            delayAfterMs = (delF.text.toString().toLongOrNull() ?: cv.delayAfterMs).coerceAtLeast(0L)
                        )
                    }
                }
            }

            is com.example.data.NotifyAction -> {
                var level = action.level
                var sendRemote = action.sendRemote
                var includeShot = action.includeScreenshot
                var phoneAlarm = action.triggerPhoneAlarm

                bodyContainer.addView(TextView(context).apply {
                    text = "🔔 Kirim Notifikasi Telegram/Discord & Alarm HP (dukung {macro}, {loop}, {actions}, {account}, {var:nama})"
                    setTextColor(Color.parseColor("#F59E0B"))
                    textSize = 11f
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                })

                val lvlBtns = mutableListOf<Pair<com.example.data.NotifyLevel, TextView>>()
                fun refreshLvl() {
                    lvlBtns.forEach { (l, tv) ->
                        val active = l == level
                        tv.background = GradientDrawable().apply {
                            setColor(if (active) Color.parseColor("#D97706") else Color.parseColor("#1E293B"))
                            cornerRadius = dp(7).toFloat()
                        }
                        tv.setTextColor(if (active) Color.WHITE else Color.parseColor("#94A3B8"))
                    }
                }
                val lvlRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                }
                com.example.data.NotifyLevel.entries.forEachIndexed { idx, l ->
                    val tv = TextView(context).apply {
                        text = "${l.icon} ${l.label}"
                        textSize = 10f
                        gravity = Gravity.CENTER
                        setPadding(dp(4), dp(6), dp(4), dp(6))
                        setOnClickListener { level = l; refreshLvl() }
                    }
                    lvlBtns += l to tv
                    lvlRow.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = if (idx < com.example.data.NotifyLevel.entries.lastIndex) dp(4) else 0
                    })
                }
                bodyContainer.addView(lvlRow)
                refreshLvl()

                val titleF = addTextRow(bodyContainer, "Judul notifikasi", action.title)
                val msgF = addTextRow(bodyContainer, "Pesan", action.message)
                val remoteBtn = makeBtn("", "#1E293B", marginEndDp = 0) {}
                val shotBtn = makeBtn("", "#1E293B", marginEndDp = 0) {}
                val alarmBtn = makeBtn("", "#1E293B", marginEndDp = 0) {}
                fun refreshFlags() {
                    remoteBtn.text = if (sendRemote) "✓ Kirim ke Telegram / Discord" else "○ Tidak kirim ke Telegram / Discord"
                    remoteBtn.background = GradientDrawable().apply {
                        setColor(if (sendRemote) Color.parseColor("#0EA5E9") else Color.parseColor("#1E293B"))
                        cornerRadius = dp(8).toFloat()
                    }
                    shotBtn.text = if (includeShot) "✓ Lampirkan Screenshot Layar" else "○ Tanpa Screenshot Layar"
                    shotBtn.background = GradientDrawable().apply {
                        setColor(if (includeShot) Color.parseColor("#10B981") else Color.parseColor("#1E293B"))
                        cornerRadius = dp(8).toFloat()
                    }
                    alarmBtn.text = if (phoneAlarm) "✓ Bunyikan Alarm & Getar HP" else "○ Tanpa Alarm HP Lokal"
                    alarmBtn.background = GradientDrawable().apply {
                        setColor(if (phoneAlarm) Color.parseColor("#D97706") else Color.parseColor("#1E293B"))
                        cornerRadius = dp(8).toFloat()
                    }
                }
                remoteBtn.setOnClickListener { sendRemote = !sendRemote; refreshFlags() }
                shotBtn.setOnClickListener { includeShot = !includeShot; refreshFlags() }
                alarmBtn.setOnClickListener { phoneAlarm = !phoneAlarm; refreshFlags() }
                listOf(remoteBtn, shotBtn, alarmBtn).forEach { b ->
                    bodyContainer.addView(b, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(5) })
                }
                refreshFlags()

                val delF = addNumberRow(bodyContainer, "Jeda setelah (ms)", action.delayAfterMs.toString())
                addSaveActionBtn {
                    saveEdited { cur ->
                        val na = (cur as? com.example.data.NotifyAction) ?: action
                        na.copy(
                            title = titleF.text.toString().trim().take(80),
                            message = msgF.text.toString().trim().take(400),
                            level = level,
                            sendRemote = sendRemote,
                            includeScreenshot = includeShot,
                            triggerPhoneAlarm = phoneAlarm,
                            delayAfterMs = (delF.text.toString().toLongOrNull() ?: na.delayAfterMs).coerceAtLeast(0L)
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

    private fun buildInstalledAppPickerView(action: Action) {
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        bar.addView(makeBtn("← Kembali", "#334155", marginEndDp = 8) {
            appPickerActionId = null
            rebuildBody()
        })
        bar.addView(TextView(context).apply {
            text = "Pilih Aplikasi Terpasang"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        })
        bodyContainer.addView(bar)

        val searchF = addTextRow(bodyContainer, "Cari nama / package", "")
        val listHolder = LinearLayout(context).apply { orientation = VERTICAL }
        bodyContainer.addView(listHolder)

        val allApps = InstalledAppsHelper.loadLaunchableApps(context)

        fun renderFiltered(queryRaw: String) {
            listHolder.removeAllViews()
            val q = queryRaw.trim().lowercase()
            if (action is ExitAppAction) {
                listHolder.addView(makeBtn("Kosongkan (aplikasi apa saja yang aktif)", "#475569", marginEndDp = 0) {
                    onUpdateAction(action.id) { cur ->
                        if (cur is ExitAppAction) cur.copy(packageName = "", appLabel = "") else cur
                    }
                    appPickerActionId = null
                    editingActionId = action.id
                    rebuildBody()
                }.apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                })
            }
            val matched = if (q.isEmpty()) allApps else allApps.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
            if (matched.isEmpty()) {
                listHolder.addView(TextView(context).apply {
                    text = "Tidak ada aplikasi cocok."
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 11f
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                })
                return
            }
            for (info in matched.take(60)) {
                val item = LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1E293B"))
                        cornerRadius = dp(9).toFloat()
                    }
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(4)
                    }
                    setOnClickListener {
                        onUpdateAction(action.id) { cur ->
                            when (cur) {
                                is LaunchAppAction -> cur.copy(packageName = info.packageName, appLabel = info.label)
                                is ExitAppAction -> cur.copy(packageName = info.packageName, appLabel = info.label)
                                else -> cur
                            }
                        }
                        appPickerActionId = null
                        editingActionId = action.id
                        rebuildBody()
                    }
                }
                item.addView(TextView(context).apply {
                    text = info.label
                    setTextColor(Color.WHITE)
                    textSize = 11.5f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                item.addView(TextView(context).apply {
                    text = info.packageName
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 9.5f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                listHolder.addView(item)
            }
        }

        searchF.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                renderFiltered(s?.toString().orEmpty())
            }
        })
        renderFiltered("")
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
        val isStuckParent = macro.isStuckAction(parentId)
        val isSwitchParent = macro.isSwitchAccountAction(parentId)
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
            onAddBranchClickTargetOnScreen(parentId, slot, isStuckParent)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.15f).apply { marginEnd = dp(5) })
        btnRow.addView(makeBtn("+ Aksi Lain (${children.size})", accentBgHex) {
            addPickerForStuck = isStuckParent
            addPickerForSwitchAccount = isSwitchParent
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
        val shortLabels = mapOf(
            NotFoundMode.CONTINUE to "Lanjut",
            NotFoundMode.SWITCH_BRANCH to "Pindah Cbg",
            NotFoundMode.CALL_BRANCH_RETURN to "Singgah",
            NotFoundMode.RESTART to "Ulangi",
            NotFoundMode.FINISH to "Selesai",
            NotFoundMode.STOP to "Stop"
        )
        NotFoundMode.entries.chunked(3).forEach { chunk ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
            }
            chunk.forEachIndexed { idx, mode ->
                val b = makeBtn(shortLabels[mode] ?: mode.label, if (mode == current) "#0284C7" else "#1E293B", marginEndDp = 0) {
                    current = mode
                    onSelect(mode)
                    btns.forEach { (m, tv) ->
                        (tv.background as? GradientDrawable)?.setColor(Color.parseColor(if (m == current) "#0284C7" else "#1E293B"))
                    }
                }
                btns += mode to b
                row.addView(b, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = if (idx < chunk.lastIndex) dp(4) else 0
                })
            }
            bodyContainer.addView(row)
        }
    }

    private fun addSaveActionBtn(onClick: () -> Unit) {
        bodyContainer.addView(makeBtn("Simpan Perubahan Aksi", "#0D9488", onClick = onClick).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
                bottomMargin = dp(4)
            }
        })
    }

    private fun buildQuickImageSwapView(action: Action) {
        val currentImageName = when (action) {
            is ImageAction -> action.imageName
            is TrackObjectAction -> action.imageName
            else -> ""
        }
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
        if (currentImageName.isNotBlank() && store.imageFile(currentImageName).exists()) {
            quickBtns.addView(makeBtn("✂ Potong Ulang", "#0284C7") {
                quickImageSwapActionId = null
                onRecropExistingImage(currentImageName)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        bodyContainer.addView(quickBtns)

        addVisualImageSelector(
            initialSelected = currentImageName,
            onSelect = { chosen ->
                onUpdateAction(action.id) { cur ->
                    when (cur) {
                        is ImageAction -> cur.copy(imageName = chosen)
                        is TrackObjectAction -> cur.copy(
                            imageName = chosen,
                            extraImageNames = cur.extraImageNames.filter { it != chosen }
                        )
                        else -> cur
                    }
                }
                quickImageSwapActionId = null
            }
        )
    }

    private fun addVisualImageSelector(
        initialSelected: String,
        onSelect: (String) -> Unit,
        targetParent: LinearLayout = bodyContainer
    ) {
        val allImages = store.images.value
        val folders = store.imageFolders.value
        if (allImages.isEmpty()) {
            targetParent.addView(TextView(context).apply {
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

        targetParent.addView(sectionLabel("Pilih Gambar (${allImages.size})"))
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
            targetParent.addView(folderContainer)
        }
        targetParent.addView(thumbStripContainer)
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
        textSize = 10.2f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(4), dp(6), dp(4), dp(6))
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
