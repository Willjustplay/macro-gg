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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.example.data.RecordAction
import com.example.data.ResolutionScaler
import com.example.data.TextAction
import com.example.data.countActions
import com.example.data.countStuckActions
import com.example.data.walk

@Composable
fun MacroHomeScreen(
    macros: List<Macro>,
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
    onImportMacro: () -> Unit,
    onEditMacro: (Macro) -> Unit,
    onLaunchMacroOverlay: (Macro) -> Unit,
    onChangeMacroLogo: (Macro) -> Unit,
    onOpenMacroSecurity: (Macro) -> Unit,
    onOpenAutoScale: (Macro) -> Unit,
    onTogglePassivePull: (Macro) -> Unit,
    onDuplicateMacro: (Macro) -> Unit,
    onExportMacro: (Macro) -> Unit,
    onExportMacroDirectToDownloads: (Macro) -> Unit = onExportMacro,
    onDeleteMacro: (Macro) -> Unit
) {
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

        // Section Header
        item(key = "list_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Macro Tersimpan",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                ) {
                    Text(
                        "${macros.size} item",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }

        if (macros.isEmpty()) {
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
            items(macros, key = { it.id }) { macro ->
                MacroListCard(
                    macro = macro,
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
                    onDuplicate = { onDuplicateMacro(macro) },
                    onExport = { onExportMacro(macro) },
                    onExportDirectToDownloads = { onExportMacroDirectToDownloads(macro) },
                    onDelete = { onDeleteMacro(macro) }
                )
            }
        }
    }
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
private fun MacroListCard(
    macro: Macro,
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
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onExportDirectToDownloads: () -> Unit = onExport,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val allActions = remember(macro) { macro.actions.walk().toList() }
    val hasImage = allActions.any { it is ImageAction }
    val hasText = allActions.any { it is TextAction }
    val hasColor = allActions.any { it is ColorAction }
    val hasRecord = allActions.any { it is RecordAction }
    val hasRandom = allActions.any { it.chancePercent < 100 }
    val stuckActionCount = remember(macro) { macro.countStuckActions() }
    val hasWatchdog = macro.autoRecoverStuckSec > 0 && stuckActionCount > 0
    val scaleTransform = remember(macro.sourceWidth, macro.sourceHeight, macro.autoScaleResolution, macro.scaleMode, screenW, screenH) {
        ResolutionScaler.forMacro(macro, screenW, screenH)
    }

    val isExpired = MacroSecurity.isExpired(macro, nowMs)
    val isTampered = MacroSecurity.isTampered(macro)
    val remainingLabel = MacroSecurity.remainingTimeLabel(macro, nowMs)
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
                        if (macro.isLocked) {
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

            // Security / Expiry / Passive / Auto-Scale / Watchdog badges row (Auto-Scale chip is always clickable without password)
            if (macro.isLocked || macro.isPassive || remainingLabel != null || isTampered || scaleTransform.isScaling || hasWatchdog) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isTampered) FeatureTag("⚠ Tidak Valid", Color(0xFFEF4444))
                    if (macro.isLocked) FeatureTag("🔒 Terkunci", Color(0xFFA78BFA))
                    if (macro.isPassive) {
                        FeatureTag(
                            text = if (macro.passiveEnabled) "🔗 Pasif ON" else "⛔ Pasif OFF",
                            color = if (macro.passiveEnabled) Color(0xFF10B981) else Color(0xFFF59E0B)
                        )
                    }
                    if (scaleTransform.isScaling || macro.isLocked) {
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
                    if (hasImage) FeatureTag("Gambar", Color(0xFF10B981))
                    if (hasText) FeatureTag("Teks", Color(0xFF22D3EE))
                    if (hasColor) FeatureTag("Warna", Color(0xFFA78BFA))
                    if (hasRecord) FeatureTag("Rekaman", Color(0xFFEF4444))
                    if (hasRandom) FeatureTag("🎲 Acak", Color(0xFFF59E0B))
                    if (!hasImage && !hasText && !hasColor && !hasRecord && !hasRandom) FeatureTag("Clicker", Color(0xFF22D3EE))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onEdit,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            imageVector = if (macro.isLocked) Icons.Filled.Lock else Icons.Filled.Edit,
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
