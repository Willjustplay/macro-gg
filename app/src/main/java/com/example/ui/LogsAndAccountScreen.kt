package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.automation.ExecutionLogBus
import com.example.automation.LogEntry
import com.example.automation.LogLevel
import com.example.data.RunHistoryEntry
import com.example.data.ScriptRunStatus
import com.example.data.ThemeMode
import com.example.data.UiState
import com.example.data.UserProfile
import com.example.ui.theme.CodeEditorTextStyle
import com.example.ui.theme.CodeSurfaceDark
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.SyntaxApi
import com.example.ui.theme.SyntaxKeyword
import com.example.ui.theme.SyntaxString
import kotlin.math.roundToInt

@Composable
fun LogsAndAccountScreen(
    liveLogs: List<LogEntry>,
    runHistoryState: UiState<List<RunHistoryEntry>>,
    userProfile: UserProfile?,
    userEmail: String?,
    dynamicColorEnabled: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    onSavePreferences: (String, ThemeMode, Int, Int) -> Unit,
    onDeleteHistoryEntry: (String) -> Unit,
    onSignOut: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text("Live Log & Riwayat")
                }
                SegmentedButton(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text("Akun & Tema")
                }
            }

            Spacer(Modifier.height(12.dp))

            if (selectedTab == 0) {
                LogsAndHistoryTab(
                    liveLogs = liveLogs,
                    runHistoryState = runHistoryState,
                    onDeleteHistoryEntry = onDeleteHistoryEntry
                )
            } else {
                AccountAndPreferencesTab(
                    userProfile = userProfile,
                    userEmail = userEmail,
                    dynamicColorEnabled = dynamicColorEnabled,
                    onDynamicColorChange = onDynamicColorChange,
                    onSavePreferences = onSavePreferences,
                    onSignOut = onSignOut
                )
            }
        }
    }
}

@Composable
private fun LogsAndHistoryTab(
    liveLogs: List<LogEntry>,
    runHistoryState: UiState<List<RunHistoryEntry>>,
    onDeleteHistoryEntry: (String) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Live Console (${liveLogs.size})", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    onClick = { ExecutionLogBus.clear() },
                    modifier = Modifier.height(48.dp).testTag("clear_live_logs_button")
                ) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Bersihkan")
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = CodeSurfaceDark,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (liveLogs.isEmpty()) {
                        Text(
                            text = "Belum ada log real-time. Jalankan macro atau script Lua untuk memantau eksekusi di sini.",
                            style = CodeEditorTextStyle,
                            color = Color(0xFF94A3B8)
                        )
                    } else {
                        liveLogs.takeLast(45).reversed().forEach { entry ->
                            val levelColor = when (entry.level) {
                                LogLevel.LUA -> SyntaxApi
                                LogLevel.ACTION -> SyntaxKeyword
                                LogLevel.COLOR -> SyntaxString
                                LogLevel.WARN -> StatusAmber
                                LogLevel.ERROR -> StatusCrimson
                                LogLevel.INFO -> Color(0xFFCBD5E1)
                            }
                            Text(
                                text = "[${entry.timestamp.substring(0, 8)} ${entry.level.tag}] ${entry.message}",
                                style = CodeEditorTextStyle,
                                color = levelColor
                            )
                        }
                    }
                }
            }
        }

        item {
            Text("Riwayat Eksekusi Cloud", style = MaterialTheme.typography.titleMedium)
        }

        when (runHistoryState) {
            is UiState.Loading -> item {
                Text("Memuat riwayat...", style = MaterialTheme.typography.bodySmall)
            }
            is UiState.Error -> item {
                Text(runHistoryState.message, color = MaterialTheme.colorScheme.error)
            }
            is UiState.Success -> {
                if (runHistoryState.data.isEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Riwayat eksekusi Anda akan muncul secara otomatis di sini.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                } else {
                    items(runHistoryState.data, key = { it.id }) { item ->
                        val status = item.resolvedStatus()
                        val badgeColor = when (status) {
                            ScriptRunStatus.SUCCESS -> StatusEmerald
                            ScriptRunStatus.STOPPED -> StatusAmber
                            ScriptRunStatus.ERROR -> StatusCrimson
                            ScriptRunStatus.NEVER -> MaterialTheme.colorScheme.outline
                        }
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(item.targetName, style = MaterialTheme.typography.titleSmall)
                                        Surface(
                                            color = badgeColor.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = "${item.targetType} • ${status.label}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = badgeColor,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = "${item.actionsExecuted} aksi • ${item.durationMs}ms — ${item.summaryMessage}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { onDeleteHistoryEntry(item.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Hapus Riwayat")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountAndPreferencesTab(
    userProfile: UserProfile?,
    userEmail: String?,
    dynamicColorEnabled: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    onSavePreferences: (String, ThemeMode, Int, Int) -> Unit,
    onSignOut: () -> Unit
) {
    var displayName by remember { mutableStateOf(userProfile?.displayName ?: "Pengguna TapLua") }
    var themeMode by remember { mutableStateOf(userProfile?.resolvedThemeMode() ?: ThemeMode.SYSTEM) }
    var tapDuration by remember { mutableFloatStateOf((userProfile?.defaultTapDurationMs ?: 60).toFloat()) }
    var jitterPx by remember { mutableFloatStateOf((userProfile?.jitterRadiusPx ?: 4).toFloat()) }

    LaunchedEffect(userProfile) {
        if (userProfile != null) {
            displayName = userProfile.displayName
            themeMode = userProfile.resolvedThemeMode()
            tapDuration = userProfile.defaultTapDurationMs.toFloat()
            jitterPx = userProfile.jitterRadiusPx.toFloat()
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = userEmail ?: userProfile?.email ?: "Sinkronisasi Cloud Aktif",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Nama Tampilan") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Text("Mode Tema Material 3 Adaptive", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ThemeMode.SYSTEM to "Sistem",
                    ThemeMode.LIGHT to "Terang",
                    ThemeMode.DARK to "Gelap"
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { themeMode = mode },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Material You Dynamic Color", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Ikuti palet wallpaper perangkat pada Android 12+",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = dynamicColorEnabled, onCheckedChange = onDynamicColorChange)
            }
        }

        item {
            Text(
                text = "Durasi Klik Default: ${tapDuration.roundToInt()} ms",
                style = MaterialTheme.typography.titleSmall
            )
            Slider(
                value = tapDuration,
                onValueChange = { tapDuration = it },
                valueRange = 20f..300f
            )
            Text(
                text = "Anti-Stuck Touch Jitter: ±${jitterPx.roundToInt()} px",
                style = MaterialTheme.typography.titleSmall
            )
            Slider(
                value = jitterPx,
                onValueChange = { jitterPx = it },
                valueRange = 0f..25f
            )
        }

        item {
            Button(
                onClick = {
                    onSavePreferences(
                        displayName,
                        themeMode,
                        tapDuration.roundToInt(),
                        jitterPx.roundToInt()
                    )
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("save_preferences_button")
            ) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Simpan Preferensi ke Cloud")
            }
        }

        item {
            OutlinedButton(
                onClick = onSignOut,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("sign_out_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Keluar Akun Google")
            }
        }
    }
}
