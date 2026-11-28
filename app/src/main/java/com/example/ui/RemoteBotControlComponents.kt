package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.automation.AutomationCoordinator
import com.example.data.ActionRemoteControl
import com.example.data.Macro
import com.example.data.NotificationConfig
import com.example.data.RemoteActionMode
import com.example.data.RemoteBotConfig
import com.example.data.RemoteIdSanitizer
import com.example.data.enabledRemoteActions
import kotlinx.coroutines.launch

/**
 * Tab 3 inside [StatsAndNotifySheet]: configures 2-way Telegram / Discord bot control listener
 * (device-only, zero backend server, never exported inside macro bundles).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteBotControlTabContent(
    savedConfig: NotificationConfig,
    macros: List<Macro>,
    isPremium: Boolean = true,
    onRequirePremium: (String) -> Unit = {},
    onSaveConfig: (NotificationConfig) -> Unit
) {
    val scope = rememberCoroutineScope()
    val botStatus by AutomationCoordinator.remoteBotStatus.collectAsStateWithLifecycle()

    val savedRb = savedConfig.remoteBot
    var listenerEnabled by remember(savedRb) { mutableStateOf(savedRb.listenerEnabled) }
    var deviceTag by remember(savedRb) { mutableStateOf(savedRb.deviceTag) }
    var telegramRemoteEnabled by remember(savedRb) { mutableStateOf(savedRb.telegramRemoteEnabled) }
    var telegramAllowedIds by remember(savedRb) { mutableStateOf(savedRb.telegramAllowedIds) }
    var discordRemoteEnabled by remember(savedRb) { mutableStateOf(savedRb.discordRemoteEnabled) }
    var discordBotToken by remember(savedRb) { mutableStateOf(savedRb.discordBotToken) }
    var discordChannelId by remember(savedRb) { mutableStateOf(savedRb.discordChannelId) }
    var discordAllowedUserIds by remember(savedRb) { mutableStateOf(savedRb.discordAllowedUserIds) }
    var discordPollSec by remember(savedRb) { mutableIntStateOf(savedRb.discordPollIntervalSec.coerceIn(3, 30)) }
    var showDiscordToken by remember { mutableStateOf(false) }

    var sendingPanel by remember { mutableStateOf(false) }
    var feedbackMsg by remember { mutableStateOf<String?>(null) }

    fun currentMergedConfig(): NotificationConfig = savedConfig.copy(
        remoteBot = RemoteBotConfig(
            listenerEnabled = if (isPremium) listenerEnabled else false,
            deviceTag = RemoteIdSanitizer.sanitize(deviceTag),
            telegramRemoteEnabled = telegramRemoteEnabled,
            telegramAllowedIds = telegramAllowedIds.trim(),
            discordRemoteEnabled = discordRemoteEnabled,
            discordBotToken = discordBotToken.trim(),
            discordChannelId = discordChannelId.trim(),
            discordAllowedUserIds = discordAllowedUserIds.trim(),
            discordPollIntervalSec = discordPollSec.coerceIn(3, 30)
        )
    )

    // Live listener status banner
    val statusColor = when {
        botStatus.running -> Color(0xFF10B981)
        botStatus.lastError.isNotBlank() -> Color(0xFFEF4444)
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = statusColor.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.SmartToy, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
                Text(
                    text = if (botStatus.running) "Listener Bot 2-Arah AKTIF" else "Listener Bot 2-Arah Nonaktif",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
            }
            val activeChannels = buildList {
                if (botStatus.telegramListening) add("Telegram")
                if (botStatus.discordListening) add("Discord")
            }.joinToString(" + ").ifBlank { "Belum ada kanal aktif" }
            Text(
                text = "Kanal: $activeChannels • Perintah diproses: ${botStatus.commandsHandled}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (botStatus.lastCommandSummary.isNotBlank()) {
                Text(
                    text = "Terakhir: ${botStatus.lastCommandSummary}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (botStatus.lastError.isNotBlank()) {
                Text(
                    text = "⚠️ ${botStatus.lastError}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFEF4444)
                )
            }
        }
    }

    if (!isPremium) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFF59E0B).copy(alpha = 0.14f),
            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = Color(0xFFF59E0B))
                Text(
                    "Kontrol Makro & Aksi via Bot Telegram/Discord tersedia di Wjust macro PRO.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF59E0B),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    // Master switch & Device Tag
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        "Aktifkan Kontrol Bot 2-Arah",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Terima perintah ON/OFF makro Aktif & Pasif serta kontrol aksi langsung dari Telegram / Discord tanpa server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = listenerEnabled && isPremium,
                    onCheckedChange = { want ->
                        if (!isPremium) {
                            onRequirePremium("Kontrol Bot Telegram/Discord 2-Arah khusus Wjust macro PRO.")
                        } else {
                            listenerEnabled = want
                        }
                    }
                )
            }

            OutlinedTextField(
                value = deviceTag,
                onValueChange = { deviceTag = it.take(20) },
                label = { Text("Tag Nama HP (opsional multi-HP, mis. hp1)") },
                supportingText = {
                    val clean = RemoteIdSanitizer.sanitize(deviceTag)
                    Text(
                        if (clean.isNotEmpty()) "Target spesifik HP ini: /on $clean:<id_makro> atau @$clean /status"
                        else "Kosongkan jika hanya 1 HP. Token & whitelist hanya tersimpan lokal di HP ini."
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // Telegram 2-way config
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Telegram Bot (2-Arah + Tombol Inline)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    val tgReady = savedConfig.telegramBotToken.isNotBlank() && savedConfig.telegramChatId.isNotBlank()
                    Text(
                        if (tgReady) "✅ Memakai Bot Token & Chat ID dari tab Notifikasi."
                        else "⚠️ Isi Bot Token & Chat ID Telegram di tab Notifikasi terlebih dahulu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tgReady) Color(0xFF10B981) else Color(0xFFF59E0B)
                    )
                }
                Switch(
                    checked = telegramRemoteEnabled,
                    onCheckedChange = { telegramRemoteEnabled = it }
                )
            }

            if (telegramRemoteEnabled) {
                OutlinedTextField(
                    value = telegramAllowedIds,
                    onValueChange = { telegramAllowedIds = it.take(200) },
                    label = { Text("Whitelist Chat/User ID Tambahan (opsional, pisah koma)") },
                    supportingText = { Text("Chat ID utama otomatis diizinkan. Pesan dari orang lain otomatis ditolak.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    // Discord 2-way config
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Discord Bot (2-Arah via Bot Token)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Webhook hanya 1 arah. Untuk kontrol 2 arah, buat Bot gratis di Discord Developer Portal (nyalakan Message Content Intent).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = discordRemoteEnabled,
                    onCheckedChange = { discordRemoteEnabled = it }
                )
            }

            if (discordRemoteEnabled) {
                OutlinedTextField(
                    value = discordBotToken,
                    onValueChange = { discordBotToken = it.take(160) },
                    label = { Text("Discord Bot Token") },
                    visualTransformation = if (showDiscordToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        OutlinedButton(
                            onClick = { showDiscordToken = !showDiscordToken },
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text(if (showDiscordToken) "Tutup" else "Lihat", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = discordChannelId,
                    onValueChange = { discordChannelId = it.filter { c -> c.isDigit() }.take(30) },
                    label = { Text("Discord Channel ID Kontrol") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = discordAllowedUserIds,
                    onValueChange = { discordAllowedUserIds = it.take(200) },
                    label = { Text("Whitelist Discord User ID (opsional, pisah koma)") },
                    supportingText = { Text("Disarankan diisi User ID Anda agar anggota lain tidak dapat mengontrol makro.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Interval Cek Pesan Discord:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3, 4, 6, 10, 15).forEach { sec ->
                        FilterChip(
                            selected = discordPollSec == sec,
                            onClick = { discordPollSec = sec },
                            label = { Text("${sec}d") }
                        )
                    }
                }
            }
        }
    }

    // Save + Send Panel buttons
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = {
                if (!isPremium) {
                    onRequirePremium("Kontrol Bot Telegram/Discord 2-Arah khusus Wjust macro PRO.")
                    return@Button
                }
                val merged = currentMergedConfig()
                onSaveConfig(merged)
                feedbackMsg = if (merged.remoteBot.hasAnyListenerReady(merged)) {
                    "✅ Disimpan — Listener Bot 2-Arah aktif!"
                } else if (merged.remoteBot.listenerEnabled) {
                    "⚠️ Disimpan — lengkapi Token & Chat/Channel ID agar listener aktif."
                } else {
                    "✅ Pengaturan Bot Kontrol disimpan (nonaktif)."
                }
            },
            modifier = Modifier.weight(1f)
        ) {
            Text("Simpan & Terapkan")
        }

        FilledTonalButton(
            enabled = !sendingPanel,
            onClick = {
                if (!isPremium) {
                    onRequirePremium("Kontrol Bot Telegram/Discord 2-Arah khusus Wjust macro PRO.")
                    return@FilledTonalButton
                }
                val merged = currentMergedConfig()
                onSaveConfig(merged)
                sendingPanel = true
                feedbackMsg = null
                scope.launch {
                    val res = AutomationCoordinator.sendRemoteBotPanelNow(merged)
                    feedbackMsg = res.message
                    sendingPanel = false
                }
            },
            modifier = Modifier.weight(1f)
        ) {
            if (sendingPanel) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text("Mengirim...")
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Kirim Panel")
            }
        }
    }

    feedbackMsg?.let { msg ->
        Text(
            text = msg,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }

    // Overview of configured macro remote IDs
    val controllable = remember(macros) {
        macros.filter { it.remoteControlEnabled && RemoteIdSanitizer.sanitize(it.remoteId).isNotEmpty() }
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Daftar ID Makro Aktif (${controllable.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            if (controllable.isEmpty()) {
                Text(
                    "Belum ada makro dengan ID Bot. Buka Editor Makro → Setelan Makro → aktifkan Kontrol Bot & isi ID Makro.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                controllable.forEach { m ->
                    val rid = RemoteIdSanitizer.sanitize(m.remoteId)
                    val kindTxt = if (m.isPassive) "Pasif" else "Aktif"
                    val actCount = m.enabledRemoteActions().size
                    Text(
                        text = "• $rid — ${m.name} [$kindTxt]" + if (actCount > 0) " • $actCount aksi bot" else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Perintah Telegram (/) & Discord (!):\n" +
                    "• /menu — Panel tombol interaktif (Telegram)\n" +
                    "• /list — Lihat semua ID makro & status\n" +
                    "• /on <id> & /off <id> — ON/OFF Makro Aktif & Pasif\n" +
                    "• /act <id_makro> <id_aksi> on|off — ON/OFF aksi live\n" +
                    "• /status • /ss (Screenshot) • /afk on|off",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Reusable section placed inside Macro Settings (`MacroSettingsSheet`) to enable & edit a macro's Bot Control ID.
 */
@Composable
fun MacroRemoteControlSettingsSection(
    macroName: String,
    isPassive: Boolean,
    enabled: Boolean,
    remoteId: String,
    existingOtherMacroIds: Set<String>,
    onEnabledChange: (Boolean) -> Unit,
    onRemoteIdChange: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF0EA5E9).copy(alpha = 0.09f),
        border = BorderStroke(1.dp, Color(0xFF0EA5E9).copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        "🤖 Kontrol Bot Telegram / Discord",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                    Text(
                        if (isPassive) "Makro Pasif: bot mengontrol ON (Dapat Ditarik) / OFF (Dilewati)."
                        else "Makro Aktif: bot mengontrol ON (Jalankan) / OFF (Hentikan).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        onEnabledChange(checked)
                        if (checked && RemoteIdSanitizer.sanitize(remoteId).isEmpty()) {
                            onRemoteIdChange(RemoteIdSanitizer.suggestMacroId(macroName, existingOtherMacroIds))
                        }
                    }
                )
            }

            if (enabled) {
                val sanitized = RemoteIdSanitizer.sanitize(remoteId)
                val duplicate = sanitized.isNotEmpty() && sanitized in existingOtherMacroIds
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = remoteId,
                        onValueChange = { raw ->
                            onRemoteIdChange(raw.lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(24))
                        },
                        label = { Text("ID Makro (mis. farm1)") },
                        isError = duplicate || sanitized.isEmpty(),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = {
                            onRemoteIdChange(RemoteIdSanitizer.suggestMacroId(macroName, existingOtherMacroIds))
                        }
                    ) {
                        Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Auto")
                    }
                }
                Text(
                    text = when {
                        sanitized.isEmpty() -> "⚠️ Isi ID Makro (huruf/angka/_/-) agar bisa dipanggil bot."
                        duplicate -> "⚠️ ID '$sanitized' sudah dipakai makro lain — gunakan ID unik."
                        else -> "Perintah bot: /on $sanitized  •  /off $sanitized  •  /act $sanitized"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (duplicate || sanitized.isEmpty()) Color(0xFFEF4444) else Color(0xFF38BDF8)
                )
            }
        }
    }
}

/**
 * Reusable per-action bot control card rendered at the bottom of [ActionEditorSheet] for ALL 25 action kinds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionRemoteControlEditorCard(
    actionKindLabel: String,
    control: ActionRemoteControl,
    existingOtherActionIds: Set<String>,
    macroRemoteIdHint: String,
    onUpdate: (ActionRemoteControl) -> Unit
) {
    val sanitized = control.normalizedId()
    val duplicate = control.enabled && sanitized.isNotEmpty() && sanitized in existingOtherActionIds
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF8B5CF6).copy(alpha = 0.09f),
        border = BorderStroke(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        "🤖 Kontrol Aksi via Bot Telegram / Discord",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFA78BFA)
                    )
                    Text(
                        if (control.enabled) "Aksi ini bisa dinyalakan/dimatikan secara live dari bot saat makro berjalan."
                        else "Aktifkan jika ingin mengontrol aksi ini ON/OFF atau memicu 1× lewat bot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = control.enabled,
                    onCheckedChange = { want ->
                        val nextId = if (want && control.normalizedId().isEmpty()) {
                            RemoteIdSanitizer.suggestActionId(actionKindLabel, existingOtherActionIds)
                        } else control.remoteId
                        onUpdate(control.copy(enabled = want, remoteId = nextId))
                    }
                )
            }

            if (control.enabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = control.remoteId,
                        onValueChange = { raw ->
                            val clean = raw.lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(24)
                            onUpdate(control.copy(remoteId = clean))
                        },
                        label = { Text("ID Aksi (mis. auto_sell)") },
                        isError = duplicate || sanitized.isEmpty(),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = {
                            onUpdate(
                                control.copy(
                                    remoteId = RemoteIdSanitizer.suggestActionId(
                                        control.label.ifBlank { actionKindLabel },
                                        existingOtherActionIds
                                    )
                                )
                            )
                        }
                    ) {
                        Text("Auto")
                    }
                }

                OutlinedTextField(
                    value = control.label,
                    onValueChange = { onUpdate(control.copy(label = it.take(36))) },
                    label = { Text("Label Tombol di Bot (opsional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Mode Kontrol:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteActionMode.entries.forEach { mode ->
                        FilterChip(
                            selected = control.mode == mode,
                            onClick = {
                                val nextActive = if (mode == RemoteActionMode.ONE_SHOT) false else control.active
                                onUpdate(control.copy(mode = mode, active = nextActive))
                            },
                            label = { Text(mode.shortLabel) }
                        )
                    }
                }
                Text(
                    control.mode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = when {
                            control.mode == RemoteActionMode.ONE_SHOT && control.active -> "Status: ⚡ Siap Dipicu 1×"
                            control.mode == RemoteActionMode.ONE_SHOT -> "Status Awal: ⚪ OFF (Menunggu Perintah Bot)"
                            control.active -> "Status Saat Ini: 🟢 ON (Aktif)"
                            else -> "Status Saat Ini: ⚪ OFF (Pasif / Dilewati)"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Switch(
                        checked = control.active,
                        onCheckedChange = { onUpdate(control.copy(active = it)) }
                    )
                }

                val mId = RemoteIdSanitizer.sanitize(macroRemoteIdHint).ifBlank { "<id_makro>" }
                val aId = sanitized.ifBlank { "<id_aksi>" }
                Text(
                    text = when {
                        sanitized.isEmpty() -> "⚠️ Isi ID Aksi agar bisa dikontrol dari bot."
                        duplicate -> "⚠️ ID Aksi '$sanitized' sudah dipakai aksi lain di makro ini."
                        control.mode == RemoteActionMode.ONE_SHOT -> "Perintah bot: /trigger $mId $aId  (atau /act $mId $aId on)"
                        else -> "Perintah bot: /act $mId $aId on  •  /act $mId $aId off"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (duplicate || sanitized.isEmpty()) Color(0xFFEF4444) else Color(0xFFA78BFA)
                )
            }
        }
    }
}
