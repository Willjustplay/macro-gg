package com.example.ui

import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.AccountFailBehavior
import com.example.data.AccountProfile
import com.example.data.AccountRotationMode
import com.example.data.AccountSwitchConfig
import com.example.data.AccountSwitchMethod
import com.example.data.AccountTriggerMode
import com.example.data.ImageAsset
import com.example.data.Macro
import com.example.data.SelectAccountAction
import com.example.data.WaitAction
import com.example.data.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ACCOUNT_CYAN = Color(0xFF0EA5E9)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MacroAccountSwitchCard(
    macro: Macro,
    images: List<ImageAsset>,
    onUpdateMacro: (Macro) -> Unit,
    onEditCreatedAction: (com.example.data.Action) -> Unit = {},
    maxFreeAccounts: Int? = null,
    isPremium: Boolean = true,
    onRequirePremium: (() -> Unit)? = null
) {
    val cfg = macro.accountSwitch
    var loopsText by remember(macro.id, cfg.loopsPerAccount) { mutableStateOf(cfg.loopsPerAccount.toString()) }
    var minutesText by remember(macro.id, cfg.minutesPerAccount) { mutableStateOf(cfg.minutesPerAccount.toString()) }
    var editingAccount by remember { mutableStateOf<AccountProfile?>(null) }
    var showCreateAccount by remember { mutableStateOf(false) }

    fun updateCfg(block: (AccountSwitchConfig) -> AccountSwitchConfig) {
        onUpdateMacro(macro.copy(accountSwitch = block(macro.accountSwitch)))
    }

    val freeLimitReached = maxFreeAccounts != null && !isPremium && cfg.accounts.size >= maxFreeAccounts

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Master Watchdog-style Multi-Account Config Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = ACCOUNT_CYAN.copy(alpha = 0.09f),
            border = BorderStroke(1.dp, ACCOUNT_CYAN.copy(alpha = 0.45f))
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
                            Icons.Filled.SwitchAccount,
                            contentDescription = null,
                            tint = ACCOUNT_CYAN,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                "Ganti Akun Universal (Semua Game)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = ACCOUNT_CYAN
                            )
                            Text(
                                "Rotasi banyak akun otomatis via OCR Daftar Akun + Auto-Scroll atau Ganti Clone App. Daftar akun pribadi TIDAK ikut diekspor ke .zip/.wjm.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = cfg.enabled,
                        onCheckedChange = { on -> updateCfg { it.copy(enabled = on) } }
                    )
                }

                // Method selector
                Text("Metode Ganti Akun", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccountSwitchMethod.entries.forEach { m ->
                        FilterChip(
                            selected = cfg.method == m,
                            onClick = { updateCfg { it.copy(method = m) } },
                            label = { Text(m.shortLabel, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Text(
                    cfg.method.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Trigger mode
                Text("Kapan Otomatis Pindah Akun?", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccountTriggerMode.entries.forEach { tm ->
                        FilterChip(
                            selected = cfg.triggerMode == tm,
                            onClick = { updateCfg { it.copy(triggerMode = tm) } },
                            label = { Text(tm.shortLabel, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Text(
                    cfg.triggerMode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (cfg.triggerMode != AccountTriggerMode.MANUAL_ACTION_ONLY) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (cfg.triggerMode == AccountTriggerMode.AFTER_LOOPS || cfg.triggerMode == AccountTriggerMode.LOOPS_OR_MINUTES) {
                            OutlinedTextField(
                                value = loopsText,
                                onValueChange = { raw ->
                                    val digits = raw.filter(Char::isDigit).take(4)
                                    loopsText = digits
                                    digits.toIntOrNull()?.let { v ->
                                        updateCfg { it.copy(loopsPerAccount = v.coerceIn(1, 9999)) }
                                    }
                                },
                                label = { Text("Loop / akun") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (cfg.triggerMode == AccountTriggerMode.AFTER_MINUTES || cfg.triggerMode == AccountTriggerMode.LOOPS_OR_MINUTES) {
                            OutlinedTextField(
                                value = minutesText,
                                onValueChange = { raw ->
                                    val digits = raw.filter(Char::isDigit).take(4)
                                    minutesText = digits
                                    digits.toIntOrNull()?.let { v ->
                                        updateCfg { it.copy(minutesPerAccount = v.coerceIn(1, 1440)) }
                                    }
                                },
                                label = { Text("Menit / akun") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Rotation order & failure policy
                Text("Urutan Rotasi & Jika Gagal", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccountRotationMode.entries.forEach { rm ->
                        FilterChip(
                            selected = cfg.rotationMode == rm,
                            onClick = { updateCfg { it.copy(rotationMode = rm) } },
                            label = { Text(rm.shortLabel, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccountFailBehavior.entries.forEach { fb ->
                        FilterChip(
                            selected = cfg.onFail == fb,
                            onClick = { updateCfg { it.copy(onFail = fb) } },
                            label = { Text("Gagal: ${fb.shortLabel}", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Ulangi Jalur Utama dari awal setelah pindah akun",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = cfg.restartMainAfterSwitch,
                        onCheckedChange = { on -> updateCfg { it.copy(restartMainAfterSwitch = on) } }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Jalankan Alur Ganti Akun saat macro baru dimulai",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = cfg.switchOnStart,
                        onCheckedChange = { on -> updateCfg { it.copy(switchOnStart = on) } }
                    )
                }
            }
        }

        // Account Profiles Queue Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Daftar Profil Akun (${cfg.accounts.size})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (maxFreeAccounts != null && !isPremium) {
                            Text(
                                "Gratis: ${cfg.accounts.size}/$maxFreeAccounts akun • PRO: Tanpa Batas",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFF59E0B)
                            )
                        } else {
                            Text(
                                "Antrean akun yang diputar otomatis (privasi aman, tidak ikut ekspor).",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Button(
                        onClick = {
                            if (freeLimitReached) {
                                onRequirePremium?.invoke()
                            } else {
                                showCreateAccount = true
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (freeLimitReached) Color(0xFFD97706) else ACCOUNT_CYAN
                        )
                    ) {
                        Icon(
                            if (freeLimitReached) Icons.Filled.Lock else Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (freeLimitReached) "Akun (PRO)" else "Akun", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (cfg.accounts.isEmpty()) {
                    Text(
                        "Belum ada profil akun. Ketuk '+ Akun' untuk menambahkan nama/email OCR atau pilih Clone App.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    cfg.accounts.forEachIndexed { idx, acc ->
                        val isLastActive = cfg.lastAccountId == acc.id
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isLastActive) ACCOUNT_CYAN.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                1.dp,
                                if (isLastActive) ACCOUNT_CYAN.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { editingAccount = acc }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${idx + 1}. ${acc.label.ifBlank { "Akun ${idx + 1}" }}" + if (isLastActive) " • Terakhir" else "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sub = buildList {
                                        if (acc.ocrKeyword.isNotBlank()) add("OCR: \"${acc.ocrKeyword}\"")
                                        if (acc.packageName.isNotBlank()) add("App: ${acc.appLabel.ifBlank { acc.packageName }}")
                                        if (acc.fallbackImageName.isNotBlank()) add("🖼 Fallback")
                                        if (acc.cooldownMinutes > 0) add("⏳ ${acc.cooldownMinutes}m")
                                    }.joinToString(" • ").ifBlank { "Ketuk untuk lengkapi kata kunci OCR / Clone App" }
                                    Text(
                                        sub,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (idx > 0) {
                                    IconButton(
                                        onClick = {
                                            val list = cfg.accounts.toMutableList()
                                            val tmp = list[idx - 1]
                                            list[idx - 1] = list[idx]
                                            list[idx] = tmp
                                            updateCfg { it.copy(accounts = list) }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Naik", modifier = Modifier.size(15.dp))
                                    }
                                }
                                if (idx < cfg.accounts.lastIndex) {
                                    IconButton(
                                        onClick = {
                                            val list = cfg.accounts.toMutableList()
                                            val tmp = list[idx + 1]
                                            list[idx + 1] = list[idx]
                                            list[idx] = tmp
                                            updateCfg { it.copy(accounts = list) }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Filled.ArrowDownward, contentDescription = "Turun", modifier = Modifier.size(15.dp))
                                    }
                                }
                                Switch(
                                    checked = acc.enabled,
                                    onCheckedChange = { on ->
                                        updateCfg { c ->
                                            c.copy(accounts = c.accounts.map { if (it.id == acc.id) it.copy(enabled = on) else it })
                                        }
                                    }
                                )
                                IconButton(
                                    onClick = { editingAccount = acc },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp))
                                }
                                IconButton(
                                    onClick = {
                                        updateCfg { c -> c.copy(accounts = c.accounts.filterNot { it.id == acc.id }) }
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.DeleteOutline,
                                        contentDescription = "Hapus",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Reusable Switch Flow Section + 1-Click Templates
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
            border = BorderStroke(1.dp, ACCOUNT_CYAN.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Filled.ManageAccounts, contentDescription = null, tint = ACCOUNT_CYAN, modifier = Modifier.size(18.dp))
                    Text(
                        "Alur Ganti Akun (${cfg.switchActions.size} aksi)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = ACCOUNT_CYAN
                    )
                }
                Text(
                    "Susun 1× langkah ganti akun sesuai game Anda (mis. Klik Setting → Klik Switch → Pilih / Buka Akun Giliran → Tunggu Loading). Jika dibiarkan kosong, engine otomatis menjalankan 'Pilih / Buka Akun Giliran'.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(
                        onClick = {
                            val sel = SelectAccountAction(methodOverride = AccountSwitchMethod.OCR_LIST, delayAfterMs = 1500L)
                            val tpl = listOf(sel, WaitAction(durationMs = 6000L, delayAfterMs = 0L))
                            updateCfg { it.copy(enabled = true, method = AccountSwitchMethod.OCR_LIST, switchActions = it.switchActions + tpl) }
                            onEditCreatedAction(sel)
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Template OCR + Scroll", style = MaterialTheme.typography.labelSmall)
                    }
                    FilledTonalButton(
                        onClick = {
                            val sel = SelectAccountAction(methodOverride = AccountSwitchMethod.CLONE_APP, delayAfterMs = 1500L)
                            val tpl = listOf(sel, WaitAction(durationMs = 8000L, delayAfterMs = 0L))
                            updateCfg { it.copy(enabled = true, method = AccountSwitchMethod.CLONE_APP, switchActions = it.switchActions + tpl) }
                            onEditCreatedAction(sel)
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Template Clone App", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = {
                            val sel = SelectAccountAction(methodOverride = AccountSwitchMethod.HYBRID, delayAfterMs = 1500L)
                            val tpl = listOf(sel, WaitAction(durationMs = 6000L, delayAfterMs = 0L))
                            updateCfg { it.copy(enabled = true, method = AccountSwitchMethod.HYBRID, switchActions = it.switchActions + tpl) }
                            onEditCreatedAction(sel)
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Template Hybrid", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    if (showCreateAccount || editingAccount != null) {
        val target = editingAccount
        AccountProfileEditorDialog(
            initial = target ?: AccountProfile(
                id = newId(),
                label = "Akun ${cfg.accounts.size + 1}"
            ),
            method = cfg.method,
            images = images,
            isEdit = target != null,
            onConfirm = { saved ->
                if (target == null) {
                    updateCfg { it.copy(enabled = true, accounts = it.accounts + saved) }
                } else {
                    updateCfg { c -> c.copy(accounts = c.accounts.map { if (it.id == target.id) saved else it }) }
                }
                showCreateAccount = false
                editingAccount = null
            },
            onDismiss = {
                showCreateAccount = false
                editingAccount = null
            }
        )
    }
}

@Composable
internal fun AccountProfileEditorDialog(
    initial: AccountProfile,
    method: AccountSwitchMethod,
    images: List<ImageAsset>,
    isEdit: Boolean,
    onConfirm: (AccountProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var label by remember(initial.id) { mutableStateOf(initial.label) }
    var ocrKeyword by remember(initial.id) { mutableStateOf(initial.ocrKeyword) }
    var fallbackImg by remember(initial.id) { mutableStateOf(initial.fallbackImageName) }
    var packageName by remember(initial.id) { mutableStateOf(initial.packageName) }
    var appLabel by remember(initial.id) { mutableStateOf(initial.appLabel) }
    var cooldownText by remember(initial.id) { mutableStateOf(initial.cooldownMinutes.toString()) }
    var enabled by remember(initial.id) { mutableStateOf(initial.enabled) }
    var showAppPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isEdit) "Edit Profil Akun" else "Tambah Profil Akun",
                style = MaterialTheme.typography.titleMedium,
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
                    value = label,
                    onValueChange = { label = it.take(40) },
                    label = { Text("Nama Label Akun") },
                    placeholder = { Text("mis. Akun Utama / Farm 2") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (method != AccountSwitchMethod.CLONE_APP) {
                    OutlinedTextField(
                        value = ocrKeyword,
                        onValueChange = { ocrKeyword = it.take(80) },
                        label = { Text("Kata Kunci Teks di Layar (OCR)") },
                        placeholder = { Text("mis. farm2@gmail.com atau Nickname") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Potongan unik nama/email yang terlihat di daftar akun game agar otomatis ditemukan OCR + Auto-Scroll.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (method != AccountSwitchMethod.OCR_LIST) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAppPicker = true }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("Aplikasi Clone / Game", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (packageName.isBlank()) "Belum dipilih — ketuk untuk pilih"
                                    else "${appLabel.ifBlank { packageName }} ($packageName)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            FilledTonalButton(
                                onClick = { showAppPicker = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Filled.Apps, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Pilih", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = packageName,
                        onValueChange = { packageName = it.trim() },
                        label = { Text("Package Name (opsional isi manual)") },
                        placeholder = { Text("mis. com.supercell.clashofclans") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (method != AccountSwitchMethod.CLONE_APP && images.isNotEmpty()) {
                    Text("Gambar Cadangan (Opsional jika font game sulit dibaca OCR)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item(key = "fb_none") {
                            FilterChip(
                                selected = fallbackImg.isBlank(),
                                onClick = { fallbackImg = "" },
                                label = { Text("Tanpa Gambar", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                        items(images, key = { "fb_${it.name}" }) { asset ->
                            val isSel = fallbackImg == asset.name
                            val bmp = rememberAssetThumbnail(asset.path, asset.updatedAt)
                            Column(
                                modifier = Modifier
                                    .width(66.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(
                                        width = if (isSel) 2.dp else 1.dp,
                                        color = if (isSel) ACCOUNT_CYAN else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { fallbackImg = if (isSel) "" else asset.name }
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF0A1020)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (bmp != null) {
                                        Image(bitmap = bmp, contentDescription = asset.displayName, contentScale = ContentScale.Fit, modifier = Modifier.size(40.dp))
                                    }
                                }
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

                OutlinedTextField(
                    value = cooldownText,
                    onValueChange = { cooldownText = it.filter(Char::isDigit).take(4) },
                    label = { Text("Cooldown setelah selesai (menit, 0 = off)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Akun Aktif dalam Rotasi", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanLabel = label.trim().ifBlank { ocrKeyword.trim().ifBlank { appLabel.trim().ifBlank { "Akun" } } }
                    onConfirm(
                        initial.copy(
                            label = cleanLabel,
                            ocrKeyword = ocrKeyword.trim(),
                            fallbackImageName = fallbackImg.trim(),
                            packageName = packageName.trim(),
                            appLabel = appLabel.trim(),
                            cooldownMinutes = (cooldownText.toIntOrNull() ?: 0).coerceIn(0, 10080),
                            enabled = enabled
                        )
                    )
                }
            ) { Text("Simpan") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        }
    )

    if (showAppPicker) {
        InstalledCloneAppPickerDialog(
            currentPackage = packageName,
            onSelect = { pkg, lbl ->
                packageName = pkg
                appLabel = lbl
                if (label.startsWith("Akun ")) label = lbl
                showAppPicker = false
            },
            onDismiss = { showAppPicker = false }
        )
    }
}

@Composable
internal fun InstalledCloneAppPickerDialog(
    currentPackage: String,
    onSelect: (packageName: String, appLabel: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                    .map { ri ->
                        val pkg = ri.activityInfo.packageName
                        val lbl = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
                        pkg to lbl
                    }
                    .distinctBy { it.first }
                    .sortedBy { it.second.lowercase() }
            }.getOrDefault(emptyList())
        }
    }

    val filtered = remember(apps, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) apps else apps.filter { (pkg, lbl) ->
            lbl.lowercase().contains(q) || pkg.lowercase().contains(q)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pilih Aplikasi Game / Clone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Cari nama aplikasi / package...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filtered, key = { it.first }) { (pkg, lbl) ->
                        val isSel = pkg == currentPackage
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelect(pkg, lbl) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) ACCOUNT_CYAN.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                Text(lbl, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
