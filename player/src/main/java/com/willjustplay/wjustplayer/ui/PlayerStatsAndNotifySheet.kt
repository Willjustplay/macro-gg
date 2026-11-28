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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.automation.RemoteNotifier
import com.example.data.FarmingSessionRecord
import com.example.data.FarmingStatsCalculator
import com.example.data.LiveSessionStats
import com.example.data.Macro
import com.example.data.NotificationConfig
import com.example.data.NotifyLevel
import com.example.data.RemoteBotConfig
import com.example.data.RemoteIdSanitizer
import com.example.data.enabledRemoteActions
import com.willjustplay.wjustplayer.data.PlayerNotificationStore
import com.willjustplay.wjustplayer.data.PlayerStore
import com.willjustplay.wjustplayer.runtime.PlayerCoordinator
import com.willjustplay.wjustplayer.runtime.PlayerNotifyManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerStatsAndNotifySheet(
    notifyStore: PlayerNotificationStore,
    liveStats: LiveSessionStats?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ctx = LocalContext.current
    val playerStore = remember(ctx) { PlayerStore.get(ctx) }
    val savedConfig by notifyStore.notificationConfig.collectAsStateWithLifecycle()
    val sessions by notifyStore.farmingStats.collectAsStateWithLifecycle()
    val macros by playerStore.macros.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }

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
                        imageVector = when (selectedTab) {
                            0 -> Icons.Filled.QueryStats
                            1 -> Icons.Filled.NotificationsActive
                            else -> Icons.Filled.SmartToy
                        },
                        contentDescription = null,
                        tint = if (selectedTab == 2) Color(0xFF38BDF8) else Color(0xFFF59E0B)
                    )
                    Text(
                        text = when (selectedTab) {
                            0 -> "Statistik Farming"
                            1 -> "Notifikasi & Alarm HP"
                            else -> "Kontrol Bot Telegram / Discord"
                        },
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
                    text = { Text("📊 Statistik", maxLines = 1) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("🔔 Notifikasi", maxLines = 1) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("🤖 Bot Kontrol", maxLines = 1) }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedTab) {
                    0 -> PlayerStatsTab(
                        liveStats = liveStats,
                        sessions = sessions,
                        onClear = { notifyStore.clearFarmingStats() }
                    )
                    1 -> PlayerNotifyTab(
                        savedConfig = savedConfig,
                        onSave = {
                            notifyStore.saveNotificationConfig(it)
                            PlayerCoordinator.syncRemoteBotListener()
                        }
                    )
                    else -> PlayerRemoteBotTab(
                        savedConfig = savedConfig,
                        macros = macros,
                        onSaveConfig = {
                            notifyStore.saveNotificationConfig(it)
                            PlayerCoordinator.syncRemoteBotListener()
                        },
                        onUpdateMacroRemote = { macroId, enabled, remoteId ->
                            playerStore.updateMacroRemoteControl(macroId, enabled, remoteId)
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun PlayerStatsTab(
    liveStats: LiveSessionStats?,
    sessions: List<FarmingSessionRecord>,
    onClear: () -> Unit
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(liveStats != null) {
        if (liveStats != null) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1000L)
            }
        }
    }

    if (liveStats != null) {
        val elapsed = (nowMs - liveStats.startedAtMs).coerceAtLeast(0L)
        val lph = FarmingStatsCalculator.ratePerHour(liveStats.loopsCompleted, elapsed)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF10B981).copy(alpha = 0.12f),
            border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.40f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "🟢 Berjalan: ${liveStats.macroName}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981)
                )
                Text(
                    "Durasi: ${FarmingStatsCalculator.formatDuration(elapsed)} • Loop: ${liveStats.loopsCompleted} (${FarmingStatsCalculator.formatRate(lph)}/jam) • Aksi: ${liveStats.actionsExecuted}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Watchdog: ${liveStats.watchdogRecoveries} • Blokir: ${liveStats.bannedBlocks} • Akun: ${liveStats.accountSwitches}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    val summary = remember(sessions) { FarmingStatsCalculator.summarize(sessions) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PlayerKpiCard(
            title = "Total Durasi",
            value = FarmingStatsCalculator.formatDuration(summary.totalDurationMs),
            subtitle = "${summary.sessionCount} sesi",
            accent = Color(0xFF0EA5E9),
            modifier = Modifier.weight(1f)
        )
        PlayerKpiCard(
            title = "Total Loop",
            value = summary.totalLoops.toString(),
            subtitle = "${FarmingStatsCalculator.formatRate(summary.avgLoopsPerHour)}/jam",
            accent = Color(0xFF10B981),
            modifier = Modifier.weight(1f)
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Riwayat Sesi (${sessions.size})",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        if (sessions.isNotEmpty()) {
            TextButton(onClick = { confirmClear = true }) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Hapus")
            }
        }
    }

    if (sessions.isEmpty()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ) {
            Text(
                "Belum ada sesi tercatat. Jalankan macro .wjm untuk melihat durasi dan kecepatan loop/jam di sini.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            )
        }
    } else {
        val dateFmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
        sessions.take(20).forEach { s ->
            val reasonColor = when (s.endReason) {
                "Error" -> Color(0xFFEF4444)
                "Dihentikan" -> Color(0xFFF59E0B)
                else -> Color(0xFF10B981)
            }
            val lph = FarmingStatsCalculator.ratePerHour(s.loopsCompleted, s.durationMs)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(11.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            s.macroName.ifBlank { "Macro" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            s.endReason,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = reasonColor
                        )
                    }
                    Text(
                        "${dateFmt.format(Date(s.startedAtMs))} • ${FarmingStatsCalculator.formatDuration(s.durationMs)} • ${s.loopsCompleted} loop (${FarmingStatsCalculator.formatRate(lph)}/jam)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Hapus Riwayat Statistik?") },
            text = { Text("Semua riwayat sesi farming lokal di Wjust Player akan dihapus.") },
            confirmButton = {
                Button(onClick = {
                    onClear()
                    confirmClear = false
                }) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Batal") }
            }
        )
    }
}

@Composable
private fun PlayerKpiCard(
    title: String,
    value: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.30f))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = accent)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerNotifyTab(
    savedConfig: NotificationConfig,
    onSave: (NotificationConfig) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(savedConfig) { mutableStateOf(savedConfig) }
    var testing by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    var isErr by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0EA5E9).copy(alpha = 0.10f),
        border = BorderStroke(1.dp, Color(0xFF0EA5E9).copy(alpha = 0.30f))
    ) {
        Text(
            "Pengaturan Bot Telegram, Discord Webhook, dan Alarm ini disimpan lokal khusus di perangkat Anda sendiri.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        )
    }

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
            Text("🔊 Alarm HP Lokal", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            PlayerSwitchRow("Suara Alarm", draft.alarmSoundEnabled) { draft = draft.copy(alarmSoundEnabled = it) }
            PlayerSwitchRow("Getar HP", draft.alarmVibrateEnabled) { draft = draft.copy(alarmVibrateEnabled = it) }
            PlayerSwitchRow("Alarm saat Error", draft.alarmOnError) { draft = draft.copy(alarmOnError = it) }
            PlayerSwitchRow("Alarm saat Selesai", draft.alarmOnComplete) { draft = draft.copy(alarmOnComplete = it) }
            FilledTonalButton(
                onClick = {
                    PlayerNotifyManager.triggerLocalAlarm(
                        context = context,
                        config = draft,
                        title = "Tes Alarm Wjust Player",
                        message = "Suara & getar alarm berfungsi normal!",
                        level = NotifyLevel.WARNING
                    )
                    isErr = false
                    feedback = "Alarm HP dibunyikan."
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Tes Alarm HP")
            }
        }
    }

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
            PlayerSwitchRow("✈️ Telegram Bot", draft.telegramEnabled) { draft = draft.copy(telegramEnabled = it) }
            OutlinedTextField(
                value = draft.telegramBotToken,
                onValueChange = { draft = draft.copy(telegramBotToken = it.trim()) },
                label = { Text("Bot Token (@BotFather)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = draft.telegramChatId,
                onValueChange = { draft = draft.copy(telegramChatId = it.trim()) },
                label = { Text("Chat ID (@userinfobot)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            HorizontalDivider()
            PlayerSwitchRow("🎮 Discord Webhook", draft.discordEnabled) { draft = draft.copy(discordEnabled = it) }
            val discordBad = draft.discordEnabled &&
                draft.discordWebhookUrl.isNotBlank() &&
                !RemoteNotifier.isValidDiscordWebhook(draft.discordWebhookUrl)
            OutlinedTextField(
                value = draft.discordWebhookUrl,
                onValueChange = { draft = draft.copy(discordWebhookUrl = it.trim()) },
                label = { Text("Discord Webhook URL") },
                isError = discordBad,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

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
            Text("⚡ Event & Laporan Berkala", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            PlayerSwitchRow("Kirim saat Selesai", draft.notifyOnComplete) { draft = draft.copy(notifyOnComplete = it) }
            PlayerSwitchRow("Kirim saat Error", draft.notifyOnError) { draft = draft.copy(notifyOnError = it) }
            PlayerSwitchRow("Kirim saat Watchdog", draft.notifyOnWatchdog) { draft = draft.copy(notifyOnWatchdog = it) }
            PlayerSwitchRow("Lampirkan Screenshot", draft.attachScreenshotOnEvents) {
                draft = draft.copy(attachScreenshotOnEvents = it)
            }
            val intervals = listOf(0 to "Mati", 15 to "15m", 30 to "30m", 60 to "60m", 120 to "2 Jam")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                intervals.forEach { (mins, label) ->
                    FilterChip(
                        selected = draft.periodicReportMinutes == mins,
                        onClick = { draft = draft.copy(periodicReportMinutes = mins) },
                        label = { Text(label) }
                    )
                }
            }
        }
    }

    if (feedback.isNotBlank()) {
        Text(
            feedback,
            style = MaterialTheme.typography.bodySmall,
            color = if (isErr) Color(0xFFEF4444) else Color(0xFF10B981),
            fontWeight = FontWeight.SemiBold
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = {
                if (!draft.hasAnyRemoteConfigured()) {
                    isErr = true
                    feedback = "Aktifkan & isi Telegram atau Discord terlebih dahulu."
                    return@OutlinedButton
                }
                testing = true
                feedback = ""
                scope.launch {
                    val res = PlayerNotifyManager.sendTestRemote(draft)
                    testing = false
                    isErr = !res.anySucceeded
                    feedback = res.summaryMessage()
                }
            },
            enabled = !testing,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            if (testing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Kirim Tes")
        }
        Button(
            onClick = {
                onSave(draft)
                isErr = false
                feedback = "Pengaturan tersimpan."
            },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Text("Simpan", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PlayerSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerRemoteBotTab(
    savedConfig: NotificationConfig,
    macros: List<Macro>,
    onSaveConfig: (NotificationConfig) -> Unit,
    onUpdateMacroRemote: (macroId: String, enabled: Boolean, remoteId: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val botStatus by PlayerCoordinator.remoteBotStatus.collectAsStateWithLifecycle()

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
    var editingMacroForId by remember { mutableStateOf<Macro?>(null) }

    fun currentMergedConfig(): NotificationConfig = savedConfig.copy(
        remoteBot = RemoteBotConfig(
            listenerEnabled = listenerEnabled,
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
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (botStatus.running) "🟢 Listener Bot 2-Arah AKTIF" else "⚪ Listener Bot 2-Arah Nonaktif",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )
            val channels = buildList {
                if (botStatus.telegramListening) add("Telegram")
                if (botStatus.discordListening) add("Discord")
            }.joinToString(" + ").ifBlank { "Belum aktif" }
            Text(
                "Kanal: $channels • Perintah diproses: ${botStatus.commandsHandled}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (botStatus.lastCommandSummary.isNotBlank()) {
                Text("Terakhir: ${botStatus.lastCommandSummary}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (botStatus.lastError.isNotBlank()) {
                Text("⚠️ ${botStatus.lastError}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFEF4444))
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PlayerSwitchRow("Aktifkan Kontrol Bot 2-Arah (Telegram / Discord)", listenerEnabled) { listenerEnabled = it }
            OutlinedTextField(
                value = deviceTag,
                onValueChange = { deviceTag = it.take(20) },
                label = { Text("Tag Nama HP (opsional multi-HP, mis. hp1)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            HorizontalDivider()
            PlayerSwitchRow("Telegram Bot 2-Arah (pakai Token & Chat ID di tab Notifikasi)", telegramRemoteEnabled) {
                telegramRemoteEnabled = it
            }
            if (telegramRemoteEnabled) {
                OutlinedTextField(
                    value = telegramAllowedIds,
                    onValueChange = { telegramAllowedIds = it.take(200) },
                    label = { Text("Whitelist Telegram Chat/User ID Tambahan (pisah koma)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            HorizontalDivider()
            PlayerSwitchRow("Discord Bot 2-Arah (via Bot Token + Channel ID)", discordRemoteEnabled) {
                discordRemoteEnabled = it
            }
            if (discordRemoteEnabled) {
                OutlinedTextField(
                    value = discordBotToken,
                    onValueChange = { discordBotToken = it.take(160) },
                    label = { Text("Discord Bot Token") },
                    visualTransformation = if (showDiscordToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showDiscordToken = !showDiscordToken }) {
                            Text(if (showDiscordToken) "Tutup" else "Lihat")
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
                    label = { Text("Whitelist Discord User ID (opsional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3, 4, 6, 10).forEach { sec ->
                        FilterChip(
                            selected = discordPollSec == sec,
                            onClick = { discordPollSec = sec },
                            label = { Text("Poll ${sec}d") }
                        )
                    }
                }
            }
        }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                val merged = currentMergedConfig()
                onSaveConfig(merged)
                feedbackMsg = if (merged.remoteBot.hasAnyListenerReady(merged)) "✅ Disimpan — Listener Bot 2-Arah aktif!" else "✅ Disimpan."
            },
            modifier = Modifier.weight(1f)
        ) { Text("Simpan & Aktifkan") }

        FilledTonalButton(
            enabled = !sendingPanel,
            onClick = {
                val merged = currentMergedConfig()
                onSaveConfig(merged)
                sendingPanel = true
                feedbackMsg = null
                scope.launch {
                    val res = PlayerCoordinator.sendRemoteBotPanelNow(merged)
                    feedbackMsg = res.message
                    sendingPanel = false
                }
            },
            modifier = Modifier.weight(1f)
        ) {
            if (sendingPanel) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Kirim Panel")
        }
    }

    feedbackMsg?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }

    // Per-macro ID assignment list (works safely even for locked imported macros without exposing steps!)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ID Makro untuk Kontrol Bot (${macros.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Atur ID Makro Aktif & Sub-Macro Pasif di sini (aman untuk makro terkunci).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            macros.forEach { m ->
                val rid = RemoteIdSanitizer.sanitize(m.remoteId)
                val hasId = m.remoteControlEnabled && rid.isNotEmpty()
                val actCount = m.enabledRemoteActions().size
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "${m.name} [${if (m.isPassive) "Pasif" else "Aktif"}]",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (hasId) "🤖 ID: $rid" + if (actCount > 0) " • $actCount aksi bot" else "" else "Belum diberi ID Bot",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasId) Color(0xFF38BDF8) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = { editingMacroForId = m }) {
                        Text(if (hasId) "Ubah ID" else "Atur ID", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    editingMacroForId?.let { target ->
        val otherIds = macros.filter { it.id != target.id && it.remoteControlEnabled }
            .map { RemoteIdSanitizer.sanitize(it.remoteId) }.filter { it.isNotEmpty() }.toSet()
        var en by remember(target.id) { mutableStateOf(target.remoteControlEnabled) }
        var rid by remember(target.id) {
            mutableStateOf(target.remoteId.ifBlank { RemoteIdSanitizer.suggestMacroId(target.name, otherIds) })
        }
        AlertDialog(
            onDismissRequest = { editingMacroForId = null },
            title = { Text("🤖 ID Kontrol Bot — ${target.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PlayerSwitchRow("Izinkan kontrol via Bot Telegram/Discord", en) { en = it }
                    if (en) {
                        OutlinedTextField(
                            value = rid,
                            onValueChange = { raw -> rid = raw.lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(24) },
                            label = { Text("ID Makro (mis. farm1)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onUpdateMacroRemote(target.id, en, rid)
                    editingMacroForId = null
                }) { Text("Simpan") }
            },
            dismissButton = {
                TextButton(onClick = { editingMacroForId = null }) { Text("Batal") }
            }
        )
    }
}

