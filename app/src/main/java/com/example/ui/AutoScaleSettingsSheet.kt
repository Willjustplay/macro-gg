package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.Macro
import com.example.data.ResolutionScaleMode
import com.example.data.ResolutionScaler

/**
 * Dedicated passwordless Auto-Scale Resolusi Layar bottom sheet.
 * Accessible from the macro list even when [Macro.isLocked] is true so users on ANY device
 * (Phones 16:9..21:9, Tablets 16:10/4:3, Foldables, Emulators, CloudPhones) can adapt scaling
 * freely without unlocking the macro's actions.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AutoScaleSettingsSheet(
    macro: Macro,
    screenW: Int,
    screenH: Int,
    onSave: (autoScale: Boolean, sourceWidth: Int, sourceHeight: Int, scaleMode: ResolutionScaleMode) -> Unit,
    onDismiss: () -> Unit
) {
    var autoScale by remember(macro.id) { mutableStateOf(macro.autoScaleResolution) }
    var sourceWText by remember(macro.id) {
        mutableStateOf(if (macro.sourceWidth > 0) macro.sourceWidth.toString() else "")
    }
    var sourceHText by remember(macro.id) {
        mutableStateOf(if (macro.sourceHeight > 0) macro.sourceHeight.toString() else "")
    }
    var scaleMode by remember(macro.id) { mutableStateOf(macro.scaleMode) }

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
            Text(
                "Auto-Scale Resolusi Layar",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (macro.isLocked) {
                    "Macro '${macro.name}' terkunci — Auto-Scale tetap bebas diatur tanpa sandi untuk menyesuaikan semua jenis layar perangkat."
                } else {
                    "Menyesuaikan klik, swipe, zoom, rekaman, area & deteksi gambar secara otomatis ke semua jenis layar (HP 16:9–21:9, Tablet 16:10/4:3, Foldable, Emulator & CloudPhone)."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()

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

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Batal") }
                Button(
                    onClick = {
                        val savedW = (sourceWText.toIntOrNull() ?: 0).coerceIn(0, 10000)
                        val savedH = (sourceHText.toIntOrNull() ?: 0).coerceIn(0, 10000)
                        onSave(autoScale, savedW, savedH, scaleMode)
                    },
                    modifier = Modifier.weight(1.4f)
                ) { Text("Simpan Auto-Scale") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AutoScaleControlsBlock(
    macro: Macro,
    screenW: Int,
    screenH: Int,
    autoScale: Boolean,
    onAutoScaleChange: (Boolean) -> Unit,
    sourceWText: String,
    onSourceWChange: (String) -> Unit,
    sourceHText: String,
    onSourceHChange: (String) -> Unit,
    scaleMode: ResolutionScaleMode,
    onScaleModeChange: (ResolutionScaleMode) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
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
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                        "Aktifkan Auto-Scale Semua Perangkat",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Mendukung HP (16:9–21:9), Tablet (16:10 / 4:3), Foldable, Emulator & CloudPhone. Bebas diubah meski macro dikunci.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = autoScale, onCheckedChange = onAutoScaleChange)
            }

            if (autoScale) {
                val parsedSrcW = sourceWText.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val parsedSrcH = sourceHText.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val previewTransform = ResolutionScaler.forMacro(
                    macro.copy(
                        sourceWidth = parsedSrcW,
                        sourceHeight = parsedSrcH,
                        autoScaleResolution = autoScale,
                        scaleMode = scaleMode
                    ),
                    screenW,
                    screenH
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = sourceWText,
                        onValueChange = { onSourceWChange(it.filter(Char::isDigit).take(4)) },
                        label = { Text("Lebar asal (px)") },
                        placeholder = { Text("$screenW") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = sourceHText,
                        onValueChange = { onSourceHChange(it.filter(Char::isDigit).take(4)) },
                        label = { Text("Tinggi asal (px)") },
                        placeholder = { Text("$screenH") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val wVal = sourceWText.ifBlank { screenW.toString() }
                            val hVal = sourceHText.ifBlank { screenH.toString() }
                            onSourceWChange(hVal)
                            onSourceHChange(wVal)
                        }
                    ) {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = "Tukar Portrait/Landscape")
                    }
                }

                Text(
                    "Preset Resolusi Asal (HP, Tablet, Foldable, Emulator):",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = parsedSrcW == screenW && parsedSrcH == screenH,
                        onClick = {
                            onSourceWChange(screenW.toString())
                            onSourceHChange(screenH.toString())
                        },
                        label = { Text("Layar Ini (${screenW}×${screenH})", style = MaterialTheme.typography.labelSmall) }
                    )
                    ResolutionScaler.DEVICE_PRESETS.forEach { preset ->
                        val pw = preset.width
                        val ph = preset.height
                        if (pw != screenW || ph != screenH) {
                            FilterChip(
                                selected = (parsedSrcW == pw && parsedSrcH == ph) || (parsedSrcW == ph && parsedSrcH == pw),
                                onClick = {
                                    onSourceWChange(pw.toString())
                                    onSourceHChange(ph.toString())
                                },
                                label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                Text(
                    "Mode Proyeksi Layar:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ResolutionScaleMode.entries.forEach { m ->
                        FilterChip(
                            selected = scaleMode == m,
                            onClick = { onScaleModeChange(m) },
                            label = { Text(m.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Text(
                    text = when {
                        parsedSrcW <= 0 || parsedSrcH <= 0 ->
                            "Resolusi asal belum disetel (deteksi gambar tetap multi-scale otomatis; isi resolusi asal agar klik & swipe ikut diskalakan). Layar ini: ${screenW}×${screenH}."
                        previewTransform.isScaling ->
                            "Skala aktif: ${previewTransform.describe()} • ${scaleMode.description}"
                        else ->
                            "Resolusi asal cocok dengan layar ini (${screenW}×${screenH}) — berjalan 1:1. ${scaleMode.description}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (previewTransform.isScaling) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
