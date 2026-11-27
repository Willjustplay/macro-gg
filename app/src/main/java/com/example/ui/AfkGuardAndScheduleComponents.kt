package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.automation.AutomationCoordinator
import com.example.automation.InstalledAppInfo
import com.example.automation.InstalledAppsHelper
import com.example.automation.MacroSchedulerManager
import com.example.data.AfkAndDeviceGuardConfig
import com.example.data.AfkBlackScreenMode
import com.example.data.DeviceGuardAction
import com.example.data.DeviceGuardEvaluator
import com.example.data.DeviceHealthSnapshot
import com.example.data.LocalStore
import com.example.data.Macro
import com.example.data.MacroSchedule
import com.example.data.MacroScheduleCalculator
import com.example.data.ScheduleRepeatMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AfkGuardAndScheduleSheet(
    store: LocalStore,
    macros: List<Macro>,
    deviceHealth: DeviceHealthSnapshot?,
    onDismiss: () -> Unit,
    isPremium: Boolean = true,
    freeScheduleLimit: Int = 1,
    onRequirePremium: (String) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val savedGuard by store.afkGuardConfig.collectAsStateWithLifecycle()
    val schedules by store.schedules.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var selectedTab by remember { mutableIntStateOf(0) }
    var editingSchedule by remember { mutableStateOf<MacroSchedule?>(null) }

    LaunchedEffect(Unit) {
        AutomationCoordinator.refreshDeviceHealth()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (selectedTab == 0) Icons.Filled.Bedtime else Icons.Filled.Schedule,
                        contentDescription = null,
                        tint = Color(0xFF818CF8)
                    )
                    Text(
                        text = if (selectedTab == 0) "AFK & Guard HP" else "Penjadwal Otomatis",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                TextButton(onClick = onDismiss) { Text("Tutup") }
            }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("🌙 Layar AFK & Guard") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("⏰ Jadwal (${schedules.count { it.enabled }})") }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (selectedTab == 0) {
                    AfkAndGuardTabContent(
                        savedConfig = savedGuard,
                        deviceHealth = deviceHealth,
                        onRefreshHealth = { AutomationCoordinator.refreshDeviceHealth() },
                        onSave = { store.saveAfkGuardConfig(it) }
                    )
                } else {
                    AutoSchedulerTabContent(
                        schedules = schedules,
                        macros = macros.filter { !it.isPassive },
                        isPremium = isPremium,
                        freeScheduleLimit = freeScheduleLimit,
                        onRequirePremium = onRequirePremium,
                        onAddNew = {
                            val runnable = macros.filter { !it.isPassive }
                            val def = runnable.firstOrNull()
                            editingSchedule = MacroSchedule(
                                macroId = def?.id.orEmpty(),
                                macroName = def?.name.orEmpty()
                            )
                        },
                        onEdit = { editingSchedule = it },
                        onToggleEnabled = { sched, want ->
                            store.saveSchedule(sched.copy(enabled = want))
                            MacroSchedulerManager.syncAlarms(context)
                        },
                        onDelete = { sched ->
                            store.deleteSchedule(sched.id)
                            MacroSchedulerManager.syncAlarms(context)
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    editingSchedule?.let { sched ->
        ScheduleEditorDialog(
            initial = sched,
            runnableMacros = macros.filter { !it.isPassive },
            onSave = { updated ->
                store.saveSchedule(updated)
                MacroSchedulerManager.syncAlarms(context)
                editingSchedule = null
            },
            onDismiss = { editingSchedule = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AfkAndGuardTabContent(
    savedConfig: AfkAndDeviceGuardConfig,
    deviceHealth: DeviceHealthSnapshot?,
    onRefreshHealth: () -> Unit,
    onSave: (AfkAndDeviceGuardConfig) -> Unit
) {
    var draft by remember(savedConfig) { mutableStateOf(savedConfig) }
    var savedMsg by remember { mutableStateOf("") }

    // Live Hardware Status Card
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF6366F1).copy(alpha = 0.11f),
        border = BorderStroke(1.dp, Color(0xFF6366F1).copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Status Perangkat Saat Ini",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF818CF8)
                )
                val healthStr = if (deviceHealth != null) {
                    val chg = if (deviceHealth.isCharging) "⚡ Mengisi Daya" else "🔋 Baterai"
                    "$chg ${deviceHealth.batteryPercent}%  •  🌡 ${DeviceGuardEvaluator.formatTemp(deviceHealth.tempCelsius)}"
                } else {
                    "Membaca sensor baterai & suhu..."
                }
                Text(healthStr, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onRefreshHealth) {
                Icon(Icons.Filled.Refresh, contentDescription = "Segarkan", tint = Color(0xFF818CF8))
            }
        }
    }

    // 1. Mode Layar Hitam AFK (🌙)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("🌙 Mode Layar Hitam AFK", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Aktifkan langsung kapan saja lewat tombol 🌙 di bar melayang. Sentuhan otomatis tetap tembus ke game tanpa gangguan.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AfkBlackScreenMode.entries.forEach { mode ->
                    FilterChip(
                        selected = draft.blackScreenMode == mode,
                        onClick = { draft = draft.copy(blackScreenMode = mode) },
                        label = { Text(mode.shortLabel) }
                    )
                }
            }
            Text(
                draft.blackScreenMode.description,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF818CF8)
            )

            Text(
                "Aktifkan Layar AFK otomatis setelah macro mulai:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            val autoSecOptions = listOf(0 to "Manual", 15 to "15d", 30 to "30d", 60 to "1m", 180 to "3m")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                autoSecOptions.forEach { (sec, lbl) ->
                    FilterChip(
                        selected = draft.autoBlackScreenAfterSec == sec,
                        onClick = { draft = draft.copy(autoBlackScreenAfterSec = sec) },
                        label = { Text(lbl) }
                    )
                }
            }

            AfkToggleRow("Turunkan kecerahan layar ke minimum (~0.5%)", draft.dimBrightnessToMin) {
                draft = draft.copy(dimBrightnessToMin = it)
            }
            AfkToggleRow("Geser halus label status (Anti Burn-In AMOLED)", draft.amoledBurnInShift) {
                draft = draft.copy(amoledBurnInShift = it)
            }
            AfkToggleRow("Ketuk 2× pada panel AFK untuk bangun", draft.doubleTapToUnlock) {
                draft = draft.copy(doubleTapToUnlock = it)
            }
        }
    }

    // 2. Guard Baterai Lemah
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
            AfkToggleRow("🔋 Guard Baterai Lemah (Stop Otomatis)", draft.batteryGuardEnabled) {
                draft = draft.copy(batteryGuardEnabled = it)
            }
            Text(
                "Otomatis menghentikan macro saat baterai rendah & HP tidak sedang dicas.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (draft.batteryGuardEnabled) {
                Text(
                    "Batas stop baterai: ${draft.minBatteryPercent}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = draft.minBatteryPercent.toFloat(),
                    onValueChange = { draft = draft.copy(minBatteryPercent = it.roundToInt().coerceIn(5, 50)) },
                    valueRange = 5f..50f,
                    steps = 8
                )
            }
        }
    }

    // 3. Guard Suhu Panas (Thermal Overheat Guard)
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
            AfkToggleRow("🌡 Guard Suhu Panas (Anti Overheat)", draft.thermalGuardEnabled) {
                draft = draft.copy(thermalGuardEnabled = it)
            }
            Text(
                "Pantau suhu baterai setiap 10 detik saat farming berjam-jam untuk mencegah HP kepanasan.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (draft.thermalGuardEnabled) {
                Text(
                    "Batas suhu maksimum: ${DeviceGuardEvaluator.formatTemp(draft.maxTempCelsius)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = draft.maxTempCelsius,
                    onValueChange = {
                        val stepped = (it * 2f).roundToInt() / 2f
                        draft = draft.copy(maxTempCelsius = stepped.coerceIn(38f, 52f))
                    },
                    valueRange = 38f..52f
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DeviceGuardAction.entries.forEach { act ->
                        FilterChip(
                            selected = draft.thermalAction == act,
                            onClick = { draft = draft.copy(thermalAction = act) },
                            label = { Text(act.shortLabel) }
                        )
                    }
                }
                if (draft.thermalAction == DeviceGuardAction.PAUSE_AND_COOL) {
                    val resumeTemp = (draft.maxTempCelsius - draft.resumeTempDropCelsius).coerceAtLeast(30f)
                    Text(
                        "Lanjut otomatis setelah dingin turun ${draft.resumeTempDropCelsius.roundToInt()}°C (≤ ${DeviceGuardEvaluator.formatTemp(resumeTemp)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF38BDF8)
                    )
                    Slider(
                        value = draft.resumeTempDropCelsius,
                        onValueChange = { draft = draft.copy(resumeTempDropCelsius = it.roundToInt().toFloat().coerceIn(2f, 8f)) },
                        valueRange = 2f..8f,
                        steps = 5
                    )
                }
            }
            AfkToggleRow("Kirim Notifikasi & Alarm HP saat Guard terpicu", draft.notifyOnGuardTrigger) {
                draft = draft.copy(notifyOnGuardTrigger = it)
            }
        }
    }

    if (savedMsg.isNotBlank()) {
        Text(
            savedMsg,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF10B981),
            fontWeight = FontWeight.SemiBold
        )
    }

    Button(
        onClick = {
            onSave(draft)
            savedMsg = "Pengaturan Mode AFK & Guard HP tersimpan."
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        Text("Simpan Pengaturan AFK & Guard", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AutoSchedulerTabContent(
    schedules: List<MacroSchedule>,
    macros: List<Macro>,
    isPremium: Boolean,
    freeScheduleLimit: Int,
    onRequirePremium: (String) -> Unit,
    onAddNew: () -> Unit,
    onEdit: (MacroSchedule) -> Unit,
    onToggleEnabled: (MacroSchedule, Boolean) -> Unit,
    onDelete: (MacroSchedule) -> Unit
) {
    val nowMs = remember(schedules) { System.currentTimeMillis() }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0EA5E9).copy(alpha = 0.10f),
        border = BorderStroke(1.dp, Color(0xFF0EA5E9).copy(alpha = 0.30f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                "⏰ Penjadwal Otomatis (Auto-Scheduler)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0EA5E9)
            )
            Text(
                "Jalankan macro otomatis sesuai jam & hari (termasuk buka game duluan, batasi durasi, dan langsung masuk Mode Layar Hitam AFK). Pastikan izin Aksesibilitas & Bebas Optimasi Baterai aktif.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!isPremium) {
                Text(
                    "Paket Gratis: maks $freeScheduleLimit jadwal. Upgrade PRO untuk jadwal tanpa batas.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFF59E0B),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    val reachedLimit = !isPremium && schedules.size >= freeScheduleLimit
    FilledTonalButton(
        onClick = {
            if (macros.isEmpty()) return@FilledTonalButton
            if (reachedLimit) {
                onRequirePremium("Jadwal Otomatis Tanpa Batas")
            } else {
                onAddNew()
            }
        },
        enabled = macros.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(
            if (reachedLimit) Icons.Filled.Lock else Icons.Filled.Add,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (macros.isEmpty()) "Buat Macro Aktif Dulu"
            else if (reachedLimit) "Tambah Jadwal (PRO)"
            else "Buat Jadwal Baru",
            fontWeight = FontWeight.Bold
        )
    }

    if (schedules.isEmpty()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ) {
            Text(
                "Belum ada jadwal otomatis. Tambahkan jadwal untuk mulai farming rutin pagi/malam tanpa disentuh.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            )
        }
    } else {
        schedules.forEach { s ->
            val nextTxt = MacroScheduleCalculator.formatNextTrigger(s, nowMs)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f),
                border = if (s.enabled) BorderStroke(1.dp, Color(0xFF6366F1).copy(alpha = 0.35f)) else null
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    MacroScheduleCalculator.formatTime(s.hour, s.minute),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (s.enabled) Color(0xFF818CF8) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF6366F1).copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        MacroScheduleCalculator.repeatSummary(s),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF818CF8),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                buildString {
                                    if (s.label.isNotBlank()) append("${s.label} • ")
                                    append(s.macroName.ifBlank { "Macro" })
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Berikutnya: $nextTxt" +
                                    (if (s.launchAppPackage.isNotBlank()) " • Buka ${s.launchAppLabel.ifBlank { s.launchAppPackage }}" else "") +
                                    (if (s.stopAfterMinutes > 0) " • Stop ${s.stopAfterMinutes}m" else "") +
                                    (if (s.enableBlackScreenOnStart) " • 🌙 AFK" else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = s.enabled,
                            onCheckedChange = { onToggleEnabled(s, it) }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { onEdit(s) }) {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Edit")
                        }
                        TextButton(onClick = { onDelete(s) }) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Hapus")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleEditorDialog(
    initial: MacroSchedule,
    runnableMacros: List<Macro>,
    onSave: (MacroSchedule) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var hourText by remember(initial.id, initial.hour) { mutableStateOf(initial.hour.toString().padStart(2, '0')) }
    var minText by remember(initial.id, initial.minute) { mutableStateOf(initial.minute.toString().padStart(2, '0')) }
    var stopMinText by remember(initial.id, initial.stopAfterMinutes) { mutableStateOf(initial.stopAfterMinutes.toString()) }
    var waitSecText by remember(initial.id, initial.launchWaitSec) { mutableStateOf(initial.launchWaitSec.toString()) }
    var showAppPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.label.isBlank() && initial.lastTriggeredAtMs == 0L) "Jadwal Baru" else "Edit Jadwal") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = draft.label,
                    onValueChange = { draft = draft.copy(label = it.take(40)) },
                    label = { Text("Nama Jadwal (opsional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Pilih Macro Target", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    runnableMacros.forEach { m ->
                        FilterChip(
                            selected = draft.macroId == m.id,
                            onClick = { draft = draft.copy(macroId = m.id, macroName = m.name) },
                            label = { Text(m.name.take(22)) }
                        )
                    }
                }

                Text("Jam Mulai (24 Jam)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = hourText,
                        onValueChange = { raw ->
                            val clean = raw.filter { it.isDigit() }.take(2)
                            hourText = clean
                            clean.toIntOrNull()?.let { h ->
                                draft = draft.copy(hour = h.coerceIn(0, 23))
                            }
                        },
                        label = { Text("Jam (0-23)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Text(":", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = minText,
                        onValueChange = { raw ->
                            val clean = raw.filter { it.isDigit() }.take(2)
                            minText = clean
                            clean.toIntOrNull()?.let { m ->
                                draft = draft.copy(minute = m.coerceIn(0, 59))
                            }
                        },
                        label = { Text("Menit (0-59)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(6 to 0, 12 to 0, 18 to 0, 22 to 0, 0 to 0).forEach { (h, m) ->
                        FilterChip(
                            selected = draft.hour == h && draft.minute == m,
                            onClick = {
                                draft = draft.copy(hour = h, minute = m)
                                hourText = h.toString().padStart(2, '0')
                                minText = m.toString().padStart(2, '0')
                            },
                            label = { Text(MacroScheduleCalculator.formatTime(h, m)) }
                        )
                    }
                }

                Text("Pengulangan", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScheduleRepeatMode.entries.forEach { mode ->
                        FilterChip(
                            selected = draft.repeatMode == mode,
                            onClick = { draft = draft.copy(repeatMode = mode) },
                            label = { Text(mode.shortLabel) }
                        )
                    }
                }
                if (draft.repeatMode == ScheduleRepeatMode.CUSTOM_DAYS) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        (1..7).forEach { day ->
                            val chosen = day in draft.daysOfWeek
                            FilterChip(
                                selected = chosen,
                                onClick = {
                                    val nextDays = if (chosen) draft.daysOfWeek - day else (draft.daysOfWeek + day).sorted()
                                    draft = draft.copy(daysOfWeek = nextDays)
                                },
                                label = { Text(MacroScheduleCalculator.dayShortLabel(day)) }
                            )
                        }
                    }
                }

                HorizontalDivider()
                Text("Buka Game Otomatis (Opsional)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { showAppPicker = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Apps, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            draft.launchAppLabel.ifBlank { draft.launchAppPackage.ifBlank { "Pilih Aplikasi Game" } },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (draft.launchAppPackage.isNotBlank()) {
                        TextButton(onClick = { draft = draft.copy(launchAppPackage = "", launchAppLabel = "") }) {
                            Text("Hapus")
                        }
                    }
                }
                if (draft.launchAppPackage.isNotBlank()) {
                    OutlinedTextField(
                        value = waitSecText,
                        onValueChange = { raw ->
                            val clean = raw.filter { it.isDigit() }.take(2)
                            waitSecText = clean
                            draft = draft.copy(launchWaitSec = (clean.toIntOrNull() ?: 8).coerceIn(1, 60))
                        },
                        label = { Text("Tunggu loading game (detik)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = stopMinText,
                    onValueChange = { raw ->
                        val clean = raw.filter { it.isDigit() }.take(4)
                        stopMinText = clean
                        draft = draft.copy(stopAfterMinutes = (clean.toIntOrNull() ?: 0).coerceIn(0, 1440))
                    },
                    label = { Text("Stop otomatis setelah (menit, 0 = ikuti macro)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                AfkToggleRow("Aktifkan Mode Layar Hitam AFK (🌙) saat mulai", draft.enableBlackScreenOnStart) {
                    draft = draft.copy(enableBlackScreenOnStart = it)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val chosenMacro = runnableMacros.firstOrNull { it.id == draft.macroId } ?: runnableMacros.firstOrNull()
                    if (chosenMacro != null) {
                        onSave(
                            draft.copy(
                                macroId = chosenMacro.id,
                                macroName = chosenMacro.name,
                                lastTriggeredAtMs = 0L // Allow newly edited schedule to trigger cleanly
                            )
                        )
                    }
                },
                enabled = runnableMacros.isNotEmpty()
            ) { Text("Simpan") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        }
    )

    if (showAppPicker) {
        var apps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
        var search by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            apps = withContext(Dispatchers.IO) { InstalledAppsHelper.loadLaunchableApps(context) }
        }
        val filtered = remember(apps, search) {
            if (search.isBlank()) apps
            else apps.filter { it.label.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = { showAppPicker = false },
            title = { Text("Pilih Aplikasi Game") },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = { Text("Cari nama / package") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(filtered, key = { it.packageName }) { app ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        draft = draft.copy(launchAppPackage = app.packageName, launchAppLabel = app.label)
                                        showAppPicker = false
                                    }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppPicker = false }) { Text("Tutup") }
            }
        )
    }
}

@Composable
private fun AfkToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
