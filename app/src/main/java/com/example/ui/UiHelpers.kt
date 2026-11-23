package com.example.ui

import android.graphics.BitmapFactory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.data.ActionKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun ActionKind.icon(): ImageVector = when (this) {
    ActionKind.CLICK -> Icons.Filled.AdsClick
    ActionKind.LONG_PRESS -> Icons.Filled.TouchApp
    ActionKind.SWIPE -> Icons.Filled.Swipe
    ActionKind.ZOOM -> Icons.Filled.ZoomIn
    ActionKind.WAIT -> Icons.Filled.HourglassEmpty
    ActionKind.IMAGE -> Icons.Filled.ImageSearch
    ActionKind.COLOR -> Icons.Filled.Colorize
    ActionKind.LOOP -> Icons.Filled.Loop
    ActionKind.RUN_MACRO -> Icons.Filled.Link
    ActionKind.RECORD -> Icons.Filled.FiberManualRecord
    ActionKind.RESTART -> Icons.Filled.Replay
    ActionKind.STOP -> Icons.Filled.StopCircle
}

fun ActionKind.accentColor(): Color = when (this) {
    ActionKind.CLICK -> Color(0xFF0D9488)
    ActionKind.LONG_PRESS -> Color(0xFF4F46E5)
    ActionKind.SWIPE -> Color(0xFF0284C7)
    ActionKind.ZOOM -> Color(0xFF0891B2)
    ActionKind.WAIT -> Color(0xFF64748B)
    ActionKind.IMAGE -> Color(0xFF10B981)
    ActionKind.COLOR -> Color(0xFF8B5CF6)
    ActionKind.LOOP -> Color(0xFFF59E0B)
    ActionKind.RUN_MACRO -> Color(0xFFEC4899)
    ActionKind.RECORD -> Color(0xFFEF4444)
    ActionKind.RESTART -> Color(0xFF06B6D4)
    ActionKind.STOP -> Color(0xFFDC2626)
}

@Composable
fun rememberAssetThumbnail(path: String?, cacheKey: Long = 0L): ImageBitmap? {
    var bitmap by remember(path, cacheKey) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, cacheKey) {
        if (path.isNullOrBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists()) return@withContext null
            runCatching {
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

fun parseHexColorOrNull(hex: String): Color? {
    val clean = hex.trim().removePrefix("#")
    if (clean.length != 6) return null
    val rgb = clean.toIntOrNull(16) ?: return null
    return Color(0xFF000000.toInt() or rgb)
}
