package com.example.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Hybrid pure-Kotlin Moving Object Tracker built specifically for fast, animated,
 * multi-directional game units (such as Clash of Clans troops: Dragons, Electro Dragons,
 * Balloons, Hog Riders, Heroes, Miners, Super Goblins, Siege Machines) moving over busy terrain.
 *
 * Combines four complementary visual cues:
 * 1. **Foreground-vs-Background HSV Color Signature**: separates the troop's inner elliptical colors
 *    from its crop-time border background (e.g. green grass/tiles) so matching is invariant to
 *    360°/8-direction isometric rotation, wing-flapping, walking animation, and battlefield zoom changes.
 * 2. **RGB Frame-to-Frame Motion Field**: detects moving/animating troop blobs directly in RGB
 *    (even when troop & grass have identical grayscale brightness) while ignoring static buildings,
 *    with an automatic camera-pan/zoom guard.
 * 3. **Foreground-Masked Elliptical RGB-NCC**: compares R/G/B structure strictly inside the troop's
 *    foreground core (ignoring rectangular corners where changing grass/walls live).
 * 4. **Color-Gated Adaptive Live Patch + Mean-Shift Centroid**: tracks smoothly through gradual
 *    turns and spell overlays without drifting onto static background.
 */
object MovingObjectTracker {

    private const val CHROMATIC_BINS = 108 // 12 hue * 3 sat * 3 val
    private const val ACHROMATIC_BINS = 12 // 12 grayscale levels
    private const val NUM_BINS = CHROMATIC_BINS + ACHROMATIC_BINS // 120
    private const val MOTION_RGB_DIFF_THRESHOLD = 38
    private const val GLOBAL_CAMERA_MOTION_RATIO = 0.32f
    private const val MAX_MOTION_BLOBS = 24

    /** Precomputed foreground-weighted HSV color & masked elliptical RGB signature of a template crop. */
    class ColorSignature(
        val name: String,
        val width: Int,
        val height: Int,
        /** Per-bin foreground probability weight [0..1] (crop border background suppressed to ~0). */
        val fgWeight: FloatArray,
        /** Normalized foreground-only color distribution (sum = 1.0). */
        val fgDistinctHist: FloatArray,
        /** Precomputed sqrt(fgDistinctHist) for fast Bhattacharyya similarity. */
        val sqrtFgDistinct: FloatArray,
        /** Average foreground probability inside the template's own inner ellipse. */
        val expectedFgDensity: Float,
        /** True when the template has strong chromatic foreground colors (typical COC troops). */
        val hasChromaticSignature: Boolean,
        /** Compact foreground-masked elliptical RGB patch (normal orientation). */
        val corePatch: CoreRgbPatch,
        /** Compact foreground-masked elliptical RGB patch (horizontally mirrored). */
        val corePatchMirrored: CoreRgbPatch
    )

    /** Lightweight foreground-weighted RGB patch (<= 20x20) for fast multi-channel structural correlation. */
    class CoreRgbPatch(
        val width: Int,
        val height: Int,
        val weights: FloatArray,
        val totalWeight: Float,
        val rZm: FloatArray,
        val gZm: FloatArray,
        val bZm: FloatArray,
        val energy: Double
    )

    /** Short-term adaptive appearance patch captured from the previous confident frame. */
    class LiveTrackPatch(
        val patch: CoreRgbPatch,
        val width: Int,
        val height: Int
    )

    data class MotionBlob(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val cellCount: Int,
        val centroidX: Int = (left + right) / 2,
        val centroidY: Int = (top + bottom) / 2
    ) {
        val centerX: Int get() = centroidX
        val centerY: Int get() = centroidY
        val width: Int get() = (right - left).coerceAtLeast(1)
        val height: Int get() = (bottom - top).coerceAtLeast(1)
    }

    /** Downsampled RGB frame-to-frame motion field with O(1) integral queries and moving blobs. */
    class MotionField(
        val regionLeft: Int,
        val regionTop: Int,
        val cellSize: Int,
        val gridW: Int,
        val gridH: Int,
        val movingFraction: Float,
        val isGlobalCameraMotion: Boolean,
        private val integral: IntArray,
        val blobs: List<MotionBlob>
    ) {
        /** Returns fraction [0..1] of cells inside the given screen rectangle that changed between frames. */
        fun motionRatioIn(left: Int, top: Int, right: Int, bottom: Int): Double {
            if (isGlobalCameraMotion || gridW <= 0 || gridH <= 0) return 0.0
            val gx0 = ((left - regionLeft) / cellSize).coerceIn(0, gridW - 1)
            val gy0 = ((top - regionTop) / cellSize).coerceIn(0, gridH - 1)
            val gx1 = (((right - regionLeft) + cellSize - 1) / cellSize).coerceIn(gx0 + 1, gridW)
            val gy1 = (((bottom - regionTop) + cellSize - 1) / cellSize).coerceIn(gy0 + 1, gridH)
            val stride = gridW + 1
            val moving = integral[gy1 * stride + gx1] -
                integral[gy0 * stride + gx1] -
                integral[gy1 * stride + gx0] +
                integral[gy0 * stride + gx0]
            val area = (gx1 - gx0) * (gy1 - gy0)
            return if (area > 0) moving.toDouble() / area.toDouble() else 0.0
        }
    }

    data class HybridMatch(
        val match: MatchResult,
        val usedScale: Double,
        val colorScore: Double,
        val structScore: Double,
        val motionRatio: Double
    )

    /** Quantizes an ARGB pixel into one of 120 HSV+Grayscale color bins. */
    fun colorBin(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC

        // Low saturation or very dark -> achromatic grayscale bucket (108..119)
        if (maxC < 38 || delta * 100 < maxC * 22) {
            val gBin = ((maxC * ACHROMATIC_BINS) / 256).coerceIn(0, ACHROMATIC_BINS - 1)
            return CHROMATIC_BINS + gBin
        }

        // Hue in [0, 6)
        val hue6 = when (maxC) {
            r -> ((g - b).toFloat() / delta.toFloat()).let { if (it < 0f) it + 6f else it }
            g -> (b - r).toFloat() / delta.toFloat() + 2f
            else -> (r - g).toFloat() / delta.toFloat() + 4f
        }
        val hBin = ((hue6 * 2f).toInt() % 12 + 12) % 12 // 12 hue sectors (30° each)
        val satPct = (delta * 100) / maxC
        val sBin = when {
            satPct < 48 -> 0
            satPct < 74 -> 1
            else -> 2
        }
        val vBin = when {
            maxC < 115 -> 0
            maxC < 185 -> 1
            else -> 2
        }
        return hBin * 9 + sBin * 3 + vBin
    }

    /** Builds a reusable [ColorSignature] from a template's full ARGB [Frame]. */
    fun buildSignature(name: String, frame: Frame, ignoreBackgroundEdges: Boolean = true): ColorSignature {
        val w = frame.width
        val h = frame.height
        val fgHist = FloatArray(NUM_BINS)
        val bgHist = FloatArray(NUM_BINS)
        var fgTotal = 0f
        var bgTotal = 0f

        val cx = (w - 1) * 0.5f
        val cy = (h - 1) * 0.5f
        val rx = max(2f, w * 0.36f)
        val ry = max(2f, h * 0.36f)
        val borderX = max(1, (w * 0.19f).roundToInt())
        val borderY = max(1, (h * 0.19f).roundToInt())

        for (y in 0 until h) {
            val ny = (y - cy) / ry
            val isBorderY = y < borderY || y >= h - borderY
            val rowOff = y * w
            for (x in 0 until w) {
                val nx = (x - cx) / rx
                val distSq = nx * nx + ny * ny
                val bin = colorBin(frame.pixels[rowOff + x])
                if (distSq <= 1.0f) {
                    val spatialW = 1.0f - 0.35f * distSq
                    fgHist[bin] += spatialW
                    fgTotal += spatialW
                } else if (ignoreBackgroundEdges && (isBorderY || x < borderX || x >= w - borderX)) {
                    bgHist[bin] += 1f
                    bgTotal += 1f
                }
            }
        }

        if (fgTotal > 0f) {
            for (i in 0 until NUM_BINS) fgHist[i] /= fgTotal
        }
        if (bgTotal > 0f) {
            for (i in 0 until NUM_BINS) bgHist[i] /= bgTotal
        }

        // Foreground-vs-background likelihood: grass/border colors drop near 0; troop colors stay ~1
        val fgWeight = FloatArray(NUM_BINS)
        if (ignoreBackgroundEdges && bgTotal > 0f) {
            for (i in 0 until NUM_BINS) {
                val f = fgHist[i]
                if (f < 0.012f) continue
                val b = bgHist[i]
                fgWeight[i] = (f / (f + b * 1.65f + 0.004f)).coerceIn(0f, 1f)
            }
            // Value-tier tolerance: preserve troop color across shadows / bright spell lighting (same Hue+Sat, adjacent Val)
            val baseCopy = fgWeight.copyOf()
            for (hBin in 0 until 12) {
                for (sBin in 0 until 3) {
                    for (vBin in 0 until 3) {
                        val idx = hBin * 9 + sBin * 3 + vBin
                        val wVal = baseCopy[idx]
                        if (wVal < 0.45f) continue
                        if (vBin > 0) {
                            val darker = idx - 1
                            if (bgHist[darker] < 0.07f) fgWeight[darker] = max(fgWeight[darker], wVal * 0.62f)
                        }
                        if (vBin < 2) {
                            val brighter = idx + 1
                            if (bgHist[brighter] < 0.07f) fgWeight[brighter] = max(fgWeight[brighter], wVal * 0.62f)
                        }
                        if (sBin > 0) {
                            val lessSat = hBin * 9 + (sBin - 1) * 3 + vBin
                            if (bgHist[lessSat] < 0.07f) fgWeight[lessSat] = max(fgWeight[lessSat], wVal * 0.50f)
                        }
                    }
                }
            }
        } else {
            for (i in 0 until NUM_BINS) {
                fgWeight[i] = if (fgHist[i] >= 0.012f) 1f else 0f
            }
        }

        // Normalized foreground-only color histogram
        val fgDistinct = FloatArray(NUM_BINS)
        var distinctSum = 0f
        var chromaticDistinctSum = 0f
        for (i in 0 until NUM_BINS) {
            val v = fgHist[i] * fgWeight[i]
            fgDistinct[i] = v
            distinctSum += v
            if (i < CHROMATIC_BINS) chromaticDistinctSum += v
        }

        // Fallback if crop had identical foreground & border colors (e.g. tight crop with no margin)
        if (distinctSum < 0.08f) {
            distinctSum = 0f
            chromaticDistinctSum = 0f
            for (i in 0 until NUM_BINS) {
                val f = fgHist[i]
                fgWeight[i] = if (f >= 0.015f) 0.85f else 0f
                fgDistinct[i] = f * fgWeight[i]
                distinctSum += fgDistinct[i]
                if (i < CHROMATIC_BINS) chromaticDistinctSum += fgDistinct[i]
            }
        }

        val sqrtDistinct = FloatArray(NUM_BINS)
        if (distinctSum > 1e-6f) {
            for (i in 0 until NUM_BINS) {
                fgDistinct[i] /= distinctSum
                sqrtDistinct[i] = sqrt(fgDistinct[i])
            }
        }

        val chromaticRatio = if (distinctSum > 1e-6f) chromaticDistinctSum / distinctSum else 0f
        var expectedDensity = 0f
        var innerCount = 0
        for (y in 0 until h) {
            val ny = (y - cy) / ry
            val rowOff = y * w
            for (x in 0 until w) {
                val nx = (x - cx) / rx
                if (nx * nx + ny * ny <= 1.0f) {
                    expectedDensity += fgWeight[colorBin(frame.pixels[rowOff + x])]
                    innerCount++
                }
            }
        }
        val avgFgDensity = if (innerCount > 0) (expectedDensity / innerCount).coerceIn(0.08f, 1f) else 0.35f
        val hasChromatic = chromaticRatio >= 0.22f && avgFgDensity >= 0.10f

        val corePatch = buildCoreRgbPatch(frame, fgWeight, mirrorX = false)
        val corePatchMirrored = buildCoreRgbPatch(frame, fgWeight, mirrorX = true)

        return ColorSignature(
            name = name,
            width = w,
            height = h,
            fgWeight = fgWeight,
            fgDistinctHist = fgDistinct,
            sqrtFgDistinct = sqrtDistinct,
            expectedFgDensity = avgFgDensity,
            hasChromaticSignature = hasChromatic,
            corePatch = corePatch,
            corePatchMirrored = corePatchMirrored
        )
    }

    /** Captures a short-term adaptive RGB patch centered at ([cx], [cy]) from the current live frame. */
    fun captureLivePatch(frame: Frame, cx: Int, cy: Int, width: Int, height: Int, fgWeight: FloatArray): LiveTrackPatch? {
        val w = width.coerceIn(12, min(frame.width, 220))
        val h = height.coerceIn(12, min(frame.height, 220))
        val left = (cx - w / 2).coerceIn(0, (frame.width - w).coerceAtLeast(0))
        val top = (cy - h / 2).coerceIn(0, (frame.height - h).coerceAtLeast(0))
        val crop = frame.crop(left, top, w, h)
        val patch = buildCoreRgbPatch(crop, fgWeight, mirrorX = false)
        if (patch.energy <= 1e-2) return null
        return LiveTrackPatch(patch, w, h)
    }

    private fun buildCoreRgbPatch(frame: Frame, fgWeight: FloatArray, mirrorX: Boolean): CoreRgbPatch {
        val maxDim = max(frame.width, frame.height).coerceAtLeast(1)
        val scale = if (maxDim > 20) 20f / maxDim.toFloat() else 1f
        val pw = (frame.width * scale).roundToInt().coerceIn(6, 20)
        val ph = (frame.height * scale).roundToInt().coerceIn(6, 20)
        val n = pw * ph

        val rArr = FloatArray(n)
        val gArr = FloatArray(n)
        val bArr = FloatArray(n)
        val wArr = FloatArray(n)
        val cx = (pw - 1) * 0.5f
        val cy = (ph - 1) * 0.5f
        val rx = max(1.5f, pw * 0.46f)
        val ry = max(1.5f, ph * 0.46f)

        var wSum = 0f
        var rSum = 0.0
        var gSum = 0.0
        var bSum = 0.0

        for (py in 0 until ph) {
            val sy = ((py + 0.5f) * frame.height / ph).toInt().coerceIn(0, frame.height - 1)
            val ny = (py - cy) / ry
            for (px in 0 until pw) {
                val srcPx = if (mirrorX) (pw - 1 - px) else px
                val sx = ((srcPx + 0.5f) * frame.width / pw).toInt().coerceIn(0, frame.width - 1)
                val nx = (px - cx) / rx
                val distSq = nx * nx + ny * ny
                val idx = py * pw + px
                if (distSq > 1.05f) {
                    wArr[idx] = 0f
                    continue
                }
                val argb = frame.pixels[sy * frame.width + sx]
                val r = ((argb ushr 16) and 0xFF).toFloat()
                val g = ((argb ushr 8) and 0xFF).toFloat()
                val b = (argb and 0xFF).toFloat()
                val ellipseW = (1.05f - distSq).coerceIn(0.05f, 1f)
                val colorW = 0.22f + 0.78f * fgWeight[colorBin(argb)]
                val weight = ellipseW * colorW
                rArr[idx] = r
                gArr[idx] = g
                bArr[idx] = b
                wArr[idx] = weight
                wSum += weight
                rSum += r * weight
                gSum += g * weight
                bSum += b * weight
            }
        }

        if (wSum <= 1e-4f) {
            return CoreRgbPatch(pw, ph, wArr, 0f, rArr, gArr, bArr, 0.0)
        }
        val rMean = (rSum / wSum).toFloat()
        val gMean = (gSum / wSum).toFloat()
        val bMean = (bSum / wSum).toFloat()
        var energy = 0.0
        for (i in 0 until n) {
            val w = wArr[i]
            if (w <= 0f) {
                rArr[i] = 0f; gArr[i] = 0f; bArr[i] = 0f
                continue
            }
            val dr = rArr[i] - rMean
            val dg = gArr[i] - gMean
            val db = bArr[i] - bMean
            rArr[i] = dr
            gArr[i] = dg
            bArr[i] = db
            energy += w * (dr * dr + dg * dg + db * db).toDouble()
        }
        return CoreRgbPatch(pw, ph, wArr, wSum, rArr, gArr, bArr, energy)
    }

    /** Computes foreground-masked multi-channel RGB-NCC in [-1..1] at screen box ([left],[top],[winW],[winH]). */
    fun scoreCoreRgbNcc(frame: Frame, patch: CoreRgbPatch, left: Int, top: Int, winW: Int, winH: Int): Double {
        if (patch.energy <= 1e-2 || patch.totalWeight <= 1e-4f || winW < 4 || winH < 4) return 0.0
        val pw = patch.width
        val ph = patch.height
        val fw = frame.width
        val fh = frame.height
        val pxBuf = frame.pixels
        val weights = patch.weights

        var rSum = 0.0
        var gSum = 0.0
        var bSum = 0.0
        val n = pw * ph
        val sr = FloatArray(n)
        val sg = FloatArray(n)
        val sb = FloatArray(n)

        for (py in 0 until ph) {
            val sy = (top + ((py + 0.5f) * winH / ph).toInt()).coerceIn(0, fh - 1)
            val rowOff = sy * fw
            val pRow = py * pw
            for (px in 0 until pw) {
                val idx = pRow + px
                val w = weights[idx]
                if (w <= 0f) continue
                val sx = (left + ((px + 0.5f) * winW / pw).toInt()).coerceIn(0, fw - 1)
                val argb = pxBuf[rowOff + sx]
                val r = ((argb ushr 16) and 0xFF).toFloat()
                val g = ((argb ushr 8) and 0xFF).toFloat()
                val b = (argb and 0xFF).toFloat()
                sr[idx] = r
                sg[idx] = g
                sb[idx] = b
                rSum += r * w
                gSum += g * w
                bSum += b * w
            }
        }

        val invW = 1.0 / patch.totalWeight
        val rMean = (rSum * invW).toFloat()
        val gMean = (gSum * invW).toFloat()
        val bMean = (bSum * invW).toFloat()

        var cross = 0.0
        var imgEnergy = 0.0
        val tr = patch.rZm
        val tg = patch.gZm
        val tb = patch.bZm
        for (i in 0 until n) {
            val w = weights[i]
            if (w <= 0f) continue
            val dr = sr[i] - rMean
            val dg = sg[i] - gMean
            val db = sb[i] - bMean
            cross += w * (dr * tr[i] + dg * tg[i] + db * tb[i]).toDouble()
            imgEnergy += w * (dr * dr + dg * dg + db * db).toDouble()
        }
        if (imgEnergy <= 1e-2) return 0.0
        return (cross / sqrt(patch.energy * imgEnergy)).coerceIn(-1.0, 1.0)
    }

    /**
     * Computes a fast downsampled RGB frame-to-frame motion field with connected moving blobs.
     * Automatically flags global camera pan/zoom when > 42% of the region changes simultaneously.
     */
    fun computeMotionField(
        prev: Frame,
        curr: Frame,
        regionLeft: Int,
        regionTop: Int,
        regionRight: Int,
        regionBottom: Int
    ): MotionField? {
        if (prev.width != curr.width || prev.height != curr.height) return null
        val rL = regionLeft.coerceIn(0, curr.width - 1)
        val rT = regionTop.coerceIn(0, curr.height - 1)
        val rR = regionRight.coerceIn(rL + 1, curr.width)
        val rB = regionBottom.coerceIn(rT + 1, curr.height)
        val rw = rR - rL
        val rh = rB - rT
        if (rw < 16 || rh < 16) return null

        val cell = if (max(rw, rh) > 900) 6 else 4
        val gw = max(1, rw / cell)
        val gh = max(1, rh / cell)
        val moving = ByteArray(gw * gh)
        val integral = IntArray((gw + 1) * (gh + 1))
        val p1 = prev.pixels
        val p2 = curr.pixels
        val fw = curr.width
        var movingCount = 0

        for (gy in 0 until gh) {
            val sy = (rT + gy * cell + cell / 2).coerceAtMost(rB - 1)
            val rowOff = sy * fw
            val gRow = gy * gw
            val iRow = (gy + 1) * (gw + 1)
            val iPrevRow = gy * (gw + 1)
            var rowSum = 0
            for (gx in 0 until gw) {
                val sx = (rL + gx * cell + cell / 2).coerceAtMost(rR - 1)
                val a = p1[rowOff + sx]
                val b = p2[rowOff + sx]
                val diff = abs(((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF)) +
                    abs(((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF)) +
                    abs((a and 0xFF) - (b and 0xFF))
                val isMov = if (diff >= MOTION_RGB_DIFF_THRESHOLD) 1 else 0
                if (isMov == 1) {
                    moving[gRow + gx] = 1
                    movingCount++
                }
                rowSum += isMov
                integral[iRow + gx + 1] = integral[iPrevRow + gx + 1] + rowSum
            }
        }

        val fraction = movingCount.toFloat() / (gw * gh).coerceAtLeast(1).toFloat()
        val isGlobalPan = fraction > GLOBAL_CAMERA_MOTION_RATIO
        if (isGlobalPan || movingCount == 0) {
            return MotionField(rL, rT, cell, gw, gh, fraction, isGlobalPan, integral, emptyList())
        }

        // Extract connected moving blobs using 8-neighbor + 2-cell bridge BFS so animated troop limbs merge cleanly
        val visited = BooleanArray(gw * gh)
        val queue = IntArray(gw * gh)
        val blobs = ArrayList<MotionBlob>()

        for (startY in 0 until gh) {
            for (startX in 0 until gw) {
                val startIdx = startY * gw + startX
                if (moving[startIdx].toInt() == 0 || visited[startIdx]) continue
                var head = 0
                var tail = 0
                queue[tail++] = startIdx
                visited[startIdx] = true

                var minGx = startX
                var maxGx = startX
                var minGy = startY
                var maxGy = startY
                var sumGx = 0L
                var sumGy = 0L
                var count = 0

                while (head < tail) {
                    val cur = queue[head++]
                    val cx = cur % gw
                    val cy = cur / gw
                    count++
                    sumGx += cx
                    sumGy += cy
                    if (cx < minGx) minGx = cx
                    if (cx > maxGx) maxGx = cx
                    if (cy < minGy) minGy = cy
                    if (cy > maxGy) maxGy = cy

                    val y0 = (cy - 2).coerceAtLeast(0)
                    val y1 = (cy + 2).coerceAtMost(gh - 1)
                    val x0 = (cx - 2).coerceAtLeast(0)
                    val x1 = (cx + 2).coerceAtMost(gw - 1)
                    for (ny in y0..y1) {
                        val nRow = ny * gw
                        for (nx in x0..x1) {
                            val nIdx = nRow + nx
                            if (moving[nIdx].toInt() != 0 && !visited[nIdx]) {
                                visited[nIdx] = true
                                queue[tail++] = nIdx
                            }
                        }
                    }
                }

                // Filter out single-cell compression specks & huge screen-wide flashes
                if (count >= 3 && count <= (gw * gh) / 3) {
                    val bLeft = (rL + minGx * cell).coerceIn(rL, rR)
                    val bTop = (rT + minGy * cell).coerceIn(rT, rB)
                    val bRight = (rL + (maxGx + 1) * cell).coerceIn(rL, rR)
                    val bBottom = (rT + (maxGy + 1) * cell).coerceIn(rT, rB)
                    val cX = (rL + ((sumGx.toDouble() / count.toDouble()) * cell + cell * 0.5).roundToInt()).coerceIn(bLeft, bRight)
                    val cY = (rT + ((sumGy.toDouble() / count.toDouble()) * cell + cell * 0.5).roundToInt()).coerceIn(bTop, bBottom)
                    blobs += MotionBlob(
                        left = bLeft,
                        top = bTop,
                        right = bRight,
                        bottom = bBottom,
                        cellCount = count,
                        centroidX = cX,
                        centroidY = cY
                    )
                }
            }
        }

        val topBlobs = blobs.sortedByDescending { it.cellCount }.take(MAX_MOTION_BLOBS)
        return MotionField(rL, rT, cell, gw, gh, fraction, false, integral, topBlobs)
    }

    /**
     * Color-free / template-free moving-object selector: picks the strongest moving troop or army clump
     * directly from [MotionField] regardless of troop color, level, or skin.
     *
     * When [preferredCenter] is provided (continuous tracking), prioritizes moving blobs near the
     * predicted trajectory so tracking stays locked on the same push before falling back globally.
     */
    fun pickBestMovingBlob(
        motion: MotionField,
        preferredCenter: Pair<Int, Int>? = null,
        maxRadiusPx: Int = Int.MAX_VALUE,
        minCells: Int = 4
    ): MatchResult? {
        if (motion.isGlobalCameraMotion || motion.blobs.isEmpty()) return null
        val eligible = motion.blobs.filter { it.cellCount >= minCells }
        if (eligible.isEmpty()) return null

        val chosen: MotionBlob = if (preferredCenter != null) {
            val (px, py) = preferredCenter
            val rad = maxRadiusPx.coerceAtLeast(60).toDouble()
            val nearby = eligible.filter { b ->
                val dx = (b.centerX - px).toDouble()
                val dy = (b.centerY - py).toDouble()
                sqrt(dx * dx + dy * dy) <= rad
            }
            if (nearby.isNotEmpty()) {
                // Balance clump size with proximity to predicted position so we stay locked on the active moving push
                nearby.maxBy { b ->
                    val dx = (b.centerX - px).toDouble()
                    val dy = (b.centerY - py).toDouble()
                    val dist = sqrt(dx * dx + dy * dy)
                    val proxWeight = 1.0 / (1.0 + (dist / (rad * 0.45)))
                    sqrt(b.cellCount.toDouble()) * (0.35 + 0.65 * proxWeight)
                }
            } else {
                // Active squad moved far or vanished -> fall back to largest moving army clump in region
                eligible.first()
            }
        } else {
            // Initial acquisition: lock onto the largest moving troop / army clump
            eligible.first()
        }

        // Confidence scaled cleanly in [0.76..0.98] based on active moving cell mass
        val conf = (0.74 + min(0.24, sqrt(chosen.cellCount.toDouble()) * 0.032)).coerceIn(0.76, 0.98)
        // Build box centered around exact mass centroid so MatchResult.centerX/centerY hit the dense troop core
        val halfW = (chosen.width / 2).coerceAtLeast(8)
        val halfH = (chosen.height / 2).coerceAtLeast(8)
        return MatchResult(
            x = (chosen.centroidX - halfW).coerceAtLeast(0),
            y = (chosen.centroidY - halfH).coerceAtLeast(0),
            width = halfW * 2,
            height = halfH * 2,
            score = conf
        )
    }

    /** Evaluates foreground color similarity + coverage of [frame] inside ([left],[top],[winW],[winH]). */
    fun scoreColorWindow(frame: Frame, sig: ColorSignature, left: Int, top: Int, winW: Int, winH: Int): Double {
        if (winW < 6 || winH < 6) return 0.0
        val fw = frame.width
        val fh = frame.height
        val l = left.coerceIn(0, fw - 1)
        val t = top.coerceIn(0, fh - 1)
        val r = (left + winW).coerceIn(l + 1, fw)
        val b = (top + winH).coerceIn(t + 1, fh)
        val w = r - l
        val h = b - t

        val stride = max(1, max(w, h) / 22)
        val cx = (w - 1) * 0.5f
        val cy = (h - 1) * 0.5f
        val rx = max(2f, w * 0.42f)
        val ry = max(2f, h * 0.42f)

        val candFg = FloatArray(NUM_BINS)
        var fgMass = 0f
        var sampleWeightSum = 0f
        val fgWeight = sig.fgWeight
        val pixels = frame.pixels

        var y = 0
        while (y < h) {
            val ny = (y - cy) / ry
            val rowOff = (t + y) * fw + l
            var x = 0
            while (x < w) {
                val nx = (x - cx) / rx
                val distSq = nx * nx + ny * ny
                if (distSq <= 1.0f) {
                    val spatialW = 1.0f - 0.30f * distSq
                    val bin = colorBin(pixels[rowOff + x])
                    val fwVal = fgWeight[bin]
                    if (fwVal > 0f) {
                        val contrib = spatialW * fwVal
                        candFg[bin] += contrib
                        fgMass += contrib
                    }
                    sampleWeightSum += spatialW
                }
                x += stride
            }
            y += stride
        }

        if (sampleWeightSum <= 1e-4f || fgMass <= 1e-4f) return 0.0
        val observedDensity = fgMass / sampleWeightSum
        val densityRatio = (observedDensity / sig.expectedFgDensity.coerceAtLeast(0.10f)).coerceIn(0f, 1.45f)
        // Reward matching density cleanly while softly penalizing extreme sparsity (stray spark)
        val coverageFactor = when {
            densityRatio < 1.0f -> densityRatio.toDouble()
            else -> (1.0 - (densityRatio - 1.0) * 0.18).coerceIn(0.85, 1.0)
        }

        // Bhattacharyya coefficient between template's foreground-only histogram and candidate's foreground-only histogram
        var bhatta = 0.0
        val sqrtTpl = sig.sqrtFgDistinct
        val invFg = 1.0 / fgMass.toDouble()
        for (i in 0 until NUM_BINS) {
            val c = candFg[i]
            if (c > 0f && sqrtTpl[i] > 0f) {
                bhatta += sqrtTpl[i] * sqrt(c * invFg)
            }
        }
        return (bhatta * (0.42 + 0.58 * coverageFactor)).coerceIn(0.0, 1.0)
    }

    /**
     * Searches inside bounds `[rLeft, rTop, rRight, rBottom]` using foreground color backprojection +
     * integral box filtering + Mean-Shift centroid refinement + multi-channel RGB-NCC + motion scoring.
     */
    fun findHybridInBounds(
        frame: Frame,
        sig: ColorSignature,
        threshold: Double,
        rLeft: Int,
        rTop: Int,
        rRight: Int,
        rBottom: Int,
        scales: DoubleArray,
        motion: MotionField?,
        livePatch: LiveTrackPatch?,
        matchMirrorX: Boolean,
        prioritizeMotion: Boolean,
        requireMovingBlob: Boolean = false,
        onBestScore: ((Double) -> Unit)? = null
    ): HybridMatch? {
        val l = rLeft.coerceIn(0, frame.width - 1)
        val t = rTop.coerceIn(0, frame.height - 1)
        val r = rRight.coerceIn(l + 1, frame.width)
        val b = rBottom.coerceIn(t + 1, frame.height)
        val rw = r - l
        val rh = b - t
        if (rw < 8 || rh < 8) return null

        // Stride 2 for local ROIs / moving blobs (crisp), stride 4 for full-screen sweeps (fast)
        val stride = if (rw * rh > 220_000) 4 else 2
        val gw = max(1, rw / stride)
        val gh = max(1, rh / stride)
        val bp = FloatArray(gw * gh)
        val integral = FloatArray((gw + 1) * (gh + 1))
        val fgWeight = sig.fgWeight
        val pixels = frame.pixels
        val fw = frame.width

        for (gy in 0 until gh) {
            val sy = (t + gy * stride).coerceAtMost(b - 1)
            val rowOff = sy * fw
            val gRow = gy * gw
            val iRow = (gy + 1) * (gw + 1)
            val iPrev = gy * (gw + 1)
            var rowSum = 0f
            for (gx in 0 until gw) {
                val sx = (l + gx * stride).coerceAtMost(r - 1)
                val w = fgWeight[colorBin(pixels[rowOff + sx])]
                bp[gRow + gx] = w
                rowSum += w
                integral[iRow + gx + 1] = integral[iPrev + gx + 1] + rowSum
            }
        }

        // Collect top backprojection peaks across requested scales
        data class Peak(val cx: Int, val cy: Int, val winW: Int, val winH: Int, val scale: Double, val response: Float)
        val peaks = ArrayList<Peak>(24)
        val iStride = gw + 1

        for (s in scales) {
            val winW = (sig.width * s).roundToInt().coerceAtLeast(10)
            val winH = (sig.height * s).roundToInt().coerceAtLeast(10)
            if (winW > rw + winW / 3 || winH > rh + winH / 3) continue

            val bw = (winW / stride).coerceIn(2, gw)
            val bh = (winH / stride).coerceIn(2, gh)
            val invArea = 1f / (bw * bh).toFloat()
            val minResponse = (sig.expectedFgDensity * 0.24f).coerceIn(0.04f, 0.22f)

            val stepG = max(1, min(bw, bh) / 4)
            var gy = 0
            while (gy <= gh - bh) {
                val y0 = gy * iStride
                val y1 = (gy + bh) * iStride
                var gx = 0
                while (gx <= gw - bw) {
                    val sum = integral[y1 + gx + bw] - integral[y0 + gx + bw] - integral[y1 + gx] + integral[y0 + gx]
                    val avg = sum * invArea
                    if (avg >= minResponse) {
                        val candCx = l + (gx + bw / 2) * stride
                        val candCy = t + (gy + bh / 2) * stride
                        val motionMul = if (motion != null && !motion.isGlobalCameraMotion && prioritizeMotion) {
                            val mr = motion.motionRatioIn(
                                candCx - winW / 2, candCy - winH / 2,
                                candCx + winW / 2, candCy + winH / 2
                            ).toFloat()
                            if (requireMovingBlob && mr < 0.04f) {
                                gx += stepG
                                continue
                            }
                            1f + mr * 0.85f
                        } else {
                            1f
                        }
                        val resp = avg * motionMul
                        if (peaks.size < 18 || resp > peaks.last().response) {
                            // Suppress nearby duplicates at same scale
                            val dupIdx = peaks.indexOfFirst {
                                abs(it.cx - candCx) < winW / 2 && abs(it.cy - candCy) < winH / 2
                            }
                            if (dupIdx >= 0) {
                                if (resp > peaks[dupIdx].response) {
                                    peaks[dupIdx] = Peak(candCx, candCy, winW, winH, s, resp)
                                }
                            } else {
                                peaks += Peak(candCx, candCy, winW, winH, s, resp)
                                if (peaks.size > 18) {
                                    peaks.sortByDescending { it.response }
                                    peaks.removeAt(peaks.lastIndex)
                                }
                            }
                        }
                    }
                    gx += stepG
                }
                gy += stepG
            }
        }

        if (peaks.isEmpty()) return null
        peaks.sortByDescending { it.response }

        var bestHit: HybridMatch? = null
        val evalLimit = min(peaks.size, 8)
        for (i in 0 until evalLimit) {
            val pk = peaks[i]
            // 2-step Mean-Shift centroid refinement snaps directly onto the troop's foreground body center
            var cx = pk.cx
            var cy = pk.cy
            repeat(2) {
                val (nx, ny) = refineCentroid(bp, gw, gh, l, t, stride, cx, cy, pk.winW, pk.winH)
                cx = nx
                cy = ny
            }

            val boxLeft = (cx - pk.winW / 2).coerceIn(0, (frame.width - pk.winW).coerceAtLeast(0))
            val boxTop = (cy - pk.winH / 2).coerceIn(0, (frame.height - pk.winH).coerceAtLeast(0))
            val boxW = pk.winW.coerceAtMost(frame.width - boxLeft)
            val boxH = pk.winH.coerceAtMost(frame.height - boxTop)

            val colorScore = scoreColorWindow(frame, sig, boxLeft, boxTop, boxW, boxH)
            val nccNormal = scoreCoreRgbNcc(frame, sig.corePatch, boxLeft, boxTop, boxW, boxH).coerceAtLeast(0.0)
            val nccMirror = if (matchMirrorX) {
                scoreCoreRgbNcc(frame, sig.corePatchMirrored, boxLeft, boxTop, boxW, boxH).coerceAtLeast(0.0)
            } else 0.0
            val liveNcc = if (livePatch != null && colorScore >= 0.36) {
                scoreCoreRgbNcc(frame, livePatch.patch, boxLeft, boxTop, boxW, boxH).coerceAtLeast(0.0)
            } else 0.0
            val structScore = max(max(nccNormal, nccMirror), liveNcc)

            val motionRatio = if (motion != null && !motion.isGlobalCameraMotion) {
                motion.motionRatioIn(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
            } else 0.0

            val combined = combineHybridScore(
                sig = sig,
                colorScore = colorScore,
                structScore = structScore,
                motionRatio = motionRatio,
                hasMotionField = motion != null && !motion.isGlobalCameraMotion,
                prioritizeMotion = prioritizeMotion
            )
            onBestScore?.invoke(combined)

            if (bestHit == null || combined > bestHit.match.score) {
                bestHit = HybridMatch(
                    match = MatchResult(boxLeft, boxTop, boxW, boxH, combined),
                    usedScale = pk.scale,
                    colorScore = colorScore,
                    structScore = structScore,
                    motionRatio = motionRatio
                )
            }
        }

        return bestHit?.takeIf { it.match.score >= threshold }
    }

    /**
     * Searches around all detected moving blobs (ultra-fast global acquisition for COC moving troops).
     * Automatically skips static buildings, walls, trees, and grass.
     */
    fun findInMovingBlobs(
        frame: Frame,
        sig: ColorSignature,
        threshold: Double,
        motion: MotionField,
        livePatch: LiveTrackPatch?,
        matchMirrorX: Boolean,
        prioritizeMotion: Boolean,
        regionLeft: Int,
        regionTop: Int,
        regionRight: Int,
        regionBottom: Int,
        onBestScore: ((Double) -> Unit)? = null
    ): HybridMatch? {
        if (motion.isGlobalCameraMotion || motion.blobs.isEmpty()) return null
        val scales = doubleArrayOf(1.0, 0.82, 1.22, 0.68, 1.42)
        var best: HybridMatch? = null

        for (blob in motion.blobs) {
            val padX = max(sig.width, blob.width / 2)
            val padY = max(sig.height, blob.height / 2)
            val bl = (blob.left - padX).coerceIn(regionLeft, regionRight)
            val bt = (blob.top - padY).coerceIn(regionTop, regionBottom)
            val br = (blob.right + padX).coerceIn(bl, regionRight)
            val bb = (blob.bottom + padY).coerceIn(bt, regionBottom)

            val hit = findHybridInBounds(
                frame = frame,
                sig = sig,
                threshold = threshold,
                rLeft = bl,
                rTop = bt,
                rRight = br,
                rBottom = bb,
                scales = scales,
                motion = motion,
                livePatch = livePatch,
                matchMirrorX = matchMirrorX,
                prioritizeMotion = prioritizeMotion,
                requireMovingBlob = prioritizeMotion,
                onBestScore = onBestScore
            )
            if (hit != null && (best == null || hit.match.score > best.match.score)) {
                best = hit
            }
        }
        return best
    }

    /**
     * Verifies a grayscale [TemplateMatcher] hit against the template's foreground color signature
     * so wrong-colored objects with identical brightness (e.g. green grass vs red Dragon) are rejected.
     */
    fun verifyTemplateMatchColor(
        frame: Frame,
        match: MatchResult,
        sig: ColorSignature
    ): Boolean {
        if (!sig.hasChromaticSignature) return true
        val colorScore = scoreColorWindow(frame, sig, match.x, match.y, match.width, match.height)
        return colorScore >= 0.34
    }

    private fun combineHybridScore(
        sig: ColorSignature,
        colorScore: Double,
        structScore: Double,
        motionRatio: Double,
        hasMotionField: Boolean,
        prioritizeMotion: Boolean
    ): Double {
        val motionBoost = if (hasMotionField && motionRatio >= 0.05) {
            (motionRatio * 0.42).coerceIn(0.0, 0.14)
        } else {
            0.0
        }
        val staticPenalty = if (prioritizeMotion && hasMotionField && motionRatio < 0.03 && structScore < 0.78) {
            0.14
        } else {
            0.0
        }

        val raw = if (sig.hasChromaticSignature) {
            // Color-dominant hybrid (invariant to 8-direction COC troop turning, wing flapping & zoom)
            // Hard floor: candidate must genuinely contain the troop's foreground colors
            if (colorScore < 0.34) return 0.0
            0.74 * colorScore + 0.26 * structScore + motionBoost - staticPenalty
        } else {
            // Achromatic / grayscale objects rely primarily on foreground-masked structure + motion
            0.22 * colorScore + 0.78 * structScore + motionBoost - staticPenalty
        }
        return raw.coerceIn(0.0, 0.99)
    }

    private fun refineCentroid(
        bp: FloatArray,
        gw: Int,
        gh: Int,
        rLeft: Int,
        rTop: Int,
        stride: Int,
        cx: Int,
        cy: Int,
        winW: Int,
        winH: Int
    ): Pair<Int, Int> {
        val halfGx = max(1, (winW / 2) / stride)
        val halfGy = max(1, (winH / 2) / stride)
        val cgx = ((cx - rLeft) / stride).coerceIn(0, gw - 1)
        val cgy = ((cy - rTop) / stride).coerceIn(0, gh - 1)
        val x0 = (cgx - halfGx).coerceAtLeast(0)
        val x1 = (cgx + halfGx).coerceAtMost(gw - 1)
        val y0 = (cgy - halfGy).coerceAtLeast(0)
        val y1 = (cgy + halfGy).coerceAtMost(gh - 1)

        var sumW = 0f
        var sumX = 0f
        var sumY = 0f
        for (gy in y0..y1) {
            val row = gy * gw
            for (gx in x0..x1) {
                val w = bp[row + gx]
                if (w > 0.15f) {
                    sumW += w
                    sumX += gx * w
                    sumY += gy * w
                }
            }
        }
        if (sumW <= 1e-4f) return cx to cy
        val ncx = rLeft + ((sumX / sumW) * stride).roundToInt()
        val ncy = rTop + ((sumY / sumW) * stride).roundToInt()
        return ncx to ncy
    }
}
