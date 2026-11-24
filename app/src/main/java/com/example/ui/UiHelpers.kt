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
import androidx.compose.material.icons.filled.TextFields
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
    ActionKind.TEXT -> Icons.Filled.TextFields
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
    ActionKind.TEXT -> Color(0xFF0EA5E9)
    ActionKind.COLOR -> Color(0xFF8B5CF6)
    ActionKind.LOOP -> Color(0xFFF59E0B)
    ActionKind.RUN_MACRO -> Color(0xFFEC4899)
    ActionKind.RECORD -> Color(0xFFEF4444)
    ActionKind.RESTART -> Color(0xFF06B6D4)
    ActionKind.STOP -> Color(0xFFDC2626)
}

private data class CachedThumbnail(
    val lastModified: Long,
    val bitmap: ImageBitmap
)

private object ThumbnailCache {
    // 6 MB cap measured in KB so scrolling large image libraries stays smooth without high RAM usage
    private val cache = object : android.util.LruCache<String, CachedThumbnail>(6 * 1024) {
        override fun sizeOf(key: String, value: CachedThumbnail): Int =
            maxOf(1, (value.bitmap.width * value.bitmap.height * 4) / 1024)
    }

    fun get(key: String): CachedThumbnail? = synchronized(cache) { cache.get(key) }

    fun put(key: String, entry: CachedThumbnail) {
        synchronized(cache) { cache.put(key, entry) }
    }

    fun remove(key: String) {
        synchronized(cache) { cache.remove(key) }
    }
}

private fun decodeSampledThumbnail(file: File, maxDimPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    val safeMax = maxDimPx.coerceAtLeast(64)
    while (bounds.outWidth / (sample * 2) >= safeMax || bounds.outHeight / (sample * 2) >= safeMax) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
}

@Composable
fun rememberAssetThumbnail(path: String?, cacheKey: Long = 0L, maxDimPx: Int = 256): ImageBitmap? {
    val memKey = if (path.isNullOrBlank()) null else "$path|$maxDimPx"
    var bitmap by remember(memKey, cacheKey) {
        mutableStateOf(memKey?.let { ThumbnailCache.get(it)?.bitmap })
    }
    LaunchedEffect(memKey, cacheKey) {
        if (path.isNullOrBlank() || memKey == null) {
            bitmap = null
            return@LaunchedEffect
        }
        val resolved = withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists()) {
                ThumbnailCache.remove(memKey)
                return@withContext null
            }
            val lastMod = file.lastModified()
            val cached = ThumbnailCache.get(memKey)
            if (cached != null && cached.lastModified == lastMod) {
                return@withContext cached.bitmap
            }
            runCatching {
                decodeSampledThumbnail(file, maxDimPx)?.also { decoded ->
                    ThumbnailCache.put(memKey, CachedThumbnail(lastMod, decoded))
                }
            }.getOrNull()
        }
        bitmap = resolved
    }
    return bitmap
}

fun parseHexColorOrNull(hex: String): Color? {
    val clean = hex.trim().removePrefix("#")
    if (clean.length != 6) return null
    val rgb = clean.toIntOrNull(16) ?: return null
    return Color(0xFF000000.toInt() or rgb)
}
