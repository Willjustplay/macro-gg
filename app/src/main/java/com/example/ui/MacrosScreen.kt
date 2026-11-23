package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.automation.AutomationCoordinator
import com.example.automation.PickedPixel
import com.example.data.MacroStep
import com.example.data.MacroStepType
import com.example.data.UiState
import com.example.data.VisualMacro

@Composable
fun MacrosScreen(
    isExpandedScreen: Boolean,
    macrosState: UiState<List<VisualMacro>>,
    lastPickedPixel: PickedPixel?,
    onSaveMacro: (String?, String, String, Int, Int, Boolean, List<MacroStep>, (String) -> Unit) -> Unit,
    onDeleteMacro: (String) -> Unit,
    onLaunchOverlayWithMacro: (VisualMacro, List<MacroStep>) -> Unit,
    onRunMacroNow: (VisualMacro, List<MacroStep>) -> Unit
) {
    var editingMacroId by remember { mutableStateOf<String?>(null) }
    var isEditing by remember { mutableStateOf(false) }

    var draftName by remember { mutableStateOf("") }
    var draftDesc by remember { mutableStateOf("") }
    var draftLoopCount by remember { mutableStateOf("1") }
    var draftLoopDelay by remember { mutableStateOf("800") }
    var draftStopOnError by remember { mutableStateOf(true) }
    var draftSteps by remember { mutableStateOf<List<MacroStep>>(emptyList()) }

    fun openForNew() {
        editingMacroId = null
        draftName = "Macro Baru"
        draftDesc = ""
        draftLoopCount = "1"
        draftLoopDelay = "800"
        draftStopOnError = true
        draftSteps = listOf(
            MacroStep(type = MacroStepType.CLICK, label = "Klik #1", x = 540, y = 960)
        )
        isEditing = true
        AutomationCoordinator.syncDraftSteps(draftSteps)
    }

    fun openForExisting(macro: VisualMacro) {
        editingMacroId = macro.id
        draftName = macro.name
        draftDesc = macro.description
        draftLoopCount = macro.loopCount.toString()
        draftLoopDelay = macro.loopDelayMs.toString()
        draftStopOnError = macro.stopOnError
        draftSteps = macro.parsedSteps()
        isEditing = true
        AutomationCoordinator.selectMacroForRun(macro, draftSteps)
    }

    // Keep live overlay marker drags reflected in editor
    LaunchedEffect(isEditing) {
        if (isEditing) {
            AutomationCoordinator.activeMacroSteps.collect { liveSteps ->
                if (liveSteps.isNotEmpty()) {
                    draftSteps = liveSteps
                }
            }
        }
    }

    if (!isExpandedScreen && isEditing) {
        BackHandler { isEditing = false }
    }

    val listPane: @Composable () -> Unit = {
        Scaffold(
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { openForNew() },
                    modifier = Modifier.testTag("add_macro_fab")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Buat Macro Baru")
                }
            }
        ) { pad ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "Visual Macros",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 12.dp)
                )
                when (macrosState) {
                    is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    is UiState.Error -> Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = macrosState.message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    is UiState.Success -> {
                        if (macrosState.data.isEmpty()) {
                            EmptyMacroState(onCreate = { openForNew() })
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(bottom = 84.dp)
                            ) {
                                items(macrosState.data, key = { it.id }) { macro ->
                                    val steps = macro.parsedSteps()
                                    MacroListCard(
                                        macro = macro,
                                        stepCount = steps.size,
                                        isSelected = isEditing && editingMacroId == macro.id,
                                        onClick = { openForExisting(macro) },
                                        onOverlay = { onLaunchOverlayWithMacro(macro, steps) },
                                        onRun = { onRunMacroNow(macro, steps) },
                                        onDelete = {
                                            if (editingMacroId == macro.id) isEditing = false
                                            onDeleteMacro(macro.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val detailPane: @Composable () -> Unit = {
        MacroEditorPane(
            showBackButton = !isExpandedScreen,
            onBack = { isEditing = false },
            name = draftName,
            onNameChange = { draftName = it },
            description = draftDesc,
            onDescriptionChange = { draftDesc = it },
            loopCount = draftLoopCount,
            onLoopCountChange = { draftLoopCount = it.filter { c -> c.isDigit() } },
            loopDelay = draftLoopDelay,
            onLoopDelayChange = { draftLoopDelay = it.filter { c -> c.isDigit() } },
            stopOnError = draftStopOnError,
            onStopOnErrorChange = { draftStopOnError = it },
            steps = draftSteps,
            lastPickedPixel = lastPickedPixel,
            onStepsChange = { updated ->
                draftSteps = updated
                AutomationCoordinator.syncDraftSteps(updated)
            },
            onSave = {
                onSaveMacro(
                    editingMacroId,
                    draftName,
                    draftDesc,
                    draftLoopCount.toIntOrNull() ?: 1,
                    draftLoopDelay.toIntOrNull() ?: 800,
                    draftStopOnError,
                    draftSteps
                ) { savedId ->
                    editingMacroId = savedId
                }
            },
            onLaunchOverlay = {
                val preview = VisualMacro(
                    id = editingMacroId.orEmpty(),
                    name = draftName.ifBlank { "Macro Visual" },
                    description = draftDesc,
                    loopCount = draftLoopCount.toIntOrNull() ?: 1,
                    loopDelayMs = draftLoopDelay.toIntOrNull() ?: 800,
                    stopOnError = draftStopOnError,
                    steps = draftSteps.map { it.toFirestoreMap() }
                )
                onLaunchOverlayWithMacro(preview, draftSteps)
            },
            onRunNow = {
                val preview = VisualMacro(
                    id = editingMacroId.orEmpty(),
                    name = draftName.ifBlank { "Macro Visual" },
                    description = draftDesc,
                    loopCount = draftLoopCount.toIntOrNull() ?: 1,
                    loopDelayMs = draftLoopDelay.toIntOrNull() ?: 800,
                    stopOnError = draftStopOnError,
                    steps = draftSteps.map { it.toFirestoreMap() }
                )
                onRunMacroNow(preview, draftSteps)
            }
        )
    }

    if (isExpandedScreen) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(0.42f).fillMaxHeight()) { listPane() }
            Box(modifier = Modifier.weight(0.58f).fillMaxHeight()) {
                if (isEditing) detailPane()
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Pilih macro dari daftar atau tekan + untuk membuat baru.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    } else {
        if (isEditing) detailPane() else listPane()
    }
}

@Composable
private fun EmptyMacroState(onCreate: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.TouchApp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Text("Belum Ada Visual Macro", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Susun titik klik, tahan, swipe, dan kondisi warna piksel lalu sinkronkan ke cloud.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onCreate,
                modifier = Modifier.height(48.dp).testTag("empty_create_macro_button")
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Buat Macro Pertama")
            }
        }
    }
}

@Composable
private fun MacroListCard(
    macro: VisualMacro,
    stepCount: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    onOverlay: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("macro_item_card_${macro.id}")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = macro.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (macro.description.isNotBlank()) {
                        Text(
                            text = macro.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Hapus Macro",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = "$stepCount Langkah",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = if (macro.loopCount == 0) "Loop ∞" else "Loop ${macro.loopCount}x",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onOverlay) {
                        Icon(Icons.Filled.Layers, contentDescription = "Buka di Overlay")
                    }
                    IconButton(onClick = onRun) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Jalankan Macro",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MacroEditorPane(
    showBackButton: Boolean,
    onBack: () -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    loopCount: String,
    onLoopCountChange: (String) -> Unit,
    loopDelay: String,
    onLoopDelayChange: (String) -> Unit,
    stopOnError: Boolean,
    onStopOnErrorChange: (Boolean) -> Unit,
    steps: List<MacroStep>,
    lastPickedPixel: PickedPixel?,
    onStepsChange: (List<MacroStep>) -> Unit,
    onSave: () -> Unit,
    onLaunchOverlay: () -> Unit,
    onRunNow: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showBackButton) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                        }
                    }
                    Text("Editor Visual Macro", style = MaterialTheme.typography.titleLarge)
                }
                Button(
                    onClick = onSave,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(48.dp).testTag("save_macro_button")
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Simpan")
                }
            }
        }

        item {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("Nama Macro") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("macro_name_input")
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = description,
                onValueChange = onDescriptionChange,
                label = { Text("Deskripsi Singkat") },
                maxLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = loopCount,
                    onValueChange = onLoopCountChange,
                    label = { Text("Loop (0 = ∞)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = loopDelay,
                    onValueChange = onLoopDelayChange,
                    label = { Text("Jeda Loop (ms)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Hentikan otomatis jika langkah gagal/timeout", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = stopOnError, onCheckedChange = onStopOnErrorChange)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onLaunchOverlay,
                    modifier = Modifier.weight(1f).height(48.dp).testTag("macro_open_overlay_button")
                ) {
                    Icon(Icons.Filled.Layers, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Atur di Overlay")
                }
                Button(
                    onClick = onRunNow,
                    modifier = Modifier.weight(1f).height(48.dp).testTag("macro_run_now_button")
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Jalankan")
                }
            }
        }

        // Quick Add Step Chips
        item {
            Text("Tambah Langkah Aksi (${steps.size}/50)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(MacroStepType.entries) { type ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            if (steps.size < 50) {
                                val nextStep = MacroStep(
                                    type = type,
                                    label = "${type.label} #${steps.size + 1}",
                                    x = lastPickedPixel?.x ?: 540,
                                    y = lastPickedPixel?.y ?: 960,
                                    durationMs = when (type) {
                                        MacroStepType.LONG_PRESS -> 600
                                        MacroStepType.SWIPE -> 350
                                        MacroStepType.WAIT -> 1000
                                        else -> 60
                                    },
                                    targetColorHex = lastPickedPixel?.hexColor ?: "#2DD4BF"
                                )
                                onStepsChange(steps + nextStep)
                            }
                        },
                        label = { Text("+ ${type.label}") }
                    )
                }
            }
        }

        itemsIndexed(steps, key = { _, step -> step.stepId }) { index, step ->
            StepEditorCard(
                index = index,
                total = steps.size,
                step = step,
                lastPickedPixel = lastPickedPixel,
                onChange = { updated ->
                    onStepsChange(steps.mapIndexed { i, s -> if (i == index) updated else s })
                },
                onMoveUp = {
                    if (index > 0) {
                        val mutable = steps.toMutableList()
                        val item = mutable.removeAt(index)
                        mutable.add(index - 1, item)
                        onStepsChange(mutable)
                    }
                },
                onMoveDown = {
                    if (index < steps.lastIndex) {
                        val mutable = steps.toMutableList()
                        val item = mutable.removeAt(index)
                        mutable.add(index + 1, item)
                        onStepsChange(mutable)
                    }
                },
                onDelete = {
                    onStepsChange(steps.filterIndexed { i, _ -> i != index })
                }
            )
        }
    }
}

@Composable
private fun StepEditorCard(
    index: Int,
    total: Int,
    step: MacroStep,
    lastPickedPixel: PickedPixel?,
    onChange: (MacroStep) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(step.type.label, style = MaterialTheme.typography.titleSmall)
                }
                Row {
                    IconButton(onClick = onMoveUp, enabled = index > 0) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Naik")
                    }
                    IconButton(onClick = onMoveDown, enabled = index < total - 1) {
                        Icon(Icons.Filled.ArrowDownward, contentDescription = "Turun")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Hapus Langkah", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            OutlinedTextField(
                value = step.label,
                onValueChange = { onChange(step.copy(label = it)) },
                label = { Text("Label Langkah") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (step.type != MacroStepType.WAIT) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = step.x.toString(),
                        onValueChange = { onChange(step.copy(x = it.toIntOrNull() ?: 0)) },
                        label = { Text("X") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = step.y.toString(),
                        onValueChange = { onChange(step.copy(y = it.toIntOrNull() ?: 0)) },
                        label = { Text("Y") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (step.type == MacroStepType.SWIPE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = step.endX.toString(),
                        onValueChange = { onChange(step.copy(endX = it.toIntOrNull() ?: 0)) },
                        label = { Text("Tujuan X2") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = step.endY.toString(),
                        onValueChange = { onChange(step.copy(endY = it.toIntOrNull() ?: 0)) },
                        label = { Text("Tujuan Y2") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (step.type == MacroStepType.WAIT_COLOR) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val previewColor = runCatching {
                        Color(android.graphics.Color.parseColor(step.targetColorHex))
                    }.getOrDefault(MaterialTheme.colorScheme.primary)
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(previewColor)
                    )
                    OutlinedTextField(
                        value = step.targetColorHex,
                        onValueChange = { onChange(step.copy(targetColorHex = it.uppercase())) },
                        label = { Text("HEX (#RRGGBB)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = step.colorTolerance.toString(),
                        onValueChange = { onChange(step.copy(colorTolerance = (it.toIntOrNull() ?: 15).coerceIn(0, 255))) },
                        label = { Text("Tol") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(80.dp)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = step.timeoutMs.toString(),
                        onValueChange = { onChange(step.copy(timeoutMs = it.toIntOrNull() ?: 5000)) },
                        label = { Text("Timeout (ms)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    if (lastPickedPixel != null) {
                        OutlinedButton(
                            onClick = {
                                onChange(
                                    step.copy(
                                        x = lastPickedPixel.x,
                                        y = lastPickedPixel.y,
                                        targetColorHex = lastPickedPixel.hexColor
                                    )
                                )
                            },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(Icons.Filled.Colorize, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Pakai Spy")
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step.type != MacroStepType.WAIT_COLOR) {
                    OutlinedTextField(
                        value = step.durationMs.toString(),
                        onValueChange = { onChange(step.copy(durationMs = it.toIntOrNull() ?: 60)) },
                        label = { Text("Durasi (ms)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = step.delayAfterMs.toString(),
                    onValueChange = { onChange(step.copy(delayAfterMs = it.toIntOrNull() ?: 0)) },
                    label = { Text("Jeda Setelah (ms)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
