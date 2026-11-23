package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.automation.PickedPixel
import com.example.data.Action
import com.example.data.ActionKind
import com.example.data.ClickAction
import com.example.data.ColorAction
import com.example.data.ContainerRef
import com.example.data.ImageAction
import com.example.data.ImageAsset
import com.example.data.Macro
import android.graphics.Bitmap
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import com.example.data.Slot
import com.example.data.StuckRecoveryMode
import com.example.data.childList
import com.example.data.create
import com.example.data.duplicateAction
import com.example.data.insertInto
import com.example.data.kind
import com.example.data.moveAction
import com.example.data.removeAction
import com.example.data.slots
import com.example.data.summary
import com.example.data.updateAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroEditorScreen(
    macro: Macro,
    allMacros: List<Macro>,
    images: List<ImageAsset>,
    lastPickedPixel: PickedPixel?,
    screenW: Int,
    screenH: Int,
    onUpdateMacro: (Macro) -> Unit,
    onRecropImage: (String, Bitmap) -> Unit,
    onLaunchOverlay: (Macro) -> Unit,
    onLaunchCropOverlay: (Macro, String?) -> Unit,
    onLaunchTargetPickerOverlay: (Macro, String) -> Unit,
    onLaunchRecorderOverlay: (Macro) -> Unit,
    onBack: () -> Unit
) {
    var editingAction by remember { mutableStateOf<Action?>(null) }
    var addTargetRef by remember { mutableStateOf<ContainerRef?>(null) }
    var showMacroSettings by remember { mutableStateOf(false) }

    val imageByName = remember(images) { images.associateBy { it.name } }

    fun mutateActions(block: (List<Action>) -> List<Action>) {
        onUpdateMacro(macro.copy(actions = block(macro.actions)))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                title = {
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
                },
                actions = {
                    IconButton(onClick = { showMacroSettings = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Pengaturan Macro")
                    }
                    FilledTonalButton(
                        onClick = { onLaunchOverlay(macro) },
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
            ExtendedFloatingActionButton(
                onClick = { addTargetRef = ContainerRef.ROOT },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Tambah Aksi") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Quick Macrorify overlay capture shortcuts
            item(key = "quick_tools") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onLaunchCropOverlay(macro, null) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Crop Gambar", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { onLaunchRecorderOverlay(macro) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Rekam Sentuhan", maxLines = 1)
                    }
                }
            }

            if (macro.actions.isEmpty()) {
                item(key = "empty") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Belum ada aksi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Ketuk 'Tambah Aksi' di bawah untuk menambah Klik, Deteksi Gambar, Deteksi Warna, Swipe, atau pakai tombol 'Crop Gambar' / 'Rekam Sentuhan' langsung di layar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(macro.actions, key = { _, a -> a.id }) { index, action ->
                    ActionNodeCard(
                        action = action,
                        index = index,
                        siblingCount = macro.actions.size,
                        depth = 0,
                        imageByName = imageByName,
                        onEdit = { editingAction = it },
                        onPickTargetOnScreen = { id -> onLaunchTargetPickerOverlay(macro, id) },
                        onAddBranchClickTarget = { parentId, slot ->
                            val newClick = ClickAction(x = screenW / 2, y = screenH / 2, delayAfterMs = 250L)
                            val updatedMacro = macro.copy(actions = macro.actions.insertInto(ContainerRef(parentId, slot), newClick))
                            onUpdateMacro(updatedMacro)
                            onLaunchTargetPickerOverlay(updatedMacro, newClick.id)
                        },
                        onToggleEnabled = { id, enabled ->
                            mutateActions { it.updateAction(id) { a -> a.withEnabled(enabled) } }
                        },
                        onMove = { id, delta -> mutateActions { it.moveAction(id, delta) } },
                        onDuplicate = { id -> mutateActions { it.duplicateAction(id) } },
                        onDelete = { id -> mutateActions { it.removeAction(id) } },
                        onAddChild = { parentId, slot -> addTargetRef = ContainerRef(parentId, slot) }
                    )
                }
            }
        }
    }

    // Add Action Picker Sheet
    addTargetRef?.let { targetRef ->
        AddActionPickerSheet(
            isNested = targetRef.parentId != null,
            onPick = { kind ->
                val defaultImg = images.firstOrNull()?.name.orEmpty()
                val created = kind.create(screenW, screenH, imageName = defaultImg)
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
        ActionEditorSheet(
            action = act,
            currentMacroId = macro.id,
            allMacros = allMacros,
            images = images,
            lastPickedPixel = lastPickedPixel,
            screenW = screenW,
            screenH = screenH,
            onLaunchCropForAction = { actionId ->
                editingAction = null
                onLaunchCropOverlay(macro, actionId)
            },
            onPickTargetOnScreen = { updatedDraft, targetActionId ->
                val updatedMacro = macro.copy(actions = macro.actions.updateAction(updatedDraft.id) { updatedDraft })
                onUpdateMacro(updatedMacro)
                editingAction = null
                onLaunchTargetPickerOverlay(updatedMacro, targetActionId)
            },
            onRecropImage = onRecropImage,
            onSave = { updated ->
                mutateActions { it.updateAction(updated.id) { updated } }
                editingAction = null
            },
            onDismiss = { editingAction = null }
        )
    }

    // Macro Settings Sheet (name, loopCount, loopDelay, jitter, randomDelay, maxDuration)
    if (showMacroSettings) {
        MacroSettingsSheet(
            macro = macro,
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
    onEdit: (Action) -> Unit,
    onPickTargetOnScreen: (String) -> Unit,
    onAddBranchClickTarget: (String, Slot) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAddChild: (String, Slot) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val accent = action.kind.accentColor()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (action.enabled) 1f else 0.5f),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (depth == 0) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEdit(action) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Leading visual: image thumbnail for ImageAction, color dot for ColorAction, icon otherwise
                when (action) {
                    is ImageAction -> {
                        val asset = imageByName[action.imageName]
                        val bmp = rememberAssetThumbnail(asset?.path, asset?.updatedAt ?: 0L)
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF0F172A))
                                .border(1.5.dp, accent, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (bmp != null) {
                                Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(36.dp))
                            } else {
                                Icon(action.kind.icon(), contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    is ColorAction -> {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
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
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(action.kind.icon(), contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                Spacer(Modifier.width(12.dp))

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
                }

                if (isPositionableAction(action)) {
                    IconButton(onClick = { onPickTargetOnScreen(action.id) }) {
                        Icon(
                            Icons.Filled.MyLocation,
                            contentDescription = "Atur Posisi di Layar",
                            tint = accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Opsi")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
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

            // Nested branches (Then / Else / Loop Body)
            val slots = action.slots()
            if (slots.isNotEmpty() && depth < 3) {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    slots.forEach { slot ->
                        val children = action.childList(slot)
                        val branchColor = when (slot) {
                            Slot.THEN -> Color(0xFF10B981)
                            Slot.ELSE -> Color(0xFFF59E0B)
                            Slot.BODY -> Color(0xFF6366F1)
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = branchColor.copy(alpha = 0.08f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, branchColor.copy(alpha = 0.35f))
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
                                    ActionNodeCard(
                                        action = child,
                                        index = childIdx,
                                        siblingCount = children.size,
                                        depth = depth + 1,
                                        imageByName = imageByName,
                                        onEdit = onEdit,
                                        onPickTargetOnScreen = onPickTargetOnScreen,
                                        onAddBranchClickTarget = onAddBranchClickTarget,
                                        onToggleEnabled = onToggleEnabled,
                                        onMove = onMove,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddActionPickerSheet(
    isNested: Boolean,
    onPick: (ActionKind) -> Unit,
    onDismiss: () -> Unit
) {
    val groups = listOf(
        "Deteksi (Macrorify)" to listOf(ActionKind.IMAGE, ActionKind.COLOR),
        "Sentuhan & Gestur" to listOf(ActionKind.CLICK, ActionKind.LONG_PRESS, ActionKind.SWIPE, ActionKind.ZOOM, ActionKind.WAIT),
        "Alur & Hubungkan Macro" to listOf(ActionKind.LOOP, ActionKind.RUN_MACRO, ActionKind.RESTART, ActionKind.STOP)
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Pengaturan Macro", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider()

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
                        onSave(
                            macro.copy(
                                name = name.trim().ifBlank { "Macro Baru" },
                                loopCount = if (infinite) 0 else (loopCount.toIntOrNull() ?: 1).coerceAtLeast(1),
                                loopDelayMs = (loopDelay.toLongOrNull() ?: 500L).coerceAtLeast(0L),
                                jitterPx = (jitter.toIntOrNull() ?: 0).coerceIn(0, 60),
                                randomDelayMs = (randomDelay.toLongOrNull() ?: 0L).coerceAtLeast(0L),
                                maxDurationMin = (maxMin.toIntOrNull() ?: 0).coerceIn(0, 1440),
                                autoRecoverStuckSec = (stuckSec.toIntOrNull() ?: 0).coerceIn(0, 3600),
                                stuckRecoveryMode = recoveryMode
                            )
                        )
                    },
                    modifier = Modifier.weight(1.4f)
                ) { Text("Simpan") }
            }
        }
    }
}
