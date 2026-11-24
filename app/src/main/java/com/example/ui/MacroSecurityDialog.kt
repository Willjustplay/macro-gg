package com.example.ui

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ExpiryUnit
import com.example.data.ImageAsset
import com.example.data.Macro
import com.example.data.MacroSecurity

/**
 * Prompts the user to verify the macro's password before opening locked actions,
 * editing security/passive/expiry settings, or duplicating a locked macro.
 */
@Composable
fun PasswordUnlockDialog(
    title: String,
    subtitle: String,
    confirmLabel: String = "Buka",
    onVerify: (String) -> Boolean,
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        errorMsg = null
                    },
                    label = { Text("Masukkan Sandi") },
                    singleLine = true,
                    isError = errorMsg != null,
                    supportingText = errorMsg?.let { msg -> { Text(msg) } },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "Sembunyikan sandi" else "Lihat sandi"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (onVerify(input)) {
                        onUnlocked()
                    } else {
                        errorMsg = "Sandi salah. Silakan coba lagi."
                    }
                },
                enabled = input.isNotEmpty()
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Batal") }
        }
    )
}

/**
 * Complete security & access control sheet for a macro:
 * - Lock macro (prevent editing / viewing actions without password)
 * - Passive mode ON/OFF (macro only runs when linked from an active macro, protected by password)
 * - Usage deadline in Days or Months (macro stops working when deadline passes)
 * - Password creation / update
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MacroSecuritySheet(
    macro: Macro,
    nowMs: Long,
    onSave: (Macro) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val alreadyHasPassword = MacroSecurity.hasPassword(macro)

    var isLocked by remember(macro.id) { mutableStateOf(macro.isLocked) }
    var isPassive by remember(macro.id) { mutableStateOf(macro.isPassive) }
    var useExpiry by remember(macro.id) { mutableStateOf(macro.expiresAtEpochMs > 0L) }
    var keepExistingExpiry by remember(macro.id) {
        mutableStateOf(macro.expiresAtEpochMs > 0L && !MacroSecurity.isExpired(macro, nowMs))
    }
    var expiryAmountText by remember(macro.id) { mutableStateOf("7") }
    var expiryUnit by remember(macro.id) { mutableStateOf(ExpiryUnit.DAYS) }

    var passwordInput by remember(macro.id) { mutableStateOf("") }
    var confirmPassword by remember(macro.id) { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val anyProtection = isLocked || isPassive || useExpiry
    val parsedAmount = (expiryAmountText.toIntOrNull() ?: 1).coerceIn(1, if (expiryUnit == ExpiryUnit.DAYS) 3650 else 120)
    val previewDeadlineMs = remember(useExpiry, keepExistingExpiry, parsedAmount, expiryUnit, macro.expiresAtEpochMs, nowMs) {
        when {
            !useExpiry -> 0L
            keepExistingExpiry && macro.expiresAtEpochMs > 0L -> macro.expiresAtEpochMs
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
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column {
                    Text("Kunci, Tenggat & Mode Pasif", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        macro.name.ifBlank { "Macro Baru" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider()

            // 1. Lock Macro toggle
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
                        Text("Kunci Macro dengan Sandi", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Mencegah edit, melihat daftar aksi di overlay, atau duplikat tanpa sandi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = isLocked, onCheckedChange = { isLocked = it; errorMsg = null })
                }
            }

            // 2. Passive Mode toggle (ON/OFF with password)
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Text("Mode Pasif (Sub-Macro)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            "Macro tidak dapat dijalankan langsung. Hanya berjalan jika dipanggil oleh macro aktif lain lewat 'Hubungkan Macro'. Memerlukan sandi untuk mematikan/mengubah.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = isPassive, onCheckedChange = { isPassive = it; errorMsg = null })
                }
            }

            // 3. Expiration Deadline (Days / Months)
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
                                Text("Tenggat Waktu Penggunaan", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                            Text(
                                "Saat waktu habis, macro tidak bisa dijalankan langsung maupun dipanggil macro lain.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = useExpiry,
                            onCheckedChange = {
                                useExpiry = it
                                if (it && (macro.expiresAtEpochMs <= 0L || MacroSecurity.isExpired(macro, nowMs))) {
                                    keepExistingExpiry = false
                                }
                                errorMsg = null
                            }
                        )
                    }

                    if (useExpiry) {
                        if (macro.expiresAtEpochMs > 0L && !MacroSecurity.isExpired(macro, nowMs)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = keepExistingExpiry,
                                    onClick = { keepExistingExpiry = true },
                                    label = { Text("Pertahankan Tenggat Saat Ini", style = MaterialTheme.typography.labelSmall) }
                                )
                                FilterChip(
                                    selected = !keepExistingExpiry,
                                    onClick = { keepExistingExpiry = false },
                                    label = { Text("Atur Ulang Durasi", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }

                        if (!keepExistingExpiry) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = expiryAmountText,
                                    onValueChange = { expiryAmountText = it.filter(Char::isDigit).take(4) },
                                    label = { Text("Durasi") },
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
                                    FilterChip(
                                        selected = parsedAmount == amt && expiryUnit == u,
                                        onClick = {
                                            expiryAmountText = amt.toString()
                                            expiryUnit = u
                                        },
                                        label = { Text("$amt ${u.label}", style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        ) {
                            Text(
                                text = "Berakhir pada: ${MacroSecurity.formatDeadlineDate(previewDeadlineMs)}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            // 4. Password Section (required whenever any protection is ON)
            if (anyProtection) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (alreadyHasPassword) "Sandi Keamanan (kosongkan jika tidak ingin mengubah)"
                        else "Buat Sandi Keamanan (wajib)",
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

            if (MacroSecurity.hasAnyProtection(macro)) {
                TextButton(
                    onClick = {
                        isLocked = false
                        isPassive = false
                        useExpiry = false
                        errorMsg = null
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Matikan Semua Proteksi", color = MaterialTheme.colorScheme.error)
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
                            keepExistingExpiry -> macro.expiryLabel.ifBlank { MacroSecurity.formatDeadlineDate(previewDeadlineMs) }
                            else -> MacroSecurity.formatDurationLabel(parsedAmount, expiryUnit)
                        }
                        val updated = MacroSecurity.applySecurity(
                            macro = macro,
                            isLocked = isLocked,
                            isPassive = isPassive,
                            expiresAtEpochMs = previewDeadlineMs,
                            expiryLabel = expLabel,
                            newPassword = passwordInput.takeIf { it.isNotBlank() }
                        )
                        onSave(updated)
                    },
                    modifier = Modifier.weight(1.4f)
                ) {
                    Text("Simpan Proteksi")
                }
            }
        }
    }
}

/**
 * Sheet to pick, replace, or remove a custom logo photo for a specific macro
 * from either the device Gallery or the in-app Image Library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroLogoPickerSheet(
    macro: Macro,
    logoFilePath: String?,
    images: List<ImageAsset>,
    onPickFromGallery: () -> Unit,
    onPickFromLibrary: (ImageAsset) -> Unit,
    onRemoveLogo: () -> Unit,
    onDismiss: () -> Unit
) {
    val currentBmp = rememberAssetThumbnail(logoFilePath, macro.updatedAt)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Foto Logo Macro", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Pilih foto khusus sebagai logo '${macro.name}'. Foto logo juga otomatis ikut tersimpan saat ekspor .zip.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0A1020))
                        .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (currentBmp != null) {
                        Image(
                            bitmap = currentBmp,
                            contentDescription = macro.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Filled.AddPhotoAlternate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onPickFromGallery,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pilih Foto dari Galeri")
                    }
                    if (macro.logoImage.isNotBlank()) {
                        OutlinedButton(
                            onClick = onRemoveLogo,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Hapus Foto Logo")
                        }
                    }
                }
            }

            if (images.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    "Atau gunakan dari Pustaka Gambar:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(images.take(24), key = { it.name }) { asset ->
                        val bmp = rememberAssetThumbnail(asset.path, asset.updatedAt)
                        Column(
                            modifier = Modifier
                                .width(76.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                .clickable { onPickFromLibrary(asset) }
                                .padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(Color(0xFF0A1020)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = asset.displayName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
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
        }
    }
}
