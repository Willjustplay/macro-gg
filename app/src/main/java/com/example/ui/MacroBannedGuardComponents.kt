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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.example.data.Action
import com.example.data.BannedImageMode
import com.example.data.ImageAsset
import com.example.data.Macro
import com.example.data.MacroBranch
import com.example.data.branchDisplayName
import com.example.data.branchIdOfAction
import com.example.data.kind
import com.example.data.newId
import kotlin.math.roundToInt

private val GUARD_RED = Color(0xFFEF4444)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MacroBannedGuardCard(
    macro: Macro,
    images: List<ImageAsset>,
    imageFolders: List<String>,
    onUpdateMacro: (Macro) -> Unit,
    onCropNewBannedImage: () -> Unit
) {
    var radiusText by remember(macro.id, macro.bannedAvoidRadiusPx) {
        mutableStateOf(macro.bannedAvoidRadiusPx.toString())
    }
    var folderFilter by remember(macro.id) { mutableStateOf<String?>(null) }
    val filteredImages = remember(images, folderFilter) {
        when (folderFilter) {
            null -> images
            "" -> images.filter { it.folder.isBlank() }
            else -> images.filter { it.folder == folderFilter }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Watchdog-style Always-Active Guard Config Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = GUARD_RED.copy(alpha = 0.09f),
            border = BorderStroke(1.dp, GUARD_RED.copy(alpha = 0.45f))
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
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Block,
                            contentDescription = null,
                            tint = GUARD_RED,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                "Guard Banned Gambar (Selalu Aktif)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = GUARD_RED
                            )
                            Text(
                                "Bekerja otomatis seperti Watchdog di setiap aksi klik (Klik, Tahan, Gambar, Teks, Warna, Rekaman). Gambar yang dipilih di bawah otomatis terblokir untuk diklik.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = macro.bannedGuardEnabled,
                        onCheckedChange = { on ->
                            onUpdateMacro(macro.copy(bannedGuardEnabled = on))
                        }
                    )
                }

                // Mode selection
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BannedImageMode.entries.forEach { bm ->
                        FilterChip(
                            selected = macro.bannedMode == bm,
                            onClick = { onUpdateMacro(macro.copy(bannedMode = bm)) },
                            label = { Text(bm.shortLabel, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Text(
                    macro.bannedMode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Similarity threshold slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Kemiripan Deteksi Banned", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${(macro.bannedThreshold * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = GUARD_RED
                        )
                    }
                    Slider(
                        value = macro.bannedThreshold.coerceIn(0.50f, 0.99f),
                        onValueChange = { onUpdateMacro(macro.copy(bannedThreshold = it)) },
                        valueRange = 0.50f..0.99f
                    )
                }

                // Safe radius when avoiding banned areas
                if (macro.bannedMode == BannedImageMode.AVOID_MATCH_AREA) {
                    OutlinedTextField(
                        value = radiusText,
                        onValueChange = { raw ->
                            val digits = raw.filter(Char::isDigit).take(3)
                            radiusText = digits
                            val parsed = digits.toIntOrNull()
                            if (parsed != null) {
                                onUpdateMacro(macro.copy(bannedAvoidRadiusPx = parsed.coerceIn(0, 500)))
                            }
                        },
                        label = { Text("Radius blokir aman di sekitar gambar (px)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(30, 60, 100, 150).forEach { presetPx ->
                            FilterChip(
                                selected = macro.bannedAvoidRadiusPx == presetPx,
                                onClick = {
                                    radiusText = presetPx.toString()
                                    onUpdateMacro(macro.copy(bannedAvoidRadiusPx = presetPx))
                                },
                                label = { Text("${presetPx}px", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                Button(
                    onClick = onCropNewBannedImage,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GUARD_RED)
                ) {
                    Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Crop Gambar Terlarang dari Layar", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Active Banned Images chips
        if (macro.bannedImages.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, GUARD_RED.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Gambar Aktif Diblokir (${macro.bannedImages.size})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = GUARD_RED
                        )
                        TextButton(
                            onClick = { onUpdateMacro(macro.copy(bannedImages = emptyList())) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Kosongkan", style = MaterialTheme.typography.labelSmall, color = GUARD_RED)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        macro.bannedImages.forEach { banName ->
                            FilterChip(
                                selected = true,
                                onClick = {
                                    onUpdateMacro(macro.copy(bannedImages = macro.bannedImages.filterNot { it == banName }))
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
            }
        }

        // Visual library toggle grid (tap any image to ban/unban)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Pilih Gambar dari Pustaka (Ketuk untuk Blokir / Lepas)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )

            if (imageFolders.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item(key = "ban_f_all") {
                        FilterChip(
                            selected = folderFilter == null,
                            onClick = { folderFilter = null },
                            label = { Text("Semua (${images.size})", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    item(key = "ban_f_root") {
                        FilterChip(
                            selected = folderFilter == "",
                            onClick = { folderFilter = "" },
                            label = { Text("Utama", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    items(imageFolders, key = { "ban_f_$it" }) { f ->
                        FilterChip(
                            selected = folderFilter == f,
                            onClick = { folderFilter = f },
                            label = { Text("📁 $f", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            if (filteredImages.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Text(
                        "Belum ada gambar di pustaka. Ketuk 'Crop Gambar Terlarang dari Layar' untuk memotong tombol / ikon / iklan yang tidak boleh diklik.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filteredImages.forEach { asset ->
                        val isBanned = asset.name in macro.bannedImages
                        val bmp = rememberAssetThumbnail(asset.path, asset.updatedAt)
                        Column(
                            modifier = Modifier
                                .width(98.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    width = if (isBanned) 2.dp else 1.dp,
                                    color = if (isBanned) GUARD_RED else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .background(
                                    if (isBanned) GUARD_RED.copy(alpha = 0.16f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
                                )
                                .clickable {
                                    val nextList = if (isBanned) {
                                        macro.bannedImages.filterNot { it == asset.name }
                                    } else {
                                        (macro.bannedImages + asset.name).distinct()
                                    }
                                    onUpdateMacro(
                                        macro.copy(
                                            bannedGuardEnabled = if (!isBanned) true else macro.bannedGuardEnabled,
                                            bannedImages = nextList
                                        )
                                    )
                                }
                                .padding(7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(66.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(Color(0xFF0A1020)),
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
                                if (isBanned) {
                                    Icon(
                                        Icons.Filled.Block,
                                        contentDescription = "Diblokir",
                                        tint = GUARD_RED,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(3.dp)
                                            .size(16.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                asset.displayName.removeSuffix(".png"),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isBanned) FontWeight.Bold else FontWeight.Medium,
                                color = if (isBanned) GUARD_RED else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

private val MOVE_BRANCH_PRESET_COLORS = listOf(
    "#F59E0B", "#06B6D4", "#10B981", "#EC4899", "#8B5CF6", "#EF4444"
)

@Composable
internal fun MoveActionToBranchDialog(
    action: Action,
    macro: Macro,
    onMoveToBranch: (targetBranchId: String) -> Unit,
    onCreateBranchAndMove: (branchName: String, colorHex: String) -> Unit,
    onDismiss: () -> Unit
) {
    val currentBranchId = remember(macro, action.id) { macro.branchIdOfAction(action.id).orEmpty() }
    var newBranchName by remember(macro.branches.size) { mutableStateOf("Cabang ${macro.branches.size + 1}") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.AutoMirrored.Filled.AltRoute,
                contentDescription = null,
                tint = Color(0xFFF59E0B)
            )
        },
        title = {
            Text(
                "Pindah Aksi ke Cabang",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Aksi: ${action.kind.label}\nSaat ini di: ${macro.branchDisplayName(currentBranchId)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Jalur Utama option
                MoveBranchTargetItem(
                    label = "Jalur Utama",
                    actionCount = macro.actions.size,
                    dotColor = Color(0xFF10B981),
                    isCurrent = currentBranchId.isBlank(),
                    onClick = { onMoveToBranch("") }
                )

                // Existing named branches
                macro.branches.forEach { branch ->
                    MoveBranchTargetItem(
                        label = branch.name,
                        actionCount = branch.actions.size,
                        dotColor = parseHexColorOrNull(branch.colorHex) ?: Color(0xFFF59E0B),
                        isCurrent = currentBranchId == branch.id,
                        onClick = { onMoveToBranch(branch.id) }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    "Atau buat cabang baru & langsung pindahkan:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedTextField(
                    value = newBranchName,
                    onValueChange = { newBranchName = it.take(36) },
                    label = { Text("Nama Cabang Baru") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                FilledTonalButton(
                    onClick = {
                        val clean = newBranchName.trim().ifBlank { "Cabang ${macro.branches.size + 1}" }
                        val colorHex = MOVE_BRANCH_PRESET_COLORS[macro.branches.size % MOVE_BRANCH_PRESET_COLORS.size]
                        onCreateBranchAndMove(clean, colorHex)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Buat Cabang Baru & Pindahkan")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        }
    )
}

@Composable
private fun MoveBranchTargetItem(
    label: String,
    actionCount: Int,
    dotColor: Color,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .clickable(enabled = !isCurrent, onClick = onClick),
        shape = RoundedCornerShape(11.dp),
        color = if (isCurrent) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f) else dotColor.copy(alpha = 0.12f),
        border = BorderStroke(
            width = 1.dp,
            color = if (isCurrent) MaterialTheme.colorScheme.outlineVariant else dotColor.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "$actionCount aksi" + if (isCurrent) " • Posisi saat ini" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isCurrent) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Saat ini",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
