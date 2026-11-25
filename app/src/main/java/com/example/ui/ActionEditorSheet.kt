package com.example.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.automation.InstalledAppInfo
import com.example.automation.InstalledAppsHelper
import com.example.automation.PickedPixel
import com.example.data.Action
import com.example.data.ActionKind
import com.example.data.ClickAction
import com.example.data.ColorAction
import com.example.data.ExitAppAction
import com.example.data.ExitAppMode
import com.example.data.ImageAction
import com.example.data.ImageAsset
import com.example.data.LaunchAppAction
import com.example.data.LongPressAction
import com.example.data.LoopAction
import com.example.data.Macro
import com.example.data.NotFoundMode
import com.example.data.RecordAction
import com.example.data.Region
import com.example.data.RestartAction
import com.example.data.RunMacroAction
import com.example.data.StopAction
import com.example.data.SwipeAction
import com.example.data.TextAction
import com.example.data.TextCompareMode
import com.example.data.WaitAction
import com.example.data.ZoomAction
import com.example.data.create
import com.example.data.kind
import com.example.data.summary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ActionEditorSheet(
    action: Action,
    currentMacroId: String,
    allMacros: List<Macro>,
    images: List<ImageAsset>,
    imageFolders: List<String>,
    lastPickedPixel: PickedPixel?,
    screenW: Int,
    screenH: Int,
    onLaunchCropForAction: (actionId: String) -> Unit,
    onPickTargetOnScreen: (updatedDraft: Action, targetActionId: String) -> Unit,
    onPickRegionOnScreen: (updatedDraft: Action, targetActionId: String) -> Unit,
    onRecropImage: (String, Bitmap) -> Unit,
    onSave: (Action) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember(action.id) { mutableStateOf(action) }
    var recropAsset by remember { mutableStateOf<ImageAsset?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
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
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(draft.kind.accentColor().copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(draft.kind.icon(), contentDescription = null, tint = draft.kind.accentColor())
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(draft.kind.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(draft.kind.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            HorizontalDivider()

            when (val a = draft) {
                is ClickAction -> {
                    PickOnScreenButton("Atur Posisi Klik di Layar") {
                        onPickTargetOnScreen(draft, a.id)
                    }
                    CoordRow(a.x, a.y) { nx, ny -> draft = a.copy(x = nx, y = ny) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberField("Durasi tekan (ms)", a.durationMs.toString(), Modifier.weight(1f)) {
                            draft = a.copy(durationMs = (it.toLongOrNull() ?: a.durationMs).coerceIn(10L, 60_000L))
                        }
                        NumberField("Jumlah klik", a.repeat.toString(), Modifier.weight(1f)) {
                            draft = a.copy(repeat = (it.toIntOrNull() ?: a.repeat).coerceIn(1, 999))
                        }
                    }
                    if (a.repeat > 1) {
                        NumberField("Interval antar klik (ms)", a.repeatIntervalMs.toString()) {
                            draft = a.copy(repeatIntervalMs = (it.toLongOrNull() ?: a.repeatIntervalMs).coerceAtLeast(10L))
                        }
                    }
                    NumberField("Jeda sesudah aksi (ms)", a.delayAfterMs.toString()) {
                        draft = a.copy(delayAfterMs = (it.toLongOrNull() ?: a.delayAfterMs).coerceAtLeast(0L))
                    }
                }

                is LongPressAction -> {
                    PickOnScreenButton("Atur Posisi Tahan di Layar") {
                        onPickTargetOnScreen(draft, a.id)
                    }
                    CoordRow(a.x, a.y) { nx, ny -> draft = a.copy(x = nx, y = ny) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberField("Durasi tahan (ms)", a.durationMs.toString(), Modifier.weight(1f)) {
                            draft = a.copy(durationMs = (it.toLongOrNull() ?: a.durationMs).coerceIn(250L, 60_000L))
                        }
                        NumberField("Jeda sesudah (ms)", a.delayAfterMs.toString(), Modifier.weight(1f)) {
                            draft = a.copy(delayAfterMs = (it.toLongOrNull() ?: a.delayAfterMs).coerceAtLeast(0L))
                        }
                    }
                }

                is SwipeAction -> {
                    PickOnScreenButton("Atur Garis Swipe A→B di Layar") {
                        onPickTargetOnScreen(draft, a.id)
                    }
                    Text("Titik Awal (A)", style = MaterialTheme.typography.labelMedium)
                    CoordRow(a.x1, a.y1) { nx, ny -> draft = a.copy(x1 = nx, y1 = ny) }
                    Text("Titik Akhir (B)", style = MaterialTheme.typography.labelMedium)
                    CoordRow(a.x2, a.y2) { nx, ny -> draft = a.copy(x2 = nx, y2 = ny) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberField("Durasi swipe (ms)", a.durationMs.toString(), Modifier.weight(1f)) {
                            draft = a.copy(durationMs = (it.toLongOrNull() ?: a.durationMs).coerceIn(30L, 10_000L))
                        }
                        NumberField("Jeda sesudah (ms)", a.delayAfterMs.toString(), Modifier.weight(1f)) {
                            draft = a.copy(delayAfterMs = (it.toLongOrNull() ?: a.delayAfterMs).coerceAtLeast(0L))
                        }
                    }
                }

                is ZoomAction -> {
                    PickOnScreenButton("Atur Titik Pusat Zoom di Layar") {
                        onPickTargetOnScreen(draft, a.id)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = a.zoomIn,
                            onClick = { draft = a.copy(zoomIn = true) },
                            label = { Text("Zoom In (Perbesar)") }
                        )
                        FilterChip(
                            selected = !a.zoomIn,
                            onClick = { draft = a.copy(zoomIn = false) },
                            label = { Text("Zoom Out (Perkecil)") }
                        )
                    }
                    CoordRow(a.cx, a.cy) { nx, ny -> draft = a.copy(cx = nx, cy = ny) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberField("Jarak cubit (px)", a.distancePx.toString(), Modifier.weight(1f)) {
                            draft = a.copy(distancePx = (it.toIntOrNull() ?: a.distancePx).coerceIn(80, 900))
                        }
                        NumberField("Durasi (ms)", a.durationMs.toString(), Modifier.weight(1f)) {
                            draft = a.copy(durationMs = (it.toLongOrNull() ?: a.durationMs).coerceIn(60L, 5000L))
                        }
                    }
                    NumberField("Jeda sesudah (ms)", a.delayAfterMs.toString()) {
                        draft = a.copy(delayAfterMs = (it.toLongOrNull() ?: a.delayAfterMs).coerceAtLeast(0L))
                    }
                }

                is WaitAction -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberField("Tunggu (ms)", a.durationMs.toString(), Modifier.weight(1f)) {
                            draft = a.copy(durationMs = (it.toLongOrNull() ?: a.durationMs).coerceAtLeast(0L))
                        }
                        NumberField("Acak tambahan (ms)", a.randomMs.toString(), Modifier.weight(1f)) {
                            draft = a.copy(randomMs = (it.toLongOrNull() ?: a.randomMs).coerceAtLeast(0L))
                        }
                    }
                }

                is ImageAction -> {
                    val selectedAsset = images.firstOrNull { it.name == a.imageName }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Gambar Target", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (selectedAsset != null) {
                                FilledTonalButton(
                                    onClick = { recropAsset = selectedAsset },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text("Potong Ulang", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Button(
                                onClick = {
                                    onSave(draft)
                                    onLaunchCropForAction(a.id)
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Mode Crop", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    if (images.isEmpty()) {
                        Text(
                            "Belum ada gambar di pustaka. Ketuk 'Mode Crop' di atas untuk mengambil potongan gambar target.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        var folderFilter by remember(a.id) { mutableStateOf<String?>(null) }
                        if (imageFolders.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                item(key = "f_all") {
                                    FilterChip(
                                        selected = folderFilter == null,
                                        onClick = { folderFilter = null },
                                        label = { Text("Semua", style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                                item(key = "f_root") {
                                    FilterChip(
                                        selected = folderFilter == "",
                                        onClick = { folderFilter = "" },
                                        label = { Text("Utama", style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                                items(imageFolders, key = { "f_$it" }) { f ->
                                    FilterChip(
                                        selected = folderFilter == f,
                                        onClick = { folderFilter = f },
                                        label = { Text("📁 $f", style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }
                        val filteredImages = remember(images, folderFilter) {
                            when (folderFilter) {
                                null -> images
                                "" -> images.filter { it.folder.isBlank() }
                                else -> images.filter { it.folder == folderFilter }
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(filteredImages, key = { it.name }) { asset ->
                                val selected = asset.name == a.imageName
                                val bmp = rememberAssetThumbnail(asset.path, asset.updatedAt)
                                Column(
                                    modifier = Modifier
                                        .width(86.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                        .clickable { draft = a.copy(imageName = asset.name) }
                                        .padding(6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF0F172A)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp,
                                                contentDescription = asset.displayName,
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier.size(60.dp)
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

                    // Similarity slider
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Kemiripan (Akurasi)", style = MaterialTheme.typography.bodyMedium)
                            Text("${(a.threshold * 100).roundToInt()}%", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = a.threshold,
                            onValueChange = { draft = a.copy(threshold = it) },
                            valueRange = 0.50f..0.99f
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberField("Batas waktu cari (ms)", a.timeoutMs.toString(), Modifier.weight(1f)) {
                            draft = a.copy(timeoutMs = (it.toLongOrNull() ?: a.timeoutMs).coerceAtLeast(0L))
                        }
                        NumberField("Jeda sesudah (ms)", a.delayAfterMs.toString(), Modifier.weight(1f)) {
                            draft = a.copy(delayAfterMs = (it.toLongOrNull() ?: a.delayAfterMs).coerceAtLeast(0L))
                        }
                    }

                    // ---- FULL "IF FOUND" SETTINGS ----
                    HorizontalDivider()
                    BranchSectionEditor(
                        title = "Saat Ditemukan (Jika Cocok)",
                        accent = Color(0xFF10B981),
                        modeLabel = "Setelah aksi Jika Ditemukan:",
                        mode = a.foundMode,
                        onModeChange = { draft = a.copy(foundMode = it) },
                        branchActions = a.thenActions,
                        onAddChild = { child -> draft = a.copy(thenActions = a.thenActions + child) },
                        onAddClickTargetOnScreen = {
                            val newClick = ClickAction(x = screenW / 2, y = screenH / 2, delayAfterMs = 250L)
                            val updated = a.copy(thenActions = a.thenActions + newClick)
                            draft = updated
                            onPickTargetOnScreen(updated, newClick.id)
                        },
                        onPickChildOnScreen = { childId ->
                            onPickTargetOnScreen(draft, childId)
                        },
                        onDeleteChild = { cid -> draft = a.copy(thenActions = a.thenActions.filter { it.id != cid }) },
                        screenW = screenW,
                        screenH = screenH,
                        defaultImage = images.firstOrNull()?.name.orEmpty(),
                        extraContent = {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Klik otomatis di tengah gambar", style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = a.clickOnFound, onCheckedChange = { draft = a.copy(clickOnFound = it) })
                            }
                            if (a.clickOnFound) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SignedNumberField("Offset X (px)", a.offsetX.toString(), Modifier.weight(1f)) {
                                        draft = a.copy(offsetX = it.toIntOrNull() ?: a.offsetX)
                                    }
                                    SignedNumberField("Offset Y (px)", a.offsetY.toString(), Modifier.weight(1f)) {
                                        draft = a.copy(offsetY = it.toIntOrNull() ?: a.offsetY)
                                    }
                                }
                            }
                        }
                    )

                    // ---- FULL "IF NOT FOUND" SETTINGS ----
                    BranchSectionEditor(
                        title = "Saat Tidak Ditemukan (Else)",
                        accent = Color(0xFFF59E0B),
                        modeLabel = "Setelah aksi Jika Tidak Ditemukan:",
                        mode = a.notFound,
                        onModeChange = { draft = a.copy(notFound = it) },
                        branchActions = a.elseActions,
                        onAddChild = { child -> draft = a.copy(elseActions = a.elseActions + child) },
                        onAddClickTargetOnScreen = {
                            val newClick = ClickAction(x = screenW / 2, y = screenH / 2, delayAfterMs = 250L)
                            val updated = a.copy(elseActions = a.elseActions + newClick)
                            draft = updated
                            onPickTargetOnScreen(updated, newClick.id)
                        },
                        onPickChildOnScreen = { childId ->
                            onPickTargetOnScreen(draft, childId)
                        },
                        onDeleteChild = { cid -> draft = a.copy(elseActions = a.elseActions.filter { it.id != cid }) },
                        screenW = screenW,
                        screenH = screenH,
                        defaultImage = images.firstOrNull()?.name.orEmpty()
                    )

                    // Optional search area
                    var useRegion by remember(a.id) { mutableStateOf(a.region != null) }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Batasi area pencarian (opsional)", style = MaterialTheme.typography.bodyMedium)
                            Text("Lebih cepat jika gambar selalu muncul di area tertentu", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = useRegion,
                            onCheckedChange = {
                                useRegion = it
                                draft = a.copy(region = if (it) (a.region ?: Region(0, 0, screenW, screenH)) else null)
                            }
                        )
                    }
                    if (useRegion) {
                        FilledTonalButton(
                            onClick = { onPickRegionOnScreen(draft, a.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.CropFree, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Pilih Area Pencarian di Layar")
                        }
                        val reg = a.region ?: Region(0, 0, screenW, screenH)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField("Kiri", reg.left.toString(), Modifier.weight(1f)) {
                                draft = a.copy(region = reg.copy(left = it.toIntOrNull() ?: reg.left))
                            }
                            NumberField("Atas", reg.top.toString(), Modifier.weight(1f)) {
                                draft = a.copy(region = reg.copy(top = it.toIntOrNull() ?: reg.top))
                            }
                            NumberField("Kanan", reg.right.toString(), Modifier.weight(1f)) {
                                draft = a.copy(region = reg.copy(right = it.toIntOrNull() ?: reg.right))
                            }
                            NumberField("Bawah", reg.bottom.toString(), Modifier.weight(1f)) {
                                draft = a.copy(region = reg.copy(bottom = it.toIntOrNull() ?: reg.bottom))
                            }
                        }
                    }
                }

                is TextAction -> {
                    FilledTonalButton(
                        onClick = { onPickRegionOnScreen(draft, a.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.CropFree, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Pilih Area Baca Teks/Nilai di Layar")
                    }

                    Text("Mode Deteksi Teks & Nilai", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextCompareMode.entries.forEach { m ->
                            FilterChip(
                                selected = a.compareMode == m,
                                onClick = { draft = a.copy(compareMode = m) },
                                label = { Text(m.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    if (!a.compareMode.isNumeric) {
                        OutlinedTextField(
                            value = a.query,
                            onValueChange = { draft = a.copy(query = it) },
                            label = { Text("Teks target (tidak peka huruf besar/kecil)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        val initNumStr = remember(a.id) {
                            if (a.targetNumber == a.targetNumber.toLong().toDouble()) {
                                a.targetNumber.toLong().toString()
                            } else {
                                a.targetNumber.toString()
                            }
                        }
                        var numText by remember(a.id) { mutableStateOf(initNumStr) }
                        OutlinedTextField(
                            value = numText,
                            onValueChange = { raw ->
                                val filtered = raw.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }
                                numText = filtered
                                filtered.replace(',', '.').toDoubleOrNull()?.let { parsed ->
                                    draft = a.copy(targetNumber = parsed)
                                }
                            },
                            label = { Text("Nilai pembanding (mendukung desimal & negatif)") },
                            supportingText = { Text("Contoh: 500, 12.5, atau 10000 (otomatis baca format 10K / 1,500)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberField("Batas waktu cari (ms)", a.timeoutMs.toString(), Modifier.weight(1f)) {
                            draft = a.copy(timeoutMs = (it.toLongOrNull() ?: a.timeoutMs).coerceAtLeast(0L))
                        }
                        NumberField("Jeda sesudah (ms)", a.delayAfterMs.toString(), Modifier.weight(1f)) {
                            draft = a.copy(delayAfterMs = (it.toLongOrNull() ?: a.delayAfterMs).coerceAtLeast(0L))
                        }
                    }

                    HorizontalDivider()
                    BranchSectionEditor(
                        title = "Saat Teks / Nilai Cocok (Jika Ditemukan)",
                        accent = Color(0xFF10B981),
                        modeLabel = "Setelah cabang Jika Cocok:",
                        mode = a.foundMode,
                        onModeChange = { draft = a.copy(foundMode = it) },
                        branchActions = a.thenActions,
                        onAddChild = { child -> draft = a.copy(thenActions = a.thenActions + child) },
                        onAddClickTargetOnScreen = {
                            val newClick = ClickAction(x = screenW / 2, y = screenH / 2, delayAfterMs = 250L)
                            val updated = a.copy(thenActions = a.thenActions + newClick)
                            draft = updated
                            onPickTargetOnScreen(updated, newClick.id)
                        },
                        onPickChildOnScreen = { childId ->
                            onPickTargetOnScreen(draft, childId)
                        },
                        onDeleteChild = { cid -> draft = a.copy(thenActions = a.thenActions.filter { it.id != cid }) },
                        screenW = screenW,
                        screenH = screenH,
                        defaultImage = images.firstOrNull()?.name.orEmpty(),
                        extraContent = {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Klik posisi teks saat cocok", style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = a.clickOnFound, onCheckedChange = { draft = a.copy(clickOnFound = it) })
                            }
                        }
                    )

                    BranchSectionEditor(
                        title = "Saat Tidak Cocok (Else)",
                        accent = Color(0xFFF59E0B),
                        modeLabel = "Setelah cabang Tidak Cocok:",
                        mode = a.notFound,
                        onModeChange = { draft = a.copy(notFound = it) },
                        branchActions = a.elseActions,
                        onAddChild = { child -> draft = a.copy(elseActions = a.elseActions + child) },
                        onAddClickTargetOnScreen = {
                            val newClick = ClickAction(x = screenW / 2, y = screenH / 2, delayAfterMs = 250L)
                            val updated = a.copy(elseActions = a.elseActions + newClick)
                            draft = updated
                            onPickTargetOnScreen(updated, newClick.id)
                        },
                        onPickChildOnScreen = { childId ->
                            onPickTargetOnScreen(draft, childId)
                        },
                        onDeleteChild = { cid -> draft = a.copy(elseActions = a.elseActions.filter { it.id != cid }) },
                        screenW = screenW,
                        screenH = screenH,
                        defaultImage = images.firstOrNull()?.name.orEmpty()
                    )

                    var useRegion by remember(a.id) { mutableStateOf(a.region != null) }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Batasi area baca OCR (disarankan)", style = MaterialTheme.typography.bodyMedium)
                            Text("Membaca hanya di kotak angka/teks agar lebih cepat & akurat", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = useRegion,
                            onCheckedChange = {
                                useRegion = it
                                draft = a.copy(region = if (it) (a.region ?: Region(0, 0, screenW, screenH)) else null)
                            }
                        )
                    }
                    if (useRegion) {
                        val reg = a.region ?: Region(0, 0, screenW, screenH)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField("Kiri", reg.left.toString(), Modifier.weight(1f)) {
                                draft = a.copy(region = reg.copy(left = it.toIntOrNull() ?: reg.left))
                            }
                            NumberField("Atas", reg.top.toString(), Modifier.weight(1f)) {
                                draft = a.copy(region = reg.copy(top = it.toIntOrNull() ?: reg.top))
                            }
                            NumberField("Kanan", reg.right.toString(), Modifier.weight(1f)) {
                                draft = a.copy(region = reg.copy(right = it.toIntOrNull() ?: reg.right))
                            }
                            NumberField("Bawah", reg.bottom.toString(), Modifier.weight(1f)) {
                                draft = a.copy(region = reg.copy(bottom = it.toIntOrNull() ?: reg.bottom))
                            }
                        }
                    }
                }

                is ColorAction -> {
                    PickOnScreenButton("Pilih Posisi & Warna di Layar") {
                        onPickTargetOnScreen(draft, a.id)
                    }
                    if (lastPickedPixel != null) {
                        OutlinedButton(
                            onClick = {
                                draft = a.copy(x = lastPickedPixel.x, y = lastPickedPixel.y, color = lastPickedPixel.hexColor)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val dotColor = parseHexColorOrNull(lastPickedPixel.hexColor) ?: Color.White
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                                    .border(1.dp, Color.Gray, CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Pakai Color Spy: (${lastPickedPixel.x},${lastPickedPixel.y}) ${lastPickedPixel.hexColor}")
                        }
                    }
                    CoordRow(a.x, a.y) { nx, ny -> draft = a.copy(x = nx, y = ny) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = a.color,
                            onValueChange = { draft = a.copy(color = it.uppercase().take(7)) },
                            label = { Text("Warna (#RRGGBB)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            leadingIcon = {
                                Box(
                                    Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(parseHexColorOrNull(a.color) ?: Color.Transparent)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                )
                            }
                        )
                        NumberField("Toleransi", a.tolerance.toString(), Modifier.width(105.dp)) {
                            draft = a.copy(tolerance = (it.toIntOrNull() ?: a.tolerance).coerceIn(0, 128))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberField("Batas waktu (ms)", a.timeoutMs.toString(), Modifier.weight(1f)) {
                            draft = a.copy(timeoutMs = (it.toLongOrNull() ?: a.timeoutMs).coerceAtLeast(0L))
                        }
                        NumberField("Jeda sesudah (ms)", a.delayAfterMs.toString(), Modifier.weight(1f)) {
                            draft = a.copy(delayAfterMs = (it.toLongOrNull() ?: a.delayAfterMs).coerceAtLeast(0L))
                        }
                    }

                    HorizontalDivider()
                    BranchSectionEditor(
                        title = "Saat Warna Cocok (Jika Ditemukan)",
                        accent = Color(0xFF10B981),
                        modeLabel = "Setelah cabang Jika Cocok:",
                        mode = a.foundMode,
                        onModeChange = { draft = a.copy(foundMode = it) },
                        branchActions = a.thenActions,
                        onAddChild = { child -> draft = a.copy(thenActions = a.thenActions + child) },
                        onAddClickTargetOnScreen = {
                            val newClick = ClickAction(x = screenW / 2, y = screenH / 2, delayAfterMs = 250L)
                            val updated = a.copy(thenActions = a.thenActions + newClick)
                            draft = updated
                            onPickTargetOnScreen(updated, newClick.id)
                        },
                        onPickChildOnScreen = { childId ->
                            onPickTargetOnScreen(draft, childId)
                        },
                        onDeleteChild = { cid -> draft = a.copy(thenActions = a.thenActions.filter { it.id != cid }) },
                        screenW = screenW,
                        screenH = screenH,
                        defaultImage = images.firstOrNull()?.name.orEmpty(),
                        extraContent = {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Klik titik saat warna cocok", style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = a.clickOnFound, onCheckedChange = { draft = a.copy(clickOnFound = it) })
                            }
                        }
                    )
                    BranchSectionEditor(
                        title = "Saat Warna Tidak Cocok (Else)",
                        accent = Color(0xFFF59E0B),
                        modeLabel = "Setelah cabang Tidak Cocok:",
                        mode = a.notFound,
                        onModeChange = { draft = a.copy(notFound = it) },
                        branchActions = a.elseActions,
                        onAddChild = { child -> draft = a.copy(elseActions = a.elseActions + child) },
                        onAddClickTargetOnScreen = {
                            val newClick = ClickAction(x = screenW / 2, y = screenH / 2, delayAfterMs = 250L)
                            val updated = a.copy(elseActions = a.elseActions + newClick)
                            draft = updated
                            onPickTargetOnScreen(updated, newClick.id)
                        },
                        onPickChildOnScreen = { childId ->
                            onPickTargetOnScreen(draft, childId)
                        },
                        onDeleteChild = { cid -> draft = a.copy(elseActions = a.elseActions.filter { it.id != cid }) },
                        screenW = screenW,
                        screenH = screenH,
                        defaultImage = images.firstOrNull()?.name.orEmpty()
                    )
                }

                is LoopAction -> {
                    NumberField("Jumlah pengulangan", a.count.toString()) {
                        draft = a.copy(count = (it.toIntOrNull() ?: a.count).coerceIn(1, 9999))
                    }
                }

                is RunMacroAction -> {
                    val others = allMacros.filter { it.id != currentMacroId }
                    Text("Pilih Macro Tujuan:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    if (others.isEmpty()) {
                        Text(
                            "Belum ada macro lain. Buat macro baru di beranda terlebih dahulu agar dapat dihubungkan.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            others.forEach { target ->
                                val tag = when {
                                    com.example.data.MacroSecurity.isExpired(target) -> " (Habis)"
                                    target.isPassive && target.passiveEnabled -> " (Pasif ON)"
                                    target.isPassive && !target.passiveEnabled -> " (Pasif OFF • skip)"
                                    else -> ""
                                }
                                FilterChip(
                                    selected = a.targetMacroId == target.id,
                                    onClick = {
                                        draft = a.copy(targetMacroId = target.id, targetMacroName = target.name)
                                    },
                                    label = { Text(target.name + tag) }
                                )
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Kembali setelah macro tujuan selesai", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (a.returnAfter) "Panggil sebagai sub-macro lalu lanjutkan macro ini"
                                else "Pindah penuh ke macro tujuan lalu berhenti",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = a.returnAfter, onCheckedChange = { draft = a.copy(returnAfter = it) })
                    }
                    NumberField("Jeda sesudah (ms)", a.delayAfterMs.toString()) {
                        draft = a.copy(delayAfterMs = (it.toLongOrNull() ?: a.delayAfterMs).coerceAtLeast(0L))
                    }
                }

                is RecordAction -> {
                    var speed by remember(a.id) { mutableFloatStateOf(a.speed) }
                    Text("Berisi ${a.gestures.size} gestur sentuhan yang direkam.", style = MaterialTheme.typography.bodyMedium)
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Kecepatan putar ulang")
                            Text("${String.format("%.1f", speed)}x", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = speed,
                            onValueChange = {
                                speed = (it * 4f).roundToInt() / 4f
                                draft = a.copy(speed = speed)
                            },
                            valueRange = 0.5f..3.0f
                        )
                    }
                    NumberField("Jeda sesudah (ms)", a.delayAfterMs.toString()) {
                        draft = a.copy(delayAfterMs = (it.toLongOrNull() ?: a.delayAfterMs).coerceAtLeast(0L))
                    }
                }

                is ExitAppAction -> {
                    var showAppPicker by remember { mutableStateOf(false) }
                    Text(
                        "Cara Keluar Aplikasi",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ExitAppMode.entries.forEach { m ->
                            FilterChip(
                                selected = a.mode == m,
                                onClick = { draft = a.copy(mode = m) },
                                label = { Text(m.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Text(
                        a.mode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilledTonalButton(
                        onClick = { showAppPicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (a.packageName.isBlank()) "Pilih Aplikasi Target (Opsional)"
                            else "Target: ${a.appLabel.ifBlank { a.packageName }}"
                        )
                    }
                    OutlinedTextField(
                        value = a.packageName,
                        onValueChange = { pkg ->
                            draft = a.copy(
                                packageName = pkg.trim(),
                                appLabel = if (pkg.trim() != a.packageName) "" else a.appLabel
                            )
                        },
                        label = { Text("Package Name (opsional, untuk bersihkan background)") },
                        placeholder = { Text("contoh: com.supercell.clashofclans") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    NumberField("Jeda sesudah keluar (ms)", a.delayAfterMs.toString()) {
                        draft = a.copy(delayAfterMs = (it.toLongOrNull() ?: a.delayAfterMs).coerceAtLeast(0L))
                    }
                    if (showAppPicker) {
                        InstalledAppPickerDialog(
                            title = "Pilih Aplikasi (Opsional)",
                            allowClear = true,
                            onSelect = { info ->
                                draft = a.copy(packageName = info?.packageName.orEmpty(), appLabel = info?.label.orEmpty())
                                showAppPicker = false
                            },
                            onDismiss = { showAppPicker = false }
                        )
                    }
                }

                is LaunchAppAction -> {
                    var showAppPicker by remember { mutableStateOf(false) }
                    FilledTonalButton(
                        onClick = { showAppPicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (a.packageName.isBlank()) "Pilih Aplikasi / Game Terpasang"
                            else "Ganti Aplikasi: ${a.appLabel.ifBlank { a.packageName }}"
                        )
                    }
                    OutlinedTextField(
                        value = a.packageName,
                        onValueChange = { pkg ->
                            draft = a.copy(
                                packageName = pkg.trim(),
                                appLabel = if (pkg.trim() != a.packageName) "" else a.appLabel
                            )
                        },
                        label = { Text("Package Name Aplikasi") },
                        placeholder = { Text("contoh: com.supercell.clashofclans") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f).padding(end = 10.dp)) {
                            Text("Buka Ulang dari Awal (Fresh Start)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "Tambahkan Clear Task agar aplikasi dimuat ulang bersih.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = a.freshStart,
                            onCheckedChange = { draft = a.copy(freshStart = it) }
                        )
                    }
                    NumberField("Jeda loading sesudah masuk (ms)", a.delayAfterMs.toString()) {
                        draft = a.copy(delayAfterMs = (it.toLongOrNull() ?: a.delayAfterMs).coerceAtLeast(0L))
                    }
                    if (showAppPicker) {
                        InstalledAppPickerDialog(
                            title = "Pilih Aplikasi untuk Dibuka",
                            allowClear = false,
                            onSelect = { info ->
                                if (info != null) {
                                    draft = a.copy(packageName = info.packageName, appLabel = info.label)
                                }
                                showAppPicker = false
                            },
                            onDismiss = { showAppPicker = false }
                        )
                    }
                }

                is RestartAction, is StopAction -> {
                    Text(
                        "Aksi kontrol alur (dapat diatur peluang Random Event di bawah jika ingin acak).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Shared Random Event probability control (applies to every action type)
            HorizontalDivider()
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Peluang Keberhasilan (Random Event)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (draft.chancePercent == 100) "100% — Aksi selalu dijalankan"
                                else "Aksi dijalankan acak dengan peluang ${draft.chancePercent}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                "${draft.chancePercent}%",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Slider(
                        value = draft.chancePercent.toFloat(),
                        onValueChange = { draft = draft.withChancePercent(it.roundToInt()) },
                        valueRange = 0f..100f
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(100, 75, 50, 25, 10).forEach { pct ->
                            FilterChip(
                                selected = draft.chancePercent == pct,
                                onClick = { draft = draft.withChancePercent(pct) },
                                label = { Text("$pct%", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Batal")
                }
                Button(onClick = { onSave(draft) }, modifier = Modifier.weight(1.4f)) {
                    Text("Simpan Aksi")
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

@Composable
private fun InstalledAppPickerDialog(
    title: String,
    allowClear: Boolean,
    onSelect: (InstalledAppInfo?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledAppInfo>?>(null) }
    var search by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            InstalledAppsHelper.loadLaunchableApps(context)
        }
    }

    val filtered = remember(apps, search) {
        val list = apps ?: emptyList()
        val q = search.trim().lowercase()
        if (q.isEmpty()) list
        else list.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Cari nama aplikasi / package") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (allowClear) {
                    TextButton(onClick = { onSelect(null) }) {
                        Text("Kosongkan (aplikasi apa saja yang aktif)")
                    }
                }
                when {
                    apps == null -> Text("Memuat daftar aplikasi...", style = MaterialTheme.typography.bodySmall)
                    filtered.isEmpty() -> Text("Tidak ada aplikasi cocok.", style = MaterialTheme.typography.bodySmall)
                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filtered, key = { it.packageName }) { info ->
                            Surface(
                                onClick = { onSelect(info) },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text(
                                        info.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        info.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Tutup") }
        }
    )
}

@Composable
private fun PickOnScreenButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

internal fun isPositionableAction(action: Action): Boolean = when (action) {
    is ClickAction, is LongPressAction, is SwipeAction, is ZoomAction, is ColorAction -> true
    else -> false
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BranchSectionEditor(
    title: String,
    accent: Color,
    modeLabel: String,
    mode: NotFoundMode,
    onModeChange: (NotFoundMode) -> Unit,
    branchActions: List<Action>,
    onAddChild: (Action) -> Unit,
    onAddClickTargetOnScreen: () -> Unit,
    onPickChildOnScreen: (childId: String) -> Unit,
    onDeleteChild: (String) -> Unit,
    screenW: Int,
    screenH: Int,
    defaultImage: String,
    extraContent: (@Composable () -> Unit)? = null
) {
    var showAddRow by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { showAddRow = !showAddRow },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(15.dp), tint = accent)
                    Spacer(Modifier.width(2.dp))
                    Text("Aksi Lain", style = MaterialTheme.typography.labelSmall, color = accent)
                }
            }

            extraContent?.invoke()

            FilledTonalButton(
                onClick = onAddClickTargetOnScreen,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp), tint = accent)
                Spacer(Modifier.width(6.dp))
                Text("+ Tempatkan Target Klik di Layar", style = MaterialTheme.typography.labelMedium)
            }

            if (showAddRow) {
                val quickKinds = listOf(
                    ActionKind.CLICK, ActionKind.SWIPE, ActionKind.ZOOM,
                    ActionKind.WAIT, ActionKind.IMAGE, ActionKind.TEXT,
                    ActionKind.EXIT_APP, ActionKind.LAUNCH_APP,
                    ActionKind.RUN_MACRO, ActionKind.RESTART, ActionKind.STOP
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    quickKinds.forEach { k ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                onAddChild(k.create(screenW, screenH, imageName = defaultImage))
                                showAddRow = false
                            },
                            label = { Text("+ ${k.label}", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            if (branchActions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    branchActions.forEachIndexed { idx, child ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${idx + 1}. ${child.kind.label} • ${child.summary()}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isPositionableAction(child)) {
                                IconButton(
                                    onClick = { onPickChildOnScreen(child.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.MyLocation,
                                        contentDescription = "Atur Posisi di Layar",
                                        modifier = Modifier.size(16.dp),
                                        tint = accent
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onDeleteChild(child.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "Hapus", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            Text(modeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NotFoundMode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { onModeChange(m) },
                        label = { Text(m.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CoordRow(x: Int, y: Int, onChange: (Int, Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberField("Koordinat X", x.toString(), Modifier.weight(1f)) {
            onChange(it.toIntOrNull() ?: x, y)
        }
        NumberField("Koordinat Y", y.toString(), Modifier.weight(1f)) {
            onChange(x, it.toIntOrNull() ?: y)
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit
) {
    var text by remember(label, value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() }
            text = filtered
            onChange(filtered)
        },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
private fun SignedNumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit
) {
    var text by remember(label, value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filterIndexed { i, c -> c.isDigit() || (c == '-' && i == 0) }
            text = filtered
            onChange(filtered)
        },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}
