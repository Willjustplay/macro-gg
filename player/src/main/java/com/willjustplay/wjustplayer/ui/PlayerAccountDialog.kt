package com.willjustplay.wjustplayer.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.example.data.AccountProfile
import com.example.data.AccountSwitchMethod
import com.example.data.AccountTriggerMode
import com.example.data.Macro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PlayerInstalledApp(
    val packageName: String,
    val label: String
)

private fun loadPlayerLaunchableApps(context: Context): List<PlayerInstalledApp> {
    val pm = context.packageManager
    val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val activities = runCatching { pm.queryIntentActivities(query, 0) }.getOrDefault(emptyList())
    val seen = LinkedHashMap<String, PlayerInstalledApp>()
    for (ri in activities) {
        val pkg = ri.activityInfo?.packageName?.trim().orEmpty()
        if (pkg.isBlank() || pkg == context.packageName) continue
        if (pkg in seen) continue
        val label = runCatching { ri.loadLabel(pm).toString().trim() }.getOrDefault("").ifBlank { pkg }
        seen[pkg] = PlayerInstalledApp(packageName = pkg, label = label)
    }
    return seen.values.sortedBy { it.label.lowercase() }
}

/**
 * Play-Only "Atur Akun Saya" dialog for `.wjm` macros that support Multi-Account switching.
 * End-users enter only their own local account identifiers (OCR keywords and/or Clone App packages)
 * while the encrypted macro logic remains completely hidden and unmodified.
 */
@Composable
fun PlayerAccountManagerDialog(
    macro: Macro,
    initialAccounts: List<AccountProfile>,
    onSave: (List<AccountProfile>) -> Unit,
    onDismiss: () -> Unit
) {
    var accounts by remember(macro.id, initialAccounts) { mutableStateOf(initialAccounts) }
    var editingProfile by remember { mutableStateOf<AccountProfile?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }

    val cfg = macro.accountSwitch
    val triggerSummary = when (cfg.triggerMode) {
        AccountTriggerMode.AFTER_LOOPS -> "Pindah tiap ${cfg.loopsPerAccount} loop"
        AccountTriggerMode.AFTER_MINUTES -> "Pindah tiap ${cfg.minutesPerAccount} menit"
        AccountTriggerMode.LOOPS_OR_MINUTES -> "Tiap ${cfg.loopsPerAccount} loop / ${cfg.minutesPerAccount} menit"
        AccountTriggerMode.MANUAL_ACTION_ONLY -> "Pindah otomatis sesuai alur macro"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.Groups,
                contentDescription = null,
                tint = Color(0xFF38BDF8)
            )
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Atur Akun Saya",
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = macro.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF0C223B),
                    border = BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Metode: ${cfg.method.label}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF7DD3FC)
                        )
                        Text(
                            text = "$triggerSummary • ${cfg.rotationMode.shortLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = Color(0xFF34D399),
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Tersimpan lokal di HP Anda & tidak dibagikan.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF6EE7B7)
                            )
                        }
                    }
                }

                if (accounts.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Text(
                            text = when (cfg.method) {
                                AccountSwitchMethod.OCR_LIST ->
                                    "Belum ada akun. Ketuk '+ Tambah Akun' lalu isi nama/email/server akun yang muncul di daftar ganti akun game Anda."
                                AccountSwitchMethod.CLONE_APP ->
                                    "Belum ada akun. Ketuk '+ Tambah Akun' lalu pilih aplikasi clone / dual app untuk masing-masing akun."
                                AccountSwitchMethod.HYBRID ->
                                    "Belum ada akun. Ketuk '+ Tambah Akun' lalu isi kata kunci OCR dan/atau pilih Clone App untuk setiap akun."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(accounts, key = { _, acc -> acc.id }) { index, acc ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (acc.enabled) 0.65f else 0.30f),
                                border = BorderStroke(
                                    1.dp,
                                    if (acc.enabled) Color(0xFF0284C7).copy(alpha = 0.55f)
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            isCreatingNew = false
                                            editingProfile = acc
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = "${index + 1}. ${acc.label}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (acc.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val desc = buildList {
                                            if (acc.ocrKeyword.isNotBlank()) add("OCR: \"${acc.ocrKeyword}\"")
                                            if (acc.packageName.isNotBlank()) add("App: ${acc.appLabel.ifBlank { acc.packageName }}")
                                            if (acc.cooldownMinutes > 0) add("CD ${acc.cooldownMinutes}m")
                                        }.joinToString(" • ").ifBlank { "Ketuk untuk lengkapi target" }
                                        Text(
                                            text = desc,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    if (index > 0) {
                                        IconButton(
                                            onClick = {
                                                val list = accounts.toMutableList()
                                                val tmp = list[index]
                                                list[index] = list[index - 1]
                                                list[index - 1] = tmp
                                                accounts = list
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Filled.ArrowUpward, contentDescription = "Naik", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    if (index < accounts.lastIndex) {
                                        IconButton(
                                            onClick = {
                                                val list = accounts.toMutableList()
                                                val tmp = list[index]
                                                list[index] = list[index + 1]
                                                list[index + 1] = tmp
                                                accounts = list
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Filled.ArrowDownward, contentDescription = "Turun", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            isCreatingNew = false
                                            editingProfile = acc
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Filled.Edit, contentDescription = "Edit", tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(
                                        onClick = { accounts = accounts.filterNot { it.id == acc.id } },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Hapus", tint = Color(0xFFF87171), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                FilledTonalButton(
                    onClick = {
                        isCreatingNew = true
                        editingProfile = AccountProfile(label = "Akun ${accounts.size + 1}")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Tambah Akun", fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(accounts) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF10B981),
                    contentColor = Color(0xFF042018)
                )
            ) {
                Text("Simpan (${accounts.count { it.enabled }})", fontWeight = FontWeight.ExtraBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        }
    )

    editingProfile?.let { current ->
        PlayerSingleAccountEditorDialog(
            initial = current,
            method = cfg.method,
            isNew = isCreatingNew,
            onSave = { updated ->
                accounts = if (isCreatingNew) {
                    accounts + updated
                } else {
                    accounts.map { if (it.id == updated.id) updated else it }
                }
                editingProfile = null
            },
            onDismiss = { editingProfile = null }
        )
    }
}

@Composable
private fun PlayerSingleAccountEditorDialog(
    initial: AccountProfile,
    method: AccountSwitchMethod,
    isNew: Boolean,
    onSave: (AccountProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var label by remember(initial.id) { mutableStateOf(initial.label) }
    var ocrKeyword by remember(initial.id) { mutableStateOf(initial.ocrKeyword) }
    var packageName by remember(initial.id) { mutableStateOf(initial.packageName) }
    var appLabel by remember(initial.id) { mutableStateOf(initial.appLabel) }
    var cooldownStr by remember(initial.id) { mutableStateOf(initial.cooldownMinutes.toString()) }
    var enabled by remember(initial.id) { mutableStateOf(initial.enabled) }
    var showAppPicker by remember { mutableStateOf(false) }

    val showOcr = method != AccountSwitchMethod.CLONE_APP
    val showClone = method != AccountSwitchMethod.OCR_LIST

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Tambah Akun" else "Edit Akun", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(36) },
                    label = { Text("Nama Tampilan Akun") },
                    placeholder = { Text("cth. Farm 1 / Akun Utama") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (showOcr) {
                    OutlinedTextField(
                        value = ocrKeyword,
                        onValueChange = { ocrKeyword = it.take(80) },
                        label = { Text("Kata Kunci OCR di Layar") },
                        placeholder = { Text("Nama / email / server persis di daftar akun game") },
                        supportingText = {
                            Text("Wjust Player akan auto-scroll & klik baris yang mengandung teks ini.")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (showClone) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "Aplikasi Clone / Dual App",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF7DD3FC)
                    )
                    OutlinedButton(
                        onClick = { showAppPicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (packageName.isBlank()) "Pilih Aplikasi Terpasang"
                            else "Ganti: ${appLabel.ifBlank { packageName }}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedTextField(
                        value = packageName,
                        onValueChange = {
                            packageName = it.trim()
                            if (packageName != initial.packageName) appLabel = ""
                        },
                        label = { Text("Package Name Clone App") },
                        placeholder = { Text("com.example.game.clone") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = cooldownStr,
                    onValueChange = { cooldownStr = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Cooldown akun (menit, 0 = tanpa jeda)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Ikutkan dalam rotasi", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanLabel = label.trim().ifBlank { initial.label.ifBlank { "Akun" } }
                    val cd = (cooldownStr.toIntOrNull() ?: 0).coerceIn(0, 1440)
                    onSave(
                        initial.copy(
                            label = cleanLabel,
                            ocrKeyword = ocrKeyword.trim(),
                            packageName = packageName.trim(),
                            appLabel = appLabel.trim(),
                            cooldownMinutes = cd,
                            enabled = enabled
                        )
                    )
                }
            ) { Text("Simpan", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        }
    )

    if (showAppPicker) {
        PlayerInstalledAppPickerDialog(
            onSelect = { info ->
                packageName = info.packageName
                appLabel = info.label
                if (label.isBlank() || label.startsWith("Akun ")) {
                    label = info.label.take(32)
                }
                showAppPicker = false
            },
            onDismiss = { showAppPicker = false }
        )
    }
}

@Composable
private fun PlayerInstalledAppPickerDialog(
    onSelect: (PlayerInstalledApp) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<PlayerInstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadPlayerLaunchableApps(context) }
        loading = false
    }

    val filtered = remember(apps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) apps else apps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pilih Game / Clone App", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Cari nama atau package") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (loading) {
                    Text(
                        "Memuat daftar aplikasi...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (filtered.isEmpty()) {
                    Text(
                        "Tidak ada aplikasi cocok.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        contentPadding = PaddingValues(vertical = 2.dp)
                    ) {
                        itemsIndexed(filtered.take(80), key = { _, app -> app.packageName }) { _, app ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(app) }
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                    Text(
                                        text = app.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = app.packageName,
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
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Tutup") }
        }
    )
}
