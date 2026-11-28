package com.example.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.automation.FloatingOverlayService
import com.example.automation.PickedPixel
import com.example.data.Action
import com.example.data.ActionKind
import com.example.data.ActionRemoteControl
import com.example.data.ClickAction
import com.example.data.RemoteActionMode
import com.example.data.RemoteIdSanitizer
import com.example.data.withActionRemoteControl
import com.example.data.ColorAction
import com.example.data.ContainerRef
import com.example.data.ExitAppAction
import com.example.data.GoToBranchAction
import com.example.data.ImageAction
import com.example.data.TrackObjectAction
import com.example.data.ImageAsset
import com.example.data.LaunchAppAction
import com.example.data.Macro
import com.example.data.MacroBranch
import com.example.data.MacroSecurity
import com.example.data.ResolutionScaleMode
import com.example.data.ResolutionScaler
import com.example.data.Slot
import com.example.data.StuckRecoveryMode
import com.example.data.TextAction
import com.example.data.WaitAction
import com.example.data.actionsForBranch
import com.example.data.childList
import com.example.data.create
import com.example.data.duplicateAction
import com.example.data.insertInto
import com.example.data.isStuckAction
import com.example.data.kind
import com.example.data.moveAction
import com.example.data.moveActionToBranch
import com.example.data.newId
import com.example.data.ownerListForAction
import com.example.data.removeAction
import com.example.data.reorderInContainer
import com.example.data.slots
import com.example.data.summary
import com.example.data.updateAction
import com.example.data.updateAnyAction
import com.example.data.updateBranchActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MacroEditorScreen(
    macro: Macro,
    allMacros: List<Macro>,
    images: List<ImageAsset>,
    imageFolders: List<String>,
    lastPickedPixel: PickedPixel?,
    logoPath: String?,
    screenW: Int,
    screenH: Int,
    snackbarHostState: androidx.compose.material3.SnackbarHostState? = null,
    onUpdateMacro: (Macro) -> Unit,
    onRecropImage: (String, Bitmap) -> Unit,
    onChangeLogo: () -> Unit,
    onOpenSecurity: () -> Unit,
    onTogglePassivePull: () -> Unit,
    onLaunchOverlay: (Macro) -> Unit,
    onLaunchCropOverlay: (Macro, String?) -> Unit,
    onLaunchTargetPickerOverlay: (Macro, String) -> Unit,
    onLaunchRegionPickerOverlay: (Macro, String) -> Unit,
    onLaunchRecorderOverlay: (Macro) -> Unit,
    onBack: () -> Unit
) {
    var editorTab by remember(macro.id) { mutableIntStateOf(0) } // 0 = Alur/Aksi, 1 = Anti-Macet, 2 = Banned Guard, 3 = Ganti Akun
    var selectedBranchId by remember(macro.id) { mutableStateOf("") } // "" = Jalur Utama
    var showCreateBranchDialog by remember { mutableStateOf(false) }
    var editingBranch by remember { mutableStateOf<MacroBranch?>(null) }
    var pendingDeleteBranch by remember { mutableStateOf<MacroBranch?>(null) }
    var movingBranchAction by remember { mutableStateOf<Action?>(null) }
    var editingAction by remember { mutableStateOf<Action?>(null) }
    var quickSwapImageAction by remember { mutableStateOf<Action?>(null) }
    var addTargetRef by remember { mutableStateOf<ContainerRef?>(null) }
    var showMacroSettings by remember { mutableStateOf(false) }

    LaunchedEffect(macro.branches, selectedBranchId) {
        if (selectedBranchId.isNotBlank() && macro.branches.none { it.id == selectedBranchId }) {
            selectedBranchId = ""
        }
    }
    val selectedBranch = remember(macro.branches, selectedBranchId) {
        if (selectedBranchId.isBlank()) null else macro.branches.firstOrNull { it.id == selectedBranchId }
    }

    // Smooth in-memory drag preview so drag-and-drop runs at full frame rate without disk I/O jitter.
    val latestMacro by rememberUpdatedState(macro)
    val latestOnUpdateMacro by rememberUpdatedState(onUpdateMacro)
    val latestEditorTab by rememberUpdatedState(editorTab)
    val latestSelectedBranchId by rememberUpdatedState(selectedBranchId)
    var dragPreviewActions by remember(macro.id, editorTab, selectedBranchId) { mutableStateOf<List<Action>?>(null) }
    val activeSourceList = when (editorTab) {
        0 -> macro.actionsForBranch(selectedBranchId)
        1 -> macro.stuckActions
        3 -> macro.accountSwitch.switchActions
        else -> emptyList()
    }
    val displayedActions = dragPreviewActions ?: activeSourceList

    val listState = rememberLazyListState()
    var draggingRootId by remember(macro.id, editorTab, selectedBranchId) { mutableStateOf<String?>(null) }
    var draggingRootOffsetY by remember(macro.id, editorTab, selectedBranchId) { mutableFloatStateOf(0f) }
    var overscrollSpeed by remember(macro.id, editorTab, selectedBranchId) { mutableFloatStateOf(0f) }

    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edgeZonePx = with(density) { 72.dp.toPx() }
    val scrollStepPx = with(density) { 16.dp.toPx() }

    val imageByName = remember(images) { images.associateBy { it.name } }
    val macroLogoBmp = rememberAssetThumbnail(logoPath, macro.updatedAt)

    fun mutateActions(block: (List<Action>) -> List<Action>) {
        dragPreviewActions = null
        val cur = latestMacro
        when (latestEditorTab) {
            0 -> latestOnUpdateMacro(cur.updateBranchActions(latestSelectedBranchId, block))
            1 -> {
                val wasEmpty = cur.stuckActions.isEmpty()
                val nextStuck = block(cur.stuckActions)
                latestOnUpdateMacro(
                    cur.copy(
                        stuckActions = nextStuck,
                        stuckRecoveryMode = if (wasEmpty && nextStuck.isNotEmpty()) StuckRecoveryMode.RUN_WATCHDOG_ACTIONS else cur.stuckRecoveryMode,
                        autoRecoverStuckSec = if (wasEmpty && nextStuck.isNotEmpty() && cur.autoRecoverStuckSec <= 0) 60 else cur.autoRecoverStuckSec
                    )
                )
            }
            3 -> {
                val wasEmpty = cur.accountSwitch.switchActions.isEmpty()
                val nextSwitch = block(cur.accountSwitch.switchActions)
                latestOnUpdateMacro(
                    cur.copy(
                        accountSwitch = cur.accountSwitch.copy(
                            enabled = if (wasEmpty && nextSwitch.isNotEmpty()) true else cur.accountSwitch.enabled,
                            switchActions = nextSwitch
                        )
                    )
                )
            }
        }
    }

    fun previewReorder(ref: ContainerRef, fromIndex: Int, toIndex: Int) {
        val current = dragPreviewActions ?: when (latestEditorTab) {
            0 -> latestMacro.actionsForBranch(latestSelectedBranchId)
            1 -> latestMacro.stuckActions
            3 -> latestMacro.accountSwitch.switchActions
            else -> emptyList()
        }
        val reordered = current.reorderInContainer(ref, fromIndex, toIndex)
        if (reordered !== current) {
            dragPreviewActions = reordered
        }
    }

    fun commitDragPreview() {
        val pending = dragPreviewActions
        dragPreviewActions = null
        val cur = latestMacro
        when (latestEditorTab) {
            0 -> {
                val curList = cur.actionsForBranch(latestSelectedBranchId)
                if (pending != null && pending != curList) {
                    latestOnUpdateMacro(cur.updateBranchActions(latestSelectedBranchId) { pending })
                }
            }
            1 -> {
                if (pending != null && pending != cur.stuckActions) {
                    latestOnUpdateMacro(cur.copy(stuckActions = pending))
                }
            }
            3 -> {
                if (pending != null && pending != cur.accountSwitch.switchActions) {
                    latestOnUpdateMacro(cur.copy(accountSwitch = cur.accountSwitch.copy(switchActions = pending)))
                }
            }
        }
    }

    fun checkRootSwap(actionId: String) {
        val actionsList = dragPreviewActions ?: when (latestEditorTab) {
            0 -> latestMacro.actionsForBranch(latestSelectedBranchId)
            1 -> latestMacro.stuckActions
            3 -> latestMacro.accountSwitch.switchActions
            else -> emptyList()
        }
        val fromIdx = actionsList.indexOfFirst { it.id == actionId }
        if (fromIdx < 0) return

        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val draggedInfo = visibleItems.firstOrNull { it.key == actionId } ?: return
        val draggedTop = draggedInfo.offset + draggingRootOffsetY
        val draggedBottom = draggedTop + draggedInfo.size

        val vpStart = listState.layoutInfo.viewportStartOffset
        val vpEnd = listState.layoutInfo.viewportEndOffset
        overscrollSpeed = when {
            draggedBottom > vpEnd - edgeZonePx -> scrollStepPx
            draggedTop < vpStart + edgeZonePx -> -scrollStepPx
            else -> 0f
        }

        if (draggingRootOffsetY > 0f && fromIdx < actionsList.lastIndex) {
            val nextId = actionsList[fromIdx + 1].id
            val nextInfo = visibleItems.firstOrNull { it.key == nextId }
            // Guard nextInfo.offset > draggedInfo.offset to avoid re-swapping before layout catches up
            if (nextInfo != null && nextInfo.offset > draggedInfo.offset && draggedBottom > nextInfo.offset + nextInfo.size / 2f) {
                val spacing = (nextInfo.offset - (draggedInfo.offset + draggedInfo.size)).toFloat().coerceAtLeast(0f)
                previewReorder(ContainerRef.ROOT, fromIdx, fromIdx + 1)
                draggingRootOffsetY -= (nextInfo.size + spacing)
                listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        } else if (draggingRootOffsetY < 0f && fromIdx > 0) {
            val prevId = actionsList[fromIdx - 1].id
            val prevInfo = visibleItems.firstOrNull { it.key == prevId }
            // Guard prevInfo.offset < draggedInfo.offset to avoid re-swapping before layout catches up
            if (prevInfo != null && prevInfo.offset < draggedInfo.offset && draggedTop < prevInfo.offset + prevInfo.size / 2f) {
                val spacing = (draggedInfo.offset - (prevInfo.offset + prevInfo.size)).toFloat().coerceAtLeast(0f)
                previewReorder(ContainerRef.ROOT, fromIdx, fromIdx - 1)
                draggingRootOffsetY += (prevInfo.size + spacing)
                listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    // Smooth edge auto-scrolling when dragging near top or bottom of the LazyColumn
    LaunchedEffect(draggingRootId) {
        val activeId = draggingRootId ?: return@LaunchedEffect
        while (isActive && draggingRootId == activeId) {
            val speed = overscrollSpeed
            if (speed != 0f) {
                val consumed = listState.scrollBy(speed)
                if (consumed != 0f) {
                    draggingRootOffsetY += consumed
                    checkRootSwap(activeId)
                }
            }
            delay(16L)
        }
    }

    Scaffold(
        snackbarHost = {
            if (snackbarHostState != null) {
                androidx.compose.material3.SnackbarHost(snackbarHostState)
            }
        },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (macroLogoBmp != null) {
                            Image(
                                bitmap = macroLogoBmp,
                                contentDescription = "Ubah Foto Logo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(onClick = onChangeLogo)
                            )
                        }
                        Column {
                            Text(
                                macro.name.ifBlank { "Macro Baru" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            val loopStr = if (macro.loopCount <= 0) "Loop ∞" else "Loop ${macro.loopCount}×"
                            Text(
                                "$loopStr • Jeda ${macro.loopDelayMs}ms" + if (macro.jitterPx > 0) " • Acak ±${macro.jitterPx}px" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSecurity) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = "Kunci, Tenggat & Mode Pasif",
                            tint = if (MacroSecurity.hasAnyProtection(macro)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showMacroSettings = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Pengaturan Macro")
                    }
                    FilledTonalButton(
                        onClick = { onLaunchOverlay(macro) },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Overlay", style = MaterialTheme.typography.labelLarge)
                    }
                }
            )
        },
        floatingActionButton = {
            if (editorTab == 2) {
                ExtendedFloatingActionButton(
                    onClick = { onLaunchCropOverlay(macro, FloatingOverlayService.TARGET_ADD_BANNED_IMAGE) },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = Color(0xFFDC2626),
                    contentColor = Color.White,
                    icon = { Icon(Icons.Filled.Crop, contentDescription = null) },
                    text = {
                        Text(
                            "Crop Banned",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            } else {
                ExtendedFloatingActionButton(
                    onClick = { addTargetRef = ContainerRef.ROOT },
                    shape = RoundedCornerShape(16.dp),
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = {
                        Text(
                            when {
                                editorTab == 1 -> "Tambah Aksi Anti-Macet"
                                editorTab == 3 -> "Tambah Aksi Ganti Akun"
                                selectedBranch != null -> "Tambah ke ${selectedBranch.name}"
                                else -> "Tambah Aksi"
                            },
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Mode Switcher: Aksi/Alur | Anti-Macet | Banned Guard | Ganti Akun
            item(key = "editor_tab_switcher") {
                val mainTabLabel = if (macro.branches.isEmpty()) {
                    "Aksi (${macro.actions.size})"
                } else {
                    "Alur (${macro.actions.size}+${macro.branches.size})"
                }
                val tabLabels = listOf(
                    mainTabLabel,
                    "Macet (${macro.stuckActions.size})",
                    "⛔ Ban (${macro.bannedImages.size})",
                    "👥 Akun (${macro.accountSwitch.accounts.size})"
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    tabLabels.forEachIndexed { idx, label ->
                        SegmentedButton(
                            selected = editorTab == idx,
                            onClick = {
                                editorTab = idx
                                dragPreviewActions = null
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = idx, count = tabLabels.size)
                        ) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            if (editorTab == 0) {
                // Sequence Branch Selector Bar: Jalur Utama + Named Branches + [+ Cabang]
                item(key = "branch_chip_bar") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            item(key = "branch_main") {
                                val isMainSelected = selectedBranchId.isBlank()
                                FilterChip(
                                    selected = isMainSelected,
                                    onClick = {
                                        selectedBranchId = ""
                                        dragPreviewActions = null
                                    },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF10B981))
                                        )
                                    },
                                    label = {
                                        Text(
                                            "Jalur Utama (${macro.actions.size})",
                                            fontWeight = if (isMainSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                )
                            }
                            items(macro.branches, key = { it.id }) { branch ->
                                val isSel = selectedBranchId == branch.id
                                val dotColor = parseHexColorOrNull(branch.colorHex) ?: Color(0xFFF59E0B)
                                FilterChip(
                                    selected = isSel,
                                    onClick = {
                                        selectedBranchId = branch.id
                                        dragPreviewActions = null
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = dotColor.copy(alpha = 0.20f),
                                        selectedLabelColor = MaterialTheme.colorScheme.onSurface
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = isSel,
                                        selectedBorderColor = dotColor.copy(alpha = 0.75f)
                                    ),
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(dotColor)
                                        )
                                    },
                                    label = {
                                        Text(
                                            "${branch.name} (${branch.actions.size})",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                )
                            }
                            item(key = "branch_add") {
                                OutlinedButton(
                                    onClick = { showCreateBranchDialog = true },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Cabang", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }

                        if (selectedBranch != null) {
                            val bColor = parseHexColorOrNull(selectedBranch.colorHex) ?: Color(0xFFF59E0B)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = bColor.copy(alpha = 0.10f),
                                border = BorderStroke(1.dp, bColor.copy(alpha = 0.40f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.AltRoute,
                                            contentDescription = null,
                                            tint = bColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Column {
                                            Text(
                                                "Cabang: ${selectedBranch.name}",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = bColor
                                            )
                                            Text(
                                                "Jalur alternatif — panggil via 'Cek Hasil & Pilih Arah', 'Pindah Cabang', atau cabang Deteksi.",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        IconButton(
                                            onClick = { editingBranch = selectedBranch },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Filled.Edit, contentDescription = "Ubah Cabang", modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(
                                            onClick = { pendingDeleteBranch = selectedBranch },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.DeleteOutline,
                                                contentDescription = "Hapus Cabang",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Quick capture shortcuts for Main Actions
                item(key = "quick_tools") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onLaunchCropOverlay(macro, null) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp)
                        ) {
                            Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Crop Gambar", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { onLaunchRecorderOverlay(macro) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp)
                        ) {
                            Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Rekam Sentuhan", maxLines = 1)
                        }
                    }
                }
            } else if (editorTab == 1) {
                // Watchdog Anti-Macet Configuration Card (out-of-band recovery actions)
                item(key = "watchdog_config") {
                    val watchdogActive = macro.autoRecoverStuckSec > 0 && macro.stuckRecoveryMode == StuckRecoveryMode.RUN_WATCHDOG_ACTIONS
                    var secText by remember(macro.id, macro.autoRecoverStuckSec) {
                        mutableStateOf(if (macro.autoRecoverStuckSec > 0) macro.autoRecoverStuckSec.toString() else "60")
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFF97316).copy(alpha = 0.09f),
                        border = BorderStroke(1.dp, Color(0xFFF97316).copy(alpha = 0.42f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Shield,
                                        contentDescription = null,
                                        tint = Color(0xFFF97316),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column {
                                        Text(
                                            "Watchdog Anti-Macet (Di Luar Aksi Utama)",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFF97316)
                                        )
                                        Text(
                                            "Jika aksi utama tidak ada progres / macet sesuai batas detik, daftar aksi di bawah otomatis aktif untuk memulihkan.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Switch(
                                    checked = watchdogActive,
                                    onCheckedChange = { on ->
                                        val parsed = (secText.toIntOrNull() ?: 60).coerceIn(5, 3600)
                                        onUpdateMacro(
                                            macro.copy(
                                                autoRecoverStuckSec = if (on) parsed else 0,
                                                stuckRecoveryMode = if (on) StuckRecoveryMode.RUN_WATCHDOG_ACTIONS else macro.stuckRecoveryMode
                                            )
                                        )
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = secText,
                                    onValueChange = { raw ->
                                        val digits = raw.filter(Char::isDigit).take(4)
                                        secText = digits
                                        val v = digits.toIntOrNull()
                                        if (v != null && v > 0) {
                                            onUpdateMacro(
                                                macro.copy(
                                                    autoRecoverStuckSec = v.coerceIn(5, 3600),
                                                    stuckRecoveryMode = StuckRecoveryMode.RUN_WATCHDOG_ACTIONS
                                                )
                                            )
                                        }
                                    },
                                    label = { Text("Deteksi macet setelah (detik)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(20, 45, 60, 120, 180).forEach { presetSec ->
                                    FilterChip(
                                        selected = macro.autoRecoverStuckSec == presetSec,
                                        onClick = {
                                            secText = presetSec.toString()
                                            onUpdateMacro(
                                                macro.copy(
                                                    autoRecoverStuckSec = presetSec,
                                                    stuckRecoveryMode = StuckRecoveryMode.RUN_WATCHDOG_ACTIONS
                                                )
                                            )
                                        },
                                        label = { Text("${presetSec}d", style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Setelah selesai: ulangi aksi utama dari awal",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = macro.stuckRestartAfter,
                                    onCheckedChange = { onUpdateMacro(macro.copy(stuckRestartAfter = it)) }
                                )
                            }

                            FilledTonalButton(
                                onClick = {
                                    val launchAct = LaunchAppAction(delayAfterMs = 8000L)
                                    val template = listOf(
                                        ExitAppAction(delayAfterMs = 1500L),
                                        WaitAction(durationMs = 1500L, delayAfterMs = 0L),
                                        launchAct
                                    )
                                    onUpdateMacro(
                                        macro.copy(
                                            stuckActions = macro.stuckActions + template,
                                            stuckRecoveryMode = StuckRecoveryMode.RUN_WATCHDOG_ACTIONS,
                                            autoRecoverStuckSec = if (macro.autoRecoverStuckSec <= 0) 60 else macro.autoRecoverStuckSec
                                        )
                                    )
                                    editingAction = launchAct
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Tambah Template: Keluar & Masuk Ulang Aplikasi", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            } else if (editorTab == 2) {
                // Always-Active Banned Image Guard (Watchdog-style click blocker)
                item(key = "banned_guard_tab") {
                    MacroBannedGuardCard(
                        macro = macro,
                        images = images,
                        imageFolders = imageFolders,
                        onUpdateMacro = onUpdateMacro,
                        onCropNewBannedImage = {
                            onLaunchCropOverlay(macro, FloatingOverlayService.TARGET_ADD_BANNED_IMAGE)
                        }
                    )
                }
            } else {
                // Universal Multi-Account Switcher Tab (OCR + Auto-Scroll / Clone App / Hybrid)
                item(key = "account_switch_tab") {
                    MacroAccountSwitchCard(
                        macro = macro,
                        images = images,
                        onUpdateMacro = onUpdateMacro,
                        onEditCreatedAction = { created -> editingAction = created }
                    )
                }
            }

            if (editorTab != 2 && displayedActions.size >= 2) {
                item(key = "drag_hint") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Filled.DragIndicator,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            "Seret ikon pegangan di kiri kartu untuk mengurutkan aksi",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (editorTab != 2 && displayedActions.isEmpty()) {
                item(key = "empty") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(26.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                when {
                                    editorTab == 1 -> "Belum ada aksi Anti-Macet"
                                    editorTab == 3 -> "Alur Ganti Akun memakai langkah otomatis"
                                    selectedBranch != null -> "Belum ada aksi di cabang '${selectedBranch.name}'"
                                    else -> "Belum ada aksi utama"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                when {
                                    editorTab == 1 -> "Aksi di sini terpisah dari aksi utama dan otomatis aktif saat aksi utama macet. Ketuk 'Tambah Template' di atas atau '+ Tambah Aksi Anti-Macet' di bawah."
                                    editorTab == 3 -> "Jika dibiarkan kosong, engine otomatis menjalankan 'Pilih / Buka Akun Giliran'. Ketuk tombol Template di atas atau '+ Tambah Aksi Ganti Akun' untuk menyusun tombol Setting/Switch khusus game Anda."
                                    selectedBranch != null -> "Tambahkan langkah alternatif yang ingin dijalankan ketika macro berbelok ke cabang ini. Setelah selesai, Anda bisa menambahkan 'Pindah Cabang' kembali ke Jalur Utama."
                                    else -> "Ketuk 'Tambah Aksi' di bawah untuk menambah Klik, Deteksi Gambar, Cek Hasil & Pilih Arah, Pindah Cabang, Ganti Akun, Deteksi Teks/Nilai, Deteksi Warna, Keluar/Masuk Aplikasi, Swipe, atau pakai 'Crop Gambar' / 'Rekam Sentuhan'."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else if (editorTab != 2) {
                itemsIndexed(displayedActions, key = { _, a -> a.id }) { index, action ->
                    val isDragging = draggingRootId == action.id
                    val dragHandleMod = if (displayedActions.size > 1) {
                        Modifier.pointerInput(action.id) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    draggingRootId = action.id
                                    draggingRootOffsetY = 0f
                                    overscrollSpeed = 0f
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    if (draggingRootId == action.id) {
                                        draggingRootOffsetY += dragAmount
                                        checkRootSwap(action.id)
                                    }
                                },
                                onDragEnd = {
                                    draggingRootId = null
                                    draggingRootOffsetY = 0f
                                    overscrollSpeed = 0f
                                    commitDragPreview()
                                },
                                onDragCancel = {
                                    draggingRootId = null
                                    draggingRootOffsetY = 0f
                                    overscrollSpeed = 0f
                                    commitDragPreview()
                                }
                            )
                        }
                    } else {
                        Modifier
                    }

                    ActionNodeCard(
                        action = action,
                        index = index,
                        siblingCount = displayedActions.size,
                        depth = 0,
                        imageByName = imageByName,
                        actionRemoteControls = macro.actionRemoteControls,
                        modifier = Modifier.animateItem(
                            placementSpec = if (isDragging) null else spring(stiffness = Spring.StiffnessMediumLow)
                        ),
                        isDragging = isDragging,
                        dragOffsetY = if (isDragging) draggingRootOffsetY else 0f,
                        dragHandleModifier = dragHandleMod,
                        onEdit = { editingAction = it },
                        onQuickSwapImage = { imgAct -> quickSwapImageAction = imgAct },
                        onPickTargetOnScreen = { id -> onLaunchTargetPickerOverlay(macro, id) },
                        onPickRegionOnScreen = { id -> onLaunchRegionPickerOverlay(macro, id) },
                        onAddBranchClickTarget = { parentId, slot ->
                            val newClick = ClickAction(x = screenW / 2, y = screenH / 2, delayAfterMs = 250L)
                            val updatedMacro = when (editorTab) {
                                0 -> macro.updateBranchActions(selectedBranchId) { list ->
                                    list.insertInto(ContainerRef(parentId, slot), newClick)
                                }
                                3 -> macro.copy(
                                    accountSwitch = macro.accountSwitch.copy(
                                        switchActions = macro.accountSwitch.switchActions.insertInto(ContainerRef(parentId, slot), newClick)
                                    )
                                )
                                else -> macro.copy(stuckActions = macro.stuckActions.insertInto(ContainerRef(parentId, slot), newClick))
                            }
                            onUpdateMacro(updatedMacro)
                            onLaunchTargetPickerOverlay(updatedMacro, newClick.id)
                        },
                        onToggleEnabled = { id, enabled ->
                            mutateActions { it.updateAction(id) { a -> a.withEnabled(enabled) } }
                        },
                        onMove = { id, delta -> mutateActions { it.moveAction(id, delta) } },
                        onMoveToBranch = if (editorTab == 0) { act -> movingBranchAction = act } else null,
                        onPreviewReorder = ::previewReorder,
                        onCommitReorder = ::commitDragPreview,
                        onDuplicate = { id -> mutateActions { it.duplicateAction(id) } },
                        onDelete = { id -> mutateActions { it.removeAction(id) } },
                        onAddChild = { parentId, slot -> addTargetRef = ContainerRef(parentId, slot) }
                    )
                }
            }
        }
    }

    // 1-Tap Quick Image Swap Sheet
    quickSwapImageAction?.let { imgAct ->
        val currentName = when (imgAct) {
            is ImageAction -> imgAct.imageName
            is TrackObjectAction -> imgAct.imageName
            else -> ""
        }
        QuickImagePickerSheet(
            currentImageName = currentName,
            images = images,
            imageFolders = imageFolders,
            onSelectImage = { chosenName ->
                mutateActions { list ->
                    list.updateAction(imgAct.id) { cur ->
                        when (cur) {
                            is ImageAction -> cur.copy(imageName = chosenName)
                            is TrackObjectAction -> cur.copy(
                                imageName = chosenName,
                                extraImageNames = cur.extraImageNames.filter { it != chosenName }
                            )
                            else -> cur
                        }
                    }
                }
                quickSwapImageAction = null
            },
            onCropNew = {
                quickSwapImageAction = null
                onLaunchCropOverlay(macro, imgAct.id)
            },
            onRecropImage = onRecropImage,
            onDismiss = { quickSwapImageAction = null }
        )
    }

    // Add Action Picker Sheet
    addTargetRef?.let { targetRef ->
        AddActionPickerSheet(
            isNested = targetRef.parentId != null,
            onPick = { kind ->
                val defaultImg = images.firstOrNull()?.name.orEmpty()
                val rawCreated = kind.create(screenW, screenH, imageName = defaultImg)
                val created = if (rawCreated is GoToBranchAction) {
                    val suggested = macro.branches.firstOrNull { it.id != selectedBranchId } ?: macro.branches.firstOrNull()
                    if (suggested != null) rawCreated.copy(targetBranchId = suggested.id, targetBranchName = suggested.name)
                    else rawCreated
                } else rawCreated
                mutateActions { it.insertInto(targetRef, created) }
                addTargetRef = null
                if (kind != ActionKind.STOP && kind != ActionKind.RESTART) {
                    editingAction = created
                }
            },
            onDismiss = { addTargetRef = null }
        )
    }

    // Edit Action Sheet
    editingAction?.let { act ->
        val ownerActions = macro.ownerListForAction(act.id)
        ActionEditorSheet(
            action = act,
            currentMacroId = macro.id,
            macroActions = ownerActions,
            branches = macro.branches,
            accounts = macro.accountSwitch.accounts,
            accountDefaultMethod = macro.accountSwitch.method,
            allMacros = allMacros,
            images = images,
            imageFolders = imageFolders,
            lastPickedPixel = lastPickedPixel,
            screenW = screenW,
            screenH = screenH,
            onLaunchCropForAction = { actionId ->
                editingAction = null
                onLaunchCropOverlay(macro, actionId)
            },
            onPickTargetOnScreen = { updatedDraft, targetActionId ->
                val updatedMacro = macro.updateAnyAction(updatedDraft.id) { updatedDraft }
                onUpdateMacro(updatedMacro)
                editingAction = null
                onLaunchTargetPickerOverlay(updatedMacro, targetActionId)
            },
            onPickRegionOnScreen = { updatedDraft, targetActionId ->
                val updatedMacro = macro.updateAnyAction(updatedDraft.id) { updatedDraft }
                onUpdateMacro(updatedMacro)
                editingAction = null
                onLaunchRegionPickerOverlay(updatedMacro, targetActionId)
            },
            onRecropImage = onRecropImage,
            remoteControl = macro.actionRemoteControls[act.id],
            existingOtherActionRemoteIds = macro.actionRemoteControls
                .filterKeys { it != act.id }
                .values
                .filter { it.enabled }
                .map { it.normalizedId() }
                .filter { it.isNotEmpty() }
                .toSet(),
            macroRemoteIdHint = macro.remoteId,
            onSaveWithRemote = { updated, remoteCtrl ->
                val withAction = macro.updateAnyAction(updated.id) { updated }
                onUpdateMacro(withAction.withActionRemoteControl(updated.id, remoteCtrl))
                editingAction = null
            },
            onSave = { updated ->
                onUpdateMacro(macro.updateAnyAction(updated.id) { updated })
                editingAction = null
            },
            onDismiss = { editingAction = null }
        )
    }

    // Create / Edit Sequence Branch Dialog
    if (showCreateBranchDialog || editingBranch != null) {
        val target = editingBranch
        MacroBranchDialog(
            initialName = target?.name ?: "Cabang ${macro.branches.size + 1}",
            initialColorHex = target?.colorHex ?: BRANCH_PRESET_COLORS[macro.branches.size % BRANCH_PRESET_COLORS.size],
            isEdit = target != null,
            onConfirm = { newName, newColorHex ->
                if (target == null) {
                    val createdBranch = MacroBranch(
                        id = newId(),
                        name = newName,
                        colorHex = newColorHex
                    )
                    onUpdateMacro(macro.copy(branches = macro.branches + createdBranch))
                    selectedBranchId = createdBranch.id
                } else {
                    onUpdateMacro(
                        macro.copy(
                            branches = macro.branches.map { b ->
                                if (b.id == target.id) b.copy(name = newName, colorHex = newColorHex) else b
                            }
                        )
                    )
                }
                showCreateBranchDialog = false
                editingBranch = null
            },
            onDismiss = {
                showCreateBranchDialog = false
                editingBranch = null
            }
        )
    }

    // Delete Sequence Branch Confirmation Dialog
    pendingDeleteBranch?.let { branch ->
        AlertDialog(
            onDismissRequest = { pendingDeleteBranch = null },
            title = { Text("Hapus Cabang '${branch.name}'?") },
            text = {
                Text(
                    "Cabang ini memiliki ${branch.actions.size} aksi. Menghapus cabang akan menghapus semua aksi di dalamnya secara permanen."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateMacro(macro.copy(branches = macro.branches.filterNot { it.id == branch.id }))
                        if (selectedBranchId == branch.id) selectedBranchId = ""
                        pendingDeleteBranch = null
                    }
                ) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteBranch = null }) { Text("Batal") }
            }
        )
    }

    // Move Action to Branch Dialog
    movingBranchAction?.let { act ->
        MoveActionToBranchDialog(
            action = act,
            macro = macro,
            onMoveToBranch = { targetBranchId ->
                onUpdateMacro(macro.moveActionToBranch(act.id, targetBranchId))
                selectedBranchId = targetBranchId
                movingBranchAction = null
            },
            onCreateBranchAndMove = { newName, newColorHex ->
                val created = MacroBranch(
                    id = newId(),
                    name = newName,
                    colorHex = newColorHex
                )
                val withBranch = macro.copy(branches = macro.branches + created)
                onUpdateMacro(withBranch.moveActionToBranch(act.id, created.id))
                selectedBranchId = created.id
                movingBranchAction = null
            },
            onDismiss = { movingBranchAction = null }
        )
    }

    // Macro Settings Sheet (logo, security, name, loopCount, loopDelay, jitter, randomDelay, maxDuration, watchdog)
    if (showMacroSettings) {
        MacroSettingsSheet(
            macro = macro,
            allMacros = allMacros,
            logoPath = logoPath,
            screenW = screenW,
            screenH = screenH,
            onChangeLogo = {
                showMacroSettings = false
                onChangeLogo()
            },
            onOpenSecurity = {
                showMacroSettings = false
                onOpenSecurity()
            },
            onTogglePassivePull = onTogglePassivePull,
            onSave = { updated ->
                onUpdateMacro(updated)
                showMacroSettings = false
            },
            onDismiss = { showMacroSettings = false }
        )
    }
}

@Composable
private fun ActionNodeCard(
    action: Action,
    index: Int,
    siblingCount: Int,
    depth: Int,
    imageByName: Map<String, ImageAsset>,
    actionRemoteControls: Map<String, ActionRemoteControl> = emptyMap(),
    modifier: Modifier = Modifier,
    isDragging: Boolean = false,
    dragOffsetY: Float = 0f,
    dragHandleModifier: Modifier = Modifier,
    onEdit: (Action) -> Unit,
    onQuickSwapImage: (Action) -> Unit,
    onPickTargetOnScreen: (String) -> Unit,
    onPickRegionOnScreen: (String) -> Unit,
    onAddBranchClickTarget: (String, Slot) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
    onMoveToBranch: ((Action) -> Unit)? = null,
    onPreviewReorder: (ContainerRef, Int, Int) -> Unit,
    onCommitReorder: () -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAddChild: (String, Slot) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val accent = action.kind.accentColor()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(targetValue = if (isDragging) 1.02f else 1f, label = "cardDragScale")
    val elevationDp by animateDpAsState(targetValue = if (isDragging) 12.dp else 0.dp, label = "cardDragElevation")

    Card(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 10f else 0f)
            .graphicsLayer {
                translationY = if (isDragging) dragOffsetY else 0f
                scaleX = scale
                scaleY = scale
                shadowElevation = with(density) { elevationDp.toPx() }
            }
            .alpha(if (action.enabled) 1f else 0.5f),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isDragging -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
                depth == 0 -> MaterialTheme.colorScheme.surface
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            }
        ),
        border = BorderStroke(
            width = if (isDragging) 2.dp else 1.dp,
            color = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEdit(action) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Drag handle (visible whenever there are 2+ siblings to reorder)
                if (siblingCount > 1) {
                    Box(
                        modifier = Modifier
                            .width(26.dp)
                            .height(42.dp)
                            .then(dragHandleModifier),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DragIndicator,
                            contentDescription = "Seret untuk ubah urutan",
                            tint = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }

                // Leading visual: image thumbnail for ImageAction / image-based TrackObjectAction, color dot for ColorAction, icon otherwise
                when {
                    action is ImageAction || (action is TrackObjectAction && !action.trackAnyMotion) -> {
                        val imgName = if (action is ImageAction) action.imageName else (action as TrackObjectAction).imageName
                        val asset = imageByName[imgName]
                        val bmp = rememberAssetThumbnail(asset?.path, asset?.updatedAt ?: 0L)
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF0A1020))
                                .border(1.5.dp, accent, RoundedCornerShape(10.dp))
                                .clickable { onQuickSwapImage(action) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (bmp != null) {
                                Image(bitmap = bmp, contentDescription = "Ganti Gambar", contentScale = ContentScale.Fit, modifier = Modifier.size(36.dp))
                            } else {
                                Icon(action.kind.icon(), contentDescription = "Pilih Gambar", tint = accent, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    action is ColorAction -> {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(accent.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(parseHexColorOrNull(action.color) ?: Color.White)
                                    .border(1.5.dp, Color.White, CircleShape)
                            )
                        }
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(action.kind.icon(), contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${index + 1}. ${action.kind.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        action.summary() + if (action.delayAfterMs > 0) " • jeda ${action.delayAfterMs}ms" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val remoteCtrl = actionRemoteControls[action.id]
                    if (remoteCtrl != null && remoteCtrl.enabled && remoteCtrl.normalizedId().isNotEmpty()) {
                        val badgeColor = when {
                            remoteCtrl.mode == RemoteActionMode.ONE_SHOT && remoteCtrl.active -> Color(0xFFF59E0B)
                            remoteCtrl.mode == RemoteActionMode.ONE_SHOT -> Color(0xFFA78BFA)
                            remoteCtrl.active -> Color(0xFF10B981)
                            else -> Color(0xFF94A3B8)
                        }
                        val statusTag = when {
                            remoteCtrl.mode == RemoteActionMode.ONE_SHOT && remoteCtrl.active -> "1× SIAP"
                            remoteCtrl.mode == RemoteActionMode.ONE_SHOT -> "1×"
                            remoteCtrl.active -> "ON"
                            else -> "OFF"
                        }
                        Text(
                            text = "🤖 Bot: ${remoteCtrl.normalizedId()} ($statusTag)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = badgeColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                when {
                    action is ImageAction || (action is TrackObjectAction && !action.trackAnyMotion) -> {
                        IconButton(onClick = { onQuickSwapImage(action) }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Filled.SwapHoriz,
                                contentDescription = "Ganti Gambar Target",
                                tint = accent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    action is TextAction || action is com.example.data.SelectAccountAction || (action is com.example.data.VariableAction && action.operation == com.example.data.VariableOperation.READ_OCR_NUMBER) || (action is TrackObjectAction && action.trackAnyMotion) -> {
                        IconButton(onClick = { onPickRegionOnScreen(action.id) }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Filled.CropFree,
                                contentDescription = "Pilih Area di Layar",
                                tint = accent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    isPositionableAction(action) -> {
                        IconButton(onClick = { onPickTargetOnScreen(action.id) }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Filled.MyLocation,
                                contentDescription = "Atur Posisi di Layar",
                                tint = accent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Opsi", modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        if (action is ImageAction || (action is TrackObjectAction && !action.trackAnyMotion)) {
                            DropdownMenuItem(
                                text = { Text("Ganti Gambar Target") },
                                leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) },
                                onClick = { menuExpanded = false; onQuickSwapImage(action) }
                            )
                        }
                        if (action is TextAction || action is com.example.data.SelectAccountAction || (action is com.example.data.VariableAction && action.operation == com.example.data.VariableOperation.READ_OCR_NUMBER) || (action is TrackObjectAction && action.trackAnyMotion)) {
                            DropdownMenuItem(
                                text = { Text("Pilih Area di Layar") },
                                leadingIcon = { Icon(Icons.Filled.CropFree, null) },
                                onClick = { menuExpanded = false; onPickRegionOnScreen(action.id) }
                            )
                        }
                        if (isPositionableAction(action)) {
                            DropdownMenuItem(
                                text = { Text("Atur Posisi di Layar") },
                                leadingIcon = { Icon(Icons.Filled.MyLocation, null) },
                                onClick = { menuExpanded = false; onPickTargetOnScreen(action.id) }
                            )
                        }
                        if (index > 0) {
                            DropdownMenuItem(
                                text = { Text("Geser ke Atas") },
                                leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                                onClick = { menuExpanded = false; onMove(action.id, -1) }
                            )
                        }
                        if (index < siblingCount - 1) {
                            DropdownMenuItem(
                                text = { Text("Geser ke Bawah") },
                                leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) },
                                onClick = { menuExpanded = false; onMove(action.id, 1) }
                            )
                        }
                        if (onMoveToBranch != null && depth == 0) {
                            DropdownMenuItem(
                                text = { Text("Pindah ke Cabang...") },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.AltRoute,
                                        contentDescription = null,
                                        tint = Color(0xFFF59E0B)
                                    )
                                },
                                onClick = { menuExpanded = false; onMoveToBranch(action) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (action.enabled) "Nonaktifkan" else "Aktifkan") },
                            onClick = { menuExpanded = false; onToggleEnabled(action.id, !action.enabled) }
                        )
                        DropdownMenuItem(
                            text = { Text("Duplikat") },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                            onClick = { menuExpanded = false; onDuplicate(action.id) }
                        )
                        DropdownMenuItem(
                            text = { Text("Hapus", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; onDelete(action.id) }
                        )
                    }
                }
            }

            // Nested branches (Then / Else / Loop Body) with their own drag-and-drop support
            val slots = action.slots()
            if (slots.isNotEmpty() && depth < 3) {
                val childSpacingPx = with(density) { 6.dp.toPx() }
                val latestOnPreviewReorder by rememberUpdatedState(onPreviewReorder)
                val latestOnCommitReorder by rememberUpdatedState(onCommitReorder)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    slots.forEach { slot ->
                        val children = action.childList(slot)
                        val latestChildren by rememberUpdatedState(children)
                        var draggedChildId by remember(action.id, slot) { mutableStateOf<String?>(null) }
                        var draggedChildOffsetY by remember(action.id, slot) { mutableFloatStateOf(0f) }
                        val childHeights = remember(action.id, slot) { mutableStateMapOf<String, Int>() }
                        val awaitingChildLayout = remember(action.id, slot) { mutableStateOf(false) }
                        androidx.compose.runtime.SideEffect { awaitingChildLayout.value = false }

                        val branchColor = when (slot) {
                            Slot.THEN -> Color(0xFF10B981)
                            Slot.ELSE -> Color(0xFFF59E0B)
                            Slot.BODY -> Color(0xFFA78BFA)
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = branchColor.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, branchColor.copy(alpha = 0.35f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${slot.label} (${children.size})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = branchColor,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = { onAddBranchClickTarget(action.id, slot) },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(13.dp), tint = branchColor)
                                        Spacer(Modifier.width(3.dp))
                                        Text("Target Klik", style = MaterialTheme.typography.labelSmall, color = branchColor)
                                    }
                                    TextButton(
                                        onClick = { onAddChild(action.id, slot) },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = branchColor)
                                        Spacer(Modifier.width(2.dp))
                                        Text("Aksi", style = MaterialTheme.typography.labelSmall, color = branchColor)
                                    }
                                }
                                children.forEachIndexed { childIdx, child ->
                                    key(child.id) {
                                        val isChildDragging = draggedChildId == child.id
                                        val childHandleMod = if (children.size > 1) {
                                            Modifier.pointerInput(child.id) {
                                                detectVerticalDragGestures(
                                                    onDragStart = {
                                                        draggedChildId = child.id
                                                        draggedChildOffsetY = 0f
                                                        awaitingChildLayout.value = false
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    },
                                                    onVerticalDrag = { change, dragAmount ->
                                                        change.consume()
                                                        if (draggedChildId != child.id) return@detectVerticalDragGestures
                                                        draggedChildOffsetY += dragAmount
                                                        if (awaitingChildLayout.value) return@detectVerticalDragGestures
                                                        val list = latestChildren
                                                        val fromIdx = list.indexOfFirst { it.id == child.id }
                                                        if (fromIdx >= 0) {
                                                            if (draggedChildOffsetY > 0f && fromIdx < list.lastIndex) {
                                                                val nextH = (childHeights[list[fromIdx + 1].id] ?: 120).toFloat()
                                                                if (draggedChildOffsetY > childSpacingPx + nextH / 2f) {
                                                                    awaitingChildLayout.value = true
                                                                    latestOnPreviewReorder(ContainerRef(action.id, slot), fromIdx, fromIdx + 1)
                                                                    draggedChildOffsetY -= (nextH + childSpacingPx)
                                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                }
                                                            } else if (draggedChildOffsetY < 0f && fromIdx > 0) {
                                                                val prevH = (childHeights[list[fromIdx - 1].id] ?: 120).toFloat()
                                                                if (draggedChildOffsetY < -(childSpacingPx + prevH / 2f)) {
                                                                    awaitingChildLayout.value = true
                                                                    latestOnPreviewReorder(ContainerRef(action.id, slot), fromIdx, fromIdx - 1)
                                                                    draggedChildOffsetY += (prevH + childSpacingPx)
                                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                }
                                                            }
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        draggedChildId = null
                                                        draggedChildOffsetY = 0f
                                                        awaitingChildLayout.value = false
                                                        latestOnCommitReorder()
                                                    },
                                                    onDragCancel = {
                                                        draggedChildId = null
                                                        draggedChildOffsetY = 0f
                                                        awaitingChildLayout.value = false
                                                        latestOnCommitReorder()
                                                    }
                                                )
                                            }
                                        } else {
                                            Modifier
                                        }

                                        ActionNodeCard(
                                            action = child,
                                            index = childIdx,
                                            siblingCount = children.size,
                                            depth = depth + 1,
                                            imageByName = imageByName,
                                            actionRemoteControls = actionRemoteControls,
                                            modifier = Modifier.onGloballyPositioned { coords ->
                                                childHeights[child.id] = coords.size.height
                                            },
                                            isDragging = isChildDragging,
                                            dragOffsetY = if (isChildDragging) draggedChildOffsetY else 0f,
                                            dragHandleModifier = childHandleMod,
                                            onEdit = onEdit,
                                            onQuickSwapImage = onQuickSwapImage,
                                            onPickTargetOnScreen = onPickTargetOnScreen,
                                            onPickRegionOnScreen = onPickRegionOnScreen,
                                            onAddBranchClickTarget = onAddBranchClickTarget,
                                            onToggleEnabled = onToggleEnabled,
                                            onMove = onMove,
                                            onPreviewReorder = onPreviewReorder,
                                            onCommitReorder = onCommitReorder,
                                            onDuplicate = onDuplicate,
                                            onDelete = onDelete,
                                            onAddChild = onAddChild
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickImagePickerSheet(
    currentImageName: String,
    images: List<ImageAsset>,
    imageFolders: List<String>,
    onSelectImage: (String) -> Unit,
    onCropNew: () -> Unit,
    onRecropImage: (String, Bitmap) -> Unit,
    onDismiss: () -> Unit
) {
    var folderFilter by remember { mutableStateOf<String?>(null) }
    var recropAsset by remember { mutableStateOf<ImageAsset?>(null) }
    val currentAsset = remember(images, currentImageName) { images.firstOrNull { it.name == currentImageName } }
    val filtered = remember(images, folderFilter) {
        when (folderFilter) {
            null -> images
            "" -> images.filter { it.folder.isBlank() }
            else -> images.filter { it.folder == folderFilter }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Ganti Gambar Target", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onCropNew, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Crop Baru dari Layar", maxLines = 1)
                }
                if (currentAsset != null) {
                    FilledTonalButton(onClick = { recropAsset = currentAsset }) {
                        Text("Potong Ulang")
                    }
                }
            }

            if (imageFolders.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item(key = "q_all") {
                        FilterChip(
                            selected = folderFilter == null,
                            onClick = { folderFilter = null },
                            label = { Text("Semua (${images.size})", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    item(key = "q_root") {
                        FilterChip(
                            selected = folderFilter == "",
                            onClick = { folderFilter = "" },
                            label = { Text("Utama", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    items(imageFolders, key = { "q_$it" }) { f ->
                        FilterChip(
                            selected = folderFilter == f,
                            onClick = { folderFilter = f },
                            label = { Text("📁 $f", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                Text(
                    "Belum ada gambar di folder ini. Ketuk 'Crop Baru dari Layar' untuk mengambil target baru.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                ) {
                    items(filtered, key = { it.name }) { asset ->
                        val selected = asset.name == currentImageName
                        val bmp = rememberAssetThumbnail(asset.path, asset.updatedAt)
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                )
                                .clickable { onSelectImage(asset.name) }
                                .padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0F172A)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = asset.displayName,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(4.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                asset.displayName.removeSuffix(".png"),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }

    recropAsset?.let { asset ->
        InAppRecropDialog(
            asset = asset,
            onSave = { cropped ->
                onRecropImage(asset.name, cropped)
                recropAsset = null
            },
            onDismiss = { recropAsset = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddActionPickerSheet(
    isNested: Boolean,
    onPick: (ActionKind) -> Unit,
    onDismiss: () -> Unit
) {
    val groups = listOf(
        "Deteksi (Macrorify)" to listOf(ActionKind.IMAGE, ActionKind.TRACK_OBJECT, ActionKind.TEXT, ActionKind.COLOR),
        "Variabel & Hitungan (Tanpa Kode)" to listOf(ActionKind.VARIABLE, ActionKind.CHECK_VARIABLE),
        "Notifikasi & Alarm HP" to listOf(ActionKind.NOTIFY),
        "Cabang & Arah Urutan" to listOf(ActionKind.BRANCH_ROUTER, ActionKind.GOTO_BRANCH),
        "Ganti Akun Game (Semua Game)" to listOf(ActionKind.SWITCH_ACCOUNT, ActionKind.SELECT_ACCOUNT),
        "Sentuhan & Gestur" to listOf(ActionKind.CLICK, ActionKind.LONG_PRESS, ActionKind.SWIPE, ActionKind.ZOOM, ActionKind.WAIT),
        "Aplikasi (Keluar / Masuk)" to listOf(ActionKind.EXIT_APP, ActionKind.LAUNCH_APP),
        "Alur & Hubungkan Macro" to listOf(ActionKind.LOOP, ActionKind.JUMP, ActionKind.RUN_MACRO, ActionKind.FINISH_MACRO, ActionKind.RESTART, ActionKind.STOP)
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (isNested) "Tambah Aksi ke Cabang" else "Tambah Aksi Baru",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            groups.forEach { (heading, kinds) ->
                Text(heading, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    kinds.forEach { kind ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable { onPick(kind) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(kind.accentColor().copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(kind.icon(), contentDescription = null, tint = kind.accentColor(), modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(kind.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(kind.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MacroSettingsSheet(
    macro: Macro,
    allMacros: List<Macro> = emptyList(),
    logoPath: String?,
    screenW: Int,
    screenH: Int,
    onChangeLogo: () -> Unit,
    onOpenSecurity: () -> Unit,
    onTogglePassivePull: () -> Unit,
    onSave: (Macro) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(macro.id) { mutableStateOf(macro.name) }
    var infinite by remember(macro.id) { mutableStateOf(macro.loopCount <= 0) }
    var loopCount by remember(macro.id) { mutableStateOf(macro.loopCount.coerceAtLeast(1).toString()) }
    var loopDelay by remember(macro.id) { mutableStateOf(macro.loopDelayMs.toString()) }
    var jitter by remember(macro.id) { mutableStateOf(macro.jitterPx.toString()) }
    var randomDelay by remember(macro.id) { mutableStateOf(macro.randomDelayMs.toString()) }
    var maxMin by remember(macro.id) { mutableStateOf(macro.maxDurationMin.toString()) }
    var stuckSec by remember(macro.id) { mutableStateOf(macro.autoRecoverStuckSec.toString()) }
    var recoveryMode by remember(macro.id) { mutableStateOf(macro.stuckRecoveryMode) }
    var autoScale by remember(macro.id) { mutableStateOf(macro.autoScaleResolution) }
    var sourceWText by remember(macro.id) {
        mutableStateOf(if (macro.sourceWidth > 0) macro.sourceWidth.toString() else "")
    }
    var sourceHText by remember(macro.id) {
        mutableStateOf(if (macro.sourceHeight > 0) macro.sourceHeight.toString() else "")
    }
    var scaleMode by remember(macro.id) { mutableStateOf(macro.scaleMode) }
    var humanizer by remember(macro.id) { mutableStateOf(macro.humanizer) }
    var remoteControlEnabled by remember(macro.id) { mutableStateOf(macro.remoteControlEnabled) }
    var remoteId by remember(macro.id) { mutableStateOf(macro.remoteId) }
    val otherRemoteMacroIds = remember(allMacros, macro.id) {
        allMacros
            .filter { it.id != macro.id && it.remoteControlEnabled }
            .map { RemoteIdSanitizer.sanitize(it.remoteId) }
            .filter { it.isNotEmpty() }
            .toSet()
    }
    val logoBmp = rememberAssetThumbnail(logoPath, macro.updatedAt)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Pengaturan Macro", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider()

            // Custom Macro Logo Photo row
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onChangeLogo)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0A1020)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (logoBmp != null) {
                            Image(
                                bitmap = logoBmp,
                                contentDescription = "Logo Macro",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                Icons.Filled.AddPhotoAlternate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Foto Logo Macro", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (macro.logoImage.isNotBlank()) "Foto khusus terpasang — ketuk untuk ganti"
                            else "Pasang foto khusus untuk logo macro ini",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(
                        onClick = onChangeLogo,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("Ubah", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Lock, Expiry & Passive Mode shortcut row
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {
                val secSummary = buildList {
                    if (macro.isLocked) add("Terkunci")
                    if (macro.isPassive) add(if (macro.passiveEnabled) "Pasif ON" else "Pasif OFF")
                    MacroSecurity.remainingTimeLabel(macro)?.let { add(it) }
                }.joinToString(" • ").ifBlank { "Tidak ada proteksi aktif" }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSecurity)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Kunci, Tenggat & Mode Pasif", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            secSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(
                        onClick = onOpenSecurity,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("Atur", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Quick passwordless Passive ON/OFF toggle when macro is already in Passive mode
            if (macro.isPassive && !MacroSecurity.isTampered(macro) && !MacroSecurity.isExpired(macro)) {
                val pullTint = if (macro.passiveEnabled) Color(0xFF10B981) else Color(0xFFF59E0B)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = pullTint.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, pullTint.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(
                                if (macro.passiveEnabled) "Status Pasif: ON (Dapat Ditarik)" else "Status Pasif: OFF (Diskip)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = pullTint
                            )
                            Text(
                                "Ubah Pasif ON/OFF langsung tanpa sandi.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = macro.passiveEnabled,
                            onCheckedChange = { onTogglePassivePull() }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nama Macro") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Ulangi tanpa batas (∞)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("Berjalan terus hingga tombol Stop ditekan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = infinite, onCheckedChange = { infinite = it })
            }

            if (!infinite) {
                OutlinedTextField(
                    value = loopCount,
                    onValueChange = { loopCount = it.filter(Char::isDigit) },
                    label = { Text("Jumlah pengulangan (Loop)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = loopDelay,
                    onValueChange = { loopDelay = it.filter(Char::isDigit) },
                    label = { Text("Jeda antar loop (ms)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = jitter,
                    onValueChange = { jitter = it.filter(Char::isDigit) },
                    label = { Text("Acak posisi (±px)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = randomDelay,
                    onValueChange = { randomDelay = it.filter(Char::isDigit) },
                    label = { Text("Acak jeda (+ms)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = maxMin,
                    onValueChange = { maxMin = it.filter(Char::isDigit) },
                    label = { Text("Stop otomatis (menit, 0=off)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider()

            // Humanizer Engine (Anti-Deteksi Alami: Gaussian click, Bezier swipe, organic delay & Human Break)
            HumanizerSettingsSection(
                config = humanizer,
                onUpdate = { humanizer = it }
            )

            HorizontalDivider()

            // Kontrol Bot Telegram / Discord (ID Makro ON/OFF untuk Makro Aktif & Pasif)
            MacroRemoteControlSettingsSection(
                macroName = name,
                isPassive = macro.isPassive,
                enabled = remoteControlEnabled,
                remoteId = remoteId,
                existingOtherMacroIds = otherRemoteMacroIds,
                onEnabledChange = { remoteControlEnabled = it },
                onRemoteIdChange = { remoteId = it }
            )

            HorizontalDivider()

            // Auto-Scale Resolusi Layar (semua perangkat: HP, Tablet, Foldable, Emulator, CloudPhone)
            AutoScaleControlsBlock(
                macro = macro,
                screenW = screenW,
                screenH = screenH,
                autoScale = autoScale,
                onAutoScaleChange = { autoScale = it },
                sourceWText = sourceWText,
                onSourceWChange = { sourceWText = it },
                sourceHText = sourceHText,
                onSourceHChange = { sourceHText = it },
                scaleMode = scaleMode,
                onScaleModeChange = { scaleMode = it }
            )

            HorizontalDivider()
            Text("Auto Perbaiki Macro Macet (Watchdog)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                value = stuckSec,
                onValueChange = { stuckSec = it.filter(Char::isDigit) },
                label = { Text("Pulihkan otomatis jika macet (detik, 0=off)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StuckRecoveryMode.entries.forEach { m ->
                    FilterChip(
                        selected = recoveryMode == m,
                        onClick = { recoveryMode = m },
                        label = { Text(m.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Batal") }
                Button(
                    onClick = {
                        val savedSrcW = (sourceWText.toIntOrNull() ?: 0).coerceIn(0, 10000)
                        val savedSrcH = (sourceHText.toIntOrNull() ?: 0).coerceIn(0, 10000)
                        val sanitizedRemoteId = RemoteIdSanitizer.sanitize(remoteId)
                        onSave(
                            macro.copy(
                                name = name.trim().ifBlank { "Macro Baru" },
                                loopCount = if (infinite) 0 else (loopCount.toIntOrNull() ?: 1).coerceAtLeast(1),
                                loopDelayMs = (loopDelay.toLongOrNull() ?: 500L).coerceAtLeast(0L),
                                jitterPx = (jitter.toIntOrNull() ?: 0).coerceIn(0, 60),
                                randomDelayMs = (randomDelay.toLongOrNull() ?: 0L).coerceAtLeast(0L),
                                maxDurationMin = (maxMin.toIntOrNull() ?: 0).coerceIn(0, 1440),
                                autoRecoverStuckSec = (stuckSec.toIntOrNull() ?: 0).coerceIn(0, 3600),
                                stuckRecoveryMode = recoveryMode,
                                sourceWidth = savedSrcW,
                                sourceHeight = savedSrcH,
                                autoScaleResolution = autoScale,
                                scaleMode = scaleMode,
                                humanizer = humanizer,
                                remoteControlEnabled = remoteControlEnabled && sanitizedRemoteId.isNotEmpty(),
                                remoteId = sanitizedRemoteId
                            )
                        )
                    },
                    modifier = Modifier.weight(1.4f)
                ) { Text("Simpan") }
            }
        }
    }
}

private val BRANCH_PRESET_COLORS = listOf(
    "#F59E0B", // Amber
    "#06B6D4", // Cyan
    "#10B981", // Emerald
    "#EC4899", // Pink
    "#8B5CF6", // Violet
    "#EF4444"  // Red
)

@Composable
private fun MacroBranchDialog(
    initialName: String,
    initialColorHex: String,
    isEdit: Boolean,
    onConfirm: (name: String, colorHex: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var selectedColorHex by remember { mutableStateOf(initialColorHex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Ubah Cabang Urutan" else "Buat Cabang Urutan Baru") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Gunakan cabang urutan untuk jalur alternatif ketika hasil di layar tidak sesuai atau berubah arah.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(36) },
                    label = { Text("Nama Cabang") },
                    placeholder = { Text("mis. Jika Kalah / Ulangi / Belok Kiri") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Warna Penanda", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BRANCH_PRESET_COLORS.forEach { hex ->
                        val c = parseHexColorOrNull(hex) ?: Color(0xFFF59E0B)
                        val isSel = selectedColorHex.equals(hex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    width = if (isSel) 3.dp else 1.dp,
                                    color = if (isSel) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { selectedColorHex = hex }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = name.trim().ifBlank { initialName.ifBlank { "Cabang Baru" } }
                    onConfirm(clean, selectedColorHex)
                }
            ) {
                Text(if (isEdit) "Simpan" else "Buat Cabang")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        }
    )
}
