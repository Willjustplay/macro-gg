package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.CheckVariableAction
import com.example.data.HumanizerConfig
import com.example.data.HumanizerPreset
import com.example.data.MacroBranch
import com.example.data.NotFoundMode
import com.example.data.VariableAction
import com.example.data.VariableCompareOp
import com.example.data.VariableOperation
import com.example.data.VariableScope
import kotlin.math.roundToInt

private fun formatCleanDouble(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VariableActionEditor(
    action: VariableAction,
    onPickRegionOnScreen: () -> Unit,
    onUpdate: (VariableAction) -> Unit
) {
    val accent = Color(0xFFA855F7)
    var valueText by remember(action.id, action.value) { mutableStateOf(formatCleanDouble(action.value)) }
    var fallbackText by remember(action.id, action.fallbackValue) { mutableStateOf(formatCleanDouble(action.fallbackValue)) }
    var timeoutText by remember(action.id, action.ocrTimeoutMs) { mutableStateOf(action.ocrTimeoutMs.toString()) }
    var delayText by remember(action.id, action.delayAfterMs) { mutableStateOf(action.delayAfterMs.toString()) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Variabel & Penghitung Visual (Tanpa Kode)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Text(
                "Simpan angka hitungan, jumlah serangan, atau tangkap angka Gold/Energi langsung dari layar (OCR) untuk dipakai kondisi percabangan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Text("Operasi Variabel", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        VariableOperation.entries.forEach { op ->
            FilterChip(
                selected = action.operation == op,
                onClick = { onUpdate(action.copy(operation = op)) },
                label = { Text(op.label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }

    Text("Cakupan Simpanan", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VariableScope.entries.forEach { sc ->
            FilterChip(
                selected = action.scope == sc,
                onClick = { onUpdate(action.copy(scope = sc)) },
                label = { Text(sc.label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }

    if (action.operation != VariableOperation.RESET_ALL) {
        OutlinedTextField(
            value = action.variableName,
            onValueChange = { raw -> onUpdate(action.copy(variableName = raw.take(32))) },
            label = { Text("Nama Variabel") },
            placeholder = { Text("mis. hitung, serangan, gold, energi") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("hitung", "serangan", "gold", "energi", "gagal").forEach { preset ->
                FilterChip(
                    selected = action.variableName.equals(preset, ignoreCase = true),
                    onClick = { onUpdate(action.copy(variableName = preset)) },
                    label = { Text(preset, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }

    when (action.operation) {
        VariableOperation.SET,
        VariableOperation.ADD,
        VariableOperation.SUBTRACT,
        VariableOperation.MULTIPLY -> {
            OutlinedTextField(
                value = valueText,
                onValueChange = { raw ->
                    valueText = raw
                    raw.replace(',', '.').toDoubleOrNull()?.let { v ->
                        onUpdate(action.copy(value = v))
                    }
                },
                label = {
                    Text(
                        when (action.operation) {
                            VariableOperation.SET -> "Nilai Baru"
                            VariableOperation.ADD -> "Tambah Sebesar (+)"
                            VariableOperation.SUBTRACT -> "Kurangi Sebesar (-)"
                            VariableOperation.MULTIPLY -> "Kalikan Dengan (×)"
                            else -> "Nilai"
                        }
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.0, 1.0, 5.0, 10.0, 100.0).forEach { qv ->
                    FilterChip(
                        selected = action.value == qv,
                        onClick = {
                            valueText = formatCleanDouble(qv)
                            onUpdate(action.copy(value = qv))
                        },
                        label = { Text(formatCleanDouble(qv), style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }

        VariableOperation.READ_OCR_NUMBER -> {
            val r = action.region
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        if (r != null) "Area OCR: (${r.left},${r.top}) → (${r.right},${r.bottom})"
                        else "Area OCR: Layar Penuh (disarankan pilih kotak angka agar akurat)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r != null) Color(0xFF22D3EE) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = onPickRegionOnScreen,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.CropFree, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (r != null) "Ubah Area" else "Pilih Area di Layar", style = MaterialTheme.typography.labelMedium)
                        }
                        if (r != null) {
                            OutlinedButton(
                                onClick = { onUpdate(action.copy(region = null)) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("Layar Penuh", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = timeoutText,
                        onValueChange = { raw ->
                            timeoutText = raw.filter(Char::isDigit)
                            timeoutText.toLongOrNull()?.let { ms ->
                                onUpdate(action.copy(ocrTimeoutMs = ms.coerceIn(0L, 30_000L)))
                            }
                        },
                        label = { Text("Batas waktu baca OCR (ms)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Pakai nilai cadangan jika OCR kosong", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text("Jika OFF, nilai variabel sebelumnya dipertahankan.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = action.useFallbackOnOcrFail,
                            onCheckedChange = { v -> onUpdate(action.copy(useFallbackOnOcrFail = v)) }
                        )
                    }
                    if (action.useFallbackOnOcrFail) {
                        OutlinedTextField(
                            value = fallbackText,
                            onValueChange = { raw ->
                                fallbackText = raw
                                raw.replace(',', '.').toDoubleOrNull()?.let { fv ->
                                    onUpdate(action.copy(fallbackValue = fv))
                                }
                            },
                            label = { Text("Nilai Cadangan") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        VariableOperation.RESET_ALL -> {
            Text(
                "Semua variabel dalam cakupan '${action.scope.label}' akan dikosongkan kembali ke 0.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    OutlinedTextField(
        value = delayText,
        onValueChange = { raw ->
            delayText = raw.filter(Char::isDigit)
            delayText.toLongOrNull()?.let { ms ->
                onUpdate(action.copy(delayAfterMs = ms.coerceAtLeast(0L)))
            }
        },
        label = { Text("Jeda setelah aksi (ms)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CheckVariableActionEditor(
    action: CheckVariableAction,
    branches: List<MacroBranch>,
    onUpdate: (CheckVariableAction) -> Unit
) {
    val accent = Color(0xFFEC4899)
    var compareToOtherVar by remember(action.id) { mutableStateOf(action.compareToVariable.isNotBlank()) }
    var targetText by remember(action.id, action.targetValue) { mutableStateOf(formatCleanDouble(action.targetValue)) }
    var delayText by remember(action.id, action.delayAfterMs) { mutableStateOf(action.delayAfterMs.toString()) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Cek Kondisi Variabel (If / Else Visual)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Text(
                "Periksa nilai variabel (mis. serangan ≥ 5 atau gold < batas) lalu jalankan cabang Benar / Salah atau pindah jalur cabang macro.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    OutlinedTextField(
        value = action.variableName,
        onValueChange = { v -> onUpdate(action.copy(variableName = v.take(32))) },
        label = { Text("Nama Variabel yang Dicek") },
        placeholder = { Text("mis. hitung, serangan, gold") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Text("Cakupan Variabel", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VariableScope.entries.forEach { sc ->
            FilterChip(
                selected = action.scope == sc,
                onClick = { onUpdate(action.copy(scope = sc)) },
                label = { Text(sc.label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }

    Text("Operator Perbandingan", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        VariableCompareOp.entries.forEach { op ->
            FilterChip(
                selected = action.operator == op,
                onClick = { onUpdate(action.copy(operator = op)) },
                label = { Text("${op.symbol} (${op.label})", style = MaterialTheme.typography.labelSmall) }
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Bandingkan dengan variabel lain", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        Switch(
            checked = compareToOtherVar,
            onCheckedChange = { checked ->
                compareToOtherVar = checked
                if (!checked) {
                    onUpdate(action.copy(compareToVariable = ""))
                }
            }
        )
    }

    if (compareToOtherVar) {
        OutlinedTextField(
            value = action.compareToVariable,
            onValueChange = { v -> onUpdate(action.copy(compareToVariable = v.take(32))) },
            label = { Text("Nama Variabel Pembanding") },
            placeholder = { Text("mis. target_gold, maks_serangan") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        OutlinedTextField(
            value = targetText,
            onValueChange = { raw ->
                targetText = raw
                raw.replace(',', '.').toDoubleOrNull()?.let { v ->
                    onUpdate(action.copy(targetValue = v))
                }
            },
            label = { Text("Angka Target (${action.operator.symbol})") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
    }

    Text("Jika BENAR (Setelah aksi cabang Benar):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFF10B981))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NotFoundMode.entries.forEach { m ->
            FilterChip(
                selected = action.foundMode == m,
                onClick = { onUpdate(action.copy(foundMode = m)) },
                label = { Text(m.label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
    if (action.foundMode == NotFoundMode.SWITCH_BRANCH || action.foundMode == NotFoundMode.CALL_BRANCH_RETURN) {
        TargetBranchPickerRow(
            title = "Cabang Tujuan saat BENAR:",
            branches = branches,
            selectedBranchId = action.foundBranchId,
            onSelect = { bid, _ -> onUpdate(action.copy(foundBranchId = bid)) }
        )
    }

    Text("Jika SALAH (Setelah aksi cabang Salah):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFFF59E0B))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NotFoundMode.entries.forEach { m ->
            FilterChip(
                selected = action.notFound == m,
                onClick = { onUpdate(action.copy(notFound = m)) },
                label = { Text(m.label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
    if (action.notFound == NotFoundMode.SWITCH_BRANCH || action.notFound == NotFoundMode.CALL_BRANCH_RETURN) {
        TargetBranchPickerRow(
            title = "Cabang Tujuan saat SALAH:",
            branches = branches,
            selectedBranchId = action.notFoundBranchId,
            onSelect = { bid, _ -> onUpdate(action.copy(notFoundBranchId = bid)) }
        )
    }

    Text(
        "Tip: Tambahkan aksi ke cabang Benar / Salah langsung melalui tombol '+ Tambah' pada kartu Cek Variabel di editor.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    OutlinedTextField(
        value = delayText,
        onValueChange = { raw ->
            delayText = raw.filter(Char::isDigit)
            delayText.toLongOrNull()?.let { ms ->
                onUpdate(action.copy(delayAfterMs = ms.coerceAtLeast(0L)))
            }
        },
        label = { Text("Jeda setelah aksi (ms)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Reusable Humanizer Engine settings card embedded in Macro Settings (Compose).
 * Optional [isPremium] / [onRequirePremium] hooks allow seamless gating on Play Store builds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HumanizerSettingsSection(
    config: HumanizerConfig,
    onUpdate: (HumanizerConfig) -> Unit,
    isPremium: Boolean = true,
    onRequirePremium: (String) -> Unit = {}
) {
    val accent = when (config.preset) {
        HumanizerPreset.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
        HumanizerPreset.NATURAL -> Color(0xFF10B981)
        HumanizerPreset.STEALTH -> Color(0xFF06B6D4)
        HumanizerPreset.CUSTOM -> Color(0xFFA855F7)
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(
            1.dp,
            if (config.preset == HumanizerPreset.OFF) MaterialTheme.colorScheme.outlineVariant
            else accent.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Humanizer Engine (Anti-Deteksi Alami)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                    Text(
                        config.preset.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HumanizerPreset.entries.forEach { preset ->
                    val needsPro = !isPremium && (preset == HumanizerPreset.STEALTH || preset == HumanizerPreset.CUSTOM)
                    FilterChip(
                        selected = config.preset == preset,
                        onClick = {
                            if (needsPro) {
                                onRequirePremium("Humanizer ${preset.label}")
                            } else {
                                val next = when (preset) {
                                    HumanizerPreset.OFF -> config.copy(preset = HumanizerPreset.OFF)
                                    HumanizerPreset.NATURAL -> config.copy(preset = HumanizerPreset.NATURAL)
                                    HumanizerPreset.STEALTH -> config.copy(
                                        preset = HumanizerPreset.STEALTH,
                                        humanBreakEnabled = true
                                    )
                                    HumanizerPreset.CUSTOM -> {
                                        val seed = if (config.preset == HumanizerPreset.OFF) {
                                            HumanizerConfig(preset = HumanizerPreset.NATURAL).effective()
                                        } else {
                                            config.effective()
                                        }
                                        seed.copy(preset = HumanizerPreset.CUSTOM)
                                    }
                                }
                                onUpdate(next)
                            }
                        },
                        leadingIcon = if (needsPro) {
                            { Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(14.dp)) }
                        } else null,
                        label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            if (config.preset == HumanizerPreset.NATURAL || config.preset == HumanizerPreset.STEALTH) {
                val eff = config.effective()
                Text(
                    "Aktif: Klik Gaussian ±${eff.gaussianClickRadiusPx}px • Tap ${eff.tapDurationMinMs}–${eff.tapDurationMaxMs}ms • Swipe Bezier ${(eff.bezierCurveFactor * 100).roundToInt()}% • Jeda ±${eff.delayVariancePercent}% • Ragu ${eff.microHesitationChancePercent}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(accent.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }

            if (config.preset == HumanizerPreset.CUSTOM) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Sebaran Klik Gaussian", style = MaterialTheme.typography.bodySmall)
                        Text("±${config.gaussianClickRadiusPx} px", fontWeight = FontWeight.Bold, color = accent, style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = config.gaussianClickRadiusPx.toFloat(),
                        onValueChange = { onUpdate(config.copy(gaussianClickRadiusPx = it.roundToInt().coerceIn(0, 40))) },
                        valueRange = 0f..30f
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = config.tapDurationMinMs.toString(),
                            onValueChange = { raw ->
                                raw.filter(Char::isDigit).toLongOrNull()?.let { v ->
                                    val minV = v.coerceIn(20L, 250L)
                                    onUpdate(config.copy(tapDurationMinMs = minV, tapDurationMaxMs = maxOf(config.tapDurationMaxMs, minV)))
                                }
                            },
                            label = { Text("Tap Min (ms)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = config.tapDurationMaxMs.toString(),
                            onValueChange = { raw ->
                                raw.filter(Char::isDigit).toLongOrNull()?.let { v ->
                                    onUpdate(config.copy(tapDurationMaxMs = v.coerceIn(config.tapDurationMinMs, 350L)))
                                }
                            },
                            label = { Text("Tap Maks (ms)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Swipe Kurva Bezier (Lengkung Alami)", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = config.bezierSwipeEnabled,
                            onCheckedChange = { onUpdate(config.copy(bezierSwipeEnabled = it)) }
                        )
                    }

                    if (config.bezierSwipeEnabled) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Kelengkungan Kurva Swipe", style = MaterialTheme.typography.bodySmall)
                            Text("${(config.bezierCurveFactor * 100).roundToInt()}%", fontWeight = FontWeight.Bold, color = accent, style = MaterialTheme.typography.bodySmall)
                        }
                        Slider(
                            value = config.bezierCurveFactor.coerceIn(0.04f, 0.30f),
                            onValueChange = { onUpdate(config.copy(bezierCurveFactor = it)) },
                            valueRange = 0.04f..0.30f
                        )
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Variasi Jeda Organik", style = MaterialTheme.typography.bodySmall)
                        Text("±${config.delayVariancePercent}%", fontWeight = FontWeight.Bold, color = accent, style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = config.delayVariancePercent.toFloat(),
                        onValueChange = { onUpdate(config.copy(delayVariancePercent = it.roundToInt().coerceIn(0, 45))) },
                        valueRange = 0f..45f
                    )

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Peluang Ragu Sesaat (Micro-Hesitation)", style = MaterialTheme.typography.bodySmall)
                        Text("${config.microHesitationChancePercent}%", fontWeight = FontWeight.Bold, color = accent, style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = config.microHesitationChancePercent.toFloat(),
                        onValueChange = { onUpdate(config.copy(microHesitationChancePercent = it.roundToInt().coerceIn(0, 30))) },
                        valueRange = 0f..30f
                    )
                }
            }

            // Human Break controls (active in STEALTH, configurable in STEALTH & CUSTOM)
            if (config.preset == HumanizerPreset.STEALTH || config.preset == HumanizerPreset.CUSTOM) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("☕ Human Break (Istirahat AFK Berkala)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Text("Berhenti sejenak secara acak lalu lanjut otomatis (aman dari Watchdog).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = config.preset == HumanizerPreset.STEALTH || config.humanBreakEnabled,
                        enabled = config.preset == HumanizerPreset.CUSTOM,
                        onCheckedChange = { onUpdate(config.copy(humanBreakEnabled = it)) }
                    )
                }

                if (config.preset == HumanizerPreset.STEALTH || config.humanBreakEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = config.breakEveryMinMinutes.toString(),
                            onValueChange = { raw ->
                                raw.filter(Char::isDigit).toIntOrNull()?.let { v ->
                                    val mn = v.coerceIn(1, 360)
                                    onUpdate(config.copy(breakEveryMinMinutes = mn, breakEveryMaxMinutes = maxOf(config.breakEveryMaxMinutes, mn)))
                                }
                            },
                            label = { Text("Tiap Min (mnt)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = config.breakEveryMaxMinutes.toString(),
                            onValueChange = { raw ->
                                raw.filter(Char::isDigit).toIntOrNull()?.let { v ->
                                    onUpdate(config.copy(breakEveryMaxMinutes = v.coerceIn(config.breakEveryMinMinutes, 480)))
                                }
                            },
                            label = { Text("Tiap Maks (mnt)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = config.breakDurationMinSec.toString(),
                            onValueChange = { raw ->
                                raw.filter(Char::isDigit).toIntOrNull()?.let { v ->
                                    val mn = v.coerceIn(5, 3600)
                                    onUpdate(config.copy(breakDurationMinSec = mn, breakDurationMaxSec = maxOf(config.breakDurationMaxSec, mn)))
                                }
                            },
                            label = { Text("Lama Min (dtk)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = config.breakDurationMaxSec.toString(),
                            onValueChange = { raw ->
                                raw.filter(Char::isDigit).toIntOrNull()?.let { v ->
                                    onUpdate(config.copy(breakDurationMaxSec = v.coerceIn(config.breakDurationMinSec, 7200)))
                                }
                            },
                            label = { Text("Lama Maks (dtk)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
