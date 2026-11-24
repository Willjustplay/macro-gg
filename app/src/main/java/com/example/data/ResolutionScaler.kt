package com.example.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure-Kotlin resolution auto-scaling engine so macros authored on one screen
 * (e.g. 1080x2400 phone) map accurately onto another screen (e.g. 720x1280 or 1080x1920 Redfinger)
 * across clicks, swipes, long-presses, pinch zooms, recorded gestures, color points, regions,
 * overlay markers, and image templates.
 */
object ResolutionScaler {

    data class ScreenTransform(
        val srcW: Int,
        val srcH: Int,
        val dstW: Int,
        val dstH: Int,
        val mode: ResolutionScaleMode,
        val enabled: Boolean
    ) {
        val isScaling: Boolean =
            enabled && srcW > 0 && srcH > 0 && dstW > 0 && dstH > 0 && (srcW != dstW || srcH != dstH)

        val scaleX: Double = if (isScaling) dstW.toDouble() / srcW.toDouble() else 1.0
        val scaleY: Double = if (isScaling) dstH.toDouble() / srcH.toDouble() else 1.0

        /** Uniform scale driven by the short side (matches Unity CanvasScaler & 16:9 mobile games). */
        val uniformScale: Double =
            if (isScaling) min(dstW, dstH).toDouble() / min(srcW, srcH).toDouble() else 1.0

        fun scalePoint(x: Int, y: Int): Pair<Int, Int> {
            if (!isScaling) return x to y
            val nx: Int
            val ny: Int
            when (mode) {
                ResolutionScaleMode.PROPORTIONAL -> {
                    nx = (x * scaleX).roundToInt()
                    ny = (y * scaleY).roundToInt()
                }
                ResolutionScaleMode.CENTER_FIT -> {
                    nx = (dstW / 2.0 + (x - srcW / 2.0) * uniformScale).roundToInt()
                    ny = (dstH / 2.0 + (y - srcH / 2.0) * uniformScale).roundToInt()
                }
            }
            return nx.coerceIn(0, dstW - 1) to ny.coerceIn(0, dstH - 1)
        }

        /** Maps a live screen coordinate back into the macro's authored [srcW]x[srcH] coordinate space. */
        fun unscalePoint(x: Int, y: Int): Pair<Int, Int> {
            if (!isScaling) return x to y
            val ox: Int
            val oy: Int
            when (mode) {
                ResolutionScaleMode.PROPORTIONAL -> {
                    ox = (x / scaleX).roundToInt()
                    oy = (y / scaleY).roundToInt()
                }
                ResolutionScaleMode.CENTER_FIT -> {
                    val s = uniformScale.coerceAtLeast(1e-6)
                    ox = (srcW / 2.0 + (x - dstW / 2.0) / s).roundToInt()
                    oy = (srcH / 2.0 + (y - dstH / 2.0) / s).roundToInt()
                }
            }
            return ox.coerceIn(0, srcW - 1) to oy.coerceIn(0, srcH - 1)
        }

        /** Scales relative pixel offsets (such as ImageAction.offsetX / offsetY). */
        fun scaleDelta(dx: Int, dy: Int): Pair<Int, Int> {
            if (!isScaling) return dx to dy
            return when (mode) {
                ResolutionScaleMode.PROPORTIONAL ->
                    (dx * scaleX).roundToInt() to (dy * scaleY).roundToInt()
                ResolutionScaleMode.CENTER_FIT ->
                    (dx * uniformScale).roundToInt() to (dy * uniformScale).roundToInt()
            }
        }

        /** Scales radial/linear pixel distances (such as ZoomAction.distancePx or jitterPx). */
        fun scaleDistance(px: Int): Int {
            if (!isScaling || px <= 0) return px
            val factor = when (mode) {
                ResolutionScaleMode.PROPORTIONAL -> min(scaleX, scaleY)
                ResolutionScaleMode.CENTER_FIT -> uniformScale
            }
            return (px * factor).roundToInt().coerceAtLeast(1)
        }

        fun scaleRegion(region: Region): Region {
            if (!isScaling) return region
            val l: Int
            val t: Int
            val r: Int
            val b: Int
            when (mode) {
                ResolutionScaleMode.PROPORTIONAL -> {
                    l = (region.left * scaleX).roundToInt().coerceIn(0, dstW - 1)
                    t = (region.top * scaleY).roundToInt().coerceIn(0, dstH - 1)
                    r = (region.right * scaleX).roundToInt().coerceIn(l + 1, dstW)
                    b = (region.bottom * scaleY).roundToInt().coerceIn(t + 1, dstH)
                }
                ResolutionScaleMode.CENTER_FIT -> {
                    l = (dstW / 2.0 + (region.left - srcW / 2.0) * uniformScale).roundToInt().coerceIn(0, dstW - 1)
                    t = (dstH / 2.0 + (region.top - srcH / 2.0) * uniformScale).roundToInt().coerceIn(0, dstH - 1)
                    r = (dstW / 2.0 + (region.right - srcW / 2.0) * uniformScale).roundToInt().coerceIn(l + 1, dstW)
                    b = (dstH / 2.0 + (region.bottom - srcH / 2.0) * uniformScale).roundToInt().coerceIn(t + 1, dstH)
                }
            }
            return Region(l, t, r, b)
        }

        fun unscaleRegion(region: Region): Region {
            if (!isScaling) return region
            val l: Int
            val t: Int
            val r: Int
            val b: Int
            when (mode) {
                ResolutionScaleMode.PROPORTIONAL -> {
                    l = (region.left / scaleX).roundToInt().coerceIn(0, srcW - 1)
                    t = (region.top / scaleY).roundToInt().coerceIn(0, srcH - 1)
                    r = (region.right / scaleX).roundToInt().coerceIn(l + 1, srcW)
                    b = (region.bottom / scaleY).roundToInt().coerceIn(t + 1, srcH)
                }
                ResolutionScaleMode.CENTER_FIT -> {
                    val s = uniformScale.coerceAtLeast(1e-6)
                    l = (srcW / 2.0 + (region.left - dstW / 2.0) / s).roundToInt().coerceIn(0, srcW - 1)
                    t = (srcH / 2.0 + (region.top - dstH / 2.0) / s).roundToInt().coerceIn(0, srcH - 1)
                    r = (srcW / 2.0 + (region.right - dstW / 2.0) / s).roundToInt().coerceIn(l + 1, srcW)
                    b = (srcH / 2.0 + (region.bottom - dstH / 2.0) / s).roundToInt().coerceIn(t + 1, srcH)
                }
            }
            return Region(l, t, r, b)
        }

        fun scaleRecordedGesture(gesture: RecordedGesture): RecordedGesture {
            if (!isScaling) return gesture
            return gesture.copy(
                strokes = gesture.strokes.map { stroke ->
                    val scaled = ArrayList<Int>(stroke.points.size)
                    var i = 0
                    while (i + 1 < stroke.points.size) {
                        val (nx, ny) = scalePoint(stroke.points[i], stroke.points[i + 1])
                        scaled += nx
                        scaled += ny
                        i += 2
                    }
                    stroke.copy(points = scaled)
                }
            )
        }

        fun unscaleRecordedGesture(gesture: RecordedGesture): RecordedGesture {
            if (!isScaling) return gesture
            return gesture.copy(
                strokes = gesture.strokes.map { stroke ->
                    val orig = ArrayList<Int>(stroke.points.size)
                    var i = 0
                    while (i + 1 < stroke.points.size) {
                        val (ox, oy) = unscalePoint(stroke.points[i], stroke.points[i + 1])
                        orig += ox
                        orig += oy
                        i += 2
                    }
                    stroke.copy(points = orig)
                }
            )
        }

        /**
         * Ordered (scaleX, scaleY) candidates for matching a cropped image template across
         * different resolutions: tries uniform short-side scale first (how most mobile games scale UI),
         * then aspect-stretched / long-side scale if ratios differ, and finally 1:1 original as fallback.
         */
        fun templateScaleCandidates(): List<Pair<Double, Double>> {
            if (!isScaling) return listOf(1.0 to 1.0)
            val result = ArrayList<Pair<Double, Double>>(4)
            fun addUnique(sx: Double, sy: Double) {
                if (sx <= 0.1 || sy <= 0.1 || sx > 6.0 || sy > 6.0) return
                if (result.none { abs(it.first - sx) < 0.025 && abs(it.second - sy) < 0.025 }) {
                    result += sx to sy
                }
            }
            addUnique(uniformScale, uniformScale)
            addUnique(scaleX, scaleY)
            val longScale = max(dstW, dstH).toDouble() / max(srcW, srcH).toDouble()
            addUnique(longScale, longScale)
            addUnique(1.0, 1.0)
            return result
        }

        fun describe(): String = "${srcW}×${srcH} → ${dstW}×${dstH} (${mode.label})"
    }

    /**
     * Builds a [ScreenTransform] for [macro] on a screen of [dstW]x[dstH].
     * Automatically aligns Portrait/Landscape orientation so macros authored for a landscape game
     * while saved from a portrait app UI never squash across swapped axes.
     */
    fun forMacro(macro: Macro, dstW: Int, dstH: Int): ScreenTransform {
        if (!macro.autoScaleResolution || macro.sourceWidth <= 0 || macro.sourceHeight <= 0 || dstW <= 0 || dstH <= 0) {
            return ScreenTransform(
                srcW = macro.sourceWidth,
                srcH = macro.sourceHeight,
                dstW = dstW,
                dstH = dstH,
                mode = macro.scaleMode,
                enabled = false
            )
        }
        var effSrcW = macro.sourceWidth
        var effSrcH = macro.sourceHeight
        if ((effSrcW > effSrcH) != (dstW > dstH)) {
            val tmp = effSrcW
            effSrcW = effSrcH
            effSrcH = tmp
        }
        return ScreenTransform(
            srcW = effSrcW,
            srcH = effSrcH,
            dstW = dstW,
            dstH = dstH,
            mode = macro.scaleMode,
            enabled = true
        )
    }

    /** Returns true if [macro] contains any action with screen coordinates or search regions. */
    fun hasCoordinateActions(macro: Macro): Boolean = macro.actions.walk().any { a ->
        when (a) {
            is ClickAction, is LongPressAction, is SwipeAction, is ZoomAction, is ColorAction -> true
            is RecordAction -> a.gestures.isNotEmpty()
            is ImageAction -> a.region != null || a.imageName.isNotBlank()
            is TextAction -> a.region != null
            else -> false
        }
    }

    /**
     * Infers the true Portrait/Landscape source resolution of a legacy macro (with `sourceWidth == 0`)
     * on a device whose screen is [deviceW]x[deviceH] by checking whether any stored coordinates
     * extend past the short side along X (landscape) or Y (portrait).
     */
    fun inferSourceSizeForDevice(macro: Macro, deviceW: Int, deviceH: Int): Pair<Int, Int> {
        if (deviceW <= 0 || deviceH <= 0) return 0 to 0
        val shortSide = min(deviceW, deviceH)
        val longSide = max(deviceW, deviceH)
        var maxX = 0
        var maxY = 0
        for (a in macro.actions.walk()) {
            when (a) {
                is ClickAction -> {
                    maxX = max(maxX, a.x)
                    maxY = max(maxY, a.y)
                }
                is LongPressAction -> {
                    maxX = max(maxX, a.x)
                    maxY = max(maxY, a.y)
                }
                is SwipeAction -> {
                    maxX = max(maxX, max(a.x1, a.x2))
                    maxY = max(maxY, max(a.y1, a.y2))
                }
                is ZoomAction -> {
                    maxX = max(maxX, a.cx)
                    maxY = max(maxY, a.cy)
                }
                is ColorAction -> {
                    maxX = max(maxX, a.x)
                    maxY = max(maxY, a.y)
                }
                is ImageAction -> a.region?.let {
                    maxX = max(maxX, it.right)
                    maxY = max(maxY, it.bottom)
                }
                is TextAction -> a.region?.let {
                    maxX = max(maxX, it.right)
                    maxY = max(maxY, it.bottom)
                }
                is RecordAction -> a.gestures.forEach { g ->
                    g.strokes.forEach { s ->
                        var i = 0
                        while (i + 1 < s.points.size) {
                            maxX = max(maxX, s.points[i])
                            maxY = max(maxY, s.points[i + 1])
                            i += 2
                        }
                    }
                }
                else -> Unit
            }
        }
        return when {
            maxX > shortSide && maxY <= shortSide -> longSide to shortSide
            maxY > shortSide && maxX <= shortSide -> shortSide to longSide
            else -> deviceW to deviceH
        }
    }

    /** Ensures [macro] has a source resolution when authoring coordinates on a screen of [screenW]x[screenH]. */
    fun ensureAuthoringResolution(macro: Macro, screenW: Int, screenH: Int): Macro {
        if (screenW <= 0 || screenH <= 0) return macro
        if (macro.sourceWidth <= 0 || macro.sourceHeight <= 0) {
            return macro.copy(sourceWidth = screenW, sourceHeight = screenH)
        }
        if (!hasCoordinateActions(macro)) {
            return macro.copy(sourceWidth = screenW, sourceHeight = screenH)
        }
        return macro
    }

    /** Common mobile & cloud-phone (Redfinger) screen presets (Portrait width x height). */
    val COMMON_PRESETS: List<Pair<Int, Int>> = listOf(
        1080 to 2400,
        1080 to 2340,
        1080 to 1920,
        720 to 1600,
        720 to 1280
    )
}
