package com.example.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Immutable ARGB pixel buffer (row-major). Pure Kotlin so it can be unit-tested on the JVM. */
class Frame(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size >= width * height) { "Invalid frame ${width}x$height" }
    }

    fun argbAt(x: Int, y: Int): Int = pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

    fun crop(left: Int, top: Int, w: Int, h: Int): Frame {
        val l = left.coerceIn(0, width - 1)
        val t = top.coerceIn(0, height - 1)
        val cw = w.coerceIn(1, width - l)
        val ch = h.coerceIn(1, height - t)
        val out = IntArray(cw * ch)
        for (row in 0 until ch) {
            System.arraycopy(pixels, (t + row) * width + l, out, row * cw, cw)
        }
        return Frame(cw, ch, out)
    }
}

/** Single-channel float image. */
class GrayImage(val width: Int, val height: Int, val data: FloatArray) {
    operator fun get(x: Int, y: Int): Float = data[y * width + x]

    companion object {
        fun fromFrame(frame: Frame, left: Int = 0, top: Int = 0, w: Int = frame.width, h: Int = frame.height): GrayImage {
            val out = FloatArray(w * h)
            val src = frame.pixels
            val fw = frame.width
            for (y in 0 until h) {
                var si = (top + y) * fw + left
                var di = y * w
                for (x in 0 until w) {
                    val p = src[si++]
                    // ITU-R BT.601 luma
                    out[di++] = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000f
                }
            }
            return GrayImage(w, h, out)
        }

        /**
         * Directly converts & box-downsamples a region of [frame] by power-of-2 [factor] in a single pass,
         * avoiding a full-resolution ~10 MB FloatArray allocation when scanning for an absent template.
         */
        fun fromFrameDownsampled(
            frame: Frame,
            left: Int,
            top: Int,
            w: Int,
            h: Int,
            factor: Int
        ): GrayImage {
            if (factor <= 1) return fromFrame(frame, left, top, w, h)
            val nw = max(1, w / factor)
            val nh = max(1, h / factor)
            val out = FloatArray(nw * nh)
            val src = frame.pixels
            val fw = frame.width
            val inv = 1f / (1000f * factor * factor)
            for (y in 0 until nh) {
                val baseY = top + y * factor
                val rowOut = y * nw
                for (x in 0 until nw) {
                    val baseX = left + x * factor
                    var sum = 0
                    for (dy in 0 until factor) {
                        var si = (baseY + dy) * fw + baseX
                        for (dx in 0 until factor) {
                            val p = src[si++]
                            sum += ((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114
                        }
                    }
                    out[rowOut + x] = sum * inv
                }
            }
            return GrayImage(nw, nh, out)
        }
    }

    /** Box-filter downsample by power-of-2 integer [factor]. */
    fun downsample(factor: Int): GrayImage {
        if (factor <= 1) return this
        val nw = max(1, width / factor)
        val nh = max(1, height / factor)
        val out = FloatArray(nw * nh)
        val inv = 1f / (factor * factor)
        for (y in 0 until nh) {
            val sy = y * factor
            for (x in 0 until nw) {
                val sx = x * factor
                var s = 0f
                for (dy in 0 until factor) {
                    var idx = (sy + dy) * width + sx
                    for (dx in 0 until factor) s += data[idx++]
                }
                out[y * nw + x] = s * inv
            }
        }
        return GrayImage(nw, nh, out)
    }

    /** Center-aligned bilinear resize for cross-resolution template auto-scaling. */
    fun resizeBilinear(newWidth: Int, newHeight: Int): GrayImage {
        val nw = newWidth.coerceAtLeast(1)
        val nh = newHeight.coerceAtLeast(1)
        if (nw == width && nh == height) return this
        val out = FloatArray(nw * nh)
        val xRatio = width.toFloat() / nw.toFloat()
        val yRatio = height.toFloat() / nh.toFloat()
        val maxSrcX = (width - 1).toFloat()
        val maxSrcY = (height - 1).toFloat()
        for (y in 0 until nh) {
            val srcY = ((y + 0.5f) * yRatio - 0.5f).coerceIn(0f, maxSrcY)
            val y0 = srcY.toInt()
            val y1 = min(y0 + 1, height - 1)
            val wy = srcY - y0
            val row0 = y0 * width
            val row1 = y1 * width
            val dstRow = y * nw
            for (x in 0 until nw) {
                val srcX = ((x + 0.5f) * xRatio - 0.5f).coerceIn(0f, maxSrcX)
                val x0 = srcX.toInt()
                val x1 = min(x0 + 1, width - 1)
                val wx = srcX - x0
                val v00 = data[row0 + x0]
                val v10 = data[row0 + x1]
                val v01 = data[row1 + x0]
                val v11 = data[row1 + x1]
                val top = v00 + (v10 - v00) * wx
                val bot = v01 + (v11 - v01) * wx
                out[dstRow + x] = top + (bot - top) * wy
            }
        }
        return GrayImage(nw, nh, out)
    }

    fun crop(left: Int, top: Int, w: Int, h: Int): GrayImage {
        val out = FloatArray(w * h)
        for (row in 0 until h) System.arraycopy(data, (top + row) * width + left, out, row * w, w)
        return GrayImage(w, h, out)
    }

    /** Horizontally mirrored copy (left <-> right) for matching moving characters that turn around. */
    fun flipHorizontal(): GrayImage {
        val out = FloatArray(width * height)
        for (y in 0 until height) {
            val rowOff = y * width
            for (x in 0 until width) {
                out[rowOff + x] = data[rowOff + (width - 1 - x)]
            }
        }
        return GrayImage(width, height, out)
    }
}

data class MatchResult(
    /** Top-left corner of the match in screen pixels. */
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** Normalized cross-correlation similarity, 0..1. */
    val score: Double
) {
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2
}

data class SearchOutcome(
    val match: MatchResult?,
    val bestScore: Double
)

data class MultiScaleOutcome(
    val match: MatchResult?,
    val bestScore: Double,
    val bestScale: Double
)

/** Template prepared once (gray + power-of-2 pyramid levels) and cached across searches. */
class PreparedTemplate private constructor(val name: String, val gray: GrayImage) {
    constructor(name: String, frame: Frame) : this(name, GrayImage.fromFrame(frame))

    val width = gray.width
    val height = gray.height

    /**
     * Strictly power-of-2 coarse downsample factor (1, 2, 4, or 8) chosen by both short side and
     * pixel area so coarse templates always keep >= 8px on the short side and >= 80 coarse pixels.
     */
    val coarseFactor: Int = run {
        val minDim = min(width, height)
        val area = width * height
        when {
            minDim >= 80 && area >= 8000 -> 8
            minDim >= 32 && area >= 1400 -> 4
            minDim >= 16 -> 2
            else -> 1
        }
    }

    /** More aggressive coarse factor used purely for fast multi-scale screening (always >= 4px short side, >= 40 coarse px). */
    internal val probeCoarseFactor: Int = run {
        val minDim = min(width, height)
        val area = width * height
        when {
            minDim >= 44 && area >= 2800 -> 8
            minDim >= 16 && area >= 550 -> 4
            minDim >= 10 -> 2
            else -> 1
        }
    }

    private val levels = HashMap<Int, TemplateLevel>()
    private val scaledVariants = HashMap<Long, PreparedTemplate>()
    private var mirroredVariant: PreparedTemplate? = null
    private var centerFocusedVariant: PreparedTemplate? = null

    @Synchronized
    fun level(factor: Int): TemplateLevel = levels.getOrPut(factor) { TemplateLevel(gray.downsample(factor)) }

    /** Returns a cached bilinear-resized copy of this template for cross-resolution matching. */
    @Synchronized
    fun scaled(scaleX: Double, scaleY: Double): PreparedTemplate {
        val nw = (width * scaleX).roundToInt().coerceAtLeast(4)
        val nh = (height * scaleY).roundToInt().coerceAtLeast(4)
        if (nw == width && nh == height) return this
        val key = (nw.toLong() shl 32) or (nh.toLong() and 0xFFFF_FFFFL)
        return scaledVariants.getOrPut(key) {
            PreparedTemplate("$name@${nw}x$nh", gray.resizeBilinear(nw, nh))
        }
    }

    /** Returns a cached horizontally mirrored (left <-> right) template for moving characters. */
    @Synchronized
    fun mirroredX(): PreparedTemplate {
        mirroredVariant?.let { return it }
        val built = PreparedTemplate("$name#mirrorX", gray.flipHorizontal())
        mirroredVariant = built
        return built
    }

    /**
     * Returns a cached central core crop (~60% inner width/height, trimming 20% off each edge)
     * that discards outer background padding while keeping the exact center alignment.
     * Significantly improves matching when a moving object crosses changing backgrounds.
     */
    @Synchronized
    fun centerFocused(): PreparedTemplate {
        centerFocusedVariant?.let { return it }
        if (width < 20 || height < 20) return this
        val mx = (width * 0.20f).roundToInt().coerceAtMost((width - 12) / 2).coerceAtLeast(0)
        val my = (height * 0.20f).roundToInt().coerceAtMost((height - 12) / 2).coerceAtLeast(0)
        if (mx == 0 && my == 0) return this
        val cw = width - mx * 2
        val ch = height - my * 2
        val built = PreparedTemplate("$name#core", gray.crop(mx, my, cw, ch))
        centerFocusedVariant = built
        return built
    }
}

/** Zero-mean template data + its energy, ready for NCC. */
class TemplateLevel(val img: GrayImage) {
    val n = img.width * img.height
    val mean: Float
    val zeroMean: FloatArray
    val zmSum: Double
    val energy: Double // sum((t - mean)^2)

    init {
        var s = 0.0
        for (v in img.data) s += v
        mean = (s / n).toFloat()
        zeroMean = FloatArray(n) { img.data[it] - mean }
        var zs = 0.0
        var e = 0.0
        for (v in zeroMean) {
            val d = v.toDouble()
            zs += d
            e += d * d
        }
        zmSum = zs
        energy = e
    }
}

/**
 * Coarse-to-fine normalized cross-correlation (NCC) template matcher.
 *
 * 1. Direct single-pass downsampled coarse NCC search (skips allocating a full-screen grayscale buffer
 *    when the template is not present on screen).
 * 2. Exact 2x pyramid refinement (8 -> 4 -> 2 -> 1) on a tightly bounded local patch around each
 *    candidate, with power-of-2 phase alignment and tight NMS so adjacent icons never mask each other.
 * 3. Full-resolution NCC verification with high-confidence early-exit in [findWithStats].
 * 4. Shared-pyramid single-pass multi-scale matching in [findMultiScale] with sub-millisecond
 *    local-patch fine scale refinement (±1.5%..±4%) so templates score 90%+ across different screen
 *    resolutions, DPIs, or gallery screenshots in tens of milliseconds.
 */
object TemplateMatcher {
    private const val FLAT_EPS = 1e-2
    private const val MAX_CANDIDATES = 16
    private const val MULTI_SCALE_MAX_CANDIDATES = 6
    private const val EARLY_EXIT_SCORE = 0.92
    private const val COARSE_REFINE_RADIUS = 3
    private const val FULL_REFINE_RADIUS = 2

    /** Dense logarithmic scale grid (~7-8% spacing) covering 0.42x..1.85x across phones, tablets, foldables & emulators. */
    private val AUTO_SCALE_GRID = doubleArrayOf(
        1.00, 0.93, 1.08, 0.87, 0.80, 0.73, 0.67, 0.62, 0.56, 0.50, 0.44, 1.16, 1.25, 1.35, 1.50, 1.68, 1.85
    )

    private data class ScaleProbe(
        val scale: Double,
        val template: PreparedTemplate,
        val factor: Int,
        val bestCoarse: Double,
        val candidates: List<Triple<Int, Int, Double>>
    )

    fun find(
        frame: Frame,
        template: PreparedTemplate,
        threshold: Double,
        regionLeft: Int = 0,
        regionTop: Int = 0,
        regionRight: Int = frame.width,
        regionBottom: Int = frame.height
    ): MatchResult? = findWithStats(frame, template, threshold, regionLeft, regionTop, regionRight, regionBottom).match

    fun findWithStats(
        frame: Frame,
        template: PreparedTemplate,
        threshold: Double,
        regionLeft: Int = 0,
        regionTop: Int = 0,
        regionRight: Int = frame.width,
        regionBottom: Int = frame.height
    ): SearchOutcome {
        val left = regionLeft.coerceIn(0, frame.width)
        val top = regionTop.coerceIn(0, frame.height)
        val right = regionRight.coerceIn(left, frame.width)
        val bottom = regionBottom.coerceIn(top, frame.height)
        val rw = right - left
        val rh = bottom - top
        if (rw < template.width || rh < template.height) return SearchOutcome(null, 0.0)

        val factor = template.coarseFactor
        val coarseImg = GrayImage.fromFrameDownsampled(frame, left, top, rw, rh, factor)
        return findWithPrecomputedCoarse(frame, left, top, rw, rh, template, factor, coarseImg, threshold)
    }

    /**
     * Fast shared-pyramid multi-scale search across 0.50x..1.50x with local-patch fine scale refinement.
     * Reuses downsampled coarse screen buffers across all probe scales and refines probe peaks directly
     * without recomputing coarse maps, achieving 90%+ accuracy in tens of milliseconds.
     */
    fun findMultiScale(
        frame: Frame,
        template: PreparedTemplate,
        threshold: Double,
        regionLeft: Int = 0,
        regionTop: Int = 0,
        regionRight: Int = frame.width,
        regionBottom: Int = frame.height,
        skipScales: List<Double> = emptyList()
    ): MultiScaleOutcome {
        val left = regionLeft.coerceIn(0, frame.width)
        val top = regionTop.coerceIn(0, frame.height)
        val right = regionRight.coerceIn(left, frame.width)
        val bottom = regionBottom.coerceIn(top, frame.height)
        val rw = right - left
        val rh = bottom - top
        if (rw < 8 || rh < 8) return MultiScaleOutcome(null, 0.0, 1.0)

        val coarseCache = HashMap<Int, GrayImage>(4)
        fun coarseScreen(factor: Int): GrayImage = coarseCache.getOrPut(factor) {
            when {
                factor == 8 && coarseCache.containsKey(4) -> coarseCache[4]!!.downsample(2)
                factor == 4 && coarseCache.containsKey(2) -> coarseCache[2]!!.downsample(2)
                else -> GrayImage.fromFrameDownsampled(frame, left, top, rw, rh, factor)
            }
        }

        val probeCoarseMin = (threshold - 0.48).coerceIn(0.18, 0.42)
        val earlyExitTarget = max(threshold, EARLY_EXIT_SCORE)
        val probes = ArrayList<ScaleProbe>(AUTO_SCALE_GRID.size)

        var bestScore = 0.0
        var bestScale = 1.0
        var bestMatch: MatchResult? = null
        var bestCenterX = -1
        var bestCenterY = -1

        fun recordCandidate(cand: PreparedTemplate, scale: Double, relX: Int, relY: Int, score: Double) {
            if (score > bestScore) {
                bestScore = score
                bestScale = scale
                bestCenterX = left + relX + cand.width / 2
                bestCenterY = top + relY + cand.height / 2
                if (score >= threshold) {
                    bestMatch = MatchResult(left + relX, top + relY, cand.width, cand.height, score)
                }
            }
        }

        // Phase 1: Single-pass coarse screening across scales (preserving top candidates to avoid re-scanning)
        for (s in AUTO_SCALE_GRID) {
            if (skipScales.any { abs(it - s) < 0.015 }) continue
            val cand = template.scaled(s, s)
            if (cand.width < 8 || cand.height < 8 || cand.width > rw || cand.height > rh) continue
            val pf = cand.probeCoarseFactor
            val cImg = coarseScreen(pf)
            val cTpl = cand.level(pf)
            if (cImg.width < cTpl.img.width || cImg.height < cTpl.img.height) continue
            val peaks = coarseSearch(cImg, cTpl, probeCoarseMin, MULTI_SCALE_MAX_CANDIDATES)
            if (peaks.isEmpty()) continue
            val topCoarse = peaks.first().third
            if (topCoarse < probeCoarseMin * 0.75) continue

            // Immediate fast-path verification if coarse peak is already overwhelmingly strong
            if (topCoarse >= 0.88) {
                val p0 = peaks.first()
                val refined = if (pf <= 1) {
                    refineFull(cImg, cand, p0.first, p0.second, radius = FULL_REFINE_RADIUS)
                } else {
                    refineCandidateFromFrame(frame, left, top, rw, rh, cand, pf, p0.first, p0.second)
                }
                recordCandidate(cand, s, refined.first, refined.second, refined.third)
                if (bestMatch != null && bestScore >= earlyExitTarget) {
                    return refineScaleLocally(
                        frame, template, left, top, right, bottom, threshold,
                        bestScale, bestCenterX, bestCenterY, bestScore, bestMatch
                    )
                }
            }
            probes += ScaleProbe(s, cand, pf, topCoarse, peaks)
        }

        if (probes.isEmpty()) {
            return MultiScaleOutcome(bestMatch, bestScore, bestScale)
        }
        probes.sortByDescending { it.bestCoarse }

        // Phase 2: Verify top 3 coarse-ranked scales directly from their saved coarse peaks
        val topCount = min(3, probes.size)
        for (i in 0 until topCount) {
            val probe = probes[i]
            val cImg = coarseScreen(probe.factor)
            val topCoarse = probe.bestCoarse
            for ((idx, c) in probe.candidates.withIndex()) {
                if (idx >= 3 && c.third < topCoarse - 0.28) break
                val refined = if (probe.factor <= 1) {
                    refineFull(cImg, probe.template, c.first, c.second, radius = FULL_REFINE_RADIUS)
                } else {
                    refineCandidateFromFrame(frame, left, top, rw, rh, probe.template, probe.factor, c.first, c.second)
                }
                recordCandidate(probe.template, probe.scale, refined.first, refined.second, refined.third)
                if (bestMatch != null && bestScore >= earlyExitTarget) break
            }
            if (bestMatch != null && bestScore >= earlyExitTarget) break
        }

        // Phase 3: Ultra-fast local-patch fine scale refinement (±1.5%..±4% around best discovered center)
        if (bestCenterX >= 0 && bestScore >= max(0.32, threshold - 0.35) &&
            (bestMatch == null || bestScore < max(threshold + 0.08, 0.94))
        ) {
            return refineScaleLocally(
                frame, template, left, top, right, bottom, threshold,
                bestScale, bestCenterX, bestCenterY, bestScore, bestMatch
            )
        }
        return MultiScaleOutcome(bestMatch, bestScore, bestScale)
    }

    /**
     * Evaluates fine scale perturbations around [anchorScale] ONLY inside a small local patch around
     * ([centerX], [centerY]), narrowing worst-case scale error to <= 1% in ~1-2ms.
     */
    private fun refineScaleLocally(
        frame: Frame,
        template: PreparedTemplate,
        regionLeft: Int,
        regionTop: Int,
        regionRight: Int,
        regionBottom: Int,
        threshold: Double,
        anchorScale: Double,
        centerX: Int,
        centerY: Int,
        initialScore: Double,
        initialMatch: MatchResult?
    ): MultiScaleOutcome {
        var bestScore = initialScore
        var bestScale = anchorScale
        var bestMatch = initialMatch
        var bestCx = centerX
        var bestCy = centerY
        if (bestScore >= 0.97 && bestMatch != null) {
            return MultiScaleOutcome(bestMatch, bestScore, bestScale)
        }

        val maxScale = (anchorScale * 1.05).coerceAtMost(2.05)
        val maxW = (template.width * maxScale).roundToInt().coerceAtLeast(8)
        val maxH = (template.height * maxScale).roundToInt().coerceAtLeast(8)
        val pad = 14
        val patchLeft = (centerX - maxW / 2 - pad).coerceIn(regionLeft, max(regionLeft, regionRight - 1))
        val patchTop = (centerY - maxH / 2 - pad).coerceIn(regionTop, max(regionTop, regionBottom - 1))
        val patchRight = (centerX + (maxW + 1) / 2 + pad).coerceIn(patchLeft + 1, regionRight)
        val patchBottom = (centerY + (maxH + 1) / 2 + pad).coerceIn(patchTop + 1, regionBottom)
        val patchW = patchRight - patchLeft
        val patchH = patchBottom - patchTop
        if (patchW < 8 || patchH < 8) return MultiScaleOutcome(bestMatch, bestScore, bestScale)

        val patchGray = GrayImage.fromFrame(frame, patchLeft, patchTop, patchW, patchH)
        val tried = ArrayList<Double>(6)
        tried += anchorScale

        fun probeFine(s: Double): Boolean {
            if (s < 0.38 || s > 2.05) return false
            if (tried.any { abs(it - s) < 0.008 }) return false
            tried += s
            val cand = template.scaled(s, s)
            if (cand.width < 8 || cand.height < 8 || cand.width > patchW || cand.height > patchH) return false
            val estX = (bestCx - cand.width / 2 - patchLeft).coerceIn(0, patchW - cand.width)
            val estY = (bestCy - cand.height / 2 - patchTop).coerceIn(0, patchH - cand.height)
            // Use quick 2x pre-centering inside the tiny patch if template is large, then exact full-res ±3px
            val refined = if (min(cand.width, cand.height) >= 48 && patchW >= cand.width + 8 && patchH >= cand.height + 8) {
                val c2 = refineAt(patchGray, cand, 2, estX / 2, estY / 2, radius = 4)
                refineFull(patchGray, cand, c2.first * 2, c2.second * 2, radius = 2)
            } else {
                refineFull(patchGray, cand, estX, estY, radius = 6)
            }
            if (refined.third > bestScore) {
                bestScore = refined.third
                bestScale = s
                bestCx = patchLeft + refined.first + cand.width / 2
                bestCy = patchTop + refined.second + cand.height / 2
                if (refined.third >= threshold) {
                    bestMatch = MatchResult(
                        patchLeft + refined.first,
                        patchTop + refined.second,
                        cand.width,
                        cand.height,
                        refined.third
                    )
                }
                return true
            }
            return false
        }

        // First bracket ±3%, then narrow by ±1.5% toward the winning direction
        val downImproved = probeFine(anchorScale * 0.97)
        val upImproved = probeFine(anchorScale * 1.03)
        if (bestScore < 0.96) {
            when {
                downImproved && !upImproved -> {
                    probeFine(anchorScale * 0.95)
                    probeFine(anchorScale * 0.985)
                }
                upImproved -> {
                    probeFine(anchorScale * 1.05)
                    probeFine(anchorScale * 1.015)
                }
                else -> {
                    probeFine(anchorScale * 0.985)
                    probeFine(anchorScale * 1.015)
                }
            }
        }
        return MultiScaleOutcome(bestMatch, bestScore, bestScale)
    }

    private fun findWithPrecomputedCoarse(
        frame: Frame,
        left: Int,
        top: Int,
        rw: Int,
        rh: Int,
        template: PreparedTemplate,
        factor: Int,
        coarseImg: GrayImage,
        threshold: Double
    ): SearchOutcome {
        val coarseTpl = template.level(factor)
        if (coarseImg.width < coarseTpl.img.width || coarseImg.height < coarseTpl.img.height) {
            return SearchOutcome(null, 0.0)
        }

        val coarseMin = (threshold - 0.45).coerceIn(0.15, 0.48)
        val candidates = coarseSearch(coarseImg, coarseTpl, coarseMin, MAX_CANDIDATES)
        if (candidates.isEmpty()) return SearchOutcome(null, 0.0)

        var bestScore = 0.0
        var bestMatch: MatchResult? = null
        val earlyExitTarget = max(threshold, EARLY_EXIT_SCORE)
        val topCoarse = candidates.first().third

        for ((idx, c) in candidates.withIndex()) {
            if (idx >= 4 && c.third < topCoarse - 0.30) break
            val refined = if (factor <= 1) {
                refineFull(coarseImg, template, c.first, c.second, radius = FULL_REFINE_RADIUS)
            } else {
                refineCandidateFromFrame(frame, left, top, rw, rh, template, factor, c.first, c.second)
            }
            if (refined.third > bestScore) {
                bestScore = refined.third
                if (refined.third >= threshold) {
                    bestMatch = MatchResult(refined.first + left, refined.second + top, template.width, template.height, refined.third)
                }
            }
            if (bestMatch != null && (bestScore >= earlyExitTarget || (idx >= 2 && bestScore >= threshold + 0.05))) break
        }
        return SearchOutcome(bestMatch, bestScore)
    }

    fun findAll(
        frame: Frame,
        template: PreparedTemplate,
        threshold: Double,
        maxResults: Int = 10,
        regionLeft: Int = 0,
        regionTop: Int = 0,
        regionRight: Int = frame.width,
        regionBottom: Int = frame.height
    ): List<MatchResult> {
        val left = regionLeft.coerceIn(0, frame.width)
        val top = regionTop.coerceIn(0, frame.height)
        val right = regionRight.coerceIn(left, frame.width)
        val bottom = regionBottom.coerceIn(top, frame.height)
        val rw = right - left
        val rh = bottom - top
        if (rw < template.width || rh < template.height) return emptyList()

        val factor = template.coarseFactor
        val coarseImg = GrayImage.fromFrameDownsampled(frame, left, top, rw, rh, factor)
        val coarseTpl = template.level(factor)
        if (coarseImg.width < coarseTpl.img.width || coarseImg.height < coarseTpl.img.height) return emptyList()

        val coarseMin = (threshold - 0.45).coerceIn(0.15, 0.48)
        val wanted = max(MAX_CANDIDATES, maxResults * 3)
        val candidates = coarseSearch(coarseImg, coarseTpl, coarseMin, wanted)
        if (candidates.isEmpty()) return emptyList()

        val results = ArrayList<MatchResult>()
        for (c in candidates) {
            val (fx, fy, score) = if (factor <= 1) {
                refineFull(coarseImg, template, c.first, c.second, radius = FULL_REFINE_RADIUS)
            } else {
                refineCandidateFromFrame(frame, left, top, rw, rh, template, factor, c.first, c.second)
            }
            if (score >= threshold) {
                results += MatchResult(fx + left, fy + top, template.width, template.height, score)
            }
        }

        results.sortByDescending { it.score }
        val picked = ArrayList<MatchResult>()
        for (r in results) {
            val overlaps = picked.any { p ->
                abs(p.x - r.x) < max(4, template.width / 2) && abs(p.y - r.y) < max(4, template.height / 2)
            }
            if (!overlaps) picked += r
            if (picked.size >= maxResults) break
        }
        return picked
    }

    /**
     * Converts only the small local patch around a coarse candidate from [frame] into full-res grayscale
     * with exact power-of-2 phase alignment, avoiding a full-screen 10 MB FloatArray allocation.
     */
    private fun refineCandidateFromFrame(
        frame: Frame,
        regionLeft: Int,
        regionTop: Int,
        rw: Int,
        rh: Int,
        template: PreparedTemplate,
        startFactor: Int,
        startX: Int,
        startY: Int
    ): Triple<Int, Int, Double> {
        val pad = startFactor * (COARSE_REFINE_RADIUS + 1)
        val maxRelX0 = ((rw - template.width).coerceAtLeast(0) / startFactor) * startFactor
        val maxRelY0 = ((rh - template.height).coerceAtLeast(0) / startFactor) * startFactor
        val relX0 = (((startX * startFactor) - pad).coerceAtLeast(0) / startFactor * startFactor).coerceAtMost(maxRelX0)
        val relY0 = (((startY * startFactor) - pad).coerceAtLeast(0) / startFactor * startFactor).coerceAtMost(maxRelY0)
        val relX1 = max(relX0 + template.width, startX * startFactor + template.width + pad).coerceAtMost(rw)
        val relY1 = max(relY0 + template.height, startY * startFactor + template.height + pad).coerceAtMost(rh)

        val patchGray = GrayImage.fromFrame(
            frame = frame,
            left = regionLeft + relX0,
            top = regionTop + relY0,
            w = relX1 - relX0,
            h = relY1 - relY0
        )
        val localStartX = (startX - relX0 / startFactor).coerceAtLeast(0)
        val localStartY = (startY - relY0 / startFactor).coerceAtLeast(0)
        val refined = refineCandidate(patchGray, template, startFactor, localStartX, localStartY)
        return Triple(refined.first + relX0, refined.second + relY0, refined.third)
    }

    private fun refineCandidate(
        fullGray: GrayImage,
        template: PreparedTemplate,
        startFactor: Int,
        startX: Int,
        startY: Int
    ): Triple<Int, Int, Double> {
        var cx = startX
        var cy = startY
        var f = startFactor
        while (f > 1) {
            val next = f / 2 // Exact because startFactor is a power of 2
            val px = cx * 2
            val py = cy * 2
            val refined = refineAt(fullGray, template, next, px, py, radius = COARSE_REFINE_RADIUS)
            cx = refined.first
            cy = refined.second
            f = next
        }
        return refineFull(fullGray, template, cx, cy, radius = FULL_REFINE_RADIUS)
    }

    /** Exhaustive NCC over all coarse positions; returns top (x, y, coarseScore) peaks with tight NMS. */
    private fun coarseSearch(img: GrayImage, tpl: TemplateLevel, minScore: Double, maxCount: Int): List<Triple<Int, Int, Double>> {
        val tw = tpl.img.width
        val th = tpl.img.height
        val ow = img.width - tw + 1
        val oh = img.height - th + 1
        if (ow <= 0 || oh <= 0) return emptyList()
        val scores = scoreMap(img, tpl, 0, 0, ow, oh)

        val found = ArrayList<Triple<Int, Int, Double>>()
        val fallbackPeaks = ArrayList<Triple<Int, Int, Double>>()
        for (y in 0 until oh) {
            val rowOff = y * ow
            for (x in 0 until ow) {
                val s = scores[rowOff + x]
                if (s < 0.05f) continue
                var isMax = true
                loop@ for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= oh) continue
                    val nRow = ny * ow
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx in 0 until ow && scores[nRow + nx] > s) {
                            isMax = false; break@loop
                        }
                    }
                }
                if (isMax) {
                    val item = Triple(x, y, s.toDouble())
                    if (s >= minScore) found += item else fallbackPeaks += item
                }
            }
        }
        val pool = if (found.isNotEmpty()) {
            found.sortByDescending { it.third }
            found
        } else {
            fallbackPeaks.sortByDescending { it.third }
            fallbackPeaks.take(6)
        }
        val picked = ArrayList<Triple<Int, Int, Double>>()
        // Keep NMS radius tight so adjacent UI icons/troop cards never suppress each other
        val nmsX = max(2, min(tw / 3, 6))
        val nmsY = max(2, min(th / 3, 6))
        for (item in pool) {
            val x = item.first
            val y = item.second
            if (picked.none { abs(it.first - x) < nmsX && abs(it.second - y) < nmsY }) {
                picked += item
                if (picked.size >= maxCount) break
            }
        }
        return picked
    }

    /**
     * Numerically stable NCC score map for top-left positions in [x0, x0+ow) × [y0, y0+oh).
     * Exploits zero-mean template identity sum((I - wMean) * zm) = sum(I * zm) - wMean * zmSum
     * to eliminate per-pixel subtraction inside the inner loop.
     */
    internal fun scoreMap(img: GrayImage, tpl: TemplateLevel, x0: Int, y0: Int, ow: Int, oh: Int): FloatArray {
        val tw = tpl.img.width
        val th = tpl.img.height
        val iw = img.width
        val aw = ow + tw - 1
        val ah = oh + th - 1
        val sum = DoubleArray((aw + 1) * (ah + 1))
        val sq = DoubleArray((aw + 1) * (ah + 1))
        val stride = aw + 1
        val data = img.data
        for (y in 0 until ah) {
            var rowS = 0.0
            var rowQ = 0.0
            val base = (y0 + y) * iw + x0
            val prevRow = y * stride
            val curRow = prevRow + stride
            for (x in 0 until aw) {
                val v = data[base + x].toDouble()
                rowS += v
                rowQ += v * v
                val i = curRow + x + 1
                sum[i] = sum[prevRow + x + 1] + rowS
                sq[i] = sq[prevRow + x + 1] + rowQ
            }
        }
        val n = tpl.n.toDouble()
        val tEnergy = tpl.energy
        val tFlat = tEnergy / n < FLAT_EPS
        val zm = tpl.zeroMean
        val zmSum = tpl.zmSum
        val out = FloatArray(ow * oh)
        for (y in 0 until oh) {
            val rowOut = y * ow
            val aRow = y * stride
            val cRow = (y + th) * stride
            val imgBaseY = y0 + y
            for (x in 0 until ow) {
                val a = aRow + x
                val b = a + tw
                val c = cRow + x
                val d = c + tw
                val ws = sum[d] - sum[b] - sum[c] + sum[a]
                val wq = sq[d] - sq[b] - sq[c] + sq[a]
                val wMean = ws / n
                val wVar = (wq - ws * wMean).coerceAtLeast(0.0)
                val score: Double
                if (tFlat || wVar / n < FLAT_EPS) {
                    score = if (tFlat && wVar / n < 4.0) 1.0 - abs(wMean - tpl.mean) / 255.0 else 0.0
                } else {
                    var cross = 0.0
                    var ti = 0
                    val imgBaseX = x0 + x
                    for (ty in 0 until th) {
                        var ii = (imgBaseY + ty) * iw + imgBaseX
                        var rowCross = 0f
                        val end = ti + tw
                        while (ti < end) {
                            rowCross += data[ii++] * zm[ti++]
                        }
                        cross += rowCross
                    }
                    cross -= wMean * zmSum
                    score = (cross / sqrt(wVar * tEnergy)).coerceIn(-1.0, 1.0)
                }
                out[rowOut + x] = score.toFloat()
            }
        }
        return out
    }

    private fun refineAt(full: GrayImage, template: PreparedTemplate, factor: Int, px: Int, py: Int, radius: Int): Pair<Int, Int> {
        val tl = template.level(factor)
        val tw = tl.img.width
        val th = tl.img.height
        val levelW = full.width / factor
        val levelH = full.height / factor
        val x0 = (px - radius).coerceIn(0, max(0, levelW - tw))
        val y0 = (py - radius).coerceIn(0, max(0, levelH - th))
        val x1 = (px + radius).coerceIn(0, max(0, levelW - tw))
        val y1 = (py + radius).coerceIn(0, max(0, levelH - th))
        val areaW = x1 - x0 + tw
        val areaH = y1 - y0 + th
        val sub = full.crop(
            x0 * factor,
            y0 * factor,
            min(areaW * factor, full.width - x0 * factor),
            min(areaH * factor, full.height - y0 * factor)
        ).downsample(factor)
        val ow = sub.width - tw + 1
        val oh = sub.height - th + 1
        if (ow <= 0 || oh <= 0) return px.coerceIn(0, max(0, levelW - tw)) to py.coerceIn(0, max(0, levelH - th))
        val scores = scoreMap(sub, tl, 0, 0, ow, oh)
        var best = 0
        for (i in scores.indices) if (scores[i] > scores[best]) best = i
        return (x0 + best % ow) to (y0 + best / ow)
    }

    private fun refineFull(full: GrayImage, template: PreparedTemplate, px: Int, py: Int, radius: Int): Triple<Int, Int, Double> {
        val tl = template.level(1)
        val maxX = full.width - template.width
        val maxY = full.height - template.height
        val x0 = (px - radius).coerceIn(0, maxX)
        val y0 = (py - radius).coerceIn(0, maxY)
        val x1 = (px + radius).coerceIn(0, maxX)
        val y1 = (py + radius).coerceIn(0, maxY)
        val ow = x1 - x0 + 1
        val oh = y1 - y0 + 1
        val scores = scoreMap(full, tl, x0, y0, ow, oh)
        var best = 0
        for (i in scores.indices) if (scores[i] > scores[best]) best = i
        var bx = x0 + best % ow
        var by = y0 + best / ow
        var bScore = scores[best].toDouble().coerceIn(0.0, 1.0)
        // If peak hit the edge of the search window (and there's room outside), follow 1 step further
        if (radius <= 2 && ((bx == x0 && x0 > 0) || (bx == x1 && x1 < maxX) || (by == y0 && y0 > 0) || (by == y1 && y1 < maxY))) {
            val fx0 = (bx - 1).coerceIn(0, maxX)
            val fy0 = (by - 1).coerceIn(0, maxY)
            val fx1 = (bx + 1).coerceIn(0, maxX)
            val fy1 = (by + 1).coerceIn(0, maxY)
            val fow = fx1 - fx0 + 1
            val foh = fy1 - fy0 + 1
            val fScores = scoreMap(full, tl, fx0, fy0, fow, foh)
            var fb = 0
            for (i in fScores.indices) if (fScores[i] > fScores[fb]) fb = i
            val fs = fScores[fb].toDouble().coerceIn(0.0, 1.0)
            if (fs > bScore) {
                bx = fx0 + fb % fow
                by = fy0 + fb / fow
                bScore = fs
            }
        }
        return Triple(bx, by, bScore)
    }
}

/** Per-channel colour comparison helpers shared by macro runner and Lua API. */
object ColorUtil {
    fun toHex(argb: Int): String = String.format("#%02X%02X%02X", (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    fun parseHex(hex: String): Int? {
        val clean = hex.trim().removePrefix("#")
        if (clean.length != 6) return null
        return clean.toIntOrNull(16)?.let { it or (0xFF shl 24) }
    }

    fun matches(actualArgb: Int, targetHex: String, tolerance: Int): Boolean {
        val target = parseHex(targetHex) ?: return false
        val tol = tolerance.coerceIn(0, 255)
        return abs(((actualArgb shr 16) and 0xFF) - ((target shr 16) and 0xFF)) <= tol &&
            abs(((actualArgb shr 8) and 0xFF) - ((target shr 8) and 0xFF)) <= tol &&
            abs((actualArgb and 0xFF) - (target and 0xFF)) <= tol
    }
}
