package com.example.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.automation.EngineState
import com.example.automation.PickedPixel
import com.example.data.LuaScript
import com.example.data.RunHistoryEntry
import com.example.data.UiState
import com.example.data.VisualMacro
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.theme.StatusEmerald

@Composable
fun DashboardScreen(
    displayName: String,
    permissionHealth: PermissionHealth,
    engineState: EngineState,
    activeTargetName: String,
    isOverlayActive: Boolean,
    lastPickedPixel: PickedPixel?,
    macrosState: UiState<List<VisualMacro>>,
    scriptsState: UiState<List<LuaScript>>,
    runHistoryState: UiState<List<RunHistoryEntry>>,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onStopMediaProjection: () -> Unit,
    onToggleOverlay: () -> Unit,
    onStopEngine: () -> Unit,
    onCreateStarterMacro: () -> Unit,
    onCreateStarterScript: () -> Unit,
    onNavigateToMacros: () -> Unit,
    onNavigateToLua: () -> Unit
) {
    val macroCount = (macrosState as? UiState.Success)?.data?.size ?: 0
    val scriptCount = (scriptsState as? UiState.Success)?.data?.size ?: 0
    val runCount = (runHistoryState as? UiState.Success)?.data?.size ?: 0

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero Command Banner
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(186.dp)
                        .clip(RoundedCornerShape(26.dp))
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_taplua_hero_1795424703000),
                        contentDescription = "Banner TapLua",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color(0xE60B1923), Color(0x880B1923))
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Halo, $displayName",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color(0xFF9CF1F0)
                                )
                                Text(
                                    text = "TapLua Control Hub",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            EngineStatusPill(engineState)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = onToggleOverlay,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isOverlayActive) StatusCrimson else MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("launch_overlay_button")
                            ) {
                                Icon(
                                    imageVector = if (isOverlayActive) Icons.Filled.Stop else Icons.Filled.Layers,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isOverlayActive) {
                                        stringResource(R.string.action_stop_overlay)
                                    } else {
                                        stringResource(R.string.action_launch_overlay)
                                    }
                                )
                            }
                            if (engineState == EngineState.RUNNING || engineState == EngineState.PAUSED) {
                                FilledTonalButton(
                                    onClick = onStopEngine,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("dashboard_stop_engine_button")
                                ) {
                                    Icon(Icons.Filled.Stop, contentDescription = "Hentikan Engine")
                                }
                            }
                        }
                    }
                }
            }

            // Quick Stats Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatTile(
                        title = "Macro",
                        value = macroCount.toString(),
                        icon = Icons.Filled.TouchApp,
                        onClick = onNavigateToMacros,
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        title = "Script Lua",
                        value = scriptCount.toString(),
                        icon = Icons.Filled.Code,
                        onClick = onNavigateToLua,
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        title = "Riwayat",
                        value = runCount.toString(),
                        icon = Icons.Filled.History,
                        onClick = {},
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Active Target & Color Spy Telemetry
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Target Overlay Aktif",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = activeTargetName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (lastPickedPixel != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                val swatchColor = runCatching {
                                    Color(android.graphics.Color.parseColor(lastPickedPixel.hexColor))
                                }.getOrDefault(MaterialTheme.colorScheme.primary)
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(swatchColor)
                                )
                                Text(
                                    text = "Color Spy Terakhir: (${lastPickedPixel.x}, ${lastPickedPixel.y}) • ${lastPickedPixel.hexColor}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // Permission Health Hub
            item {
                Text(
                    text = stringResource(R.string.perm_hub_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PermissionCard(
                        icon = Icons.Filled.AccessibilityNew,
                        title = stringResource(R.string.perm_accessibility_title),
                        description = stringResource(R.string.perm_accessibility_desc),
                        isActive = permissionHealth.accessibilityEnabled,
                        statusText = if (permissionHealth.accessibilityEnabled) {
                            stringResource(R.string.perm_status_active)
                        } else {
                            stringResource(R.string.perm_status_required)
                        },
                        actionLabel = if (permissionHealth.accessibilityEnabled) "Pengaturan" else "Aktifkan",
                        onAction = onOpenAccessibilitySettings,
                        testTag = "perm_accessibility_button"
                    )
                    PermissionCard(
                        icon = Icons.Filled.Layers,
                        title = stringResource(R.string.perm_overlay_title),
                        description = stringResource(R.string.perm_overlay_desc),
                        isActive = permissionHealth.overlayEnabled,
                        statusText = if (permissionHealth.overlayEnabled) {
                            stringResource(R.string.perm_status_active)
                        } else {
                            stringResource(R.string.perm_status_required)
                        },
                        actionLabel = if (permissionHealth.overlayEnabled) "Pengaturan" else "Izinkan",
                        onAction = onRequestOverlayPermission,
                        testTag = "perm_overlay_button"
                    )
                    PermissionCard(
                        icon = Icons.Filled.Colorize,
                        title = stringResource(R.string.perm_capture_title),
                        description = stringResource(R.string.perm_capture_desc),
                        isActive = permissionHealth.colorCaptureActive,
                        statusText = if (permissionHealth.colorCaptureActive) {
                            stringResource(R.string.perm_status_active)
                        } else {
                            stringResource(R.string.perm_status_ondemand)
                        },
                        actionLabel = if (permissionHealth.colorCaptureActive) "Matikan" else "Mulai",
                        onAction = if (permissionHealth.colorCaptureActive) onStopMediaProjection else onRequestMediaProjection,
                        testTag = "perm_capture_button"
                    )
                }
            }

            // Quick Starter Actions
            item {
                Text(
                    text = "Mulai Cepat",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onCreateStarterMacro,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("starter_macro_button")
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Template Macro")
                    }
                    OutlinedButton(
                        onClick = onCreateStarterScript,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("starter_lua_button")
                    ) {
                        Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Template Lua")
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineStatusPill(state: EngineState) {
    val (color, label) = when (state) {
        EngineState.RUNNING -> StatusEmerald to "Berjalan"
        EngineState.PAUSED -> StatusAmber to "Dijeda"
        EngineState.ERROR -> StatusCrimson to "Error"
        EngineState.IDLE -> Color(0xFF4CDADA) to "Siap"
    }
    Surface(
        color = color.copy(alpha = 0.22f),
        shape = RoundedCornerShape(50)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
        }
    }
}

@Composable
private fun StatTile(
    title: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    isActive: Boolean,
    statusText: String,
    actionLabel: String,
    onAction: () -> Unit,
    testTag: String
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isActive) StatusEmerald.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isActive) StatusEmerald else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = if (isActive) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = statusText,
                        tint = if (isActive) StatusEmerald else StatusAmber,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onAction,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag(testTag)
            ) {
                Text(text = actionLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
