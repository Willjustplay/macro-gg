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
import com.example.automation.InstalledAppInfo
import com.example.automation.InstalledAppsHelper
import com.example.automation.PrismClonerBridge
import com.example.automation.PrismProtocol
import com.example.automation.PrismSlotInfo
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
    val context = LocalContext.current
    val cfg = macro.accountSwitch
    var loopsText by remember(macro.id, cfg.loopsPerAccount) { mutableStateOf(cfg.loopsPerAccount.toString()) }
    var minutesText by remember(macro.id, cfg.minutesPerAccount) { mutableStateOf(cfg.minutesPerAccount.toString()) }
    var editingAccount by remember { mutableStateOf<AccountProfile?>(null) }
    var showCreateAccount by remember { mutableStateOf(false) }
    var prismInstalled by remember { mutableStateOf(false) }
    var detectedPrismSlots by remember { mutableStateOf<List<PrismSlotInfo>>(emptyList()) }
    var prismScanTick by remember { mutableStateOf(0) }

    LaunchedEffect(prismScanTick, cfg.method) {
        val (installed, slots) = withContext(Dispatchers.IO) {
            PrismClonerBridge.isPrismInstalled(context) to PrismClonerBridge.discoverInstalledPrismRootSlots(context)
        }
        prismInstalled = installed
        detectedPrismSlots = slots
    }

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
                                "Rotasi banyak akun otomatis via OCR Daftar Akun + Auto-Scroll, Ganti Clone App, atau PRISM Root Cloner (Anti-BAL). Daftar akun pribadi TIDAK ikut diekspor ke .zip/.wjm.",
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

                // Live PRISM Cloner (clone-app) Connection Panel
                if (cfg.method != AccountSwitchMethod.OCR_LIST || prismInstalled) {
                    val statusColor = if (prismInstalled) Color(0xFF10B981) else Color(0xFFF59E0B)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = statusColor.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (prismInstalled) {
                                            "🔗 PRISM Cloner Terhubung ✓ (${detectedPrismSlots.size} slot Root terdeteksi)"
                                        } else {
                                            "🔗 PRISM Cloner (${PrismProtocol.PRISM_HOST_PACKAGE}) belum terpasang"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = statusColor
                                    )
                                    Text(
                                        text = if (prismInstalled) {
                                            "Package buatan app.prism.c.* otomatis dipicu via Broadcast Anti-BAL Root (tanpa error Force Stop / BAL)."
                                        } else {
                                            "Pasang PRISM Cloner (clone-app) untuk ganti akun Root instan tanpa duplikasi data."
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (prismInstalled) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (detectedPrismSlots.isNotEmpty()) {
                                        FilledTonalButton(
                                            onClick = {
                                                val existingPkgs = cfg.accounts.map { it.packageName }.toHashSet()
                                                val candidates = detectedPrismSlots.filter { it.stubPackage !in existingPkgs }
                                                val remainingSlots = if (maxFreeAccounts != null && !isPremium) {
                                                    (maxFreeAccounts - cfg.accounts.size).coerceAtLeast(0)
                                                } else {
                                                    candidates.size
                                                }
                                                if (remainingSlots == 0 && candidates.isNotEmpty()) {
                                                    onRequirePremium?.invoke()
                                                } else {
                                                    val toAdd = candidates.take(remainingSlots).mapIndexed { idx, slot ->
                                                        val orderNum = slot.slotOrder.takeIf { it > 0 } ?: (cfg.accounts.size + idx + 1)
                                                        val cleanLbl = slot.displayLabel.ifBlank { "PRISM #$orderNum" }
                                                        AccountProfile(
                                                            id = newId(),
                                                            label = cleanLbl,
                                                            packageName = slot.stubPackage,
                                                            appLabel = cleanLbl,
                                                            prismTargetPackage = slot.sourcePackage,
                                                            prismSlotOrder = slot.slotOrder,
                                                            prismProfileId = slot.profileId,
                                                            enabled = true
                                                        )
                                                    }
                                                    updateCfg {
                                                        it.copy(
                                                            enabled = true,
                                                            method = if (it.method == AccountSwitchMethod.OCR_LIST) AccountSwitchMethod.PRISM_ROOT else it.method,
                                                            accounts = it.accounts + toAdd
                                                        )
                                                    }
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Impor ${detectedPrismSlots.size} Slot PRISM", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { prismScanTick++ },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Segarkan Deteksi", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }

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
                        if (cfg.method == AccountSwitchMethod.PRISM_ROOT) {
                            "Mode PRISM Root sudah bisa langsung jalan (Switch Next otomatis mengikuti urutan di PRISM Cloner), atau ketuk 'Impor Slot PRISM' / '+ Akun' untuk kontrol slot spesifik."
                        } else {
                            "Belum ada profil akun. Ketuk '+ Akun' untuk menambahkan nama/email OCR atau pilih Clone App / package PRISM."
                        },
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
                                        val isPrismPkg = PrismProtocol.isPrismStubPackageName(acc.packageName) || acc.prismSlotOrder > 0
                                        if (isPrismPkg) {
                                            val slotNum = acc.prismSlotOrder.takeIf { it > 0 } ?: PrismProtocol.parseSlotNumberFromPackage(acc.packageName)
                                            add(if (slotNum > 0) "🔀 PRISM #%02d".format(slotNum) else "🔀 PRISM Root")
                                        }
                                        if (acc.packageName.isNotBlank()) add("App: ${acc.appLabel.ifBlank { acc.packageName }}")
                                        else if (acc.prismTargetPackage.isNotBlank()) add("Game: ${acc.prismTargetPackage}")
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
                            val sel = SelectAccountAction(
                                methodOverride = AccountSwitchMethod.PRISM_ROOT,
                                exitPreviousApp = false,
                                launchWaitMs = 6000L,
                                prismUseSwitchNext = cfg.accounts.isEmpty(),
                                prismTargetPackage = detectedPrismSlots.firstOrNull()?.sourcePackage.orEmpty(),
                                delayAfterMs = 1500L
                            )
                            val tpl = listOf(sel)
                            updateCfg { it.copy(enabled = true, method = AccountSwitchMethod.PRISM_ROOT, switchActions = it.switchActions + tpl) }
                            onEditCreatedAction(sel)
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Template PRISM Root", style = MaterialTheme.typography.labelSmall)
                    }
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
    var prismTargetPackage by remember(initial.id) { mutableStateOf(initial.prismTargetPackage) }
    var prismSlotText by remember(initial.id) {
        mutableStateOf(if (initial.prismSlotOrder > 0) initial.prismSlotOrder.toString() else "")
    }
    var prismProfileId by remember(initial.id) { mutableStateOf(initial.prismProfileId) }
    var cooldownText by remember(initial.id) { mutableStateOf(initial.cooldownMinutes.toString()) }
    var enabled by remember(initial.id) { mutableStateOf(initial.enabled) }
    var showAppPicker by remember { mutableStateOf(false) }

    val usesOcr = method != AccountSwitchMethod.CLONE_APP && method != AccountSwitchMethod.PRISM_ROOT
    val showPrismFields = method == AccountSwitchMethod.PRISM_ROOT || PrismProtocol.isPrismStubPackageName(packageName)

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

                if (usesOcr) {
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
                                Text(
                                    if (method == AccountSwitchMethod.PRISM_ROOT) "Package PRISM / Aplikasi Target"
                                    else "Aplikasi Clone / PRISM / Game",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
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
                        label = { Text("Package Name / Stub PRISM (opsional)") },
                        placeholder = { Text("mis. app.prism.c.p123456.u01") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (showPrismFields) {
                    OutlinedTextField(
                        value = prismTargetPackage,
                        onValueChange = { prismTargetPackage = it.trim() },
                        label = { Text("Package Game Asli PRISM (opsional)") },
                        placeholder = { Text("mis. com.supercell.clashofclans") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = prismSlotText,
                            onValueChange = { prismSlotText = it.filter(Char::isDigit).take(2) },
                            label = { Text("Urutan Slot #") },
                            placeholder = { Text("1..99") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = prismProfileId,
                            onValueChange = { prismProfileId = it.trim().take(80) },
                            label = { Text("Profile ID (opsional)") },
                            singleLine = true,
                            modifier = Modifier.weight(1.4f)
                        )
                    }
                    Text(
                        "Package PRISM (app.prism.c.*) atau Nomor Slot #01..#99 otomatis dikirim via Broadcast Anti-BAL ke PRISM Cloner.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF38BDF8)
                    )
                }

                if (usesOcr && images.isNotEmpty()) {
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
                    val cleanPkg = packageName.trim()
                    val parsedSlot = (prismSlotText.toIntOrNull() ?: 0).coerceIn(0, 99)
                        .takeIf { it > 0 } ?: PrismProtocol.parseSlotNumberFromPackage(cleanPkg)
                    val cleanLabel = label.trim().ifBlank {
                        ocrKeyword.trim().ifBlank {
                            appLabel.trim().ifBlank {
                                if (parsedSlot > 0) "PRISM #$parsedSlot" else "Akun"
                            }
                        }
                    }
                    onConfirm(
                        initial.copy(
                            label = cleanLabel,
                            ocrKeyword = ocrKeyword.trim(),
                            fallbackImageName = fallbackImg.trim(),
                            packageName = cleanPkg,
                            appLabel = appLabel.trim(),
                            prismTargetPackage = prismTargetPackage.trim(),
                            prismSlotOrder = parsedSlot,
                            prismProfileId = prismProfileId.trim(),
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
            onSelect = { info ->
                packageName = info.packageName
                appLabel = info.label
                if (info.isPrismRoot) {
                    if (info.prismTargetPackage.isNotBlank()) prismTargetPackage = info.prismTargetPackage
                    if (info.prismSlotOrder in 1..99) prismSlotText = info.prismSlotOrder.toString()
                    if (info.prismProfileId.isNotBlank()) prismProfileId = info.prismProfileId
                }
                if (label.startsWith("Akun ")) label = info.label
                showAppPicker = false
            },
            onDismiss = { showAppPicker = false }
        )
    }
}

@Composable
internal fun InstalledCloneAppPickerDialog(
    currentPackage: String,
    onSelect: (InstalledAppInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            InstalledAppsHelper.loadLaunchableApps(context, forceRefresh = true)
        }
    }

    val filtered = remember(apps, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) apps else apps.filter { info ->
            info.label.lowercase().contains(q) ||
                info.packageName.lowercase().contains(q) ||
                info.prismTargetPackage.lowercase().contains(q)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pilih Aplikasi Game / Clone / PRISM", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
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
                    items(filtered, key = { it.packageName }) { info ->
                        val isSel = info.packageName == currentPackage
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelect(info) },
                            shape = RoundedCornerShape(8.dp),
                            color = when {
                                isSel -> ACCOUNT_CYAN.copy(alpha = 0.20f)
                                info.isPrismRoot -> Color(0xFF0E7490).copy(alpha = 0.16f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        info.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (info.isPrismRoot) {
                                        Text(
                                            if (info.prismSlotOrder > 0) "PRISM #%02d".format(info.prismSlotOrder) else "PRISM Root",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF38BDF8)
                                        )
                                    }
                                }
                                Text(
                                    if (info.isPrismRoot && info.prismTargetPackage.isNotBlank()) {
                                        "${info.packageName} → ${info.prismTargetPackage}"
                                    } else {
                                        info.packageName
                                    },
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
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Tutup") }
        }
    )
}
