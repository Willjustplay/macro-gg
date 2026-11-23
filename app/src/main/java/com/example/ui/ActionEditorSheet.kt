package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.automation.PickedPixel
import com.example.data.Action
import com.example.data.ClickAction
import com.example.data.ColorAction
import com.example.data.ImageAction
import com.example.data.ImageAsset
import com.example.data.LongPressAction
import com.example.data.LoopAction
import com.example.data.NotFoundMode
import com.example.data.RecordAction
import com.example.data.Region
import com.example.data.RestartAction
import com.example.data.StopAction
import com.example.data.SwipeAction
import com.example.data.WaitAction
import com.example.data.kind
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ActionEditorSheet(
    action: Action,
    images: List<ImageAsset>,
    lastPickedPixel: PickedPixel?,
    onLaunchCropForAction: (actionId: String) -> Unit,
    onSave: (Action) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember(action.id) { mutableStateOf(action) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Gambar Target", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Button(
                            onClick = {
                                onSave(draft)
                                onLaunchCropForAction(a.id)
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Mode Crop")
                        }
                    }

                    if (images.isEmpty()) {
                        Text(
                            "Belum ada gambar di pustaka. Ketuk 'Crop Layar' di atas atau tab Gambar untuk mengambil potongan gambar target.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(images, key = { it.name }) { asset ->
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
                                                contentDescription = asset.name,
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier.size(60.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        asset.name.removeSuffix(".png"),
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
                            valueRange = 0.55f..0.99f
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

                    // Click on found toggle
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Klik gambar saat ditemukan", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("Ketuk otomatis di tengah gambar yang cocok", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
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
                                draft = a.copy(region = if (it) (a.region ?: Region(0, 0, 1080, 1200)) else null)
                            }
                        )
                    }
                    if (useRegion) {
                        val reg = a.region ?: Region(0, 0, 1080, 1200)
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

                    NotFoundModePicker(a.notFound) { draft = a.copy(notFound = it) }
                }

                is ColorAction -> {
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
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Klik titik saat warna cocok", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = a.clickOnFound, onCheckedChange = { draft = a.copy(clickOnFound = it) })
                    }
                    NotFoundModePicker(a.notFound) { draft = a.copy(notFound = it) }
                }

                is LoopAction -> {
                    NumberField("Jumlah pengulangan", a.count.toString()) {
                        draft = a.copy(count = (it.toIntOrNull() ?: a.count).coerceIn(1, 9999))
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

                is RestartAction, is StopAction -> {
                    Text(
                        "Aksi kontrol alur tidak memerlukan pengaturan tambahan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotFoundModePicker(current: NotFoundMode, onChange: (NotFoundMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Jika tidak ditemukan (setelah cabang Else):", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NotFoundMode.entries.forEach { mode ->
                FilterChip(
                    selected = current == mode,
                    onClick = { onChange(mode) },
                    label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) }
                )
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
