package com.willjustplay.wjustplayer.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.automation.LogLevel
import com.example.data.Macro
import com.example.data.MacroSecurity
import com.example.data.MacroSet
import com.willjustplay.wjustplayer.data.PlayerMacroAccounts
import com.willjustplay.wjustplayer.data.PlayerStorageHelper
import com.willjustplay.wjustplayer.runtime.PlayerEngineState
import com.willjustplay.wjustplayer.runtime.SafeLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PlayerDarkScheme = darkColorScheme(
    primary = Color(0xFF22D3EE),
    onPrimary = Color(0xFF061321),
    primaryContainer = Color(0xFF0F2B46),
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = Color(0xFF10B981),
    onSecondary = Color(0xFF05221A),
    secondaryContainer = Color(0xFF0B352A),
    onSecondaryContainer = Color(0xFFA7F3D0),
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF0F162A),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF18223C),
    onSurfaceVariant = Color(0xFF94A3B8),
    outlineVariant = Color(0xFF283556),
    error = Color(0xFFEF4444),
    onError = Color.White
)

@Composable
fun WjustPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PlayerDarkScheme,
        content = content
    )
}

@Composable
fun PlayerHomeScreen(
    macros: List<Macro>,
    macroSets: List<MacroSet>,
    accountsByKey: Map<String, PlayerMacroAccounts>,
    permissions: PlayerPermissions,
    engineState: PlayerEngineState,
    isOverlayActive: Boolean,
    activeMacroId: String,
    nowMs: Long,
    resolveLogoPath: (String) -> String?,
    accountKeyFor: (Macro) -> String,
    onOpenAccessibility: () -> Unit,
    onRequestOverlay: () -> Unit,
    onActivateScreenCapture: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onToggleOverlay: () -> Unit,
    onStopEngine: () -> Unit,
    onImportWjm: () -> Unit,
    onScanLocalDownloads: () -> Unit,
    onPlayMacro: (Macro) -> Unit,
    onManageAccounts: (Macro) -> Unit,
    onTogglePassive: (Macro) -> Unit,
    onDeleteMacro: (Macro) -> Unit,
    onDeleteMacroSet: (MacroSet, Boolean) -> Unit,
    onOpenStatsAndNotify: () -> Unit = {},
    onOpenAfkAndSchedule: () -> Unit = {}
) {
    var expandedSetIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDeleteMacro by remember { mutableStateOf<Macro?>(null) }
    var confirmDeleteSet by remember { mutableStateOf<MacroSet?>(null) }

    val setById = remember(macroSets) { macroSets.associateBy { it.id } }
    val macrosBySetId = remember(macros, setById) {
        macros.groupBy { m -> if (m.setId in setById) m.setId else "" }
    }
    val standaloneMacros = macrosBySetId[""].orEmpty()

    val activePermCount = remember(permissions) {
        listOf(
            permissions.accessibilityEnabled,
            permissions.overlayEnabled,
            permissions.screenCaptureActive,
            permissions.batteryOptimizationIgnored
        ).count { it }
    }
    val allPermsReady = activePermCount == 4
    var permsExpanded by remember { mutableStateOf(!allPermsReady) }

    LaunchedEffect(allPermsReady) {
        permsExpanded = !allPermsReady
    }

    // Automatically expand sets when first imported so user sees Play & Passive toggles right away
    LaunchedEffect(macroSets) {
        if (macroSets.size == 1 && expandedSetIds.isEmpty()) {
            expandedSetIds = setOf(macroSets.first().id)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Brand Header
        item(key = "header") {
            val statusColor by animateColorAsState(
                targetValue = when {
                    engineState == PlayerEngineState.RUNNING -> Color(0xFF10B981)
                    engineState == PlayerEngineState.PAUSED -> Color(0xFFF59E0B)
                    engineState == PlayerEngineState.ERROR -> Color(0xFFEF4444)
                    isOverlayActive -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                label = "playerStatusColor"
            )
            val statusText = when {
                engineState == PlayerEngineState.RUNNING -> "Sedang Berjalan"
                engineState == PlayerEngineState.PAUSED -> "Dijeda"
                engineState == PlayerEngineState.ERROR -> "Periksa Izin / Log"
                isOverlayActive -> "Bar Melayang Aktif"
                else -> "Play-Only • .wjm Terenkripsi"
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF0E2944), Color(0xFF0B3B31))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Wjust Player",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Wjust Player",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(Modifier.width(6.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF6366F1).copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, Color(0xFF6366F1).copy(alpha = 0.35f))
                ) {
                    IconButton(
                        onClick = onOpenAfkAndSchedule,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Bedtime,
                            contentDescription = "AFK, Guard & Jadwal",
                            tint = Color(0xFF818CF8),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                Spacer(Modifier.width(5.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFF59E0B).copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.35f))
                ) {
                    IconButton(
                        onClick = onOpenStatsAndNotify,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QueryStats,
                            contentDescription = "Statistik & Notifikasi",
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                Spacer(Modifier.width(6.dp))

                if (engineState == PlayerEngineState.RUNNING || engineState == PlayerEngineState.PAUSED) {
                    Button(
                        onClick = onStopEngine,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    FilledTonalButton(
                        onClick = onToggleOverlay,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Filled.Layers, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isOverlayActive) "Tutup Bar" else "Buka Bar", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Collapsible 4-Permission Card
        item(key = "permissions") {
            val headerTint = if (allPermsReady) Color(0xFF10B981) else Color(0xFFF59E0B)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (allPermsReady) headerTint.copy(alpha = 0.10f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ),
                border = BorderStroke(
                    1.dp,
                    if (allPermsReady) headerTint.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { permsExpanded = !permsExpanded }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (allPermsReady) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
                                contentDescription = null,
                                tint = headerTint,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = if (allPermsReady) "Semua Izin Siap Digunakan" else "Izin Diperlukan ($activePermCount/4 Aktif)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (allPermsReady) headerTint else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Icon(
                            imageVector = if (permsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    AnimatedVisibility(
                        visible = permsExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PlayerPermissionPill(
                                    label = "Aksesibilitas",
                                    active = permissions.accessibilityEnabled,
                                    modifier = Modifier.weight(1f),
                                    onClick = onOpenAccessibility
                                )
                                PlayerPermissionPill(
                                    label = "Overlay",
                                    active = permissions.overlayEnabled,
                                    modifier = Modifier.weight(1f),
                                    onClick = onRequestOverlay
                                )
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PlayerPermissionPill(
                                    label = "Tangkap Layar",
                                    active = permissions.screenCaptureActive,
                                    modifier = Modifier.weight(1f),
                                    onClick = onActivateScreenCapture
                                )
                                PlayerPermissionPill(
                                    label = "Tetap Aktif",
                                    active = permissions.batteryOptimizationIgnored,
                                    modifier = Modifier.weight(1f),
                                    onClick = onRequestBatteryUnrestricted
                                )
                            }
                        }
                    }
                }
            }
        }

        // Import Actions (WJM ONLY — zero Create / Edit / Export buttons)
        item(key = "import_bar") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onImportWjm,
                    modifier = Modifier.weight(1.25f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF10B981),
                        contentColor = Color(0xFF042018)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Impor File .wjm", fontWeight = FontWeight.ExtraBold, maxLines = 1)
                }
                FilledTonalButton(
                    onClick = onScanLocalDownloads,
                    modifier = Modifier.weight(0.95f),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Scan Download", fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }

        // Empty State
        if (macroSets.isEmpty() && standaloneMacros.isEmpty()) {
            item(key = "empty_state") {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = Color(0xFF22D3EE),
                            modifier = Modifier.size(38.dp)
                        )
                        Text(
                            text = "Belum Ada Macro .wjm",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Impor file macro terenkripsi (.wjm) yang dibuat dari Wjust macro untuk langsung menjalankannya. Mode Play-Only melindungi konfigurasi macro agar tidak berubah.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // Macro Sets
        if (macroSets.isNotEmpty()) {
            item(key = "sets_title") {
                Text(
                    text = "Macro Set (${macroSets.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(macroSets, key = { "set_${it.id}" }) { set ->
                val members = macrosBySetId[set.id].orEmpty()
                val expanded = set.id in expandedSetIds
                PlayerMacroSetCard(
                    set = set,
                    members = members,
                    accountsByKey = accountsByKey,
                    accountKeyFor = accountKeyFor,
                    expanded = expanded,
                    activeMacroId = activeMacroId,
                    nowMs = nowMs,
                    resolveLogoPath = resolveLogoPath,
                    onToggleExpand = {
                        expandedSetIds = if (expanded) expandedSetIds - set.id else expandedSetIds + set.id
                    },
                    onPlayMacro = onPlayMacro,
                    onManageAccounts = onManageAccounts,
                    onTogglePassive = onTogglePassive,
                    onRequestDeleteSet = { confirmDeleteSet = set }
                )
            }
        }

        // Standalone Macros
        if (standaloneMacros.isNotEmpty()) {
            item(key = "macros_title") {
                Text(
                    text = "Daftar Macro (${standaloneMacros.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(standaloneMacros, key = { "macro_${it.id}" }) { macro ->
                val localAccCount = (accountsByKey[accountKeyFor(macro)] ?: accountsByKey[macro.id])
                    ?.accounts?.count { it.enabled } ?: 0
                PlayerMacroCard(
                    macro = macro,
                    parentSet = null,
                    localAccountCount = localAccCount,
                    isActiveSelected = macro.id == activeMacroId,
                    nowMs = nowMs,
                    logoPath = if (macro.logoImage.isNotBlank()) resolveLogoPath(macro.logoImage) else null,
                    onPlay = { onPlayMacro(macro) },
                    onManageAccounts = { onManageAccounts(macro) },
                    onTogglePassive = { onTogglePassive(macro) },
                    onDelete = { confirmDeleteMacro = macro }
                )
            }
        }
    }

    confirmDeleteMacro?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDeleteMacro = null },
            title = { Text("Hapus Macro?", fontWeight = FontWeight.Bold) },
            text = { Text("Hapus '${target.name}' dari Wjust Player? Anda perlu file .wjm lagi jika ingin mengimpornya kembali.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDeleteMacro = null
                        onDeleteMacro(target)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteMacro = null }) { Text("Batal") }
            }
        )
    }

    confirmDeleteSet?.let { targetSet ->
        val count = macrosBySetId[targetSet.id].orEmpty().size
        AlertDialog(
            onDismissRequest = { confirmDeleteSet = null },
            title = { Text("Hapus Macro Set?", fontWeight = FontWeight.Bold) },
            text = { Text("Hapus Set '${targetSet.name}' beserta $count macro di dalamnya dari Wjust Player?") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDeleteSet = null
                        onDeleteMacroSet(targetSet, true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Hapus Set & Isi") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSet = null }) { Text("Batal") }
            }
        )
    }
}

@Composable
private fun PlayerMacroSetCard(
    set: MacroSet,
    members: List<Macro>,
    accountsByKey: Map<String, PlayerMacroAccounts>,
    accountKeyFor: (Macro) -> String,
    expanded: Boolean,
    activeMacroId: String,
    nowMs: Long,
    resolveLogoPath: (String) -> String?,
    onToggleExpand: () -> Unit,
    onPlayMacro: (Macro) -> Unit,
    onManageAccounts: (Macro) -> Unit,
    onTogglePassive: (Macro) -> Unit,
    onRequestDeleteSet: () -> Unit
) {
    val accent = remember(set.colorHex) {
        runCatching { Color(android.graphics.Color.parseColor(set.colorHex)) }.getOrElse { Color(0xFF22D3EE) }
    }
    val isExpired = MacroSecurity.isSetExpired(set, nowMs)
    val remainingLabel = MacroSecurity.remainingSetTimeLabel(set, nowMs)
    val activeMembers = members.filterNot { it.isPassive }
    val passiveMembers = members.filter { it.isPassive }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)),
        border = BorderStroke(1.dp, if (isExpired) Color(0xFFEF4444).copy(alpha = 0.6f) else accent.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 5.dp, height = 38.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(accent)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = set.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append("${activeMembers.size} Utama")
                            if (passiveMembers.isNotEmpty()) append(" • ${passiveMembers.size} Sub-Macro")
                            if (remainingLabel != null) append(" • $remainingLabel")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isExpired) Color(0xFFFCA5A5) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRequestDeleteSet, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "Hapus Set",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Quick Play (and optional Atur Akun Saya) when collapsed and there is 1 active macro
            if (!expanded && activeMembers.size == 1) {
                val primary = activeMembers.first()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onPlayMacro(primary) },
                        enabled = !isExpired,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 10.dp)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (isExpired) "Masa Aktif Berakhir" else "Jalankan '${primary.name}'",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (primary.accountSwitch.enabled) {
                        val accCnt = (accountsByKey[accountKeyFor(primary)] ?: accountsByKey[primary.id])
                            ?.accounts?.count { it.enabled } ?: 0
                        OutlinedButton(
                            onClick = { onManageAccounts(primary) },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (accCnt == 0) Color(0xFFF59E0B) else Color(0xFF38BDF8)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Filled.Groups,
                                contentDescription = null,
                                tint = if (accCnt == 0) Color(0xFFFBBF24) else Color(0xFF38BDF8),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Akun ($accCnt)",
                                color = if (accCnt == 0) Color(0xFFFBBF24) else Color(0xFF7DD3FC),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    members.forEach { member ->
                        val localAccCount = (accountsByKey[accountKeyFor(member)] ?: accountsByKey[member.id])
                            ?.accounts?.count { it.enabled } ?: 0
                        PlayerMacroCard(
                            macro = member,
                            parentSet = set,
                            localAccountCount = localAccCount,
                            isActiveSelected = member.id == activeMacroId,
                            nowMs = nowMs,
                            logoPath = if (member.logoImage.isNotBlank()) resolveLogoPath(member.logoImage) else null,
                            compact = true,
                            onPlay = { onPlayMacro(member) },
                            onManageAccounts = { onManageAccounts(member) },
                            onTogglePassive = { onTogglePassive(member) },
                            onDelete = null
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerMacroCard(
    macro: Macro,
    parentSet: MacroSet?,
    localAccountCount: Int,
    isActiveSelected: Boolean,
    nowMs: Long,
    logoPath: String?,
    compact: Boolean = false,
    onPlay: () -> Unit,
    onManageAccounts: () -> Unit,
    onTogglePassive: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val isExpired = MacroSecurity.isExpired(macro, nowMs) ||
        (parentSet != null && MacroSecurity.isSetExpired(parentSet, nowMs))
    val remaining = MacroSecurity.remainingTimeLabel(macro, nowMs)
        ?: parentSet?.let { MacroSecurity.remainingSetTimeLabel(it, nowMs) }
    val logoBmp = rememberPlayerThumbnail(logoPath, macro.updatedAt)

    Surface(
        shape = RoundedCornerShape(if (compact) 12.dp else 16.dp),
        color = if (isActiveSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (compact) 0.35f else 0.52f)
        },
        border = BorderStroke(
            width = if (isActiveSelected) 1.5.dp else 1.dp,
            color = when {
                isExpired -> Color(0xFFEF4444).copy(alpha = 0.6f)
                isActiveSelected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Optional custom logo bundled inside .wjm
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0E1B33)),
                    contentAlignment = Alignment.Center
                ) {
                    if (logoBmp != null) {
                        Image(
                            bitmap = logoBmp,
                            contentDescription = macro.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = if (macro.isPassive) Icons.Filled.Layers else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = if (macro.isPassive) Color(0xFFA78BFA) else Color(0xFF10B981),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = macro.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = buildList {
                        add(if (macro.isPassive) (if (macro.passiveEnabled) "Sub-Macro • ON" else "Sub-Macro • OFF") else "Siap Dijalankan")
                        if (!macro.isPassive && macro.accountSwitch.enabled) {
                            add("👥 $localAccountCount Akun")
                        }
                        if (remaining != null) add(remaining)
                    }.joinToString(" • ")
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            isExpired -> Color(0xFFFCA5A5)
                            macro.isPassive && macro.passiveEnabled -> Color(0xFF34D399)
                            !macro.isPassive && macro.accountSwitch.enabled && localAccountCount == 0 -> Color(0xFFFBBF24)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (macro.isPassive) {
                    Switch(
                        checked = macro.passiveEnabled,
                        enabled = !isExpired,
                        onCheckedChange = { onTogglePassive() }
                    )
                } else {
                    Button(
                        onClick = onPlay,
                        enabled = !isExpired,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF10B981),
                            contentColor = Color(0xFF042018)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(if (isExpired) "Habis" else "Play", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (onDelete != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = "Hapus",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (!macro.isPassive && macro.accountSwitch.enabled) {
                val needsSetup = localAccountCount == 0
                OutlinedButton(
                    onClick = onManageAccounts,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        1.dp,
                        if (needsSetup) Color(0xFFF59E0B).copy(alpha = 0.8f) else Color(0xFF38BDF8).copy(alpha = 0.65f)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Groups,
                        contentDescription = null,
                        tint = if (needsSetup) Color(0xFFFBBF24) else Color(0xFF38BDF8),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (needsSetup) {
                            "Atur Akun Saya — Isi Daftar Akun (${macro.accountSwitch.method.shortLabel})"
                        } else {
                            "Atur Akun Saya ($localAccountCount Aktif • ${macro.accountSwitch.method.shortLabel})"
                        },
                        color = if (needsSetup) Color(0xFFFDE68A) else Color(0xFF7DD3FC),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerPermissionPill(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tint = if (active) Color(0xFF10B981) else Color(0xFFF59E0B)
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = tint.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.42f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (active) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun PlayerLogScreen(
    logs: List<SafeLogEntry>,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Log Status", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text(
                    "Menampilkan status berjalan, progres loop, dan pemberitahuan error secara ringkas",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = onClear,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Bersihkan", style = MaterialTheme.typography.labelMedium)
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF070C18),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Belum ada aktivitas. Jalankan macro .wjm untuk melihat status di sini.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(logs, key = { it.id }) { entry ->
                        val color = when (entry.level) {
                            LogLevel.ERROR -> Color(0xFFFCA5A5)
                            LogLevel.WARN -> Color(0xFFFDE68A)
                            else -> Color(0xFFCBD5E1)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = entry.timestamp,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color(0xFF64748B)
                            )
                            Text(
                                text = entry.message,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = color
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LocalWjmImportDialog(
    files: List<PlayerStorageHelper.LocalWjmEntry>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onPickFile: (PlayerStorageHelper.LocalWjmEntry) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Impor .wjm dari Folder Download", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Menampilkan file terenkripsi .wjm di Download/WjustMacro/ dan Download/.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when {
                    loading -> Text("Memindai folder Download...", style = MaterialTheme.typography.bodyMedium)
                    files.isEmpty() -> Text(
                        "Belum ada file .wjm ditemukan. Salin file .wjm ke folder Download atau Download/WjustMacro lalu ketuk Segarkan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(files, key = { "${it.displayPath}|${it.sizeBytes}" }) { item ->
                            val kb = ((item.sizeBytes + 1023L) / 1024L).coerceAtLeast(1L)
                            Surface(
                                onClick = { onPickFile(item) },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(item.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${item.displayPath} • $kb KB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onRefresh) { Text("Segarkan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } }
    )
}

@Composable
fun PlayerAccessibilityDisclosureDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDecline,
        icon = {
            Icon(
                imageVector = Icons.Filled.AccessibilityNew,
                contentDescription = null,
                tint = Color(0xFF22D3EE),
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Penggunaan Layanan Aksesibilitas",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Wjust Player membutuhkan izin AccessibilityService API hanya untuk menjalankan file macro .wjm yang Anda impor:",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• Menjalankan sentuhan (Klik, Swipe, Gestur) secara otomatis saat Anda menekan tombol Play.\n" +
                        "• Menjalankan aksi navigasi ketika macro aktif.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFCBD5E1)
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF0B253A),
                    border = BorderStroke(1.dp, Color(0xFF22D3EE).copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(Icons.Filled.Shield, contentDescription = null, tint = Color(0xFF22D3EE), modifier = Modifier.size(18.dp))
                        Text(
                            text = "Privasi terjamin: Wjust Player berjalan 100% lokal di perangkat Anda dan TIDAK mengumpulkan, menyimpan, atau mengirim data pribadi/layar ke server mana pun.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFBAE6FD)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22D3EE), contentColor = Color(0xFF061321))
            ) { Text("Setuju & Lanjutkan", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text("Tolak") }
        }
    )
}

@Composable
private fun rememberPlayerThumbnail(path: String?, cacheKey: Long): ImageBitmap? {
    val state by produceState<ImageBitmap?>(initialValue = null, path, cacheKey) {
        value = if (path.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }
    return state
}
