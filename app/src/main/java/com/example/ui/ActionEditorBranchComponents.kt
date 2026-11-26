package com.example.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.data.BannedImageMode
import com.example.data.BranchConditionType
import com.example.data.BranchNavigationMode
import com.example.data.BranchOutcomeAction
import com.example.data.BranchRouteRule
import com.example.data.BranchRouterAction
import com.example.data.GoToBranchAction
import com.example.data.ImageAction
import com.example.data.ImageAsset
import com.example.data.MacroBranch
import com.example.data.TextCompareMode
import com.example.data.newId
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TargetBranchPickerRow(
    title: String,
    branches: List<MacroBranch>,
    selectedBranchId: String,
    onSelect: (branchId: String, branchName: String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = selectedBranchId.isBlank(),
                onClick = { onSelect("", "Jalur Utama") },
                label = { Text("🟢 Jalur Utama", style = MaterialTheme.typography.labelSmall) }
            )
            branches.forEach { b ->
                val isSel = selectedBranchId == b.id
                val dotColor = parseHexColorOrNull(b.colorHex) ?: Color(0xFFF59E0B)
                FilterChip(
                    selected = isSel,
                    onClick = { onSelect(b.id, b.name) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                    },
                    label = { Text(b.name, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        if (branches.isEmpty()) {
            Text(
                "Tips: Buat cabang baru lewat tombol '+ Cabang' di bar atas editor macro agar bisa berbelok ke jalur lain.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BannedImagesEditorSection(
    action: ImageAction,
    images: List<ImageAsset>,
    onUpdate: (ImageAction) -> Unit
) {
    var showBannedPicker by remember(action.id) { mutableStateOf(false) }
    val bannedAccent = Color(0xFFEF4444)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bannedAccent.copy(alpha = if (action.bannedImages.isNotEmpty()) 0.09f else 0.04f),
        border = BorderStroke(
            1.dp,
            bannedAccent.copy(alpha = if (action.bannedImages.isNotEmpty()) 0.45f else 0.25f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Filled.Block,
                        contentDescription = null,
                        tint = bannedAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            "Gambar Terlarang (Hindari Klik)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = bannedAccent
                        )
                        Text(
                            if (action.bannedImages.isEmpty()) "Belum ada — opsional untuk mencegah salah klik"
                            else "${action.bannedImages.size} gambar diblokir • ${action.bannedMode.shortLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(
                    onClick = { showBannedPicker = !showBannedPicker },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (showBannedPicker) "Tutup" else "+ Pilih",
                        style = MaterialTheme.typography.labelSmall,
                        color = bannedAccent
                    )
                }
            }

            if (action.bannedImages.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    action.bannedImages.forEach { banName ->
                        FilterChip(
                            selected = true,
                            onClick = {
                                onUpdate(action.copy(bannedImages = action.bannedImages.filter { it != banName }))
                            },
                            label = {
                                Text(
                                    "⛔ ${banName.substringAfterLast('/').removeSuffix(".png")} ✕",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
            }

            if (showBannedPicker) {
                val candidates = images.filter { it.name != action.imageName }
                if (candidates.isEmpty()) {
                    Text(
                        "Tambahkan gambar lain ke pustaka terlebih dahulu agar bisa dipilih sebagai gambar terlarang.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(candidates, key = { "ban_${it.name}" }) { asset ->
                            val chosen = asset.name in action.bannedImages
                            val bmp = rememberAssetThumbnail(asset.path, asset.updatedAt)
                            Column(
                                modifier = Modifier
                                    .width(78.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(
                                        width = if (chosen) 2.dp else 1.dp,
                                        color = if (chosen) bannedAccent else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .background(
                                        if (chosen) bannedAccent.copy(alpha = 0.16f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    )
                                    .clickable {
                                        val next = if (chosen) {
                                            action.bannedImages.filter { it != asset.name }
                                        } else {
                                            (action.bannedImages + asset.name).distinct()
                                        }
                                        onUpdate(action.copy(bannedImages = next))
                                    }
                                    .padding(5.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF0F172A)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp,
                                            contentDescription = asset.displayName,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.size(50.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
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

            if (action.bannedImages.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BannedImageMode.entries.forEach { bm ->
                        FilterChip(
                            selected = action.bannedMode == bm,
                            onClick = { onUpdate(action.copy(bannedMode = bm)) },
                            label = { Text(bm.shortLabel, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Text(
                    action.bannedMode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Kemiripan Gambar Terlarang", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${(action.bannedThreshold * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = bannedAccent
                        )
                    }
                    Slider(
                        value = action.bannedThreshold,
                        onValueChange = { onUpdate(action.copy(bannedThreshold = it)) },
                        valueRange = 0.50f..0.99f
                    )
                }
                if (action.bannedMode == BannedImageMode.AVOID_MATCH_AREA) {
                    BranchNumberInput("Radius aman hindar (px)", action.bannedAvoidRadiusPx.toString()) {
                        onUpdate(action.copy(bannedAvoidRadiusPx = (it.toIntOrNull() ?: action.bannedAvoidRadiusPx).coerceIn(0, 500)))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GoToBranchActionEditor(
    action: GoToBranchAction,
    branches: List<MacroBranch>,
    onUpdate: (GoToBranchAction) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFF59E0B).copy(alpha = 0.09f),
        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f))
    ) {
        Text(
            "Pindahkan urutan macro langsung ke cabang tujuan — bisa pindah permanen atau singgah lalu otomatis kembali melanjutkan aksi berikutnya.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp)
        )
    }
    TargetBranchPickerRow(
        title = "Pilih Jalur Tujuan:",
        branches = branches,
        selectedBranchId = action.targetBranchId,
        onSelect = { id, name ->
            onUpdate(action.copy(targetBranchId = id, targetBranchName = name))
        }
    )
    Text("Mode Perpindahan Jalur", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BranchNavigationMode.entries.forEach { m ->
            FilterChip(
                selected = action.mode == m,
                onClick = { onUpdate(action.copy(mode = m)) },
                label = { Text(m.shortLabel, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
    Text(
        action.mode.description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    BranchNumberInput("Jeda sesudah aksi (ms)", action.delayAfterMs.toString()) {
        onUpdate(action.copy(delayAfterMs = (it.toLongOrNull() ?: action.delayAfterMs).coerceAtLeast(0L)))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BranchRouterActionEditor(
    action: BranchRouterAction,
    branches: List<MacroBranch>,
    images: List<ImageAsset>,
    lastPickedPixel: PickedPixel?,
    onUpdate: (BranchRouterAction) -> Unit
) {
    val routerAccent = Color(0xFF06B6D4)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = routerAccent.copy(alpha = 0.09f),
        border = BorderStroke(1.dp, routerAccent.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.AltRoute, contentDescription = null, tint = routerAccent, modifier = Modifier.size(20.dp))
            Text(
                "Mengecek semua kondisi di bawah sekaligus pada 1 tangkapan layar. Begitu ada yang cocok, macro langsung belok ke cabang tujuan. Jika hasil tidak sesuai semua kondisi, jalur cadangan (Fallback) dijalankan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BranchNumberInput("Batas waktu cek (ms)", action.timeoutMs.toString(), Modifier.weight(1f)) {
            onUpdate(action.copy(timeoutMs = (it.toLongOrNull() ?: action.timeoutMs).coerceAtLeast(0L)))
        }
        BranchNumberInput("Jeda sesudah (ms)", action.delayAfterMs.toString(), Modifier.weight(1f)) {
            onUpdate(action.copy(delayAfterMs = (it.toLongOrNull() ?: action.delayAfterMs).coerceAtLeast(0L)))
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "Daftar Kondisi & Arah (${action.rules.size})",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        FilledTonalButton(
            onClick = {
                val defaultBranch = branches.firstOrNull()
                val newRule = BranchRouteRule(
                    id = newId(),
                    label = "Kondisi ${action.rules.size + 1}",
                    conditionType = BranchConditionType.IMAGE,
                    imageName = images.firstOrNull()?.name.orEmpty(),
                    outcome = BranchOutcomeAction.SWITCH_BRANCH,
                    targetBranchId = defaultBranch?.id.orEmpty(),
                    targetBranchName = defaultBranch?.name ?: "Jalur Utama"
                )
                onUpdate(action.copy(rules = action.rules + newRule))
            },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Tambah Kondisi", style = MaterialTheme.typography.labelSmall)
        }
    }

    if (action.rules.isEmpty()) {
        Text(
            "Belum ada kondisi pengecekan. Ketuk '+ Tambah Kondisi' untuk mengecek gambar, teks/angka, atau warna piksel di layar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        action.rules.forEachIndexed { idx, rule ->
            BranchRouteRuleCard(
                index = idx,
                totalCount = action.rules.size,
                rule = rule,
                branches = branches,
                images = images,
                lastPickedPixel = lastPickedPixel,
                accent = routerAccent,
                onUpdateRule = { updatedRule ->
                    onUpdate(action.copy(rules = action.rules.mapIndexed { i, r -> if (i == idx) updatedRule else r }))
                },
                onMoveUp = {
                    val list = action.rules.toMutableList()
                    val tmp = list[idx - 1]
                    list[idx - 1] = list[idx]
                    list[idx] = tmp
                    onUpdate(action.copy(rules = list))
                },
                onMoveDown = {
                    val list = action.rules.toMutableList()
                    val tmp = list[idx + 1]
                    list[idx + 1] = list[idx]
                    list[idx] = tmp
                    onUpdate(action.copy(rules = list))
                },
                onDelete = {
                    onUpdate(action.copy(rules = action.rules.filterIndexed { i, _ -> i != idx }))
                }
            )
        }
    }

    // Fallback card: when result does NOT match any rule
    HorizontalDivider()
    val fallbackAccent = Color(0xFFF59E0B)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = fallbackAccent.copy(alpha = 0.09f),
        border = BorderStroke(1.dp, fallbackAccent.copy(alpha = 0.42f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Jika Hasil Tidak Sesuai Semua Kondisi (Fallback)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = fallbackAccent
            )
            Text(
                "Dijalankan otomatis ketika batas waktu habis dan tidak ada satupun kondisi di atas yang cocok di layar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BranchOutcomeAction.entries.forEach { out ->
                    FilterChip(
                        selected = action.fallbackOutcome == out,
                        onClick = { onUpdate(action.copy(fallbackOutcome = out)) },
                        label = { Text(out.shortLabel, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            if (action.fallbackOutcome == BranchOutcomeAction.SWITCH_BRANCH || action.fallbackOutcome == BranchOutcomeAction.CALL_BRANCH_RETURN) {
                TargetBranchPickerRow(
                    title = "Cabang Tujuan Cadangan (Fallback):",
                    branches = branches,
                    selectedBranchId = action.fallbackBranchId,
                    onSelect = { bid, bname ->
                        onUpdate(action.copy(fallbackBranchId = bid, fallbackBranchName = bname))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BranchRouteRuleCard(
    index: Int,
    totalCount: Int,
    rule: BranchRouteRule,
    branches: List<MacroBranch>,
    images: List<ImageAsset>,
    lastPickedPixel: PickedPixel?,
    accent: Color,
    onUpdateRule: (BranchRouteRule) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Kondisi #${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (index > 0) {
                        IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "Naik", modifier = Modifier.size(16.dp))
                        }
                    }
                    if (index < totalCount - 1) {
                        IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = "Turun", modifier = Modifier.size(16.dp))
                        }
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = "Hapus Kondisi",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            OutlinedTextField(
                value = rule.label,
                onValueChange = { v -> onUpdateRule(rule.copy(label = v.take(36))) },
                label = { Text("Label Kondisi (opsional)") },
                placeholder = { Text("mis. Jika Menang / Ada Pop-up") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BranchConditionType.entries.forEach { ct ->
                    FilterChip(
                        selected = rule.conditionType == ct,
                        onClick = { onUpdateRule(rule.copy(conditionType = ct)) },
                        label = { Text(ct.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            when (rule.conditionType) {
                BranchConditionType.IMAGE -> {
                    if (images.isEmpty()) {
                        Text(
                            "Belum ada gambar di pustaka. Potong gambar terlebih dahulu.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(images, key = { "br_${rule.id}_${it.name}" }) { asset ->
                                val sel = asset.name == rule.imageName
                                val bmp = rememberAssetThumbnail(asset.path, asset.updatedAt)
                                Column(
                                    modifier = Modifier
                                        .width(74.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(
                                            width = if (sel) 2.dp else 1.dp,
                                            color = if (sel) accent else MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .background(
                                            if (sel) accent.copy(alpha = 0.16f)
                                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                                        )
                                        .clickable { onUpdateRule(rule.copy(imageName = asset.name)) }
                                        .padding(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFF0F172A)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp,
                                                contentDescription = asset.displayName,
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier.size(44.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
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
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Kemiripan Gambar", style = MaterialTheme.typography.bodySmall)
                            Text("${(rule.threshold * 100).roundToInt()}%", fontWeight = FontWeight.Bold, color = accent)
                        }
                        Slider(
                            value = rule.threshold,
                            onValueChange = { v -> onUpdateRule(rule.copy(threshold = v)) },
                            valueRange = 0.50f..0.99f
                        )
                    }
                }

                BranchConditionType.TEXT -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextCompareMode.entries.forEach { tm ->
                            FilterChip(
                                selected = rule.compareMode == tm,
                                onClick = { onUpdateRule(rule.copy(compareMode = tm)) },
                                label = { Text(tm.shortLabel, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    if (rule.compareMode.isNumeric) {
                        var numStr by remember(rule.id, rule.targetNumber) {
                            mutableStateOf(
                                if (rule.targetNumber == rule.targetNumber.toLong().toDouble()) {
                                    rule.targetNumber.toLong().toString()
                                } else rule.targetNumber.toString()
                            )
                        }
                        OutlinedTextField(
                            value = numStr,
                            onValueChange = { raw ->
                                numStr = raw
                                raw.replace(',', '.').toDoubleOrNull()?.let { num ->
                                    onUpdateRule(rule.copy(targetNumber = num))
                                }
                            },
                            label = { Text("Angka Target (${rule.compareMode.label})") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = rule.textQuery,
                            onValueChange = { q -> onUpdateRule(rule.copy(textQuery = q)) },
                            label = { Text("Teks Target (${rule.compareMode.label})") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                BranchConditionType.COLOR -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BranchNumberInput("Koordinat X", rule.x.toString(), Modifier.weight(1f)) {
                            onUpdateRule(rule.copy(x = it.toIntOrNull() ?: rule.x))
                        }
                        BranchNumberInput("Koordinat Y", rule.y.toString(), Modifier.weight(1f)) {
                            onUpdateRule(rule.copy(y = it.toIntOrNull() ?: rule.y))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = rule.color,
                            onValueChange = { hex -> onUpdateRule(rule.copy(color = hex.take(7))) },
                            label = { Text("Warna #RRGGBB") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        BranchNumberInput("Toleransi", rule.tolerance.toString(), Modifier.weight(0.75f)) {
                            onUpdateRule(rule.copy(tolerance = (it.toIntOrNull() ?: rule.tolerance).coerceIn(0, 255)))
                        }
                    }
                    if (lastPickedPixel != null) {
                        TextButton(
                            onClick = {
                                onUpdateRule(
                                    rule.copy(
                                        x = lastPickedPixel.x,
                                        y = lastPickedPixel.y,
                                        color = lastPickedPixel.hexColor
                                    )
                                )
                            }
                        ) {
                            Text(
                                "Pakai Piksel Color Spy (${lastPickedPixel.x}, ${lastPickedPixel.y}) ${lastPickedPixel.hexColor}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Klik target otomatis saat cocok", style = MaterialTheme.typography.bodySmall)
                Switch(
                    checked = rule.clickOnFound,
                    onCheckedChange = { v -> onUpdateRule(rule.copy(clickOnFound = v)) }
                )
            }

            Text("Jika cocok, arahkan ke:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BranchOutcomeAction.entries.forEach { out ->
                    FilterChip(
                        selected = rule.outcome == out,
                        onClick = { onUpdateRule(rule.copy(outcome = out)) },
                        label = { Text(out.shortLabel, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            if (rule.outcome == BranchOutcomeAction.SWITCH_BRANCH || rule.outcome == BranchOutcomeAction.CALL_BRANCH_RETURN) {
                TargetBranchPickerRow(
                    title = "Cabang Tujuan Kondisi #${index + 1}:",
                    branches = branches,
                    selectedBranchId = rule.targetBranchId,
                    onSelect = { bid, bname ->
                        onUpdateRule(rule.copy(targetBranchId = bid, targetBranchName = bname))
                    }
                )
            }
        }
    }
}

@Composable
private fun BranchNumberInput(
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
