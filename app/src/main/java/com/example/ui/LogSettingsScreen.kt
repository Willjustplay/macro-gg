package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.sp
import com.example.automation.ExecutionLogBus
import com.example.automation.LogEntry
import com.example.automation.LogLevel
import com.example.data.AppSettings
import com.example.data.ThemeMode
import com.example.ui.theme.JetBrainsMonoFamily

@Composable
fun LiveLogPanel(logs: List<LogEntry>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Log Eksekusi (${logs.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = { ExecutionLogBus.clear() }) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Bersihkan")
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220))
        ) {
            if (logs.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Belum ada log. Jalankan macro atau script Lua untuk melihat aktivitas real-time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs.asReversed(), key = { it.id }) { entry ->
                        val tagColor = when (entry.level) {
                            LogLevel.IMAGE -> Color(0xFF34D399)
                            LogLevel.ACTION -> Color(0xFF38BDF8)
                            LogLevel.COLOR -> Color(0xFFC084FC)
                            LogLevel.LUA -> Color(0xFFFBBF24)
                            LogLevel.WARN -> Color(0xFFFB923C)
                            LogLevel.ERROR -> Color(0xFFF87171)
                            LogLevel.INFO -> Color(0xFF94A3B8)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                entry.timestamp.substring(0, 8),
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 10.5.sp,
                                color = Color(0xFF64748B)
                            )
                            Text(
                                entry.level.tag,
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = tagColor
                            )
                            Text(
                                entry.message,
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 11.sp,
                                color = Color(0xFFE2E8F0)
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsPanel(
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onExportAllBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onExportBackupDirectToDownloads: () -> Unit = onExportAllBackup,
    onImportFromDownloadsFolder: () -> Unit = onImportBackup,
    billingState: com.example.billing.BillingUiState = com.example.billing.BillingUiState(),
    onOpenPaywall: () -> Unit = {},
    onRestorePurchases: () -> Unit = {},
    onManageSubscriptions: () -> Unit = {}
) {
    var tapMsText by remember(settings.defaultTapDurationMs) { mutableStateOf(settings.defaultTapDurationMs.toString()) }
    var jitterText by remember(settings.defaultJitterPx) { mutableStateOf(settings.defaultJitterPx.toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Subscription / PRO Card
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (billingState.isPremium) {
                    Color(0xFF064E3B).copy(alpha = 0.25f)
                } else {
                    ProGoldColor.copy(alpha = 0.10f)
                }
            )
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (billingState.isPremium) "Wjust macro PRO Aktif" else "Wjust macro PRO",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = if (billingState.isPremium) {
                                "Paket aktif: ${billingState.activePlanLabel} • Semua fitur terbuka"
                            } else {
                                "Paket saat ini: Gratis (Klik, Swipe, Deteksi Gambar • maks. 3 macro)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!billingState.isPremium) {
                        ProLockBadge(text = "UPGRADE")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenPaywall, modifier = Modifier.weight(1.2f)) {
                        Text(if (billingState.isPremium) "Detail Paket" else "Upgrade PRO", maxLines = 1)
                    }
                    OutlinedButton(onClick = onRestorePurchases, modifier = Modifier.weight(1f)) {
                        Text("Pulihkan", maxLines = 1)
                    }
                }
                if (billingState.isPremium) {
                    OutlinedButton(onClick = onManageSubscriptions, modifier = Modifier.fillMaxWidth()) {
                        Text("Kelola Langganan di Google Play", maxLines = 1)
                    }
                }
            }
        }

        // Backup / Export / Import (with direct CloudPhone / Redfinger Download/WjustMacro option)
        Card(shape = RoundedCornerShape(14.dp)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Backup & Pindah Perangkat (Offline)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (!billingState.isPremium) {
                        ProLockBadge()
                    }
                }
                Text(
                    "Semua macro, gambar target, dan script Lua disimpan lokal di perangkat ini. Di CloudPhone/Redfinger, gunakan simpan langsung ke Download/WjustMacro agar tidak bergantung pada pemilih file sistem.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onExportAllBackup, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.FileDownload, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Backup (.zip)")
                    }
                    OutlinedButton(onClick = onImportBackup, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.FileUpload, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Restore")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilledTonalButton(
                        onClick = onExportBackupDirectToDownloads,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Simpan ke Download", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = onImportFromDownloadsFolder,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Impor dr Download", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // Touch & Detection Defaults
        Card(shape = RoundedCornerShape(14.dp)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Preferensi Sentuhan & Deteksi", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = tapMsText,
                        onValueChange = { raw ->
                            tapMsText = raw.filter(Char::isDigit)
                            tapMsText.toLongOrNull()?.let { ms ->
                                onUpdateSettings { it.copy(defaultTapDurationMs = ms.coerceIn(10L, 5000L)) }
                            }
                        },
                        label = { Text("Durasi klik default (ms)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = jitterText,
                        onValueChange = { raw ->
                            jitterText = raw.filter(Char::isDigit)
                            jitterText.toIntOrNull()?.let { px ->
                                onUpdateSettings { it.copy(defaultJitterPx = px.coerceIn(0, 60)) }
                            }
                        },
                        label = { Text("Acak posisi (±px)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Tampilkan kotak hijau saat gambar ditemukan", style = MaterialTheme.typography.bodyMedium)
                        Text("Memudahkan cek akurasi Image Detection di layar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings.showDebugMatchBox,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(showDebugMatchBox = v) } }
                    )
                }
            }
        }

        // Appearance
        Card(shape = RoundedCornerShape(14.dp)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tampilan", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                            label = { Text(mode.label) }
                        )
                    }
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Warna Dinamis (Material You)", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = settings.dynamicColor,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(dynamicColor = v) } }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
