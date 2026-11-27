package com.example.ui

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.automation.EngineState
import com.example.data.ColorAction
import com.example.data.ImageAction
import com.example.data.Macro
import com.example.data.MacroSecurity
import com.example.data.MacroSet
import com.example.data.RecordAction
import com.example.data.ResolutionScaler
import com.example.data.TextAction
import com.example.data.allActionsSequence
import com.example.data.countActions
import com.example.data.countStuckActions
import com.example.data.walk

private const val SET_FILTER_ALL = "__ALL__"
private const val SET_FILTER_UNGROUPED = "__UNGROUPED__"

@Composable
fun MacroHomeScreen(
    macros: List<Macro>,
    macroSets: List<MacroSet> = emptyList(),
    permissions: PermissionHealth,
    engineState: EngineState,
    isOverlayActive: Boolean,
    activeMacroId: String,
    nowMs: Long,
    screenW: Int,
    screenH: Int,
    resolveLogoPath: (String) -> String?,
    onOpenAccessibility: () -> Unit,
    onRequestOverlay: () -> Unit,
    onActivateScreenCapture: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onToggleOverlay: () -> Unit,
    onStopEngine: () -> Unit,
    onQuickAutoClicker: () -> Unit,
    onCreateMacro: () -> Unit,
    onCreateMacroInSet: (MacroSet) -> Unit = { onCreateMacro() },
    onImportMacro: () -> Unit,
    onCreateMacroSet: () -> Unit = {},
    onEditMacroSet: (MacroSet) -> Unit = {},
    onManageMacroSetMembers: (MacroSet) -> Unit = {},
    onOpenMacroSetSecurity: (MacroSet) -> Unit = {},
    onDuplicateMacroSet: (MacroSet) -> Unit = {},
    onExportMacroSet: (MacroSet, Boolean) -> Unit = { _, _ -> },
    onExportMacroSetDirectToDownloads: (MacroSet, Boolean) -> Unit = onExportMacroSet,
    onDeleteMacroSet: (MacroSet) -> Unit = {},
    onMoveMacroToSet: (Macro) -> Unit = {},
    onEditMacro: (Macro) -> Unit,
    onLaunchMacroOverlay: (Macro) -> Unit,
    onChangeMacroLogo: (Macro) -> Unit,
    onOpenMacroSecurity: (Macro) -> Unit,
    onOpenAutoScale: (Macro) -> Unit,
    onTogglePassivePull: (Macro) -> Unit,
    onDuplicateMacro: (Macro) -> Unit,
    onExportMacro: (Macro, Boolean) -> Unit,
    onExportMacroDirectToDownloads: (Macro, Boolean) -> Unit = onExportMacro,
    onDeleteMacro: (Macro) -> Unit,
    isPremium: Boolean = false,
    activePlanLabel: String = "Gratis",
    onOpenPaywall: (com.example.billing.PremiumFeature) -> Unit = {}
) {
    var exportDialogMacro by remember { mutableStateOf<Macro?>(null) }
    var exportDialogPreferDownloads by remember { mutableStateOf(false) }
    var exportDialogSet by remember { mutableStateOf<MacroSet?>(null) }
    var exportSetPreferDownloads by remember { mutableStateOf(false) }
    var selectedSetFilter by remember { mutableStateOf(SET_FILTER_ALL) }
    var expandedSetIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val setById = remember(macroSets) { macroSets.associateBy { it.id } }
    val macrosBySetId = remember(macros, setById) {
        macros.groupBy { m -> if (m.setId in setById) m.setId else "" }
    }
    val ungroupedMacros = macrosBySetId[""].orEmpty()

    LaunchedEffect(macroSets) {
        if (selectedSetFilter != SET_FILTER_ALL &&
            selectedSetFilter != SET_FILTER_UNGROUPED &&
            selectedSetFilter !in setById
        ) {
            selectedSetFilter = SET_FILTER_ALL
        }
    }

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

    // Automatically collapse when all 4 permissions become active, and expand if any drops.
    LaunchedEffect(allPermsReady) {
        permsExpanded = !allPermsReady
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Brand Header with Wjust macro Logo + Live Status + Overlay Action
        item(key = "header") {
            val statusColor by animateColorAsState(
                targetValue = when {
                    engineState == EngineState.RUNNING -> Color(0xFF10B981)
                    engineState == EngineState.PAUSED -> Color(0xFFF59E0B)
                    isOverlayActive -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                label = "statusColor"
            )
            val statusText = when {
                engineState == EngineState.RUNNING -> "Berjalan"
                engineState == EngineState.PAUSED -> "Dijeda"
                isOverlayActive -> "Overlay Aktif"
                else -> "Siap • Offline"
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
                    Image(
                        painter = painterResource(id = R.drawable.wjust_logo_badge),
                        contentDescription = "Logo Wjust macro",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Wjust macro",
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
                                statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                if (engineState == EngineState.RUNNING || engineState == EngineState.PAUSED) {
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

        // Subscription / Free Tier Status Banner
        item(key = "subscription_banner") {
            if (isPremium) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF064E3B).copy(alpha = 0.25f),
                    border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPaywall(com.example.billing.PremiumFeature.UPGRADE_GENERAL) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF34D399),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Wjust macro PRO • $activePlanLabel",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFA7F3D0)
                            )
                        }
                        Text(
                            text = "Semua Fitur Aktif",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF6EE7B7)
                        )
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = ProGoldColor.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, ProGoldColor.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPaywall(com.example.billing.PremiumFeature.UPGRADE_GENERAL) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Versi Gratis • ${macros.size}/${com.example.billing.PremiumFeatureGate.FREE_MAX_MACROS} Macro",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = ProGoldLightColor
                            )
                            Text(
                                text = "Gratis: Klik, Swipe & Deteksi Gambar • Upgrade PRO untuk semua fitur",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        ProLockBadge(text = "UPGRADE PRO")
                    }
                }
            }
        }

        // Dynamic Collapsible Permission Card (collapses neatly once 4/4 active)
        item(key = "permissions") {
            val headerTint = if (allPermsReady) Color(0xFF10B981) else Color(0xFFF59E0B)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (allPermsReady) {
                        headerTint.copy(alpha = 0.10f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (allPermsReady) headerTint.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
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
                                text = if (allPermsReady) "Semua Izin Otomasi Aktif" else "Izin Otomasi ($activePermCount/4 Aktif)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (allPermsReady) headerTint else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Icon(
                            imageVector = if (permsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (permsExpanded) "Tutup" else "Buka",
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PermissionPill(
                                    label = "Aksesibilitas",
                                    active = permissions.accessibilityEnabled,
                                    modifier = Modifier.weight(1f),
                                    onClick = onOpenAccessibility
                                )
                                PermissionPill(
                                    label = "Overlay",
                                    active = permissions.overlayEnabled,
                                    modifier = Modifier.weight(1f),
                                    onClick = onRequestOverlay
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PermissionPill(
                                    label = "Tangkap Layar",
                                    active = permissions.screenCaptureActive,
                                    modifier = Modifier.weight(1f),
                                    onClick = onActivateScreenCapture
                                )
                                PermissionPill(
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

        // Quick Start Actions
        item(key = "quick_actions") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onQuickAutoClicker,
                    modifier = Modifier.weight(1.28f),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Filled.AdsClick, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Auto Clicker", maxLines = 1, fontWeight = FontWeight.SemiBold)
                }
                FilledTonalButton(
                    onClick = onCreateMacro,
                    modifier = Modifier.weight(0.95f),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Baru", maxLines = 1, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onImportMacro,
                    modifier = Modifier.weight(0.92f),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Impor", maxLines = 1, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Macro Set Filter Strip & Quick "+ Buat Set"
        item(key = "macro_sets_bar") {
            if (macroSets.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Filled.CreateNewFolder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    "Macro Set",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Kelompokkan, kunci & ekspor beberapa macro sekaligus",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = onCreateMacroSet,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("Buat Set", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item(key = "chip_all") {
                        FilterChip(
                            selected = selectedSetFilter == SET_FILTER_ALL,
                            onClick = { selectedSetFilter = SET_FILTER_ALL },
                            label = {
                                Text("Semua (${macros.size})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            }
                        )
                    }
                    items(macroSets, key = { "chip_${it.id}" }) { set ->
                        val count = macrosBySetId[set.id]?.size ?: 0
                        val accent = parseSetAccentColor(set.colorHex)
                        val selected = selectedSetFilter == set.id
                        FilterChip(
                            selected = selected,
                            onClick = { selectedSetFilter = set.id },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (set.isLocked) Icons.Filled.Lock else Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else accent,
                                    modifier = Modifier.size(15.dp)
                                )
                            },
                            label = {
                                Text(
                                    "${set.name} ($count)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent.copy(alpha = 0.22f)
                            )
                        )
                    }
                    if (ungroupedMacros.isNotEmpty()) {
                        item(key = "chip_ungrouped") {
                            FilterChip(
                                selected = selectedSetFilter == SET_FILTER_UNGROUPED,
                                onClick = { selectedSetFilter = SET_FILTER_UNGROUPED },
                                label = {
                                    Text("Tanpa Set (${ungroupedMacros.size})", style = MaterialTheme.typography.labelMedium)
                                }
                            )
                        }
                    }
                    item(key = "chip_add_set") {
                        FilledTonalButton(
                            onClick = onCreateMacroSet,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("+ Set", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Focused Single Macro Set Mode
        val focusedSet = if (selectedSetFilter != SET_FILTER_ALL && selectedSetFilter != SET_FILTER_UNGROUPED) {
            setById[selectedSetFilter]
        } else null

        if (focusedSet != null) {
            val setMembers = macrosBySetId[focusedSet.id].orEmpty()
            item(key = "focused_set_banner_${focusedSet.id}") {
                FocusedMacroSetBanner(
                    set = focusedSet,
                    members = setMembers,
                    nowMs = nowMs,
                    onBackToAll = { selectedSetFilter = SET_FILTER_ALL },
                    onCreateMacroInSet = { onCreateMacroInSet(focusedSet) },
                    onManageMembers = { onManageMacroSetMembers(focusedSet) },
                    onEditSet = { onEditMacroSet(focusedSet) },
                    onOpenSecurity = { onOpenMacroSetSecurity(focusedSet) },
                    onDuplicateSet = { onDuplicateMacroSet(focusedSet) },
                    onExportSet = {
                        exportSetPreferDownloads = false
                        exportDialogSet = focusedSet
                    },
                    onExportSetDownloads = {
                        exportSetPreferDownloads = true
                        exportDialogSet = focusedSet
                    },
                    onDeleteSet = { onDeleteMacroSet(focusedSet) }
                )
            }
            if (setMembers.isEmpty()) {
                item(key = "focused_set_empty_${focusedSet.id}") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = parseSetAccentColor(focusedSet.colorHex)
                            )
                            Text("Macro Set ini masih kosong", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Tambahkan macro baru atau pilih macro yang sudah ada untuk dimasukkan ke '${focusedSet.name}'.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onCreateMacroInSet(focusedSet) }, shape = RoundedCornerShape(10.dp)) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Macro Baru")
                                }
                                FilledTonalButton(onClick = { onManageMacroSetMembers(focusedSet) }, shape = RoundedCornerShape(10.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.PlaylistAddCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Pilih Macro")
                                }
                            }
                        }
                    }
                }
            } else {
                items(setMembers, key = { it.id }) { macro ->
                    MacroListCard(
                        macro = macro,
                        parentSet = focusedSet,
                        isActive = macro.id == activeMacroId && isOverlayActive,
                        nowMs = nowMs,
                        screenW = screenW,
                        screenH = screenH,
                        logoPath = if (macro.logoImage.isNotBlank()) resolveLogoPath(macro.logoImage) else null,
                        modifier = Modifier.animateItem(),
                        onEdit = { onEditMacro(macro) },
                        onLaunchOverlay = { onLaunchMacroOverlay(macro) },
                        onChangeLogo = { onChangeMacroLogo(macro) },
                        onOpenSecurity = { onOpenMacroSecurity(macro) },
                        onOpenAutoScale = { onOpenAutoScale(macro) },
                        onTogglePassivePull = { onTogglePassivePull(macro) },
                        onMoveToSet = { onMoveMacroToSet(macro) },
                        onDuplicate = { onDuplicateMacro(macro) },
                        onExport = {
                            exportDialogPreferDownloads = false
                            exportDialogMacro = macro
                        },
                        onExportDirectToDownloads = {
                            exportDialogPreferDownloads = true
                            exportDialogMacro = macro
                        },
                        onDelete = { onDeleteMacro(macro) }
                    )
                }
            }
        } else {
            // "Semua" or "Tanpa Set" mode
            if (selectedSetFilter == SET_FILTER_ALL && macroSets.isNotEmpty()) {
                item(key = "sets_section_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Macro Set",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        ) {
                            Text(
                                "${macroSets.size} set",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                items(macroSets, key = { "set_card_${it.id}" }) { set ->
                    val members = macrosBySetId[set.id].orEmpty()
                    val isExpanded = set.id in expandedSetIds
                    MacroSetCard(
                        set = set,
                        members = members,
                        isExpanded = isExpanded,
                        activeMacroId = activeMacroId,
                        isOverlayActive = isOverlayActive,
                        nowMs = nowMs,
                        screenW = screenW,
                        screenH = screenH,
                        resolveLogoPath = resolveLogoPath,
                        modifier = Modifier.animateItem(),
                        onToggleExpand = {
                            expandedSetIds = if (isExpanded) expandedSetIds - set.id else expandedSetIds + set.id
                        },
                        onFocusSet = { selectedSetFilter = set.id },
                        onCreateMacroInSet = { onCreateMacroInSet(set) },
                        onManageMembers = { onManageMacroSetMembers(set) },
                        onEditSet = { onEditMacroSet(set) },
                        onOpenSecurity = { onOpenMacroSetSecurity(set) },
                        onDuplicateSet = { onDuplicateMacroSet(set) },
                        onExportSet = {
                            exportSetPreferDownloads = false
                            exportDialogSet = set
                        },
                        onExportSetDownloads = {
                            exportSetPreferDownloads = true
                            exportDialogSet = set
                        },
                        onDeleteSet = { onDeleteMacroSet(set) },
                        onEditMacro = onEditMacro,
                        onLaunchMacroOverlay = onLaunchMacroOverlay,
                        onChangeMacroLogo = onChangeMacroLogo,
                        onOpenMacroSecurity = onOpenMacroSecurity,
                        onOpenAutoScale = onOpenAutoScale,
                        onTogglePassivePull = onTogglePassivePull,
                        onMoveMacroToSet = onMoveMacroToSet,
                        onDuplicateMacro = onDuplicateMacro,
                        onExportMacro = { m ->
                            exportDialogPreferDownloads = false
                            exportDialogMacro = m
                        },
                        onExportMacroDownloads = { m ->
                            exportDialogPreferDownloads = true
                            exportDialogMacro = m
                        },
                        onDeleteMacro = onDeleteMacro
                    )
                }
            }

            // Ungrouped Macros Section
            val visibleLooseMacros = if (selectedSetFilter == SET_FILTER_UNGROUPED || macroSets.isNotEmpty()) {
                ungroupedMacros
            } else {
                macros
            }
            val showLooseHeader = visibleLooseMacros.isNotEmpty() || macroSets.isEmpty() || selectedSetFilter == SET_FILTER_UNGROUPED
            if (showLooseHeader) {
                item(key = "list_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (macroSets.isEmpty()) "Macro Tersimpan" else "Macro Tanpa Set",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        ) {
                            Text(
                                "${visibleLooseMacros.size} item",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            if (macros.isEmpty() && macroSets.isEmpty()) {
                item(key = "empty") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.ImageSearch,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Belum ada macro tersimpan",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Tekan 'Auto Clicker' untuk mulai cepat atau 'Baru' untuk membuat macro deteksi gambar, teks, atau warna.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(visibleLooseMacros, key = { it.id }) { macro ->
                    MacroListCard(
                        macro = macro,
                        parentSet = null,
                        isActive = macro.id == activeMacroId && isOverlayActive,
                        nowMs = nowMs,
                        screenW = screenW,
                        screenH = screenH,
                        logoPath = if (macro.logoImage.isNotBlank()) resolveLogoPath(macro.logoImage) else null,
                        modifier = Modifier.animateItem(),
                        onEdit = { onEditMacro(macro) },
                        onLaunchOverlay = { onLaunchMacroOverlay(macro) },
                        onChangeLogo = { onChangeMacroLogo(macro) },
                        onOpenSecurity = { onOpenMacroSecurity(macro) },
                        onOpenAutoScale = { onOpenAutoScale(macro) },
                        onTogglePassivePull = { onTogglePassivePull(macro) },
                        onMoveToSet = { onMoveMacroToSet(macro) },
                        onDuplicate = { onDuplicateMacro(macro) },
                        onExport = {
                            exportDialogPreferDownloads = false
                            exportDialogMacro = macro
                        },
                        onExportDirectToDownloads = {
                            exportDialogPreferDownloads = true
                            exportDialogMacro = macro
                        },
                        onDelete = { onDeleteMacro(macro) }
                    )
                }
            }
        }
    }

    exportDialogMacro?.let { targetMacro ->
        ExportMacroOptionsDialog(
            macro = targetMacro,
            preferDownloads = exportDialogPreferDownloads,
            onExportToPicker = { singleUse ->
                exportDialogMacro = null
                onExportMacro(targetMacro, singleUse)
            },
            onExportToDownloads = { singleUse ->
                exportDialogMacro = null
                onExportMacroDirectToDownloads(targetMacro, singleUse)
            },
            onDismiss = { exportDialogMacro = null }
        )
    }

    exportDialogSet?.let { targetSet ->
        val members = macrosBySetId[targetSet.id].orEmpty()
        ExportMacroSetOptionsDialog(
            set = targetSet,
            memberCount = members.size,
            passiveCount = members.count { it.isPassive },
            preferDownloads = exportSetPreferDownloads,
            onExportToPicker = { singleUse ->
                exportDialogSet = null
                onExportMacroSet(targetSet, singleUse)
            },
            onExportToDownloads = { singleUse ->
                exportDialogSet = null
                onExportMacroSetDirectToDownloads(targetSet, singleUse)
            },
            onDismiss = { exportDialogSet = null }
        )
    }
}

@Composable
private fun ExportMacroOptionsDialog(
    macro: Macro,
    preferDownloads: Boolean,
    onExportToPicker: (singleUseImport: Boolean) -> Unit,
    onExportToDownloads: (singleUseImport: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var singleUseImport by remember(macro.id) { mutableStateOf(false) }
    val actionCount = remember(macro) { macro.countActions() }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (singleUseImport) Icons.Filled.Lock else Icons.Filled.FileDownload,
                contentDescription = null,
                tint = if (singleUseImport) Color(0xFFF59E0B) else MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                "Ekspor / Ekstrak Macro",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Macro: '${macro.name}' ($actionCount aksi + gambar terkait ke format .zip).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                                "File .zip diberi token unik bertanda tangan. Setelah 1× diimpor, file yang sama akan ditolak jika coba diimpor ulang.",
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
                        "Nama file akan otomatis ditandai akhiran '_1x.zip'. Macro asli di HP Anda tidak berubah dan tetap bisa diekspor lagi dengan token baru.",
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
                TextButton(onClick = onDismiss) {
                    Text("Batal")
                }
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

@Composable
private fun PermissionPill(
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
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MacroSetCard(
    set: MacroSet,
    members: List<Macro>,
    isExpanded: Boolean,
    activeMacroId: String,
    isOverlayActive: Boolean,
    nowMs: Long,
    screenW: Int,
    screenH: Int,
    resolveLogoPath: (String) -> String?,
    modifier: Modifier = Modifier,
    onToggleExpand: () -> Unit,
    onFocusSet: () -> Unit,
    onCreateMacroInSet: () -> Unit,
    onManageMembers: () -> Unit,
    onEditSet: () -> Unit,
    onOpenSecurity: () -> Unit,
    onDuplicateSet: () -> Unit,
    onExportSet: () -> Unit,
    onExportSetDownloads: () -> Unit,
    onDeleteSet: () -> Unit,
    onEditMacro: (Macro) -> Unit,
    onLaunchMacroOverlay: (Macro) -> Unit,
    onChangeMacroLogo: (Macro) -> Unit,
    onOpenMacroSecurity: (Macro) -> Unit,
    onOpenAutoScale: (Macro) -> Unit,
    onTogglePassivePull: (Macro) -> Unit,
    onMoveMacroToSet: (Macro) -> Unit,
    onDuplicateMacro: (Macro) -> Unit,
    onExportMacro: (Macro) -> Unit,
    onExportMacroDownloads: (Macro) -> Unit,
    onDeleteMacro: (Macro) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val accent = parseSetAccentColor(set.colorHex)
    val isExpired = MacroSecurity.isSetExpired(set, nowMs)
    val isTampered = MacroSecurity.isSetTampered(set)
    val remainingLabel = MacroSecurity.remainingSetTimeLabel(set, nowMs)
    val passiveCount = remember(members) { members.count { it.isPassive } }
    val activeCount = (members.size - passiveCount).coerceAtLeast(0)
    val hasRunningMember = isOverlayActive && members.any { it.id == activeMacroId }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isExpired || isTampered -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.14f)
                hasRunningMember -> accent.copy(alpha = 0.10f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (isExpanded || hasRunningMember || isExpired) 1.5.dp else 1.dp,
            color = when {
                isExpired || isTampered -> MaterialTheme.colorScheme.error.copy(alpha = 0.65f)
                isExpanded || hasRunningMember -> accent.copy(alpha = 0.8f)
                else -> accent.copy(alpha = 0.42f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.FolderOpen else Icons.Filled.Folder,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            set.name.ifBlank { "Set Baru" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (set.isLocked) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = "Set Terkunci",
                                tint = Color(0xFFA78BFA),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    val countSummary = buildString {
                        append("${members.size} macro")
                        if (members.isNotEmpty()) {
                            append(" ($activeCount Aktif")
                            if (passiveCount > 0) append(" • $passiveCount Pasif")
                            append(")")
                        }
                    }
                    Text(
                        countSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (set.description.isNotBlank()) {
                        Text(
                            set.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onToggleExpand, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (isExpanded) "Tutup Set" else "Buka Set",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu Set", modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Buka Tampilan Fokus Set") },
                            leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                            onClick = { menuOpen = false; onFocusSet() }
                        )
                        DropdownMenuItem(
                            text = { Text("Tambah Macro Baru ke Set") },
                            leadingIcon = { Icon(Icons.Filled.Add, null) },
                            onClick = { menuOpen = false; onCreateMacroInSet() }
                        )
                        DropdownMenuItem(
                            text = { Text("Kelola Isi Set (${members.size})") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAddCheck, null) },
                            onClick = { menuOpen = false; onManageMembers() }
                        )
                        DropdownMenuItem(
                            text = { Text("Edit Nama & Warna Set") },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menuOpen = false; onEditSet() }
                        )
                        DropdownMenuItem(
                            text = { Text("Kunci & Tenggat Set") },
                            leadingIcon = { Icon(Icons.Filled.Lock, null) },
                            onClick = { menuOpen = false; onOpenSecurity() }
                        )
                        DropdownMenuItem(
                            text = { Text("Duplikat Set") },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                            onClick = { menuOpen = false; onDuplicateSet() }
                        )
                        DropdownMenuItem(
                            text = { Text("Ekspor Macro Set (.zip)") },
                            leadingIcon = { Icon(Icons.Filled.FileDownload, null) },
                            onClick = { menuOpen = false; onExportSet() }
                        )
                        DropdownMenuItem(
                            text = { Text("Simpan Set ke Download (.zip)") },
                            leadingIcon = { Icon(Icons.Filled.FileDownload, null) },
                            onClick = { menuOpen = false; onExportSetDownloads() }
                        )
                        DropdownMenuItem(
                            text = { Text("Hapus Set", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDeleteSet() }
                        )
                    }
                }
            }

            if (set.isLocked || remainingLabel != null || isTampered) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isTampered) FeatureTag("⚠ Set Tidak Valid", Color(0xFFEF4444))
                    if (set.isLocked) FeatureTag("🔒 Set Terkunci", Color(0xFFA78BFA))
                    if (remainingLabel != null) {
                        val expColor = if (isExpired) Color(0xFFEF4444) else Color(0xFFF59E0B)
                        FeatureTag((if (isExpired) "⏰ " else "⏳ ") + remainingLabel, expColor)
                    }
                }
            }

            // Quick Action Bar for the Set (Lock, Export, Manage/Expand)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onOpenSecurity,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = if (set.isLocked) Color(0xFFA78BFA) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (set.isLocked) "Terkunci" else "Kunci", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onExportSet,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Ekspor", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = onToggleExpand,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        if (isExpanded) "Sembunyikan" else "Lihat (${members.size})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = accent.copy(alpha = 0.25f))
                    if (members.isEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Belum ada macro di set ini.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = onManageMembers) {
                                Text("+ Pilih Macro")
                            }
                        }
                    } else {
                        members.forEach { macro ->
                            MacroListCard(
                                macro = macro,
                                parentSet = set,
                                isActive = macro.id == activeMacroId && isOverlayActive,
                                nowMs = nowMs,
                                screenW = screenW,
                                screenH = screenH,
                                logoPath = if (macro.logoImage.isNotBlank()) resolveLogoPath(macro.logoImage) else null,
                                onEdit = { onEditMacro(macro) },
                                onLaunchOverlay = { onLaunchMacroOverlay(macro) },
                                onChangeLogo = { onChangeMacroLogo(macro) },
                                onOpenSecurity = { onOpenMacroSecurity(macro) },
                                onOpenAutoScale = { onOpenAutoScale(macro) },
                                onTogglePassivePull = { onTogglePassivePull(macro) },
                                onMoveToSet = { onMoveMacroToSet(macro) },
                                onDuplicate = { onDuplicateMacro(macro) },
                                onExport = { onExportMacro(macro) },
                                onExportDirectToDownloads = { onExportMacroDownloads(macro) },
                                onDelete = { onDeleteMacro(macro) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusedMacroSetBanner(
    set: MacroSet,
    members: List<Macro>,
    nowMs: Long,
    onBackToAll: () -> Unit,
    onCreateMacroInSet: () -> Unit,
    onManageMembers: () -> Unit,
    onEditSet: () -> Unit,
    onOpenSecurity: () -> Unit,
    onDuplicateSet: () -> Unit,
    onExportSet: () -> Unit,
    onExportSetDownloads: () -> Unit,
    onDeleteSet: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val accent = parseSetAccentColor(set.colorHex)
    val isExpired = MacroSecurity.isSetExpired(set, nowMs)
    val isTampered = MacroSecurity.isSetTampered(set)
    val remainingLabel = MacroSecurity.remainingSetTimeLabel(set, nowMs)
    val passiveCount = members.count { it.isPassive }
    val activeCount = (members.size - passiveCount).coerceAtLeast(0)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.12f)),
        border = BorderStroke(1.5.dp, if (isExpired || isTampered) MaterialTheme.colorScheme.error else accent.copy(alpha = 0.7f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (set.isLocked) Icons.Filled.Lock else Icons.Filled.FolderOpen,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        set.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${members.size} macro ($activeCount Aktif • $passiveCount Pasif)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (set.description.isNotBlank()) {
                        Text(
                            set.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu Set")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Kembali ke Semua") },
                            leadingIcon = { Icon(Icons.Filled.Folder, null) },
                            onClick = { menuOpen = false; onBackToAll() }
                        )
                        DropdownMenuItem(
                            text = { Text("Edit Nama & Warna") },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menuOpen = false; onEditSet() }
                        )
                        DropdownMenuItem(
                            text = { Text("Duplikat Set") },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                            onClick = { menuOpen = false; onDuplicateSet() }
                        )
                        DropdownMenuItem(
                            text = { Text("Simpan Set ke Download (.zip)") },
                            leadingIcon = { Icon(Icons.Filled.FileDownload, null) },
                            onClick = { menuOpen = false; onExportSetDownloads() }
                        )
                        DropdownMenuItem(
                            text = { Text("Hapus Set", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDeleteSet() }
                        )
                    }
                }
            }

            if (set.isLocked || remainingLabel != null || isTampered) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isTampered) FeatureTag("⚠ Set Tidak Valid", Color(0xFFEF4444))
                    if (set.isLocked) FeatureTag("🔒 Set Terkunci", Color(0xFFA78BFA))
                    if (remainingLabel != null) {
                        val expColor = if (isExpired) Color(0xFFEF4444) else Color(0xFFF59E0B)
                        FeatureTag((if (isExpired) "⏰ " else "⏳ ") + remainingLabel, expColor)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilledTonalButton(
                    onClick = onCreateMacroInSet,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f).height(34.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Macro", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onManageMembers,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f).height(34.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAddCheck, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Isi Set", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onOpenSecurity,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f).height(34.dp)
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Kunci", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Button(
                    onClick = onExportSet,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f).height(34.dp)
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Ekspor", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun MacroListCard(
    macro: Macro,
    parentSet: MacroSet? = null,
    isActive: Boolean,
    nowMs: Long,
    screenW: Int,
    screenH: Int,
    logoPath: String?,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onLaunchOverlay: () -> Unit,
    onChangeLogo: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenAutoScale: () -> Unit,
    onTogglePassivePull: () -> Unit,
    onMoveToSet: () -> Unit = {},
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onExportDirectToDownloads: () -> Unit = onExport,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val allActions = remember(macro) { macro.allActionsSequence().toList() }
    val hasImage = allActions.any {
        it is ImageAction ||
            it is com.example.data.TrackObjectAction ||
            (it is com.example.data.BranchRouterAction && it.rules.any { r -> r.conditionType == com.example.data.BranchConditionType.IMAGE })
    }
    val hasText = allActions.any {
        it is TextAction ||
            (it is com.example.data.BranchRouterAction && it.rules.any { r -> r.conditionType == com.example.data.BranchConditionType.TEXT })
    }
    val hasColor = allActions.any {
        it is ColorAction ||
            (it is com.example.data.BranchRouterAction && it.rules.any { r -> r.conditionType == com.example.data.BranchConditionType.COLOR })
    }
    val hasBranch = macro.branches.isNotEmpty() || allActions.any {
        it is com.example.data.BranchRouterAction || it is com.example.data.GoToBranchAction
    }
    val hasRecord = allActions.any { it is RecordAction }
    val hasRandom = allActions.any { it.chancePercent < 100 }
    val stuckActionCount = remember(macro) { macro.countStuckActions() }
    val hasWatchdog = macro.autoRecoverStuckSec > 0 && stuckActionCount > 0
    val hasBannedGuard = macro.bannedGuardEnabled && macro.bannedImages.any { it.isNotBlank() }
    val hasMultiAccount = macro.accountSwitch.enabled
    val scaleTransform = remember(macro.sourceWidth, macro.sourceHeight, macro.autoScaleResolution, macro.scaleMode, screenW, screenH) {
        ResolutionScaler.forMacro(macro, screenW, screenH)
    }

    val effectivelyLocked = MacroSecurity.isEffectivelyLocked(macro, parentSet)
    val isExpired = MacroSecurity.isExpired(macro, nowMs) || (parentSet != null && MacroSecurity.isSetExpired(parentSet, nowMs))
    val isTampered = MacroSecurity.isTampered(macro) || (parentSet != null && MacroSecurity.isSetTampered(parentSet))
    val remainingLabel = MacroSecurity.remainingTimeLabel(macro, nowMs)
        ?: parentSet?.let { MacroSecurity.remainingSetTimeLabel(it, nowMs) }
    val logoBmp = rememberAssetThumbnail(logoPath, macro.updatedAt)

    val badgeColor = when {
        isExpired || isTampered -> Color(0xFFEF4444)
        hasImage -> Color(0xFF10B981)
        hasText -> Color(0xFF22D3EE)
        hasColor -> Color(0xFFA78BFA)
        else -> MaterialTheme.colorScheme.primary
    }
    val badgeIcon = when {
        hasImage -> Icons.Filled.ImageSearch
        hasText -> Icons.Filled.TextFields
        else -> Icons.Filled.AdsClick
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isExpired || isTampered -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.14f)
                isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (isActive || isExpired) 1.5.dp else 1.dp,
            color = when {
                isExpired || isTampered -> MaterialTheme.colorScheme.error.copy(alpha = 0.65f)
                isActive -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (logoBmp != null) Color(0xFF0A1020) else badgeColor.copy(alpha = 0.15f))
                        .clickable(onClick = onChangeLogo),
                    contentAlignment = Alignment.Center
                ) {
                    if (logoBmp != null) {
                        Image(
                            bitmap = logoBmp,
                            contentDescription = "Logo ${macro.name}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = badgeIcon,
                            contentDescription = null,
                            tint = badgeColor,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            macro.name.ifBlank { "Macro Baru" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (effectivelyLocked) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = "Terkunci",
                                tint = Color(0xFFA78BFA),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        if (isActive) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF10B981))
                            )
                        }
                    }
                    val loopLabel = if (macro.loopCount <= 0) "Loop ∞" else "Loop ${macro.loopCount}×"
                    val watchdogSub = if (stuckActionCount > 0) " • Anti-Macet $stuckActionCount" else ""
                    Text(
                        "${macro.countActions()} aksi • $loopLabel$watchdogSub",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu", modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Auto-Scale Layar (Tanpa Sandi)") },
                            leadingIcon = { Icon(Icons.Filled.AspectRatio, null) },
                            onClick = { menuOpen = false; onOpenAutoScale() }
                        )
                        DropdownMenuItem(
                            text = { Text("Pindah ke Macro Set") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
                            onClick = { menuOpen = false; onMoveToSet() }
                        )
                        DropdownMenuItem(
                            text = { Text("Ubah Foto Logo") },
                            leadingIcon = { Icon(Icons.Filled.AddPhotoAlternate, null) },
                            onClick = { menuOpen = false; onChangeLogo() }
                        )
                        DropdownMenuItem(
                            text = { Text("Kunci, Tenggat & Pasif") },
                            leadingIcon = { Icon(Icons.Filled.Lock, null) },
                            onClick = { menuOpen = false; onOpenSecurity() }
                        )
                        if (macro.isPassive && !isExpired && !isTampered) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (macro.passiveEnabled) "Mode Pasif: Matikan (OFF)"
                                        else "Mode Pasif: Nyalakan (ON)"
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (macro.passiveEnabled) Icons.Filled.LinkOff else Icons.Filled.Link,
                                        contentDescription = null
                                    )
                                },
                                onClick = { menuOpen = false; onTogglePassivePull() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Duplikat") },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                            onClick = { menuOpen = false; onDuplicate() }
                        )
                        DropdownMenuItem(
                            text = { Text("Ekspor Macro + Gambar (.zip)") },
                            leadingIcon = { Icon(Icons.Filled.FileDownload, null) },
                            onClick = { menuOpen = false; onExport() }
                        )
                        DropdownMenuItem(
                            text = { Text("Simpan ke Download (.zip)") },
                            leadingIcon = { Icon(Icons.Filled.FileDownload, null) },
                            onClick = { menuOpen = false; onExportDirectToDownloads() }
                        )
                        DropdownMenuItem(
                            text = { Text("Hapus", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
            }

            // Security / Expiry / Passive / Auto-Scale / Watchdog / Banned Guard / Multi-Account badges row
            if (effectivelyLocked || macro.isPassive || remainingLabel != null || isTampered || scaleTransform.isScaling || hasWatchdog || hasBannedGuard || hasMultiAccount) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isTampered) FeatureTag("⚠ Tidak Valid", Color(0xFFEF4444))
                    if (effectivelyLocked) FeatureTag("🔒 Terkunci", Color(0xFFA78BFA))
                    if (macro.isPassive) {
                        FeatureTag(
                            text = if (macro.passiveEnabled) "🔗 Pasif ON" else "⛔ Pasif OFF",
                            color = if (macro.passiveEnabled) Color(0xFF10B981) else Color(0xFFF59E0B)
                        )
                    }
                    if (scaleTransform.isScaling || effectivelyLocked) {
                        val scaleTint = if (macro.autoScaleResolution) Color(0xFF06B6D4) else Color(0xFF64748B)
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onOpenAutoScale),
                            shape = RoundedCornerShape(6.dp),
                            color = scaleTint.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = if (macro.autoScaleResolution) "📐 Auto-Scale" else "📐 Scale OFF",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = scaleTint,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (hasWatchdog) {
                        FeatureTag("🛡 Anti-Macet ${macro.autoRecoverStuckSec}d", Color(0xFFF97316))
                    }
                    if (hasBannedGuard) {
                        FeatureTag("⛔ Banned ${macro.bannedImages.size}", Color(0xFFEF4444))
                    }
                    if (hasMultiAccount) {
                        FeatureTag("👥 ${macro.accountSwitch.accounts.size} Akun", Color(0xFF0EA5E9))
                    }
                    if (remainingLabel != null) {
                        val expColor = if (isExpired) Color(0xFFEF4444) else Color(0xFFF59E0B)
                        FeatureTag((if (isExpired) "⏰ " else "⏳ ") + remainingLabel, expColor)
                    }
                }
            }

            // Feature tags + action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (hasBranch) FeatureTag("Cabang", Color(0xFF06B6D4))
                    if (hasImage) FeatureTag("Gambar", Color(0xFF10B981))
                    if (hasText) FeatureTag("Teks", Color(0xFF22D3EE))
                    if (hasColor) FeatureTag("Warna", Color(0xFFA78BFA))
                    if (hasRecord) FeatureTag("Rekaman", Color(0xFFEF4444))
                    if (hasRandom) FeatureTag("🎲 Acak", Color(0xFFF59E0B))
                    if (!hasBranch && !hasImage && !hasText && !hasColor && !hasRecord && !hasRandom) FeatureTag("Clicker", Color(0xFF22D3EE))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onEdit,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            imageVector = if (effectivelyLocked) Icons.Filled.Lock else Icons.Filled.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Edit", style = MaterialTheme.typography.labelMedium)
                    }
                    when {
                        isExpired || isTampered -> {
                            OutlinedButton(
                                onClick = onLaunchOverlay,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("Habis", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        macro.isPassive -> {
                            if (macro.passiveEnabled) {
                                FilledTonalButton(
                                    onClick = onTogglePassivePull,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color(0xFF10B981).copy(alpha = 0.18f),
                                        contentColor = Color(0xFF10B981)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Pasif ON", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = onTogglePassivePull,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF59E0B)),
                                    border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.6f)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(Icons.Filled.LinkOff, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Pasif OFF", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        else -> {
                            Button(
                                onClick = onLaunchOverlay,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Mulai", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureTag(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = color.copy(alpha = 0.14f)
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}
