package com.example.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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

    fun crop(left: Int, top: Int, w: Int, h: Int): GrayImage {
        val out = FloatArray(w * h)
        for (row in 0 until h) System.arraycopy(data, (top + row) * width + left, out, row * w, w)
        return GrayImage(w, h, out)
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

/** Template prepared once (gray + power-of-2 pyramid levels) and cached across searches. */
class PreparedTemplate(val name: String, frame: Frame) {
    val width = frame.width
    val height = frame.height
    val gray: GrayImage = GrayImage.fromFrame(frame)

    /**
     * Strictly power-of-2 coarse downsample factor (1, 2, 4, or 8) so every pyramid step
     * halves the factor cleanly (8 -> 4 -> 2 -> 1) with zero integer-division coordinate drift.
     */
    val coarseFactor: Int = when {
        min(width, height) >= 120 -> 8
        min(width, height) >= 56 -> 4
        min(width, height) >= 24 -> 2
        else -> 1
    }

    private val levels = HashMap<Int, TemplateLevel>()

    @Synchronized
    fun level(factor: Int): TemplateLevel = levels.getOrPut(factor) { TemplateLevel(gray.downsample(factor)) }
}

/** Zero-mean template data + its energy, ready for NCC. */
class TemplateLevel(val img: GrayImage) {
    val n = img.width * img.height
    val mean: Float
    val zeroMean: FloatArray
    val energy: Double // sum((t - mean)^2)

    init {
        var s = 0.0
        for (v in img.data) s += v
        mean = (s / n).toFloat()
        zeroMean = FloatArray(n) { img.data[it] - mean }
        var e = 0.0
        for (v in zeroMean) e += v.toDouble() * v
        energy = e
    }
}

/**
 * Coarse-to-fine normalized cross-correlation (NCC) template matcher.
 *
 * 1. Exhaustive NCC search on power-of-2 downsampled image using integral images for fast
 *    window variance + numerically stable zero-mean cross-correlation.
 * 2. Exact 2x pyramid refinement (8 -> 4 -> 2 -> 1) with generous ±3px search radius to absorb
 *    any sub-block phase offset.
 * 3. Full-resolution NCC verification; returns matches above threshold and best observed score.
 */
object TemplateMatcher {
    private const val FLAT_EPS = 1e-2
    private const val MAX_CANDIDATES = 16

    fun find(
        frame: Frame,
        template: PreparedTemplate,
        threshold: Double,
        regionLeft: Int = 0,
        regionTop: Int = 0,
        regionRight: Int = frame.width,
        regionBottom: Int = frame.height
    ): MatchResult? = findAll(frame, template, threshold, 1, regionLeft, regionTop, regionRight, regionBottom).firstOrNull()

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

        val fullGray = GrayImage.fromFrame(frame, left, top, rw, rh)
        val factor = template.coarseFactor
        val coarseImg = fullGray.downsample(factor)
        val coarseTpl = template.level(factor)
        if (coarseImg.width < coarseTpl.img.width || coarseImg.height < coarseTpl.img.height) {
            return SearchOutcome(null, 0.0)
        }

        val coarseMin = (threshold - 0.35).coerceAtLeast(0.15)
        val candidates = coarseSearch(coarseImg, coarseTpl, coarseMin, MAX_CANDIDATES)
        var bestScore = 0.0
        var bestMatch: MatchResult? = null

        for (c in candidates) {
            val refined = refineCandidate(fullGray, template, factor, c.first, c.second)
            if (refined.third > bestScore) {
                bestScore = refined.third
                if (refined.third >= threshold) {
                    bestMatch = MatchResult(refined.first + left, refined.second + top, template.width, template.height, refined.third)
                }
            }
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

        val fullGray = GrayImage.fromFrame(frame, left, top, rw, rh)
        val factor = template.coarseFactor
        val coarseImg = fullGray.downsample(factor)
        val coarseTpl = template.level(factor)
        if (coarseImg.width < coarseTpl.img.width || coarseImg.height < coarseTpl.img.height) return emptyList()

        val coarseMin = (threshold - 0.35).coerceAtLeast(0.15)
        val wanted = max(MAX_CANDIDATES, maxResults * 3)
        val candidates = coarseSearch(coarseImg, coarseTpl, coarseMin, wanted)

        val results = ArrayList<MatchResult>()
        for (c in candidates) {
            val (fx, fy, score) = refineCandidate(fullGray, template, factor, c.first, c.second)
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
            val refined = refineAt(fullGray, template, next, px, py, radius = 3)
            cx = refined.first
            cy = refined.second
            f = next
        }
        return refineFull(fullGray, template, cx, cy, radius = 3)
    }

    /** Exhaustive NCC over all coarse positions; returns top candidate positions with NMS. */
    private fun coarseSearch(img: GrayImage, tpl: TemplateLevel, minScore: Double, maxCount: Int): List<Pair<Int, Int>> {
        val tw = tpl.img.width
        val th = tpl.img.height
        val ow = img.width - tw + 1
        val oh = img.height - th + 1
        val scores = scoreMap(img, tpl, 0, 0, ow, oh)

        val found = ArrayList<Triple<Int, Int, Double>>()
        for (y in 0 until oh) {
            for (x in 0 until ow) {
                val s = scores[y * ow + x]
                if (s < minScore) continue
                var isMax = true
                loop@ for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until ow && ny in 0 until oh && scores[ny * ow + nx] > s) {
                        isMax = false; break@loop
                    }
                }
                if (isMax) found += Triple(x, y, s.toDouble())
            }
        }
        found.sortByDescending { it.third }
        val picked = ArrayList<Pair<Int, Int>>()
        val nmsX = max(2, tw / 2)
        val nmsY = max(2, th / 2)
        for ((x, y, _) in found) {
            if (picked.none { abs(it.first - x) < nmsX && abs(it.second - y) < nmsY }) {
                picked += x to y
                if (picked.size >= maxCount) break
            }
        }
        return picked
    }

    /**
     * Numerically stable NCC score map for top-left positions in [x0, x0+ow) × [y0, y0+oh).
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
        for (y in 0 until ah) {
            var rowS = 0.0
            var rowQ = 0.0
            val base = (y0 + y) * iw + x0
            for (x in 0 until aw) {
                val v = img.data[base + x].toDouble()
                rowS += v
                rowQ += v * v
                val i = (y + 1) * stride + (x + 1)
                sum[i] = sum[i - stride] + rowS
                sq[i] = sq[i - stride] + rowQ
            }
        }
        val n = tpl.n.toDouble()
        val tEnergy = tpl.energy
        val tFlat = tEnergy / n < FLAT_EPS
        val zm = tpl.zeroMean
        val data = img.data
        val out = FloatArray(ow * oh)
        for (y in 0 until oh) {
            for (x in 0 until ow) {
                val a = y * stride + x
                val b = a + tw
                val c = a + th * stride
                val d = c + tw
                val ws = sum[d] - sum[b] - sum[c] + sum[a]
                val wq = sq[d] - sq[b] - sq[c] + sq[a]
                val wMean = ws / n
                val wVar = (wq - ws * wMean).coerceAtLeast(0.0)
                val score: Double
                if (tFlat || wVar / n < FLAT_EPS) {
                    score = if (tFlat && wVar / n < 4.0) 1.0 - abs(wMean - tpl.mean) / 255.0 else 0.0
                } else {
                    val wMeanF = wMean.toFloat()
                    var cross = 0.0
                    var ti = 0
                    for (ty in 0 until th) {
                        var ii = (y0 + y + ty) * iw + (x0 + x)
                        for (tx in 0 until tw) {
                            cross += (data[ii++] - wMeanF) * zm[ti++]
                        }
                    }
                    score = (cross / sqrt(wVar * tEnergy)).coerceIn(-1.0, 1.0)
                }
                out[y * ow + x] = score.toFloat()
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
        return Triple(x0 + best % ow, y0 + best / ow, scores[best].toDouble().coerceIn(0.0, 1.0))
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
