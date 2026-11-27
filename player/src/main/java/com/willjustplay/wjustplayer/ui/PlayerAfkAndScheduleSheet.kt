package com.willjustplay.wjustplayer.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import com.example.data.AfkAndDeviceGuardConfig
import com.example.data.AfkBlackScreenMode
import com.example.data.DeviceGuardAction
import com.example.data.DeviceGuardEvaluator
import com.example.data.DeviceHealthSnapshot
import com.example.data.Macro
import com.example.data.MacroSchedule
import com.example.data.MacroScheduleCalculator
import com.example.data.ScheduleRepeatMode
import com.willjustplay.wjustplayer.data.PlayerNotificationStore
import com.willjustplay.wjustplayer.runtime.PlayerCoordinator
import com.willjustplay.wjustplayer.runtime.PlayerSchedulerManager
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerAfkAndScheduleSheet(
    notifyStore: PlayerNotificationStore,
    macros: List<Macro>,
    deviceHealth: DeviceHealthSnapshot?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val savedGuard by notifyStore.afkGuardConfig.collectAsStateWithLifecycle()
    val schedules by notifyStore.schedules.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var selectedTab by remember { mutableIntStateOf(0) }
    var editingSchedule by remember { mutableStateOf<MacroSchedule?>(null) }

    LaunchedEffect(Unit) {
        PlayerCoordinator.refreshDeviceHealth()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("🌙 AFK & Guard") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("⏰ Jadwal (${schedules.count { it.enabled }})") })
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (selectedTab == 0) {
                    PlayerAfkGuardTab(
                        saved = savedGuard,
                        health = deviceHealth,
                        onRefresh = { PlayerCoordinator.refreshDeviceHealth() },
                        onSave = { notifyStore.saveAfkGuardConfig(it) }
                    )
                } else {
                    val runnable = macros.filter { !it.isPassive }
                    PlayerSchedulerTab(
                        schedules = schedules,
                        runnableMacros = runnable,
                        onAdd = {
                            val def = runnable.firstOrNull()
                            editingSchedule = MacroSchedule(macroId = def?.id.orEmpty(), macroName = def?.name.orEmpty())
                        },
                        onEdit = { editingSchedule = it },
                        onToggle = { s, en ->
                            notifyStore.saveSchedule(s.copy(enabled = en))
                            PlayerSchedulerManager.syncAlarms(context)
                        },
                        onDelete = { s ->
                            notifyStore.deleteSchedule(s.id)
                            PlayerSchedulerManager.syncAlarms(context)
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    editingSchedule?.let { s ->
        PlayerScheduleDialog(
            initial = s,
            runnableMacros = macros.filter { !it.isPassive },
            onSave = { updated ->
                notifyStore.saveSchedule(updated)
                PlayerSchedulerManager.syncAlarms(context)
                editingSchedule = null
            },
            onDismiss = { editingSchedule = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerAfkGuardTab(
    saved: AfkAndDeviceGuardConfig,
    health: DeviceHealthSnapshot?,
    onRefresh: () -> Unit,
    onSave: (AfkAndDeviceGuardConfig) -> Unit
) {
    var draft by remember(saved) { mutableStateOf(saved) }
    var savedMsg by remember { mutableStateOf("") }

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
            Column(modifier = Modifier.weight(1f)) {
                Text("Status HP Saat Ini", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF818CF8))
                val txt = if (health != null) {
                    val chg = if (health.isCharging) "⚡ Mengisi" else "🔋 Baterai"
                    "$chg ${health.batteryPercent}% • 🌡 ${DeviceGuardEvaluator.formatTemp(health.tempCelsius)}"
                } else "Membaca sensor..."
                Text(txt, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Segarkan", tint = Color(0xFF818CF8))
            }
        }
    }

    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🌙 Mode Layar Hitam AFK", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AfkBlackScreenMode.entries.forEach { mode ->
                    FilterChip(
                        selected = draft.blackScreenMode == mode,
                        onClick = { draft = draft.copy(blackScreenMode = mode) },
                        label = { Text(mode.shortLabel) }
                    )
                }
            }
            Text(draft.blackScreenMode.description, style = MaterialTheme.typography.labelSmall, color = Color(0xFF818CF8))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "Manual", 15 to "15d", 30 to "30d", 60 to "1m", 180 to "3m").forEach { (sec, lbl) ->
                    FilterChip(
                        selected = draft.autoBlackScreenAfterSec == sec,
                        onClick = { draft = draft.copy(autoBlackScreenAfterSec = sec) },
                        label = { Text(lbl) }
                    )
                }
            }
            PlayerAfkRow("Kecerahan minimum (~0.5%)", draft.dimBrightnessToMin) { draft = draft.copy(dimBrightnessToMin = it) }
            PlayerAfkRow("Anti Burn-In AMOLED", draft.amoledBurnInShift) { draft = draft.copy(amoledBurnInShift = it) }
            PlayerAfkRow("Ketuk 2× untuk bangun", draft.doubleTapToUnlock) { draft = draft.copy(doubleTapToUnlock = it) }
        }
    }

    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PlayerAfkRow("🔋 Guard Baterai Lemah", draft.batteryGuardEnabled) { draft = draft.copy(batteryGuardEnabled = it) }
            if (draft.batteryGuardEnabled) {
                Text("Stop di bawah: ${draft.minBatteryPercent}%", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = draft.minBatteryPercent.toFloat(),
                    onValueChange = { draft = draft.copy(minBatteryPercent = it.roundToInt().coerceIn(5, 50)) },
                    valueRange = 5f..50f,
                    steps = 8
                )
            }
            PlayerAfkRow("🌡 Guard Suhu Panas (Overheat)", draft.thermalGuardEnabled) { draft = draft.copy(thermalGuardEnabled = it) }
            if (draft.thermalGuardEnabled) {
                Text("Batas suhu: ${DeviceGuardEvaluator.formatTemp(draft.maxTempCelsius)}", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = draft.maxTempCelsius,
                    onValueChange = { draft = draft.copy(maxTempCelsius = ((it * 2f).roundToInt() / 2f).coerceIn(38f, 52f)) },
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
            }
            PlayerAfkRow("Notif & Alarm saat Guard terpicu", draft.notifyOnGuardTrigger) {
                draft = draft.copy(notifyOnGuardTrigger = it)
            }
        }
    }

    if (savedMsg.isNotBlank()) {
        Text(savedMsg, style = MaterialTheme.typography.bodySmall, color = Color(0xFF10B981), fontWeight = FontWeight.SemiBold)
    }

    Button(
        onClick = {
            onSave(draft)
            savedMsg = "Pengaturan AFK & Guard tersimpan."
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        Text("Simpan", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PlayerSchedulerTab(
    schedules: List<MacroSchedule>,
    runnableMacros: List<Macro>,
    onAdd: () -> Unit,
    onEdit: (MacroSchedule) -> Unit,
    onToggle: (MacroSchedule, Boolean) -> Unit,
    onDelete: (MacroSchedule) -> Unit
) {
    val nowMs = remember(schedules) { System.currentTimeMillis() }

    FilledTonalButton(
        onClick = onAdd,
        enabled = runnableMacros.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (runnableMacros.isEmpty()) "Impor Macro .wjm Dulu" else "Buat Jadwal Baru", fontWeight = FontWeight.Bold)
    }

    if (schedules.isEmpty()) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
            Text(
                "Belum ada jadwal otomatis. Tambahkan jadwal agar macro .wjm jalan otomatis sesuai jam pilihan Anda.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(14.dp)
            )
        }
    } else {
        schedules.forEach { s ->
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f)) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${MacroScheduleCalculator.formatTime(s.hour, s.minute)} • ${MacroScheduleCalculator.repeatSummary(s)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (s.enabled) Color(0xFF818CF8) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                s.macroName.ifBlank { "Macro .wjm" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Berikutnya: ${MacroScheduleCalculator.formatNextTrigger(s, nowMs)}" +
                                    (if (s.stopAfterMinutes > 0) " • Stop ${s.stopAfterMinutes}m" else "") +
                                    (if (s.enableBlackScreenOnStart) " • 🌙 AFK" else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = s.enabled, onCheckedChange = { onToggle(s, it) })
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
private fun PlayerScheduleDialog(
    initial: MacroSchedule,
    runnableMacros: List<Macro>,
    onSave: (MacroSchedule) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var hourText by remember(initial.id, initial.hour) { mutableStateOf(initial.hour.toString().padStart(2, '0')) }
    var minText by remember(initial.id, initial.minute) { mutableStateOf(initial.minute.toString().padStart(2, '0')) }
    var stopText by remember(initial.id, initial.stopAfterMinutes) { mutableStateOf(initial.stopAfterMinutes.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Atur Jadwal Macro") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Pilih Macro .wjm", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    runnableMacros.forEach { m ->
                        FilterChip(
                            selected = draft.macroId == m.id,
                            onClick = { draft = draft.copy(macroId = m.id, macroName = m.name) },
                            label = { Text(m.name.take(22)) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = hourText,
                        onValueChange = { raw ->
                            val clean = raw.filter { it.isDigit() }.take(2)
                            hourText = clean
                            clean.toIntOrNull()?.let { draft = draft.copy(hour = it.coerceIn(0, 23)) }
                        },
                        label = { Text("Jam") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Text(":", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = minText,
                        onValueChange = { raw ->
                            val clean = raw.filter { it.isDigit() }.take(2)
                            minText = clean
                            clean.toIntOrNull()?.let { draft = draft.copy(minute = it.coerceIn(0, 59)) }
                        },
                        label = { Text("Menit") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
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
                            val sel = day in draft.daysOfWeek
                            FilterChip(
                                selected = sel,
                                onClick = {
                                    draft = draft.copy(
                                        daysOfWeek = if (sel) draft.daysOfWeek - day else (draft.daysOfWeek + day).sorted()
                                    )
                                },
                                label = { Text(MacroScheduleCalculator.dayShortLabel(day)) }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = stopText,
                    onValueChange = { raw ->
                        val clean = raw.filter { it.isDigit() }.take(4)
                        stopText = clean
                        draft = draft.copy(stopAfterMinutes = (clean.toIntOrNull() ?: 0).coerceIn(0, 1440))
                    },
                    label = { Text("Stop setelah (menit, 0 = normal)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                PlayerAfkRow("Aktifkan Layar Hitam AFK (🌙) saat mulai", draft.enableBlackScreenOnStart) {
                    draft = draft.copy(enableBlackScreenOnStart = it)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val m = runnableMacros.firstOrNull { it.id == draft.macroId } ?: runnableMacros.firstOrNull()
                    if (m != null) onSave(draft.copy(macroId = m.id, macroName = m.name, lastTriggeredAtMs = 0L))
                },
                enabled = runnableMacros.isNotEmpty()
            ) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}

@Composable
private fun PlayerAfkRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
