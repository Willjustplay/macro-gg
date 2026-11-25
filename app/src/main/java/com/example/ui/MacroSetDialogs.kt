package com.example.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ExpiryUnit
import com.example.data.Macro
import com.example.data.MacroSecurity
import com.example.data.MacroSet
import com.example.data.countActions

val MACRO_SET_ACCENT_PALETTE: List<String> = listOf(
    "#22D3EE", // Cyan
    "#10B981", // Emerald
    "#A78BFA", // Violet
    "#F59E0B", // Amber
    "#F43F5E", // Rose
    "#3B82F6"  // Blue
)

fun parseSetAccentColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex.ifBlank { "#22D3EE" })) }
        .getOrElse { Color(0xFF22D3EE) }

/**
 * Dialog to create a new Macro Set (optionally selecting initial macros right away)
 * or edit an existing Macro Set's name, description, and accent color.
 */
@Composable
fun CreateOrEditMacroSetDialog(
    existingSet: MacroSet? = null,
    allMacros: List<Macro> = emptyList(),
    allSets: List<MacroSet> = emptyList(),
    preselectedMacroIds: Set<String> = emptySet(),
    onConfirm: (name: String, description: String, colorHex: String, selectedMemberIds: Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val isEdit = existingSet != null
    var name by remember(existingSet?.id) { mutableStateOf(existingSet?.name ?: "") }
    var description by remember(existingSet?.id) { mutableStateOf(existingSet?.description ?: "") }
    var colorHex by remember(existingSet?.id) {
        mutableStateOf(existingSet?.colorHex?.ifBlank { "#22D3EE" } ?: "#22D3EE")
    }
    var selectedIds by remember(existingSet?.id, preselectedMacroIds) { mutableStateOf(preselectedMacroIds) }
    val setById = remember(allSets) { allSets.associateBy { it.id } }

    // Eligible macros for quick grouping when creating a new set (skip macros inside another locked set)
    val selectableMacros = remember(allMacros, setById, isEdit) {
        if (isEdit) emptyList()
        else allMacros.filter { m ->
            val owner = setById[m.setId]
            owner == null || !owner.isLocked
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = parseSetAccentColor(colorHex)
            )
        },
        title = {
            Text(
                if (isEdit) "Edit Macro Set" else "Buat Macro Set Baru",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(48) },
                    label = { Text("Nama Macro Set") },
                    placeholder = { Text("Contoh: COC TH16 Farm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(120) },
                    label = { Text("Catatan / Deskripsi (opsional)") },
                    placeholder = { Text("1 Macro Aktif + 4 Sub-Macro Pasif") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Warna Label Set",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MACRO_SET_ACCENT_PALETTE.forEach { hex ->
                            val tint = parseSetAccentColor(hex)
                            val chosen = colorHex.equals(hex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(tint)
                                    .border(
                                        width = if (chosen) 2.5.dp else 1.dp,
                                        color = if (chosen) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { colorHex = hex },
                                contentAlignment = Alignment.Center
                            ) {
                                if (chosen) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (!isEdit && selectableMacros.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                    Text(
                        "Masukkan Macro ke Set ini (${selectedIds.size} dipilih):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        selectableMacros.take(12).forEach { m ->
                            val checked = m.id in selectedIds
                            val curSetName = setById[m.setId]?.name
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedIds = if (checked) selectedIds - m.id else selectedIds + m.id
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selectedIds = if (it) selectedIds + m.id else selectedIds - m.id
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = (if (m.isPassive) "🔗 " else "") + m.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (!curSetName.isNullOrBlank()) {
                                        Text(
                                            text = "Saat ini di Set: $curSetName",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        if (selectableMacros.size > 12) {
                            Text(
                                "Macro lainnya bisa ditambahkan kapan saja lewat menu 'Kelola Isi Set'.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim().ifBlank { "Set Baru" }, description.trim(), colorHex, selectedIds) },
                enabled = name.isNotBlank()
            ) {
                Text(if (isEdit) "Simpan" else "Buat Set")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Batal") }
        }
    )
}

/**
 * Bottom sheet to add or remove member macros inside a Macro Set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageMacroSetMembersSheet(
    set: MacroSet,
    allMacros: List<Macro>,
    allSets: List<MacroSet>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val setById = remember(allSets) { allSets.associateBy { it.id } }
    val initialSelected = remember(set.id, allMacros) {
        allMacros.filter { it.setId == set.id }.map { it.id }.toSet()
    }
    var selectedIds by remember(set.id) { mutableStateOf(initialSelected) }
    val accent = parseSetAccentColor(set.colorHex)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = accent)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Kelola Isi Macro Set", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${set.name} • ${selectedIds.size} macro dipilih",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (MacroSecurity.hasAnySetProtection(set)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFA78BFA).copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, Color(0xFFA78BFA).copy(alpha = 0.45f))
                ) {
                    Text(
                        text = "🔒 Set ini memiliki kunci/tenggat aktif. Macro baru yang dimasukkan akan otomatis ikut dikunci & disinkronkan dengan proteksi Set.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            HorizontalDivider()

            if (allMacros.isEmpty()) {
                Text(
                    "Belum ada macro yang tersimpan.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(allMacros, key = { it.id }) { m ->
                        val checked = m.id in selectedIds
                        val otherSet = if (m.setId.isNotBlank() && m.setId != set.id) setById[m.setId] else null
                        val lockedInOtherSet = otherSet != null && otherSet.isLocked
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (checked) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(
                                1.dp,
                                if (checked) accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !lockedInOtherSet) {
                                    selectedIds = if (checked) selectedIds - m.id else selectedIds + m.id
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    enabled = !lockedInOtherSet,
                                    onCheckedChange = {
                                        if (!lockedInOtherSet) {
                                            selectedIds = if (it) selectedIds + m.id else selectedIds - m.id
                                        }
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    val prefix = buildString {
                                        if (m.isLocked) append("🔒 ")
                                        if (m.isPassive) append(if (m.passiveEnabled) "🔗 " else "⛔ ")
                                    }
                                    Text(
                                        text = prefix + m.name.ifBlank { "Macro Baru" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sub = buildString {
                                        append("${m.countActions()} aksi")
                                        if (m.isPassive) append(" • Pasif")
                                        if (otherSet != null) {
                                            append(if (lockedInOtherSet) " • 🔒 di Set '${otherSet.name}'" else " • di Set '${otherSet.name}'")
                                        }
                                    }
                                    Text(
                                        text = sub,
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

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Batal") }
                Button(
                    onClick = { onSave(selectedIds) },
                    modifier = Modifier.weight(1.4f)
                ) {
                    Text("Simpan Isi Set")
                }
            }
        }
    }
}

private enum class SetExpiryEditMode {
    KEEP,
    EXTEND,
    RESET
}

/**
 * Security & Lock bottom sheet for a Macro Set:
 * - Lock entire Macro Set & all member macros with a single password
 * - Set usage deadline in Days or Months (Keep / Extend / Reset), applied to the Set & all member macros
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MacroSetSecuritySheet(
    set: MacroSet,
    memberCount: Int,
    nowMs: Long,
    onSave: (MacroSet) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val alreadyHasPassword = MacroSecurity.hasSetPassword(set)
    val hasActiveExistingExpiry = set.expiresAtEpochMs > 0L && !MacroSecurity.isSetExpired(set, nowMs)

    var isLocked by remember(set.id) { mutableStateOf(set.isLocked) }
    var useExpiry by remember(set.id) { mutableStateOf(set.expiresAtEpochMs > 0L) }
    var expiryEditMode by remember(set.id) {
        mutableStateOf(if (hasActiveExistingExpiry) SetExpiryEditMode.KEEP else SetExpiryEditMode.RESET)
    }
    var expiryAmountText by remember(set.id) {
        mutableStateOf(if (set.expiryDurationAmount > 0) set.expiryDurationAmount.toString() else "7")
    }
    var expiryUnit by remember(set.id) { mutableStateOf(set.expiryDurationUnit) }

    var passwordInput by remember(set.id) { mutableStateOf("") }
    var confirmPassword by remember(set.id) { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val anyProtection = isLocked || useExpiry
    val parsedAmount = (expiryAmountText.toIntOrNull() ?: 1).coerceIn(1, if (expiryUnit == ExpiryUnit.DAYS) 3650 else 120)
    val previewDeadlineMs = remember(useExpiry, expiryEditMode, parsedAmount, expiryUnit, set.expiresAtEpochMs, nowMs) {
        when {
            !useExpiry -> 0L
            expiryEditMode == SetExpiryEditMode.KEEP && hasActiveExistingExpiry -> set.expiresAtEpochMs
            expiryEditMode == SetExpiryEditMode.EXTEND && hasActiveExistingExpiry ->
                MacroSecurity.computeExpiryEpochMs(set.expiresAtEpochMs, parsedAmount, expiryUnit)
            else -> MacroSecurity.computeExpiryEpochMs(nowMs, parsedAmount, expiryUnit)
        }
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFA78BFA).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = Color(0xFFA78BFA))
                }
                Column {
                    Text("Kunci & Tenggat Macro Set", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${set.name} • $memberCount macro di dalam set",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider()

            // 1. Lock Set toggle
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f).padding(end = 10.dp)) {
                        Text("Kunci Seluruh Macro Set dengan Sandi", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Mengunci Set & semua macro di dalamnya agar tidak bisa diedit, dilihat aksinya, diubah anggotanya, atau diduplikat tanpa sandi. Auto-Scale & Pasif ON/OFF tetap bebas tanpa sandi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = isLocked, onCheckedChange = { isLocked = it; errorMsg = null })
                }
            }

            // 2. Expiration Deadline for Set
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
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Icon(Icons.Filled.Schedule, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(16.dp))
                                Text("Tenggat Waktu Set", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                            Text(
                                "Berlaku untuk seluruh macro di dalam Set ini. Saat Set yang sama ditimpa via impor, sisa waktu otomatis ditambahkan.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = useExpiry,
                            onCheckedChange = {
                                useExpiry = it
                                if (it && !hasActiveExistingExpiry) {
                                    expiryEditMode = SetExpiryEditMode.RESET
                                }
                                errorMsg = null
                            }
                        )
                    }

                    if (useExpiry) {
                        if (hasActiveExistingExpiry) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = expiryEditMode == SetExpiryEditMode.KEEP,
                                    onClick = { expiryEditMode = SetExpiryEditMode.KEEP },
                                    label = { Text("Pertahankan", style = MaterialTheme.typography.labelSmall) }
                                )
                                FilterChip(
                                    selected = expiryEditMode == SetExpiryEditMode.EXTEND,
                                    onClick = { expiryEditMode = SetExpiryEditMode.EXTEND },
                                    label = { Text("+ Tambah Waktu", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold) }
                                )
                                FilterChip(
                                    selected = expiryEditMode == SetExpiryEditMode.RESET,
                                    onClick = { expiryEditMode = SetExpiryEditMode.RESET },
                                    label = { Text("Atur Ulang", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }

                        if (expiryEditMode != SetExpiryEditMode.KEEP) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = expiryAmountText,
                                    onValueChange = { expiryAmountText = it.filter(Char::isDigit).take(4) },
                                    label = { Text(if (expiryEditMode == SetExpiryEditMode.EXTEND) "Tambah durasi" else "Durasi") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ExpiryUnit.entries.forEach { u ->
                                        FilterChip(
                                            selected = expiryUnit == u,
                                            onClick = { expiryUnit = u },
                                            label = { Text(u.label, fontWeight = FontWeight.SemiBold) }
                                        )
                                    }
                                }
                            }

                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    1 to ExpiryUnit.DAYS,
                                    7 to ExpiryUnit.DAYS,
                                    30 to ExpiryUnit.DAYS,
                                    1 to ExpiryUnit.MONTHS,
                                    3 to ExpiryUnit.MONTHS,
                                    12 to ExpiryUnit.MONTHS
                                ).forEach { (amt, u) ->
                                    val prefix = if (expiryEditMode == SetExpiryEditMode.EXTEND) "+" else ""
                                    FilterChip(
                                        selected = parsedAmount == amt && expiryUnit == u,
                                        onClick = {
                                            expiryAmountText = amt.toString()
                                            expiryUnit = u
                                        },
                                        label = { Text("$prefix$amt ${u.label}", style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                if (expiryEditMode == SetExpiryEditMode.EXTEND && hasActiveExistingExpiry) {
                                    Text(
                                        text = "Saat ini: ${MacroSecurity.formatDeadlineDate(set.expiresAtEpochMs)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = if (expiryEditMode == SetExpiryEditMode.EXTEND && hasActiveExistingExpiry) {
                                        "Setelah +$parsedAmount ${expiryUnit.label}: ${MacroSecurity.formatDeadlineDate(previewDeadlineMs)}"
                                    } else {
                                        "Berakhir pada: ${MacroSecurity.formatDeadlineDate(previewDeadlineMs)}"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // 3. Password section
            if (anyProtection) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (alreadyHasPassword) "Sandi Set (kosongkan jika tidak ingin mengubah)"
                        else "Buat Sandi Macro Set (wajib)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it; errorMsg = null },
                        label = { Text(if (alreadyHasPassword) "Sandi baru (opsional)" else "Sandi (min. 4 karakter)") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!alreadyHasPassword || passwordInput.isNotEmpty()) {
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it; errorMsg = null },
                            label = { Text("Konfirmasi sandi") },
                            singleLine = true,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            errorMsg?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (MacroSecurity.hasAnySetProtection(set)) {
                TextButton(
                    onClick = {
                        isLocked = false
                        useExpiry = false
                        errorMsg = null
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Matikan Semua Proteksi Set", color = MaterialTheme.colorScheme.error)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Batal") }
                Button(
                    onClick = {
                        if (anyProtection) {
                            val settingNewPass = !alreadyHasPassword || passwordInput.isNotEmpty()
                            if (settingNewPass) {
                                if (passwordInput.length < 4) {
                                    errorMsg = "Sandi minimal 4 karakter."
                                    return@Button
                                }
                                if (passwordInput != confirmPassword) {
                                    errorMsg = "Konfirmasi sandi tidak cocok."
                                    return@Button
                                }
                            }
                        }
                        val expLabel = when {
                            !useExpiry -> ""
                            expiryEditMode == SetExpiryEditMode.KEEP -> set.expiryLabel.ifBlank { MacroSecurity.formatDeadlineDate(previewDeadlineMs) }
                            expiryEditMode == SetExpiryEditMode.EXTEND -> "+${MacroSecurity.formatDurationLabel(parsedAmount, expiryUnit)}"
                            else -> MacroSecurity.formatDurationLabel(parsedAmount, expiryUnit)
                        }
                        val durAmount = when {
                            !useExpiry -> 0
                            expiryEditMode == SetExpiryEditMode.KEEP -> set.expiryDurationAmount
                            else -> parsedAmount
                        }
                        val durUnit = when {
                            !useExpiry -> ExpiryUnit.DAYS
                            expiryEditMode == SetExpiryEditMode.KEEP -> set.expiryDurationUnit
                            else -> expiryUnit
                        }
                        val updated = MacroSecurity.applySetSecurity(
                            set = set,
                            isLocked = isLocked,
                            expiresAtEpochMs = previewDeadlineMs,
                            expiryLabel = expLabel,
                            newPassword = passwordInput.takeIf { it.isNotBlank() },
                            expiryDurationAmount = durAmount,
                            expiryDurationUnit = durUnit
                        )
                        onSave(updated)
                    },
                    modifier = Modifier.weight(1.4f)
                ) {
                    Text("Simpan Proteksi Set")
                }
            }
        }
    }
}

/**
 * Dialog to export a whole Macro Set (all member macros, linked sub-macros, logos, and images)
 * as a single portable `.zip` with optional 1× Import protection.
 */
@Composable
fun ExportMacroSetOptionsDialog(
    set: MacroSet,
    memberCount: Int,
    passiveCount: Int,
    preferDownloads: Boolean,
    onExportToPicker: (singleUseImport: Boolean) -> Unit,
    onExportToDownloads: (singleUseImport: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var singleUseImport by remember(set.id) { mutableStateOf(false) }
    val activeCount = (memberCount - passiveCount).coerceAtLeast(0)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (singleUseImport || set.isLocked) Icons.Filled.Lock else Icons.Filled.FileDownload,
                contentDescription = null,
                tint = if (singleUseImport) Color(0xFFF59E0B) else parseSetAccentColor(set.colorHex)
            )
        },
        title = {
            Text(
                "Ekspor Macro Set (.zip)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val breakdown = buildString {
                    append("Set '${set.name}': $memberCount macro")
                    if (memberCount > 0) append(" ($activeCount Aktif, $passiveCount Pasif)")
                    append(" + semua gambar & tautan terkait.")
                }
                Text(
                    breakdown,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (set.isLocked || set.expiresAtEpochMs > 0L) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFA78BFA).copy(alpha = 0.14f)
                    ) {
                        val tags = buildList {
                            if (set.isLocked) add("🔒 Terkunci Sandi")
                            if (set.expiresAtEpochMs > 0L) add("⏳ Tenggat ${set.expiryLabel.ifBlank { MacroSecurity.formatDeadlineDate(set.expiresAtEpochMs) }}")
                        }.joinToString(" • ")
                        Text(
                            text = "Proteksi aktif ikut terbawa: $tags",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFA78BFA),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (singleUseImport) Color(0xFFF59E0B).copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(
                        1.dp,
                        if (singleUseImport) Color(0xFFF59E0B).copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { singleUseImport = !singleUseImport }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(
                                "🔒 Hanya Bisa Impor Sekali (1× Pakai)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "File .zip Set diberi token unik bertanda tangan. Setelah 1× diimpor, file yang sama tidak bisa diimpor ulang.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = singleUseImport,
                            onCheckedChange = { singleUseImport = it }
                        )
                    }
                }
                if (singleUseImport) {
                    Text(
                        "Nama file otomatis ditandai '_set_1x.zip'. Set asli di HP Anda tidak berubah dan tetap bisa diekspor lagi dengan token baru.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFF59E0B)
                    )
                }
            }
        },
        confirmButton = {
            if (preferDownloads) {
                Button(onClick = { onExportToDownloads(singleUseImport) }) {
                    Text("Simpan Download")
                }
            } else {
                Button(onClick = { onExportToPicker(singleUseImport) }) {
                    Text("Pilih Lokasi")
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss) { Text("Batal") }
                if (preferDownloads) {
                    FilledTonalButton(onClick = { onExportToPicker(singleUseImport) }) {
                        Text("Pilih Lokasi")
                    }
                } else {
                    FilledTonalButton(onClick = { onExportToDownloads(singleUseImport) }) {
                        Text("Ke Download")
                    }
                }
            }
        }
    )
}

/**
 * Dialog to move a single macro into any existing Macro Set, back to Ungrouped, or create a new Set.
 */
@Composable
fun MoveMacroToSetDialog(
    macro: Macro,
    macroSets: List<MacroSet>,
    memberCounts: Map<String, Int>,
    onSelectSet: (targetSetId: String) -> Unit,
    onCreateNewSetWithMacro: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Pindahkan ke Macro Set", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Pilih Macro Set tujuan untuk '${macro.name}':",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Ungrouped option
                val isUngrouped = macro.setId.isBlank() || macroSets.none { it.id == macro.setId }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isUngrouped) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = if (isUngrouped) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectSet("") }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Tanpa Set (Umum)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Keluarkan dari kelompok Macro Set", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isUngrouped) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                macroSets.forEach { s ->
                    val chosen = macro.setId == s.id
                    val accent = parseSetAccentColor(s.colorHex)
                    val cnt = memberCounts[s.id] ?: 0
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (chosen) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = if (chosen) BorderStroke(1.dp, accent) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelectSet(s.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(accent.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (s.isLocked) Icons.Filled.Lock else Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "$cnt macro" + if (s.isLocked) " • 🔒 Terkunci (perlu sandi)" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (chosen) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                FilledTonalButton(
                    onClick = onCreateNewSetWithMacro,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Buat Macro Set Baru")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Tutup") }
        }
    )
}

/**
 * Confirmation dialog for deleting a Macro Set (offering choice to keep or also delete member macros).
 */
@Composable
fun DeleteMacroSetDialog(
    set: MacroSet,
    memberCount: Int,
    onDeleteSetOnly: () -> Unit,
    onDeleteSetAndMembers: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Hapus Macro Set?", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (memberCount > 0) {
                        "Macro Set '${set.name}' berisi $memberCount macro. Pilih apakah ingin menghapus Set saja (macro tetap aman) atau sekalian menghapus semua macro di dalamnya."
                    } else {
                        "Hapus Macro Set '${set.name}'?"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            if (memberCount > 0) {
                Button(
                    onClick = onDeleteSetAndMembers,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Hapus Set + $memberCount Macro")
                }
            } else {
                Button(
                    onClick = onDeleteSetOnly,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Hapus Set")
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss) { Text("Batal") }
                if (memberCount > 0) {
                    OutlinedButton(onClick = onDeleteSetOnly) {
                        Text("Hapus Set Saja")
                    }
                }
            }
        }
    )
}
