package com.example.ui

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QueryStats
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.automation.PhoneAlarmAndNotifyManager
import com.example.automation.RemoteNotifier
import com.example.data.FarmingSessionRecord
import com.example.data.FarmingStatsCalculator
import com.example.data.LiveSessionStats
import com.example.data.LocalStore
import com.example.data.NotificationConfig
import com.example.data.NotifyAction
import com.example.data.NotifyLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StatsAndNotifySheet(
    store: LocalStore,
    liveStats: LiveSessionStats?,
    onDismiss: () -> Unit,
    isPremium: Boolean = true,
    onRequirePremium: (String) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val savedConfig by store.notificationConfig.collectAsStateWithLifecycle()
    val sessions by store.farmingStats.collectAsStateWithLifecycle()
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
                        imageVector = if (selectedTab == 0) Icons.Filled.QueryStats else Icons.Filled.NotificationsActive,
                        contentDescription = null,
                        tint = Color(0xFFF59E0B)
                    )
                    Text(
                        text = if (selectedTab == 0) "Statistik Farming" else "Notifikasi & Alarm HP",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("Tutup")
                }
            }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("📊 Statistik") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("🔔 Notifikasi & Alarm") }
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
                    FarmingStatsTabContent(
                        liveStats = liveStats,
                        sessions = sessions,
                        onClearHistory = { store.clearFarmingStats() }
                    )
                } else {
                    NotifySettingsTabContent(
                        savedConfig = savedConfig,
                        isPremium = isPremium,
                        onRequirePremium = onRequirePremium,
                        onSaveConfig = { store.saveNotificationConfig(it) }
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FarmingStatsTabContent(
    liveStats: LiveSessionStats?,
    sessions: List<FarmingSessionRecord>,
    onClearHistory: () -> Unit
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
        val loopsHr = FarmingStatsCalculator.ratePerHour(liveStats.loopsCompleted, elapsed)
        val actsHr = FarmingStatsCalculator.ratePerHour(liveStats.actionsExecuted, elapsed)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF10B981).copy(alpha = 0.12f),
            border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.40f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "🟢 Sesi Berjalan: ${liveStats.macroName}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981)
                )
                Text(
                    "Durasi: ${FarmingStatsCalculator.formatDuration(elapsed)} • Loop: ${liveStats.loopsCompleted} (${FarmingStatsCalculator.formatRate(loopsHr)}/jam) • Aksi: ${liveStats.actionsExecuted} (${FarmingStatsCalculator.formatRate(actsHr)}/jam)",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Watchdog: ${liveStats.watchdogRecoveries} • Banned Dicegah: ${liveStats.bannedBlocks} • Ganti Akun: ${liveStats.accountSwitches} • Break: ${liveStats.humanBreaks}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    val summary = remember(sessions) { FarmingStatsCalculator.summarize(sessions) }
    val breakdowns = remember(sessions) { FarmingStatsCalculator.breakdownByMacro(sessions) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatsKpiCard(
            title = "Total Durasi",
            value = FarmingStatsCalculator.formatDuration(summary.totalDurationMs),
            subtitle = "${summary.sessionCount} sesi",
            accent = Color(0xFF0EA5E9),
            modifier = Modifier.weight(1f)
        )
        StatsKpiCard(
            title = "Total Loop",
            value = summary.totalLoops.toString(),
            subtitle = "${FarmingStatsCalculator.formatRate(summary.avgLoopsPerHour)} loop/jam",
            accent = Color(0xFF10B981),
            modifier = Modifier.weight(1f)
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatsKpiCard(
            title = "Total Aksi",
            value = summary.totalActions.toString(),
            subtitle = "${FarmingStatsCalculator.formatRate(summary.avgActionsPerHour)} aksi/jam",
            accent = Color(0xFFA855F7),
            modifier = Modifier.weight(1f)
        )
        StatsKpiCard(
            title = "Proteksi & Akun",
            value = "${summary.totalWatchdogRecoveries}W / ${summary.totalBannedBlocks}B",
            subtitle = "Akun: ${summary.totalAccountSwitches} • Break: ${summary.totalHumanBreaks}",
            accent = Color(0xFFF59E0B),
            modifier = Modifier.weight(1f)
        )
    }

    if (breakdowns.isNotEmpty()) {
        Text(
            "Ringkasan per Macro",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        breakdowns.take(8).forEach { b ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            b.macroName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${b.sessionCount} sesi • ${FarmingStatsCalculator.formatDuration(b.totalDurationMs)} • ${b.totalLoops} loop • ${b.totalActions} aksi",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF10B981).copy(alpha = 0.16f)
                    ) {
                        Text(
                            "${FarmingStatsCalculator.formatRate(b.loopsPerHour)}/jam",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Riwayat Sesi Terbaru (${sessions.size})",
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
                "Belum ada sesi farming tercatat. Jalankan macro untuk mulai merekam durasi, loop/jam, Watchdog, dan hasil variabel secara otomatis.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            )
        }
    } else {
        val dateFmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
        sessions.take(25).forEach { s ->
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
                    verticalArrangement = Arrangement.spacedBy(4.dp)
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
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = reasonColor.copy(alpha = 0.16f)
                        ) {
                            Text(
                                s.endReason,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = reasonColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        "${dateFmt.format(Date(s.startedAtMs))} • ${FarmingStatsCalculator.formatDuration(s.durationMs)} • ${s.loopsCompleted} loop (${FarmingStatsCalculator.formatRate(lph)}/jam) • ${s.actionsExecuted} aksi",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (s.watchdogRecoveries > 0 || s.bannedBlocks > 0 || s.accountSwitches > 0 || s.humanBreaks > 0) {
                        Text(
                            "Watchdog: ${s.watchdogRecoveries} • Banned: ${s.bannedBlocks} • Akun: ${s.accountSwitches} • Break: ${s.humanBreaks}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (s.finalVariables.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            s.finalVariables.entries.take(8).forEach { (k, v) ->
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFA855F7).copy(alpha = 0.16f)
                                ) {
                                    Text(
                                        "$k=${FarmingStatsCalculator.formatPlainDouble(v)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFA855F7),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Hapus Riwayat Statistik?") },
            text = { Text("Semua catatan sesi farming lokal di HP ini akan dihapus.") },
            confirmButton = {
                Button(onClick = {
                    onClearHistory()
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
private fun StatsKpiCard(
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
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotifySettingsTabContent(
    savedConfig: NotificationConfig,
    isPremium: Boolean,
    onRequirePremium: (String) -> Unit,
    onSaveConfig: (NotificationConfig) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(savedConfig) { mutableStateOf(savedConfig) }
    var testingRemote by remember { mutableStateOf(false) }
    var feedbackMsg by remember { mutableStateOf("") }
    var feedbackError by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0EA5E9).copy(alpha = 0.10f),
        border = BorderStroke(1.dp, Color(0xFF0EA5E9).copy(alpha = 0.32f))
    ) {
        Text(
            "🔒 Privasi Aman: Bot Token Telegram & Webhook Discord hanya disimpan lokal di HP ini dan TIDAK PERNAH ikut terekspor ke file .zip atau .wjm.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(11.dp)
        )
    }

    // 1. Local Phone Alarm (Always FREE)
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
            Text(
                "🔊 Alarm HP Lokal",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            CompactToggleRow("Suara Alarm / Notifikasi", draft.alarmSoundEnabled) {
                draft = draft.copy(alarmSoundEnabled = it)
            }
            CompactToggleRow("Getar HP (Waveform)", draft.alarmVibrateEnabled) {
                draft = draft.copy(alarmVibrateEnabled = it)
            }
            CompactToggleRow("Bunyikan Alarm saat Macro Error", draft.alarmOnError) {
                draft = draft.copy(alarmOnError = it)
            }
            CompactToggleRow("Bunyikan Alarm saat Macro Selesai", draft.alarmOnComplete) {
                draft = draft.copy(alarmOnComplete = it)
            }
            FilledTonalButton(
                onClick = {
                    PhoneAlarmAndNotifyManager.triggerLocalAlarm(
                        context = context,
                        config = draft,
                        title = "Tes Alarm HP",
                        message = "Suara & getar alarm Wjust macro berfungsi normal!",
                        level = NotifyLevel.WARNING
                    )
                    feedbackError = false
                    feedbackMsg = "Alarm HP lokal dibunyikan."
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Tes Alarm HP Sekarang")
            }
        }
    }

    // 2. Telegram Bot
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "✈️ Telegram Bot",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (!isPremium) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = "PRO",
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                Switch(
                    checked = draft.telegramEnabled,
                    onCheckedChange = { want ->
                        if (want && !isPremium) {
                            onRequirePremium("Notifikasi Telegram Bot")
                        } else {
                            draft = draft.copy(telegramEnabled = want)
                        }
                    }
                )
            }
            Text(
                "Buat bot gratis via @BotFather (salin Token), lalu kirim /start ke bot Anda & cek Chat ID via @userinfobot.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = draft.telegramBotToken,
                onValueChange = { draft = draft.copy(telegramBotToken = it.trim()) },
                label = { Text("Bot Token (contoh: 123456:ABC-def...)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = draft.telegramChatId,
                onValueChange = { draft = draft.copy(telegramChatId = it.trim()) },
                label = { Text("Chat ID (contoh: 987654321 atau -100...)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // 3. Discord Webhook
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "🎮 Discord Webhook",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (!isPremium) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = "PRO",
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                Switch(
                    checked = draft.discordEnabled,
                    onCheckedChange = { want ->
                        if (want && !isPremium) {
                            onRequirePremium("Notifikasi Discord Webhook")
                        } else {
                            draft = draft.copy(discordEnabled = want)
                        }
                    }
                )
            }
            Text(
                "Buka Edit Channel Discord → Integrations → Webhooks → Copy Webhook URL.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val discordInvalid = draft.discordEnabled &&
                draft.discordWebhookUrl.isNotBlank() &&
                !RemoteNotifier.isValidDiscordWebhook(draft.discordWebhookUrl)
            OutlinedTextField(
                value = draft.discordWebhookUrl,
                onValueChange = { draft = draft.copy(discordWebhookUrl = it.trim()) },
                label = { Text("Discord Webhook URL") },
                isError = discordInvalid,
                supportingText = if (discordInvalid) {
                    { Text("URL harus diawali https://discord.com/api/webhooks/...") }
                } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // 4. Auto-Event Triggers & Periodic Report
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
            Text(
                "⚡ Pemicu Otomatis & Laporan Berkala",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            CompactToggleRow("Kirim saat Macro Selesai", draft.notifyOnComplete) {
                draft = draft.copy(notifyOnComplete = it)
            }
            CompactToggleRow("Kirim saat Macro Error", draft.notifyOnError) {
                draft = draft.copy(notifyOnError = it)
            }
            CompactToggleRow("Kirim saat Watchdog Anti-Macet Aktif", draft.notifyOnWatchdog) {
                draft = draft.copy(notifyOnWatchdog = it)
            }
            CompactToggleRow("Kirim saat Ganti Akun", draft.notifyOnAccountSwitch) {
                draft = draft.copy(notifyOnAccountSwitch = it)
            }
            CompactToggleRow("Lampirkan Screenshot Layar (bila aktif)", draft.attachScreenshotOnEvents) {
                draft = draft.copy(attachScreenshotOnEvents = it)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                "Laporan Status Berkala (Telegram / Discord)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
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

    if (feedbackMsg.isNotBlank()) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = (if (feedbackError) Color(0xFFEF4444) else Color(0xFF10B981)).copy(alpha = 0.14f)
        ) {
            Text(
                feedbackMsg,
                style = MaterialTheme.typography.bodySmall,
                color = if (feedbackError) Color(0xFFEF4444) else Color(0xFF10B981),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = {
                if (!isPremium) {
                    onRequirePremium("Tes Telegram / Discord")
                    return@OutlinedButton
                }
                if (!draft.hasAnyRemoteConfigured()) {
                    feedbackError = true
                    feedbackMsg = "Aktifkan & isi Telegram Bot atau Discord Webhook terlebih dahulu."
                    return@OutlinedButton
                }
                testingRemote = true
                feedbackMsg = ""
                scope.launch {
                    val res = PhoneAlarmAndNotifyManager.sendTestRemote(draft)
                    testingRemote = false
                    feedbackError = !res.anySucceeded
                    feedbackMsg = res.summaryMessage()
                }
            },
            enabled = !testingRemote,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            if (testingRemote) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text("Kirim Tes", maxLines = 1)
        }

        Button(
            onClick = {
                onSaveConfig(draft)
                feedbackError = false
                feedbackMsg = "Pengaturan notifikasi & alarm tersimpan."
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
private fun CompactToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotifyActionEditor(
    action: NotifyAction,
    onUpdate: (NotifyAction) -> Unit
) {
    val accent = Color(0xFFF59E0B)
    var delayText by remember(action.id, action.delayAfterMs) { mutableStateOf(action.delayAfterMs.toString()) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Kirim Notifikasi / Alarm HP",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Text(
                "Kirim pesan ke Telegram / Discord atau bunyikan Alarm HP tepat saat aksi ini dijalankan (contoh: saat item langka muncul, energi habis, atau target variabel tercapai).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Text("Tingkat Alert", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NotifyLevel.entries.forEach { lvl ->
            FilterChip(
                selected = action.level == lvl,
                onClick = { onUpdate(action.copy(level = lvl)) },
                label = { Text("${lvl.icon} ${lvl.label}") }
            )
        }
    }

    OutlinedTextField(
        value = action.title,
        onValueChange = { onUpdate(action.copy(title = it.take(80))) },
        label = { Text("Judul Notifikasi") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = action.message,
        onValueChange = { onUpdate(action.copy(message = it.take(400))) },
        label = { Text("Isi Pesan (dukung variabel & status)") },
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth()
    )

    Text(
        "Sisipkan variabel dinamis ke pesan:",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val placeholders = listOf("{macro}", "{loop}", "{actions}", "{account}", "{var:gold}")
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        placeholders.forEach { token ->
            FilterChip(
                selected = false,
                onClick = {
                    val sep = if (action.message.isBlank() || action.message.endsWith(" ")) "" else " "
                    onUpdate(action.copy(message = (action.message + sep + token).take(400)))
                },
                label = { Text("+ $token", style = MaterialTheme.typography.labelSmall) }
            )
        }
    }

    CompactToggleRow(
        label = "Kirim ke Telegram / Discord (sesuai pengaturan HP)",
        checked = action.sendRemote,
        onCheckedChange = { onUpdate(action.copy(sendRemote = it)) }
    )

    if (action.sendRemote) {
        CompactToggleRow(
            label = "Lampirkan Screenshot Layar saat ini",
            checked = action.includeScreenshot,
            onCheckedChange = { onUpdate(action.copy(includeScreenshot = it)) }
        )
    }

    CompactToggleRow(
        label = "Bunyikan Alarm & Getar HP Lokal",
        checked = action.triggerPhoneAlarm,
        onCheckedChange = { onUpdate(action.copy(triggerPhoneAlarm = it)) }
    )

    OutlinedTextField(
        value = delayText,
        onValueChange = { raw ->
            val clean = raw.filter { it.isDigit() }.take(7)
            delayText = clean
            onUpdate(action.copy(delayAfterMs = (clean.toLongOrNull() ?: 0L).coerceAtLeast(0L)))
        },
        label = { Text("Jeda setelah aksi (ms)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}
