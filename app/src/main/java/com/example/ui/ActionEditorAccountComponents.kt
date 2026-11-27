package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.SwitchAccount
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.AccountOcrMatchMode
import com.example.data.AccountProfile
import com.example.data.AccountScrollDirection
import com.example.data.AccountSwitchMethod
import com.example.data.ExitAppMode
import com.example.data.MacroBranch
import com.example.data.NotFoundMode
import com.example.data.SelectAccountAction
import com.example.data.SwitchAccountAction
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SwitchAccountActionEditor(
    action: SwitchAccountAction,
    accounts: List<AccountProfile>,
    onUpdate: (SwitchAccountAction) -> Unit
) {
    val accent = Color(0xFF38BDF8)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.09f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.42f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.SwitchAccount, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            Text(
                "Memajukan giliran ke akun berikutnya di Tab 'Akun', menjalankan 'Alur Ganti Akun', lalu melanjutkan macro untuk akun baru.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Text("Target Akun Tujuan", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = action.specificAccountId.isBlank(),
            onClick = { onUpdate(action.copy(specificAccountId = "", specificAccountLabel = "")) },
            label = { Text("⏭ Akun Berikutnya (Rotasi)", style = MaterialTheme.typography.labelSmall) }
        )
        accounts.forEach { acc ->
            FilterChip(
                selected = action.specificAccountId == acc.id,
                onClick = { onUpdate(action.copy(specificAccountId = acc.id, specificAccountLabel = acc.label)) },
                label = { Text("👤 ${acc.label}", style = MaterialTheme.typography.labelSmall) }
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "Ulangi Jalur Utama dari awal setelah ganti akun",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = action.restartAfterSwitch,
            onCheckedChange = { onUpdate(action.copy(restartAfterSwitch = it)) }
        )
    }

    AccountNumberInput("Jeda sesudah aksi (ms)", action.delayAfterMs.toString()) {
        onUpdate(action.copy(delayAfterMs = (it.toLongOrNull() ?: action.delayAfterMs).coerceAtLeast(0L)))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SelectAccountActionEditor(
    action: SelectAccountAction,
    macroDefaultMethod: AccountSwitchMethod,
    branches: List<MacroBranch>,
    onPickRegionOnScreen: () -> Unit,
    onUpdate: (SelectAccountAction) -> Unit
) {
    val accent = Color(0xFF06B6D4)
    val effectiveMethod = action.methodOverride ?: macroDefaultMethod

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.09f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.42f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.ManageAccounts, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            Text(
                "Otomatis memilih / membuka akun yang sedang giliran dari Tab 'Akun' (via OCR + Auto-Scroll dan/atau Ganti Clone App).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Text("Metode untuk Aksi Ini", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = action.methodOverride == null,
            onClick = { onUpdate(action.copy(methodOverride = null)) },
            label = { Text("Ikuti Tab Akun (${macroDefaultMethod.shortLabel})", style = MaterialTheme.typography.labelSmall) }
        )
        AccountSwitchMethod.entries.forEach { m ->
            FilterChip(
                selected = action.methodOverride == m,
                onClick = { onUpdate(action.copy(methodOverride = m)) },
                label = { Text(m.shortLabel, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }

    if (effectiveMethod != AccountSwitchMethod.CLONE_APP) {
        HorizontalDivider()
        Text("Pengaturan OCR Daftar Akun + Auto-Scroll", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = accent)

        val regionVal = action.region
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(onClick = onPickRegionOnScreen, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.CropFree, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (regionVal != null) "Ubah Area Daftar (${regionVal.width}×${regionVal.height})"
                    else "Pilih Area Daftar di Layar (Opsional)",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (regionVal != null) {
                TextButton(onClick = { onUpdate(action.copy(region = null)) }) {
                    Text("Layar Penuh", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AccountOcrMatchMode.entries.forEach { mm ->
                FilterChip(
                    selected = action.matchMode == mm,
                    onClick = { onUpdate(action.copy(matchMode = mm)) },
                    label = { Text(mm.shortLabel, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AccountScrollDirection.entries.forEach { dir ->
                FilterChip(
                    selected = action.scrollDirection == dir,
                    onClick = { onUpdate(action.copy(scrollDirection = dir)) },
                    label = { Text("Scroll ${dir.shortLabel}", style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountNumberInput("Maks. geser scroll", action.maxScrolls.toString(), Modifier.weight(1f)) {
                onUpdate(action.copy(maxScrolls = (it.toIntOrNull() ?: action.maxScrolls).coerceIn(0, 30)))
            }
            AccountNumberInput("Jeda setelah scroll (ms)", action.scrollSettleMs.toString(), Modifier.weight(1f)) {
                onUpdate(action.copy(scrollSettleMs = (it.toLongOrNull() ?: action.scrollSettleMs).coerceIn(100L, 5000L)))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountSignedNumberInput("Offset klik X (px)", action.clickOffsetX.toString(), Modifier.weight(1f)) {
                onUpdate(action.copy(clickOffsetX = it.toIntOrNull() ?: action.clickOffsetX))
            }
            AccountSignedNumberInput("Offset klik Y (px)", action.clickOffsetY.toString(), Modifier.weight(1f)) {
                onUpdate(action.copy(clickOffsetY = it.toIntOrNull() ?: action.clickOffsetY))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Otomatis scroll balik jika mentok ujung daftar",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = action.reverseOnEnd,
                onCheckedChange = { onUpdate(action.copy(reverseOnEnd = it)) }
            )
        }

        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Akurasi Gambar Fallback", style = MaterialTheme.typography.bodySmall)
                Text("${(action.fallbackThreshold * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = accent)
            }
            Slider(
                value = action.fallbackThreshold.coerceIn(0.50f, 0.99f),
                onValueChange = { onUpdate(action.copy(fallbackThreshold = it)) },
                valueRange = 0.50f..0.99f
            )
        }
    }

    if (effectiveMethod != AccountSwitchMethod.OCR_LIST) {
        HorizontalDivider()
        Text("Pengaturan Ganti Clone App", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = accent)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Tutup aplikasi lama sebelum buka clone baru", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(
                checked = action.exitPreviousApp,
                onCheckedChange = { onUpdate(action.copy(exitPreviousApp = it)) }
            )
        }

        if (action.exitPreviousApp) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExitAppMode.entries.forEach { em ->
                    FilterChip(
                        selected = action.exitMode == em,
                        onClick = { onUpdate(action.copy(exitMode = em)) },
                        label = { Text(em.label.substringBefore(" ("), style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountNumberInput("Jeda tutup app (ms)", action.exitWaitMs.toString(), Modifier.weight(1f)) {
                onUpdate(action.copy(exitWaitMs = (it.toLongOrNull() ?: action.exitWaitMs).coerceIn(0L, 15000L)))
            }
            AccountNumberInput("Tunggu loading clone (ms)", action.launchWaitMs.toString(), Modifier.weight(1f)) {
                onUpdate(action.copy(launchWaitMs = (it.toLongOrNull() ?: action.launchWaitMs).coerceIn(0L, 60000L)))
            }
        }
    }

    HorizontalDivider()
    Text("Jika Akun Tidak Ditemukan / Gagal", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NotFoundMode.entries.forEach { nm ->
            FilterChip(
                selected = action.notFound == nm,
                onClick = { onUpdate(action.copy(notFound = nm)) },
                label = { Text(nm.label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
    if (action.notFound == NotFoundMode.SWITCH_BRANCH || action.notFound == NotFoundMode.CALL_BRANCH_RETURN) {
        TargetBranchPickerRow(
            title = "Cabang Tujuan Jika Gagal:",
            branches = branches,
            selectedBranchId = action.notFoundBranchId,
            onSelect = { bid, _ -> onUpdate(action.copy(notFoundBranchId = bid)) }
        )
    }

    AccountNumberInput("Jeda sesudah aksi (ms)", action.delayAfterMs.toString()) {
        onUpdate(action.copy(delayAfterMs = (it.toLongOrNull() ?: action.delayAfterMs).coerceAtLeast(0L)))
    }
}

@Composable
private fun AccountNumberInput(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
private fun AccountSignedNumberInput(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val filtered = buildString {
                raw.forEachIndexed { idx, ch ->
                    if (ch.isDigit() || (ch == '-' && idx == 0)) append(ch)
                }
            }
            onChange(filtered)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}
