package com.example.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure-Kotlin resolution auto-scaling engine so macros authored on ANY screen
 * (phones 16:9..21:9, tablets 16:10/4:3, foldables, emulators, or cloud phones like Redfinger)
 * map accurately onto ANY other device screen across clicks, swipes, long-presses, pinch zooms,
 * recorded gestures, color points, regions, overlay markers, and image templates.
 */
object ResolutionScaler {

    data class DevicePreset(
        val label: String,
        val width: Int,
        val height: Int
    )

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

        /** Uniform scale driven by the short side (matches Unity CanvasScaler & letterboxed games). */
        val uniformScale: Double =
            if (isScaling) min(dstW, dstH).toDouble() / min(srcW, srcH).toDouble() else 1.0

        /** Strict fit-inside scale guaranteeing every source point fits inside target with zero clipping. */
        val fitInsideScale: Double =
            if (isScaling) min(scaleX, scaleY) else 1.0

        /**
         * Piecewise-linear edge-anchored adaptive axis mapping:
         * - Keeps outer 25% edge HUD controls at uniform-scaled physical inset from nearest edge
         * - Maps center 50% smoothly and symmetrically (always preserving exact screen center)
         * - Strictly monotonic, continuous, zero-clipping, and 100% invertible across ANY aspect ratio!
         */
        private fun adaptiveForwardAxis(v: Double, srcLen: Double, dstLen: Double): Double {
            val edgeSrc = srcLen * 0.25
            val edgeDst = (edgeSrc * uniformScale).coerceIn(dstLen * 0.08, dstLen * 0.36)
            return when {
                v <= edgeSrc -> (v / edgeSrc) * edgeDst
                v >= srcLen - edgeSrc -> dstLen - ((srcLen - v) / edgeSrc) * edgeDst
                else -> edgeDst + ((v - edgeSrc) / (srcLen - 2.0 * edgeSrc)) * (dstLen - 2.0 * edgeDst)
            }
        }

        private fun adaptiveInverseAxis(v: Double, srcLen: Double, dstLen: Double): Double {
            val edgeSrc = srcLen * 0.25
            val edgeDst = (edgeSrc * uniformScale).coerceIn(dstLen * 0.08, dstLen * 0.36)
            return when {
                v <= edgeDst -> (v / edgeDst) * edgeSrc
                v >= dstLen - edgeDst -> srcLen - ((dstLen - v) / edgeDst) * edgeSrc
                else -> edgeSrc + ((v - edgeDst) / (dstLen - 2.0 * edgeDst)) * (srcLen - 2.0 * edgeSrc)
            }
        }

        fun scalePoint(x: Int, y: Int): Pair<Int, Int> {
            if (!isScaling) return x to y
            val nx: Int
            val ny: Int
            when (mode) {
                ResolutionScaleMode.ADAPTIVE -> {
                    nx = adaptiveForwardAxis(x.toDouble(), srcW.toDouble(), dstW.toDouble()).roundToInt()
                    ny = adaptiveForwardAxis(y.toDouble(), srcH.toDouble(), dstH.toDouble()).roundToInt()
                }
                ResolutionScaleMode.PROPORTIONAL -> {
                    nx = (x * scaleX).roundToInt()
                    ny = (y * scaleY).roundToInt()
                }
                ResolutionScaleMode.FIT_INSIDE -> {
                    nx = (dstW / 2.0 + (x - srcW / 2.0) * fitInsideScale).roundToInt()
                    ny = (dstH / 2.0 + (y - srcH / 2.0) * fitInsideScale).roundToInt()
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
                ResolutionScaleMode.ADAPTIVE -> {
                    ox = adaptiveInverseAxis(x.toDouble(), srcW.toDouble(), dstW.toDouble()).roundToInt()
                    oy = adaptiveInverseAxis(y.toDouble(), srcH.toDouble(), dstH.toDouble()).roundToInt()
                }
                ResolutionScaleMode.PROPORTIONAL -> {
                    ox = (x / scaleX).roundToInt()
                    oy = (y / scaleY).roundToInt()
                }
                ResolutionScaleMode.FIT_INSIDE -> {
                    val s = fitInsideScale.coerceAtLeast(1e-6)
                    ox = (srcW / 2.0 + (x - dstW / 2.0) / s).roundToInt()
                    oy = (srcH / 2.0 + (y - dstH / 2.0) / s).roundToInt()
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
                ResolutionScaleMode.ADAPTIVE ->
                    (dx * uniformScale).roundToInt() to (dy * uniformScale).roundToInt()
                ResolutionScaleMode.PROPORTIONAL ->
                    (dx * scaleX).roundToInt() to (dy * scaleY).roundToInt()
                ResolutionScaleMode.FIT_INSIDE ->
                    (dx * fitInsideScale).roundToInt() to (dy * fitInsideScale).roundToInt()
                ResolutionScaleMode.CENTER_FIT ->
                    (dx * uniformScale).roundToInt() to (dy * uniformScale).roundToInt()
            }
        }

        /** Scales radial/linear pixel distances (such as ZoomAction.distancePx or jitterPx). */
        fun scaleDistance(px: Int): Int {
            if (!isScaling || px <= 0) return px
            val factor = when (mode) {
                ResolutionScaleMode.ADAPTIVE -> fitInsideScale
                ResolutionScaleMode.PROPORTIONAL -> min(scaleX, scaleY)
                ResolutionScaleMode.FIT_INSIDE -> fitInsideScale
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
                ResolutionScaleMode.ADAPTIVE -> {
                    l = adaptiveForwardAxis(region.left.toDouble(), srcW.toDouble(), dstW.toDouble()).roundToInt().coerceIn(0, dstW - 1)
                    t = adaptiveForwardAxis(region.top.toDouble(), srcH.toDouble(), dstH.toDouble()).roundToInt().coerceIn(0, dstH - 1)
                    r = adaptiveForwardAxis(region.right.toDouble(), srcW.toDouble(), dstW.toDouble()).roundToInt().coerceIn(l + 1, dstW)
                    b = adaptiveForwardAxis(region.bottom.toDouble(), srcH.toDouble(), dstH.toDouble()).roundToInt().coerceIn(t + 1, dstH)
                }
                ResolutionScaleMode.PROPORTIONAL -> {
                    l = (region.left * scaleX).roundToInt().coerceIn(0, dstW - 1)
                    t = (region.top * scaleY).roundToInt().coerceIn(0, dstH - 1)
                    r = (region.right * scaleX).roundToInt().coerceIn(l + 1, dstW)
                    b = (region.bottom * scaleY).roundToInt().coerceIn(t + 1, dstH)
                }
                ResolutionScaleMode.FIT_INSIDE -> {
                    l = (dstW / 2.0 + (region.left - srcW / 2.0) * fitInsideScale).roundToInt().coerceIn(0, dstW - 1)
                    t = (dstH / 2.0 + (region.top - srcH / 2.0) * fitInsideScale).roundToInt().coerceIn(0, dstH - 1)
                    r = (dstW / 2.0 + (region.right - srcW / 2.0) * fitInsideScale).roundToInt().coerceIn(l + 1, dstW)
                    b = (dstH / 2.0 + (region.bottom - srcH / 2.0) * fitInsideScale).roundToInt().coerceIn(t + 1, dstH)
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
                ResolutionScaleMode.ADAPTIVE -> {
                    l = adaptiveInverseAxis(region.left.toDouble(), srcW.toDouble(), dstW.toDouble()).roundToInt().coerceIn(0, srcW - 1)
                    t = adaptiveInverseAxis(region.top.toDouble(), srcH.toDouble(), dstH.toDouble()).roundToInt().coerceIn(0, srcH - 1)
                    r = adaptiveInverseAxis(region.right.toDouble(), srcW.toDouble(), dstW.toDouble()).roundToInt().coerceIn(l + 1, srcW)
                    b = adaptiveInverseAxis(region.bottom.toDouble(), srcH.toDouble(), dstH.toDouble()).roundToInt().coerceIn(t + 1, srcH)
                }
                ResolutionScaleMode.PROPORTIONAL -> {
                    l = (region.left / scaleX).roundToInt().coerceIn(0, srcW - 1)
                    t = (region.top / scaleY).roundToInt().coerceIn(0, srcH - 1)
                    r = (region.right / scaleX).roundToInt().coerceIn(l + 1, srcW)
                    b = (region.bottom / scaleY).roundToInt().coerceIn(t + 1, srcH)
                }
                ResolutionScaleMode.FIT_INSIDE -> {
                    val s = fitInsideScale.coerceAtLeast(1e-6)
                    l = (srcW / 2.0 + (region.left - dstW / 2.0) / s).roundToInt().coerceIn(0, srcW - 1)
                    t = (srcH / 2.0 + (region.top - dstH / 2.0) / s).roundToInt().coerceIn(0, srcH - 1)
                    r = (srcW / 2.0 + (region.right - dstW / 2.0) / s).roundToInt().coerceIn(l + 1, srcW)
                    b = (srcH / 2.0 + (region.bottom - dstH / 2.0) / s).roundToInt().coerceIn(t + 1, srcH)
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
         * any device resolution (phones, tablets, foldables, emulators, cloud phones).
         */
        fun templateScaleCandidates(): List<Pair<Double, Double>> {
            if (!isScaling) return listOf(1.0 to 1.0)
            val result = ArrayList<Pair<Double, Double>>(5)
            fun addUnique(sx: Double, sy: Double) {
                if (sx <= 0.1 || sy <= 0.1 || sx > 6.0 || sy > 6.0) return
                if (result.none { abs(it.first - sx) < 0.025 && abs(it.second - sy) < 0.025 }) {
                    result += sx to sy
                }
            }
            addUnique(uniformScale, uniformScale)
            addUnique(fitInsideScale, fitInsideScale)
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
    fun hasCoordinateActions(macro: Macro): Boolean = macro.allActionsSequence().any { a ->
        when (a) {
            is ClickAction, is LongPressAction, is SwipeAction, is ZoomAction, is ColorAction -> true
            is RecordAction -> a.gestures.isNotEmpty()
            is ImageAction -> a.region != null || a.imageName.isNotBlank()
            is TrackObjectAction -> a.region != null || a.allTemplateNames().isNotEmpty() ||
                a.interactionMode == TrackInteractionMode.DRAG_TO_POINT ||
                a.interactionMode == TrackInteractionMode.DRAG_FROM_POINT_TO_OBJECT
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
        for (a in macro.allActionsSequence()) {
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
                is TrackObjectAction -> {
                    if (a.interactionMode == TrackInteractionMode.DRAG_TO_POINT ||
                        a.interactionMode == TrackInteractionMode.DRAG_FROM_POINT_TO_OBJECT
                    ) {
                        maxX = max(maxX, a.targetX)
                        maxY = max(maxY, a.targetY)
                    }
                    a.region?.let {
                        maxX = max(maxX, it.right)
                        maxY = max(maxY, it.bottom)
                    }
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

    /** Broad presets covering Phones (16:9..21:9, HD..QHD+), Tablets (16:10/4:3), Foldables & Emulators/CloudPhones. */
    val DEVICE_PRESETS: List<DevicePreset> = listOf(
        DevicePreset("HP 20:9 (1080×2400)", 1080, 2400),
        DevicePreset("HP 19.5:9 (1080×2340)", 1080, 2340),
        DevicePreset("HP 1.5K (1220×2712)", 1220, 2712),
        DevicePreset("HP QHD+ (1440×3200)", 1440, 3200),
        DevicePreset("HP 21:9 (1080×2520)", 1080, 2520),
        DevicePreset("HD+ 20:9 (720×1600)", 720, 1600),
        DevicePreset("16:9 FHD (1080×1920)", 1080, 1920),
        DevicePreset("16:9 HD (720×1280)", 720, 1280),
        DevicePreset("16:9 540p (540×960)", 540, 960),
        DevicePreset("Tablet 16:10 (1600×2560)", 1600, 2560),
        DevicePreset("Tablet 16:10 (1200×1920)", 1200, 1920),
        DevicePreset("Tablet 4:3 (1536×2048)", 1536, 2048),
        DevicePreset("Foldable (1812×2176)", 1812, 2176)
    )

    /** Backward-compatible list of (width, height) presets. */
    val COMMON_PRESETS: List<Pair<Int, Int>> = DEVICE_PRESETS.map { it.width to it.height }
}
